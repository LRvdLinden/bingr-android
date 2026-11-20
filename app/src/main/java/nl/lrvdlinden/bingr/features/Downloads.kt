package nl.lrvdlinden.bingr

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Eén download (film of aflevering), met alles om hem offline te tonen en af te spelen. */
@Serializable
data class DownloadItem(
    /** id van de film of aflevering (= voortgangssleutel, zodat hervatten online en offline gelijk loopt) */
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val poster: String? = null,
    val type: MediaType,
    val seriesID: String? = null,
    val seriesTitle: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val imdbID: String? = null,
    /** Server (PlaylistSource.id) waar hij vandaan komt */
    val sourceID: String? = null,
    /** Kijkprofiel dat hem downloadde */
    val profileID: String? = null,
    val url: String,
    val fileName: String,
    val totalBytes: Long = -1,
    val bytes: Long = 0,
    /** queued, downloading, paused, done, failed */
    val status: String = Downloads.QUEUED,
    val error: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    /** build 171: HLS (.m3u8), via Media3 in de cache van [HlsDownloads] (geen los bestand) */
    val hls: Boolean = false,
) {
    val key: String get() = "${sourceID ?: "-"}|$id"
    val progress: Double get() = if (totalBytes > 0) (bytes.toDouble() / totalBytes).coerceIn(0.0, 1.0) else 0.0
    val isDone: Boolean get() = status == Downloads.DONE
}

/**
 * Offline downloads (build 52), alleen op telefoon/tablet. Directe videobestanden (Xtream, M3U, Plex/Jellyfin/Emby,
 * MAC-portal na het aanmaken van een link) worden op de achtergrond gedownload, met een voortgangsmelding (ook op het
 * vergrendelscherm). Hervat na netwerkverlies of een herstart (HTTP Range).
 *
 * Build 141: geen voorgronddienst van het type `dataSync` meer (en dus geen FOREGROUND_SERVICE_DATA_SYNC, waarvoor
 * Google Play een verklaring vraagt). Android 14+: een door de gebruiker gestarte gegevensoverdracht ("user-initiated
 * data transfer job", [DownloadJobService], zoals Google voor downloads aanraadt). Android 7–13: WorkManager met
 * setForeground zonder type (daar is geen type nodig). Kan de taak op Android 14+ niet als door de gebruiker gestart
 * worden ingepland (app niet in beeld), dan WorkManager zonder voorgronddienst; die gaat verder zodra het mag.
 * HLS/DASH-streams (.m3u8/.mpd) kunnen niet gedownload worden.
 * Gegevens: files/downloads.json; bestanden in de app-map "downloads" (externe app-opslag, anders intern).
 */
object Downloads {
    const val QUEUED = "queued"
    const val DOWNLOADING = "downloading"
    const val PAUSED = "paused"
    const val DONE = "done"
    const val FAILED = "failed"

    private const val WORK = "bingr-downloads"
    const val CHANNEL = "downloads"

    private lateinit var ctx: Context
    private val lock = Any()
    private var list = listOf<DownloadItem>()
    private val main = Handler(Looper.getMainLooper())

    /** Alle downloads (Compose-state, bijgewerkt op de main thread). */
    var items by mutableStateOf(listOf<DownloadItem>())
        private set

    /** Downloads bestaan alleen op telefoon en tablet. */
    val supported: Boolean get() = !AppState.isTV

    fun init(c: Context) {
        ctx = c.applicationContext
        if (!supported) return
        val loaded = runCatching {
            Http.json.decodeFromString(ListSerializer(DownloadItem.serializer()), metaFile.readText())
        }.getOrDefault(emptyList())
        // was bezig toen de app stopte → weer in de wachtrij
        synchronized(lock) { list = loaded.map { if (it.status == DOWNLOADING) it.copy(status = QUEUED) else it } }
        publish(save = false)
        verifyFiles()
        createChannel()
        // build 141: Android 14+ pas als de app in beeld is ([resumeIfQueued] vanuit MainActivity.onResume), want alleen dan
        // mag een door de gebruiker gestarte overdracht ingepland worden
        if (list.any { it.status == QUEUED } && Build.VERSION.SDK_INT < 34) schedule()
    }

    /** Build 141: app weer in beeld (MainActivity.onResume): wat nog in de wachtrij staat verder downloaden. */
    fun resumeIfQueued() {
        if (!::ctx.isInitialized || !supported) return
        if (synchronized(lock) { list.any { it.status == QUEUED } } && !DownloadRunner.running) schedule()
    }

    private val metaFile: File get() = File(ctx.filesDir, "downloads.json")
    private val dir: File
        get() = (ctx.getExternalFilesDir("downloads") ?: File(ctx.filesDir, "downloads")).apply { mkdirs() }

    fun file(d: DownloadItem): File = File(dir, d.fileName)

    private fun publish(save: Boolean = true) {
        val snapshot = synchronized(lock) { list }
        if (save) runCatching {
            val tmp = File(ctx.filesDir, "downloads.json.tmp")
            tmp.writeText(Http.json.encodeToString(ListSerializer(DownloadItem.serializer()), snapshot))
            if (!tmp.renameTo(metaFile)) { metaFile.delete(); tmp.renameTo(metaFile) }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) items = snapshot else main.post { items = synchronized(lock) { list } }
    }

    private fun mutate(change: (List<DownloadItem>) -> List<DownloadItem>) {
        synchronized(lock) { list = change(list) }
        publish()
    }

    /**
     * Build 179 (opruimen): titel [id] staat niet meer op server [sid]. Klaar zijnde downloads wijzen naar [to] (server, id)
     * of verliezen hun koppeling met de server; het bestand blijft en blijft afspeelbaar.
     */
    fun detach179(sid: String, id: String, to: Pair<String, String>?) {
        if (!::ctx.isInitialized || !supported) return
        if (synchronized(lock) { list.none { it.isDone && it.sourceID.equals(sid, true) && (it.id == id || it.seriesID == id) } }) return
        mutate { l ->
            l.map { d ->
                if (!d.isDone || !d.sourceID.equals(sid, true) || (d.id != id && d.seriesID != id)) d
                else if (to == null) d.copy(sourceID = null)
                else if (d.id == id) d.copy(sourceID = to.first, id = to.second)
                else d.copy(sourceID = to.first, seriesID = to.second)
            }
        }
    }

    internal fun update(key: String, change: (DownloadItem) -> DownloadItem) =
        mutate { l -> l.map { if (it.key == key) change(it) else it } }

    internal fun get(key: String): DownloadItem? = synchronized(lock) { list.firstOrNull { it.key == key } }
    internal fun nextQueued(): DownloadItem? = synchronized(lock) { list.filter { it.status == QUEUED }.minByOrNull { it.addedAt } }

    /** Downloads van het actieve profiel (oude zonder profiel horen bij iedereen), nieuwste eerst. */
    val visible: List<DownloadItem>
        get() {
            val p = Store.activeProfileID
            return items.filter { it.profileID == null || p == null || it.profileID == p }.sortedByDescending { it.addedAt }
        }

    fun find(sourceID: String?, id: String): DownloadItem? = items.firstOrNull { it.id == id && (sourceID == null || it.sourceID == null || it.sourceID == sourceID) }

    /** Ruimte die alle downloads innemen (bytes). */
    fun storageUsed(): Long = runCatching { dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)

    /** Build 171: "Alleen via wifi" (standaard aan, per apparaat), zoals "Mobiele data" uit op Apple. */
    var wifiOnly: Boolean
        get() = Prefs.bool("downloads.wifiOnly", true)
        set(v) { Prefs.put("downloads.wifiOnly", v); if (::ctx.isInitialized) schedule() }

    /** Nu op een netwerk met datalimiet (mobiel)? */
    internal fun metered(): Boolean = runCatching {
        (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).isActiveNetworkMetered
    }.getOrDefault(false)

    /** Build 171: speelt dit af vanaf een download (telt niet als stream, net als op Apple)? */
    fun isOffline(url: String?): Boolean {
        if (url == null) return false
        val scheme = Uri.parse(url).scheme?.lowercase(Locale.ROOT)
        if (scheme == "file" || scheme == "content") return true
        return items.any { it.hls && it.isDone && it.url == url }
    }

    private fun isStreamManifest(url: String): Boolean {
        val l = url.lowercase(Locale.ROOT).substringBefore('?')
        return l.endsWith(".m3u8") || l.endsWith(".mpd") || l.contains(".m3u8") || l.contains("/manifest")
    }

    /** Kan dit item (waarschijnlijk) gedownload worden? (MAC-portal en Stremio pas zeker na het opvragen van de link) */
    fun canDownload(item: PlayableItem): Boolean {
        if (!supported || item.type == MediaType.LIVE) return false
        // build 179: geen downloads van een WebDAV-bron
        if (item.id.startsWith("dav:")) return false
        val u = item.url
        if (u != null && item.stalkerCmd == null) {
            val scheme = Uri.parse(u).scheme?.lowercase(Locale.ROOT)
            // build 171: HLS ook (Media3 HlsDownloader); DASH niet
            return (scheme == "http" || scheme == "https") && !u.lowercase(Locale.ROOT).substringBefore('?').endsWith(".mpd")
        }
        return item.stalkerCmd != null || item.stremio != null
    }

    /**
     * Download starten. Geeft null bij succes, anders een (vertaalde) reden. De link wordt nu opgevraagd
     * (MAC-portal/Stremio); een HLS-stream valt af.
     */
    suspend fun start(item: PlayableItem, seriesTitle: String? = null, poster: String? = null): String? {
        if (!supported) return L("Downloads are not available on this device.")
        val src = Playlists.source?.id
        find(src, item.id)?.let { existing ->
            shareWithActive(existing)
            if (existing.status == FAILED || existing.status == PAUSED) resume(existing.key)
            return null
        }
        val url = runCatching { Playlists.resolve(item) }.getOrNull() ?: return L("No stream address available.")
        val scheme = Uri.parse(url).scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return L("This title can't be downloaded (streaming only).")
        // build 171: HLS via Media3; DASH en andere manifesten niet
        val hls = url.lowercase(Locale.ROOT).contains(".m3u8")
        if (!hls && isStreamManifest(url)) return L("This title can't be downloaded (streaming only).")
        val ext = Uri.parse(url).lastPathSegment?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
            ?.takeIf { it.length in 2..4 && it.all { c -> c.isLetterOrDigit() } } ?: "mp4"
        val safe = (src ?: "x").filter { it.isLetterOrDigit() }.take(12) + "_" + item.id.filter { it.isLetterOrDigit() || it == '-' }.take(60)
        val d = DownloadItem(
            id = item.id, title = item.title, subtitle = item.subtitle, poster = poster ?: item.artwork, type = item.type,
            seriesID = item.seriesID, seriesTitle = seriesTitle, season = item.season, episode = item.episode, imdbID = item.imdbID,
            sourceID = src, profileID = Store.activeProfileID, url = url, fileName = if (hls) "hls/$safe" else "$safe.$ext", hls = hls,
        )
        mutate { it.filterNot { x -> x.key == d.key } + d }
        askNotificationPermission()
        schedule()
        return null
    }

    /**
     * Al gedownload door een ander profiel (build 60): voortaan gedeeld (profileID null), anders toont de
     * detailpagina "Gedownload" terwijl hij in Downloads van dit profiel nergens staat.
     */
    fun shareWithActive(d: DownloadItem) {
        val me = Store.activeProfileID ?: return
        if (d.profileID != null && d.profileID != me) update(d.key) { it.copy(profileID = null) }
    }

    fun pause(key: String) = update(key) { if (it.status == DONE) it else it.copy(status = PAUSED) }

    fun resume(key: String) {
        update(key) { if (it.status == DONE) it else it.copy(status = QUEUED, error = null) }
        schedule()
    }

    fun delete(key: String) {
        val d = get(key) ?: return
        mutate { l -> l.filterNot { it.key == key } }
        if (d.hls) Thread { HlsDownloads.remove(ctx, d.url) }.start() else runCatching { file(d).delete() }
    }

    /**
     * Voltooide downloads waarvan het bestand buiten de app is verwijderd (bestandsbeheer, opslag opgeschoond):
     * uit de lijst halen, zodat de detailpagina niet meer "Gedownload" toont en je opnieuw kunt downloaden.
     * Bij het starten en telkens als de app weer op de voorgrond komt.
     */
    fun verifyFiles() {
        if (!::ctx.isInitialized || !supported) return
        // opslag (even) niet beschikbaar: niets weghalen, anders verdwijnen downloads die er gewoon nog zijn
        val ext = ctx.getExternalFilesDir("downloads") ?: return
        if (android.os.Environment.getExternalStorageState(ext) != android.os.Environment.MEDIA_MOUNTED) return
        val missing = synchronized(lock) { list.filter { !it.hls && it.status == DONE && runCatching { !file(it).isFile }.getOrDefault(false) } }
        if (missing.isEmpty()) return
        val keys = missing.map { it.key }.toSet()
        mutate { l -> l.filterNot { it.key in keys } }
    }

    /** Profiel verwijderd (hier of via de sync, build 60): zijn downloads en bestanden ook weg. */
    fun profileDeleted(id: String) {
        if (!::ctx.isInitialized || !supported) return
        val gone = synchronized(lock) { list.filter { it.profileID != null && it.profileID.equals(id, ignoreCase = true) } }
        if (gone.isEmpty()) return
        val keys = gone.map { it.key }.toSet()
        mutate { l -> l.filterNot { it.key in keys } }
        gone.forEach { d -> if (d.hls) Thread { HlsDownloads.remove(ctx, d.url) }.start() else runCatching { file(d).delete() } }
    }

    /** Afspeelbaar item voor een voltooide download (lokaal bestand, hervat op de opgeslagen positie). */
    fun playable(d: DownloadItem): PlayableItem = PlayableItem(
        id = d.id, title = d.seriesTitle ?: d.title, subtitle = d.subtitle,
        url = if (d.hls) d.url else Uri.fromFile(file(d)).toString(), type = d.type, imdbID = d.imdbID, season = d.season, episode = d.episode,
        artwork = d.poster, resumeAt = Store.progress[d.id]?.fraction ?: 0.0, seriesID = d.seriesID,
    )

    // MARK: achtergrond

    private const val JOB_ID = 5201

    fun schedule() {
        // build 141: loopt er al een download, dan pakt die de wachtrij op (niet opnieuw inplannen: dat stopt hem)
        if (DownloadRunner.running) return
        if (Build.VERSION.SDK_INT >= 34 && scheduleUserInitiated()) return
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        runCatching { WorkManager.getInstance(ctx).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, req) }
    }

    /** Android 14+: door de gebruiker gestarte gegevensoverdracht (zonder voorgronddienst). false = lukte niet. */
    @androidx.annotation.RequiresApi(34)
    private fun scheduleUserInitiated(): Boolean = runCatching {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return false
        val left = synchronized(lock) { list.filter { it.status == QUEUED || it.status == DOWNLOADING } }
            .sumOf { d -> if (d.totalBytes > 0) (d.totalBytes - d.bytes).coerceAtLeast(0) else 0L }
        val info = JobInfo.Builder(JOB_ID, ComponentName(ctx, DownloadJobService::class.java))
            .setUserInitiated(true)
            .setRequiredNetworkType(if (wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
            .apply { if (left > 0) setEstimatedNetworkBytes(left, JobInfo.NETWORK_BYTES_UNKNOWN.toLong()) }
            .build()
        js.schedule(info) == JobScheduler.RESULT_SUCCESS
    }.getOrDefault(false)

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        runCatching { MainActivity.current?.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(CHANNEL, L("Downloads"), NotificationManager.IMPORTANCE_LOW)
        ch.lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    internal fun notification(c: Context, title: String, text: String, percent: Int?, done: Boolean): android.app.Notification {
        val open = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(if (done) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(!done)
            .setAutoCancel(done)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .apply { if (!done) setProgress(100, percent ?: 0, percent == null) }
            .build()
    }

    fun formatBytes(b: Long): String {
        if (b < 0) return "?"
        val mb = b / 1_048_576.0
        return if (mb >= 1024) String.format(Localizer.locale, "%.1f GB", mb / 1024) else String.format(Localizer.locale, "%.0f MB", mb)
    }
}

/**
 * Build 141: werkt de wachtrij af, één download tegelijk (voor [DownloadWorker] en [DownloadJobService]).
 * [stopped] = het systeem stopt de taak; [progress] = voortgangsmelding bijwerken.
 */
class DownloadRunner(
    private val context: Context,
    private val stopped: () -> Boolean,
    private val progress: (android.app.Notification) -> Unit,
) {
    companion object {
        /** Er loopt nu een download (in deze app). */
        @Volatile var running = false
            private set
    }
    private val nm get() = context.getSystemService(NotificationManager::class.java)

    class NetworkLost : Exception()

    /** true = later opnieuw proberen (geen netwerk, of gestopt met nog iets in de wachtrij). */
    suspend fun run(): Boolean {
        running = true
        try {
            while (!stopped()) {
                val d = Downloads.nextQueued() ?: break
                try {
                    download(d)
                } catch (e: CancellationException) {
                    requeue(d); throw e
                } catch (_: NetworkLost) {
                    requeue(d)
                    return true
                }
            }
            return stopped() && Downloads.nextQueued() != null
        } finally { running = false }
    }

    private fun requeue(d: DownloadItem) {
        Downloads.update(d.key) { if (it.status == Downloads.DOWNLOADING) it.copy(status = Downloads.QUEUED) else it }
    }

    private suspend fun download(start: DownloadItem) = withContext(Dispatchers.IO) {
        val key = start.key
        // build 171: "Alleen via wifi": op mobiel netwerk wachten (de taak start weer als er wifi is)
        if (Downloads.wifiOnly && Downloads.metered()) throw NetworkLost()
        if (start.hls) { downloadHls(start); return@withContext }
        val file = Downloads.file(start)
        var have = if (file.exists()) file.length() else 0L
        Downloads.update(key) { it.copy(status = Downloads.DOWNLOADING, error = null, bytes = have) }
        val client = Http.client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
        val req = Request.Builder().url(start.url).header("User-Agent", Http.USER_AGENT)
            .apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        val resp = try { client.newCall(req).execute() } catch (e: IOException) { throw NetworkLost() }
        resp.use { r ->
            if (r.code == 416 && have > 0) {   // al compleet
                Downloads.update(key) { it.copy(status = Downloads.DONE, bytes = have, totalBytes = have) }
                finished(start, true); return@withContext
            }
            if (!r.isSuccessful) {
                if (r.code >= 500 || r.code == 429) throw NetworkLost()
                fail(key, "HTTP ${r.code}"); return@withContext
            }
            val type = r.header("Content-Type")?.lowercase(Locale.ROOT) ?: ""
            if (type.contains("mpegurl") || type.contains("dash+xml")) {
                fail(key, L("This title can't be downloaded (streaming only).")); return@withContext
            }
            // server negeert Range: opnieuw beginnen
            if (have > 0 && r.code != 206) { have = 0; file.delete() }
            val body = r.body ?: run { fail(key, "HTTP ${r.code}"); return@withContext }
            val len = body.contentLength()
            val total = if (len >= 0) have + len else -1L
            Downloads.update(key) { it.copy(totalBytes = total, bytes = have) }
            var lastUI = 0L
            try {
                FileOutputStream(file, have > 0).use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            have += n
                            val now = System.currentTimeMillis()
                            if (now - lastUI > 700) {
                                lastUI = now
                                val cur = Downloads.get(key)
                                if (cur == null) { runCatching { file.delete() }; return@withContext }   // verwijderd
                                if (cur.status == Downloads.PAUSED) { Downloads.update(key) { it.copy(bytes = have) }; return@withContext }
                                if (stopped()) throw NetworkLost()
                                Downloads.update(key) { it.copy(bytes = have) }
                                val pct = if (total > 0) (have * 100 / total).toInt() else null
                                val text = if (total > 0) "${Downloads.formatBytes(have)} / ${Downloads.formatBytes(total)}" else Downloads.formatBytes(have)
                                runCatching { progress(Downloads.notification(context, cur.subtitle?.let { "${cur.seriesTitle ?: cur.title} · $it" } ?: cur.title, text, pct, false)) }
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                Downloads.update(key) { it.copy(bytes = have) }
                throw NetworkLost()
            }
            if (Downloads.get(key) == null) { runCatching { file.delete() }; return@withContext }
            if (total > 0 && have < total) { Downloads.update(key) { it.copy(bytes = have) }; throw NetworkLost() }
            Downloads.update(key) { it.copy(status = Downloads.DONE, bytes = have, totalBytes = have) }
            finished(start, true)
        }
    }

    /** Build 171: HLS met Media3; hervat zelf (wat al in de cache staat, wordt overgeslagen). */
    private fun downloadHls(start: DownloadItem) {
        val key = start.key
        Downloads.update(key) { it.copy(status = Downloads.DOWNLOADING, error = null) }
        var lastUI = 0L
        var why: String? = null
        try {
            HlsDownloads.download(context, start.url) { pct, bytes ->
                val now = System.currentTimeMillis()
                if (now - lastUI < 700) return@download true
                lastUI = now
                val cur = Downloads.get(key) ?: return@download false
                if (cur.status == Downloads.PAUSED) return@download false
                if (stopped()) { why = "stopped"; return@download false }
                // percent → bytes: geschat totaal zodat de bestaande voortgang (bytes / totalBytes) klopt
                val total = if (pct > 0.5f) (bytes * 100 / pct).toLong() else -1L
                Downloads.update(key) { it.copy(bytes = bytes, totalBytes = total) }
                runCatching { progress(Downloads.notification(context, cur.subtitle?.let { "${cur.seriesTitle ?: cur.title} · $it" } ?: cur.title,
                    "${pct.toInt()}%", pct.toInt(), false)) }
                true
            }
        } catch (e: InterruptedException) {
            Thread.interrupted()
        } catch (e: IOException) {
            if (Downloads.get(key)?.status == Downloads.DOWNLOADING) throw NetworkLost()
        } catch (e: Exception) {
            val cur = Downloads.get(key)
            if (cur != null && cur.status == Downloads.DOWNLOADING) { fail(key, L("This title can't be downloaded (streaming only).")); return }
        }
        val cur = Downloads.get(key) ?: run { HlsDownloads.remove(context, start.url); return }
        if (cur.status == Downloads.PAUSED) return
        if (why != null) throw NetworkLost()
        if (cur.status != Downloads.DOWNLOADING) return
        Downloads.update(key) { it.copy(status = Downloads.DONE, totalBytes = it.bytes) }
        finished(start, true)
    }

    private fun fail(key: String, why: String) {
        Downloads.update(key) { it.copy(status = Downloads.FAILED, error = why) }
        Downloads.get(key)?.let { finished(it, false) }
    }

    private fun finished(d: DownloadItem, ok: Boolean) {
        val title = d.subtitle?.let { "${d.seriesTitle ?: d.title} · $it" } ?: d.title
        val n = Downloads.notification(context, title, if (ok) L("Download complete") else L("Download failed"), null, true)
        runCatching { nm?.notify(d.key.hashCode(), n) }
    }
}

/**
 * Downloads via WorkManager: Android 7–13 als voorgronddienst zonder type (met voortgangsmelding); Android 14+ alleen als
 * terugval (zonder voorgronddienst, zie [Downloads.schedule]). Zonder netwerk stopt WorkManager de taak en start hem weer
 * zodra er verbinding is; na een herstart ook.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val notifID = 5200

    // build 141: zonder type (FOREGROUND_SERVICE_DATA_SYNC is weg); alleen gebruikt op Android 13 en ouder
    private fun foregroundInfo(n: android.app.Notification): ForegroundInfo = ForegroundInfo(notifID, n)

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(Downloads.notification(applicationContext, L("Downloads"), L("Downloading…"), null, false))

    override suspend fun doWork(): Result {
        val fg = Build.VERSION.SDK_INT < 34
        // voorgronddienst (Android 13 en ouder); mag soms niet (Android 12+ vanaf de achtergrond): dan gewoon zonder
        if (fg) try { setForeground(getForegroundInfo()) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        val retry = DownloadRunner(applicationContext, { isStopped }) { n ->
            if (fg) setForegroundAsync(foregroundInfo(n)) else runCatching { nm?.notify(notifID, n) }
        }.run()
        if (!fg) runCatching { nm?.cancel(notifID) }
        return if (retry) Result.retry() else Result.success()
    }
}

/**
 * Build 141: Android 14+: downloads als door de gebruiker gestarte gegevensoverdracht (JobScheduler, setUserInitiated),
 * met de voortgangsmelding van het systeem (setNotification). Geen voorgronddienst en geen FOREGROUND_SERVICE_DATA_SYNC.
 */
@androidx.annotation.RequiresApi(34)
class DownloadJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    @Volatile private var stopped = false
    private val notifID = 5200

    override fun onStartJob(params: JobParameters): Boolean {
        stopped = false
        // binnen 10 s een melding (verplicht voor deze soort taak)
        runCatching {
            setNotification(params, notifID, Downloads.notification(this, L("Downloads"), L("Downloading…"), null, false),
                JOB_END_NOTIFICATION_POLICY_REMOVE)
        }
        job = scope.launch {
            val retry = try {
                DownloadRunner(applicationContext, { stopped }) { n ->
                    runCatching { setNotification(params, notifID, n, JOB_END_NOTIFICATION_POLICY_REMOVE) }
                }.run()
            } catch (e: CancellationException) { throw e } catch (_: Exception) { true }
            jobFinished(params, retry && !stopped)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped = true
        job?.cancel()
        // opnieuw inplannen als er nog iets in de wachtrij staat
        return Downloads.nextQueued() != null
    }
}
