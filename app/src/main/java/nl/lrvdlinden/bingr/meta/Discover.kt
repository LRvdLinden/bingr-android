package nl.lrvdlinden.bingr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// MARK: Home: "Binnenkort op Bingr" en "Nieuw op Bingr" (TMDB) — build 111 (namen build 112), = Discover.swift
//
// Twee rijen op Home met titels uit TMDB: wat binnenkort uitkomt en wat net nieuw is op streamingdiensten in de regio
// (TMDB watch providers, gegevens van JustWatch). Nooit namen of logo's van streamingdiensten: we vragen alleen
// "flatrate" op, zonder de aanbieders te tonen. Staat een titel in je bibliotheek (titel + jaar), dan opent die; anders
// een detailpagina met Mijn lijst. Build 112: "Nieuw op Bingr" alleen met titels uit je bibliotheek; de bronvermelding
// (TMDB, JustWatch) staat in Instellingen → Over in plaats van onder de rijen. 12 uur bewaard; netwerk en koppelen op de achtergrond. Zelfde sleutels als iOS/web.

enum class DiscoverKind(val key: String) {
    UPCOMING("upcoming"), STREAMING("streaming");

    /** Getoonde naam (build 112: "Binnenkort op Bingr" / "Nieuw op Bingr"); de sleutels blijven upcoming/streaming */
    val title: String get() = if (this == UPCOMING) L("Coming soon to Bingr") else L("New on Bingr")
    /** Build 112: "Nieuw op Bingr" alleen met titels uit de eigen bibliotheek (meteen afspeelbaar) */
    val onlyLibrary: Boolean get() = this == STREAMING

    companion object {
        fun of(key: String): DiscoverKind? = entries.firstOrNull { it.key == key }
        /** Build 112: bronvermelding van TMDB en JustWatch, één keer in Instellingen → Over (niet meer onder de rijen) */
        val attribution: String
            get() = L("Movie and series data: TMDB. This app uses the TMDB API but is not endorsed or certified by TMDB. Availability data: JustWatch.")
    }
}

/** Eén titel uit TMDB (niet per se in je bibliotheek). */
@Serializable
data class DiscoverItem(
    val tmdbID: Int,
    val isShow: Boolean,
    val title: String,
    val originalTitle: String? = null,
    /** yyyy-MM-dd */
    val date: String? = null,
    val posterPath: String? = null,
    val overview: String? = null,
    /** "upcoming" of "streaming" */
    val kind: String = "upcoming",
) {
    /** Id van de detailpagina en in Mijn lijst: "tmdb-movie-123" / "tmdb-tv-123" (zelfde op alle platforms) */
    val id: String get() = externalID(tmdbID, isShow)
    val year: Int? get() = date?.take(4)?.toIntOrNull()
    val poster: String? get() = TMDB.image(posterPath, "w342")
    val releaseDate: Date? get() = date?.let { runCatching { dayFormat().parse(it.take(10)) }.getOrNull() }

    /**
     * Korte datum op de poster ("17 okt", "Oct 17"; in de app-taal), alleen bij Binnenkort op Bingr. Build 112: als rood
     * label linksboven (PosterLabel, zoals SERIE/FILM), zonder punt achter de afgekorte maand.
     */
    val dateLabel: String?
        get() {
            if (kind != DiscoverKind.UPCOMING.key) return null
            val d = releaseDate ?: return null
            val loc = Localizer.locale
            val pattern = android.text.format.DateFormat.getBestDateTimePattern(loc, "dMMM")
            return Build149Logic.Dates.fmt(pattern, loc).format(d).trim().removeSuffix(".")
        }

    /** Detailpagina voor een titel die niet in je bibliotheek staat (zoals de aanbevelingen in DetailScreen) */
    val target: DetailTarget
        get() = if (isShow) DetailTarget.SeriesT(Series(id = id, title = title, year = year, poster = poster, plot = overview))
        else DetailTarget.MovieT(Movie(id = id, title = title, year = year, poster = poster, plot = overview))

    val external: ExternalTitle get() = ExternalTitle(listOfNotNull(title, originalTitle).filter { it.isNotEmpty() }.distinct(), year)

    companion object {
        const val PREFIX = "tmdb-"
        fun externalID(id: Int, isShow: Boolean) = "$PREFIX${if (isShow) "tv" else "movie"}-$id"
        /** Titel die alleen uit TMDB komt (niet uit een afspeellijst)? */
        fun isExternal(id: String) = id.startsWith(PREFIX)
        fun parse(id: String): Pair<Int, Boolean>? {
            if (!id.startsWith(PREFIX)) return null
            val parts = id.removePrefix(PREFIX).split("-")
            if (parts.size != 2 || (parts[0] != "tv" && parts[0] != "movie")) return null
            val n = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
            return n to (parts[0] == "tv")
        }
        fun dayFormat() = Build149Logic.Dates.fmt("yyyy-MM-dd")  // build 149: één per thread
        fun day(d: Date): String = dayFormat().format(d)
    }
}

/** Eén kaart in een TMDB-rij: de titel en, als hij in je bibliotheek staat, die titel. */
data class DiscoverEntry(val item: DiscoverItem, val match: DetailTarget?) {
    val id: String get() = match?.let { (if (it.isSeries) "s:" else "m:") + it.id } ?: item.id
    val target: DetailTarget get() = match ?: item.target
}

/**
 * TMDB-titels die op Home stonden (of via Mijn lijst opgezocht zijn), zodat Mijn lijst ze kan tonen. Alleen op dit
 * apparaat (geen sync), maximaal 400; de oudste vallen eraf.
 */
object DiscoverKnown {
    @Serializable
    private data class Saved(val item: DiscoverItem, val seen: Long)

    private const val KEY = "discover.known"
    private val serializer = MapSerializer(String.serializer(), Saved.serializer())
    private var items: HashMap<String, Saved>? = null

    @Synchronized
    private fun loaded(): HashMap<String, Saved> {
        items?.let { return it }
        val d = Prefs.string(KEY)?.let { runCatching { Http.json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyMap()
        return HashMap(d).also { items = it }
    }

    @Synchronized
    fun item(id: String): DiscoverItem? = loaded()[id]?.item

    fun contains(id: String) = item(id) != null

    @Synchronized
    fun remember(list: List<DiscoverItem>) {
        if (list.isEmpty()) return
        val all = loaded()
        val now = System.currentTimeMillis()
        for (i in list) all[i.id] = Saved(i, now)
        if (all.size > 400) {
            val keep = all.entries.sortedByDescending { it.value.seen }.take(400).map { it.key }.toSet()
            all.keys.retainAll(keep)
        }
        Prefs.put(KEY, Http.json.encodeToString(serializer, all))
    }

    /** Sleutel uit Mijn lijst ("<bron>|tmdb-movie-123") → de TMDB-titel, als die bekend is. */
    fun item(forKey: String, playlist: String): DiscoverItem? {
        val p = "$playlist|"
        // server-id hoofdletterongevoelig (iOS schrijft hem met hoofdletters)
        if (forKey.length <= p.length || !forKey.startsWith(p, ignoreCase = true)) return null
        val id = forKey.substring(p.length)
        return if (DiscoverItem.isExternal(id)) item(id) else null
    }
    fun movie(forKey: String, playlist: String): Movie? =
        item(forKey, playlist)?.takeIf { !it.isShow }?.let { (it.target as? DetailTarget.MovieT)?.movie }
    fun series(forKey: String, playlist: String): Series? =
        item(forKey, playlist)?.takeIf { it.isShow }?.let { (it.target as? DetailTarget.SeriesT)?.series }
}

/** Haalt de TMDB-rijen op (12 uur bewaard per soort, taal, regio en kinderprofiel) en koppelt ze aan je bibliotheek. */
object DiscoverStore {
    @Serializable
    private data class Saved(val date: Long, val items: List<DiscoverItem>)

    private const val MAX_AGE = 12 * 3_600_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = HashMap<String, Deferred<List<DiscoverItem>>>()

    /**
     * Regio voor TMDB: die van de app-taal (nl → NL, de → DE, …); bij Engels of een andere taal de regio van het
     * apparaat, anders NL. Zelfde regel op alle platforms.
     */
    val region: String
        get() {
            val byLanguage = mapOf("nl" to "NL", "de" to "DE", "fr" to "FR", "es" to "ES", "it" to "IT", "pt" to "PT", "pl" to "PL", "tr" to "TR")
            byLanguage[Localizer.language]?.let { return it }
            val c = Locale.getDefault().country
            return if (c.length == 2 && c.all { it in 'A'..'Z' }) c else "NL"
        }

    private fun key(kind: DiscoverKind, kids: Boolean) =
        "discover.${kind.key}.${if (kids) "kids." else ""}${Localizer.language}.$region"

    private fun saved(kind: DiscoverKind, kids: Boolean): Saved? =
        Prefs.string(key(kind, kids))?.let { runCatching { Http.json.decodeFromString(Saved.serializer(), it) }.getOrNull() }

    /** Direct uit de cache (ook als die verlopen is), zonder netwerk; Binnenkort zonder wat intussen uit is. */
    fun cached(kind: DiscoverKind, kids: Boolean): List<DiscoverItem> {
        val items = saved(kind, kids)?.items ?: return emptyList()
        if (kind != DiscoverKind.UPCOMING) return items
        val today = DiscoverItem.day(Date())
        return items.filter { (it.date ?: "") > today }
    }

    suspend fun get(kind: DiscoverKind, kids: Boolean): List<DiscoverItem> {
        val s = saved(kind, kids)
        if (s != null && s.items.isNotEmpty() && System.currentTimeMillis() - s.date < MAX_AGE) return cached(kind, kids)
        val k = key(kind, kids)
        val t = synchronized(inFlight) { inFlight.getOrPut(k) { scope.async { fetch(kind, kids) } } }
        val items = try { t.await() } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() } finally {
            synchronized(inFlight) { if (inFlight[k] === t) inFlight.remove(k) }
        }
        if (items.isEmpty()) return cached(kind, kids)
        withContext(Dispatchers.IO) {
            Prefs.put(k, Http.json.encodeToString(Saved.serializer(), Saved(System.currentTimeMillis(), items)))
            DiscoverKnown.remember(items)
        }
        return items
    }

    private suspend fun page(path: String, q: List<Pair<String, String>>, show: Boolean, kind: DiscoverKind): List<DiscoverItem> {
        val r = TMDB.request(path, q) ?: return emptyList()
        return r.jList("results").mapNotNull { i ->
            if (i.jB("adult") == true) return@mapNotNull null
            val id = i.jI("id") ?: return@mapNotNull null
            val t = (if (show) i.jS("name") else i.jS("title")) ?: i.jS("title") ?: i.jS("name")
            if (t.isNullOrEmpty()) return@mapNotNull null
            val poster = i.jS("poster_path")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val o = if (show) i.jS("original_name") else i.jS("original_title")
            DiscoverItem(tmdbID = id, isShow = show, title = t, originalTitle = o?.takeIf { it != t },
                date = (if (show) i.jS("first_air_date") else i.jS("release_date"))?.takeIf { it.isNotEmpty() },
                posterPath = poster, overview = i.jS("overview")?.takeIf { it.isNotEmpty() }, kind = kind.key)
        }
    }

    /** TMDB-verzoeken voor een rij (films en series, populairste eerst). Kinderprofiel: animatie/familie (films t/m PG). */
    private suspend fun fetch(kind: DiscoverKind, kids: Boolean): List<DiscoverItem> {
        if (!Secrets.isSet(Secrets.tmdbAPIKey)) return emptyList()
        val lang = TMDB.appLanguage
        val reg = region
        val now = System.currentTimeMillis()
        fun d(days: Int) = DiscoverItem.day(Date(now + days * 86_400_000L))
        val today = d(0)
        val mq = mutableListOf("language" to lang, "region" to reg, "include_adult" to "false", "sort_by" to "popularity.desc", "page" to "1")
        val tq = mutableListOf("language" to lang, "include_adult" to "false", "sort_by" to "popularity.desc", "page" to "1")
        if (kids) {
            mq += listOf("with_genres" to "16|10751", "certification_country" to "US", "certification.lte" to "PG")
            tq += "with_genres" to "10762|16|10751"
        }
        val moviePath: String
        when (kind) {
            DiscoverKind.UPCOMING -> {
                if (kids) {
                    moviePath = "/discover/movie"
                    mq += listOf("with_release_type" to "2|3", "release_date.gte" to d(1), "release_date.lte" to d(120))
                } else moviePath = "/movie/upcoming"
                tq += listOf("first_air_date.gte" to d(1), "first_air_date.lte" to d(120))
            }
            DiscoverKind.STREAMING -> {
                moviePath = "/discover/movie"
                mq += listOf("watch_region" to reg, "with_watch_monetization_types" to "flatrate",
                    "release_date.gte" to d(-90), "release_date.lte" to today)
                tq += listOf("watch_region" to reg, "with_watch_monetization_types" to "flatrate",
                    "first_air_date.gte" to d(-120), "first_air_date.lte" to today)
            }
        }
        val (mv, tv) = coroutineScope {
            val a = async { page(moviePath, mq, false, kind) }
            val b = async { page("/discover/tv", tq, true, kind) }
            a.await() to b.await()
        }
        val upcoming = kind == DiscoverKind.UPCOMING
        // /movie/upcoming geeft ook titels van de afgelopen dagen
        val movies = mv.filter { if (upcoming) (it.date ?: "") > today else (it.date ?: "") <= today }
        val shows = tv.filter { if (upcoming) (it.date ?: "") > today else (it.date ?: "") <= today }
        // om en om (populairste eerst), geen dubbele
        val out = ArrayList<DiscoverItem>()
        val seen = HashSet<String>()
        for (i in 0 until maxOf(movies.size, shows.size)) {
            listOfNotNull(movies.getOrNull(i), shows.getOrNull(i)).forEach { if (seen.add(it.id)) out.add(it) }
        }
        val top = out.take(40)
        // Binnenkort: de 40 populairste, op datum (eerst wat het eerst uitkomt)
        return if (upcoming) top.sortedWith(compareBy({ it.date ?: "" }, { it.title })) else top
    }

    /**
     * Koppelen aan je bibliotheek (op de achtergrond, titel + jaar). [onlyLibrary]: profielen met een leeftijdsgrens zien
     * alleen wat in hun (gefilterde) bibliotheek staat, zoals de aanbevelingen op de detailpagina.
     */
    suspend fun match(items: List<DiscoverItem>, matcher: TitleMatcher?, onlyLibrary: Boolean, limit: Int = 30): List<DiscoverEntry> =
        withContext(Dispatchers.Default) {
            val out = ArrayList<DiscoverEntry>()
            val seen = HashSet<String>()
            for (i in items) {
                val m: DetailTarget? = when {
                    matcher == null -> null
                    i.isShow -> matcher.series(i.external)?.let { DetailTarget.SeriesT(it) }
                    else -> matcher.movie(i.external)?.let { DetailTarget.MovieT(it) }
                }
                if (onlyLibrary && m == null) continue
                val e = DiscoverEntry(i, m)
                if (seen.add(e.id)) out.add(e)
                if (out.size >= limit) break
            }
            out
        }

    /** TMDB-titels uit Mijn lijst die dit apparaat nog niet kent (bijv. op een ander apparaat toegevoegd) opzoeken. */
    suspend fun resolve(ids: List<String>): Boolean = withContext(Dispatchers.IO) {
        val found = ArrayList<DiscoverItem>()
        for (id in ids.distinct().take(20)) {
            if (DiscoverKnown.contains(id)) continue
            val (tid, show) = DiscoverItem.parse(id) ?: continue
            val f = TMDB.request(if (show) "/tv/$tid" else "/movie/$tid", listOf("language" to TMDB.appLanguage)) ?: continue
            val t = (if (show) f.jS("name") else f.jS("title")) ?: f.jS("title") ?: f.jS("name") ?: continue
            val o = if (show) f.jS("original_name") else f.jS("original_title")
            found += DiscoverItem(tmdbID = tid, isShow = show, title = t, originalTitle = o?.takeIf { it != t },
                date = if (show) f.jS("first_air_date") else f.jS("release_date"), posterPath = f.jS("poster_path"),
                overview = f.jS("overview"), kind = DiscoverKind.STREAMING.key)
        }
        DiscoverKnown.remember(found)
        found.isNotEmpty()
    }

    /** Regel op de detailpagina van een TMDB-titel: niet in je afspeellijst en de datum (build 112: zonder bronvermelding). */
    fun detailNote(id: String): String? {
        if (!DiscoverItem.isExternal(id)) return null
        val item = DiscoverKnown.item(id)
        var first = L("Not in your playlists")
        item?.releaseDate?.let { d ->
            val df = java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG, Localizer.locale)
            first += " · " + L("Release date: %@", df.format(d))
        }
        return first
    }
}
