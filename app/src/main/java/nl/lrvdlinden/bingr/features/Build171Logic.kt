package nl.lrvdlinden.bingr

import java.util.Locale

/**
 * Build 171 (versie 1.0.6): pure logica zonder Android, met unittests (tools/test/Build171LogicTest.kt). Zelfde regels
 * als `Shared/Build171.swift` (Apple) en de webspeler.
 *
 * - [StillWatching]: "Kijk je nog?" (3 afleveringen achter elkaar automatisch gestart, of 2/3/4 uur live of film zonder
 *   bediening; 30 s om te antwoorden).
 * - [ProfileSets]: verborgen zenders (`hiddenChannels`), geblokkeerde titels (`blockedTitles`) en vastgepinde
 *   categorieën (`pinnedCategories`) per profiel: sleutels en filteren.
 * - [Countries]: land of regio uit het voorvoegsel van een categorie of zender (de pipe-tags van build 153).
 * - [Radio]: radiozenders herkennen.
 * - [DeviceList]: "Te veel apparaten" en "Te veel streams": sorteren, voorstel om af te melden, "laatst actief".
 * - [Crash]: crashrapporten schoonmaken (URL's, IP's, e-mail, tokens), inkorten, signatuur en de wachtrij.
 */
object Build171Logic {

    // MARK: 1. Kijk je nog?

    object StillWatching {
        /** Na zoveel automatisch gestarte afleveringen achter elkaar (zonder bediening) de vraag stellen. */
        const val EPISODES = 3
        /** Zo lang (ms) om te antwoorden; daarna pauzeren en `stream_stop`. */
        const val ANSWER_MS = 30_000L
        /** Keuzes voor de tijd (uren) bij live en films; standaard 3. */
        val HOURS = listOf(2, 3, 4)
        const val DEFAULT_HOURS = 3

        enum class Kind { LIVE, MOVIE, EPISODE }
        enum class Reason { EPISODES, TIME }

        /** [autoEpisodes]: afleveringen die automatisch (of met Volgende aflevering) zijn gestart sinds de laatste bediening. */
        data class State(val autoEpisodes: Int = 0, val lastInteraction: Long = 0L)

        fun hours(raw: Int?): Int = if (raw != null && raw in HOURS) raw else DEFAULT_HOURS

        /** Bediening (toets, tik, focuswissel, volume, zappen): alles opnieuw. */
        fun touch(s: State, now: Long): State = State(0, now)

        /** Een titel start: automatisch (volgende aflevering) telt op, met de hand = bediening. */
        fun started(s: State, auto: Boolean, now: Long): State =
            if (auto) s.copy(autoEpisodes = s.autoEpisodes + 1) else touch(s, now)

        /** Moet de vraag nu komen? null = nee. */
        fun ask(s: State, now: Long, enabled: Boolean, hours: Int, kind: Kind): Reason? {
            if (!enabled) return null
            if (kind == Kind.EPISODE && s.autoEpisodes >= EPISODES) return Reason.EPISODES
            if ((kind == Kind.LIVE || kind == Kind.MOVIE) && s.lastInteraction > 0 &&
                now - s.lastInteraction >= hours(hours) * 3_600_000L) return Reason.TIME
            return null
        }

        /** Seconden die nog over zijn om te antwoorden (0 = verlopen). */
        fun secondsLeft(askedAt: Long, now: Long): Int =
            ((ANSWER_MS - (now - askedAt)).coerceAtLeast(0L) + 999L).div(1000L).toInt()

        fun expired(askedAt: Long, now: Long): Boolean = now - askedAt >= ANSWER_MS
    }

    // MARK: 15, 17, 20. Sets per profiel

    object ProfileSets {
        /** Zender: "<server-id kleine letters>|<zender-id>". */
        fun channelKey(sid: String, channelID: String): String = sid.lowercase(Locale.ROOT) + "|" + channelID
        /** Titel: "<server-id kleine letters>|movie:<id>" of "…|series:<id>". */
        fun titleKey(sid: String, series: Boolean, id: String): String =
            sid.lowercase(Locale.ROOT) + "|" + (if (series) "series:" else "movie:") + id
        /** Categorie (vastpinnen): "<server-id kleine letters>|<categorienaam exact>", zoals `hiddenCategories`. */
        fun categoryKey(sid: String, name: String): String = sid.lowercase(Locale.ROOT) + "|" + name

        /** Server-deel naar kleine letters (iOS kan hoofdletters schrijven); de rest blijft exact. */
        fun norm(key: String): String {
            val i = key.indexOf('|')
            return if (i <= 0) key else key.substring(0, i).lowercase(Locale.ROOT) + key.substring(i)
        }

        data class TitleRef(val sid: String, val series: Boolean, val id: String)

        fun parseTitle(key: String): TitleRef? {
            val i = key.indexOf('|'); if (i <= 0) return null
            val rest = key.substring(i + 1)
            val series = when {
                rest.startsWith("series:") -> true
                rest.startsWith("movie:") -> false
                else -> return null
            }
            val id = rest.substringAfter(':')
            if (id.isEmpty()) return null
            return TitleRef(key.substring(0, i).lowercase(Locale.ROOT), series, id)
        }

        /** Server-id en item-id uit een zender- of categoriesleutel. */
        fun split(key: String): Pair<String, String>? {
            val i = key.indexOf('|'); if (i <= 0 || i == key.length - 1) return null
            return key.substring(0, i).lowercase(Locale.ROOT) to key.substring(i + 1)
        }

        /** Zender-id's van één server. */
        fun channelIDs(keys: Collection<String>, sid: String): Set<String> {
            val s = sid.lowercase(Locale.ROOT)
            val out = HashSet<String>()
            for (k in keys) { val p = split(k) ?: continue; if (p.first == s) out += p.second }
            return out
        }

        /** Film- en serie-id's van één server. */
        fun titleIDs(keys: Collection<String>, sid: String): Pair<Set<String>, Set<String>> {
            val s = sid.lowercase(Locale.ROOT)
            val movies = HashSet<String>(); val series = HashSet<String>()
            for (k in keys) { val r = parseTitle(k) ?: continue; if (r.sid == s) (if (r.series) series else movies) += r.id }
            return movies to series
        }

        /** Een sleutel aan of uit in een set (hoofdletterongevoelig op de server). */
        fun toggle(set: Set<String>, key: String, on: Boolean): Set<String> {
            val n = norm(key)
            val without = set.filterNot { norm(it) == n }.toSet()
            return if (on) without + n else without
        }

        fun contains(set: Set<String>, key: String): Boolean { val n = norm(key); return set.any { norm(it) == n } }

        /** Lijst filteren op verborgen id's (server per item, null = [activeSid]). */
        fun <T> filter(list: List<T>, keys: Set<String>, activeSid: String, sidOf: (T) -> String?, idOf: (T) -> String,
                       keyOf: (String, String) -> String): List<T> {
            if (keys.isEmpty()) return list
            val norm = keys.mapTo(HashSet()) { norm(it) }
            return list.filter { keyOf(sidOf(it) ?: activeSid, idOf(it)).let { k -> norm(k) !in norm } }
        }

        /** Vastgepinde categorieën eerst (in de volgorde van [pinned]), daarna de rest in hun eigen volgorde. */
        fun <T> pinnedFirst(list: List<T>, pinned: Set<String>, keyOf: (T) -> String): List<T> {
            if (pinned.isEmpty()) return list
            val n = pinned.mapTo(HashSet()) { norm(it) }
            val (a, b) = list.partition { norm(keyOf(it)) in n }
            return a + b
        }
    }

    // MARK: 17. Landen en regio's

    object Countries {
        /** Volgorde van de chips (de rest alfabetisch erachter). */
        val ORDER = listOf("NL", "BE", "UK", "EN", "US", "DE", "FR", "ES", "IT", "PT", "TR", "PL", "AR")

        /** Land/regio van een categorie- of zendernaam (eerste landlabel), anders null. */
        fun of(name: String): String? =
            Build150Logic.CategoryName.cached(name).tags.firstOrNull { t -> t.length in 2..4 && t.all { it.isUpperCase() } }

        /** Chips: de landen die voorkomen, de bekende eerst; alleen landen met minstens [min] categorieën. */
        fun chips(names: Iterable<String>, min: Int = 1): List<String> {
            val count = HashMap<String, Int>()
            for (n in names) of(n)?.let { count[it] = (count[it] ?: 0) + 1 }
            val known = ORDER.filter { (count[it] ?: 0) >= min }
            val rest = count.filter { it.value >= min && it.key !in ORDER }.keys.sorted()
            return known + rest
        }

        fun matches(name: String, chip: String?): Boolean = chip == null || of(name) == chip
    }

    // MARK: 30. Radio

    object Radio {
        private val WORDS = listOf("radio", "radios", "rádio", "rádios", "radyo", "radjo", "fm radio")

        /** Categorie- of groepsnaam met "radio" als woord ("Radio NL", "NL | RADIO", "Radiozenders"). */
        fun isRadioGroup(group: String): Boolean {
            val g = group.lowercase(Locale.ROOT)
            if (g.contains("radio")) {
                // "Radiohead" of "Radioactive" is geen radio: na "radio" geen letter, of een Nederlandse samenstelling
                var i = g.indexOf("radio")
                while (i >= 0) {
                    val before = if (i == 0) ' ' else g[i - 1]
                    val after = g.getOrNull(i + 5)
                    val rest = g.substring(i + 5)
                    if (!before.isLetter() && (after == null || !after.isLetter() || rest.startsWith("s") && (rest.length == 1 || !rest[1].isLetter()) ||
                            rest.startsWith("zender") || rest.startsWith("station") || rest.startsWith("kanal"))) return true
                    i = g.indexOf("radio", i + 1)
                }
            }
            return WORDS.any { w -> Regex("(^|[^\\p{L}])" + Regex.escape(w) + "($|[^\\p{L}])").containsMatchIn(g) }
        }

        /** Radiozender: vlag van de server (Xtream `radio_streams`, M3U `radio="true"`) of een radiocategorie. */
        fun isRadio(flag: Boolean, group: String): Boolean = flag || isRadioGroup(group)

        /** Volgorde: favorieten eerst (in hun volgorde), daarna op nummer en naam. */
        fun <T> order(list: List<T>, isFavorite: (T) -> Boolean, number: (T) -> Int?, name: (T) -> String): List<T> {
            val (fav, rest) = list.partition(isFavorite)
            val cmp = compareBy<T>({ number(it) ?: Int.MAX_VALUE }, { name(it).lowercase(Locale.ROOT) })
            return fav.sortedWith(cmp) + rest.sortedWith(cmp)
        }
    }

    // MARK: 31. Apparaten en streams

    object DeviceList {
        /** Na zoveel dagen zonder gebruik stellen we voor het apparaat af te melden. */
        const val STALE_DAYS = 14

        data class Dev(val id: String, val lastSeen: Long?, val current: Boolean)

        /** Langst niet gebruikt bovenaan (nooit gezien eerst); dit apparaat altijd onderaan. */
        fun <T> sortStale(list: List<T>, lastSeen: (T) -> Long?, current: (T) -> Boolean): List<T> =
            list.sortedWith(compareBy<T>({ if (current(it)) 1 else 0 }, { lastSeen(it) ?: Long.MIN_VALUE }))

        /** Het apparaat om voor te stellen (langst niet gebruikt, niet dit apparaat, minstens [STALE_DAYS] niet gebruikt). */
        fun <T> suggestion(list: List<T>, lastSeen: (T) -> Long?, current: (T) -> Boolean, now: Long): T? =
            sortStale(list, lastSeen, current).firstOrNull { d ->
                !current(d) && (lastSeen(d)?.let { now - it >= STALE_DAYS * 86_400_000L } ?: true)
            }

        /** Relatieve tijd als (Engelse sleutel, getal): "just now", "%d min ago", "%d hours ago", "yesterday", "%d days ago"; null = datum tonen. */
        fun relative(at: Long?, now: Long): Pair<String, Int>? {
            if (at == null) return null
            val d = (now - at).coerceAtLeast(0L)
            val min = d / 60_000L
            return when {
                min < 1 -> "just now" to 0
                min < 60 -> "%d min ago" to min.toInt()
                min < 120 -> "1 hour ago" to 1
                min < 24 * 60 -> "%d hours ago" to (min / 60).toInt()
                min < 48 * 60 -> "yesterday" to 1
                min < 30L * 24 * 60 -> "%d days ago" to (min / (24 * 60)).toInt()
                else -> null
            }
        }

        /** Streams: van andere apparaten eerst, langst geleden gepingd bovenaan. */
        fun <T> sortStreams(list: List<T>, lastPing: (T) -> Long?, own: (T) -> Boolean): List<T> =
            list.sortedWith(compareBy<T>({ if (own(it)) 1 else 0 }, { lastPing(it) ?: Long.MIN_VALUE }))

        /** Platformgroep voor het icoon. */
        fun iconKind(platform: String): String {
            val p = platform.lowercase(Locale.ROOT)
            return when {
                p.contains("tv") || p == "tizen" || p == "webos" -> "tv"
                p.contains("ipad") || p.contains("tablet") -> "tablet"
                p.contains("mac") || p == "windows" -> "desktop"
                p.contains("browser") || p == "web" -> "web"
                else -> "phone"
            }
        }
    }

    // MARK: 37. Crashrapporten

    object Crash {
        const val MAX_MESSAGE = 500
        const val MAX_STACK = 16 * 1024
        const val MAX_PER_HOUR = 20
        const val KEEP_DAYS = 7
        const val MAX_QUEUE = 50

        private val URL_RE = Regex("""(?i)\b[a-z][a-z0-9+.\-]{1,15}://[^\s"'<>()\[\]{}]+""")
        private val WWW_RE = Regex("""(?i)\bwww\.[^\s"'<>()\[\]{}]+""")
        /** host:poort/pad of host/pad met een punt in de host ("example.com/live/user/pass/1.ts"). */
        private val HOSTPATH_RE = Regex("""(?i)\b(?:[a-z0-9\-]+\.)+[a-z]{2,}(?::\d+)?/[^\s"'<>()\[\]{}]*""")
        private val EMAIL_RE = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
        private val IPV4_RE = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}(?::\d+)?\b""")
        private val IPV6_RE = Regex("""(?i)\b(?:[0-9a-f]{1,4}:){2,7}[0-9a-f]{1,4}\b""")
        private val QUERY_RE = Regex("""\?[^\s"'<>]*=[^\s"'<>]*""")
        /** Xtream-paden zonder host ("/live/<user>/<pass>/1.ts") en de bekende IPTV-scripts. */
        private val XTREAM_RE = Regex("""(?i)/(?:live|movie|series|timeshift)/[^\s/"'<>]+/[^\s/"'<>]+/[^\s"'<>]*""")
        private val SCRIPT_RE = Regex("""(?i)\b(?:get|player_api|xmltv|panel_api)\.php\b[^\s"'<>]*""")
        private val USERINFO_RE = Regex("""(?i)\b[^\s:/@"'<>]+:[^\s:/@"'<>]+@[a-z0-9.\-]+(?::\d+)?[^\s"'<>]*""")
        private val SECRET_KV_RE = Regex("""(?i)\b(username|password|passwd|pass|token|key|apikey|api_key|mac|auth|secret)=([^\s&"'<>]+)""")
        private val JWT_RE = Regex("""eyJ[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]*""")
        private val HEX_RE = Regex("""\b[0-9a-fA-F]{32,}\b""")
        /** Lange tokens: 24+ tekens zonder spatie met cijfers én letters (JWT, sleutels, wachtwoorden in paden). */
        private val TOKEN_RE = Regex("""[A-Za-z0-9_\-+/=]{24,}""")

        /** Alles wat persoonlijk kan zijn eruit: URL's (IPTV-links bevatten gebruikersnaam en wachtwoord), IP, e-mail, tokens. */
        fun scrub(s: String): String {
            var t = s
            t = JWT_RE.replace(t, "<token>")
            t = URL_RE.replace(t, "<url>")
            t = USERINFO_RE.replace(t, "<url>")
            t = WWW_RE.replace(t, "<url>")
            t = HOSTPATH_RE.replace(t, "<url>")
            t = XTREAM_RE.replace(t, "<url>")
            t = SCRIPT_RE.replace(t, "<url>")
            t = EMAIL_RE.replace(t, "<email>")
            t = IPV4_RE.replace(t, "<ip>")
            t = IPV6_RE.replace(t) { m -> if (m.value.count { it == ':' } >= 2 && m.value.any { it.isDigit() || it in 'a'..'f' }) "<ip>" else m.value }
            t = SECRET_KV_RE.replace(t) { m -> m.groupValues[1] + "=<redacted>" }
            t = QUERY_RE.replace(t, "<query>")
            t = HEX_RE.replace(t, "<token>")
            t = TOKEN_RE.replace(t) { m ->
                val v = m.value
                // pakketnamen en klassen ("nl.lrvdlinden.bingr.PlayerController") hebben punten en zijn geen token
                if (v.any { it.isDigit() } && v.any { it.isLetter() }) "<token>" else v
            }
            return t
        }

        /** Inkorten tot [max] bytes (UTF-8), niet midden in een teken. */
        fun clampBytes(s: String, max: Int): String {
            val b = s.toByteArray(Charsets.UTF_8)
            if (b.size <= max) return s
            var n = max
            while (n > 0 && (b[n].toInt() and 0xC0) == 0x80) n--
            return String(b, 0, n, Charsets.UTF_8)
        }

        fun message(raw: String?): String = scrub(raw ?: "").take(MAX_MESSAGE)
        fun stack(raw: String?): String = clampBytes(scrub(raw ?: ""), MAX_STACK)

        /** Regels van de stack zonder regelnummers ("at a.b.C.d(C.kt:12)" → "a.b.C.d"). */
        fun frames(stack: String): List<String> = stack.lineSequence().map { it.trim() }
            .filter { it.startsWith("at ") }.map { it.removePrefix("at ").substringBefore('(').trim() }.toList()

        /**
         * Signatuur (contract build 171): klasse van de fout + het eerste frame van Bingr (anders het eerste frame), zonder
         * regelnummers of adressen; max. 200 tekens. "java.lang.IllegalStateException @ nl.lrvdlinden.bingr.A.b"
         */
        fun signature(kind: String, exception: String, stack: String): String {
            val all = frames(stack)
            val top = all.firstOrNull { it.startsWith("nl.lrvdlinden.") } ?: all.firstOrNull()
            val cls = exception.substringBefore(':').trim().ifEmpty { kind }
            return scrub(if (top != null) "$cls @ $top" else cls).replace(Regex("\\d{3,}"), "#").take(200)
        }

        data class Report(
            val kind: String, val signature: String, val message: String, val stack: String,
            val at: Long, val count: Int = 1, val appVersion: String = "", val build: Int = 0,
        )

        /** In de wachtrij zetten: dezelfde signatuur en build telt op; oud (> 7 dagen) eruit; hooguit [MAX_QUEUE]. */
        fun enqueue(queue: List<Report>, r: Report, now: Long): List<Report> {
            val fresh = prune(queue, now)
            val i = fresh.indexOfFirst { it.signature == r.signature && it.build == r.build }
            val out = if (i >= 0) fresh.toMutableList().also { it[i] = it[i].copy(count = it[i].count + r.count, at = maxOf(it[i].at, r.at)) }
                else fresh + r
            return out.sortedByDescending { it.at }.take(MAX_QUEUE)
        }

        fun prune(queue: List<Report>, now: Long): List<Report> = queue.filter { now - it.at < KEEP_DAYS * 86_400_000L }

        /** Mag er nog een rapport weg? (max. [MAX_PER_HOUR] per uur per apparaat) */
        fun allowed(sentTimes: List<Long>, now: Long): Boolean = sentTimes.count { now - it < 3_600_000L } < MAX_PER_HOUR

        /** Wat te doen met het antwoord van de server: verstuurd, later opnieuw (404/5xx/netwerk) of weggooien (400/413). */
        enum class Outcome { SENT, RETRY, DROP }
        /** 400 unknown_action / 404 = oude server, 401/429/5xx = later; 400 bad_report en 413 = weggooien. */
        fun outcome(code: Int?, error: String? = null): Outcome = when {
            code == null -> Outcome.RETRY
            code in 200..299 -> Outcome.SENT
            code == 400 && error == "unknown_action" -> Outcome.RETRY
            code == 404 || code == 401 || code == 429 || code >= 500 -> Outcome.RETRY
            else -> Outcome.DROP
        }
    }
}
