package nl.lrvdlinden.bingr

// Build 195: Nieuwe aflevering in Verder kijken (Build195Logic.kt). Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/features/Build195Logic.kt tools/test/Build195LogicTest.kt -include-runtime -d /tmp/b195.jar
//   java -jar /tmp/b195.jar

private var failures = 0
private var checks = 0
private fun eq(name: String, got: Any?, want: Any?) {
    checks++
    if (got != want) { failures++; println("FOUT $name kreeg <$got>, verwacht <$want>"); println("::error title=Build195LogicTest::$name") }
}

fun main() {
    val L = Build195Logic
    fun e(s: Int, n: Int) = Build195Logic.Ep(s, n)
    fun season(s: Int, n: Int) = (1..n).map { e(s, it) }
    fun seen(vararg k: String): (Build195Logic.Ep) -> Boolean { val set = k.toSet(); return { it.key in set } }
    val s1 = season(1, 3)

    eq("parse", L.parse("2-10"), e(2, 10))
    eq("parse fout", L.parse("x"), null)
    eq("label", e(1, 5).label, "S1E5")
    eq("volgorde", L.ordered(listOf(e(2, 1), e(1, 2), e(1, 1), e(0, 1), e(1, 1))), listOf(e(1, 1), e(1, 2), e(2, 1)))

    // bij zijn: alles gezien, mark = laatste
    val all = seen("1-1", "1-2", "1-3")
    eq("bij", L.caughtUp(s1, all), true)
    eq("niet bij", L.caughtUp(s1, seen("1-1")), false)
    eq("leeg niet bij", L.caughtUp(emptyList(), all), false)
    eq("mark zet", L.nextMark(s1, all, null), "1-3")
    eq("mark blijft", L.nextMark(s1, seen("1-1"), "1-2"), "1-2")
    eq("mark nooit terug", L.nextMark(season(1, 2), seen("1-1", "1-2"), "1-3"), "1-3")

    // nieuwe aflevering erbij
    val s1b = season(1, 4)
    eq("nieuwe aflevering", L.nextNew(s1b, all, "1-3", null), e(1, 4))
    eq("nieuw seizoen", L.nextNew(s1 + e(2, 1), all, "1-3", null), e(2, 1))
    eq("geen mark = niets", L.nextNew(s1b, all, null, null), null)
    eq("niets nieuws", L.nextNew(s1, all, "1-3", null), null)
    eq("niets gezien", L.nextNew(s1b, seen(), "1-3", null), null)
    eq("achterstand is geen nieuw", L.nextNew(season(1, 6), seen("1-1", "1-2"), "1-6", null), null)
    eq("gat in gezien", L.nextNew(s1b, seen("1-1", "1-3"), "1-3", null), null)
    eq("meerdere nieuwe: eerste", L.nextNew(season(1, 5), all, "1-3", null), e(1, 4))
    eq("specials tellen niet", L.nextNew(s1 + e(0, 1), seen("1-1", "1-2", "1-3"), "1-3", null), null)
    eq("alles gezien incl. nieuwe", L.nextNew(s1b, seen("1-1", "1-2", "1-3", "1-4"), "1-3", null), null)
    eq("eerder seizoen niet af", L.nextNew(season(1, 2) + season(2, 3), seen("2-1", "2-2"), "2-2", null), null)

    // handmatig weggehaald
    val h = L.hiddenValue(s1b)
    eq("hidden waarde", h, "1-4")
    eq("weggehaald: weg", L.nextNew(s1b, all, "1-3", h), null)
    eq("weggehaald: terug bij nieuwere", L.nextNew(season(1, 5), all, "1-3", h), e(1, 4))
    eq("hidden leeg", L.hiddenValue(emptyList()), null)

    eq("titel normaal", L.titleKey("The Last of Us!"), "thelastofus")
    eq("prefkey profiel", L.prefKey("mark", "p1", "A B"), "ne195.mark.p1.ab")
    eq("prefkey main", L.prefKey("hid", null, "A"), "ne195.hid.main.a")

    println("Build195LogicTest: $checks controles, $failures fout")
    if (failures > 0) kotlin.system.exitProcess(1)
}
