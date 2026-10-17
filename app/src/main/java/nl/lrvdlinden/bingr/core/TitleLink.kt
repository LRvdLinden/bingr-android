package nl.lrvdlinden.bingr

import android.net.Uri

/**
 * Link naar een film of serie (build 120, = TitleLink.swift), uit de wekelijkse e-mail van Bingr:
 * - app link `https://bingr.online/titel/?type=movie|tv&id=<tmdb-id>&q=<titel>&y=<jaar>` (assetlinks.json op bingr.online)
 * - `bingr://title?type=&id=&q=&y=` (fallbackknop op die pagina)
 * De app zoekt de titel in de bibliotheek van de actieve bron: eerst op TMDB-id (als die al bekend is uit eerder
 * geladen details), anders op titel (+ jaar). Eén goede match → detailpagina, anders het zoekscherm met de titel.
 */
data class TitleLink(val kind: Kind?, val tmdbID: Int?, val query: String, val year: Int?) {
    enum class Kind { MOVIE, TV }

    sealed class Resolution {
        data class Detail(val target: DetailTarget) : Resolution()
        data class Search(val query: String) : Resolution()
    }

    companion object {
        /** null als de link geen titel-link is (of niets bruikbaars bevat). */
        fun parse(u: Uri): TitleLink? {
            when (u.scheme?.lowercase()) {
                "https", "http" -> {
                    val host = u.host?.lowercase()
                    if (host != "bingr.online" && host != "www.bingr.online") return null
                    val path = (u.path ?: "").lowercase()
                    if (path != "/titel" && !path.startsWith("/titel/")) return null
                }
                // bingr://title?… (host) of bingr:title?… (opaak)
                "bingr" -> {
                    val target = (u.host?.takeIf { it.isNotEmpty() }
                        ?: u.schemeSpecificPart?.substringBefore('?')?.trim('/'))?.lowercase()
                    if (target != "title" && target != "titel") return null
                }
                else -> return null
            }
            // opake URI's (bingr:title?…) hebben geen query-parameters voor Uri: via een hiërarchische kopie lezen
            val h = if (u.isOpaque) Uri.parse("bingr://x?" + (u.schemeSpecificPart?.substringAfter('?', "") ?: "")) else u
            fun q(name: String): String? = runCatching { h.getQueryParameter(name) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            val kind = when (q("type")?.lowercase()) {
                "movie", "film" -> Kind.MOVIE
                "tv", "series", "serie", "show" -> Kind.TV
                else -> null
            }
            val id = q("id")?.toIntOrNull()?.takeIf { it > 0 }
            val query = (q("q") ?: "").take(200)
            val year = q("y")?.take(4)?.toIntOrNull()?.takeIf { it in 1870..2200 }
            if (query.isEmpty() && id == null) return null
            return TitleLink(kind, id, query, year)
        }

        /** Zoekt de titel in de (gefilterde) bibliotheek. Zwaar bij grote lijsten: niet op de main thread. */
        fun resolve(link: TitleLink, movies: List<Movie>, series: List<Series>, pref: String?): Resolution {
            val lookMovies = link.kind != Kind.TV
            val lookSeries = link.kind != Kind.MOVIE
            // 1. TMDB-id uit eerder geladen details
            link.tmdbID?.let { id ->
                val hits = (if (lookMovies) movies.filter { Details.cachedTMDB(it.id) == id }.map { DetailTarget.MovieT(it) } else emptyList()) +
                    (if (lookSeries) series.filter { Details.cachedTMDB(it.id) == id }.map { DetailTarget.SeriesT(it) } else emptyList())
                pick(hits, pref)?.let { return Resolution.Detail(it) }
            }
            // 2. titel (+ jaar), genormaliseerd zoals de rest van de app (taallabels en jaartal uit de titel weg)
            val wanted = Net.normTitle(link.query)
            val wantedClean = Net.normTitle(TitleCleaner.parse(ContentLanguage.displayTitle(link.query)).title)
            if (wanted.length <= 1 && wantedClean.length <= 1) return Resolution.Search(link.query)
            val strong = ArrayList<DetailTarget>()
            val weak = ArrayList<DetailTarget>()
            fun consider(t: DetailTarget, title: String, year: Int?) {
                val p = TitleCleaner.parse(ContentLanguage.displayTitle(title))
                val n = Net.normTitle(p.title)
                if (n.length <= 1 || (n != wanted && n != wantedClean)) return
                val y = year ?: p.year
                val want = link.year
                when {
                    want == null -> weak += t
                    y == null -> weak += t
                    kotlin.math.abs(y - want) <= 1 -> strong += t
                }
            }
            if (lookMovies) for (m in movies) consider(DetailTarget.MovieT(m), m.title, m.year)
            if (lookSeries) for (s in series) consider(DetailTarget.SeriesT(s), s.title, s.year)
            pick(strong, pref)?.let { return Resolution.Detail(it) }
            // zonder (passend) jaar alleen als het er precies één is: anders kan het een andere titel met dezelfde naam zijn
            if (strong.isEmpty() && weak.size == 1) return Resolution.Detail(weak[0])
            return Resolution.Search(link.query)
        }

        /**
         * Eén titel, eventueel in meerdere taalversies (de detailpagina toont de andere versies zelf): de versie in de
         * voorkeurstaal, anders de eerste. Films én series met dezelfde match = niet eenduidig.
         */
        private fun pick(list: List<DetailTarget>, pref: String?): DetailTarget? {
            if (list.isEmpty()) return null
            val movies = list.count { it is DetailTarget.MovieT }
            if (movies != 0 && movies != list.size) return null
            return list.firstOrNull { pref != null && ContentLanguage.of(it)?.code == pref } ?: list.first()
        }
    }
}
