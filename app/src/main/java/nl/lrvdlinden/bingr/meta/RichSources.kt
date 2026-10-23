package nl.lrvdlinden.bingr

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement

// MARK: - Trakt: beschrijving, score, cast, aanbevelingen, afleveringen -------------

object TraktDetails {
    data class Result(
        val imdb: String?, val slug: String?, val overview: String?, val rating: Double?, val runtime: Int?,
        val genres: List<String> = emptyList(), val certification: String?, val trailer: String?,
        val poster: String?, val fanart: String?, val logo: String?,
        val cast: List<TitleDetails.CastMember> = emptyList(),
        val related: List<TitleDetails.Recommendation> = emptyList(),
        val episodes: Map<String, TitleDetails.EpisodeInfo> = emptyMap(),
    )

    /** Trakt-afbeelding: {"poster": ["walter-r2.trakt.tv/…"]} */
    fun image(images: JsonElement?, key: String): String? {
        val s = images.jStrings(key).firstOrNull() ?: return null
        return if (s.startsWith("http")) s else "https://$s"
    }

    private suspend fun get(path: String, query: List<Pair<String, String>>): JsonElement? =
        runCatching { Trakt.get(path, query, auth = false) }.getOrNull()

    suspend fun fetch(imdb: String?, title: String, year: Int?, isShow: Boolean): Result? {
        if (!Secrets.isSet(Secrets.traktClientID)) return null
        val type = if (isShow) "show" else "movie"
        fun media(hits: JsonElement?): JsonElement? = hits.jItems.firstOrNull()?.let { it.jO("movie") ?: it.jO("show") }
        var m: JsonElement? = null
        if (imdb != null) m = media(get("/search/imdb/$imdb", listOf("type" to type, "extended" to "full,images")))
        if (m == null) {
            val q = mutableListOf("query" to title, "extended" to "full,images", "limit" to "3")
            if (year != null) q.add("years" to year.toString())
            m = media(get("/search/$type", q))
        }
        val media = m ?: return null
        val ids = media.jO("ids")
        val slug = ids.jS("slug") ?: ids.jS("trakt") ?: return null
        val images = media.jO("images")
        val path = if (isShow) "shows" else "movies"

        return coroutineScope {
            val people = async { get("/$path/$slug/people", listOf("extended" to "images")) }
            val related = async { get("/$path/$slug/related", listOf("limit" to "15", "extended" to "images")) }
            val seasons = async { if (isShow) get("/shows/$slug/seasons", listOf("extended" to "episodes,full,images")) else null }

            val cast = people.await().jList("cast").take(20).mapNotNull { p ->
                val person = p.jO("person")
                val name = person.jS("name") ?: return@mapNotNull null
                TitleDetails.CastMember(name = name, role = p.jList("characters").firstOrNull().jFlex ?: p.jS("character"),
                    photo = image(person.jO("images"), "headshot"), tmdbID = person.jO("ids").jI("tmdb"))
            }
            val rel = related.await().jItems.map { x ->
                TitleDetails.Recommendation(title = x.jS("title") ?: "?", year = x.jI("year"), poster = image(x.jO("images"), "poster"),
                    imdbID = x.jO("ids").jS("imdb"), isShow = isShow, tmdbID = x.jO("ids").jI("tmdb"))
            }
            val eps = HashMap<String, TitleDetails.EpisodeInfo>()
            for (s in seasons.await().jItems) {
                for (e in s.jList("episodes")) {
                    val sn = e.jI("season") ?: continue
                    val n = e.jI("number") ?: continue
                    eps["$sn-$n"] = TitleDetails.EpisodeInfo(title = e.jS("title"), overview = e.jS("overview"),
                        still = image(e.jO("images"), "screenshot"), airDate = Net.parseISODate(e.jS("first_aired")),
                        runtime = e.jI("runtime"))
                }
            }
            Result(imdb = ids.jS("imdb"), slug = ids.jS("slug"), overview = media.jS("overview"), rating = media.jD("rating"),
                runtime = media.jI("runtime"), genres = media.jList("genres").mapNotNull { it.jFlex },
                certification = media.jS("certification"), trailer = media.jS("trailer"),
                poster = image(images, "poster"), fanart = image(images, "fanart"), logo = image(images, "logo"),
                cast = cast, related = rel, episodes = eps)
        }
    }
}

// MARK: - TMDB (optioneel) --------------------------------------------------------

/**
 * Alleen gebruikt als er een TMDB-sleutel is. Levert foto's van de cast, titel-logo's,
 * achtergronden, afleveringsstills en teksten in de app-taal. De score blijft IMDb + Trakt.
 */
object TMDB {
    data class Result(
        val id: Int, val imdb: String?, val overview: String?, val poster: String?, val backdrop: String?, val logo: String?,
        val trailer: String?, val cast: List<TitleDetails.CastMember> = emptyList(),
        val recommendations: List<TitleDetails.Recommendation> = emptyList(),
        /** original_language (build 59) */
        val originalLanguage: String? = null,
    )

    private val language: String
        get() {
            val l = Localizer.language
            return when {
                l == "zh-Hans" -> "zh-CN"
                l == "pt" -> "pt-PT"
                l.length == 2 -> "$l-${l.uppercase()}"
                else -> l
            }
        }

    private fun img(path: String?, size: String): String? =
        if (path.isNullOrEmpty()) null else "https://image.tmdb.org/t/p/$size$path"

    private suspend fun get(path: String, q: List<Pair<String, String>> = emptyList()): JsonElement? {
        val key = Secrets.tmdbAPIKey
        if (!Secrets.isSet(key)) return null
        val headers = HashMap<String, String>()
        val items = q.toMutableList()
        // v4-token (lang, begint met eyJ) als Bearer, anders v3-sleutel als query
        if (key.length > 40) headers["Authorization"] = "Bearer $key" else items.add("api_key" to key)
        val u = Net.url("https://api.themoviedb.org/3$path", items) ?: return null
        return runCatching { Net.json(u, headers) }.getOrNull()
    }

    private fun yearOf(s: String?): Int? = s?.take(4)?.toIntOrNull()

    /** Build 111: TMDB-verzoek voor de rijen Binnenkort / Nieuw op streamingdiensten (zie Discover.kt). */
    suspend fun request(path: String, q: List<Pair<String, String>> = emptyList()): JsonElement? = get(path, q)
    /** Build 111: TMDB-locale van de app-taal en posterpad → URL (zie Discover.kt). */
    val appLanguage: String get() = language
    fun image(path: String?, size: String): String? = img(path, size)

    /** Trending vandaag (Netflix-achtige Top 10): titels in de app-taal én origineel, met jaar. */
    suspend fun trending(shows: Boolean): List<ExternalTitle> {
        val out = ArrayList<ExternalTitle>()
        for (page in listOf("1", "2")) {
            val r = get(if (shows) "/trending/tv/day" else "/trending/movie/day", listOf("language" to language, "page" to page)) ?: break
            for (i in r.jList("results")) {
                val t = listOf("title", "name", "original_title", "original_name").mapNotNull { i.jS(it) }.filter { it.isNotEmpty() }
                val y = yearOf(i.jS("release_date") ?: i.jS("first_air_date"))
                if (t.isNotEmpty()) out.add(ExternalTitle(t, y))
            }
        }
        return out
    }

    /** Populair bij kids: animatie/familie (films, t/m PG) en kids/animatie/familie (series), zonder volwassen titels. */
    suspend fun kidsTrending(shows: Boolean): List<ExternalTitle> {
        val base = mutableListOf("language" to language, "sort_by" to "popularity.desc", "include_adult" to "false",
            "with_genres" to if (shows) "10762|16|10751" else "16|10751")
        if (!shows) { base.add("certification_country" to "US"); base.add("certification.lte" to "PG") }
        val out = ArrayList<ExternalTitle>()
        for (page in listOf("1", "2")) {
            val r = get(if (shows) "/discover/tv" else "/discover/movie", base + ("page" to page)) ?: break
            for (i in r.jList("results")) {
                val t = listOf("title", "name", "original_title", "original_name").mapNotNull { i.jS(it) }.filter { it.isNotEmpty() }
                val y = yearOf(i.jS("release_date") ?: i.jS("first_air_date"))
                if (t.isNotEmpty()) out.add(ExternalTitle(t, y))
            }
        }
        return out
    }

    suspend fun details(imdb: String?, tmdbID: Int?, title: String, year: Int?, isShow: Boolean): Result? {
        if (!Secrets.isSet(Secrets.tmdbAPIKey)) return null
        var id = tmdbID
        if (id == null && imdb != null) {
            val f = get("/find/$imdb", listOf("external_source" to "imdb_id"))
            id = f.jList(if (isShow) "tv_results" else "movie_results").firstOrNull().jI("id")
        }
        if (id == null) {
            val q = mutableListOf("query" to title)
            if (year != null) q.add((if (isShow) "first_air_date_year" else "year") to year.toString())
            id = get(if (isShow) "/search/tv" else "/search/movie", q).jList("results").firstOrNull().jI("id")
        }
        val tid = id ?: return null
        val lang = language
        val base = lang.take(2)
        val f = get(if (isShow) "/tv/$tid" else "/movie/$tid", listOf(
            "language" to lang,
            "append_to_response" to if (isShow) "aggregate_credits,recommendations,images,videos,external_ids" else "credits,recommendations,images,videos",
            "include_image_language" to "$base,en,null",
            "include_video_language" to "$base,en",
        )) ?: return null
        val logos = f.jO("images").jList("logos")
        val logo = logos.firstOrNull { it.jS("iso_639_1") == base } ?: logos.firstOrNull { it.jS("iso_639_1") == "en" } ?: logos.firstOrNull()
        val castSrc = f.jO(if (isShow) "aggregate_credits" else "credits").jList("cast")
        return Result(
            id = f.jI("id") ?: tid,
            imdb = f.jS("imdb_id") ?: f.jO("external_ids").jS("imdb_id"),
            overview = f.jS("overview"),
            poster = img(f.jS("poster_path"), "w780"), backdrop = img(f.jS("backdrop_path"), "w1280"),
            logo = img(logo.jS("file_path"), "w500"),
            trailer = f.jO("videos").jList("results").firstOrNull { it.jS("site") == "YouTube" && it.jS("type") == "Trailer" }.jS("key"),
            cast = castSrc.take(20).mapNotNull { c ->
                val name = c.jS("name") ?: return@mapNotNull null
                TitleDetails.CastMember(name = name, role = c.jS("character") ?: c.jList("roles").firstOrNull().jS("character"),
                    photo = img(c.jS("profile_path"), "w185"), tmdbID = c.jI("id"))
            },
            recommendations = f.jO("recommendations").jList("results").take(15).map { r ->
                TitleDetails.Recommendation(title = r.jS("title") ?: r.jS("name") ?: "?",
                    year = yearOf(r.jS("release_date") ?: r.jS("first_air_date")),
                    poster = img(r.jS("poster_path"), "w342"), imdbID = null, isShow = isShow,
                    tmdbID = r.jI("id"), posterPath = r.jS("poster_path"))
            },
            originalLanguage = f.jS("original_language"),
        )
    }

    // MARK: personen (cast-pagina's en zoeken op naam)

    data class Person(
        val id: Int, val name: String, val photo: String?, val department: String?,
        val birthday: String?, val deathday: String?, val birthplace: String?, val biography: String?,
    )
    data class PersonHit(val id: Int, val name: String, val photo: String?, val department: String?, val popularity: Double)
    /** Eén film of serie waarin iemand speelt. [titles] = titel in de app-taal en origineel (om te koppelen). */
    data class Credit(
        val id: Int, val isShow: Boolean, val title: String, val titles: List<String>, val year: Int?,
        val poster: String?, val overview: String?, val popularity: Double, val date: String?,
    ) { val key: String get() = (if (isShow) "tv-" else "movie-") + id }

    /** Persoon in de app-taal; lege biografie → Engels (en-US). */
    suspend fun person(id: Int): Person? {
        val lang = language
        val r = get("/person/$id", listOf("language" to lang)) ?: return null
        var bio = r.jS("biography")?.trim()
        if (bio.isNullOrEmpty() && lang != "en-US") bio = get("/person/$id", listOf("language" to "en-US")).jS("biography")?.trim()
        return Person(
            id = r.jI("id") ?: id, name = r.jS("name") ?: "", photo = img(r.jS("profile_path"), "h632"),
            department = r.jS("known_for_department"), birthday = r.jS("birthday"), deathday = r.jS("deathday"),
            birthplace = r.jS("place_of_birth"), biography = bio?.takeIf { it.isNotEmpty() },
        )
    }

    /** Alle films en series waarin iemand speelt (cast, ontdubbeld; zonder talkshows en nieuws), populairste eerst. */
    suspend fun personCredits(id: Int): List<Credit> {
        val r = get("/person/$id/combined_credits", listOf("language" to language)) ?: return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<Credit>()
        for (c in r.jList("cast")) {
            val cid = c.jI("id") ?: continue
            val isShow = c.jS("media_type") == "tv"
            if (!isShow && c.jS("media_type") != "movie") continue
            val genres = c.jList("genre_ids").mapNotNull { it.jFlex?.toIntOrNull() }
            if (isShow && (10767 in genres || 10763 in genres)) continue
            val credit = Credit(
                id = cid, isShow = isShow,
                title = c.jS(if (isShow) "name" else "title") ?: continue,
                titles = listOf("title", "name", "original_title", "original_name").mapNotNull { c.jS(it) }.filter { it.isNotEmpty() }.distinct(),
                year = yearOf(c.jS("release_date") ?: c.jS("first_air_date")),
                poster = img(c.jS("poster_path"), "w342"), overview = c.jS("overview")?.takeIf { it.isNotBlank() },
                popularity = c.jD("popularity") ?: 0.0, date = (c.jS("release_date") ?: c.jS("first_air_date"))?.takeIf { it.isNotEmpty() },
            )
            if (seen.add(credit.key)) out.add(credit)
        }
        return out.sortedWith(compareByDescending<Credit> { it.popularity }.thenByDescending { it.date ?: "" })
    }

    /** Zoeken op naam (acteurs en actrices eerst). */
    suspend fun searchPeople(query: String): List<PersonHit> {
        val r = get("/search/person", listOf("query" to query, "language" to language, "include_adult" to "false")) ?: return emptyList()
        return r.jList("results").mapNotNull { p ->
            val id = p.jI("id") ?: return@mapNotNull null
            PersonHit(id = id, name = p.jS("name") ?: return@mapNotNull null, photo = img(p.jS("profile_path"), "w185"),
                department = p.jS("known_for_department"), popularity = p.jD("popularity") ?: 0.0)
        }
    }

    /**
     * Leeftijdsinfo voor het kinderfilter (build 53): TMDB-id, genres en minimumleeftijd
     * (certificering per land, Kijkwijzer eerst; series via content_ratings, films via release_dates).
     * null = netwerkfout (niet cachen); found = false = TMDB kent de titel niet.
     */
    suspend fun ageInfo(imdb: String?, title: String, year: Int?, isShow: Boolean): AgeRating? {
        if (!Secrets.isSet(Secrets.tmdbAPIKey)) return null
        val now = System.currentTimeMillis()
        var hit: JsonElement? = null
        if (imdb != null) {
            val f = get("/find/$imdb", listOf("external_source" to "imdb_id")) ?: return null
            hit = f.jList(if (isShow) "tv_results" else "movie_results").firstOrNull()
        }
        if (hit == null) {
            val path = if (isShow) "/search/tv" else "/search/movie"
            val q = mutableListOf("query" to title, "include_adult" to "true")
            if (year != null) q.add((if (isShow) "first_air_date_year" else "year") to year.toString())
            val r = get(path, q) ?: return null
            hit = r.jList("results").firstOrNull()
            // jaar uit de lijst klopt niet altijd (jaar van toevoegen i.p.v. uitkomen): zonder jaar nog een keer
            if (hit == null && year != null) hit = (get(path, listOf("query" to title, "include_adult" to "true")) ?: return null).jList("results").firstOrNull()
        }
        val h = hit ?: return AgeRating(found = false, checked = now)
        val id = h.jI("id") ?: return AgeRating(found = false, checked = now)
        val genres = h.jList("genre_ids").mapNotNull { it.jFlex?.trim()?.toIntOrNull() }
        val certs = ArrayList<Pair<String, String>>()
        if (isShow) {
            val r = get("/tv/$id/content_ratings") ?: return null
            for (c in r.jList("results")) {
                val country = c.jS("iso_3166_1") ?: continue
                c.jS("rating")?.takeIf { it.isNotBlank() }?.let { certs.add(country to it) }
            }
        } else {
            val r = get("/movie/$id/release_dates") ?: return null
            for (c in r.jList("results")) {
                val country = c.jS("iso_3166_1") ?: continue
                for (d in c.jList("release_dates")) d.jS("certification")?.takeIf { it.isNotBlank() }?.let { certs.add(country to it) }
            }
        }
        val age = if (h.jB("adult") == true) 18 else ParentalControl.ageFromCertifications(certs)
        return AgeRating(tmdbID = id, age = age, genres = genres, found = true, checked = now)
    }

    /**
     * Trailer van één seizoen (build 53): YouTube, Trailer vóór Teaser, de app-taal vóór Engels.
     * null = geen seizoenstrailer (dan de trailer van de serie gebruiken).
     */
    suspend fun seasonTrailer(tvID: Int, season: Int): String? {
        val base = language.take(2)
        val r = get("/tv/$tvID/season/$season/videos", listOf("language" to language, "include_video_language" to "$base,en")) ?: return null
        var list = r.jList("results")
        // sommige TMDB-antwoorden negeren include_video_language: dan nog een keer in het Engels
        if (list.none { it.jS("site") == "YouTube" && (it.jS("type") == "Trailer" || it.jS("type") == "Teaser") } && base != "en") {
            list = list + get("/tv/$tvID/season/$season/videos", listOf("language" to "en-US")).jList("results")
        }
        fun typeRank(v: JsonElement) = when (v.jS("type")) { "Trailer" -> 0; "Teaser" -> 1; else -> 9 }
        fun langRank(v: JsonElement) = when (v.jS("iso_639_1")) { base -> 0; "en" -> 1; else -> 2 }
        return list.filter { it.jS("site") == "YouTube" && typeRank(it) < 9 && !it.jS("key").isNullOrBlank() }
            .sortedWith(compareBy<JsonElement> { typeRank(it) }.thenBy { langRank(it) }.thenByDescending { it.jB("official") == true })
            .firstOrNull()?.jS("key")
    }

    /** Afleveringen van één seizoen; key "s-e". */
    suspend fun season(tvID: Int, season: Int): Map<String, TitleDetails.EpisodeInfo> {
        val r = get("/tv/$tvID/season/$season", listOf("language" to language))
        val out = HashMap<String, TitleDetails.EpisodeInfo>()
        for (e in r.jList("episodes")) {
            val n = e.jI("episode_number") ?: continue
            out["$season-$n"] = TitleDetails.EpisodeInfo(title = e.jS("name"), overview = e.jS("overview"),
                still = img(e.jS("still_path"), "w500"), airDate = Net.parseDay(e.jS("air_date")), runtime = e.jI("runtime"))
        }
        return out
    }
}
