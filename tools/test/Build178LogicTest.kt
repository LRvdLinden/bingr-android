package nl.lrvdlinden.bingr

import java.io.File
import nl.lrvdlinden.bingr.Build178Logic as L
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// Build 178: pure logica (Build178Logic.kt) tegen de gedeelde gevallen uit docs/build178_cases.json van iptv-ios-appletv
// (kopie: tools/test/build178_cases.json; niet hier aanpassen). Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/features/Build178Logic.kt tools/test/Build178LogicTest.kt -include-runtime -d /tmp/b178.jar
//   java -jar /tmp/b178.jar tools/test/build178_cases.json

private var failures = 0
private var checks = 0
private fun eq(name: String, got: Any?, want: Any?) {
    checks++
    if (got != want) { failures++; println("FOUT $name kreeg <$got>, verwacht <$want>"); println("::error title=Build178LogicTest::$name") }
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
private fun listings(json: String): List<Map<String, Any?>> {
    val v = try { J(json).value() } catch (_: Exception) { return emptyList() }
    val m = v as? Map<String, Any?> ?: return emptyList()
    return when (val l = m["epg_listings"]) {
        is List<*> -> l.filterIsInstance<Map<String, Any?>>()
        is Map<*, *> -> l.values.filterIsInstance<Map<String, Any?>>()
        else -> emptyList()
    }
}

@Suppress("UNCHECKED_CAST")
fun main(args: Array<String>) {
    val path = args.firstOrNull() ?: "tools/test/build178_cases.json"
    val root = J(File(path).readText()).value() as Map<String, Any?>
    fun cases(k: String) = (root[k] as Map<String, Any?>)["cases"] as List<List<Any?>>
    fun int(v: Any?) = (v as Double).toInt()
    fun sm(v: Any?) = (v as Map<String, Any?>).mapValues { L.str(it.value) ?: "" }
    eq("gevallen geladen", cases("durationDays").size >= 15 && cases("m3uURL").size >= 15, true)

    cases("durationDays").forEachIndexed { i, c -> eq("duur $i ${c[0]}", L.days(c[0]), int(c[1])) }
    cases("xtreamDays").forEachIndexed { i, c -> eq("xtream $i", L.xtreamDays(c[0], c[1]), int(c[2])) }
    cases("stalkerDays").forEachIndexed { i, c -> eq("stalker $i", L.stalkerDays(c[0], c[1]), int(c[2])) }
    cases("m3u").forEachIndexed { i, c ->
        val w = c[2] as List<Any?>
        eq("m3u $i", L.m3u(sm(c[0]), sm(c[1])), L.M3UCatchup(int(w[0]), w[1] as String?, w[2] as String?))
    }
    cases("xtreamURL").forEachIndexed { i, c ->
        eq("xtream-url $i", L.xtreamURL(c[0] as String, c[1] as String, c[2] as String, c[3] as String, iso(c[4] as String),
            iso(c[5] as String), TimeZone.getTimeZone(c[6] as String)), c[7])
    }
    cases("m3uURL").forEachIndexed { i, c ->
        eq("m3u-url $i ${c[0]}", L.m3uURL(c[0] as String, c[1] as String?, c[2] as String?, iso(c[3] as String), iso(c[4] as String),
            iso(c[5] as String), TimeZone.getTimeZone(c[6] as String)), c[7])
    }
    cases("replay").forEachIndexed { i, c ->
        eq("replay $i", L.replayable(iso(c[0] as String), iso(c[1] as String), iso(c[2] as String), int(c[3]), c[4] as Boolean?), c[5])
    }
    cases("dayOffsets").forEachIndexed { i, c ->
        eq("dagen $i", L.dayOffsets(int(c[0]), (c[1] as List<Any?>).map { int(it) }), (c[2] as List<Any?>).map { int(it) })
    }
    cases("keep").forEachIndexed { i, c ->
        eq("venster $i", L.keep(iso(c[0] as String), iso(c[1] as String), iso(c[2] as String), int(c[3]), int(c[4])), c[5])
    }
    cases("simpleTable").forEachIndexed { i, c ->
        val got = L.simpleTable(listings(c[0] as String)).map { listOf(it.title, it.start, it.end, it.archive) }
        val want = (c[1] as List<List<Any?>>).map { listOf(it[0], iso(it[1] as String), iso(it[2] as String), it[3]) }
        eq("tabel $i", got, want)
    }
    cases("merge").forEachIndexed { i, c ->
        val g = (c[0] as List<List<Any?>>).map { Triple(it[0] as String, iso(it[1] as String), iso(it[2] as String)) }
        val a = (c[1] as List<List<Any?>>).map { L.TableProgram(it[0] as String, null, iso(it[1] as String), iso(it[2] as String), it[3] as Boolean?) }
        val want = (c[2] as List<List<Any?>>).map { listOf(it[0], iso(it[1] as String), iso(it[2] as String), it[3]) }
        eq("samenvoegen $i", L.merge(g, a).map { listOf(it.title, it.start, it.end, it.archive) }, want)
    }
    cases("summary").forEachIndexed { i, c ->
        val s = L.summary((c[0] as List<Any?>).map { int(it) })
        eq("samenvatting $i", listOf(s.first, s.second), (c[1] as List<Any?>).map { int(it) })
    }
    eq("base64-titel", L.text("Tk9TIEpvdXJuYWFs"), "NOS Journaal")
    eq("korte gewone titel blijft", L.text("Test"), "Test")
    println("$checks controles, $failures fout")
    if (failures > 0) kotlin.system.exitProcess(1)
}
