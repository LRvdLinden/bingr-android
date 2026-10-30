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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Forward30
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
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

private enum class PlayerPanel { AUDIO_SUBS, OPENSUBS, OPTIONS, CAST, SOURCES, RATE, INTRO, BACKUP }

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

    fun flash(text: String) { toast = text; toastID++ }
    fun poke() { showControls = true; pokeID++ }
    fun toggleControls() { showControls = !showControls; if (showControls) poke() }
    fun close() { player.dismissed = true; player.stop(); onClose() }

    // vooruit gedeclareerd: bind() verwijst naar playNext(), dat start() aanroept
    var playNextRef: (PlayableItem) -> Unit = {}

    fun bind() {
        player.onFinished = {
            val cur = player.item
            val n = nextUp
            when {
                n != null -> playNextRef(n)   // zoals Netflix: gewoon door naar de volgende aflevering
                Trakt.askRating && Trakt.isLoggedIn && cur.imdbID != null && cur.type != MediaType.LIVE -> { panel = PlayerPanel.RATE }
                else -> close()
            }
            Unit
        }
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

    suspend fun start(itm: PlayableItem) {
        if (player.dismissed) return
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
            (next.sourceID ?: Playlists.source?.id)?.let { Store.pushRecentChannel(it, next.id) }
            player.stop()
            start(livePlayable(next))
        }
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
        start(item)
    }
    DisposableEffect(player) {
        val obs = LifecycleEventObserver { _, e ->
            // app naar de achtergrond zonder beeld-in-beeld of Chromecast (bijv. TV-thuisknop): de stream echt stoppen,
            // zodat de providerverbinding vrijkomt (pauzeren hield hem open); terug = verder op dezelfde plek
            if (e == Lifecycle.Event.ON_STOP && !AppState.inPiP && !Casting.connected) player.suspendForBackground()
            if (e == Lifecycle.Event.ON_START) player.resumeFromBackground()
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

    // bediening tevoorschijn → focus op afspelen/pauze; weg → focus terug naar het scherm zelf (vangt de D-pad op).
    // Fout zonder bediening (TV) → focus op "Try again".
    val hasError = player.errorText != null
    val skipVisible = showSkip || showCredits
    LaunchedEffect(showControls, panel, inPiP, hasError, skipVisible) {
        if (panel != null || inPiP) return@LaunchedEffect
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
    LaunchedEffect(showCredits, player.item.id) {
        if (!showCredits) return@LaunchedEffect
        countdown = 10
        while (countdown > 0) {
            delay(1000)
            if (player.isPlaying) countdown--
        }
        nextUp?.let { playNext(it) }
    }

    BackHandler {
        when {
            panel != null -> panel = null
            isTV && showControls -> showControls = false
            else -> close()
        }
    }

    // MARK: afstandsbediening

    fun handleKey(code: Int, down: Boolean, repeat: Int): Boolean {
        if (!down) return swallowUp.remove(code)
        if (panel != null) return false
        var handled = true
        when (code) {
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { player.togglePlay(); poke() }
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> { player.resume(); poke() }
            AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); poke() }
            AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekBy(10.0)
            AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> seekBy(-10.0)
            AndroidKeyEvent.KEYCODE_CHANNEL_UP -> zap(-1)
            AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> zap(1)
            AndroidKeyEvent.KEYCODE_MEDIA_NEXT -> { val n = nextUp; if (n != null) playNext(n) else if (player.isLive) zap(1) else handled = false }
            AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS -> { if (player.isLive) zap(-1) else handled = false }
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

    val showNext = nextUp != null && player.errorText == null && player.duration > 120 && !showCredits &&
        (player.duration - player.currentTime < 45 || (creditsAt != null && player.currentTime >= creditsAt))

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
                    detectVerticalDragGestures(
                        onDragStart = { dy = 0f },
                        onDragEnd = {
                            val min = 60.dp.toPx()
                            if (player.isLive && kotlin.math.abs(dy) > min) zap(if (dy < 0) 1 else -1)
                        },
                        onVerticalDrag = { change, amount -> dy += amount; change.consume() }
                    )
                }.pointerInput(player) {
                    detectTapGestures(
                        onTap = { toggleControls() },
                        onDoubleTap = { off ->
                            val w = size.width
                            when {
                                off.x < w * 0.35f -> { seekBy(-10.0); poke() }
                                off.x > w * 0.65f -> { seekBy(10.0); poke() }
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

            AnimatedVisibility(showControls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
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
                    onClose = { close() },
                    onPoke = { poke() },
                    onSeek = { seekBy(it) },
                    onZap = { zap(it) },
                    onPanel = { panel = it },
                    onMultiview = { openMultiview() },
                    canMarkIntro = player.item.type == MediaType.EPISODE && markerKey != null,
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
                    Text(L("Next episode in %d s", countdown), color = Color.White, fontSize = tsp(15), fontWeight = FontWeight.Bold)
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
        }
    }

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
        PlayerPanel.AUDIO_SUBS -> TracksPanel(player, langVersions, onVersion = { v -> panel = null; switchVersion(v) }) { panel = null }
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
    onClose: () -> Unit,
    onPoke: () -> Unit,
    onSeek: (Double) -> Unit,
    onZap: (Int) -> Unit,
    onPanel: (PlayerPanel) -> Unit,
    onMultiview: () -> Unit,
    canMarkIntro: Boolean = false,
) {
    val live = player.isLive
    val canSeek = !live || player.canTimeshift
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

            // onderbalk
            Column(
                Modifier.fillMaxWidth().padding(start = Theme.edge, end = Theme.edge, bottom = tvs(18)),
                verticalArrangement = Arrangement.spacedBy(tvs(10))
            ) {
                if (!live) PlayerTimeline(player, isTV, scrubbing, onScrub, onScrubEnd)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = if (isTV) 12.dp else 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(tvs(if (isTV) 14 else 22)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val buttons = mutableListOf<Triple<ImageVector, String, () -> Unit>>()
                    if (isTV) {
                        buttons.add(Triple(if (player.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            if (player.isPlaying) L("Pause") else L("Play")) { player.togglePlay() })
                        if (showNext) buttons.add(Triple(Icons.Filled.SkipNext, L("Next episode")) { onNext() })
                        if (canSeek) {
                            buttons.add(Triple(Icons.Filled.Replay10, "−10") { onSeek(-10.0) })
                            buttons.add(Triple(Icons.Filled.Forward30, "+30") { onSeek(30.0) })
                        }
                        if (live) {
                            buttons.add(Triple(Icons.Filled.KeyboardArrowUp, L("Previous channel")) { onZap(-1) })
                            buttons.add(Triple(Icons.Filled.KeyboardArrowDown, L("Next channel")) { onZap(1) })
                        }
                    }
                    buttons.add(Triple(Icons.Filled.Subtitles, L("Audio & subtitles")) { onPanel(PlayerPanel.AUDIO_SUBS) })
                    if (!live) buttons.add(Triple(Icons.Filled.ClosedCaption, "OpenSubtitles") { onPanel(PlayerPanel.OPENSUBS) })
                    if (hasSources) buttons.add(Triple(Icons.Filled.VideoLibrary, L("Sources")) { onPanel(PlayerPanel.SOURCES) })
                    if (live && Playlists.library.channels.isNotEmpty()) buttons.add(Triple(Icons.Filled.GridView, "Multiview") { onMultiview() })
                    // build 52: andere bron (live) en intro/aftiteling markeren (afleveringen)
                    if (live && Store.playlists.size > 1) buttons.add(Triple(BingrIcons.otherSources, L("Other source")) { onPanel(PlayerPanel.BACKUP) })
                    if (canMarkIntro) buttons.add(Triple(Icons.Filled.Flag, L("Intro & credits")) { onPanel(PlayerPanel.INTRO) })
                    buttons.add(Triple(Icons.Filled.Tune, L("Options")) { onPanel(PlayerPanel.OPTIONS) })
                    buttons.forEachIndexed { i, (icon, label, action) ->
                        PlayerBarButton(icon, label, isTV, if (i == 0) Modifier.focusRequester(firstButton) else Modifier) {
                            action(); onPoke()
                        }
                    }
                    player.sleepAt?.let { s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Bedtime, null, tint = Theme.secondary, modifier = Modifier.size(tvs(14)))
                            Spacer(Modifier.width(4.dp))
                            Text(DateFormat.getTimeInstance(DateFormat.SHORT, Localizer.locale).format(Date(s)),
                                color = Theme.secondary, fontSize = tsp(12))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerTimeline(player: PlayerController, isTV: Boolean, scrubbing: Float?, onScrub: (Float) -> Unit, onScrubEnd: () -> Unit) {
    val frac = scrubbing?.toDouble() ?: player.position
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(tvs(12))) {
        Text(PlayerController.format(player.duration * frac), color = Theme.secondary, fontSize = tsp(12),
            fontFamily = FontFamily.Monospace, modifier = Modifier.widthIn(min = 44.dp))
        if (isTV) {
            ProgressBar(frac, Modifier.weight(1f).height(6.dp).clip(CircleShape))
        } else {
            Slider(
                value = frac.toFloat().coerceIn(0f, 1f),
                onValueChange = onScrub,
                onValueChangeFinished = onScrubEnd,
                colors = SliderDefaults.colors(
                    thumbColor = Theme.red, activeTrackColor = Theme.red, inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                ),
                modifier = Modifier.weight(1f)
            )
        }
        Text("-" + PlayerController.format(player.duration * (1 - frac)), color = Theme.secondary, fontSize = tsp(12),
            fontFamily = FontFamily.Monospace, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 50.dp))
    }
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
