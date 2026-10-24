package nl.lrvdlinden.bingr

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tablet
import androidx.compose.material.icons.filled.Tv
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

// Gekoppelde apparaten (limiet van de server, nu 15), zie docs/ACCOUNT.md in de iOS-repo.

/** Samsung TV-app (Tizen, vanaf build 55) meldt zich met platform `tizen`. */
fun isTizenPlatform(platform: String): Boolean =
    platform.equals("tizen", ignoreCase = true) || platform.equals("Samsung TV", ignoreCase = true)

/** Build 147: LG-tv-app (webOS) meldt zich met platform `webos`. */
fun isWebOSPlatform(platform: String): Boolean =
    platform.equals("webos", ignoreCase = true) || platform.equals("LG TV", ignoreCase = true)

/** Webspeler op bingr.online (build 62) meldt zich met platform `browser`; naam zoals "Chrome op Mac". */
fun isBrowserPlatform(platform: String): Boolean = platform.equals("browser", ignoreCase = true)

/** Build 146: de Windows-app (webspeler in een eigen venster) meldt zich met platform `windows`. */
fun isWindowsPlatform(platform: String): Boolean = platform.equals("windows", ignoreCase = true)

/** Platformnaam onder de apparaatnaam (`tizen` wordt "Samsung TV", `webos` wordt "LG TV", `browser` wordt "Browser", `windows` wordt "Windows"). */
fun devicePlatformLabel(platform: String): String = when {
    isTizenPlatform(platform) -> L("Samsung TV")
    isWebOSPlatform(platform) -> L("LG TV")
    isBrowserPlatform(platform) -> L("Browser")
    isWindowsPlatform(platform) -> "Windows"
    else -> platform
}

/** Apparaatnaam in Nu aan het kijken en de kijkgeschiedenis: bij de webspeler met "Browser" erachter ("Chrome op Mac · Browser"). */
fun deviceNameWithLabel(name: String, platform: String): String {
    // build 146: de Windows-app: "Windows" (of de naam met "· Windows" als die het nog niet zegt)
    if (isWindowsPlatform(platform)) {
        return if (name.isBlank() || name.equals(platform, ignoreCase = true)) "Windows"
            else if (name.contains("Windows", ignoreCase = true)) name else "$name · Windows"
    }
    if (!isBrowserPlatform(platform)) return name
    val label = L("Browser")
    return if (name.isBlank() || name.equals(platform, ignoreCase = true) || name.equals(label, ignoreCase = true)) label else "$name · $label"
}

/** Is dit een Apple TV (iOS-app meldt zich met platform "Apple TV")? */
fun isAppleTvPlatform(platform: String): Boolean =
    platform.equals("Apple TV", ignoreCase = true) || platform.equals("AppleTV", ignoreCase = true) || platform.equals("tvOS", ignoreCase = true)

/**
 * Apple TV-icoon (build 74): het SF Symbol "appletv" (afgeronde rechthoek met appel-logo en "tv"), precies het
 * pad uit de iOS-app. Kleur via de tint van [Icon] (huidige inhoudskleur).
 */
val AppleTvIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "AppleTv", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 19.4976f, viewportHeight = 19.4314f,
    ).apply {
        addPath(addPathNodes(APPLE_TV_FRAME), fill = SolidColor(Color.Black))
        addPath(addPathNodes(APPLE_TV_LOGO), fill = SolidColor(Color.Black))
    }.build()
}
private const val APPLE_TV_FRAME = "M5.20687 19.4314L14.2907 19.4314C15.949 19.4314 17.3557 19.2815 18.3579 18.2793C19.3476 17.2792 19.4976 15.8974 19.4976 14.2246L19.4976 5.22355C19.4976 3.55067 19.3476 2.1668 18.3579 1.16882C17.3536 0.176964 15.949 0.0166814 14.2907 0.0166814L5.18577 0.0166814C3.55067 0.0166814 2.14188 0.179074 1.14179 1.17093C0.149932 2.17102 0 3.55489 0 5.1921L0 14.2246C0 15.8974 0.147822 17.2917 1.13968 18.2793C2.14188 19.2815 3.5361 19.4314 5.20687 19.4314ZM4.93695 17.8607C3.93656 17.8607 2.87758 17.7212 2.2877 17.1313C1.69781 16.5414 1.57069 15.497 1.57069 14.4945L1.57069 4.98508C1.57069 3.95535 1.69781 2.90461 2.28559 2.31473C2.87547 1.72274 3.94078 1.58737 4.9684 1.58737L14.5606 1.58737C15.561 1.58737 16.6179 1.72696 17.2078 2.31684C17.7997 2.90672 17.9269 3.95113 17.9269 4.95363L17.9269 14.4945C17.9269 15.497 17.7976 16.5414 17.2078 17.1313C16.6179 17.7233 15.561 17.8607 14.5606 17.8607Z"
private const val APPLE_TV_LOGO = "M4.85163 12.6004C5.26284 12.6004 5.4373 12.3138 5.94819 12.3138C6.47155 12.3138 6.59616 12.6004 7.04476 12.6004C7.49335 12.6004 7.79241 12.1768 8.09148 11.7655C8.39054 11.3045 8.54007 10.8434 8.54007 10.8185C8.52761 10.8185 7.64288 10.4571 7.64288 9.46027C7.64288 8.61293 8.32823 8.22664 8.36562 8.18925C7.92948 7.56621 7.23167 7.54128 7.04476 7.54128C6.53386 7.54128 6.12265 7.82789 5.86097 7.82789C5.58683 7.82789 5.22546 7.56621 4.77687 7.56621C3.95444 7.56621 3.1071 8.2391 3.1071 9.53503C3.1071 10.345 3.40616 11.1923 3.79245 11.7406C4.14136 12.2141 4.42796 12.6004 4.85163 12.6004ZM5.89835 7.45406C6.5214 7.45406 7.16937 6.78117 7.15691 6.04597C7.16937 6.00859 7.15691 5.95875 7.14444 5.92136C6.48402 5.94628 5.81112 6.60671 5.81112 7.32945C5.81112 7.36683 5.81112 7.42914 5.82359 7.4416C5.83605 7.45406 5.86097 7.45406 5.89835 7.45406ZM11.0073 12.5755C11.2192 12.5755 11.4435 12.563 11.5556 12.5381L11.5805 12.5257L11.5805 11.7157L11.5432 11.7157C11.4809 11.7282 11.3313 11.7406 11.2316 11.7406C10.8578 11.7406 10.6958 11.5662 10.6958 11.1799L10.6958 8.60046L11.593 8.60046L11.593 7.77804L10.6958 7.77804L10.6958 6.65656L9.68648 6.65656L9.68648 7.77804L9.00112 7.77804L9.00112 8.60046L9.68648 8.60046L9.68648 11.292C9.68648 12.2017 10.0728 12.5755 11.0073 12.5755ZM13.6864 12.5381L14.7332 12.5381L16.4029 7.77804L15.3562 7.77804L14.2223 11.4914L13.0759 7.77804L11.9917 7.77804Z"

/** Icoon per platform. */
fun deviceIcon(platform: String): ImageVector = if (isTizenPlatform(platform) || isWebOSPlatform(platform)) Icons.Default.Tv
else if (isBrowserPlatform(platform)) Icons.Default.Language
else if (isWindowsPlatform(platform)) Icons.Default.DesktopWindows
else if (isAppleTvPlatform(platform)) AppleTvIcon else when (platform) {
    "iPhone" -> Icons.Default.PhoneIphone
    "iPad", "Android tablet" -> Icons.Default.Tablet
    "Android TV", "Google TV", "Fire TV" -> Icons.Default.Tv
    "Mac" -> Icons.Default.Laptop
    else -> Icons.Default.Smartphone
}

/** "12 okt 2026" */
fun deviceDate(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM, Localizer.locale).format(Date(ms))

/** "just now", "5 min ago", tijd van vandaag, "yesterday" of een datum; eigen apparaat "Now". */
fun deviceLastUsed(ms: Long?, current: Boolean): String? {
    if (current) return L("Now")
    if (ms == null) return null
    val now = System.currentTimeMillis()
    val diff = now - ms
    val today = s2StartOfDay(now)
    val yesterday = s2StartOfDay(today - 1)
    return when {
        diff < 60_000L -> L("just now")
        diff < 3_600_000L -> L("%d min ago", (diff / 60_000L).toInt())
        ms >= today -> s2Time(ms)
        ms >= yesterday -> L("yesterday")
        else -> deviceDate(ms)
    }
}

/** Eén apparaat: icoon, naam (+ "This device"), platform, gekoppeld en laatst gebruikt, knoppen Rename en Remove. */
@Composable
private fun DeviceRow(
    d: Account.Device, removeFocus: FocusRequester?, enabled: Boolean,
    onRename: (() -> Unit)? = null, onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = tvs(12)),
        verticalAlignment = Alignment.Top
    ) {
        Box(Modifier.size(tvs(42)).clip(CircleShape).background(Theme.elevated), contentAlignment = Alignment.Center) {
            Icon(deviceIcon(d.platform), null, tint = Color.White, modifier = Modifier.size(tvs(22)))
        }
        Spacer(Modifier.width(tvs(14)))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.name, color = Color.White, fontSize = tsp(15), fontWeight = FontWeight.SemiBold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (d.current) {
                    Spacer(Modifier.width(8.dp))
                    Text(L("This device"), color = Theme.onAccent, fontSize = tsp(11), fontWeight = FontWeight.Bold, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Theme.red).padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
            if (d.platform.isNotEmpty()) Text(devicePlatformLabel(d.platform), color = Theme.secondary, fontSize = tsp(13), maxLines = 1)
            val parts = listOfNotNull(
                (d.signedInAt ?: d.createdAt)?.let { L("Signed in since %@", deviceDate(it)) },
                deviceLastUsed(d.lastSeenAt, d.current)?.let { L("Last used %@", it) },
            )
            if (parts.isNotEmpty()) Text(parts.joinToString(" · "), color = Theme.secondary, fontSize = tsp(12), maxLines = 3,
                overflow = TextOverflow.Ellipsis)
            // Knoppen onder de tekst, naast elkaar (build 61)
            Spacer(Modifier.height(tvs(10)))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onRename != null) {
                    DeviceSmallButton(L("Rename"), Color.White, null, enabled, onRename)
                    Spacer(Modifier.width(tvs(8)))
                }
                DeviceSmallButton(L("Remove"), Theme.red, removeFocus, enabled, onRemove)
            }
        }
    }
}

/** Kleine knop ("Rename", "Remove"); op tv wit bij focus. */
@Composable
private fun DeviceSmallButton(label: String, tint: Color, fr: FocusRequester?, enabled: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val lit = focused && AppState.isTV
    Text(
        label,
        color = if (lit) Color.Black else tint.copy(alpha = if (enabled) 1f else 0.4f),
        fontSize = tsp(14), fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier
            .then(if (fr != null) Modifier.focusRequester(fr) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .focusLift(1.06f) { if (enabled) onClick() }
            .clip(RoundedCornerShape(50))
            .background(if (lit) Color.White else Color.White.copy(alpha = 0.08f))
            .then(if (focused && !AppState.isTV) Modifier.border(2.dp, Color.White, RoundedCornerShape(50)) else Modifier)
            .padding(horizontal = tvs(14), vertical = tvs(7))
    )
}

/** Bevestiging "Remove %@? It will be signed out." */
@Composable
private fun DeviceRemoveConfirm(d: Account.Device, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    S2ActionDialog(
        L("Remove %@? It will be signed out.", d.name),
        listOf(
            S2Action(L("Remove"), Icons.Default.Delete, destructive = true, run = onConfirm),
            S2Action(L("Cancel"), Icons.Default.Close) {},
        ),
        onDismiss = onDismiss
    )
}

/** "Rename": tekstveld met de huidige naam (max. 60 tekens), Save en Cancel. Op tv opent het tv-toetsenbord. */
@Composable
private fun DeviceRenameDialog(d: Account.Device, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(TextFieldValue(d.name, TextRange(d.name.length))) }
    val fr = remember { FocusRequester() }
    val valid = text.text.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Theme.elevated,
        title = { Text(L("Rename"), color = Color.White) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.text.length <= 60) text = it },
                label = { Text(L("Device name")) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) { onSave(text.text.trim()); onDismiss() } }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.White, unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
                    focusedLabelColor = Color.White, unfocusedLabelColor = Theme.secondary, cursorColor = Theme.red,
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedContainerColor = Theme.card, unfocusedContainerColor = Theme.card,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(fr)
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.text.trim()); onDismiss() }, enabled = valid) {
                Text(L("Save"), color = if (valid) Theme.red else Theme.secondary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L("Cancel"), color = Color.White) } }
    )
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
}

/** Mijn Bingr → Account → "Devices": alle gekoppelde apparaten, met Rename en Remove. */
@Composable
fun DevicesScreen(onClose: () -> Unit) {
    var data by remember { mutableStateOf<Account.DeviceLimit?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<Account.Device?>(null) }
    var renaming by remember { mutableStateOf<Account.Device?>(null) }
    val firstRemove = rememberInitialFocus(key = data)

    LaunchedEffect(reload) {
        loading = true
        try { data = Account.listDevices(); error = null }
        catch (e: CancellationException) { throw e }
        catch (e: AccountException) { error = e.message }
        catch (e: Exception) { error = L("Something went wrong. Please try again.") }
        loading = false
    }

    S2FullDialog(onClose) {
        S2Page(L("Devices"), onClose = onClose) {
            val d = data
            if (d != null) {
                item { S2Header(L("%d of %d devices", d.devices.size, d.limit)) }
                itemsIndexed(d.devices, key = { _, dv -> dv.id }) { i, dev ->
                    Column(Modifier.s2Cell(i, d.devices.size)) {
                        if (i > 0) S2Divider()
                        DeviceRow(dev, if (i == 0) firstRemove else null, enabled = !Account.busy,
                            onRename = { renaming = dev }) { confirm = dev }
                    }
                }
            }
            if (loading || Account.busy) item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Theme.red, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                }
            }
            error?.let { e -> item { S2Footer(e) } }
        }
    }

    renaming?.let { dev ->
        DeviceRenameDialog(dev, onDismiss = { renaming = null }) { name ->
            if (name.isEmpty() || name == dev.name) return@DeviceRenameDialog
            AppScope.launch {
                try { Account.renameDevice(dev.id, name); error = null; reload++ }
                catch (e: CancellationException) { throw e }
                catch (e: AccountException) { error = e.message }
                catch (e: Exception) { error = L("Something went wrong. Please try again.") }
            }
        }
    }

    confirm?.let { dev ->
        DeviceRemoveConfirm(dev, onDismiss = { confirm = null }) {
            confirm = null
            AppScope.launch {
                if (dev.current) {
                    // eigen apparaat verwijderen = uitloggen
                    Account.signOut(); AppState.signedOut()
                    return@launch
                }
                try { Account.removeDevice(dev.id); error = null; reload++ }
                catch (e: CancellationException) { throw e }
                catch (e: AccountException) { error = e.message }
                catch (e: Exception) { error = L("Something went wrong. Please try again.") }
            }
        }
    }
}

/**
 * "Too many devices": register_device gaf device_limit. Apparaat verwijderen → opnieuw registreren
 * → door naar "Wie kijkt er?". Cancel logt uit.
 */
@Composable
fun TooManyDevicesScreen(info: Account.DeviceLimit) {
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Account.Device?>(null) }
    val firstRemove = rememberInitialFocus(key = info)

    fun cancel() {
        if (working) return
        working = true
        AppScope.launch {
            Account.signOut(); AppState.signedOut()
            working = false
        }
    }
    BackHandler { cancel() }

    Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = tvs(28)),
        ) {
            item { BingrLogo(tsp(30)) }
            item { Spacer(Modifier.height(tvs(24))) }
            item {
                Column(Modifier.widthIn(max = S2MaxWidth).fillMaxWidth().padding(horizontal = Theme.edge),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(tvs(8))) {
                    Text(L("Too many devices"), color = Color.White, fontSize = tsp(26), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text(L("Your account can be used on up to %d devices. Remove a device to sign in on this one.", info.limit),
                        color = Theme.secondary, fontSize = tsp(15), textAlign = TextAlign.Center)
                    // build 88: Gratis → met Lifetime meer apparaten
                    if (info.isFree) {
                        Text(L("Or upgrade to Lifetime to use Bingr on up to %d devices.", Entitlements.LIFETIME_DEVICES),
                            color = Color.White, fontSize = tsp(14), textAlign = TextAlign.Center)
                    }
                }
            }
            item { S2Header(L("%d of %d devices", info.devices.size, info.limit)) }
            itemsIndexed(info.devices, key = { _, dv -> dv.id }) { i, dev ->
                Column(Modifier.s2Cell(i, info.devices.size)) {
                    if (i > 0) S2Divider()
                    DeviceRow(dev, if (i == 0) firstRemove else null, enabled = !working) { confirm = dev }
                }
            }
            error?.let { e -> item { Text(e, color = Theme.red, fontSize = tsp(13), modifier = Modifier.padding(16.dp)) } }
            item {
                Box(Modifier.widthIn(max = S2MaxWidth).fillMaxWidth().padding(horizontal = Theme.edge, vertical = tvs(20)),
                    contentAlignment = Alignment.Center) {
                    if (working) CircularProgressIndicator(color = Theme.red)
                    else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // build 88: Gratis → upgraden naar Lifetime (meer apparaten)
                        if (info.isFree) UpgradeLimitButton(Entitlements.Feature.DEVICES, Modifier.widthIn(min = 200.dp))
                        NetflixButton(L("Cancel"), kind = NetflixKind.SECONDARY, modifier = Modifier.widthIn(min = 200.dp)) { cancel() }
                    }
                }
            }
        }
    }

    confirm?.let { dev ->
        DeviceRemoveConfirm(dev, onDismiss = { confirm = null }) {
            confirm = null
            working = true; error = null
            AppScope.launch {
                try {
                    Account.removeDevice(dev.id)
                    // opnieuw registreren: gelukt → verder; nog steeds vol → lijst bijwerken
                    Account.registerDevice()
                    Account.deviceLimit = null
                    if (AppState.stage == AppState.Stage.AUTH) AppState.signedIn()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Account.DeviceLimitException) {
                    Account.deviceLimit = e.info
                } catch (e: AccountException) {
                    error = e.message
                } catch (e: Exception) {
                    error = L("Something went wrong. Please try again.")
                }
                working = false
            }
        }
    }
}
