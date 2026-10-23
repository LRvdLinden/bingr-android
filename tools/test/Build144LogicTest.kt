package nl.lrvdlinden.bingr

// Build 144: unit-tests van features/Build144Logic.kt (Verder kijken: welke poster; automatisch verversen pas na het
// openen). Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/meta/TitleCleaner.kt app/src/main/java/nl/lrvdlinden/bingr/features/Build144Logic.kt \
//     tools/test/Build144LogicTest.kt -include-runtime -d /tmp/b144.jar
//   java -jar /tmp/b144.jar
// De workflow Build doet dit vóór het bouwen van de APK; een fout laat de build mislukken.
// Zelfde gevallen als tizen/test/build144_test.js.

private var failures = 0
private var checks = 0
private fun eq(name: String, got: Any?, want: Any?) {
    checks++
    if (got != want) { failures++; println("FOUT $name kreeg <$got>, verwacht <$want>") }
}

fun main() {
    val C = Build144Logic.ContinueCover
    fun W(t: String, y: Int?, show: Boolean) = Build144Logic.ContinueCover.Want(t, y, show)

    // MARK: sleutel (gelijk aan Apple en Samsung TV)
    eq("sleutel: film met jaar uit de titel", C.key("NL - The Batman (2022) [4K]", null, false), "m|the batman|2022")
    eq("sleutel: jaar uit de bibliotheek gaat voor", C.key("The Batman (2022)", 2021, false), "m|the batman|2021")
    eq("sleutel: serie zonder jaar", C.key("Breaking Bad", null, true), "s|breaking bad|0")
    eq("sleutel: film en serie verschillen", C.key("Dune", 2021, false) == C.key("Dune", 2021, true), false)

    // MARK: volgorde: bibliotheek/catalogmeta → TMDB → bewaard beeld
    val lib = "https://srv/poster.jpg"; val meta = "https://srv/meta.jpg"
    val tmdb = "https://image.tmdb.org/t/p/w342/x.jpg"; val still = "https://srv/still.jpg"
    eq("volgorde: bibliotheek eerst", C.pick(lib, meta, tmdb, still), lib)
    eq("volgorde: dan catalogmeta", C.pick(null, meta, tmdb, still), meta)
    eq("volgorde: dan TMDB", C.pick(null, null, tmdb, still), tmdb)
    eq("volgorde: pas dan het bewaarde beeld", C.pick(null, null, null, still), still)
    eq("volgorde: niets", C.pick(null, null, null, null), null)
    eq("volgorde: lege teksten tellen niet", C.pick("  ", "", "", still), still)
    eq("volgorde: meta = bewaard beeld is geen poster (TMDB gaat voor)", C.pick(null, still, tmdb, still), tmdb)
    eq("volgorde: meta = bewaard beeld, zonder TMDB het beeld", C.pick(null, still, null, still), still)
    eq("opzoeken: niet met bibliotheekposter", C.needsLookup(lib, null, still), false)
    eq("opzoeken: niet met echte catalogmeta", C.needsLookup(null, meta, still), false)
    eq("opzoeken: wel zonder poster", C.needsLookup(null, null, still), true)
    eq("opzoeken: wel als meta het bewaarde beeld is", C.needsLookup(null, still, still), true)

    // MARK: wat wordt opgezocht
    val wants = listOf(W("Dune (2021)", null, false), W("Dune", 2021, false), W("Breaking Bad", null, true),
        W("  ", null, false), W("Known", 1999, false))
    val known = setOf(C.key("Known", 1999, false))
    val inFlight = setOf(C.key("Breaking Bad", null, true))
    eq("pending: dubbel één keer, bekend en onderweg niet, lege titel niet",
        C.pending(wants, { it in known }, inFlight).map { it.first }, listOf("m|dune|2021"))
    eq("pending: hooguit 12", C.pending((0 until 30).map { W("Film $it", 2000, false) }, { false }, emptySet()).size, 12)
    eq("query: schone titel en jaar", C.query(W("NL - Dune (2021) HD", null, false)), "Dune" to 2021)

    // MARK: automatisch verversen pas na het openen
    val A = Build144Logic.AutoRefresh
    eq("auto: niet bij het openen", A.mayStart(0, true, false), false)
    eq("auto: niet na 44 s", A.mayStart(44_000, true, false), false)
    eq("auto: na 45 s", A.mayStart(45_000, true, false), true)
    eq("auto: niet in profielkeuze/pincodescherm", A.mayStart(120_000, false, false), false)
    eq("auto: niet tijdens afspelen", A.mayStart(120_000, true, true), false)
    eq("auto: wachten tot 45 s", A.waitMs(10_000), 35_000L)
    eq("auto: daarna elke 5 s kijken", A.waitMs(90_000), 5_000L)
    eq("auto: zelfde server en profiel", A.stillCurrent(3, 3), true)
    eq("auto: andere server of profiel stopt", A.stillCurrent(3, 4), false)
    eq("auto: andere servers pas na een minuut van het netwerk", A.OTHERS_NETWORK_AFTER_MS >= A.SETTLE_MS, true)

    if (failures > 0) {
        println("$checks controles, $failures fout")
        kotlin.system.exitProcess(1)
    }
    println("Build 144: alle $checks controles geslaagd")
}
