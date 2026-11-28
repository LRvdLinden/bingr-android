package nl.lrvdlinden.bingr

// Build 193: betaaltrechter (Build193Logic.kt). Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/features/Build193Logic.kt tools/test/Build193LogicTest.kt -include-runtime -d /tmp/b193.jar
//   java -jar /tmp/b193.jar

private var failures = 0
private var checks = 0
private fun eq(name: String, got: Any?, want: Any?) {
    checks++
    if (got != want) { failures++; println("FOUT $name kreeg <$got>, verwacht <$want>"); println("::error title=Build193LogicTest::$name") }
}

fun main() {
    val L = Build193Logic

    eq("product jaar", L.product("year"), "year")
    eq("product hoofdletters", L.product(" Lifetime "), "lifetime")
    eq("product onbekend", L.product("lifetime49"), null)
    eq("product leeg", L.product(null), null)

    eq("fout leeg = netwerk", L.errorCode(null), "network")
    eq("fout spaties = netwerk", L.errorCode("  "), "network")
    eq("fout error = server", L.errorCode("error"), "server_error")
    eq("fout code blijft", L.errorCode("premium_not_configured"), "premium_not_configured")
    eq("fout hoofdletters", L.errorCode("Rate_Limited"), "rate_limited")
    eq("fout vrije tekst", L.errorCode("No internet connection."), "server_error")
    eq("fout met id", L.errorCode("cs_live_a1B2-c3"), "server_error")
    eq("fout te lang", L.errorCode("a".repeat(41)), "server_error")
    eq("fout 40 tekens", L.errorCode("a".repeat(40)), "a".repeat(40))

    eq("body getoond", L.body(L.PAYWALL_SHOWN, "android", 193),
        "{\"action\":\"funnel\",\"step\":\"paywall_shown\",\"platform\":\"android\",\"build\":193}")
    eq("body gestart met product", L.body(L.PURCHASE_STARTED, "androidtv", 193, "year"),
        "{\"action\":\"funnel\",\"step\":\"purchase_started\",\"platform\":\"androidtv\",\"build\":193,\"product\":\"year\"}")
    eq("body mislukt met fout", L.body(L.PURCHASE_FAILED, "android", 193, "month", "Whoops \"x\""),
        "{\"action\":\"funnel\",\"step\":\"purchase_failed\",\"platform\":\"android\",\"build\":193,\"product\":\"month\",\"error\":\"server_error\"}")
    eq("body onbekend product valt weg", L.body(L.PURCHASE_OK, "android", 193, "gold"),
        "{\"action\":\"funnel\",\"step\":\"purchase_ok\",\"platform\":\"android\",\"build\":193}")
    eq("body onbekende stap", L.body("bought", "android", 193), null)
    eq("body onbekend platform", L.body(L.PAYWALL_SHOWN, "ios", 193), null)
    eq("body zonder build", L.body(L.PAYWALL_SHOWN, "android", 0), null)

    val d = Build193Logic.Dedup(2_000)
    eq("eerste keer", d.shouldSend(L.PAYWALL_SHOWN, null, 1_000), true)
    eq("meteen opnieuw", d.shouldSend(L.PAYWALL_SHOWN, null, 1_500), false)
    eq("ander product", d.shouldSend(L.PURCHASE_STARTED, "year", 1_500), true)
    eq("zelfde product", d.shouldSend(L.PURCHASE_STARTED, "year", 2_000), false)
    eq("ander product telt apart", d.shouldSend(L.PURCHASE_STARTED, "lifetime", 2_000), true)
    eq("na het venster", d.shouldSend(L.PAYWALL_SHOWN, null, 3_100), true)

    val p = Build193Logic.Pending(60_000)
    eq("niets gestart", p.resolve(paid = true, returned = true, now = 0), Build193Logic.Outcome.NONE)
    p.start("year", 1_000)
    eq("nog niet terug", p.resolve(paid = false, returned = false, now = 2_000), Build193Logic.Outcome.NONE)
    eq("terug zonder betaling", p.resolve(paid = false, returned = true, now = 3_000), Build193Logic.Outcome.CANCELLED)
    eq("afgebroken maar één keer", p.resolve(paid = false, returned = true, now = 4_000), Build193Logic.Outcome.NONE)
    eq("betaling komt later binnen", p.resolve(paid = true, returned = false, now = 5_000), Build193Logic.Outcome.OK)
    eq("gelukt maar één keer", p.resolve(paid = true, returned = true, now = 6_000), Build193Logic.Outcome.NONE)
    eq("product onthouden", p.product, "year")
    p.start("lifetime", 10_000)
    eq("te lang geleden", p.resolve(paid = true, returned = true, now = 80_000), Build193Logic.Outcome.NONE)
    p.start("month", 100_000)
    eq("direct betaald", p.resolve(paid = true, returned = true, now = 101_000), Build193Logic.Outcome.OK)

    println("Build193LogicTest: $checks controles, $failures fouten")
    if (failures > 0) kotlin.system.exitProcess(1)
}
