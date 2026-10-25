package nl.lrvdlinden.bingr

import java.util.Locale

/**
 * Build 150: pure logica (zonder Android, getest in tools/test/Build150LogicTest.kt en, voor Samsung TV en LG, in
 * tizen/test/build150_test.js met dezelfde gevallen; js/build150.js is dezelfde code).
 *
 * - [CategoryName]: taal-, land- en kwaliteitsaanduidingen weg uit categorienamen, in elke combinatie en volgorde,
 *   voorin en achterin ("| NL | NETFLIX" → "Netflix", "| MULTI / LINGO | NETFLIX" → "Netflix", "NETFLIX (NL)" →
 *   "Netflix"), met nette hoofdletters ("BIOSCOOP" → "Bioscoop", "HBO MAX" → "HBO Max"). Wat weggehaald is, komt als
 *   label terug ([CategoryName.Clean.tags]: "NL", "Multi"). Eén plek; zoeken ([Build149Logic.Search.key]) gebruikt
 *   dezelfde opschoning ([CategoryName.strip]).
 * - [Merge]: categorieën met dezelfde opgeschoonde naam (hoofdletterongevoelig, over talen, kwaliteit en servers heen)
 *   samenvoegen tot één tegel/rij, de inhoud ontdubbeld (zelfde IMDb/TMDB-id, of anders genormaliseerde titel + jaar).
 * - [Logos]: welk TMDB-logo bij een (opgeschoonde) categorienaam hoort, met een betere alias-tabel.
 */
object Build150Logic {

    object CategoryName {
        /** Scheidingstekens (altijd). `-` en `/` alleen met een spatie ernaast of aan de rand ("Sci-Fi", "AC/DC" blijven). */
        private const val SEPS = "|¦│:–—•·★»«[](){}"
        private fun isSepChar(c: Char) = SEPS.indexOf(c) >= 0
        private fun isSpace(c: Char) = c == ' ' || c == '\t' || c == ' ' || c == '\n' || c == '\r'

        /** ISO 639-1 (talen). */
        private val ISO639 = ("aa ab ae af ak am an ar as av ay az ba be bg bh bi bm bn bo br bs ca ce ch co cr cs cu cv cy da de dv dz " +
            "ee el en eo es et eu fa ff fi fj fo fr fy ga gd gl gn gu gv ha he hi ho hr ht hu hy hz ia id ie ig ii ik io is it iu ja " +
            "jv ka kg ki kj kk kl km kn ko kr ks ku kv kw ky la lb lg li ln lo lt lu lv mg mh mi mk ml mn mr ms mt my na nb nd ne ng " +
            "nl nn no nr nv ny oc oj om or os pa pi pl ps pt qu rm rn ro ru rw sa sc sd se sg si sk sl sm sn so sq sr ss st su sv sw " +
            "ta te tg th ti tk tl tn to tr ts tt tw ty ug uk ur uz ve vi vo wa wo xh yi yo za zh zu").split(' ').toHashSet()
        /** ISO 3166-1 alpha-2 (landen) plus UK en EU. */
        private val ISO3166 = ("ad ae af ag ai al am ao aq ar as at au aw ax az ba bb bd be bf bg bh bi bj bl bm bn bo bq br bs bt bv bw " +
            "by bz ca cc cd cf cg ch ci ck cl cm cn co cr cu cv cw cx cy cz de dj dk dm do dz ec ee eg eh er es et fi fj fk fm fo fr " +
            "ga gb gd ge gf gg gh gi gl gm gn gp gq gr gs gt gu gw gy hk hm hn hr ht hu id ie il im in io iq ir is it je jm jo jp ke " +
            "kg kh ki km kn kp kr kw ky kz la lb lc li lk lr ls lt lu lv ly ma mc md me mf mg mh mk ml mm mn mo mp mq mr ms mt mu mv " +
            "mw mx my mz na nc ne nf ng ni nl no np nr nu nz om pa pe pf pg ph pk pl pm pn pr ps pt pw py qa re ro rs ru rw sa sb sc " +
            "sd se sg sh si sj sk sl sm sn so sr ss st sv sx sy sz tc td tf tg th tj tk tl tm tn to tr tt tw tz ua ug um us uy " +
            "uz va vc ve vg vi vn vu wf ws ye yt za zm zw uk eu").split(' ').toHashSet()
        /** Codes zoals aanbieders ze gebruiken (ook los, zonder scheidingsteken: "NL Films", "Kids NL"). */
        private val COMMON = ("nl be vl en uk us gb de at ch fr es it pt br tr pl ar ru se dk no fi gr jp kr cn al ro hu cz il ir pk " +
            "in th vn ku ua bg hr rs ma dz tn eg sa ae lat nld dut eng ger deu fra fre spa esp ita por tur ara pol rus swe dan nor " +
            "fin jpn kor chi alb hin usa nlbe ptbr exyu yu").split(' ').toHashSet()
        /** Los (zonder scheidingsteken) geen taal: gewone woorden ("No Sports", "IT Crowd", "In Bruges"). Nooit een code: TV. */
        private val BARE_SKIP = setOf("no", "at", "he", "el", "da", "th", "ch", "it", "in", "hi", "be", "al", "ma", "me", "am", "to", "so", "or", "is", "us")
        private val NEVER = setOf("tv", "go", "f1", "dc", "uf")
        /** Talen en landen voluit (NL en EN), alleen met een scheidingsteken of tussen haken. */
        private val NAMES: Map<String, String> = buildMap {
            fun add(code: String, vararg k: String) { for (x in k) put(x, code) }
            add("NL", "nederlands", "nederlandse", "dutch", "nederland", "netherlands", "holland", "hollands")
            add("BE", "vlaams", "vlaamse", "flemish", "belgie", "belgië", "belgium", "belgisch")
            add("EN", "engels", "engelse", "english", "britain", "british", "american", "amerikaans")
            add("DE", "duits", "duitse", "german", "deutsch", "duitsland", "germany", "deutschland")
            add("FR", "frans", "franse", "french", "francais", "français", "frankrijk", "france")
            add("ES", "spaans", "spaanse", "spanish", "espanol", "español", "spanje", "spain", "latino", "castellano")
            add("IT", "italiaans", "italiaanse", "italian", "italiano", "italie", "italië", "italy")
            add("PT", "portugees", "portugese", "portuguese", "portugues", "português", "brazil", "brasil", "braziliaans")
            add("TR", "turks", "turkse", "turkish", "turkce", "türkçe", "turkije", "turkey")
            add("AR", "arabisch", "arabische", "arabic", "arab")
            add("PL", "pools", "poolse", "polish", "polski", "polen", "poland")
            add("RU", "russisch", "russische", "russian", "rusland", "russia")
            add("HI", "hindi", "indian", "indiaas")
            add("SV", "zweeds", "swedish")
            add("DA", "deens", "danish")
            add("NO", "noors", "norwegian")
            add("EL", "grieks", "greek")
            add("AL", "albanees", "albanian", "shqip")
            add("KU", "koerdisch", "kurdish")
            add("RO", "roemeens", "romanian")
            add("UK", "oekraïens", "ukrainian")
            add("Intl", "international", "internationaal", "world", "wereld")
        }
        /** Meertalig, ondertiteld, nagesynchroniseerd (los of met scheidingsteken, nooit als de naam alleen dat is). */
        private val MULTI = setOf("multi", "multilang", "multilingo", "multilanguage", "multiaudio", "multisub", "multisubs", "lingo",
            "lang", "dual", "dualaudio", "multilingual")
        private val SUBDUB = setOf("sub", "subs", "subbed", "subtitled", "dub", "dubbed", "vostfr", "vost", "vose", "vo", "vf", "vff")
        private val QUALITY = setOf("4k", "uhd", "fhd", "hd", "sd", "hevc", "h265", "h264", "x265", "hdr", "hdr10", "dv", "1080p",
            "720p", "2160p", "4kuhd", "uhd4k", "8k", "3d", "atmos", "raw", "60fps", "50fps")
        /** Alleen vooraan met een scheidingsteken ("VOD | Netflix", "FILMS: Actie"). */
        private val KIND = setOf("vod", "series", "serie", "serien", "films", "film", "movies", "movie", "tv shows", "shows")
        private val SUBRE = Regex("^([a-z]{2,3})(sub|subs|dub)$")

        enum class Kind { CODE, NAME, MULTI, SUB, QUALITY, KIND }

        /** Wat voor label is dit woord (null = gewoon woord)? [sep] = met scheidingsteken/haken ernaast. */
        fun kindOf(word: String, sep: Boolean, front: Boolean): Kind? {
            val w = word.lowercase(Locale.ROOT)
            val n = w.replace("-", "").replace("_", "").replace(".", "")
            if (n.isEmpty()) return null
            if (n in NEVER) return null
            if (n in QUALITY) return Kind.QUALITY
            if (n in MULTI) return Kind.MULTI
            if (n in SUBDUB) return Kind.SUB
            SUBRE.find(n)?.let { m -> if (m.groupValues[1] in COMMON || m.groupValues[1] in ISO639) return Kind.SUB }
            val upper = word == word.uppercase(Locale.ROOT)
            val letters = n.all { it in 'a'..'z' }
            if (letters && (n.length == 2 || n.length == 3 || n == "nlbe" || n == "ptbr" || n == "exyu")) {
                if (sep) {
                    if (n in COMMON && (upper || n.length == 2)) return Kind.CODE
                    if (upper && n.length == 2 && (n in ISO639 || n in ISO3166)) return Kind.CODE
                } else if (upper && n in COMMON && n !in BARE_SKIP) return Kind.CODE
            }
            if (sep && w in NAMES) return Kind.NAME
            if (sep && front && n in KIND) return Kind.KIND
            return null
        }

        /** Label voor de chip ("NL", "Multi"); kwaliteit en soort geen label. */
        private fun tagOf(word: String, kind: Kind): String? = when (kind) {
            Kind.CODE -> word.uppercase(Locale.ROOT).let { if (it == "VL") "BE" else if (it == "GB") "UK" else it }
            Kind.NAME -> NAMES[word.lowercase(Locale.ROOT)]
            Kind.MULTI -> "Multi"
            Kind.SUB -> SUBRE.find(word.lowercase(Locale.ROOT).replace("-", ""))?.let { it.groupValues[1].uppercase(Locale.ROOT) + " " + it.groupValues[2].uppercase(Locale.ROOT) }
                ?: word.uppercase(Locale.ROOT).takeIf { it == "VOSTFR" }
            else -> null
        }

        private class W(val start: Int, val end: Int, val text: String)

        /** Woorden met hun plaats; scheidingstekens en spaties ertussen. */
        private fun words(s: String): List<W> {
            val out = ArrayList<W>()
            var i = 0
            val n = s.length
            fun sepAt(j: Int): Boolean {
                val c = s[j]
                if (isSepChar(c)) return true
                if (c == '-' || c == '/') {
                    val before = j == 0 || isSpace(s[j - 1]) || isSepChar(s[j - 1])
                    val after = j == n - 1 || isSpace(s[j + 1]) || isSepChar(s[j + 1])
                    return before || after
                }
                return false
            }
            while (i < n) {
                if (isSpace(s[i]) || sepAt(i)) { i++; continue }
                val st = i
                while (i < n && !isSpace(s[i]) && !sepAt(i)) i++
                out += W(st, i, s.substring(st, i))
            }
            return out
        }

        private fun gapHasSep(s: String, from: Int, to: Int): Boolean {
            for (j in from until to) { val c = s[j]; if (!isSpace(c)) return true }
            return false
        }

        private fun trimSeps(s: String): String {
            var i = 0; var j = s.length
            while (i < j && (isSpace(s[i]) || isSepChar(s[i]) || s[i] == '-' || s[i] == '/')) i++
            while (j > i && (isSpace(s[j - 1]) || isSepChar(s[j - 1]) || s[j - 1] == '-' || s[j - 1] == '/')) j--
            return s.substring(i, j).ifEmpty { s.trim() }
        }

        data class Clean(val name: String, val tags: List<String>)

        /** Alleen weghalen (geen hoofdletters veranderen): voor zoeken en vergelijken. */
        fun strip(raw: String): Clean {
            val s = raw.trim()
            if (s.isEmpty()) return Clean("", emptyList())
            val ws = words(s)
            if (ws.isEmpty()) return Clean(s, emptyList())
            var a = 0; var b = ws.size - 1
            val front = ArrayList<String>(); val back = ArrayList<String>()
            // vooraan
            while (a < b) {
                val w = ws[a]
                val sep = gapHasSep(s, if (a == 0) 0 else ws[a - 1].end, w.start) || gapHasSep(s, w.end, ws[a + 1].start)
                val k = kindOf(w.text, sep, true) ?: break
                tagOf(w.text, k)?.let { front += it }
                a++
            }
            // achteraan
            while (b > a) {
                val w = ws[b]
                val sep = gapHasSep(s, ws[b - 1].end, w.start) || gapHasSep(s, w.end, s.length)
                val k = kindOf(w.text, sep, false) ?: break
                tagOf(w.text, k)?.let { back.add(0, it) }
                b--
            }
            // alleen labels over ("FILMS | NL", "| NL |"): een taal/meertalig als enige rest → de naam zoals hij was;
            // kwaliteit of soort als rest is de echte naam ("NL | 4K" → "4K")
            if (a == b && (a > 0 || b < ws.size - 1)) {
                val rest = kindOf(ws[a].text, true, false)
                if (rest == Kind.CODE || rest == Kind.NAME || rest == Kind.MULTI || rest == Kind.SUB) return Clean(trimSeps(s), emptyList())
            }
            var st = ws[a].start; var en = ws[b].end
            // haakjes heel houden: "Disney+ (Kids)" niet "Disney+ (Kids"
            val mid = s.substring(st, en)
            for ((o, c) in listOf('(' to ')', '[' to ']', '{' to '}')) {
                val open = mid.count { it == o }; val close = mid.count { it == c }
                if (open > close) { var j = en; while (j < s.length && isSpace(s[j])) j++; if (j < s.length && s[j] == c) en = j + 1 }
                if (close > open) { var j = st - 1; while (j >= 0 && isSpace(s[j])) j--; if (j >= 0 && s[j] == o) st = j }
            }
            val tags = LinkedHashSet<String>().apply { addAll(front); addAll(back) }.toList()
            return Clean(s.substring(st, en).trim(), tags)
        }

        /** Merken en afkortingen die zo blijven ("HBO Max", "VTM GO", "BBC"). */
        private val KEEP = ("HBO BBC NPO RTL SBS VTM ESPN TV F1 GO CNN MTV NBA NFL NHL MLB UFC WWE MMA DAZN XXX VRT VPRO KRO NOS BNN TLC AMC " +
            "FX HGTV CBS ABC NBC ITV USA UK DC UEFA FIFA KPN TF1 M6 ZDF ARD SVT NRK TVE RAI TRT ATV OSN MBC BVN AVRO EO NTR WNL " +
            "VTV VIER VIJF ZES NPO1 NPO2 NPO3 SBS6 SBS9 RTL4 RTL5 RTL7 RTL8 RTLZ ID TNT AXN DTV AT5 OUTTV MAX+ PSG PSV AFC AZ ATP WTA " +
            "PGA NASCAR DTM WRC MOTOGP F2 F3 GP BTS KPOP K-POP DJ MTV2 BET CW HD UHD 4K VOD").split(' ').toHashSet()
        /** Vaste schrijfwijze van diensten (sleutel = [Merge.key]). */
        private val BRANDS = mapOf(
            "skyshowtime" to "SkyShowtime", "appletvplus" to "Apple TV+", "appletv" to "Apple TV", "youtube" to "YouTube",
            "hbomax" to "HBO Max", "disneyplus" to "Disney+", "primevideo" to "Prime Video", "amazonprimevideo" to "Amazon Prime Video",
            "paramountplus" to "Paramount+", "npostart" to "NPO Start", "npoplus" to "NPO Plus", "vtmgo" to "VTM GO",
            "streamz" to "Streamz", "videoland" to "Videoland", "viaplay" to "Viaplay", "netflix" to "Netflix", "kijk" to "KIJK",
            "discoveryplus" to "discovery+", "ziggo" to "Ziggo", "espn" to "ESPN", "dazn" to "DAZN",
        )

        private fun cap(word: String): String {
            if (word.isEmpty()) return word
            if (word in KEEP || word.length <= 2 || word.any { it.isDigit() }) return word
            // "SCI-FI" → "Sci-Fi", "L'AMOUR" → "L'amour"
            val sb = StringBuilder(word.length)
            var startOfPart = true
            for (c in word) {
                sb.append(if (startOfPart) c.titlecaseChar() else c.lowercaseChar())
                startOfPart = c == '-' || c == '/'
            }
            return sb.toString()
        }

        /** Hoofdletters netjes: alleen als de hele naam in hoofdletters staat; één kort woord (≤ 3) laten staan ("XXX"). */
        fun tidyCase(name: String): String {
            if (name.none { it.isLetter() }) return name
            if (name.any { it.isLowerCase() }) return name
            BRANDS[Merge.key(name)]?.let { return it }
            val parts = name.split(' ')
            if (parts.size == 1 && name.length <= 3) return name
            return parts.joinToString(" ") { cap(it) }
        }

        /** Naam zoals de app hem toont, plus de labels die eraf gehaald zijn. Leeg of alleen labels → de oorspronkelijke naam. */
        fun clean(raw: String): Clean {
            val st = strip(raw)
            val n = st.name.ifEmpty { raw.trim() }
            return Clean(tidyCase(n), st.tags)
        }

        private val memo = java.util.concurrent.ConcurrentHashMap<String, Clean>()
        /** [clean] met cache (een paar honderd categorieën, vaak gevraagd). */
        fun cached(raw: String): Clean = memo[raw] ?: clean(raw).also { if (memo.size > 20_000) memo.clear(); memo[raw] = it }
        fun display(raw: String): String = cached(raw).name
    }

    // MARK: samenvoegen

    object Merge {
        /** Vergelijksleutel (= Build149Logic.Discover.key): kleine letters, alleen letters en cijfers, `+` → "plus". */
        fun key(name: String): String {
            val sb = StringBuilder(name.length)
            for (ch in name.lowercase(Locale.ROOT)) {
                when {
                    ch == '+' -> sb.append("plus")
                    ch == '&' -> sb.append("and")
                    ch in 'a'..'z' || ch in '0'..'9' -> sb.append(ch)
                    ch.isLetterOrDigit() -> sb.append(ch)
                }
            }
            return sb.toString()
        }

        /** Sleutel van een categorie voor het samenvoegen ("| NL | NETFLIX" en "MULTI | Netflix" → "netflix"). */
        fun groupKey(raw: String): String = key(CategoryName.cached(raw).name).ifEmpty { raw.lowercase(Locale.ROOT) }

        /** Eén samengevoegde groep: de getoonde naam, de bronnen (originele namen, in volgorde) en hun labels. */
        data class Group(val key: String, val name: String, val sources: List<String>, val tags: List<String>)

        /** Groepen in de volgorde van de eerste bron. */
        fun groups(names: Iterable<String>): List<Group> {
            val by = LinkedHashMap<String, MutableList<String>>()
            for (n in names) by.getOrPut(if (n.isBlank()) "" else groupKey(n)) { ArrayList() }.let { if (n !in it) it += n }
            return by.map { (k, src) ->
                val tags = LinkedHashSet<String>()
                for (s in src) tags.addAll(CategoryName.cached(s).tags)
                // de naam van de bron met de meeste kleine letters (die is al netjes geschreven), anders de eerste
                val best = src.map { CategoryName.cached(it).name }.maxByOrNull { nm -> nm.count { it.isLowerCase() } } ?: src.first()
                Group(k, best, src, tags.toList())
            }
        }

        /** Eén samengevoegde categorie met de (ontdubbelde) inhoud. */
        class Merged<T>(val name: String, val items: List<T>, val sources: List<String>, val tags: List<String>)

        /**
         * Lijsten per categorie (originele naam → inhoud, in bronvolgorde) samenvoegen op de opgeschoonde naam. Bij meer
         * dan één bron: bronnen in de voorkeurstaal eerst ([preferSources]), dan ontdubbelen ([dedupe]). Eén bron: de
         * lijst zoals hij is (geen extra werk).
         */
        fun <T> merge(lists: List<Pair<String, List<T>>>, preferred: List<String>, id: (T) -> String?, title: (T) -> String, year: (T) -> Int?): List<Merged<T>> {
            val byRaw = HashMap<String, List<T>>(lists.size * 2)
            for ((k, v) in lists) byRaw[k] = byRaw[k]?.let { it + v } ?: v
            return groups(lists.map { it.first }).map { g ->
                if (g.sources.size == 1) Merged(g.name, byRaw[g.sources[0]].orEmpty(), g.sources, g.tags)
                else {
                    val ordered = preferSources(g.sources, preferred)
                    val all = ArrayList<T>()
                    for (src in ordered) all.addAll(byRaw[src].orEmpty())
                    Merged(g.name, dedupe(all, id, title, year), ordered, g.tags)
                }
            }
        }

        /** Labels op de tegel/kop: "NL · Multi" (hooguit [max]). */
        fun chips(tags: List<String>, max: Int = 3): String = tags.take(max).joinToString(" · ")

        /**
         * Ontdubbelen: dezelfde titel (zelfde id uit [id], bv. IMDb of TMDB) of anders genormaliseerde titel + jaar maar
         * één keer; de eerste wint (bron in de app-taal vooraan zetten doet de aanroeper).
         */
        fun <T> dedupe(items: List<T>, id: (T) -> String?, title: (T) -> String, year: (T) -> Int?): List<T> {
            if (items.size < 2) return items
            val seenId = HashSet<String>(); val seenTitle = HashSet<String>()
            val out = ArrayList<T>(items.size)
            for (x in items) {
                val i = id(x)?.takeIf { it.isNotBlank() }
                val t = Build149Logic.Search.key(title(x)).replace(" ", "") + "|" + (year(x)?.toString() ?: "")
                if (i != null) { if (!seenId.add(i)) continue; if (!seenTitle.add(t) && t.length > 1) continue }
                else if (!seenTitle.add(t) && t.length > 1) continue
                out += x
            }
            return out
        }

        /** Bronvolgorde bij het afspelen: eerst de bron met een label in [preferred] (app-taal of audiotaal), dan de rest. */
        fun preferSources(sources: List<String>, preferred: List<String>): List<String> {
            if (preferred.isEmpty()) return sources
            val p = preferred.map { it.uppercase(Locale.ROOT) }
            fun rank(s: String): Int {
                val tags = CategoryName.cached(s).tags.map { it.uppercase(Locale.ROOT) }
                val i = p.indexOfFirst { it in tags || (it == "NL" && "BE" in tags) }
                return if (i < 0) p.size + (if ("MULTI" in tags) 0 else 1) else i
            }
            return sources.withIndex().sortedWith(compareBy<IndexedValue<String>> { rank(it.value) }.thenBy { it.index }).map { it.value }
        }
    }

    // MARK: logo's

    object Logos {
        /** Aliassen: sleutel van de (opgeschoonde) categorienaam → mogelijke sleutels van TMDB provider_name, in volgorde. */
        val ALIASES: Map<String, List<String>> = mapOf(
            "netflix" to listOf("netflix", "netflixstandardwithads", "netflixbasicwithads"),
            "netflixkids" to listOf("netflixkids", "netflix"),
            "videoland" to listOf("videoland"),
            "disney" to listOf("disneyplus"), "disneyplus" to listOf("disneyplus"),
            "hbo" to listOf("hbomax", "max"), "hbomax" to listOf("hbomax", "max"), "max" to listOf("max", "hbomax"),
            "prime" to listOf("amazonprimevideo", "primevideo", "amazonvideo"), "primevideo" to listOf("amazonprimevideo", "primevideo", "amazonvideo"),
            "amazon" to listOf("amazonprimevideo", "primevideo", "amazonvideo"), "amazonprime" to listOf("amazonprimevideo", "primevideo"),
            "amazonprimevideo" to listOf("amazonprimevideo", "primevideo"),
            "skyshowtime" to listOf("skyshowtime"), "sky" to listOf("skyshowtime"), "showtime" to listOf("skyshowtime"),
            "viaplay" to listOf("viaplay"),
            "apple" to listOf("appletvplus", "appletv"), "appletv" to listOf("appletvplus", "appletv"), "appletvplus" to listOf("appletvplus", "appletv"),
            "paramount" to listOf("paramountplus"), "paramountplus" to listOf("paramountplus"),
            "npo" to listOf("npostart", "npoplus"), "npostart" to listOf("npostart", "npoplus"), "npoplus" to listOf("npoplus", "npostart"),
            "vtmgo" to listOf("vtmgo"), "streamz" to listOf("streamz"), "pickx" to listOf("pickx"), "kijk" to listOf("kijk"),
            "discovery" to listOf("discoveryplus"), "discoveryplus" to listOf("discoveryplus"), "mubi" to listOf("mubi"),
            "pathe" to listOf("pathethuis"), "pathethuis" to listOf("pathethuis"), "canal" to listOf("canalplus"), "canalplus" to listOf("canalplus"),
        )
        /** Woorden die na een dienstnaam mogen staan ("Netflix Films", "Disney+ Series 4K", "Videoland Kids"). */
        private val TAIL = Regex("(films?|movies?|series|serie|shows?|tv|kids|nl|be|en|4k|uhd|fhd|hd|originals?|vod|new|nieuw|collectie|collection|exclusive|exclusief|docu|documentaires?)+$")

        /** Sleutel van [providers] (key(provider_name) → logo-URL) bij een categorie, of null. Opschonen gebeurt hier ook. */
        fun providerFor(category: String, providers: Map<String, String>): String? {
            if (providers.isEmpty()) return null
            val k = Merge.key(CategoryName.cached(category).name)
            if (k.isEmpty()) return null
            val cands = LinkedHashSet<String>()
            cands += k
            var t = k
            repeat(3) { t = TAIL.replace(t, ""); if (t.isNotEmpty()) cands += t }
            for (c in cands) {
                if (c in providers) return c
                ALIASES[c]?.let { list ->
                    for (a in list) if (a in providers) return a
                    for (a in list) providers.keys.firstOrNull { it.startsWith(a) }?.let { return it }
                }
            }
            return null
        }
    }
}
