package nl.lrvdlinden.bingr

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/**
 * Upgraden naar Lifetime (build 88). Het betalen gebeurt op https://bingr.online/upgrade/: op telefoon/tablet in de
 * browser, op Android TV / Google TV / Fire TV met een QR-code en het adres. Alleen als de server `upgrade.link` geeft
 * (standaard aan); anders alleen de vergelijking.
 */
object Upgrade {
    /** Zichtbaar venster; [feature] = waarom (null = algemeen, vanuit Abonnement). */
    var visible by mutableStateOf(false)
        private set
    var feature by mutableStateOf<Entitlements.Feature?>(null)
        private set
    /** Na een upgrade terwijl het venster open is: bedankt tonen. */
    var thanks by mutableStateOf(false)
        private set
    /** Telefoon zonder browser: QR-code in het venster tonen. */
    var forceQR by mutableStateOf(false)

    fun show(f: Entitlements.Feature? = null) {
        feature = f; thanks = false; forceQR = false; visible = true
        Entitlements.launchFetch()
    }

    fun close() { visible = false; thanks = false; forceQR = false }

    fun upgraded() { if (visible) thanks = true }

    /** [f] open → [action], anders het upgradevenster. */
    fun gate(f: Entitlements.Feature, action: () -> Unit) {
        if (Entitlements.allowed(f)) action() else show(f)
    }

    /** Telefoon/tablet: bingr.online/upgrade in de browser; lukt dat niet, dan de QR-code. */
    fun openInBrowser(ctx: Context) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(Entitlements.upgradeUrl))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { ctx.startActivity(intent) }.isFailure) forceQR = true
    }

    fun reason(f: Entitlements.Feature?): String = when (f) {
        Entitlements.Feature.STATS -> L("Statistics are part of Bingr Lifetime.")
        Entitlements.Feature.NOW_WATCHING -> L("Watching now and the watch history are part of Bingr Lifetime.")
        Entitlements.Feature.TRAKT -> L("Trakt is part of Bingr Lifetime.")
        Entitlements.Feature.MULTI_SERVER -> L("More servers are part of Bingr Lifetime. Your current servers keep working.")
        Entitlements.Feature.THEMES -> L("Accent colors are part of Bingr Lifetime.")
        Entitlements.Feature.STREAMS -> L("With Bingr Lifetime you can watch on up to %d screens at the same time.", Entitlements.LIFETIME_STREAMS) +
            " " + L("How many you can watch at once also depends on your IPTV provider.")
        Entitlements.Feature.DEVICES -> L("With Bingr Lifetime you can use up to %d devices.", Entitlements.LIFETIME_DEVICES)
        null -> L("Get more out of Bingr with a one-time upgrade.")
    }
}

/**
 * Lifetime-icoon (build 104): badge met een uitgespaard vinkje (even-odd), 24×24, zelfde pad als op iOS en de website.
 * Met [Icon] en `tint = Theme.brandRed` in Bingr-rood (op een rode knop wit).
 */
val LifetimeIcon: ImageVector by lazy {
    ImageVector.Builder(name = "Lifetime", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData = addPathNodes("M9.5924 3.20027C9.34888 3.4078 9.22711 3.51158 9.09706 3.59874C8.79896 3.79854 8.46417 3.93721 8.1121 4.00672C7.95851 4.03705 7.79903 4.04977 7.48008 4.07522C6.6787 4.13918 6.278 4.17115 5.94371 4.28923C5.17051 4.56233 4.56233 5.17051 4.28923 5.94371C4.17115 6.278 4.13918 6.6787 4.07522 7.48008C4.04977 7.79903 4.03705 7.95851 4.00672 8.1121C3.93721 8.46417 3.79854 8.79896 3.59874 9.09706C3.51158 9.22711 3.40781 9.34887 3.20027 9.5924C2.67883 10.2043 2.4181 10.5102 2.26522 10.8301C1.91159 11.57 1.91159 12.43 2.26522 13.1699C2.41811 13.4898 2.67883 13.7957 3.20027 14.4076C3.40778 14.6511 3.51158 14.7729 3.59874 14.9029C3.79854 15.201 3.93721 15.5358 4.00672 15.8879C4.03705 16.0415 4.04977 16.201 4.07522 16.5199C4.13918 17.3213 4.17115 17.722 4.28923 18.0563C4.56233 18.8295 5.17051 19.4377 5.94371 19.7108C6.278 19.8288 6.6787 19.8608 7.48008 19.9248C7.79903 19.9502 7.95851 19.963 8.1121 19.9933C8.46417 20.0628 8.79896 20.2015 9.09706 20.4013C9.22711 20.4884 9.34887 20.5922 9.5924 20.7997C10.2043 21.3212 10.5102 21.5819 10.8301 21.7348C11.57 22.0884 12.43 22.0884 13.1699 21.7348C13.4898 21.5819 13.7957 21.3212 14.4076 20.7997C14.6511 20.5922 14.7729 20.4884 14.9029 20.4013C15.201 20.2015 15.5358 20.0628 15.8879 19.9933C16.0415 19.963 16.201 19.9502 16.5199 19.9248C17.3213 19.8608 17.722 19.8288 18.0563 19.7108C18.8295 19.4377 19.4377 18.8295 19.7108 18.0563C19.8288 17.722 19.8608 17.3213 19.9248 16.5199C19.9502 16.201 19.963 16.0415 19.9933 15.8879C20.0628 15.5358 20.2015 15.201 20.4013 14.9029C20.4884 14.7729 20.5922 14.6511 20.7997 14.4076C21.3212 13.7957 21.5819 13.4898 21.7348 13.1699C22.0884 12.43 22.0884 11.57 21.7348 10.8301C21.5819 10.5102 21.3212 10.2043 20.7997 9.5924C20.5922 9.34887 20.4884 9.22711 20.4013 9.09706C20.2015 8.79896 20.0628 8.46417 19.9933 8.1121C19.963 7.95851 19.9502 7.79903 19.9248 7.48008C19.8608 6.6787 19.8288 6.278 19.7108 5.94371C19.4377 5.17051 18.8295 4.56233 18.0563 4.28923C17.722 4.17115 17.3213 4.13918 16.5199 4.07522C16.201 4.04977 16.0415 4.03705 15.8879 4.00672C15.5358 3.93721 15.201 3.79854 14.9029 3.59874C14.7729 3.51158 14.6511 3.40781 14.4076 3.20027C13.7957 2.67883 13.4898 2.41811 13.1699 2.26522C12.43 1.91159 11.57 1.91159 10.8301 2.26522C10.5102 2.4181 10.2043 2.67883 9.5924 3.20027ZM16.3735 9.86314C16.6913 9.5453 16.6913 9.03 16.3735 8.71216C16.0557 8.39433 15.5403 8.39433 15.2225 8.71216L10.3723 13.5624L8.77746 11.9676C8.45963 11.6498 7.94432 11.6498 7.62649 11.9676C7.30866 12.2854 7.30866 12.8007 7.62649 13.1186L9.79678 15.2889C10.1146 15.6067 10.6299 15.6067 10.9478 15.2889L16.3735 9.86314Z"),
            pathFillType = PathFillType.EvenOdd,
            fill = SolidColor(Color(0xFFE50914)),
        )
        .build()
}

/** Het Lifetime-icoon in Bingr-rood (abonnementsrij, upgradevenster). */
@Composable
fun LifetimeMark(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Icon(LifetimeIcon, null, tint = Theme.brandRed, modifier = modifier.size(size))
}

/** "Lifetime" met het Lifetime-icoon: overal hetzelfde naast iets dat bij Lifetime hoort (alleen voor Gratis). */
@Composable
fun LifetimeBadge(modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Theme.imdb).padding(horizontal = tvs(8), vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LifetimeMark(tvs(13))
        Spacer(Modifier.width(3.dp))
        Text(L("Lifetime"), color = Color.Black, fontSize = tsp(11), fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Het upgradevenster; staat één keer in [BingrRoot], dus ook boven de speler en "Te veel apparaten". */
@Composable
fun UpgradeDialogHost() {
    if (!Upgrade.visible) return
    val ctx = LocalContext.current
    val tv = AppState.isTV
    val close = { Upgrade.close() }
    // tv (geen terugkeer naar de voorgrond na betalen op de telefoon): het abonnement zolang het venster open is af en toe ophalen
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000L)
            Entitlements.launchFetch(force = true)
        }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val fr = remember { FocusRequester() }
        val thanks = Upgrade.thanks || Entitlements.isLifetime && Upgrade.feature == null
        val link = Entitlements.upgradeLink
        val showQR = link && (tv || Upgrade.forceQR) && !thanks
        Box(
            Modifier.padding(horizontal = 16.dp, vertical = 24.dp)
                .widthIn(min = 280.dp, max = if (showQR && tv) 900.dp else 520.dp)
                .clip(RoundedCornerShape(16.dp)).background(Theme.elevated)
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(tvs(22)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(tvs(12)),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LifetimeMark(tvs(30))
                    Spacer(Modifier.width(tvs(10)))
                    Text(L("Bingr Lifetime"), color = Color.White, fontSize = tsp(22), fontWeight = FontWeight.Bold)
                }
                if (thanks) {
                    Text(L("You have Bingr Lifetime. Thank you!"), color = Color.White, fontSize = tsp(15), textAlign = TextAlign.Center)
                    NetflixButton(L("Close"), Icons.Default.Check, kind = NetflixKind.PRIMARY,
                        modifier = Modifier.fillMaxWidth().focusRequester(fr)) { close() }
                } else {
                    Text(Upgrade.reason(Upgrade.feature), color = Theme.secondary, fontSize = tsp(14), textAlign = TextAlign.Center)
                    if (showQR && tv) {
                        Row(horizontalArrangement = Arrangement.spacedBy(tvs(24)), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { PlanComparison() }
                            UpgradeQR(Modifier.widthIn(max = 260.dp))
                        }
                    } else {
                        PlanComparison()
                        if (showQR) UpgradeQR()
                    }
                    Text(L("%@ one-time", Entitlements.price), color = Color.White, fontSize = tsp(18), fontWeight = FontWeight.Bold)
                    Text(L("Pay once, keep it forever. No subscription."), color = Theme.secondary, fontSize = tsp(12), textAlign = TextAlign.Center)
                    if (link && !tv && !Upgrade.forceQR) {
                        NetflixButton(L("Upgrade on bingr.online"), Icons.Default.OpenInBrowser, kind = NetflixKind.ACCENT,
                            modifier = Modifier.fillMaxWidth()) { Upgrade.openInBrowser(ctx) }
                    }
                    NetflixButton(L("Close"), Icons.Default.Close, kind = NetflixKind.SECONDARY,
                        modifier = Modifier.fillMaxWidth().focusRequester(fr)) { close() }
                }
            }
        }
        LaunchedEffect(thanks) { runCatching { delay(60); fr.requestFocus() } }
    }
}

@Composable
private fun UpgradeQR(modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(tvs(8))) {
        QrCodeImage(Entitlements.upgradeUrl, tvs(150))
        Text(Entitlements.upgradeUrl.removePrefix("https://").removeSuffix("/"), color = Color.White, fontSize = tsp(15),
            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(L("Scan the QR code with your phone's camera to upgrade on bingr.online."), color = Theme.secondary,
            fontSize = tsp(12), textAlign = TextAlign.Center)
    }
}

/** Vergelijking Gratis ↔ Lifetime (je eigen limieten komen van de server). */
@Composable
private fun PlanComparison() {
    val c = Entitlements.current
    val freeStreams = c?.takeIf { it.isFree }?.streams ?: Entitlements.FREE_STREAMS
    val freeDevices = c?.takeIf { it.isFree }?.devices ?: Entitlements.FREE_DEVICES
    val freeServers = c?.takeIf { it.isFree }?.servers ?: Entitlements.FREE_SERVERS
    // build 128: de echte waarden van de server als je Lifetime hebt; "tot 3" schermen (hangt ook af van de IPTV-aanbieder)
    val lifeStreams = c?.takeIf { !it.isFree }?.streams ?: Entitlements.LIFETIME_STREAMS
    val lifeDevices = c?.takeIf { !it.isFree }?.devices ?: Entitlements.LIFETIME_DEVICES
    val yes = "✓"; val no = "—"
    val rows = listOf(
        Triple(L("Streams at the same time"), "$freeStreams", L("up to %d", lifeStreams)),
        Triple(L("Devices"), "$freeDevices", "$lifeDevices"),
        Triple(L("Servers"), "$freeServers", L("Unlimited")),
        Triple(L("Statistics"), no, yes),
        Triple(L("Watching now"), no, yes),
        Triple("Trakt", no, yes),
        Triple(L("Accent colors"), no, yes),
    )
    val current = when {
        c == null -> null
        c.isFree -> 0
        else -> 1
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(tvs(6))) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Theme.card)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = tvs(8)), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1.6f))
            listOf(L("Free"), L("Lifetime")).forEachIndexed { i, t ->
                Text(t, color = if (i == 1) Theme.imdb else Color.White, fontSize = tsp(13), fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        rows.forEach { (label, free, life) ->
            S2Divider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = tvs(7)), verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = Color.White, fontSize = tsp(13), modifier = Modifier.weight(1.6f))
                Text(free, color = Theme.secondary, fontSize = tsp(13), textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                Text(life, color = Color.White, fontSize = tsp(13), fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f))
            }
        }
        if (current != null) {
            S2Divider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = tvs(7)), verticalAlignment = Alignment.CenterVertically) {
                Text(L("Your plan"), color = Theme.secondary, fontSize = tsp(12), modifier = Modifier.weight(1.6f))
                listOf(0, 1).forEach { i ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (i == current) Icon(Icons.Default.Check, null, tint = Theme.green, modifier = Modifier.size(tvs(16)))
                    }
                }
            }
        }
    }
    Text(L("How many you can watch at once also depends on your IPTV provider."), color = Theme.secondary, fontSize = tsp(12),
        modifier = Modifier.padding(horizontal = 4.dp))
    }
}

/** "Upgraden naar Lifetime"-knop onder de limietschermen (alleen Gratis). */
@Composable
fun UpgradeLimitButton(feature: Entitlements.Feature, modifier: Modifier = Modifier) {
    NetflixButton(L("Upgrade to Lifetime"), LifetimeIcon, kind = NetflixKind.ACCENT, modifier = modifier) { Upgrade.show(feature) }
}
