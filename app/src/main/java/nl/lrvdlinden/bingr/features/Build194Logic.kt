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
}
