package nl.lrvdlinden.bingr

import java.io.File

// Build 150: unit-tests van features/Build150Logic.kt (categorienamen opschonen, samenvoegen, ontdubbelen, logo's) met
// de gedeelde gevallen uit tools/test/build150_cases.json (dezelfde als de webspeler; build 153: pipes), plus zoeken via
// Build149Logic.Search.key (zelfde opschoning).
// Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/features/Build149Logic.kt app/src/main/java/nl/lrvdlinden/bingr/features/Build150Logic.kt \
//     tools/test/Build150LogicTest.kt -include-runtime -d /tmp/b150.jar
//   java -jar /tmp/b150.jar tools/test/build150_cases.json

private var failures = 0
private var checks = 0
private fun eq(name: String, got: Any?, want: Any?) {
    checks++
    if (got != want) { failures++; println("FOUT $name kreeg <$got>, verwacht <$want>"); println("::error title=Build150LogicTest::$name") }
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
    val path = args.firstOrNull() ?: "tools/test/build150_cases.json"
    val cases = J(File(path).readText()).value() as Map<String, Any?>
    val CN = Build150Logic.CategoryName

    for (c in cases["clean"] as List<List<Any?>>) {
        val raw = c[0] as String
        val r = CN.clean(raw)
        eq("opschonen '$raw'", r.name, c[1])
        eq("labels '$raw'", r.tags, c[2])
        // twee keer opschonen verandert niets meer
        eq("idempotent '$raw'", CN.clean(r.name).name, r.name)
    }

    for (c in cases["merge"] as List<List<Any?>>) {
        val g = Build150Logic.Merge.groups(c[0] as List<String>)
        val want = (c[1] as List<List<Any?>>).map { Triple(it[0] as String, (it[1] as Double).toInt(), it[2] as String) }
        eq("samenvoegen ${c[0]}", g.map { Triple(it.name, it.sources.size, Build150Logic.Merge.chips(it.tags)) }, want)
    }

    // build 153: elk pipe-teken wordt `|`, en een stuk ertussen aan het begin of eind gaat weg
    for (c in (cases["pipes"] as? List<List<Any?>>).orEmpty()) {
        val ch = c[0] as String
        eq("pipe U+%04X".format(ch[0].code), Build150Logic.Pipes.normalize(ch), c[1])
        eq("pipe-stuk U+%04X".format(ch[0].code), CN.clean("${ch}NL$ch Netflix").name, "Netflix")
    }
    eq("pipes: stuk > 25 tekens blijft", Build150Logic.Pipes.cut("|" + "x".repeat(26) + "| Netflix").front, emptyList<String>())
    eq("pipes: alleen een stuk → zoals hij was", Build150Logic.Pipes.cut("┃NL┃").rest, "|NL|")

    for (c in cases["search"] as List<List<Any?>>) eq("zoeksleutel '${c[0]}'", Build149Logic.Search.key(c[0] as String), c[1])

    val providers = listOf("Netflix", "Videoland", "Disney Plus", "HBO Max", "Amazon Prime Video", "SkyShowtime", "Viaplay",
        "Apple TV Plus", "NPO Start", "Pathé Thuis").associate { Build150Logic.Merge.key(it) to "https://image.tmdb.org/t/p/w300/$it.png" }
    for (c in cases["logos"] as List<List<Any?>>) eq("logo '${c[0]}'", Build150Logic.Logos.providerFor(c[0] as String, providers), c[1])

    // ontdubbelen: zelfde id, of anders titel + jaar
    data class T(val id: String?, val title: String, val year: Int?)
    val items = listOf(T("tt1", "NL | Dune", 2021), T("tt1", "Dune (MULTI)", 2021), T(null, "The Witcher", 2019), T(null, "MULTI | The Witcher", 2019),
        T(null, "The Witcher", 2020), T("tt9", "Other", null))
    val d = Build150Logic.Merge.dedupe(items, { it.id }, { it.title }, { it.year })
    eq("ontdubbelen: aantal", d.size, 4)
    eq("ontdubbelen: eerste wint", d.first().title, "NL | Dune")

    // bron bij het afspelen: app-taal eerst, dan meertalig
    eq("voorkeursbron NL", Build150Logic.Merge.preferSources(listOf("| MULTI | NETFLIX", "| EN | NETFLIX", "| NL | NETFLIX"), listOf("NL")).first(), "| NL | NETFLIX")
    eq("voorkeursbron onbekend → meertalig", Build150Logic.Merge.preferSources(listOf("| EN | NETFLIX", "| MULTI | NETFLIX"), listOf("DE")).first(), "| MULTI | NETFLIX")

    // samenvoegen met inhoud: Netflix uit twee bronnen, dubbele titel één keer, NL-bron eerst
    val lists = listOf("| MULTI / LINGO | NETFLIX" to listOf(T("tt1", "Dune", 2021), T(null, "Wednesday", 2022)),
        "| NL | NETFLIX" to listOf(T("tt1", "NL | Dune", 2021), T(null, "Squid Game", 2021)), "Kids" to listOf(T(null, "Bluey", 2018)))
    val mg = Build150Logic.Merge.merge(lists, listOf("NL"), { it.id }, { it.title }, { it.year })
    eq("samenvoegen: groepen", mg.map { it.name }, listOf("Netflix", "Kids"))
    eq("samenvoegen: ontdubbeld aantal", mg[0].items.size, 3)
    eq("samenvoegen: NL-bron eerst", mg[0].items.first().title, "NL | Dune")
    eq("samenvoegen: bronnen", mg[0].sources, listOf("| NL | NETFLIX", "| MULTI / LINGO | NETFLIX"))

    // snelheid: 50.000 namen
    val t0 = System.nanoTime()
    for (i in 0 until 50_000) CN.strip("| NL | NETFLIX SERIES $i (4K)")
    val ms = (System.nanoTime() - t0) / 1_000_000
    println("opschonen: 50.000 namen in $ms ms")
    ok("opschonen: 50.000 namen < 3 s", ms < 3000)

    println(if (failures == 0) "Build150Logic: alle $checks controles geslaagd" else "Build150Logic: $failures van $checks controles mislukt")
    kotlin.system.exitProcess(if (failures > 0) 1 else 0)
}
