package nl.lrvdlinden.bingr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Build 172: tabblad Sport (telefoon, tablet, Android TV). Wedstrijden uit de tv-gids van de servers van het account
// ([Build173Logic]), een kaart "Nu live" met het live voorbeeld (zelfde route als de tv-gids van build 164: één
// voorbeeld tegelijk, zonder geluid, telt als stream, instelling Live voorbeeld) en per dag de wedstrijden met een
// belletje (herinnering, docs/ACCOUNT.md → "Build 172: Sport" in iptv-ios-appletv).

/** Zoekt de wedstrijden op de achtergrond (met een cache per bibliotheek) en zet de automatische belletjes. */
object SportCenter {
    var groups by mutableStateOf(listOf<Build173Logic.Group>()); private set
    var loading by mutableStateOf(false); private set
    /** Zender per programma ([Build173Logic.Listing.id]), met `sourceID` als hij van een andere server komt. */
    private var channels: Map<String, Channel> = emptyMap()
    private var lastKey: String? = null
    private var lastRun = 0L
    private var running = false
    private var again = false

    /** Instellingen: standaardmoment van de melding (0, 5 of 15 min vóór de start; standaard 5). */
    const val LEAD_KEY = "sport.lead"
    var defaultLead: Int
        get() = Build173Logic.lead(Prefs.int(LEAD_KEY, Build173Logic.DEFAULT_LEAD))
        set(v) { Prefs.put(LEAD_KEY, Build173Logic.lead(v)) }

    fun refresh(force: Boolean = false) {
        val src = Playlists.source ?: return
        val lib = Playlists.library
        val key = listOf(src.id, System.identityHashCode(lib), lib.channels.size, lib.epg.size, Store.playlists.size,
            Store.favorites.size, Store.activeProfileID ?: "-").joinToString("|")
        val now = System.currentTimeMillis()
        if (!force && key == lastKey && now - lastRun < 900_000L) return
        if (running) { again = true; return }
        running = true; lastKey = key; lastRun = now
        if (groups.isEmpty()) loading = true
        val favs = Store.favorites
        val others = Store.playlists.filter { !it.id.equals(src.id, ignoreCase = true) && !it.kind.isMediaServer }
        val contexts = others.associate { it.id to Playlists.context(it) }
        AppScope.launch {
            val r = runCatching {
                val otherLibs = others.mapNotNull { p ->
                    runCatching { AllServers.otherLibrary(p, contexts.getValue(p.id), allowNetwork = false, background = true) }
                        .getOrNull()?.let { p.id to it }
                }
                withContext(Dispatchers.Default) { compute(lib, src.id, otherLibs, favs, System.currentTimeMillis()) }
            }.getOrNull()
            if (r != null) {
                channels = r.second
                if (groups != r.first) groups = r.first
            }
            loading = false
            running = false
            applyAuto()
            if (again) { again = false; refresh(force = true) }
        }
    }

    private fun isFav(favs: Set<String>, pid: String, id: String) =
        "$pid|$id" in favs || "${pid.lowercase()}|$id" in favs || "${pid.uppercase()}|$id" in favs

    /** Sportzenders van alle servers, hun programma's van nu tot 7 dagen vooruit, herkennen en ontdubbelen. */
    private fun compute(active: Library, activeID: String, others: List<Pair<String, Library>>, favs: Set<String>,
                        now: Long): Pair<List<Build173Logic.Group>, Map<String, Channel>> {
        val listings = ArrayList<Build173Logic.Listing>()
        val chans = HashMap<String, Channel>()
        val until = now + Build173Logic.DAYS * 86_400_000L
        fun scan(lib: Library, server: String, mark: Boolean) {
            for (c in lib.channels) {
                if (c.radio) continue
                val pid = c.sourceID ?: server
                val fav = isFav(favs, pid, c.id)
                if (!Build173Logic.isSportChannel(c.name, c.group, fav)) continue
                val list = lib.epg[c.epgID ?: c.id] ?: continue
                for (p in list) {
                    if (p.end <= now || p.start > until) continue
                    val m = Build173Logic.classify(p.title, p.description, c.name, c.group, p.start, p.end) ?: continue
                    val id = "$pid|${c.id}|${p.start / 1000}"
                    if (chans.containsKey(id)) continue
                    chans[id] = if (mark) c.copy(sourceID = server) else c
                    listings += Build173Logic.Listing(id, c.name, fav, p.start, p.end, m)
                }
            }
        }
        scan(active, activeID, false)
        for ((id, lib) in others) scan(lib, id, true)
        return Build173Logic.dedupe(listings) to chans
    }

    /** Wedstrijden van favoriete teams en competities krijgen automatisch een belletje (eén per wedstrijd). */
    private fun applyAuto() {
        val teams = Store.favoriteTeams; val comps = Store.favoriteCompetitions
        if (teams.isEmpty() && comps.isEmpty()) return
        val existing = Store.reminders.filter { Reminders.isMine(it) }.mapNotNull { it.matchKey }.toSet()
        val keys = Build173Logic.autoReminders(groups, System.currentTimeMillis(), teams, comps, Store.sportMuted, existing)
        val active = Playlists.source?.id ?: return
        for (k in keys) {
            val g = groups.firstOrNull { it.key == k } ?: continue
            val c = channel(g) ?: continue
            Reminders.addSport(k, g.match, g.start, c, c.sourceID ?: active, defaultLead, auto = true)
        }
    }

    fun channel(g: Build173Logic.Group): Channel? = g.listings.firstOrNull()?.let { channels[it.id] }
    fun channel(l: Build173Logic.Listing): Channel? = channels[l.id]
    fun reminder(g: Build173Logic.Group): ProgramReminder? = Reminders.sportReminder(g.key)

    /** Belletje aan (met [lead]) of uit; uitzetten dempt de wedstrijd (een automatisch belletje komt niet terug). */
    fun setBell(g: Build173Logic.Group, on: Boolean, lead: Int? = null) {
        reminder(g)?.let { Reminders.remove(it.id) }
        if (on) {
            val c = channel(g) ?: return
            val pid = c.sourceID ?: Playlists.source?.id ?: return
            Store.setSportMuted(g.key, false)
            Reminders.addSport(g.key, g.match, g.start, c, pid, lead ?: defaultLead)
        } else {
            Store.setSportMuted(g.key, true)
        }
    }

    fun setFavoriteTeam(name: String, on: Boolean) { Store.setFavoriteTeam(name, on); if (on) applyAuto() }
    fun setFavoriteCompetition(id: String, on: Boolean) { Store.setFavoriteCompetition(id, on); if (on) applyAuto() }

    fun play(c: Channel) {
        ChannelPreview.stop()
        val lib = Playlists.library
        val local = c.sourceID == null
        AppState.play(Playables.channel(c, if (local) lib.nowPlaying(c) else null),
            if (local) lib.channels.filter { it.group == c.group } else emptyList())
    }

    // MARK: teksten

    fun sportName(id: String): String = when (id) {
        "football" -> L("Football")
        "motorsport" -> L("F1/Motorsport")
        "tennis" -> L("Tennis")
        "darts" -> L("Darts")
        "cycling" -> L("Cycling")
        "basketball" -> L("Basketball")
        "icehockey" -> L("Ice hockey")
        "americanfootball" -> L("American football")
        "combat" -> L("Combat sports")
        "golf" -> L("Golf")
        else -> L("Other")
    }

    fun leadName(m: Int): String = when (m) { 0 -> L("At the start"); 15 -> L("15 min before"); else -> L("5 min before") }
    fun label(m: Build173Logic.Match) = m.competition.ifEmpty { sportName(m.sport) }
    fun time(ms: Long): String = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(ms))

    fun dayTitle(dayStart: Long): String {
        val today = startOfDay(System.currentTimeMillis())
        val tomorrow = startOfDay(today + 36 * 3_600_000L)
        if (dayStart == today) return L("Today")
        if (dayStart == tomorrow) return L("Tomorrow")
        val loc = Locale.forLanguageTag(Localizer.language.ifEmpty { "en" })
        return SimpleDateFormat("EEEE d MMMM", loc).format(Date(dayStart)).replaceFirstChar { it.titlecase(loc) }
    }

    fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

private sealed class SportFilter {
    data object All : SportFilter()
    data object Mine : SportFilter()
    data class Of(val sport: String) : SportFilter()
}

private fun Build173Logic.Group.isLive(now: Long) = start <= now && now < end

// MARK: - Tabblad

@Composable
fun SportScreen() {
    val tv = AppState.isTV
    var filter by remember { mutableStateOf<SportFilter>(SportFilter.All) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var sheet by remember { mutableStateOf<Build173Logic.Group?>(null) }
    val lib = Playlists.library
    LaunchedEffect(lib, Store.favoriteTeams, Store.favoriteCompetitions) { SportCenter.refresh() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
            SportCenter.refresh()
        }
    }
    DisposableEffect(Unit) { onDispose { ChannelPreview.stop() } }

    val groups = SportCenter.groups
    val present = groups.map { it.match.sport }.toSet()
    val filters = listOf(SportFilter.All, SportFilter.Mine) + Build173Logic.SPORTS.filter { it in present }.map { SportFilter.Of(it) }
    val teams = Store.favoriteTeams; val comps = Store.favoriteCompetitions
    val visible = groups.filter { g ->
        g.end > now && when (val f = filter) {
            SportFilter.All -> true
            SportFilter.Mine -> Build173Logic.isFavorite(g.match, teams, comps) || SportCenter.reminder(g) != null
            is SportFilter.Of -> g.match.sport == f.sport
        }
    }
    val live = visible.filter { it.isLive(now) }
    val days = visible.filter { !it.isLive(now) }.groupBy { SportCenter.startOfDay(it.start) }

    LazyColumn(
        Modifier.fillMaxSize().background(Theme.bg).then(if (tv) Modifier else Modifier.statusBarsPadding()),
        contentPadding = PaddingValues(bottom = tvs(90)),
        verticalArrangement = Arrangement.spacedBy(tvs(12)),
    ) {
        item {
            Column(Modifier.padding(top = tvs(8))) {
                // build 173: rechtsboven het vergrootglas en het profielmenu, zoals op Home, Films en Series
                // (tv: Zoeken en het profiel staan al in de bovenbalk)
                if (!tv) Row(Modifier.fillMaxWidth().padding(horizontal = Theme.edge), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(L("Sport"), color = Color.White, fontSize = Theme.titleSize, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    Box(Modifier.size(34.dp).focusLift { ScreensNav.search() }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Search, L("Search"), tint = Color.White, modifier = Modifier.size(25.dp))
                    }
                    ProfileMenuButton(avatarSize = 30.dp)
                }
                LazyRow(contentPadding = PaddingValues(horizontal = Theme.edge, vertical = tvs(6)),
                    horizontalArrangement = Arrangement.spacedBy(tvs(8))) {
                    items(filters) { f ->
                        val title = when (f) { SportFilter.All -> L("All"); SportFilter.Mine -> L("My teams"); is SportFilter.Of -> SportCenter.sportName(f.sport) }
                        SportChip(title, f == filter) { filter = f }
                    }
                }
            }
        }
        when {
            SportCenter.loading && groups.isEmpty() -> item {
                Row(Modifier.padding(horizontal = Theme.edge, vertical = tvs(30)), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(tvs(20)), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(L("Looking for matches…"), color = Theme.secondary, fontSize = tsp(14))
                }
            }
            groups.isEmpty() -> item { SportEmpty(L("No matches found"), L("Matches come from the TV guide of your servers: sports channels and event categories.")) }
            visible.isEmpty() -> item { SportEmpty(L("No matches for this filter."), null) }
            else -> {
                if (live.isNotEmpty()) {
                    item { SportLiveHeader() }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = Theme.edge, vertical = tvs(8)),
                            horizontalArrangement = Arrangement.spacedBy(tvs(14))) {
                            items(live, key = { it.key }) { g ->
                                SportLiveCard(g, now, autoPreview = !tv && g.key == live.first().key) {
                                    SportCenter.channel(g)?.let { SportCenter.play(it) }
                                }
                            }
                        }
                    }
                }
                days.forEach { (day, list) ->
                    item(key = "day-$day") {
                        Text(SportCenter.dayTitle(day), color = Color.White, fontSize = tsp(18), fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = Theme.edge).padding(top = tvs(8)))
                    }
                    if (tv) {
                        item(key = "row-$day") {
                            LazyRow(contentPadding = PaddingValues(horizontal = Theme.edge, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                items(list, key = { it.key }) { g -> SportTvCard(g, now) { sheet = g } }
                            }
                        }
                    } else {
                        items(list, key = { it.key }) { g -> SportRow(g) { sheet = g } }
                    }
                }
            }
        }
    }
    sheet?.let { g -> SportMatchDialog(g) { sheet = null } }
}

@Composable
private fun SportEmpty(title: String, text: String?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Theme.edge, vertical = tvs(60)), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Default.SportsSoccer, null, tint = Theme.secondary, modifier = Modifier.size(tvs(40)))
        Text(title, color = Color.White, fontSize = tsp(18), fontWeight = FontWeight.Bold)
        if (text != null) Text(text, color = Theme.secondary, fontSize = tsp(14))
    }
}

@Composable
private fun SportChip(title: String, on: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier.heightIn(min = tvs(36)).clip(RoundedCornerShape(50))
            .background(if (focused) Color.White else if (on) Theme.red.copy(alpha = 0.18f) else Color(0xFF141414))
            .border(1.dp, if (on) Theme.red else Color(0xFF2C2C2C), RoundedCornerShape(50))
            .onFocusChanged { focused = it.isFocused }
            .focusLift(scale = 1.05f, onClick = onClick)
            .padding(horizontal = tvs(14), vertical = tvs(8)),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, color = if (focused) Color.Black else Color.White, fontSize = tsp(13), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SportLiveHeader() {
    Row(Modifier.padding(horizontal = Theme.edge), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(tvs(8)).clip(CircleShape).background(Theme.brandRed))
        Spacer(Modifier.width(8.dp))
        Text(L("LIVE NOW"), color = Color(0xFFFF5A62), fontSize = tsp(13), fontWeight = FontWeight.ExtraBold)
    }
}

/** Cirkel met initialen en een vaste kleur per team (geen echte clublogo's). */
@Composable
fun SportTeamBadge(name: String, size: Int = 30) {
    Box(Modifier.size(tvs(size)).clip(CircleShape).background(Color(0xFF000000 or Build173Logic.color(name))),
        contentAlignment = Alignment.Center) {
        Text(Build173Logic.short(name), color = Color.White, fontSize = tsp((size * 0.36f).toInt()), fontWeight = FontWeight.ExtraBold,
            maxLines = 1)
    }
}

@Composable
private fun SportLiveCard(g: Build173Logic.Group, now: Long, autoPreview: Boolean, onWatch: () -> Unit) {
    val c = SportCenter.channel(g)
    val tv = AppState.isTV
    LaunchedEffect(c?.id, autoPreview) { if (autoPreview && c != null) ChannelPreview.focus(c) }
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier.width(if (tv) 430.dp else 330.dp).clip(RoundedCornerShape(14.dp)).background(Theme.card)
            .border(if (focused) 3.dp else 1.dp, if (focused) Color.White else Color(0xFF262626), RoundedCornerShape(14.dp))
            .onFocusChanged {
                val was = focused
                focused = it.isFocused
                // TV: ±1 s na de focus speelt de zender live (zoals de gids); weg = stoppen
                if (tv && c != null) { if (it.isFocused) ChannelPreview.focus(c) else if (was) ChannelPreview.blur(c) }
            }
            .focusLift(scale = 1.04f, onClick = onWatch)
            .semantics { contentDescription = L("LIVE") + ", " + SportCenter.label(g.match) + ", " + g.match.title }
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFF134A2A))) {
            if (c != null && ChannelPreview.channel?.id == c.id && ChannelPreview.player != null) {
                ChannelPreviewView(Modifier.fillMaxSize())
            } else if (c != null) {
                RemoteImage(c.logo, Modifier.fillMaxSize().padding(tvs(28)), placeholder = c.displayName, fit = true)
            }
            Text(L("LIVE"), color = Color.White, fontSize = tsp(11), fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(10.dp).clip(RoundedCornerShape(4.dp)).background(Theme.brandRed).padding(horizontal = 7.dp, vertical = 3.dp))
            if (c != null) Text(c.displayName, color = Color.White, fontSize = tsp(11), fontWeight = FontWeight.Bold, maxLines = 1,
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 7.dp, vertical = 3.dp))
        }
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(SportCenter.label(g.match), color = Theme.secondary, fontSize = tsp(12))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (g.match.isDuel) SportTeamBadge(g.match.home)
                Text(g.match.title, color = Color.White, fontSize = tsp(17), fontWeight = FontWeight.Bold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (g.match.isDuel) SportTeamBadge(g.match.away)
            }
            LinearProgressIndicator(progress = { (((now - g.start).toFloat()) / (g.end - g.start).coerceAtLeast(1)).coerceIn(0f, 1f) },
                color = Theme.brandRed, trackColor = Color(0xFF333333), modifier = Modifier.fillMaxWidth().height(3.dp))
        }
    }
}

@Composable
private fun SportRow(g: Build173Logic.Group, onOpen: () -> Unit) {
    val on = SportCenter.reminder(g) != null
    Row(
        Modifier.padding(horizontal = Theme.edge).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF121212))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.weight(1f).clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(SportCenter.time(g.start), color = Color.White, fontSize = tsp(15), fontWeight = FontWeight.ExtraBold)
                Text(SportCenter.channel(g)?.displayName ?: "", color = Color(0xFF8A8A8A), fontSize = tsp(11), maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.weight(1f)) {
                Text(SportCenter.label(g.match), color = Theme.secondary, fontSize = tsp(12), maxLines = 1)
                Text(g.match.title, color = Color.White, fontSize = tsp(15), fontWeight = FontWeight.Bold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(if (on) Theme.brandRed.copy(alpha = 0.18f) else Color.Transparent)
                .clickable { SportCenter.setBell(g, !on) }
                .semantics { contentDescription = if (on) L("Turn off reminder") else L("Turn on reminder") },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (on) Icons.Default.Notifications else Icons.Default.NotificationsNone, null,
                tint = if (on) Theme.brandRed else Theme.secondary, modifier = Modifier.size(22.dp).bounceOnChange(on))
        }
    }
}

/** Android TV: OK = kijken (live) of het belletje aan/uit (nog niet begonnen); lang indrukken = meer. */
@Composable
private fun SportTvCard(g: Build173Logic.Group, now: Long, onMore: () -> Unit) {
    val on = SportCenter.reminder(g) != null
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier.width(300.dp).clip(RoundedCornerShape(12.dp))
            .background(if (focused) Color(0xFF2A2A2A) else Color(0xFF121212))
            .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusLift(scale = 1.05f, onLongClick = onMore) {
                if (g.isLive(now)) SportCenter.channel(g)?.let { SportCenter.play(it) } else SportCenter.setBell(g, !on)
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(SportCenter.time(g.start), color = Color.White, fontSize = tsp(20), fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            Icon(if (on) Icons.Default.Notifications else Icons.Default.NotificationsNone, null,
                tint = if (on) Theme.brandRed else Theme.secondary, modifier = Modifier.size(24.dp).bounceOnChange(on))
        }
        Text(listOfNotNull(SportCenter.label(g.match), SportCenter.channel(g)?.displayName).filter { it.isNotEmpty() }.joinToString(" · "),
            color = Theme.secondary, fontSize = tsp(13), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(g.match.title, color = Color.White, fontSize = tsp(17), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// MARK: - Blad van een wedstrijd

@Composable
private fun SportMatchDialog(g: Build173Logic.Group, onDismiss: () -> Unit) {
    val reminder = SportCenter.reminder(g)
    var lead by remember { mutableStateOf(reminder?.leadMin?.let { Build173Logic.lead(it) } ?: SportCenter.defaultLead) }
    val now = System.currentTimeMillis()
    val live = g.isLive(now)
    val loc = Locale.forLanguageTag(Localizer.language.ifEmpty { "en" })
    val subtitle = listOfNotNull(SportCenter.label(g.match), SimpleDateFormat("EEE d MMM", loc).format(Date(g.start)),
        SportCenter.time(g.start), SportCenter.channel(g)?.displayName).filter { it.isNotEmpty() }.joinToString(" · ")
    S2FullDialog(onDismiss) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(tvs(20)), verticalArrangement = Arrangement.spacedBy(tvs(14))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(subtitle, color = Theme.secondary, fontSize = tsp(12), modifier = Modifier.weight(1f))
                Box(Modifier.size(44.dp).clip(CircleShape).focusLift(onClick = onDismiss), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Close, L("Close"), tint = Color.White)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (g.match.isDuel) SportTeamBadge(g.match.home, 36)
                Text(g.match.title, color = Color.White, fontSize = tsp(20), fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f, fill = false))
                if (g.match.isDuel) SportTeamBadge(g.match.away, 36)
            }
            if (live) {
                NetflixButton(L("Watch live"), Icons.Default.PlayArrow, kind = NetflixKind.PLAY, modifier = Modifier.fillMaxWidth()) {
                    onDismiss(); SportCenter.channel(g)?.let { SportCenter.play(it) }
                }
            } else {
                Text(L("Notification"), color = Color.White, fontSize = tsp(14), fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Build173Logic.LEADS.forEach { m ->
                        Box(Modifier.weight(1f)) {
                            SportChip(SportCenter.leadName(m), lead == m) {
                                lead = m
                                if (SportCenter.reminder(g) != null) SportCenter.setBell(g, true, m)
                            }
                        }
                    }
                }
            }
            if (g.match.isDuel) {
                SportSwitchRow(L("All matches of %@", g.match.home), Store.isFavoriteTeam(g.match.home)) { SportCenter.setFavoriteTeam(g.match.home, it) }
                SportSwitchRow(L("All matches of %@", g.match.away), Store.isFavoriteTeam(g.match.away)) { SportCenter.setFavoriteTeam(g.match.away, it) }
            }
            if (g.match.competitionId.isNotEmpty()) {
                SportSwitchRow(L("All matches of %@", g.match.competition), g.match.competitionId in Store.favoriteCompetitions) {
                    SportCenter.setFavoriteCompetition(g.match.competitionId, it)
                }
            }
            if (!live) {
                val has = SportCenter.reminder(g) != null
                NetflixButton(if (has) L("Turn off reminder") else L("Turn on reminder"),
                    if (has) Icons.Default.NotificationsNone else Icons.Default.Notifications,
                    kind = if (has) NetflixKind.SECONDARY else NetflixKind.PLAY, modifier = Modifier.fillMaxWidth()) {
                    SportCenter.setBell(g, !has, lead)
                }
                if (has) Text(L("Reminder on"), color = Theme.secondary, fontSize = tsp(13))
            }
            if (g.listings.size > 1) {
                Text(L("Also on"), color = Color.White, fontSize = tsp(14), fontWeight = FontWeight.Bold)
                g.listings.drop(1).take(6).forEach { l ->
                    val canPlay = l.start <= now && now < l.end
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .then(if (canPlay) Modifier.focusLift(scale = 1.02f) { onDismiss(); SportCenter.channel(l)?.let { SportCenter.play(it) } } else Modifier)
                        .padding(vertical = 8.dp)) {
                        Text(ContentLanguage.channelTitle(l.channel), color = Color.White, fontSize = tsp(14), modifier = Modifier.weight(1f), maxLines = 1)
                        Text(SportCenter.time(l.start), color = Theme.secondary, fontSize = tsp(14))
                    }
                }
            }
        }
    }
}

@Composable
private fun SportSwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF1D1D1D))
            .focusLift(scale = 1.02f) { onChange(!checked) }
            .heightIn(min = 44.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Color.White, fontSize = tsp(14), modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = Theme.red, checkedThumbColor = Color.White))
    }
}

// MARK: - Instellingen > Sport

/** Mijn teams en competities (toevoegen, verwijderen). */
@Composable
fun SportFavoritesDialog(onDismiss: () -> Unit) {
    var newTeam by remember { mutableStateOf("") }
    S2FullDialog(onDismiss) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = tvs(16)),
            verticalArrangement = Arrangement.spacedBy(tvs(12))) {
            Row(Modifier.padding(horizontal = Theme.edge), verticalAlignment = Alignment.CenterVertically) {
                Text(L("My teams and competitions"), color = Color.White, fontSize = tsp(20), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Box(Modifier.size(44.dp).clip(CircleShape).focusLift(onClick = onDismiss), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Close, L("Close"), tint = Color.White)
                }
            }
            S2Section(L("Teams")) {
                if (Store.favoriteTeams.isEmpty()) S2Row(L("No favorite teams yet. Turn on “All matches of …” at a match."), tint = Theme.secondary)
                Store.favoriteTeams.sorted().forEach { t ->
                    S2Row(t, leading = { SportTeamBadge(t) }, trailing = {
                        Icon(Icons.Default.Close, L("Remove"), tint = Theme.brandRed)
                    }, onClick = { SportCenter.setFavoriteTeam(t, false) })
                }
                if (!AppState.isTV) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            if (newTeam.isEmpty()) Text(L("Team name"), color = Theme.secondary, fontSize = tsp(15))
                            BasicTextField(newTeam, { newTeam = it }, singleLine = true,
                                textStyle = TextStyle(color = Color.White, fontSize = tsp(15)), cursorBrush = SolidColor(Color.White),
                                modifier = Modifier.fillMaxWidth())
                        }
                        val ok = Build173Logic.cleanTeam(newTeam).length >= 2
                        Text(L("Add"), color = if (ok) Theme.red else Theme.secondary, fontSize = tsp(15), fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable(enabled = ok) { SportCenter.setFavoriteTeam(newTeam, true); newTeam = "" }.padding(8.dp))
                    }
                }
            }
            S2Section(L("Competitions"), L("Matches of your teams and competitions get a reminder automatically.")) {
                Build173Logic.COMPETITIONS.forEach { c ->
                    S2Toggle(c.name, c.id in Store.favoriteCompetitions) { SportCenter.setFavoriteCompetition(c.id, it) }
                }
            }
        }
    }
}
