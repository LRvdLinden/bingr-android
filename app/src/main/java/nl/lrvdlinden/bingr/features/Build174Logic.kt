package nl.lrvdlinden.bingr

/**
 * Build 174 (versie 1.0.7): detailpagina op de telefoon, trailers in de app en Bingr Radio.
 * Pure logica zonder Android, met unittests (tools/test/Build174LogicTest.kt). Dezelfde regels als Apple
 * (`Shared/Player/Build174.swift`) en de webspeler (`web/js/build174.js`).
 */
object Build174Logic {

    // MARK: 1. Detailpagina: knoppen onder Afspelen (telefoon)

    enum class Action { MY_LIST, TRAILER, SOURCES, WATCHED, MORE }

    /** Smal scherm (telefoon, dp): de vaste rij met tekst onder het icoon. Breed (tablet, tv): de brede rij blijft. */
    const val COMPACT_MAX_DP = 600

    fun isCompact(widthDp: Int, tv: Boolean): Boolean = !tv && widthDp in 1 until COMPACT_MAX_DP

    /** Mijn lijst · Trailer · Bronnen · Gezien · Meer; wat niet van toepassing is valt weg, de rest schuift op. */
    fun compactActions(hasListID: Boolean, hasTrailer: Boolean, otherSources: Int, languageVersions: Int, isMovie: Boolean): List<Action> {
        val out = ArrayList<Action>()
        if (hasListID) out += Action.MY_LIST
        if (hasTrailer) out += Action.TRAILER
        if (otherSources + languageVersions > 0) out += Action.SOURCES
        if (isMovie) out += Action.WATCHED
        out += Action.MORE
        return out
    }

    enum class SourcesTarget { NONE, OTHER_SOURCES, LANGUAGES, CHOOSE }

    fun sourcesTarget(otherSources: Int, languageVersions: Int): SourcesTarget = when {
        otherSources > 0 && languageVersions > 0 -> SourcesTarget.CHOOSE
        otherSources > 0 -> SourcesTarget.OTHER_SOURCES
        languageVersions > 0 -> SourcesTarget.LANGUAGES
        else -> SourcesTarget.NONE
    }

    // MARK: 2. Trailers: de beste uit alle bronnen

    enum class Source { TMDB, SERVER, TRAKT }

    data class Candidate(
        val key: String, val source: Source, val type: String? = null, val language: String? = null,
        val official: Boolean? = null, val site: String = "YouTube",
    )

    private val ID = Regex("^[A-Za-z0-9_-]{11}$")
    fun isValidID(s: String?): Boolean = s != null && ID.matches(s)

    /** YouTube-id uit een id of een link (watch?v=, embed/, youtu.be/), anders null. */
    fun youtubeID(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (isValidID(s)) return s
        Regex("[?&]v=([A-Za-z0-9_-]{11})(?:[&#]|$)").find(s)?.let { return it.groupValues[1] }
        Regex("youtu(?:\\.be|be(?:-nocookie)?\\.com)/(?:embed/|shorts/)?([A-Za-z0-9_-]{11})(?:[?&#/]|$)").find(s)?.let { return it.groupValues[1] }
        return null
    }

    /** Trailer, dan onbekend (server/Trakt), dan Teaser; app-taal, Engels, zonder taal, rest; officieel; TMDB, server, Trakt. */
    fun rank(c: Candidate, appLanguage: String): List<Int> {
        val base = appLanguage.lowercase().take(2)
        val typeRank = when (c.type?.lowercase()) { "trailer" -> 0; null -> 1; "teaser" -> 2; else -> 9 }
        val lang = c.language?.lowercase()
        val langRank = when {
            lang == null -> if (c.source == Source.TMDB) 2 else 1
            lang == base -> 0
            lang == "en" -> if (base == "en") 0 else 1
            else -> 3
        }
        return listOf(typeRank, langRank, if (c.official == false) 1 else 0, c.source.ordinal)
    }

    fun bestTrailer(candidates: List<Candidate>, appLanguage: String): String? {
        var best: Candidate? = null
        var bestRank: List<Int>? = null
        for (c in candidates) {
            if (!isValidID(c.key) || !c.site.equals("youtube", ignoreCase = true)) continue
            val r = rank(c, appLanguage)
            if (r[0] >= 9) continue
            if (bestRank == null || less(r, bestRank)) { best = c; bestRank = r }
        }
        return best?.key
    }

    private fun less(a: List<Int>, b: List<Int>): Boolean {
        for (i in a.indices) if (a[i] != b[i]) return a[i] < b[i]
        return false
    }

    enum class Opening { IN_APP, YOUTUBE_APP, BROWSER, NOT_INSTALLED }

    /** In de app (YouTube IFrame-player in een WebView); zonder WebView de YouTube-app, anders de browser. */
    fun opening(canEmbed: Boolean, hasYouTubeApp: Boolean, hasBrowser: Boolean): Opening = when {
        canEmbed -> Opening.IN_APP
        hasYouTubeApp -> Opening.YOUTUBE_APP
        hasBrowser -> Opening.BROWSER
        else -> Opening.NOT_INSTALLED
    }

    // MARK: 3. Bingr Radio-widget

    data class Station(val id: String, val name: String, val favorite: Boolean = false)

    /** Knoppen in de widget: favorieten eerst, aangevuld met de eerste zenders. */
    fun widgetStations(s: List<Station>, max: Int): List<Station> =
        if (max <= 0) emptyList() else (s.filter { it.favorite } + s.filter { !it.favorite }).take(max)

    /** De zender bovenaan in de widget: die nu speelt, anders de eerste favoriet, anders de eerste zender. */
    fun featured(s: List<Station>, currentID: String?): Station? =
        s.firstOrNull { it.id == currentID } ?: s.firstOrNull { it.favorite } ?: s.firstOrNull()

    /** ICY-metadata ("StreamTitle='Artiest - Titel';") → nette tekst; lege titels, de zendernaam en reclame tellen niet. */
    fun cleanStreamTitle(raw: String?, station: String? = null): String? {
        var s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val i = s.indexOf("StreamTitle='")
        if (i >= 0) {
            s = s.substring(i + 13)
            val end = s.indexOf("';").takeIf { it >= 0 } ?: s.indexOf('\'')
            if (end >= 0) s = s.substring(0, end)
        }
        s = s.replace("\u0000", "").trim().replace(Regex(" {2,}"), " ").removePrefix("- ").removeSuffix(" -").trim()
        if (s.isEmpty() || s.none { it.isLetterOrDigit() }) return null
        val lower = s.lowercase()
        if (station != null && lower == station.trim().lowercase()) return null
        val junk = setOf("unknown", "advert", "advertisement", "commercial", "reclame", "jingle", "spot", "unknown - unknown", "n/a", "-", "untitled")
        if (lower in junk || lower.startsWith("adbreak") || lower.startsWith("ad break")) return null
        return s.take(120)
    }
}
