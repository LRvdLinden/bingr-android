package nl.lrvdlinden.bingr

/**
 * Build 195 (versie 1.1.3), aangepast in build 196: "Nieuwe aflevering" in Verder kijken. Staat een serie in Mijn lijst
 * (van welke server van het profiel ook), heb je minstens één gewone aflevering gekeken en staat er na de laatst gekeken
 * aflevering een die je nog niet zag, dan komt de eerste daarvan als volgende in Verder kijken. Pure logica zonder Android,
 * met unittests (tools/test/Build195LogicTest.kt).
 *
 * Regels (gelijk aan Apple, Build195.swift, en de webspeler):
 * - alleen series in Mijn lijst, minstens één gewone aflevering (seizoen 1+) gekeken; specials (seizoen 0) tellen nooit mee
 *   en komen nooit als tegel;
 * - build 196: de regel "bij zijn" vervalt. Overgeslagen eerdere afleveringen doen er niet toe: de tegel is de eerste
 *   ongeziene gewone aflevering NA de laatst gekeken gewone aflevering, op seizoen en nummer;
 * - heeft de serie al een voortgangstegel (half gekeken aflevering), dan geen tweede tegel ([tile]);
 * - handmatig weggehaald = per serie de weggehaalde aflevering onthouden: de tegel komt pas terug bij een nieuwere aflevering.
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
     * De nieuwe aflevering of null (zelfde regels als Apple en web): minstens één gewone aflevering gezien; de tegel is de
     * eerste gewone aflevering NA de laatst geziene, op seizoen en nummer. Eerdere afleveringen die je oversloeg doen er niet
     * toe (build 196; tot build 195 blokkeerden ze de serie). Niet als die al is weggehaald ([hidden] = sleutel van de
     * weggehaalde aflevering; pas een nieuwere aflevering brengt de tegel terug).
     */
    fun nextNew(eps: List<Ep>, watched: (Ep) -> Boolean, hidden: String?): Ep? {
        val o = ordered(eps)
        val lastSeen = o.indexOfLast(watched)
        if (lastSeen < 0) return null                                 // niets gekeken
        val next = o.getOrNull(lastSeen + 1) ?: return null          // niets na de laatst gekeken aflevering
        val hid = parse(hidden)
        if (hid != null && next <= hid) return null
        return next
    }

    /**
     * Build 196: aantal ongeziene gewone afleveringen NA de laatst gekeken gewone aflevering (specials, seizoen 0, tellen niet;
     * 0 zonder gekeken aflevering of zonder nieuws). Zelfde regel als [nextNew] (de tegel is de eerste daarvan); voor de rode
     * banner "Nieuwe aflevering(en)" op de cover in Verder kijken en Mijn lijst. Eén = enkelvoud, meer = meervoud.
     */
    fun newCount(eps: List<Ep>, watched: (Ep) -> Boolean): Int {
        val o = ordered(eps)
        val lastSeen = o.indexOfLast(watched)
        return if (lastSeen < 0) 0 else o.size - lastSeen - 1
    }

    /**
     * Loopt er een aflevering (meer dan niets, minder dan 85% gekeken)? Dan houdt de serie alleen die tegel. Build 196:
     * 85% zoals Apple en de gekeken-drempel van de speler (Build154Logic.Threshold); tot build 195 was dit 95%, waardoor een
     * aflevering van 85-95% al als gezien gold maar de serie toch geen nieuwe tegel kreeg.
     */
    fun hasRunning(fractions: Collection<Double>): Boolean = fractions.any { it > 0.0 && it < RUNNING_MAX }
    const val RUNNING_MAX = 0.85

    /**
     * Zoals [nextNew], maar nooit voor een serie met een lopende aflevering: die houdt zijn eigen tegel uit de voortgang
     * (zonder tweede of andere tegel). [progress] = aflevering-id naar fractie (alleen van de server van deze serie); wordt
     * alleen gelezen, nooit geschreven.
     */
    fun tile(eps: List<Pair<String, Ep>>, progress: Map<String, Double>, watched: (Ep) -> Boolean, hidden: String?): Ep? {
        if (hasRunning(eps.mapNotNull { progress[it.first] })) return null
        return nextNew(eps.map { it.second }, watched, hidden)
    }

    /** Wat te onthouden als de tegel wordt weggehaald: de aflevering van de tegel. */
    fun hiddenValue(e: Ep): String = e.key

    /** Titelsleutel zoals Mijn lijst en Verder kijken titels vergelijken (hoofdletters, leestekens). */
    fun titleKey(title: String): String = title.lowercase().filter { it.isLetterOrDigit() }

    /** Prefs-sleutels per profiel en serie. */
    fun prefKey(kind: String, profile: String?, title: String) = "ne195.$kind.${profile ?: "main"}.${titleKey(title)}"
}
