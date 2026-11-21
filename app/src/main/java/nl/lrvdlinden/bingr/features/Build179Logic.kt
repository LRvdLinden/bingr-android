package nl.lrvdlinden.bingr

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Build 179 (1.0.8): pure logica zonder Android, gelijk aan Apple `Shared/**/Build179*.swift` en web `web/js/build179.js`.
 * Contract: docs/ACCOUNT.md "Build 179" in iptv-ios-appletv; gedeelde testgevallen in docs/build179_cases.json
 * (kopie: tools/test/build179_cases.json, niet los aanpassen). Unittest: tools/test/Build179LogicTest.kt.
 * Alles is zelf gebouwd vanaf de bestaande Bingr-code (geen code of tekst uit andere apps).
 *
 * Gebruikt [Build173Logic] (teamKey), [Build176Logic] (normaliseren zoals epgSearch).
 */
object Build179Logic {

    /** `norm`: zoals build 176 epgSearch (kleine letters, zonder accenten, geen letter/cijfer = spatie). */
    fun norm(s: String): String = Build176Logic.EpgSearch.fold(s)

    // MARK: 1. Gids en servers vanzelf verversen (`refresh`)

    object Refresh {
        const val CATALOG_PREF = "refresh.catalog"
        const val EPG_PREF = "refresh.epg"
        const val CELLULAR_PREF = "refresh.cellular"
        val CATALOG = listOf("6h", "daily", "3d", "weekly", "manual")
        val EPG = listOf("6h", "12h", "daily")

        fun catalog(raw: String?): String = if (raw in CATALOG) raw!! else "daily"
        fun epg(raw: String?): String = if (raw in EPG) raw!! else "6h"

        /** Termijn in seconden; null = alleen handmatig. */
        fun seconds(v: String): Long? = when (v) {
            "6h" -> 6 * 3600L; "12h" -> 12 * 3600L; "daily" -> 86_400L; "3d" -> 3 * 86_400L; "weekly" -> 7 * 86_400L
            else -> null
        }

        /** `due(i, last) = i != manual && (last == null || now - last >= i || last - now > 3600)` (seconden). */
        fun due(interval: String, last: Long?, now: Long): Boolean {
            val i = seconds(interval) ?: return false
            return last == null || now - last >= i || last - now > 3600
        }

        /** "full", "epg", "none" of "blocked" (tijden in seconden). */
        fun action(catalog: String?, epg: String?, lastFull: Long?, lastEpg: Long?, now: Long, saver: Boolean, cellular: Boolean): String {
            val c = catalog(catalog); val e = epg(epg)
            val full = due(c, lastFull, now)
            val epgLast = if (lastEpg == null) lastFull else if (lastFull == null) lastEpg else maxOf(lastEpg, lastFull)
            val g = due(e, epgLast, now)
            val out = if (full) "full" else if (g) "epg" else "none"
            return if (out != "none" && saver && !cellular) "blocked" else out
        }
    }

    // MARK: 2. Nu en straks (mediasessie)

    object NowNext {
        /** Index van het programma van nu en van het volgende in een op starttijd gesorteerde lijst ([starts]/[ends] in ms). */
        fun of(starts: List<Long>, ends: List<Long>, nowMs: Long): Pair<Int?, Int?> {
            var now: Int? = null
            for (i in starts.indices) if (starts[i] <= nowMs && nowMs < ends[i]) now = i
            val from = now?.let { ends[it] } ?: nowMs
            var next: Int? = null
            for (i in starts.indices) if (i != now && starts[i] >= from) { next = i; break }
            return now to next
        }
    }

    // MARK: 3. Automatisch een andere speler proberen

    object Engine {
        const val MEMO_PREF = "player.engineMemo"
        const val MEMO_MAX = 500
        const val MEMO_DAYS = 60

        /** `engineFirst`: de onthouden engine als die in de volgorde staat, anders de eerste. */
        fun first(order: List<String>, remembered: String?): String? =
            if (remembered != null && remembered in order) remembered else order.firstOrNull()

        /**
         * `engineSwitch` (seconden): `!switched && ((error && played < 10) || (!firstFrame && sinceStart >= 10) ||
         * (firstFrame && stalled >= 10 && played < 30))`.
         */
        fun shouldSwitch(error: Boolean, firstFrame: Boolean, sinceStart: Double, stalled: Double, played: Double, switched: Boolean): Boolean =
            !switched && ((error && played < 10) || (!firstFrame && sinceStart >= 10) || (firstFrame && stalled >= 10 && played < 30))

        /** `engineNext`: de eerste in de volgorde die niet de huidige is; al gewisseld = null. */
        fun next(order: List<String>, current: String, switched: Boolean): String? =
            if (switched) null else order.firstOrNull { it != current }

        /** `engineMemo`: gelukt = de engine die speelt; anders wissen (null). */
        fun memo(remembered: String?, startedWith: String, switchedTo: String?, success: Boolean): String? =
            if (success) (switchedTo ?: startedWith) else null

        /** Sleutel: live `"<server>|<stream-id>"`, film `"<server>|movie:<id>"`, aflevering `"<server>|episode:<id>"`. */
        fun key(serverID: String, type: String, id: String): String {
            val s = serverID.lowercase(Locale.ROOT)
            return when (type) { "movie" -> "$s|movie:$id"; "episode" -> "$s|episode:$id"; else -> "$s|$id" }
        }

        data class Memo(val engine: String, val at: Long)

        /** Bijwerken (null = wissen); oudste eraf boven [MEMO_MAX], ouder dan [MEMO_DAYS] weg (tijden in s). */
        fun update(map: Map<String, Memo>, key: String, engine: String?, now: Long): LinkedHashMap<String, Memo> {
            val out = LinkedHashMap<String, Memo>()
            for ((k, v) in map.entries.sortedBy { it.value.at }) if (k != key && now - v.at < MEMO_DAYS * 86_400L) out[k] = v
            if (engine != null) out[key] = Memo(engine, now)
            while (out.size > MEMO_MAX) out.remove(out.keys.first())
            return out
        }

        fun encode(map: Map<String, Memo>): String = map.entries.joinToString("\n") { "${it.key}\t${it.value.engine}\t${it.value.at}" }
        fun decode(s: String?): Map<String, Memo> {
            val out = LinkedHashMap<String, Memo>()
            s?.lineSequence()?.forEach { line ->
                val p = line.split('\t')
                if (p.size == 3) p[2].toLongOrNull()?.let { out[p[0]] = Memo(p[1], it) }
            }
            return out
        }
    }

    // MARK: X2. Stream stopt na ±27 s: opnieuw verbinden (gelijk aan Apple Shared/Player/Build179Stream.swift)

    object Stream {
        val LIVE_DELAYS = listOf(0.5, 1.5, 3.0, 5.0, 8.0)
        val COLD_DELAYS = listOf(1.5, 3.0)
        val VOD_DELAYS = listOf(1.5)
        const val OUTAGE_MAX = 45.0
        const val STALE_GRACE = 4.0

        /** Wachttijd (s) vóór poging [attempt] (0 = eerste); null = opgeven (reservebron of foutmelding). */
        fun retryDelay(attempt: Int, live: Boolean, hadPicture: Boolean, outage: Double = 0.0): Double? {
            if (outage > OUTAGE_MAX) return null
            val list = if (!live) VOD_DELAYS else if (hadPicture) LIVE_DELAYS else COLD_DELAYS
            return list.getOrNull(attempt)
        }

        /** "Afgelopen"/"fout" vlak na het laden zonder die status te verlaten = de vorige stream. */
        fun terminalIsStale(sinceLoad: Double, leftTerminal: Boolean): Boolean = !leftTerminal && sinceLoad >= 0 && sinceLoad < STALE_GRACE

        /** "serverClosed", "error", "timeout" of null. */
        fun reason(error: Boolean, ended: Boolean, live: Boolean, stalledFor: Double): String? = when {
            error -> "error"
            ended && live -> "serverClosed"
            live && stalledFor > 15 -> "timeout"
            else -> null
        }
    }

    // MARK: 5. Zappen-lijst

    object Zap {
        const val FAVORITES = "*favorites"

        /** `zapCategories`: "*favorites" eerst als er favorieten zijn, dan de groepen in volgorde van eerste voorkomen. */
        fun categories(groups: List<String>, favorites: List<Boolean>): List<String> {
            val out = ArrayList<String>()
            if (favorites.any { it }) out += FAVORITES
            val seen = HashSet<String>()
            for (g in groups) if (seen.add(g)) out += g
            return out
        }

        /**
         * `zapSearch`: alleen cijfers = zendernummer (geen zender met dat nummer: de cijfers in de naam zonder spaties);
         * anders genormaliseerd en elk woord (≥ 2 tekens of een getal) als deel van de genormaliseerde naam.
         */
        fun search(q: String, names: List<String>, numbers: List<Int?>): List<Int> {
            val t = q.trim()
            if (t.isEmpty()) return names.indices.toList()
            if (t.all { it in '0'..'9' }) {
                val n = t.toIntOrNull()
                val byNumber = names.indices.filter { numbers.getOrNull(it) != null && numbers[it] == n }
                if (byNumber.isNotEmpty()) return byNumber
                return names.indices.filter { names[it].replace(" ", "").contains(t) }
            }
            val words = norm(t).split(' ').filter { w -> w.isNotEmpty() && (w.length >= 2 || w.all { it in '0'..'9' }) }
            if (words.isEmpty()) return names.indices.toList()
            return names.indices.filter { i -> val n = norm(names[i]); words.all { n.contains(it) } }
        }
    }

    // MARK: 7. Live scores (ESPN)

    object Scores {
        const val SCORES_TTL = 60L
        const val SCORES_TTL_SAVER = 300L
        const val STANDINGS_TTL = 3600L
        const val LINK_SECONDS = 900L

        private val LEAGUES = mapOf(
            "eredivisie" to "soccer/ned.1", "kkd" to "soccer/ned.2", "knvb-beker" to "soccer/ned.cup",
            "champions-league" to "soccer/uefa.champions", "europa-league" to "soccer/uefa.europa",
            "conference-league" to "soccer/uefa.europa.conf", "premier-league" to "soccer/eng.1", "laliga" to "soccer/esp.1",
            "serie-a" to "soccer/ita.1", "bundesliga" to "soccer/ger.1", "ligue-1" to "soccer/fra.1",
            "jupiler-pro-league" to "soccer/bel.1", "nba" to "basketball/nba", "nfl" to "football/nfl", "nhl" to "hockey/nhl",
        )

        /** Competitie (id van build 172) → "<sport>/<league>" bij ESPN, of null (geen scores). */
        fun league(competitionID: String): String? = LEAGUES[competitionID]
        val leagues: Map<String, String> get() = LEAGUES

        fun scoreboardURL(league: String, day: String) = "https://site.api.espn.com/apis/site/v2/sports/$league/scoreboard?dates=$day"
        fun standingsURL(league: String) = "https://site.api.espn.com/apis/v2/sports/$league/standings"

        /** `teamEq`: teamKey gelijk, of de kortste (≥ 3) is het begin van de langste, of de kortste (≥ 5) staat erin. */
        fun teamEq(a: String, b: String): Boolean {
            val x = Build173Logic.teamKey(a); val y = Build173Logic.teamKey(b)
            if (x.isEmpty() || y.isEmpty()) return false
            if (x == y) return true
            val (s, l) = if (x.length <= y.length) x to y else y to x
            if (s.length >= 3 && l.startsWith(s)) return true
            return s.length >= 5 && l.contains(s)
        }

        fun teamMatches(guide: String, names: List<String>) = names.any { teamEq(guide, it) }

        data class Link(val id: String, val home: List<String>, val away: List<String>, val start: Long)

        /** `espnLink`: beide teams (ook omgedraaid), aftrap ≤ 900 s verschil, de dichtstbijzijnde (tijden in s). */
        fun link(home: String, away: String, start: Long, events: List<Link>): String? {
            var best: Link? = null
            for (e in events) {
                val d = kotlin.math.abs(e.start - start)
                if (d > LINK_SECONDS) continue
                val ok = (teamMatches(home, e.home) && teamMatches(away, e.away)) || (teamMatches(home, e.away) && teamMatches(away, e.home))
                if (!ok) continue
                if (best == null || d < kotlin.math.abs(best.start - start)) best = e
            }
            return best?.id
        }

        private fun num(v: Any?): String? = when (v) {
            null -> null
            is Number -> { val d = v.toDouble(); if (d == Math.floor(d)) d.toLong().toString() else d.toString() }
            is String -> v.trim().ifEmpty { null }
            else -> v.toString()
        }

        /** `scoreText`: `in` "2–1 · 67'", `post` "3–2", anders null. */
        fun text(state: String?, home: Any?, away: Any?, clock: String?): String? {
            if (state != "in" && state != "post") return null
            val h = num(home) ?: return null
            val a = num(away) ?: return null
            val score = "$h–$a"
            val c = clock?.trim().orEmpty()
            return if (state == "in" && c.isNotEmpty()) "$score · $c" else score
        }

        /** "YYYYMMDD" (UTC) van een tijd in ms. */
        fun day(ms: Long): String {
            val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = ms }
            return String.format(Locale.ROOT, "%04d%02d%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
        }

        fun fresh(cachedAt: Long?, now: Long, ttl: Long): Boolean = cachedAt != null && now >= cachedAt && now - cachedAt < ttl
    }

    // MARK: 8. Teamkleur (`teamColor`, `contrast`)

    object TeamColor {
        /** 6 hex-tekens (met of zonder #), anders null. */
        fun parse(hex: String?): Int? {
            val h = hex?.trim()?.removePrefix("#") ?: return null
            if (h.length != 6 || !h.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) return null
            return h.toInt(16)
        }

        fun hex(rgb: Int): String = String.format(Locale.ROOT, "%06x", rgb and 0xFFFFFF)

        private fun lin(c: Int): Double { val v = c / 255.0; return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4) }
        fun luminance(rgb: Int): Double =
            0.2126 * lin((rgb shr 16) and 0xff) + 0.7152 * lin((rgb shr 8) and 0xff) + 0.0722 * lin(rgb and 0xff)

        /** Contrast met wit: `1.05 / (L + 0.05)`. */
        fun contrast(rgb: Int): Double = 1.05 / (luminance(rgb) + 0.05)

        /** `teamColor`: eerste geldige kandidaat met contrast ≥ 4.5; anders de eerste donkerder (1 − 0.1·k); geen: terugval. */
        fun pick(candidates: List<String?>, fallback: String): String {
            val valid = candidates.mapNotNull { parse(it) }
            if (valid.isEmpty()) return fallback.trim().removePrefix("#").lowercase(Locale.ROOT)
            valid.firstOrNull { contrast(it) >= 4.5 }?.let { return hex(it) }
            val c = valid.first()
            for (k in 1..9) {
                val f = 1 - 0.1 * k
                val r = Math.round(((c shr 16) and 0xff) * f).toInt()
                val g = Math.round(((c shr 8) and 0xff) * f).toInt()
                val b = Math.round((c and 0xff) * f).toInt()
                val d = (r shl 16) or (g shl 8) or b
                if (contrast(d) >= 4.5) return hex(d)
            }
            return "000000"
        }

        /** Gemiddelde kleur van de niet-transparante pixels (ARGB) als hex, of null. */
        fun average(argb: IntArray): String? {
            var r = 0L; var g = 0L; var b = 0L; var n = 0
            for (p in argb) {
                if ((p ushr 24) < 128) continue
                r += (p shr 16) and 0xff; g += (p shr 8) and 0xff; b += p and 0xff; n++
            }
            if (n == 0) return null
            return hex(((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt())
        }
    }

    // MARK: 9. Beoordelingen (`ratingText`)

    object Ratings {
        val ORDER = listOf("imdb", "tomatoes", "tomatoesaudience", "metacritic", "letterboxd", "trakt", "tmdb")
        const val CACHE_DAYS = 7

        private fun round(v: Double, scale: Int): BigDecimal = BigDecimal(v).setScale(scale, RoundingMode.HALF_EVEN)

        fun text(source: String, value: Double?): String? {
            val v = value ?: return null
            if (v.isNaN()) return null
            return when (source) {
                "imdb", "tmdb", "letterboxd" -> round(v, 1).toPlainString()
                "tomatoes", "tomatoesaudience", "trakt" -> round(v, 0).toPlainString() + "%"
                "metacritic" -> round(v, 0).toPlainString()
                else -> null
            }
        }

        /** Korte naam op de detailpagina ("Publiek" via de vertaling). */
        fun shortName(source: String): String = when (source) {
            "imdb" -> "IMDb"; "tmdb" -> "TMDB"; "tomatoes" -> "RT"; "tomatoesaudience" -> "Audience"; "metacritic" -> "MC"
            "letterboxd" -> "Letterboxd"; "trakt" -> "Trakt"; else -> source
        }
    }

    // MARK: 10. IntroDB (via de server-functie `introdb`)

    object Intro {
        const val CACHE_DAYS = 30
        const val MISS_DAYS = 1

        data class Range(val start: Double, val end: Double)

        /** Bruikbaar segment: begint ≥ 0 en eindigt erna. */
        fun range(start: Double?, end: Double?): Range? {
            if (start == null || end == null || start < 0 || end <= start + 1) return null
            return Range(start, end)
        }

        /** Welke knop nu (tot 1 s voor het einde): "recap", "intro" of null. */
        fun active(intro: Range?, recap: Range?, t: Double): String? {
            if (recap != null && t >= recap.start && t < recap.end - 1) return "recap"
            if (intro != null && t >= intro.start && t < intro.end - 1) return "intro"
            return null
        }

        fun fresh(cachedAtMs: Long, nowMs: Long, miss: Boolean): Boolean =
            nowMs >= cachedAtMs && nowMs - cachedAtMs < (if (miss) MISS_DAYS else CACHE_DAYS) * 86_400_000L

        /** Build 186: verzoek aan `introdb` → (cachesleutel, velden). Zonder TMDB-id de serietitel, het jaar en het
         *  imdb-id (de server zoekt dan zelf het TMDB-id). null = niets op te vragen. */
        fun request(tmdb: Int?, imdb: String?, title: String?, year: Int?, season: Int?, episode: Int?): Pair<String, Map<String, Any>>? {
            if (season == null || episode == null || season < 0 || episode < 0) return null
            val id = tmdb?.takeIf { it > 0 }
            val tt = imdb?.trim()?.takeIf { Regex("^tt\\d{5,10}$").matches(it) }
            val name = title?.trim()?.takeIf { it.isNotEmpty() }?.take(200)
            val y = year?.takeIf { it in 1900..2100 }
            val f = LinkedHashMap<String, Any>()
            val key = when {
                id != null -> { f["tmdb"] = id; "$id" }
                tt != null || name != null -> {
                    name?.let { f["title"] = it }
                    y?.let { f["year"] = it }
                    tt ?: "t:${name!!.lowercase()}:${y ?: ""}"
                }
                else -> return null
            }
            tt?.let { f["imdb"] = it }
            f["season"] = season; f["episode"] = episode
            return "$key-$season-$episode" to f
        }

        /** Build 186: heeft IntroDB tijden voor de intro of de samenvatting, dan geen "Intro hier markeren". */
        fun known(intro: Range?, recap: Range?): Boolean = intro != null || recap != null
    }

    // MARK: 11. Voor jou (`forYou`)

    object ForYou {
        const val MAX = 30
        const val PREF = "home.forYou"

        data class Title(val key: String, val title: String, val genres: List<String>, val cast: List<String>, val rating: Double?)

        fun rank(titles: List<Title>, liked: Set<String>, disliked: Set<String>, watched: Set<String>, blocked: Set<String>,
                 recLiked: Map<String, Int>, recWatched: Map<String, Int>): List<String> {
            val byKey = titles.associateBy { it.key }
            val genre = HashMap<String, Int>()
            val cast = HashMap<String, Int>()
            for (k in liked) byKey[k]?.let { t ->
                t.genres.forEach { genre[it] = (genre[it] ?: 0) + 2 }
                t.cast.take(5).forEach { cast[it] = (cast[it] ?: 0) + 1 }
            }
            for (k in disliked) byKey[k]?.let { t -> t.genres.forEach { genre[it] = (genre[it] ?: 0) - 2 } }
            for (k in watched) if (k !in liked && k !in disliked) byKey[k]?.let { t -> t.genres.forEach { genre[it] = (genre[it] ?: 0) + 1 } }
            data class Scored(val t: Title, val score: Int, val name: String)
            val out = ArrayList<Scored>()
            for (t in titles) {
                if (t.key in liked || t.key in disliked || t.key in watched || t.key in blocked) continue
                var s = 0
                for (g in t.genres) s += genre[g] ?: 0
                for (c in t.cast.take(5)) s += cast[c] ?: 0
                s += 3 * (recLiked[t.key] ?: 0) + (recWatched[t.key] ?: 0)
                if (s > 0) out += Scored(t, s, norm(t.title).trim())
            }
            return out.sortedWith(compareByDescending<Scored> { it.score }.thenByDescending { it.t.rating ?: 0.0 }.thenBy { it.name })
                .take(MAX).map { it.t.key }
        }

        /** Sleutel: `tmdb:movie:<id>` / `tmdb:tv:<id>`; zonder TMDB-id `"<server>|movie:<id>"` / `"<server>|series:<id>"`. */
        fun key(series: Boolean, tmdb: String?, serverID: String, id: String): String =
            if (!tmdb.isNullOrBlank()) "tmdb:${if (series) "tv" else "movie"}:${tmdb.trim()}"
            else "${serverID.lowercase(Locale.ROOT)}|${if (series) "series" else "movie"}:$id"
    }

    // MARK: 12. Sorteren per categorie (`sort`, `sortKey`, `sortMerge`)

    object Sort {
        val MODES = listOf("server", "name", "year", "added", "rating")
        fun mode(raw: String?): String = if (raw in MODES) raw!! else "server"

        fun key(serverID: String, kind: String, category: String): String =
            (if (serverID == "*") "*" else serverID.lowercase(Locale.ROOT)) + "|" + kind + "|" + category

        private val ARTICLES = listOf("the ", "de ", "het ", "een ", "a ", "an ")
        fun sortName(name: String): String {
            var n = norm(name).trim().replace(Regex(" +"), " ")
            for (a in ARTICLES) if (n.startsWith(a) && n.length > a.length) { n = n.substring(a.length); break }
            return n
        }

        data class Item(val name: String, val year: Int?, val added: Long?, val rating: Double?)

        /** Indexen in de gekozen volgorde; ontbrekend of ≤ 0 achteraan, gelijk = volgorde van de server. */
        fun order(mode: String?, items: List<Item>): List<Int> {
            val idx = items.indices.toList()
            return when (mode(mode)) {
                "name" -> { val k = items.map { sortName(it.name) }; idx.sortedWith(compareBy { k[it] }) }
                "year" -> desc(idx) { items[it].year?.toDouble() }
                "added" -> desc(idx) { items[it].added?.toDouble() }
                "rating" -> desc(idx) { items[it].rating }
                else -> idx
            }
        }

        private fun desc(idx: List<Int>, v: (Int) -> Double?): List<Int> {
            val (has, none) = idx.partition { (v(it) ?: 0.0) > 0 }
            return has.sortedWith(compareByDescending { v(it)!! }) + none
        }

        data class Entry(val value: String, val at: String)

        /** Per sleutel de nieuwste `at` (gelijk: [a] blijft). */
        fun merge(a: Map<String, Entry>, b: Map<String, Entry>): Map<String, Entry> {
            val out = LinkedHashMap(a)
            for ((k, v) in b) {
                val cur = out[k]
                if (cur == null || (Iso.parse(v.at) ?: 0) > (Iso.parse(cur.at) ?: 0)) out[k] = v
            }
            return out
        }
    }

    /** Kleine ISO-8601-lezer (Z of ±hh:mm, met of zonder fracties) → ms. */
    object Iso {
        private val RE = Regex("^(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.(\\d+))?)?(Z|[+-]\\d{2}:?\\d{2})?$")
        fun parse(s: String?): Long? {
            val m = RE.matchEntire(s?.trim() ?: return null) ?: return null
            val g = m.groupValues
            val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear(); set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].ifEmpty { "0" }.toInt())
            }
            var ms = c.timeInMillis + (g[7].ifEmpty { "0" } + "00").take(3).toLong()
            val z = g[8]
            if (z.isNotEmpty() && z != "Z") {
                val sign = if (z[0] == '-') -1 else 1
                val d = z.substring(1).replace(":", "")
                ms -= sign * (d.substring(0, 2).toLong() * 3600_000L + d.substring(2, 4).toLong() * 60_000L)
            }
            return ms
        }
        fun format(ms: Long): String {
            val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = ms }
            return String.format(Locale.ROOT, "%04d-%02d-%02dT%02d:%02d:%02dZ", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1,
                c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND))
        }
    }

    // MARK: 13. Opruimen (`cleanupAllowed`, `cleanupAction`)

    object Cleanup {
        fun allowed(old: Int, new: Int, complete: Boolean): Boolean = complete && new > 0 && !(old >= 100 && new < old * 0.5)
        fun action(onServer: Boolean, elsewhere: Boolean): String = if (onServer) "keep" else if (elsewhere) "remap" else "remove"
        /** Sleutel voor dezelfde titel op een andere server (zonder TMDB-id): genormaliseerde titel + jaar + soort. */
        fun titleKey(title: String, year: Int?, series: Boolean): String =
            norm(title).trim().replace(Regex(" +"), " ") + "|" + (year ?: "") + "|" + (if (series) "series" else "movie")
    }

    // MARK: 16. WebDAV (`webdavParse`)

    object WebDAV {
        val VIDEO = setOf("mkv", "mp4", "m4v", "avi", "mov", "wmv", "ts", "m2ts", "webm", "mpg", "mpeg", "flv")
        private val QUALITY = setOf("2160p", "1080p", "1080i", "720p", "576p", "480p", "4k", "uhd", "hdr", "hdr10",
            "x264", "x265", "h264", "h265", "hevc", "avc", "web-dl", "webdl", "webrip", "web", "bluray", "blu-ray", "brrip",
            "bdrip", "dvdrip", "hdtv", "hdrip", "remux", "proper", "repack", "dts", "aac", "ac3", "dd5.1", "10bit")
        private val GENERIC = setOf("movie", "film", "video")
        private val SXE = Regex("(?i)(?:^|[^a-z0-9])s(\\d{1,2})[ ._-]*e(\\d{1,3})(?![0-9])")
        private val NXN = Regex("(?i)(?:^|[^a-z0-9])(\\d{1,2})x(\\d{1,3})(?![0-9])")
        private val SEASON_DIR = Regex("(?i)^(?:season|seizoen|staffel|saison)[ ._-]*(\\d{1,3})$|^s(\\d{1,3})$")
        private val EP_IN_NAME = Regex("(?i)(?:^|[^a-z0-9])(?:e|ep|episode|aflevering)[ ._-]*(\\d{1,3})(?![0-9])")
        private val LEADING_NUM = Regex("^(\\d{1,3})(?![0-9])")
        private val YEAR = Regex("^(19|20)\\d\\d$")

        data class Parsed(val kind: String, val title: String? = null, val year: Int? = null, val season: Int? = null, val episode: Int? = null)

        fun decode(s: String): String = runCatching { java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

        /** `.`/`_` → spatie, `[...]` weg, haakjes weg, spaties samen. */
        fun tidy(s: String): String {
            var t = s.replace(Regex("\\[[^\\]]*\\]"), " ").replace('.', ' ').replace('_', ' ').replace("(", " ").replace(")", " ")
            t = t.replace(Regex("\\s+"), " ").trim()
            return t.trim(' ', '-', '–')
        }

        /** Titel en jaar uit een (bestands- of map)naam: afkappen bij een kwaliteitswoord, laatste jaartal niet vooraan. */
        fun titleYear(raw: String): Pair<String, Int?> {
            val words = tidy(raw).split(' ').filter { it.isNotEmpty() }
            val cut = words.indexOfFirst { it.lowercase(Locale.ROOT) in QUALITY }
            val w = if (cut > 0) words.take(cut) else words
            var yi = -1
            for (i in w.indices) if (i > 0 && YEAR.matches(w[i])) yi = i
            if (yi > 0) return w.take(yi).joinToString(" ").trim(' ', '-') to w[yi].toInt()
            return w.joinToString(" ").trim(' ', '-') to null
        }

        private fun seriesTitle(dir: String): String = titleYear(dir).first

        fun parse(path: String): Parsed {
            val parts = decode(path).split('/').filter { it.isNotEmpty() }
            if (parts.isEmpty()) return Parsed("other")
            val file = parts.last()
            val dot = file.lastIndexOf('.')
            if (dot <= 0) return Parsed("other")
            val ext = file.substring(dot + 1).lowercase(Locale.ROOT)
            if (ext !in VIDEO) return Parsed("other")
            val base = file.substring(0, dot)
            val dirs = parts.dropLast(1)
            val parent = dirs.lastOrNull()
            val seasonDir = parent?.let { SEASON_DIR.matchEntire(it.trim()) }
            val seasonFromDir = seasonDir?.let { (it.groupValues[1].ifEmpty { it.groupValues[2] }).toInt() }
            val seriesDir = if (seasonDir != null) dirs.getOrNull(dirs.size - 2) else parent

            // SxxEyy of 1x02 in de bestandsnaam
            val m = SXE.find(base) ?: NXN.find(base)
            if (m != null) {
                val season = m.groupValues[1].toInt(); val ep = m.groupValues[2].toInt()
                val prefix = tidy(base.substring(0, m.range.first + (if (m.value.first().isLetterOrDigit()) 0 else 1)))
                val title = prefix.ifEmpty { seriesDir?.let { seriesTitle(it) } ?: "" }
                return Parsed("episode", title, null, season, ep)
            }
            // in een seizoensmap: E04 / Episode 4 / Aflevering 4 / een nummer vooraan
            if (seasonFromDir != null) {
                val e = EP_IN_NAME.find(base)?.groupValues?.get(1)?.toInt() ?: LEADING_NUM.find(base.trim())?.groupValues?.get(1)?.toInt()
                if (e != null) return Parsed("episode", seriesDir?.let { seriesTitle(it) } ?: "", null, seasonFromDir, e)
            }
            // film
            var (title, year) = titleYear(base)
            if (year == null || title.lowercase(Locale.ROOT) in GENERIC) {
                val up = parent?.let { titleYear(it) }
                if (up != null && up.second != null) { title = up.first; year = up.second }
            }
            return Parsed("movie", title, year)
        }
    }
}
