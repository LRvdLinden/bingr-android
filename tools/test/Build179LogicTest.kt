package nl.lrvdlinden.bingr

import java.io.File
import nl.lrvdlinden.bingr.Build179Logic as B

// Build 179: pure logica (Build179Logic.kt) tegen de gedeelde gevallen uit docs/build179_cases.json van iptv-ios-appletv
// (kopie: tools/test/build179_cases.json; niet hier aanpassen). Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/features/{Build173Logic,Build176Logic,Build179Logic}.kt \
//     tools/test/Build179LogicTest.kt -include-runtime -d /tmp/b179.jar
//   java -jar /tmp/b179.jar tools/test/build179_cases.json

private var failures = 0
private var checks = 0
private fun eq(name: String, got: Any?, want: Any?) {
    checks++
    if (got != want) { failures++; println("FOUT $name kreeg <$got>, verwacht <$want>"); println("::error title=Build179LogicTest::$name") }
}

private class J(val s: String) {
    var i = 0
    fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
    fun value(): Any? {
        ws()
        return when (s[i]) {
            '{' -> { i++; val m = LinkedHashMap<String, Any?>(); ws(); if (s[i] == '}') { i++; return m }
                while (true) { ws(); val k = str(); ws(); i++; m[k] = value(); ws(); if (s[i++] == '}') break }; m }
            '[' -> { i++; val l = ArrayList<Any?>(); ws(); if (s[i] == ']') { i++; return l }
                while (true) { l += value(); ws(); if (s[i++] == ']') break }; l }
            '"' -> str()
            't' -> { i += 4; true }
            'f' -> { i += 5; false }
            'n' -> { i += 4; null }
            else -> { val st = i; while (i < s.length && (s[i].isDigit() || s[i] in "-+.eE")) i++; s.substring(st, i).toDouble() }
        }
    }
    fun str(): String {
        i++
        val sb = StringBuilder()
        while (s[i] != '"') {
            if (s[i] == '\\') { i++; when (s[i]) { 'n' -> sb.append('\n'); 'u' -> { sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }; else -> sb.append(s[i]) } }
            else sb.append(s[i])
            i++
        }
        i++
        return sb.toString()
    }
}


@Suppress("UNCHECKED_CAST")
private fun cases(root: Map<String, Any?>, k: String): List<Any?> = ((root[k] as Map<String, Any?>)["cases"] as List<Any?>)
private fun L(v: Any?): List<Any?> = v as List<Any?>
@Suppress("UNCHECKED_CAST")
private fun M(v: Any?): Map<String, Any?> = v as Map<String, Any?>
private fun S(v: Any?): String? = v as String?
private fun N(v: Any?): Double? = (v as Double?)
private fun Lg(v: Any?): Long? = (v as Double?)?.toLong()
private fun strs(v: Any?): List<String> = L(v).map { it as String }

fun main(args: Array<String>) {
    val root = M(J(File(args.getOrElse(0) { "tools/test/build179_cases.json" }).readText()).value())

    for (c in cases(root, "refresh")) { val a = L(c)
        eq("refresh $a", B.Refresh.action(S(a[0]), S(a[1]), Lg(a[2]), Lg(a[3]), Lg(a[4])!!, a[5] as Boolean, a[6] as Boolean), a[7]) }
    for (c in cases(root, "engineFirst")) { val a = L(c); eq("engineFirst $a", B.Engine.first(strs(a[0]), S(a[1])), a[2]) }
    for (c in cases(root, "engineSwitch")) { val a = L(c)
        eq("engineSwitch $a", B.Engine.shouldSwitch(a[0] as Boolean, a[1] as Boolean, N(a[2])!!, N(a[3])!!, N(a[4])!!, a[5] as Boolean), a[6]) }
    for (c in cases(root, "engineNext")) { val a = L(c); eq("engineNext $a", B.Engine.next(strs(a[0]), a[1] as String, a[2] as Boolean), a[3]) }
    for (c in cases(root, "engineMemo")) { val a = L(c); eq("engineMemo $a", B.Engine.memo(S(a[0]), a[1] as String, S(a[2]), a[3] as Boolean), a[4]) }
    for (c in cases(root, "zapSearch")) { val m = M(c)
        val ch = L(m["channels"]).map { M(it) }
        val got = B.Zap.search(m["q"] as String, ch.map { it["name"] as String }, ch.map { N(it["number"])?.toInt() })
        eq("zapSearch ${m["q"]}", got, L(m["expect"]).map { N(it)!!.toInt() }) }
    for (c in cases(root, "zapCategories")) { val a = L(c); eq("zapCategories $a", B.Zap.categories(strs(a[0]), L(a[1]).map { it as Boolean }), strs(a[2])) }
    for (c in cases(root, "teamEq")) { val a = L(c); eq("teamEq $a", B.Scores.teamEq(a[0] as String, a[1] as String), a[2]) }
    for (c in cases(root, "espnLink")) { val m = M(c); val g = M(m["guide"])
        val ev = L(m["events"]).map { M(it) }.map { B.Scores.Link(it["id"] as String, strs(it["home"]), strs(it["away"]), Lg(it["start"])!!) }
        eq("espnLink $g", B.Scores.link(g["home"] as String, g["away"] as String, Lg(g["start"])!!, ev), m["expect"]) }
    for (c in cases(root, "scoreText")) { val a = L(c); eq("scoreText $a", B.Scores.text(S(a[0]), a[1], a[2], S(a[3])), a[4]) }
    for (c in cases(root, "teamColor")) { val a = L(c); eq("teamColor $a", B.TeamColor.pick(L(a[0]).map { it as String? }, a[1] as String), a[2]) }
    for (c in cases(root, "contrast")) { val a = L(c)
        val got = Math.round(B.TeamColor.contrast(B.TeamColor.parse(a[0] as String)!!) * 100) / 100.0
        eq("contrast $a", kotlin.math.abs(got - N(a[1])!!) <= 0.011, true) }
    for (c in cases(root, "ratingText")) { val a = L(c); eq("ratingText $a", B.Ratings.text(a[0] as String, N(a[1])), a[2]) }
    for (c in cases(root, "forYou")) { val m = M(c)
        val titles = L(m["titles"]).map { M(it) }.map { B.ForYou.Title(it["key"] as String, it["title"] as String, strs(it["genres"]), strs(it["cast"]), N(it["rating"])) }
        fun counts(v: Any?) = M(v).mapValues { N(it.value)!!.toInt() }
        val got = B.ForYou.rank(titles, strs(m["liked"]).toSet(), strs(m["disliked"]).toSet(), strs(m["watched"]).toSet(),
            (m["blocked"]?.let { strs(it) } ?: emptyList()).toSet(), counts(m["recLiked"]), counts(m["recWatched"]))
        eq("forYou ${m["liked"]}", got, strs(m["expect"])) }
    for (c in cases(root, "sort")) { val m = M(c)
        val items = L(m["items"]).map { M(it) }.map { B.Sort.Item(it["name"] as String, N(it["year"])?.toInt(), Lg(it["added"]), N(it["rating"])) }
        eq("sort ${m["mode"]}", B.Sort.order(m["mode"] as String, items), L(m["expect"]).map { N(it)!!.toInt() }) }
    for (c in cases(root, "sortKey")) { val a = L(c); eq("sortKey $a", B.Sort.key(a[0] as String, a[1] as String, a[2] as String), a[3]) }
    for (c in cases(root, "sortMerge")) { val m = M(c)
        fun e(v: Any?) = M(v).mapValues { val o = M(it.value); B.Sort.Entry(o["value"] as String, o["at"] as String) }
        eq("sortMerge", B.Sort.merge(e(m["a"]), e(m["b"])), e(m["expect"])) }
    for (c in cases(root, "cleanupAllowed")) { val a = L(c)
        eq("cleanupAllowed $a", B.Cleanup.allowed(N(a[0])!!.toInt(), N(a[1])!!.toInt(), a[2] as Boolean), a[3]) }
    for (c in cases(root, "cleanupAction")) { val a = L(c); eq("cleanupAction $a", B.Cleanup.action(a[0] as Boolean, a[1] as Boolean), a[2]) }
    for (c in cases(root, "webdavParse")) { val m = M(c); val e = M(m["expect"])
        val p = B.WebDAV.parse(m["path"] as String)
        val got = if (p.kind == "other") mapOf("kind" to "other") else if (p.kind == "movie")
            mapOf("kind" to "movie", "title" to p.title, "year" to p.year?.toDouble())
            else mapOf("kind" to "episode", "title" to p.title, "year" to p.year?.toDouble(), "season" to p.season?.toDouble(), "episode" to p.episode?.toDouble())
        eq("webdavParse ${m["path"]}", got, e) }

    // X2: herverbinden (eigen gevallen, gelijk aan de Apple-zelftest)
    eq("retry live 0", B.Stream.retryDelay(0, true, true), 0.5)
    eq("retry live 4", B.Stream.retryDelay(4, true, true), 8.0)
    eq("retry live 5", B.Stream.retryDelay(5, true, true), null)
    eq("retry cold 1", B.Stream.retryDelay(1, true, false), 3.0)
    eq("retry cold 2", B.Stream.retryDelay(2, true, false), null)
    eq("retry vod 1", B.Stream.retryDelay(1, false, true), null)
    eq("retry outage", B.Stream.retryDelay(0, true, true, 46.0), null)
    eq("stale", B.Stream.terminalIsStale(2.0, false), true)
    eq("stale left", B.Stream.terminalIsStale(2.0, true), false)
    eq("reason ended", B.Stream.reason(false, true, true, 0.0), "serverClosed")
    eq("reason timeout", B.Stream.reason(false, false, true, 16.0), "timeout")

    println("Build179LogicTest: $checks controles, $failures fouten")
    if (failures > 0) kotlin.system.exitProcess(1)
}
