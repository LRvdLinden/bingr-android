package nl.lrvdlinden.bingr

import java.io.File
import nl.lrvdlinden.bingr.SportLogos178Logic as G

// Build 178: sport-logo's (SportLogos178Logic.kt) tegen de gedeelde gevallen uit docs/sportlogos_cases.json van
// iptv-ios-appletv (kopie: tools/test/sportlogos_cases.json; niet hier aanpassen). Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/features/Build173Logic.kt app/src/main/java/nl/lrvdlinden/bingr/features/SportLogos178Logic.kt \
//     tools/test/SportLogos178LogicTest.kt -include-runtime -d /tmp/b178l.jar
//   java -jar /tmp/b178l.jar tools/test/sportlogos_cases.json

private var failures = 0
private var checks = 0
private fun eq(name: String, got: Any?, want: Any?) {
    checks++
    if (got != want) { failures++; println("FOUT $name kreeg <$got>, verwacht <$want>"); println("::error title=SportLogos178LogicTest::$name") }
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
fun main(args: Array<String>) {
    val path = args.firstOrNull() ?: "tools/test/sportlogos_cases.json"
    val root = J(File(path).readText()).value() as Map<String, Any?>
    fun rows(k: String) = (root[k] as? List<Any?> ?: emptyList()).map { it as List<Any?> }
    fun strs(v: Any?) = (v as List<Any?>).map { it as String }
    eq("gevallen geladen", rows("keys").size >= 10 && rows("teamArt").size >= 8, true)

    for (c in rows("keys")) eq("sleutels ${c[0]}", G.keys(c[0] as String), strs(c[1]))
    val index = (root["matchIndex"] as Map<String, Any?>).mapValues { it.value as String }
    for (c in rows("match")) eq("zoeken ${c[0]}", G.match(G.keys(c[0] as String), index), c[1])
    val bi = root["buildIndex"] as Map<String, Any?>
    val teams = (bi["teams"] as List<Any?>).map { val t = it as Map<String, Any?>; G.Team(strs(t["names"]), t["logo"] as String) }
    eq("index uit een teamlijst", G.buildIndex(teams), (bi["expect"] as Map<String, Any?>).mapValues { it.value as String })
    for (c in rows("sportsDBThumb")) eq("TheSportsDB-badge ${c[0]}", G.sportsDBThumb(c[0] as String), c[1])
    for (c in rows("sportsDBSport")) eq("TheSportsDB-sport ${c[0]}", G.sportsDBSport(c[0] as String), c[1])
    for (c in rows("leagueSlug")) eq("slug ${c[0]}", G.leagueSlug(c[0] as String), c[1])
    for (c in rows("site")) eq("site ${c[0]}", G.site(c[0] as String), c[1])
    for (c in rows("competitionURLs")) eq("competitie-adressen ${c[0]} ${c[1]}", G.competitionURLs(c[0] as String, c[1] as Boolean), strs(c[2]))
    for (c in rows("espnLeagues")) eq("ESPN ${c[0]} ${c[1]}", G.espnLeagues(c[0] as String, c[1] as String), strs(c[2]))
    for (c in rows("countryCode")) eq("land ${c[0]}", G.countryCode(c[0] as String), c[1])
    for (c in rows("teamArt")) eq("team ${c[0]} ${c[1]}", G.teamArt(c[0] as String, c[1] as String, c[2] as String), c[3])
    val urls = root["urls"] as Map<String, Any?>
    fun urows(k: String) = (urls[k] as List<Any?>).map { it as List<Any?> }
    for (c in urows("league")) eq("url competitie ${c[0]}", G.leagueURL(c[0] as String), c[1])
    for (c in urows("flag")) eq("url vlag ${c[0]}", G.flagURL(c[0] as String), c[1])
    for (c in urows("favicon")) eq("url favicon ${c[0]}", G.faviconURL(c[0] as String, (c[1] as Double).toInt()), c[2])
    for (c in urows("espnTeams")) eq("url ESPN ${c[0]}", G.espnTeamsURL(c[0] as String), c[1])
    for (c in urows("sportsDBSearch")) eq("url TheSportsDB ${c[0]}", G.sportsDBSearchURL(c[0] as String), c[1])
    for (c in rows("usable")) eq("cache $c", G.usable(c[0] as String, c[1] as Double, c[2] as Boolean), c[3])
    for (c in rows("cacheName")) eq("cachenaam ${c[1]}", G.cacheName(c[0] as String, c[1] as String, c[2] as String), c[3])

    println("$checks controles, $failures fout")
    if (failures > 0) kotlin.system.exitProcess(1)
}
