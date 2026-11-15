package nl.lrvdlinden.bingr

import java.text.Normalizer

/**
 * Build 172 (versie 1.0.7): categorieën slim samenvoegen ("Categorieën opschonen"), pure logica zonder Android, met
 * unittests (tools/test/Build172LogicTest.kt). Zelfde regels als Apple (`Shared/Providers/Build172.swift`) en de
 * webspeler (`web/js/catmerge.js`); vaste testgevallen in docs/merge_cases.json van iptv-ios-appletv (kopie:
 * tools/test/merge_cases.json, niet hier aanpassen).
 *
 * Opslag: precies het bestaande formaat van [Customization]: `merged[bron] = doelgroep`, de doelnaam als
 * `renamed["group:<doel>"]` en de volgorde in `groupOrder`. Oudere apps zien het samenvoegen dus ook.
 */
object Build172Logic {

    data class Group(val name: String, val count: Int)

    data class Suggestion(
        val id: String,
        /** "brand", "country" of "prefix" */
        val kind: String,
        /** Doelnaam ("Netflix"); aan te passen */
        val target: String,
        /** De groep waarin de andere worden samengevoegd */
        val targetGroup: String,
        /** Groepen in volgorde (de doelgroep hoort erbij) */
        val groups: List<String>,
        val counts: Map<String, Int>,
        val total: Int,
        /** Standaard aangevinkt */
        val on: Boolean,
        /** Groepen met een eigen naam van de gebruiker (waarschuwing) */
        val renamed: List<String>,
    )

    /** Het bestaande formaat (alleen wat samenvoegen raakt) */
    data class Custom(
        val merged: Map<String, String> = emptyMap(),
        val renamed: Map<String, String> = emptyMap(),
        val groupOrder: List<String> = emptyList(),
    )

    class Brand(val id: String, val name: String, val on: Boolean, val aliases: List<String>)

    /** Merken en diensten (makkelijk uit te breiden). `!` = streng: alleen de hele naam of de naam + soortwoorden. */
    val BRANDS = listOf(
        Brand("netflix", "Netflix", true, listOf("netflix")),
        Brand("disneyplus", "Disney+", true, listOf("disney plus")),
        Brand("hulu", "Hulu", true, listOf("hulu")),
        Brand("primevideo", "Prime Video", true, listOf("prime video", "amazon prime video", "amazon prime", "amazon video", "amazon")),
        Brand("hbomax", "HBO Max", true, listOf("hbo max", "hbo", "!max")),
        Brand("appletvplus", "Apple TV+", true, listOf("apple tv plus")),
        Brand("paramountplus", "Paramount+", true, listOf("paramount plus")),
        Brand("peacock", "Peacock", true, listOf("peacock")),
        Brand("videoland", "Videoland", true, listOf("videoland")),
        Brand("viaplay", "Viaplay", true, listOf("viaplay")),
        Brand("skyshowtime", "SkyShowtime", true, listOf("skyshowtime")),
        Brand("nlziet", "NLZIET", true, listOf("nlziet")),
        Brand("npo", "NPO", true, listOf("npo start", "npo plus", "npo")),
        Brand("discoveryplus", "Discovery+", true, listOf("discovery plus")),
        Brand("canalplus", "Canal+", true, listOf("canal plus")),
        // Sky: wel als merk, maar standaard uit (Sky Sports, Sky Cinema en Sky News blijven vaak bewust apart)
        Brand("sky", "Sky", false, listOf("sky")),
        Brand("bbciplayer", "BBC iPlayer", true, listOf("bbc iplayer")),
        Brand("itvx", "ITVX", true, listOf("itvx")),
        Brand("crunchyroll", "Crunchyroll", true, listOf("crunchyroll")),
        Brand("starz", "Starz", true, listOf("starz")),
        Brand("showtime", "Showtime", true, listOf("showtime")),
        Brand("amcplus", "AMC+", true, listOf("amc plus")),
        Brand("hallmark", "Hallmark", true, listOf("hallmark")),
        Brand("pathethuis", "Pathé Thuis", true, listOf("pathe thuis")),
        Brand("streamz", "Streamz", true, listOf("streamz")),
        Brand("vtmgo", "VTM GO", true, listOf("vtm go")),
        Brand("rtlplus", "RTL+", true, listOf("rtl plus")),
        Brand("joyn", "Joyn", true, listOf("joyn")),
    )

    private fun set(s: String): Set<String> = s.split(' ').filter { it.isNotEmpty() }.toHashSet()

    private const val PIPES = "¦│┃｜‖∣丨┆┇┊┋╎╏︱︳⏐⎮▏▕ǀ׀⼁∥❘❙❚￨"
    private val CODES = set("nl be uk us en de fr es it tr pl pt ar gb nld dut eng deu ger fra fre ita spa esp tur pol por ara int eu ca au " +
        "ie se no dk fi ch at in br mx ru gr ro al ma rs hr ba bg hu cz sk si ua il ir pk ph th vn jp kr cn hk za eg sa ae " +
        "lu is nz lat latam exyu")
    private val SPACED = set("nl be uk us en de fr es tr pl pt ar gb nld eng deu ger fra ita spa tur pol por ara")
    private val CANON = mapOf("eng" to "en", "gb" to "uk", "nld" to "nl", "dut" to "nl", "deu" to "de", "ger" to "de", "fra" to "fr",
        "fre" to "fr", "ita" to "it", "spa" to "es", "esp" to "es", "tur" to "tr", "pol" to "pl", "por" to "pt", "ara" to "ar")
    private val QUALITY = set("4k 8k uhd fhd hd sd hevc h265 h264 x265 x264 60fps 50fps hdr hdr10 fullhd 1080p 720p 2160p")
    private val TAGS = set("multi vip vod sub subs dub dubbed subbed")
    val TYPE = set("films film movies movie series serie documentaires documentaire documentaries documentary docs doc kids " +
        "kinderen anime comedy drama action actie horror thriller romance sci fi scifi collection collectie originals specials " +
        "nl en dubbed subbed gesproken ondertiteld")
    private val SKIP = set("vod films film movies movie series serie tv shows top new nieuw all alle")
    private val GENERIC = set("nl uk us en be de movies movie films film series serie kids new nieuw top best 4k vod live tv the het een " +
        "a all alle hd")
    private const val LEAD = " \t|:-–—*•·#~_=»>/.,"
    private val OPEN = mapOf('[' to ']', '(' to ')', '{' to '}')
    private val CLOSE = mapOf(']' to '[', ')' to '(', '}' to '{')
    private const val SEPS = "|:-–—"
    private val MARKS = Regex("[\\u0300-\\u036f]")

    private fun isAZ(c: Char) = c in 'a'..'z'
    private fun isAN(c: Char) = c in 'a'..'z' || c in '0'..'9'

    private fun words(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (c in s) {
            if (isAN(c)) cur.append(c) else if (cur.isNotEmpty()) { out += cur.toString(); cur.setLength(0) }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    private fun canon(w: String) = CANON[w] ?: w

    /** Inhoud tussen haken een label? null = nee; "" = ja zonder land; anders het land. */
    private fun tagLike(inner: String): String? {
        val w = words(inner)
        if (w.isEmpty()) return null
        if (w.any { it !in CODES && it !in TAGS && it !in QUALITY }) return null
        return w.firstOrNull { it in CODES }?.let { canon(it) } ?: ""
    }

    private fun trimLead(s: String): String { var i = 0; while (i < s.length && LEAD.indexOf(s[i]) >= 0) i++; return s.substring(i) }
    private fun trimTail(s: String): String { var i = s.length; while (i > 0 && LEAD.indexOf(s[i - 1]) >= 0) i--; return s.substring(0, i) }
    private fun isYear(t: String) = t.length == 4 && t.all { it in '0'..'9' } && (t.startsWith("19") || t.startsWith("20"))
    private fun isDigits(t: String) = t.isNotEmpty() && t.all { it in '0'..'9' }

    data class Norm(val tokens: List<String>, val country: String)

    /** Naam → woorden en land. Kleine letters, zonder accenten, pipes als "|", "+" als "plus", land-/taalvoorvoegsels en
     *  -achtervoegsels (met scheidingsteken of haken) en kwaliteitslabels weg. */
    fun norm(raw: String): Norm {
        val t = StringBuilder()
        for (c in raw) t.append(if (PIPES.indexOf(c) >= 0) '|' else c)
        var s = MARKS.replace(Normalizer.normalize(t.toString(), Normalizer.Form.NFD), "").lowercase().replace("+", " plus ")
        var country = ""
        fun note(c: String) { if (country.isEmpty() && c.isNotEmpty()) country = c }
        for (n in 0 until 6) {
            s = trimLead(s)
            if (s.isEmpty()) break
            val closer = OPEN[s[0]]
            if (closer != null) {
                val close = s.indexOf(closer, 1)
                if (close > 0) {
                    val tag = tagLike(s.substring(1, close))
                    if (tag != null && trimLead(s.substring(close + 1)).isNotEmpty()) { note(tag); s = s.substring(close + 1); continue }
                }
                break
            }
            var k = 0
            while (k < s.length && isAN(s[k])) k++
            val w = s.substring(0, k)
            var r = k
            while (r < s.length && s[r] == ' ') r++
            if (k >= 2 && r < s.length && (SEPS.indexOf(s[r]) >= 0 || s[r] == ']' || s[r] == ')') && trimLead(s.substring(r + 1)).isNotEmpty()) {
                if (k <= 3 && w in CODES && isAZ(w[0])) { note(canon(w)); s = s.substring(r + 1); continue }
                if (w in TAGS || w in QUALITY) { s = s.substring(r + 1); continue }
            }
            break
        }
        for (m in 0 until 4) {
            s = trimTail(s)
            if (s.isEmpty()) break
            val opener = CLOSE[s[s.length - 1]]
            if (opener != null) {
                val op = if (s.length >= 2) s.lastIndexOf(opener, s.length - 2) else -1
                if (op >= 0) {
                    val tg = tagLike(s.substring(op + 1, s.length - 1))
                    if (tg != null && trimTail(s.substring(0, op)).isNotEmpty()) { note(tg); s = s.substring(0, op); continue }
                }
                break
            }
            var e = s.length
            while (e > 0 && isAN(s[e - 1])) e--
            val tw = s.substring(e)
            var q = e
            while (q > 0 && s[q - 1] == ' ') q--
            if (tw.length >= 2 && q > 0 && SEPS.indexOf(s[q - 1]) >= 0 && trimTail(s.substring(0, q - 1)).isNotEmpty()) {
                if (tw.length <= 3 && tw in CODES && isAZ(tw[0])) { note(canon(tw)); s = s.substring(0, q - 1); continue }
                if (tw in TAGS || tw in QUALITY) { s = s.substring(0, q - 1); continue }
            }
            break
        }
        val tokens = words(s).filter { it !in QUALITY }
        if (tokens.size > 1 && tokens.last() in SPACED) note(canon(tokens.last()))
        return Norm(tokens, country)
    }

    private class Alias(val brand: Int, val compact: String, val strict: Boolean)
    private val ALIASES: List<Alias> = BRANDS.flatMapIndexed { i, b ->
        b.aliases.map { a -> val strict = a.startsWith("!"); Alias(i, words(if (strict) a.substring(1) else a).joinToString(""), strict) }
    }

    private fun matchFrom(tokens: List<String>, start: Int): Int? {
        val compact = StringBuilder()
        val endIdx = HashMap<Int, Int>()
        for (i in start until tokens.size) { compact.append(tokens[i]); endIdx[compact.length] = i + 1 }
        val cs = compact.toString()
        var best = -1; var bestLen = -1
        for (al in ALIASES) {
            val l = al.compact.length
            val end = endIdx[l] ?: continue
            if (!cs.startsWith(al.compact)) continue
            if (al.strict && (end until tokens.size).any { tokens[it] !in TYPE && tokens[it] !in SPACED }) continue
            if (l > bestLen) { best = al.brand; bestLen = l }
        }
        return if (best >= 0) best else null
    }

    private fun brandOf(t: List<String>): Pair<Int?, String> {
        if (t.isEmpty()) return null to ""
        matchFrom(t, 0)?.let { return it to "" }
        var s = 0; var c = ""
        while (s < t.size - 1 && (t[s] in SKIP || t[s] in SPACED)) {
            if (c.isEmpty() && t[s] in SPACED) c = canon(t[s])
            s++
        }
        if (s > 0) matchFrom(t, s)?.let { return it to c }
        return null to ""
    }

    /** Merk-id van een categorienaam (null = geen merk) */
    fun brandOf(raw: String): String? = brandOf(norm(raw).tokens).first?.let { BRANDS[it].id }

    private fun stemOK(st: List<String>): Boolean {
        val first = st.firstOrNull() ?: return false
        if (first in GENERIC || first in CODES) return false
        return st.none { it in TYPE || isYear(it) || isDigits(it) }
    }

    private fun titleCase(st: List<String>) = st.joinToString(" ") { w ->
        if (w.length <= 3 && w.none { it in "aeiouy" }) w.uppercase() else w.substring(0, 1).uppercase() + w.substring(1)
    }

    private class Item(val name: String, val idx: Int, val tokens: List<String>, val ncountry: String, val count: Int) { var country = "" }
    private class Bucket(val st: List<String>, val first: Int) { val items = ArrayList<Pair<Item, Boolean>>() }

    /** Voorstellen voor één server en één soort (Live TV, Films of Series). Op de achtergrond aanroepen. */
    fun suggest(
        groups: List<Group>, merged: Map<String, String> = emptyMap(), renamed: Map<String, String> = emptyMap(),
        hidden: Set<String> = emptySet(), ignored: Set<String> = emptySet(),
    ): List<Suggestion> {
        val countOf = HashMap<String, Int>()
        for (g in groups) countOf[g.name] = (countOf[g.name] ?: 0) + g.count
        val isTarget = HashSet<String>(); val extra = HashMap<String, Int>()
        for ((src, tgt) in merged) {
            if (tgt == src) continue
            isTarget += tgt
            extra[tgt] = (extra[tgt] ?: 0) + (countOf[src] ?: 0)
        }
        val list = ArrayList<Item>(); val seen = HashSet<String>()
        for (g in groups) {
            val n = g.name
            if (n in seen || n in hidden) continue
            val m = merged[n]
            if (m != null && m != n) continue
            seen += n
            val nm = norm(n)
            if (nm.tokens.isEmpty()) continue
            list += Item(n, list.size, nm.tokens, nm.country, (countOf[n] ?: 0) + (extra[n] ?: 0))
        }
        data class Out(val s: Suggestion, val o0: Int, val o1: Int, val seq: Int)
        val out = ArrayList<Out>()
        val claimed = HashSet<String>()
        fun make(kind: String, id: String, target: String, gs: List<Item>, on: Boolean): Suggestion {
            var tg = gs[0].name
            if (kind != "country") gs.firstOrNull { it.name in isTarget }?.let { tg = it.name }
            val counts = LinkedHashMap<String, Int>(); var total = 0; val ren = ArrayList<String>()
            for (g in gs) {
                counts[g.name] = g.count; total += g.count
                if (renamed["group:" + g.name] != null) ren += g.name
            }
            return Suggestion(id, kind, target, tg, gs.map { it.name }, counts, total, on, ren)
        }
        // 1. merken
        val byBrand = LinkedHashMap<Int, ArrayList<Item>>()
        for (g in list) {
            val (bi, bc) = brandOf(g.tokens)
            if (bi == null) continue
            g.country = g.ncountry.ifEmpty { bc }
            byBrand.getOrPut(bi) { ArrayList() } += g
        }
        for ((bi, gs) in byBrand) {
            val br = BRANDS[bi]
            if (gs.size < 2) continue
            gs.forEach { claimed += it.name }
            val countries = ArrayList<String>(); val per = HashMap<String, Int>()
            for (g in gs) if (g.country.isNotEmpty()) {
                if (per[g.country] == null) { per[g.country] = 0; countries += g.country }
                per[g.country] = per[g.country]!! + 1
            }
            val sugg = ArrayList<Triple<Suggestion, Int, Int>>()
            if (countries.size <= 1) {
                sugg += Triple(make("brand", "brand:" + br.id, br.name, gs, br.on), gs[0].idx, 0)
            } else {
                var dom = countries[0]
                for (c in countries) if (per[c]!! > per[dom]!!) dom = c
                val main = gs.filter { it.country.isEmpty() || it.country == dom }
                val rest = gs.filter { it.country.isNotEmpty() && it.country != dom }
                if (main.size >= 2) {
                    val ms = make("brand", "brand:" + br.id, br.name, main, br.on)
                    sugg += Triple(ms, main[0].idx, 0)
                    val head = main.filter { it.name == ms.targetGroup }
                    sugg += Triple(make("country", "country:" + br.id, br.name, head + rest, false), main[0].idx, 1)
                } else {
                    sugg += Triple(make("country", "country:" + br.id, br.name, main + rest, false), gs[0].idx, 1)
                }
            }
            for (x in sugg) if (x.first.id !in ignored) out += Out(x.first, x.second, x.third, out.size)
        }
        // 2. zelfde begin
        val buckets = HashMap<String, Bucket>(); val bucketList = ArrayList<Bucket>()
        for (g in list) {
            if (g.name in claimed) continue
            val t = g.tokens
            val cands = ArrayList<Pair<List<String>, Boolean>>()
            for (k in 2 downTo 1) if (t.size > k && t[k] in TYPE && stemOK(t.subList(0, k))) cands += t.subList(0, k).toList() to true
            if (t.size <= 2 && stemOK(t)) cands += t to false
            for ((st, cont) in cands) {
                val key = st.joinToString(" ")
                val b = buckets.getOrPut(key) { Bucket(st, g.idx).also { bucketList += it } }
                if (b.items.none { it.first === g }) b.items += g to cont
            }
        }
        val sorted = bucketList.sortedWith(compareBy<Bucket>({ -it.st.size }, { it.first }))
        val used = HashSet<String>()
        for (b in sorted) {
            val items = b.items.filter { it.first.name !in used }.sortedBy { it.first.idx }
            if (items.size < 2 || items.none { it.second }) continue
            items.forEach { used += it.first.name }
            val id = "prefix:" + b.st.joinToString("-")
            if (id in ignored) continue
            val gs = items.map { it.first }
            out += Out(make("prefix", id, titleCase(b.st), gs, false), gs[0].idx, 2, out.size)
        }
        return out.sortedWith(compareBy<Out>({ it.o0 }, { it.o1 }, { it.seq })).map { it.s }
    }

    /** Samenvoegen in het bestaande formaat. `groups`: de aangevinkte groepen (in volgorde); doel = `targetGroup` als die
     *  aangevinkt is, anders de eerste. `cleanName`: hoe het doel zonder eigen naam heet (opgeschoond). */
    fun apply(custom: Custom, groups: List<String>, targetGroup: String, name: String, cleanName: String): Custom {
        if (groups.size < 2) return custom
        val merged = LinkedHashMap(custom.merged); val renamed = LinkedHashMap(custom.renamed)
        val order = ArrayList(custom.groupOrder)
        val target = if (targetGroup in groups) targetGroup else groups[0]
        for (g in groups) {
            if (g == target) continue
            merged[g] = target
            for (x in merged.keys.toList()) if (merged[x] == g) merged[x] = target
        }
        merged.remove(target)
        val nm = name.trim(); val key = "group:$target"
        if (nm.isNotEmpty() && nm != cleanName) renamed[key] = nm else if (nm.isNotEmpty() && nm == cleanName) renamed.remove(key)
        if (order.isNotEmpty()) {
            var pos = -1
            for (g in groups) { val i = order.indexOf(g); if (i >= 0 && (pos < 0 || i < pos)) pos = i }
            if (pos >= 0) {
                val ti = order.indexOf(target)
                if (ti != pos) {
                    if (ti >= 0) order.removeAt(ti)
                    order.add(if (ti in 0 until pos) pos - 1 else pos, target)
                }
            }
        }
        return Custom(merged, renamed, order)
    }

    /** Losmaken: `sources` null = alle bronnen van `target`. Is er daarna geen bron meer en is de eigen naam van het doel
     *  een merknaam of `madeName` (door het opschonen gezet), dan gaat die eigen naam ook weg. */
    fun detach(custom: Custom, target: String, sources: List<String>?, madeName: String?): Custom {
        val merged = LinkedHashMap(custom.merged); val renamed = LinkedHashMap(custom.renamed)
        for ((s, t) in custom.merged) if (t == target && (sources == null || s in sources)) merged.remove(s)
        val left = merged.values.contains(target)
        val key = "group:$target"
        val r = renamed[key]
        if (!left && r != null && (r == madeName || BRANDS.any { it.name == r })) renamed.remove(key)
        return Custom(merged, renamed, custom.groupOrder)
    }

    data class Target(val target: String, val sources: List<String>)

    /** Doelgroepen met hun bronnen (voor "Samengevoegd"), in de volgorde van `order` (de groepen van de server). */
    fun mergedTargets(merged: Map<String, String>, order: List<String>): List<Target> {
        val by = LinkedHashMap<String, ArrayList<String>>()
        for ((s, t) in merged) if (t != s) by.getOrPut(t) { ArrayList() } += s
        val pos = HashMap<String, Int>()
        order.forEachIndexed { i, g -> if (pos[g] == null) pos[g] = i }
        val cmp = compareBy<String>({ pos[it] ?: 1_000_000_000 }, { it })
        return by.keys.sortedWith(cmp).map { Target(it, by[it]!!.sortedWith(cmp)) }
    }

    /** "12 categorieën worden 3": aantal unieke groepen en aantal unieke doelgroepen. */
    fun summary(picks: List<Suggestion>): Pair<Int, Int> {
        val all = HashSet<String>(); val tg = HashSet<String>()
        for (p in picks) { all += p.groups; tg += p.targetGroup }
        return all.size to tg.size
    }

    /** Sleutel van een overgeslagen voorstel: per soort ("live", "movie", "series"), dan het id */
    fun ignoreKey(section: String, id: String) = "$section|$id"
}
