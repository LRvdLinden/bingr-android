package nl.lrvdlinden.bingr

// Build 145: unit-tests van features/Build145Logic.kt ("Servers laden bij openen": Vragen / Altijd / Nooit, alleen
// handmatig; de vraag "Servers bijwerken?"; volgorde van het bijwerken). Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/features/Build145Logic.kt tools/test/Build145LogicTest.kt \
//     -include-runtime -d /tmp/b145.jar
//   java -jar /tmp/b145.jar
// De workflow Build doet dit vóór het bouwen van de APK; een fout laat de build mislukken.
// Zelfde gevallen als tizen/test/build145_test.js (en de webspeler: web/js/xs.js, X.loadOnOpen).

private var failures = 0
private var checks = 0
private fun eq(name: String, got: Any?, want: Any?) {
    checks++
    if (got != want) { failures++; println("FOUT $name kreeg <$got>, verwacht <$want>") }
}

fun main() {
    val L = Build145Logic.LoadOnOpen
    val ASK = Build145Logic.LoadOnOpen.Mode.ASK; val ALWAYS = Build145Logic.LoadOnOpen.Mode.ALWAYS
    val NEVER = Build145Logic.LoadOnOpen.Mode.NEVER
    val A_ASK = Build145Logic.LoadOnOpen.Action.ASK; val A_UPDATE = Build145Logic.LoadOnOpen.Action.UPDATE_ALL
    val A_NOTHING = Build145Logic.LoadOnOpen.Action.NOTHING

    // MARK: stand op het apparaat
    eq("stand: niets = Vragen", L.current(null), ASK)
    eq("stand: leeg = Vragen", L.current(""), ASK)
    eq("stand: ask", L.current("ask"), ASK)
    eq("stand: always", L.current("always"), ALWAYS)
    eq("stand: never", L.current("never"), NEVER)
    eq("stand: met spaties", L.current(" never "), NEVER)
    eq("stand: oude termijn telt niet (daily)", L.current("daily"), ASK)
    eq("stand: onbekend = Vragen", L.current("sometimes"), ASK)
    eq("ruwe waarden gelijk op alle apps", Build145Logic.LoadOnOpen.Mode.entries.map { it.raw }, listOf("ask", "always", "never"))

    // MARK: eenmalige omzetting van de oude vernieuwtermijn
    eq("omzetten: niets ingesteld → Vragen", L.migrate(null), ASK)
    for (old in listOf("manual", "launch", "sixHours", "daily", "weekly")) eq("omzetten: oud $old → Vragen", L.migrate(old), ASK)
    eq("omzetten: al Vragen → niets", L.migrate("ask"), null)
    eq("omzetten: al Altijd → niets", L.migrate("always"), null)
    eq("omzetten: al Nooit → niets", L.migrate("never"), null)

    // MARK: wat na het kiezen van een profiel
    eq("Vragen, bewaarde lijst, volwassene: vraag", L.decide(ASK, hasSavedList = true, kids = false, askedThisLaunch = false), A_ASK)
    eq("Vragen: één keer per keer openen", L.decide(ASK, true, false, true), A_NOTHING)
    eq("Vragen, kinderprofiel: Later", L.decide(ASK, true, true, false), A_NOTHING)
    eq("Vragen, geen bewaarde lijst: geen vraag (laadt zelf)", L.decide(ASK, false, false, false), A_NOTHING)
    eq("Altijd: meteen bijwerken", L.decide(ALWAYS, true, false, false), A_UPDATE)
    eq("Altijd, kinderprofiel: ook bijwerken", L.decide(ALWAYS, true, true, false), A_UPDATE)
    eq("Altijd, na een profielwissel: ook", L.decide(ALWAYS, true, false, true), A_UPDATE)
    eq("Altijd, geen bewaarde lijst: de andere ook", L.decide(ALWAYS, false, false, false), A_UPDATE)
    eq("Nooit: niets", L.decide(NEVER, true, false, false), A_NOTHING)
    eq("Nooit, geen bewaarde lijst: niets extra", L.decide(NEVER, false, false, false), A_NOTHING)
    eq("Nooit, kinderprofiel: niets", L.decide(NEVER, true, true, false), A_NOTHING)

    // MARK: volgorde van het bijwerken
    val ids = listOf("A1", "B2", "C3")
    eq("volgorde: open server eerst", L.order(ids, "B2"), listOf("B2", "A1", "C3"))
    eq("volgorde: open server zonder op hoofdletters te letten", L.order(ids, "b2"), listOf("B2", "A1", "C3"))
    eq("volgorde: geen open server", L.order(ids, null), ids)
    eq("volgorde: onbekende open server", L.order(ids, "X9"), ids)
    eq("volgorde: al bijgewerkt overslaan", L.order(ids, "B2", setOf("b2")), listOf("A1", "C3"))
    eq("volgorde: alles al bijgewerkt", L.order(ids, "A1", setOf("a1", "b2", "c3")), emptyList<String>())
    eq("volgorde: dubbel één keer", L.order(listOf("A1", "a1", "B2"), null), listOf("A1", "B2"))
    eq("volgorde: leeg", L.order(emptyList(), "A1"), emptyList<String>())

    // MARK: wanneer de volgende server
    eq("verder: hoofdscherm, niets speelt", L.mayRun(onMain = true, playing = false), true)
    eq("wachten: tijdens het afspelen", L.mayRun(true, true), false)
    eq("wachten: Wie kijkt er? / pincodescherm", L.mayRun(false, false), false)
    eq("stoppen: ander profiel of andere server", L.stillCurrent(3, 4), false)
    eq("verder: zelfde keuze", L.stillCurrent(3, 3), true)

    if (failures > 0) {
        println("$checks controles, $failures fout")
        kotlin.system.exitProcess(1)
    }
    println("Build 145: alle $checks controles geslaagd")
}
