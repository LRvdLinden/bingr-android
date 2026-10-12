package nl.lrvdlinden.bingr

import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.min
import kotlin.math.pow

/**
 * Stuurt één afspeelsessie aan: kiest de engine (ExoPlayer of VLC), herverbindt
 * automatisch, doet timeshift bij live, bewaart voortgang en scrobblet naar Trakt.
 * Alle aanroepen op de main thread.
 */
class PlayerController(item: PlayableItem) {
    enum class EngineChoice(val key: String) {
        AUTO("auto"), APPLE("apple"), VLC("vlc");
        val title: String
            get() = when (this) {
                AUTO -> L("Automatic")
                APPLE -> L("ExoPlayer (HDR, Chromecast)")
                VLC -> L("VLC (all formats)")
            }
        companion object { fun from(key: String?): EngineChoice = entries.firstOrNull { it.key == key } ?: AUTO }
    }

    enum class Aspect {
        FIT, FILL, R169, R43;
        val title: String
            get() = when (this) { FIT -> L("Fit"); FILL -> L("Fill"); R169 -> "16:9"; R43 -> "4:3" }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ctx get() = AppState.appContext

    var item by mutableStateOf(item)
    var engine: PlaybackEngine by mutableStateOf<PlaybackEngine>(IdleEngine())
        private set
    /** true = ExoPlayer, false = VLC */
    var usingExo by mutableStateOf(false); private set
    var isPlaying by mutableStateOf(false); private set
    var isBuffering by mutableStateOf(true); private set
    var position by mutableStateOf(0.0); private set
    var currentTime by mutableStateOf(0.0); private set
    var duration by mutableStateOf(0.0); private set
    var errorText by mutableStateOf<String?>(null); private set
    var reconnecting by mutableStateOf(false); private set
    /** Hoeveel seconden achter live (timeshift) */
    var behindLive by mutableStateOf(0.0); private set
    var subtitleText by mutableStateOf<String?>(null); private set
    /** Telt op als de sporen veranderen (voor het sporenpaneel) */
    var tracksVersion by mutableStateOf(0); private set

    private var _rate by mutableStateOf(1f)
    var rate: Float
        get() = _rate
        set(v) { _rate = v; engine.rate = v }
    /** Slaaptimer: epoch-ms */
    var sleepAt by mutableStateOf<Long?>(null)
    private var _aspect by mutableStateOf(Aspect.FIT)
    var aspect: Aspect
        get() = _aspect
        set(v) { _aspect = v; engine.setAspect(v) }
    private var _muted by mutableStateOf(false)
    var muted: Boolean
        get() = _muted
        set(v) { _muted = v; engine.muted = v }
    /** Korte melding voor in beeld (bijv. "beeld-in-beeld niet beschikbaar") */
    var notice by mutableStateOf<String?>(null)

    private var stopped = false
    val isStopped: Boolean get() = stopped
    /** Spelerscherm is gesloten: een stream die daarna pas klaar is met laden niet meer starten. */
    var dismissed = false

    /** Echte afspeel-URL (na Stalker/Stremio-resolutie) */
    var url: String? = null; private set
    var catchupProvider: ((Long, Long) -> String?)? = null
    var onFinished: (() -> Unit)? = null
    /** Begin van de aftiteling van deze serie in seconden (build 52): daarna telt de aflevering als gezien */
    var creditsStart: Double? = null
    /** Multiview-tegels: geen voortgang/scrobble */
    var isPreview = false
    /** De gewone live-URL (om na timeshift terug te keren) */
    var liveURL: String? = null

    private var timer: Job? = null
    private var lastSaved = 0L
    private var didScrobbleStart = false
    private var stallSince: Long? = null
    private var attempts = 0
    private var triedOtherEngine = false
    /** Foutherstel (build 52): er staat al een stille nieuwe poging klaar. */
    private var retryPending = false
    /** Deze stream heeft al gespeeld (dan is een fout eerder een netwerkprobleem dan een formaatprobleem). */
    private var everPlayed = false
    /** Laatste positie (0…1) waarop het nog goed speelde, om na een fout daar verder te gaan. */
    private var lastGoodPosition = 0.0
    /** Live: na de stille poging één keer automatisch naar een andere server (reservebron) geprobeerd. */
    var triedBackup = false
    /** Reservebron wordt gezocht: intussen niet de foutmelding tonen. */
    private var backupPending = false
    /** Live-zender kan niet spelen na de stille poging: PlayerScreen zoekt een reservebron (anders [showFailure]). */
    var onLiveFailure: (() -> Unit)? = null
    private var pausedAt: Long? = null
    private var timeshiftStart: Long? = null        // begin van de catch-up-stream die nu speelt (epoch-ms)
    private var externalCues: List<SubtitleCue> = emptyList()
    private var tracksApplied = false
    private var watchedSeconds = 0.0
    private var playingTicks = 0
    private var trackSignature = ""
    private var recordedHistory = false
    private var vlcFrameRateDone = false
    /** Tegelijk kijken (build 51): de stream die bij de server is aangemeld, en voor welke titel. */
    private var streamOwner: StreamSession.Owner? = null
    private var streamItemID: String? = null
    /** Niet-null: stream_start gaf 403 stream_limit → scherm "Too many people are watching". */
    var streamLimit by mutableStateOf<Account.StreamList?>(null); private set

    val isLive: Boolean get() = item.type == MediaType.LIVE
    val canTimeshift: Boolean
        get() = isLive && (catchupProvider != null || ((engine as? ExoEngine)?.liveWindow ?: 0.0) > 30)

    // MARK: engine kiezen

    private fun makeEngine(exo: Boolean) {
        engine.release()
        engine = if (exo) ExoEngine(ctx).also { it.frameRateMatching = !isPreview } else VlcEngine(ctx)
        // starttijd-log (build 59)
        when (val e = engine) {
            is ExoEngine -> e.onFirstFrame = { StartTiming.end("first frame (ExoPlayer)") }
            is VlcEngine -> e.onFirstFrame = { StartTiming.end("first frame (VLC)") }
            else -> {}
        }
        usingExo = exo
        vlcFrameRateDone = false
        engine.rate = rate
        engine.muted = muted
        engine.setAspect(aspect)
    }

    // MARK: afspelen

    /** Nieuwe titel/zender: niets meenemen van de vorige (timeshift, pauze, herverbind-pogingen, fout). */
    fun prepareNew() {
        timeshiftStart = null; behindLive = 0.0; pausedAt = null
        // niets van de vorige titel gebruiken voor de hervatplek (build 59)
        duration = 0.0; currentTime = 0.0; position = 0.0
        autoSubsFor = null; osJob?.cancel(); osJob = null
        contentInfo = null
        attempts = 0; triedOtherEngine = false; reconnecting = false; errorText = null; isBuffering = true
        stallSince = null
        retryPending = false; everPlayed = false; lastGoodPosition = 0.0; triedBackup = false; backupPending = false
    }

    /** Kan niet afspelen (geen stream gevonden): foutmelding met "Try again" in plaats van een eindeloze spinner. */
    fun fail(message: String) {
        errorText = message; isBuffering = false; reconnecting = false
    }

    /** Nieuwe ondertitelgrootte meteen toepassen (Opties tijdens het afspelen). */
    fun applySubtitleSize() {
        engine.setSubtitleSize(SubtitleSize.current)
        tracksVersion++
    }

    fun start(url: String, forceExo: Boolean? = null) {
        if (dismissed) return
        this.url = url
        errorText = null; didScrobbleStart = false; stallSince = null
        tracksApplied = false; playingTicks = 0
        if (stopped || timer == null) { watchedSeconds = 0.0; recordedHistory = false; attempts = 0; triedOtherEngine = false }
        if (url != this.lastStartedURL) everPlayed = false
        lastStartedURL = url
        retryPending = false
        stopped = false
        // nieuwe titel of zender (ook zappen en de volgende aflevering): stream_start; herverbinden niet
        if (!isPreview && streamItemID != item.id) beginStream()
        if (contentInfo == null && !isPreview) contentInfo = ContentLanguage.of(item)
        val exo = forceExo ?: prefersExo(url, live = isLive)
        if (exo != usingExo || timer == null) makeEngine(exo)
        val live = isLive && timeshiftStart == null
        val fraction = startFraction()
        val secs = startSeconds(fraction)
        StartTiming.mark("engine load (${if (usingExo) "ExoPlayer" else "VLC"}${secs?.let { ", resume ${it.toInt()} s" } ?: ""})")
        val gate = ageGate?.takeIf { !it.isCompleted || !gateResult(it) }
        if (gate == null) {
            engine.load(url, live = live, startFraction = fraction, startSeconds = secs)
        } else {
            // kinderprofiel (build 59): de leeftijdscontrole loopt tegelijk met het voorbereiden van de speler;
            // alleen het eerste beeld wacht erop. ExoPlayer laadt en buffert alvast; VLC begint pas na de controle.
            val e = engine
            holdingForAge = true
            if (e is ExoEngine) { e.holdStart = true; e.load(url, live = live, startFraction = fraction, startSeconds = secs) }
            scope.launch {
                val ok = runCatching { gate.await() }.getOrDefault(false)
                StartTiming.mark("age check ${if (ok) "ok" else "blocked"}")
                holdingForAge = false
                if (stopped || dismissed || engine !== e || this@PlayerController.url != url) return@launch
                if (!ok) { blockedByAge(); return@launch }
                if (e is ExoEngine) e.play() else e.load(url, live = live, startFraction = fraction, startSeconds = secs)
            }
        }
        timer?.cancel()
        timer = scope.launch {
            while (isActive) {
                delay(500)
                tick()
            }
        }
    }

    private var lastStartedURL: String? = null

    /** Hervatpunt (0 = vanaf het begin; bijna uitgekeken = ook vanaf het begin). */
    private fun startFraction(): Double? {
        if (isLive) return null
        val r = item.resumeAt
        return if (r > 0.01 && r < 0.97) r else null
    }

    /**
     * Exacte hervatplek in seconden (build 59), zodat de engine meteen daar begint in plaats van eerst vanaf 0 te
     * bufferen en dan te spoelen (dat kostte bij hervatten een tweede buffermoment). Bron: een expliciete plek
     * ([pendingStartSeconds], bijv. bij wisselen van taalversie), de lengte van deze stream (andere speler,
     * herverbinden), of de bewaarde voortgang van dit profiel.
     */
    private fun startSeconds(fraction: Double?): Double? {
        if (isLive) return null
        pendingStartSeconds?.let { pendingStartSeconds = null; return it.takeIf { s -> s > 5 } }
        val f = fraction ?: return null
        if (duration > 60) return f * duration
        Store.progress[item.id]?.let { p -> if (kotlin.math.abs(p.fraction - f) < 0.01 && p.position > 5) return p.position }
        return null
    }

    /** Volgende start op precies deze plek (seconden), bijv. bij wisselen naar een andere taalversie. */
    var pendingStartSeconds: Double? = null

    /**
     * Leeftijdscontrole die nog loopt (build 59, kinderprofielen): afspelen wordt al voorbereid, het eerste beeld
     * wacht op de uitkomst. Gezet door het spelerscherm.
     */
    var ageGate: Deferred<Boolean>? = null
    /** Beeld afdekken: de leeftijdscontrole loopt nog. */
    var holdingForAge by mutableStateOf(false); private set

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun gateResult(d: Deferred<Boolean>): Boolean = runCatching { d.getCompleted() }.getOrDefault(false)

    private fun blockedByAge() {
        runCatching { engine.stop() }
        timer?.cancel(); timer = null
        isPlaying = false
        fail(L("Not available on this profile (age limit)."))
    }

    /** Wisselt naar ExoPlayer op dezelfde positie. */
    fun switchToExo() {
        val u = url ?: return
        if (usingExo || !canUseExo(u)) return
        item = item.copy(resumeAt = position)
        start(u, forceExo = true)
    }

    /** Wisselt naar VLC op dezelfde positie. */
    fun switchToVLC() {
        val u = url ?: return
        if (!usingExo) return
        item = item.copy(resumeAt = position)
        start(u, forceExo = false)
    }

    private fun tick() {
        val st = engine.state
        isPlaying = engine.isPlaying
        isBuffering = st == EngineState.BUFFERING || st == EngineState.OPENING
        duration = engine.duration
        currentTime = engine.time
        position = engine.position
        val exo = engine as? ExoEngine
        if (exo != null && isLive && timeshiftStart == null) behindLive = if (exo.behindLive > 8) exo.behindLive else 0.0

        if (externalCues.isNotEmpty()) {
            val t = currentTime - subtitleDelay
            val cue = externalCues.lastOrNull { it.start <= t }
            subtitleText = if (cue != null && cue.end >= t) cue.text else null
        }

        // automatisch herverbinden
        if (st == EngineState.ERROR || (isLive && st == EngineState.ENDED)) {
            reconnect()
        } else if (isBuffering && isLive) {
            val now = System.currentTimeMillis()
            if (stallSince == null) stallSince = now
            stallSince?.let { if (now - it > 15_000) reconnect() }
        } else if (st == EngineState.PLAYING) {
            stallSince = null; attempts = 0; reconnecting = false; everPlayed = true
            if (!isLive && duration > 0 && position > 0) lastGoodPosition = position
        }

        if (isPlaying && !didScrobbleStart && duration > 0 && !isPreview) {
            didScrobbleStart = true
            scrobble(Trakt.Scrobble.START, position, announce = true)
        }
        if (st == EngineState.PLAYING) watchedSeconds += 0.5
        // ook als er meteen naar een Chromecast gecast wordt (lokaal gepauzeerd, lengte wel bekend)
        if (!tracksApplied && (st == EngineState.PLAYING || (Casting.connected && duration > 0))) {
            playingTicks += 1
            // sporen zijn er meestal na een paar tellen; daarna niet meer ingrijpen (jouw eigen keuze blijft staan)
            if (engine.audioTracks.isNotEmpty() || playingTicks > 12) applyTrackPreferences()
        }
        // sporenpaneel bijwerken als er sporen bijkomen of wisselen
        val sig = "${engine.audioTracks.size}|${engine.subtitleTracks.size}|${engine.currentAudio}|${engine.currentSubtitle}"
        if (sig != trackSignature) { trackSignature = sig; tracksVersion++ }

        // VLC: beeldfrequentie van het videospoor (Android TV, build 51)
        if (!usingExo && !vlcFrameRateDone && !isPreview && st == EngineState.PLAYING && FrameRateMatcher.enabled) {
            vlcFrameRateDone = true
            runCatching {
                val t = (engine as? VlcEngine)?.player?.currentVideoTrack
                if (t != null && t.frameRateDen > 0 && t.frameRateNum > 0) FrameRateMatcher.apply(t.frameRateNum.toFloat() / t.frameRateDen, null, exo = false)
            }
        }
        sleepAt?.let { if (System.currentTimeMillis() >= it) { sleepAt = null; pause() } }
        if (System.currentTimeMillis() - lastSaved > 15_000) saveProgress()
        if (!isLive && st == EngineState.ENDED) finish()
    }

    /**
     * Slim foutherstel (build 52): eerst één stille nieuwe poging (met de andere engine als de stream nog nooit
     * gespeeld heeft, want dan is het eerder een formaatprobleem). Lukt dat niet: bij live één keer automatisch
     * naar dezelfde zender op een andere server ([onLiveFailure]), en anders de foutmelding met
     * "Opnieuw", "Andere bron" en "Andere speler". Films en afleveringen gaan verder op de laatste goede positie.
     */
    private fun reconnect() {
        val u = url ?: return
        if (retryPending || backupPending || errorText != null) return
        if (attempts >= 1) {
            val hook = onLiveFailure
            if (isLive && !triedBackup && hook != null && !isPreview) {
                triedBackup = true
                backupPending = true
                reconnecting = true
                hook()
                return
            }
            showFailure()
            return
        }
        attempts += 1
        reconnecting = true
        val resume = if (isLive) null else lastGoodPosition.takeIf { it > 0.0 } ?: position.takeIf { it > 0.0 }
        if (!isLive && resume != null) item = item.copy(resumeAt = resume)
        val otherEngine = !everPlayed && !triedOtherEngine && engine.state == EngineState.ERROR && (usingExo || canUseExo(u))
        if (otherEngine) {
            triedOtherEngine = true
            retryPending = true
            scope.launch {
                delay(500)
                retryPending = false
                if (stopped || dismissed) return@launch
                start(u, forceExo = !usingExo)
            }
            return
        }
        retryPending = true
        stallSince = System.currentTimeMillis() + 4_000L
        val resumeSecs = resume?.let { if (duration > 60) it * duration else null }
        scope.launch {
            delay(1_500)
            retryPending = false
            if (stopped || dismissed) return@launch
            engine.load(u, live = isLive && timeshiftStart == null, startFraction = resume, startSeconds = resumeSecs)
        }
    }

    /** Foutmelding tonen (Opnieuw / Andere bron / Andere speler); de timer stopt. */
    fun showFailure() {
        errorText = L("This stream can't be played right now."); reconnecting = false; isBuffering = false
        retryPending = false; backupPending = false
        if (!isLive) lastGoodPosition.takeIf { it > 0.0 }?.let { item = item.copy(resumeAt = it) }
        timer?.cancel(); timer = null
    }

    /** Kan er met de andere engine geprobeerd worden ("Andere speler")? */
    val canSwitchEngine: Boolean get() = url?.let { usingExo || canUseExo(it) } ?: false

    /** "Andere speler": ExoPlayer ↔ VLC, op de laatste goede positie. */
    fun switchEngine() {
        val u = url ?: return
        if (!canSwitchEngine) return
        if (!isLive) item = item.copy(resumeAt = lastGoodPosition.takeIf { it > 0.0 } ?: position)
        attempts = 0; triedOtherEngine = true; reconnecting = false
        start(u, forceExo = !usingExo)
    }

    /**
     * Andere bron afspelen (reservebron of handmatig gekozen): zelfde titel, andere URL. Bij live geen
     * terugkijken meer (dat hoort bij de oorspronkelijke server).
     */
    fun playAlternative(u: String) {
        if (!isLive) item = item.copy(resumeAt = lastGoodPosition.takeIf { it > 0.0 } ?: position)
        else { catchupProvider = null; timeshiftStart = null; behindLive = 0.0; liveURL = u }
        backupPending = false
        attempts = 0; triedOtherEngine = false; reconnecting = false
        start(u)
    }

    /** Opnieuw proberen na een fout ("Try again"). */
    fun retry() {
        val u = url ?: return
        if (!isLive && lastGoodPosition > 0.0) item = item.copy(resumeAt = lastGoodPosition)
        attempts = 0; triedOtherEngine = false; reconnecting = false
        start(u)
    }

    fun togglePlay() { if (engine.isPlaying) pause() else resume() }

    fun pause() {
        engine.pause()
        if (isLive) pausedAt = System.currentTimeMillis()
        saveProgress()
        if (!isPreview) scrobble(Trakt.Scrobble.PAUSE, position)
    }

    fun resume() {
        // Live gepauzeerd met catch-up: verder kijken waar je was (timeshift)
        val p = pausedAt
        val provider = catchupProvider
        val now = System.currentTimeMillis()
        if (isLive && p != null && now - p > 10_000 && provider != null && !(engine is ExoEngine && (engine as ExoEngine).liveWindow > 30)) {
            val from = timeshiftStart?.let { it + (currentTime * 1000).toLong() } ?: p
            val u = provider(from, now + 3 * 3600_000L)
            if (u != null) {
                timeshiftStart = from
                behindLive = (now - from) / 1000.0
                pausedAt = null
                start(u)
                return
            }
        }
        pausedAt = null
        engine.play()
        if (!isPreview) scrobble(Trakt.Scrobble.START, position)
    }

    /** Spoelen. Bij live: binnen het HLS-venster, of via catch-up (timeshift). */
    fun seek(seconds: Double) {
        if (!isLive) { engine.jump(seconds); return }
        val exo = engine as? ExoEngine
        if (exo != null && timeshiftStart == null && exo.liveWindow > 30) { exo.jump(seconds); return }
        val provider = catchupProvider ?: return
        val now = System.currentTimeMillis()
        val currentWall = timeshiftStart?.let { it + (currentTime * 1000).toLong() } ?: now
        val target = min(currentWall + (seconds * 1000).toLong(), now)
        if (now - target < 10_000) { goLive(); return }
        val u = provider(target, now + 3 * 3600_000L) ?: return
        timeshiftStart = target
        behindLive = (now - target) / 1000.0
        start(u)
    }

    fun goLive() {
        val exo = engine as? ExoEngine
        if (exo != null && timeshiftStart == null) { exo.goLive(); behindLive = 0.0; return }
        if (timeshiftStart == null) return
        val u = liveURL ?: item.url ?: return
        timeshiftStart = null; behindLive = 0.0
        start(u)
    }

    fun seekTo(fraction: Double) { if (!isLive) engine.seekToFraction(fraction) }

    fun stop() {
        endStream()
        if (stopped) return
        stopped = true
        recordHistory()
        timer?.cancel(); timer = null
        // OpenSubtitles-zoekactie van deze titel niet laten doorlopen na het sluiten (build 60)
        osJob?.cancel(); osJob = null
        saveProgress()
        if (!isPreview) { scrobble(Trakt.Scrobble.STOP, position); StateSync.flush() }
        engine.release()
        isPlaying = false
        if (current === this) current = null
    }

    private fun finish() {
        recordHistory()
        timer?.cancel(); timer = null
        position = 1.0
        saveProgress()
        if (!isPreview) { scrobble(Trakt.Scrobble.STOP, 1.0); StateSync.flush() }
        onFinished?.invoke()
    }

    // MARK: tegelijk kijken (build 51)

    private fun beginStream() {
        endStream()
        streamItemID = item.id
        streamLimit = null
        val o = StreamSession.Owner(info = { streamInfo() }, onBlocked = { l -> blockedByLimit(l) })
        streamOwner = o
        StreamSession.begin(o)
    }

    private fun endStream() {
        streamOwner?.let { StreamSession.end(it) }
        streamOwner = null
        streamItemID = null
    }

    /** Titel voor de server: bij live de zender plus het programma ("NPO 1 · Journaal"). */
    private fun streamInfo(): StreamSession.Info {
        val it = item
        return when (it.type) {
            MediaType.LIVE -> {
                val lib = Playlists.library
                val prog = it.channelID?.let { cid -> lib.channels.firstOrNull { c -> c.id == cid } }
                    ?.let { c -> lib.nowPlaying(c)?.title } ?: it.subtitle
                StreamSession.Info(listOfNotNull(it.title, prog?.takeIf { p -> p.isNotBlank() }).joinToString(" · "), "live", it.channelID ?: it.id)
            }
            MediaType.EPISODE -> StreamSession.Info(
                listOfNotNull(it.title, it.subtitle?.takeIf { s -> s.isNotBlank() }).joinToString(" · "), "episode", it.id)
            else -> StreamSession.Info(it.title, "movie", it.id)
        }
    }

    /** 403 stream_limit: afspelen stoppen; het spelerscherm toont wie er kijkt. */
    private fun blockedByLimit(l: Account.StreamList) {
        streamOwner = null
        streamItemID = null
        saveProgress()
        timer?.cancel(); timer = null
        runCatching { engine.stop() }
        isPlaying = false; isBuffering = false; reconnecting = false
        streamLimit = l
    }

    /** "Close" of "Try again" op het scherm "Too many people are watching". */
    fun clearStreamLimit() { streamLimit = null }

    /**
     * Scrobble naar Trakt. Bij [announce] kort laten zien dat Trakt meekijkt (of waarom niet), als
     * `Trakt.scrobble` een resultaat teruggeeft (enum OK / NOT_FOUND / FAILED / SKIPPED, zoals ScrobbleResult op iOS).
     * Geeft het Unit terug, dan wordt er niets getoond.
     */
    private fun scrobble(action: Trakt.Scrobble, progress: Double, announce: Boolean = false) {
        if (isPreview) return
        val itm = item
        AppScope.launch {
            if (!(Trakt.isLoggedIn && Trakt.scrobbleEnabled)) return@launch
            val result: Any? = try {
                Trakt.scrobble(action, itm, progress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (!announce || stopped || item.id != itm.id) return@launch
            val key = result?.toString()?.lowercase(Locale.ROOT)?.replace("_", "") ?: return@launch
            when (key) {
                "ok" -> notice = L("Trakt: watching %@", itm.subtitle?.let { "${itm.title} · $it" } ?: itm.title)
                "notfound" -> notice = L("Trakt: title not found, not synced")
                "failed" -> notice = L("Trakt: could not connect")
                else -> {}
            }
        }
    }

    /** Na minstens een minuut kijken komt het in de kijkgeschiedenis van dit profiel. */
    private fun recordHistory() {
        if (isPreview || recordedHistory || watchedSeconds < 60) return
        recordedHistory = true
        val it = item
        Store.addHistory(
            HistoryEntry(
                itemID = it.channelID ?: it.id, playlistID = Store.activePlaylistID,
                title = it.title, subtitle = it.subtitle, artwork = it.artwork,
                type = it.type, channelID = it.channelID, date = System.currentTimeMillis(),
                profileID = Store.activeProfileID
            )
        )
    }

    private fun saveProgress() {
        lastSaved = System.currentTimeMillis()
        val it = item
        if (isPreview || it.type == MediaType.LIVE || it.channelID != null || it.id.contains("-cu-") || duration <= 60) return
        // gezien: bijna uitgekeken, of alleen de aftiteling nog over — per profiel, ook zonder Trakt
        val left = duration - currentTime
        val inCredits = creditsStart?.let { c -> c > 60 && currentTime >= c } == true
        if (position >= 0.85 || (duration > 600 && left < 180 && position > 0.7) || inCredits) {
            Store.clearProgress(it.id)
            Store.setLocalWatched(it.id, true)
            val s = it.season; val e = it.episode
            if (it.type == MediaType.EPISODE && s != null && e != null) {
                seriesID(it)?.let { sid -> Store.setLocalWatched(Store.episodeKey(sid, s, e), true) }
            }
        } else if (position > 0.02) {
            Store.setProgress(
                it.id,
                ProgressEntry(
                    fraction = position, position = currentTime, updated = System.currentTimeMillis(),
                    title = it.title, artwork = it.artwork, playlistID = Store.activePlaylistID,
                    profileID = Store.activeProfileID
                )
            )
        }
    }

    /** Serie van een aflevering: uit het item, anders opzoeken in de bibliotheek. */
    private fun seriesID(p: PlayableItem): String? {
        p.seriesID?.let { sid -> return sid }
        val lib = Playlists.library
        return lib.series.firstOrNull { s -> s.episodes.any { e -> e.id == p.id } }?.id
            ?: lib.series.firstOrNull { s -> s.title == p.title }?.id
    }

    // MARK: beeld-in-beeld

    /** Start beeld-in-beeld (Android: de hele app gaat in het PiP-venster, beide engines werken). */
    fun startPiP() {
        val ok = MainActivity.current?.enterPiP() ?: false
        if (!ok) notice = L("Picture in Picture isn't available for this stream.")
    }

    // MARK: sporen & ondertitels

    /**
     * Voorkeurstalen toepassen zodra de sporen bekend zijn (beide engines):
     * - audio (build 59): de ingestelde taal, standaard de taal van de app, als de stream meerdere audiosporen heeft;
     * - ondertitels: zie [applySubtitlePreferences].
     */
    private fun applyTrackPreferences() {
        tracksApplied = true
        if (isPreview) return
        val pref = TrackPreferences.preferredAudio
        val audios = engine.audioTracks.filter { it.id != "-1" }
        if (pref != null && audios.size > 1) {
            val t = audios.firstOrNull { TrackPreferences.matches(it, pref) }
            if (t != null && t.id != engine.currentAudio) engine.selectAudio(t)
        }
        applySubtitlePreferences()
        tracksVersion++
        StartTiming.mark("tracks applied (${audios.size} audio, ${engine.subtitleTracks.size} subtitles)")
    }

    // MARK: automatische ondertitels (build 59)

    /** Taal van deze titel volgens de titel/categorie/TMDB (voor als het audiospoor geen taal heeft). */
    private var contentInfo: ContentLanguage.Info? = null
    /** Voor welke titel de automatische ondertitels al geregeld zijn. */
    private var autoSubsFor: String? = null
    private var osJob: Job? = null

    /** Taal van het audiospoor dat nu speelt; zonder taal in de stream die van de titel (bijv. "(IT)"). */
    val currentAudioLanguage: String?
        get() {
            tracksVersion
            val id = engine.currentAudio
            val all = engine.audioTracks.filter { it.id != "-1" }
            val t = all.firstOrNull { it.id == id } ?: all.singleOrNull()
            return t?.let { TrackPreferences.codeOf(it) } ?: contentInfo?.code?.takeIf { all.size <= 1 }
        }

    /**
     * - "Uit": zoals ingesteld bij "Standaard ondertiteltaal" (spoor in de stream, of uit).
     * - "Als nodig": spreekt de audio een andere taal dan je ondertiteltaal en zit er geen ondertitel in die taal in
     *   beeld (ingebakken "NL SUBS"), dan het spoor in de stream in jouw taal, anders op de achtergrond de beste
     *   van OpenSubtitles.
     * - "Altijd": altijd ondertitels in jouw taal (eerst uit de stream, anders OpenSubtitles).
     * Het afspelen wacht hier nooit op.
     */
    private fun applySubtitlePreferences() {
        if (externalCues.isNotEmpty() || externalSubtitleName != null) return
        val mode = TrackPreferences.autoSubtitles
        val want = TrackPreferences.preferredSubtitle
        val burnedIn = contentInfo?.subs == want
        val audioLang = currentAudioLanguage
        val needed = when (mode) {
            TrackPreferences.AUTO_ALWAYS -> !burnedIn
            TrackPreferences.AUTO_WHEN_NEEDED -> audioLang != null && audioLang != want && !burnedIn
            else -> false
        }
        if (needed && autoSubsFor != item.id) {
            autoSubsFor = item.id
            val embedded = engine.subtitleTracks.firstOrNull { TrackPreferences.matches(it, want) }
            if (embedded != null) {
                if (embedded.id != engine.currentSubtitle) engine.selectSubtitle(embedded)
                notice = L("Subtitles: %@", TrackPreferences.name(want))
                Casting.subtitlesChanged(this)
            } else if (!isLive && OpenSubtitles.isLoggedIn) {
                fetchOpenSubtitles(want)
            }
            return
        }
        if (needed) return
        // geen automatische ondertitels nodig: de gewone standaard voor sporen in de stream
        when (val sub = TrackPreferences.subtitle) {
            "off" -> engine.selectSubtitle(null)
            "" -> {}
            else -> engine.subtitleTracks.firstOrNull { TrackPreferences.matches(it, sub) }?.let { engine.selectSubtitle(it) }
        }
        // ook op de Chromecast (build 60): daar ging alleen de automatische keuze heen, niet de standaard-ondertiteltaal
        if (TrackPreferences.subtitle.isNotEmpty()) Casting.subtitlesChanged(this)
    }

    /** Beste ondertitel van OpenSubtitles in [lang] op de achtergrond zoeken en inladen (ook naar de Chromecast). */
    private fun fetchOpenSubtitles(lang: String) {
        if (osJob?.isActive == true) return
        val itm = item
        val u = url
        osJob = scope.launch {
            try {
                val t0 = System.currentTimeMillis()
                val year = if (itm.type == MediaType.MOVIE) itm.subtitle?.toIntOrNull() else null
                val tmdb = Details.cachedTMDB(if (itm.type == MediaType.EPISODE) itm.seriesID else itm.id)
                val hash = u?.let { OpenSubtitles.movieHash(it) }
                val found = OpenSubtitles.search(
                    imdbID = itm.imdbID, title = ContentLanguage.displayTitle(itm.title), year = year,
                    season = itm.season, episode = itm.episode, languages = listOf(lang), tmdbID = tmdb, movieHash = hash,
                )
                val best = found.firstOrNull() ?: return@launch
                if (stopped || item.id != itm.id || externalSubtitleName != null) return@launch
                val file = OpenSubtitles.download(best)
                if (stopped || item.id != itm.id || externalSubtitleName != null) return@launch
                addSubtitle(file, "${TrackPreferences.flag(best.language)} ${best.release}", best.language)
                notice = L("Subtitles: %@ (OpenSubtitles)", TrackPreferences.name(lang))
                android.util.Log.i("BingrStart", "[${itm.title.take(40)}] OpenSubtitles loaded in ${System.currentTimeMillis() - t0} ms")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    // MARK: andere taalversie (build 59)

    /** Eén audiospoor (of geen keuze): het audiomenu toont dan de taal en de andere taalversies. */
    val hasSingleAudio: Boolean get() { tracksVersion; return engine.audioTracks.count { it.id != "-1" } <= 1 }

    val audioTracks: List<MediaTrack> get() { tracksVersion; return engine.audioTracks }
    val subtitleTracks: List<MediaTrack> get() { tracksVersion; return engine.subtitleTracks }
    val currentAudio: String? get() { tracksVersion; return engine.currentAudio }
    val currentSubtitle: String? get() { tracksVersion; return if (externalCues.isEmpty()) engine.currentSubtitle else "ext" }
    val hasExternalSubtitle: Boolean get() = externalCues.isNotEmpty() || externalSubtitleName != null
    /** Naam van de laatst ingeladen OpenSubtitles-ondertitel (met vlag) */
    var externalSubtitleName by mutableStateOf<String?>(null); private set
    /** Verschuiving van de ondertitels in seconden (+ = later tonen) */
    private var _subtitleDelay by mutableStateOf(0.0)
    var subtitleDelay: Double
        get() = _subtitleDelay
        set(v) { _subtitleDelay = v; engine.setSubtitleDelay(v) }

    fun selectAudio(t: MediaTrack) { engine.selectAudio(t); tracksVersion++ }
    fun selectSubtitle(t: MediaTrack?) {
        externalCues = emptyList(); subtitleText = null; externalSubtitleName = null
        externalSubtitleFile = null; externalSubtitleLang = null
        engine.selectSubtitle(t); tracksVersion++
        Casting.subtitlesChanged(this)
    }

    /** Ingeladen ondertitelbestand (UTF-8) en de taal ervan, ook voor de Chromecast (build 57). */
    private var externalSubtitleFile: File? = null
    private var externalSubtitleLang: String? = null

    /**
     * Welke ondertitel er ook op de Chromecast moet (build 57): een ingeladen bestand gaat voor,
     * anders het gekozen spoor uit de stream, anders uit (null).
     */
    fun castSubtitle(): Casting.CastSubtitle? {
        externalSubtitleFile?.let { f ->
            return Casting.CastSubtitle.External(f, externalSubtitleLang, externalSubtitleName ?: f.nameWithoutExtension, subtitleDelay)
        }
        val id = engine.currentSubtitle ?: return null
        val tracks = engine.subtitleTracks
        val i = tracks.indexOfFirst { it.id == id }
        if (i < 0) return null
        return Casting.CastSubtitle.Embedded(tracks[i], i, tracks.size)
    }

    /** [lang]: taalcode van het bestand (bijv. van OpenSubtitles), voor het tekstspoor op de Chromecast. */
    fun addSubtitle(file: File, name: String? = null, lang: String? = null) {
        // altijd als UTF-8 aanbieden (OpenSubtitles levert soms Windows-1252/Latin-1 → rare tekens)
        val utf8 = SRT.normalizedFile(file) ?: file
        if (engine.supportsExternalSubtitles) {
            engine.addSubtitle(utf8)
        } else {
            val bytes = runCatching { utf8.readBytes() }.getOrNull()
            if (bytes != null) {
                engine.selectSubtitle(null)
                externalCues = SRT.parse(bytes)
                subtitleText = null
            }
        }
        externalSubtitleName = name ?: file.nameWithoutExtension
        externalSubtitleFile = utf8; externalSubtitleLang = lang
        engine.setSubtitleDelay(subtitleDelay)
        tracksVersion++
        Casting.subtitlesChanged(this)
    }

    companion object {
        /** De speler van het open spelerscherm (voor beeld-in-beeld en MainActivity). */
        var current: PlayerController? = null

        val choice: EngineChoice get() = EngineChoice.from(Prefs.string("player.engine"))

        val pipSupported: Boolean
            get() = Build.VERSION.SDK_INT >= 26 && !AppState.isTV &&
                AppState.appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

        private fun scheme(url: String): String = Uri.parse(url).scheme?.lowercase(Locale.ROOT) ?: ""

        /** ExoPlayer kan alleen http(s)/file/content openen; smb://, ftp://, rtmp:// enz. alleen VLC. */
        fun canUseExo(url: String): Boolean = scheme(url) in setOf("http", "https", "file", "content")

        fun prefersExo(url: String, live: Boolean = false): Boolean {
            if (!canUseExo(url)) return false
            // deinterlacen "Aan" (build 52): live-zenders bij Automatisch met VLC (ExoPlayer kan niet deinterlacen)
            if (live && choice == EngineChoice.AUTO && PlayerSettings.deinterlace == "on") return false
            return when (choice) {
                EngineChoice.APPLE -> true
                EngineChoice.VLC -> false
                EngineChoice.AUTO -> {
                    val path = Uri.parse(url).path ?: ""
                    val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)
                    val s = url.lowercase(Locale.ROOT)
                    ext in setOf("m3u8", "mp4", "m4v", "mov", "mpd") || s.contains(".m3u8") || s.contains(".mpd")
                }
            }
        }

        fun format(s: Double): String {
            if (!s.isFinite() || s < 0) return "0:00"
            val t = s.toInt(); val h = t / 3600; val m = (t % 3600) / 60; val sec = t % 60
            return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ROOT, "%d:%02d", m, sec)
        }
    }
}

/** Plaatshouder tot [PlayerController.start] de echte engine kiest. */
private class IdleEngine : PlaybackEngine {
    override val state: EngineState get() = EngineState.OPENING
    override val isPlaying: Boolean get() = false
    override val position: Double get() = 0.0
    override val time: Double get() = 0.0
    override val duration: Double get() = 0.0
    override var rate: Float = 1f
    override var muted: Boolean = false
    override val supportsExternalSubtitles: Boolean get() = false
    override fun load(url: String, live: Boolean, startFraction: Double?, startSeconds: Double?) {}
    override fun play() {}
    override fun pause() {}
    override fun stop() {}
    override fun release() {}
    override fun seekToFraction(f: Double) {}
    override fun jump(seconds: Double) {}
    override val audioTracks: List<MediaTrack> get() = emptyList()
    override val subtitleTracks: List<MediaTrack> get() = emptyList()
    override val currentAudio: String? get() = null
    override val currentSubtitle: String? get() = null
    override fun selectAudio(t: MediaTrack) {}
    override fun selectSubtitle(t: MediaTrack?) {}
    override fun addSubtitle(file: File) {}
    override fun setAspect(a: PlayerController.Aspect) {}
    override fun setSubtitleDelay(seconds: Double) {}
    override fun setSubtitleSize(size: SubtitleSize) {}
}
