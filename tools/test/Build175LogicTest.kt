package nl.lrvdlinden.bingr

import nl.lrvdlinden.bingr.Build175Logic.Episode as E
import nl.lrvdlinden.bingr.Build175Logic.MovieMenu as M
import nl.lrvdlinden.bingr.Build175Logic.Scope as S
import nl.lrvdlinden.bingr.Build175Logic.State as St

// Build 175: downloaden vanuit Meer, ook bij series (Build175Logic.kt). Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/features/Build175Logic.kt tools/test/Build175LogicTest.kt -include-runtime -d /tmp/b175.jar
//   java -jar /tmp/b175.jar

private var failures = 0
private var checks = 0
private fun eq(name: String, got: Any?, want: Any?) {
    checks++
    if (got != want) { failures++; println("FOUT $name kreeg <$got>, verwacht <$want>"); println("::error title=Build175LogicTest::$name") }
}

fun main() {
    val L = Build175Logic
    eq("status done", L.state("done"), St.COMPLETED); eq("status null", L.state(null), St.NONE)
    eq("status failed", L.state("failed"), St.FAILED); eq("status queued", L.state("queued"), St.QUEUED)
    eq("film: downloaden", L.movieMenu(true, St.NONE), M.DOWNLOAD)
    eq("film: uitgegrijsd met reden", L.movieMenu(false, St.NONE), M.UNAVAILABLE)
    eq("film: gedownload gaat voor", L.movieMenu(false, St.COMPLETED), M.DOWNLOADED)
    eq("film: bezig", L.movieMenu(true, St.DOWNLOADING), M.ACTIVE)
    eq("film: mislukt = hervatten", L.movieMenu(true, St.FAILED), M.RESUME)
    eq("film: gepauzeerd = hervatten", L.movieMenu(true, St.PAUSED), M.RESUME)

    val eps = listOf(E("s2e1", 2, 1), E("s1e2", 1, 2, state = St.COMPLETED), E("s1e1", 1, 1, state = St.COMPLETED),
        E("s1e3", 1, 3), E("s1e4", 1, 4, state = St.DOWNLOADING), E("s1e5", 1, 5, state = St.FAILED),
        E("s2e2", 2, 2, downloadable = false), E("s2e3", 2, 3))
    eq("volgorde", L.ordered(eps).map { it.id }, listOf("s1e1", "s1e2", "s1e3", "s1e4", "s1e5", "s2e1", "s2e2", "s2e3"))
    eq("seizoen: zonder binnen/onderweg", L.episodes(S.SEASON, eps, 1, null), listOf("s1e3", "s1e5"))
    eq("seizoen: zonder stream valt af", L.episodes(S.SEASON, eps, 2, null), listOf("s2e1", "s2e3"))
    eq("volgende 3 over seizoenen", L.episodes(S.NEXT_THREE, eps, 1, "s1e2"), listOf("s1e3", "s1e5", "s2e1"))
    eq("volgende 3 zonder volgende", L.episodes(S.NEXT_THREE, eps, 1, null), listOf("s1e3", "s1e5", "s2e1"))
    eq("volgende 3 aan het eind", L.episodes(S.NEXT_THREE, eps, 1, "s2e3"), listOf("s2e3"))
    eq("onbekende volgende", L.episodes(S.NEXT_THREE, eps, 1, "onbekend"), listOf("s1e3", "s1e5", "s2e1"))
    eq("hele serie", L.episodes(S.ALL, eps, 1, null), listOf("s1e3", "s1e5", "s2e1", "s2e3"))
    eq("hele serie: bevestigen", L.needsConfirmation(S.ALL, 4), true)
    eq("seizoen: niet bevestigen", L.needsConfirmation(S.SEASON, 4), false)
    eq("niets te doen: niet bevestigen", L.needsConfirmation(S.ALL, 0), false)
    eq("seriemenu", L.seriesMenu(eps, 1, "s1e2"), Build175Logic.SeriesMenu(true, 2, 3, 4, 4, false))
    val done = L.seriesMenu(listOf(E("a", 1, 1, state = St.COMPLETED), E("b", 1, 2, downloadable = false)), 1, null)
    eq("serie: alles gedownload", Triple(done.complete, done.all, done.downloaded), Triple(true, 0, 1))
    val none = L.seriesMenu(listOf(E("x", 1, 1, downloadable = false)), 1, null)
    eq("serie zonder downloadbare stream", none.available || none.complete, false)
    eq("serie zonder afleveringen", L.seriesMenu(emptyList(), 1, null).available, false)

    println("build 175: $checks controles, $failures fout")
    if (failures > 0) kotlin.system.exitProcess(1)
}
