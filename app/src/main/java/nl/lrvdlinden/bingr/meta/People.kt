package nl.lrvdlinden.bingr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Wie de personenpagina opent: TMDB-id als die bekend is, anders zoeken we op naam. */
data class PersonRef(val name: String, val tmdbID: Int? = null, val photo: String? = null)

/**
 * Personenpagina's (cast) en zoeken op naam via TMDB, in het geheugen bewaard (= PersonService.swift).
 * Koppelen aan de afspeellijst gaat met dezelfde [TitleMatcher] als Top 10/trending.
 */
object People {
    private val persons = ConcurrentHashMap<String, TMDB.Person>()
    private val credits = ConcurrentHashMap<Int, List<TMDB.Credit>>()
    private val searches = ConcurrentHashMap<String, List<TMDB.PersonHit>>()
    private val nameIDs = ConcurrentHashMap<String, Int>()

    val isAvailable: Boolean get() = Secrets.isSet(Secrets.tmdbAPIKey)

    /** Cast zonder TMDB-id: eerste treffer van /search/person. */
    suspend fun resolve(ref: PersonRef): Int? {
        ref.tmdbID?.let { return it }
        val k = ref.name.trim().lowercase()
        if (k.isEmpty()) return null
        nameIDs[k]?.let { return it }
        val id = TMDB.searchPeople(ref.name.trim()).firstOrNull()?.id ?: return null
        nameIDs[k] = id
        return id
    }

    suspend fun person(id: Int): TMDB.Person? {
        val k = "$id|${Localizer.language}"
        persons[k]?.let { return it }
        val p = TMDB.person(id) ?: return null
        persons[k] = p
        return p
    }

    suspend fun credits(id: Int): List<TMDB.Credit> {
        credits[id]?.let { return it }
        val c = TMDB.personCredits(id)
        if (c.isNotEmpty()) credits[id] = c
        return c
    }

    suspend fun search(query: String): List<TMDB.PersonHit> {
        val k = "${query.trim().lowercase()}|${Localizer.language}"
        searches[k]?.let { return it }
        val r = TMDB.searchPeople(query.trim())
        searches[k] = r
        return r
    }

    /** Wat ervan in je (gefilterde) afspeellijst staat, en de rest. */
    class Split(val inLibrary: List<Pair<TMDB.Credit, DetailTarget>>, val other: List<TMDB.Credit>)

    suspend fun split(all: List<TMDB.Credit>, lib: Library, matcherKey: String): Split {
        if (all.isEmpty()) return Split(emptyList(), emptyList())
        val matcher = screensSafe { MatcherCache.matcher(lib, matcherKey) } ?: return Split(emptyList(), all)
        return withContext(Dispatchers.Default) {
            val found = ArrayList<Pair<TMDB.Credit, DetailTarget>>()
            val other = ArrayList<TMDB.Credit>()
            val used = HashSet<String>()
            for (c in all) {
                val ext = ExternalTitle(c.titles.ifEmpty { listOf(c.title) }, c.year)
                val t: DetailTarget? = if (c.isShow) matcher.series(ext)?.let { DetailTarget.SeriesT(it) }
                else matcher.movie(ext)?.let { DetailTarget.MovieT(it) }
                if (t != null && used.add((if (t.isSeries) "s|" else "m|") + t.id)) found.add(c to t)
                else if (t == null) other.add(c)
            }
            Split(found, other)
        }
    }
}
