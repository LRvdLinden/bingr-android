package nl.lrvdlinden.bingr

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * Build 167: Bingr wordt alleen Lifetime (docs/ACCOUNT.md in iptv-ios-appletv, "Build 167: alleen Lifetime").
 *
 * - Na de overgangsdatum geeft de server een gratis account `access: "activate"` (en `stream_start` 403
 *   activation_required). Dan staat over alles heen het scherm Activeer Lifetime ([ActivateLifetimeScreen]); de
 *   gegevens (servers, lijsten, profielen, instellingen) blijven gewoon bewaard.
 * - Daarvóór, vanaf de aankondiging, een kaart op Home ([LifetimeNoticeCard]) met de eigen kortingscode en de datum.
 *
 * Telefoon/tablet: de knop opent `upgrade.url` met `?code=` in de browser. Android TV / Google TV / Fire TV: QR-code en adres.
 * Pure logica in Build167Logic.kt.
 */
object Activation {
    /** `stream_start` gaf 403 activation_required (ook als het bewaarde abonnement nog "full" zegt). */
    var blocked by mutableStateOf(false)
        private set

    /** Het scherm Activeer Lifetime staat erover. */
    val required: Boolean
        get() = Build167Logic.Access.mustActivate(Account.isSignedIn && Account.configured, Entitlements.access, blocked)

    fun streamBlocked() {
        blocked = true
        Entitlements.launchFetch(force = true)
    }

    fun cleared() { blocked = false }

    /** Betaalpagina (met de eigen code) in de browser; false = geen browser of geen geldig adres (dan de QR-code). */
    fun openUpgrade(ctx: Context): Boolean {
        val url = Entitlements.upgradeUrlWithCode
        if (!Build141Logic.TrustedHosts.isWebURL(url)) return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(intent) }.isSuccess
    }

    // MARK: aankondigingskaart wegklikken (per account)

    private fun dismissKey(): String? = Account.userId?.let { "lifetime.notice.dismissed.$it" }

    /** Telt op bij elk wegklikken, zodat de kaart opnieuw kijkt. */
    var dismissTick by mutableLongStateOf(0L)
        private set

    /** Wanneer dit account de kaart het laatst wegklikte (0 = nooit). */
    fun dismissedAt(): Long = dismissKey()?.let { Prefs.long(it, 0L) } ?: 0L

    fun dismissNotice() {
        val k = dismissKey() ?: return
        Prefs.put(k, System.currentTimeMillis())
        dismissTick++
    }

    fun showNotice(dismissedAt: Long): Boolean {
        if (Store.activeProfile?.kids == true) return false
        return Build167Logic.Notice.showCard(Entitlements.lifetimeOnly, Entitlements.isFree, Entitlements.access,
            System.currentTimeMillis(), dismissedAt.takeIf { it > 0L })
    }

    /** "7 december 2026" in de app-taal en de tijdzone van het apparaat. */
    fun day(ms: Long): String = Build167Logic.Dates.day(ms, TimeZone.getDefault(), Localizer.locale)

    /** Laatste geldige dag ("t/m 6 december 2026" bij een eindtijd van 7 december 00:00). */
    fun through(ms: Long): String = Build167Logic.Dates.through(ms, TimeZone.getDefault(), Localizer.locale)
}

/** De eigen kortingscode, groot, met de korting en de geldigheidsdatum (alleen Android/web; de Apple-apps tonen hem nooit). */
@Composable
fun LifetimeCodeBox(modifier: Modifier = Modifier) {
    val code = Entitlements.lifetimeCode ?: return
    val lo = Entitlements.lifetimeOnly
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Theme.imdb.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(horizontal = tvs(16), vertical = tvs(12)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(tvs(4)),
    ) {
        val pct = lo?.discountPct
        Text(if (pct != null) L("Your personal code for %d%% off", pct) else L("Your personal discount code"),
            color = Theme.secondary, fontSize = tsp(12), textAlign = TextAlign.Center)
        Text(code, color = Color.White, fontSize = tsp(22), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center)
        lo?.codeValidUntil?.let { until ->
            Text(L("Valid through %@", Activation.through(until)), color = Theme.secondary, fontSize = tsp(12), textAlign = TextAlign.Center)
        }
        Text(L("The code is filled in automatically on bingr.online."), color = Theme.secondary, fontSize = tsp(11),
            textAlign = TextAlign.Center)
    }
}

/** QR-code met de betaalpagina (met de eigen code) en het adres eronder. */
@Composable
fun LifetimeUpgradeQR(modifier: Modifier = Modifier) {
    val url = Entitlements.upgradeUrlWithCode
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(tvs(8))) {
        QrCodeImage(url, tvs(170))
        Text(Build167Logic.Links.display(url), color = Color.White, fontSize = tsp(14), fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center)
        Text(L("Scan the QR code with your phone's camera to activate Lifetime on bingr.online."), color = Theme.secondary,
            fontSize = tsp(12), textAlign = TextAlign.Center)
    }
}

/**
 * Build 167: Activeer Lifetime (schermvullend, over alles heen, zoals "Te veel apparaten"). Alleen uitleg, de betaalpagina
 * (telefoon: knop, tv: QR-code), opnieuw kijken, Support, Uitloggen en Account verwijderen. Niets wordt gewist.
 */
@Composable
fun ActivateLifetimeScreen() {
    val ctx = LocalContext.current
    val tv = AppState.isTV
    val link = Entitlements.upgradeLink
    var noBrowser by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var supportNew by remember { mutableStateOf(false) }
    var supportOpen by remember { mutableStateOf<Int?>(null) }
    val first = rememberInitialFocus(key = "activate")
    val buttons = Build167Logic.Screen.buttons(tv, link)
    val showQR = Build167Logic.Screen.showQR(tv, link, noBrowser)

    // betalen gebeurt op een ander apparaat of in de browser: zolang dit scherm openstaat af en toe opnieuw ophalen
    LaunchedEffect(Unit) {
        Entitlements.launchFetch(force = true)
        while (true) {
            delay(Build167Logic.Screen.REFRESH_MS)
            Entitlements.launchFetch(force = true)
        }
    }
    // terug = de app naar de achtergrond (niets eronder is bruikbaar)
    BackHandler { MainActivity.current?.moveTaskToBack(true) }

    Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = tvs(28)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BingrLogo(tsp(30))
            Spacer(Modifier.height(tvs(22)))
            Column(
                Modifier.widthIn(max = if (tv && showQR) 900.dp else 560.dp).fillMaxWidth().padding(horizontal = Theme.edge),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(tvs(12)),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LifetimeMark(tvs(30))
                    Spacer(Modifier.width(tvs(10)))
                    Text(L("Activate Bingr Lifetime"), color = Color.White, fontSize = tsp(24), fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center)
                }
                Text(L("Bingr is now Lifetime only. Your servers, lists, profiles and settings are saved."),
                    color = Theme.secondary, fontSize = tsp(15), textAlign = TextAlign.Center)
                if (!link) {
                    Text(L("Your account hasn't been activated yet."), color = Color.White, fontSize = tsp(15),
                        fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                } else {
                    Text(L("Activate Lifetime once and keep using Bingr on all your devices."), color = Color.White,
                        fontSize = tsp(14), textAlign = TextAlign.Center)
                    Text(L("%@ one-time", Entitlements.price), color = Color.White, fontSize = tsp(18), fontWeight = FontWeight.Bold)
                    Text(L("Pay once, keep it forever. No subscription."), color = Theme.secondary, fontSize = tsp(12),
                        textAlign = TextAlign.Center)
                    if (showQR && tv) {
                        Row(horizontalArrangement = Arrangement.spacedBy(tvs(24)), verticalAlignment = Alignment.CenterVertically) {
                            LifetimeCodeBox(Modifier.weight(1f))
                            LifetimeUpgradeQR(Modifier.widthIn(max = 300.dp))
                        }
                    } else {
                        LifetimeCodeBox(Modifier.fillMaxWidth())
                        if (showQR) LifetimeUpgradeQR()
                    }
                }
                error?.let { Text(it, color = Theme.red, fontSize = tsp(13), textAlign = TextAlign.Center) }
                if (working) CircularProgressIndicator(color = Theme.red)
                Spacer(Modifier.height(tvs(4)))
                buttons.forEachIndexed { i, b ->
                    val mod = Modifier.fillMaxWidth().widthIn(max = 420.dp).let { if (i == 0) it.focusRequester(first) else it }
                    when (b) {
                        Build167Logic.Screen.Button.OPEN_UPGRADE -> if (!noBrowser) {
                            NetflixButton(L("Activate on bingr.online"), Icons.Default.OpenInBrowser, kind = NetflixKind.ACCENT,
                                modifier = mod) { if (!Activation.openUpgrade(ctx)) noBrowser = true }
                        }
                        Build167Logic.Screen.Button.CHECK_AGAIN -> NetflixButton(L("Check again"), Icons.Default.Refresh,
                            kind = NetflixKind.PRIMARY, modifier = mod) {
                            if (working) return@NetflixButton
                            working = true; error = null
                            AppScope.launch {
                                val ok = runCatching { Entitlements.fetch() }.getOrDefault(false)
                                if (!ok) error = L("Something went wrong. Please try again.")
                                working = false
                            }
                        }
                        Build167Logic.Screen.Button.SUPPORT -> NetflixButton(L("Support"), Icons.Default.SupportAgent,
                            kind = NetflixKind.SECONDARY, modifier = mod) { supportNew = true }
                        Build167Logic.Screen.Button.SIGN_OUT -> NetflixButton(L("Sign out"), Icons.AutoMirrored.Filled.Logout,
                            kind = NetflixKind.SECONDARY, modifier = mod) { confirmSignOut = true }
                        Build167Logic.Screen.Button.DELETE_ACCOUNT -> NetflixButton(L("Delete account"), Icons.Default.Delete,
                            kind = NetflixKind.SECONDARY, modifier = mod) { confirmDelete = true }
                    }
                }
            }
        }
    }

    if (confirmSignOut) {
        S2ActionDialog(
            L("Sign out of Bingr on this device?"),
            listOf(S2Action(L("Sign out"), Icons.AutoMirrored.Filled.Logout, destructive = true) {
                working = true
                AppScope.launch { runCatching { Account.signOut() }; AppState.signedOut(); working = false }
            }),
            onDismiss = { confirmSignOut = false }
        )
    }
    // H1: met het huidige wachtwoord, zoals in Instellingen > Account
    if (confirmDelete) {
        S2DeleteAccountDialog(onDismiss = { confirmDelete = false }) { password ->
            error = null; working = true
            AppScope.launch {
                try { Account.deleteAccount(password); AppState.signedOut() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: AccountException) { error = e.message }
                catch (e: Exception) { error = L("Something went wrong. Please try again.") }
                working = false
            }
        }
    }
    if (supportNew) SupportNewScreen(onClose = { supportNew = false }, onCreated = { n -> supportNew = false; supportOpen = n })
    supportOpen?.let { n ->
        SupportTicketScreen(n, onClose = { supportOpen = null }, onNew = { supportOpen = null; supportNew = true })
    }
}

/**
 * Build 167: aankondiging op Home vóór de overgangsdatum ("Bingr wordt Lifetime op 7 december 2026"), met de eigen code,
 * de geldigheidsdatum en de knop. Weg te klikken (komt na een paar dagen terug, in de laatste week elke dag).
 */
@Composable
fun LifetimeNoticeCard(modifier: Modifier = Modifier) {
    val dismissed = remember(Account.userId, Activation.dismissTick) { Activation.dismissedAt() }
    if (!Activation.showNotice(dismissed)) return
    val ctx = LocalContext.current
    val tv = AppState.isTV
    val lo = Entitlements.lifetimeOnly ?: return
    Box(modifier.fillMaxWidth().padding(horizontal = Theme.edge), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 760.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Theme.elevated)
                .border(1.dp, Theme.imdb.copy(alpha = 0.45f), RoundedCornerShape(14.dp)).padding(tvs(16)),
            verticalArrangement = Arrangement.spacedBy(tvs(10)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LifetimeMark(tvs(24))
                Spacer(Modifier.width(tvs(10)))
                Text(
                    lo.cutoverAt?.let { L("Bingr is going Lifetime on %@", Activation.day(it)) } ?: L("Bingr is going Lifetime"),
                    color = Color.White, fontSize = tsp(17), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                )
                Box(Modifier.size(tvs(34)).clip(RoundedCornerShape(17.dp)).focusLift(onClick = { Activation.dismissNotice() }),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Close, L("Close"), tint = Color.White, modifier = Modifier.size(tvs(18)))
                }
            }
            Text(L("From then on, Bingr needs Lifetime. Your servers, lists, profiles and settings stay saved."),
                color = Theme.secondary, fontSize = tsp(13))
            LifetimeCodeBox(Modifier.fillMaxWidth())
            if (Entitlements.upgradeLink) {
                NetflixButton(L("Get Lifetime"), LifetimeIcon, kind = NetflixKind.ACCENT) {
                    // telefoon: meteen de betaalpagina met de code; tv (of geen browser): het venster met de QR-code
                    if (tv || !Activation.openUpgrade(ctx)) {
                        Upgrade.show(null)
                        if (!tv) Upgrade.forceQR = true
                    }
                }
            }
        }
    }
}

/**
 * Build 167, aanvulling 1 (eerst betalen, dan het account): het welkomstscherm vóór het inloggen. De publieke
 * `billing config` wordt bewaard (Prefs), zodat het ook bij een trage verbinding meteen klopt; zonder config of zonder
 * netwerk het welkomstscherm zoals nu.
 */
object LifetimeWelcome {
    private const val KEY = "lifetime.public.config"

    var config by mutableStateOf<Build167Logic.Welcome.Config?>(null)
        private set
    private var loaded = false

    private fun parse(j: kotlinx.serialization.json.JsonElement?): Build167Logic.Welcome.Config? {
        val lo = j.jObj?.get("lifetime_only") as? kotlinx.serialization.json.JsonObject
        return Build167Logic.Welcome.parse(lo != null, lo.jB("enabled"), lo.jS("cutover_at"), lo.jB("after_cutover"),
            lo.jI("trial_days"), parseTime = { Net.parseISODate(it) })
    }

    fun load() {
        if (loaded) return
        loaded = true
        config = Prefs.string(KEY)?.let { s -> runCatching { parse(Http.json.parseToJsonElement(s)) }.getOrNull() }
    }

    /** Ophalen en bewaren; een fout laat de bewaarde config staan. */
    suspend fun refresh() {
        load()
        val j = runCatching { Account.billingConfigJson() }.getOrNull() ?: return
        val lo = j.jObj?.get("lifetime_only") as? kotlinx.serialization.json.JsonObject
        // oudere server zonder lifetime_only: niets bewaren (zoals nu)
        if (lo == null) { Prefs.remove(KEY); config = null; return }
        Prefs.put(KEY, buildJsonObjectOf(lo))
        config = parse(j)
    }

    private fun buildJsonObjectOf(lo: kotlinx.serialization.json.JsonObject): String =
        kotlinx.serialization.json.JsonObject(mapOf("lifetime_only" to lo)).toString()

    val buyFirst: Boolean get() = Build167Logic.Welcome.buyFirst(config, System.currentTimeMillis())

    /** Gastaankoop op bingr.online in de browser; false = geen browser (dan de QR-code). */
    fun openGuest(ctx: Context): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(Build167Logic.Welcome.GUEST_URL))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(intent) }.isSuccess
    }
}

/**
 * Welkomstscherm na de overgangsdatum (nog niet ingelogd): Koop Lifetime (telefoon: knop naar
 * https://bingr.online/upgrade/?guest=1; tv: QR-code met dat adres) en daaronder Ik heb al een account / Inloggen.
 * Na betalen maakt de koper zijn account op bingr.online (of logt in); daarna gewoon inloggen in de app.
 */
@Composable
fun LifetimeWelcomeScreen(onSignIn: () -> Unit) {
    val ctx = LocalContext.current
    val tv = AppState.isTV
    var noBrowser by remember { mutableStateOf(false) }
    val showQR = Build167Logic.Welcome.showQR(tv, noBrowser)
    val first = rememberInitialFocus(key = "welcome")
    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(vertical = tvs(28)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BingrLogo(tsp(34))
        Spacer(Modifier.height(tvs(22)))
        Column(
            Modifier.widthIn(max = if (tv) 900.dp else 520.dp).fillMaxWidth().padding(horizontal = Theme.edge),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(tvs(12)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LifetimeMark(tvs(28))
                Spacer(Modifier.width(tvs(10)))
                Text(L("Bingr Lifetime"), color = Color.White, fontSize = tsp(24), fontWeight = FontWeight.Bold)
            }
            Text(L("Buy Bingr Lifetime first, then create your account with the same email address."),
                color = Theme.secondary, fontSize = tsp(15), textAlign = TextAlign.Center)
            Text(L("Pay once, keep it forever. No subscription."), color = Theme.secondary, fontSize = tsp(12),
                textAlign = TextAlign.Center)
            if (showQR) {
                val url = Build167Logic.Welcome.GUEST_URL
                QrCodeImage(url, tvs(170))
                Text(Build167Logic.Links.display(url), color = Color.White, fontSize = tsp(14), fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center)
                Text(L("Scan the QR code with your phone's camera to buy Lifetime on bingr.online."), color = Theme.secondary,
                    fontSize = tsp(12), textAlign = TextAlign.Center)
            } else {
                NetflixButton(L("Buy Lifetime"), LifetimeIcon, kind = NetflixKind.ACCENT,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp).focusRequester(first)) {
                    if (!LifetimeWelcome.openGuest(ctx)) noBrowser = true
                }
            }
            Spacer(Modifier.height(tvs(4)))
            NetflixButton(L("I already have an account"), kind = NetflixKind.SECONDARY,
                modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp).let { if (showQR) it.focusRequester(first) else it }) { onSignIn() }
            Text(L("Already paid? Sign in with the account you created on bingr.online."), color = Theme.secondary,
                fontSize = tsp(12), textAlign = TextAlign.Center)
        }
    }
}
