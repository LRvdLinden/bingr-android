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

    /**
     * Tweeling-koppeling voor terugkijken. Een zender zonder eigen catch-up (dagen 0) krijgt, binnen dezelfde server,
     * de zender met catch-up die ondubbelzinnig dezelfde is. Live blijft op de originele zender; alleen terugkijken
     * (klokje, optie in het programmablad, archief en catch-up-URL) gebruikt de tweeling. Regels (gelijk aan Apple):
     * 1. alleen dezelfde server (de lijst die je meegeeft is één server); 2. kandidaten = zenders met dagen > 0;
     * 3. eerst epg-id (niet leeg, gelijk zonder hoofdletters): precies 1 kandidaat = match, 2 of meer = geen match
     * (niet doorvallen naar de naam); 4. geen epg-id of geen kandidaat met dat id: genormaliseerde naam (minstens 3
     * tekens): precies 1 kandidaat = match, 2 of meer = geen match; 5. een zender met eigen catch-up gebruikt zichzelf.
     */
    object Twin {
        class Item(val key: String, val epg: String?, val name: String, val days: Int)

        private val tagInner = Regex("catch[- ]?up|terugkijken|replay|timeshift")
        private val brackets = Regex("\\[[^\\]]*]|\\([^)]*\\)|\\{[^}]*}")
        private val words = Regex("\\b(?:hd|uhd|fhd|sd|4k|hevc|h265|raw|catch[- ]?up|terugkijken|replay|timeshift)\\b")

        /** Naam voor het vergelijken: kleine letters, zonder accenten, zonder catch-up-tags en HD/UHD/FHD/SD/4K/HEVC/H265/RAW, alleen letters en cijfers. */
        fun normalize(name: String): String {
            var n = java.text.Normalizer.normalize(name.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            n = brackets.replace(n) { if (tagInner.containsMatchIn(it.value)) " " else it.value }
            // delen tussen | of ┃ die een catch-up-aanduiding zijn ("RTL 4 | Catch-up") vallen weg
            if ('|' in n || '\u2503' in n) n = n.split('|', '\u2503').filterNot { tagInner.containsMatchIn(it) }.joinToString(" ")
            n = words.replace(n, " ")
            return n.filter { it.isLetterOrDigit() }
        }

        private fun epgKey(e: String?): String? = e?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

        /** Sleutel van de zender zonder eigen catch-up -> sleutel van zijn tweeling. Alleen zenders met een ondubbelzinnige tweeling staan erin. */
        fun link(items: List<Item>): Map<String, String> {
            val cands = items.filter { it.days > 0 }
            if (cands.isEmpty() || cands.size == items.size) return emptyMap()
            val byEpg = HashMap<String, MutableList<String>>()
            val byName = HashMap<String, MutableList<String>>()
            for (c in cands) {
                epgKey(c.epg)?.let { byEpg.getOrPut(it) { ArrayList() }.add(c.key) }
                normalize(c.name).takeIf { it.length >= 3 }?.let { byName.getOrPut(it) { ArrayList() }.add(c.key) }
            }
            val out = HashMap<String, String>()
            for (i in items) {
                if (i.days > 0) continue
                val e = epgKey(i.epg)?.let { byEpg[it] }
                val pick: List<String>? = if (e != null) e else normalize(i.name).takeIf { it.length >= 3 }?.let { byName[it] }
                if (pick != null && pick.size == 1 && pick[0] != i.key) out[i.key] = pick[0]
            }
            return out
        }
    }

    /**
     * Terugkijken vanuit de gids met spoelen. Een replay-stream (timeshift van de provider) heeft vaak geen bekende duur,
     * dus de lengte van het programma uit de gids is de duur en elke spoelactie bouwt de replay-URL opnieuw op vanaf
     * programmabegin + positie (het einde blijft het programma-einde). Posities zijn seconden vanaf programmabegin.
     */
    object Replay {
        /** Veiligheidsmarge aan het eind: nooit tot op de laatste seconde spoelen (de stream zou meteen eindigen). */
        const val END_MARGIN = 5.0

        /** Duur van het programma in seconden (0 bij een ongeldig of leeg bereik). */
        fun duration(startMs: Long, endMs: Long): Double = if (endMs > startMs) (endMs - startMs) / 1000.0 else 0.0

        /** Is dit een terugkijk-item met bruikbaar bereik? */
        fun valid(startMs: Long?, endMs: Long?): Boolean = startMs != null && endMs != null && endMs - startMs >= 10_000L

        /** Positie in het programma: stream-begin (ms) minus programmabegin, plus de afgespeelde seconden, binnen 0...duur. */
        fun offset(startMs: Long, streamStartMs: Long, playedSec: Double, durationSec: Double): Double =
            (((streamStartMs - startMs) / 1000.0) + playedSec.coerceAtLeast(0.0)).coerceIn(0.0, durationSec.coerceAtLeast(0.0))

        /**
         * Tot waar je mag spoelen (zoals Apple `Replay194.limit`): niet voorbij het programma (min. de marge) en niet in de
         * toekomst bij een programma dat nog loopt (nu - begin - 3 s).
         */
        fun limit(startMs: Long, endMs: Long, nowMs: Long): Double {
            val d = duration(startMs, endMs)
            return Math.max(0.0, Math.min(Math.max(0.0, d - END_MARGIN), (nowMs - startMs) / 1000.0 - 3.0))
        }

        /** Nieuwe positie na [deltaSec] spoelen vanaf [offsetSec], binnen 0...[limitSec]. */
        fun target(offsetSec: Double, deltaSec: Double, limitSec: Double): Double =
            (offsetSec + deltaSec).coerceIn(0.0, limitSec.coerceAtLeast(0.0))

        /** Positie bij een sleepstand (0...1 van de tijdbalk), binnen 0...[limitSec]. */
        fun fromFraction(fraction: Double, durationSec: Double, limitSec: Double): Double =
            (fraction.coerceIn(0.0, 1.0) * durationSec).coerceIn(0.0, limitSec.coerceAtLeast(0.0))

        /** Begin (epoch-ms) van de stream die bij [offsetSec] hoort. */
        fun streamStart(startMs: Long, offsetSec: Double): Long = startMs + Math.round(offsetSec * 1000.0)

        /** Voortgang 0...1 voor de tijdbalk. */
        fun fraction(offsetSec: Double, durationSec: Double): Double =
            if (durationSec <= 0.0) 0.0 else (offsetSec / durationSec).coerceIn(0.0, 1.0)

        /**
         * Kan de speler zelf spoelen? Alleen als de stream een bekende duur heeft die bij het programma past (tot 10%, min.
         * 2 minuten, verschil door voor- en nablijf van de provider); een groeiende of onbekende duur is dat niet.
         */
        fun nativeSeekable(engineDurationSec: Double, programDurationSec: Double): Boolean =
            engineDurationSec > 60.0 && programDurationSec > 0.0 &&
                Math.abs(engineDurationSec - programDurationSec) <= Math.max(120.0, programDurationSec * 0.1)
    }
}
