package nl.lrvdlinden.bingr

/**
 * Build 191 (versie 1.1.2): tabbladen op de detailpagina en de tekst op de downloadknop.
 * Pure logica zonder Android, met unittests (tools/test/Build191LogicTest.kt).
 */
object Build191Logic {

    enum class Tab { EPISODES, MORE_LIKE_THIS, TRAILERS }

    /** Alleen tabbladen met inhoud: Afleveringen (serie), Meer zoals dit (aanbevelingen), Trailers. */
    fun tabs(isSeries: Boolean, recommendations: Int, trailers: Int): List<Tab> {
        val out = ArrayList<Tab>()
        if (isSeries) out += Tab.EPISODES
        if (recommendations > 0) out += Tab.MORE_LIKE_THIS
        if (trailers > 0) out += Tab.TRAILERS
        return out
    }

    /** Het gekozen tabblad, of het eerste als de keuze (nog) niet bestaat; null zonder tabbladen. */
    fun selected(tabs: List<Tab>, wanted: Tab?): Tab? = if (wanted != null && wanted in tabs) wanted else tabs.firstOrNull()

    /** Aantal trailers: die van de titel en die van het gekozen seizoen (als dat een andere is). */
    fun trailerCount(titleTrailer: String?, seasonTrailer: String?): Int =
        listOfNotNull(titleTrailer, seasonTrailer).distinct().size

    /** "Downloaden N afleveringen" vanaf 2 afleveringen die nog binnen moeten; anders alleen "Downloaden". */
    fun showEpisodeCount(episodesToDownload: Int, complete: Boolean): Boolean = !complete && episodesToDownload >= 2
}
