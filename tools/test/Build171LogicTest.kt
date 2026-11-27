package nl.lrvdlinden.bingr

// Build 171: Kijk je nog?, verborgen zenders en geblokkeerde titels, landen uit voorvoegsels, radio, apparatenlijst en
// het schoonmaken van crashrapporten. Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/features/Build149Logic.kt app/src/main/java/nl/lrvdlinden/bingr/features/Build150Logic.kt \
//     app/src/main/java/nl/lrvdlinden/bingr/features/Build171Logic.kt tools/test/Build171LogicTest.kt -include-runtime -d /tmp/b171.jar
//   java -jar /tmp/b171.jar

private var failures = 0
private var checks = 0
private fun eq(name: String, got: Any?, want: Any?) {
    checks++
    if (got != want) { failures++; println("FOUT $name kreeg <$got>, verwacht <$want>"); println("::error title=Build171LogicTest::$name") }
}

fun main() {
    // 1. Kijk je nog?
    val W = Build171Logic.StillWatching
    var s = W.touch(Build171Logic.StillWatching.State(), 1_000L)
    eq("vers: geen vraag", W.ask(s, 2_000L, true, 3, Build171Logic.StillWatching.Kind.EPISODE), null)
    s = W.started(s, auto = true, now = 2_000L); s = W.started(s, true, 3_000L)
    eq("2 automatisch: nog niet", W.ask(s, 3_000L, true, 3, Build171Logic.StillWatching.Kind.EPISODE), null)
    s = W.started(s, true, 4_000L)
    eq("3 automatisch: vraag", W.ask(s, 4_000L, true, 3, Build171Logic.StillWatching.Kind.EPISODE), Build171Logic.StillWatching.Reason.EPISODES)
    eq("uit: nooit", W.ask(s, 4_000L, false, 3, Build171Logic.StillWatching.Kind.EPISODE), null)
    eq("bediening tussendoor reset", W.ask(W.touch(s, 5_000L), 5_000L, true, 3, Build171Logic.StillWatching.Kind.EPISODE), null)
    eq("met de hand gestart = bediening", W.started(s, false, 6_000L).autoEpisodes, 0)
    val live = W.touch(Build171Logic.StillWatching.State(), 0L).copy(lastInteraction = 10_000L)
    eq("live 2u59", W.ask(live, 10_000L + 3 * 3_600_000L - 1, true, 3, Build171Logic.StillWatching.Kind.LIVE), null)
    eq("live 3u", W.ask(live, 10_000L + 3 * 3_600_000L, true, 3, Build171Logic.StillWatching.Kind.LIVE), Build171Logic.StillWatching.Reason.TIME)
    eq("film 2u", W.ask(live, 10_000L + 2 * 3_600_000L, true, 2, Build171Logic.StillWatching.Kind.MOVIE), Build171Logic.StillWatching.Reason.TIME)
    eq("aflevering niet op tijd", W.ask(live, 10_000L + 5 * 3_600_000L, true, 3, Build171Logic.StillWatching.Kind.EPISODE), null)
    eq("ongeldige uren = 3", W.hours(7), 3)
    eq("4 uur", W.hours(4), 4)
    eq("30 s over", W.secondsLeft(0L, 0L), 30)
    eq("1 s over", W.secondsLeft(0L, 29_500L), 1)
    eq("verlopen", W.expired(0L, 30_000L), true)
    eq("niet verlopen", W.expired(0L, 29_999L), false)

    // 15/20. sets per profiel
    val P = Build171Logic.ProfileSets
    eq("zendersleutel", P.channelKey("ABC-1", "x12"), "abc-1|x12")
    eq("titelsleutel film", P.titleKey("ABC", false, "xv9"), "abc|movie:xv9")
    eq("titelsleutel serie", P.titleKey("ABC", true, "xs9"), "abc|series:xs9")
    eq("parse", P.parseTitle("ABC|series:xs9"), Build171Logic.ProfileSets.TitleRef("abc", true, "xs9"))
    eq("parse ongeldig", P.parseTitle("abc|xs9"), null)
    eq("ids per server", P.channelIDs(setOf("abc|x1", "ABC|x2", "def|x3"), "Abc"), setOf("x1", "x2"))
    eq("titels per server", P.titleIDs(setOf("abc|movie:1", "abc|series:2", "d|movie:3"), "abc"), setOf("1") to setOf("2"))
    eq("aan", P.toggle(emptySet(), "ABC|x1", true), setOf("abc|x1"))
    eq("uit (hoofdletters)", P.toggle(setOf("ABC|x1", "abc|x2"), "abc|x1", false), setOf("abc|x2"))
    eq("bevat", P.contains(setOf("ABC|x1"), "abc|x1"), true)
    val list = listOf("x1" to null, "x2" to "OTHER", "x3" to null)
    eq("filter", P.filter(list, setOf("act|x1", "other|x2"), "act", { it.second }, { it.first }, P::channelKey).map { it.first }, listOf("x3"))
    eq("vastgepind eerst", P.pinnedFirst(listOf("a", "b", "c"), setOf("s|c"), { "s|$it" }), listOf("c", "a", "b"))

    // 17. landen
    val C = Build171Logic.Countries
    eq("NL |", C.of("NL | Sport"), "NL")
    eq("pipes", C.of("┃BE┃ Films"), "BE")
    eq("haken", C.of("Sport (DE)"), "DE")
    eq("GB = UK", C.of("GB: News"), "UK")
    eq("geen land", C.of("Actie"), null)
    eq("chips", C.chips(listOf("NL | A", "NL | B", "DE | C", "XX", "SE | D")), listOf("NL", "DE", "SE"))
    eq("matches", C.matches("NL | Sport", "NL"), true)
    eq("matches alle", C.matches("Sport", null), true)

    // 30. radio
    val R = Build171Logic.Radio
    eq("Radio NL", R.isRadioGroup("Radio NL"), true)
    eq("NL | RADIO", R.isRadioGroup("NL | RADIO"), true)
    eq("Radiozenders", R.isRadioGroup("Radiozenders"), true)
    eq("Radios", R.isRadioGroup("UK Radios"), true)
    eq("Radiohead", R.isRadioGroup("Radiohead concerts"), false)
    eq("Rádio", R.isRadioGroup("PT | Rádio"), true)
    eq("Radyo", R.isRadioGroup("TR Radyo"), true)
    eq("Sport", R.isRadioGroup("NL Sport"), false)
    eq("vlag", R.isRadio(true, "NL"), true)
    eq("volgorde", R.order(listOf("b" to 2, "a" to 1, "f" to 9), { it.first == "f" }, { it.second }, { it.first }).map { it.first }, listOf("f", "a", "b"))

    // 31. apparaten
    val D = Build171Logic.DeviceList
    val day = 86_400_000L
    val now = 100 * day
    val devs = listOf(Triple("a", now - day, false), Triple("me", now - 50 * day, true), Triple("b", now - 20 * day, false), Triple("c", null, false))
    eq("sorteren", D.sortStale(devs, { it.second }, { it.third }).map { it.first }, listOf("c", "b", "a", "me"))
    eq("voorstel", D.suggestion(devs, { it.second }, { it.third }, now)?.first, "c")
    eq("geen voorstel", D.suggestion(listOf(Triple("a", now - day, false)), { it.second }, { it.third }, now), null)
    eq("zojuist", D.relative(now - 10_000L, now), "just now" to 0)
    eq("minuten", D.relative(now - 5 * 60_000L, now), "%d min ago" to 5)
    eq("uren", D.relative(now - 3 * 3_600_000L, now), "%d hours ago" to 3)
    eq("gisteren", D.relative(now - 30 * 3_600_000L, now), "yesterday" to 1)
    eq("dagen", D.relative(now - 5 * day, now), "%d days ago" to 5)
    eq("datum", D.relative(now - 40 * day, now), null)
    eq("icoon tv", D.iconKind("Android TV"), "tv")
    eq("icoon tablet", D.iconKind("iPad"), "tablet")
    eq("icoon telefoon", D.iconKind("Android"), "phone")

    // 37. crashrapporten
    val K = Build171Logic.Crash
    eq("url", K.scrub("open http://host.tv:8080/live/user/pass/1.ts mislukt"), "open <url> mislukt")
    eq("host/pad", K.scrub("at line.example.com/get.php?username=a&password=b"), "at <url>")
    eq("xtream-pad", K.scrub("open /live/jan/geheim/123.ts"), "open <url>")
    eq("gebruiker@host", K.scrub("jan:geheim@tv.host:8080 down"), "<url> down")
    eq("sleutel=waarde", K.scrub("failed password=abc&x"), "failed password=<redacted>&x")
    eq("query", K.scrub("load ?a=1&b=2"), "load <query>")
    eq("jwt", K.scrub("bearer eyJhbGc.eyJzdWIi.sig"), "bearer <token>")
    eq("e-mail", K.scrub("user a.b@c.nl"), "user <email>")
    eq("ip", K.scrub("connect 192.168.1.20:80 failed"), "connect <ip> failed")
    eq("token", K.scrub("key eyJhbGciOiJIUzI1NiJ9abc123def456"), "key <token>")
    eq("klasse blijft", K.scrub("at nl.lrvdlinden.bingr.PlayerController.start(PlayerController.kt:12)"),
        "at nl.lrvdlinden.bingr.PlayerController.start(PlayerController.kt:12)")
    eq("message max", K.message("x".repeat(900)).length, 500)
    eq("stack max", K.stack("é".repeat(20_000)).toByteArray().size <= 16 * 1024, true)
    eq("frames", K.frames("java.lang.X: y\n\tat nl.lrvdlinden.bingr.A.b(A.kt:1)\n\tat java.C.d(C.java:2)"), listOf("nl.lrvdlinden.bingr.A.b", "java.C.d"))
    val sig1 = K.signature("crash", "java.lang.IllegalStateException: a", "\tat nl.lrvdlinden.bingr.A.b(A.kt:1)")
    val sig2 = K.signature("crash", "java.lang.IllegalStateException: b", "\tat nl.lrvdlinden.bingr.A.b(A.kt:99)")
    eq("signatuur zonder regels", sig1, sig2)
    eq("signatuur leesbaar", sig1, "java.lang.IllegalStateException @ nl.lrvdlinden.bingr.A.b")
    val r = Build171Logic.Crash.Report("crash", sig1, "m", "s", now, build = 171)
    var q = K.enqueue(emptyList(), r, now)
    q = K.enqueue(q, r.copy(at = now + 1), now + 1)
    eq("ontdubbeld", q.size to q[0].count, 1 to 2)
    eq("oud weg", K.enqueue(q, r.copy(signature = "x", at = now + 8 * day), now + 8 * day).size, 1)
    eq("20 per uur", K.allowed(List(20) { now - it * 1000L }, now), false)
    eq("19 per uur", K.allowed(List(19) { now - it * 1000L }, now), true)
    eq("404 later", K.outcome(404), Build171Logic.Crash.Outcome.RETRY)
    eq("netwerk later", K.outcome(null), Build171Logic.Crash.Outcome.RETRY)
    eq("200 klaar", K.outcome(200), Build171Logic.Crash.Outcome.SENT)
    eq("400 weg", K.outcome(400, "bad_report"), Build171Logic.Crash.Outcome.DROP)
    eq("400 oude server", K.outcome(400, "unknown_action"), Build171Logic.Crash.Outcome.RETRY)
    eq("413 weg", K.outcome(413), Build171Logic.Crash.Outcome.DROP)

    println("build171: $checks controles")
    if (failures > 0) { println("Build171LogicTest: $failures van $checks MISLUKT"); kotlin.system.exitProcess(1) }
    println("Build171LogicTest: alle $checks controles geslaagd")
}
