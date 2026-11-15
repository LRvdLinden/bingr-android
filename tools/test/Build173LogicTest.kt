package nl.lrvdlinden.bingr

import java.io.File
import java.time.Instant

// Build 172 (Sport): wedstrijden herkennen, teams, ontdubbelen en automatische herinneringen.
// Gedeelde gevallen: docs/sport_cases.json in LRvdLinden/iptv-ios-appletv (bron; niet hier veranderen), met een
// kopie in tools/test/sport_cases.json. Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/features/Build173Logic.kt tools/test/Build173LogicTest.kt -include-runtime -d /tmp/b173.jar
//   java -jar /tmp/b173.jar tools/test/sport_cases.json

private var failures = 0
private var checks = 0
private fun eq(name: String, got: Any?, want: Any?) {
    checks++
    if (got != want) { failures++; println("FOUT $name kreeg <$got>, verwacht <$want>"); println("::error title=Build173LogicTest::$name") }
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



private fun ms(v: Any?): Long = Instant.parse(v as String).toEpochMilli()
@Suppress("UNCHECKED_CAST") private fun pairs(c: Map<String, Any?>, k: String) = (c[k] as List<List<Any?>>)
@Suppress("UNCHECKED_CAST") private fun objs(v: Any?) = (v as? List<Map<String, Any?>>) ?: emptyList()
@Suppress("UNCHECKED_CAST") private fun strs(v: Any?) = (v as? List<String>) ?: emptyList()
private fun num(v: Any?) = (v as Double).toInt()

@Suppress("UNCHECKED_CAST")
fun main(args: Array<String>) {
    val path = args.firstOrNull() ?: "tools/test/sport_cases.json"
    val C = J(File(path).readText()).value() as Map<String, Any?>
    val S = Build173Logic
    pairs(C, "categories").forEach { eq("categorie ${it[0]}", S.isSportCategory(it[0] as String), it[1]) }
    pairs(C, "channels").forEach { eq("zender ${it[0]} / ${it[1]}", S.isSportChannel(it[0] as String, it[1] as String, it[2] as Boolean), it[3]) }
    pairs(C, "quality").forEach { eq("kwaliteit ${it[0]}", S.quality(it[0] as String), num(it[1])) }
    pairs(C, "excluded").forEach { eq("herhaling ${it[0]}", S.isExcluded(it[0] as String), it[1]) }
    pairs(C, "clean").forEach { eq("opschonen ${it[0]}", S.cleanTeam(it[0] as String), it[1]) }
    pairs(C, "short").forEach { eq("initialen ${it[0]}", S.short(it[0] as String), it[1]) }
    pairs(C, "teamKey").forEach { eq("teamsleutel ${it[0]}", S.teamKey(it[0] as String), it[1]) }
    pairs(C, "color").forEach { eq("kleur ${it[0]}", S.colorIndex(it[0] as String), num(it[1])) }

    val start = ms("2026-11-21T19:30:00Z")
    objs(C["classify"]).forEach { c ->
        val t = c["title"] as String
        val got = S.classify(t, c["description"] as String?, c["channel"] as String? ?: "", c["category"] as String? ?: "",
            start, start + num(c["minutes"]) * 60_000L)
        val e = c["expect"] as Map<String, Any?>?
        val want = e?.let { Build173Logic.Match(it["kind"] as String, it["title"] as String, it["home"] as String, it["away"] as String,
            it["sport"] as String, it["competition"] as String, it["competitionId"] as String? ?: "") }
        eq("wedstrijd $t", got, want)
    }
    objs(C["matchKey"]).forEach { c ->
        val s = ms(c["start"])
        val m = S.classify(c["title"] as String, null, c["channel"] as String? ?: "Sport", "Sport", s, s + 7_200_000L)
        eq("sleutel ${c["title"]}", m?.let { S.matchKey(it, s) }, c["key"])
    }
    fun listings(v: Any?) = objs(v).mapNotNull { l ->
        val s = ms(l["start"]); val e = s + num(l["minutes"]) * 60_000L
        S.classify(l["title"] as String, null, l["channel"] as String, "Sport", s, e)?.let {
            Build173Logic.Listing(l["id"] as String, l["channel"] as String, l["favorite"] == true, s, e, it)
        }
    }
    objs(C["dedupe"]).forEachIndexed { i, c ->
        eq("ontdubbelen $i", S.dedupe(listings(c["listings"])).map { g -> g.listings.map { it.id } }, c["expect"])
    }
    objs(C["auto"]).forEachIndexed { i, c ->
        val groups = S.dedupe(listings(c["listings"]))
        eq("automatisch $i", S.autoReminders(groups, ms(c["now"]), strs(c["teams"]), strs(c["competitions"]),
            strs(c["muted"]).toSet(), strs(c["existing"]).toSet()), c["expect"])
    }
    objs(C["pruneMuted"]).forEachIndexed { i, c -> eq("dempen opruimen $i", S.pruneMuted(strs(c["keys"]), ms(c["now"])), c["expect"]) }
    eq("lead 15", S.lead(15), 15); eq("lead 7", S.lead(7), 5); eq("lead null", S.lead(null), 5)
    eq("fnv leeg", S.hash(""), 0x811c9dc5L); eq("fnv a", S.hash("a"), 0xe40c292cL)
    eq("fold", S.fold("Atlético Ølstykke Straße"), "atletico olstykke strasse")
    // prestaties: 50.000 programma's
    val t0 = System.nanoTime()
    var found = 0
    for (n in 0 until 50_000) {
        val t = when (n % 3) { 0 -> "Ajax - PSV ${n % 7}"; 1 -> "Journaal"; else -> "GP Las Vegas: kwalificatie" }
        if (S.classify(t, null, "ESPN 1", "Sport", start, start + 5_400_000L) != null) found++
    }
    val took = (System.nanoTime() - t0) / 1_000_000
    eq("50.000 programma's binnen 20 s", took < 20_000, true)
    println("sport: 50.000 programma's in $took ms ($found wedstrijden)")
    println("$checks controles, $failures fout")
    if (failures > 0) kotlin.system.exitProcess(1)
}
