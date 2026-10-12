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

/** Platformnaam onder de apparaatnaam (`tizen` wordt "Samsung TV"). */
fun devicePlatformLabel(platform: String): String = if (isTizenPlatform(platform)) L("Samsung TV") else platform

/** Icoon per platform. */
fun deviceIcon(platform: String): ImageVector = if (isTizenPlatform(platform)) Icons.Default.Tv else when (platform) {
    "iPhone" -> Icons.Default.PhoneIphone
    "iPad", "Android tablet" -> Icons.Default.Tablet
    "Apple TV", "Android TV", "Google TV", "Fire TV" -> Icons.Default.Tv
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
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(tvs(42)).clip(CircleShape).background(Theme.elevated), contentAlignment = Alignment.Center) {
            Icon(deviceIcon(d.platform), null, tint = Color.White, modifier = Modifier.size(tvs(22)))
        }
        Spacer(Modifier.width(tvs(14)))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.name, color = Color.White, fontSize = tsp(15), fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (d.current) {
                    Spacer(Modifier.width(8.dp))
                    Text(L("This device"), color = Color.White, fontSize = tsp(11), fontWeight = FontWeight.Bold, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Theme.red).padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
            if (d.platform.isNotEmpty()) Text(devicePlatformLabel(d.platform), color = Theme.secondary, fontSize = tsp(13), maxLines = 1)
            val parts = listOfNotNull(
                (d.signedInAt ?: d.createdAt)?.let { L("Signed in since %@", deviceDate(it)) },
                deviceLastUsed(d.lastSeenAt, d.current)?.let { L("Last used %@", it) },
            )
            if (parts.isNotEmpty()) Text(parts.joinToString(" · "), color = Theme.secondary, fontSize = tsp(12), maxLines = 2,
                overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(tvs(10)))
        if (onRename != null) {
            DeviceSmallButton(L("Rename"), Color.White, null, enabled, onRename)
            Spacer(Modifier.width(tvs(8)))
        }
        DeviceSmallButton(L("Remove"), Theme.red, removeFocus, enabled, onRemove)
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
                    else NetflixButton(L("Cancel"), kind = NetflixKind.SECONDARY, modifier = Modifier.widthIn(min = 200.dp)) { cancel() }
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
