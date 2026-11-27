package nl.lrvdlinden.bingr

// Build 191: tabbladen op de detailpagina (Build191Logic.kt). Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/features/Build191Logic.kt tools/test/Build191LogicTest.kt -include-runtime -d /tmp/b191.jar
//   java -jar /tmp/b191.jar

private var failures = 0
private var checks = 0
private fun eq(name: String, got: Any?, want: Any?) {
    checks++
    if (got != want) { failures++; println("FOUT $name kreeg <$got>, verwacht <$want>"); println("::error title=Build191LogicTest::$name") }
}

fun main() {
    val L = Build191Logic
    val T = Build191Logic.Tab
    eq("serie met alles", L.tabs(true, 5, 1), listOf(T.EPISODES, T.MORE_LIKE_THIS, T.TRAILERS))
    eq("film zonder afleveringen", L.tabs(false, 5, 1), listOf(T.MORE_LIKE_THIS, T.TRAILERS))
    eq("film alleen trailer", L.tabs(false, 0, 1), listOf(T.TRAILERS))
    eq("serie zonder rest", L.tabs(true, 0, 0), listOf(T.EPISODES))
    eq("film zonder inhoud", L.tabs(false, 0, 0), emptyList<Build191Logic.Tab>())
    val s = L.tabs(true, 3, 2)
    eq("gekozen blijft", L.selected(s, T.TRAILERS), T.TRAILERS)
    eq("onbekende keuze valt terug", L.selected(listOf(T.MORE_LIKE_THIS), T.EPISODES), T.MORE_LIKE_THIS)
    eq("geen keuze: eerste", L.selected(s, null), T.EPISODES)
    eq("geen tabbladen", L.selected(emptyList(), T.EPISODES), null)
    eq("twee trailers", L.trailerCount("a", "b"), 2)
    eq("zelfde trailer", L.trailerCount("a", "a"), 1)
    eq("alleen seizoen", L.trailerCount(null, "b"), 1)
    eq("geen trailers", L.trailerCount(null, null), 0)
    eq("aantal bij 8", L.showEpisodeCount(8, false), true)
    eq("geen aantal bij 1", L.showEpisodeCount(1, false), false)
    eq("geen aantal als compleet", L.showEpisodeCount(8, true), false)

    println("Build191LogicTest: $checks controles, $failures fouten")
    if (failures > 0) kotlin.system.exitProcess(1)
}
