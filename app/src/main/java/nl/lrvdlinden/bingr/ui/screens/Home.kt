package nl.lrvdlinden.bingr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

enum class Filter { ALL, MOVIES, SERIES }

// MARK: vooraf berekende rijen (= HomeIndex in HomeView.swift)

/** Vooraf berekende rijen voor Home/Films/Series: één keer per lijst, op de achtergrond (Dispatchers.Default). */
class HomeIndex(
    val movieGroups: List<Pair<String, List<Movie>>> = emptyList(),
    val seriesGroups: List<Pair<String, List<Series>>> = emptyList(),
    val recent: List<Movie> = emptyList(),
    val movieByID: Map<String, Movie> = emptyMap(),
    val seriesByID: Map<String, Series> = emptyMap(),
    val channelByID: Map<String, Channel> = emptyMap(),
    val movieByIMDb: Map<String, Movie> = emptyMap(),
    val seriesByTitle: Map<String, Series> = emptyMap(),
    /**
     * Build 111: rij "Nieuw toegevoegd" op Home: films én series van de laatste 30 dagen (Xtream `added` /
     * `last_modified`), nieuwste eerst, max. 30; zonder datum (M3U) blijft hij leeg.
     */
    val recentTitles: List<DetailTarget> = emptyList(),
) {
    val seriesTitles: Set<String> get() = seriesByTitle.keys

    private fun itemID(key: String, playlist: String): String? =
        // server-id hoofdletterongevoelig: iOS schrijft hem met hoofdletters, web/Android/Samsung TV soms met kleine
        if (key.length > playlist.length + 1 && key.startsWith("$playlist|", ignoreCase = true)) key.substring(playlist.length + 1) else null

    fun movie(forKey: String, playlist: String): Movie? = itemID(forKey, playlist)?.let { movieByID[it] }
    fun series(forKey: String, playlist: String): Series? = itemID(forKey, playlist)?.let { seriesByID[it] }
    fun channel(forKey: String, playlist: String): Channel? = itemID(forKey, playlist)?.let { channelByID[it] }

    /** Staat deze titel (nog) in de gefilterde lijst? */
    fun contains(t: DetailTarget): Boolean = if (t.isSeries) t.id in seriesByID else t.id in movieByID

    companion object {
        fun build(lib: Library): HomeIndex {
            val mGroups = LinkedHashMap<String, MutableList<Movie>>()
            val movieByID = HashMap<String, Movie>(lib.movies.size * 2)
            val movieByIMDb = HashMap<String, Movie>()
            for (m in lib.movies) {
                mGroups.getOrPut(m.group) { ArrayList() }.add(m)
                movieByID.putIfAbsent(m.id, m)
                val i = m.imdbID
                if (!i.isNullOrEmpty()) movieByIMDb.putIfAbsent(i, m)
            }
            val sGroups = LinkedHashMap<String, MutableList<Series>>()
            val seriesByID = HashMap<String, Series>(lib.series.size * 2)
            val seriesByTitle = HashMap<String, Series>()
            for (s in lib.series) {
                sGroups.getOrPut(s.group) { ArrayList() }.add(s)
                seriesByID.putIfAbsent(s.id, s)
                seriesByTitle.putIfAbsent(s.title, s)
            }
            val channelByID = HashMap<String, Channel>(lib.channels.size * 2)
            for (c in lib.channels) channelByID.putIfAbsent(c.id, c)
            val recent = lib.movies.filter { it.addedAt != null }.sortedByDescending { it.addedAt ?: 0L }
            // build 111: films én series van de laatste 30 dagen (één keer per id)
            val cutoff = System.currentTimeMillis() - 30L * 86_400_000L
            val fresh = ArrayList<Pair<DetailTarget, Long>>()
            for (m in movieByID.values) { val a = m.addedAt; if (a != null && a >= cutoff) fresh.add(DetailTarget.MovieT(m) to a) }
            for (x in seriesByID.values) { val a = x.addedAt; if (a != null && a >= cutoff) fresh.add(DetailTarget.SeriesT(x) to a) }
            val recentTitles = fresh.sortedByDescending { it.second }.take(30).map { it.first }
            // build 150: rijen per categorie op de opgeschoonde naam samengevoegd ("| NL | NETFLIX" + "MULTI | Netflix" =
            // één rij "Netflix"), de inhoud ontdubbeld, de bron in de voorkeurstaal eerst
            val pref = ContentLanguage.preferredSourceLanguages
            val mMerged = Build150Logic.Merge.merge(mGroups.map { it.key to it.value }, pref, { it.imdbID }, { it.title }, { it.year })
            val sMerged = Build150Logic.Merge.merge(sGroups.map { it.key to it.value }, pref, { it.imdbID }, { it.title }, { it.year })
            return HomeIndex(
                movieGroups = mMerged.map { e -> Pair<String, List<Movie>>(e.name, e.items) },
                seriesGroups = sMerged.map { e -> Pair<String, List<Series>>(e.name, e.items) },
                recent = recent, movieByID = movieByID, seriesByID = seriesByID, channelByID = channelByID,
                movieByIMDb = movieByIMDb, seriesByTitle = seriesByTitle, recentTitles = recentTitles,
            )
        }
    }
}

/** Eén [HomeIndex] per geladen lijst, gedeeld door Home/Films/Series en Mijn Bingr (niet opnieuw bij wisselen van tab). */
object HomeIndexCache {
    private var lib: Library? = null
    private var index: HomeIndex? = null

    /** Al klaar voor deze lijst? (alleen op de main thread) */
    fun peek(l: Library): HomeIndex? = if (lib === l) index else null

    /** Bouwt (op Dispatchers.Default) of geeft de bestaande index voor deze lijst. Aanroepen vanaf de main thread. */
    suspend fun get(l: Library): HomeIndex {
        peek(l)?.let { return it }
        val built = withContext(Dispatchers.Default) { HomeIndex.build(l) }
        lib = l; index = built
        return built
    }
}

/** De index van de huidige lijst als Compose-state: eerst wat er al is, dan na het (achtergrond)bouwen. */
@Composable
fun rememberHomeIndex(lib: Library): HomeIndex {
    val libKey = System.identityHashCode(lib)
    var index by remember(libKey) { mutableStateOf(HomeIndexCache.peek(lib) ?: HomeIndex()) }
    LaunchedEffect(libKey) { index = HomeIndexCache.get(lib) }
    return index
}

// MARK: Billboard, Top 10, Voor jou

/** Gegevens van één Home-tab (billboard, Top 10, Voor jou) — eerst uit de cache, daarna het netwerk. */
private class HomeModel(val filter: Filter) {
    var hero by mutableStateOf<DetailTarget?>(null)
    var trendingMovies by mutableStateOf(emptyList<Movie>())
    var trendingSeries by mutableStateOf(emptyList<Series>())
    var forYou by mutableStateOf(emptyList<DetailTarget>())
    /** Build 140: geen kijkgeschiedenis in dit profiel → de rij heet "Populair" (populaire titels uit je bibliotheek). */
    var forYouPopular by mutableStateOf(false)
    /** Build 140: Nieuw op Bingr: laatst aan de bibliotheek (alle servers) toegevoegd, nieuwste eerst. */
    var newOnBingr by mutableStateOf(emptyList<DetailTarget>())
    /** Build 140: vaste selectie voor uitgelicht (om en om Voor jou en Nieuw op Bingr), wisselt om de 8 s. */
    var heroPool by mutableStateOf(emptyList<DetailTarget>())

    /**
     * Wat niet (meer) in de gefilterde lijst staat weghalen (ander profiel, leeftijdsgrens, verborgen groep).
     * Build 141: de opzoektabellen (tienduizenden id's) op de achtergrond, niet op de main thread.
     */
    suspend fun prune(lib: Library) {
        val (movieIDs, seriesIDs) = withContext(Dispatchers.Default) { lib.movies.mapTo(HashSet()) { it.id } to lib.series.mapTo(HashSet()) { it.id } }
        // build 140: titels van een andere server staan niet in deze lijst (die zijn al met de filters van hun server gekozen)
        fun ok(t: DetailTarget) = t.sourceID != null || (if (t.isSeries) t.id in seriesIDs else t.id in movieIDs)
        hero?.let { if (!ok(it)) hero = null }
        if (trendingMovies.any { it.id !in movieIDs }) trendingMovies = trendingMovies.filter { it.id in movieIDs }
        if (trendingSeries.any { it.id !in seriesIDs }) trendingSeries = trendingSeries.filter { it.id in seriesIDs }
        if (forYou.any { !ok(it) }) forYou = forYou.filter { ok(it) }
        if (newOnBingr.any { !ok(it) }) newOnBingr = newOnBingr.filter { ok(it) }
        if (heroPool.any { !ok(it) }) heroPool = heroPool.filter { ok(it) }
    }

    private fun updateHeroPool() {
        val key: (DetailTarget) -> String = { (if (it.isSeries) "s:" else "m:") + (it.sourceID?.lowercase() ?: "") + "|" + it.id }
        val fy = forYou.filter { t -> when (filter) { Filter.MOVIES -> !t.isSeries; Filter.SERIES -> t.isSeries; Filter.ALL -> true } }
        val nw = newOnBingr.filter { t -> when (filter) { Filter.MOVIES -> !t.isSeries; Filter.SERIES -> t.isSeries; Filter.ALL -> true } }
        val pool = Build140Logic.Picks.heroPool(fy, nw, key)
        if (pool.map(key) != heroPool.map(key)) heroPool = pool
    }

    suspend fun prepare(key: String) {
        val lib = Playlists.library
        if (lib.movies.isEmpty() && lib.series.isEmpty()) {
            hero = null; trendingMovies = emptyList(); trendingSeries = emptyList(); forYou = emptyList()
            newOnBingr = emptyList(); heroPool = emptyList()
            return
        }
        // 0. wat niet (meer) in de gefilterde lijst staat weghalen
        prune(lib)
        // 1. meteen iets laten zien: billboard en Top 10 uit de cache van vandaag (of je eigen lijst)
        if (hero == null) hero = quickHero(lib)
        val matcher = screensSafe { MatcherCache.matcher(lib, key) }
        applyTrending(lib, matcher, TrendingStore.cached(false, kidsProfile()), TrendingStore.cached(true, kidsProfile()))
        if (forYou.isEmpty()) setForYou(buildForYou(lib, false, matcher))
        if (hero == null) hero = quickHero(lib)
        // build 140: Nieuw op Bingr (alle servers, uit de cache op dit apparaat), daarna de vaste selectie voor uitgelicht.
        // Build 141: eerst de actieve server; de andere servers komen op de achtergrond (NewOnBingr.version → [refreshNew])
        refreshNew()

        // 2. daarna pas het netwerk (gedeeld door alle tabbladen)
        val (m, s) = coroutineScope {
            val a = async { screensSafe { TrendingStore.get(false, kidsProfile()) } ?: emptyList() }
            val b = async { screensSafe { TrendingStore.get(true, kidsProfile()) } ?: emptyList() }
            a.await() to b.await()
        }
        applyTrending(lib, matcher, m, s)
        setForYou(buildForYou(lib, true, matcher))
        updateHeroPool()
        prune(Playlists.library)
    }

    /** Build 141: Nieuw op Bingr opnieuw samenstellen (snel: de andere servers komen uit de cache van NewOnBingr). */
    suspend fun refreshNew() {
        val lib = Playlists.library
        if (lib.movies.isEmpty() && lib.series.isEmpty()) return
        val raw = Playlists.rawLibrary
        val pid = Playlists.source?.id
        val n = screensSafe { NewOnBingr.build(lib, raw, pid) } ?: return
        if (n != newOnBingr) newOnBingr = n
        updateHeroPool()
    }

    private fun setForYou(r: Pair<List<DetailTarget>, Boolean>) {
        forYou = r.first
        forYouPopular = r.second
    }

    /**
     * Billboard zonder te wachten (zolang de vaste selectie er nog niet is): iets uit de Top 10, anders een titel met
     * poster. Build 140: niet meer willekeurig per opening, maar per dag een vaste keuze.
     */
    private fun quickHero(lib: Library): DetailTarget? {
        if (Screenshots.isActive) return ScreenshotDemo.hero(filter, lib)
        val day = Build140Logic.Picks.dayNumber(System.currentTimeMillis(), java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()))
        fun <T> List<T>.pick(): T? = if (isEmpty()) null else this[Build140Logic.Picks.heroStart(day, size)]
        return when (filter) {
            Filter.SERIES -> (trendingSeries.firstOrNull() ?: lib.series.filter { it.poster != null }.pick())?.let { DetailTarget.SeriesT(it) }
            Filter.MOVIES -> (trendingMovies.firstOrNull() ?: lib.movies.filter { it.poster != null }.pick())?.let { DetailTarget.MovieT(it) }
            Filter.ALL -> {
                val m = trendingMovies.take(5).pick() ?: lib.movies.filter { it.poster != null }.pick()
                if (m != null) DetailTarget.MovieT(m) else lib.series.pick()?.let { DetailTarget.SeriesT(it) }
            }
        }
    }

    /** Top 10: trending gekoppeld aan je lijst; te weinig treffers → aanvullen met je best beoordeelde/nieuwste titels. */
    private suspend fun applyTrending(lib: Library, matcher: TitleMatcher?, movies: List<ExternalTitle>, series: List<ExternalTitle>) {
        if (filter != Filter.SERIES) {
            val found = withContext(Dispatchers.Default) { if (matcher == null || movies.isEmpty()) emptyList() else matcher.movies(movies) }
            if (found.size >= 5 || trendingMovies.isEmpty()) {
                trendingMovies = if (found.size >= 5) found
                else found + withContext(Dispatchers.Default) { localTopMovies(lib.movies) }.filter { m -> found.none { it.id == m.id } }
            }
        }
        if (filter != Filter.MOVIES) {
            val found = withContext(Dispatchers.Default) { if (matcher == null || series.isEmpty()) emptyList() else matcher.series(series) }
            if (found.size >= 5 || trendingSeries.isEmpty()) {
                trendingSeries = if (found.size >= 5) found
                else found + withContext(Dispatchers.Default) { localTopSeries(lib.series) }.filter { s -> found.none { it.id == s.id } }
            }
        }
    }

    private fun localTopMovies(items: List<Movie>): List<Movie> {
        val withPoster = items.filter { it.poster != null }
        val pool = withPoster.ifEmpty { items }
        val rated = pool.filter { (it.rating ?: 0.0) > 0 }
        if (rated.size >= 10) return rated.sortedByDescending { it.rating ?: 0.0 }.take(10)
        val recent = pool.filter { it.addedAt != null }.sortedByDescending { it.addedAt ?: 0L }
        if (recent.size >= 10) return recent.take(10)
        return pool.take(10)
    }

    private fun localTopSeries(items: List<Series>): List<Series> {
        val withPoster = items.filter { it.poster != null }
        val pool = withPoster.ifEmpty { items }
        val rated = pool.filter { (it.rating ?: 0.0) > 0 }
        if (rated.size >= 10) return rated.sortedByDescending { it.rating ?: 0.0 }.take(10)
        return pool.take(10)
    }

    /**
     * Build 140: "Voor jou" van HET actieve profiel (docs/ACCOUNT.md → "Build 140"): de laatst/meest bekeken titels van dit
     * profiel (Verder kijken en kijkgeschiedenis) als basis; met TMDB hun "recommendations", daarna titels met dezelfde
     * genres (meest overlap eerst) en uit dezelfde categorieën. Alleen wat in de (gefilterde: kinder- en leeftijdsfilter)
     * bibliotheek staat, nooit iets dat dit profiel al bekeken heeft of aan het kijken is. Zonder geschiedenis: populaire
     * titels met de kop "Populair" (second = true). Geen willekeur: dezelfde geschiedenis geeft dezelfde rij.
     * Tot build 139 kwamen er ook Trakt-titels van het hoofdaccount en willekeurige titels in (shuffled).
     */
    private suspend fun buildForYou(lib: Library, useNetwork: Boolean, matcher: TitleMatcher?): Pair<List<DetailTarget>, Boolean> {
        // Compose-state op de main thread lezen, daarna op de achtergrond rekenen
        val progress = Store.progress.filter { Store.belongsToActive(it.value.profileID) }
        val history = Store.historyAll()
        val localWatched = Store.localWatched
        val pid = Playlists.source?.id
        val useTrakt = Store.isMainActive
        val tm = trendingMovies
        val ts = trendingSeries
        return withContext(Dispatchers.Default) {
            val movieByID = HashMap<String, Movie>(lib.movies.size * 2).apply { lib.movies.forEach { putIfAbsent(it.id, it) } }
            val seriesByID = HashMap<String, Series>(lib.series.size * 2).apply { lib.series.forEach { putIfAbsent(it.id, it) } }
            val seriesByTitle = HashMap<String, Series>().apply {
                lib.series.forEach { putIfAbsent(it.title, it); putIfAbsent(AllServersLogic.normTitle(it.title), it) }
            }
            fun series(title: String) = seriesByTitle[title] ?: seriesByTitle[AllServersLogic.normTitle(title)]
            fun onThisServer(p: String?) = p == null || pid == null || p.equals(pid, ignoreCase = true)
            fun keyOf(t: DetailTarget) = (if (t.isSeries) "s:" else "m:") + t.id

            // 1. basis: wat dit profiel het laatst keek (voortgang en geschiedenis), nieuwste eerst
            val seedTimes = LinkedHashMap<String, Pair<DetailTarget, Long>>()
            fun seed(t: DetailTarget?, at: Long) {
                t ?: return
                val k = keyOf(t)
                val c = seedTimes[k]
                if (c == null || at > c.second) seedTimes[k] = t to at
            }
            for ((k, e) in progress) {
                if (!onThisServer(e.playlistID)) continue
                seed(movieByID[k]?.let { DetailTarget.MovieT(it) } ?: series(e.title)?.let { DetailTarget.SeriesT(it) }, e.updated)
            }
            for (h in history) {
                if (!onThisServer(h.playlistID)) continue
                when (h.type) {
                    MediaType.MOVIE -> seed(movieByID[h.itemID]?.let { DetailTarget.MovieT(it) }, h.date)
                    MediaType.SERIES, MediaType.EPISODE ->
                        seed((seriesByID[h.itemID] ?: series(h.title))?.let { DetailTarget.SeriesT(it) }, h.date)
                    else -> {}
                }
            }
            val seeds = seedTimes.values.sortedByDescending { it.second }.map { it.first }
                .filter { t -> when (filter) { Filter.MOVIES -> !t.isSeries; Filter.SERIES -> t.isSeries; Filter.ALL -> true } }
                .take(6)

            val out = ArrayList<DetailTarget>()
            val seen = HashSet<String>(seeds.map { keyOf(it) })
            fun watched(t: DetailTarget): Boolean = when (t) {
                is DetailTarget.MovieT -> progress[t.id] != null || t.movie.id in localWatched ||
                    (useTrakt && Trakt.isWatched(Trakt.imdb(t.movie)))
                is DetailTarget.SeriesT -> Store.localWatchedCount(t.series.id) > 0 ||
                    (useTrakt && Playables.watchedEpisodeCount(t.series) > 0)
            }
            fun add(t: DetailTarget) {
                if (filter == Filter.MOVIES && t.isSeries) return
                if (filter == Filter.SERIES && !t.isSeries) return
                if (!seen.add(keyOf(t)) || watched(t)) return
                out.add(t)
            }

            if (seeds.isEmpty()) {
                // zonder geschiedenis: "Populair" (Top 10 van vandaag uit je bibliotheek, daarna best beoordeeld)
                tm.take(10).zip(ts.take(10)).forEach { (a, b) -> add(DetailTarget.MovieT(a)); add(DetailTarget.SeriesT(b)) }
                tm.forEach { add(DetailTarget.MovieT(it)) }
                ts.forEach { add(DetailTarget.SeriesT(it)) }
                if (out.size < 10) {
                    lib.movies.filter { it.poster != null && (it.rating ?: 0.0) > 0 }.sortedByDescending { it.rating ?: 0.0 }.take(10)
                        .forEach { add(DetailTarget.MovieT(it)) }
                    lib.series.filter { it.poster != null && (it.rating ?: 0.0) > 0 }.sortedByDescending { it.rating ?: 0.0 }.take(10)
                        .forEach { add(DetailTarget.SeriesT(it)) }
                }
                return@withContext out.take(30) to true
            }

            // 2. TMDB-aanbevelingen van de laatste drie titels (gekoppeld aan je bibliotheek)
            if (useNetwork && matcher != null) {
                for (t in seeds.take(3)) {
                    val imdb = when (t) { is DetailTarget.MovieT -> t.movie.imdbID; is DetailTarget.SeriesT -> t.series.imdbID }
                    val r = screensSafe { TMDB.details(imdb, null, t.title, t.year, t.isSeries) } ?: continue
                    val ext = r.recommendations.map { ExternalTitle(listOf(it.title), it.year) }
                    if (ext.isEmpty()) continue
                    if (t.isSeries) matcher.series(ext, 8).forEach { add(DetailTarget.SeriesT(it)) }
                    else matcher.movies(ext, 8).forEach { add(DetailTarget.MovieT(it)) }
                }
            }

            // 3. dezelfde genres (bekende metadata), meest overlap eerst, recente titels zwaarder
            val P = Build140Logic.Picks
            fun genresOf(t: DetailTarget) = P.genres(when (t) { is DetailTarget.MovieT -> Metadata.cached(t.movie); is DetailTarget.SeriesT -> Metadata.cached(t.series) }?.genre)
            val weights = P.genreWeights(seeds.map { genresOf(it) })
            if (weights.isNotEmpty()) {
                val byKey = HashMap<String, DetailTarget>()
                val cands = ArrayList<Build140Logic.Picks.Cand>()
                if (filter != Filter.SERIES) for (m in lib.movies) {
                    val g = P.genres(Metadata.cached(m)?.genre).takeIf { it.isNotEmpty() } ?: continue
                    val t = DetailTarget.MovieT(m); byKey[keyOf(t)] = t
                    cands += Build140Logic.Picks.Cand(keyOf(t), g, m.rating, m.addedAt)
                }
                if (filter != Filter.MOVIES) for (x in lib.series) {
                    val g = P.genres(Metadata.cached(x)?.genre).takeIf { it.isNotEmpty() } ?: continue
                    val t = DetailTarget.SeriesT(x); byKey[keyOf(t)] = t
                    cands += Build140Logic.Picks.Cand(keyOf(t), g, x.rating, x.addedAt)
                }
                P.byGenres(cands, weights, seen, 40).forEach { k -> byKey[k]?.let { add(it) } }
            }

            // 4. dezelfde categorieën van je server (best beoordeeld, dan nieuwste)
            if (out.size < 20) {
                val groups = seeds.map { it.group }.filter { it.isNotBlank() }.distinct().take(4)
                for (g in groups) {
                    lib.movies.filter { it.group == g }.sortedWith(compareByDescending<Movie> { it.rating ?: 0.0 }.thenByDescending { it.addedAt ?: 0L })
                        .take(6).forEach { add(DetailTarget.MovieT(it)) }
                    lib.series.filter { it.group == g }.sortedWith(compareByDescending<Series> { it.rating ?: 0.0 }.thenByDescending { it.addedAt ?: 0L })
                        .take(6).forEach { add(DetailTarget.SeriesT(it)) }
                }
            }
            out.take(30) to false
        }
    }
}

// MARK: Verder kijken (= ContinueWatching.swift)

class ContinueItem(
    val id: String, val title: String, val subtitle: String, val artwork: String?,
    val fraction: Double, val updated: Long, val isMovie: Boolean,
    val position: Double? = null, val traktPlaybackID: Long? = null,
    /** Build 128: server van dit item als die niet de actieve is (Verder kijken over alle servers) */
    val sourceID: String? = null,
    /** Build 144: geen poster in de bibliotheek of de catalogmeta: deze titel bij TMDB opzoeken (zie [ContinueCovers]) */
    val coverLookup: Build144Logic.ContinueCover.Want? = null,
)

/**
 * Build 144: afbeelding van een tegel in Verder kijken: de poster uit de bibliotheek of de catalogmeta, anders de
 * TMDB-poster (bewaard op het apparaat), pas daarna het bewaarde beeld (vaak de scène). Ook voor oude voortgang en voortgang
 * van andere apparaten. Geeft de afbeelding en (als die ontbreekt) de titel om op te zoeken.
 */
private fun continueCover(title: String, year: Int?, isShow: Boolean, library: String?, meta: String?,
                          stored: String?): Pair<String?, Build144Logic.ContinueCover.Want?> {
    val C = Build144Logic.ContinueCover
    val need = C.needsLookup(library, meta, stored)
    val tmdb = if (need && title.isNotBlank()) ContinueCovers.cover(title, year, isShow) else null
    return C.pick(library, meta, tmdb, stored) to if (need && title.isNotBlank()) Build144Logic.ContinueCover.Want(title, year, isShow) else null
}

/**
 * Verder kijken van het actieve profiel op deze server (nieuwste eerst). Afleveringen alleen als de serie (nog)
 * zichtbaar is voor dit profiel. [trakt] = films die je in een andere app (via Trakt) half keek.
 */
fun continueItems(filter: Filter, index: HomeIndex, pid: String?, progress: Map<String, ProgressEntry>,
                  trakt: List<ContinueItem> = emptyList()): List<ContinueItem> {
    val out = ArrayList<ContinueItem>()
    for ((key, e) in progress) {
        if (!Store.belongsToActive(e.profileID)) continue                    // alleen van dit profiel
        // build 128: ook van de andere (en verwijderde) servers; titel en artwork staan al in de voortgang
        if (e.playlistID != null && !e.playlistID.equals(pid, ignoreCase = true)) {
            val full = "${e.playlistID}|$key"
            val item = AllServers.item(full)
            // build 140: kinderprofiel: alleen wat in de gefilterde bibliotheek van zijn server staat
            if (item == null && Store.activeProfile?.kids == true) continue
            val kind = CatalogMeta.kind(full)
            val isMovie = item is Movie || (item == null && kind == "movie")
            if (filter == Filter.MOVIES && !isMovie) continue
            if (filter == Filter.SERIES && isMovie) continue
            val left = max(0.0, e.position / max(e.fraction, 0.01) - e.position)
            // build 141: de poster van de film/serie, niet de still van de aflevering die de voortgang bewaarde
            // build 144: zonder poster op die server de TMDB-poster (ook voor voortgang van andere apparaten)
            val seriesKey = CatalogMeta.episode(full)?.series
            val title = e.title.ifBlank { CatalogMeta.title(full) ?: "" }
            val (art, lookup) = continueCover(
                (item as? Movie)?.title ?: (item as? Series)?.title ?: title,
                (item as? Movie)?.year ?: (item as? Series)?.year, !isMovie,
                (item as? Movie)?.poster ?: (item as? Series)?.poster,
                if (seriesKey != null) CatalogMeta.image(seriesKey) else if (kind != "episode") CatalogMeta.image(full) else null,
                e.artwork)
            out.add(ContinueItem(key, title.ifBlank { L("Unknown title") }, L("%d min left", (left / 60).toInt()),
                art, e.fraction, e.updated, isMovie, position = if (e.position > 0) e.position else null, sourceID = e.playlistID,
                coverLookup = lookup))
            continue
        }
        val isMovie = key in index.movieByID
        if (!isMovie && e.title !in index.seriesTitles) continue
        if (filter == Filter.MOVIES && !isMovie) continue
        if (filter == Filter.SERIES && isMovie) continue
        val left = max(0.0, e.position / max(e.fraction, 0.01) - e.position)
        // build 141: de poster van de film/serie gaat voor (bij een aflevering bewaarde de voortgang vaak een still)
        // build 144: zonder poster in de bibliotheek de TMDB-poster, pas daarna het bewaarde beeld
        val movie = if (isMovie) index.movieByID[key] else null
        val series = if (isMovie) null else index.seriesByTitle[e.title]
        val (art, lookup) = continueCover(movie?.title ?: series?.title ?: e.title, movie?.year ?: series?.year, !isMovie,
            movie?.poster ?: series?.poster, null, e.artwork)
        out.add(ContinueItem(key, e.title, L("%d min left", (left / 60).toInt()), art, e.fraction, e.updated, isMovie,
            position = if (e.position > 0) e.position else null, coverLookup = lookup))
    }
    if (filter != Filter.SERIES && trakt.isNotEmpty()) {
        val known = out.mapTo(HashSet()) { it.id }
        out.addAll(trakt.filter { it.id !in known })
    }
    // build 128: hetzelfde werk op twee servers = één tegel (de nieuwste)
    val seen = HashMap<String, String?>()
    return out.sortedByDescending { it.updated }.filter { c ->
        val k = (if (c.isMovie) "m:" else "s:") + AllServersLogic.normTitle(c.title)
        val prev = seen[k]
        if (k !in seen) { seen[k] = c.sourceID; true } else AllServersLogic.sameServer(prev ?: pid, c.sourceID ?: pid)
    }.take(20)
}

/** Films die je in een andere app (via Trakt) half keek, gekoppeld aan je lijst (op de achtergrond). */
@Composable
fun rememberTraktContinue(index: HomeIndex): List<ContinueItem> {
    val playback = Trakt.playback
    var list by remember { mutableStateOf(emptyList<ContinueItem>()) }
    LaunchedEffect(index, playback) {
        if (playback.isEmpty()) { list = emptyList(); return@LaunchedEffect }
        val movies = index.movieByID.values
        list = withContext(Dispatchers.Default) {
            playback.filter { it.type == "movie" }.mapNotNull { p ->
                val imdb = p.imdb ?: return@mapNotNull null
                val m = index.movieByIMDb[imdb] ?: movies.firstOrNull { Trakt.imdb(it) == imdb } ?: return@mapNotNull null
                ContinueItem(m.id, m.title, "Trakt · ${p.progress.toInt()}%", m.poster, p.progress / 100.0, 0L, true,
                    traktPlaybackID = p.id)
            }
        }
    }
    return list
}

fun ContinueItem.choice(index: HomeIndex): ResumeChoice {
    sourceID?.let { sid ->
        val d = when (val x = AllServers.item("$sid|$id")) {
            is Movie -> DetailTarget.MovieT(x)
            is Series -> DetailTarget.SeriesT(x)
            else -> null
        }
        return ResumeChoice(id, title, fraction, position, d, sourceID = sid)
    }
    val d: DetailTarget? = if (isMovie) index.movieByID[id]?.let { DetailTarget.MovieT(it) }
    else index.seriesByTitle[title]?.let { DetailTarget.SeriesT(it) }
    return ResumeChoice(id, title, fraction, position, d)
}

fun removeFromContinue(c: ContinueItem) {
    Store.clearProgress(c.id)
    c.traktPlaybackID?.let { id -> AppScope.launch { screensSafe { Trakt.removePlayback(id) } } }
}

/**
 * Kaart voor "Verder kijken": de poster (cover) van de film of serie met de afspeelknop en de voortgangsbalk erover,
 * daaronder de resterende tijd. Build 141: de balk staat op de poster (niet eronder) en de poster is die van de titel,
 * niet een still van de scène.
 */
@Composable
fun ContinueCard(title: String, poster: String?, fraction: Double, caption: String?, modifier: Modifier = Modifier) {
    Column(modifier.width(Theme.posterWidth), verticalArrangement = Arrangement.spacedBy(tvs(5))) {
        Box(contentAlignment = Alignment.Center) {
            PosterCard(title, poster)
            Box(
                Modifier.size(tvs(38)).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))
                    .border(1.5.dp, Color.White.copy(alpha = 0.9f), CircleShape),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(tvs(20))) }
            // voortgang over de onderkant van de poster, op een donkere band zodat hij op elke cover te zien is
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                    .padding(start = tvs(8), end = tvs(8), top = tvs(14), bottom = tvs(8))
            ) {
                ProgressBar(max(fraction, 0.03), Modifier.fillMaxWidth().height(tvs(4)).clip(RoundedCornerShape(50)))
            }
        }
        if (caption != null) {
            Text(caption, color = Theme.secondary, fontSize = tsp(10), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(Theme.posterWidth))
        }
    }
}

/** Build 144: ontbrekende posters van Verder kijken op de achtergrond opzoeken (TMDB, bewaard op het apparaat). */
@Composable
fun ResolveContinueCovers(items: List<ContinueItem>) {
    val wants = remember(items) { items.mapNotNull { it.coverLookup }.distinct() }
    LaunchedEffect(wants) { if (wants.isNotEmpty()) screensSafe { ContinueCovers.resolve(wants) } }
}

/** "Hervatten vanaf … / Vanaf het begin kijken / Meer info" voor een titel uit Verder kijken. */
@Composable
fun ResumeDialog(choice: ResumeChoice, onDismiss: () -> Unit) {
    val pct = (choice.fraction * 100).roundToInt()
    val actions = buildList {
        add(S2Action(choice.position?.let { L("Resume from %@", Playables.formatPosition(it)) } ?: L("Resume"), Icons.Default.PlayArrow, primary = true) {
            AppScope.launch { ResumePlayer.play(choice.id, choice.title, restart = false, sourceID = choice.sourceID)?.let { ScreensNav.detail(it) } }
        })
        add(S2Action(L("Watch from the beginning"), Icons.Default.Replay) {
            AppScope.launch { ResumePlayer.play(choice.id, choice.title, restart = true, sourceID = choice.sourceID)?.let { ScreensNav.detail(it) } }
        })
        choice.detail?.let { d -> add(S2Action(L("More info"), Icons.Outlined.Info) { ScreensNav.detail(d) }) }
    }
    S2ActionDialog("${choice.title} · ${L("%d%% watched", pct)}", actions, onDismiss = onDismiss)
}

/** Rij "Verder kijken": tik = kiezen (hervatten/opnieuw/meer info), lang indrukken = verwijderen. */
@Composable
fun ContinueRow(items: List<ContinueItem>, index: HomeIndex) {
    if (items.isEmpty()) return
    var choice by remember { mutableStateOf<ResumeChoice?>(null) }
    var removing by remember { mutableStateOf<ContinueItem?>(null) }
    val keys = rememberKeys(items) { "${it.sourceID ?: ""}|${it.id}" }
    MediaRow(L("Continue watching")) {
        keyedItems(items, keys) { c ->
            ContinueCard(
                c.title, c.artwork, c.fraction, c.subtitle,
                Modifier.focusLift(onLongClick = { removing = c }) { choice = c.choice(index) }
            )
        }
    }
    choice?.let { ResumeDialog(it) { choice = null } }
    removing?.let { c ->
        S2ActionDialog(c.title, listOf(
            S2Action(L("Remove from Continue watching"), Icons.Default.Close, destructive = true) { removeFromContinue(c) }
        ), onDismiss = { removing = null })
    }
}

private fun openDetail(t: DetailTarget) = ScreensNav.detail(t)
private fun openCategory(title: String, items: List<DetailTarget>) = ScreensNav.category(CategoryTarget(title, items))

/** Profielen met een leeftijdsgrens gaan via de detailpagina (die controleert de classificatie). */
private fun homePlay(t: DetailTarget) {
    val ageLimited = Store.activeProfile?.effectiveAgeLimit != null
    when {
        // build 128: van een verwijderde server (of niet meer op zijn server): eerst zoeken (ScreensNav.detail)
        t.sourceID != null && AllServers.isOrphan("${t.sourceID}|${t.id}") -> openDetail(t)
        // build 111: TMDB-titel (niet in je bibliotheek, bijv. uit Mijn lijst) → detailpagina
        t is DetailTarget.MovieT && !ageLimited && !DiscoverItem.isExternal(t.movie.id) -> AppState.play(Playables.movie(t.movie, Trakt.imdb(t.movie)))
        else -> openDetail(t)
    }
}

/** Home / Films / Series in Netflix-stijl (= HomeView.swift). */
@Composable
fun HomeScreen(filter: Filter) {
    val tv = AppState.isTV
    val lib = Playlists.library
    val pid = Playlists.source?.id
    val model = remember(filter) { HomeModel(filter) }
    val listState = rememberLazyListState()
    val index = rememberHomeIndex(lib)
    val prepKey = "${Store.activeProfileID ?: ""}-${pid ?: ""}-${lib.movies.size}-${lib.series.size}"
    LaunchedEffect(prepKey) { model.prepare("${pid ?: ""}-${lib.movies.size}-${lib.series.size}") }

    val kids = Store.activeProfile?.kids ?: false
    val groupLimit = if (filter == Filter.ALL) 8 else 40

    // Verder kijken, Mijn lijst en favorieten via sleutels (snel), niet door de hele bibliotheek te lopen
    val progress = Store.progress
    val traktContinue = rememberTraktContinue(index)
    val allV = AllServers.version + CatalogMeta.version
    // build 144: ContinueCovers.version = TMDB-posters binnen
    val continueList = remember(progress, index, pid, filter, traktContinue, Store.activeProfileID, allV, ContinueCovers.version) {
        continueItems(filter, index, pid, progress, traktContinue)
    }
    ResolveContinueCovers(continueList)
    val myList = Store.myList
    // build 111: plus TMDB-titels (Binnenkort op Bingr / Nieuw op Bingr) die niet in je bibliotheek staan; alleen
    // zonder leeftijdsgrens (die profielen zien alleen hun eigen, gefilterde bibliotheek)
    var knownTick by remember { mutableStateOf(0) }
    val external = Store.activeProfile?.effectiveAgeLimit == null
    // build 128: Mijn lijst over alle servers (laatst toegevoegd eerst; hetzelfde werk op twee servers = één tegel)
    val allMine = remember(myList, index, pid, external, knownTick, allV) {
        if (pid == null) emptyList<DetailTarget>()
        else AllServers.myList(myList, pid) { k ->
            (index.movie(k, pid) ?: if (external) DiscoverKnown.movie(k, pid) else null)?.let { DetailTarget.MovieT(it) }
                ?: (index.series(k, pid) ?: if (external) DiscoverKnown.series(k, pid) else null)?.let { DetailTarget.SeriesT(it) }
        }
    }
    val myMovies = remember(allMine) { allMine.mapNotNull { (it as? DetailTarget.MovieT)?.movie } }
    val mySeries = remember(allMine) { allMine.mapNotNull { (it as? DetailTarget.SeriesT)?.series } }
    // build 111: TMDB-titels in Mijn lijst die dit apparaat nog niet kent (op een ander apparaat toegevoegd)
    LaunchedEffect(myList, pid) {
        if (pid == null) return@LaunchedEffect
        // build 141: ook TMDB-titels die onder een andere server staan (op een ander apparaat toegevoegd)
        val ids = myList.mapNotNull { Build141Feedback.MyListKeys.externalID(it) }.distinct()
        if (ids.isEmpty()) return@LaunchedEffect
        val missing = withContext(Dispatchers.IO) { ids.filter { !DiscoverKnown.contains(it) } }
        if (missing.isNotEmpty() && screensSafe { DiscoverStore.resolve(missing) } == true) knownTick++
    }
    val favorites = Store.favorites
    // build 128: favoriete en recente zenders over alle servers
    val favChannels = remember(favorites, index, pid, allV) {
        if (pid == null) emptyList<Channel>() else {
            val times = StateSync.addedTimes(Store.activeProfileID, "favorites")
            AllServers.channels(favorites.sortedByDescending { times[it] ?: 0L }, pid) { index.channel(it, pid) }
        }
    }
    val recentChannels = Store.recentChannels
    val liveChannels = remember(favChannels, recentChannels, index, pid, allV) {
        favChannels.ifEmpty { if (pid == null) emptyList() else AllServers.channels(recentChannels, pid) { index.channel(it, pid) } }
    }
    val collections = Collections.visible
    // build 141: zonder "Nieuw toegevoegd"; "Nieuw op Bingr" staat op die plek (HomeLayouts.displayed)
    val layoutRows = remember(Store.activeHomeLayout, collections) { HomeLayouts.displayed(Store.activeHomeLayout, collections) }
    // build 141: Nieuw op Bingr van de andere servers is (op de achtergrond) klaar → de rij bijwerken
    LaunchedEffect(NewOnBingr.version) { if (NewOnBingr.version > 0) model.refreshNew() }

    // build 111: Binnenkort op Bingr / Nieuw op Bingr (TMDB): alleen de rijen die in de indeling aan staan; eerst wat
    // bewaard is, daarna het netwerk (12 uur bewaard), koppelen aan je bibliotheek op de achtergrond
    val discoverKinds = remember(layoutRows, filter) {
        // build 140: Nieuw op Bingr ("streaming") komt uit je eigen bibliotheek (NewOnBingr), niet meer uit TMDB
        if (filter != Filter.ALL) emptyList() else DiscoverKind.entries.filter { k -> k != DiscoverKind.STREAMING && layoutRows.any { it.key == k.key && it.visible } }
    }
    var discover by remember(filter) { mutableStateOf(mapOf<DiscoverKind, List<DiscoverEntry>>()) }
    LaunchedEffect(System.identityHashCode(lib), discoverKinds, Store.activeProfileID, Localizer.language) {
        if (discoverKinds.isEmpty() || (lib.movies.isEmpty() && lib.series.isEmpty())) {
            if (discover.isNotEmpty()) discover = emptyMap()
            return@LaunchedEffect
        }
        val profile = Store.activeProfile
        val kidsProfile = profile?.kids == true
        val onlyLibrary = profile?.effectiveAgeLimit != null
        val matcher = screensSafe { MatcherCache.matcher(lib, "${pid ?: ""}-${lib.movies.size}-${lib.series.size}") }
        val out = HashMap<DiscoverKind, List<DiscoverEntry>>()
        for (k in discoverKinds) {
            val cached = withContext(Dispatchers.IO) { DiscoverStore.cached(k, kidsProfile) }
            // build 112: Nieuw op Bingr alleen met titels uit je bibliotheek (k.onlyLibrary)
            if (cached.isNotEmpty()) out[k] = DiscoverStore.match(cached, matcher, onlyLibrary || k.onlyLibrary)
        }
        if (out != discover) discover = out.toMap()
        for (k in discoverKinds) {
            val items = screensSafe { DiscoverStore.get(k, kidsProfile) } ?: emptyList()
            out[k] = DiscoverStore.match(items, matcher, onlyLibrary || k.onlyLibrary)
        }
        if (out != discover) discover = out.toMap()
    }

    Box(Modifier.fillMaxSize().background(Theme.bg)) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(tvs(26)),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "hero") {
                // build 140: vaste roulerende selectie (om en om Voor jou en Nieuw op Bingr, max. 8): om de 8 s de volgende;
                // waar hij begint schuift elke dag één plek op. Geen willekeur per opening.
                val pool = model.heroPool
                var heroAt by remember(pool) {
                    val now = System.currentTimeMillis()
                    mutableIntStateOf(Build140Logic.Picks.heroStart(
                        Build140Logic.Picks.dayNumber(now, java.util.TimeZone.getDefault().getOffset(now)), pool.size))
                }
                LaunchedEffect(pool) {
                    if (pool.size < 2) return@LaunchedEffect
                    while (true) {
                        delay(Build140Logic.Picks.HERO_INTERVAL)
                        heroAt = (heroAt + 1) % pool.size
                    }
                }
                val h = pool.getOrNull(heroAt) ?: model.hero
                if (h != null) {
                    HeroBanner(target = h, onPlay = { homePlay(h) }, onInfo = { openDetail(h) })
                } else {
                    Spacer(Modifier.then(if (tv) Modifier else Modifier.statusBarsPadding()).height(tvs(90)))
                }
            }

            // build 86: rij "Categorieën" (tegels) op zijn standaardplek (in de indeling direct na "live"): precies vijf
            // getoonde rijen tussen "Nu live" en Categorieën; zijn er daarna minder rijen, dan onderaan. Staat "Nu live"
            // uit of is die rij leeg, dan telt de plek waar hij zou staan. Elders in de indeling gezet (bv. op een
            // ander apparaat verplaatst): dan staat hij op die plek.
            val tilesOn = filter == Filter.ALL && (lib.movies.isNotEmpty() || lib.series.isNotEmpty()) &&
                layoutRows.any { it.key == HomeLayouts.CATEGORY_TILES && it.visible }
            val tilesAt = layoutRows.indexOfFirst { it.key == HomeLayouts.CATEGORY_TILES }
            val autoTiles = tilesAt > 0 && layoutRows[tilesAt - 1].key == "live"
            var sinceLive = -1
            var tilesPlaced = false
            fun LazyListScope.placeTiles() {
                if (!tilesOn || tilesPlaced) return
                tilesPlaced = true
                item(key = "browse") { CategoriesHomeRow() }
                // build 149: Ontdek categorieën (grote tegels per categorie van de server, logo van de dienst uit TMDB)
                item(key = "discovercats") { DiscoverCategoriesRow() }
            }
            /** Na elke getoonde rij: tellen vanaf "Nu live" en de categorierij na de vijfde plaatsen. */
            fun LazyListScope.shown(visible: Boolean = true) {
                if (!visible || sinceLive < 0) return
                sinceLive++
                if (autoTiles && sinceLive == 5) placeTiles()
            }

            fun LazyListScope.section(key: String) {
                when (key) {
                    // Verder kijken
                    "continue" -> { item(key = "continue") { ContinueRow(continueList, index) }; shown(continueList.isNotEmpty()) }
                    // Mijn lijst
                    "mylist" -> item(key = "mylist") {
                        when (filter) {
                            Filter.ALL -> {
                                val mine = allMine
                                if (mine.isNotEmpty()) TargetRow(L("My List"), mine, pid)
                            }
                            Filter.MOVIES -> if (myMovies.isNotEmpty()) MovieRow(L("My List"), myMovies, myMovies, pid)
                            Filter.SERIES -> if (mySeries.isNotEmpty()) SeriesRow(L("My List"), mySeries, mySeries)
                        }
                    }.also { shown(myMovies.isNotEmpty() || mySeries.isNotEmpty()) }
                    // build 141: "Nieuw toegevoegd" is weg (HomeLayouts.displayed zet Nieuw op Bingr op zijn plek)
                    "recent" -> {}
                    // build 111: Binnenkort op Bingr / Nieuw op Bingr (TMDB)
                    // build 140: Nieuw op Bingr = laatst toegevoegd aan je bibliotheek (alle servers), nieuwste eerst.
                    // Build 141: met het label Film/Serie op de tegels (zoals Voor jou), ook op Films en Series (alleen die soort)
                    "streaming" -> {
                        val list = when (filter) {
                            Filter.ALL -> model.newOnBingr
                            Filter.MOVIES -> model.newOnBingr.filter { !it.isSeries }
                            Filter.SERIES -> model.newOnBingr.filter { it.isSeries }
                        }
                        if (list.isNotEmpty()) item(key = "d|streaming") { LabeledTargetRow(L("New on Bingr"), list) }
                        shown(list.isNotEmpty())
                    }
                    "upcoming" -> if (filter == Filter.ALL) {
                        val kind = DiscoverKind.of(key)
                        val list = kind?.let { discover[it] }.orEmpty()
                        if (kind != null && list.isNotEmpty()) item(key = "d|$key") { DiscoverRow(kind, list, pid) }
                        shown(list.isNotEmpty())
                    }
                    // Voor jou
                    "foryou" -> { item(key = "foryou") { ForYouRow(filter, model.forYou, model.forYouPopular) }; shown(model.forYou.isNotEmpty()) }
                    // Top 10 (Home: eerst series, dan films)
                    "top10" -> {
                        if (filter != Filter.MOVIES) item(key = "top10s") {
                            val list = model.trendingSeries
                            if (list.isNotEmpty()) Top10SeriesRow(if (kids) L("Top 10 kids series") else L("Top 10 series today"), list)
                        }.also { shown(model.trendingSeries.isNotEmpty()) }
                        if (filter != Filter.SERIES) item(key = "top10m") {
                            val list = model.trendingMovies
                            if (list.isNotEmpty()) Top10MoviesRow(if (kids) L("Top 10 kids movies") else L("Top 10 movies today"), list)
                        }.also { shown(model.trendingMovies.isNotEmpty()) }
                    }
                    // favoriete zenders (anders de laatst bekeken zenders)
                    "live" -> { if (filter == Filter.ALL && liveChannels.isNotEmpty()) item(key = "favs") {
                        val keys = rememberKeys(liveChannels) { "${it.sourceID ?: ""}|${it.id}" }
                        MediaRow(if (favChannels.isNotEmpty()) L("Favorite channels") else L("Live TV")) {
                            keyedItems(liveChannels, keys) { c ->
                                val now = if (c.sourceID == null) lib.nowPlaying(c) else null
                                ChannelCard(
                                    c, now, favorite = favChannels.isNotEmpty(),
                                    modifier = Modifier.focusLift {
                                        AllServers.playChannel(c, liveChannels, if (favChannels.isNotEmpty()) AllServers.Field.FAVORITES else AllServers.Field.RECENT)
                                    }
                                )
                            }
                        }
                    }
                        // ankerpunt "Nu live", ook als de rij leeg is (dan telt de plek waar hij zou staan)
                        if (sinceLive < 0) sinceLive = 0
                    }
                    // alle films / series (Home)
                    "movies" -> if (filter == Filter.ALL && lib.movies.isNotEmpty()) item(key = "allmovies") {
                        MovieRow(L("Movies"), lib.movies.take(40), lib.movies, pid)
                    }.also { shown(lib.movies.isNotEmpty()) }
                    "series" -> if (filter == Filter.ALL && lib.series.isNotEmpty()) item(key = "allseries") {
                        SeriesRow(L("Series"), lib.series.take(40), lib.series)
                    }.also { shown(lib.series.isNotEmpty()) }
                    // categorieën
                    "categories" -> {
                        // per groep één item, zodat de categorierij er ook tussen kan staan (vijf rijen onder "Nu live")
                        if (filter != Filter.SERIES) for ((g, all) in index.movieGroups.take(groupLimit)) {
                            item(key = "m|$g") { if (all.isNotEmpty()) MovieRow(g, all.take(40), all, pid) }
                            shown(all.isNotEmpty())
                        }
                        if (filter != Filter.MOVIES) for ((g, all) in index.seriesGroups.take(groupLimit)) {
                            item(key = "s|$g") { if (all.isNotEmpty()) SeriesRow(g, all.take(40), all) }
                            shown(all.isNotEmpty())
                        }
                    }
                    // eigen collecties (build 51)
                    else -> if (key.startsWith("collection:")) {
                        val c = Collections.byID(key.removePrefix("collection:"))
                        val fits = c != null && when (filter) {
                            Filter.ALL -> true
                            Filter.MOVIES -> Collections.mayHaveMovies(c)
                            Filter.SERIES -> Collections.mayHaveSeries(c)
                        }
                        if (c != null && fits) item(key = "c|" + c.id + "|" + filter.name) {
                            CollectionRow(c, only = when (filter) {
                                Filter.ALL -> null; Filter.MOVIES -> BingrCollection.MOVIE; Filter.SERIES -> BingrCollection.SERIES
                            })
                        }
                        shown(c != null && fits)
                    }
                }
            }

            if (filter == Filter.ALL) {
                // startscherm van dit profiel (Mijn Bingr → Home screen); uitgezette rijen tellen niet, maar
                // "Nu live" blijft ook uitgezet het ankerpunt voor de categorierij
                layoutRows.forEach { r ->
                    when {
                        r.key == HomeLayouts.CATEGORY_TILES -> if (!autoTiles) placeTiles()
                        r.visible -> section(r.key)
                        r.key == "live" && sinceLive < 0 -> { sinceLive = 0 }
                    }
                }
                if (autoTiles) placeTiles()
            } else {
                // build 141: Nieuw op Bingr op de plek van "Nieuw toegevoegd"
                listOf("continue", "mylist", "streaming", "foryou", "top10").forEach { section(it) }
                layoutRows.filter { it.visible && it.key.startsWith("collection:") }.forEach { section(it.key) }
                section("categories")
            }

            if (lib.movies.isEmpty() && lib.series.isEmpty() && !Playlists.isLoading && !Playlists.isFiltering) item(key = "empty") {
                Column(
                    Modifier.fillMaxWidth().padding(top = 60.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Movie, null, tint = Theme.secondary, modifier = Modifier.size(tvs(40)))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (filter == Filter.ALL) L("This playlist only contains live TV.") else L("No titles found in this playlist."),
                        color = Theme.secondary, fontSize = tsp(14), textAlign = TextAlign.Center
                    )
                }
            }
        }

        // telefoon: bovenbalk die donker wordt bij scrollen (op TV zit Zoeken in de tabbalk)
        if (!tv) {
            val solid by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 300 } }
            Row(
                Modifier.fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = if (solid) 0.95f else 0.55f), Color.Black.copy(alpha = if (solid) 0.9f else 0f))
                        )
                    )
                    .statusBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                BingrLogo(26.sp)
                // build 86: Categorieën via Mijn Bingr en de rij op Home (de chip uit build 85 is weg)
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(34.dp).focusLift { ScreensNav.search() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Search, L("Search"), tint = Color.White, modifier = Modifier.size(25.dp))
                }
                // build 72: profielmenu (andere profielen, Mijn Bingr, Instellingen, Account, Uitloggen) zoals op bingr.online
                ProfileMenuButton(avatarSize = 30.dp)
            }
        }
    }
}

// MARK: rijen

@Composable
private fun MovieRow(title: String, items: List<Movie>, all: List<Movie>?, pid: String?) {
    val keys = rememberKeys(items) { it.id }
    MediaRow(title, onSeeAll = all?.let { list -> { openCategory(title, list.map { DetailTarget.MovieT(it) }) } }) {
        keyedItems(items, keys) { m -> MoviePoster(m, pid) }
    }
}

@Composable
private fun SeriesRow(title: String, items: List<Series>, all: List<Series>?) {
    val keys = rememberKeys(items) { it.id }
    MediaRow(title, onSeeAll = all?.let { list -> { openCategory(title, list.map { DetailTarget.SeriesT(it) }) } }) {
        keyedItems(items, keys) { s -> SeriesPoster(s) }
    }
}

@Composable
private fun SeriesPoster(s: Series) {
    var collect by remember { mutableStateOf(false) }
    PosterCard(
        s.title, s.poster,
        modifier = Modifier.focusLift(onLongClick = { collect = true }) { openDetail(DetailTarget.SeriesT(s)) },
        language = languageLabel(s),
        watchedEpisodes = Playables.watchedEpisodeCount(s)
    )
    if (collect) AddToCollectionDialog(Collections.ref(DetailTarget.SeriesT(s)), s.title, s.poster) { collect = false }
}

/** Films én series in één rij (Mijn lijst op Home). */
@Composable
private fun TargetRow(title: String, items: List<DetailTarget>, pid: String?) {
    val keys = rememberKeys(items) { (if (it.isSeries) "s:" else "m:") + it.id }
    MediaRow(title, onSeeAll = { openCategory(title, items) }) {
        keyedItems(items, keys) { t ->
            when (t) {
                is DetailTarget.MovieT -> MoviePoster(t.movie, pid)
                is DetailTarget.SeriesT -> SeriesPoster(t.series)
            }
        }
    }
}

/**
 * Build 111: rij Binnenkort op Bingr / Nieuw op Bingr (TMDB). Titels uit je bibliotheek openen die titel; de rest
 * een detailpagina met Mijn lijst. Build 112: Nieuw op Bingr alleen met bibliotheektitels; geen bronvermelding meer onder
 * de rij (staat in Instellingen → Over).
 */
@Composable
private fun DiscoverRow(kind: DiscoverKind, list: List<DiscoverEntry>, pid: String?) {
    val keys = rememberKeys(list) { it.id }
    Column(verticalArrangement = Arrangement.spacedBy(tvs(4))) {
        MediaRow(kind.title) {
            keyedItems(list, keys) { e ->
                when (val m = e.match) {
                    is DetailTarget.MovieT -> MoviePoster(m.movie, pid)
                    is DetailTarget.SeriesT -> SeriesPoster(m.series)
                    null -> ExternalPoster(e.item, pid)
                }
            }
        }
        // build 112: geen bronvermelding meer onder de rij (staat in Instellingen → Over)
    }
}

/** Build 111: poster van een TMDB-titel die niet in je bibliotheek staat (lang indrukken = Mijn lijst). */
@Composable
private fun ExternalPoster(item: DiscoverItem, pid: String?) {
    var menu by remember { mutableStateOf(false) }
    PosterCard(
        item.title, item.poster,
        modifier = Modifier.focusLift(onLongClick = { menu = true }) { openDetail(item.target) },
        // build 112: datum als rood label linksboven (zoals SERIE/FILM)
        badge = item.dateLabel,
        badgeTopStart = true
    )
    if (menu && pid != null) {
        val inList = Store.inMyList(pid, item.id)
        S2ActionDialog(item.title, listOf(
            S2Action(L("More info"), Icons.Outlined.Info) { openDetail(item.target) },
            S2Action(if (inList) L("Remove from My List") else L("Add to My List"), PlusCheck.icon(inList)) {
                Store.setMyList(pid, item.id, !inList)
            }
        ), onDismiss = { menu = false })
    }
}

/** Poster met lang-indrukken-menu (afspelen, bekeken, Mijn lijst). */
@Composable
private fun MoviePoster(m: Movie, pid: String?) {
    var menu by remember { mutableStateOf(false) }
    var collect by remember { mutableStateOf(false) }
    val imdb = Trakt.imdb(m)
    val watched = Playables.isWatched(m, imdb)
    PosterCard(
        m.title, m.poster,
        modifier = Modifier.focusLift(onLongClick = { menu = true }) { openDetail(DetailTarget.MovieT(m)) },
        watched = watched,
        progress = Store.progress[m.id]?.fraction,
        language = languageLabel(m)
    )
    if (menu) {
        val actions = buildList {
            add(S2Action(L("Play"), Icons.Default.PlayArrow) { homePlay(DetailTarget.MovieT(m)) })
            add(S2Action(if (watched) L("Mark as unwatched") else L("Mark as watched"),
                if (watched) Icons.Default.VisibilityOff else Icons.Default.CheckCircle) {
                Store.setLocalWatched(m.id, !watched)
                AppScope.launch { screensSafe { Trakt.setWatched(Playables.movie(m, imdb), !watched) } }
            })
            if (pid != null) {
                // build 140: met de server van de titel (een titel van een andere server kwam anders niet in Mijn lijst)
                val t = DetailTarget.MovieT(m)
                val inList = AllServers.inMyList(t, pid)
                add(S2Action(if (inList) L("Remove from My List") else L("Add to My List"),
                    PlusCheck.icon(inList)) { AllServers.setMyList(t, !inList, pid) })
            }
            add(S2Action(L("Add to collection…"), Icons.AutoMirrored.Filled.PlaylistAdd) { collect = true })
        }
        S2ActionDialog(m.title, actions, onDismiss = { menu = false })
    }
    if (collect) AddToCollectionDialog(Collections.ref(DetailTarget.MovieT(m)), m.title, m.poster) { collect = false }
}

@Composable
private fun ForYouRow(filter: Filter, all: List<DetailTarget>, popular: Boolean = false) {
    // build 74: altijd alfabetisch op titel (zonder taallabels; hoofdletters en accenten tellen niet), films en series door elkaar
    val items = remember(all, filter) {
        val collator = java.text.Collator.getInstance(Localizer.locale).apply { strength = java.text.Collator.PRIMARY }
        all.filter { t ->
            when (filter) {
                Filter.MOVIES -> !t.isSeries
                Filter.SERIES -> t.isSeries
                Filter.ALL -> true
            }
        }.map { ContentLanguage.displayTitle(it.title) to it }
            .sortedWith { a, b -> collator.compare(a.first, b.first).takeIf { it != 0 } ?: collator.compare(a.second.title, b.second.title) }
            .map { it.second }
    }
    if (items.isEmpty()) return
    val keys = rememberKeys(items) { (if (it.isSeries) "s:" else "m:") + it.id }
    // build 140: zonder kijkgeschiedenis in dit profiel heet de rij "Populair"
    val title = if (popular) L("Popular") else L("For you")
    MediaRow(title, onSeeAll = { openCategory(title, items) }) {
        keyedItems(items, keys) { t ->
            // build 75: bekeken-label onder het SERIE/FILM-label, vinkje rechtsboven voor bekeken films
            PosterCard(
                t.title, t.poster,
                modifier = Modifier.focusLift { openDetail(t) },
                watched = (t as? DetailTarget.MovieT)?.let { Playables.isWatched(it.movie, Trakt.imdb(it.movie)) } ?: false,
                badge = if (t.isSeries) L("SERIES") else L("MOVIE"),
                language = languageLabel(t),
                badgeTopStart = true,
                watchedEpisodes = (t as? DetailTarget.SeriesT)?.let { Playables.watchedEpisodeCount(it.series) } ?: 0
            )
        }
    }
}

/**
 * Build 141: rij in de volgorde van [items] (Nieuw op Bingr: nieuwste eerst) met het label FILM/SERIE linksboven op elke
 * tegel, zoals bij Voor jou.
 */
@Composable
private fun LabeledTargetRow(title: String, items: List<DetailTarget>) {
    val keys = rememberKeys(items) { (if (it.isSeries) "s:" else "m:") + (it.sourceID?.lowercase() ?: "") + "|" + it.id }
    MediaRow(title, onSeeAll = { openCategory(title, items) }) {
        keyedItems(items, keys) { t ->
            PosterCard(
                t.title, t.poster,
                modifier = Modifier.focusLift { openDetail(t) },
                watched = (t as? DetailTarget.MovieT)?.let { Playables.isWatched(it.movie, Trakt.imdb(it.movie)) } ?: false,
                badge = if (t.isSeries) L("SERIES") else L("MOVIE"),
                language = languageLabel(t),
                badgeTopStart = true,
                watchedEpisodes = (t as? DetailTarget.SeriesT)?.let { Playables.watchedEpisodeCount(it.series) } ?: 0
            )
        }
    }
}

@Composable
private fun Top10MoviesRow(title: String, all: List<Movie>) {
    val recentSince = System.currentTimeMillis() - 14L * 86_400_000L
    MediaRow(title, onSeeAll = { openCategory(title, all.map { DetailTarget.MovieT(it) }) }) {
        itemsIndexed(all.take(10)) { i, m ->
            Box(Modifier.focusLift { openDetail(DetailTarget.MovieT(m)) }) {
                Top10Card(
                    i + 1, m.title, m.poster,
                    watched = Playables.isWatched(m, Trakt.imdb(m)),
                    badge = if ((m.addedAt ?: 0L) > recentSince) L("Recently added") else null,
                    language = languageLabel(m)
                )
            }
        }
    }
}

@Composable
private fun Top10SeriesRow(title: String, all: List<Series>) {
    MediaRow(title, onSeeAll = { openCategory(title, all.map { DetailTarget.SeriesT(it) }) }) {
        itemsIndexed(all.take(10)) { i, s ->
            Box(Modifier.focusLift { openDetail(DetailTarget.SeriesT(s)) }) {
                Top10Card(i + 1, s.title, s.poster, language = languageLabel(s), watchedEpisodes = Playables.watchedEpisodeCount(s))
            }
        }
    }
}

// MARK: billboard (= HeroBanner.swift)

/** Grote uitgelichte titel bovenaan Home (Netflix "billboard"). */
@Composable
fun HeroBanner(target: DetailTarget, onPlay: () -> Unit, onInfo: () -> Unit, playFocus: FocusRequester? = null) {
    var info by remember(target.id) { mutableStateOf<TitleMetadata?>(null) }
    LaunchedEffect(target.id) {
        info = screensSafe {
            when (target) {
                is DetailTarget.MovieT -> Metadata.metadata(target.movie)
                is DetailTarget.SeriesT -> Metadata.metadata(target.series)
            }
        }
    }
    val poster = info?.poster ?: target.poster
    val tags = listOfNotNull(
        info?.year,
        info?.genre?.split(",")?.take(2)?.joinToString(" • ") { it.trim() },
        info?.runtime
    ).filter { it.isNotBlank() }.joinToString("  •  ").ifEmpty { if (target.isSeries) L("Series") else L("Movie") }

    if (AppState.isTV) {
        Box(Modifier.fillMaxWidth().height(400.dp)) {
            RemoteImage(poster, Modifier.matchParentSize().blur(30.dp).graphicsLayer { alpha = 0.7f })
            RemoteImage(
                poster,
                Modifier.align(Alignment.CenterEnd).padding(end = 80.dp).fillMaxHeight().aspectRatio(2f / 3f)
            )
            Box(
                Modifier.matchParentSize().background(
                    Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.95f), 0.55f to Color.Black.copy(alpha = 0.4f), 1f to Color.Transparent)
                )
            )
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black)))
            Column(
                Modifier.align(Alignment.BottomStart).padding(start = Theme.edge, bottom = 28.dp).widthIn(max = 540.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(ContentLanguage.displayTitle(target.title), color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Black, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, lineHeight = 42.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    info?.imdbRating?.takeIf { it != "N/A" }?.let { ImdbBadge(it) }
                    Text(tags, color = Theme.secondary, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
                info?.plot?.takeIf { it.isNotBlank() && it != "N/A" }?.let {
                    Text(it, color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    NetflixButton(
                        title = L("Play"), icon = Icons.Default.PlayArrow, kind = NetflixKind.PLAY,
                        modifier = if (playFocus != null) Modifier.focusRequester(playFocus) else Modifier, onClick = onPlay
                    )
                    NetflixButton(title = L("More info"), icon = Icons.Outlined.Info, kind = NetflixKind.SECONDARY, onClick = onInfo)
                }
            }
        }
    } else if (LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp || it.screenWidthDp >= 600 }) {
        // build 131: liggend/breed scherm (tablet, foldable, telefoon liggend): breed billboard dat binnen de
        // schermhoogte past (max ~60%), poster rechts en tekst + knoppen links. De staande kaart werd hier 86% van de
        // schermbreedte en daardoor hoger dan het scherm.
        val cfg = LocalConfiguration.current
        val height = (cfg.screenHeightDp * 0.6f).dp.coerceIn(260.dp, 600.dp)
        val roomy = height >= 380.dp
        val shape = RoundedCornerShape(12.dp)
        Box(Modifier.fillMaxWidth().height(height)) {
            RemoteImage(poster, Modifier.matchParentSize().blur(50.dp).graphicsLayer { alpha = 0.65f })
            Box(
                Modifier.align(Alignment.BottomEnd)
                    .statusBarsPadding()
                    .padding(top = 72.dp, bottom = 20.dp, end = Theme.edge + 8.dp)
                    .fillMaxHeight().aspectRatio(2f / 3f)
                    .shadow(20.dp, shape)
                    .clip(shape)
                    .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
            ) {
                RemoteImage(poster, Modifier.matchParentSize(), placeholder = target.title)
            }
            Box(
                Modifier.matchParentSize().background(
                    Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.9f), 0.5f to Color.Black.copy(alpha = 0.35f), 0.75f to Color.Transparent)
                )
            )
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black)))
            Column(
                Modifier.align(Alignment.BottomStart)
                    .padding(start = Theme.edge, end = Theme.edge, bottom = 20.dp)
                    .fillMaxWidth(0.55f).widthIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    target.title.uppercase(), color = Color.White, fontSize = if (roomy) 34.sp else 26.sp, fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = if (roomy) 38.sp else 29.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    info?.imdbRating?.takeIf { it != "N/A" }?.let { ImdbBadge(it) }
                    Text(tags, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                if (roomy) {
                    info?.plot?.takeIf { it.isNotBlank() && it != "N/A" }?.let {
                        Text(it, color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NetflixButton(title = L("Play"), icon = Icons.Default.PlayArrow, kind = NetflixKind.PLAY,
                        modifier = Modifier.widthIn(min = 140.dp), onClick = onPlay)
                    NetflixButton(title = L("Info"), icon = Icons.Outlined.Info, kind = NetflixKind.SECONDARY,
                        modifier = Modifier.widthIn(min = 140.dp), onClick = onInfo)
                }
            }
        }
    } else {
        Box(Modifier.fillMaxWidth()) {
            RemoteImage(poster, Modifier.matchParentSize().blur(50.dp).graphicsLayer { alpha = 0.65f })
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black)))
            val shape = RoundedCornerShape(12.dp)
            Box(
                Modifier.align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 80.dp, bottom = 24.dp)
                    .fillMaxWidth(0.86f).widthIn(max = 330.dp).aspectRatio(330f / 470f)
                    .shadow(20.dp, shape)
                    .clip(shape)
                    .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
            ) {
                RemoteImage(poster, Modifier.matchParentSize(), placeholder = target.title)
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
                Column(
                    Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        target.title.uppercase(), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 31.sp
                    )
                    Text(tags, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NetflixButton(title = L("Play"), icon = Icons.Default.PlayArrow, kind = NetflixKind.PLAY,
                            modifier = Modifier.weight(1f), onClick = onPlay)
                        NetflixButton(title = L("Info"), icon = Icons.Outlined.Info, kind = NetflixKind.SECONDARY,
                            modifier = Modifier.weight(1f), onClick = onInfo)
                    }
                }
            }
        }
    }
}

@Composable
fun ImdbBadge(rating: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "IMDb", color = Color.Black, fontSize = tsp(10), fontWeight = FontWeight.Black,
            modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(Theme.imdb).padding(horizontal = 4.dp, vertical = 1.dp)
        )
        Text(rating, color = Color.White, fontSize = tsp(14), fontWeight = FontWeight.Bold)
    }
}


/** Kinderprofiel: eigen Top 10 met kids-titels. */
private fun kidsProfile(): Boolean = Store.activeProfile?.kids == true
