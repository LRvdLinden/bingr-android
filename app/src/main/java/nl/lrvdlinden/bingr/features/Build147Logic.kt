package nl.lrvdlinden.bingr

/**
 * Build 147: pure logica (zonder Android, getest in tools/test/Build147LogicTest.kt en, voor Samsung TV, in
 * tizen/test/build147_test.js met dezelfde gevallen). Diagnose-schema 2 (docs/ACCOUNT.md in iptv-ios-appletv, "Build 147").
 *
 * - [Start]: eerlijke starttijden (app-tijd los van wachten op de gebruiker) en de samenvatting goed / traag / vastgelopen.
 * - [Hang]: hang-detectie op de hoofdthread (ping elke seconde, geen antwoord binnen 2 s = hang), met taak, voorgrond en
 *   een momentopname van de stack; een hang op de achtergrond telt niet.
 * - [Previous]: de vorige start als `start.previous` (none / hang / crash / not_finished).
 * - [Fetch]: per server en soort (zenders, films, series): geleverd, provider leverde niets, ophalen mislukt (korte
 *   foutcode zonder URL) of alles verborgen.
 * - [Storage]: één totaal dat precies optelt uit cache, catalogus en downloads.
 * - [NetType]: wifi / cellular / ethernet / unknown.
 * - [Slices]: werk in kleine stukken met een tijdsbudget (de UI krijgt alleen het eindresultaat).
 */
object Build147Logic {

    // MARK: eerlijke starttijden

    object Start {
        /** Traag: app-tijd boven 5 s, of een hang boven 2 s. Vastgelopen: een hang boven 10 s of een crash. */
        const val SLOW_APP_MS = 5_000L
        const val SLOW_HANG_MS = 2_000L
        const val HUNG_MS = 10_000L
        const val FINAL = "ready"

        /** Mijlpaalparen waartussen de app op de gebruiker wacht (profielkeuze, pincode). */
        val WAITS = listOf("profiles" to "profile_picked", "pin" to "pin_ok")

        data class Timing(val totalMs: Long, val appMs: Long, val userWaitMs: Long, val finished: Boolean)

        /**
         * [marks] = (naam, ms sinds de start). Totaal = tot "ready" (of de laatste mijlpaal). Wachten = per paar uit [WAITS]
         * het stuk tussen begin en eind (eind ontbreekt en er is geen "ready": tot de laatste mijlpaal); app-tijd = de rest.
         */
        fun timing(marks: List<Pair<String, Long>>): Timing {
            val at = HashMap<String, Long>()
            for ((n, ms) in marks) if (n !in at) at[n] = ms
            val ready = at[FINAL]
            val total = ready ?: (marks.maxOfOrNull { it.second } ?: 0L)
            var wait = 0L
            for ((a, b) in WAITS) {
                val s = at[a] ?: continue
                if (s > total) continue
                val e = (at[b] ?: total).coerceAtMost(total)
                if (e > s) wait += e - s
            }
            wait = wait.coerceIn(0L, total)
            return Timing(total, total - wait, wait, ready != null)
        }

        /** `good` | `slow` | `hung`. [maxHangMs] = langste hang op de voorgrond (achtergrond telt niet). */
        fun summary(appMs: Long, maxHangMs: Long, crashed: Boolean): String = when {
            crashed || maxHangMs > HUNG_MS -> "hung"
            appMs > SLOW_APP_MS || maxHangMs > SLOW_HANG_MS -> "slow"
            else -> "good"
        }

        /** Engelse sleutel (Translations.json) voor het scherm: "Start: good" enz. */
        fun label(summary: String): String = when (summary) {
            "hung" -> "Start: stuck"
            "slow" -> "Start: slow"
            else -> "Start: good"
        }
    }

    // MARK: hang-detectie

    object Hang {
        const val PING_MS = 1_000L
        /** Geen antwoord van de hoofdthread binnen 2 s = hang. */
        const val LIMIT_MS = 2_000L
        const val MAX_STACK = 30

        data class Event(
            val startedAt: Long,
            val ms: Long,
            val task: String?,
            val foreground: Boolean,
            val afterReady: Boolean,
            val stack: List<String>,
            /** mijlpaal (de laatste vóór de hang) */
            val milestone: String?,
        )

        /**
         * Volgt de pings: [sent] bij elke ping, [answered] als de hoofdthread hem heeft afgehandeld, [check] periodiek vanuit
         * de bewakingsthread. Een hang die op de voorgrond begint of ooit op de voorgrond is, telt als voorgrond; de stack
         * wordt één keer bij het begin van de hang opgehaald en nog eens als hij langer dan 10 s duurt (dan is te zien waar
         * hij écht vastzit).
         */
        class Tracker(private val limitMs: Long = LIMIT_MS) {
            private var sentAt = -1L
            private var current: Event? = null
            private var deepStack = false
            /** Langste hang op de voorgrond (ms) */
            var maxForegroundMs = 0L; private set
            /** Langste hang (voorgrond voorgaand op achtergrond), afgerond of nog bezig */
            var worst: Event? = null; private set
            var count = 0; private set

            val pending: Boolean get() = sentAt >= 0

            fun sent(now: Long) { if (sentAt < 0) sentAt = now }

            /** Hoofdthread antwoordde: een lopende hang is voorbij. Geeft de afgeronde hang (of null). */
            fun answered(now: Long): Event? {
                val c = current
                sentAt = -1L
                current = null
                deepStack = false
                return c
            }

            /**
             * Controle: zit de hoofdthread al [limitMs] vast, dan de (bijgewerkte) hang. [stack] wordt alleen aangeroepen als
             * er een momentopname nodig is.
             */
            fun check(now: Long, foreground: Boolean, task: String?, afterReady: Boolean, milestone: String?, stack: () -> List<String>): Event? {
                if (sentAt < 0) return null
                val stuck = now - sentAt
                if (stuck < limitMs) return null
                val c = current
                val e = if (c == null) {
                    count++
                    Event(sentAt, stuck, task, foreground, afterReady, stack().take(MAX_STACK), milestone)
                } else {
                    val wantDeep = !deepStack && stuck >= Start.HUNG_MS
                    if (wantDeep) deepStack = true
                    c.copy(ms = stuck, foreground = c.foreground || foreground, task = c.task ?: task,
                        stack = if (wantDeep) stack().take(MAX_STACK).ifEmpty { c.stack } else c.stack)
                }
                current = e
                if (e.foreground && e.ms > maxForegroundMs) maxForegroundMs = e.ms
                val w = worst
                if (w == null || better(e, w)) worst = e
                return e
            }

            private fun better(a: Event, b: Event): Boolean = when {
                a.foreground != b.foreground -> a.foreground
                else -> a.ms > b.ms
            }
        }
    }

    // MARK: vorige start

    object Previous {
        data class Info(
            val problem: String,
            val milestone: String?,
            val seconds: Double,
            val foreground: Boolean,
            val task: String?,
            val stack: List<String>,
        )

        /**
         * `start.previous`: crash > hang (voorgrond, boven 2 s) > niet klaar > none. [hangMs]/[hangFg] = langste hang van die
         * start; [lastMs] = tijd van de laatste mijlpaal.
         */
        fun of(crashed: Boolean, crashStack: List<String>, finished: Boolean, lastMark: String?, lastMs: Long,
               hangMs: Long?, hangFg: Boolean, hangAt: String?, hangTask: String?, hangStack: List<String>): Info {
            fun s(ms: Long) = Math.round(ms / 100.0) / 10.0
            return when {
                crashed -> Info("crash", lastMark, s(lastMs), true, null, crashStack.take(50))
                hangMs != null && hangFg && hangMs > Start.SLOW_HANG_MS ->
                    Info("hang", hangAt ?: lastMark, s(hangMs), true, hangTask, hangStack.take(50))
                !finished -> Info("not_finished", lastMark, s(lastMs), true, null, emptyList())
                else -> Info("none", lastMark, s(lastMs), hangFg, if (hangMs != null) hangTask else null,
                    if (hangMs != null) hangStack.take(50) else emptyList())
            }
        }
    }

    // MARK: ophalen per server en soort

    object Fetch {
        val KINDS = listOf("live", "movie", "series")

        data class Row(val server: String, val kind: String, val status: String, val count: Int, val error: String?)

        /**
         * [raw] = wat de provider leverde (null = niet opgehaald), [visible] = na verborgen categorieën en het kinderfilter,
         * [error] = korte foutcode als het ophalen mislukte.
         */
        fun status(raw: Int?, visible: Int?, error: String?): String = when {
            error != null && (raw == null || raw == 0) -> "failed"
            raw == null || raw == 0 -> "empty"
            visible != null && visible == 0 -> "hidden"
            else -> "ok"
        }

        private val codeRe = Regex("[^a-z0-9_]+")

        /**
         * Korte foutcode zonder URL of andere gegevens: `timeout`, `http_<code>`, `parse`, `network`, `auth`, `cancelled`
         * of `error`. [type] = klassenaam van de fout, [message] = foutmelding (wordt alleen herkend, nooit meegestuurd).
         */
        fun errorCode(type: String?, message: String?, httpCode: Int? = null): String {
            if (httpCode != null && httpCode in 100..599) return "http_$httpCode"
            val t = (type ?: "").lowercase()
            val m = (message ?: "").lowercase()
            Regex("\\b(?:http|status)[\\s:_]*([1-5][0-9]{2})\\b").find(m)?.let { return "http_" + it.groupValues[1] }
            return when {
                "timeout" in t || "timeout" in m || "timed out" in m -> "timeout"
                "cancel" in t -> "cancelled"
                "json" in t || "serializ" in t || "decod" in t || "pars" in m || "json" in m -> "parse"
                "unknownhost" in t || "connect" in t || "socket" in t || "ssl" in t || "eof" in t || "network" in m -> "network"
                "auth" in m || "sign in" in m || "inloggen" in m -> "auth"
                else -> "error"
            }.let { codeRe.replace(it, "_").take(24) }
        }

        /** Anonieme servernamen: s1, s2, … in de volgorde van de lijst. */
        fun serverName(index: Int): String = "s${index + 1}"
    }

    // MARK: opslag

    object Storage {
        data class Parts(val totalMB: Int, val cacheMB: Int, val catalogMB: Int, val downloadsMB: Int, val freeMB: Int?)

        private fun mb(b: Long): Int = ((b.coerceAtLeast(0L) + 524_288L) / 1_048_576L).toInt()

        /** Totaal = cache + catalogus + downloads (afgerond per deel, zodat het totaal precies optelt). */
        fun of(cacheBytes: Long, catalogBytes: Long, downloadsBytes: Long, freeBytes: Long?): Parts {
            val c = mb(cacheBytes); val k = mb(catalogBytes); val d = mb(downloadsBytes)
            return Parts(c + k + d, c, k, d, freeBytes?.let { mb(it) })
        }
    }

    // MARK: soort verbinding

    object NetType {
        /** Volgorde zoals het systeem: ethernet gaat voor wifi (tv's hebben vaak beide), dan mobiel. */
        fun of(ethernet: Boolean, wifi: Boolean, cellular: Boolean): String = when {
            ethernet -> "ethernet"
            wifi -> "wifi"
            cellular -> "cellular"
            else -> "unknown"
        }

        /** Engelse sleutel (Translations.json) voor het scherm. */
        fun label(type: String): String = when (type) {
            "wifi" -> "Wi-Fi"
            "cellular" -> "Mobile"
            "ethernet" -> "Ethernet"
            "none" -> "No connection"
            else -> "Unknown"
        }
    }

    // MARK: werk in stukken

    object Slices {
        /** Langste aaneengesloten stuk werk per keer op een thread die ook iets anders moet doen. */
        const val BUDGET_MS = 12L

        /**
         * [list] verwerken in stukken van hooguit [budgetMs] (gemeten met [clock]); tussen de stukken [yieldNow] (bijv.
         * `yield()` in een coroutine of een nieuwe post). Geeft het aantal stukken.
         */
        inline fun <T> run(list: List<T>, budgetMs: Long = BUDGET_MS, clock: () -> Long, yieldNow: () -> Unit, each: (T) -> Unit): Int {
            var i = 0
            var slices = 0
            while (i < list.size) {
                val t0 = clock()
                slices++
                while (i < list.size) {
                    each(list[i])
                    i++
                    if ((i and 63) == 0 && clock() - t0 >= budgetMs) break
                }
                if (i < list.size) yieldNow()
            }
            return slices
        }
    }
}
