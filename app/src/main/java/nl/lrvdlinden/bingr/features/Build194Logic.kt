package nl.lrvdlinden.bingr

/**
 * Build 194 (versie 1.1.3): Live TV krijgt een ingebouwde groep "Terugkijken" (catch-up) naast Alle zenders, Favorieten en
 * Recent: alle zenders waarvan de server terugkijken doorgeeft (catch-up-dagen > 0), van de gekozen server plus de
 * zenders van andere servers die al in Favorieten of Recent staan. Zo hoeft niemand de categorie van de provider te zoeken.
 * Pure logica zonder Android, met unittests (tools/test/Build194LogicTest.kt).
 */
object Build194Logic {
    /** Sleutel van de groep in de categoriekeuze (los van de sleutels van de andere ingebouwde groepen). */
    const val GROUP_KEY = "⟲"

    /** Een zender heeft terugkijken als de server minstens één dag doorgeeft. */
    fun hasCatchup(days: Int): Boolean = days > 0

    /**
     * De zenders met terugkijken: eerst die van de gekozen server (in de volgorde van de lijst), dan die van andere
     * servers; dezelfde zender (zelfde sleutel) komt één keer voor.
     */
    fun <T> channels(active: List<T>, foreign: List<T>, key: (T) -> String, days: (T) -> Int): List<T> {
        val seen = HashSet<String>()
        val out = ArrayList<T>()
        for (c in active) if (hasCatchup(days(c)) && seen.add(key(c))) out.add(c)
        for (c in foreign) if (hasCatchup(days(c)) && seen.add(key(c))) out.add(c)
        return out
    }

    /** De groep staat alleen in de keuze als er minstens één zender is. */
    fun showGroup(count: Int): Boolean = count > 0
}
