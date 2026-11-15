package nl.lrvdlinden.bingr

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.SwitchVideo
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.DesktopAccessDisabled
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Layers
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import java.text.DateFormat
import java.util.Date

private enum class PlayerPanel {
    AUDIO_SUBS, OPENSUBS, OPTIONS, CAST, SOURCES, RATE, INTRO, BACKUP,
    /** Build 173: zaplijst en afleveringen (net als Audio en ondertitels onderin over het beeld) */
    ZAP, EPISODES;
    /** Build 173: deze panelen openen onderin over het beeld (geen dialoog) en pauzeren niet */
    val isOverlay: Boolean get() = this == AUDIO_SUBS || this == ZAP || this == EPISODES
}

/** Zender → afspeelbaar item (zoals Playables.channel op iOS). */
private fun livePlayable(c: Channel): PlayableItem = PlayableItem(
    id = c.id, title = c.name, subtitle = Playlists.nowOf(c)?.title, url = c.streamURL,
    type = MediaType.LIVE, artwork = c.logo, stalkerCmd = c.stalkerCmd, channelID = c.id, sourceID = c.sourceID,
)

/**
 * Volledige speler: video, eigen bediening (tik/D-pad), timeline, ±10 s, zappen bij live,
 * timeshift/catch-up, panelen voor audio/ondertitels/OpenSubtitles/opties/bronnen, Chromecast en beeld-in-beeld.
 */
@Composable
fun PlayerScreen(item: PlayableItem, onClose: () -> Unit) {
    val player = remember(item.id) { PlayerController(item) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val isTV = AppState.isTV
    val inPiP = AppState.inPiP

    var showControls by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf<PlayerPanel?>(null) }
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var toastID by remember { mutableIntStateOf(0) }
    var pokeID by remember { mutableIntStateOf(0) }
    var stremioChoices by remember { mutableStateOf(listOf<StremioStream>()) }
    /** Volgende aflevering (series): knop aan het eind en automatisch verder kijken */
    var nextUp by remember { mutableStateOf<PlayableItem?>(null) }
    val rootFocus = remember { FocusRequester() }
    val firstButton = remember { FocusRequester() }
    val retryButton = remember { FocusRequester() }
    /** Heeft het spelerscherm zelf de focus (bediening verborgen), of een knop erin? */
    var rootFocused by remember { mutableStateOf(false) }
    val swallowUp = remember { mutableSetOf<Int>() }
    val castAvailable = remember { Casting.isAvailable(ctx) }
    /** Zenderbanner bij zappen (build 51) */
    var zapBanner by remember { mutableStateOf<Channel?>(null) }
    var zapBannerID by remember { mutableIntStateOf(0) }
    var zapJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val externalTarget = ExternalDisplay.target
    /** Andere bronnen (build 52): zelfde zender/film op de andere servers; null = nog niet gezocht */
    var backupHits by remember { mutableStateOf<List<BackupSources.Hit>?>(null) }
    var backupFor by remember { mutableStateOf<String?>(null) }
    var backupLoading by remember { mutableStateOf(false) }
    /** Aftiteling: volgende-aflevering-kaart weggeklikt voor deze aflevering */
    var creditsDismissedFor by remember { mutableStateOf<String?>(null) }
    var countdown by remember { mutableIntStateOf(10) }
    /** Andere taalversies van wat er speelt (build 59), voor het audiomenu */
    var langVersions by remember { mutableStateOf(listOf<ContentLanguage.Version>()) }
    val skipFocus = remember { FocusRequester() }

    // build 176: helderheid terugzetten bij het sluiten van de speler
    DisposableEffect(Unit) { onDispose { SwipeControls.restoreBrightness() } }
    // build 171: Kijk je nog?
    val still = remember { StillWatchingState() }
    var autoNext by remember { mutableStateOf(false) }
    // build 171: bediening vergrendeld (telefoon/tablet)
    var locked by remember { mutableStateOf(false) }
    // build 173: vorige zender, dubbeltikken, vraag vanaf het begin, slaaptimer, afleveringen
    var prevChannel by remember { mutableStateOf(Build173PlayerLogic.PreviousChannel()) }
    var doubleTap by remember { mutableStateOf(Build173PlayerLogic.DoubleTap()) }
    var tapBadge by remember { mutableStateOf<Int?>(null) }
    var tapBadgeID by remember { mutableIntStateOf(0) }
    var startOverPrompt by remember { mutableStateOf<EPGProgram?>(null) }
    var sleepStopped by remember { mutableStateOf(false) }
    var catchupOK by remember { mutableStateOf(false) }
    var episodeSeries by remember { mutableStateOf<Series?>(null) }
    var episodeList by remember { mutableStateOf(listOf<Episode>()) }
    fun flash(text: String) { toast = text; toastID++ }
    fun poke() { showControls = true; pokeID++ }
    fun toggleControls() { showControls = !showControls; if (showControls) poke() }
    fun close() { player.dismissed = true; player.stop(); onClose() }
    /** Build 176: "Kijk je nog?" staat open of is aan de beurt; dan start er niets vanzelf. */
    fun stillDue(): Boolean = still.askedAt != null || still.paused ||
        Build171Logic.StillWatching.ask(still.state, System.currentTimeMillis(), StillWatchingPrefs.enabled, StillWatchingPrefs.hours,
            Build171Logic.StillWatching.Kind.EPISODE) != null

    // vooruit gedeclareerd: bind() verwijst naar playNext(), dat start() aanroept
    var playNextRef: (PlayableItem) -> Unit = {}
    var sleepNowRef: () -> Unit = {}

    fun bind() {
        player.onFinished = {
            val cur = player.item
            val n = nextUp
            when {
                // zoals Netflix: gewoon door naar de volgende aflevering; build 176: niet als het profiel dat uit heeft
                // (of "Kijk je nog?" aan de beurt is): dan blijft de knop Volgende aflevering staan
                n != null && AutoplayNext.start(stillDue()) -> { autoNext = true; playNextRef(n) }
                n != null -> poke()
                Trakt.askRating && Trakt.isLoggedIn && cur.imdbID != null && cur.type != MediaType.LIVE -> { panel = PlayerPanel.RATE }
                else -> close()
            }
            Unit
        }
        // build 173: slaaptimer afgegaan → pauzeren, stream_stop en "Slaaptimer: gestopt"
        player.onSleep = { sleepNowRef() }
    }

    /** Zoekt de aflevering na deze (ook over seizoenen heen). */
    suspend fun findNext(itm: PlayableItem) {
        nextUp = null
        if (itm.type != MediaType.EPISODE) return
        val lib = Playlists.library
        val series = itm.seriesID?.let { sid -> lib.series.firstOrNull { it.id == sid } }
            ?: lib.series.firstOrNull { s -> s.episodes.any { it.id == itm.id } }
            ?: lib.series.firstOrNull { it.title == itm.title }
            ?: return
        val eps = runCatching { Playlists.episodes(series) }.getOrDefault(emptyList())
            .sortedWith(compareBy<Episode>({ it.season }, { it.number }))
        if (player.dismissed || player.item.id != itm.id) return
        val i = eps.indexOfFirst { it.id == itm.id }
        if (i < 0 || i + 1 >= eps.size) return
        nextUp = Playables.episode(eps[i + 1], series, itm.imdbID).copy(seriesID = series.id)
    }

    /** Andere bronnen voor wat nu speelt (één keer per zender/titel gezocht). */
    suspend fun loadBackups(): List<BackupSources.Hit> {
        val itm = player.item
        if (backupFor == itm.id) backupHits?.let { return it }
        backupLoading = true
        val hits = try {
            when {
                itm.type == MediaType.LIVE -> {
                    val c = Playlists.library.channels.firstOrNull { it.id == itm.channelID }
                        ?: Playlists.rawLibrary.channels.firstOrNull { it.id == itm.channelID }
                    if (c != null) BackupSources.channels(c) else emptyList()
                }
                itm.type == MediaType.MOVIE -> BackupSources.movies(itm.title, itm.subtitle?.toIntOrNull())
                else -> emptyList()
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { emptyList() }
        finally { backupLoading = false }
        if (player.item.id == itm.id) { backupHits = hits; backupFor = itm.id }
        return hits
    }

    /** Andere bron kiezen (automatisch bij een storing of met de hand). */
    suspend fun switchTo(h: BackupSources.Hit): Boolean {
        val itm = player.item
        val u = BackupSources.resolve(h, live = itm.type == MediaType.LIVE) ?: return false
        if (player.dismissed || player.item.id != itm.id) return false
        player.playAlternative(u)
        flash(L("Switched to %@", h.source.name))
        return true
    }

    /**
     * Nieuwe titel terwijl er een Chromecast verbonden is: daarheen sturen. Bij films/afleveringen eerst wachten tot de
     * lokale speler het hervatpunt kent (duur bekend), zodat de Chromecast op dezelfde plek begint.
     */
    suspend fun followCast(itm: PlayableItem) {
        if (itm.type != MediaType.LIVE) {
            withTimeoutOrNull(8_000) { snapshotFlow { player.duration > 0 || player.errorText != null }.first { it } }
        }
        if (player.dismissed || player.item.id != itm.id || !Casting.connected) return
        Casting.castCurrent()
    }

    /**
     * Build 173: programma al bezig en terugkijken kan: vragen "Vanaf het begin" of "Live kijken" (live speelt alvast;
     * zonder antwoord na 12 s blijft het live). Zonder terugkijken geen vraag.
     */
    fun askStartOverIfRunning() {
        if (!player.isLive || !catchupOK) return
        val p = currentProgram173(player) ?: return
        if (!Build173PlayerLogic.askStartOver(true, p.start, p.end, System.currentTimeMillis())) return
        startOverPrompt = p
        scope.launch {
            delay(12_000)
            if (startOverPrompt?.id == p.id) startOverPrompt = null
        }
    }

    suspend fun start(itm: PlayableItem, askStartOver: Boolean = false) {
        if (player.dismissed) return
        startOverPrompt = null
        StartTiming.ensure(itm.title)
        StartTiming.mark("player screen")
        player.prepareNew()
        player.item = itm
        PlayerController.current = player
        bind()
        nextUp = null
        player.creditsStart = IntroMarkers.get(IntroMarkers.keyFor(itm))?.creditsStart
        // limiet van de IPTV-provider (alleen Xtream; bij M3U e.d. null): vóór het afspelen alvast ophalen
        // (blokkeert niets), en bij een mislukte stream opnieuw controleren voor de juiste foutmelding
        val providerSrc = Playlists.source
        scope.launch { runCatching { ProviderLimit.prime(providerSrc) } }
        player.providerLimitCheck = { ProviderLimit.failureMessage(providerSrc) }
        // live-zender werkt niet (ook niet na een stille nieuwe poging): automatisch naar een andere server
        player.onLiveFailure = {
            scope.launch {
                val forID = player.item.id
                val hits = loadBackups()
                var ok = false
                for (h in hits.take(3)) { if (switchTo(h)) { ok = true; break } }
                // intussen gezapt of gesloten: niets meer doen
                if (!ok && !player.dismissed && player.item.id == forID) player.showFailure()
            }
        }
        val cid = itm.channelID
        // laatst bekeken zender van dit profiel (ook vanuit Home, de gids en zoeken; voor "Start bij laatste zender")
        if (itm.type == MediaType.LIVE && cid != null && !itm.id.contains("-cu-")) {
            (itm.sourceID ?: Playlists.source?.id)?.let { Store.pushRecentChannel(it, cid) }
        }
        player.catchupProvider =
            // build 128: zender van een andere server: terugkijken via die server
            if (itm.type == MediaType.LIVE && cid != null) ({ from: Long, to: Long -> Playlists.catchupURL(cid, from, to, itm.sourceID) }) else null
        if (itm.stremio != null) {
            val found = runCatching { Playlists.stremioStreams(itm) }.getOrDefault(emptyList())
            // intussen gesloten of al naar een andere zender/aflevering → niet meer starten
            if (player.dismissed || player.item.id != itm.id) return
            stremioChoices = found
            val first = found.firstOrNull()
            StartTiming.mark("stremio streams (${found.size})")
            if (first == null) { player.fail(L("No playable streams found for this title.")); StartTiming.end("no stream"); return }
            player.start(first.url)
        } else {
            stremioChoices = emptyList()
            val url = runCatching { Playlists.resolve(itm) }.getOrNull()
            if (player.dismissed || player.item.id != itm.id) return
            StartTiming.mark("url resolved")
            if (url == null) { player.fail(L("No stream address available.")); StartTiming.end("no stream"); return }
            player.liveURL = url
            player.start(url)
        }
        // build 173: terugkijken mogelijk (knop Vanaf het begin), vorige zender bijhouden, vraag bij een lopend programma
        catchupOK = player.hasCatchup
        if (itm.type == MediaType.LIVE && cid != null && !itm.id.contains("-cu-")) prevChannel = prevChannel.tuned(cid)
        if (askStartOver) askStartOverIfRunning()
        // Chromecast pas na het starten koppelen (build 59): de eerste keer kost CastContext een paar honderd ms
        // op de main thread, en dat zat vóór het laden van de stream
        Casting.init(ctx)
        // al verbonden met een Chromecast (bijv. volgende aflevering of andere zender): meteen daar verder
        if (Casting.connected) scope.launch { followCast(itm) }
        poke()
        // automatische ondertitels regelt PlayerController zelf zodra de sporen bekend zijn (build 59)
        scope.launch { findNext(itm) }
    }

    /**
     * Andere taalversie van wat er speelt (build 59), op dezelfde plek. Elke versie houdt zijn eigen voortgang
     * (eigen item-id). Bij series dezelfde aflevering in de andere versie.
     */
    fun switchVersion(v: ContentLanguage.Version) {
        val cur = player.item
        val at = player.currentTime
        val frac = player.position
        scope.launch {
            val next: PlayableItem? = when (val t = v.target) {
                is DetailTarget.MovieT -> Playables.movie(t.movie, Trakt.imdb(t.movie) ?: cur.imdbID)
                is DetailTarget.SeriesT -> {
                    val eps = runCatching { Playlists.episodes(t.series) }.getOrDefault(emptyList())
                    eps.firstOrNull { it.season == cur.season && it.number == cur.episode }
                        ?.let { e -> Playables.episode(e, t.series, cur.imdbID).copy(seriesID = t.series.id) }
                }
            }
            if (next == null) { flash(L("This episode isn't available in that language.")); return@launch }
            if (player.dismissed) return@launch
            StartTiming.begin(next.title)
            player.stop()
            player.pendingStartSeconds = at.takeIf { it > 5 }
            flash(L("Switched to %@", v.info?.longName ?: ContentLanguage.displayTitle(next.title)))
            start(next.copy(resumeAt = if (frac in 0.01..0.97) frac else next.resumeAt))
        }
    }

    fun playNext(n: PlayableItem) {
        sleepStopped = false
        // build 171: vanzelf of met Volgende aflevering = telt voor "Kijk je nog?"
        still.autoStarted(viaButton = !autoNext); autoNext = false
        nextUp = null
        panel = null
        flash(n.displaySubtitle ?: n.displayTitle)
        scope.launch { player.stop(); start(n) }
    }
    playNextRef = { n -> playNext(n) }

    /**
     * Zappen binnen de huidige groep (build 51): de zenderbanner volgt meteen; met "Zap delay" start de stream pas
     * als er even niet meer gezapt is (snel doorzappen werkt dan alleen de banner bij).
     */
    fun zap(step: Int) {
        val list = AppState.liveZapList
        if (!player.isLive || list.isEmpty()) return
        val cur = zapBanner?.id ?: player.item.channelID ?: player.item.id
        val i = list.indexOfFirst { it.id == cur }
        if (i < 0) return
        val next = list[((i + step) % list.size + list.size) % list.size]
        zapBanner = next
        zapBannerID++
        zapJob?.cancel()
        val wait = PlayerSettings.zapDelay.toLong()
        zapJob = scope.launch {
            if (wait > 0) delay(wait)
            // build 176: vergrendelde zender: eerst de pincode; Annuleren = blijven op de huidige zender
            Locks.guardChannel(next) {
                scope.launch {
                    (next.sourceID ?: Playlists.source?.id)?.let { Store.pushRecentChannel(it, next.id) }
                    player.stop()
                    start(livePlayable(next))
                }
            }
            if (Locks.request != null) zapBanner = null
        }
    }

    /** Build 173: direct naar een zender (zaplijst, Vorige zender), zonder de speler te verlaten. */
    fun tune(c: Channel) {
        if (c.id == player.item.channelID) return
        still.touch()
        // build 176: vergrendelde zender: eerst de pincode
        Locks.guardChannel(c) {
            zapJob?.cancel()
            zapBanner = c
            zapBannerID++
            (c.sourceID ?: Playlists.source?.id)?.let { Store.pushRecentChannel(it, c.id) }
            zapJob = scope.launch { player.stop(); start(livePlayable(c)) }
        }
    }

    /** Build 173: één tik terug naar de vorige zender (en weer terug). */
    /** Build 176: moment bewaren op de huidige tijd (sleutel = die van Verder kijken, `bookmarks` in het profiel). */
    fun saveMoment() {
        val it = player.item
        val sid = it.sourceID ?: Store.activePlaylistID ?: Playlists.source?.id ?: return
        val title = if (it.type == MediaType.EPISODE && it.season != null && it.episode != null)
            "${it.displayTitle} · S${it.season}:E${it.episode}" else it.displayTitle
        Store.addBookmark(sid, it.id, title, player.currentTime)
        flash(L("Moment saved"))
    }

    fun previousChannel() {
        val id = prevChannel.previous ?: return
        val c = Playlists.library.channels.firstOrNull { it.id == id } ?: return
        tune(c)
    }

    /** Build 173: zaplijst: favorieten, dan de huidige categorie, dan de rest (verborgen zenders en kinderprofiel: al gefilterd). */
    fun zapChannels(): List<Channel> {
        val all = Playlists.library.channels.filter { !Radio.isRadio(it) }.distinctBy { it.id }
        val pid = Playlists.source?.id ?: ""
        val group = all.firstOrNull { it.id == player.item.channelID }?.group
        val fav = all.map { Store.isFavorite(it.sourceID ?: pid, it.id) }
        return Build173PlayerLogic.zapOrder(all.map { it.group }, fav, group).map { all[it] }
    }

    /** Build 173: Vanaf het begin van het lopende programma (terugkijken van de server; daarna Terug naar live). */
    fun startOver173() {
        startOverPrompt = null
        val p = currentProgram173(player) ?: return
        if (player.startOver(p.start)) flash(L("From the start"))
        poke()
    }

    /** Build 173: afleveringen van deze serie ophalen en het paneel openen (de speler blijft open). */
    fun openEpisodes() {
        panel = PlayerPanel.EPISODES
        val itm = player.item
        if (episodeSeries != null && episodeSeries?.id == itm.seriesID && episodeList.isNotEmpty()) return
        val lib = Playlists.library
        val series = itm.seriesID?.let { sid -> lib.series.firstOrNull { it.id == sid } }
            ?: lib.series.firstOrNull { s -> s.episodes.any { it.id == itm.id } }
            ?: lib.series.firstOrNull { it.title == itm.title }
            ?: run { flash(L("No episodes")); panel = null; return }
        scope.launch {
            val eps = runCatching { Playlists.episodes(series) }.getOrDefault(emptyList())
                .sortedWith(compareBy<Episode>({ it.season }, { it.number }))
            episodeList = eps
            episodeSeries = series
        }
    }

    /** Build 173: dubbeltik = 10 s; vaker tikken telt op (20, 30 s) met een kleine animatie. */
    fun doubleTapSeek(forward: Boolean, taps: Int) {
        if (locked) return
        if (player.isLive && !player.canTimeshift) { toggleControls(); return }
        still.touch()
        val now = System.currentTimeMillis()
        val n = if (taps == 2 && doubleTap.active(now)) 2 else 1
        var d = doubleTap
        var step = 0
        repeat(n) {
            val before = if (d.active(now) && d.forward == forward) d.total else 0
            d = d.tap(forward, now)
            step += d.total - before
        }
        doubleTap = d
        player.seek(step.toDouble())
        tapBadge = d.total
        tapBadgeID++
    }

    /** Build 173: slaaptimer afgegaan: pauzeren, de stream afmelden (stream_stop, zoals bij "Kijk je nog?") en het scherm. */
    fun sleepNow() {
        panel = null
        showControls = false
        startOverPrompt = null
        player.pauseForStillWatching()
        sleepStopped = true
    }
    sleepNowRef = { sleepNow() }

    fun continueAfterSleep() {
        sleepStopped = false
        if (player.item.type != MediaType.LIVE && player.position >= 0.999) {
            val n = nextUp
            if (n != null) playNext(n) else close()
            return
        }
        player.resumeAfterStillWatching()
        poke()
    }

    fun openMultiview() {
        val current = Playlists.library.channels.firstOrNull { it.id == player.item.channelID }
        val zapList = AppState.liveZapList
        close()
        AppScope.launch {
            delay(400)
            AppState.openMultiview(listOfNotNull(current), zapList)
        }
    }

    fun seekBy(s: Double) {
        if (player.isLive && !player.canTimeshift) { poke(); return }
        player.seek(s)
        flash(if (s < 0) "−${(-s).toInt()} s" else "+${s.toInt()} s")
    }

    // MARK: levenscyclus

    LaunchedEffect(player) {
        ChannelPreview.stop()
        // leeftijdscontrole van de detailpagina (kinderprofiel, build 59) loopt tegelijk met het voorbereiden
        AppState.playingAgeCheck?.let { check ->
            AppState.playingAgeCheck = null
            player.ageGate = scope.async { try { check() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { false } }
        }
        start(item, askStartOver = true)
    }
    // build 164: stilstaand beeld voor de tv-gids: elke minuut een kleine momentopname van de live zender (alleen ExoPlayer)
    LaunchedEffect(player) {
        delay(8_000)
        while (true) {
            val itm = player.item
            val cid = itm.channelID
            val eng = player.engine
            if (itm.type == MediaType.LIVE && cid != null && !itm.id.contains("-cu-") && eng is ExoEngine && eng.isPlaying) {
                ChannelStills.capture(eng.view, Build164Logic.Stills.key(itm.sourceID ?: Playlists.source?.id, cid))
            }
            delay(Build164Logic.Stills.EVERY_MS)
        }
    }
    DisposableEffect(player) {
        val obs = LifecycleEventObserver { _, e ->
            // app naar de achtergrond zonder beeld-in-beeld of Chromecast (bijv. TV-thuisknop): de stream echt stoppen,
            // zodat de providerverbinding vrijkomt (pauzeren hield hem open); terug = verder op dezelfde plek
            if (e == Lifecycle.Event.ON_STOP && !AppState.inPiP && !Casting.connected) player.suspendForBackground()
            if (e == Lifecycle.Event.ON_START && !still.paused && !sleepStopped) player.resumeFromBackground()
        }
        val lc = ProcessLifecycleOwner.get().lifecycle
        lc.addObserver(obs)
        onDispose {
            lc.removeObserver(obs)
            player.dismissed = true
            player.stop()
            if (PlayerController.current === player) PlayerController.current = null
            MainActivity.current?.keepScreenOn(false)
            MainActivity.current?.updatePiP(false)   // build 97: geen automatisch beeld-in-beeld meer na de speler
            FrameRateMatcher.reset((player.engine as? ExoEngine)?.let { FrameRateMatcher.surfaceOf(it.view) })
        }
    }
    LaunchedEffect(zapBannerID) {
        if (zapBanner != null) { delay(PlayerSettings.zapDelay.toLong() + 3_000); zapBanner = null }
    }
    LaunchedEffect(player.isPlaying) {
        MainActivity.current?.keepScreenOn(player.isPlaying)
        // build 97: knop afspelen/pauzeren in het PiP-venster en automatisch PiP (Android 12+) volgen de afspeelstand
        MainActivity.current?.updatePiP(player.isPlaying)
    }
    LaunchedEffect(pokeID, panel, player.isPlaying, showControls) {
        if (showControls) {
            delay(6000)   // 6 s niets gedaan → bediening weg (rondkijken met de D-pad houdt hem zichtbaar)
            if (panel == null && player.isPlaying && scrubbing == null) showControls = false
        }
    }
    // build 169: bediening weg op TV = voorbeeldpositie van de tijdbalk vervalt (niet spoelen)
    LaunchedEffect(showControls) { if (isTV && !showControls) scrubbing = null }
    LaunchedEffect(tapBadgeID) {
        if (tapBadge != null) { delay(900); tapBadge = null }
    }
    LaunchedEffect(toastID) {
        if (toast != null) { delay(1800); toast = null }
    }
    LaunchedEffect(player.notice) {
        player.notice?.let { flash(it); player.notice = null }
    }
    // andere taalversies van deze titel (build 59): de film zelf of de serie van deze aflevering
    LaunchedEffect(player.item.id, player.item.seriesID) {
        val itm = player.item
        val lib = Playlists.library
        val t: DetailTarget? = when (itm.type) {
            MediaType.MOVIE -> lib.movies.firstOrNull { it.id == itm.id }?.let { DetailTarget.MovieT(it) }
            MediaType.EPISODE -> (itm.seriesID?.let { id -> lib.series.firstOrNull { it.id == id } }
                ?: lib.series.firstOrNull { it.title == itm.title })?.let { DetailTarget.SeriesT(it) }
            else -> null
        }
        langVersions = t?.let { runCatching { ContentLanguage.versions(it) }.getOrNull() } ?: emptyList()
    }
    // intro en aftiteling (build 52)
    // build 60: één keer per titel opzoeken (zonder serie-id doorzoekt dit de hele bibliotheek), niet bij elke tik
    val markerKey = remember(player.item.id, player.item.seriesID, Playlists.source?.id) { IntroMarkers.keyFor(player.item) }
    val marker = IntroMarkers.get(markerKey)
    val introRange = if (!player.isLive && player.errorText == null) marker?.intro else null
    val showSkip = introRange != null && player.currentTime >= introRange.start && player.currentTime < introRange.endInclusive - 1 &&
        !inPiP && panel == null
    val creditsAt = marker?.creditsStart
    val showCredits = creditsAt != null && nextUp != null && player.errorText == null && !player.isLive &&
        player.currentTime >= creditsAt && creditsDismissedFor != player.item.id && !inPiP && panel == null
    LaunchedEffect(markerKey, creditsAt) { player.creditsStart = creditsAt }

    val showNext = nextUp != null && player.errorText == null && player.duration > 120 && !showCredits &&
        (player.duration - player.currentTime < 45 || (creditsAt != null && player.currentTime >= creditsAt))

    // build 173: wat er nu op is (live), Vanaf het begin, en de belangrijkste knop van dit moment
    val currentProgram = currentProgram173(player)
    val canStartOver = player.errorText == null &&
        Build173PlayerLogic.canStartOver(catchupOK, currentProgram?.start, System.currentTimeMillis(), player.behindLive)
    val primary = if (player.errorText != null) null else
        Build173PlayerLogic.primary(inIntro = showSkip, nextEpisode = showNext || showCredits, isLive = player.isLive,
            canStartOver = canStartOver, behindLiveSec = player.behindLive)
    fun runPrimary(p: Build173PlayerLogic.Primary) {
        when (p) {
            Build173PlayerLogic.Primary.SKIP_INTRO -> introRange?.let { r -> player.seek(r.endInclusive - player.currentTime) }
            Build173PlayerLogic.Primary.NEXT_EPISODE -> nextUp?.let { playNext(it) }
            Build173PlayerLogic.Primary.START_OVER -> startOver173()
            Build173PlayerLogic.Primary.GO_LIVE -> { player.goLive(); flash(L("Back to live")) }
        }
        poke()
    }
    val overlayOpen = panel?.isOverlay == true

    // bediening tevoorschijn → focus op afspelen/pauze; weg → focus terug naar het scherm zelf (vangt de D-pad op).
    // Fout zonder bediening (TV) → focus op "Try again".
    val hasError = player.errorText != null
    val skipVisible = showSkip || showCredits
    LaunchedEffect(showControls, panel, inPiP, hasError, skipVisible, primary != null, sleepStopped) {
        if (panel != null || inPiP || sleepStopped) return@LaunchedEffect
        delay(60)
        runCatching {
            when {
                isTV && showControls -> firstButton.requestFocus()
                isTV && hasError -> retryButton.requestFocus()
                isTV && skipVisible -> skipFocus.requestFocus()
                else -> rootFocus.requestFocus()
            }
        }
    }
    // aftiteling: aftellen en dan de volgende aflevering
    // build 176: automatisch volgende aflevering uit (per profiel): de kaart blijft, zonder aftellen
    val autoplayOn = AutoplayNext.enabled
    LaunchedEffect(showCredits, player.item.id, autoplayOn) {
        if (!showCredits || !autoplayOn) return@LaunchedEffect
        countdown = 10
        while (countdown > 0) {
            delay(1000)
            if (player.isPlaying) countdown--
        }
        // build 173: slaaptimer "na deze aflevering": niet door naar de volgende
        if (player.sleep == Build173PlayerLogic.Sleep.EndOfEpisode) { player.setSleep(Build173PlayerLogic.Sleep.Off); sleepNow(); return@LaunchedEffect }
        if (!AutoplayNext.start(stillDue())) return@LaunchedEffect
        nextUp?.let { autoNext = true; playNext(it) }
    }

    BackHandler {
        when {
            sleepStopped -> close()
            panel != null -> panel = null
            startOverPrompt != null -> startOverPrompt = null
            // build 169: Terug zet de voorbeeldpositie van de tijdbalk terug zonder te spoelen
            isTV && scrubbing != null -> { scrubbing = null; poke() }
            isTV && showControls -> showControls = false
            else -> close()
        }
    }

    // MARK: afstandsbediening

    fun handleKey(code: Int, down: Boolean, repeat: Int): Boolean {
        if (!down) return swallowUp.remove(code)
        // build 171: elke toets is bediening; met de vraag of het pauzescherm open: alleen de knoppen daar (Afspelen = verder)
        still.touch()
        if (still.askedAt != null || still.paused) {
            if (still.paused && (code == AndroidKeyEvent.KEYCODE_MEDIA_PLAY || code == AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
                still.answered(); player.resumeAfterStillWatching(); swallowUp += code; return true
            }
            return false
        }
        if (panel != null || sleepStopped || startOverPrompt != null) return false
        var handled = true
        when (code) {
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                // build 169: met een voorbeeldpositie op de tijdbalk springt Afspelen/Pauze erheen (zoals OK)
                val pv = scrubbing
                if (isTV && pv != null) { player.seekTo(pv.toDouble()); scrubbing = null } else player.togglePlay()
                poke()
            }
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> { player.resume(); poke() }
            AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); poke() }
            AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekBy(10.0)
            AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> seekBy(-10.0)
            AndroidKeyEvent.KEYCODE_CHANNEL_UP -> zap(-1)
            AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> zap(1)
            AndroidKeyEvent.KEYCODE_MEDIA_NEXT -> { val n = nextUp; if (n != null) playNext(n) else if (player.isLive) zap(1) else handled = false }
            AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS -> { if (player.isLive) zap(-1) else handled = false }
            // build 173: vaste toets "vorige zender" op de afstandsbediening
            AndroidKeyEvent.KEYCODE_LAST_CHANNEL -> { if (player.isLive && prevChannel.previous != null) previousChannel() else handled = false }
            else -> handled = false
        }
        if (handled) { swallowUp += code; return true }
        // bediening zichtbaar: de D-pad navigeert door de balk (focus-systeem), elke toets houdt hem zichtbaar
        if (showControls) { poke(); return false }
        // een knop buiten de balk heeft de focus (bijv. "Try again"): die handelt de toets zelf af
        if (!rootFocused) return false
        // bediening verborgen: OK = tonen · ←/→ = 10 s spoelen · ↑/↓ = zappen (live) of tonen
        when (code) {
            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> seekBy(-10.0)
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> seekBy(10.0)
            AndroidKeyEvent.KEYCODE_DPAD_UP -> if (repeat == 0) { if (player.isLive) zap(-1) else poke() }
            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> if (repeat == 0) { if (player.isLive) zap(1) else poke() }
            AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> poke()
            else -> return false
        }
        swallowUp += code
        return true
    }

    // MARK: opbouw


    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { ev ->
                val down = ev.type == KeyEventType.KeyDown
                if (!down && ev.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                handleKey(ev.nativeKeyEvent.keyCode, down, ev.nativeKeyEvent.repeatCount)
            }
            .onFocusChanged { rootFocused = it.isFocused }
            .focusRequester(rootFocus)
            .focusable()
            .stillWatchingTouches(still)
    ) {
        if (externalTarget != null && !inPiP) {
            // video op het externe scherm, bediening hier
            ExternalPresentation(player, externalTarget)
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Tv, null, tint = Theme.secondary, modifier = Modifier.size(tvs(56)))
                Spacer(Modifier.height(10.dp))
                Text(L("Playing on the external display"), color = Color.White, fontSize = tsp(15), fontWeight = FontWeight.SemiBold)
                Text(externalTarget.name ?: "", color = Theme.secondary, fontSize = tsp(12))
            }
        } else EngineView(player, Modifier.fillMaxSize())
        // kinderprofiel (build 59): geen beeld zolang de leeftijdscontrole nog loopt (ExoPlayer buffert al)
        if (player.holdingForAge) Box(Modifier.fillMaxSize().background(Color.Black))

        if (!inPiP && !isTV) {
            // vegen omhoog/omlaag = zappen binnen de groep (live)
            Box(
                Modifier.fillMaxSize().pointerInput(player) {
                    var dy = 0f
                    // build 176: linker derde = helderheid, rechter derde = volume, midden = zappen (live); niet als de
                    // bediening vergrendeld is
                    var side = Build176Logic.Swipe.Side.MIDDLE
                    var start = 0f
                    detectVerticalDragGestures(
                        onDragStart = { o ->
                            dy = 0f
                            side = if (locked) Build176Logic.Swipe.Side.MIDDLE else Build176Logic.Swipe.side(o.x, size.width.toFloat())
                            start = when (side) {
                                Build176Logic.Swipe.Side.BRIGHTNESS -> SwipeControls.brightness()
                                Build176Logic.Swipe.Side.VOLUME -> SwipeControls.volume()
                                Build176Logic.Swipe.Side.MIDDLE -> 0f
                            }
                        },
                        onDragEnd = {
                            val min = 60.dp.toPx()
                            if (side == Build176Logic.Swipe.Side.MIDDLE && !locked && player.isLive && kotlin.math.abs(dy) > min) zap(if (dy < 0) 1 else -1)
                            SwipeControls.endSoon()
                        },
                        onDragCancel = { SwipeControls.endSoon() },
                        onVerticalDrag = { change, amount ->
                            dy += amount; change.consume()
                            if (side != Build176Logic.Swipe.Side.MIDDLE) {
                                val v = Build176Logic.Swipe.apply(start, dy, size.height.toFloat())
                                if (side == Build176Logic.Swipe.Side.BRIGHTNESS) SwipeControls.setBrightness(v) else SwipeControls.setVolume(v)
                            }
                        }
                    )
                }.pointerInput(player) {
                    // build 173: dubbeltik links/rechts = 10 s; vaker tikken telt op (20, 30 s) met een kleine animatie
                    detectTapGestures(
                        onTap = { off ->
                            val w = size.width
                            val side = when { off.x < w * 0.35f -> false; off.x > w * 0.65f -> true; else -> null }
                            if (side != null && doubleTap.active(System.currentTimeMillis()) && doubleTap.forward == side) doubleTapSeek(side, 1)
                            else toggleControls()
                        },
                        onDoubleTap = { off ->
                            val w = size.width
                            when {
                                off.x < w * 0.35f -> doubleTapSeek(false, 2)
                                off.x > w * 0.65f -> doubleTapSeek(true, 2)
                                else -> toggleControls()
                            }
                        }
                    )
                }
            )
        }

        player.subtitleText?.let { s ->
            Box(
                Modifier.fillMaxSize().padding(
                    start = 24.dp, end = 24.dp,
                    bottom = if (inPiP) 8.dp else if (showControls) tvs(120) else tvs(40)
                ),
                contentAlignment = Alignment.BottomCenter
            ) {
                Text(
                    s, color = Color.White, fontSize = (if (inPiP) tsp(11) else tsp(20)) * SubtitleSize.current.overlayFactor,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        if (!inPiP) {
            if ((player.isBuffering || player.reconnecting) && player.errorText == null) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(tvs(48)), strokeWidth = 3.dp)
                    if (player.reconnecting) {
                        Spacer(Modifier.height(10.dp))
                        Text(L("Reconnecting…"), color = Theme.secondary, fontSize = tsp(13))
                    }
                }
            }
            player.errorText?.let { e ->
                Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Filled.Warning, null, tint = Theme.red, modifier = Modifier.size(tvs(40)))
                    Text(e, color = Color.White, fontSize = tsp(16), textAlign = TextAlign.Center)
                    // slim foutherstel (build 52): opnieuw, andere bron of andere speler (positie blijft bewaard)
                    Row(horizontalArrangement = Arrangement.spacedBy(tvs(12)), verticalAlignment = Alignment.CenterVertically) {
                        NetflixButton(L("Retry"), Icons.Filled.Refresh, modifier = Modifier.focusRequester(retryButton)) {
                            if (player.url != null) player.retry() else scope.launch { start(player.item) }
                        }
                        val itmType = player.item.type
                        if (player.url != null && (itmType == MediaType.LIVE || itmType == MediaType.MOVIE || stremioChoices.size > 1)) {
                            NetflixButton(L("Other source"), BingrIcons.otherSources, kind = NetflixKind.SECONDARY) {
                                panel = if (itmType != MediaType.LIVE && stremioChoices.size > 1) PlayerPanel.SOURCES else PlayerPanel.BACKUP
                            }
                        }
                        if (player.canSwitchEngine) {
                            NetflixButton(L("Other player"), Icons.Filled.SwitchVideo, kind = NetflixKind.SECONDARY) { player.switchEngine() }
                        }
                    }
                }
            }
            if (Casting.connected) {
                Box(Modifier.align(Alignment.Center)) { CastingOverlay { player.resume() } }
            }

            AnimatedVisibility(showControls && !overlayOpen && !sleepStopped, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                PlayerControls(
                    player = player,
                    isTV = isTV,
                    castAvailable = castAvailable,
                    firstButton = firstButton,
                    scrubbing = scrubbing,
                    hasSources = stremioChoices.isNotEmpty(),
                    showNext = showNext,
                    onNext = { nextUp?.let { playNext(it) } },
                    onScrub = { scrubbing = it; poke() },
                    onScrubEnd = { scrubbing?.let { player.seekTo(it.toDouble()) }; scrubbing = null; poke() },
                    onScrubCancel = { scrubbing = null; poke() },
                    onClose = { close() },
                    onPoke = { poke() },
                    onSeek = { seekBy(it) },
                    onZap = { zap(it) },
                    onPanel = { panel = it },
                    onMultiview = { openMultiview() },
                    canMarkIntro = player.item.type == MediaType.EPISODE && markerKey != null,
                    primary = primary,
                    onPrimary = { runPrimary(it) },
                    currentProgram = currentProgram,
                    canStartOver = canStartOver,
                    onStartOver = { startOver173() },
                    onZapList = { panel = PlayerPanel.ZAP },
                    onPreviousChannel = if (prevChannel.previous != null) ({ previousChannel() }) else null,
                    onEpisodes = if (player.item.type == MediaType.EPISODE) ({ openEpisodes() }) else null,
                    onSaveMoment = if (!player.isLive && player.item.channelID == null &&
                        (player.item.type == MediaType.MOVIE || player.item.type == MediaType.EPISODE)) ({ saveMoment() }) else null,
                )
            }

            // volgende aflevering: in de laatste 45 s (op TV ook in de balk)
            AnimatedVisibility(
                showNext && !(isTV && showControls), enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(end = tvs(40), bottom = if (showControls) tvs(130) else tvs(50))
            ) {
                NetflixButton(L("Next episode"), Icons.Filled.SkipNext) { nextUp?.let { playNext(it) } }
            }

            // intro overslaan (build 52)
            AnimatedVisibility(
                showSkip && !(isTV && showControls), enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(end = tvs(40), bottom = if (showControls) tvs(130) else tvs(50))
            ) {
                NetflixButton(L("Skip intro"), Icons.Filled.FastForward, modifier = Modifier.focusRequester(skipFocus)) {
                    introRange?.let { r -> player.seek(r.endInclusive - player.currentTime); poke() }
                }
            }

            // aftiteling: volgende aflevering met aftellen (build 52)
            AnimatedVisibility(
                showCredits && !(isTV && showControls), enter = fadeIn() + slideInVertically { it }, exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(end = tvs(40), bottom = if (showControls) tvs(130) else tvs(50))
            ) {
                Column(
                    Modifier.widthIn(max = 420.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.85f))
                        .padding(tvs(14)),
                    verticalArrangement = Arrangement.spacedBy(tvs(10))
                ) {
                    Text(if (autoplayOn) L("Next episode in %d s", countdown) else L("Next episode"), color = Color.White, fontSize = tsp(15), fontWeight = FontWeight.Bold)
                    nextUp?.displaySubtitle?.let { Text(it, color = Theme.secondary, fontSize = tsp(13), maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    Row(horizontalArrangement = Arrangement.spacedBy(tvs(10))) {
                        NetflixButton(L("Play now"), Icons.Filled.SkipNext, modifier = Modifier.focusRequester(skipFocus)) {
                            nextUp?.let { playNext(it) }
                        }
                        NetflixButton(L("Keep watching"), kind = NetflixKind.SECONDARY) { creditsDismissedFor = player.item.id }
                    }
                }
            }

            // zenderbanner bij zappen
            AnimatedVisibility(
                zapBanner != null, enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = tvs(if (showControls) 120 else 36))
            ) {
                zapBanner?.let { c -> ZapBanner(c, pending = c.id != (player.item.channelID ?: player.item.id)) }
            }

            // build 173: panelen onderin over het beeld (pauzeren niet)
            if (!locked) when (panel) {
                PlayerPanel.AUDIO_SUBS -> AudioSubsPanel173(player, langVersions,
                    onVersion = { v -> panel = null; switchVersion(v) },
                    onOpenSubtitles = if (player.isLive) null else ({
                        // build 157: OpenSubtitles hoort bij Lifetime (Gratis: slotje met de vergelijking)
                        Upgrade.gate(Entitlements.Feature.OPENSUBTITLES) { panel = PlayerPanel.OPENSUBS }
                    }),
                    onClose = { panel = null })
                PlayerPanel.ZAP -> {
                    val channels = remember(player.item.channelID) { zapChannels() }
                    ZapPanel173(channels, player.item.channelID, onPick = { c -> panel = null; tune(c) }, onClose = { panel = null })
                }
                PlayerPanel.EPISODES -> EpisodesPanel173(
                    series = episodeSeries?.takeIf { s -> player.item.seriesID == null || s.id == player.item.seriesID },
                    episodes = episodeList, currentID = player.item.id,
                    onPick = { e ->
                        panel = null
                        val sr = episodeSeries
                        if (e.id != player.item.id && sr != null) playNext(Playables.episode(e, sr, player.item.imdbID).copy(seriesID = sr.id))
                    },
                    onClose = { panel = null })
                else -> {}
            }
            // build 173: vraag bij een lopend programma (live speelt alvast)
            startOverPrompt?.let { p ->
                if (panel == null && !sleepStopped) {
                    StartOverPrompt173(p, onStartOver = { startOver173() }, onLive = { startOverPrompt = null; poke() },
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = Theme.edge, bottom = if (showControls) tvs(150) else tvs(40)))
                }
            }
            tapBadge?.let { DoubleTapBadge173(it) }

            // build 171: slotknop (telefoon en tablet), links in het midden zolang de bediening zichtbaar is
            if (!isTV && showControls && !locked && player.errorText == null && !overlayOpen) {
                Box(Modifier.align(Alignment.CenterStart).padding(start = 20.dp).size(44.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f)).focusLift { locked = true; showControls = false; panel = null },
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.LockOpen, L("Lock controls"), tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
            if (locked) ControlsLockLayer(onUnlock = { locked = false; poke() })
            // build 173: slaaptimer afgegaan
            if (sleepStopped) SleepStopped173(onContinue = { continueAfterSleep() }, onClose = { close() })

            AnimatedVisibility(
                toast != null, enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = tvs(40))
            ) {
                Text(
                    toast ?: "", color = Color.White, fontSize = tsp(14), fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.75f))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
            // build 176: helderheid/volume tijdens het vegen
            if (!isTV) SwipeIndicator(Modifier.align(Alignment.Center))
        }
    }

    // MARK: build 171: Kijk je nog?
    StillWatchingDriver(still,
        kind = when (player.item.type) {
            MediaType.LIVE -> Build171Logic.StillWatching.Kind.LIVE
            MediaType.EPISODE -> Build171Logic.StillWatching.Kind.EPISODE
            else -> Build171Logic.StillWatching.Kind.MOVIE
        },
        playing = player.isPlaying && !inPiP && !Casting.connected,
        onTimeout = { player.pauseForStillWatching() })
    if (!inPiP) StillWatchingOverlay(still, onStop = { close() }, onResume = { still.answered(); player.resumeAfterStillWatching() })

    // MARK: tegelijk kijken (build 51)

    player.streamLimit?.let { info ->
        StreamLimitScreen(
            info,
            onRetry = { player.clearStreamLimit(); scope.launch { start(player.item) } },
            onClose = { player.clearStreamLimit(); close() },
        )
        return
    }

    // MARK: panelen

    when (panel) {
        // build 173: onderin over het beeld (zie boven)
        PlayerPanel.AUDIO_SUBS, PlayerPanel.ZAP, PlayerPanel.EPISODES -> {}
        PlayerPanel.OPENSUBS -> OpenSubtitlesPanel(player) { panel = null }
        PlayerPanel.OPTIONS -> OptionsPanel(player) { panel = null }
        PlayerPanel.CAST -> CastDevicesDialog { panel = null }
        PlayerPanel.SOURCES -> StremioSourcesPanel(stremioChoices, player.url, onPick = { s ->
            panel = null
            player.start(s.url)
        }, onDismiss = { panel = null })
        PlayerPanel.RATE -> RatingPanel(player.item) { panel = null; close() }
        // geen sleutel (meer): paneel sluiten via een effect, niet tijdens het opbouwen (build 60)
        PlayerPanel.INTRO -> if (markerKey != null) IntroMarkersPanel(player, markerKey) { panel = null } else LaunchedEffect(Unit) { panel = null }
        PlayerPanel.BACKUP -> {
            LaunchedEffect(player.item.id) { loadBackups() }
            BackupSourcesPanel(
                hits = if (backupFor == player.item.id) backupHits else null,
                loading = backupLoading,
                currentServer = Playlists.source?.displayName,
                onPick = { h -> panel = null; scope.launch { if (!switchTo(h)) flash(L("This stream can't be played right now.")) } },
                onDismiss = { panel = null },
            )
        }
        null -> {}
    }
}

// MARK: - Bediening ---------------------------------------------------------------------------

@Composable
private fun PlayerControls(
    player: PlayerController,
    isTV: Boolean,
    castAvailable: Boolean,
    firstButton: FocusRequester,
    scrubbing: Float?,
    hasSources: Boolean,
    showNext: Boolean,
    onNext: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    onScrubCancel: () -> Unit,
    onClose: () -> Unit,
    onPoke: () -> Unit,
    onSeek: (Double) -> Unit,
    onZap: (Int) -> Unit,
    onPanel: (PlayerPanel) -> Unit,
    onMultiview: () -> Unit,
    canMarkIntro: Boolean = false,
    // build 173
    primary: Build173PlayerLogic.Primary? = null,
    onPrimary: (Build173PlayerLogic.Primary) -> Unit = {},
    currentProgram: EPGProgram? = null,
    canStartOver: Boolean = false,
    onStartOver: () -> Unit = {},
    onZapList: () -> Unit = {},
    onPreviousChannel: (() -> Unit)? = null,
    onEpisodes: (() -> Unit)? = null,
    /** Build 176: "Moment bewaren" (films en afleveringen) */
    onSaveMoment: (() -> Unit)? = null,
) {
    val live = player.isLive
    val sleepCountdown = sleepCountdown173(player)
    val sleepEpisode = player.sleep == Build173PlayerLogic.Sleep.EndOfEpisode
    // build 173: tv: de belangrijkste knop krijgt de eerste focus, anders de tijdbalk, anders de eerste knop
    val tvTimelineFirst = isTV && primary == null && !live && player.duration > 0
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.75f), 0.3f to Color.Transparent,
                    0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f)
                )
            )
        )
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            // bovenbalk
            Row(
                Modifier.fillMaxWidth().padding(start = Theme.edge, end = Theme.edge, top = tvs(12)),
                verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (!isTV) PlayerRoundIcon(Icons.Filled.Close, L("Close"), 24) { onClose() }
                Column(Modifier.weight(1f).padding(top = if (isTV) 0.dp else 8.dp)) {
                    // films/series zonder taallabel in de titel (build 59); build 153: zendernamen opgeschoond (┃NL┃ e.d. weg)
                    Text(player.item.displayTitle,
                        color = Color.White, fontSize = tsp(17), fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    player.item.displaySubtitle?.let {
                        Text(it, color = Theme.secondary, fontSize = tsp(13), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (!isTV) {
                    if (castAvailable) {
                        PlayerRoundIcon(if (Casting.connected) Icons.Filled.CastConnected else Icons.Filled.Cast, "Chromecast", 22) {
                            onPanel(PlayerPanel.CAST)
                        }
                    }
                    // build 176: audio-uitvoer (Bluetooth, koptelefoon, luidspreker) via de systeemkiezer
                    val outCtx = androidx.compose.ui.platform.LocalContext.current
                    PlayerRoundIcon(AudioOutput.icon(rememberAudioOutput()), L("Audio output"), 22) { AudioOutput.show(outCtx) }
                    if (PlayerController.pipSupported) {
                        PlayerRoundIcon(Icons.Filled.PictureInPictureAlt, L("Picture in Picture"), 22) { player.startPiP() }
                    }
                    if (ExternalDisplay.display != null) {
                        PlayerRoundIcon(if (ExternalDisplay.enabled) Icons.Filled.Tv else Icons.Filled.DesktopAccessDisabled,
                            L("Show on external display"), 22) { ExternalDisplay.use(!ExternalDisplay.enabled) }
                    }
                }
                if (live) {
                    val behind = player.behindLive > 5
                    var focused by remember { mutableStateOf(false) }
                    Row(
                        Modifier
                            .padding(top = if (isTV) 0.dp else 8.dp)
                            .onFocusChanged { focused = it.isFocused }
                            .focusLift(1.08f) { player.goLive(); onPoke() }
                            .clip(CircleShape)
                            .background(if (focused) Color.White else if (behind) Theme.elevated else Theme.red)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val fg = if (focused) Color.Black else if (behind) Color.White else Theme.onAccent
                        Icon(Icons.Filled.Sensors, null, tint = fg, modifier = Modifier.size(tvs(14)))
                        Spacer(Modifier.width(5.dp))
                        Text(if (behind) "−${PlayerController.format(player.behindLive)}" else "LIVE",
                            color = fg, fontSize = tsp(12), fontWeight = FontWeight.Black)
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // midden (telefoon)
            if (!isTV) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(56.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (live && !player.canTimeshift) PlayerRoundIcon(Icons.Filled.KeyboardArrowUp, L("Previous channel"), 34) { onZap(-1) }
                    else PlayerRoundIcon(Icons.Filled.Replay10, "−10", 36) { onSeek(-10.0); onPoke() }
                    PlayerRoundIcon(if (player.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (player.isPlaying) L("Pause") else L("Play"), 52) { player.togglePlay(); onPoke() }
                    if (live && !player.canTimeshift) PlayerRoundIcon(Icons.Filled.KeyboardArrowDown, L("Next channel"), 34) { onZap(1) }
                    else PlayerRoundIcon(Icons.Filled.Forward10, "+10", 36) { onSeek(10.0); onPoke() }
                }
                Spacer(Modifier.weight(1f))
            }

            // onderbalk. Build 173: op de tv geen knoppen voor afspelen/pauze en terug (die zitten op de afstandsbediening);
            // de knoppen bij elkaar linksonder boven een iets grotere tijdbalk, de belangrijkste knop het grootst.
            Column(
                Modifier.fillMaxWidth().padding(start = Theme.edge, end = Theme.edge, bottom = tvs(18)),
                verticalArrangement = Arrangement.spacedBy(tvs(10))
            ) {
                if (!isTV) {
                    if (live) LiveInfo173(currentProgram) else PlayerTimeline(player, false, scrubbing, onScrub, onScrubEnd, onScrubCancel)
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = if (isTV) 12.dp else 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(tvs(if (isTV) 12 else 22)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // de belangrijkste knop: op de tv altijd vooraan (Intro overslaan en Volgende aflevering staan op de
                    // telefoon al boven het beeld, Terug naar live in het LIVE-label)
                    val showPrimary = primary != null && (isTV || primary == Build173PlayerLogic.Primary.START_OVER)
                    if (primary != null && showPrimary) {
                        PrimaryButton173(primary, isTV, if (isTV) Modifier.focusRequester(firstButton) else Modifier) { onPrimary(primary) }
                    }
                    val buttons = mutableListOf<Triple<ImageVector, String, () -> Unit>>()
                    if (live) {
                        buttons.add(Triple(Icons.Filled.LiveTv, L("Zap")) { onZapList() })
                        onPreviousChannel?.let { f -> buttons.add(Triple(Icons.Filled.History, L("Previous channel")) { f() }) }
                        if (canStartOver && primary != Build173PlayerLogic.Primary.START_OVER) {
                            buttons.add(Triple(Icons.Filled.SkipPrevious, L("From the start")) { onStartOver() })
                        }
                    }
                    buttons.add(Triple(Icons.Filled.Subtitles, L("Audio & subtitles")) { onPanel(PlayerPanel.AUDIO_SUBS) })
                    onEpisodes?.let { f -> buttons.add(Triple(Icons.Filled.Layers, L("Episodes")) { f() }) }
                    onSaveMoment?.let { f -> buttons.add(Triple(Icons.Filled.BookmarkAdd, L("Save moment")) { f() }) }
                    if (hasSources) buttons.add(Triple(Icons.Filled.VideoLibrary, L("Sources")) { onPanel(PlayerPanel.SOURCES) })
                    if (live && Playlists.library.channels.isNotEmpty()) buttons.add(Triple(Icons.Filled.GridView, "Multiview") { onMultiview() })
                    // build 52: andere bron (live) en intro/aftiteling markeren (afleveringen)
                    if (live && Store.playlists.size > 1) buttons.add(Triple(BingrIcons.otherSources, L("Other source")) { onPanel(PlayerPanel.BACKUP) })
                    if (canMarkIntro) buttons.add(Triple(Icons.Filled.Flag, L("Intro & credits")) { onPanel(PlayerPanel.INTRO) })
                    buttons.add(Triple(Icons.Filled.Tune, L("Options")) { onPanel(PlayerPanel.OPTIONS) })
                    val firstIsButton = isTV && !showPrimary && !tvTimelineFirst
                    buttons.forEachIndexed { i, (icon, label, action) ->
                        PlayerBarButton(icon, label, isTV, if (i == 0 && (firstIsButton || !isTV)) Modifier.focusRequester(firstButton) else Modifier) {
                            action(); onPoke()
                        }
                    }
                    // slaaptimer: aftellen (build 173)
                    if (sleepCountdown != null || sleepEpisode) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Bedtime, null, tint = Theme.secondary, modifier = Modifier.size(tvs(14)))
                            if (sleepCountdown != null) {
                                Spacer(Modifier.width(4.dp))
                                Text(sleepCountdown, color = Theme.secondary, fontSize = tsp(12), fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
                if (isTV) {
                    if (live) LiveInfo173(currentProgram)
                    else PlayerTimeline(player, true, scrubbing, onScrub, onScrubEnd, onScrubCancel,
                        barModifier = if (tvTimelineFirst) Modifier.focusRequester(firstButton) else Modifier)
                }
            }
        }
    }
}

/** Build 173: grote knop vooraan (Intro overslaan, Volgende aflevering, Vanaf het begin, Terug naar live). */
@Composable
private fun PrimaryButton173(p: Build173PlayerLogic.Primary, isTV: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val (title, icon) = when (p) {
        Build173PlayerLogic.Primary.SKIP_INTRO -> L("Skip intro") to Icons.Filled.FastForward
        Build173PlayerLogic.Primary.NEXT_EPISODE -> L("Next episode") to Icons.Filled.SkipNext
        Build173PlayerLogic.Primary.START_OVER -> L("From the start") to Icons.Filled.SkipPrevious
        Build173PlayerLogic.Primary.GO_LIVE -> L("Back to live") to Icons.Filled.Sensors
    }
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .focusLift(1.08f, onClick = onClick)
            .clip(RoundedCornerShape(if (isTV) 10.dp else 50.dp))
            .background(if (!isTV || focused) Color.White else Color.White.copy(alpha = 0.85f))
            .padding(horizontal = if (isTV) tvs(22) else 14.dp, vertical = if (isTV) tvs(11) else 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Color.Black, modifier = Modifier.size(if (isTV) tvs(24) else 18.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, color = Color.Black, fontSize = if (isTV) tsp(19) else tsp(14), fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Build 173: live: wat er nu op is, met voortgang en de eindtijd volgens de gids. */
@Composable
private fun LiveInfo173(p: EPGProgram?) {
    if (p == null) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(tvs(12))) {
        Text(p.displayTitle, color = Color.White, fontSize = tsp(13), fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = tvs(320)))
        Box(Modifier.weight(1f).clip(CircleShape)) { ProgressBar(p.progress) }
        Text(L("Ends at %@", clock173(p.end)), color = Theme.secondary, fontSize = tsp(12), maxLines = 1)
    }
}

@Composable
private fun PlayerTimeline(
    player: PlayerController, isTV: Boolean, scrubbing: Float?,
    onScrub: (Float) -> Unit, onScrubEnd: () -> Unit, onScrubCancel: () -> Unit,
    barModifier: Modifier = Modifier,
) {
    val frac = scrubbing?.toDouble() ?: player.position
    // build 173: eindtijd (rekening houdend met de afspeelsnelheid)
    val endsAt = Build173PlayerLogic.endsAt(System.currentTimeMillis(),
        (player.duration * (1 - player.position) * 1000).toLong(), player.rate)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(tvs(12))) {
        Text(PlayerController.format(player.duration * frac), color = Theme.secondary, fontSize = tsp(12),
            fontFamily = FontFamily.Monospace, modifier = Modifier.widthIn(min = 44.dp))
        if (isTV) {
            TvScrubBar(player, scrubbing, onScrub, onScrubEnd, onScrubCancel, Modifier.weight(1f).then(barModifier))
        } else {
            BoxWithConstraints(Modifier.weight(1f)) {
                Slider(
                    value = frac.toFloat().coerceIn(0f, 1f),
                    onValueChange = onScrub,
                    onValueChangeFinished = onScrubEnd,
                    colors = SliderDefaults.colors(
                        thumbColor = Theme.red, activeTrackColor = Theme.red, inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                // build 169: tijd van de voorbeeldpositie en het verschil met nu boven de duim, zolang je sleept
                scrubbing?.let { f ->
                    ScrubBubble(player, f.toDouble(), inset = 10.dp, gap = 14.dp, Modifier.align(Alignment.CenterStart))
                }
            }
        }
        Text("-" + PlayerController.format(player.duration * (1 - frac)), color = Theme.secondary, fontSize = tsp(12),
            fontFamily = FontFamily.Monospace, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 50.dp))
        if (endsAt != null && player.duration > 0) {
            Text(L("Ends at %@", clock173(endsAt)), color = Theme.secondary, fontSize = tsp(12), maxLines = 1)
        }
    }
}

/**
 * Build 169: tijdbalk op Android TV. Krijgt focus met de D-pad (↑ vanaf de knoppenrij), wordt dan dikker met een kop op
 * de huidige positie. ←/→ verschuiven een voorbeeldpositie (10 s, ingedrukt houden of snel herhalen: 30 s, daarna 60 s),
 * het afspelen loopt door. OK (of Afspelen/Pauze, in PlayerScreen) springt erheen, Terug annuleert (BackHandler).
 * Is de focus 5 s van de balk zonder bevestigen, dan vervalt de voorbeeldpositie. Logica: [Build169Logic].
 */
@Composable
private fun TvScrubBar(
    player: PlayerController, scrubbing: Float?,
    onScrub: (Float) -> Unit, onScrubEnd: () -> Unit, onScrubCancel: () -> Unit,
    modifier: Modifier,
) {
    val S = Build169Logic.Scrub
    val dur = player.duration
    val canScrub = dur > 0
    var focused by remember { mutableStateOf(false) }
    var streak by remember { mutableIntStateOf(0) }
    var lastMs by remember { mutableStateOf<Long?>(null) }
    var lastDir by remember { mutableIntStateOf(0) }
    val previewing = scrubbing != null
    LaunchedEffect(focused, previewing) {
        if (!focused && previewing) { delay(S.EXPIRE_MS); onScrubCancel() }
    }
    // build 173: iets groter
    val barHeight = if (focused) tvs(13) else tvs(8)
    val knob = tvs(22)
    val pos = player.position.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
    val head = (scrubbing?.toDouble() ?: pos).coerceIn(0.0, 1.0)
    BoxWithConstraints(
        modifier
            .height(tvs(30))
            .onKeyEvent { ev ->
                if (!canScrub) return@onKeyEvent false
                val ne = ev.nativeKeyEvent
                val dir = when (ne.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> -1
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> 1
                    else -> 0
                }
                if (dir != 0) {
                    if (ev.type == KeyEventType.KeyDown) {
                        val now = ne.eventTime
                        streak = S.streak(streak, lastMs, now, ne.repeatCount, sameDirection = dir == lastDir)
                        lastMs = now; lastDir = dir
                        val from = scrubbing?.let { S.seconds(it.toDouble(), dur) } ?: player.currentTime
                        onScrub(S.fraction(S.move(from, dir, streak, dur), dur).toFloat())
                    }
                    return@onKeyEvent true   // ←/→ blijven op de balk (geen focus naar een buur)
                }
                val ok = ne.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER || ne.keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                    ne.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                if (ok && scrubbing != null) {
                    if (ev.type == KeyEventType.KeyDown && ne.repeatCount == 0) { streak = 0; lastMs = null; onScrubEnd() }
                    return@onKeyEvent true
                }
                false
            }
            .onFocusChanged { focused = it.isFocused; if (!it.isFocused) { streak = 0; lastMs = null } }
            .focusable(enabled = canScrub)
    ) {
        Box(
            Modifier.align(Alignment.CenterStart).fillMaxWidth().height(barHeight).clip(CircleShape)
                .background(Color.White.copy(alpha = if (focused) 0.35f else 0.3f))
        ) {
            // voorbeeldpositie verder dan nu: lichte band tot de kop
            if (previewing && head > pos) Box(Modifier.fillMaxHeight().fillMaxWidth(head.toFloat()).background(Color.White.copy(alpha = 0.35f)))
            Box(Modifier.fillMaxHeight().fillMaxWidth(pos.toFloat()).background(Theme.red))
        }
        if (focused || previewing) {
            Box(
                Modifier.align(Alignment.CenterStart)
                    .offset(x = maxWidth * head.toFloat() - knob / 2)
                    .size(knob).clip(CircleShape).background(Color.White)
            )
        }
        if (previewing) ScrubBubble(player, head, inset = 0.dp, gap = knob / 2 + 6.dp, Modifier.align(Alignment.CenterStart))
    }
}

/**
 * Build 169: tijd van de voorbeeldpositie plus het verschil met nu ("12:34  +4:30"), gecentreerd boven [fraction] van de
 * breedte (min [inset] aan beide kanten, zoals de duim van de Slider). Neemt zelf geen ruimte in.
 */
@Composable
private fun ScrubBubble(player: PlayerController, fraction: Double, inset: androidx.compose.ui.unit.Dp, gap: androidx.compose.ui.unit.Dp, modifier: Modifier) {
    val S = Build169Logic.Scrub
    val text = Build169Logic.Label.bubble(S.seconds(fraction, player.duration), player.currentTime)
    Text(
        text, color = Color.White, fontSize = tsp(13), fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
        maxLines = 1,
        modifier = modifier
            .layout { m, c ->
                val p = m.measure(Constraints())
                val w = c.maxWidth.takeIf { it != Constraints.Infinity } ?: p.width
                val ins = inset.roundToPx()
                val cx = ins + ((w - 2 * ins).coerceAtLeast(0) * fraction.coerceIn(0.0, 1.0)).toInt()
                val x = (cx - p.width / 2).coerceIn(0, (w - p.width).coerceAtLeast(0))
                layout(0, 0) { p.place(x, -p.height - gap.roundToPx()) }
            }
            .clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.8f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun PlayerRoundIcon(icon: ImageVector, description: String, size: Int, onClick: () -> Unit) {
    Box(
        Modifier.size((size + 24).dp).clip(CircleShape).focusLift(1.12f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size(size.dp))
    }
}

/** Knop in de onderbalk: op TV icoon + tekst (wit bij focus), op de telefoon alleen het icoon. */
@Composable
private fun PlayerBarButton(icon: ImageVector, label: String, isTV: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val fg = if (focused && isTV) Color.Black else Color.White
    Row(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .focusLift(1.06f, onClick = onClick)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused && isTV) Color.White else Color.Transparent)
            .padding(horizontal = if (isTV) 14.dp else 6.dp, vertical = if (isTV) 8.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, label, tint = fg, modifier = Modifier.size(if (isTV) tvs(22) else 24.dp))
        if (isTV) {
            Spacer(Modifier.width(8.dp))
            Text(label, color = fg, fontSize = tsp(15), fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** Zenderbanner bij zappen: nummer, logo, naam, groep en wat er nu speelt. */
@Composable
private fun ZapBanner(c: Channel, pending: Boolean) {
    val now = Playlists.nowOf(c)
    Row(
        Modifier.widthIn(max = 640.dp).padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.82f))
            .padding(horizontal = tvs(16), vertical = tvs(10)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        c.number?.let {
            Text("$it", color = Color.White, fontSize = tsp(22), fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(tvs(12)))
        }
        RemoteImage(c.logo, Modifier.width(tvs(64)).height(tvs(40)).clip(RoundedCornerShape(4.dp)),
            placeholder = c.displayName.take(3), fit = true)
        Spacer(Modifier.width(tvs(12)))
        Column(Modifier.weight(1f, fill = false)) {
            Text(c.displayName, color = Color.White, fontSize = tsp(16), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = listOfNotNull(now?.let { "${s2Time(it.start)}  ${it.displayTitle}" }, c.group.takeIf { it.isNotBlank() }?.let { ContentLanguage.categoryTitle(it) }).firstOrNull()
            if (line != null) Text(line, color = Theme.secondary, fontSize = tsp(12), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (now != null) {
                Spacer(Modifier.height(4.dp))
                Box(Modifier.width(tvs(200)).clip(RoundedCornerShape(2.dp))) { ProgressBar(now.progress) }
            }
        }
        if (pending) {
            Spacer(Modifier.width(tvs(12)))
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(tvs(16)))
        }
    }
}
