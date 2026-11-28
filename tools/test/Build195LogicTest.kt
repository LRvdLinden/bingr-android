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

    val all = seen("1-1", "1-2", "1-3")
    val s1b = season(1, 4)
    eq("nieuwe aflevering", L.nextNew(s1b, all, null), e(1, 4))
    eq("nieuw seizoen", L.nextNew(s1 + e(2, 1), all, null), e(2, 1))
    eq("niets nieuws", L.nextNew(s1, all, null), null)
    eq("niets gezien", L.nextNew(s1b, seen(), null), null)
    eq("midden in de serie: volgende", L.nextNew(season(1, 6), seen("1-1", "1-2"), null), e(1, 3))
    eq("gat in gezien", L.nextNew(s1b, seen("1-1", "1-3"), null), null)
    eq("meerdere nieuwe: eerste", L.nextNew(season(1, 5), all, null), e(1, 4))
    eq("specials tellen niet", L.nextNew(s1 + e(0, 1), all, null), null)
    eq("special niet als tegel", L.nextNew(s1 + e(0, 9), all, null), null)
    eq("alles gezien", L.nextNew(s1b, seen("1-1", "1-2", "1-3", "1-4"), null), null)
    eq("eerder seizoen niet af", L.nextNew(season(1, 2) + season(2, 3), seen("2-1", "2-2"), null), null)
    eq("eerder seizoen af", L.nextNew(season(1, 2) + season(2, 3), seen("1-1", "1-2", "2-1"), null), e(2, 2))
    eq("onvolgorde in invoer", L.nextNew(listOf(e(1, 4), e(1, 1), e(1, 3), e(1, 2)), all, null), e(1, 4))

    // handmatig weggehaald: pas weer bij een nieuwere aflevering
    eq("hidden waarde", L.hiddenValue(e(1, 4)), "1-4")
    eq("weggehaald: weg", L.nextNew(s1b, all, "1-4"), null)
    eq("weggehaald: terug na kijken", L.nextNew(season(1, 5), seen("1-1", "1-2", "1-3", "1-4"), "1-4"), e(1, 5))
    eq("weggehaald: oudere weggehaald, nu nieuwere", L.nextNew(season(1, 5), all, "1-3"), e(1, 4))

    // lopende aflevering: geen Nieuwe-aflevering-tegel, voortgang blijft zoals hij was
    val ids = s1b.map { "id${it.number}" to it }
    val running = mapOf("id3" to 0.5)
    val before = running.toMap()
    eq("lopend: geen tegel", L.tile(ids, running, all, null), null)
    eq("lopend: voortgang onveranderd", running, before)
    eq("lopend (3 van 4 half): geen tegel ook zonder gezien", L.tile(ids, mapOf("id2" to 0.01), seen("1-1"), null), null)
    eq("bijna klaar telt niet als lopend", L.tile(ids, mapOf("id3" to 0.97), all, null), e(1, 4))
    eq("geen voortgang: tegel", L.tile(ids, emptyMap(), all, null), e(1, 4))
    eq("nul voortgang telt niet", L.tile(ids, mapOf("id4" to 0.0), all, null), e(1, 4))
    eq("lopende aflevering van een andere serie niet in lijst", L.tile(ids, mapOf("x" to 0.5), all, null), e(1, 4))
    eq("hasRunning", L.hasRunning(listOf(0.0, 0.5)), true)

    eq("titel normaal", L.titleKey("The Last of Us!"), "thelastofus")
    eq("prefkey profiel", L.prefKey("hid", "p1", "A B"), "ne195.hid.p1.ab")
    eq("prefkey main", L.prefKey("hid", null, "A"), "ne195.hid.main.a")

    println("Build195LogicTest: $checks controles, $failures fout")
    if (failures > 0) kotlin.system.exitProcess(1)
}
