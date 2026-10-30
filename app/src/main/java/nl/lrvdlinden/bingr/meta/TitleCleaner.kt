package nl.lrvdlinden.bingr

/**
 * Maakt IPTV-titels schoon ("NL - The Batman (2022) [4K]" → "The Batman", 2022)
 * en herkent afleveringen ("Breaking Bad S01 E02").
 */
object TitleCleaner {
    data class Parsed(val title: String, val year: Int? = null, val season: Int? = null, val episode: Int? = null)

    // build 154: een voorvoegsel met `:`/`-`/`|` gaat alleen weg als het een taal- of landcode, kwaliteitswoord of bekend
    // IPTV-woord is (lijsten van build 150): "NL: Bioscoop" → "Bioscoop", "NCIS: Hawaii" en "BBC - Planet Earth" blijven heel
    private val bracketPrefix = Regex("^\\s*\\[[^\\]]{1,12}\\]\\s*")
    private val wordPrefix = Regex("^\\s*([A-Za-z0-9+]{2,8})\\s*[-:|]\\s+")
    private val episode = Regex("[Ss](\\d{1,2})\\s*[Ee](\\d{1,3})")
    // build 154: een jaartal alleen tussen haakjes/blokhaken, of achteraan achter een scheidingsteken ("Dune - 2021");
    // "Blade Runner 2049" en "1917" blijven heel
    private val bracketYear = Regex("[\\(\\[]\\s*((?:19|20)\\d{2})\\s*[\\)\\]]")
    private val sepYear = Regex("\\s[-|–—]\\s*((?:19|20)\\d{2})\\s*$")
    // build 154: "sub"/"subs" los niet meer weg ("Sub Zero"); "NL Sub" wel
    private val junk = Regex(
        "(?i)\\b(4k|uhd|fhd|hd|sd|hevc|h\\.?265|x265|x264|1080p|720p|2160p|multi|nl ?subs?|vost\\w*|dolby|atmos|hdr10?\\+?|dv)\\b|\\[[^\\]]*\\]|\\([^\\)]*\\)"
    )
    private fun maxYear(): Int = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) + 1
    private val spaces = Regex("[ \\t\\u00A0]+")

    fun parse(raw: String): Parsed {
        // build 153: pipe-tekens één `|`, stukken tussen pipes aan begin en eind weg ("┃NL┃ Dune", "Dune |4K|")
        var s = Build150Logic.Pipes.cut(raw).rest
        // landcodes/labels vooraan weg (eventueel meerdere)
        repeat(2) {
            bracketPrefix.find(s)?.let { m -> if (m.range.last + 1 < s.length) s = s.substring(m.range.last + 1) }
            wordPrefix.find(s)?.let { m ->
                val w = m.groupValues[1]
                if (m.range.last + 1 < s.length && Build150Logic.CategoryName.kindOf(w, sep = true, front = true) != null) s = s.substring(m.range.last + 1)
            }
        }

        var season: Int? = null
        var ep: Int? = null
        episode.find(s)?.let { m ->
            season = m.groupValues[1].toIntOrNull(); ep = m.groupValues[2].toIntOrNull()
            s = s.substring(0, m.range.first)
        }

        var y: Int? = null
        val top = maxYear()
        bracketYear.findAll(s).lastOrNull { (it.groupValues[1].toIntOrNull() ?: 0) in 1900..top }?.let { m ->
            y = m.groupValues[1].toIntOrNull()
            s = s.removeRange(m.range)
        }

        s = junk.replace(s, " ")
        if (y == null) sepYear.find(s.trimEnd())?.let { m ->
            val v = m.groupValues[1].toIntOrNull()
            if (v != null && v in 1900..top && m.range.first > 0) { y = v; s = s.trimEnd().substring(0, m.range.first) }
        }
        s = s.replace("_", " ").replace(".", " ")
            .split(spaces).filter { it.isNotEmpty() }.joinToString(" ")
            .trim { it == ' ' || it == '-' || it == '|' || it == ':' }
        return Parsed(title = s.ifEmpty { raw }, year = y, season = season, episode = ep)
    }
}
