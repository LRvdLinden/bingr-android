package nl.lrvdlinden.bingr

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/**
 * Pincode-invoer (4 cijfers) — werkt met aanraken, afstandsbediening (D-pad) en cijfertoetsen.
 * Telefoon/tablet: compact 3×4-toetsenblok dat zich aan de schermhoogte aanpast.
 * TV: één rij cijfers 1–9, 0, ⌫ (zoals de tvOS-versie), past ook met overscan.
 * [onSubmit] geeft true bij een juiste code (dan sluit het scherm via [onDismiss]), anders schudt het en begint opnieuw.
 * Build 141 (M6): [guarded] (standaard) telt foute pogingen; na 5 fouten op rij volgt een oplopende wachttijd
 * ([ParentalControl.lockoutRemaining]) waarin de toetsen niets doen. Alleen bij het kiezen van een nieuwe pincode
 * (PinSetupScreen) staat het uit.
 * Build 189: bovenaan de ronde profielfoto ([profile], anders het actieve profiel), vier losse vakjes (het laatste cijfer
 * is even zichtbaar, dan een punt) en "Pincode vergeten?" voor wie is ingelogd: met het wachtwoord van het Bingr-account
 * wordt de pincode verwijderd (daarna kies je in Instellingen een nieuwe).
 */
@Composable
fun PinPad(title: String, onDismiss: () -> Unit, guarded: Boolean = true, profile: ViewerProfile? = null, onSubmit: (String) -> Boolean) {
    var pin by remember { mutableStateOf("") }
    var reveal by remember { mutableIntStateOf(-1) }
    var forgot by remember { mutableStateOf(false) }
    val canForgot = guarded && Account.isSignedIn && Account.email != null
    LaunchedEffect(reveal) { if (reveal >= 0) { delay(Build189Logic.PinBoxes.REVEAL_MS); reveal = -1 } }
    val shake = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val tv = AppState.isTV
    var wait by remember { mutableStateOf(if (guarded) ParentalControl.lockoutRemaining() else 0L) }
    val lockVersion = ParentalControl.lockoutVersion
    LaunchedEffect(guarded, lockVersion) {
        if (!guarded) return@LaunchedEffect
        wait = ParentalControl.lockoutRemaining()
        while (wait > 0) { delay(1000); wait = ParentalControl.lockoutRemaining() }
    }

    // build 146: de pincode is een hash (PBKDF2, 100.000 iteraties): eerst buiten de main thread afleiden ("Controleren…"),
    // daarna gebruikt [onSubmit] (ParentalControl.verify/check/setPIN) die uitkomst
    var checking by remember { mutableStateOf(false) }
    fun tap(k: String) {
        if (checking) return
        if (guarded && ParentalControl.lockoutRemaining() > 0) { pin = ""; return }
        if (k == "⌫") { if (pin.isNotEmpty()) pin = pin.dropLast(1); reveal = -1; return }
        if (pin.length >= 4) return
        pin += k
        reveal = pin.length - 1
        if (pin.length == 4) {
            val entered = pin
            checking = true
            scope.launch {
                withContext(Dispatchers.Default) { runCatching { ParentalControl.prepare(entered, newPin = !guarded) } }
                checking = false
                val ok = onSubmit(entered)
                ParentalControl.forgetPrepared()
                if (guarded) { if (ok) ParentalControl.pinSucceeded() else ParentalControl.pinFailed() }
                if (ok) onDismiss()
                else {
                    pin = ""; reveal = -1
                    repeat(3) { shake.animateTo(-10f, tween(40)); shake.animateTo(10f, tween(40)) }
                    shake.animateTo(0f, tween(40))
                }
            }
        }
    }

    S2FullDialog(onDismiss) {
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
                        else -> false
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            val w = maxWidth
            val h = maxHeight
            if (tv) {
                // één rij: 11 toetsen over de breedte
                val gap = 12.dp
                val key: Dp = ((w - 96.dp - gap * 10) / 11).coerceIn(40.dp, 72.dp)
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    PinHeader(profile, 72.dp)
                    Spacer(Modifier.height(14.dp))
                    Text(title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 820.dp))
                    Spacer(Modifier.height(18.dp))
                    PinBoxes(pin, reveal, 44.dp, 52.dp, 24, shake.value)
                    PinChecking(checking, 18)
                    PinWait(wait, 18)
                    Spacer(Modifier.height(26.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "⌫").forEach { k ->
                            PinKey(k, key, if (k == "1") first else null) { tap(k) }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (canForgot) PinForgotLink { forgot = true }
                        Text(L("Cancel"), color = Theme.secondary, fontSize = 20.sp,
                            modifier = Modifier.focusLift(1.08f, dimWhenIdle = true, onClick = onDismiss).padding(12.dp))
                    }
                }
            } else {
                // 3×4 toetsenblok, zo groot als past (kleine schermen / liggend)
                val gap = 12.dp
                val byHeight = (h - 230.dp - gap * 3) / 4
                val byWidth = (w - 48.dp - gap * 2) / 3
                val key: Dp = minOf(byHeight, byWidth).coerceIn(44.dp, 72.dp)
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = h)
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    PinHeader(profile, if (h < 520.dp) 52.dp else 76.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 420.dp))
                    Spacer(Modifier.height(16.dp))
                    PinBoxes(pin, reveal, 46.dp, 54.dp, 24, shake.value)
                    PinChecking(checking, 14)
                    PinWait(wait, 14)
                    Spacer(Modifier.height(18.dp))
                    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "⌫"))
                    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        rows.forEach { r ->
                            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                                r.forEach { k ->
                                    if (k.isEmpty()) Spacer(Modifier.size(key))
                                    else PinKey(k, key, if (k == "1") first else null, RoundedCornerShape(14.dp)) { tap(k) }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    if (canForgot) PinForgotLink { forgot = true }
                    Text(L("Cancel"), color = Theme.secondary, fontSize = 16.sp,
                        modifier = Modifier.focusLift(1.08f, onClick = onDismiss).padding(12.dp))
                }
            }
        }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    }
    if (forgot) {
        val targetId = profile?.id ?: Store.mainProfileID
        PinForgotDialog(targetId, onClose = { forgot = false }, onReset = { forgot = false; onDismiss() })
    }
}

/** Ronde profielfoto bovenaan; zonder profiel het slotje. */
@Composable
private fun PinHeader(profile: ViewerProfile?, size: Dp) {
    val p = profile ?: Store.activeProfile
    if (p != null) ProfileAvatar(p, size) else Icon(Icons.Default.Lock, null, tint = Theme.red, modifier = Modifier.size(size * 0.5f))
}

/** Build 189: vier losse vakjes; het laatste cijfer is even zichtbaar ([Build189Logic.PinBoxes]), de rest een punt. */
@Composable
private fun PinBoxes(pin: String, reveal: Int, w: Dp, h: Dp, fontSize: Int, shake: Float) {
    val active = Build189Logic.PinBoxes.activeIndex(pin)
    val shape = RoundedCornerShape(8.dp)
    Row(Modifier.offset(x = shake.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (i in 0 until Build189Logic.PinBoxes.LENGTH) {
            Box(Modifier.size(w, h).clip(shape)
                .border(2.dp, if (i == active || i < pin.length) Color.White else Color.White.copy(alpha = 0.3f), shape),
                contentAlignment = Alignment.Center) {
                Text(Build189Logic.PinBoxes.cell(pin, i, reveal), color = Color.White, fontSize = fontSize.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun PinForgotLink(onClick: () -> Unit) {
    Text(L("Forgot PIN?"), color = Color.White, fontSize = 15.sp, textDecoration = TextDecoration.Underline,
        modifier = Modifier.focusLift(1.08f, onClick = onClick).padding(12.dp))
}

/**
 * Build 189: "Pincode vergeten?" (alleen ingelogd). Met het wachtwoord van het Bingr-account wordt de pincode van dit
 * profiel (zonder profiel: van het hoofdprofiel) verwijderd, zoals bij Instellingen > Pincode uitzetten; daarna kies je
 * zelf een nieuwe. Het wachtwoord gaat alleen naar het inloggen van Bingr zelf en wordt niet bewaard.
 */
@Composable
private fun PinForgotDialog(targetId: String?, onClose: () -> Unit, onReset: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun submit() {
        val email = Account.email ?: return
        if (busy || password.isEmpty()) return
        busy = true; error = null
        scope.launch {
            try {
                Account.signIn(email, password)
                if (targetId != null) ParentalControl.setPIN(null, targetId) else ParentalControl.setPIN(null)
                if (targetId == null || Store.isMain(targetId)) Store.updateParental { it.copy(enabled = false) }
                ParentalControl.pinSucceeded()
                onReset()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: AccountException) {
                error = e.message?.takeIf { it.isNotBlank() } ?: L("Something went wrong. Please try again.")
            } catch (_: Exception) {
                error = L("Something went wrong. Please try again.")
            }
            busy = false
        }
    }
    S2FullDialog(onClose) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(L("Forgot PIN?"), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(L("Enter your Bingr password to remove the PIN. You can choose a new one afterwards in Settings."),
                color = Theme.secondary, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 460.dp))
            Spacer(Modifier.height(18.dp))
            TextField(
                value = password, onValueChange = { password = it; error = null }, singleLine = true,
                placeholder = { Text(L("Password"), color = Theme.secondary.copy(alpha = 0.7f)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                shape = RoundedCornerShape(50),
                colors = TextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedContainerColor = Theme.elevated, unfocusedContainerColor = Theme.elevated,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()
            )
            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = Theme.red, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 460.dp))
            }
            Spacer(Modifier.height(16.dp))
            Box(Modifier.focusLift(1.06f, onClick = { submit() }).clip(RoundedCornerShape(50)).background(Theme.red)
                .padding(horizontal = 28.dp, vertical = 12.dp)) {
                Text(if (busy) L("Checking…") else L("Remove PIN"), color = Theme.onAccent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            Text(L("Cancel"), color = Theme.secondary, fontSize = 16.sp,
                modifier = Modifier.focusLift(1.08f, onClick = onClose).padding(12.dp))
        }
    }
}

/** Build 146: de pincode wordt gecontroleerd (afleiden van de hash). */
@Composable
private fun PinChecking(checking: Boolean, size: Int) {
    if (!checking) return
    Spacer(Modifier.height(10.dp))
    Text(L("Checking…"), color = Theme.secondary, fontSize = size.sp, textAlign = TextAlign.Center)
}

/** Build 141: melding tijdens de wachttijd na te veel foute pincodes. */
@Composable
private fun PinWait(waitMs: Long, size: Int) {
    if (waitMs <= 0) return
    Spacer(Modifier.height(10.dp))
    Text(L("Too many incorrect PINs. Try again in %@.", Build141Logic.PinLockout.shortText(waitMs)),
        color = Theme.red, fontSize = size.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 520.dp))
}

@Composable
internal fun PinKey(k: String, size: Dp, fr: FocusRequester?, shape: androidx.compose.ui.graphics.Shape = CircleShape, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val tv = AppState.isTV
    Box(
        Modifier.size(size)
            .then(if (fr != null) Modifier.focusRequester(fr) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .focusLift(if (tv) 1.15f else 1.06f, onClick = onClick)
            .clip(shape)
            .background(if (focused && tv) Color.White else Theme.elevated)
            .then(if (focused && !tv) Modifier.border(2.dp, Color.White, shape) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        val fg = if (focused && tv) Color.Black else Color.White
        if (k == "⌫") {
            Icon(Icons.AutoMirrored.Filled.Backspace, L("Delete"), tint = fg, modifier = Modifier.size(size * 0.38f))
        } else {
            Text(k, color = fg, fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * Nieuwe pincode instellen (2× invoeren) voor een profiel (null = actief profiel, anders hoofdaccount).
 * Daarna is alles weer op slot. [enableParental] zet ook het kinderslot aan (hoofdaccount).
 */
@Composable
fun PinSetupScreen(profileID: String? = null, enableParental: Boolean = false, onClose: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var first by remember { mutableStateOf<String?>(null) }
    val prof = profileID?.let { id -> Store.profiles.firstOrNull { it.id == id } }   // build 189: profielfoto bovenaan
    key(step) {
        if (step == 0) {
            PinPad(L("Choose a 4-digit PIN"), onDismiss = onClose, guarded = false, profile = prof) { pin -> first = pin; step = 1; false }
        } else {
            PinPad(L("Enter the PIN again"), onDismiss = onClose, guarded = false, profile = prof) { pin ->
                if (pin != first) { step = 0; false }
                else {
                    val id = profileID ?: Store.activeProfileID ?: Store.mainProfileID
                    if (id != null) ParentalControl.setPIN(pin, id) else ParentalControl.setPIN(pin)
                    if (enableParental) Store.updateParental { it.copy(enabled = true) }
                    ParentalControl.lock()
                    true
                }
            }
        }
    }
}

/**
 * Build 141 (H1): pincode wijzigen. Heeft het profiel al een pincode, dan eerst de huidige (die van het profiel, of die
 * van het hoofdaccount); daarna pas een nieuwe kiezen ([PinSetupScreen]). Zonder pincode meteen kiezen.
 */
@Composable
fun PinChangeScreen(profileID: String? = null, onClose: () -> Unit) {
    val id = profileID ?: Store.activeProfileID ?: Store.mainProfileID
    val has = if (id != null) ParentalControl.hasPIN(id) else ParentalControl.hasPIN
    var verified by remember { mutableStateOf(!has) }
    val ok = remember { booleanArrayOf(false) }
    if (!verified) {
        PinPad(L("Enter your current PIN"), onDismiss = { if (ok[0]) verified = true else onClose() },
            profile = id?.let { pid -> Store.profiles.firstOrNull { it.id == pid } }) { pin ->
            ok[0] = (if (id != null) ParentalControl.verify(pin, id) else ParentalControl.verify(pin)) || ParentalControl.check(pin)
            ok[0]
        }
    } else {
        PinSetupScreen(profileID = profileID, onClose = onClose)
    }
}

/**
 * Build 141 (H1): iets van het kinderslot uitzetten of een groep ontgrendelen mag alleen met de pincode van het
 * hoofdaccount. Zonder pincode (nog niet ingesteld) gaat [onOK] meteen.
 */
@Composable
fun MainPinGate(title: String, onDismiss: () -> Unit, onOK: () -> Unit) {
    val main = Store.mainProfileID
    val needed = if (main != null) ParentalControl.hasPIN(main) else ParentalControl.hasPIN
    if (!needed) { LaunchedEffect(Unit) { onOK(); onDismiss() }; return }
    PinPad(title, onDismiss = onDismiss) { pin ->
        val good = if (main != null) ParentalControl.verify(pin, main) else ParentalControl.verify(pin)
        if (good) onOK()
        good
    }
}
