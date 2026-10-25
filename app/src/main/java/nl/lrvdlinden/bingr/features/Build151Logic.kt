package nl.lrvdlinden.bingr

/**
 * Build 151: pure logica (zonder Android, getest in tools/test/Build151LogicTest.kt en, voor Samsung TV en LG, in
 * tizen/test/build151_test.js met dezelfde gevallen uit tools/test/build151_cases.json; js/build151.js is dezelfde code).
 *
 * - [Channels]: de rij "Zenders" op Home: zenderlogo's van de eigen server (zoals een rij streamingdiensten). Eerst de
 *   recente zenders en favorieten, aangevuld met zenders uit de grootste zichtbare live-categorieën (met logo eerst),
 *   hooguit [Channels.MAX]. Dezelfde zender in HD/FHD/4K of met "NL |" ervoor telt één keer. Kinderprofiel: alleen
 *   zenders die het kinderfilter toelaat.
 */
object Build151Logic {

    object Channels {
        const val MAX = 15
        /** Hooguit zoveel aanvullers per categorie, zodat de rij niet uit één categorie bestaat. */
        const val PER_GROUP = 3
        /** Minder zenders dan dit: geen rij. */
        const val MIN = 3

        /** Zendernaam zonder taal-, land- en kwaliteitsaanduiding ("NL | NPO 1 HD" → "NPO 1"); hoofdletters blijven. */
        fun name(raw: String): String = Build150Logic.CategoryName.strip(raw).name.ifEmpty { raw.trim() }

        /** Sleutel om dezelfde zender in een andere variant te herkennen ("NPO 1 HD", "NL: NPO 1 FHD" → "npo1"). */
        fun sameKey(raw: String): String = Build150Logic.Merge.key(name(raw)).ifEmpty { raw.trim().lowercase() }

        /**
         * Welke zenders in de rij komen, in volgorde.
         * [preferred] = recente zenders en dan favorieten (de aanroeper zet ze zo), [groups] = zichtbare live-categorieën
         * met hun zenders. [allowed] = kinderfilter e.d.; [key] = unieke sleutel (server|id).
         */
        fun <T> pick(
            preferred: List<T>,
            groups: List<Pair<String, List<T>>>,
            key: (T) -> String,
            name: (T) -> String,
            hasLogo: (T) -> Boolean,
            allowed: (T) -> Boolean,
            max: Int = MAX,
        ): List<T> {
            val out = ArrayList<T>()
            val keys = HashSet<String>()
            val names = HashSet<String>()
            fun add(t: T): Boolean {
                if (out.size >= max || !allowed(t)) return false
                val k = key(t); val n = sameKey(name(t))
                if (k in keys || n in names) return false
                keys += k; names += n; out += t
                return true
            }
            for (t in preferred) { if (out.size >= max) break; add(t) }
            // grootste categorie eerst; bij gelijke grootte de volgorde van de server
            val sorted = groups.withIndex().sortedWith(compareByDescending<IndexedValue<Pair<String, List<T>>>> { it.value.second.size }.thenBy { it.index })
            for ((_, g) in sorted) {
                if (out.size >= max) break
                var n = 0
                // zenders met een logo eerst (de rij is een rij logo's), verder de volgorde van de server
                for (pass in 0..1) {
                    for (t in g.second) {
                        if (n >= PER_GROUP || out.size >= max) break
                        if (hasLogo(t) != (pass == 0)) continue
                        if (add(t)) n++
                    }
                }
            }
            return out
        }
    }
}
