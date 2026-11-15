package nl.lrvdlinden.bingr

import java.io.File
import nl.lrvdlinden.bingr.Build176Logic as L
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// Build 176: pure logica (Build176Logic.kt) tegen de gedeelde gevallen uit docs/build176_cases.json van iptv-ios-appletv
// (kopie: tools/test/build176_cases.json; niet hier aanpassen). Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/features/Build176Logic.kt tools/test/Build176LogicTest.kt -include-runtime -d /tmp/b176.jar
//   java -jar /tmp/b176.jar tools/test/build176_cases.json

private var failures = 0
private var checks = 0
private fun eq(name: String, got: Any?, want: Any?) {
    checks++
    if (got != want) { failures++; println("FOUT $name kreeg <$got>, verwacht <$want>"); println("::error title=Build176LogicTest::$name") }
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

@Suppress("UNCHECKED_CAST")
fun main(args: Array<String>) {

    val path = args.firstOrNull() ?: "tools/test/build176_cases.json"
    val root = J(File(path).readText()).value() as Map<String, Any?>
    fun cases(k: String) = (root[k] as Map<String, Any?>)["cases"] as List<Any?>

    for ((i, c) in cases("autoplay").withIndex()) {
        val a = c as List<Any?>
        eq("autoplay $i", L.Autoplay.start(a[0] as Boolean?, a[1] as Boolean, a[2] as Boolean), a[3])
    }
    for ((i, c) in cases("lock").withIndex()) {
        val a = c as List<Any?>
        eq("lock $i", L.Lock.ask(a[0] as Boolean, a[1] as Boolean, (a[2] as Double?)?.toLong(), (a[3] as Double).toLong()), a[4])
    }
    for ((i, c) in cases("dataMode").withIndex()) {
        val a = c as List<Any?>
        eq("dataMode $i", L.DataMode.saver(a[0] as String, a[1] as String, a[2] as Boolean), a[3])
    }
    for ((i, c) in cases("variant").withIndex()) {
        val m = c as Map<String, Any?>
        val vs = (m["variants"] as List<Any?>).map { v -> val o = v as Map<String, Any?>
            L.DataMode.Variant((o["bandwidth"] as Double).toLong(), (o["height"] as Double?)?.toInt()) }
        eq("variant $i", L.DataMode.variant(m["saver"] as Boolean, vs), (m["expect"] as Double?)?.toInt())
    }
    for (c in cases("epgSearch")) {
        val m = c as Map<String, Any?>
        val q = m["q"] as String; val title = m["title"] as String; val desc = m["desc"] as String?
        val hit = L.EpgSearch.match(q, title, desc)
        val exp = m["expect"] as Map<String, Any?>?
        val got = hit?.let { mapOf("field" to if (it.field == L.EpgSearch.Field.TITLE) "title" else "description", "highlight" to it.highlight(title, desc)) }
        eq("epgSearch '$q'", got, exp)
    }
    for ((i, c) in cases("releaseReminder").withIndex()) {
        val a = c as List<Any?>
        val tmdb = (a[1] as Double).toInt(); val season = (a[2] as Double?)?.toInt()
        eq("release id $i", L.Release.id(a[0] as String, tmdb, season, a[3] as String?), a[6])
        eq("release start $i", L.Release.start(a[4] as String, TimeZone.getTimeZone(a[5] as String))?.let { isoFmt.format(java.util.Date(it)) }, a[7])
    }

    // eigen gevallen (Android)
    eq("autoplay instelling kind", L.Autoplay.effective(null, true), false)
    eq("poster maat", L.Posters.size(300), "w342"); eq("poster maat groot", L.Posters.size(2000), "w780")
    eq("poster url kleiner", L.Posters.sized("https://image.tmdb.org/t/p/original/a.jpg", 180), "https://image.tmdb.org/t/p/w185/a.jpg")
    eq("poster url al klein", L.Posters.sized("https://image.tmdb.org/t/p/w154/a.jpg", 300), "https://image.tmdb.org/t/p/w154/a.jpg")
    eq("poster url w500 → w342", L.Posters.sized("https://image.tmdb.org/t/p/w500/a.jpg", 300), "https://image.tmdb.org/t/p/w342/a.jpg")
    eq("poster andere url", L.Posters.sized("http://x/p.jpg", 100), "http://x/p.jpg")
    eq("help nl", L.Help.url("nl"), "https://support.bingr.online/hulp/"); eq("help en", L.Help.url("de"), "https://support.bingr.online/hulp/?lang=en")
    eq("veeg links", L.Swipe.side(10f, 100f), L.Swipe.Side.BRIGHTNESS); eq("veeg rechts", L.Swipe.side(70f, 100f), L.Swipe.Side.VOLUME); eq("veeg midden", L.Swipe.side(50f, 100f), L.Swipe.Side.MIDDLE)
    eq("veeg omhoog", L.Swipe.apply(0.5f, -40f, 100f), 1f); eq("veeg omlaag", L.Swipe.apply(0.5f, 20f, 100f), 0.25f)
    eq("venster", L.EpgSearch.inWindow(0, 10, 5), true); eq("venster voorbij", L.EpgSearch.inWindow(0, 5, 5), false)
    eq("venster 8 dagen", L.EpgSearch.inWindow(8 * 86_400_000L, 9 * 86_400_000L, 0), false)
    eq("voorvertoning saver", L.Preview.allowed(true, true, false, false), false)
    data class H(val f: L.EpgSearch.Field, val s: Long, val k: String)
    eq("volgorde", L.EpgSearch.order(listOf(H(L.EpgSearch.Field.DESCRIPTION, 1, "a"), H(L.EpgSearch.Field.TITLE, 5, "b"),
        H(L.EpgSearch.Field.TITLE, 2, "c"), H(L.EpgSearch.Field.TITLE, 9, "c")), { it.f }, { it.s }, { it.k }).map { it.k }, listOf("c", "b", "a"))

    println(if (failures == 0) "Build176Logic: $checks controles, alles goed" else "Build176Logic: $failures van $checks controles fout")
    if (failures > 0) kotlin.system.exitProcess(1)
}
