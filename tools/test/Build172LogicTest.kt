package nl.lrvdlinden.bingr

import java.io.File

// Build 172: unit-tests van features/Build172Logic.kt (categorieën opschonen) met de gedeelde gevallen uit
// docs/merge_cases.json van iptv-ios-appletv (kopie: tools/test/merge_cases.json; niet hier aanpassen).
// Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/features/Build172Logic.kt tools/test/Build172LogicTest.kt \
//     -include-runtime -d /tmp/b172.jar
//   java -jar /tmp/b172.jar tools/test/merge_cases.json

private var failures = 0
private var checks = 0
private fun eq(name: String, got: Any?, want: Any?) {
    checks++
    if (got != want) { failures++; println("FOUT $name kreeg <$got>, verwacht <$want>"); println("::error title=Build172LogicTest::$name") }
}

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
private fun strs(v: Any?): List<String> = (v as? List<Any?>)?.map { it as String } ?: emptyList()
@Suppress("UNCHECKED_CAST")
private fun dict(v: Any?): Map<String, String> = (v as? Map<String, Any?>)?.mapValues { it.value as String } ?: emptyMap()
@Suppress("UNCHECKED_CAST")
private fun custom(v: Any?): Build172Logic.Custom {
    val d = v as? Map<String, Any?> ?: emptyMap()
    return Build172Logic.Custom(dict(d["merged"]), dict(d["renamed"]), strs(d["groupOrder"]))
}
private fun int(v: Any?) = (v as Double).toInt()

@Suppress("UNCHECKED_CAST")
private fun input(c: Map<String, Any?>): List<Build172Logic.Suggestion> {
    val gs = (c["groups"] as List<Map<String, Any?>>).map { Build172Logic.Group(it["name"] as String, int(it["count"])) }
    return Build172Logic.suggest(gs, dict(c["merged"]), dict(c["renamed"]), strs(c["hidden"]).toSet(), strs(c["ignored"]).toSet())
}

@Suppress("UNCHECKED_CAST")
fun main(args: Array<String>) {
    val path = args.firstOrNull() ?: "tools/test/merge_cases.json"
    val root = J(File(path).readText()).value() as Map<String, Any?>
    val L = Build172Logic
    for (c in root["norm"] as List<Map<String, Any?>>) {
        val n = c["name"] as String
        val r = L.norm(n)
        eq("norm woorden '$n'", r.tokens, strs(c["tokens"]))
        eq("norm land '$n'", r.country, c["country"])
    }
    for (c in root["brand"] as List<Map<String, Any?>>) eq("merk '${c["name"]}'", L.brandOf(c["name"] as String), c["brand"])
    val all = HashMap<String, List<Build172Logic.Suggestion>>()
    for (c in root["suggest"] as List<Map<String, Any?>>) {
        val name = c["name"] as String
        val got = input(c)
        all[name] = got
        val want = (c["expect"] as List<Map<String, Any?>>).map {
            listOf(it["id"], it["kind"], it["target"], it["targetGroup"], strs(it["groups"]), int(it["total"]), it["on"], strs(it["renamed"]))
        }
        eq("voorstellen: $name", got.map { listOf(it.id, it.kind, it.target, it.targetGroup, it.groups, it.total, it.on, it.renamed) }, want)
    }
    for (c in root["apply"] as List<Map<String, Any?>>) {
        val got = L.apply(custom(c["custom"]), strs(c["groups"]), c["targetGroup"] as String, c["targetName"] as String, c["cleanName"] as String)
        eq("apply: ${c["name"]}", got, custom(c["expect"]))
    }
    for (c in root["detach"] as List<Map<String, Any?>>) {
        val src = c["sources"]?.let { strs(it) }
        val got = L.detach(custom(c["custom"]), c["target"] as String, src, c["madeName"] as String?)
        eq("detach: ${c["name"]}", got, custom(c["expect"]))
    }
    for (c in root["summary"] as List<Map<String, Any?>>) {
        val w = c["expect"] as Map<String, Any?>
        eq("samenvatting: ${c["name"]}", L.summary(all[c["case"] as String] ?: emptyList()), int(w["from"]) to int(w["to"]))
    }
    // prestaties: 5.000 groepen binnen een seconde
    val big = (0 until 5000).map { i: Int -> Build172Logic.Group(if (i % 5 != 0) "NL | Categorie $i Films" else (if (i % 2 == 1) "Netflix $i" else "Disney+ $i"), i) }
    L.suggest(big)   // opwarmen (JIT)
    val t0 = System.nanoTime()
    L.suggest(big)
    val ms = (System.nanoTime() - t0) / 1_000_000
    eq("5.000 groepen binnen een seconde ($ms ms)", ms < 1000, true)
    println("$checks controles, $failures fout (5.000 groepen: $ms ms)")
    if (failures > 0) kotlin.system.exitProcess(1)
}
