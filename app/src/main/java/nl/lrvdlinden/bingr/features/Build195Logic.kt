package nl.lrvdlinden.bingr

/**
 * Build 195 (versie 1.1.3): "Nieuwe aflevering" in Verder kijken. Staat een serie in Mijn lijst, heb je minstens één
 * aflevering gekeken, ben je bij en staat er in de bibliotheek een aflevering die je nog niet zag, dan komt die als
 * volgende in Verder kijken. Pure logica zonder Android, met unittests (tools/test/Build195LogicTest.kt).
 *
 * Specials (seizoen 0) tellen niet mee. Handmatig weggehaald = per serie de weggehaalde aflevering onthouden: de tegel komt
 * pas terug bij een nieuwere aflevering. Regels gelijk aan Apple (Build195.swift) en de webspeler.
 */
object Build195Logic {
    data class Ep(val season: Int, val number: Int) : Comparable<Ep> {
        override fun compareTo(other: Ep) = compareValuesBy(this, other, { it.season }, { it.number })
        val key: String get() = "$season-$number"
        /** Bijschrift van de tegel, bv. S1E5 (zonder tekst ervoor, die is vertaald). */
        val label: String get() = "S${season}E$number"
    }

    fun parse(key: String?): Ep? {
        val p = key?.split("-") ?: return null
        if (p.size != 2) return null
        val s = p[0].toIntOrNull() ?: return null
        val n = p[1].toIntOrNull() ?: return null
        return Ep(s, n)
    }

    /** Afleveringen op volgorde seizoen/aflevering, zonder specials en dubbelen. */
    fun ordered(eps: List<Ep>): List<Ep> = eps.filter { it.season > 0 }.distinct().sorted()

    /**
     * De nieuwe aflevering of null (zelfde regels als Apple en web): minstens één gewone aflevering gezien; alles vóór de
     * laatst geziene is gezien (ook eerdere seizoenen); de tegel is de eerste ongeziene daarna; niet als die al is
     * weggehaald ([hidden] = sleutel van de weggehaalde aflevering; pas een nieuwere aflevering brengt de tegel terug).
     */
    fun nextNew(eps: List<Ep>, watched: (Ep) -> Boolean, hidden: String?): Ep? {
        val o = ordered(eps)
        val lastSeen = o.indexOfLast(watched)
        if (lastSeen < 0) return null
        if (!o.take(lastSeen + 1).all(watched)) return null          // gaten: niet bij
        val next = o.getOrNull(lastSeen + 1) ?: return null          // niets nieuws
        val hid = parse(hidden)
        if (hid != null && next <= hid) return null
        return next
    }

    /** Titelsleutel zoals Mijn lijst en Verder kijken titels vergelijken (hoofdletters, leestekens). */
    fun titleKey(title: String): String = title.lowercase().filter { it.isLetterOrDigit() }

    /** Prefs-sleutels per profiel en serie. */
    fun prefKey(kind: String, profile: String?, title: String) = "ne195.$kind.${profile ?: "main"}.${titleKey(title)}"
}
