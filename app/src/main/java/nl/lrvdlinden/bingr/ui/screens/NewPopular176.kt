package nl.lrvdlinden.bingr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * Build 176 (punt 4, docs/ACCOUNT.md "Build 176", 4): "Nieuw en populair": Binnenkort (TMDB upcoming, zoals de rij
 * Binnenkort op Bingr), Nieuwe seizoenen (series uit Mijn lijst en Verder kijken met een aflevering of seizoen binnen
 * 60 dagen) en Populair (`/trending/all/week`), via de bestaande TMDB-route. Per titel een belletje (herinnering op de
 * releasedatum om 09:00); staat de titel al op een server van het account, dan "Nu beschikbaar" en afspelen/openen.
 */
object NewPopular {
    /** Eén kaart: de titel uit TMDB, de releasedatum ("yyyy-MM-dd"), een nieuw seizoen en (als het er al is) de eigen titel. */
    data class Entry(val item: DiscoverItem, val date: String?, val season: Int?, val match: DetailTarget?) {
        val mediaType: String get() = if (item.isShow) "tv" else "movie"
        val key: String get() = Build176Logic.Release.key(mediaType, item.tmdbID, season)
        val target: DetailTarget get() = match ?: item.target
    }
    data class Sections(val upcoming: List<Entry> = emptyList(), val seasons: List<Entry> = emptyList(), val popular: List<Entry> = emptyList()) {
        val isEmpty: Boolean get() = upcoming.isEmpty() && seasons.isEmpty() && popular.isEmpty()
    }

    var sections by mutableStateOf(Sections()); private set
    var loading by mutableStateOf(false); private set
    private var loadedKey = ""
    private var loadedAt = 0L

    private fun today() = DiscoverItem.day(Date())

    /** Laden (12 uur bewaard per profiel/taal/server). */
    suspend fun load(force: Boolean = false) {
        val lib = Playlists.library
        val pid = Playlists.source?.id ?: ""
        val profile = Store.activeProfile
        val kids = profile?.kids == true
        val key = "${Store.activeProfileID}|${Localizer.language}|$pid|${lib.movies.size}|${lib.series.size}"
        if (!force && key == loadedKey && System.currentTimeMillis() - loadedAt < 12 * 3_600_000L && !sections.isEmpty) return
        if (!Secrets.isSet(Secrets.tmdbAPIKey)) return
        loading = true
        try {
            val matcher = screensSafe { MatcherCache.matcher(lib, "${pid}-${lib.movies.size}-${lib.series.size}") }
            val (up, pop, seasons) = coroutineScope {
                val a = async { screensSafe { DiscoverStore.get(DiscoverKind.UPCOMING, kids) } ?: emptyList() }
                // kinderprofiel: geen Populair (geen zekere leeftijd)
                val b = async { if (kids) emptyList() else screensSafe { trending() } ?: emptyList() }
                val c = async { screensSafe { newSeasons(lib, pid) } ?: emptyList() }
                Triple(a.await(), b.await(), c.await())
            }
            // profiel met een leeftijdsgrens: alleen wat in de (gefilterde) bibliotheek staat
            val onlyLibrary = profile?.effectiveAgeLimit != null
            suspend fun entries(items: List<DiscoverItem>): List<Entry> =
                (screensSafe { DiscoverStore.match(items, matcher, onlyLibrary, limit = 40) } ?: emptyList())
                    .map { Entry(it.item, it.item.date, null, it.match) }
            sections = Sections(entries(up), seasons, entries(pop))
            loadedKey = key
            loadedAt = System.currentTimeMillis()
            DiscoverKnown.remember(up + pop + seasons.map { it.item })
        } finally { loading = false }
    }

    private suspend fun trending(): List<DiscoverItem> {
        val r = TMDB.request("/trending/all/week", listOf("language" to TMDB.appLanguage)) ?: return emptyList()
        return r.jList("results").mapNotNull { i ->
            if (i.jB("adult") == true) return@mapNotNull null
            val show = when (i.jS("media_type")) { "tv" -> true; "movie" -> false; else -> return@mapNotNull null }
            val id = i.jI("id") ?: return@mapNotNull null
            val t = (if (show) i.jS("name") else i.jS("title"))?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val poster = i.jS("poster_path")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val o = if (show) i.jS("original_name") else i.jS("original_title")
            DiscoverItem(tmdbID = id, isShow = show, title = t, originalTitle = o?.takeIf { it != t },
                date = (if (show) i.jS("first_air_date") else i.jS("release_date"))?.takeIf { it.isNotEmpty() },
                posterPath = poster, overview = i.jS("overview")?.takeIf { it.isNotEmpty() }, kind = "popular")
        }.take(30)
    }

    /** Series uit Mijn lijst en Verder kijken (actieve server) met een aflevering of seizoen in de komende 60 dagen. */
    private suspend fun newSeasons(lib: Library, pid: String): List<Entry> {
        val today = today()
        val until = DiscoverItem.day(Date(System.currentTimeMillis() + 60L * 86_400_000L))
        val wanted = LinkedHashMap<String, Series>()
        withContext(Dispatchers.Default) {
            val byID = lib.series.associateBy { it.id }
            val movieIDs = lib.movies.mapTo(HashSet()) { it.id }
            for (k in Store.myList) {
                val parts = k.split('|', limit = 2)
                if (parts.size == 2 && parts[0].equals(pid, true)) byID[parts[1]]?.let { wanted[it.id] = it }
            }
            // Verder kijken: de serie van een aflevering (titel in de voortgang = serietitel)
            val byTitle = lib.series.associateBy { it.title }
            for ((k, e) in Store.progress) {
                if (k in movieIDs) continue
                byTitle[e.title]?.let { wanted[it.id] = it }
            }
        }
        val out = ArrayList<Entry>()
        for (s in wanted.values.take(25)) {
            val tmdb = Watched.tmdb(s)?.toIntOrNull() ?: continue
            val d = TMDB.request("/tv/$tmdb", listOf("language" to TMDB.appLanguage)) ?: continue
            val next = d.jO("next_episode_to_air")
            var date = next.jS("air_date")?.takeIf { it.isNotEmpty() }
            var season = next.jI("season_number")
            if (date == null || date < today || date > until) {
                val sea = d.jList("seasons").firstOrNull { x -> val a = x.jS("air_date") ?: ""; a in today..until }
                date = sea?.jS("air_date"); season = sea?.jI("season_number")
            }
            if (date == null || date < today || date > until || season == null) continue
            val item = DiscoverItem(tmdbID = tmdb, isShow = true, title = d.jS("name") ?: s.title, date = date,
                posterPath = d.jS("poster_path"), overview = d.jS("overview"), kind = "season")
            out += Entry(item, date, season, DetailTarget.SeriesT(s))
        }
        return out.sortedBy { it.date ?: "" }
    }

    fun reminder(e: Entry): ProgramReminder? = Reminders.releaseReminder(e.mediaType, e.item.tmdbID, e.season)

    /** Belletje aan/uit; false = geen (toekomstige) datum. */
    fun toggleBell(e: Entry): Boolean {
        reminder(e)?.let { Reminders.remove(it.id); return true }
        val date = e.date ?: return false
        if (date <= today()) return false
        val title = if (e.season != null) "${e.item.title} · " + L("Season %d", e.season) else e.item.title
        return Reminders.addRelease(e.mediaType, e.item.tmdbID, e.season, title, date)
    }

    /** "Nu beschikbaar": de titel staat op een server (en is geen nieuw seizoen dat nog moet komen). */
    fun available(e: Entry): Boolean = e.match != null && e.season == null

    fun open(e: Entry) {
        val m = e.match
        if (m is DetailTarget.MovieT && available(e) && Store.activeProfile?.effectiveAgeLimit == null) {
            AppState.play(Playables.movie(m.movie, Trakt.imdb(m.movie)))
        } else ScreensNav.detail(e.target)
    }

    fun dateLabel(date: String?): String? {
        val d = date?.let { runCatching { DiscoverItem.dayFormat().parse(it.take(10)) }.getOrNull() } ?: return null
        val loc = Localizer.locale
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(loc, "dMMM")
        return Build149Logic.Dates.fmt(pattern, loc).format(d).trim().removeSuffix(".")
    }
}

/** Kaart: poster met datum of "Nu beschikbaar", daaronder het belletje (tv: apart te kiezen). */
@Composable
fun NewPopularCard(e: NewPopular.Entry, showBell: Boolean = true) {
    val available = NewPopular.available(e)
    val upcoming = (e.date ?: "") > DiscoverItem.day(Date())
    Column(Modifier.width(Theme.posterWidth), verticalArrangement = Arrangement.spacedBy(tvs(6))) {
        PosterCard(
            e.item.title, e.item.poster ?: e.match?.poster,
            modifier = Modifier.cardPreviewFocus(e.target).focusLift { NewPopular.open(e) },
            badge = when {
                available -> L("Available now")
                e.season != null -> L("Season %d", e.season) + (NewPopular.dateLabel(e.date)?.let { " · $it" } ?: "")
                upcoming -> NewPopular.dateLabel(e.date)
                else -> null
            },
            badgeTopStart = true,
            preview = { CardPreviewSlot(e.target) },
        )
        if (showBell && !available && upcoming) {
            val on = NewPopular.reminder(e) != null
            Row(
                Modifier.focusLift(1.06f) { NewPopular.toggleBell(e) }.clip(RoundedCornerShape(50))
                    .background(if (on) Theme.red.copy(alpha = 0.18f) else Theme.card).padding(horizontal = tvs(10), vertical = tvs(6)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(tvs(6)),
            ) {
                Icon(if (on) Icons.Default.Notifications else Icons.Default.NotificationsNone, null,
                    tint = if (on) Theme.red else Color.White, modifier = Modifier.size(tvs(16)).bounceOnChange(on))
                Text(if (on) L("Reminder set") else L("Remind me"), color = Color.White, fontSize = tsp(11), maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        } else if (available) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(tvs(4))) {
                Icon(Icons.Default.PlayArrow, null, tint = Theme.red, modifier = Modifier.size(tvs(14)))
                Text(L("Available now"), color = Theme.secondary, fontSize = tsp(11), maxLines = 1)
            }
        }
    }
}

/** Rij "Nieuw en populair" op Home (Binnenkort en nieuwe seizoenen); Alles bekijken = de pagina. */
@Composable
fun NewPopularHomeRow() {
    LaunchedEffect(Store.activeProfileID, Playlists.library, Localizer.language) { screensSafe { NewPopular.load() } }
    val s = NewPopular.sections
    val list = remember(s) { (s.seasons + s.upcoming).distinctBy { it.key }.take(20) }
    if (list.isEmpty()) return
    MediaRow(L("New & Popular"), onSeeAll = { ScreensNav.push(ScreenEntry.NewPopular) }) {
        items(list, key = { it.key }) { e -> NewPopularCard(e) }
    }
}

/** De pagina met de drie secties. */
@Composable
fun NewPopularScreen(onClose: () -> Unit) {
    LaunchedEffect(Unit) { screensSafe { NewPopular.load() } }
    val s = NewPopular.sections
    S2Page(L("New & Popular"), onClose = onClose) {
        if (s.isEmpty) item {
            Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
                if (NewPopular.loading) CircularProgressIndicator(color = Theme.red)
                else Text(L("Nothing to show yet."), color = Theme.secondary, fontSize = tsp(14))
            }
        }
        fun section(title: String, list: List<NewPopular.Entry>) {
            if (list.isEmpty()) return
            item(key = "np-$title") {
                Column(Modifier.padding(top = tvs(14)), verticalArrangement = Arrangement.spacedBy(tvs(8))) {
                    Text(title, color = Color.White, fontSize = Theme.titleSize, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = Theme.edge))
                    LazyRow(contentPadding = PaddingValues(horizontal = Theme.edge, vertical = tvs(6)),
                        horizontalArrangement = Arrangement.spacedBy(Theme.rowSpacing)) {
                        items(list, key = { it.key }) { e -> NewPopularCard(e) }
                    }
                }
            }
        }
        section(L("Coming soon"), s.upcoming)
        section(L("New seasons"), s.seasons)
        section(L("Popular"), s.popular)
        item { Box(Modifier.padding(tvs(20))) {} }
    }
}
