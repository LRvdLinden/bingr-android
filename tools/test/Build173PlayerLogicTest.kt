package nl.lrvdlinden.bingr

import nl.lrvdlinden.bingr.Build173PlayerLogic.Primary as P
import nl.lrvdlinden.bingr.Build173PlayerLogic.Sleep as S
import nl.lrvdlinden.bingr.Build173PlayerLogic.TraktFailure as T
import nl.lrvdlinden.bingr.Build173PlayerLogic.OsOnResponse as O
import nl.lrvdlinden.bingr.Build173PlayerLogic.OsLogin as G

// Build 173: bediening in de speler en betrouwbare Trakt/OpenSubtitles (Build173PlayerLogic.kt). Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/features/Build173PlayerLogic.kt tools/test/Build173PlayerLogicTest.kt -include-runtime -d /tmp/b173p.jar
//   java -jar /tmp/b173p.jar

private var failures = 0
private var checks = 0
private fun eq(name: String, got: Any?, want: Any?) {
    checks++
    if (got != want) { failures++; println("FOUT $name kreeg <$got>, verwacht <$want>"); println("::error title=Build173PlayerLogicTest::$name") }
}
private fun ok(name: String, v: Boolean) = eq(name, v, true)
private fun no(name: String, v: Boolean) = eq(name, v, false)

fun main() {
    // build 179 (X1): eerst Sleep.Off aanraken (zoals PlayerController), daarna mag er geen null in de keuzes staan
    val off: S = S.Off
    eq("slaaptimer Off eerst", off.code, "off")
    no("slaaptimer keuzes zonder null", (S.choices as List<Any?>).contains(null))
    ok("slaaptimer keuzes met Off", S.choices.contains(S.Off))
    eq("slaaptimer keuzes", S.choices.map { it.code }, listOf("episode", "m30", "m60", "m90", "off"))
    val L = Build173PlayerLogic
    val now = 1_800_000_000_000L
    eq("primair intro", L.primary(true, true, false, false, 0.0), P.SKIP_INTRO)
    eq("primair volgende", L.primary(false, true, false, false, 0.0), P.NEXT_EPISODE)
    eq("primair terug naar live", L.primary(false, false, true, true, 120.0), P.GO_LIVE)
    eq("primair vanaf begin", L.primary(false, false, true, true, 0.0), P.START_OVER)
    eq("primair live zonder terugkijken", L.primary(false, false, true, false, 0.0), null)
    eq("primair film", L.primary(false, false, false, true, 0.0), null)
    ok("vanaf begin 10 min", L.canStartOver(true, now - 600_000, now, 0.0))
    no("vanaf begin zonder terugkijken", L.canStartOver(false, now - 600_000, now, 0.0))
    no("vanaf begin net begonnen", L.canStartOver(true, now - 30_000, now, 0.0))
    no("vanaf begin al aan het begin", L.canStartOver(true, now - 600_000, now, 590.0))
    ok("vraag bezig", L.askStartOver(true, now - 600_000, now + 600_000, now))
    no("vraag zonder terugkijken", L.askStartOver(false, now - 600_000, now + 600_000, now))
    no("vraag net begonnen", L.askStartOver(true, now - 60_000, now + 600_000, now))
    no("vraag bijna afgelopen", L.askStartOver(true, now - 600_000, now + 30_000, now))
    no("vraag zonder gids", L.askStartOver(true, null, null, now))
    eq("zapvolgorde", L.zapOrder(listOf("A", "B", "A", "C", "B"), listOf(false, false, false, true, false), "B"), listOf(3, 1, 4, 0, 2))
    eq("zapvolgorde zonder favorieten", L.zapOrder(listOf("A", "B"), emptyList(), null), listOf(0, 1))
    var pc = Build173PlayerLogic.PreviousChannel()
    pc = pc.tuned("1"); eq("vorige eerste", pc.previous, null)
    pc = pc.tuned("2"); eq("vorige na wisselen", pc.previous, "1")
    pc = pc.tuned("2"); eq("vorige zelfde", pc.previous, "1")
    pc = pc.tuned("1"); eq("vorige terug", pc.previous, "2")
    var dt = Build173PlayerLogic.DoubleTap()
    dt = dt.tap(true, 0); eq("dubbeltik 10", dt.total, 10)
    dt = dt.tap(true, 500); eq("dubbeltik 20", dt.total, 20)
    dt = dt.tap(true, 1000); eq("dubbeltik 30", dt.total, 30)
    dt = dt.tap(true, 3000); eq("dubbeltik opnieuw", dt.total, 10)
    dt = dt.tap(false, 3200); eq("dubbeltik andere kant", dt.total, -10)
    ok("dubbeltik reeks loopt", dt.active(3500)); no("dubbeltik voorbij", dt.active(5000))
    eq("eindtijd", L.endsAt(now, 3_600_000, 1f), now + 3_600_000)
    eq("eindtijd 2x", L.endsAt(now, 3_600_000, 2f), now + 1_800_000)
    eq("eindtijd onbekend", L.endsAt(now, 0, 1f), null)
    ok("terugspoelen 10", L.rewindSubtitles(-10.0, false, true, true))
    ok("terugspoelen 30", L.rewindSubtitles(-30.0, false, true, true))
    no("terugspoelen 60", L.rewindSubtitles(-60.0, false, true, true))
    no("vooruit", L.rewindSubtitles(10.0, false, true, true))
    no("al aan", L.rewindSubtitles(-10.0, true, true, true))
    no("geen spoor", L.rewindSubtitles(-10.0, false, false, true))
    no("instelling uit", L.rewindSubtitles(-10.0, false, true, false))
    ok("voorbij het punt", L.rewindSubtitlesDone(100_000, 100_000, 90_000))
    no("nog niet voorbij", L.rewindSubtitlesDone(95_000, 100_000, 90_000))
    ok("ver teruggesprongen", L.rewindSubtitlesDone(40_000, 100_000, 90_000))
    eq("spoor op naam", L.subtitleTrack(listOf("English", "Nederlands"), listOf("nl")), 1)
    eq("spoor op code", L.subtitleTrack(listOf("en", "nl-NL"), listOf("nl")), 1)
    eq("geen spoor", L.subtitleTrack(listOf("English"), listOf("nl")), null)
    eq("tweede voorkeur", L.subtitleTrack(listOf("Deutsch", "English"), listOf("nl", "en")), 1)
    eq("slaaptimer 30", L.sleepDeadline(S.Minutes(30), now), now + 1_800_000)
    eq("slaaptimer aflevering", L.sleepDeadline(S.EndOfEpisode, now), null)
    ok("slaaptimer af", L.sleepDue(S.Minutes(30), now, now, false))
    no("slaaptimer nog niet", L.sleepDue(S.Minutes(30), now + 1, now, false))
    ok("slaaptimer na aflevering", L.sleepDue(S.EndOfEpisode, null, now, true))
    no("slaaptimer aflevering loopt", L.sleepDue(S.EndOfEpisode, null, now, false))
    no("slaaptimer uit", L.sleepDue(S.Off, now, now, true))
    eq("aftellen m:ss", L.countdown(65_000), "1:05"); eq("aftellen u:mm:ss", L.countdown(3_725_000), "1:02:05")
    eq("slaaptimer code", S.Minutes(60).code, "m60")
    eq("trakt netwerk", L.traktClassify(null), T.TRANSIENT)
    eq("trakt 429", L.traktClassify(429), T.TRANSIENT)
    eq("trakt 502", L.traktClassify(502), T.TRANSIENT)
    eq("trakt 401", L.traktClassify(401), T.REAUTH)
    eq("trakt 403", L.traktClassify(403), T.REAUTH)
    eq("trakt 423", L.traktClassify(423), T.LOCKED)
    eq("verversen invalid_grant", L.traktRefreshFailure(listOf(400, 401)), T.REAUTH)
    eq("verversen netwerk", L.traktRefreshFailure(listOf(401, null)), T.TRANSIENT)
    eq("verversen 503", L.traktRefreshFailure(listOf(503, 401)), T.TRANSIENT)
    eq("verversen geen poging", L.traktRefreshFailure(emptyList()), T.TRANSIENT)
    no("geen fout", L.traktShowWarning(null, null, now))
    ok("reauth meteen", L.traktShowWarning(T.REAUTH, now, now))
    ok("vergrendeld meteen", L.traktShowWarning(T.LOCKED, now, now))
    no("1 uur tijdelijk", L.traktShowWarning(T.TRANSIENT, now - 3_600_000, now))
    ok("25 uur tijdelijk", L.traktShowWarning(T.TRANSIENT, now - 25 * 3_600_000L, now))
    no("ouder token niet overnemen", L.traktAdoptRemote("b", now + 86_400_000, "a", now + 3_600_000))
    ok("nieuwer token overnemen", L.traktAdoptRemote("a", now + 3_600_000, "b", now + 86_400_000))
    ok("geen lokaal token", L.traktAdoptRemote(null, null, "a", now))
    ok("zelfde token", L.traktAdoptRemote("a", now, "a", now))
    no("os 1 uur", L.osShouldRenew(now - 3_600_000, now, true))
    ok("os 23,5 uur", L.osShouldRenew(now - 84_600_000, now, true))
    ok("os leeftijd onbekend", L.osShouldRenew(null, now, true))
    no("os zonder wachtwoord", L.osShouldRenew(null, now, false))
    eq("os 401 met wachtwoord", L.osOnResponse(401, true, true), O.RELOGIN)
    eq("os 401 zonder wachtwoord", L.osOnResponse(401, true, false), O.EXPIRED)
    eq("os 401 bij zoeken", L.osOnResponse(401, false, true), O.KEEP)
    eq("os 503", L.osOnResponse(503, true, true), O.KEEP)
    eq("os netwerk", L.osOnResponse(null, true, true), O.KEEP)
    eq("os login 200", L.osLoginResult(200), G.OK)
    eq("os login 401", L.osLoginResult(401), G.BAD_CREDENTIALS)
    eq("os login 400", L.osLoginResult(400), G.BAD_CREDENTIALS)
    eq("os login 403", L.osLoginResult(403), G.TRANSIENT)
    eq("os login 429", L.osLoginResult(429), G.TRANSIENT)
    eq("os login netwerk", L.osLoginResult(null), G.TRANSIENT)
    println("$checks controles, $failures fout")
    if (failures > 0) kotlin.system.exitProcess(1)
}
