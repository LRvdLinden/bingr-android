package nl.lrvdlinden.bingr

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Taal van een film of serie (build 59, idee uit UHF): IPTV-aanbieders zetten de taal in de titel of de
 * categorie ("Animal (IT)", "NL | Frozen", "[NL] Reacher", "EN-Dune", "VOD | DE", "NL Films", "… NL SUBS",
 * "… MULTI"). Dezelfde regels als `ContentLanguage` op iOS en Samsung TV:
 *
 * 1. de titel: taalcode tussen haken/strepen vooraan, met een scheidingsteken vooraan, of tussen haken achteraan;
 *    gewone woorden in een titel tellen niet ("It", "In Bruges", "Animal Kingdom");
 * 2. anders de categorie/groep (code als eerste of laatste woord, tussen haken, of een taalnaam);
 * 3. anders de originele taal uit TMDB (`original_language`), zodra de details een keer geladen zijn.
 *
 * Daarnaast: "NL SUBS" (ondertiteld in die taal) en "MULTI"/"DUAL" (meerdere audiotalen).
 * Uitkomsten worden per titel+groep gecachet (thread-veilig, ook vanaf Dispatchers.Default te gebruiken).
 */
object ContentLanguage {
    /** [code] = ISO 639-1 van de audio (null = onbekend), [subs] = taal van ingebakken/meegeleverde ondertitels. */
    data class Info(
        val code: String? = null,
        val subs: String? = null,
        val multi: Boolean = false,
        /** true = alleen uit TMDB (original_language), niet uit de titel of categorie */
        val fromMetadata: Boolean = false,
    ) {
        val isEmpty: Boolean get() = code == null && subs == null && !multi

        /** Tekst voor de strook op de poster: "NL", "IT", "EN · NL ondertitels", "Multi". */
        val label: String?
            get() {
                val parts = ArrayList<String>(2)
                when {
                    multi -> parts += L("Multi")
                    code != null -> parts += short(code)
                }
                subs?.let { parts += L("%@ subtitles", short(it)) }
                return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
            }

        /** Voor de detailpagina: "Italiaans", "Meerdere talen", "Engels · Ondertitels: Nederlands". */
        val longName: String?
            get() {
                val parts = ArrayList<String>(2)
                when {
                    multi -> parts += L("Multiple languages")
                    code != null -> parts += TrackPreferences.name(code)
                }
                subs?.let { parts += L("Subtitles: %@", TrackPreferences.name(it)) }
                return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
            }
    }

    /** Korte code op de poster: "en" → "EN", "hi" → "HI". */
    fun short(code: String): String = code.uppercase()

    // MARK: herkenning

    /** Codes zoals aanbieders ze schrijven → ISO 639-1. Ook landcodes (UK/US → en, BR → pt, IN → hi). */
    private val codes: Map<String, String> = buildMap {
        fun add(iso: String, vararg keys: String) { for (k in keys) put(k, iso) }
        add("nl", "NL", "NLD", "DUT", "VL", "VLA", "NLBE")
        add("en", "EN", "ENG", "UK", "US", "USA", "GB")
        add("de", "DE", "GER", "DEU", "AT", "CH")
        add("fr", "FR", "FRA", "FRE", "VF", "VFF", "VFQ")
        add("es", "ES", "SPA", "ESP", "LAT", "MX", "CAST")
        add("it", "IT", "ITA")
        add("pt", "PT", "POR", "BR", "PTBR")
        add("tr", "TR", "TUR")
        add("ar", "AR", "ARA")
        add("pl", "PL", "POL")
        add("hi", "IN", "HI", "HIN", "IND")
        add("ru", "RU", "RUS")
        add("sv", "SE", "SV", "SWE")
        add("da", "DK", "DA", "DAN")
        add("no", "NO", "NOR")
        add("fi", "FI", "FIN")
        add("el", "GR", "EL", "GRE")
        add("ja", "JP", "JA", "JPN")
        add("ko", "KR", "KO", "KOR")
        add("zh", "CN", "ZH", "CHI")
        add("sq", "AL", "ALB", "SQ")
        add("ro", "RO", "ROM")
        add("hu", "HU", "HUN")
        add("cs", "CZ", "CS", "CZE")
        add("he", "IL", "HE", "HEB")
        add("fa", "IR", "FA", "PER")
        add("ur", "PK", "UR", "URD")
        add("th", "TH", "THA")
        add("vi", "VN", "VI", "VIE")
        add("ku", "KU", "KUR")
        add("uk", "UA", "UKR")
        add("bg", "BG", "BUL")
        add("hr", "HR", "HRV")
        add("sr", "RS", "SRB")
        add("ta", "TAM")
        add("te", "TEL")
        add("ml", "MAL")
        add("pa", "PUN")
    }

    /** Taalnamen (hoofdletterongevoelig) — tellen ook als los woord in een categorie. */
    private val names: Map<String, String> = buildMap {
        fun add(iso: String, vararg keys: String) { for (k in keys) put(k, iso) }
        add("nl", "dutch", "nederlands", "nederlandse", "vlaams", "flemish")
        add("en", "english", "engels")
        add("de", "german", "deutsch", "duits")
        add("fr", "french", "francais", "français", "frans")
        add("es", "spanish", "espanol", "español", "spaans", "latino", "castellano")
        add("it", "italian", "italiano", "italiaans")
        add("pt", "portuguese", "portugues", "português", "brazil", "brasil")
        add("tr", "turkish", "turkce", "türkçe", "turks")
        add("ar", "arabic", "arabisch")
        add("pl", "polish", "polski", "pools")
        add("hi", "hindi", "bollywood")
        add("ru", "russian", "russisch")
    }

    /** Woorden die gewone Engelse titelwoorden zijn: niet als code met alleen een dubbele punt vooraan ("IT: Welcome to Derry"). */
    private val wordLike = setOf("IT", "IN", "NO", "US", "AT", "HE", "EL", "DA", "HI", "TH", "CAST")

    /** In een categorienaam alleen tussen haken/strepen als taal tellen ("NO SPORTS" is geen Noors). */
    private val groupWordLike = setOf("NO", "AT", "HE", "EL", "DA", "TH", "CH")

    private val multiWords =setOf("MULTI", "MULTIAUDIO", "MULTILANG", "DUAL", "DUALAUDIO", "MULTISUB")

    /** Kwaliteits- en andere labels vooraan/achteraan die geen taal zijn (overslaan, niet weghalen). */
    private val neutral = setOf(
        "4K", "UHD", "FHD", "HD", "SD", "HDR", "HEVC", "VOD", "TOP", "NEW", "3D", "H265", "X265", "DV", "HDR10", "ATMOS",
        "NETFLIX", "DISNEY", "AMZ", "PRIME", "HBO", "APPLE", "KIDS", "4KUHD",
    )

    private val subsAfter = Regex("^([A-Z]{2,3})SUB(?:S|BED|TITLED)?$")
    private val subsBefore = Regex("^SUBS?([A-Z]{2,3})$")
    private val groupBracket = Regex("[|\\[(]\\s*([A-Za-z ]{2,10}?)\\s*[|\\])]")
    private val groupSubs = Regex("\\b([A-Z]{2,3})[ -]?SUBS?\\b")
    private val groupMulti = Regex("\\b(MULTI|DUAL)\\b", RegexOption.IGNORE_CASE)
    private val nonLetters = Regex("[^\\p{L}]+")

    /** Eén label ("NL", "NL SUBS", "MULTI", "VOSTFR", "Dutch") → taalinfo, of null als het geen taal is. */
    private fun token(raw: String, allowWordLike: Boolean = true): Info? {
        val t = raw.trim().trim('-', '|', ':', '.', '–').trim()
        if (t.isEmpty()) return null
        val up = t.uppercase().replace(" ", "").replace("-", "").replace("_", "").replace(".", "")
        if (up in multiWords) return Info(multi = true)
        if (up == "VOSTFR" || up == "VOST") return Info(subs = "fr")
        // "NL SUBS", "NLSUB", "NL-SUBBED", "SUB NL"
        subsAfter.find(up)?.let { m -> codes[m.groupValues[1]]?.let { return Info(subs = it) } }
        subsBefore.find(up)?.let { m -> codes[m.groupValues[1]]?.let { return Info(subs = it) } }
        // "MULTI NL", "NL MULTI"
        if (up.startsWith("MULTI") || up.endsWith("MULTI")) return Info(multi = true)
        if (t.length in 2..4 && t == t.uppercase() && t.all { it.isLetter() }) {
            if (!allowWordLike && up in wordLike) return null
            codes[up]?.let { return Info(code = it) }
        }
        names[t.lowercase()]?.let { return Info(code = it) }
        return null
    }

    private fun merge(a: Info?, b: Info?): Info? {
        if (a == null) return b
        if (b == null) return a
        return Info(code = a.code ?: b.code, subs = a.subs ?: b.subs, multi = a.multi || b.multi)
    }

    /** Resultaat van één titel: gevonden taal en de titel zonder taallabels. */
    private data class Parsed(val info: Info?, val clean: String)

    private val bracketPrefix = Regex("^\\s*(?:\\|\\s*([^|]{2,14}?)\\s*\\||\\[\\s*([^\\]]{2,14}?)\\s*\\]|\\(\\s*([^)]{2,14}?)\\s*\\))\\s*(?:[-|:–]\\s*)?")
    private val plainPrefix = Regex("^\\s*([A-Za-z0-9]{2,7}(?:[ -]SUBS?)?)(\\s*[|:–]\\s*|\\s+-\\s+|-(?=\\S))")
    private val bracketSuffix = Regex("\\s*(?:\\(\\s*([^)]{1,20}?)\\s*\\)|\\[\\s*([^\\]]{1,20}?)\\s*\\])\\s*$")
    private val subsSuffix = Regex("(?:\\s+[-|]\\s*|\\s+)([A-Z]{2,3})[ -]?SUB(?:S|BED)?\\s*$", RegexOption.IGNORE_CASE)
    private val multiSuffix = Regex("(?:\\s+[-|]\\s*|\\s+)(MULTI|DUAL)(?:[ -]?(?:AUDIO|LANG|SUBS?))?\\s*$", RegexOption.IGNORE_CASE)
    private val codeSuffix = Regex("\\s+[-|]\\s+([A-Z]{2,3})\\s*$")
    private val yearOrQuality = Regex("^(?:19\\d{2}|20\\d{2}|4K|UHD|FHD|HD|SD|HDR|HEVC|1080P|720P|2160P|DV|ATMOS)$", RegexOption.IGNORE_CASE)

    private fun parseTitle(raw: String): Parsed {
        // build 153: alle pipe-tekens ("┃", "｜" …) eerst één `|`
        var s = Build150Logic.Pipes.normalize(raw)
        var info: Info? = null
        // vooraan: max. 3 labels ("4K | NL | Titel"); taal weghalen, andere labels laten staan
        var kept = ""
        for (i in 0 until 3) {
            val m = bracketPrefix.find(s)
            if (m != null) {
                val tok = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: break
                val ti = token(tok)
                if (ti != null) { info = merge(info, ti); s = s.substring(m.range.last + 1); continue }
                if (tok.uppercase().replace(" ", "") in neutral) { kept += m.value; s = s.substring(m.range.last + 1); continue }
                break
            }
            val p = plainPrefix.find(s) ?: break
            val tok = p.groupValues[1]
            // "IT: Welcome to Derry" is geen Italiaans; met | of " - " wel ("IT | Gomorra")
            val sep = p.groupValues[2]
            val ti = token(tok, allowWordLike = !sep.contains(':') && sep != "-")
            if (ti != null) { info = merge(info, ti); s = s.substring(p.range.last + 1); continue }
            if (tok.uppercase() in neutral) { kept += p.value; s = s.substring(p.range.last + 1); continue }
            break
        }
        s = kept + s
        // achteraan: groepen tussen haken (jaar/kwaliteit laten staan), "NL SUBS", "MULTI", " - NL"
        var tail = ""
        for (i in 0 until 4) {
            val b = bracketSuffix.find(s)
            if (b != null) {
                val tok = b.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: break
                val ti = token(tok)
                if (ti != null) { info = merge(info, ti); s = s.substring(0, b.range.first); continue }
                if (yearOrQuality.matches(tok.trim())) { tail = b.value + tail; s = s.substring(0, b.range.first); continue }
                break
            }
            val sm = subsSuffix.find(s)
            val sc = sm?.let { codes[it.groupValues[1].uppercase()] }
            if (sm != null && sc != null) { info = merge(info, Info(subs = sc)); s = s.substring(0, sm.range.first); continue }
            val mm = multiSuffix.find(s)
            if (mm != null) { info = merge(info, Info(multi = true)); s = s.substring(0, mm.range.first); continue }
            val cm = codeSuffix.find(s)
            val cc = cm?.let { m -> codes[m.groupValues[1]]?.takeIf { m.groupValues[1] !in wordLike } }
            if (cm != null && cc != null) { info = merge(info, Info(code = cc)); s = s.substring(0, cm.range.first); continue }
            break
        }
        var clean = (s + tail).trim()
        // build 153: wat er aan het begin of eind nog tussen twee pipes staat ("|4K|", "┃ABC┃"), gaat helemaal weg
        val cut = Build150Logic.Pipes.cut(clean)
        if (cut.front.isNotEmpty() || cut.back.isNotEmpty()) {
            for (b in cut.front + cut.back) token(b)?.let { info = merge(info, it) }
            clean = cut.rest
        }
        clean = clean.trim().trim('-', '|', ':', '–').trim()
        return Parsed(info, clean.ifEmpty { Build150Logic.Pipes.normalize(raw).trim() })
    }

    private fun parseGroup(group: String): Info? {
        if (group.isBlank()) return null
        var info: Info? = null
        // tussen haken/strepen: "|NL| Films", "[DE] Serien", "Films (NL)"
        groupBracket.findAll(group).forEach { m -> info = merge(info, token(m.groupValues[1])) }
        // "NL SUBS" / "MULTI" ergens in de naam
        groupSubs.find(group)?.let { m -> codes[m.groupValues[1]]?.let { info = merge(info, Info(subs = it)) } }
        if (groupMulti.containsMatchIn(group)) info = merge(info, Info(multi = true))
        val words = group.split(nonLetters).filter { it.isNotEmpty() }
        if (words.isEmpty()) return info
        // code als eerste of laatste woord ("NL Films", "VOD | DE", "Netflix NL"); taalnamen overal ("Dutch Movies")
        for ((i, w) in words.withIndex()) {
            val edge = i == 0 || i == words.lastIndex
            val ti = names[w.lowercase()]?.let { Info(code = it) }
                ?: if (edge && w.length in 2..3 && w == w.uppercase() && w !in groupWordLike) codes[w]?.let { Info(code = it) } else null
            if (ti != null) { info = merge(info, ti); break }
        }
        return info
    }

    // MARK: cache

    private val titleCache = ConcurrentHashMap<String, Parsed>()
    private val infoCache = ConcurrentHashMap<String, Info>()
    private val none = Info()

    private fun parsed(title: String): Parsed = titleCache[title] ?: parseTitle(title).also {
        if (titleCache.size > 60_000) titleCache.clear()
        titleCache[title] = it
    }

    /** Taal uit titel en categorie (gecachet). Leeg [Info] als er niets herkend is. */
    fun parse(title: String, group: String?): Info {
        val k = title + "\u0000" + (group ?: "")
        infoCache[k]?.let { return it }
        val fromTitle = parsed(title).info
        val fromGroup = group?.let { parseGroup(it) }
        // de titel gaat voor; de categorie vult aan (bijv. ondertitels)
        val r = merge(fromTitle, fromGroup) ?: none
        if (infoCache.size > 60_000) infoCache.clear()
        infoCache[k] = r
        return r
    }

    /** Titel zonder taallabels ("Animal (IT)" → "Animal", "NL | Frozen (2013)" → "Frozen (2013)"). */
    fun displayTitle(raw: String): String = parsed(raw).clean

    /**
     * Build 150: categorienaam zoals de app hem overal toont: taal, land en kwaliteit weg in elke vorm en volgorde, nette
     * hoofdletters ("| NL | NETFLIX" → "Netflix", "| MULTI / LINGO | NETFLIX" → "Netflix"). Eén plek: Build150Logic.
     */
    fun categoryTitle(raw: String): String = Build150Logic.CategoryName.display(raw)

    /** Build 153: zendernaam zoals de app hem toont ("┃NL┃ NPO 1 HD" → "NPO 1"); afspelen en id's gebruiken de ruwe naam. */
    fun channelTitle(raw: String): String = Build151Logic.Channels.display(raw)

    /** Build 153: alleen de pipe-aanduidingen weg (server- en profielnamen: "┃NL┃ Mijn server" → "Mijn server"). */
    fun plainName(raw: String): String = Build150Logic.Pipes.cut(raw).rest

    /** Build 153: getoonde titel van iets dat een zender ([live]) of een film/serie/aflevering kan zijn. */
    fun shownTitle(raw: String, live: Boolean): String = if (live) channelTitle(raw) else displayTitle(raw)

    /** Build 150: labels die van een categorienaam af gehaald zijn ("NL", "Multi"), voor de chips. */
    fun categoryTags(raw: String): List<String> = Build150Logic.CategoryName.cached(raw).tags

    /** Build 150: talen voor de bronkeuze bij samengevoegde categorieën (audiotaal, dan app-taal), als code ("NL"). */
    val preferredSourceLanguages: List<String>
        get() = listOfNotNull(TrackPreferences.preferredAudio, runCatching { Localizer.locale.language }.getOrNull())
            .map { it.uppercase() }.filter { it.isNotEmpty() }.distinct()

    // MARK: TMDB (original_language) als laatste redmiddel

    /** Compose-state: verhoogt als er een TMDB-taal bijkomt (posters tekenen dan opnieuw). */
    var version by mutableIntStateOf(0); private set
    private val original = ConcurrentHashMap<String, String>()
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private val file: File get() = File(AppState.appContext.filesDir, "content-languages.json")
    @Volatile private var loaded = false
    private var saveJob: Job? = null

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        AppScope.launch {
            val l = withContext(Dispatchers.IO) { runCatching { Http.json.decodeFromString(serializer, file.readText()) }.getOrNull() }
            if (!l.isNullOrEmpty()) { l.forEach { (k, v) -> original.putIfAbsent(k, v) }; version++ }
        }
    }

    private fun originalKey(title: String, year: Int?, isShow: Boolean) = Metadata.key(TitleCleaner.parse(title).title, year, isShow)

    /** Originele taal uit TMDB onthouden (na het laden van de details). */
    fun rememberOriginal(title: String, year: Int?, isShow: Boolean, code: String?) {
        val c = code?.lowercase()?.takeIf { it.length == 2 && it != "xx" } ?: return
        ensureLoaded()
        val k = originalKey(title, year, isShow)
        if (original[k] == c) return
        original[k] = c
        AppScope.launch(Dispatchers.Main) { version++ }
        saveJob?.cancel()
        saveJob = AppScope.launch {
            delay(2_000)
            withContext(Dispatchers.IO) { runCatching { file.writeText(Http.json.encodeToString(serializer, HashMap(original))) } }
        }
    }

    private fun withOriginal(i: Info, title: String, year: Int?, isShow: Boolean): Info? {
        if (i.code != null || i.multi) return i
        ensureLoaded()
        version   // lezen = opnieuw tekenen als er een TMDB-taal bijkomt
        val o = original[originalKey(title, year, isShow)]
        return when {
            o != null -> i.copy(code = o, fromMetadata = true)
            i.isEmpty -> null
            else -> i
        }
    }

    fun of(m: Movie): Info? = withOriginal(parse(m.title, m.group), m.title, m.year, false)
    fun of(s: Series): Info? = withOriginal(parse(s.title, s.group), s.title, s.year, true)
    fun of(t: DetailTarget): Info? = when (t) {
        is DetailTarget.MovieT -> of(t.movie)
        is DetailTarget.SeriesT -> of(t.series)
    }

    /** Taal van wat er speelt (films/afleveringen); live-zenders niet. */
    fun of(p: PlayableItem): Info? {
        if (p.type == MediaType.LIVE) return null
        val lib = Playlists.library
        if (p.type == MediaType.EPISODE) {
            val s = p.seriesID?.let { id -> lib.series.firstOrNull { it.id == id } } ?: lib.series.firstOrNull { it.title == p.title }
            return s?.let { of(it) } ?: parse(p.title, null).takeIf { !it.isEmpty }
        }
        val m = lib.movies.firstOrNull { it.id == p.id }
        return m?.let { of(it) } ?: parse(p.title, null).takeIf { !it.isEmpty }
    }

    // MARK: andere taalversies

    /** Sleutel voor "dezelfde titel": schone titel (zonder taal/jaar/kwaliteit) + jaar. */
    fun versionKey(title: String, year: Int?): String {
        val p = TitleCleaner.parse(displayTitle(title))
        return Net.normTitle(p.title) + "|" + ((year ?: p.year)?.toString() ?: "")
    }

    /** Een andere taalversie van dezelfde titel in de huidige bibliotheek. */
    data class Version(val target: DetailTarget, val info: Info?) {
        val id: String get() = target.id
    }

    /**
     * Andere taalversies van [t] (zelfde IMDb-id, of dezelfde schone titel + jaar; zonder jaar aan één kant
     * telt alleen de titel). Alleen versies met een andere (of onbekende) taal dan [t] zelf.
     */
    suspend fun versions(t: DetailTarget): List<Version> {
        val lib = Playlists.library
        val own = of(t)
        return withContext(Dispatchers.Default) {
            val norm = Net.normTitle(TitleCleaner.parse(displayTitle(t.title)).title)
            if (norm.isEmpty()) return@withContext emptyList()
            val year = t.year ?: TitleCleaner.parse(t.title).year
            val imdb = when (t) { is DetailTarget.MovieT -> t.movie.imdbID; is DetailTarget.SeriesT -> t.series.imdbID }
            fun same(title: String, y: Int?, otherImdb: String?): Boolean {
                if (imdb != null && otherImdb != null) return imdb == otherImdb
                val p = TitleCleaner.parse(displayTitle(title))
                if (Net.normTitle(p.title) != norm) return false
                val oy = y ?: p.year
                // jaar ±1 (docs/ACCOUNT.md, build 59): releasejaren verschillen soms per land of bron
                return year == null || oy == null || kotlin.math.abs(oy - year) <= 1
            }
            val out = ArrayList<Version>()
            when (t) {
                is DetailTarget.MovieT -> for (m in lib.movies) {
                    if (m.id != t.id && same(m.title, m.year, m.imdbID)) out += Version(DetailTarget.MovieT(m), of(m))
                }
                is DetailTarget.SeriesT -> for (s in lib.series) {
                    if (s.id != t.id && same(s.title, s.year, s.imdbID)) out += Version(DetailTarget.SeriesT(s), of(s))
                }
            }
            // alleen echt andere talen (of onbekend), elke taal één keer; voorkeurstaal eerst
            val pref = TrackPreferences.preferredAudio
            out.filter { v -> val l = v.info?.label; l == null || l != own?.label }
                .distinctBy { it.info?.label ?: it.id }
                .sortedBy { if (it.info?.code == pref) 0 else 1 }
        }
    }
}
