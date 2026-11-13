package nl.lrvdlinden.bingr

// Build 169: doorspoelen via de tijdbalk op Android TV (stappen, versnelling, begrenzen, verschiltekst, vervallen).
// Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/features/Build169Logic.kt tools/test/Build169LogicTest.kt \
//     -include-runtime -d /tmp/b169.jar
//   java -jar /tmp/b169.jar

private var failures = 0
private var checks = 0
private fun eq(name: String, got: Any?, want: Any?) {
    checks++
    if (got != want) { failures++; println("FOUT $name kreeg <$got>, verwacht <$want>"); println("::error title=Build169LogicTest::$name") }
}

fun main() {
    val S = Build169Logic.Scrub
    val L = Build169Logic.Label

    // 1. reeks: ingedrukt houden of snel herhalen gaat door, anders opnieuw
    eq("eerste druk", S.streak(0, null, 1_000, 0), 0)
    eq("snel herhalen", S.streak(0, 1_000, 1_300, 0), 1)
    eq("precies op de grens", S.streak(3, 1_000, 1_450, 0), 4)
    eq("te traag = opnieuw", S.streak(5, 1_000, 1_451, 0), 0)
    eq("ingedrukt houden", S.streak(7, null, 9_999, 3), 8)
    eq("andere richting = opnieuw", S.streak(7, 1_000, 1_100, 2, sameDirection = false), 0)
    eq("klok terug = opnieuw", S.streak(4, 2_000, 1_000, 0), 0)

    // 2. versnelling: 10 s, daarna 30 s, daarna 60 s
    eq("stap 0", S.step(0), 10.0)
    eq("stap 3", S.step(3), 10.0)
    eq("stap 4", S.step(4), 30.0)
    eq("stap 9", S.step(9), 30.0)
    eq("stap 10", S.step(10), 60.0)
    eq("stap 500", S.step(500), 60.0)
    // 12 keer ingedrukt houden vanaf 0: 4×10 + 6×30 + 2×60 = 340
    var p = 0.0; var st = 0
    for (i in 0 until 12) { p = S.move(p, 1, st, 7_200.0); st = S.streak(st, null, 0, 1) }
    eq("versnelde reeks", p, 340.0)

    // 3. begrenzen op 0…duur
    eq("terug voorbij 0", S.move(5.0, -1, 0, 3_600.0), 0.0)
    eq("vooruit voorbij het eind", S.move(3_590.0, 1, 10, 3_600.0), 3_600.0)
    eq("gewoon vooruit", S.move(100.0, 1, 0, 3_600.0), 110.0)
    eq("gewoon terug", S.move(100.0, -1, 4, 3_600.0), 70.0)
    eq("richting begrensd", S.move(100.0, 5, 0, 3_600.0), 110.0)
    eq("duur onbekend", S.move(100.0, 1, 0, 0.0), 0.0)
    eq("duur NaN", S.clamp(10.0, Double.NaN), 0.0)
    eq("positie NaN", S.clamp(Double.NaN, 100.0), 0.0)
    eq("fractie", S.fraction(900.0, 3_600.0), 0.25)
    eq("fractie voorbij", S.fraction(9_000.0, 3_600.0), 1.0)
    eq("fractie zonder duur", S.fraction(10.0, 0.0), 0.0)
    eq("seconden", S.seconds(0.5, 3_600.0), 1_800.0)
    eq("seconden begrensd", S.seconds(1.5, 100.0), 100.0)

    // 4. vervallen na 5 s zonder focus
    eq("focus op de balk", S.expired(null, 99_999), false)
    eq("net weg", S.expired(10_000, 14_999), false)
    eq("5 s weg", S.expired(10_000, 15_000), true)

    // 5. teksten boven de kop
    eq("tijd", L.time(754.0), "12:34")
    eq("tijd uur", L.time(3_754.0), "1:02:34")
    eq("tijd ongeldig", L.time(-1.0), "0:00")
    eq("vooruit", L.delta(400.0, 130.0), "+4:30")
    eq("terug (minteken)", L.delta(60.0, 130.0), "−1:10")
    eq("geen verschil", L.delta(130.4, 130.0), "0:00")
    eq("ruim een uur", L.delta(3_800.0, 0.0), "+1:03:20")
    eq("ongeldig", L.delta(Double.NaN, 1.0), "0:00")
    eq("bubbel", L.bubble(400.0, 130.0), "6:40  +4:30")

    println("scrub: $checks controles")
    if (failures > 0) { println("Build169LogicTest: $failures van $checks MISLUKT"); kotlin.system.exitProcess(1) }
    println("Build169LogicTest: alle $checks controles geslaagd")
}
