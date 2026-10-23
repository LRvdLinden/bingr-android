package nl.lrvdlinden.bingr

/**
 * Build 144: pure logica (zonder Android, getest in tools/test/Build144LogicTest.kt en, voor Samsung TV, in
 * tizen/test/build144_test.js met dezelfde gevallen).
 *
 * - [ContinueCover]: welke afbeelding een tegel in Verder kijken krijgt. Gelijk aan de Apple-app (build 143): de echte poster
 *   van de film of serie, ook bij oude voortgang en bij voortgang van een ander apparaat (account-sync). Tot build 143 viel
 *   Android terug op het bewaarde beeld (bij een aflevering vaak de scène) zodra de bibliotheek geen poster had.
 * - [AutoRefresh]: wanneer het automatisch verversen na het openen mag beginnen (niet bij het openen zelf).
 */
object Build144Logic {

    object ContinueCover {
        /** Een titel waarvan de TMDB-poster opgezocht moet worden. */
        data class Want(val title: String, val year: Int?, val isShow: Boolean)

        /** Hoeveel posters per keer bij TMDB worden opgezocht. */
        const val MAX_LOOKUPS = 12

        private fun ok(s: String?): String? = s?.takeIf { it.isNotBlank() }

        /**
         * Sleutel van de bewaarde TMDB-poster: "m|<schone titel>|<jaar>" of "s|…" (jaar 0 = onbekend). Gelijk aan
         * ContinueCovers.key op Apple: de titel zonder landcode, kwaliteit en jaar ([TitleCleaner]).
         */
        fun key(title: String, year: Int?, isShow: Boolean): String {
            val p = TitleCleaner.parse(title)
            return (if (isShow) "s|" else "m|") + p.title.lowercase() + "|" + (year ?: p.year ?: 0)
        }

        /**
         * Cataloguspost die alleen het bewaarde beeld herhaalt (bij voortgang zonder titel in de catalogus legt de app het
         * beeld van het afspelen vast als afbeelding van de serie): dat is geen poster.
         */
        private fun realMeta(meta: String?, stored: String?): String? = ok(meta)?.takeIf { it != ok(stored) }

        /**
         * Afbeelding van de tegel, in deze volgorde:
         * 1. de poster uit de bibliotheek ([library]) of de catalogmeta van de server ([meta]);
         * 2. anders de TMDB-poster ([tmdb], bewaard op het apparaat);
         * 3. pas als er niets anders is het bewaarde beeld van de voortgang ([stored], vaak de scène).
         */
        fun pick(library: String?, meta: String?, tmdb: String?, stored: String?): String? =
            ok(library) ?: realMeta(meta, stored) ?: ok(tmdb) ?: ok(stored)

        /** Moet de TMDB-poster opgezocht worden? Alleen als de bibliotheek en de catalogmeta geen poster hebben. */
        fun needsLookup(library: String?, meta: String?, stored: String?): Boolean =
            ok(library) == null && realMeta(meta, stored) == null

        /**
         * Welke titels nu opgezocht worden: nog niet bekend (ook niet "gezocht, niets gevonden"), niet al onderweg, elke
         * sleutel één keer, hooguit [max]. Lege titels nooit.
         */
        fun pending(wants: List<Want>, known: (String) -> Boolean, inFlight: Set<String>, max: Int = MAX_LOOKUPS): List<Pair<String, Want>> {
            val out = ArrayList<Pair<String, Want>>()
            val seen = HashSet<String>()
            for (w in wants) {
                if (out.size >= max) break
                if (w.title.isBlank()) continue
                val k = key(w.title, w.year, w.isShow)
                if (known(k) || k in inFlight || !seen.add(k)) continue
                out += k to w
            }
            return out
        }

        /** Zoekopdracht voor TMDB: schone titel en jaar (uit de bibliotheek, anders uit de titel). */
        fun query(w: Want): Pair<String, Int?> {
            val p = TitleCleaner.parse(w.title)
            return p.title to (w.year ?: p.year)
        }
    }

    object AutoRefresh {
        /**
         * Zo lang (sinds de start van de app) wordt er na het openen met een bewaarde catalogus niets opgehaald. Gelijk aan
         * PlaylistService.autoSettle op Apple (45 s).
         */
        const val SETTLE_MS = 45_000L
        /** Andere servers zonder bewaarde catalogus pas van het netwerk na de eerste minuut (CrossServer op Apple). */
        const val OTHERS_NETWORK_AFTER_MS = 60_000L

        /**
         * Mag het automatisch verversen nu beginnen? Pas na [SETTLE_MS], alleen op het hoofdscherm ([onMain]: niet in
         * "Wie kijkt er?" of het pincodescherm) en nooit tijdens het afspelen.
         */
        fun mayStart(uptimeMs: Long, onMain: Boolean, playing: Boolean): Boolean =
            uptimeMs >= SETTLE_MS && onMain && !playing

        /** Hoe lang wachten voor de volgende controle (minstens 5 s). */
        fun waitMs(uptimeMs: Long): Long = maxOf(5_000L, SETTLE_MS - uptimeMs)

        /** Hoort dit verversen nog bij de open server en het open profiel ([started] = stand bij het plannen)? */
        fun stillCurrent(started: Int, now: Int): Boolean = started == now
    }
}
