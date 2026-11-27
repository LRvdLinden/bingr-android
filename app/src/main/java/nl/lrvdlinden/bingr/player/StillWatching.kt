package nl.lrvdlinden.bingr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay

/**
 * Build 171: "Kijk je nog?" (Instellingen > Afspelen, per apparaat; regels in [Build171Logic.StillWatching]).
 * Na 3 automatisch gestarte afleveringen, of na 2/3/4 uur live of film zonder bediening: het beeld wordt gedimd met
 * Verder kijken / Stoppen. Geen antwoord binnen 30 s: pauzeren én `stream_stop` (plek in de streamlimiet en de
 * providerverbinding vrij), dan "Gepauzeerd: druk op Afspelen om verder te gaan" (hervatten op dezelfde plek, live: live).
 */
object StillWatchingPrefs {
    var enabled: Boolean
        get() = Prefs.bool("player.askStillWatching", true)
        set(v) = Prefs.put("player.askStillWatching", v)
    var hours: Int
        get() = Build171Logic.StillWatching.hours(Prefs.int("player.stillWatchingHours", Build171Logic.StillWatching.DEFAULT_HOURS))
        set(v) = Prefs.put("player.stillWatchingHours", Build171Logic.StillWatching.hours(v))
    val hourOptions: List<Pair<Int, String>> get() = Build171Logic.StillWatching.HOURS.map { it to L("%d hours", it) }
}

class StillWatchingState {
    private val W = Build171Logic.StillWatching
    var state by mutableStateOf(W.touch(Build171Logic.StillWatching.State(), System.currentTimeMillis()))
        private set
    /** Niet-null: de vraag staat open (sinds). */
    var askedAt by mutableStateOf<Long?>(null)
    /** Na 30 s zonder antwoord gepauzeerd (stream gestopt). */
    var paused by mutableStateOf(false)
    private var autoBeforeTouch = 0
    private var touchedAt = 0L

    /** Bediening: toets, tik, focuswissel, volume, zappen. Telt niet terwijl de vraag of het pauzescherm open staat. */
    fun touch() {
        if (askedAt != null || paused) return
        val now = System.currentTimeMillis()
        if (now - touchedAt > 1_000) autoBeforeTouch = state.autoEpisodes
        touchedAt = now
        state = W.touch(state, now)
    }

    /** Volgende aflevering gestart: vanzelf, of met de knop Volgende aflevering ([viaButton]; die tik telt dan niet als bediening). */
    fun autoStarted(viaButton: Boolean) {
        val now = System.currentTimeMillis()
        val base = if (viaButton && now - touchedAt < 3_000) state.copy(autoEpisodes = autoBeforeTouch) else state
        state = W.started(base, true, now)
    }

    fun answered() {
        askedAt = null; paused = false
        state = W.touch(Build171Logic.StillWatching.State(), System.currentTimeMillis())
    }
}

/** Elke tik of veeg op het scherm telt als bediening (zonder de tik zelf af te vangen). */
fun Modifier.stillWatchingTouches(st: StillWatchingState): Modifier = this.pointerInput(st) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial)
            st.touch()
        }
    }
}

/** Tijdklok: vraag stellen en na 30 s [onTimeout] (pauzeren + stream_stop). */
@Composable
fun StillWatchingDriver(st: StillWatchingState, kind: Build171Logic.StillWatching.Kind, playing: Boolean, onTimeout: () -> Unit) {
    val k by rememberUpdatedState(kind)
    val p by rememberUpdatedState(playing)
    val t by rememberUpdatedState(onTimeout)
    LaunchedEffect(st) {
        while (true) {
            delay(1_000)
            if (st.paused) continue
            val now = System.currentTimeMillis()
            val a = st.askedAt
            if (a == null) {
                if (p && Build171Logic.StillWatching.ask(st.state, now, StillWatchingPrefs.enabled, StillWatchingPrefs.hours, k) != null) st.askedAt = now
            } else if (Build171Logic.StillWatching.expired(a, now)) {
                st.askedAt = null
                st.paused = true
                t()
            }
        }
    }
}

/** Het gedimde beeld met de vraag, of het pauzescherm. */
@Composable
fun StillWatchingOverlay(st: StillWatchingState, onStop: () -> Unit, onResume: () -> Unit) {
    val asked = st.askedAt
    if (asked == null && !st.paused) return
    val focus = remember { FocusRequester() }
    var left by remember(asked) { mutableIntStateOf(Build171Logic.StillWatching.ANSWER_MS.div(1000L).toInt()) }
    LaunchedEffect(asked) {
        if (asked == null) return@LaunchedEffect
        while (true) { left = Build171Logic.StillWatching.secondsLeft(asked, System.currentTimeMillis()); delay(250) }
    }
    LaunchedEffect(asked, st.paused) { delay(100); runCatching { focus.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (st.paused) 0.85f else 0.7f)).stillWatchingBlock(),
        contentAlignment = Alignment.Center) {
        Column(Modifier.padding(tvs(24)), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(tvs(14))) {
            if (st.paused) {
                Text(L("Paused: press Play to continue"), color = Color.White, fontSize = tsp(22), fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center)
                Row(horizontalArrangement = Arrangement.spacedBy(tvs(12))) {
                    NetflixButton(L("Play"), Icons.Filled.PlayArrow, kind = NetflixKind.PLAY, modifier = Modifier.focusRequester(focus)) { onResume() }
                    NetflixButton(L("Stop"), Icons.Filled.Close, kind = NetflixKind.SECONDARY) { onStop() }
                }
            } else {
                Text(L("Are you still watching?"), color = Color.White, fontSize = tsp(24), fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center)
                Text(L("Playback pauses in %d s", left), color = Theme.secondary, fontSize = tsp(14), textAlign = TextAlign.Center)
                Row(horizontalArrangement = Arrangement.spacedBy(tvs(12))) {
                    NetflixButton(L("Continue watching"), Icons.Filled.PlayArrow, modifier = Modifier.focusRequester(focus)) { st.answered() }
                    NetflixButton(L("Stop"), Icons.Filled.Close, kind = NetflixKind.SECONDARY) { onStop() }
                }
            }
        }
    }
}

/** Tikken en vegen onder de laag niet doorgeven (de knoppen zelf werken gewoon). */
private fun Modifier.stillWatchingBlock(): Modifier = this.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Final)
            e.changes.forEach { if (!it.isConsumed) it.consume() }
        }
    }
}
