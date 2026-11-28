package nl.lrvdlinden.bingr

// Build 194: groep Terugkijken in Live TV (Build194Logic.kt). Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/features/Build194Logic.kt tools/test/Build194LogicTest.kt -include-runtime -d /tmp/b194.jar
//   java -jar /tmp/b194.jar

private var failures = 0
private var checks = 0
private fun eq(name: String, got: Any?, want: Any?) {
    checks++
    if (got != want) { failures++; println("FOUT $name kreeg <$got>, verwacht <$want>"); println("::error title=Build194LogicTest::$name") }
}

private data class Ch(val id: String, val days: Int)

fun main() {
    val L = Build194Logic
    fun list(a: List<Ch>, f: List<Ch> = emptyList()) = L.channels(a, f, { it.id }, { it.days }).map { it.id }

    eq("dagen 0 = geen", L.hasCatchup(0), false)
    eq("dagen negatief = geen", L.hasCatchup(-1), false)
    eq("dagen 1 = wel", L.hasCatchup(1), true)

    eq("alleen zenders met terugkijken", list(listOf(Ch("a", 0), Ch("b", 3), Ch("c", 1))), listOf("b", "c"))
    eq("volgorde van de lijst blijft", list(listOf(Ch("z", 2), Ch("a", 5))), listOf("z", "a"))
    eq("geen zenders", list(emptyList()), emptyList<String>())
    eq("andere server erachter", list(listOf(Ch("a", 3)), listOf(Ch("x", 5))), listOf("a", "x"))
    eq("zonder terugkijken elders", list(listOf(Ch("a", 3)), listOf(Ch("x", 0))), listOf("a"))
    eq("dubbele zender eenmaal", list(listOf(Ch("a", 3), Ch("a", 3)), listOf(Ch("a", 3), Ch("x", 2))), listOf("a", "x"))
    eq("alleen elders", list(emptyList(), listOf(Ch("x", 2))), listOf("x"))

    eq("groep zichtbaar met zenders", L.showGroup(1), true)
    eq("groep verborgen zonder zenders", L.showGroup(0), false)
    eq("sleutel verschilt van andere groepen", L.GROUP_KEY !in setOf("★", "◷", "*", "⚑", "♫", "▣"), true)

    println("$checks controles, $failures fout(en)")
    if (failures > 0) kotlin.system.exitProcess(1)
}
