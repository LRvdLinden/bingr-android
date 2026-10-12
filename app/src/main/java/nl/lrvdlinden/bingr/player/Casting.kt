package nl.lrvdlinden.bingr

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.HlsSegmentFormat
import com.google.android.gms.cast.HlsVideoSegmentFormat
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.MediaTrack as CastTrack
import com.google.android.gms.cast.TextTrackStyle
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.images.WebImage
import java.io.File
import java.util.Locale

/** Chromecast: standaard media-ontvanger (CC1AD845). Staat in AndroidManifest als OPTIONS_PROVIDER_CLASS_NAME. */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/**
 * Chromecast via de Google Cast SDK. Zodra een Cast-sessie start (apparaat gekozen in [CastDevicesDialog]),
 * wordt wat er nu speelt ([PlayerController.current]) op de Chromecast gezet en lokaal gepauzeerd.
 *
 * Build 56: de standaard-ontvanger (CC1AD845) speelt HLS (ook met TS-segmenten), MP4, WebM en MP3/AAC, maar geen
 * ruwe MPEG-TS-stream en officieel geen MKV/AVI. Live-zenders van Xtream kwamen als .ts binnen (video/mp2t) en werden
 * dus altijd geweigerd: "Deze stream kan niet op Chromecast worden afgespeeld". Nu proberen we per stream een rijtje
 * pogingen ([Attempt]): eerst het formaat dat de ontvanger kan (bijv. .m3u8), dan via het doorgeefluik op de telefoon
 * ([CastRelay], voor servers zonder CORS), en pas als alles faalt de melding.
 *
 * Build 57: ondertitels. Een ingeladen bestand (OpenSubtitles, SRT/VTT) gaat als WebVTT-tekstspoor mee via
 * [CastRelay.subtitleUrl]; een gekozen spoor uit de stream wordt bij HLS gezocht tussen de ondertitels die de ontvanger
 * zelf in de playlist vindt (op taal, naam of volgorde) en aangezet. Wisselen tijdens het casten gaat mee.
 */
object Casting {
    var connected by mutableStateOf(false); private set
    var deviceName by mutableStateOf<String?>(null); private set
    var status by mutableStateOf(""); private set
    var isPlaying by mutableStateOf(false); private set

    private var initialized = false
    private var client: RemoteMediaClient? = null
    private val main = Handler(Looper.getMainLooper())

    /** Ondertitel die op de Chromecast moet (zie [PlayerController.castSubtitle]). */
    sealed class CastSubtitle {
        /** Ingeladen bestand; [shift] = vertraging in seconden op het moment van laden. */
        data class External(val file: File, val lang: String?, val name: String, val shift: Double) : CastSubtitle()
        /** Spoor [index] van [count] uit de stream. */
        data class Embedded(val track: MediaTrack, val index: Int, val count: Int) : CastSubtitle()
    }

    /** Id van ons eigen tekstspoor; hoog, zodat het niet botst met de sporen die de ontvanger zelf uit HLS haalt. */
    private const val EXTERNAL_TRACK_ID = 9_001L

    /** Gewenste ondertitel en wat daarvan op de ontvanger staat. */
    private var wantedSub: CastSubtitle? = null
    /** Bestand dat als tekstspoor in de huidige MediaInfo zit (null = geen). */
    private var loadedExternal: File? = null
    /** Spoor uit de stream is aangezet of opgegeven; false = nog zoeken bij de volgende statusupdate. */
    private var embeddedDone = true
    /** Poging die nu speelt (voor opnieuw laden met een nieuw ondertitelbestand). */
    private var currentAttempt: Attempt? = null

    /** Eén poging: adres + contentType (+ HLS-segmenthint), eventueel via het doorgeefluik. */
    internal data class Attempt(val url: String, val contentType: String, val relay: Boolean = false, val tsHint: Boolean = true)

    // Wat we nu proberen te casten, met de nog te proberen pogingen (bij een fout de volgende).
    private var pendingItem: PlayableItem? = null
    private var pendingPosMs = 0L
    private var attempts: List<Attempt> = emptyList()
    private var attemptNo = 0
    private var tried = 0
    /** Laadverzoek verstuurd, nog geen antwoord: foutstatussen van de vorige poging negeren. */
    private var awaitingResult = false
    /** Deze poging speelt (of stond al eens op pauze): niet meer automatisch wisselen. */
    private var started = false

    private val clientCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() { updateStatus() }
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            status = L("Connecting to %@…", session.castDevice?.friendlyName ?: "Chromecast")
        }
        override fun onSessionStarted(session: CastSession, sessionId: String) { attach(session, castCurrent = true) }
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            detach(); status = L("Chromecast error: %@", error.toString())
        }
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionEnded(session: CastSession, error: Int) { detach() }
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        // hervatte sessie (app terug uit de achtergrond): speelt de ontvanger niets meer, dan het huidige opnieuw casten
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            val idle = session.remoteMediaClient?.let { it.mediaStatus == null || it.playerState == MediaStatus.PLAYER_STATE_IDLE } ?: true
            attach(session, castCurrent = idle)
        }
        override fun onSessionResumeFailed(session: CastSession, error: Int) { detach() }
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    @Suppress("DEPRECATION")
    fun castContext(ctx: Context): CastContext? =
        runCatching { CastContext.getSharedInstance(ctx.applicationContext) }.getOrNull()

    /** Chromecast mogelijk? (telefoon/tablet met Google Play-services; niet op TV/Fire TV) */
    fun isAvailable(ctx: Context): Boolean {
        if (AppState.isTV) return false
        val gms = runCatching {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx) == ConnectionResult.SUCCESS
        }.getOrDefault(false)
        return gms && castContext(ctx) != null
    }

    /** Eenmalig luisteren naar Cast-sessies (aanroepen vanaf het spelerscherm). */
    fun init(ctx: Context) {
        if (initialized || !isAvailable(ctx)) return
        val cc = castContext(ctx) ?: return
        initialized = true
        cc.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
        cc.sessionManager.currentCastSession?.let { if (it.isConnected) attach(it, castCurrent = false) }
    }

    private fun attach(session: CastSession, castCurrent: Boolean) {
        connected = true
        deviceName = session.castDevice?.friendlyName
        client?.unregisterCallback(clientCallback)
        client = session.remoteMediaClient
        client?.registerCallback(clientCallback)
        updateStatus()
        if (castCurrent) castCurrent()
    }

    /** Wat nu in de speler staat op de Chromecast zetten (en lokaal pauzeren). */
    fun castCurrent(): Boolean {
        val p = PlayerController.current ?: return false
        val u = p.url ?: return false
        if (p.isStopped) return false
        val ok = cast(p.item, u, (p.currentTime * 1000).toLong(), p.castSubtitle())
        if (ok) p.pause()
        return ok
    }

    private fun detach() {
        client?.unregisterCallback(clientCallback)
        client = null
        main.removeCallbacksAndMessages(null)
        attempts = emptyList(); pendingItem = null; awaitingResult = false
        wantedSub = null; loadedExternal = null; embeddedDone = true; currentAttempt = null
        CastRelay.stop()
        connected = false; deviceName = null; isPlaying = false; status = ""
    }

    private fun updateStatus() {
        val c = client ?: return
        isPlaying = c.isPlaying
        if (c.isPlaying || c.isPaused) started = true
        val failed = c.playerState == MediaStatus.PLAYER_STATE_IDLE && c.idleReason == MediaStatus.IDLE_REASON_ERROR
        // fout bij het laden: eerst de volgende poging (ander formaat / via de telefoon), pas daarna opgeven
        if (failed && !awaitingResult && !started && pendingItem != null) {
            if (next(c)) return
        }
        if (!failed && !awaitingResult) applyEmbedded(c)
        status = when {
            failed -> L("This stream can't be played on Chromecast.")
            c.isPlaying -> L("Playing on Chromecast")
            c.isPaused -> L("Paused on Chromecast")
            c.isBuffering -> L("Connecting to %@…", deviceName ?: "Chromecast")
            else -> deviceName ?: "Chromecast"
        }
    }

    /** Volgende poging starten; false = niets meer te proberen. */
    private fun next(c: RemoteMediaClient): Boolean {
        val item = pendingItem ?: return false
        while (attempts.isNotEmpty()) {
            val a = attempts.first(); attempts = attempts.drop(1)
            status = if (tried++ == 0) L("Connecting to %@…", deviceName ?: "Chromecast") else L("Trying another stream format…")
            if (load(c, item, a, pendingPosMs)) return true
        }
        return false
    }

    /**
     * Pogingen voor dit adres, beste eerst. Xtream-live (/live/u/p/123.ts) → .m3u8 (HLS); Xtream-terugkijken idem;
     * films/afleveringen met hun eigen container en MIME-type. HLS daarna ook via het doorgeefluik (CORS/user-agent).
     */
    internal fun castAttempts(url: String, live: Boolean): List<Attempt> {
        val out = mutableListOf<Attempt>()
        val u = runCatching { Uri.parse(url) }.getOrNull()
        val path = u?.path.orEmpty()
        val segs = path.split('/').filter { it.isNotEmpty() }
        val lower = path.lowercase(Locale.ROOT)
        val ext = lower.substringAfterLast('/').substringAfterLast('.', "")
        fun withExt(newExt: String): String? {
            if (u == null || segs.isEmpty()) return null
            val last = segs.last().substringBeforeLast('.')
            return u.buildUpon().path("/" + (segs.dropLast(1) + "$last.$newExt").joinToString("/")).build().toString()
        }
        fun hls(h: String) { out += Attempt(h, HLS); out += Attempt(h, HLS, relay = true) }

        val xtreamLive = segs.size >= 4 && segs[segs.size - 4].equals("live", true)
        val xtreamShort = live && segs.size == 3 && segs[2].substringBeforeLast('.').all { it.isDigit() }
        val timeshift = segs.firstOrNull()?.equals("timeshift", true) == true && segs.size >= 6
        when {
            ext == "m3u8" || ext == "m3u" -> { hls(url); out += Attempt(url, HLS, tsHint = false) }
            ext == "mpd" -> { out += Attempt(url, "application/dash+xml"); out += Attempt(url, "application/dash+xml", relay = true) }
            xtreamLive || timeshift -> {
                withExt("m3u8")?.let { hls(it); out += Attempt(it, HLS, tsHint = false) }
                out += Attempt(url, "video/mp2t")
            }
            xtreamShort && u != null -> {
                val id = segs[2].substringBeforeLast('.')
                hls(u.buildUpon().path("/live/${segs[0]}/${segs[1]}/$id.m3u8").build().toString())
                out += Attempt(url, "video/mp2t")
            }
            // andere live-adressen: .ts of zonder extensie; vaak werkt dezelfde naam met .m3u8
            live && (ext == "ts" || ext.isEmpty()) -> {
                if (ext == "ts") url.replaceFirst(Regex("\\.ts(?=($|\\?))", RegexOption.IGNORE_CASE), ".m3u8").let { hls(it) }
                out += Attempt(url, if (ext.isEmpty()) HLS else "video/mp2t")
                if (ext.isEmpty()) out += Attempt(url, "video/mp2t")
            }
            ext == "mp4" || ext == "m4v" || ext == "mov" -> { out += Attempt(url, "video/mp4"); out += Attempt(url, "video/mp4", relay = true) }
            ext == "webm" -> { out += Attempt(url, "video/webm"); out += Attempt(url, "video/webm", relay = true) }
            ext == "mp3" -> out += Attempt(url, "audio/mpeg")
            ext == "aac" || ext == "m4a" -> out += Attempt(url, "audio/mp4")
            // MKV: niet officieel, maar met H.264/AAC speelt de ontvanger het vaak wel (ook als video/mp4 aangeboden)
            ext == "mkv" -> {
                out += Attempt(url, "video/x-matroska"); out += Attempt(url, "video/mp4"); out += Attempt(url, "video/mp4", relay = true)
            }
            // AVI, FLV, TS-films e.d.: proberen, de ontvanger herkent het formaat soms zelf
            else -> { out += Attempt(url, if (ext == "ts") "video/mp2t" else "video/mp4"); out += Attempt(url, "video/mp4", relay = true) }
        }
        return out.distinct()
    }

    private const val HLS = "application/x-mpegurl"

    /** Zet [item] op de verbonden Chromecast, met ondertitel [sub]. false = geen sessie of niets te proberen. */
    fun cast(item: PlayableItem, url: String, positionMs: Long, sub: CastSubtitle? = null): Boolean {
        val c = client ?: castContext(AppState.appContext)?.sessionManager?.currentCastSession?.remoteMediaClient ?: return false
        pendingItem = item; pendingPosMs = positionMs
        wantedSub = sub
        attempts = castAttempts(url, item.type == MediaType.LIVE); tried = 0
        return next(c)
    }

    private fun load(c: RemoteMediaClient, item: PlayableItem, a: Attempt, positionMs: Long): Boolean {
        val url = if (a.relay) CastRelay.url(a.url, if (a.contentType == HLS) "m3u8" else a.url.substringBefore('?').substringAfterLast('.', "ts").take(5)) ?: return false
                  else a.url
        val type = when (item.type) {
            MediaType.MOVIE -> MediaMetadata.MEDIA_TYPE_MOVIE
            MediaType.EPISODE, MediaType.SERIES -> MediaMetadata.MEDIA_TYPE_TV_SHOW
            else -> MediaMetadata.MEDIA_TYPE_GENERIC
        }
        val md = MediaMetadata(type)
        md.putString(MediaMetadata.KEY_TITLE, item.subtitle?.takeIf { type == MediaMetadata.MEDIA_TYPE_TV_SHOW } ?: item.title)
        if (type == MediaMetadata.MEDIA_TYPE_TV_SHOW) {
            md.putString(MediaMetadata.KEY_SERIES_TITLE, item.title)
            item.season?.let { md.putInt(MediaMetadata.KEY_SEASON_NUMBER, it) }
            item.episode?.let { md.putInt(MediaMetadata.KEY_EPISODE_NUMBER, it) }
        } else {
            item.subtitle?.let { md.putString(MediaMetadata.KEY_SUBTITLE, it) }
        }
        item.artwork?.takeIf { it.startsWith("http") }?.let { md.addImage(WebImage(Uri.parse(it))) }
        val live = item.type == MediaType.LIVE
        val b = MediaInfo.Builder(url)
            .setContentUrl(url)
            .setStreamType(if (live) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(a.contentType)
            .setMetadata(md)
        // IPTV-HLS gebruikt bijna altijd MPEG-TS-segmenten; zonder deze hint verwacht de ontvanger soms fMP4/AAC
        if (a.contentType == HLS && a.tsHint) {
            b.setHlsSegmentFormat(HlsSegmentFormat.TS)
            b.setHlsVideoSegmentFormat(HlsVideoSegmentFormat.MPEG2_TS)
        }
        // ondertitel: een bestand als WebVTT via het doorgeefluik (ook als de stream zelf rechtstreeks gaat)
        b.setTextTrackStyle(textStyle())
        val ext = wantedSub as? CastSubtitle.External
        val extTrack = ext?.let { externalTrack(it) }
        if (extTrack != null) b.setMediaTracks(listOf(extTrack))
        loadedExternal = if (extTrack != null) ext?.file else null
        embeddedDone = wantedSub !is CastSubtitle.Embedded
        val rb = MediaLoadRequestData.Builder()
            .setMediaInfo(b.build())
            .setAutoplay(true)
            .setCurrentTime(if (live) 0L else positionMs.coerceAtLeast(0))
        if (extTrack != null) rb.setActiveTrackIds(longArrayOf(EXTERNAL_TRACK_ID))
        val req = rb.build()
        currentAttempt = a
        val no = ++attemptNo
        awaitingResult = true; started = false
        val pending = runCatching { c.load(req) }.getOrNull() ?: run { awaitingResult = false; return false }
        pending.setResultCallback { r ->
            if (no != attemptNo) return@setResultCallback
            awaitingResult = false
            // LOAD_FAILED / LOAD_CANCELLED of geen antwoord: volgende poging
            if (!r.status.isSuccess) { if (!next(c)) status = L("This stream can't be played on Chromecast.") } else updateStatus()
        }
        // blijft de ontvanger hangen (geen fout, maar ook geen beeld), dan na 20 s de volgende poging
        main.postDelayed({
            if (no == attemptNo && !started && client === c && attempts.isNotEmpty()) {
                awaitingResult = false
                next(c)
            }
        }, 20_000)
        return true
    }

    fun togglePause() { client?.togglePlayback() }

    // MARK: ondertitels (build 57)

    /** Goed leesbaar op een tv: witte letters met zwarte rand op een half doorzichtige balk; grootte uit de instellingen. */
    private fun textStyle(): TextTrackStyle = TextTrackStyle().apply {
        fontScale = when (SubtitleSize.current) { SubtitleSize.SMALL -> 1.0f; SubtitleSize.MEDIUM -> 1.2f; SubtitleSize.LARGE -> 1.45f }
        foregroundColor = 0xFFFFFFFF.toInt()
        backgroundColor = 0x80000000.toInt()
        edgeType = TextTrackStyle.EDGE_TYPE_OUTLINE
        edgeColor = 0xFF000000.toInt()
        windowType = TextTrackStyle.WINDOW_TYPE_NONE
        fontGenericFamily = TextTrackStyle.FONT_FAMILY_SANS_SERIF
    }

    /** Tekstspoor voor een ingeladen bestand (WebVTT via het doorgeefluik); null = geen lokaal netwerkadres. */
    private fun externalTrack(sub: CastSubtitle.External): CastTrack? {
        val url = CastRelay.subtitleUrl(file = sub.file, shiftSeconds = sub.shift) ?: return null
        val lang = sub.lang?.trim()?.replace('_', '-')?.takeIf { it.isNotEmpty() } ?: "und"
        return CastTrack.Builder(EXTERNAL_TRACK_ID, CastTrack.TYPE_TEXT)
            .setSubtype(CastTrack.SUBTYPE_SUBTITLES)
            .setContentId(url)
            .setContentType("text/vtt")
            .setName(sub.name.ifBlank { L("Subtitles") })
            .setLanguage(lang)
            .build()
    }

    /** "nld", "dut", "nl-NL" → "nl". */
    private fun baseLang(code: String?): String? {
        val c = code?.lowercase(Locale.ROOT)?.split('-', '_')?.firstOrNull()?.takeIf { it.isNotEmpty() && it != "und" } ?: return null
        if (c.length == 3) TrackPreferences.languages.firstOrNull { c in it.second }?.let { return it.first }
        return c
    }

    /** Tekstsporen die de ontvanger zelf in de stream vond (bij HLS: de ondertitels uit de playlist). */
    private fun receiverTextTracks(c: RemoteMediaClient): List<CastTrack> =
        c.mediaInfo?.mediaTracks.orEmpty().filter { it.type == CastTrack.TYPE_TEXT && it.id != EXTERNAL_TRACK_ID }

    /** Spoor op de ontvanger dat past bij het gekozen spoor uit de stream: op taal, dan op naam, dan op volgorde. */
    private fun matchEmbedded(sub: CastSubtitle.Embedded, tracks: List<CastTrack>): CastTrack? {
        if (tracks.isEmpty()) return null
        val name = sub.track.name.trim()
        tracks.firstOrNull { it.name?.trim()?.equals(name, ignoreCase = true) == true }?.let { return it }
        val lang = baseLang(sub.track.lang)
        if (lang != null) {
            val sameLang = tracks.filter { baseLang(it.language) == lang }
            if (sameLang.isNotEmpty()) return sameLang.first()
        }
        // VLC kent geen taalcode: naam als "Nederlands" / "Dutch - [Nederlands]"
        tracks.firstOrNull { t -> baseLang(t.language)?.let { TrackPreferences.matches(sub.track, it) } == true }?.let { return it }
        return if (tracks.size == sub.count) tracks.getOrNull(sub.index) else null
    }

    /** Spoor uit de stream aanzetten zodra de ontvanger zijn sporen kent; lukt het niet, één keer een melding. */
    private fun applyEmbedded(c: RemoteMediaClient) {
        if (embeddedDone) return
        val sub = wantedSub
        if (sub !is CastSubtitle.Embedded) { embeddedDone = true; return }
        if (c.mediaInfo == null || !(c.isPlaying || c.isPaused)) return
        val tracks = receiverTextTracks(c)
        val match = matchEmbedded(sub, tracks)
        if (match != null) {
            embeddedDone = true
            runCatching { c.setActiveMediaTracks(longArrayOf(match.id)) }
            return
        }
        // speelt al een paar seconden en nog geen passend spoor: deze ondertitels kan de Chromecast niet tonen
        if (tracks.isEmpty() && !embeddedGiveUpPosted) {
            embeddedGiveUpPosted = true
            val no = attemptNo
            main.postDelayed({
                embeddedGiveUpPosted = false
                if (no != attemptNo || embeddedDone || wantedSub !== sub) return@postDelayed
                val cl = client ?: return@postDelayed
                val again = matchEmbedded(sub, receiverTextTracks(cl))
                embeddedDone = true
                if (again != null) runCatching { cl.setActiveMediaTracks(longArrayOf(again.id)) }
                else PlayerController.current?.notice = L("These subtitles can't be shown on Chromecast.")
            }, 6_000)
        } else if (tracks.isNotEmpty()) {
            embeddedDone = true
            PlayerController.current?.notice = L("These subtitles can't be shown on Chromecast.")
        }
    }
    private var embeddedGiveUpPosted = false

    /**
     * De kijker koos tijdens het casten een andere ondertitel (of zette hem uit). Spoor uit de stream of uit: alleen de
     * actieve sporen wisselen. Nieuw bestand: opnieuw laden op dezelfde plek, want een tekstspoor toevoegen aan media
     * die al speelt kan de standaard-ontvanger niet.
     */
    fun subtitlesChanged(player: PlayerController) {
        val c = client ?: return
        if (!connected || PlayerController.current !== player || pendingItem?.id != player.item.id) return
        val sub = player.castSubtitle()
        wantedSub = sub
        main.post {
            if (client !== c) return@post
            when (sub) {
                null -> { embeddedDone = true; runCatching { c.setActiveMediaTracks(longArrayOf()) } }
                is CastSubtitle.Embedded -> { embeddedDone = false; applyEmbedded(c) }
                is CastSubtitle.External -> {
                    if (loadedExternal == sub.file) { embeddedDone = true; runCatching { c.setActiveMediaTracks(longArrayOf(EXTERNAL_TRACK_ID)) } }
                    else {
                        val item = pendingItem ?: return@post
                        val a = currentAttempt ?: return@post
                        val live = item.type == MediaType.LIVE
                        val pos = if (live) 0L else runCatching { c.approximateStreamPosition }.getOrDefault(pendingPosMs)
                        pendingPosMs = pos
                        load(c, item, a, pos)
                    }
                }
            }
        }
    }

    fun stop() {
        runCatching { castContext(AppState.appContext)?.sessionManager?.endCurrentSession(true) }
        detach()
    }

    fun selector(ctx: Context): MediaRouteSelector =
        castContext(ctx)?.mergedSelector ?: MediaRouteSelector.Builder()
            .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
            .build()
}

/**
 * Lijst met Chromecasts in het netwerk (eigen Compose-keuzelijst via MediaRouter; MediaRouteButton
 * vereist een FragmentActivity met AppCompat-thema, MainActivity is een ComponentActivity).
 */
@Composable
fun CastDevicesDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val router = remember { MediaRouter.getInstance(ctx) }
    val selector = remember { Casting.selector(ctx) }
    var routes by remember { mutableStateOf(listOf<MediaRouter.RouteInfo>()) }

    DisposableEffect(Unit) {
        fun refresh() {
            routes = router.routes.filter { !it.isDefault && it.isEnabled && it.matchesSelector(selector) }
                .sortedBy { it.name.lowercase() }
        }
        val cb = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) { refresh() }
            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) { refresh() }
            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) { refresh() }
        }
        router.addCallback(selector, cb, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        refresh()
        onDispose { router.removeCallback(cb) }
    }

    PlayerSheet(title = "Chromecast", onDismiss = onDismiss, doneLabel = L("Close")) {
        if (routes.isEmpty() && !Casting.connected) {
            item {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(L("Searching for Chromecasts…"), color = Theme.secondary)
                }
            }
        }
        for (r in routes) {
            item(key = r.id) {
                PlayerSheetRow(r.name, selected = r.isSelected, icon = Icons.Filled.Cast) {
                    // al verbonden met dit apparaat: alleen opnieuw casten wat er nu speelt
                    if (r.isSelected && Casting.connected) Casting.castCurrent() else router.selectRoute(r)
                    onDismiss()
                }
            }
        }
        if (Casting.connected) {
            item {
                PlayerSheetRow(L("Stop casting"), destructive = true) { Casting.stop(); onDismiss() }
            }
        }
    }
}

/** Overlay in de speler zolang er naar een Chromecast gestuurd wordt. */
@Composable
fun CastingOverlay(onStop: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.85f))
            .padding(30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icons.Filled.Cast, null, tint = Color.White, modifier = Modifier.size(48.dp))
        Text(Casting.status.ifEmpty { Casting.deviceName ?: "Chromecast" }, color = Color.White, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            NetflixButton(
                title = if (Casting.isPlaying) L("Pause") else L("Play"),
                icon = if (Casting.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            ) { Casting.togglePause() }
            NetflixButton(title = L("Stop casting"), icon = Icons.Filled.Close, kind = NetflixKind.SECONDARY) {
                Casting.stop(); onStop()
            }
        }
    }
}
