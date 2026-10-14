package nl.lrvdlinden.bingr

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Foutkleur van het Netflix-inlogscherm (oranje). */
private val AuthError = Color(0xFFE87C03)

private enum class AuthMode { SIGN_IN, SIGN_UP, RESET }

/**
 * Inlogscherm (verplicht Bingr-account, zie docs/ACCOUNT.md in de iOS-repo).
 * Telefoon/tablet: Netflix-formulier op een vervaagde postercollage. Tv: QR-code + code links, "OR", e-mail rechts.
 */
@Composable
fun AuthScreen() {
    var justSignedIn by remember { mutableStateOf(false) }
    // ingelogd (formulier of tv-code) → door naar "Wie kijkt er?"; op tv eerst kort "Signed in as …"
    LaunchedEffect(Account.isSignedIn) {
        if (Account.isSignedIn) {
            if (AppState.isTV) { justSignedIn = true; delay(1600) }
            AppState.signedIn()
        } else {
            // uitgelogd (bv. Cancel bij "Too many devices"): weer het inlogscherm
            justSignedIn = false
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AuthBackdrop()
        when {
            !Account.configured -> AuthNotConfigured()
            justSignedIn -> AuthSignedInBanner()
            AppState.isTV -> AuthTv()
            else -> AuthPhone()
        }
    }
}

/** Gedimde, licht vervaagde collage van covers; zonder covers een donker verloop met rood. */
@Composable
private fun AuthBackdrop() {
    val posters = remember { runCatching { Playlists.splashPosters() }.getOrDefault(emptyList()) }
    if (posters.isNotEmpty()) {
        val columns = if (AppState.isTV) 8 else 4
        Column(Modifier.fillMaxSize().blur(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (r in 0 until 6) {
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in 0 until columns) {
                        val url = posters[(r * columns + c) % posters.size]
                        coil.compose.AsyncImage(
                            model = url, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(Theme.card)
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Black.copy(alpha = 0.6f), Color.Black.copy(alpha = 0.9f)))
        ))
    } else {
        Box(Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(Color(0xFF3A0508), Color.Black, Color(0xFF140203), Color.Black))
        ))
    }
}

@Composable
private fun AuthSignedInBanner() {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        BingrLogo(tsp(34))
        Spacer(Modifier.height(tvs(24)))
        Text(L("Signed in as %@", Account.email ?: ""), color = Color.White, fontSize = tsp(20), fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center)
    }
}

/** Build zonder Supabase-sleutels: melding + "Continue without account". */
@Composable
private fun AuthNotConfigured() {
    val fr = remember { FocusRequester() }
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(Theme.edge),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        BingrLogo(tsp(34))
        Spacer(Modifier.height(tvs(32)))
        Text(L("Accounts are not configured in this build."), color = Color.White, fontSize = tsp(17), textAlign = TextAlign.Center)
        Spacer(Modifier.height(tvs(24)))
        AuthRedButton(L("Continue without account"), busy = false, modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().focusRequester(fr)) {
            AppState.skippedAccount = true
            AppState.signedIn()
        }
    }
    LaunchedEffect(Unit) { if (AppState.isTV) runCatching { fr.requestFocus() } }
}

// MARK: telefoon / tablet

@Composable
private fun AuthPhone() {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(28.dp))
        BingrLogo(34.sp)
        Spacer(Modifier.height(36.dp))
        AuthEmailCard(onBack = null)
        Spacer(Modifier.height(40.dp))
    }
}

/**
 * Kaart met het e-mailformulier: Sign In / Create account / Reset password.
 * [onBack] (tv): terug naar de keuze QR-code of e-mail.
 */
@Composable
private fun AuthEmailCard(onBack: (() -> Unit)?) {
    var mode by remember { mutableStateOf(AuthMode.SIGN_IN) }
    var email by remember { mutableStateOf(Account.email ?: "") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val busy = Account.busy || ProfileSync.syncing || SourceSync.syncing
    val firstField = remember { FocusRequester() }
    val passwordField = remember { FocusRequester() }

    BackHandler(enabled = mode != AuthMode.SIGN_IN || onBack != null) {
        if (mode != AuthMode.SIGN_IN) { mode = AuthMode.SIGN_IN; error = null; info = null } else onBack?.invoke()
    }

    fun submit() {
        if (busy) return
        error = null; info = null
        scope.launch {
            try {
                when (mode) {
                    AuthMode.SIGN_IN -> Account.signIn(email, password)
                    AuthMode.SIGN_UP -> if (!Account.signUp(email, password)) {
                        info = L("Check your inbox to confirm your email address, then sign in.")
                        mode = AuthMode.SIGN_IN; password = ""
                    }
                    AuthMode.RESET -> { info = Account.recover(email); mode = AuthMode.SIGN_IN }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AccountException) {
                error = e.message
            } catch (e: Exception) {
                error = L("Something went wrong. Please try again.")
            }
        }
    }

    Column(
        Modifier.widthIn(max = if (AppState.isTV) 560.dp else 450.dp).fillMaxWidth()
            .clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.75f))
            .padding(horizontal = tvs(28), vertical = tvs(32)),
        verticalArrangement = Arrangement.spacedBy(tvs(14))
    ) {
        Text(
            when (mode) { AuthMode.SIGN_IN -> L("Sign In"); AuthMode.SIGN_UP -> L("Create account"); AuthMode.RESET -> L("Reset password") },
            color = Color.White, fontSize = tsp(30), fontWeight = FontWeight.Bold
        )
        AuthField(
            email, { email = it }, L("Email"), Modifier.focusRequester(firstField),
            keyboardType = KeyboardType.Email,
            imeAction = if (mode == AuthMode.RESET) ImeAction.Done else ImeAction.Next,
            onIme = { if (mode == AuthMode.RESET) submit() else runCatching { passwordField.requestFocus() } }
        )
        if (mode != AuthMode.RESET) {
            AuthField(
                password, { password = it }, L("Password"), Modifier.focusRequester(passwordField),
                keyboardType = KeyboardType.Password, hidden = !showPassword, imeAction = ImeAction.Done, onIme = { submit() },
                trailing = {
                    Text(if (showPassword) L("Hide") else L("Show"), color = Theme.secondary, fontSize = tsp(13), fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(end = 6.dp).focusLift(1.06f) { showPassword = !showPassword }.padding(8.dp))
                }
            )
        }
        // build 82: geblokkeerd account (ook na uitloggen door een blokkade tijdens een sessie)
        (error ?: Account.bannedMessage.takeIf { Account.blockedNotice })?.let { Text(it, color = AuthError, fontSize = tsp(14)) }
        info?.let { Text(it, color = Theme.green, fontSize = tsp(14)) }
        AuthRedButton(
            when (mode) { AuthMode.SIGN_IN -> L("Sign In"); AuthMode.SIGN_UP -> L("Create account"); AuthMode.RESET -> L("Send reset link") },
            busy = busy, modifier = Modifier.fillMaxWidth().padding(top = tvs(6)), onClick = { submit() }
        )
        if (mode == AuthMode.SIGN_IN) {
            AuthLink(L("Forgot password?"), Modifier.align(Alignment.CenterHorizontally)) {
                mode = AuthMode.RESET; error = null; info = null
            }
        }
        Spacer(Modifier.height(tvs(8)))
        when (mode) {
            AuthMode.SIGN_IN -> AuthSwitch(L("New to Bingr?"), L("Sign up now.")) { mode = AuthMode.SIGN_UP; error = null; info = null }
            AuthMode.SIGN_UP, AuthMode.RESET -> AuthSwitch(L("Already have an account?"), L("Sign in.")) {
                mode = AuthMode.SIGN_IN; error = null; info = null
            }
        }
    }
    LaunchedEffect(Unit) { if (AppState.isTV) runCatching { firstField.requestFocus() } }
}

@Composable
private fun AuthField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType,
    hidden: Boolean = false,
    imeAction: ImeAction,
    onIme: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        trailingIcon = trailing,
        visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onAny = { onIme() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.White, unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
            focusedLabelColor = Color.White, unfocusedLabelColor = Theme.secondary, cursorColor = Theme.red,
            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
            focusedContainerColor = Color(0xFF333333).copy(alpha = 0.7f), unfocusedContainerColor = Color(0xFF333333).copy(alpha = 0.7f),
        ),
        modifier = modifier.fillMaxWidth()
    )
}

/** Rode volle knop; spinner tijdens het laden. Op tv bij focus een witte rand. */
@Composable
private fun AuthRedButton(title: String, busy: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .focusLift(1.04f) { if (!busy) onClick() }
            .clip(RoundedCornerShape(6.dp))
            .background(Theme.red)
            .then(if (focused && AppState.isTV) Modifier.border(3.dp, Color.White, RoundedCornerShape(6.dp)) else Modifier)
            .heightIn(min = tvs(48))
            .padding(horizontal = 20.dp, vertical = tvs(12)),
        contentAlignment = Alignment.Center
    ) {
        if (busy) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(tvs(22)))
        else Text(title, color = Color.White, fontSize = tsp(16), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun AuthLink(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(text, color = if (focused) Color.White else Theme.secondary.copy(alpha = 0.95f), fontSize = tsp(15),
        fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
        modifier = modifier.onFocusChanged { focused = it.isFocused }.focusLift(1.05f, onClick = onClick).padding(6.dp))
}

/** "New to Bingr? **Sign up now.**" */
@Composable
private fun AuthSwitch(question: String, action: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = Theme.secondary)) { append(question); append(" ") }
            withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.Bold)) { append(action) }
        },
        fontSize = tsp(15),
        modifier = Modifier.onFocusChanged { focused = it.isFocused }.focusLift(1.04f, onClick = onClick)
            .clip(RoundedCornerShape(6.dp))
            .then(if (focused && AppState.isTV) Modifier.border(2.dp, Color.White, RoundedCornerShape(6.dp)) else Modifier)
            .padding(6.dp)
    )
}

// MARK: tv

@Composable
private fun AuthTv() {
    var showEmail by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf<Account.TvCode?>(null) }
    var tvError by remember { mutableStateOf<String?>(null) }
    // tv-code starten en pollen; verloopt de code, dan komt er vanzelf een nieuwe. Loopt door terwijl het e-mailformulier open is.
    LaunchedEffect(Unit) {
        Account.runTvFlow(onCode = { code = it }, onError = { tvError = it })
    }
    if (showEmail) {
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 40.dp), contentAlignment = Alignment.TopCenter) {
            AuthEmailCard(onBack = { showEmail = false })
        }
        return
    }
    val emailButton = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().padding(horizontal = Theme.edge, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BingrLogo(tsp(30))
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
            // links: met je telefoon
            Column(Modifier.weight(1.2f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(L("Sign in with your phone"), color = Color.White, fontSize = tsp(24), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text(L("Scan the QR code with your phone's camera"), color = Theme.secondary, fontSize = tsp(14), textAlign = TextAlign.Center)
                val c = code
                Box(Modifier.size(tvs(170) + 16.dp), contentAlignment = Alignment.Center) {
                    if (c != null) QrCodeImage(c.url, tvs(170))
                    else CircularProgressIndicator(color = Theme.red)
                }
                val host = c?.url?.substringAfter("://")?.substringBefore("?")?.trimEnd('/') ?: "bingr.online/koppelen"
                Text(
                    buildAnnotatedString {
                        append(L("Or go to")); append(" ")
                        withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.Bold)) { append(host) }
                        append(" "); append(L("and enter this code:"))
                    },
                    color = Theme.secondary, fontSize = tsp(14), textAlign = TextAlign.Center
                )
                Text(c?.display ?: "———", color = Color.White, fontSize = tsp(34), fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace, letterSpacing = 4.sp,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Theme.elevated).padding(horizontal = 22.dp, vertical = 6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Theme.red, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(L("Waiting for confirmation…"), color = Theme.secondary, fontSize = tsp(13))
                }
                (Account.bannedMessage.takeIf { Account.blockedNotice } ?: tvError)?.let {
                    Text(it, color = AuthError, fontSize = tsp(13), textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 520.dp))
                }
            }
            // OR
            Column(Modifier.fillMaxHeight().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.weight(1f).width(1.dp).background(Color.White.copy(alpha = 0.25f)))
                Text(L("OR"), color = Theme.secondary, fontSize = tsp(16), fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 12.dp))
                Box(Modifier.weight(1f).width(1.dp).background(Color.White.copy(alpha = 0.25f)))
            }
            // rechts: met e-mail
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                AuthRedButton(L("Sign in with email"), busy = false,
                    modifier = Modifier.widthIn(min = 260.dp, max = 380.dp).focusRequester(emailButton)) { showEmail = true }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { emailButton.requestFocus() } }
}
