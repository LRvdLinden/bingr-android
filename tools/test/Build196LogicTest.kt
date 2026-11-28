package nl.lrvdlinden.bingr

// Build 196: verversen van de nieuwe afleveringen (Build196Logic.kt). Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/features/Build196Logic.kt tools/test/Build196LogicTest.kt -include-runtime -d /tmp/b196.jar
//   java -jar /tmp/b196.jar

private var failures = 0
private var checks = 0
private fun eq(name: String, got: Any?, want: Any?) {
    checks++
    if (got != want) { failures++; println("FOUT $name kreeg <$got>, verwacht <$want>"); println("::error title=Build196LogicTest::$name") }
}

fun main() {
    val L = Build196Logic
    val H = 3_600_000L
    val now = 100 * H

    eq("2 uur = 7200 s", L.REFRESH_MS, 7_200_000L)
    eq("gelijktijdigheid 3", L.CONCURRENCY, 3)
    eq("nooit opgehaald: aan de beurt", L.isDue(now, null, null), true)
    eq("net opgehaald: niet", L.isDue(now, now - 1000, null), false)
    eq("1u59 geleden: niet", L.isDue(now, now - 2 * H + 60_000, null), false)
    eq("precies 2 uur: wel", L.isDue(now, now - 2 * H, null), true)
    eq("3 uur geleden: wel", L.isDue(now, now - 3 * H, null), true)
    eq("was 6 uur nodig? nee, 2 uur is genoeg", L.isDue(now, now - 2 * H - 1, null), true)
    // mislukt: laatste goede resultaat blijft, opnieuw na 10 minuten
    eq("mislukt zonder eerder resultaat: nog niet meteen weer", L.isDue(now, null, now - 60_000), false)
    eq("mislukt: na 10 min weer", L.isDue(now, null, now - 10 * 60_000), true)
    eq("goed maar verouderd en net mislukt: wacht", L.isDue(now, now - 5 * H, now - 60_000), false)
    eq("goed maar verouderd, mislukking lang geleden: weer", L.isDue(now, now - 5 * H, now - 20 * 60_000), true)
    eq("mislukt maar goed nog vers: niet", L.isDue(now, now - H, now - 20 * 60_000), false)

    // geen limiet: 500 series zijn allemaal aan de beurt
    val many = (1..500).map { "s|$it" }
    eq("geen limiet op aantal", many.count { L.isDue(now, null, null) }, 500)
    eq("dubbelen eruit, rest blijft", L.unique(many + many.take(10)) { it }.size, 500)

    // volgende controle terwijl de app open staat
    eq("geen series: niets te doen", L.nextDelay(now, emptyList()), null)
    eq("over 30 min", L.nextDelay(now, listOf(L.dueAt(now - 90 * 60_000, null))), 30 * 60_000L)
    eq("kortste telt", L.nextDelay(now, listOf(L.dueAt(now - 30 * 60_000, null), L.dueAt(now - 100 * 60_000, null))), 20 * 60_000L)
    eq("al verlopen: minstens een minuut", L.nextDelay(now, listOf(L.dueAt(now - 5 * H, null))), L.MIN_WAIT_MS)
    eq("nooit hoger dan 2 uur", L.nextDelay(now, listOf(now + 10 * H)), L.REFRESH_MS)
    eq("na mislukking 10 min", L.nextDelay(now, listOf(L.dueAt(null, now))), 10 * 60_000L)

    println("Build196LogicTest: $checks controles, $failures fout")
    if (failures > 0) kotlin.system.exitProcess(1)
}
