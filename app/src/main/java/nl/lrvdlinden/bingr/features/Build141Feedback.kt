package nl.lrvdlinden.bingr

import java.util.PriorityQueue

/**
 * Build 141: feedback van gebruikers (naast de beveiliging in [Build141Logic]). Pure logica zonder Android, zodat hij
 * los te testen is (tools/test/Build141FeedbackTest.kt, ook in de workflow Build). Zelfde regels als de Samsung TV-app
 * (tizen/js/build141.js).
 *
 * - [Recent]: de nieuwste N titels zonder de hele bibliotheek te sorteren of te kopiëren (Nieuw op Bingr).
 * - [HomeRows]: de rij "Nieuw toegevoegd" is weg; "Nieuw op Bingr" staat op zijn plek.
 * - [MyListKeys]: Mijn lijst-sleutels van TMDB-titels (ook van een andere server) en aanbevelingen.
 * - [ContinueArt]: Verder kijken toont de poster van de film of serie, niet een still van de aflevering.
 * - [Background]: wanneer zwaar achtergrondwerk (andere servers) mag na het kiezen van een profiel.
 */
object Build141Feedback {

    // MARK: Nieuw op Bingr

    object Recent {
        /**
         * De [k] items met de hoogste datum ([date] > 0), nieuwste eerst; bij gelijke datum op [tie] (alfabetisch).
         * Eén keer door de lijst met een heap van maximaal [k] items (geen sortering van tienduizenden titels, geen kopieën).
         */
        fun <T> top(items: Sequence<T>, k: Int, date: (T) -> Long, tie: (T) -> String): List<Pair<T, Long>> {
            if (k <= 0) return emptyList()
            val cmp = compareBy<Pair<T, Long>> { it.second }.thenByDescending { tie(it.first) }
            val heap = PriorityQueue(k + 1, cmp)   // kleinste bovenaan
            for (x in items) {
                val d = date(x)
                if (d <= 0) continue
                val p = x to d
                if (heap.size < k) heap.add(p)
                else if (cmp.compare(p, heap.peek()) > 0) { heap.poll(); heap.add(p) }
            }
            return heap.sortedWith(cmp.reversed())
        }

        /** Samenvoegen van de lijsten van meerdere servers: nieuwste eerst, dubbele ([dedup]) weg, maximaal [k]. */
        fun <T> merge(lists: List<List<Pair<T, Long>>>, k: Int, tie: (T) -> String, dedup: (T) -> String): List<T> {
            val seen = HashSet<String>()
            return lists.flatten()
                .sortedWith(compareByDescending<Pair<T, Long>> { it.second }.thenBy { tie(it.first) })
                .filter { seen.add(dedup(it.first)) }
                .take(k).map { it.first }
        }
    }

    // MARK: Home-indeling

    object HomeRows {
        const val RECENT = "recent"
        const val NEW_ON_BINGR = "streaming"

        /**
         * Zoals de rijen getoond (en in "Startscherm" bewerkt) worden: "Nieuw toegevoegd" ([RECENT]) verdwijnt en
         * "Nieuw op Bingr" ([NEW_ON_BINGR]) komt op zijn plek, met zijn eigen aan/uit. De opgeslagen indeling verandert
         * niet (andere apps kennen beide rijen nog), zie [forSave].
         */
        fun <R> displayed(rows: List<R>, key: (R) -> String): List<R> {
            val iRecent = rows.indexOfFirst { key(it) == RECENT }
            val iNew = rows.indexOfFirst { key(it) == NEW_ON_BINGR }
            if (iRecent < 0) return rows
            if (iNew < 0) return rows.filterIndexed { i, _ -> i != iRecent }
            val out = ArrayList<R>(rows.size - 1)
            rows.forEachIndexed { i, r ->
                when (i) {
                    iRecent -> out.add(rows[iNew])
                    iNew -> {}
                    else -> out.add(r)
                }
            }
            return out
        }

        /**
         * Bewerkte rijen ([edited], zonder [RECENT]) weer opslaan: [RECENT] terug direct vóór [NEW_ON_BINGR], met de
         * zichtbaarheid die hij had ([oldRecentVisible]; onbekend = aan), zodat iOS, web en oudere apps hun eigen rij
         * "Nieuw toegevoegd" op dezelfde plek houden.
         */
        fun <R> forSave(edited: List<R>, key: (R) -> String, recentRow: (Boolean) -> R, oldRecentVisible: Boolean?): List<R> {
            if (edited.any { key(it) == RECENT }) return edited
            val i = edited.indexOfFirst { key(it) == NEW_ON_BINGR }
            val r = recentRow(oldRecentVisible ?: true)
            if (i < 0) return edited + r
            return edited.subList(0, i) + r + edited.subList(i, edited.size)
        }
    }

    // MARK: Mijn lijst

    object MyListKeys {
        const val EXTERNAL = "tmdb-"
        const val RECOMMENDATION = "rec-"

        /** Item-id uit een sleutel "<server>|<item>" (de server-id bevat zelf geen "|"). */
        fun itemOf(key: String): String = key.substringAfter('|', key)
        fun serverOf(key: String): String? = key.indexOf('|').takeIf { it > 0 }?.let { key.substring(0, it) }

        /** TMDB-titel (niet in een bibliotheek) als item-id van deze sleutel, op welke server de sleutel ook staat. */
        fun externalID(key: String): String? = itemOf(key).takeIf { it.startsWith(EXTERNAL) }

        /** Id van een TMDB-titel ("tmdb-movie-123" / "tmdb-tv-123", zelfde op alle platforms). */
        fun externalFor(tmdbID: Int, isShow: Boolean): String = "$EXTERNAL${if (isShow) "tv" else "movie"}-$tmdbID"

        /**
         * Een aanbeveling van de detailpagina ("rec-…", niet in je bibliotheek) kan niet zo in Mijn lijst: geen ander apparaat
         * kan die sleutel terugvinden. Met een TMDB-id wordt het de TMDB-titel; zonder: null (eerst in de bibliotheek zoeken).
         */
        fun storableID(id: String, tmdbID: Int?, isShow: Boolean): String? = when {
            !id.startsWith(RECOMMENDATION) -> id
            tmdbID != null && tmdbID > 0 -> externalFor(tmdbID, isShow)
            else -> null
        }

        /** Server van een TMDB-titel in Mijn lijst: de actieve (zoals iOS en web), tenzij er al een sleutel voor bestaat. */
        fun keyFor(existing: Collection<String>, activeSid: String, itemID: String): String =
            existing.firstOrNull { itemOf(it) == itemID && itemID.startsWith(EXTERNAL) } ?: "$activeSid|$itemID"
    }

    // MARK: Verder kijken

    object ContinueArt {
        /**
         * Afbeelding van een tegel in Verder kijken: de poster van de film of serie ([titlePoster]) gaat voor; wat de
         * voortgang bewaarde ([stored], bij afleveringen vaak een still van de scène) alleen als er geen poster is.
         */
        fun pick(titlePoster: String?, metaPoster: String?, stored: String?): String? =
            titlePoster?.takeIf { it.isNotBlank() } ?: metaPoster?.takeIf { it.isNotBlank() } ?: stored?.takeIf { it.isNotBlank() }
    }

    // MARK: achtergrondwerk na het kiezen van een profiel

    object Background {
        /** Zo lang na het openen van een profiel wacht het werk aan de andere servers (de actieve eerst). */
        const val SETTLE_MS = 12_000L
        /** Zo lang na het openen begint het verversen van de actieve server (eerst het scherm, dan het netwerk). */
        const val ACTIVE_REFRESH_DELAY_MS = 6_000L

        /** Mag het zware werk aan de andere servers nu beginnen? */
        fun mayRun(playing: Boolean, loading: Boolean, updating: Boolean, filtering: Boolean, sinceOpenMs: Long): Boolean =
            !playing && !loading && !updating && !filtering && sinceOpenMs >= SETTLE_MS

        /**
         * Hoeveel catalogi van andere servers tegelijk in het geheugen mogen blijven (zacht, mag weg): op een tv of
         * toestel met een kleine heap één, anders twee.
         */
        fun otherLibrariesToKeep(maxHeapMb: Long): Int = if (maxHeapMb < 384) 1 else 2
    }
}
