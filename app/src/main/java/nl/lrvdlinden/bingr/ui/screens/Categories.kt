package nl.lrvdlinden.bingr

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.Collator

/*
 * Categorieën (build 85), Netflix-stijl, = bingr.online en de Apple-apps.
 * Overzicht met tegels (naam, aantal, collage van posters op een kleurverloop per genre) in twee secties:
 * "Genres" (TMDB-genres, films en series samen) en "Van je server" (de VOD-/seriecategorieën van de bron, zoals die
 * na verbergen en kinderslot/leeftijdsgrens van dit profiel in Playlists.library staan).
 * Categoriepagina: kop met aantal, Alles/Films/Series, sorteren (Populair, Nieuwste, A–Z, Beoordeling) en een raster.
 * Wie in een genre hoort komt uit de caches die er al zijn: TMDB-genre-id's (AgeRatings) en de OMDb-genres (Metadata);
 * per bibliotheek + cacheversie één keer op de achtergrond uitgerekend ([CategoryIndexCache]).
 */

/** Eén TMDB-genre (film- en serie-id's samengevoegd) met de OMDb-namen die erbij horen en een eigen kleurverloop. */
data class GenreDef(
    val slug: String,
    /** Engelse naam = vertaalsleutel */
    val name: String,
    val movieIDs: Set<Int>,
    val tvIDs: Set<Int>,
    val omdb: Set<String>,
    val colors: List<Color>,
)

object Genres {
    private fun c(a: Long, b: Long) = listOf(Color(a), Color(b))

    /** Volgorde = volgorde in het overzicht (zelfde lijst als web en Apple). */
    val all: List<GenreDef> = listOf(
        GenreDef("action", "Action", setOf(28), setOf(10759), setOf("action"), c(0xFFB3121D, 0xFF3A0A0E)),
        GenreDef("adventure", "Adventure", setOf(12), setOf(10759), setOf("adventure"), c(0xFFD9822B, 0xFF4A2308)),
        GenreDef("comedy", "Comedy", setOf(35), setOf(35), setOf("comedy"), c(0xFFF2B705, 0xFF5C3A00)),
        GenreDef("drama", "Drama", setOf(18), setOf(18), setOf("drama"), c(0xFF6A4C93, 0xFF1E1233)),
        GenreDef("thriller", "Thriller", setOf(53), emptySet(), setOf("thriller"), c(0xFF37474F, 0xFF0B1114)),
        GenreDef("crime", "Crime", setOf(80), setOf(80), setOf("crime", "film-noir"), c(0xFF8D2F23, 0xFF1C0907)),
        GenreDef("scifi", "Science Fiction", setOf(878), setOf(10765), setOf("sci-fi", "science fiction"), c(0xFF1E88E5, 0xFF07203F)),
        GenreDef("fantasy", "Fantasy", setOf(14), setOf(10765), setOf("fantasy"), c(0xFF7B1FA2, 0xFF220833)),
        GenreDef("horror", "Horror", setOf(27), emptySet(), setOf("horror"), c(0xFF4A0D0D, 0xFF000000)),
        GenreDef("mystery", "Mystery", setOf(9648), setOf(9648), setOf("mystery"), c(0xFF283593, 0xFF0A0E2E)),
        GenreDef("romance", "Romance", setOf(10749), emptySet(), setOf("romance"), c(0xFFD81B60, 0xFF3F0A1F)),
        GenreDef("animation", "Animation", setOf(16), setOf(16), setOf("animation"), c(0xFF00ACC1, 0xFF06323A)),
        GenreDef("family", "Family", setOf(10751), setOf(10751), setOf("family"), c(0xFF43A047, 0xFF0F2E12)),
        GenreDef("kids", "Kids", emptySet(), setOf(10762), emptySet(), c(0xFFFFB300, 0xFF0277BD)),
        GenreDef("documentary", "Documentary", setOf(99), setOf(99), setOf("documentary"), c(0xFF5D7B6F, 0xFF16211C)),
        GenreDef("history", "History", setOf(36), emptySet(), setOf("history", "biography"), c(0xFF8D6E63, 0xFF2A1D18)),
        GenreDef("war", "War", setOf(10752), setOf(10768), setOf("war"), c(0xFF556B2F, 0xFF171E0B)),
        GenreDef("western", "Western", setOf(37), setOf(37), setOf("western"), c(0xFFBF6B2C, 0xFF3A1C07)),
        GenreDef("music", "Music", setOf(10402), emptySet(), setOf("music", "musical"), c(0xFFEC407A, 0xFF311B92)),
        GenreDef("reality", "Reality", emptySet(), setOf(10764), setOf("reality-tv"), c(0xFFFF7043, 0xFF3E1408)),
        GenreDef("soap", "Soap", emptySet(), setOf(10766), emptySet(), c(0xFFAB47BC, 0xFF2D0F33)),
    )
    val bySlug: Map<String, GenreDef> = all.associateBy { it.slug }
    private val byMovieID: Map<Int, List<String>> = all.flatMap { g -> g.movieIDs.map { it to g.slug } }.groupBy({ it.first }, { it.second })
    private val byTvID: Map<Int, List<String>> = all.flatMap { g -> g.tvIDs.map { it to g.slug } }.groupBy({ it.first }, { it.second })
    private val byOmdb: Map<String, List<String>> = all.flatMap { g -> g.omdb.map { it to g.slug } }.groupBy({ it.first }, { it.second })

    /** Genres van één titel uit de TMDB-id's en/of de OMDb-tekst ("Action, Sci-Fi"). Thread-veilig. */
    fun of(tmdb: List<Int>?, omdb: String?, isShow: Boolean, out: MutableSet<String>) {
        if (tmdb != null) for (id in tmdb) (if (isShow) byTvID[id] else byMovieID[id])?.let { out.addAll(it) }
        if (!omdb.isNullOrBlank()) for (p in omdb.split(',')) byOmdb[p.trim().lowercase()]?.let { out.addAll(it) }
    }

    /** Kleurverloop voor een categorie van de server: vast per naam. */
    fun serverColors(name: String): List<Color> {
        val palette = all.filter { it.slug != "horror" && it.slug != "thriller" }
        return palette[Math.floorMod(name.lowercase().hashCode(), palette.size)].colors
    }
}

/** Welke categorie: een genre of een categorie van de server (films en series met dezelfde groepsnaam samen). */
data class CategoryRef(val genre: String?, val server: String?) {
    val title: String get() = genre?.let { Genres.bySlug[it]?.name?.let { n -> L(n) } } ?: server ?: ""
}

/** De titels in één categorie, met per soort een paar posters voor de collage. */
class CategoryMembers(val movies: List<Movie>, val series: List<Series>) {
    private val previews = HashMap<Filter, List<String>>()

    fun count(f: Filter): Int = when (f) { Filter.ALL -> movies.size + series.size; Filter.MOVIES -> movies.size; Filter.SERIES -> series.size }

    /** Posters voor de collage: de best beoordeelde titels met een poster (één keer per soort uitgerekend). */
    fun posters(f: Filter): List<String> = synchronized(previews) {
        previews.getOrPut(f) {
            val list = ArrayList<Pair<String, Double>>()
            if (f != Filter.SERIES) for (m in movies) { val p = m.poster ?: continue; list.add(p to (m.rating ?: 0.0)) }
            if (f != Filter.MOVIES) for (s in series) { val p = s.poster ?: continue; list.add(p to (s.rating ?: 0.0)) }
            list.sortedByDescending { it.second }.asSequence().map { it.first }.distinct().take(4).toList()
        }
    }

    fun targets(f: Filter): List<DetailTarget> = buildList {
        if (f != Filter.SERIES) movies.forEach { add(DetailTarget.MovieT(it)) }
        if (f != Filter.MOVIES) series.forEach { add(DetailTarget.SeriesT(it)) }
    }
}

/** Eén tegel in de rij op Home. */
class CategoryTileData(val ref: CategoryRef, val title: String, val members: CategoryMembers, val colors: List<Color>) {
    val key: String get() = ref.genre?.let { "g|$it" } ?: ("s|" + (ref.server ?: ""))
}

class CategoryIndex(val genres: Map<String, CategoryMembers>, val server: List<Pair<String, CategoryMembers>>) {
    fun members(ref: CategoryRef): CategoryMembers? =
        ref.genre?.let { genres[it] } ?: ref.server?.let { s -> server.firstOrNull { it.first == s }?.second }

    /** Aantal categorieën in het overzicht (Alles): genres en servercategorieën met minstens één titel. */
    fun visibleCount(): Int = genres.values.count { it.count(Filter.ALL) > 0 } + server.count { it.second.count(Filter.ALL) > 0 }

    /**
     * Build 86: tegels voor de rij "Categorieën" op Home: de best gevulde genres eerst, aangevuld met de grootste
     * categorieën van de server als er minder dan [limit] genres zijn.
     */
    fun topTiles(limit: Int): List<CategoryTileData> {
        val out = ArrayList<CategoryTileData>()
        Genres.all.mapNotNull { g -> genres[g.slug]?.takeIf { it.count(Filter.ALL) > 0 }?.let { g to it } }
            .sortedByDescending { it.second.count(Filter.ALL) }
            .take(limit)
            .forEach { (g, m) -> out.add(CategoryTileData(CategoryRef(g.slug, null), L(g.name), m, g.colors)) }
        if (out.size < limit) server.filter { it.second.count(Filter.ALL) > 0 }
            .sortedByDescending { it.second.count(Filter.ALL) }
            .take(limit - out.size)
            .forEach { (name, m) -> out.add(CategoryTileData(CategoryRef(null, name), ContentLanguage.displayTitle(name), m, Genres.serverColors(name))) }
        return out
    }

    companion object {
        fun build(lib: Library): CategoryIndex {
            val gm = HashMap<String, MutableList<Movie>>()
            val gs = HashMap<String, MutableList<Series>>()
            val tmp = HashSet<String>()
            for (m in lib.movies) {
                tmp.clear()
                Genres.of(AgeRatings.cached(m)?.genres, Metadata.cached(m)?.genre, false, tmp)
                for (g in tmp) gm.getOrPut(g) { ArrayList() }.add(m)
            }
            for (s in lib.series) {
                tmp.clear()
                Genres.of(AgeRatings.cached(s)?.genres, Metadata.cached(s)?.genre, true, tmp)
                for (g in tmp) gs.getOrPut(g) { ArrayList() }.add(s)
            }
            val genres = HashMap<String, CategoryMembers>()
            for (g in Genres.all) {
                val mv = gm[g.slug].orEmpty(); val se = gs[g.slug].orEmpty()
                if (mv.isNotEmpty() || se.isNotEmpty()) genres[g.slug] = CategoryMembers(mv, se)
            }
            // categorieën van de server in de volgorde van de bron (films eerst, dan series met een nieuwe naam)
            val sm = LinkedHashMap<String, MutableList<Movie>>()
            val ss = LinkedHashMap<String, MutableList<Series>>()
            val order = LinkedHashSet<String>()
            for (m in lib.movies) { if (m.group.isBlank()) continue; sm.getOrPut(m.group) { ArrayList() }.add(m); order.add(m.group) }
            for (s in lib.series) { if (s.group.isBlank()) continue; ss.getOrPut(s.group) { ArrayList() }.add(s); order.add(s.group) }
            val server = order.map { it to CategoryMembers(sm[it].orEmpty(), ss[it].orEmpty()) }
            return CategoryIndex(genres, server)
        }
    }
}

/** Eén index per bibliotheek + cacheversie (Metadata/AgeRatings); de vorige blijft bruikbaar tot de nieuwe klaar is. */
object CategoryIndexCache {
    private var key: String? = null
    /** build 97: de bibliotheek van [data] zelf (identiteit), niet zijn identityHashCode (die kan botsen) */
    private var dataLib = java.lang.ref.WeakReference<Library>(null)
    private var data: CategoryIndex? = null
    private var warmedFor: Int = 0
    /** build 97: vast volgnummer per bibliotheek-object, voor een stabiele sleutel */
    private var genLib = java.lang.ref.WeakReference<Library>(null)
    private var gen = 0L
    /** build 97: één berekening per sleutel tegelijk; wie dezelfde index vraagt, wacht op dezelfde berekening */
    private val lock = Mutex()
    private var inflight: Pair<String, Deferred<CategoryIndex>>? = null

    private fun genOf(lib: Library): Long {
        if (genLib.get() !== lib) { genLib = java.lang.ref.WeakReference(lib); gen++ }
        return gen
    }

    private fun keyOf(lib: Library) = "${genOf(lib)}|${AgeRatings.version}|${Metadata.version}"

    /** Laatst berekende index van deze bibliotheek (ook als de caches intussen nieuwer zijn). */
    fun peek(lib: Library): CategoryIndex? = if (dataLib.get() === lib) data else null

    suspend fun get(lib: Library): CategoryIndex {
        val (k, job) = lock.withLock {
            val k = keyOf(lib)
            data?.let { if (key == k && dataLib.get() === lib) return it }
            val running = inflight?.takeIf { it.first == k && !it.second.isCancelled }?.second
            k to (running ?: AppScope.async(Dispatchers.Default) { CategoryIndex.build(lib) }.also { inflight = k to it })
        }
        // een mislukte berekening telt als geannuleerd (isCancelled) en wordt de volgende keer opnieuw gestart
        val built = job.await()
        lock.withLock { key = k; dataLib = java.lang.ref.WeakReference(lib); data = built }
        return built
    }

    /**
     * Genres van de hele bibliotheek op de achtergrond bij TMDB opzoeken (dezelfde cache als het kinderfilter,
     * build 53). Bij een profiel met leeftijdsgrens loopt dat al; anders één keer per bibliotheek als je Categorieën opent.
     */
    fun warm(lib: Library) {
        if (Store.activeProfile?.effectiveAgeLimit != null) return
        val id = System.identityHashCode(lib)
        if (warmedFor == id) return
        warmedFor = id
        AppScope.launch { screensSafe { AgeRatings.warm(lib) } }
    }
}

/** De index als Compose-state; na nieuwe genres (cacheversie) rustig opnieuw (hooguit eens per 1,5 s). */
@Composable
fun rememberCategoryIndex(): CategoryIndex? {
    val lib = Playlists.library
    val libKey = System.identityHashCode(lib)
    var idx by remember(libKey) { mutableStateOf(CategoryIndexCache.peek(lib)) }
    val versions = AgeRatings.version to Metadata.version
    LaunchedEffect(libKey) { CategoryIndexCache.warm(lib) }
    LaunchedEffect(libKey, versions) {
        if (idx != null) delay(1_500)
        // build 97: een fout bij het opbouwen laat het scherm niet crashen (de vorige index blijft staan)
        screensSafe { CategoryIndexCache.get(lib) }?.let { idx = it }
    }
    return idx
}

// MARK: overzicht

/**
 * Overzicht "Categorieën". [asTab] = tabblad (niet meer gebruikt sinds build 86); anders een scherm erboven,
 * geopend via Mijn Bingr → Categorieën of de rij "Categorieën" op Home.
 */
@Composable
fun CategoriesScreen(initial: Filter, asTab: Boolean = false, onClose: () -> Unit = {}) {
    val tv = AppState.isTV
    if (!asTab) BackHandler { onClose() }
    var kind by remember { mutableStateOf(initial) }
    val index = rememberCategoryIndex()
    val firstFocus = rememberInitialFocus(enabled = tv && !asTab)
    val genres = remember(index, kind) {
        index?.let { i -> Genres.all.mapNotNull { g -> i.genres[g.slug]?.takeIf { it.count(kind) > 0 }?.let { g to it } } }.orEmpty()
    }
    val server = remember(index, kind) { index?.server?.filter { it.second.count(kind) > 0 }.orEmpty() }

    Column(Modifier.fillMaxSize().background(Theme.bg).then(if (tv) Modifier else Modifier.statusBarsPadding())) {
        Row(
            Modifier.fillMaxWidth().padding(start = Theme.edge - (if (asTab) 0.dp else tvs(6)), end = Theme.edge, top = tvs(8)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(tvs(10))
        ) {
            if (!tv && !asTab) {
                Box(Modifier.size(40.dp).focusLift { onClose() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, L("Back"), tint = Color.White, modifier = Modifier.size(24.dp))
                }
            }
            Text(L("Categories"), color = Color.White, fontSize = tsp(if (tv) 26 else 24), fontWeight = FontWeight.Black, maxLines = 1)
        }
        Spacer(Modifier.height(tvs(10)))
        KindSegments(kind, Modifier.padding(horizontal = Theme.edge), focus = if (asTab) null else firstFocus) { kind = it }
        Spacer(Modifier.height(tvs(8)))

        if (index == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Theme.red, modifier = Modifier.size(tvs(32)))
            }
            return@Column
        }
        LazyVerticalGrid(
            columns = if (tv) GridCells.Fixed(4) else GridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(start = Theme.edge, end = Theme.edge, top = tvs(8), bottom = tvs(40)),
            horizontalArrangement = Arrangement.spacedBy(if (tv) 22.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(if (tv) 22.dp else 10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            if (genres.isNotEmpty()) {
                item(key = "h-genres", span = { GridItemSpan(maxLineSpan) }) { SectionTitle(L("Genres")) }
                items(genres, key = { "g|" + it.first.slug }) { (g, m) ->
                    CategoryTile(L(g.name), m.count(kind), m.posters(kind), g.colors) {
                        ScreensNav.push(ScreenEntry.CategoryPage(CategoryRef(g.slug, null), kind))
                    }
                }
            }
            if (server.isNotEmpty()) {
                item(key = "h-server", span = { GridItemSpan(maxLineSpan) }) { SectionTitle(L("From your server")) }
                items(server, key = { "s|" + it.first }) { (name, m) ->
                    CategoryTile(ContentLanguage.displayTitle(name), m.count(kind), m.posters(kind), Genres.serverColors(name)) {
                        ScreensNav.push(ScreenEntry.CategoryPage(CategoryRef(null, name), kind))
                    }
                }
            }
            if (genres.isEmpty() && server.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                Text(L("No titles in this category yet."), color = Theme.secondary, fontSize = tsp(14), modifier = Modifier.padding(top = 30.dp))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Color.White, fontSize = tsp(17), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = tvs(8), bottom = 2.dp))
}

/** Alles / Films / Series als pillen (= web en Apple). */
@Composable
private fun KindSegments(
    selected: Filter, modifier: Modifier = Modifier, focus: androidx.compose.ui.focus.FocusRequester? = null,
    available: Set<Filter> = setOf(Filter.ALL, Filter.MOVIES, Filter.SERIES), onSelect: (Filter) -> Unit,
) {
    val options = listOf(Filter.ALL to L("All"), Filter.MOVIES to L("Movies"), Filter.SERIES to L("Series")).filter { it.first in available }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(tvs(8))) {
        options.forEach { (f, label) ->
            Pill(label, f == selected, if (f == selected) focus else null) { onSelect(f) }
        }
    }
}

@Composable
private fun Pill(label: String, selected: Boolean, focus: androidx.compose.ui.focus.FocusRequester? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Text(
        label,
        color = if (selected || focused) Color.Black else Color.White,
        fontSize = tsp(13), fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .focusLift(1.06f) { onClick() }
            .clip(shape)
            .background(if (selected || focused) Color.White else Color.Transparent)
            .border(1.dp, if (selected || focused) Color.White else Color.White.copy(alpha = 0.45f), shape)
            .padding(horizontal = tvs(14), vertical = tvs(6))
    )
}

/** Tegel: kleurverloop per genre, een waaier van posters rechts, naam en aantal linksonder. Op tv groter met witte rand. */
@Composable
private fun CategoryTile(
    title: String, count: Int, posters: List<String>, colors: List<Color>,
    modifier: Modifier = Modifier.fillMaxWidth(), compact: Boolean = false, onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(tvs(if (compact) 8 else 10))
    BoxWithConstraints(
        modifier.aspectRatio(16f / 9f)
            .onFocusChanged { focused = it.isFocused }
            .focusLift(1.06f) { onClick() }
            .clip(shape)
            .background(Brush.linearGradient(colors))
            .then(if (focused) Modifier.border(2.5.dp, Color.White, shape) else Modifier)
    ) {
        val h = maxHeight * 0.80f
        val w = h * (2f / 3f)
        // achterste poster eerst; de voorste staat het meest rechts
        posters.take(4).reversed().forEachIndexed { ri, url ->
            val i = posters.take(4).size - 1 - ri
            Box(
                Modifier.align(Alignment.CenterEnd)
                    .offset(x = -(maxWidth * 0.04f) - w * 0.52f * i, y = maxHeight * 0.06f)
                    .zIndex((4 - i).toFloat())
                    .rotate(4f - i * 3.5f)
                    .shadow(10.dp, RoundedCornerShape(6.dp))
                    .size(w, h).clip(RoundedCornerShape(6.dp))
            ) { RemoteImage(url, Modifier.matchParentSize()) }
        }
        // leesbaarheid van de tekst links
        Box(
            Modifier.matchParentSize().zIndex(10f).background(
                Brush.horizontalGradient(0f to colors.first().copy(alpha = 0.92f), 0.55f to colors.first().copy(alpha = 0.35f), 0.8f to Color.Transparent)
            )
        )
        Column(Modifier.align(Alignment.BottomStart).zIndex(11f).padding(tvs(if (compact) 10 else 12))) {
            Text(
                title, color = Color.White, fontSize = tsp(if (compact) 15 else 17), fontWeight = FontWeight.Black, maxLines = 2,
                overflow = TextOverflow.Ellipsis, lineHeight = tsp(if (compact) 17 else 19), modifier = Modifier.fillMaxWidth(0.62f)
            )
            Text(countText(count), color = Color.White.copy(alpha = 0.8f), fontSize = tsp(if (compact) 11 else 12), fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

private fun countText(n: Int): String = if (n == 1) L("1 title") else L("%d titles", n)

// MARK: categoriepagina

private enum class GenreSort(val raw: String) {
    POPULAR("popular"), NEWEST("newest"), AZ("az"), RATING("rating");

    val title: String
        get() = when (this) {
            POPULAR -> L("Popular")
            NEWEST -> L("Newest")
            AZ -> L("A–Z")
            RATING -> L("Rating")
        }
}

private fun imdbVotes(t: DetailTarget): Long? {
    val md = when (t) { is DetailTarget.MovieT -> Metadata.cached(t.movie); is DetailTarget.SeriesT -> Metadata.cached(t.series) }
    return md?.imdbVotes?.filter { it.isDigit() }?.toLongOrNull()
}

private fun sortRating(t: DetailTarget): Double {
    val md = when (t) { is DetailTarget.MovieT -> Metadata.cached(t.movie); is DetailTarget.SeriesT -> Metadata.cached(t.series) }
    return md?.imdbRating?.toDoubleOrNull() ?: t.rating?.takeIf { it > 0 } ?: -1.0
}

/**
 * build 97: sorteren met vooraf berekende sleutels (één keer per titel, niet bij elke vergelijking). Draait op
 * Dispatchers.Default; leest alleen de caches (Metadata.cached), net als [CategoryIndex.build].
 */
private fun sortTitles(list: List<DetailTarget>, sort: GenreSort, locale: java.util.Locale): List<DetailTarget> = when (sort) {
    // populair: aantal IMDb-stemmen, dan de score; zonder gegevens de volgorde van de bron
    GenreSort.POPULAR -> list.map { Triple(it, imdbVotes(it) ?: -1L, sortRating(it)) }
        .sortedWith(compareByDescending<Triple<DetailTarget, Long, Double>> { it.second }.thenByDescending { it.third })
        .map { it.first }
    GenreSort.NEWEST -> list.sortedWith(
        compareByDescending<DetailTarget> { (it as? DetailTarget.MovieT)?.movie?.addedAt ?: Long.MIN_VALUE }
            .thenByDescending { it.year ?: Int.MIN_VALUE }
    )
    GenreSort.AZ -> {
        val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
        list.map { it to collator.getCollationKey(ContentLanguage.displayTitle(it.title)) }
            .sortedWith { a, b -> a.second.compareTo(b.second) }
            .map { it.first }
    }
    GenreSort.RATING -> list.map { it to sortRating(it) }.sortedByDescending { it.second }.map { it.first }
}

/** Een genre of servercategorie: kop met aantal, Alles/Films/Series, sorteren en een raster met posters. */
@Composable
fun CategoryPageScreen(ref: CategoryRef, initial: Filter, onClose: () -> Unit) {
    val tv = AppState.isTV
    BackHandler { onClose() }
    val index = rememberCategoryIndex()
    val members = index?.members(ref)
    val available = remember(members) {
        buildSet {
            add(Filter.ALL)
            if ((members?.movies?.size ?: 0) > 0) add(Filter.MOVIES)
            if ((members?.series?.size ?: 0) > 0) add(Filter.SERIES)
        }
    }
    var kindState by remember { mutableStateOf(initial) }
    val kind = if (kindState in available) kindState else Filter.ALL
    var sortRaw by remember { mutableStateOf(Prefs.string("categories.sort") ?: GenreSort.POPULAR.raw) }
    val sort = GenreSort.entries.firstOrNull { it.raw == sortRaw } ?: GenreSort.POPULAR
    // build 97: nieuwe metadata (stemmen, scores) hooguit eens per 2 s opnieuw sorteren, en het sorteren zelf buiten de
    // main thread met vooraf berekende sorteersleutels. De vorige volgorde blijft staan tot de nieuwe klaar is; de
    // items houden hun key, dus de scrollpositie blijft waar hij was.
    var sortVersion by remember { mutableStateOf(Metadata.version) }
    LaunchedEffect(Unit) {
        snapshotFlow { Metadata.version }.collect { v ->
            if (v != sortVersion) sortVersion = v
            delay(2_000)
        }
    }
    val locale = Localizer.locale
    val sorted by produceState<List<DetailTarget>?>(null, members, kind, sort, sortVersion, locale) {
        value = withContext(Dispatchers.Default) { sortTitles(members?.targets(kind).orEmpty(), sort, locale) }
    }
    val titles = sorted.orEmpty()
    val firstFocus = rememberInitialFocus(key = ref)
    val genre = ref.genre?.let { Genres.bySlug[it] }
    val colors = genre?.colors ?: Genres.serverColors(ref.server ?: "")

    Box(Modifier.fillMaxSize().background(Theme.bg)) {
        // zachte gloed in de kleur van het genre bovenin
        Box(
            Modifier.fillMaxWidth().height(tvs(220))
                .background(Brush.verticalGradient(listOf(colors.first().copy(alpha = 0.55f), Color.Transparent)))
        )
        LazyVerticalGrid(
            columns = if (tv) GridCells.Fixed(6) else GridCells.Adaptive(110.dp),
            contentPadding = PaddingValues(start = Theme.edge, end = Theme.edge, bottom = tvs(40)),
            horizontalArrangement = Arrangement.spacedBy(if (tv) 24.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(tvs(16)),
            modifier = Modifier.fillMaxSize().then(if (tv) Modifier else Modifier.statusBarsPadding())
        ) {
            item(key = "head", span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth().padding(top = tvs(8))) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(tvs(8))) {
                        if (!tv) {
                            Box(Modifier.size(40.dp).offset(x = (-8).dp).focusLift { onClose() }, contentAlignment = Alignment.Center) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, L("Back"), tint = Color.White, modifier = Modifier.size(24.dp))
                            }
                        }
                        Text(
                            ref.title.let { if (ref.server != null) ContentLanguage.displayTitle(it) else it },
                            color = Color.White, fontSize = tsp(if (tv) 30 else 26), fontWeight = FontWeight.Black,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                    Text(countText(titles.size), color = Theme.secondary, fontSize = tsp(13), modifier = Modifier.padding(top = 2.dp))
                    Spacer(Modifier.height(tvs(12)))
                    if (available.size > 2) {
                        KindSegments(kind, focus = firstFocus, available = available) { kindState = it }
                        Spacer(Modifier.height(tvs(10)))
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(tvs(8))) {
                        items(GenreSort.entries.toList(), key = { it.raw }) { s ->
                            Pill(s.title, s == sort, if (available.size <= 2 && s == sort) firstFocus else null) {
                                sortRaw = s.raw
                                Prefs.put("categories.sort", s.raw)
                            }
                        }
                    }
                }
            }
            if (index == null) {
                item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Theme.red, modifier = Modifier.size(tvs(32)))
                    }
                }
            } else if (sorted == null) {
                item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Theme.red, modifier = Modifier.size(tvs(32)))
                    }
                }
            } else if (titles.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    Text(L("No titles in this category yet."), color = Theme.secondary, fontSize = tsp(14), modifier = Modifier.padding(top = 30.dp))
                }
            }
            itemsIndexed(titles, key = { _, t -> (if (t.isSeries) "s:" else "m:") + t.id }) { _, t ->
                CategoryPoster(t)
            }
        }
    }
}

/** Poster in het raster met dezelfde labels als "Bekijk allemaal", en lang indrukken / menuknop voor acties. */
@Composable
private fun CategoryPoster(t: DetailTarget) {
    var menu by remember { mutableStateOf(false) }
    var collect by remember { mutableStateOf(false) }
    CategoryCell(t, Modifier.focusLift(1.06f, onLongClick = { menu = true }) { ScreensNav.detail(t) })
    if (menu) {
        val pid = Playlists.source?.id
        val actions = buildList {
            if (t is DetailTarget.MovieT && Store.activeProfile?.effectiveAgeLimit == null) {
                add(S2Action(L("Play"), Icons.Default.PlayArrow, primary = true) { AppState.play(Playables.movie(t.movie, Trakt.imdb(t.movie))) })
            }
            add(S2Action(L("More info"), Icons.Default.Info) { ScreensNav.detail(t) })
            if (t is DetailTarget.MovieT) {
                val imdb = Trakt.imdb(t.movie)
                val watched = Playables.isWatched(t.movie, imdb)
                add(S2Action(if (watched) L("Mark as unwatched") else L("Mark as watched"),
                    if (watched) Icons.Default.VisibilityOff else Icons.Default.CheckCircle) {
                    Store.setLocalWatched(t.movie.id, !watched)
                    AppScope.launch { screensSafe { Trakt.setWatched(Playables.movie(t.movie, imdb), !watched) } }
                })
            }
            if (pid != null) {
                val inList = Store.inMyList(pid, t.id)
                add(S2Action(if (inList) L("Remove from My List") else L("Add to My List"),
                    PlusCheck.icon(inList)) { Store.setMyList(pid, t.id, !inList) })
            }
            add(S2Action(L("Add to collection…"), Icons.AutoMirrored.Filled.PlaylistAdd) { collect = true })
        }
        S2ActionDialog(ContentLanguage.displayTitle(t.title), actions, onDismiss = { menu = false })
    }
    if (collect) AddToCollectionDialog(Collections.ref(t), t.title, t.poster) { collect = false }
}

// MARK: rij op Home (build 86)

/**
 * Rij "Categorieën" op Home (build 86): kleinere tegels (kleurverloop, waaier van posters, naam en aantal) voor de
 * best gevulde categorieën, als laatste tegel "Alle categorieën" naar het overzicht. Staat vijf rijen onder "Nu live"
 * (zie HomeScreen); aan/uit per profiel via Mijn Bingr → Startscherm (sleutel [HomeLayouts.CATEGORY_TILES]).
 */
@Composable
fun CategoriesHomeRow() {
    val index = rememberCategoryIndex() ?: return
    val tiles = remember(index) { index.topTiles(10) }
    if (tiles.isEmpty()) return
    val total = remember(index) { index.visibleCount() }
    val openAll = { ScreensNav.push(ScreenEntry.Categories(Filter.ALL)) }
    val width = tvs(if (AppState.isTV) 190 else 200)
    MediaRow(L("Categories"), onSeeAll = openAll) {
        items(tiles, key = { it.key }) { t ->
            CategoryTile(t.title, t.members.count(Filter.ALL), t.members.posters(Filter.ALL), t.colors, Modifier.width(width), compact = true) {
                ScreensNav.push(ScreenEntry.CategoryPage(t.ref, Filter.ALL))
            }
        }
        item(key = "all") { AllCategoriesTile(total, Modifier.width(width), onClick = openAll) }
    }
}

/** Laatste tegel van de rij: "Alle categorieën" met het aantal, naar het overzicht. */
@Composable
private fun AllCategoriesTile(count: Int, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(tvs(8))
    Box(
        modifier.aspectRatio(16f / 9f)
            .onFocusChanged { focused = it.isFocused }
            .focusLift(1.06f) { onClick() }
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF3A3A3A), Color(0xFF141414))))
            .border(if (focused) 2.5.dp else 1.dp, if (focused) Color.White else Color.White.copy(alpha = 0.18f), shape),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(tvs(4))) {
            Icon(Icons.Default.GridView, null, tint = Color.White, modifier = Modifier.size(tvs(26)))
            Text(L("All categories"), color = Color.White, fontSize = tsp(15), fontWeight = FontWeight.Black, maxLines = 1)
            if (count > 0) Text(
                if (count == 1) L("1 category") else L("%d categories", count),
                color = Color.White.copy(alpha = 0.7f), fontSize = tsp(11), maxLines = 1
            )
        }
    }
}
