package nl.lrvdlinden.bingr

// Build 166: dagbalk van de tv-gids alleen met dagen waarvoor er gids-info is.
// Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/features/Build164Logic.kt app/src/main/java/nl/lrvdlinden/bingr/features/Build166Logic.kt \
//     tools/test/Build166LogicTest.kt -include-runtime -d /tmp/b166.jar
//   java -jar /tmp/b166.jar

private var failures = 0
private var checks = 0
private fun eq(name: String, got: Any?, want: Any?) {
    checks++
    if (got != want) { failures++; println("FOUT $name kreeg <$got>, verwacht <$want>"); println("::error title=Build166LogicTest::$name") }
}

/** Testzender: dagen met programma's en terugkijkdagen. */
private data class Ch(val days: Set<Int>, val catchup: Int = 0)

fun main() {
    val G = Build166Logic.GuideDays
    val cand = Build164Logic.Days.offsets(7)   // -7..7
    fun av(chs: List<Ch>) = G.available(cand, chs, { c, o -> o in c.days }, { it.catchup })

    // 1. alleen dagen met programma's; vandaag altijd
    eq("vandaag en morgen", av(listOf(Ch(setOf(0, 1)))), listOf(0, 1))
    eq("zonder gids alleen vandaag", av(listOf(Ch(emptySet()))), listOf(0))
    eq("zonder zenders alleen vandaag", av(emptyList()), listOf(0))
    eq("vandaag zonder programma's staat er toch", av(listOf(Ch(setOf(2, 3)))), listOf(0, 2, 3))
    eq("gat in de week", av(listOf(Ch(setOf(0, 1, 4)))), listOf(0, 1, 4))
    eq("dagen van meerdere zenders samen", av(listOf(Ch(setOf(0, 1)), Ch(setOf(2)))), listOf(0, 1, 2))

    // 2. gisteren en eerder: gids-info én terugkijken (van dezelfde zender, ver genoeg terug)
    eq("gisteren zonder terugkijken weg", av(listOf(Ch(setOf(-1, 0, 1)))), listOf(0, 1))
    eq("gisteren met terugkijken", av(listOf(Ch(setOf(-1, 0, 1), catchup = 1))), listOf(-1, 0, 1))
    eq("terugkijken zonder gids-info weg", av(listOf(Ch(setOf(0), catchup = 7))), listOf(0))
    eq("niet verder terug dan de terugkijkdagen", av(listOf(Ch(setOf(-3, -2, -1, 0), catchup = 2))), listOf(-2, -1, 0))
    eq("gids op de ene, terugkijken op de andere zender telt niet",
        av(listOf(Ch(setOf(-1, 0)), Ch(setOf(0), catchup = 3))), listOf(0))
    eq("foto build 165 (di 3 en wo 4 nov zonder gids weg)",
        av(listOf(Ch(setOf(0, 1)), Ch(setOf(0, 1)), Ch(setOf(0)))), listOf(0, 1))

    // 3. steekproef: in beeld eerst, dan de eerste zenders, max. 50, geen dubbele
    eq("kleine lijst helemaal", G.sample(3, emptyList()), listOf(0, 1, 2))
    eq("grote lijst max 50", G.sample(500, emptyList()).size, 50)
    eq("eerste 50", G.sample(500, emptyList()).last(), 49)
    eq("in beeld eerst", G.sample(500, listOf(120, 121)).take(3), listOf(120, 121, 0))
    eq("in beeld + rest = 50", G.sample(500, listOf(120, 121)).size, 50)
    eq("geen dubbele", G.sample(10, listOf(2, 3)), listOf(2, 3, 0, 1, 4, 5, 6, 7, 8, 9))
    eq("ongeldige indexen weg", G.sample(3, listOf(-1, 7, 1)), listOf(1, 0, 2))
    eq("lege lijst", G.sample(0, listOf(1)), emptyList<Int>())

    // 4. gekozen dag houden als hij er nog is, anders vandaag
    eq("dag blijft", G.keep(2, listOf(0, 1, 2)), 2)
    eq("dag weg = vandaag", G.keep(3, listOf(0, 1, 2)), 0)
    eq("gisteren weg = vandaag", G.keep(-1, listOf(0, 1)), 0)

    println("dagbalk: ${av(listOf(Ch(setOf(-1, 0, 1, 2), catchup = 1))).joinToString(" ")}")
    println(if (failures == 0) "OK: $checks controles geslaagd" else "MISLUKT: $failures van $checks controles")
    if (failures > 0) kotlin.system.exitProcess(1)
}
