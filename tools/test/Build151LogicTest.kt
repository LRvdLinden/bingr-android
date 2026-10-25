package nl.lrvdlinden.bingr

import java.io.File

// Build 151: unit-tests van features/Build151Logic.kt (rij "Zenders" op Home: welke zenders, naam, ontdubbelen) met de
// gedeelde gevallen uit tools/test/build151_cases.json (dezelfde als tizen/test/build151_test.js).
// Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/features/Build149Logic.kt app/src/main/java/nl/lrvdlinden/bingr/features/Build150Logic.kt \
//     app/src/main/java/nl/lrvdlinden/bingr/features/Build151Logic.kt \
//     tools/test/Build151LogicTest.kt -include-runtime -d /tmp/b151.jar
//   java -jar /tmp/b151.jar tools/test/build151_cases.json

private var failures = 0
private var checks = 0
private fun eq(name: String, got: Any?, want: Any?) {
    checks++
    if (got != want) { failures++; println("FOUT $name kreeg <$got>, verwacht <$want>"); println("::error title=Build151LogicTest::$name") }
}
private fun ok(name: String, v: Boolean) = eq(name, v, true)

/** Kleine JSON-lezer (alleen voor de testgevallen): String, Double, Boolean, null, List, Map. */
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
fun main(args: Array<String>) {
    val path = args.firstOrNull() ?: "tools/test/build151_cases.json"
    val cases = J(File(path).readText()).value() as Map<String, Any?>
    val CH = Build151Logic.Channels

    for (c in cases["names"] as List<List<Any?>>) {
        val raw = c[0] as String
        eq("zendernaam '$raw'", CH.name(raw), c[1])
        eq("zendersleutel '$raw'", CH.sameKey(raw), c[2])
    }

    data class Ch(val id: String, val name: String, val logo: Boolean, val kids: Boolean)
    for (c in cases["pick"] as List<Map<String, Any?>>) {
        val chans = (c["channels"] as Map<String, List<Any?>>).mapValues { (id, v) -> Ch(id, v[0] as String, v[1] as Boolean, v[2] as Boolean) }
        val pref = (c["preferred"] as List<String>).map { chans.getValue(it) }
        val groups = (c["groups"] as List<List<Any?>>).map { g -> (g[0] as String) to (g[1] as List<String>).map { chans.getValue(it) } }
        val kids = c["kids"] as Boolean
        val got = CH.pick(pref, groups, { it.id }, { it.name }, { it.logo }, { !kids || it.kids }, (c["max"] as Double).toInt())
        eq("kiezen: ${c["name"]}", got.map { it.id }, c["want"])
    }

    // ontdubbelen per rij: twee servers (A = actief, B) met overlappende titels
    val RW = Build151Logic.Rows
    for (c in cases["dedupe"] as List<Map<String, Any?>>) {
        val items = c["items"] as List<Map<String, Any?>>
        val pref = c["preferred"] as List<String>
        fun year(x: Map<String, Any?>) = (x["year"] as Double?)?.toInt()
        fun langRank(x: Map<String, Any?>) = RW.rank(RW.tags(x["group"] as String, x["title"] as String), x["src"] == "A", pref)
        val got = when (c["mode"]) {
            "channels" -> RW.dedupe(items, { "c:" + CH.sameKey(it["title"] as String) }, { "" }, { null }) { langRank(it) }
            "recent" -> RW.dedupe(items, { it["id"] as String? }, { it["title"] as String }, { year(it) }) { -((it["updated"] as Double).toLong()) }
            else -> RW.dedupe(items, { it["id"] as String? }, { it["title"] as String }, { year(it) }) { langRank(it) }
        }
        eq("ontdubbelen: ${c["name"]}", got.map { it["k"] }, c["want"])
    }

    // belasting: 12.000 zenders in 400 categorieën, 50 recente/favoriete
    val big = (0 until 12_000).map { Ch("c$it", "NL | Zender $it HD", it % 3 != 0, false) }
    val bigGroups = big.groupBy { "G" + (it.id.drop(1).toInt() % 400) }.toList()
    val t0 = System.nanoTime()
    var r: List<Ch> = emptyList()
    repeat(20) { r = CH.pick(big.take(50), bigGroups, { it.id }, { it.name }, { it.logo }, { true }) }
    val ms = (System.nanoTime() - t0) / 1_000_000 / 20
    println("kiezen: 12.000 zenders in $ms ms")
    eq("kiezen: maximum", r.size, CH.MAX)
    ok("kiezen: 12.000 zenders < 50 ms", ms < 50)

    println(if (failures == 0) "Build151Logic: alle $checks controles geslaagd" else "Build151Logic: $failures van $checks controles mislukt")
    kotlin.system.exitProcess(if (failures > 0) 1 else 0)
}
