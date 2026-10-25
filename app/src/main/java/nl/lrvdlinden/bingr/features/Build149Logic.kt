package nl.lrvdlinden.bingr

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Build 149: pure logica (zonder Android, getest in tools/test/Build149LogicTest.kt en, voor Samsung TV en LG, in
 * tizen/test/build149_test.js met dezelfde gevallen).
 *
 * - [Dates]: nergens meer per item een datumopmaker. Eigen snelle ISO-8601- en XMLTV-parser en -opmaker (geen
 *   SimpleDateFormat/ICU), en voor de overige patronen één opmaker per thread ([Dates.fmt]).
 * - [Fold]: snelle weg voor gewone ASCII-tekst bij het vouwen van titels en zoeksleutels (geen Normalizer en regex).
 * - [Stream]: een Xtream-lijst per item inlezen met alleen de nodige velden (geen JSON-boom van de hele lijst), items
 *   uit verborgen categorieën alleen tellen ([Skip]), met een geheugengrens ([TooLarge]).
 * - [Discover]: de rij "Ontdek categorieën" op Home (welke categorieën, de naam, de kleur, het logo van een dienst).
 * - [Search]: zoeken op woorden (volgorde maakt niet uit), genormaliseerd, met ranking en één typefout bij lange woorden.
 */
object Build149Logic {

    // MARK: datums

    object Dates {
        private val DAYS_BEFORE = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)

        /** Dagen sinds 1970-01-01 (proleptisch Gregoriaans), zonder Calendar. */
        fun epochDay(y: Int, m: Int, d: Int): Long {
            val yy = if (m <= 2) y - 1 else y
            val era = (if (yy >= 0) yy else yy - 399) / 400
            val yoe = yy - era * 400
            val mp = (m + 9) % 12
            val doy = (153 * mp + 2) / 5 + d - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146097L + doe - 719468L
        }

        private fun num(s: CharSequence, from: Int, len: Int): Int {
            if (from + len > s.length) return -1
            var v = 0
            for (i in from until from + len) { val c = s[i]; if (c < '0' || c > '9') return -1; v = v * 10 + (c - '0') }
            return v
        }

        /**
         * ISO-8601 → epoch-ms: `yyyy-MM-dd`, `yyyy-MM-ddTHH:mm[:ss[.fractie]]` met `Z`, `±HH:mm`, `±HHmm` of zonder zone
         * (= UTC); een spatie mag in plaats van `T`. 0–9 decimalen. null = geen geldige datum.
         */
        fun parseIso(input: String?): Long? {
            val s = input?.trim() ?: return null
            if (s.length < 10 || s[4] != '-' || s[7] != '-') return null
            val y = num(s, 0, 4); val mo = num(s, 5, 2); val d = num(s, 8, 2)
            if (y < 0 || mo !in 1..12 || d !in 1..31) return null
            var ms = epochDay(y, mo, d) * 86_400_000L
            if (s.length == 10) return ms
            if (s[10] != 'T' && s[10] != 't' && s[10] != ' ') return null
            val h = num(s, 11, 2); val mi = num(s, 14, 2)
            if (h !in 0..23 || mi !in 0..59 || s.length < 16 || s[13] != ':') return null
            ms += h * 3_600_000L + mi * 60_000L
            var i = 16
            if (i < s.length && s[i] == ':') {
                val sec = num(s, 17, 2); if (sec !in 0..60) return null
                ms += sec * 1000L; i = 19
                if (i < s.length && (s[i] == '.' || s[i] == ',')) {
                    i++
                    var frac = 0; var digits = 0
                    while (i < s.length && s[i] in '0'..'9') { if (digits < 3) { frac = frac * 10 + (s[i] - '0'); digits++ }; i++ }
                    while (digits in 1..2) { frac *= 10; digits++ }
                    ms += frac
                }
            }
            if (i >= s.length) return ms
            val z = s[i]
            if ((z == 'Z' || z == 'z') && i == s.length - 1) return ms
            if (z != '+' && z != '-') return null
            val oh = num(s, i + 1, 2); if (oh < 0) return null
            val om = when {
                s.length == i + 3 -> 0
                s.length == i + 6 && s[i + 3] == ':' -> num(s, i + 4, 2)
                s.length == i + 5 -> num(s, i + 3, 2)
                else -> -1
            }
            if (om < 0 || oh > 18) return null
            val off = (oh * 60 + om) * 60_000L
            return if (z == '+') ms - off else ms + off
        }

        private fun two(sb: StringBuilder, v: Int) { if (v < 10) sb.append('0'); sb.append(v) }

        /** epoch-ms → `yyyy-MM-ddTHH:mm:ss.SSSZ` (UTC), zoals de sync en de diagnose hem schrijven. */
        fun formatIso(ms: Long): String {
            val day = Math.floorDiv(ms, 86_400_000L)
            var rest = Math.floorMod(ms, 86_400_000L)
            // dag → datum (omgekeerde van epochDay)
            val z = day + 719468L
            val era = Math.floorDiv(z, 146097L)
            val doe = z - era * 146097L
            val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
            var y = yoe + era * 400
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
            val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
            if (m <= 2) y++
            val h = (rest / 3_600_000L).toInt(); rest %= 3_600_000L
            val mi = (rest / 60_000L).toInt(); rest %= 60_000L
            val sec = (rest / 1000L).toInt(); val milli = (rest % 1000L).toInt()
            val sb = StringBuilder(24)
            val yi = y.toInt()
            if (yi in 0..9999) { if (yi < 1000) sb.append('0'); if (yi < 100) sb.append('0'); if (yi < 10) sb.append('0') }
            sb.append(yi).append('-'); two(sb, m); sb.append('-'); two(sb, d); sb.append('T')
            two(sb, h); sb.append(':'); two(sb, mi); sb.append(':'); two(sb, sec); sb.append('.')
            if (milli < 100) sb.append('0'); if (milli < 10) sb.append('0'); sb.append(milli).append('Z')
            return sb.toString()
        }

        /** Zelfde, zonder milliseconden (`yyyy-MM-ddTHH:mm:ssZ`, Jellyfin/Emby). */
        fun formatIsoSeconds(ms: Long): String = formatIso(ms).let { it.substring(0, 19) + "Z" }

        /** XMLTV-tijd `yyyyMMddHHmmss[ ±HHmm]` → epoch-ms (zonder zone = UTC). Geen uitzonderingen in de lus. */
        fun parseXmltv(input: String?): Long? {
            val s = input?.trim() ?: return null
            if (s.length < 12) return null
            val y = num(s, 0, 4); val mo = num(s, 4, 2); val d = num(s, 6, 2); val h = num(s, 8, 2); val mi = num(s, 10, 2)
            if (y < 0 || mo !in 1..12 || d !in 1..31 || h !in 0..23 || mi !in 0..59) return null
            val sec = if (s.length >= 14) num(s, 12, 2).takeIf { it in 0..60 } ?: return null else 0
            var ms = epochDay(y, mo, d) * 86_400_000L + h * 3_600_000L + mi * 60_000L + sec * 1000L
            var i = 14
            while (i < s.length && s[i] == ' ') i++
            if (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val oh = num(s, i + 1, 2); val om = if (s.length > i + 4 && s[i + 3] == ':') num(s, i + 4, 2) else num(s, i + 3, 2)
                if (oh >= 0 && om >= 0) { val off = (oh * 60 + om) * 60_000L; ms = if (s[i] == '+') ms - off else ms + off }
            }
            return ms
        }

        /** `yyyy-MM-dd` in de tijdzone [tz] (standaard: van het apparaat) → epoch-ms van middernacht. */
        fun parseDayLocal(input: String?, tz: TimeZone = TimeZone.getDefault()): Long? {
            val s = input?.trim()?.take(10)?.takeIf { it.length == 10 } ?: return null
            val utc = parseIso(s) ?: return null
            return utc - tz.getOffset(utc - tz.rawOffset)
        }

        /** Eén opmaker per thread, patroon, taal en zone (SimpleDateFormat is niet thread-veilig, en aanmaken is duur). */
        private val cache = object : ThreadLocal<HashMap<String, SimpleDateFormat>>() {
            override fun initialValue() = HashMap<String, SimpleDateFormat>()
        }
        fun fmt(pattern: String, locale: Locale = Locale.US, tz: TimeZone? = null): SimpleDateFormat {
            val key = pattern + "\u0000" + locale.toLanguageTag() + "\u0000" + (tz?.id ?: "")
            val m = cache.get()!!
            return m.getOrPut(key) { SimpleDateFormat(pattern, locale).also { f -> if (tz != null) f.timeZone = tz } }.also {
                // standaardzone kan intussen veranderd zijn (reizen); alleen zetten als hij niet vast is opgegeven
                if (tz == null && it.timeZone.id != TimeZone.getDefault().id) it.timeZone = TimeZone.getDefault()
            }
        }
        /** Hoeveel opmakers deze thread heeft (voor de test: niet per item een nieuwe). */
        fun cachedCount(): Int = cache.get()!!.size
    }

    // MARK: vouwen (titels en zoeksleutels)

    object Fold {
        /** Alleen ASCII? Dan zijn accenten weghalen en NFD niet nodig. */
        fun isAscii(s: CharSequence): Boolean { for (i in 0 until s.length) if (s[i].code > 0x7F) return false; return true }

        /** Kleine letters (ASCII) zonder kopie als de tekst al zo is. */
        fun lowerAscii(s: String): String {
            var i = 0
            while (i < s.length) { val c = s[i]; if (c in 'A'..'Z') break; i++ }
            if (i == s.length) return s
            val a = s.toCharArray()
            while (i < a.size) { val c = a[i]; if (c in 'A'..'Z') a[i] = c + 32; i++ }
            return String(a)
        }

        /** Alleen letters en cijfers, kleine letters (ASCII-weg van Net.normTitle). */
        fun normAscii(s: String): String {
            val sb = StringBuilder(s.length)
            for (c in s) {
                when (c) {
                    in 'a'..'z', in '0'..'9' -> sb.append(c)
                    in 'A'..'Z' -> sb.append(c + 32)
                }
            }
            return sb.toString()
        }
    }

    // MARK: streamend inlezen

    /** Het geheugen raakt op: netjes stoppen in plaats van wegvallen. */
    class TooLarge : RuntimeException("too_large")

    /** Eén item met alleen de gevraagde velden (als tekst; getallen en booleans ook). */
    class Row(private val keys: Array<String>) {
        internal val values = arrayOfNulls<String>(keys.size)
        operator fun get(key: String): String? { for (i in keys.indices) if (keys[i] == key) return values[i]; return null }
        fun int(key: String): Int? = get(key)?.trim()?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }
        fun double(key: String): Double? = get(key)?.trim()?.toDoubleOrNull()
        internal fun clear() { java.util.Arrays.fill(values, null) }
    }

    /**
     * Items die niet ingelezen worden omdat hun categorie verborgen is: per categorienaam een telling. [hidden] krijgt
     * de categorienaam (zoals de app hem toont) en wordt per categorie één keer gevraagd.
     */
    class Skip(private val hidden: (String) -> Boolean) {
        private val memo = HashMap<String, Boolean>()
        val counts = LinkedHashMap<String, Int>()
        fun skip(group: String): Boolean {
            val h = memo.getOrPut(group) { hidden(group) }
            if (h) counts[group] = (counts[group] ?: 0) + 1
            return h
        }
        val total: Int get() = counts.values.sum()
    }

    object Stream {
        /**
         * Leest een Xtream-lijst (JSON-array met objecten, of een object met objecten als waarden) item voor item. Per item
         * alleen [keys] (eenvoudige waarden; geneste waarden worden overgeslagen); [block] krijgt steeds dezelfde [Row]
         * (niet bewaren). [check] wordt elke 2.000 items aangeroepen (geheugengrens: gooi [TooLarge]). Geeft het aantal
         * items. Ongeldige JSON → IllegalArgumentException.
         */
        fun forEach(text: CharSequence, keys: List<String>, check: (Int) -> Unit = {}, block: (Row) -> Unit): Int {
            val p = P(text)
            val row = Row(keys.toTypedArray())
            val idx = HashMap<String, Int>(); keys.forEachIndexed { i, k -> idx[k] = i }
            p.ws()
            if (p.eof()) return 0
            val c = p.peek()
            var n = 0
            fun item() {
                p.ws()
                if (p.peek() != '{') { p.skipValue(); return }
                row.clear()
                p.expect('{'); p.ws()
                if (p.peek() == '}') { p.i++ } else while (true) {
                    p.ws(); val k = p.string(); p.ws(); p.expect(':'); p.ws()
                    val at = idx[k]
                    if (at != null) row.values[at] = p.scalarOrSkip() else p.skipValue()
                    p.ws()
                    val e = p.next()
                    if (e == '}') break
                    if (e != ',') throw IllegalArgumentException("json")
                }
                n++
                block(row)
                if (n % 2000 == 0) check(n)
            }
            when (c) {
                '[' -> {
                    p.i++; p.ws()
                    if (p.peek() == ']') return 0
                    while (true) { item(); p.ws(); val e = p.next(); if (e == ']') break; if (e != ',') throw IllegalArgumentException("json") }
                }
                '{' -> {
                    p.i++; p.ws()
                    if (p.peek() == '}') return 0
                    while (true) {
                        p.ws(); p.string(); p.ws(); p.expect(':'); item(); p.ws()
                        val e = p.next(); if (e == '}') break; if (e != ',') throw IllegalArgumentException("json")
                    }
                }
                else -> { p.skipValue(); return 0 }
            }
            return n
        }

        private class P(val s: CharSequence) {
            var i = 0
            fun eof() = i >= s.length
            fun peek(): Char = if (i < s.length) s[i] else '\u0000'
            fun next(): Char = if (i < s.length) s[i++] else throw IllegalArgumentException("json")
            fun expect(c: Char) { if (next() != c) throw IllegalArgumentException("json") }
            fun ws() { while (i < s.length) { val c = s[i]; if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++ else break } }
            fun string(): String {
                expect('"')
                val start = i
                // snelle weg: geen escapes
                while (i < s.length) {
                    val c = s[i]
                    if (c == '"') { val r = s.subSequence(start, i).toString(); i++; return r }
                    if (c == '\\') break
                    i++
                }
                val sb = StringBuilder().append(s, start, i)
                while (true) {
                    val c = next()
                    if (c == '"') return sb.toString()
                    if (c != '\\') { sb.append(c); continue }
                    when (val e = next()) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> { if (i + 4 > s.length) throw IllegalArgumentException("json"); sb.append(s.subSequence(i, i + 4).toString().toInt(16).toChar()); i += 4 }
                        else -> sb.append(e)
                    }
                }
            }
            /** Eenvoudige waarde als tekst (bool → "1"/"0", null → null); object of lijst overslaan (null). */
            fun scalarOrSkip(): String? = when (peek()) {
                '"' -> string()
                '{', '[' -> { skipValue(); null }
                't' -> { lit("true"); "1" }
                'f' -> { lit("false"); "0" }
                'n' -> { lit("null"); null }
                else -> number()
            }
            fun lit(w: String) { for (c in w) expect(c) }
            fun number(): String {
                val start = i
                while (i < s.length) { val c = s[i]; if (c in '0'..'9' || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') i++ else break }
                if (i == start) throw IllegalArgumentException("json")
                return s.subSequence(start, i).toString()
            }
            fun skipValue() {
                when (peek()) {
                    '"' -> { expect('"'); while (true) { val c = next(); if (c == '\\') next() else if (c == '"') break } }
                    '{', '[' -> {
                        var depth = 0
                        while (true) {
                            val c = next()
                            when (c) {
                                '"' -> { while (true) { val d = next(); if (d == '\\') next() else if (d == '"') break } }
                                '{', '[' -> depth++
                                '}', ']' -> { depth--; if (depth == 0) return }
                            }
                        }
                    }
                    't' -> lit("true"); 'f' -> lit("false"); 'n' -> lit("null")
                    else -> number()
                }
            }
        }

        /** Geheugengrens: gebruikt > [fraction] van het maximum (na opruimen) → [TooLarge]. */
        fun memoryCheck(fraction: Double = 0.85, used: () -> Long, max: () -> Long, gc: () -> Unit = {}): (Int) -> Unit = {
            if (used() > max() * fraction) {
                gc()
                if (used() > max() * fraction) throw TooLarge()
            }
        }
    }

    // MARK: Ontdek categorieën

    object Discover {
        const val MAX = 10
        const val MIN_ITEMS = 4

        /** Vaste, rustige palet (balk onder de collage), gekozen op basis van de naam. */
        val PALETTE = listOf(0xFF7A2E3A, 0xFF2E4A7A, 0xFF2F6B4F, 0xFF6B4A2F, 0xFF4F2E6B, 0xFF2E6B6B, 0xFF7A5A2E, 0xFF3A3A5C,
            0xFF6B2F55, 0xFF355E3B).map { it.toInt() }

        /** Stabiele hash (FNV-1a 32 over de gevouwen naam), zelfde als build149.js. */
        fun hash(s: String): Int {
            var h = 0x811C9DC5.toInt()
            for (c in s) { h = h xor c.code; h *= 0x01000193 }
            return h
        }
        fun colorFor(name: String): Int = PALETTE[Math.floorMod(hash(key(name)), PALETTE.size)]

        /** Vergelijksleutel: kleine letters, alleen letters en cijfers ("HBO Max" → "hbomax", "Disney+" → "disneyplus"). */
        fun key(name: String): String {
            val sb = StringBuilder()
            for (ch in name.lowercase(Locale.ROOT)) {
                when {
                    ch == '+' -> sb.append("plus")
                    ch in 'a'..'z' || ch in '0'..'9' -> sb.append(ch)
                    ch.isLetterOrDigit() -> sb.append(ch)
                }
            }
            return sb.toString()
        }

        /** Aliassen (categorienaam → TMDB provider_name als sleutel). */
        val ALIASES = mapOf(
            "netflix" to "netflix", "disney" to "disneyplus", "disneyplus" to "disneyplus",
            "hbo" to "hbomax", "hbomax" to "hbomax", "max" to "hbomax",
            "prime" to "amazonprimevideo", "primevideo" to "amazonprimevideo", "amazon" to "amazonprimevideo",
            "amazonprime" to "amazonprimevideo", "amazonprimevideo" to "amazonprimevideo",
            "skyshowtime" to "skyshowtime", "viaplay" to "viaplay", "videoland" to "videoland",
            "appletv" to "appletvplus", "appletvplus" to "appletvplus", "apple" to "appletvplus",
            "paramount" to "paramountplus", "paramountplus" to "paramountplus", "npoplus" to "npoplus", "npo" to "npoplus",
        )
        /** Woorden die na een dienstnaam mogen staan ("Netflix Films", "Disney+ Series 4K"). */
        private val TAIL = Regex("(films?|movies?|series|shows?|tv|kids|nl|en|4k|uhd|fhd|hd|originals?|vod|new|nieuw)+$")

        /**
         * Dienst bij een categorie: sleutel van [providers] (key(provider_name) → logo-URL), of null. Eerst de hele naam,
         * dan zonder staartwoorden, dan de aliassen.
         */
        fun providerFor(category: String, providers: Map<String, String>): String? {
            val k = key(category)
            if (k.isEmpty()) return null
            val cands = LinkedHashSet<String>()
            cands += k
            var t = k
            repeat(3) { t = TAIL.replace(t, "") ; if (t.isNotEmpty()) cands += t }
            for (c in cands) {
                if (c in providers) return c
                ALIASES[c]?.let { a -> if (a in providers) return a; providers.keys.firstOrNull { it.startsWith(a) }?.let { return it } }
            }
            return null
        }

        data class Cat(val group: String, val title: String, val count: Int, val posters: List<String>, val isMovie: Boolean, val color: Int)

        /**
         * De rij: zichtbare categorieën (films en series) met minstens [MIN_ITEMS] titels, de grootste eerst, hooguit
         * [MAX], films en series om en om. [clean] = bestaande opschoning ("NL | Netflix" → "Netflix"). Bij een
         * kinderprofiel alleen als alle categorieën kindercategorieën zijn ([isKids]).
         */
        fun <M, S> rows(
            movies: List<M>, series: List<S>, movieGroup: (M) -> String, seriesGroup: (S) -> String,
            moviePoster: (M) -> String?, seriesPoster: (S) -> String?, clean: (String) -> String,
            kidsProfile: Boolean, isKids: (String) -> Boolean, max: Int = MAX,
        ): List<Cat> {
            class Acc(val group: String, val movie: Boolean) { var n = 0; val posters = ArrayList<String>(4) }
            val acc = LinkedHashMap<String, Acc>()
            for (m in movies) {
                val g = movieGroup(m); val a = acc.getOrPut("m|$g") { Acc(g, true) }; a.n++
                if (a.posters.size < 4) moviePoster(m)?.takeIf { it.isNotEmpty() && it !in a.posters }?.let { a.posters += it }
            }
            for (s in series) {
                val g = seriesGroup(s); val a = acc.getOrPut("s|$g") { Acc(g, false) }; a.n++
                if (a.posters.size < 4) seriesPoster(s)?.takeIf { it.isNotEmpty() && it !in a.posters }?.let { a.posters += it }
            }
            if (kidsProfile && acc.values.any { !isKids(it.group) }) return emptyList()
            val ok = acc.values.filter { it.n >= MIN_ITEMS && it.posters.size >= 3 }
            val m = ok.filter { it.movie }.sortedByDescending { it.n }
            val s = ok.filter { !it.movie }.sortedByDescending { it.n }
            val out = ArrayList<Cat>()
            val seen = HashSet<String>()
            var i = 0
            while (out.size < max && (i < m.size || i < s.size)) {
                for (a in listOfNotNull(m.getOrNull(i), s.getOrNull(i))) {
                    if (out.size >= max) break
                    val t = clean(a.group).ifEmpty { a.group }
                    // dezelfde naam bij films en series (bv. "Netflix") één keer tonen: de grootste wint
                    if (!seen.add((if (a.movie) "m|" else "s|") + key(t))) continue
                    out += Cat(a.group, t, a.n, a.posters.take(4), a.movie, colorFor(t))
                }
                i++
            }
            return out
        }

        /** Gemiddelde kleur (ARGB) van pixels, gedempt zodat witte tekst leesbaar blijft. Leeg → null. */
        fun mutedAverage(pixels: IntArray): Int? {
            var r = 0L; var g = 0L; var b = 0L; var n = 0
            for (p in pixels) {
                if ((p ushr 24) < 128) continue
                val pr = (p shr 16) and 255; val pg = (p shr 8) and 255; val pb = p and 255
                // bijna wit of bijna zwart (achtergrond van het logo) telt niet mee
                val mx = maxOf(pr, pg, pb); val mn = minOf(pr, pg, pb)
                if (mx > 235 && mn > 235) continue
                if (mx < 20) continue
                r += pr; g += pg; b += pb; n++
            }
            if (n == 0) return null
            var ar = (r / n).toInt(); var ag = (g / n).toInt(); var ab = (b / n).toInt()
            // donkerder dan 140 helderheid (contrast met witte tekst)
            val lum = (ar * 299 + ag * 587 + ab * 114) / 1000
            if (lum > 110) { val f = 110.0 / lum; ar = (ar * f).toInt(); ag = (ag * f).toInt(); ab = (ab * f).toInt() }
            return (0xFF shl 24) or (ar shl 16) or (ag shl 8) or ab
        }
    }

    // MARK: zoeken

    object Search {
        private val marks = Regex("\\p{M}+")
        /** Voorvoegsels als "NL | ", "4K - ", "[NL] ", "|NL| " (ook herhaald). */
        private val prefix = Regex("^\\s*(?:\\|[^|]{1,12}\\||\\[[^\\]]{1,12}]|\\([^)]{1,6}\\)|[A-Z0-9+]{2,4}\\s*[-:|•]\\s+)")

        /**
         * Zoeksleutel: voorvoegsels weg, accenten en diakrieten weg, kleine letters, `&` → "and", leestekens → spatie,
         * één spatie tussen de woorden. "NL | Amélie & Co." → "amelie and co".
         */
        fun key(raw: String): String {
            var s = raw
            repeat(3) { val n = prefix.replace(s, ""); if (n == s) return@repeat; s = n }
            if (!Fold.isAscii(s)) s = marks.replace(java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD), "")
            val sb = StringBuilder(s.length)
            var space = true
            for (ch0 in s) {
                val ch = ch0.lowercaseChar()
                when {
                    ch == '&' -> { if (!space) sb.append(' '); sb.append("and "); space = true }
                    ch == '\'' || ch == '’' -> {}  // "Grey's" → "greys"
                    ch.isLetterOrDigit() -> { sb.append(when (ch) { 'ß' -> "ss"; 'æ' -> "ae"; 'ø' -> "o"; 'ł' -> "l"; 'đ' -> "d"; else -> ch.toString() }); space = false }
                    else -> { if (!space) { sb.append(' '); space = true } }
                }
            }
            return sb.toString().trim()
        }

        fun words(q: String): List<String> = key(q).split(' ').filter { it.isNotEmpty() }

        /** Hooguit één bewerking (vervangen, invoegen, weglaten, twee letters omgedraaid) verschil? */
        fun oneEdit(a: String, b: String): Boolean {
            if (a == b) return true
            val la = a.length; val lb = b.length
            if (kotlin.math.abs(la - lb) > 1) return false
            var i = 0
            while (i < la && i < lb && a[i] == b[i]) i++
            if (la == lb) {
                if (a.substring(i + 1) == b.substring(i + 1)) return true
                return i + 1 < la && a[i] == b[i + 1] && a[i + 1] == b[i] && a.substring(i + 2) == b.substring(i + 2)
            }
            return if (la > lb) a.substring(i + 1) == b.substring(i) else a.substring(i) == b.substring(i + 1)
        }

        const val EXACT = 4000; const val PREFIX = 3000; const val WORDS = 2000; const val CONTAINS = 1000; const val TYPO = 500

        /**
         * Score van een titel (als [key]) voor de zoekwoorden [q] (uit [words]); 0 = geen treffer. Exact > begint met >
         * elk woord als begin van een woord > elk woord ergens in de tekst > met één typefout (woorden ≥ 5 letters).
         * Kortere titels iets hoger.
         */
        fun score(key: String, q: List<String>, phrase: String = q.joinToString(" ")): Int {
            if (q.isEmpty() || key.isEmpty()) return 0
            val tie = (100 - key.length).coerceIn(0, 99)
            if (key == phrase) return EXACT + tie
            if (key.startsWith(phrase)) return PREFIX + tie
            var allContain = true
            for (w in q) if (!key.contains(w)) { allContain = false; break }
            if (allContain) {
                var allBegin = true
                for (w in q) if (!(key.startsWith(w) || key.contains(" $w"))) { allBegin = false; break }
                return (if (allBegin) WORDS else CONTAINS) + tie
            }
            // typefouten: elk woord moet er zijn, lange woorden mogen één bewerking afwijken van een woord(begin)
            val kw = key.split(' ')
            for (w in q) {
                if (key.contains(w)) continue
                if (w.length < 5) return 0
                val ok = kw.any { k -> oneEdit(w, k) || (k.length > w.length && oneEdit(w, k.substring(0, w.length))) || (k.length >= w.length - 1 && k.length < w.length && oneEdit(w, k)) }
                if (!ok) return 0
            }
            return TYPO + tie
        }

        /**
         * Vooraf berekende zoeksleutels per lijst (op identiteit van de lijst: een nieuwe catalogus is een nieuw object).
         * Hooguit [MAX_LISTS] lijsten bewaard.
         */
        private const val MAX_LISTS = 16
        private val cache = ArrayList<Pair<List<*>, Array<String>>>()
        fun <T> keys(list: List<T>, text: (T) -> String): Array<String> {
            synchronized(cache) { for ((l, k) in cache) if (l === list) return k }
            val k = Array(list.size) { key(text(list[it])) }
            synchronized(cache) {
                cache.removeAll { it.first === list }
                cache.add(0, list to k)
                while (cache.size > MAX_LISTS) cache.removeAt(cache.size - 1)
            }
            return k
        }

        /** Treffers uit [list] (beste eerst), hooguit [limit]. */
        fun <T> find(list: List<T>, q: String, limit: Int, text: (T) -> String): List<T> {
            val w = words(q)
            if (w.isEmpty()) return emptyList()
            val phrase = w.joinToString(" ")
            val ks = keys(list, text)
            val hits = ArrayList<Pair<Int, Int>>()
            for (i in ks.indices) { val sc = score(ks[i], w, phrase); if (sc > 0) hits += i to sc }
            hits.sortWith(compareByDescending<Pair<Int, Int>> { it.second }.thenBy { it.first })
            return hits.asSequence().take(limit).map { list[it.first] }.toList()
        }
    }
}
