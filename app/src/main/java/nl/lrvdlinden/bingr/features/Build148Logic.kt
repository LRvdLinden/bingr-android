package nl.lrvdlinden.bingr

/**
 * Build 148: pure logica (zonder Android, getest in tools/test/Build148LogicTest.kt en, voor Samsung TV, in
 * tizen/test/build148_test.js met dezelfde gevallen). Diagnose-schema 3 (docs/ACCOUNT.md in iptv-ios-appletv, "Build 148").
 *
 * - [Codes]: mijlpalen, taken en fasen als vaste codes (snake_case Engels); oude namen van schema 1/2 worden omgezet.
 *   De app vertaalt de codes zelf ([Codes.label] = Engelse sleutel in Translations.json); de bijlage bevat alleen codes.
 * - [Updates]: bijwerken van een server in fasen (ophalen → inlezen → indexeren → toepassen → bewaren), de laatste 5.
 * - [Hidden]: per soort "geleverd" naast "zichtbaar", met de reden van het verschil.
 * - [Stalls]: haperingen tijdens het afspelen (`playback_stall`).
 */
object Build148Logic {

    // MARK: codes

    object Codes {
        /** Vaste lijst uit het contract (build 148). */
        val FIXED = listOf(
            "started", "storage", "account", "first_frame", "intro_done", "profile_picker", "profile_picked", "pin_screen",
            "pin_ok", "profile", "servers", "home", "catalog", "ready", "fetch", "parse", "index", "apply", "save",
            "sync_apply", "catalog_filter", "catalog_sort", "catalog_meta", "pin_migrate", "rows_build", "guide_parse",
        )
        /** Nieuw in build 148 (zelfde stijl, gemeld bij de server-agent). */
        val NEW = listOf("categories_migrate", "pin_hash", "pin_check", "track_prefs", "playback_stall")
        val ALL: List<String> = FIXED + NEW

        private val RE = Regex("^[a-z][a-z0-9_]{0,39}$")
        fun valid(code: String?): Boolean = code != null && RE.matches(code)

        /** Oude namen (mijlpalen en taken van schema 1/2, ook de Nederlandse taaknamen van build 147) → code. */
        private val OLD = mapOf(
            "start" to "started", "launch" to "started", "app" to "storage", "activity" to "first_frame",
            "splash" to "first_frame", "auth" to "account", "session" to "account", "profiles" to "profile_picker",
            "pin" to "pin_screen", "servers_loaded" to "servers", "sources" to "servers", "epg" to "guide_parse",
            "categorieën migreren" to "categories_migrate", "sync toepassen" to "sync_apply", "pin hashen" to "pin_hash",
            "pin controleren" to "pin_check", "pin migreren" to "pin_migrate", "catalogus verwerken" to "parse",
            "catalogus filteren" to "catalog_filter", "catalogus sorteren" to "catalog_sort", "indexeren" to "index",
            "bibliotheek toepassen" to "apply", "rijen opbouwen" to "rows_build", "gids inlezen" to "guide_parse",
        )

        /** Naam (oud of al een code) → code; null als er geen geldige code van te maken is. */
        fun of(name: String?): String? {
            if (name == null) return null
            OLD[name]?.let { return it }
            return name.takeIf { valid(it) }
        }

        /** Engelse sleutel (Translations.json) voor het scherm; een onbekende code wordt als code getoond. */
        fun label(code: String?): String = when (code) {
            "started" -> "Started"; "storage" -> "Storage loaded"; "account" -> "Account"; "first_frame" -> "First screen"
            "intro_done" -> "Intro done"; "profile_picker" -> "Profile picker"; "profile_picked" -> "Profile chosen"
            "pin_screen" -> "PIN screen"; "pin_ok" -> "PIN correct"; "profile" -> "Profile opened"; "servers" -> "Servers loaded"
            "home" -> "Home"; "catalog" -> "Catalog"; "ready" -> "Ready"; "fetch" -> "Fetching"; "parse" -> "Reading"
            "index" -> "Indexing"; "apply" -> "Applying"; "save" -> "Saving"; "sync_apply" -> "Applying sync"
            "catalog_filter" -> "Filtering catalog"; "catalog_sort" -> "Sorting catalog"; "catalog_meta" -> "Catalog details"
            "pin_migrate" -> "Migrating PIN"; "rows_build" -> "Building rows"; "guide_parse" -> "Reading TV guide"
            "categories_migrate" -> "Migrating categories"; "pin_hash" -> "Securing PIN"; "pin_check" -> "Checking PIN"
            "track_prefs" -> "Choosing audio and subtitles"; "playback_stall" -> "Playback stall"
            else -> code ?: ""
        }

        /** Mijlpalen als codes, elke code één keer (de eerste telt). */
        fun <T> dedupe(marks: List<T>, name: (T) -> String): List<Pair<String, T>> {
            val seen = HashSet<String>()
            val out = ArrayList<Pair<String, T>>()
            for (m in marks) { val c = of(name(m)) ?: continue; if (seen.add(c)) out += c to m }
            return out
        }
    }

    // MARK: bijwerken per server

    object Updates {
        val PHASES = listOf("fetch", "parse", "index", "apply", "save")
        const val MAX = 5

        data class Phase(val code: String, val ms: Long, val memMB: Int)
        data class Update(val server: String, val at: Long, val phases: List<Phase>, val totalMs: Long, val peakMB: Int, val result: String)

        /**
         * Meet één keer bijwerken: [phase] sluit de lopende fase af (tijd sinds de vorige mark telt bij [code]; dezelfde
         * code twee keer telt op). [finish] geeft het resultaat met de fasen in vaste volgorde.
         */
        class Recorder(private val server: String, private val at: Long, private val start: Long, startMB: Int) {
            private var last = start
            private val ms = LinkedHashMap<String, Long>()
            private val mem = HashMap<String, Int>()
            var peakMB = startMB; private set

            fun sample(mb: Int) { if (mb > peakMB) peakMB = mb }

            fun phase(code: String, now: Long, mb: Int) {
                if (code !in PHASES) return
                ms[code] = (ms[code] ?: 0L) + (now - last).coerceAtLeast(0L)
                mem[code] = mb
                last = now
                sample(mb)
            }

            fun finish(result: String, now: Long): Update {
                val phases = PHASES.mapNotNull { c -> ms[c]?.let { Phase(c, it.coerceIn(0L, 3_600_000L), (mem[c] ?: 0).coerceIn(0, 100_000)) } }
                val r = if (result in setOf("ok", "failed", "cancelled")) result else "failed"
                return Update(server, at, phases, (now - start).coerceIn(0L, 3_600_000L), peakMB.coerceIn(0, 100_000), r)
            }
        }

        /** Nieuwste eerst, hooguit [MAX]. */
        fun push(list: List<Update>, u: Update): List<Update> = (listOf(u) + list).take(MAX)
    }

    // MARK: geleverd en zichtbaar

    object Hidden {
        data class Counts(val delivered: Int, val visible: Int, val categories: Int, val kids: Int, val duplicates: Int, val other: Int)

        /** Rest van het verschil = overig (leeftijd, kinderslot, zelf verborgen titels). Nooit negatief. */
        fun of(delivered: Int, visible: Int, categories: Int, kids: Int, duplicates: Int): Counts {
            val d = delivered.coerceAtLeast(0)
            val v = visible.coerceIn(0, d)
            var gap = d - v
            val c = categories.coerceIn(0, gap); gap -= c
            val k = kids.coerceIn(0, gap); gap -= k
            val du = duplicates.coerceIn(0, gap); gap -= du
            return Counts(d, v, c, k, du, gap)
        }

        /**
         * Per item de reden van verbergen: eerst verborgen categorieën, dan het kinderfilter, de rest is "overig".
         * [hiddenCategory]/[notKids] per groep één keer uitgerekend (tienduizenden titels delen een paar honderd groepen).
         */
        fun <T> count(items: List<T>, visible: Int, group: (T) -> String, hiddenCategory: (String) -> Boolean, notKids: (String) -> Boolean): Counts {
            val memo = HashMap<String, Int>()
            var cats = 0; var kids = 0
            for (x in items) {
                val g = group(x)
                val r = memo.getOrPut(g) { if (hiddenCategory(g)) 1 else if (notKids(g)) 2 else 0 }
                if (r == 1) cats++ else if (r == 2) kids++
            }
            return of(items.size, visible, cats, kids, 0)
        }
    }

    // MARK: haperingen tijdens het afspelen

    object Stalls {
        /** Korter dan dit telt niet (een korte buffer-tik). */
        const val MIN_MS = 500L
        const val MAX = 10

        data class Stall(val ms: Long, val atS: Int, val live: Boolean)

        /** Volgt de buffer-toestand na het eerste beeld. [update] elke tik; geeft een afgeronde hapering of null. */
        class Tracker {
            private var playedAt = -1L
            private var since = -1L
            fun update(now: Long, playing: Boolean, buffering: Boolean, live: Boolean): Stall? {
                if (playing && !buffering) {
                    if (playedAt < 0) playedAt = now
                    val s = since
                    since = -1L
                    if (s >= 0 && now - s >= MIN_MS) return Stall(now - s, ((s - playedAt) / 1000).toInt().coerceAtLeast(0), live)
                    return null
                }
                if (buffering && playedAt >= 0 && since < 0) since = now
                return null
            }
            fun reset() { playedAt = -1L; since = -1L }
        }

        fun push(list: List<Stall>, s: Stall): List<Stall> = (listOf(s) + list).take(MAX)
    }
}
