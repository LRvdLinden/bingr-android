package nl.lrvdlinden.bingr

/**
 * Build 166: pure logica (zonder Android, getest in tools/test/Build166LogicTest.kt) van de dagbalk van de tv-gids:
 * alleen dagen waarvoor er echt gids-info is.
 *
 * - [GuideDays.sample]: welke zenders tellen mee (de zenders in beeld, aangevuld met de eerste uit de lijst, max. [GuideDays.SAMPLE]).
 * - [GuideDays.available]: welke dagen in de balk komen. Vandaag altijd; morgen en later alleen met programma's; gisteren en
 *   eerder alleen als een zender op die dag programma's heeft én zo ver terugkijken heeft.
 * - [GuideDays.keep]: de gekozen dag houden als hij er nog is, anders terug naar vandaag.
 *
 * Kandidaten (gisteren t/m 7 dagen vooruit, verder terug max. 14) komen uit [Build164Logic.Days.offsets].
 */
object Build166Logic {
    object GuideDays {
        /** Zoveel zenders kijken we hooguit na (de rest van een grote categorie heeft vrijwel altijd dezelfde dagen). */
        const val SAMPLE = 50

        /**
         * Steekproef: eerst de zenders in beeld ([visible], indexen in [channels]), dan de eerste zenders van de lijst,
         * zonder dubbele indexen en hooguit [max] stuks. Geeft indexen terug.
         */
        fun sample(count: Int, visible: List<Int>, max: Int = SAMPLE): List<Int> {
            if (count <= 0 || max <= 0) return emptyList()
            val out = LinkedHashSet<Int>()
            for (i in visible) { if (out.size >= max) break; if (i in 0 until count) out.add(i) }
            var i = 0
            while (out.size < max && i < count) { out.add(i); i++ }
            return out.toList()
        }

        /**
         * Dagen (verschil met vandaag) die in de balk komen, in de volgorde van [candidates].
         * [hasGuide] (zender, dag) = er staat minstens één programma op die dag; [catchupDays] = terugkijkdagen van de zender.
         */
        fun <C> available(candidates: List<Int>, channels: List<C>, hasGuide: (C, Int) -> Boolean, catchupDays: (C) -> Int): List<Int> =
            candidates.filter { o ->
                when {
                    o == 0 -> true
                    o > 0 -> channels.any { hasGuide(it, o) }
                    else -> channels.any { catchupDays(it) >= -o && hasGuide(it, o) }
                }
            }.let { if (0 in it) it else (it + 0).sorted() }

        /** De gekozen dag blijft als hij in de balk staat, anders vandaag. */
        fun keep(offset: Int, available: List<Int>): Int = if (offset in available) offset else 0
    }
}
