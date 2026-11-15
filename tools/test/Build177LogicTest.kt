package nl.lrvdlinden.bingr

import java.io.File
import nl.lrvdlinden.bingr.Build177Logic as L
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// Build 177: pure logica (Build177Logic.kt) tegen de gedeelde gevallen uit docs/build177_cases.json van iptv-ios-appletv
// (kopie: tools/test/build177_cases.json; niet hier aanpassen). Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/features/Build177Logic.kt tools/test/Build177LogicTest.kt -include-runtime -d /tmp/b177.jar
//   java -jar /tmp/b177.jar tools/test/build177_cases.json

private var failures = 0
private var checks = 0
private fun eq(name: String, got: Any?, want: Any?) {
    checks++
    if (got != want) { failures++; println("FOUT $name kreeg <$got>, verwacht <$want>"); println("::error title=Build177LogicTest::$name") }
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

private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
private fun iso(s: String): Long = isoFmt.parse(s).time

@Suppress("UNCHECKED_CAST")
fun main(args: Array<String>) {
    val path = args.firstOrNull() ?: "tools/test/build177_cases.json"
    val root = J(File(path).readText()).value() as Map<String, Any?>
    fun cases(k: String) = (root[k] as Map<String, Any?>)["cases"] as List<Any?>
    eq("gevallen geladen", cases("clean").size >= 8 && cases("column").size >= 8, true)

    for ((i, c) in cases("clean").withIndex()) {
        val a = c as List<Any?>
        val input = a[0] as List<List<Any?>>
        val want = (a[1] as List<List<Any?>>).map { L.Cleaned((it[0] as Double).toInt(), iso(it[1] as String), iso(it[2] as String)) }
        eq("clean $i", L.clean(input.map { it[0] as String }, input.map { iso(it[1] as String) }, input.map { iso(it[2] as String) }), want)
    }
    for ((i, c) in cases("column").withIndex()) {
        val a = (c as List<Any?>)[0] as List<Any?>
        val w = c[1] as List<Any?>
        val col = L.column((a[0] as Double).toInt(), (a[1] as Double?)?.toInt(), (a[2] as Double?)?.toInt(), a[3] as Boolean, a[4] as Boolean)
        eq("column regels $i", col.rows.map { if (it.now) "n" + (if (it.index >= 0) it.index.toString() else "") else "p${it.index}" }, w[0])
        eq("column focus $i", col.focus, (w[1] as Double?)?.toInt())
        eq("column anker $i", col.anchor, (w[2] as Double?)?.toInt())
    }
    for ((i, c) in cases("releaseStops").withIndex()) {
        val a = c as List<Any?>
        eq("releaseStops $i", L.releaseStops(a[0] as String, a[1] as String?, a[2] as String?), a[3])
    }
    for ((i, c) in cases("secondsToNextMinute").withIndex()) {
        val a = c as List<Any?>
        eq("minuut $i", L.secondsToNextMinute(a[0] as Double), a[1] as Double)
    }
    // eigen gevallen (Android)
    eq("titelsleutel", L.titleKey("  De   Frogers "), "de frogers")
    data class P(val t: String, val s: Long, val e: Long)
    val ps = listOf(P("B", 20, 30), P("A", 10, 20), P("a", 11, 19))
    eq("clean lijst", L.clean(ps, { it.t }, { it.s * 1000 }, { it.e * 1000 }) { p, s, e -> p.copy(s = s / 1000, e = e / 1000) },
        listOf(P("A", 10, 20), P("B", 20, 30)))

    println("$checks controles, $failures fout")
    if (failures > 0) kotlin.system.exitProcess(1)
}
