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
import androidx.compose.material.icons.filled.Lock
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
        Entitlements.Feature.STREAMS -> L("With Bingr Lifetime you can watch on up to %d screens at the same time.", Entitlements.LIFETIME_STREAMS)
        Entitlements.Feature.DEVICES -> L("With Bingr Lifetime you can use up to %d devices.", Entitlements.LIFETIME_DEVICES)
        null -> L("Get more out of Bingr with a one-time upgrade.")
    }
}

/** Slotje met "Lifetime": overal hetzelfde naast iets dat bij Lifetime hoort (alleen voor Gratis). */
@Composable
fun LifetimeBadge(modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Theme.imdb).padding(horizontal = tvs(8), vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Lock, null, tint = Color.Black, modifier = Modifier.size(tvs(11)))
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
    val yes = "✓"; val no = "—"
    val rows = listOf(
        Triple(L("Streams at the same time"), "$freeStreams", "${Entitlements.LIFETIME_STREAMS}"),
        Triple(L("Devices"), "$freeDevices", "${Entitlements.LIFETIME_DEVICES}"),
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
}

/** "Upgraden naar Lifetime"-knop onder de limietschermen (alleen Gratis). */
@Composable
fun UpgradeLimitButton(feature: Entitlements.Feature, modifier: Modifier = Modifier) {
    NetflixButton(L("Upgrade to Lifetime"), Icons.Default.Lock, kind = NetflixKind.ACCENT, modifier = modifier) { Upgrade.show(feature) }
}
