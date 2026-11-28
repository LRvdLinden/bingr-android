package nl.lrvdlinden.bingr

/**
 * Build 195 (versie 1.1.3): "Nieuwe aflevering" in Verder kijken. Staat een serie in Mijn lijst, heb je minstens één
 * aflevering gekeken, ben je bij en staat er in de bibliotheek een aflevering die je nog niet zag, dan komt die als
 * volgende in Verder kijken. Pure logica zonder Android, met unittests (tools/test/Build195LogicTest.kt).
 *
 * "Bij zijn": alle afleveringen vóór de eerste ongeziene zijn gezien (seizoenen eerder en het laatst gekeken seizoen).
 * Specials (seizoen 0) tellen niet mee. Een aflevering is "nieuw" als hij na de laatste aflevering komt die de app kende
 * toen je helemaal bij was ([mark], de bibliotheek heeft geen uitzenddatum). Handmatig verwijderd = per serie de laatste
 * aflevering onthouden ([hidden]): de tegel komt pas terug bij een nog nieuwere aflevering.
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

    /** Is iedere aflevering gezien (en is er minstens één)? */
    fun caughtUp(eps: List<Ep>, watched: (Ep) -> Boolean): Boolean {
        val o = ordered(eps)
        return o.isNotEmpty() && o.all(watched)
    }

    /** Nieuwe waarde van [mark]: de laatste aflevering als je helemaal bij bent, anders de oude (nooit terug). */
    fun nextMark(eps: List<Ep>, watched: (Ep) -> Boolean, mark: String?): String? {
        if (!caughtUp(eps, watched)) return mark
        val last = ordered(eps).last()
        val old = parse(mark)
        return if (old == null || last > old) last.key else mark
    }

    /**
     * De nieuwe aflevering of null. Voorwaarden: minstens één aflevering gezien; alles vóór de eerste ongeziene na de
     * laatst geziene is gezien; die aflevering komt na [mark]; en de laatste aflevering is nieuwer dan [hidden].
     */
    fun nextNew(eps: List<Ep>, watched: (Ep) -> Boolean, mark: String?, hidden: String?): Ep? {
        val o = ordered(eps)
        val markEp = parse(mark) ?: return null
        val lastSeen = o.indexOfLast(watched)
        if (lastSeen < 0) return null
        if (!o.take(lastSeen + 1).all(watched)) return null          // gaten: niet bij
        val next = o.getOrNull(lastSeen + 1) ?: return null          // niets nieuws
        if (next <= markEp) return null                              // oude, nog niet gekeken aflevering: geen nieuwe
        val hid = parse(hidden)
        if (hid != null && o.last() <= hid) return null              // weggehaald en niets nieuwers
        return next
    }

    /** Wat te onthouden als de tegel wordt weggehaald: de laatste aflevering van dat moment. */
    fun hiddenValue(eps: List<Ep>): String? = ordered(eps).lastOrNull()?.key

    /** Titelsleutel zoals Mijn lijst en Verder kijken titels vergelijken (hoofdletters, leestekens). */
    fun titleKey(title: String): String = title.lowercase().filter { it.isLetterOrDigit() }

    /** Prefs-sleutels per profiel en serie. */
    fun prefKey(kind: String, profile: String?, title: String) = "ne195.$kind.${profile ?: "main"}.${titleKey(title)}"
}
