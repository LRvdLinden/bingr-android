package nl.lrvdlinden.bingr

import java.text.Normalizer
import java.util.Calendar
import java.util.TimeZone

/**
 * Build 172 (Sport): pure logica zonder Android, met unittests (tools/test/Build173LogicTest.kt). Zelfde regels als
 * `Shared/Sport/Build173.swift` (Apple) en `web/js/sportlogic.js` (webspeler); gedeelde testgevallen in
 * docs/sport_cases.json van iptv-ios-appletv.
 *
 * - sportzenders herkennen aan de categorie (en favoriete zenders die op sport lijken);
 * - een programma is een wedstrijd als de titel een duel is ("A - B", "A vs B", "A v B") of een bekend evenement;
 * - herhalingen, samenvattingen en studio's niet; korter dan 20 min alleen evenementen;
 * - sport en competitie uit titel, beschrijving, zender en categorie (kleine tabel), anders "other";
 * - teamnamen opschonen, initialen (3 tekens) en een vaste kleur;
 * - ontdubbelen over zenders/servers (zelfde teams of titel, aftrap ±15 min): favoriet > kwaliteit > gids;
 * - een vaste sleutel per wedstrijd (herinneringen, dempen).
 */
object Build173Logic {

    data class Match(
        /** "duel" of "event" */
        val kind: String,
        val title: String,
        val home: String,
        val away: String,
        val sport: String,
        val competition: String,
        val competitionId: String,
    ) { val isDuel get() = kind == "duel" }

    /** Eén programma op één zender dat een wedstrijd is (tijden in ms). */
    data class Listing(val id: String, val channel: String, val favorite: Boolean, val start: Long, val end: Long, val match: Match)

    /** Eén wedstrijd met alle zenders (beste eerst). */
    data class Group(val key: String, val start: Long, val end: Long, val match: Match, val listings: List<Listing>)

    data class Competition(val id: String, val name: String, val sport: String, val phrases: List<String>)

    const val MIN_MINUTES = 20.0
    const val MERGE_MS = 15 * 60_000L
    const val DAYS = 7
    val LEADS = listOf(0, 5, 15)
    const val DEFAULT_LEAD = 5
    val SPORTS = listOf("football", "motorsport", "tennis", "darts", "cycling", "basketball", "icehockey",
        "americanfootball", "combat", "golf", "other")
    val COLORS = longArrayOf(0xC8102E, 0x1D4ED8, 0x15803D, 0xB45309, 0x7C3AED, 0xBE185D,
        0x0E7490, 0x4D7C0F, 0x9A3412, 0x1E3A8A, 0x6D28D9, 0x374151)

    // MARK: tekst

    private val EXTRA = mapOf('ß' to "ss", 'ø' to "o", 'æ' to "ae", 'œ' to "oe", 'ł' to "l", 'đ' to "d", 'ı' to "i", 'þ' to "th")
    private val MARKS = Regex("[\\u0300-\\u036f]")

    /** Kleine letters, zonder accenten (NFD zonder U+0300–U+036F) en een paar losse letters. */
    fun fold(s: String): String {
        val lower = MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase()
        val sb = StringBuilder(lower.length)
        for (c in lower) { val r = EXTRA[c]; if (r != null) sb.append(r) else sb.append(c) }
        return sb.toString()
    }

    /** Woorden van [a-z0-9] na fold. */
    fun tokens(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (c in fold(s)) {
            if (c in 'a'..'z' || c in '0'..'9') cur.append(c)
            else if (cur.isNotEmpty()) { out += cur.toString(); cur.setLength(0) }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    private fun padded(s: String) = " " + tokens(s).joinToString(" ") + " "
    private fun hasPhrase(p: String, phrase: String) = p.contains(" $phrase ")
    private fun anyPhrase(p: String, list: List<String>) = list.any { hasPhrase(p, it) }

    // MARK: zenders en categorieën

    private val CAT_WORDS = listOf("sport", "sports", "voetbal", "football", "soccer", "f1", "motorsport", "tennis", "darts", "golf",
        "nba", "nfl", "nhl", "ufc", "boxing", "dazn", "espn", "eurosport", "viaplay", "bein", "supersport")
    private val CAT_PHRASES = listOf("ziggo sport", "sky sports", "tnt sports", "canal sport", "arena sport")
    private val EVENT_WORDS = listOf("events", "event", "ppv")

    private fun wordHit(tokens: List<String>, words: List<String>): Boolean {
        for (t in tokens) for (w in words) {
            if (t == w) return true
            if (t.length > w.length && t.startsWith(w) && t.substring(w.length).all { it in '0'..'9' }) return true
        }
        return false
    }

    fun looksSport(name: String): Boolean {
        val t = tokens(name)
        return wordHit(t, CAT_WORDS) || anyPhrase(" " + t.joinToString(" ") + " ", CAT_PHRASES)
    }
    fun isEventCategory(name: String) = wordHit(tokens(name), EVENT_WORDS)
    fun isSportCategory(name: String) = looksSport(name) || isEventCategory(name)
    fun isSportChannel(channelName: String, category: String, favorite: Boolean) =
        isSportCategory(category) || (favorite && looksSport(channelName))

    /** 4 = 4K/UHD, 3 = FHD, 2 = HD (en onbekend), 1 = SD. */
    fun quality(channelName: String): Int {
        val t = tokens(channelName).toSet()
        if (t.any { it in setOf("4k", "uhd", "2160p", "2160") }) return 4
        if (t.any { it in setOf("fhd", "1080p", "1080", "1080i") }) return 3
        if (t.any { it in setOf("sd", "576p", "480p", "576i") }) return 1
        return 2
    }

    // MARK: titels opschonen

    private val DROP_WORDS = setOf("live", "hd", "fhd", "uhd", "4k", "sd", "hevc", "h265", "h264", "50fps", "60fps", "1080p", "720p", "2160p")
    private val TIME = Regex("^[0-9]{1,2}[:.h][0-9]{2}u?$", RegexOption.IGNORE_CASE)
    private val BRACKETS = Regex("\\([^()]*\\)|\\[[^\\[\\]]*\\]|\\{[^{}]*\\}")
    private val PIPE_TAG = Regex("^\\s*\\|[^|]{1,12}\\|\\s*")
    private val COUNTRY = Regex("^\\s*[A-Z]{2,3}\\s*[:|]\\s*")
    private val SPACES = Regex("\\s+")
    private const val TRIM = " -|:.,·•*–—_/"

    private fun stripBrackets(s: String): String {
        var cur = s
        while (true) { val next = BRACKETS.replace(cur, " "); if (next == cur) return cur; cur = next }
    }

    private fun stripPrefixTags(s: String): String {
        var cur = s
        while (true) {
            val next = COUNTRY.replaceFirst(PIPE_TAG.replaceFirst(cur, ""), "")
            if (next == cur) return cur
            cur = next
        }
    }

    private fun trimPunct(s: String) = s.trim { it in TRIM }
    private fun words(s: String) = s.split(SPACES).filter { it.isNotEmpty() }
    private fun collapse(s: String) = SPACES.replace(s, " ").trim()

    private fun dropNoise(s: String) = words(s).filter { w ->
        val core = trimPunct(w)
        core.isEmpty() || !(fold(core) in DROP_WORDS || TIME.matches(core))
    }.joinToString(" ")

    fun cleanTitle(raw: String): String {
        var s = stripPrefixTags(raw)
        s = stripBrackets(s)
        s = dropNoise(s)
        s = trimPunct(collapse(s))
        return collapse(s)
    }

    fun cleanTeam(raw: String) = cleanTitle(raw)

    // MARK: herhalingen

    private val REPLAY = setOf("herh", "herhaling", "replay", "samenvatting", "samenvattingen", "highlights", "studio",
        "voorbeschouwing", "nabeschouwing", "magazine")
    private val RERUN = Regex("\\(\\s*r\\s*\\)", RegexOption.IGNORE_CASE)

    fun isExcluded(title: String) = RERUN.containsMatchIn(title) || tokens(title).any { it in REPLAY }

    // MARK: competities (volgorde telt: specifiek vóór algemeen)

    val COMPETITIONS = listOf(
        Competition("premier-league-darts", "Premier League Darts", "darts", listOf("premier league darts")),
        Competition("wk-darts", "WK Darts", "darts", listOf("wk darts", "world darts championship", "pdc world championship")),
        Competition("pdc", "PDC", "darts", listOf("pdc")),
        Competition("eredivisie", "Eredivisie", "football", listOf("eredivisie")),
        Competition("kkd", "Keuken Kampioen Divisie", "football", listOf("keuken kampioen divisie", "kkd", "eerste divisie")),
        Competition("knvb-beker", "KNVB beker", "football", listOf("knvb beker", "knvb cup")),
        Competition("champions-league", "Champions League", "football", listOf("champions league", "ucl")),
        Competition("europa-league", "Europa League", "football", listOf("europa league", "uel")),
        Competition("conference-league", "Conference League", "football", listOf("conference league", "uecl")),
        Competition("premier-league", "Premier League", "football", listOf("premier league", "epl")),
        Competition("laliga", "LaLiga", "football", listOf("laliga", "la liga")),
        Competition("serie-a", "Serie A", "football", listOf("serie a")),
        Competition("bundesliga", "Bundesliga", "football", listOf("bundesliga")),
        Competition("ligue-1", "Ligue 1", "football", listOf("ligue 1")),
        Competition("jupiler-pro-league", "Jupiler Pro League", "football", listOf("jupiler pro league", "jupiler")),
        Competition("wk", "WK", "football", listOf("wk voetbal", "fifa world cup", "wk kwalificatie", "world cup qualifier", "world cup qualifiers")),
        Competition("ek", "EK", "football", listOf("ek voetbal", "uefa euro", "ek kwalificatie", "euro qualifier", "euro qualifiers")),
        Competition("motogp", "MotoGP", "motorsport", listOf("motogp", "moto gp")),
        Competition("formula-1", "Formule 1", "motorsport", listOf("formule 1", "formula 1", "f1", "grand prix", "gp")),
        Competition("atp", "ATP", "tennis", listOf("atp")),
        Competition("wta", "WTA", "tennis", listOf("wta")),
        Competition("nba", "NBA", "basketball", listOf("nba")),
        Competition("nfl", "NFL", "americanfootball", listOf("nfl")),
        Competition("nhl", "NHL", "icehockey", listOf("nhl")),
        Competition("ufc", "UFC", "combat", listOf("ufc")),
    )

    fun findCompetition(text: String): Competition? {
        val p = padded(text)
        if (p.length <= 2) return null
        return COMPETITIONS.firstOrNull { anyPhrase(p, it.phrases) }
    }
    fun competitionById(id: String) = COMPETITIONS.firstOrNull { it.id == id }

    // MARK: evenementen

    private val EV_DARTS = listOf("darts", "pdc", "premier league darts", "wk darts")
    private val EV_TENNIS = listOf("atp", "wta", "wimbledon", "roland garros", "australian open", "us open", "davis cup",
        "billie jean king cup", "grand slam")
    private val EV_CYCLING = listOf("tour de", "giro", "vuelta", "parijs roubaix", "paris roubaix", "ronde van vlaanderen",
        "tour of flanders", "milaan sanremo", "milano sanremo", "luik bastenaken luik", "liege bastogne liege",
        "amstel gold race", "strade bianche", "omloop het nieuwsblad", "gent wevelgem", "il lombardia", "wielrennen", "cycling")
    private val EV_COMBAT = listOf("ufc", "boksen", "boxing", "mma")
    private val EV_MOTOR = listOf("grand prix", "gp", "formule 1", "formula 1", "f1", "motogp", "moto gp", "kwalificatie",
        "qualifying", "indycar", "nascar")
    private val EV_MOTOR_WEAK = listOf("race", "sprint", "training", "vrije training", "practice", "fp1", "fp2", "fp3")

    fun eventSport(title: String, context: String): String? {
        val p = padded(title)
        return when {
            anyPhrase(p, EV_DARTS) -> "darts"
            anyPhrase(p, EV_TENNIS) -> "tennis"
            anyPhrase(p, EV_CYCLING) -> "cycling"
            anyPhrase(p, EV_COMBAT) -> "combat"
            anyPhrase(p, EV_MOTOR) -> "motorsport"
            anyPhrase(p, EV_MOTOR_WEAK) && anyPhrase(padded(context), EV_MOTOR + "motorsport") -> "motorsport"
            else -> null
        }
    }

    private val KEYWORDS = listOf(
        "americanfootball" to listOf("american football", "nfl"),
        "icehockey" to listOf("ijshockey", "ice hockey", "hockey", "nhl"),
        "basketball" to listOf("basketbal", "basketball", "nba", "euroleague"),
        "motorsport" to listOf("f1", "formule 1", "formula 1", "motorsport", "motogp", "racing"),
        "tennis" to listOf("tennis"),
        "darts" to listOf("darts"),
        "cycling" to listOf("wielrennen", "cycling", "wielersport"),
        "combat" to listOf("vechtsport", "ufc", "boxing", "boksen", "mma", "kickboxing"),
        "golf" to listOf("golf", "pga"),
        "football" to listOf("voetbal", "football", "soccer", "fussball", "futbol", "calcio"),
    )
    fun keywordSport(text: String): String? { val p = padded(text); return KEYWORDS.firstOrNull { anyPhrase(p, it.second) }?.first }

    // MARK: duels

    private val NOT_TEAM = setOf("journaal", "weer", "nieuws", "news", "weather", "talkshow", "show", "documentaire", "documentary",
        "aflevering", "episode", "seizoen", "season", "deel", "part", "film", "movie", "serie", "series",
        "race", "sprint", "kwalificatie", "qualifying", "training", "etappe", "stage", "gp")
    private val GENERIC = setOf("sport", "sports", "voetbal", "football", "soccer", "live", "tennis", "darts", "wielrennen")
    private val SEP = Regex("\\s+(?:vs\\.?|v|-|–|—)\\s+", RegexOption.IGNORE_CASE)

    private fun isLabelPart(part: String): Boolean {
        val t = tokens(part)
        if (t.isEmpty()) return true
        val j = t.joinToString(" ")
        if (j in GENERIC) return true
        return COMPETITIONS.any { j in it.phrases || fold(it.name) == j }
    }

    private fun hasLatinLetter(w: String) = w.any { it in 'A'..'Z' || it in 'a'..'z' || it.code in 0xC0..0x24F }

    private fun validTeam(name: String): Boolean {
        val t = tokens(name)
        if (fold(name).count { it in 'a'..'z' } < 2) return false
        if (words(name).count(::hasLatinLetter) > 4) return false
        if (t.any { it in NOT_TEAM }) return false
        if (t.joinToString(" ") in GENERIC) return false
        return true
    }

    private fun splitDuel(text: String): Pair<String, String>? {
        val parts = text.split(SEP).map { it.trim() }.toMutableList()
        while (parts.size > 2 && isLabelPart(parts[0])) parts.removeAt(0)
        while (parts.size > 2 && isLabelPart(parts[parts.size - 1])) parts.removeAt(parts.size - 1)
        if (parts.size != 2) return null
        val home = cleanTeam(parts[0]); val away = cleanTeam(parts[1])
        if (!validTeam(home) || !validTeam(away)) return null
        return home to away
    }

    fun parseDuel(title: String): Pair<String, String>? {
        val s = collapse(stripBrackets(stripPrefixTags(title)))
        val c = s.indexOf(": ")
        if (c > 0) {
            val head = s.substring(0, c); val tail = s.substring(c + 2)
            if (!SEP.containsMatchIn(head)) splitDuel(tail)?.let { return it }
        }
        return splitDuel(s)
    }

    // MARK: teams

    private val AFFIX = setOf("fc", "afc", "cf", "sc", "sv", "vv", "bv", "fk", "sk", "cd", "ud", "ac", "as", "ss", "ssc", "us", "rc", "sl")
    private val NUMERIC = Regex("^[0-9.]+$")
    private val WORD_SPLIT = Regex("[\\s\\-]+")

    fun teamKey(name: String): String {
        val t = tokens(cleanTeam(name))
        val k = t.filter { it !in AFFIX }
        return (k.ifEmpty { t }).joinToString("")
    }

    fun short(name: String): String {
        val clean = cleanTeam(name)
        val raw = clean.split(WORD_SPLIT).filter { tokens(it).isNotEmpty() }
        var ws = raw.filter { tokens(it).joinToString("") !in AFFIX && !NUMERIC.matches(it) }
        if (ws.isEmpty()) ws = raw
        if (ws.isEmpty()) return ""
        fun letters(w: String) = fold(w).filter { it in 'a'..'z' || it in '0'..'9' }.uppercase()
        fun isCaps(w: String): Boolean { val l = w.filter { it in 'A'..'Z' || it in 'a'..'z' }; return l.isNotEmpty() && l == l.uppercase() }
        val first = ws[0]
        if (ws.size == 1) return letters(first).take(3)
        if (ws.size == 2) return if (isCaps(first) && letters(first).length <= 3) letters(first) else letters(first).take(3)
        return ws.take(3).joinToString("") { letters(it).take(1) }
    }

    /** FNV-1a (32 bit) over de UTF-8-bytes. */
    fun hash(s: String): Long {
        var h = 0x811c9dc5L
        for (b in s.toByteArray(Charsets.UTF_8)) { h = h xor (b.toLong() and 0xff); h = (h * 0x01000193L) and 0xffffffffL }
        return h
    }
    fun colorIndex(name: String) = (hash(teamKey(name)) % COLORS.size).toInt()
    fun color(name: String) = COLORS[colorIndex(name)]

    // MARK: herkennen

    fun classify(title: String, description: String? = null, channel: String = "", category: String = "", start: Long, end: Long): Match? {
        if (title.isEmpty() || isExcluded(title)) return null
        val desc = description ?: ""
        val ev = eventSport(title, listOf(channel, category, desc).joinToString(" "))
        val duel = if (ev == "motorsport" || ev == "cycling") null else parseDuel(title)
        if (duel == null && ev == null) return null
        val minutes = (end - start) / 60_000.0
        if (duel != null && ev == null && !(minutes >= MIN_MINUTES)) return null
        var comp = findCompetition(title) ?: findCompetition(desc)
        val sport: String
        if (comp != null) sport = comp.sport
        else {
            val cc = findCompetition(channel) ?: findCompetition(category)
            if (ev != null) { sport = ev; comp = if (cc?.sport == ev) cc else null }
            else if (cc != null) { sport = cc.sport; comp = cc }
            else sport = keywordSport(title) ?: keywordSport(desc) ?: keywordSport(channel) ?: keywordSport(category) ?: "other"
        }
        return Match(if (duel != null) "duel" else "event", duel?.let { it.first + " - " + it.second } ?: cleanTitle(title),
            duel?.first ?: "", duel?.second ?: "", sport, comp?.name ?: "", comp?.id ?: "")
    }

    private fun utcStamp(ms: Long, withTime: Boolean): String {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = ms }
        var s = String.format(java.util.Locale.ROOT, "%04d%02d%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
        if (withTime) s += String.format(java.util.Locale.ROOT, "%02d%02d", c.get(Calendar.HOUR_OF_DAY), if (c.get(Calendar.MINUTE) < 30) 0 else 30)
        return s
    }

    /** "sport:<a>~<b>:<jjjjmmdd>" (teams op volgorde) of "sport:<titel>:<jjjjmmdduumm>". */
    fun matchKey(m: Match, start: Long): String {
        if (m.isDuel) {
            val a = teamKey(m.home); val b = teamKey(m.away)
            return "sport:" + (if (a <= b) "$a~$b" else "$b~$a") + ":" + utcStamp(start, false)
        }
        return "sport:" + tokens(m.title).joinToString("") + ":" + utcStamp(start, true)
    }

    private val KEY_DAY = Regex(":([0-9]{8})(?:[0-9]{4})?$")
    fun keyDay(key: String): String? = KEY_DAY.find(key)?.groupValues?.get(1)

    /** Zelfde identiteit (zelfde teams of zelfde titel) en binnen 15 min = dezelfde wedstrijd. */
    fun ident(m: Match): String =
        if (m.isDuel) "d:" + listOf(teamKey(m.home), teamKey(m.away)).sorted().joinToString("~")
        else "e:" + tokens(m.title).joinToString("")

    /** Ontdubbelen (invoer in gidsvolgorde); op aftrap gesorteerd, per wedstrijd: favoriet, kwaliteit, gids. */
    fun dedupe(listings: List<Listing>): List<Group> {
        val groups = ArrayList<MutableList<IndexedValue<Listing>>>()
        val byIdent = HashMap<String, MutableList<Int>>()
        listings.withIndex().forEach { l ->
            val cand = byIdent.getOrPut(ident(l.value.match)) { ArrayList() }
            val g = cand.firstOrNull { kotlin.math.abs(groups[it][0].value.start - l.value.start) <= MERGE_MS }
            if (g != null) groups[g] += l else { cand += groups.size; groups += mutableListOf(l) }
        }
        val out = groups.mapIndexed { gi, ls ->
            val sorted = ls.sortedWith(compareBy<IndexedValue<Listing>>({ !it.value.favorite }, { -quality(it.value.channel) }, { it.index })).map { it.value }
            val best = sorted[0]
            gi to Group(matchKey(best.match, best.start), best.start, best.end, best.match, sorted)
        }
        return out.sortedWith(compareBy<Pair<Int, Group>>({ it.second.start }, { it.second.match.title }, { it.first })).map { it.second }
    }

    // MARK: herinneringen

    fun lead(n: Int?): Int = if (n != null && n in LEADS) n else DEFAULT_LEAD

    fun isFavorite(m: Match, teams: Collection<String>, competitions: Collection<String>): Boolean {
        val keys = teams.map(::teamKey).toSet()
        if (m.isDuel && (teamKey(m.home) in keys || teamKey(m.away) in keys)) return true
        return m.competitionId.isNotEmpty() && m.competitionId in competitions
    }

    fun autoReminders(groups: List<Group>, now: Long, teams: Collection<String>, competitions: Collection<String>,
                      muted: Set<String>, existing: Set<String>): List<String> {
        val until = now + DAYS * 86_400_000L
        return groups.filter { g ->
            g.start > now && g.start <= until && isFavorite(g.match, teams, competitions) && g.key !in muted && g.key !in existing
        }.map { it.key }
    }

    fun pruneMuted(keys: Collection<String>, now: Long): List<String> {
        val limit = utcStamp(now - 2 * 86_400_000L, false)
        return keys.filter { k -> keyDay(k)?.let { it >= limit } ?: true }
    }
}
