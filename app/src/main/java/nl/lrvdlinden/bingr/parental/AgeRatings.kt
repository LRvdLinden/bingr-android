package nl.lrvdlinden.bingr

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Leeftijdsinfo van één titel uit TMDB (build 53).
 * [age] = minimumleeftijd (Kijkwijzer eerst, anders VS/andere landen); null = geen classificatie bekend.
 * [genres] = TMDB-genre-id's; [found] = false als TMDB de titel niet kent (dan na 30 dagen opnieuw proberen).
 */
@Serializable
data class AgeRating(
    val tmdbID: Int? = null,
    val age: Int? = null,
    val genres: List<Int> = emptyList(),
    val found: Boolean = true,
    val checked: Long = 0,
)

/**
 * Leeftijdsclassificaties voor het kinderfilter (build 53).
 *
 * Waarom: het filter had alleen de OMDb-"Rated" uit [Metadata], en die werd pas opgehaald als je een titel opende.
 * Daardoor was van vrijwel de hele bibliotheek de leeftijd onbekend, en liet een kinderprofiel alles door wat in een
 * groep met een "kinderwoord" stond (ook Disney+-groepen met Marvel/Star Wars en "anime").
 *
 * Nu: per titel één keer TMDB opzoeken (id, genres, certificering per land), op schijf gecachet, en op de achtergrond
 * voor de hele bibliotheek bijgewerkt zodra een profiel met leeftijdsgrens actief is ([warm]).
 * [cached] is thread-veilig (ConcurrentHashMap) zodat het filteren op Dispatchers.Default het kan lezen.
 */
object AgeRatings {
    /** Verhoogt als er nieuwe classificaties binnen zijn (de bibliotheek wordt dan opnieuw gefilterd). */
    var version by mutableIntStateOf(0); private set

    private val cache = ConcurrentHashMap<String, AgeRating>()
    private val serializer = MapSerializer(String.serializer(), AgeRating.serializer())
    private val cacheFile: File get() = File(AppState.appContext.filesDir, "age-ratings.json")
    private var saveJob: Job? = null
    @Volatile private var loaded = false

    /** Niet gevonden bij TMDB: na zoveel tijd opnieuw proberen. */
    private const val RETRY_MS = 30L * 86_400_000L
    /** Maximaal zoveel opzoekingen per keer (de rest volgt bij de volgende start; de cache blijft bewaard). */
    private const val MAX_PER_RUN = 4000

    // TMDB-genres
    private val kidsGenres = setOf(16, 10751, 10762)            // animatie, familie, kids (tv)
    private val adultGenres = setOf(27, 53, 80, 10752, 10768)   // horror, thriller, misdaad, oorlog, oorlog & politiek (tv)

    fun key(title: String, year: Int?, isShow: Boolean): String = Metadata.key(title, year, isShow)

    fun cached(m: Movie): AgeRating? = cache[key(m.title, m.year, false)]
    fun cached(s: Series): AgeRating? = cache[key(s.title, s.year, true)]
    fun cached(t: DetailTarget): AgeRating? = when (t) {
        is DetailTarget.MovieT -> cached(t.movie)
        is DetailTarget.SeriesT -> cached(t.series)
    }

    /** Genres (TMDB-id's en/of OMDb-tekst) die op kinderen/familie/animatie wijzen. */
    fun isKidsGenre(info: AgeRating?, omdbGenre: String?): Boolean {
        if (info?.genres?.any { it in kidsGenres } == true) return true
        val g = omdbGenre?.lowercase() ?: return false
        return g.contains("animation") || g.contains("family")
    }

    /** Genres die niet bij kinderen horen (horror, thriller, misdaad, oorlog). */
    fun isAdultGenre(info: AgeRating?, omdbGenre: String?): Boolean {
        if (info?.genres?.any { it in adultGenres } == true) return true
        val g = omdbGenre?.lowercase() ?: return false
        return listOf("horror", "thriller", "crime", "war").any { w -> g.split(",").any { it.trim() == w } }
    }

    /** Cache van schijf laden (bij het starten, zodat het eerste filteren de bekende classificaties al heeft). */
    suspend fun preload() = ensureLoaded()

    private suspend fun ensureLoaded() {
        if (loaded) return
        val l = withContext(Dispatchers.IO) {
            runCatching { Http.json.decodeFromString(serializer, cacheFile.readText()) }.getOrNull()
        }
        if (!l.isNullOrEmpty()) l.forEach { (k, v) -> cache.putIfAbsent(k, v) }
        loaded = true
        if (!l.isNullOrEmpty()) withContext(Dispatchers.Main) { version++ }
    }

    /** Eén titel opzoeken (detailpagina); gecachet. */
    suspend fun lookup(t: DetailTarget): AgeRating? {
        ensureLoaded()
        return when (t) {
            is DetailTarget.MovieT -> fetch(t.movie.title, t.movie.year, false, t.movie.imdbID)
            is DetailTarget.SeriesT -> fetch(t.series.title, t.series.year, true, t.series.imdbID)
        }
    }

    private fun isFresh(r: AgeRating?): Boolean =
        r != null && (r.found || System.currentTimeMillis() - r.checked < RETRY_MS)

    private suspend fun fetch(title: String, year: Int?, isShow: Boolean, imdb: String?): AgeRating? {
        val k = key(title, year, isShow)
        cache[k]?.let { if (isFresh(it)) return it }
        // IPTV-rommel eraf ("NL | Frozen (2013) 4K" → "Frozen", 2013)
        val p = TitleCleaner.parse(title)
        val clean = p.title.ifBlank { title }
        val r = try { TMDB.ageInfo(imdb, clean, year ?: p.year, isShow) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            ?: return cache[k]   // netwerkfout: niets opslaan, later opnieuw
        cache[k] = r
        scheduleSave()
        return r
    }

    /**
     * Hele bibliotheek op de achtergrond bijwerken (alleen nodig bij een profiel met leeftijdsgrens).
     * Eerst titels uit kindergroepen (die zijn zonder classificatie zichtbaar en moeten zo snel mogelijk gecontroleerd),
     * daarna de rest, best beoordeelde eerst. Opnieuw filteren in batches: de kindergroepen meteen, daarna hooguit
     * eens per 20 s (build 56; eerst elke 40 titels, wat tijdens het scrollen steeds de hele bibliotheek opnieuw filterde).
     */
    suspend fun warm(raw: Library) {
        if (!Secrets.isSet(Secrets.tmdbAPIKey)) return
        ensureLoaded()
        data class Todo(val title: String, val year: Int?, val isShow: Boolean, val imdb: String?, val kids: Boolean, val rating: Double)
        val jobs = withContext(Dispatchers.Default) {
            val seen = HashSet<String>()
            val list = ArrayList<Todo>()
            for (m in raw.movies) {
                val k = key(m.title, m.year, false)
                if (!seen.add(k) || isFresh(cache[k])) continue
                list.add(Todo(m.title, m.year, false, m.imdbID, ParentalControl.isKidsGroup(m.group), m.rating ?: 0.0))
            }
            for (s in raw.series) {
                val k = key(s.title, s.year, true)
                if (!seen.add(k) || isFresh(cache[k])) continue
                list.add(Todo(s.title, s.year, true, s.imdbID, ParentalControl.isKidsGroup(s.group), s.rating ?: 0.0))
            }
            list.sortedWith(compareByDescending<Todo> { it.kids }.thenByDescending { it.rating }).take(MAX_PER_RUN)
        }
        if (jobs.isEmpty()) return
        var sinceBump = 0
        var lastBump = android.os.SystemClock.uptimeMillis()
        val kidsCount = jobs.count { it.kids }
        var done = 0
        // met 4 tegelijk (TMDB staat ruim 40 verzoeken per seconde toe; elke titel kost 2–3 verzoeken)
        for (chunk in jobs.chunked(4)) {
            coroutineScope {
                chunk.map { j -> async { fetch(j.title, j.year, j.isShow, j.imdb) } }.awaitAll()
            }
            sinceBump += chunk.size; done += chunk.size
            val now = android.os.SystemClock.uptimeMillis()
            val kidsDone = kidsCount in 1..done && done - chunk.size < kidsCount
            if (sinceBump > 0 && (kidsDone || (sinceBump >= 200 && now - lastBump >= 20_000))) {
                sinceBump = 0; lastBump = now
                withContext(Dispatchers.Main) { version++ }
            }
            // niet ophalen terwijl er gescrold wordt (netwerk + JSON kosten ook CPU)
            if (UserActivity.quietFor() < 800) UserActivity.awaitIdle(800)
            delay(60)
        }
        if (sinceBump > 0) withContext(Dispatchers.Main) { version++ }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = AppScope.launch(Dispatchers.IO) {
            delay(3_000)
            val snapshot = HashMap(cache)
            runCatching { cacheFile.writeText(Http.json.encodeToString(serializer, snapshot)) }
        }
    }
}
