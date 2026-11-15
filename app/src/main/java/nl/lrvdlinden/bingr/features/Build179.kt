package nl.lrvdlinden.bingr

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Build 179 (1.0.8): Android-kant van docs/ACCOUNT.md "Build 179" (pure regels in [Build179Logic]).
 * Punten 1 (vanzelf verversen), 3 (andere speler onthouden) en 13 (opruimen) staan hier; de rest in Build179*.kt.
 */

// MARK: 1. Gids en servers vanzelf verversen

object AutoRefresh179 {
    private val R = Build179Logic.Refresh

    var catalog: String
        get() = R.catalog(Prefs.string(R.CATALOG_PREF))
        set(v) { Prefs.put(R.CATALOG_PREF, v); schedule() }
    var epg: String
        get() = R.epg(Prefs.string(R.EPG_PREF))
        set(v) { Prefs.put(R.EPG_PREF, v); schedule() }
    /** "Ook via mobiel netwerk" (alleen telefoon/tablet). */
    var cellular: Boolean
        get() = Prefs.bool(R.CELLULAR_PREF, false)
        set(v) { Prefs.put(R.CELLULAR_PREF, v); schedule() }

    val catalogOptions: List<Pair<String, String>> get() = listOf(
        "6h" to L("Every 6 hours"), "daily" to L("Daily"), "3d" to L("Every 3 days"), "weekly" to L("Weekly"),
        "manual" to L("Manually only"))
    val epgOptions: List<Pair<String, String>> get() = listOf("6h" to L("Every 6 hours"), "12h" to L("Every 12 hours"), "daily" to L("Daily"))

    private fun key(kind: String, id: String) = "refresh.last.$kind." + id.lowercase(java.util.Locale.ROOT)

    /** Laatste geslaagde volledige verversing (ms); zonder record de tijd van de bewaarde lijst. */
    fun lastFull(p: PlaylistSource): Long? =
        Prefs.long(key("full", p.id), 0L).takeIf { it > 0 } ?: Playlists.cacheStamp(p).takeIf { it > 0 }
    fun lastEpg(p: PlaylistSource): Long? = Prefs.long(key("epg", p.id), 0L).takeIf { it > 0 }
    fun markFull(id: String) = Prefs.put(key("full", id), System.currentTimeMillis())
    fun markEpg(id: String) = Prefs.put(key("epg", id), System.currentTimeMillis())

    fun rememberEpgURL(id: String, url: String?) {
        val k = "refresh.epgurl." + id.lowercase(java.util.Locale.ROOT)
        if (url.isNullOrBlank()) { if (Prefs.has(k)) Prefs.remove(k) } else if (Prefs.string(k) != url) Prefs.put(k, url)
    }
    fun epgURL(id: String): String? = Prefs.string("refresh.epgurl." + id.lowercase(java.util.Locale.ROOT))

    private fun saver(): Boolean = runCatching { DataSaver.active() }.getOrDefault(false)

    /** "full", "epg", "none" of "blocked" voor server [p]. Mediaservers en Stremio hebben geen gids. */
    fun action(p: PlaylistSource, nowMs: Long = System.currentTimeMillis()): String {
        val a = R.action(catalog, epg, lastFull(p)?.div(1000), lastEpg(p)?.div(1000), nowMs / 1000, saver(), cellular)
        if (a == "epg" && p.kind.isMediaServer) return "none"
        return a
    }

    fun anyDue(): Boolean = !Screenshots.isActive && !StressMode.isActive &&
        Store.playlists.any { val a = action(it); a == "full" || a == "epg" }

    private var job: Job? = null
    private var ticker: Job? = null
    private fun mayRun() = AppState.stage == AppState.Stage.MAIN && AppState.playing == null && !Playlists.isLoading &&
        !Playlists.refreshingAll && !Playlists.updatingAll

    /**
     * Kijken of er iets ververst moet worden (na de profielkeuze, bij terugkeer naar de voorgrond en elke 15 minuten): per
     * server, de open server eerst, één tegelijk, niet tijdens het afspelen en niet buiten het hoofdscherm (wachten).
     */
    fun check() {
        if (Screenshots.isActive || StressMode.isActive) return
        startTicker()
        if (job?.isActive == true) return
        job = AppScope.launch {
            delay(1_500)
            val active = Playlists.source?.id
            val list = Store.playlists.sortedBy { if (it.id == active) 0 else 1 }
            val work = list.map { it to action(it) }.filter { it.second == "full" || it.second == "epg" }
            if (work.isEmpty()) return@launch
            var others = false
            var shown = false
            try {
                for ((i, w) in work.withIndex()) {
                    val (p, _) = w
                    while (!mayRun()) delay(2_000)
                    if (Store.playlists.none { it.id == p.id }) continue
                    // opnieuw bepalen: intussen misschien handmatig ververst
                    val a = action(p)
                    if (a != "full" && a != "epg") continue
                    shown = true
                    Playlists.setAutoStatus(if (work.size > 1) L("Refreshing %@ (%d of %d)…", p.name, i + 1, work.size) else L("Updating…"))
                    if (a == "full") {
                        if (p.id == Playlists.source?.id) withTimeoutOrNull(180_000) { Playlists.refresh(p, showSpinner = false) }
                        else { AllServers.heavy { Playlists.refreshOther(p) }; others = true }
                    } else Playlists.refreshEpg(p)
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
            } finally {
                if (shown && !Playlists.refreshingAll) Playlists.setAutoStatus(null)
            }
            if (others) AllServers.catalogsRefreshed()
        }
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = AppScope.launch {
            while (true) {
                delay(15 * 60_000L)
                if (AppState.stage == AppState.Stage.MAIN) check()
            }
        }
    }

    // MARK: achtergrond (WorkManager)

    private const val WORK = "bingr.refresh179"

    fun schedule(ctx: Context = AppState.appContext) {
        if (Screenshots.isActive || StressMode.isActive) return
        runCatching {
            val hours = R.seconds(epg)!! / 3600   // de gids heeft altijd een termijn (6, 12 of 24 uur)
            val c = Constraints.Builder()
                .setRequiredNetworkType(if (cellular || AppState.isTV) NetworkType.CONNECTED else NetworkType.UNMETERED)
                .build()
            val req = PeriodicWorkRequestBuilder<Refresh179Worker>(hours, TimeUnit.HOURS).setConstraints(c).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }

    /** Op de achtergrond: wat `due` is verversen (alleen de bewaarde lijsten; de open lijst ververst de app zelf). */
    suspend fun background() = withContext(Dispatchers.Main) {
        if (Screenshots.isActive || StressMode.isActive) return@withContext
        if (AppState.stage == AppState.Stage.MAIN) { check(); return@withContext }
        if (Playlists.isLoading || Playlists.refreshingAll || Playlists.updatingAll || job?.isActive == true) return@withContext
        for (p in Store.playlists.toList()) {
            when (action(p)) {
                "full" -> runCatching { AllServers.heavy { Playlists.refreshOther(p) } }
                "epg" -> runCatching { Playlists.refreshEpg(p) }
                else -> {}
            }
        }
    }
}

class Refresh179Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try { AutoRefresh179.background() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        return Result.success()
    }
}

// MARK: 3. Andere speler: onthouden per stream (lokaal `player.engineMemo`)

object EngineMemo179 {
    private val E = Build179Logic.Engine
    private var cache: Map<String, Build179Logic.Engine.Memo>? = null

    private fun all(): Map<String, Build179Logic.Engine.Memo> =
        cache ?: E.decode(Prefs.string(E.MEMO_PREF)).also { cache = it }

    /** Sleutel van dit item, of null als het niet meedoet (gedownload, geen server). */
    fun key(item: PlayableItem): String? {
        val sid = item.sourceID ?: Playlists.source?.id ?: return null
        return when (item.type) {
            MediaType.LIVE -> E.key(sid, "live", item.channelID ?: item.id)
            MediaType.MOVIE -> E.key(sid, "movie", item.id)
            MediaType.EPISODE -> E.key(sid, "episode", item.id)
            else -> null
        }
    }

    fun get(key: String?): String? = key?.let { all()[it]?.engine }

    fun set(key: String, engine: String?) {
        val now = System.currentTimeMillis() / 1000
        if (engine != null && all()[key]?.engine == engine && now - (all()[key]?.at ?: 0) < 86_400) return
        if (engine == null && key !in all()) return
        val next = E.update(all(), key, engine, now)
        cache = next
        Prefs.put(E.MEMO_PREF, E.encode(next))
    }
}

// MARK: 13. Titels opruimen die de aanbieder verwijderd heeft

object Cleanup179 {
    private val C = Build179Logic.Cleanup
    /** "%d titels niet meer beschikbaar" (kort in beeld na het verversen). */
    var notice by mutableStateOf<String?>(null)

    private data class Gone(val id: String, val series: Boolean, val title: String, val year: Int?, val tmdb: String?)

    /**
     * Na een volledige, geslaagde verversing van [p]: verwijzingen in Mijn lijst, Verder kijken (films) en de metadata
     * van downloads naar titels die niet meer op [p] staan, wijzen naar dezelfde titel op een andere server (TMDB-id, anders
     * titel + jaar + soort) of gaan weg. Kijkgeschiedenis, gezien en Trakt blijven. Alleen het actieve profiel.
     * Op de main thread aanroepen.
     */
    fun afterRefresh(p: PlaylistSource, before: Library?, after: Library) {
        if (before == null || Screenshots.isActive || StressMode.isActive) return
        // niet ingelezen categorieën (build 149) tellen niet als verwijderd
        val skipM = after.hiddenGroupNames("movie").toSet(); val skipS = after.hiddenGroupNames("series").toSet()
        val oldM = before.movies.filter { it.group !in skipM }
        val oldS = before.series.filter { it.group !in skipS }
        if (!C.allowed(oldM.size + oldS.size, after.movies.size + after.series.size, true)) return
        val newM = after.movies.mapTo(HashSet()) { it.id }
        val newS = after.series.mapTo(HashSet()) { it.id }
        val gone = oldM.filter { it.id !in newM }.map { Gone(it.id, false, it.title, it.year, it.tmdbID) } +
            oldS.filter { it.id !in newS }.map { Gone(it.id, true, it.title, it.year, it.tmdbID) }
        if (gone.isEmpty()) return
        // alleen titels waar iets naar verwijst
        val pid = p.id
        val refs = gone.filter { g ->
            Store.inMyList(pid, g.id) ||
                (!g.series && Store.progress[g.id]?.let { Store.samePlaylist(it.playlistID, pid) } == true) ||
                Downloads.items.any { d -> d.isDone && d.sourceID != null && d.sourceID.equals(pid, true) && (d.id == g.id || d.seriesID == g.id) }
        }
        if (refs.isEmpty()) return
        AppScope.launch {
            val others = withContext(Dispatchers.IO) {
                Store.playlists.filter { !it.id.equals(pid, ignoreCase = true) }
                    .mapNotNull { o -> runCatching { Playlists.readCatalog(o) }.getOrNull()?.let { o.id to it } }
            }
            val byTmdb = HashMap<String, Pair<String, String>>()
            val byTitle = HashMap<String, Pair<String, String>>()
            for ((sid, lib) in others) {
                for (m in lib.movies) {
                    m.tmdbID?.takeIf { it.isNotBlank() }?.let { byTmdb.putIfAbsent("movie:$it", sid to m.id) }
                    byTitle.putIfAbsent(C.titleKey(m.title, m.year, false), sid to m.id)
                }
                for (s in lib.series) {
                    s.tmdbID?.takeIf { it.isNotBlank() }?.let { byTmdb.putIfAbsent("series:$it", sid to s.id) }
                    byTitle.putIfAbsent(C.titleKey(s.title, s.year, true), sid to s.id)
                }
            }
            var count = 0
            for (g in refs) {
                val target = g.tmdb?.takeIf { it.isNotBlank() }?.let { byTmdb[(if (g.series) "series:" else "movie:") + it] }
                    ?: byTitle[C.titleKey(g.title, g.year, g.series)]
                if (C.action(false, target != null) == "keep") continue
                count++
                if (Store.inMyList(pid, g.id)) {
                    Store.setMyList(pid, g.id, false)
                    if (target != null) Store.setMyList(target.first, target.second, true)
                }
                if (!g.series) Store.progress[g.id]?.takeIf { Store.samePlaylist(it.playlistID, pid) }?.let { e ->
                    if (target != null && Store.progress[target.second] == null) Store.setProgress(target.second, e.copy(playlistID = target.first))
                    Store.clearProgress(g.id)
                }
                Downloads.detach179(pid, g.id, target)
            }
            if (count > 0) {
                notice = L("%d titles no longer available", count)
                delay(8_000)
                notice = null
            }
        }
    }
}
