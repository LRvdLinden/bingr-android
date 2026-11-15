package nl.lrvdlinden.bingr

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Build 176 (punt 11, docs/ACCOUNT.md "Build 176", 7): voorvertoning op een titel. Tv: na 2 s focus op een poster speelt
 * de trailer (YouTube, nooit de IPTV-stream) zonder geluid in de kaart. Telefoon/tablet: alleen in de kop van Home.
 * Niet bij Data besparen, niet bij een vergrendelde titel en niet in een kinderprofiel (zonder zekere leeftijd); telt niet
 * als stream. Instelling "Voorvertoning" (per apparaat, standaard aan).
 */
object CardPreview {
    val state = HeroTrailerState()
    var activeKey by mutableStateOf<String?>(null)
        private set
    private var focusedKey: String? = null
    private var job: Job? = null
    /** YouTube-id per titel (null = opgezocht, geen trailer). */
    private val trailers = HashMap<String, String?>()

    fun key(t: DetailTarget): String = "${t.sourceID ?: Playlists.source?.id ?: ""}|${if (t.isSeries) "s" else "m"}|${t.id}"

    fun allowed(t: DetailTarget): Boolean =
        !Screenshots.isActive && !DiscoverItem.isExternal(t.id) &&
            PosterPreview.allowed(locked = Locks.titleLocked(t), kidsBlocked = Store.activeProfile?.kids == true)

    suspend fun trailer(t: DetailTarget): String? {
        val k = key(t)
        if (trailers.containsKey(k)) return trailers[k]
        val id = screensSafe { Details.load(t).trailerYouTubeID }?.let { Build174Logic.youtubeID(it) }
        trailers[k] = id
        return id
    }

    /** Focus op een poster (tv) gekregen of kwijt. */
    fun focus(t: DetailTarget, focused: Boolean) {
        val k = key(t)
        if (focused) {
            focusedKey = k
            job?.cancel()
            if (activeKey != null && activeKey != k) stop()
            if (!AppState.isTV || !allowed(t)) return
            job = AppScope.launch {
                delay(Build176Logic.Preview.DELAY_MS)
                if (focusedKey != k) return@launch
                val id = trailer(t) ?: return@launch
                if (focusedKey != k || AppState.playing != null) return@launch
                activeKey = k
                state.start(id)
            }
        } else if (focusedKey == k) {
            focusedKey = null
            job?.cancel()
            if (activeKey == k) stop()
        }
    }

    fun stop() {
        job?.cancel()
        state.stop()
        activeKey = null
    }
}

/** Tv: focus volgen voor de voorvertoning (zet op de klikbare kaart, naast focusLift). */
fun Modifier.cardPreviewFocus(t: DetailTarget): Modifier =
    if (!AppState.isTV) this else this.onFocusChanged { CardPreview.focus(t, it.isFocused) }

/** De trailer in de kaart (alleen voor de kaart die nu voorvertoont); gebruik als `preview` van [PosterCard]. */
@Composable
fun BoxScope.CardPreviewSlot(t: DetailTarget) {
    if (!AppState.isTV) return
    if (CardPreview.activeKey != CardPreview.key(t)) return
    HeroTrailerView(CardPreview.state, Modifier.matchParentSize())
    LaunchedEffect(AppState.playing) { if (AppState.playing != null) CardPreview.stop() }
}

/** Telefoon/tablet: speelt er nu een voorvertoning in de kop van Home (dan wacht het wisselen van de titel). */
object HeroPreview {
    var busy by mutableStateOf(false)
}

/** Telefoon/tablet: na 2 s de trailer zonder geluid in de kop van Home (over de poster). */
@Composable
fun BoxScope.HeroPreviewSlot(t: DetailTarget) {
    if (AppState.isTV) return
    val ctx = LocalContext.current
    val st = remember(t.id) { HeroTrailerState() }
    LaunchedEffect(t.id) {
        delay(Build176Logic.Preview.DELAY_MS)
        if (!CardPreview.allowed(t) || !HeroTrailer.allowed(ctx) || AppState.playing != null) return@LaunchedEffect
        val id = CardPreview.trailer(t) ?: return@LaunchedEffect
        if (AppState.playing == null) st.start(id)
    }
    LaunchedEffect(st.videoID, st.visible) { HeroPreview.busy = st.videoID != null }
    LaunchedEffect(AppState.playing) { if (AppState.playing != null) st.stop() }
    DisposableEffect(t.id) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) st.stop() }
        val lc = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle
        lc.addObserver(obs)
        onDispose { lc.removeObserver(obs); st.stop(); HeroPreview.busy = false }
    }
    HeroTrailerView(st, Modifier.matchParentSize())
}
