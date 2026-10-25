package nl.lrvdlinden.bingr

import java.text.Normalizer
import java.util.Locale

/**
 * Build 128 ("Eén lijst over alle servers", docs/ACCOUNT.md van de iOS-repo): pure logica zonder Android, zodat hij los
 * te testen is (tools/test/AllServersLogicTest.kt, ook in de workflow Build).
 *
 * - normaliseren van titels en zendernamen (zelfde regels als iOS, web en Samsung TV);
 * - zoeken van hetzelfde werk op een andere server (TMDB-id, anders titel + jaar ±1; zender op EPG-id of naam);
 * - remap-regels volgen (ketens a→b→c, nooit een lus) en toepassen op lijsten met tijden;
 * - samenvoegen (per sleutel wint de nieuwste `at`) en begrenzen van `catalogmeta`.
 */
object AllServersLogic {
    const val DAY = 86_400_000L
    const val META_KEEP_DAYS = 90L
    const val REMAP_KEEP_DAYS = 180L
    const val MAX_META = 6000

    // MARK: sleutels

    /** "<server>|<item>" → server-id in kleine letters + "|" + item exact (sleutel in catalogmeta.items). */
    fun metaKey(key: String): String {
        val bar = key.indexOf('|')
        return if (bar <= 0) key else key.substring(0, bar).lowercase(Locale.ROOT) + key.substring(bar)
    }
    /** Sleutel van een remap-regel: de oude sleutel helemaal in kleine letters. */
    fun remapKey(key: String): String = key.lowercase(Locale.ROOT)
    fun serverOf(key: String): String? = key.indexOf('|').takeIf { it > 0 }?.let { key.substring(0, it) }
    fun itemOf(key: String): String = key.indexOf('|').let { if (it < 0) key else key.substring(it + 1) }
    fun sameServer(a: String?, b: String?): Boolean = a != null && b != null && a.equals(b, ignoreCase = true)

    // MARK: normaliseren

    private val langPrefix = Regex("^\\s*(?:[a-z]{2,3}\\s*[:|]\\s*|[\\[(|]\\s*[a-z]{2,3}\\s*[\\])|]\\s*|[a-z]{2}\\s+-\\s+)")
    private val yearParen = Regex("[(\\[]\\s*((?:19|20)\\d{2})\\s*[)\\]]")
    private val trailingYear = Regex("\\s((?:19|20)\\d{2})\\s*$")
    private val tags = Regex("[(\\[][^)\\]]{0,12}[)\\]]")
    private val titleNoise = Regex("\\b(?:4k|uhd|fhd|hd|sd|hevc|h\\.?26[45]|x26[45]|1080p|720p|2160p|multi|dual|nl subs?|subs?|dolby|atmos|hdr|dubbed|vost|vose)\\b")
    private val episodeMark = Regex("\\bs\\d{1,2}\\s*e\\d{1,3}\\b.*$")
    private val channelNoise = Regex("\\b(?:hd|fhd|uhd|4k|8k|sd|hevc|h\\.?26[45]|1080[pi]?|720p|2160p|50fps|60fps|25fps|hdr|backup|raw)\\b")
    private val trailingCountry = Regex("\\s(?:nl|be|de|uk|us|fr|es|it|pl|tr|pt|en)\\s*$")

    /** Accenten weg en kleine letters ("Amélie" → "amelie"). */
    // build 149: regex één keer, gewone ASCII-tekst zonder Normalizer (over tienduizenden titels)
    private val marks = Regex("\\p{M}+")
    fun fold(s: String): String =
        if (s.all { it.code < 0x80 }) s.lowercase(Locale.ROOT)
        else marks.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)

    /** Jaartal uit een titel ("Dune (2021)", "Dune 2021"), anders null. */
    fun yearIn(title: String): Int? =
        (yearParen.find(title) ?: trailingYear.find(title.trim()))?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Titel om te vergelijken: zonder landvoorvoegsel ("NL | ", "NL: ", "[NL] "), jaartal, tags tussen haakjes,
     * kwaliteit (4K, HD, HEVC, MULTI, NL SUBS), leestekens en hoofdletters. "NL | Dune: Part Two (2024) 4K" → "dune part two".
     */
    fun normTitle(raw: String): String {
        var s = fold(raw).trim()
        repeat(2) { s = langPrefix.replace(s, "") }
        s = episodeMark.replace(s, " ")
        s = yearParen.replace(s, " ")
        s = tags.replace(s, " ")
        s = trailingYear.replace(s, " ")
        s = titleNoise.replace(s, " ")
        s = s.replace('&', ' ').map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
        return s.split(' ').filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** Zendernaam om te vergelijken: "NL: NPO 1 HD" → "npo1", "|BE| VTM 4K" → "vtm", "RTL 4 50fps" → "rtl4". */
    fun normChannel(raw: String): String {
        var s = fold(raw).trim()
        repeat(2) { s = langPrefix.replace(s, "") }
        s = channelNoise.replace(s, " ")
        s = s.replace("ᴴᴰ", " ").replace("ʰᵈ", " ")
        s = trailingCountry.replace(s.trim(), "")
        return s.filter { it.isLetterOrDigit() || it == '+' }
    }

    // MARK: matchen

    /** Een titel of zender op een server (kandidaat om naar te verhuizen). */
    data class Candidate(
        val sid: String, val id: String, val kind: String, val title: String,
        val year: Int? = null, val tmdb: Int? = null, val epg: String? = null,
    ) { val key: String get() = "$sid|$id" }

    /** Wat er gezocht wordt (uit catalogmeta of uit voortgang/geschiedenis). */
    data class Wanted(val kind: String, val title: String, val year: Int? = null, val tmdb: Int? = null, val epg: String? = null)

    fun yearOK(a: Int?, b: Int?): Boolean = a == null || b == null || kotlin.math.abs(a - b) <= 1

    /** Komt [c] overeen met [w]? Films/series: TMDB-id (als beide er een hebben), anders titel + jaar ±1. */
    fun matches(w: Wanted, c: Candidate): Boolean {
        val kind = if (w.kind == "episode") "series" else w.kind
        if (c.kind != kind) return false
        if (kind == "channel") {
            val e1 = w.epg?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
            val e2 = c.epg?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
            if (e1 != null && e1 == e2) return true
            val n = normChannel(w.title)
            return n.length >= 2 && n == normChannel(c.title)
        }
        if (w.tmdb != null && c.tmdb != null) return w.tmdb == c.tmdb
        val n = normTitle(w.title)
        if (n.isEmpty() || n != normTitle(c.title)) return false
        return yearOK(w.year ?: yearIn(w.title), c.year ?: yearIn(c.title))
    }

    /**
     * Beste kandidaat: servers in [order] (actieve eerst, dan de overige op volgorde), binnen een server een TMDB-match
     * vóór een titelmatch en een exact jaar vóór ±1. [exclude]: server-id's die niet meedoen (de oude server).
     */
    fun best(w: Wanted, candidates: List<Candidate>, order: List<String>, exclude: Set<String> = emptySet()): Candidate? {
        val ex = exclude.map { it.lowercase(Locale.ROOT) }.toSet()
        val rank = order.mapIndexed { i, s -> s.lowercase(Locale.ROOT) to i }.toMap()
        val wy = w.year ?: yearIn(w.title)
        return candidates.asSequence()
            .filter { it.sid.lowercase(Locale.ROOT) !in ex && matches(w, it) }
            .sortedWith(compareBy<Candidate>({ rank[it.sid.lowercase(Locale.ROOT)] ?: Int.MAX_VALUE },
                { if (w.tmdb != null && it.tmdb == w.tmdb) 0 else 1 },
                { val y = it.year ?: yearIn(it.title); if (wy != null && y == wy) 0 else 1 }))
            .firstOrNull()
    }

    /** Dezelfde tegel voor hetzelfde werk op twee servers: TMDB-id, anders genormaliseerde titel + jaar (zenders: naam). */
    fun dedupKey(kind: String, title: String, year: Int?, tmdb: Int?): String = when {
        kind == "channel" -> "c:" + normChannel(title)
        tmdb != null -> "${if (kind == "movie") "m" else "s"}:tmdb:$tmdb"
        else -> "${if (kind == "movie") "m" else "s"}:" + normTitle(title) + ":" + (year ?: yearIn(title) ?: "")
    }

    // MARK: build 129: zoeken en slimme collecties over alle servers

    /** Hetzelfde werk op twee servers: [base] gelijk (soort + TMDB-id of genormaliseerde titel/zender) en jaar ±1. */
    data class Cross(val base: String, val year: Int?)

    /**
     * Sleutel om treffers van verschillende servers samen te voegen (zelfde regels als het matchen: TMDB-id, anders titel
     * + jaar ±1, onbekend jaar = alleen de titel; zenders op genormaliseerde naam); null = niet samenvoegen (naam is na
     * het normaliseren leeg, bijv. alleen "HD").
     */
    fun crossKey(kind: String, title: String, year: Int?, tmdb: Int? = null): Cross? {
        if (kind == "channel") return normChannel(title).takeIf { it.length >= 2 }?.let { Cross("c:$it", null) }
        val p = if (kind == "movie") "m:" else "s:"
        if (tmdb != null) return Cross(p + "tmdb:" + tmdb, null)
        val n = normTitle(title).takeIf { it.isNotEmpty() } ?: return null
        return Cross(p + n, year ?: yearIn(title))
    }

    /**
     * Treffers van meerdere servers tot één lijst: [groups] op volgorde van voorrang (actieve server eerst, dan de
     * overige zoals in de lijst met servers). De eerste groep blijft helemaal staan (ook twee versies van dezelfde titel
     * op de actieve server); van de andere servers valt een treffer weg als hetzelfde werk er al in staat. Volgorde blijft.
     */
    fun <T> mergeAcross(groups: List<List<T>>, key: (T) -> Cross?, limit: Int = Int.MAX_VALUE): List<T> {
        val seen = HashMap<String, MutableList<Int?>>()
        val out = ArrayList<T>()
        groups.forEachIndexed { gi, list ->
            for (x in list) {
                if (out.size >= limit) return out
                val k = key(x)
                if (k != null && gi > 0 && seen[k.base]?.any { yearOK(it, k.year) } == true) continue
                if (k != null) seen.getOrPut(k.base) { ArrayList() } += k.year
                out += x
            }
        }
        return out
    }

    /**
     * Slimme collectie: past dit item? [folded] = gevouwen naam, [words] = gevouwen zoekwoorden (één is genoeg),
     * [wantGroup] = alleen deze categorie (null = alle). Gelijk aan Collections.resolve op de actieve server.
     */
    fun smartHit(folded: String, group: String, words: List<String>, wantGroup: String?): Boolean {
        if (wantGroup != null && group != wantGroup) return false
        return words.any { it.isNotEmpty() && folded.contains(it) }
    }

    // MARK: build 129: terugkijken op een MAC-portal (Stalker) zonder netwerk op het moment van tekenen

    /** Voorvoegsel van een terugkijk-adres dat pas bij het afspelen een echte link wordt (MAC-portal). */
    const val ARCHIVE_SCHEME = "bingr-archive://"

    /** "bingr-archive://<server>/<zender>/<start>/<eind>" (server en zender URL-gecodeerd, tijden in ms). */
    fun archiveURL(sid: String, channelID: String, start: Long, end: Long): String =
        ARCHIVE_SCHEME + enc(sid) + "/" + enc(channelID) + "/" + start + "/" + end

    data class Archive(val sid: String, val channelID: String, val start: Long, val end: Long)

    fun parseArchive(u: String?): Archive? {
        if (u == null || !u.startsWith(ARCHIVE_SCHEME)) return null
        val p = u.removePrefix(ARCHIVE_SCHEME).split('/')
        if (p.size != 4) return null
        val s = p[2].toLongOrNull() ?: return null
        val e = p[3].toLongOrNull() ?: return null
        val sid = dec(p[0]).takeIf { it.isNotEmpty() } ?: return null
        val ch = dec(p[1]).takeIf { it.isNotEmpty() } ?: return null
        return Archive(sid, ch, s, e)
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String) = runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault("")

    /**
     * Programma in het archief van een MAC-portal (epg get_simple_data_table): het item waarvan de start binnen 5 minuten
     * van [start] ligt en dat in het archief staat (mark_archive 1/true). [items] = (start in ms, in archief, id).
     */
    fun archiveProgram(items: List<Triple<Long, Boolean, String?>>, start: Long): String? =
        items.firstOrNull { kotlin.math.abs(it.first - start) <= 5 * 60_000L && it.second && it.third != null }?.third

    // MARK: remap

    data class Rule(val to: String, val at: Long)

    /** Eindsleutel van [key] via de regels (ketens volgen, lussen afbreken); null = geen regel. */
    fun resolve(rules: Map<String, Rule>, key: String): Pair<String, Long>? {
        var cur = remapKey(key)
        var out: Pair<String, Long>? = null
        val seen = HashSet<String>()
        while (seen.size < 10 && seen.add(cur)) {
            val r = rules[cur] ?: break
            if (remapKey(r.to) == remapKey(key)) return null   // lus terug naar het begin: niets doen
            out = r.to to maxOf(out?.second ?: 0L, r.at)
            cur = remapKey(r.to)
        }
        return out
    }

    /** Een regel toevoegen zonder lus: wijst de nieuwe sleutel (via ketens) terug naar de oude, dan niet. */
    fun addRule(rules: Map<String, Rule>, from: String, to: String, at: Long): Map<String, Rule> {
        if (remapKey(from) == remapKey(to)) return rules
        val end = resolve(rules, to)?.first ?: to
        if (remapKey(end) == remapKey(from)) return rules
        return rules + (remapKey(from) to Rule(to, at))
    }

    /** Eén item in een lijst: wanneer gezet en of het er (nog) is. */
    data class Entry(val at: Long, val alive: Boolean)

    /**
     * Remap toepassen op één lijst (Mijn lijst, favorieten, recente zenders, …) met tijden. Oude sleutel → verwijdering
     * met de remap-tijd (en altijd later dan het item zelf, anders komt hij bij het samenvoegen terug); nieuwe sleutel
     * met dezelfde `at`, tenzij hij er al is of daarna bewust verwijderd is. Idempotent. [keyOf] = welke sleutel de
     * regels zien (bij voortgang: "<playlistID>|<key>").
     */
    fun remapEntries(m: Map<String, Entry>, rules: Map<String, Rule>, keyOf: (String) -> String? = { it },
                     newKeyOf: (String) -> String = { it }): Map<String, Entry>? {
        if (rules.isEmpty()) return null
        var out: HashMap<String, Entry>? = null
        for ((k, e) in m) {
            if (!e.alive) continue
            val rk = keyOf(k) ?: continue
            val (target, at) = resolve(rules, rk) ?: continue
            val nk = newKeyOf(target)
            if (nk == k) continue
            val o = out ?: HashMap(m).also { out = it }
            o[k] = Entry(maxOf(at, e.at + 1), false)
            val cur = o.entries.firstOrNull { it.key.equals(nk, ignoreCase = false) || metaKey(it.key) == metaKey(nk) }
            if (cur == null || (!cur.value.alive && cur.value.at < e.at)) o[nk] = Entry(e.at, true)
        }
        return out
    }

    // MARK: catalogmeta samenvoegen en begrenzen

    /** Per sleutel wint de nieuwste tijd (gelijke tijd: [b], de serverversie). */
    fun <V> mergeNewest(a: Map<String, V>, b: Map<String, V>, atOf: (V) -> Long): Map<String, V> {
        val out = LinkedHashMap(a)
        for ((k, v) in b) { val c = out[k]; if (c == null || atOf(v) >= atOf(c)) out[k] = v }
        return out
    }

    /**
     * Opruimen: items die nergens meer naar verwezen worden en ouder zijn dan 90 dagen weg; daarna hooguit [max] items
     * (eerst oude onverwezen, dan de oudste). Remap-regels 180 dagen.
     */
    fun <V> pruneItems(items: Map<String, V>, atOf: (V) -> Long, referenced: Set<String>, now: Long, max: Int = MAX_META): Map<String, V> {
        val cutoff = now - META_KEEP_DAYS * DAY
        var keep = items.filter { (k, v) -> k in referenced || atOf(v) >= cutoff }
        if (keep.size > max) {
            val sorted = keep.entries.sortedWith(compareBy<Map.Entry<String, V>>({ if (it.key in referenced) 1 else 0 }, { atOf(it.value) }))
            keep = sorted.drop(keep.size - max).associate { it.key to it.value }
        }
        return keep
    }

    /** Seizoen/aflevering uit een ondertitel als "S1:E2 · Titel" of "S01E02" (geschiedenis van een aflevering). */
    fun seasonEpisode(s: String?): Pair<Int, Int>? {
        val m = Regex("(?i)\\bS(\\d{1,2})\\s*:?\\s*E(\\d{1,3})\\b").find(s ?: return null) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    fun pruneRules(rules: Map<String, Rule>, now: Long): Map<String, Rule> =
        rules.filterValues { it.at >= now - REMAP_KEEP_DAYS * DAY }

    // MARK: build 130: achtergrondwerk voor de andere servers

    /** Een andere server zonder cache hooguit eens per 6 uur van het netwerk halen. */
    const val FETCH_EVERY = 6 * 3_600_000L
    /** Time-out per andere server (ophalen op de achtergrond of bij "alles vernieuwen"). */
    const val FETCH_TIMEOUT = 90_000L
    /** Na het openen eerst de actieve server: zo lang (minimaal) niets voor de andere servers doen. */
    const val START_GRACE = 10_000L

    /**
     * Mag server-catalogus weer van het netwerk gehaald worden? Hooguit eens per [every] sinds de laatste poging
     * ([last], 0/null = nooit). Een klok die terugloopt (last in de toekomst) telt als "mag".
     */
    fun mayFetch(last: Long?, now: Long, every: Long = FETCH_EVERY): Boolean =
        last == null || last <= 0L || last > now || now - last >= every

    /**
     * Mag de achtergrond aan de andere servers werken? Niet tijdens het afspelen, niet zolang de actieve server laadt,
     * ververst of filtert, en niet in de eerste [START_GRACE] ms na het openen.
     */
    fun idle(playing: Boolean, loading: Boolean, updating: Boolean, filtering: Boolean, sinceStart: Long): Boolean =
        !playing && !loading && !updating && !filtering && sinceStart >= START_GRACE

    /** Volgorde voor "alles vernieuwen": de actieve eerst, dan de overige zoals in de lijst; elke server één keer. */
    fun refreshOrder(ids: List<String>, active: String?): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        ids.firstOrNull { sameServer(it, active) }?.let { out += it; seen += it.lowercase(Locale.ROOT) }
        for (id in ids) if (seen.add(id.lowercase(Locale.ROOT))) out += id
        return out
    }

    /**
     * Handtekening van één doorzoekronde van een server: dezelfde verwijzingen ([refs], volgorde maakt niet uit), dezelfde
     * cache ([stamp]) en dezelfde filters ([filters]) = dezelfde uitkomst, dus niet opnieuw inlezen of doorzoeken.
     */
    fun scanSig(refs: Collection<String>, stamp: Long, filters: String): String =
        refs.sorted().joinToString("\u0001") + "\u0002" + stamp + "\u0002" + filters
}
