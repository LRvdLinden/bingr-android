package nl.lrvdlinden.bingr

import java.net.URLEncoder

/**
 * Build 132: de eigen Bingr-link om een film of serie te delen (in plaats van IMDb/Trakt/TMDB), het omgekeerde van
 * [TitleLink.parse] (= TitleLink.swift): `https://bingr.online/titel/?type=movie|tv&id=<tmdb-id>&q=<titel>&y=<jaar>`.
 * Zonder TMDB-id alleen type + q + y. Op bingr.online staat een preview (cover + meta); met de app geïnstalleerd opent
 * de link via App Links (assetlinks.json) meteen de detailpagina. Pure logica (geen Android), getest in
 * tools/test/TitleShareTest.kt.
 */
object TitleShare {
    const val BASE = "https://bingr.online/titel/"

    /** [isMovie] true = film, false = serie, null = onbekend (geen type in de link). */
    fun url(isMovie: Boolean?, tmdbID: Int?, title: String, year: Int?): String {
        val parts = ArrayList<String>()
        isMovie?.let { parts += "type=" + if (it) "movie" else "tv" }
        tmdbID?.takeIf { it > 0 }?.let { parts += "id=$it" }
        val q = title.trim().take(200)
        if (q.isNotEmpty()) parts += "q=" + enc(q)
        year?.takeIf { it in 1870..2200 }?.let { parts += "y=$it" }
        return if (parts.isEmpty()) BASE else BASE + "?" + parts.joinToString("&")
    }

    /** "Titel (jaar)" voor de deeltekst ("Filmtip: %@" / "Serietip: %@"). */
    fun label(title: String, year: Int?): String = year?.takeIf { it in 1870..2200 }?.let { "$title ($it)" } ?: title

    /** Jaar uit een tekst als "2024" of "2024-05-01". */
    fun year(s: String?): Int? = s?.trim()?.take(4)?.toIntOrNull()?.takeIf { it in 1870..2200 }

    /** Percent-encoding voor een queryparameter; spatie = %20 (niet "+", dat leest niet elke parser als spatie). */
    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("%7E", "~")
}
