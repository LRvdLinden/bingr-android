package nl.lrvdlinden.bingr

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class MainTab(val id: Int, val title: String, val icon: ImageVector)

/**
 * Hoofdscherm (= MainTabView.swift). Telefoon: onderbalk; TV: bovenbalk met tabbladen zoals Netflix op TV.
 * Tabbladen: 0 Home, 1 Live TV, 2 Films, 3 Series, 4 Zoeken, 7 Categorieën (build 140, in plaats van Mijn Bingr),
 * 6 Downloads (build 52, niet op TV). 5 = Mijn Bingr: geen tabblad meer, maar bereikbaar via het profiel/de avatar bovenin
 * (profielmenu → Mijn Bingr, Instellingen, …).
 */
@Composable
fun MainScreen() {
    val tv = AppState.isTV
    // telefoon: max. 5 tabbladen; zoeken zit daar al als vergrootglas op Home/Films/Series (zoals iOS)
    val isPhone = !tv && LocalConfiguration.current.smallestScreenWidthDp < 600
    val source = Playlists.source
    val lib = Playlists.library

    val hasLive = lib.channels.isNotEmpty() || !(source?.kind?.isMediaServer ?: false)
    val showLive = Prefs.bool("ui.showLive", true)
    val showMovies = Prefs.bool("ui.showMovies", true)
    val showSeries = Prefs.bool("ui.showSeries", true)
    val tabs = buildList {
        add(MainTab(0, L("Home"), Icons.Default.Home))
        if (hasLive && showLive) add(MainTab(1, L("Live TV"), Icons.Default.LiveTv))
        if (showMovies) add(MainTab(2, L("Movies"), Icons.Default.Movie))
        if (showSeries) add(MainTab(3, L("Series"), Icons.Default.VideoLibrary))
        // build 140: "Categorieën" naast Series, in plaats van "Mijn Bingr" (dat zit in het profielmenu achter de avatar)
        add(MainTab(7, L("Categories"), Icons.Default.Category))
        if (!isPhone) add(MainTab(4, L("Search"), Icons.Default.Search))
        if (Downloads.supported) add(MainTab(6, L("Downloads"), Icons.Default.Download))
    }
    var tab by rememberSaveable {
        val start = Prefs.int("ui.startTab", 0)
        mutableIntStateOf(if (start in 0..3 && tabs.any { it.id == start }) start else 0)
    }
    // 5 (Mijn Bingr) is geen tabblad meer maar kan wel open staan (via het profielmenu)
    val current = if (tab == 5 || tabs.any { it.id == tab }) tab else 0
    /** Tabblad kiezen; Mijn Bingr opent dan altijd op het overzicht. */
    fun select(id: Int) {
        if (id == 5) MyBingrNav.page = MyBingrPage.ROOT
        tab = id
    }
    // profielmenu (build 72): naar Mijn Bingr / Instellingen / Account en apparaten / Profielen beheren
    // build 128: één lijst over alle servers (achtergrond: andere servers, meta, verhuizen)
    LaunchedEffect(Unit) { if (!Screenshots.isActive) AllServers.start() }
    // build 128: categorieën aan/uit per profiel (eenmalige migratie van de keuzes per server)
    // build 140: daarna naar de account-set (ingelogd: vastgelegd na de eerste sync, zie StateSync)
    LaunchedEffect(Store.profiles.size) {
        StartDiagnostics.task("categorieën migreren") { Store.migrateCategoriesToProfiles() }
        // build 147: de profielbestanden buiten de main thread lezen
        Store.migrateCategoriesToAccountOffMain(allowFlag = !Account.isSignedIn)
    }
    // build 146: startdiagnose "klaar" zodra het hoofdscherm even in beeld is; support-badge bijwerken
    LaunchedEffect(Unit) {
        delay(1_500)
        StartDiagnostics.mark("ready")
        SupportCenter.refresh()
    }
    // build 146: tik op de pushmelding support_reply / bingr://support: Instellingen → Support (kinderprofiel: ouderpoort)
    LaunchedEffect(SupportCenter.openRequest) {
        if (SupportCenter.openRequest != null) MyBingrNav.openSettings(SettingsTab.SUPPORT)
    }
    LaunchedEffect(MyBingrNav.tabRequest) {
        val t = MyBingrNav.tabRequest ?: return@LaunchedEffect
        MyBingrNav.tabRequest = null
        // build 140: Mijn Bingr/Instellingen vanuit een kinderprofiel: eerst de rekensom (ouderpoort)
        if (t == 5) KidsGate.guard { tab = t } else tab = t
    }

    // terug: eerst naar Home, dan naar de profielkeuze (lagen erboven hebben hun eigen terug-afhandeling)
    BackHandler(enabled = ScreensNav.stack.isEmpty()) {
        // build 140: naar "Wie kijkt er?" vanuit een kinderprofiel: eerst de rekensom
        if (current != 0) tab = 0 else KidsGate.guard { AppState.switchPlaylist() }
    }

    // item uit een andere afspeellijst (globaal zoeken / herinnering) openen zodra de lijst geladen is
    LaunchedEffect(AppState.pendingItemID, lib.channels.size, lib.movies.size, lib.series.size) {
        val id = AppState.pendingItemID ?: return@LaunchedEffect
        val l = Playlists.library
        val c = l.channels.firstOrNull { it.id == id }
        val m = if (c == null) l.movies.firstOrNull { it.id == id } else null
        val s = if (c == null && m == null) l.series.firstOrNull { it.id == id } else null
        when {
            c != null -> { AppState.pendingItemID = null; AppState.play(Playables.channel(c, l.nowPlaying(c)), l.channels) }
            m != null -> { AppState.pendingItemID = null; ScreensNav.detail(DetailTarget.MovieT(m)) }
            s != null -> { AppState.pendingItemID = null; ScreensNav.detail(DetailTarget.SeriesT(s)) }
        }
    }

    // build 120: titel uit een link (e-mail van Bingr, of sinds build 132 een gedeelde link): in de bibliotheek van de
    // actieve bron zoeken op TMDB-id of titel (+ jaar). Eén goede match → detailpagina, anders het zoekscherm. Wacht tot
    // de bibliotheek geladen is.
    // Build 132: elke open server telt (sinds build 130 kan een profiel een andere server dan de hoofdserver openen; dan
    // bleef de link eerder liggen), en staat de titel niet op de actieve server, dan de andere servers van het account
    // (cache, filters van dit profiel; build 128/129): detailpagina via die server, zonder te wisselen.
    LaunchedEffect(PushNav.pendingTitle, Playlists.isLoading, Playlists.isFiltering, source?.id, lib.movies.size, lib.series.size) {
        val link = PushNav.pendingTitle ?: return@LaunchedEffect
        if (Playlists.isLoading || Playlists.isFiltering) return@LaunchedEffect
        val active = source ?: return@LaunchedEffect
        // nog leeg: misschien begint het laden net; na een paar seconden zonder lijst toch het zoekscherm
        if (Playlists.library.isEmpty) delay(6_000)
        if (PushNav.pendingTitle != link || Playlists.isLoading) return@LaunchedEffect
        PushNav.pendingTitle = null
        // build 132: buiten dit effect afmaken: het leegmaken van pendingTitle (een sleutel) of een veranderende
        // bibliotheek start het effect opnieuw, en dat brak het zoeken (grote lijsten, cache van andere servers) af
        AppScope.launch { openTitleLink(link, active.id) }
    }

    // "Start bij laatste zender" (build 52): na het kiezen van een profiel meteen de laatst bekeken zender
    LaunchedEffect(AppState.pendingLastChannel, lib.channels.size) {
        if (!AppState.pendingLastChannel || lib.channels.isEmpty()) return@LaunchedEffect
        AppState.pendingLastChannel = false
        val pid = Playlists.source?.id ?: return@LaunchedEffect
        val ch = Store.recentChannels.asSequence()
            .mapNotNull { k -> k.split('|', limit = 2).takeIf { it.size == 2 && it[0] == pid }?.get(1) }
            .mapNotNull { id -> lib.channels.firstOrNull { it.id == id } }
            .firstOrNull() ?: return@LaunchedEffect
        if (AppState.playing == null && AppState.pendingItemID == null) {
            AppState.play(Playables.channel(ch, lib.nowPlaying(ch)), lib.channels.filter { it.group == ch.group })
        }
    }

    val holder = rememberSaveableStateHolder()
    val covered = ScreensNav.stack.isNotEmpty() || Playlists.isLoading

    Box(Modifier.fillMaxSize().background(Theme.bg)) {
        Column(Modifier.fillMaxSize().blockFocus(covered)) {
            if (tv) TvTabBar(tabs, current, focusEnabled = !covered) { select(it) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                holder.SaveableStateProvider(current) {
                    when (current) {
                        0 -> HomeScreen(Filter.ALL)
                        1 -> Box(Modifier.fillMaxSize().then(if (tv) Modifier else Modifier.statusBarsPadding())) { LiveTVScreen() }
                        2 -> HomeScreen(Filter.MOVIES)
                        3 -> HomeScreen(Filter.SERIES)
                        4 -> SearchScreen(modal = false, onClose = {})
                        6 -> Box(Modifier.fillMaxSize().statusBarsPadding()) { DownloadsScreen() }
                        // build 140: het bestaande overzicht Categorieën als tabblad (kinderprofiel: alleen kindercategorieën,
                        // want het overzicht komt uit de gefilterde bibliotheek)
                        7 -> CategoriesScreen(Filter.ALL, asTab = true)
                        else -> Box(Modifier.fillMaxSize().then(if (tv) Modifier else Modifier.statusBarsPadding())) { SettingsScreen() }
                    }
                }
            }
            if (!tv) {
                NavigationBar(containerColor = Color.Black.copy(alpha = 0.92f), contentColor = Color.White, tonalElevation = 0.dp) {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = t.id == current,
                            onClick = { select(t.id) },
                            icon = { Icon(t.icon, contentDescription = t.title) },
                            label = { Text(t.title, maxLines = 1, fontSize = tsp(10)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.White, selectedTextColor = Color.White,
                                unselectedIconColor = Theme.secondary, unselectedTextColor = Theme.secondary,
                                indicatorColor = Color.Transparent,
                            )
                        )
                    }
                }
            }
        }

        // ook tijdens het filteren voor een profiel met leeftijdsgrens (dan is de lijst nog leeg)
        AnimatedVisibility(Playlists.isLoading || (Playlists.isFiltering && Playlists.library.isEmpty), enter = fadeIn(), exit = fadeOut()) {
            FullLayer { LoadingOverlay(Playlists.loadingText, source) }
        }

        // verversen op de achtergrond (de lijst uit de cache staat er al): klein label "Updating…"
        // build 130: ook bij "alles vernieuwen" (met de server die nu aan de beurt is)
        AnimatedVisibility((Playlists.updating || Playlists.refreshStatus != null) && !Playlists.isLoading, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (tv) 24.dp else 96.dp)) {
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.8f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(color = Theme.red, strokeWidth = 2.dp, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(8.dp))
                Text(Playlists.refreshStatus ?: L("Updating…"), color = Color.White, fontSize = tsp(12), fontWeight = FontWeight.Medium)
            }
        }

        // build 146: de tv-gids is verlopen (geen programma's meer voor nu of de komende uren): kleine melding, blokkeert niets
        if (current == 0 || current == 1) {
            EpgExpiredNotice(Modifier.align(Alignment.TopCenter).padding(top = if (tv) 84.dp else 56.dp, start = 12.dp, end = 12.dp))
        }

        ScreensStackHost()
    }
    SupportDialogHost()
    KidsGateHost()
    // build 145: "Servers bijwerken?" na het kiezen van een profiel (instelling "Servers laden bij openen": Vragen)
    UpdateAskHost()

    val err = Playlists.error
    if (err != null) {
        AlertDialog(
            onDismissRequest = { Playlists.error = null },
            title = { Text(L("Couldn't load playlist")) },
            text = { Text(err) },
            confirmButton = {
                TextButton(onClick = {
                    Playlists.error = null
                    val s = Playlists.source
                    if (s != null) AppScope.launch { Playlists.select(s, forceRefresh = true) }
                }) { Text(L("Try again"), color = Theme.red) }
            },
            dismissButton = { TextButton(onClick = { Playlists.error = null }) { Text("OK", color = Color.White) } },
            containerColor = Theme.elevated,
        )
    }
}

/** Netflix-TV: bovenbalk met logo, tabbladen als "pillen" en het profiel rechts. */
@Composable
private fun TvTabBar(tabs: List<MainTab>, current: Int, focusEnabled: Boolean, onSelect: (Int) -> Unit) {
    val first = rememberInitialFocus(enabled = focusEnabled, key = focusEnabled)
    Row(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black, Color.Black.copy(alpha = 0.85f))))
            .padding(horizontal = Theme.edge, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BingrLogo(tsp(18))
        Spacer(Modifier.width(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEach { t ->
                var focused by remember { mutableStateOf(false) }
                val selected = t.id == current
                Text(
                    t.title,
                    color = when { focused -> Color.Black; selected -> Color.White; else -> Theme.secondary },
                    fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium,
                    fontSize = tsp(13),
                    maxLines = 1,
                    modifier = Modifier
                        .then(if (selected) Modifier.focusRequester(first) else Modifier)
                        .onFocusChanged { focused = it.isFocused }
                        .focusLift(1.06f) { onSelect(t.id) }
                        .clip(RoundedCornerShape(50))
                        .background(
                            when {
                                focused -> Color.White
                                selected -> Color.White.copy(alpha = 0.16f)
                                else -> Color.Transparent
                            }
                        )
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }
        Spacer(Modifier.weight(1f))
        // build 72: avatar van het actieve profiel opent het profielmenu
        ProfileMenuButton(avatarSize = 30.dp)
    }
}

/** Zwart scherm met kloppende avatar terwijl de afspeellijst laadt. */
@Composable
private fun LoadingOverlay(text: String, source: PlaylistSource?) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val s by pulse.animateFloat(0.97f, 1.05f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "s")
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (source != null) {
            Column(
                Modifier.graphicsLayer { scaleX = s; scaleY = s },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val prof = Store.activeProfile
                if (prof != null) ProfileAvatar(prof, tvs(110)) else AvatarCircle(source, tvs(110))
                Spacer(Modifier.height(10.dp))
                Text(prof?.name ?: source.name, color = Theme.secondary, fontSize = tsp(14), maxLines = 1)
            }
            Spacer(Modifier.height(tvs(22)))
        }
        CircularProgressIndicator(color = Theme.red, modifier = Modifier.size(tvs(36)))
        Spacer(Modifier.height(tvs(22)))
        Text(text, color = Theme.secondary, fontSize = tsp(14))
    }
}

/**
 * Build 120/132: titel-link openen: eerst de actieve server, dan de andere servers van het account (cache op dit
 * apparaat, filters van het actieve profiel). Eén goede match → detailpagina (via die server), anders het zoekscherm
 * (dat zoekt sinds build 129 ook op alle servers).
 */
private suspend fun openTitleLink(link: TitleLink, activeID: String) {
    val l = Playlists.library
    val pref = TrackPreferences.preferredAudio
    var r = withContext(Dispatchers.Default) { TitleLink.resolve(link, l.movies, l.series, pref) }
    if (r is TitleLink.Resolution.Search) {
        for (p in Store.playlists) {
            if (p.id.equals(activeID, ignoreCase = true)) continue
            val ctx = Playlists.context(p)
            val ol = screensSafe { AllServers.otherLibrary(p, ctx, allowNetwork = false) } ?: continue
            val o = withContext(Dispatchers.Default) { TitleLink.resolve(link, ol.movies, ol.series, pref) }
            if (o is TitleLink.Resolution.Detail) {
                r = TitleLink.Resolution.Detail(when (val t = o.target) {
                    is DetailTarget.MovieT -> DetailTarget.MovieT(t.movie.copy(sourceID = p.id))
                    is DetailTarget.SeriesT -> DetailTarget.SeriesT(t.series.copy(sourceID = p.id))
                })
                break
            }
        }
    }
    // intussen een nieuwere link: die gaat voor; intussen een andere server geopend (bijv. bij een koude start eerst de
    // laatste, daarna de hoofdserver): opnieuw, zodra die geladen is
    if (PushNav.pendingTitle != null) return
    if (Playlists.source?.id != activeID) { PushNav.pendingTitle = link; return }
    when (r) {
        is TitleLink.Resolution.Detail -> ScreensNav.detail(r.target)
        is TitleLink.Resolution.Search -> ScreensNav.push(ScreenEntry.SearchFor(r.query))
    }
}
