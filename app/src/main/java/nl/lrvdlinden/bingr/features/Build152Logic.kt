package nl.lrvdlinden.bingr

import java.util.Locale

/**
 * Build 152: pure logica (zonder Android, getest in tools/test/Build152LogicTest.kt).
 *
 * - [HiddenCategories]: verborgen categorieën (account-set `account.hiddenCategories`, docs/ACCOUNT.md build 140) samen
 *   met het overslaan tijdens het inlezen (build 149) en het samenvoegen op de opgeschoonde naam (build 150):
 *   - sleutels van een samengevoegde regel: altijd per bron, met de **exacte ruwe naam** ([HiddenCategories.keysFor]);
 *   - welke overgeslagen categorieën weer aan staan, ook op de andere servers ([HiddenCategories.comeBack]);
 *   - een item dat niet in de gefilterde catalogus staat is "verborgen", niet "weg" ([HiddenCategories.presence]), zodat
 *     Mijn lijst/Verder kijken/recente zenders van een andere server geen tegel uit de meta tonen en er geen remap komt;
 *   - "eerst gezien" voor Nieuw op Bingr: een categorie die weer aan gaat, is niet ineens helemaal nieuw
 *     ([HiddenCategories.firstSeen]).
 * - [Cinema]: de rij "Nu in de bioscoop" (TMDB `/movie/now_playing`, populairste eerst, max. 20).
 */
object Build152Logic {

    object HiddenCategories {
        /** Sleutel "<server-id kleine letters>|<categorienaam exact>" (zelfde als Build140Logic.CatMarks.key). */
        fun key(sid: String, rawName: String): String = sid.lowercase(Locale.ROOT) + "|" + rawName

        /**
         * Sleutels voor een samengevoegde regel (bijv. "| NL | NETFLIX" + "| MULTI / LINGO | NETFLIX" = "Netflix"): per bron
         * ([sources] = ruwe namen) en per server waar die bron voorkomt ([serversOf]). Nooit de opgeschoonde naam.
         */
        fun keysFor(sources: List<String>, serversOf: (String) -> List<String>): List<String> {
            val out = LinkedHashSet<String>()
            for (raw in sources) for (sid in serversOf(raw)) out += key(sid, raw)
            return out.toList()
        }

        /**
         * Overgeslagen categorieën ("live|naam", "movie|naam", "series|naam", zoals Library.hiddenGroups) waarvan er nu
         * één weer zichtbaar is ([hidden] = verborgen voor het actieve profiel, ruwe naam): dan moet die server opnieuw
         * opgehaald worden.
         */
        fun comeBack(skipped: Collection<String>, hidden: (String) -> Boolean): Boolean =
            skipped.any { k -> val bar = k.indexOf('|'); bar > 0 && !hidden(k.substring(bar + 1)) }

        enum class Presence { FOUND, FILTERED, GONE }

        /**
         * Staat een item (nog) op zijn server? [inFiltered] = in de gefilterde catalogus (wat het profiel ziet), [inRaw] =
         * in de ruwe catalogus, [rawSkipped] = de ruwe catalogus heeft overgeslagen categorieën (build 149: die items
         * staan nergens, dus "niet gevonden" bewijst dan niet dat het weg is). Alleen GONE mag als weg tellen.
         */
        fun presence(inFiltered: Boolean, inRaw: Boolean?, rawSkipped: Boolean): Presence = when {
            inFiltered -> Presence.FOUND
            inRaw == true -> Presence.FILTERED
            inRaw == null -> Presence.FILTERED   // ruwe catalogus niet te lezen: niet als weg behandelen
            rawSkipped -> Presence.FILTERED
            else -> Presence.GONE
        }

        /**
         * "Eerst gezien" (Nieuw op Bingr) bijwerken. [items] = (id, ruwe categorie) van de catalogus; [wasSkipped] =
         * categorieën die bij de vorige keer overgeslagen waren; [skippedNow] = de catalogus heeft nu overgeslagen
         * categorieën. Een nieuwe id uit een categorie die eerst overgeslagen was krijgt 0 (onbekend, niet "nieuw");
         * met [skippedNow] blijven bekende ids die er niet zijn staan (ze zitten mogelijk in een verborgen categorie).
         */
        fun firstSeen(known: Map<String, Long>?, items: List<Pair<String, String>>, now: Long, wasSkipped: Set<String>, skippedNow: Boolean): Map<String, Long> {
            if (known == null) return items.associate { it.first to 0L }
            val out = HashMap<String, Long>(items.size * 2)
            if (skippedNow) out.putAll(known)
            for ((id, g) in items) out[id] = known[id] ?: if (g in wasSkipped) 0L else now
            return out
        }
    }

    object Cinema {
        const val MAX = 20

        /** Eén film uit TMDB now_playing (alleen wat de keuze nodig heeft). */
        data class Item(val id: Int, val title: String, val poster: String?, val popularity: Double, val adult: Boolean = false)

        /** Populairste eerst, zonder poster/adult/dubbele, hooguit [max]. */
        fun pick(items: List<Item>, max: Int = MAX): List<Item> {
            val seen = HashSet<Int>()
            return items.filter { !it.adult && !it.poster.isNullOrEmpty() && it.title.isNotBlank() }
                .sortedWith(compareByDescending<Item> { it.popularity }.thenBy { it.title })
                .filter { seen.add(it.id) }
                .take(max)
        }
    }
}
