package nl.lrvdlinden.bingr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Pagina's van Mijn Bingr (build 72): overzicht, Instellingen, Profielen beheren, Account en apparaten, Trakt. */
enum class MyBingrPage { ROOT, SETTINGS, PROFILES, ACCOUNT, TRAKT }

/**
 * Navigatie naar (een pagina van) Mijn Bingr, ook vanuit het profielmenu bovenin (build 72).
 * [jumps] telt op bij elke sprong, zodat Mijn Bingr open subschermen (geschiedenis, collecties…) sluit.
 */
object MyBingrNav {
    var page by mutableStateOf(MyBingrPage.ROOT)
    var jumps by mutableIntStateOf(0)
        private set
    /** Gevraagd tabblad (5 = Mijn Bingr); MainScreen neemt het over en zet het terug op null. */
    var tabRequest by mutableStateOf<Int?>(null)

    fun go(p: MyBingrPage) {
        page = p
        jumps++
        tabRequest = 5
    }
}

/**
 * Ronde avatar van het actieve profiel met een pijltje; tik/OK opent het profielmenu (zoals op bingr.online).
 * Telefoon/tablet: in de bovenbalk; tv: aan het eind van de tabbalk.
 */
@Composable
fun ProfileMenuButton(avatarSize: Dp, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val tv = AppState.isTV
    Box(modifier) {
        Row(
            Modifier.onFocusChanged { focused = it.isFocused }
                .focusLift(1.1f) { open = true }
                .clip(RoundedCornerShape(50))
                .background(if (focused && tv) Color.White.copy(alpha = 0.18f) else Color.Transparent)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(if (focused && tv) Modifier.border(2.dp, Color.White, CircleShape) else Modifier) {
                ProfileAvatar(size = avatarSize)
            }
            Spacer(Modifier.width(4.dp))
            Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, L("Switch profile"),
                tint = Color.White, modifier = Modifier.size(tvs(18)))
        }
        ProfileMenu(open) { open = false }
    }
}

/**
 * Het profielmenu: de andere profielen (direct wisselen, met pincode als dat profiel er een heeft), dan
 * Wissel van profiel, Profielen beheren (alleen hoofdprofiel), Mijn Bingr, Instellingen, Account en apparaten, Uitloggen.
 * Een DropdownMenu, dus ook met de afstandsbediening te bedienen (eerste item krijgt focus op tv).
 */
@Composable
fun ProfileMenu(expanded: Boolean, onDismiss: () -> Unit) {
    var pinFor by remember { mutableStateOf<ViewerProfile?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val tv = AppState.isTV

    fun switchTo(p: ViewerProfile) {
        MyBingrNav.page = MyBingrPage.ROOT
        AppState.open(p)
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.background(Theme.elevated).widthIn(min = if (tv) 300.dp else 240.dp)
    ) {
        val first = remember { FocusRequester() }
        var firstUsed = false
        fun firstOnce(): FocusRequester? = if (!firstUsed) { firstUsed = true; first } else null

        val others = Store.profiles.filter { it.id != Store.activeProfileID }
        others.forEach { p ->
            val locked = ParentalControl.hasPIN(p)
            ProfileMenuItem(p.name, focus = firstOnce(), leading = { ProfileAvatar(p, tvs(30)) }, locked = locked) {
                onDismiss()
                if (ParentalControl.hasPIN(p)) pinFor = p else switchTo(p)
            }
        }
        if (others.isNotEmpty()) ProfileMenuDivider()
        ProfileMenuItem(L("Switch profile"), Icons.Default.People, focus = firstOnce()) {
            onDismiss(); AppState.switchPlaylist()
        }
        // profielen beheren: alleen het hoofdprofiel (zelfde regel als in Mijn Bingr)
        if (Store.canManage) {
            ProfileMenuItem(L("Manage profiles"), Icons.Default.Edit) { onDismiss(); MyBingrNav.go(MyBingrPage.PROFILES) }
        }
        ProfileMenuItem(L("My Bingr"), Icons.Default.Person) { onDismiss(); MyBingrNav.go(MyBingrPage.ROOT) }
        ProfileMenuItem(L("Settings"), Icons.Default.Settings) { onDismiss(); MyBingrNav.go(MyBingrPage.SETTINGS) }
        if (Account.isSignedIn) {
            ProfileMenuItem(L("Account & devices"), Icons.Default.AccountCircle) { onDismiss(); MyBingrNav.go(MyBingrPage.ACCOUNT) }
            ProfileMenuDivider()
            ProfileMenuItem(L("Sign out"), Icons.AutoMirrored.Filled.Logout) { onDismiss(); confirmSignOut = true }
        }
        LaunchedEffect(Unit) { if (tv) runCatching { first.requestFocus() } }
    }

    pinFor?.let { p ->
        PinPad(L("PIN for %@", p.name), onDismiss = { pinFor = null }) { pin ->
            if (ParentalControl.verify(pin, p)) {
                scope.launch { delay(80); switchTo(p) }
                true
            } else false
        }
    }
    if (confirmSignOut) {
        S2ActionDialog(
            L("Sign out of Bingr on this device?"),
            listOf(S2Action(L("Sign out"), Icons.AutoMirrored.Filled.Logout, destructive = true) {
                AppScope.launch { Account.signOut(); AppState.signedOut() }
            }),
            onDismiss = { confirmSignOut = false }
        )
    }
}

@Composable
private fun ProfileMenuItem(
    title: String,
    icon: ImageVector? = null,
    focus: FocusRequester? = null,
    leading: (@Composable () -> Unit)? = null,
    locked: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val lit = focused && AppState.isTV
    val fg = if (lit) Color.Black else Color.White
    DropdownMenuItem(
        text = { Text(title, color = fg, fontSize = tsp(15), maxLines = 1) },
        leadingIcon = {
            if (leading != null) leading()
            else if (icon != null) Icon(icon, null, tint = if (lit) Color.Black else Theme.secondary, modifier = Modifier.size(tvs(20)))
        },
        trailingIcon = {
            if (locked) Icon(Icons.Default.Lock, null, tint = if (lit) Color.Black else Theme.secondary, modifier = Modifier.size(tvs(14)))
        },
        onClick = onClick,
        modifier = Modifier
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .background(if (lit) Color.White else Color.Transparent)
    )
}

@Composable
private fun ProfileMenuDivider() {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(0.5.dp).background(Color.White.copy(alpha = 0.14f)))
}
