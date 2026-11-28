package nl.lrvdlinden.bingr

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
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
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Build 181: Bingr Premium (docs/ACCOUNT.md in iptv-ios-appletv, "Build 181: Bingr Premium" §4, §6 en §10). Zelfde gedrag en
 * teksten als de webspeler (bingr-website web/js/premium.js).
 *
 * - Kies je plan ([PremiumPaywallScreen]): bij `access = "activate"` met `premium.enabled` over alles heen, in plaats van
 *   Activeer Lifetime. Schakelaar Maand / Halfjaar / Jaar, kaart Premium (jaar uitgelicht, "3 dagen gratis" als de proef mag)
 *   en kaart Lifetime.
 * - Telefoon/tablet: `billing checkout` met het plan, Stripe Checkout in een Custom Tab (vinkje directe levering verplicht,
 *   zoals op bingr.online). Android TV / Google TV / Fire TV: QR-code naar bingr.online/upgrade/?plan=….
 * - Na terugkeer (de app komt weer op de voorgrond) en elke 15 s zolang het scherm openstaat: `billing refresh`.
 * - Abonnement beheren = `billing portal` (Stripe Customer Portal) in een Custom Tab.
 * Pure logica in Build181Logic.kt.
 */
object Premium {
    /** Er is net een Checkout of de portal geopend: bij terugkeer meteen verversen. */
    private var pending = false

    val lang: String get() = if (Localizer.language.startsWith("nl")) "nl" else "en"

    fun planName(plan: String?): String = when (plan) {
        Build181Logic.MONTH -> L("Premium monthly")
        Build181Logic.HALF -> L("Premium every 6 months")
        Build181Logic.YEAR -> L("Premium yearly")
        Build181Logic.LIFETIME -> "Lifetime"
        else -> "Premium"
    }

    /** Naam in Instellingen > Abonnement: een abonnement heet naar zijn plan, nooit "Lifetime". */
    fun subscriptionValue(isFree: Boolean): String {
        val p = Entitlements.premium
        return when {
            Build181Logic.isSubscription(p) -> planName(p?.plan)
            isFree -> L("Free")
            else -> L("Lifetime")
        }
    }

    fun euro(cents: Int): String = Build181Logic.Offer.euro(cents, Localizer.locale)

    /** "Verlengt op …", "Loopt af op …", "Gratis proef tot …", of "". */
    fun statusLine(p: Build181Logic.Premium?): String {
        val (kind, at) = Build181Logic.Status.line(p)
        val d = at?.let { Activation.day(it) } ?: return ""
        return when (kind) {
            Build181Logic.Status.Kind.TRIAL -> L("Free trial until %@", d)
            Build181Logic.Status.Kind.PAYMENT_FAILED -> L("Payment failed: update your payment method before %@", d)
            Build181Logic.Status.Kind.RENEWS -> L("Renews on %@", d)
            Build181Logic.Status.Kind.ENDS -> L("Ends on %@", d)
            Build181Logic.Status.Kind.NONE -> ""
        }
    }

    fun errorText(error: String?): String = if (error == null) L("No internet connection.") else when (Build181Logic.Errors.kind(error)) {
        Build181Logic.Errors.Kind.ALREADY -> L("This account already has Premium.")
        Build181Logic.Errors.Kind.NOT_READY -> L("Paying isn't available yet. Please try again later.")
        Build181Logic.Errors.Kind.OFF -> L("Premium isn't available for this account yet.")
        Build181Logic.Errors.Kind.RATE_LIMITED -> L("Too many attempts. Please wait a few minutes and try again.")
        Build181Logic.Errors.Kind.GENERIC -> L("Checkout didn't work. Please try again or contact support.")
    }

    /** Stripe-pagina in een Custom Tab (anders de browser); false = geen browser. */
    fun openTab(ctx: Context, url: String): Boolean {
        val uri = Uri.parse(url)
        val tab = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setDefaultColorSchemeParams(CustomTabColorSchemeParams.Builder().setToolbarColor(0xFF000000.toInt()).build())
            .build()
        if (ctx !is Activity) tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = try { tab.launchUrl(ctx, uri); true } catch (_: ActivityNotFoundException) { false } catch (_: Exception) { false }
        if (ok) { pending = true; return true }
        val view = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(view) }.isSuccess.also { if (it) pending = true }
    }

    /** Afrekenen: Checkout-sessie bij de server, dan de Custom Tab. Terug: foutmelding of null; [noBrowser] bij geen browser. */
    suspend fun checkout(ctx: Context, plan: String, noBrowser: () -> Unit): String? {
        val r = runCatching { Account.billingCheckout(plan, lang, waiver = true) }.getOrElse { Account.BillingLink(null, "error") }
        val url = r.url ?: run {
            if (Build181Logic.Errors.kind(r.error) == Build181Logic.Errors.Kind.ALREADY) refresh()
            else Funnel.failed(plan, r.error)   // build 193: betaaltrechter
            return errorText(r.error)
        }
        if (openTab(ctx, url)) Funnel.started(plan)
        else { Funnel.failed(plan, "no_browser"); noBrowser() }
        return null
    }

    /** Abonnement beheren (Stripe Customer Portal). Terug: foutmelding of null. */
    suspend fun manage(ctx: Context): String? {
        val r = runCatching { Account.billingPortal(lang) }.getOrElse { Account.BillingLink(null, "error") }
        val url = r.url ?: return if (r.error == null) L("No internet connection.") else L("Couldn’t open Manage subscription. Please try again.")
        return if (openTab(ctx, url)) null else L("Couldn’t open Manage subscription. Please try again.")
    }

    /** Stripe opnieuw laten ophalen (vangnet als de webhook nog niet binnen is) en de entitlements toepassen. */
    suspend fun refresh(): Boolean {
        val j = runCatching { Account.billingRefreshJson(Entitlements.platform) }.getOrNull()
        if (j != null && Entitlements.apply(j)) return true
        return runCatching { Entitlements.fetch() }.getOrDefault(false)
    }

    /** De app komt weer op de voorgrond (na de Custom Tab): verversen. */
    fun resumed() {
        if (!pending) return
        pending = false
        // build 193: terug van de betaalpagina: gelukt of afgebroken melden (betaaltrechter)
        AppScope.launch { refresh(); Funnel.checked(returned = true) }
    }
}

/** Verversen zodra de app terug is van Stripe (ON_START van het proces). */
@Composable
private fun RefreshOnReturn() {
    DisposableEffect(Unit) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_START) Premium.resumed()
        }
        val lc = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle
        lc.addObserver(obs)
        onDispose { lc.removeObserver(obs) }
    }
}

/**
 * Plannenblok: schakelaar Maand / Halfjaar / Jaar, kaart Premium en kaart Lifetime. Telefoon: knoppen naar Stripe Checkout
 * (met het vinkje); tv (of geen browser): QR-code naar bingr.online/upgrade/?plan=… van het gekozen plan.
 */
@Composable
fun PremiumOffer(p: Build181Logic.Premium, modifier: Modifier = Modifier, firstFocus: androidx.compose.ui.focus.FocusRequester? = null) {
    val ctx = LocalContext.current
    val tv = AppState.isTV
    val pr = p.prices
    val trial = p.trialEligible
    var period by remember { mutableStateOf(Build181Logic.YEAR) }
    var choice by remember { mutableStateOf(Build181Logic.YEAR) }
    var waiver by remember { mutableStateOf(false) }
    var noBrowser by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val qr = tv || noBrowser
    RefreshOnReturn()
    // build 193: betaaltrechter, één melding per keer dat de plannen in beeld komen
    LaunchedEffect(Unit) { Funnel.paywallShown() }

    fun pay(plan: String) {
        choice = plan
        if (qr || working) return
        if (!waiver) { error = L("First tick the box to confirm you want to use Premium right away."); return }
        working = true; error = null
        AppScope.launch {
            error = Premium.checkout(ctx, plan) { noBrowser = true }
            working = false
        }
    }

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(tvs(12))) {
        // schakelaar
        Row(Modifier.clip(RoundedCornerShape(50)).background(Theme.card).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Build181Logic.PLANS.forEachIndexed { i, k ->
                val on = k == period
                val save = Build181Logic.Offer.savings(pr, k)
                val label = when (k) { Build181Logic.MONTH -> L("Month"); Build181Logic.HALF -> L("6 months"); else -> L("Year") }
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(if (on) Color.White else Color.Transparent)
                        .let { if (i == 2 && firstFocus != null) it.focusRequester(firstFocus) else it }
                        .focusLift(onClick = { period = k; if (choice != Build181Logic.LIFETIME || !qr) choice = k })
                        .padding(horizontal = tvs(14), vertical = tvs(8)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, color = if (on) Color.Black else Color.White, fontSize = tsp(14), fontWeight = FontWeight.SemiBold)
                    if (save > 0) {
                        Spacer(Modifier.width(6.dp))
                        Text("−$save%", color = if (on) Theme.brandRed else Theme.green, fontSize = tsp(12), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        @Composable
        fun card(highlight: Boolean, selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Theme.elevated)
                    .border(if (highlight || selected) 2.dp else 1.dp,
                        if (selected && qr) Color.White else if (highlight) Theme.brandRed else Color.White.copy(alpha = 0.12f),
                        RoundedCornerShape(14.dp))
                    .let { if (qr) it.focusLift(scale = 1.02f, onClick = onClick) else it }
                    .padding(tvs(16)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(tvs(6)),
            ) { content() }
        }

        val save = Build181Logic.Offer.savings(pr, period)
        val per = when (period) { Build181Logic.MONTH -> L("per month"); Build181Logic.HALF -> L("every 6 months"); else -> L("per year") }
        val cards: @Composable (Modifier) -> Unit = { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(tvs(10))) {
                card(highlight = period == Build181Logic.YEAR, selected = choice != Build181Logic.LIFETIME, onClick = { choice = period; Funnel.started(period) }) {
                    if (period == Build181Logic.YEAR) Tag(L("Most popular · save %d%%", save), Theme.brandRed)
                    else if (save > 0) Tag(L("Save %d%%", save), Theme.card)
                    Text("Premium", color = Color.White, fontSize = tsp(18), fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(Premium.euro(pr.of(period)), color = Color.White, fontSize = tsp(26), fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp))
                        Text(per, color = Theme.secondary, fontSize = tsp(13), modifier = Modifier.padding(bottom = 4.dp))
                    }
                    Build181Logic.Offer.perMonth(pr, period)?.let {
                        Text(L("%@ per month", Premium.euro(it)), color = Theme.secondary, fontSize = tsp(12))
                    }
                    if (trial) Tag(L("3 days free"), Theme.green.copy(alpha = 0.25f), Theme.green)
                    if (!qr) {
                        NetflixButton(if (trial) L("Try 3 days free") else L("Choose Premium"), Icons.Default.OpenInBrowser,
                            kind = NetflixKind.ACCENT, modifier = Modifier.fillMaxWidth()) { pay(period) }
                    }
                }
                card(highlight = false, selected = choice == Build181Logic.LIFETIME, onClick = { choice = Build181Logic.LIFETIME; Funnel.started(Build181Logic.LIFETIME) }) {
                    Text("Lifetime", color = Color.White, fontSize = tsp(18), fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(Premium.euro(pr.lifetime), color = Color.White, fontSize = tsp(22), fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp))
                        Text(L("one-time"), color = Theme.secondary, fontSize = tsp(13), modifier = Modifier.padding(bottom = 3.dp))
                    }
                    Text(L("Pay once, Premium forever."), color = Theme.secondary, fontSize = tsp(12))
                    if (!qr) {
                        NetflixButton(L("Buy Lifetime"), Icons.Default.OpenInBrowser, kind = NetflixKind.SECONDARY,
                            modifier = Modifier.fillMaxWidth()) { pay(Build181Logic.LIFETIME) }
                    }
                }
            }
        }

        if (qr) {
            val url = Build181Logic.Links.tvUrl(choice)
            val qrBlock: @Composable (Modifier) -> Unit = { m ->
                Column(m, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(tvs(8))) {
                    QrCodeImage(url, tvs(170))
                    Text(Build167Logic.Links.display(url), color = Color.White, fontSize = tsp(14), fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center)
                    Text(L("Scan the QR code with your phone's camera and pay on bingr.online, signed in with this account."),
                        color = Theme.secondary, fontSize = tsp(12), textAlign = TextAlign.Center)
                }
            }
            if (tv) {
                Row(horizontalArrangement = Arrangement.spacedBy(tvs(24)), verticalAlignment = Alignment.CenterVertically) {
                    cards(Modifier.weight(1f))
                    qrBlock(Modifier.widthIn(max = 300.dp))
                }
            } else {
                cards(Modifier.fillMaxWidth())
                qrBlock(Modifier.fillMaxWidth())
            }
        } else {
            cards(Modifier.fillMaxWidth())
            // vinkje directe levering / afstand herroepingsrecht (verplicht voor billing checkout, zoals op bingr.online)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).focusLift(scale = 1.01f, onClick = { waiver = !waiver; error = null })
                    .padding(vertical = tvs(6)),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(if (waiver) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, null,
                    tint = if (waiver) Theme.brandRed else Color.White, modifier = Modifier.size(tvs(22)))
                Spacer(Modifier.width(tvs(10)))
                Text(L("I want to use Premium right away and understand that I thereby lose my right of withdrawal (with a trial only after the trial: you can cancel free of charge during the trial)."),
                    color = Color.White, fontSize = tsp(13))
            }
        }
        error?.let { Text(it, color = Theme.red, fontSize = tsp(13), textAlign = TextAlign.Center) }
        if (working) CircularProgressIndicator(color = Theme.red)
        Text(if (qr) L("Prices incl. VAT. You pay on bingr.online, signed in with this account. Cancel any time.")
            else L("Prices incl. VAT. You pay securely with Stripe. Cancel any time."),
            color = Theme.secondary, fontSize = tsp(11), textAlign = TextAlign.Center)
    }
}

@Composable
private fun Tag(text: String, bg: Color, fg: Color = Color.White) {
    Text(text, color = fg, fontSize = tsp(11), fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = tvs(10), vertical = 3.dp))
}

/**
 * Kies je plan (schermvullend, over alles heen, zoals Activeer Lifetime). Gegevens blijven bewaard; daaronder Ik heb al betaald,
 * Support, Uitloggen en Account verwijderen.
 */
@Composable
fun PremiumPaywallScreen() {
    val p = Entitlements.premium ?: return ActivateLifetimeScreen()
    val tv = AppState.isTV
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var supportNew by remember { mutableStateOf(false) }
    var supportOpen by remember { mutableStateOf<Int?>(null) }
    val first = rememberInitialFocus(key = "premium")

    // betalen gebeurt in de Custom Tab of op een ander apparaat: zolang dit scherm openstaat af en toe opnieuw ophalen
    LaunchedEffect(Unit) {
        Entitlements.launchFetch(force = true)
        while (true) {
            delay(Build167Logic.Screen.REFRESH_MS)
            Entitlements.launchFetch(force = true)
        }
    }
    BackHandler { MainActivity.current?.moveTaskToBack(true) }

    Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = tvs(28)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BingrLogo(tsp(30))
            Spacer(Modifier.height(tvs(18)))
            Column(
                Modifier.widthIn(max = if (tv) 960.dp else 560.dp).fillMaxWidth().padding(horizontal = Theme.edge),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(tvs(12)),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LifetimeMark(tvs(30))
                    Spacer(Modifier.width(tvs(10)))
                    Text(L("Choose your plan"), color = Color.White, fontSize = tsp(24), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
                Text(
                    if (p.trialEligible) L("Start with 3 days free. Your servers, lists, profiles and settings are all here.")
                    else L("Your account is safe: your servers, lists, profiles and settings are all still here. Choose a plan to keep watching."),
                    color = Theme.secondary, fontSize = tsp(15), textAlign = TextAlign.Center,
                )
                PremiumOffer(p, firstFocus = first)
                status?.let { Text(it, color = Theme.secondary, fontSize = tsp(13), textAlign = TextAlign.Center) }
                if (working) CircularProgressIndicator(color = Theme.red)
                Spacer(Modifier.height(tvs(4)))
                val mod = Modifier.fillMaxWidth().widthIn(max = 420.dp)
                NetflixButton(L("I already paid"), Icons.Default.Refresh, kind = NetflixKind.PRIMARY, modifier = mod) {
                    if (working) return@NetflixButton
                    working = true; status = L("Checking…")
                    AppScope.launch {
                        val ok = Premium.refresh()
                        status = when {
                            !ok -> L("Something went wrong. Please try again.")
                            Entitlements.access == Build167Logic.ACTIVATE -> L("We don’t see Premium on this account yet. It can take a minute after paying.")
                            else -> null
                        }
                        working = false
                    }
                }
                NetflixButton(L("Support"), Icons.Default.SupportAgent, kind = NetflixKind.SECONDARY, modifier = mod) { supportNew = true }
                NetflixButton(L("Sign out"), Icons.AutoMirrored.Filled.Logout, kind = NetflixKind.SECONDARY, modifier = mod) { confirmSignOut = true }
                NetflixButton(L("Delete account"), Icons.Default.Delete, kind = NetflixKind.SECONDARY, modifier = mod) { confirmDelete = true }
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
    if (confirmDelete) {
        S2DeleteAccountDialog(onDismiss = { confirmDelete = false }) { password ->
            status = null; working = true
            AppScope.launch {
                try { Account.deleteAccount(password); AppState.signedOut() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: AccountException) { status = e.message }
                catch (e: Exception) { status = L("Something went wrong. Please try again.") }
                working = false
            }
        }
    }
    if (supportNew) SupportNewScreen(onClose = { supportNew = false }, onCreated = { n -> supportNew = false; supportOpen = n })
    supportOpen?.let { n ->
        SupportTicketScreen(n, onClose = { supportOpen = null }, onNew = { supportOpen = null; supportNew = true })
    }
}

/** Huidig abonnement (in het upgradevenster): naam, verlengt op / loopt af op, en Abonnement beheren. */
@Composable
fun PremiumStatusBlock(p: Build181Logic.Premium, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    RefreshOnReturn()
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(tvs(8))) {
        Text("Bingr " + Premium.planName(p.plan), color = Color.White, fontSize = tsp(18), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Premium.statusLine(p).takeIf { it.isNotEmpty() }?.let {
            Text(it, color = Theme.secondary, fontSize = tsp(13), textAlign = TextAlign.Center)
        }
        when (p.manage) {
            "apple" -> Text(L("Your subscription runs through the App Store. Manage it on your iPhone, iPad or Mac."),
                color = Theme.secondary, fontSize = tsp(12), textAlign = TextAlign.Center)
            "stripe" -> if (AppState.isTV) {
                Text(L("Manage your subscription on bingr.online/upgrade."), color = Theme.secondary, fontSize = tsp(12), textAlign = TextAlign.Center)
            } else {
                NetflixButton(L("Manage subscription"), Icons.Default.OpenInBrowser, kind = NetflixKind.SECONDARY,
                    modifier = Modifier.fillMaxWidth()) {
                    if (working) return@NetflixButton
                    working = true; error = null
                    AppScope.launch { error = Premium.manage(ctx); working = false }
                }
            }
        }
        error?.let { Text(it, color = Theme.red, fontSize = tsp(12), textAlign = TextAlign.Center) }
    }
}
