package nl.lrvdlinden.bingr

// Build 195 (regels aangepast in build 196): Nieuwe aflevering in Verder kijken (Build195Logic.kt). Draaien zonder Android:
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
    // build 196: de regel "bij zijn" vervalt: een overgeslagen aflevering ervoor blokkeert de serie niet meer
    eq("overgeslagen aflevering ervoor geeft wel tegel", L.nextNew(s1b, seen("1-1", "1-3"), null), e(1, 4))
    eq("alleen een latere gekeken: volgende na de laatste", L.nextNew(season(1, 6), seen("1-4"), null), e(1, 5))
    eq("hele rij ervoor overgeslagen", L.nextNew(season(1, 6), seen("1-5"), null), e(1, 6))
    eq("niets na laatst gekeken: geen tegel", L.nextNew(s1b, seen("1-1", "1-4"), null), null)
    eq("niets na laatst gekeken, ervoor gaten: geen tegel", L.nextNew(s1b, seen("1-4"), null), null)
    eq("overgeslagen over seizoenen heen", L.nextNew(season(1, 3) + season(2, 3), seen("2-1"), null), e(2, 2))
    eq("tegel niet de overgeslagen aflevering", L.nextNew(s1b, seen("1-3"), null), e(1, 4))
    eq("laatst gekeken is special: telt niet", L.nextNew(s1 + e(0, 1), seen("0-1"), null), null)
    eq("special gekeken, gewone niet: geen tegel", L.nextNew(s1, seen("0-1"), null), null)
    eq("meerdere nieuwe: eerste", L.nextNew(season(1, 5), all, null), e(1, 4))
    eq("specials tellen niet", L.nextNew(s1 + e(0, 1), all, null), null)
    eq("special niet als tegel", L.nextNew(s1 + e(0, 9), all, null), null)
    eq("alles gezien", L.nextNew(s1b, seen("1-1", "1-2", "1-3", "1-4"), null), null)
    eq("eerder seizoen niet af: telt niet meer (build 196)", L.nextNew(season(1, 2) + season(2, 3), seen("2-1", "2-2"), null), e(2, 3))
    eq("eerder seizoen af", L.nextNew(season(1, 2) + season(2, 3), seen("1-1", "1-2", "2-1"), null), e(2, 2))
    eq("onvolgorde in invoer", L.nextNew(listOf(e(1, 4), e(1, 1), e(1, 3), e(1, 2)), all, null), e(1, 4))

    // handmatig weggehaald: pas weer bij een nieuwere aflevering
    eq("hidden waarde", L.hiddenValue(e(1, 4)), "1-4")
    eq("weggehaald: weg", L.nextNew(s1b, all, "1-4"), null)
    eq("weggehaald: terug na kijken", L.nextNew(season(1, 5), seen("1-1", "1-2", "1-3", "1-4"), "1-4"), e(1, 5))
    eq("weggehaald: oudere weggehaald, nu nieuwere", L.nextNew(season(1, 5), all, "1-3"), e(1, 4))

    // build 196: telling voor de banner (zelfde regel als de tegel)
    eq("telling: een nieuwe", L.newCount(s1b, all), 1)
    eq("telling: meer nieuwe", L.newCount(season(1, 6), all), 3)
    eq("telling: niets nieuws", L.newCount(s1, all), 0)
    eq("telling: niets gekeken", L.newCount(s1b, seen()), 0)
    eq("telling: specials niet", L.newCount(s1b + e(0, 1) + e(0, 2), all), 1)
    eq("telling: special gekeken telt niet als gekeken", L.newCount(s1, seen("0-1")), 0)
    eq("telling: overgeslagen ervoor telt niet mee", L.newCount(season(1, 6), seen("1-1", "1-4")), 2)
    eq("telling: over seizoenen", L.newCount(season(1, 3) + season(2, 3), seen("1-3")), 3)
    eq("telling: dubbelen", L.newCount(s1b + e(1, 4), all), 1)
    eq("telling gelijk aan tegel", L.newCount(season(1, 5), seen("1-2")) > 0 && L.nextNew(season(1, 5), seen("1-2"), null) == e(1, 3), true)

    // build 196: voortgang altijd vóór nieuwe-aflevering-tegels (item = naam, nieuw?, tijd)
    data class It(val n: String, val new: Boolean, val t: Long)
    val mixed = listOf(It("nieuw-recent", true, 900), It("voortgang-oud", false, 100), It("nieuw-oud", true, 50), It("voortgang-nieuw", false, 500))
    eq("volgorde: voortgang eerst, dan nieuw", L.order(mixed, { it.new }, { it.t }).map { it.n },
        listOf("voortgang-nieuw", "voortgang-oud", "nieuw-recent", "nieuw-oud"))
    eq("volgorde: nieuwe tegel met nieuwere datum nooit ervoor", L.order(listOf(It("n", true, 9_999), It("v", false, 1)), { it.new }, { it.t }).first().n, "v")
    eq("volgorde: alleen nieuw", L.order(listOf(It("a", true, 1), It("b", true, 2)), { it.new }, { it.t }).map { it.n }, listOf("b", "a"))
    eq("volgorde: leeg", L.order(emptyList<It>(), { it.new }, { it.t }), emptyList<It>())
    eq("volgorde: gelijk blijft stabiel", L.order(listOf(It("a", false, 5), It("b", false, 5)), { it.new }, { it.t }).map { it.n }, listOf("a", "b"))

    // lopende aflevering: geen Nieuwe-aflevering-tegel, voortgang blijft zoals hij was
    val ids = s1b.map { "id${it.number}" to it }
    val running = mapOf("id3" to 0.5)
    val before = running.toMap()
    eq("lopend: geen tegel", L.tile(ids, running, all, null), null)
    eq("lopend: voortgang onveranderd", running, before)
    eq("lopend (3 van 4 half): geen tegel ook zonder gezien", L.tile(ids, mapOf("id2" to 0.01), seen("1-1"), null), null)
    eq("bijna klaar telt niet als lopend", L.tile(ids, mapOf("id3" to 0.97), all, null), e(1, 4))
    eq("85-95% telt niet als lopend (drempel 85%, gelijk aan Apple)", L.tile(ids, mapOf("id3" to 0.9), all, null), e(1, 4))
    eq("net onder 85% loopt", L.tile(ids, mapOf("id4" to 0.84), all, null), null)
    eq("drempel", Build195Logic.RUNNING_MAX, 0.85)
    eq("overgeslagen aflevering half gekeken: serie houdt voortgangstegel", L.tile(ids, mapOf("id2" to 0.3), seen("1-1", "1-3"), null), null)
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
