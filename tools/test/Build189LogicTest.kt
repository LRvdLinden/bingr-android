package nl.lrvdlinden.bingr

// Build 189: pincodevakjes en dubbele spoornamen (Build189Logic.kt). Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/features/Build189Logic.kt tools/test/Build189LogicTest.kt -include-runtime -d /tmp/b189.jar
//   java -jar /tmp/b189.jar

private var failures = 0
private var checks = 0
private fun eq(name: String, got: Any?, want: Any?) {
    checks++
    if (got != want) { failures++; println("FOUT $name kreeg <$got>, verwacht <$want>"); println("::error title=Build189LogicTest::$name") }
}

fun main() {
    val B = Build189Logic.PinBoxes
    eq("leeg vakje", B.cell("", 0, -1), "")
    eq("vakje voorbij de invoer", B.cell("48", 2, -1), "")
    eq("verborgen cijfer", B.cell("48", 0, 1), "•")
    eq("zichtbaar cijfer", B.cell("48", 1, 1), "8")
    eq("alles verborgen", B.cell("4821", 3, -1), "•")
    eq("buiten bereik", B.cell("4821", 4, 3), "")
    eq("negatief vakje", B.cell("4821", -1, 3), "")
    eq("actief vakje leeg", B.activeIndex(""), 0)
    eq("actief vakje twee", B.activeIndex("48"), 2)
    eq("actief vakje vol", B.activeIndex("4821"), -1)

    val T = Build189Logic.Tracks
    eq("geen dubbele", T.numberDuplicates(listOf("Dutch", "English")), listOf("Dutch", "English"))
    eq("dubbele genummerd", T.numberDuplicates(listOf("Polish", "Polish", "Polish")), listOf("Polish (1)", "Polish (2)", "Polish (3)"))
    eq("gemengd", T.numberDuplicates(listOf("Spanish", "Russian", "Spanish", "German")),
        listOf("Spanish (1)", "Russian", "Spanish (2)", "German"))
    eq("twee groepen", T.numberDuplicates(listOf("A", "B", "A", "B")), listOf("A (1)", "B (1)", "A (2)", "B (2)"))
    eq("lege lijst", T.numberDuplicates(emptyList()), emptyList<String>())
    eq("verschil in hoofdletters is een ander spoor", T.numberDuplicates(listOf("Polish", "polish")), listOf("Polish", "polish"))

    println("Build189LogicTest: $checks controles, $failures fout(en)")
    if (failures > 0) System.exit(1)
}
