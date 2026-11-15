package nl.lrvdlinden.bingr

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/*
 * Build 171: Instellingen > Profiel > "Verborgen zenders" en "Geblokkeerde titels" (per profiel, gesynct in
 * `profile.<id>.hiddenChannels` / `blockedTitles`). Het hoofdaccount kiest het profiel (ook een kinderprofiel); een
 * ander profiel ziet alleen zijn eigen lijst. Een kinderprofiel komt hier alleen met de pincode van het hoofdaccount.
 */

/** Welk profiel: het hoofdaccount mag kiezen, anders het actieve. */
@Composable
private fun profileChoice(): Pair<String?, (@Composable () -> Unit)?> {
    var chosen by remember { mutableStateOf(Store.activeProfileID) }
    val canPick = Store.isMainActive && Store.profiles.size > 1
    val id = if (canPick) chosen else Store.activeProfileID
    val picker: (@Composable () -> Unit)? = if (!canPick) null else ({
        S2Section {
            S2Dropdown(L("Profile"), Store.profiles.map { it.id to it.displayName }, chosen ?: "", icon = Icons.Default.Person) { chosen = it }
        }
    })
    return id to picker
}

private fun channelName(key: String): String {
    val (sid, cid) = Build171Logic.ProfileSets.split(key) ?: return key
    val src = Store.playlists.firstOrNull { it.id.equals(sid, ignoreCase = true) }
    val active = Playlists.source?.id?.equals(sid, ignoreCase = true) == true
    val name = if (active) Playlists.rawLibrary.channels.firstOrNull { it.id == cid }?.displayName
        else (AllServers.item("${src?.id ?: sid}|$cid") as? Channel)?.displayName
    return name ?: CatalogMeta.title("${src?.id ?: sid}|$cid") ?: cid
}

private fun titleName(key: String): Pair<String, String?> {
    val r = Build171Logic.ProfileSets.parseTitle(key) ?: return key to null
    val src = Store.playlists.firstOrNull { it.id.equals(r.sid, ignoreCase = true) }
    val active = Playlists.source?.id?.equals(r.sid, ignoreCase = true) == true
    val lib = Playlists.rawLibrary
    val name = if (active) (if (r.series) lib.series.firstOrNull { it.id == r.id }?.displayTitle else lib.movies.firstOrNull { it.id == r.id }?.displayTitle)
        else null
    val title = name ?: CatalogMeta.title("${src?.id ?: r.sid}|${r.id}")?.let { ContentLanguage.displayTitle(it) } ?: r.id
    return title to listOfNotNull(if (r.series) L("Series") else L("Movie"), src?.displayName).joinToString(" · ")
}

@Composable
fun HiddenChannelsScreen(onClose: () -> Unit) {
    val (pid, picker) = profileChoice()
    var tick by remember { mutableIntStateOf(0) }
    val keys = remember(pid, tick, Store.hiddenChannels) {
        if (pid == null) emptyList() else Store.profileSets(pid).first.sortedBy { channelName(it).lowercase() }
    }
    S2Page(L("Hidden channels"), onClose = onClose) {
        if (picker != null) item { picker() }
        item {
            S2Section(footer = L("Hidden channels don't appear in Live TV, the TV guide, search, zapping, Favorites and Recent of this profile. Hold a channel and choose Hide channel.")) {
                if (keys.isEmpty()) S2Row(L("No hidden channels"), tint = Theme.secondary)
            }
        }
        itemsIndexed(keys, key = { _, k -> k }) { i, k ->
            Column(Modifier.s2Cell(i, keys.size)) {
                if (i > 0) S2Divider()
                S2Row(channelName(k), subtitle = Build171Logic.ProfileSets.split(k)?.first?.let { s ->
                    Store.playlists.firstOrNull { it.id.equals(s, ignoreCase = true) }?.displayName },
                    trailing = {
                        DeviceSmallButton(L("Show again"), androidx.compose.ui.graphics.Color.White, null, true) {
                            Store.setChannelKeyHidden(k, false, pid); tick++
                        }
                    })
            }
        }
    }
}

@Composable
fun BlockedTitlesScreen(onClose: () -> Unit) {
    val (pid, picker) = profileChoice()
    var tick by remember { mutableIntStateOf(0) }
    val keys = remember(pid, tick, Store.blockedTitles) {
        if (pid == null) emptyList() else Store.profileSets(pid).third.sortedBy { titleName(it).first.lowercase() }
    }
    S2Page(L("Blocked titles"), onClose = onClose) {
        if (picker != null) item { picker() }
        item {
            S2Section(footer = L("Blocked titles don't appear in rows, search, Top 10, Continue watching and My List of this profile. On a title page, choose Hide for this profile.")) {
                if (keys.isEmpty()) S2Row(L("No blocked titles"), tint = Theme.secondary)
            }
        }
        itemsIndexed(keys, key = { _, k -> k }) { i, k ->
            Column(Modifier.s2Cell(i, keys.size)) {
                if (i > 0) S2Divider()
                val (title, sub) = titleName(k)
                S2Row(title, subtitle = sub, trailing = {
                    DeviceSmallButton(L("Unblock"), androidx.compose.ui.graphics.Color.White, null, true) {
                        Store.setTitleKeyBlocked(k, false, pid); tick++
                    }
                })
            }
        }
    }
}

/** Melding als een deeplink of zoekresultaat een geblokkeerde titel opent. */
@Composable
fun BlockedTitleNotice(onClose: () -> Unit) {
    S2Page("", onClose = onClose) {
        item {
            S2Section {
                S2Row(L("This title is hidden for this profile"), icon = Icons.Default.Visibility, tint = Theme.secondary)
            }
        }
    }
}

/**
 * Build 176: Instellingen > Profiel > "Vergrendeld": zenders en titels die de pincode van het hoofdprofiel vragen
 * (`profile.<id>.lockedChannels` / `lockedTitles`). Ontgrendelen (uit de lijst halen) vraagt altijd die pincode.
 */
@Composable
fun LockedItemsScreen(onClose: () -> Unit) {
    val (pid, picker) = profileChoice()
    var tick by remember { mutableIntStateOf(0) }
    val sets = remember(pid, tick, Store.lockedChannels, Store.lockedTitles) {
        if (pid == null) emptySet<String>() to emptySet() else Store.lockedSets(pid)
    }
    val channels = remember(sets) { sets.first.sortedBy { channelName(it).lowercase() } }
    val titles = remember(sets) { sets.second.sortedBy { titleName(it).first.lowercase() } }
    S2Page(L("Locked"), onClose = onClose) {
        if (picker != null) item { picker() }
        item {
            S2Section(footer = L("Locked channels and titles stay visible, but opening them asks for the PIN of the main profile. After the PIN they stay open for 4 hours on this device. Hold a channel or title and choose Lock with PIN.")) {
                if (channels.isEmpty() && titles.isEmpty()) S2Row(L("Nothing locked"), tint = Theme.secondary)
            }
        }
        if (channels.isNotEmpty()) item { S2Header(L("Channels")) }
        itemsIndexed(channels, key = { _, k -> "c$k" }) { i, k ->
            Column(Modifier.s2Cell(i, channels.size)) {
                if (i > 0) S2Divider()
                S2Row(channelName(k), subtitle = Build171Logic.ProfileSets.split(k)?.first?.let { s ->
                    Store.playlists.firstOrNull { it.id.equals(s, ignoreCase = true) }?.displayName },
                    trailing = {
                        DeviceSmallButton(L("Unlock"), androidx.compose.ui.graphics.Color.White, null, true) {
                            Locks.unlockWithPin { Store.setChannelKeyLocked(k, false, pid); tick++ }
                        }
                    })
            }
        }
        if (titles.isNotEmpty()) item { S2Header(L("Titles")) }
        itemsIndexed(titles, key = { _, k -> "t$k" }) { i, k ->
            Column(Modifier.s2Cell(i, titles.size)) {
                if (i > 0) S2Divider()
                val (title, sub) = titleName(k)
                S2Row(title, subtitle = sub, trailing = {
                    DeviceSmallButton(L("Unlock"), androidx.compose.ui.graphics.Color.White, null, true) {
                        Locks.unlockWithPin { Store.setTitleKeyLocked(k, false, pid); tick++ }
                    }
                })
            }
        }
    }
}
