package nl.lrvdlinden.bingr

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Build 179 (docs/ACCOUNT.md "Build 179"): beoordelingen (server-functie `ratings`, MDBList; punt 9), intro en samenvatting
 * via IntroDB (server-functie `introdb`; punt 10), duimen per profiel en de rij "Voor jou" (punt 11). Sleutels voor
 * MDBList/IntroDB staan alleen als function-secret op de server; zonder secret antwoordt de functie `ok: false` en blijft
 * alles zoals het was.
 */

/** `profile.<id>.ratings`: duim 1 / -1 / 0 (weggehaald), `at` in ms. */
@Serializable
data class Thumb179(val value: Int, val at: Long)

/** `profile.<id>.categorySort`: sortering (server/name/year/added/rating), `at` in ms. */
@Serializable
data class SortPref179(val value: String, val at: Long)

/** Kleine JSON-cache in cacheDir/meta179 (opnieuw op te halen). */
private object Cache179 {
    private val dir: File by lazy { File(AppState.appContext.cacheDir, "meta179").apply { mkdirs() } }
    private fun f(name: String) = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120) + ".json")
    fun read(name: String): Pair<Long, JsonElement>? = runCatching {
        val file = f(name)
        if (!file.exists()) return null
        file.lastModified() to Http.json.parseToJsonElement(file.readText())
    }.getOrNull()
    fun write(name: String, e: JsonElement) { runCatching { f(name).writeText(e.toString()) } }
}

// MARK: 9. Beoordelingen

object Ratings179 {
    data class R(val source: String, val text: String)

    private fun key(t: DetailTarget): Pair<String, JsonObject>? {
        val tmdb = when (t) { is DetailTarget.MovieT -> Watched.tmdb(t.movie); is DetailTarget.SeriesT -> Watched.tmdb(t.series) }
            ?.toIntOrNull()
        val imdb = when (t) { is DetailTarget.MovieT -> t.movie.imdbID; is DetailTarget.SeriesT -> t.series.imdbID }
            ?.takeIf { it.startsWith("tt") }
        val type = if (t.isSeries) "show" else "movie"
        return when {
            tmdb != null -> "$type-$tmdb" to buildJsonObject { put("type", type); put("tmdb", tmdb) }
            imdb != null -> "imdb-$imdb" to buildJsonObject { put("imdb", imdb) }
            else -> null
        }
    }

    /** Beoordelingen als korte tekst in de vaste volgorde; leeg zonder server of sleutel. Cache 7 dagen. */
    suspend fun load(t: DetailTarget): List<R> {
        val (k, body) = key(t) ?: return emptyList()
        val name = "ratings-$k"
        val cached = Cache179.read(name)
        val fresh = cached != null && System.currentTimeMillis() - cached.first < Build179Logic.Ratings.CACHE_DAYS * 86_400_000L
        val json = if (fresh) cached!!.second else Account.function179("ratings", body)?.also {
            if (it.jB("ok") == true) Cache179.write(name, it)
        } ?: cached?.second
        if (json == null || json.jB("ok") != true) return emptyList()
        val values = json.jList("ratings").mapNotNull { r -> r.jS("source")?.let { it to r.jD("value") } }.toMap()
        return Build179Logic.Ratings.ORDER.mapNotNull { src ->
            Build179Logic.Ratings.text(src, values[src])?.let { R(src, it) }
        }
    }

    fun label(source: String): String = if (source == "tomatoesaudience") L("Audience") else Build179Logic.Ratings.shortName(source)
}

// MARK: 10. IntroDB

object IntroDB179 {
    data class Seg(val intro: Build179Logic.Intro.Range?, val recap: Build179Logic.Intro.Range?, val credits: Double?)

    private val memory = java.util.concurrent.ConcurrentHashMap<String, Seg>()

    fun cached(item: PlayableItem): Seg? = keyOf(item)?.let { memory[it.first] }

    private fun keyOf(item: PlayableItem): Pair<String, JsonObject>? {
        if (item.type != MediaType.EPISODE) return null
        // build 186: zonder TMDB-id de serietitel (uit de bibliotheek via seriesID), het jaar en het imdb-id meesturen
        val series = item.seriesID?.let { sid -> runCatching { Playlists.library.series.firstOrNull { it.id == sid } }.getOrNull() }
        val tmdb = item.tmdbID?.toIntOrNull() ?: series?.tmdbID?.toIntOrNull()
        val (k, fields) = Build179Logic.Intro.request(tmdb, series?.imdbID ?: item.imdbID, series?.title,
            series?.year ?: item.year, item.season, item.episode) ?: return null
        return k to buildJsonObject {
            for ((f, v) in fields) when (v) { is Int -> put(f, v); else -> put(f, v.toString()) }
        }
    }

    private fun parse(o: JsonElement): Seg {
        fun r(k: String) = o.jO(k)?.let { Build179Logic.Intro.range(it.jD("start"), it.jD("end")) }
        return Seg(r("intro"), r("recap"), o.jO("credits")?.jD("start"))
    }

    /** Tijden van deze aflevering (cache 30 dagen, niet gevonden 1 dag); null = de eigen markeringen gebruiken. */
    suspend fun load(item: PlayableItem): Seg? {
        val (k, body) = keyOf(item) ?: return null
        memory[k]?.let { return it }
        val name = "introdb-$k"
        val cached = Cache179.read(name)
        val now = System.currentTimeMillis()
        if (cached != null && Build179Logic.Intro.fresh(cached.first, now, cached.second.jB("ok") != true)) {
            return if (cached.second.jB("ok") == true) parse(cached.second).also { memory[k] = it } else null
        }
        val json = Account.function179("introdb", body) ?: return cached?.second?.takeIf { it.jB("ok") == true }?.let { parse(it) }
        // `unavailable` (geen sleutel/fout) niet bewaren, `not_found` wel (1 dag)
        if (json.jB("ok") == true || json.jS("error") == "not_found") Cache179.write(name, json)
        if (json.jB("ok") != true) return null
        return parse(json).also { memory[k] = it }
    }
}

// MARK: 11. Duimen en Voor jou

object ForYou179 {
    var enabled: Boolean
        get() = Prefs.bool(Build179Logic.ForYou.PREF, true)
        set(v) = Prefs.put(Build179Logic.ForYou.PREF, v)

    fun key(t: DetailTarget): String {
        val sid = t.sourceID ?: Playlists.source?.id ?: ""
        return when (t) {
            is DetailTarget.MovieT -> Build179Logic.ForYou.key(false, Watched.tmdb(t.movie), sid, t.movie.id)
            is DetailTarget.SeriesT -> Build179Logic.ForYou.key(true, Watched.tmdb(t.series), sid, t.series.id)
        }
    }

    fun thumb(t: DetailTarget): Int = Store.thumb(key(t))

    /** Duim omhoog/omlaag; nog eens dezelfde = weghalen. */
    fun toggle(t: DetailTarget, value: Int) {
        val k = key(t)
        Store.setThumb(k, if (Store.thumb(k) == value) 0 else value)
    }

    private fun meta(t: DetailTarget): TitleMetadata? = when (t) {
        is DetailTarget.MovieT -> Metadata.cached(t.movie)
        is DetailTarget.SeriesT -> Metadata.cached(t.series)
    }

    /**
     * De rij "Voor jou" (`forYou`): kandidaten = titels van de (gefilterde) bibliotheek met bekende genres; [watched] =
     * wat dit profiel keek; [recLiked]/[recWatched] = hoe vaak een titel in de TMDB-aanbevelingen van titels met duim
     * omhoog / bekeken titels stond. Leeg = niets met score > 0.
     */
    fun rank(lib: Library, watched: List<DetailTarget>, recLiked: Map<String, Int>, recWatched: Map<String, Int>): List<DetailTarget> {
        val thumbs = Store.thumbs
        val liked = thumbs.filter { it.value.value == 1 }.keys
        val disliked = thumbs.filter { it.value.value == -1 }.keys
        val byKey = HashMap<String, DetailTarget>()
        val titles = ArrayList<Build179Logic.ForYou.Title>()
        fun add(t: DetailTarget, rating: Double?) {
            val m = meta(t)
            val genres = Build140Logic.Picks.genres(m?.genre)
            val cast = m?.actors?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() } ?: emptyList()
            val k = key(t)
            if (byKey.putIfAbsent(k, t) != null) return
            titles += Build179Logic.ForYou.Title(k, t.title, genres, cast, rating)
        }
        lib.movies.forEach { add(DetailTarget.MovieT(it), it.rating) }
        lib.series.forEach { add(DetailTarget.SeriesT(it), it.rating) }
        watched.forEach { w -> if (!byKey.containsKey(key(w))) add(w, w.rating) }
        val blocked = byKey.filter { (_, t) -> Store.isTitleBlocked(t.sourceID, t.isSeries, t.id) }.keys
        return Build179Logic.ForYou.rank(titles, liked, disliked, watched.map { key(it) }.toSet(), blocked, recLiked, recWatched)
            .mapNotNull { byKey[it] }
    }

    /** Titels met duim omhoog uit de bibliotheek (voor de TMDB-aanbevelingen). */
    fun likedTargets(lib: Library): List<DetailTarget> {
        val liked = Store.thumbs.filter { it.value.value == 1 }.toList().sortedByDescending { it.second.at }.map { it.first }.toSet()
        if (liked.isEmpty()) return emptyList()
        val out = ArrayList<DetailTarget>()
        for (m in lib.movies) { val t = DetailTarget.MovieT(m); if (key(t) in liked) out += t }
        for (s in lib.series) { val t = DetailTarget.SeriesT(s); if (key(t) in liked) out += t }
        return out
    }
}
