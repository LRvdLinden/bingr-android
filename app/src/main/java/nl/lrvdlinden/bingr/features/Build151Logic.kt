package nl.lrvdlinden.bingr

/**
 * Build 151: pure logica (zonder Android, getest in tools/test/Build151LogicTest.kt en, voor Samsung TV en LG, in
 * tizen/test/build151_test.js met dezelfde gevallen uit tools/test/build151_cases.json; js/build151.js is dezelfde code).
 *
 * - [Channels]: de rij "Zenders" op Home: zenderlogo's van de eigen server (zoals een rij streamingdiensten). Eerst de
 *   recente zenders en favorieten, aangevuld met zenders uit de grootste zichtbare live-categorieën (met logo eerst),
 *   hooguit [Channels.MAX]. Dezelfde zender in HD/FHD/4K of met "NL |" ervoor telt één keer. Kinderprofiel: alleen
 *   zenders die het kinderfilter toelaat.
 * - [Rows]: elke rij op Home ontdubbelen over servers heen: dezelfde titel (zelfde TMDB/IMDb-id, of anders
 *   genormaliseerde titel + jaar; zenders op de opgeschoonde naam) maar één tegel, op de plek waar hij het eerst staat.
 *   Welke bron blijft: in de audio- of app-taal, dan de actieve server, dan de rest ([Rows.rank]); bij Verder kijken de
 *   meest recente voortgang. De andere servers blijven als "Andere bronnen" op de detailpagina.
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

        private val memo = java.util.concurrent.ConcurrentHashMap<String, String>()
        /**
         * Build 153: zendernaam zoals de app hem overal toont (Live TV, Zenders, tv-gids, speler, favorieten, zoeken):
         * [name] met cache ("┃NL┃ NPO 1 HD" → "NPO 1"). Afspelen, id's en sleutels blijven de ruwe naam gebruiken.
         */
        fun display(raw: String): String = memo[raw] ?: name(raw).also { if (memo.size > 50_000) memo.clear(); memo[raw] = it }

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

    object Rows {
        /** Titelsleutel: genormaliseerde titel (taal/kwaliteit eraf, zoals zoeken) + jaar. */
        fun titleKey(title: String, year: Int?): String =
            Build149Logic.Search.key(title).replace(" ", "") + "|" + (year?.toString() ?: "")

        /**
         * Rangorde van een bron (lager = beter): eerst een label in [preferred] (audio- of app-taal, uit de categorie of
         * de titel: "NL | …"), dan meertalig, dan de rest; daarbinnen de actieve server voor een andere.
         */
        fun rank(tags: List<String>, active: Boolean, preferred: List<String>): Long {
            val up = tags.map { it.uppercase() }
            val p = preferred.map { it.uppercase() }
            val i = p.indexOfFirst { it in up || (it == "NL" && "BE" in up) }
            val lang = if (i >= 0) i else p.size + (if ("MULTI" in up) 0 else 1)
            return lang * 2L + (if (active) 0 else 1)
        }

        /** Labels van een bron: uit de categorie en uit de titel ("| NL | Netflix", "NL | Dune"). */
        fun tags(group: String, title: String): List<String> =
            (Build150Logic.CategoryName.cached(group).tags + Build150Logic.CategoryName.strip(title).tags).distinct()

        /**
         * Ontdubbelen: items met hetzelfde id ([id], TMDB of IMDb) of dezelfde [titleKey] vormen één groep (ook als het ene
         * item een id heeft en het andere niet). Per groep blijft het item met de laagste [rank] (gelijk: het eerste), op
         * de plek van het eerste item van die groep. [title] leeg = altijd uniek (geen titel om op te vergelijken).
         */
        fun <T> dedupe(items: List<T>, id: (T) -> String?, title: (T) -> String, year: (T) -> Int?, rank: (T) -> Long): List<T> {
            if (items.size < 2) return items
            val byId = HashMap<String, Int>(); val byTitle = HashMap<String, Int>()
            val best = ArrayList<Int>()       // groep → index van het beste item
            val bestRank = ArrayList<Long>()
            for ((ix, x) in items.withIndex()) {
                val i = id(x)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                val t = title(x).let { if (Build149Logic.Search.key(it).isBlank()) null else titleKey(it, year(x)) }
                val g = (i?.let { byId[it] } ?: t?.let { byTitle[it] }) ?: run { best += ix; bestRank += rank(x); best.size - 1 }
                if (i != null) byId.putIfAbsent(i, g)
                if (t != null) byTitle.putIfAbsent(t, g)
                if (best[g] != ix) { val r = rank(x); if (r < bestRank[g]) { best[g] = ix; bestRank[g] = r } }
            }
            if (best.size == items.size) return items
            return best.map { items[it] }
        }
    }
}
