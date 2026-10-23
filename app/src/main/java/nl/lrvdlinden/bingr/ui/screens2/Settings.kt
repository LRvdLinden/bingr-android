package nl.lrvdlinden.bingr

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.height
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Text
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import java.io.File

/** Gecentreerde voettekst onderaan Mijn Bingr: app-icoon, versie (build) en copyright. */
@Composable
private fun S2AppFooter() {
    val tv = AppState.isTV
    var focused by remember { mutableStateOf(false) }
    val iconSize = if (tv) 84.dp else 56.dp
    Column(
        Modifier.fillMaxWidth()
            .padding(top = if (tv) 32.dp else 24.dp, bottom = if (tv) 48.dp else 16.dp)
            .then(if (tv) Modifier.onFocusChanged { focused = it.isFocused }.focusable() else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(R.mipmap.ic_launcher),
            contentDescription = "Bingr",
            modifier = Modifier.size(iconSize)
                .clip(RoundedCornerShape(iconSize * 0.22f))
                .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(iconSize * 0.22f)) else Modifier)
        )
        Spacer(Modifier.height(tvs(10)))
        Text(L("Version: %@ (%@)", BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toString()),
            color = if (focused) Color.White else Theme.secondary, fontSize = tsp(13), textAlign = TextAlign.Center)
        Spacer(Modifier.height(tvs(4)))
        Text("\u00A9 2026 Bingr",
            color = Theme.secondary, fontSize = tsp(12), textAlign = TextAlign.Center)
    }
}

private enum class S2SettingsSub { CATEGORIES, MANAGE, HISTORY, NAS, CHECK, COLLECTIONS, HOME_SCREEN, WATCHING, WATCH_LOG, STATS }
private enum class S2PinMode { SET, UNLOCK, DISABLE }

private const val S2_FILE_AUTHORITY = "nl.lrvdlinden.bingr.files"
private const val S2_BACKUP_NAME = "Bingr-backup.json"

/** Build 141 (M7): tijdelijk exportbestand weghalen (na het delen, en bij het openen van Mijn Bingr als het nog staat). */
internal fun s2DeleteBackupFile(ctx: Context) { runCatching { File(ctx.cacheDir, S2_BACKUP_NAME).delete() } }

/**
 * Alles (incl. wachtwoorden) als .json-bestand delen (deelmenu). Build 141 (M7): het deelmenu start met [launch]
 * (resultaat = deelmenu dicht), zodat het tijdelijke bestand daarna meteen weg kan.
 */
private fun s2ExportBackup(ctx: Context, launch: (Intent) -> Unit): Boolean = runCatching {
    val f = File(ctx.cacheDir, S2_BACKUP_NAME)
    f.writeText(Store.exportJSON())
    val uri: Uri = FileProvider.getUriForFile(ctx, S2_FILE_AUTHORITY, f)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Bingr")
        clipData = ClipData.newRawUri("Bingr", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    launch(Intent.createChooser(send, L("Export to file")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    true
}.getOrElse { s2DeleteBackupFile(ctx); false }

/**
 * "Mijn Bingr": zelfde secties en volgorde als SettingsView.swift.
 * Profiel (wisselen/bewerken) voor iedereen; server, profielen, Trakt, kinderslot en beheer alleen voor het hoofdaccount.
 * Persoonlijke kijkvoorkeuren (ondertitels, talen) worden bij het actieve profiel bewaard.
 */
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val listState = rememberLazyListState()
    var sub by remember { mutableStateOf<S2SettingsSub?>(null) }
    var editing by remember { mutableStateOf<PlaylistSource?>(null) }
    var addingServer by remember { mutableStateOf(false) }
    var deletingServer by remember { mutableStateOf<PlaylistSource?>(null) }
    var editingProfile by remember { mutableStateOf<ViewerProfile?>(null) }
    var addingProfile by remember { mutableStateOf(false) }
    var showTraktLogin by remember { mutableStateOf(false) }
    var showOSLogin by remember { mutableStateOf(false) }
    var setup by remember { mutableStateOf<String?>(null) }
    var pinMode by remember { mutableStateOf<S2PinMode?>(null) }
    /** Build 141 (H1/M7): wacht op de pincode van het hoofdaccount: titel + wat er daarna gebeurt. */
    var mainPinFor by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var confirmExport by remember { mutableStateOf(false) }
    var shareStatus by remember { mutableStateOf<String?>(null) }
    var linkingTv by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }
    var accountConfirm by remember { mutableStateOf<String?>(null) }
    var accountError by remember { mutableStateOf<String?>(null) }

    // instellingen (Prefs) als Compose-state
    var startTab by remember { mutableIntStateOf(Prefs.int("ui.startTab", 0)) }
    var showLive by remember { mutableStateOf(Prefs.bool("ui.showLive", true)) }
    var showMovies by remember { mutableStateOf(Prefs.bool("ui.showMovies", true)) }
    var showSeries by remember { mutableStateOf(Prefs.bool("ui.showSeries", true)) }
    var autoplayTrailers by remember { mutableStateOf(HeroTrailer.enabled) }
    /**
     * build 91: kijkvoorkeur wijzigen. Alleen het hoofdprofiel (of een account zonder profielen) past ook de
     * instellingen van het account aan (`settings.shared`); een ander profiel alleen zijn eigen `prefs`.
     */
    fun viewingChange(block: () -> Unit) {
        if (Store.canManage) { block(); StateSync.rememberLocalViewing() } else StateSync.withoutSettingsChange { block() }
    }
    var engine by remember { mutableStateOf(Prefs.string("player.engine") ?: "auto") }
    var refreshPolicy by remember { mutableStateOf(RefreshPolicy.current) }
    var zapDelay by remember { mutableIntStateOf(PlayerSettings.zapDelay) }
    var channelPreview by remember { mutableStateOf(PlayerSettings.channelPreview) }
    var matchFrameRate by remember { mutableStateOf(PlayerSettings.matchFrameRate) }
    // build 52
    var deinterlace by remember { mutableStateOf(PlayerSettings.deinterlace) }
    var resumeLastChannel by remember { mutableStateOf(PlayerSettings.resumeLastChannel) }
    var iconConfirm by remember { mutableStateOf<String?>(null) }

    // build 141 (M7): deelmenu voor de export; als het dicht is, het tijdelijke bestand weghalen
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        s2DeleteBackupFile(ctx)
    }
    LaunchedEffect(Unit) { s2DeleteBackupFile(ctx) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } }.getOrNull()
            shareStatus = if (text != null && Store.importJSON(text)) L("Imported") else L("Import failed")
        }
    }

    LaunchedEffect(Unit) { runCatching { OpenSubtitles.refreshUser() } }
    /** Wie kijkt er nu (alleen hoofdprofiel), elke 30 s bijgewerkt. */
    var watching by remember { mutableStateOf<Account.StreamList?>(null) }
    LaunchedEffect(Account.isSignedIn, Store.canManage, sub, Entitlements.nowWatching) {
        // build 88: Nu aan het kijken hoort bij Lifetime
        if (!Account.isSignedIn || !Store.canManage || sub != null || !Entitlements.nowWatching) { watching = null; return@LaunchedEffect }
        while (true) {
            try { watching = Account.listStreams() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
            kotlinx.coroutines.delay(30_000L)
        }
    }

    // sprong vanuit het profielmenu (build 72): open subschermen sluiten
    LaunchedEffect(MyBingrNav.jumps) {
        if (MyBingrNav.jumps == 0) return@LaunchedEffect
        sub = null; editing = null; addingServer = false; editingProfile = null; addingProfile = false
    }

    // build 88: onderdeel van Lifetime (ook als de server het tijdens het kijken dichtzet): terug en het upgradevenster
    val lockedSub = when (sub) {
        S2SettingsSub.STATS -> Entitlements.Feature.STATS.takeIf { !Entitlements.stats }
        S2SettingsSub.WATCHING, S2SettingsSub.WATCH_LOG -> Entitlements.Feature.NOW_WATCHING.takeIf { !Entitlements.nowWatching }
        else -> null
    }
    LaunchedEffect(lockedSub) {
        if (lockedSub != null) { sub = null; Upgrade.show(lockedSub) }
    }

    // volledige subschermen (vervangen de lijst, zodat de focus erin blijft)
    if (lockedSub == null) when (sub) {
        S2SettingsSub.CATEGORIES -> { if (Store.activeProfile?.kids != true) { ManageCategoriesScreen { sub = null }; return } else sub = null }
        S2SettingsSub.MANAGE -> { ManageScreen { sub = null }; return }
        S2SettingsSub.HISTORY -> { HistoryScreen { sub = null }; return }
        S2SettingsSub.NAS -> { NetworkBrowserScreen { sub = null }; return }
        S2SettingsSub.CHECK -> { SourceCheckScreen { sub = null }; return }
        S2SettingsSub.COLLECTIONS -> { CollectionsScreen { sub = null }; return }
        S2SettingsSub.HOME_SCREEN -> { HomeScreenSettings { sub = null }; return }
        S2SettingsSub.WATCHING -> { WatchingNowScreen { sub = null }; return }
        S2SettingsSub.WATCH_LOG -> { WatchLogScreen { sub = null }; return }
        S2SettingsSub.STATS -> { StatsScreen { sub = null }; return }
        null -> {}
    }
    val editingNow = editing
    if (editingNow != null) {
        AddPlaylistScreen(existing = editingNow, onClose = { editing = null })
        return
    }
    if (addingServer) {
        AddPlaylistScreen(existing = null, onClose = { addingServer = false })
        return
    }
    val editingProfileNow = editingProfile
    if (editingProfileNow != null) {
        ProfileEditScreen(existing = editingProfileNow, onClose = { editingProfile = null })
        return
    }
    if (addingProfile) {
        ProfileEditScreen(existing = null, onClose = { addingProfile = false })
        return
    }

    val source = Playlists.source
    val lib = Playlists.library
    val profile = Store.activeProfile
    /** Hoofdaccount (of nog geen profielen): mag de server en alles instellen. */
    val isMain = Store.canManage

    iconConfirm?.let { k ->
        S2ActionDialog(L("Some launchers briefly close Bingr when the icon changes."), listOf(
            S2Action(L("Change icon"), Icons.Default.Check) { AppIcon.set(k) },
        ), onDismiss = { iconConfirm = null })
    }

    // build 72: Mijn Bingr zoals op bingr.online: bovenaan je rijen, dan BIBLIOTHEEK en PROFIEL;
    // al het andere staat onder Instellingen, Account en apparaten, Profielen beheren en Trakt
    val page = when (val p = MyBingrNav.page) {
        MyBingrPage.PROFILES, MyBingrPage.TRAKT -> if (isMain) p else MyBingrPage.ROOT
        MyBingrPage.ACCOUNT -> if (Account.isSignedIn) p else MyBingrPage.ROOT
        else -> p
    }
    val pageState = remember(page) { LazyListState() }
    val title = when (page) {
        MyBingrPage.ROOT -> profile?.name ?: L("My Bingr")
        MyBingrPage.SETTINGS -> L("Settings")
        MyBingrPage.PROFILES -> L("Manage profiles")
        MyBingrPage.ACCOUNT -> L("Account & devices")
        MyBingrPage.TRAKT -> "Trakt"
    }

    S2Page(title, onClose = if (page == MyBingrPage.ROOT) null else ({ MyBingrNav.page = MyBingrPage.ROOT }),
        state = if (page == MyBingrPage.ROOT) listState else pageState) {
      if (page == MyBingrPage.ROOT) {
        // MARK: zoals "Mijn Netflix": profiel, Verder kijken, Mijn lijst, favoriete zenders
        item { S2MyBingrHeader() }

        // MARK: bibliotheek
        item {
            S2Section(L("Library")) {
                val historyCount = Store.historyAll().size
                S2NavRow(L("Watch history"), Icons.Default.History, value = historyCount.takeIf { it > 0 }?.toString()) {
                    sub = S2SettingsSub.HISTORY
                }
                S2NavRow(L("Collections"), Icons.AutoMirrored.Filled.PlaylistAdd,
                    value = Collections.visible.size.takeIf { it > 0 }?.toString()) { sub = S2SettingsSub.COLLECTIONS }
                // build 86: Categorieën (overzicht met genres en de categorieën van je server), eerst een chip/tabblad
                val catLib = Playlists.library
                if (catLib.movies.isNotEmpty() || catLib.series.isNotEmpty()) {
                    val catIndex = rememberCategoryIndex()
                    S2NavRow(L("Categories"), Icons.Default.GridView, value = catIndex?.visibleCount()?.takeIf { it > 0 }?.toString()) {
                        ScreensNav.push(ScreenEntry.Categories(Filter.ALL))
                    }
                }
                // kijkstatistieken (build 68): hoofdprofiel alle profielen, andere profielen alleen hun eigen
                S2NavRow(L("Statistics"), Icons.Default.BarChart, locked = !Entitlements.stats) {
                    Upgrade.gate(Entitlements.Feature.STATS) { sub = S2SettingsSub.STATS }
                }
                if (isMain) {
                    S2NavRow("Trakt",
                        locked = !Trakt.isLoggedIn && !Entitlements.trakt,
                        subtitle = if (Trakt.isLoggedIn) Trakt.username ?: Trakt.displayName else L("Connect Trakt"),
                        value = if (Trakt.isLoggedIn) Trakt.lastRefresh?.let { L("Trakt synced %@", s2Time(it)) } else null,
                        leading = {
                            Box(Modifier.size(tvs(28)).clip(CircleShape).background(Theme.elevated), contentAlignment = Alignment.Center) {
                                val a = Trakt.avatar
                                if (Trakt.isLoggedIn && a != null) RemoteImage(a, Modifier.size(tvs(28)).clip(CircleShape))
                                else Icon(Icons.Default.Sync, null, tint = Theme.red, modifier = Modifier.size(tvs(18)))
                            }
                        }) { MyBingrNav.page = MyBingrPage.TRAKT }
                }
            }
        }

        // MARK: profiel
        item {
            S2Section(L("Profile")) {
                if (isMain) {
                    S2NavRow(L("Manage profiles"), Icons.Default.People,
                        value = Store.profiles.size.takeIf { it > 0 }?.toString()) { MyBingrNav.page = MyBingrPage.PROFILES }
                }
                S2NavRow(L("Home screen"), Icons.Default.ViewAgenda) { sub = S2SettingsSub.HOME_SCREEN }
                S2NavRow(L("Settings"), Icons.Default.Settings) { MyBingrNav.page = MyBingrPage.SETTINGS }
                if (Account.isSignedIn) {
                    S2NavRow(L("Account & devices"), Icons.Default.AccountCircle) { MyBingrNav.page = MyBingrPage.ACCOUNT }
                    // build 88: abonnement (Gratis / Lifetime); tik = vergelijking en upgraden
                    Entitlements.current?.let { e ->
                        S2NavRow(L("Subscription"), leading = { LifetimeMark(tvs(20)) },
                            value = if (e.isFree) L("Free") else L("Lifetime")) { Upgrade.show(null) }
                    }
                }
                // build 75: support.bingr.online (telefoon: browser, TV: QR-code)
                S2Row(L("Support"), icon = Icons.Default.SupportAgent, onClick = { Support.open(ctx) })
            }
        }

        // MARK: onderaan: app-icoon, versie en copyright (op TV focusbaar, zodat het in beeld scrolt)
        item { S2AppFooter() }
      }

      if (page == MyBingrPage.SETTINGS) {
        // MARK: profiel
        item {
            S2Section(L("Profile"), if (!isMain) L("The server and all settings are managed in the main account.") else null) {
                if (profile != null) {
                    S2Row(profile.name,
                        subtitle = when {
                            Store.isMain(profile) -> L("Main account")
                            profile.effectiveAgeLimit != null -> L("Up to %d years", profile.effectiveAgeLimit ?: 0)
                            else -> null
                        },
                        leading = { ProfileAvatar(profile, tvs(44)) })
                    S2Row(L("Edit profile"), icon = Icons.Default.Edit, onClick = { editingProfile = profile })
                }
                S2Row(L("Switch profile"), icon = Icons.Default.SwapHoriz, onClick = { AppState.switchPlaylist() })
            }
        }

      }

      if (page == MyBingrPage.ACCOUNT) {
        // MARK: Bingr-account (hoofdaccount en alle profielen)
        if (Account.isSignedIn) {
            item {
                S2Section(L("Account")) {
                    S2Row(L("Signed in as %@", Account.email ?: ""), icon = Icons.Default.AccountCircle)
                    if (!AppState.isTV) S2Row(L("Link a TV"), icon = Icons.Default.Tv, onClick = { linkingTv = true })
                    S2Row(L("Devices"), icon = Icons.Default.Devices, onClick = { showDevices = true })
                    if (isMain) {
                        // build 88: Nu aan het kijken en de geschiedenis van 7 dagen horen bij Lifetime
                        val nw = Entitlements.nowWatching
                        S2Row(L("Watching now"), icon = Icons.Default.PlayCircle,
                            value = watching?.takeIf { nw }?.let { w -> L("%d of %d watching now", w.streams.size, w.limit) },
                            onClick = { Upgrade.gate(Entitlements.Feature.NOW_WATCHING) { sub = S2SettingsSub.WATCHING } },
                            trailing = { if (!(nw)) LifetimeBadge() })
                        S2Row(L("Watch history (7 days)"), icon = Icons.Default.History,
                            onClick = { Upgrade.gate(Entitlements.Feature.NOW_WATCHING) { sub = S2SettingsSub.WATCH_LOG } },
                            trailing = { if (!(nw)) LifetimeBadge() })
                    }
                    S2Row(L("Sign out"), icon = Icons.AutoMirrored.Filled.Logout, onClick = { accountConfirm = "signout" })
                    S2Row(L("Delete account"), icon = Icons.Default.Delete, tint = Theme.red, onClick = { accountConfirm = "delete" })
                    if (Account.busy) S2Row("", leading = {
                        CircularProgressIndicator(color = Theme.red, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    })
                    accountError?.let { S2Row(it, tint = Theme.red) }
                }
            }
        }
        // build 75: hulp nodig? → support.bingr.online
        item {
            S2Section(L("Help")) {
                S2Row(L("Support"), icon = Icons.Default.SupportAgent, value = Support.URL.removePrefix("https://"),
                    onClick = { Support.open(ctx) })
            }
        }

      }

      // build 130: alle servers van het account zijn er voor elk profiel (kiezen en vernieuwen); toevoegen, wijzigen en
      // verwijderen blijven bij het hoofdprofiel
      if (page == MyBingrPage.SETTINGS) {
            // MARK: server
            item {
                S2Section(L("Server"), if (isMain) L("The server, Trakt and OpenSubtitles belong to the main account and are used by all profiles.")
                    else L("All servers of the account are available in every profile. Adding, editing and removing servers is done in the main account.")) {
                    Store.playlists.forEach { src ->
                        val active = src.id == source?.id
                        S2Row(src.name, subtitle = src.kind.title,
                            leading = { AvatarCircle(src, tvs(34)) },
                            onLongClick = { if (isMain) editing = src },
                            onClick = { if (!active) AppState.open(src) },
                            trailing = { if (active) Icon(Icons.Default.Check, null, tint = Theme.red, modifier = Modifier.size(tvs(20))) })
                    }
                    if (source != null) {
                        val current = Store.playlists.firstOrNull { it.id == source.id } ?: source
                        S2Row(L("%@ · %d channels · %d movies · %d series", current.kind.title,
                            lib.channels.size, lib.movies.size, lib.series.size), tint = Theme.secondary)
                        if (isMain) {
                            S2Row(L("Edit server"), icon = Icons.Default.Dns, onClick = { editing = current })
                            S2Row(L("Categories on/off (NL, BE, DE…)"), icon = Icons.Default.FilterList, onClick = { sub = S2SettingsSub.CATEGORIES })
                            S2Row(L("Manage groups and channels"), icon = Icons.Default.Tune, onClick = { sub = S2SettingsSub.MANAGE })
                            S2Row(L("Check source"), icon = Icons.Default.Analytics, onClick = { sub = S2SettingsSub.CHECK })
                        }
                        // build 130: eerst deze server, daarna de andere één voor één op de achtergrond
                        S2Row(L("Refresh now"), icon = Icons.Default.Refresh, enabled = !Playlists.refreshingAll,
                            subtitle = refreshSubtitle(), onClick = {
                            AppScope.launch { runCatching { Playlists.refreshAll(spinner = true) } }
                        })
                    }
                    // build 88: met Gratis één server; bestaande extra servers blijven gewoon werken
                    val canAdd = Entitlements.canAddServer(Store.playlists.size)
                    if (isMain) S2Row(L("Add server"), icon = Icons.Default.Add,
                        onClick = { if (canAdd) addingServer = true else Upgrade.show(Entitlements.Feature.MULTI_SERVER) },
                        trailing = { if (!(canAdd)) LifetimeBadge() })
                    if (source != null && isMain) {
                        val current = Store.playlists.firstOrNull { it.id == source.id } ?: source
                        S2Row(L("Delete server"), icon = Icons.Default.Delete, tint = Theme.red, onClick = { deletingServer = current })
                    }
                }
            }

      }

      if (page == MyBingrPage.SETTINGS) {
            // MARK: afspeellijsten bijwerken (build 130: vernieuwen voor elk profiel, het interval alleen in het hoofdprofiel)
            item {
                S2Section(L("Update playlists"), L("Channels, movies, series and the TV guide are refreshed in the background at this interval.")) {
                    if (isMain) S2Dropdown(L("Update playlists"), RefreshPolicy.entries.map { it to it.title }, refreshPolicy) {
                        refreshPolicy = it; RefreshPolicy.current = it
                    }
                    val busy = Playlists.refreshingAll
                    S2Row(L("Refresh all playlists now"), icon = Icons.Default.Refresh, enabled = !busy, subtitle = refreshSubtitle(),
                        onClick = { AppScope.launch { runCatching { Playlists.refreshAll() } } },
                        trailing = { if (busy) CircularProgressIndicator(color = Theme.red, strokeWidth = 2.dp, modifier = Modifier.size(18.dp)) })
                }
            }
      }

      if (page == MyBingrPage.PROFILES) {
            // MARK: profielen
            item {
                S2Section(L("Profiles"), L("Each profile uses the same server, with its own My List, Continue watching, favorites, history and age limit.")) {
                    Store.profiles.forEach { p ->
                        S2Row(p.name,
                            subtitle = if (Store.isMain(p)) L("Main account")
                                else p.effectiveAgeLimit?.let { L("Up to %d years", it) } ?: L("Profile"),
                            leading = { ProfileAvatar(p, tvs(34)) },
                            onClick = { editingProfile = p },
                            trailing = {
                                if (ParentalControl.hasPIN(p)) Icon(Icons.Default.Lock, null, tint = Theme.secondary, modifier = Modifier.size(tvs(16)))
                                Icon(Icons.Default.Edit, null, tint = Theme.secondary, modifier = Modifier.size(tvs(18)))
                            })
                    }
                    if (Store.profiles.size < 8) {
                        S2Row(L("Add profile"), icon = Icons.Default.PersonAdd, onClick = { addingProfile = true })
                    }
                }
            }
        }

      if (page == MyBingrPage.SETTINGS && isMain) {
            // MARK: NAS en netwerk (stond vroeger onder Bibliotheek)
            item {
                S2Section(L("Library"), L("Watch history is kept per profile. Plex, Jellyfin and Emby can be added as a playlist.")) {
                    S2Row(L("NAS & network (SMB/DLNA)"), icon = Icons.Default.Lan, onClick = { sub = S2SettingsSub.NAS })
                }
            }


      }

      if (page == MyBingrPage.TRAKT) {
            // MARK: Trakt
            item {
                S2Section("Trakt", L("What you watch in Bingr is scrobbled automatically. What you've watched on Trakt (or in another app) is checked off here.")) {
                    if (Trakt.isLoggedIn) {
                        S2Row(Trakt.displayName ?: Trakt.username ?: "Trakt", subtitle = Trakt.username, leading = {
                            Box(Modifier.size(tvs(52)).clip(CircleShape).background(Theme.elevated), contentAlignment = Alignment.Center) {
                                val a = Trakt.avatar
                                if (a != null) RemoteImage(a, Modifier.size(tvs(52)).clip(CircleShape))
                                else Icon(Icons.Default.Person, null, tint = Theme.secondary)
                            }
                        })
                        S2Toggle(L("Sync playback progress"), Trakt.scrobbleEnabled, icon = Icons.Default.Sync) { Trakt.scrobbleEnabled = it }
                        S2Toggle(L("Ask for ratings"), Trakt.askRating, icon = Icons.Default.Star) { Trakt.askRating = it }
                        S2Row(L("Watched"), value = L("%d movies · %d episodes", Trakt.watchedMovies.size,
                            Trakt.watchedEpisodes.values.sumOf { it.size }))
                        Trakt.lastRefresh?.let { S2Row(L("Last synced"), value = s2Time(it)) }
                        Trakt.lastScrobble?.let { m ->
                            S2Row(L("Last sent"), subtitle = m + (Trakt.lastScrobbleDate?.let { " · " + s2Time(it) } ?: ""))
                        }
                        val syncing = Trakt.isSyncing
                        S2Row(
                            when {
                                syncing -> L("Syncing…")
                                Trakt.justSynced -> L("Up to date")
                                else -> L("Sync now")
                            },
                            icon = if (Trakt.justSynced) Icons.Default.CheckCircle else Icons.Default.Sync,
                            enabled = !syncing,
                            onClick = { s2TraktSyncNow() },
                            trailing = {
                                if (syncing) CircularProgressIndicator(color = Theme.red, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                            })
                        S2Row(L("Sign out of Trakt"), icon = Icons.AutoMirrored.Filled.Logout, tint = Theme.red, onClick = { Trakt.logout() })
                    } else {
                        S2Row(L("Connect Trakt"), icon = Icons.Default.Link,
                            trailing = { if (!(Entitlements.trakt)) LifetimeBadge() }, onClick = {
                            // build 88: Trakt koppelen hoort bij Lifetime
                            if (!Entitlements.trakt) Upgrade.show(Entitlements.Feature.TRAKT)
                            else s2WithServerKeys({ Secrets.traktReady }, { showTraktLogin = true }, { setup = "trakt" })
                        })
                    }
                    Trakt.lastError?.let { S2Row(it, tint = Theme.red) }
                }
            }
        }

      if (page == MyBingrPage.SETTINGS) {
        // MARK: OpenSubtitles
        item {
            S2Section("OpenSubtitles", L("While playing, choose OpenSubtitles to pick another subtitle or language. Automatically uses a subtitle track in the stream first; otherwise the best match from OpenSubtitles is loaded (requires sign-in).") +
                " " + L("Automatic subtitles “When needed”: if the audio isn't in your subtitle language, subtitles in that language are turned on by themselves.") +
                " " + L("These choices apply to this profile only.")) {
                if (OpenSubtitles.isLoggedIn) {
                    S2Row(L("Signed in as"), value = OpenSubtitles.username ?: "")
                    OpenSubtitles.remainingDownloads?.let { S2Row(L("Downloads left today"), value = "$it") }
                    if (isMain) {
                        S2Row(L("Sign out"), icon = Icons.AutoMirrored.Filled.Logout, tint = Theme.red, onClick = {
                            AppScope.launch { runCatching { OpenSubtitles.logout() } }
                        })
                    }
                } else if (isMain) {
                    S2Row(L("Sign in to OpenSubtitles"), icon = Icons.Default.Subtitles, onClick = {
                        s2WithServerKeys({ Secrets.openSubtitlesReady }, { showOSLogin = true }, { setup = "opensubtitles" })
                    })
                }
                S2Dropdown(L("Default language"),
                    listOf("" to L("All languages")) + TrackPreferences.sortedCodes.map { it to TrackPreferences.label(it) },
                    OpenSubtitles.defaultLanguage) { v ->
                    viewingChange {
                        OpenSubtitles.defaultLanguage = v
                        Store.updateActivePreferences { it.copy(subtitleLanguage = v) }
                    }
                }
                // build 59: uit / alleen als nodig (andere audiotaal) / altijd
                S2Dropdown(L("Automatic subtitles"), listOf(
                    TrackPreferences.AUTO_OFF to L("Off"),
                    TrackPreferences.AUTO_WHEN_NEEDED to L("When needed"),
                    TrackPreferences.AUTO_ALWAYS to L("Always"),
                ), TrackPreferences.autoSubtitles) { v -> // build 91: rechtstreeks lezen (ook na een wijziging uit de sync)
                    viewingChange {
                        TrackPreferences.autoSubtitles = v
                        Store.updateActivePreferences { it.copy(autoSubtitles = null, autoSubtitleMode = v) }
                    }
                }
            }
        }

        // MARK: speler
        item {
            S2Section(L("Player"), L("Automatic uses ExoPlayer for HLS, DASH and MP4 and VLC for everything else. If a stream fails, Bingr switches over and reconnects automatically. The default languages are chosen automatically when a stream has them.")) {
                S2Dropdown(L("Player engine"), listOf("auto" to L("Automatic"), "apple" to "ExoPlayer", "vlc" to "VLC"), engine) {
                    engine = it; Prefs.put("player.engine", it)
                }
                // Prefs "subtitle.size": 0 klein, 1 middel, 2 groot (ook tijdens het afspelen bij Opties)
                S2Dropdown(L("Subtitle size"), SubtitleSize.entries.map { it to it.title }, SubtitleSize.current) {
                    SubtitleSize.current = it
                }
                // build 59: standaard de taal van de app; "Zoals de stream" kiest niets automatisch
                S2Dropdown(L("Default audio language"),
                    listOf("" to L("App language"), TrackPreferences.STREAM to L("As in the stream")) +
                        TrackPreferences.sortedCodes.map { it to TrackPreferences.label(it) },
                    TrackPreferences.audio) { v ->
                    viewingChange {
                        TrackPreferences.audio = v
                        Store.updateActivePreferences { it.copy(audioLanguage = v) }
                    }
                }
                S2Dropdown(L("Zap delay"), PlayerSettings.zapDelayOptions, zapDelay) {
                    zapDelay = it; PlayerSettings.zapDelay = it
                }
                S2Toggle(L("Channel preview"), channelPreview) { channelPreview = it; PlayerSettings.channelPreview = it }
                S2Toggle(L("Start with last channel"), resumeLastChannel,
                    subtitle = L("After choosing a profile, the channel it watched last starts playing.")) {
                    resumeLastChannel = it; PlayerSettings.resumeLastChannel = it
                }
                S2Dropdown(L("Deinterlacing"), PlayerSettings.deinterlaceOptions, deinterlace) {
                    deinterlace = it; PlayerSettings.deinterlace = it
                }
                if (AppState.isTV) {
                    S2Toggle(L("Match frame rate"), matchFrameRate) { matchFrameRate = it; PlayerSettings.matchFrameRate = it }
                } else {
                    S2Toggle(L("Show on external display"), ExternalDisplay.enabled) { ExternalDisplay.use(it) }
                }
                // build 60 (docs/ACCOUNT.md): `subtitleLanguage` = taal van ondertitels in de stream, "" = de app-taal.
                // Het oude "Uit" is nu "Automatische ondertitels: Uit" en staat hier niet meer.
                S2Dropdown(L("Default subtitle language"),
                    listOf("" to L("App language")) + TrackPreferences.sortedCodes.map { it to TrackPreferences.label(it) },
                    TrackPreferences.subtitle.takeIf { it != "off" } ?: "") { v ->
                    viewingChange {
                        // oud "off" eerst vastleggen als automatische stand, anders gaat "Uit" verloren
                        if (TrackPreferences.subtitle == "off") TrackPreferences.autoSubtitles = TrackPreferences.autoSubtitles
                        TrackPreferences.subtitle = v
                        Store.updateActivePreferences { it.copy(streamSubtitleLanguage = v) }
                    }
                }
            }
        }

        // MARK: pincode (per profiel) + kinderslot (hoofdaccount)
        item {
            S2Section(L("PIN code"), L("With a PIN, the profile asks for the code when it is chosen in “Who's watching?”.")) {
                if (profile != null) {
                    val has = ParentalControl.hasPIN(profile)
                    S2Row(if (has) L("This profile is protected with a PIN") else L("No PIN on this profile"),
                        icon = if (has) Icons.Default.Lock else Icons.Default.LockOpen,
                        tint = if (has) Theme.green else Theme.secondary)
                    if (has) {
                        // build 141 (H1): eerst de huidige pincode (PinChangeScreen)
                        S2Row(L("Change PIN"), onClick = { pinMode = S2PinMode.SET })
                        S2Row(L("Remove PIN"), tint = Theme.red, onClick = { pinMode = S2PinMode.DISABLE })
                    } else {
                        S2Row(L("Set PIN"), icon = Icons.Default.Lock, onClick = { pinMode = S2PinMode.SET })
                    }
                }
                val mainHasPIN = profile?.let { ParentalControl.hasPIN(it) } ?: ParentalControl.hasPIN
                if (isMain && mainHasPIN) {
                    val parental = Store.parental
                    // build 141 (H1): uitzetten alleen met de pincode (aanzetten mag altijd)
                    S2Toggle(L("Lock adult content with this PIN"), parental.enabled) { v ->
                        if (v) Store.updateParental { it.copy(enabled = true) }
                        else mainPinFor = L("Enter PIN to turn off") to { Store.updateParental { it.copy(enabled = false) } }
                    }
                    if (parental.enabled) {
                        S2Toggle(L("Lock adult groups automatically"), parental.autoLockAdult) { v ->
                            if (v) Store.updateParental { it.copy(autoLockAdult = true) }
                            else mainPinFor = L("Enter PIN to turn off") to { Store.updateParental { it.copy(autoLockAdult = false) } }
                        }
                        val unlocked = ParentalControl.isUnlocked
                        S2Row(if (unlocked) L("Unlocked for 30 minutes") else L("Locked"),
                            icon = if (unlocked) Icons.Default.LockOpen else Icons.Default.Lock,
                            tint = if (unlocked) Theme.imdb else Theme.green)
                        if (unlocked) S2Row(L("Lock now"), tint = Theme.red, onClick = { ParentalControl.lock() })
                        else S2Row(L("Unlock with PIN"), tint = Theme.red, onClick = { pinMode = S2PinMode.UNLOCK })
                        S2Row(L("Lock individual groups under Manage groups and channels (hold a group). Set age limits per profile under Profiles."),
                            tint = Theme.secondary)
                    }
                }
            }
        }

        // MARK: weergave
        item {
            S2Section(L("Interface")) {
                S2Dropdown(L("Start screen"),
                    listOf(0 to L("Home"), 1 to L("Live TV"), 2 to L("Movies"), 3 to L("Series")), startTab) {
                    startTab = it; Prefs.put("ui.startTab", it)
                }
                // build 140: categorieën aan/uit voor het hele account (alle profielen behalve kinderprofielen; die zien dit niet)
                if (Store.activeProfile?.kids != true) {
                    S2Row(L("Categories on/off (NL, BE, DE…)"), icon = Icons.Default.FilterList, onClick = { sub = S2SettingsSub.CATEGORIES })
                }
                S2Toggle(L("Show Live TV"), showLive) { showLive = it; Prefs.put("ui.showLive", it) }
                S2Toggle(L("Show Movies"), showMovies) { showMovies = it; Prefs.put("ui.showMovies", it) }
                S2Toggle(L("Show Series"), showSeries) { showSeries = it; Prefs.put("ui.showSeries", it) }
                // build 82: trailer zonder geluid in de kop van film- en seriepagina's
                S2Toggle(L("Play trailers automatically"), autoplayTrailers,
                    subtitle = if (autoplayTrailers) L("Muted, on movie and series pages. Not on metered connections or with animations off.") else null
                ) { autoplayTrailers = it; HeroTrailer.enabled = it }
                if (isMain) S2Row(L("Rename, hide and reorder"), icon = Icons.Default.Tune, onClick = { sub = S2SettingsSub.MANAGE })
            }
        }

        // MARK: uiterlijk (build 52; build 54: accentkleur per profiel, app-icoon alleen voor het hoofdprofiel)
        item {
            S2Section(L("Appearance"),
                if (AppIcon.canChoose) L("Each profile has its own accent color. The app icon is only changed on this device.")
                else L("Each profile has its own accent color.")) {
                // build 88: accentkleuren horen bij Lifetime
                if (Entitlements.themes) {
                    S2Dropdown(L("Accent color"), Accent.options.map { it.key to L(it.titleKey) }, Accent.key) { Accent.set(it) }
                } else {
                    S2Row(L("Accent color"), value = L("Red"), onClick = { Upgrade.show(Entitlements.Feature.THEMES) },
                        trailing = { LifetimeBadge() })
                }
                if (AppIcon.canChoose) {
                    S2Dropdown(L("App icon"), AppIcon.options.map { it.key to L(it.titleKey) }, AppIcon.current) { k ->
                        if (k != AppIcon.current) iconConfirm = k
                    }
                }
            }
        }

        // MARK: pushmeldingen (docs/PUSH.md): per apparaat, alleen telefoon/tablet met Firebase in de build en een account
        if (!AppState.isTV && Push.configured && Account.isSignedIn) {
            item {
                S2Section(L("Notifications"),
                    L("Get a notification when a new version of Bingr is available, when a program you set a reminder for is about to start, and for messages from Bingr.")) {
                    val pushOn = Push.enabled
                    S2Toggle(L("Push notifications"), pushOn, icon = Icons.Default.Notifications) { Push.enabled = it }
                    if (pushOn && !Push.permitted) {
                        S2Row(L("Notifications are turned off for Bingr in Android settings."), tint = Theme.secondary)
                        S2Row(L("Open notification settings"), icon = Icons.Default.Settings, onClick = { Push.openSystemSettings(ctx) })
                    }
                }
            }
        }

        // MARK: taal
        item {
            S2Section(L("Language")) {
                S2Dropdown(L("App language"), listOf("" to L("System")) + Localizer.supported, Localizer.override) {
                    Localizer.setOverride(it)
                }
            }
        }

        // niet op TV (build 60): daar is geen deelmenu of bestandskiezer, dus beide knoppen liepen dood
        if (isMain && !AppState.isTV) {
            // MARK: delen tussen apparaten (vervangt iCloud)
            item {
                S2Section(L("Share between devices"),
                    L("Export saves your playlists (incl. passwords), favorites, My List, watch progress, history and your changes to a file. Import it on another device to merge.")) {
                    S2Row(L("Playlists"), value = "${Store.playlists.size}")
                    S2Row(L("Watch progress"), value = L("%d titles", Store.progress.size))
                    // build 141 (M7): eerst een waarschuwing, dan de pincode (als die er is), dan pas het deelmenu
                    S2Row(L("Export to file"), icon = Icons.Default.Upload, onClick = { confirmExport = true })
                    S2Row(L("Import from file"), icon = Icons.Default.Download, onClick = {
                        val ok = runCatching { importLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }.isSuccess
                        if (!ok) shareStatus = L("Import failed")
                    })
                    shareStatus?.let { S2Row(it, tint = Theme.secondary) }
                }
            }
        }

        // MARK: over
        item {
            // build 112: verplichte bronvermelding van TMDB en JustWatch (stond eerder onder de Home-rijen)
            S2Section(L("About"), footer = DiscoverKind.attribution) {
                S2Row(L("Version"), value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                S2Row(L("IMDb data"), value = if (Secrets.isSet(Secrets.omdbAPIKey)) L("Active (OMDb)") else L("No OMDb key"))
            }
        }

        // MARK: koppelingen (geavanceerd, onderaan): de sleutels komen normaal van de Bingr-server
        if (isMain) {
            item {
                S2Section(footer = L("Trakt and OpenSubtitles require a one-time app registration. Saved on this device; use Export to take them along.")) {
                    S2Row(L("Set up connections"), icon = Icons.Default.Key, onClick = { setup = "all" })
                }
            }
        }

      }
    }

    // MARK: dialogen
    if (linkingTv) S2LinkTvScreen { linkingTv = false }
    if (showDevices) DevicesScreen { showDevices = false }
    when (accountConfirm) {
        "signout" -> S2ActionDialog(
            L("Sign out of Bingr on this device?"),
            listOf(S2Action(L("Sign out"), Icons.AutoMirrored.Filled.Logout, destructive = true) {
                AppScope.launch { Account.signOut(); AppState.signedOut() }
            }),
            onDismiss = { accountConfirm = null }
        )
        // H1: bevestigen met het huidige wachtwoord (de server controleert het; fout = 403 wrong_password)
        "delete" -> S2DeleteAccountDialog(onDismiss = { accountConfirm = null }) { password ->
            accountError = null
            AppScope.launch {
                try { Account.deleteAccount(password); AppState.signedOut() }
                catch (e: AccountException) { accountError = e.message }
                catch (e: Exception) { accountError = L("Something went wrong. Please try again.") }
            }
        }
    }
    if (showTraktLogin) TraktLoginScreen { showTraktLogin = false }
    if (showOSLogin) OpenSubtitlesLoginScreen { showOSLogin = false }
    setup?.let { focus ->
        ConnectionsSetupScreen(focus) {
            setup = null
            when (focus) {
                "trakt" -> if (Secrets.traktReady && !Trakt.isLoggedIn && Entitlements.trakt) showTraktLogin = true
                "opensubtitles" -> if (Secrets.openSubtitlesReady && !OpenSubtitles.isLoggedIn) showOSLogin = true
            }
        }
    }
    deletingServer?.let { src ->
        S2ActionDialog(
            L("Profiles keep their own lists. Channels, movies and series of this server disappear from the app."),
            listOf(S2Action(L("Delete %@", src.name), Icons.Default.Delete, destructive = true) {
                val wasActive = Playlists.source?.id == src.id
                Playlists.forget(src)
                if (wasActive) {
                    val next = Store.mainSource
                    if (next != null) AppState.open(next) else AppState.switchPlaylist()
                }
            }),
            onDismiss = { deletingServer = null }
        )
    }
    if (confirmExport) {
        S2ActionDialog(
            L("Export to file"),
            listOf(S2Action(L("Export"), Icons.Default.Upload) {
                val run: () -> Unit = {
                    val ok = s2ExportBackup(ctx) { i -> runCatching { exportLauncher.launch(i) }.onFailure { s2DeleteBackupFile(ctx) } }
                    if (!ok) shareStatus = L("Export failed")
                }
                val main = Store.mainProfileID
                val needsPIN = if (main != null) ParentalControl.hasPIN(main) else ParentalControl.hasPIN
                if (needsPIN) mainPinFor = L("Enter PIN") to run else run()
            }),
            onDismiss = { confirmExport = false },
            message = L("The file contains the passwords of your servers and your PINs. Only share it with yourself, and delete it after importing."),
        )
    }
    mainPinFor?.let { (title, action) ->
        MainPinGate(title, onDismiss = { mainPinFor = null }, onOK = action)
    }
    val prof = Store.activeProfile
    when (pinMode) {
        S2PinMode.SET -> PinChangeScreen(profileID = prof?.id) { pinMode = null }
        S2PinMode.UNLOCK -> PinPad(L("Enter PIN"), onDismiss = { pinMode = null }) { pin ->
            if (prof != null) ParentalControl.verify(pin, prof) else ParentalControl.verify(pin)
        }
        S2PinMode.DISABLE -> PinPad(L("Enter PIN to turn off"), onDismiss = { pinMode = null }) { pin ->
            if (prof == null) {
                if (ParentalControl.verify(pin)) { ParentalControl.setPIN(null); Store.updateParental { it.copy(enabled = false) }; true } else false
            } else if (ParentalControl.verify(pin, prof)) {
                ParentalControl.setPIN(null, prof)
                if (Store.isMain(prof)) Store.updateParental { it.copy(enabled = false) }
                true
            } else false
        }
        null -> {}
    }
}

/** Trakt nu synchroniseren (draaiende pijltjes zolang [Trakt.isSyncing]). */
private fun s2TraktSyncNow() {
    if (Trakt.isSyncing) return
    AppScope.launch { runCatching { Trakt.refresh(force = true) } }
}

/** Verder kijken van dít profiel: per serie alleen de laatste aflevering (= MyBingrHeader.swift). */

/**
 * Bovenkant van Mijn Bingr, zoals "Mijn Netflix": je profiel (tik = wisselen), Trakt-status,
 * en daaronder Verder kijken, Mijn lijst en favoriete zenders (= MyBingrHeader.swift).
 */
@Composable
private fun S2MyBingrHeader() {
    val lib = Playlists.library
    val pid = Playlists.source?.id
    val index = rememberHomeIndex(lib)
    val profile = Store.activeProfile
    val progress = Store.progress
    var choice by remember { mutableStateOf<ResumeChoice?>(null) }

    // build 128: Verder kijken, Mijn lijst en favorieten over alle servers (zelfde als Home)
    val allV = AllServers.version + CatalogMeta.version
    val traktNone = remember { emptyList<ContinueItem>() }
    val cont = remember(progress, index, pid, Store.activeProfileID, allV) { continueItems(Filter.ALL, index, pid, progress, traktNone) }
    val myList = Store.myList
    val mine = remember(myList, index, pid, allV) {
        if (pid == null) emptyList<DetailTarget>()
        else AllServers.myList(myList, pid) { k ->
            index.movie(k, pid)?.let { DetailTarget.MovieT(it) } ?: index.series(k, pid)?.let { DetailTarget.SeriesT(it) }
        }
    }
    val favorites = Store.favorites
    val favs = remember(favorites, index, pid, allV) {
        if (pid == null) emptyList<Channel>() else {
            val times = StateSync.addedTimes(Store.activeProfileID, "favorites")
            AllServers.channels(favorites.sortedByDescending { times[it] ?: 0L }, pid) { index.channel(it, pid) }
        }
    }

    Column(Modifier.fillMaxWidth().padding(vertical = tvs(8)), verticalArrangement = Arrangement.spacedBy(tvs(18))) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(tvs(8))) {
            ProfileAvatar(profile, tvs(84))
            var focused by remember { mutableStateOf(false) }
            // build 72: naam + pijltje opent het profielmenu (andere profielen, Wissel van profiel, …)
            var menu by remember { mutableStateOf(false) }
            Box {
            Row(
                Modifier.onFocusChanged { focused = it.isFocused }
                    .focusLift(1.06f) { menu = true }
                    .clip(RoundedCornerShape(50))
                    .background(if (focused && AppState.isTV) Color.White else Color.Transparent)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val fg = if (focused && AppState.isTV) Color.Black else Color.White
                Text(profile?.name ?: L("My Bingr"), color = fg, fontSize = tsp(20), fontWeight = FontWeight.Bold, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Default.KeyboardArrowDown, L("Switch profile"), tint = fg, modifier = Modifier.size(tvs(18)))
            }
            ProfileMenu(menu) { menu = false }
            }
            if (Trakt.isLoggedIn) S2TraktStatus()
        }

        if (cont.isNotEmpty()) {
            MediaRow(L("Continue watching")) {
                items(cont) { c ->
                    ContinueCard(
                        c.title, c.artwork, c.fraction, c.subtitle,
                        Modifier.focusLift { choice = c.choice(index) }
                    )
                }
            }
        }
        if (mine.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = Theme.edge), verticalArrangement = Arrangement.spacedBy(tvs(8))) {
                Text(L("My List"), color = Color.White, fontSize = Theme.titleSize, fontWeight = FontWeight.Bold)
                Text(L("Add titles with + My List and they'll appear here."), color = Theme.secondary, fontSize = tsp(13))
            }
        } else {
            MediaRow(L("My List"), onSeeAll = { ScreensNav.category(CategoryTarget(L("My List"), mine)) }) {
                items(mine) { t ->
                    val watched = (t as? DetailTarget.MovieT)?.let { Playables.isWatched(it.movie, Trakt.imdb(it.movie)) } ?: false
                    PosterCard(t.title, t.poster, modifier = Modifier.focusLift { ScreensNav.detail(t) },
                        watched = watched, progress = progress[t.id]?.fraction,
                        watchedEpisodes = (t as? DetailTarget.SeriesT)?.let { Playables.watchedEpisodeCount(it.series) } ?: 0)
                }
            }
        }
        if (favs.isNotEmpty()) {
            MediaRow(L("Favorite channels")) {
                items(favs) { c ->
                    val now = if (c.sourceID == null) lib.nowPlaying(c) else null
                    ChannelCard(c, now, favorite = true,
                        modifier = Modifier.focusLift { AllServers.playChannel(c, favs, AllServers.Field.FAVORITES) })
                }
            }
        }
    }
    choice?.let { ResumeDialog(it) { choice = null } }
}

/** Trakt-status: draaiende pijltjes tijdens synchroniseren, anders de laatste sync; tik = nu synchroniseren. */
@Composable
private fun S2TraktStatus() {
    val syncing = Trakt.isSyncing
    var focused by remember { mutableStateOf(false) }
    val lit = focused && AppState.isTV
    Row(
        Modifier.onFocusChanged { focused = it.isFocused }
            .focusLift(1.05f) { s2TraktSyncNow() }
            .clip(RoundedCornerShape(50))
            .background(if (lit) Color.White else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (syncing) {
            CircularProgressIndicator(color = Theme.red, strokeWidth = 2.dp, modifier = Modifier.size(tvs(14)))
        } else {
            val ok = Trakt.lastError == null
            Icon(if (ok) Icons.Default.CheckCircle else Icons.Default.Warning, null,
                tint = if (ok) Theme.green else Theme.red, modifier = Modifier.size(tvs(14)))
        }
        Spacer(Modifier.width(6.dp))
        val text = when {
            syncing -> L("Syncing with Trakt…")
            Trakt.justSynced -> L("Up to date")
            else -> Trakt.lastRefresh?.let { L("Trakt synced %@", s2Time(it)) } ?: L("Sync now")
        }
        Text(text, color = if (lit) Color.Black else Theme.secondary, fontSize = tsp(12), fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** Tv koppelen vanaf telefoon/tablet: code van de tv invoeren (hoofdletters, zonder streepje of spaties). */
@Composable
private fun S2LinkTvScreen(onClose: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<String?>(null) }
    /** Build 141 (M3): eerst tonen welk apparaat toegang vraagt, pas na bevestigen goedkeuren. */
    var confirm by remember { mutableStateOf<Account.TvCodeInfo?>(null) }
    S2FullDialog(onClose) {
        S2Page(L("Link a TV"), onClose = onClose) {
            item {
                S2Section(footer = done?.let { L("%@ is now signed in.", it) }) {
                    Column(Modifier.padding(12.dp)) {
                        S2TextField(code, { v -> code = v.uppercase().filter { it.isLetterOrDigit() || it == '-' || it == ' ' }.take(9) },
                            L("Enter the code shown on your TV"))
                    }
                }
            }
            error?.let { e -> item { Text(e, color = Theme.red, fontSize = tsp(13), modifier = Modifier.padding(16.dp)) } }
            item {
                Box(Modifier.fillMaxWidth().padding(horizontal = Theme.edge, vertical = 16.dp)) {
                    if (Account.busy) CircularProgressIndicator(color = Theme.red, modifier = Modifier.align(Alignment.Center))
                    else {
                        val ok = Account.normalizeCode(code).length >= 6
                        NetflixButton(L("Link"), kind = if (ok) NetflixKind.ACCENT else NetflixKind.SECONDARY,
                            modifier = Modifier.fillMaxWidth()) {
                            if (!ok) return@NetflixButton
                            error = null; done = null
                            AppScope.launch {
                                try { confirm = Account.tvCodeInfo(code) }
                                catch (e: AccountException) { error = e.message }
                                catch (e: Exception) { error = L("Something went wrong. Please try again.") }
                            }
                        }
                    }
                }
            }
        }
    }
    // na het scherm, zodat het venster erboven ligt
    confirm?.let { info ->
        S2LinkTvConfirmDialog(info, onDismiss = { confirm = null }) {
            confirm = null
            error = null
            AppScope.launch {
                try { done = Account.approveTv(code); code = "" }
                catch (e: AccountException) { error = e.message }
                catch (e: Exception) { error = L("Something went wrong. Please try again.") }
            }
        }
    }
}

/**
 * Build 141 (M3): bevestigen vóór een tv toegang krijgt: soort apparaat, de naam die het apparaat opgaf, hoe lang
 * geleden de code is gemaakt (en plaats/platform als de server die geeft), met een waarschuwing tegen codes van anderen.
 */
@Composable
private fun S2LinkTvConfirmDialog(info: Account.TvCodeInfo, onDismiss: () -> Unit, onApprove: () -> Unit) {
    val what = listOfNotNull(info.label ?: info.platform, info.device.takeIf { it != info.label }?.let { "“$it”" }).joinToString(" · ")
    val age = info.ageSeconds?.let { a ->
        if (a < 90) L("Code created just now") else L("Code created %d min ago", ((a + 30) / 60).toInt())
    }
    val details = listOfNotNull(
        what.ifEmpty { info.device },
        info.platform?.takeIf { it != info.label }?.let { L("Platform: %@", it) },
        info.location?.let { L("Location: %@", it) },
        age,
    ).joinToString("\n")
    S2ActionDialog(
        L("Give this device access to your Bingr account?"),
        listOf(
            S2Action(L("Allow access"), Icons.Default.Tv, primary = true) { onApprove() },
            S2Action(L("Cancel")) { },
        ),
        onDismiss = onDismiss,
        message = details + "\n\n" + L("Only continue if this device is in front of you and you entered the code from its screen yourself. Never enter a code that someone sent you: they would get full access to your account, including your server passwords."),
    )
}

/**
 * Inloggen bij Trakt/OpenSubtitles: is de sleutel er (zelf ingevuld, van de server of ingebouwd), dan gewoon inloggen.
 * Ontbreekt hij, dan eerst de config van de Bingr-server ophalen; pas als hij dan nog ontbreekt "Koppelingen instellen".
 */
internal fun s2WithServerKeys(ready: () -> Boolean, login: () -> Unit, setup: () -> Unit) {
    if (ready()) { login(); return }
    if (!Account.isSignedIn || !Account.configured) { setup(); return }
    AppScope.launch {
        try { Account.fetchConfig() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
        if (ready()) login() else setup()
    }
}

/** Rij in Mijn Bingr die naar een andere pagina gaat: icoon, titel, aantal en een pijltje (zoals op bingr.online). */
@Composable
private fun S2NavRow(
    title: String,
    icon: ImageVector? = null,
    value: String? = null,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    /** Build 88: hoort bij Lifetime (Gratis): slotje "Lifetime" voor het pijltje. */
    locked: Boolean = false,
    onClick: () -> Unit,
) {
    S2Row(title, icon = icon, value = value, subtitle = subtitle, leading = leading, onClick = onClick, trailing = {
        if (locked) LifetimeBadge()
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Theme.secondary, modifier = Modifier.size(tvs(20)))
    })
}

/** H1: "Account verwijderen" bevestigen met het huidige wachtwoord (alleen in het geheugen van deze dialoog). */
@Composable
private fun S2DeleteAccountDialog(onDismiss: () -> Unit, onDelete: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    val fr = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Theme.elevated,
        title = { Text(L("Delete your Bingr account? This can't be undone."), color = Color.White) },
        text = {
            Column {
                Text(L("Enter your password to confirm."), color = Theme.secondary, fontSize = tsp(13))
                Spacer(Modifier.height(10.dp))
                S2TextField(password, { password = it }, L("Password"), password = true, modifier = Modifier.focusRequester(fr))
            }
        },
        confirmButton = {
            TextButton(onClick = { val p = password; password = ""; onDismiss(); onDelete(p) }, enabled = password.isNotEmpty()) {
                Text(L("Delete account"), color = if (password.isNotEmpty()) Theme.red else Theme.secondary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L("Cancel"), color = Color.White) } }
    )
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
}

/** Build 130: voortgang van "alles vernieuwen", of welke servers de laatste keer niet lukten. */
private fun refreshSubtitle(): String? = Playlists.refreshStatus
    ?: Playlists.refreshFailed.takeIf { it.isNotEmpty() }?.let { L("Couldn't refresh: %@", it.joinToString(", ")) }
