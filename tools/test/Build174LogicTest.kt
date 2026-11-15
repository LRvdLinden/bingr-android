package nl.lrvdlinden.bingr

import nl.lrvdlinden.bingr.Build174Logic.Action as A
import nl.lrvdlinden.bingr.Build174Logic.Candidate as C
import nl.lrvdlinden.bingr.Build174Logic.Source as Src

// Build 174: detailpagina, trailers en Bingr Radio-widget (Build174Logic.kt). Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/features/Build174Logic.kt tools/test/Build174LogicTest.kt -include-runtime -d /tmp/b174.jar
//   java -jar /tmp/b174.jar

private var failures = 0
private var checks = 0
private fun eq(name: String, got: Any?, want: Any?) {
    checks++
    if (got != want) { failures++; println("FOUT $name kreeg <$got>, verwacht <$want>"); println("::error title=Build174LogicTest::$name") }
}

fun main() {
    val L = Build174Logic
    eq("vijf knoppen", L.compactActions(true, true, 2, 0, true), listOf(A.MY_LIST, A.TRAILER, A.SOURCES, A.WATCHED, A.MORE))
    eq("geen trailer en één bron", L.compactActions(true, false, 0, 0, true), listOf(A.MY_LIST, A.WATCHED, A.MORE))
    eq("serie zonder Gezien", L.compactActions(false, true, 0, 1, false), listOf(A.TRAILER, A.SOURCES, A.MORE))
    eq("telefoon", L.isCompact(390, false), true); eq("tablet", L.isCompact(820, false), false); eq("tv", L.isCompact(390, true), false)
    eq("bronnen kiezen", L.sourcesTarget(1, 1), Build174Logic.SourcesTarget.CHOOSE)
    eq("bronnen taal", L.sourcesTarget(0, 2), Build174Logic.SourcesTarget.LANGUAGES)
    eq("bronnen geen", L.sourcesTarget(0, 0), Build174Logic.SourcesTarget.NONE)

    val c = listOf(C("aaaaaaaaaaa", Src.SERVER), C("bbbbbbbbbbb", Src.TMDB, "Teaser", "nl"),
        C("ccccccccccc", Src.TMDB, "Trailer", "en", true), C("ddddddddddd", Src.TMDB, "Trailer", "nl", true),
        C("eeeeeeeeeee", Src.TMDB, "Featurette", "nl"))
    eq("trailer app-taal", L.bestTrailer(c, "nl-NL"), "ddddddddddd")
    eq("Engels", L.bestTrailer(c, "de"), "ccccccccccc")
    eq("server voor teaser", L.bestTrailer(listOf(c[0], c[1]), "nl"), "aaaaaaaaaaa")
    eq("teaser", L.bestTrailer(listOf(c[1], c[4]), "nl"), "bbbbbbbbbbb")
    eq("featurette", L.bestTrailer(listOf(c[4]), "nl"), null)
    eq("ongeldig", L.bestTrailer(listOf(C("bad<id>", Src.SERVER)), "nl"), null)
    eq("officieel", L.bestTrailer(listOf(C("fffffffffff", Src.TMDB, "Trailer", "en", false), C("ggggggggggg", Src.TMDB, "Trailer", "en", true)), "en"), "ggggggggggg")
    eq("server voor Trakt", L.bestTrailer(listOf(C("hhhhhhhhhhh", Src.TRAKT), C("iiiiiiiiiii", Src.SERVER)), "nl"), "iiiiiiiiiii")
    eq("alleen YouTube", L.bestTrailer(listOf(C("jjjjjjjjjjj", Src.TMDB, "Trailer", site = "Vimeo")), "nl"), null)
    eq("watch-link", L.youtubeID("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=1"), "dQw4w9WgXcQ")
    eq("korte link", L.youtubeID("https://youtu.be/dQw4w9WgXcQ"), "dQw4w9WgXcQ")
    eq("rommel", L.youtubeID("javascript:alert(1)"), null)
    eq("zonder WebView: YouTube-app", L.opening(false, true, true), Build174Logic.Opening.YOUTUBE_APP)
    eq("zonder WebView en app: browser", L.opening(false, false, true), Build174Logic.Opening.BROWSER)
    eq("in de app", L.opening(true, true, true), Build174Logic.Opening.IN_APP)

    val st = listOf(Build174Logic.Station("1", "Radio 1"), Build174Logic.Station("2", "538", true), Build174Logic.Station("3", "BBC"))
    eq("widget favorieten eerst", L.widgetStations(st, 2).map { it.id }, listOf("2", "1"))
    eq("widget eerste favoriet", L.featured(st, null)?.id, "2")
    eq("widget speelt nu", L.featured(st, "3")?.id, "3")
    eq("ICY", L.cleanStreamTitle("StreamTitle='Queen - Bohemian Rhapsody';StreamUrl='';"), "Queen - Bohemian Rhapsody")
    eq("lege ICY", L.cleanStreamTitle("StreamTitle='';"), null)
    eq("zendernaam", L.cleanStreamTitle("Radio 538", "radio 538"), null)
    eq("reclame", L.cleanStreamTitle("ADBREAK_120"), null)

    println("build 174: $checks controles, $failures fout")
    if (failures > 0) kotlin.system.exitProcess(1)
}
