package nl.lrvdlinden.bingr

/**
 * Build 178: sport-logo's. Pure logica zonder Android, met unittests (tools/test/SportLogos178LogicTest.kt). Zelfde regels
 * als `Shared/Sport/SportLogos178.swift` (Apple) en `web/js/sportlogos.js` (webspeler); gedeelde testgevallen in
 * docs/sportlogos_cases.json van iptv-ios-appletv.
 *
 * Werkt zoals Weekdot: niets in de app of de repo; logo's worden live opgehaald en op het toestel bewaard, en alleen ter
 * herkenning getoond (vermelding onderaan het sportoverzicht).
 * - competitielogo's: maak-agenda.nl/images/leagues/<slug>.png, anders het favicon van de officiële site (Google);
 * - vlaggen van landenteams: maak-agenda.nl/images/national/<LAND>.png;
 * - clublogo's: de teamlijst van ESPN per competitie, zoeken op de opgeschoonde naam; niet gevonden: TheSportsDB;
 * - cache: index 7 dagen, niet gevonden 1 dag, afbeeldingen 7 dagen; met Data besparen alleen uit de cache.
 */
object SportLogos178Logic {
    const val INDEX_MAX_AGE = 7 * 86400.0
    const val MISS_MAX_AGE = 1 * 86400.0
    const val IMAGE_MAX_AGE = 7 * 86400.0
    val TEAM_SPORTS = setOf("football", "basketball", "americanfootball", "icehockey")

    data class Team(val names: List<String>, val logo: String)

    private val AFFIXES = setOf("fc", "sc", "afc", "cf", "ac", "sv", "bv", "vv", "fk", "sk", "if", "bk",
        "club", "cd", "ud", "sd", "rc", "rcd", "ss", "as", "us", "ogc", "losc", "the")

    /** Sleutels om een club te zoeken: alle woorden aan elkaar, en zonder clubaffixen (als dat iets anders is). */
    fun keys(name: String): List<String> {
        val t = Build173Logic.tokens(Build173Logic.cleanTeam(name))
        val full = t.joinToString("")
        val stripped = t.filter { it !in AFFIXES }.joinToString("")
        val out = ArrayList<String>()
        for (k in listOf(full, stripped)) if (k.isNotEmpty() && k !in out) out += k
        return out
    }

    /** Zoeken in een index (sleutel → logo): eerst precies, dan een uniek voorvoegsel. */
    fun match(wanted: List<String>, index: Map<String, String>): String? {
        for (k in wanted) index[k]?.let { return it }
        for (k in wanted) {
            if (k.length < 3) continue
            val found = HashSet<String>()
            for ((key, value) in index) if (key.startsWith(k) || (k.startsWith(key) && key.length >= 4)) found += value
            if (found.size == 1) return found.first()
        }
        return null
    }

    /** Index uit een teamlijst: elke naam van een team → zijn logo (alleen https; de eerste wint). */
    fun buildIndex(teams: List<Team>): Map<String, String> {
        val index = LinkedHashMap<String, String>()
        for (t in teams) {
            val logo = t.logo.trim()
            if (!logo.lowercase().startsWith("https://")) continue
            for (n in t.names) for (k in keys(n)) if (!index.containsKey(k)) index[k] = logo
        }
        return index
    }

    fun sportsDBThumb(badge: String): String? {
        val b = badge.trim()
        if (!b.lowercase().startsWith("https://")) return null
        return if (b.endsWith("/small")) b else "$b/small"
    }

    fun sportsDBSport(sport: String): String = when (sport) {
        "basketball" -> "Basketball"
        "americanfootball" -> "American Football"
        "icehockey" -> "Ice Hockey"
        else -> "Soccer"
    }

    // MARK: competities

    val LEAGUE_SLUGS = mapOf(
        "eredivisie" to "eredivisie", "kkd" to "keuken-kampioen-divisie", "knvb-beker" to "beker",
        "champions-league" to "champions-league", "europa-league" to "europa-league",
        "conference-league" to "uefa-conference-league", "premier-league" to "premier-league", "laliga" to "primera-division",
        "serie-a" to "serie-a", "bundesliga" to "bundesliga", "ligue-1" to "ligue-1", "jupiler-pro-league" to "jupiler-pro-league",
        "wk" to "fifa-world-cup-2026", "motogp" to "motogp", "formula-1" to "formula-1", "nba" to "nba", "nfl" to "nfl",
    )
    val SITES = mapOf(
        "premier-league-darts" to "pdc.tv", "wk-darts" to "pdc.tv", "pdc" to "pdc.tv",
        "eredivisie" to "eredivisie.nl", "kkd" to "keukenkampioendivisie.nl", "knvb-beker" to "knvb.nl",
        "champions-league" to "uefa.com", "europa-league" to "uefa.com", "conference-league" to "uefa.com",
        "premier-league" to "premierleague.com", "laliga" to "laliga.com", "serie-a" to "legaseriea.it",
        "bundesliga" to "bundesliga.com", "ligue-1" to "ligue1.com", "jupiler-pro-league" to "proleague.be",
        "wk" to "fifa.com", "ek" to "uefa.com", "motogp" to "motogp.com", "formula-1" to "formula1.com",
        "atp" to "atptour.com", "wta" to "wtatennis.com", "nba" to "nba.com", "nfl" to "nfl.com", "nhl" to "nhl.com", "ufc" to "ufc.com",
    )
    fun leagueSlug(id: String): String? = LEAGUE_SLUGS[id]
    fun site(id: String): String? = SITES[id]

    /** Groot = maak-agenda.nl en dan het favicon; klein (lijsten) = eerst het favicon, zoals Weekdot. */
    fun competitionURLs(id: String, small: Boolean): List<String> {
        val out = ArrayList<String>()
        leagueSlug(id)?.let { out += leagueURL(it) }
        site(id)?.let { out += faviconURL(it, 128) }
        return if (small) out.reversed() else out
    }

    // MARK: clubs

    private val FOOTBALL_MAJOR = listOf("ned.1", "eng.1", "esp.1", "ger.1", "ita.1", "fra.1", "por.1", "bel.1", "tur.1",
        "sco.1", "uefa.champions", "uefa.europa", "uefa.europa.conf", "ned.2")

    fun espnLeagues(sport: String, competitionID: String): List<String> = when (sport) {
        "basketball" -> listOf("basketball/nba")
        "americanfootball" -> listOf("football/nfl")
        "icehockey" -> listOf("hockey/nhl")
        "football" -> when (competitionID) {
            "eredivisie" -> listOf("ned.1", "ned.2")
            "kkd" -> listOf("ned.2", "ned.1")
            "knvb-beker" -> listOf("ned.1", "ned.2")
            "champions-league" -> listOf("uefa.champions")
            "europa-league" -> listOf("uefa.europa")
            "conference-league" -> listOf("uefa.europa.conf")
            "premier-league" -> listOf("eng.1", "eng.2")
            "laliga" -> listOf("esp.1")
            "serie-a" -> listOf("ita.1")
            "bundesliga" -> listOf("ger.1", "ger.2")
            "ligue-1" -> listOf("fra.1")
            "jupiler-pro-league" -> listOf("bel.1")
            "wk", "ek" -> emptyList()
            else -> FOOTBALL_MAJOR
        }
        else -> emptyList()
    }

    // MARK: landenteams

    private val COUNTRY_LIST: List<Pair<String, List<String>>> = listOf(
        "NL" to listOf("nederland", "netherlands", "holland", "oranje", "niederlande", "pays bas", "paises bajos"),
        "BE" to listOf("belgie", "belgium", "belgien", "belgique", "belgica"),
        "DE" to listOf("duitsland", "germany", "deutschland", "allemagne", "alemania"),
        "FR" to listOf("frankrijk", "france", "frankreich", "francia"),
        "GB" to listOf("engeland", "england", "angleterre", "inglaterra", "groot brittannie", "great britain"),
        "ES" to listOf("spanje", "spain", "spanien", "espagne", "espana"),
        "IT" to listOf("italie", "italy", "italien", "italia"),
        "PT" to listOf("portugal"),
        "DK" to listOf("denemarken", "denmark", "danemark", "dinamarca"),
        "SE" to listOf("zweden", "sweden", "schweden", "suede", "suecia"),
        "NO" to listOf("noorwegen", "norway", "norwegen", "norvege", "noruega"),
        "FI" to listOf("finland", "finnland", "finlande", "finlandia"),
        "IS" to listOf("ijsland", "iceland", "island", "islande", "islandia"),
        "IE" to listOf("ierland", "ireland", "irland", "irlande", "irlanda"),
        "CH" to listOf("zwitserland", "switzerland", "schweiz", "suisse", "suiza"),
        "AT" to listOf("oostenrijk", "austria", "osterreich", "autriche"),
        "PL" to listOf("polen", "poland", "pologne", "polonia"),
        "CZ" to listOf("tsjechie", "czechia", "czech republic", "tschechien", "tchequie", "chequia"),
        "SK" to listOf("slowakije", "slovakia", "slowakei", "slovaquie", "eslovaquia"),
        "HU" to listOf("hongarije", "hungary", "ungarn", "hongrie", "hungria"),
        "RO" to listOf("roemenie", "romania", "rumanien", "roumanie", "rumania"),
        "BG" to listOf("bulgarije", "bulgaria", "bulgarien", "bulgarie"),
        "GR" to listOf("griekenland", "greece", "griechenland", "grece", "grecia"),
        "TR" to listOf("turkije", "turkey", "turkiye", "turkei", "turquie", "turquia"),
        "HR" to listOf("kroatie", "croatia", "kroatien", "croatie", "croacia"),
        "RS" to listOf("servie", "serbia", "serbien", "serbie"),
        "SI" to listOf("slovenie", "slovenia", "slowenien", "eslovenia"),
        "BA" to listOf("bosnie", "bosnia", "bosnie herzegovina", "bosnia and herzegovina", "bosnien"),
        "ME" to listOf("montenegro"),
        "AL" to listOf("albanie", "albania", "albanien"),
        "MK" to listOf("noord macedonie", "north macedonia", "nordmazedonien"),
        "UA" to listOf("oekraine", "ukraine", "ucrania"),
        "GE" to listOf("georgie", "georgia", "georgien"),
        "AM" to listOf("armenie", "armenia", "armenien"),
        "AZ" to listOf("azerbeidzjan", "azerbaijan", "aserbaidschan"),
        "KZ" to listOf("kazachstan", "kazakhstan", "kasachstan"),
        "IL" to listOf("israel"),
        "CY" to listOf("cyprus", "zypern", "chypre", "chipre"),
        "LU" to listOf("luxemburg", "luxembourg"),
        "LT" to listOf("litouwen", "lithuania", "litauen"),
        "LV" to listOf("letland", "latvia", "lettland"),
        "EE" to listOf("estland", "estonia"),
        "MT" to listOf("malta"),
        "FO" to listOf("faeroer", "faroe islands", "faroer"),
        "SM" to listOf("san marino"),
        "AD" to listOf("andorra"),
        "LI" to listOf("liechtenstein"),
        "GI" to listOf("gibraltar"),
        "MD" to listOf("moldavie", "moldova", "moldawien"),
        "BY" to listOf("wit rusland", "belarus", "weissrussland"),
        "RU" to listOf("rusland", "russia", "russland", "russie", "rusia"),
        "US" to listOf("verenigde staten", "united states", "usa"),
        "CA" to listOf("canada", "kanada"),
        "MX" to listOf("mexico", "mexiko"),
        "BR" to listOf("brazilie", "brazil", "brasilien", "bresil", "brasil"),
        "AR" to listOf("argentinie", "argentina", "argentinien", "argentine"),
        "UY" to listOf("uruguay"),
        "CO" to listOf("colombia", "kolumbien", "colombie"),
        "CL" to listOf("chili", "chile"),
        "EC" to listOf("ecuador"),
        "PE" to listOf("peru"),
        "PY" to listOf("paraguay"),
        "JP" to listOf("japan", "japon"),
        "KR" to listOf("zuid korea", "south korea", "korea republic", "sudkorea"),
        "AU" to listOf("australie", "australia", "australien"),
        "MA" to listOf("marokko", "morocco", "maroc", "marruecos"),
        "SN" to listOf("senegal"),
        "NG" to listOf("nigeria"),
        "GH" to listOf("ghana"),
        "CM" to listOf("kameroen", "cameroon", "kamerun", "cameroun"),
        "EG" to listOf("egypte", "egypt", "agypten"),
        "TN" to listOf("tunesie", "tunisia", "tunesien", "tunisie"),
        "DZ" to listOf("algerije", "algeria", "algerien", "algerie"),
        "CI" to listOf("ivoorkust", "ivory coast", "cote d ivoire", "elfenbeinkuste"),
        "ZA" to listOf("zuid afrika", "south africa", "sudafrika"),
        "SA" to listOf("saoedi arabie", "saudi arabia", "saudi arabien"),
        "QA" to listOf("qatar", "katar"),
        "IR" to listOf("iran"),
        "CW" to listOf("curacao"),
        "SR" to listOf("suriname"),
    )
    private val COUNTRIES: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        for ((code, names) in COUNTRY_LIST) for (n in names) m[Build173Logic.tokens(n).joinToString("")] = code
        m
    }
    private val WOMEN = setOf("vrouwen", "women", "womens", "dames", "frauen", "femmes", "w")

    /** Landcode als deze naam een landenteam is, anders null. */
    fun countryCode(name: String): String? {
        val k = Build173Logic.tokens(Build173Logic.cleanTeam(name)).filter { it !in WOMEN }.joinToString("")
        if (k.isEmpty()) return null
        return COUNTRIES[k]
    }

    /** "flag:<LAND>", "club" of "" (cirkel met initialen). */
    fun teamArt(name: String, sport: String, competitionID: String): String {
        if (sport !in TEAM_SPORTS) return ""
        countryCode(name)?.let { return "flag:$it" }
        return if (espnLeagues(sport, competitionID).isEmpty()) "" else "club"
    }

    // MARK: adressen

    fun leagueURL(slug: String) = "https://maak-agenda.nl/images/leagues/${slug.split("/").first()}.png"
    fun flagURL(code: String) = "https://maak-agenda.nl/images/national/${code.uppercase()}.png"
    fun faviconURL(domain: String, size: Int) = "https://www.google.com/s2/favicons?domain=${encode(domain)}&sz=$size"
    fun espnTeamsURL(code: String) =
        "https://site.api.espn.com/apis/site/v2/sports/" + (if (code.contains("/")) code else "soccer/$code") + "/teams"
    fun sportsDBSearchURL(name: String) =
        "https://www.thesportsdb.com/api/v1/json/123/searchteams.php?t=" + encode(Build173Logic.cleanTeam(name))

    /** Procent-codering: alles behalve A-Z a-z 0-9 - . _ ~ (UTF-8, hoofdletters). */
    fun encode(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            if (c in 0x41..0x5A || c in 0x61..0x7A || c in 0x30..0x39 || c == 0x2D || c == 0x2E || c == 0x5F || c == 0x7E) sb.append(c.toChar())
            else sb.append('%').append(String.format(java.util.Locale.ROOT, "%02X", c))
        }
        return sb.toString()
    }

    // MARK: cache

    /** Mag een bewaard item gebruikt worden? kind = index | miss | image; age in seconden. Data besparen: alles wat er is. */
    fun usable(kind: String, age: Double, saver: Boolean): Boolean {
        if (saver) return true
        val max = when (kind) { "index" -> INDEX_MAX_AGE; "miss" -> MISS_MAX_AGE; else -> IMAGE_MAX_AGE }
        return age >= 0 && age < max
    }

    /** Naam van een cachebestand: voorvoegsel + FNV-1a (8 hex) van de sleutel. */
    fun cacheName(prefix: String, key: String, ext: String): String =
        prefix + "-" + String.format(java.util.Locale.ROOT, "%08x", Build173Logic.hash(key)) + (if (ext.isEmpty()) "" else ".$ext")
}
