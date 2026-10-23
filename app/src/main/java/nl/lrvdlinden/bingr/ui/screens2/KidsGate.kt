package nl.lrvdlinden.bingr

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Build 140: ouderpoort (docs/ACCOUNT.md → "Build 140", zoals NLziet). Vóór elke actie waarmee je een actief kinderprofiel
 * verlaat (profiel wisselen, "Wie kijkt er?", Instellingen, Mijn Bingr, account en apparaten, uitloggen) eerst een
 * rekensom. Goed = de actie gaat door; fout of annuleren = je blijft in het kinderprofiel. Geen ontgrendeling voor later.
 * Alleen lokaal; er gaat niets naar de server.
 */
object KidsGate {
    /** De actie die op de som wacht (null = geen som in beeld). */
    var pending by mutableStateOf<(() -> Unit)?>(null)
        private set

    /**
     * Na een geslaagde som in "Wie kijkt er?": daar niet nog eens vragen. Weer false zodra er een kinderprofiel geopend
     * wordt. Bij het starten van de app false: was het laatst actieve profiel een kinderprofiel, dan vraagt "Wie kijkt er?"
     * de som ook bij het kiezen van een profiel dat geen kinderprofiel is.
     */
    var leftKids = false
        private set

    /** Is er nu een kinderprofiel actief? */
    val active: Boolean get() = Store.activeProfile?.kids == true

    /** [action] uitvoeren; bij een actief kinderprofiel pas na een goede som. */
    fun guard(action: () -> Unit) {
        if (!active) { action(); return }
        pending = action
    }

    /** "Wie kijkt er?": profiel [p] kiezen (of beheren, [p] = null) nadat een kinderprofiel het laatst actief was. */
    fun guardPicker(p: ViewerProfile?, action: () -> Unit) {
        if (!active || leftKids || p?.kids == true) { action(); return }
        pending = action
    }

    /** Een kinderprofiel is (weer) geopend. */
    fun kidsOpened() { leftKids = false }

    internal fun passed() {
        val a = pending
        pending = null
        leftKids = true
        a?.invoke()
    }

    internal fun cancel() { pending = null }
}

/** Zet dit één keer per scherm met acties die een kinderprofiel verlaten (MainScreen, "Wie kijkt er?"). */
@Composable
fun KidsGateHost() {
    if (KidsGate.pending == null) return
    KidsGatePad(onCancel = { KidsGate.cancel() }, onPass = { KidsGate.passed() })
}

@Composable
private fun KidsGatePad(onCancel: () -> Unit, onPass: () -> Unit) {
    val state = remember { Build140Logic.Gate.State(Build140Logic.Gate.newSum()) }
    var sum by remember { mutableStateOf(state.sum) }
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val shake = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val tv = AppState.isTV

    fun wobble() = scope.launch {
        repeat(3) { shake.animateTo(-10f, tween(40)); shake.animateTo(10f, tween(40)) }
        shake.animateTo(0f, tween(40))
    }

    fun check(force: Boolean) {
        when (state.check(input, force)) {
            Build140Logic.Gate.Result.WAIT -> {}
            Build140Logic.Gate.Result.OK -> onPass()
            Build140Logic.Gate.Result.WRONG -> { input = ""; error = L("That's not right. Try again."); wobble() }
            Build140Logic.Gate.Result.NEW_SUM -> { input = ""; sum = state.sum; error = L("That's not right. Try again."); wobble() }
        }
    }

    fun tap(k: String) {
        when (k) {
            "⌫" -> { if (input.isNotEmpty()) input = input.dropLast(1); return }
            "OK" -> { if (input.isNotEmpty()) check(force = true); return }
        }
        if (input.length >= Build140Logic.Gate.MAX_DIGITS) return
        input += k
        error = null
        check(force = false)
    }

    fun newSum() {
        state.sum = Build140Logic.Gate.newSum()
        sum = state.sum
        input = ""; error = null
    }

    S2FullDialog(onCancel) {
        val first = remember { FocusRequester() }
        BoxWithConstraints(
            Modifier.fillMaxSize()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val code = e.nativeKeyEvent.keyCode
                    when (code) {
                        in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> { tap("${code - AndroidKeyEvent.KEYCODE_0}"); true }
                        in AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9 -> { tap("${code - AndroidKeyEvent.KEYCODE_NUMPAD_0}"); true }
                        AndroidKeyEvent.KEYCODE_DEL -> { tap("⌫"); true }
                        AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> { tap("OK"); true }
                        else -> false
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            val w = maxWidth
            val h = maxHeight
            val gap = 12.dp
            val key: Dp = if (tv) ((w - 96.dp - gap * 11) / 12).coerceIn(40.dp, 68.dp)
                else minOf((h - 300.dp - gap * 3) / 4, (w - 48.dp - gap * 2) / 3).coerceIn(44.dp, 72.dp)
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = h)
                    .padding(horizontal = if (tv) 48.dp else 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.ChildCare, null, tint = Theme.red, modifier = Modifier.size(if (tv) 36.dp else 30.dp))
                Spacer(Modifier.height(12.dp))
                Text(L("Ask a grown-up"), color = Color.White, fontSize = if (tv) 28.sp else 21.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(L("Solve this sum to leave the kids profile."), color = Theme.secondary, fontSize = if (tv) 18.sp else 14.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 520.dp))
                Spacer(Modifier.height(18.dp))
                Text(sum.text, color = Color.White, fontSize = if (tv) 40.sp else 32.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(10.dp))
                Text(input.ifEmpty { " " }, color = Color.White, fontSize = if (tv) 34.sp else 28.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.offset(x = shake.value.dp))
                Text(error ?: " ", color = Theme.red, fontSize = if (tv) 16.sp else 13.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                if (tv) {
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "⌫").forEach { k ->
                            PinKey(k, key, if (k == "1") first else null) { tap(k) }
                        }
                    }
                } else {
                    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", ""))
                    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        rows.forEach { r ->
                            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                                r.forEach { k ->
                                    if (k.isEmpty()) Spacer(Modifier.size(key))
                                    else PinKey(k, key, if (k == "1") first else null) { tap(k) }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    NetflixButton(title = L("OK"), icon = null, kind = NetflixKind.PLAY) { tap("OK") }
                    Text(L("New sum"), color = Color.White, fontSize = if (tv) 20.sp else 16.sp,
                        modifier = Modifier.focusLift(1.08f, dimWhenIdle = true) { newSum() }.padding(12.dp))
                    Text(L("Cancel"), color = Theme.secondary, fontSize = if (tv) 20.sp else 16.sp,
                        modifier = Modifier.focusLift(1.08f, dimWhenIdle = true, onClick = onCancel).padding(12.dp))
                }
            }
        }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    }
}
