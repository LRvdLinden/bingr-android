package nl.lrvdlinden.bingr

// Build 132: unit-tests van TitleShare (deellink https://bingr.online/titel/?…). Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/core/TitleShare.kt tools/test/TitleShareTest.kt \
//     -include-runtime -d /tmp/s.jar && java -jar /tmp/s.jar
// De workflow Build doet dit vóór het bouwen van de APK; een fout laat de build mislukken.

private var failures = 0
private var checks = 0
private fun eq(name: String, got: Any?, want: Any?) {
    checks++
    if (got != want) { failures++; println("FOUT $name kreeg <$got>, verwacht <$want>") }
}

/** Queryparameters zoals TitleLink.parse ze leest (decoderen, "+" = spatie). */
private fun params(url: String): Map<String, String> {
    val q = url.substringAfter('?', "")
    if (q.isEmpty()) return emptyMap()
    return q.split('&').associate {
        val k = it.substringBefore('=')
        k to java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
    }
}

fun main() {
    val S = TitleShare
    eq("film met TMDB-id", S.url(true, 693134, "Dune: Part Two", 2024),
        "https://bingr.online/titel/?type=movie&id=693134&q=Dune%3A%20Part%20Two&y=2024")
    eq("serie met TMDB-id", S.url(false, 108978, "Reacher", 2022),
        "https://bingr.online/titel/?type=tv&id=108978&q=Reacher&y=2022")
    eq("zonder TMDB-id: type + q + y", S.url(true, null, "Amélie", 2001),
        "https://bingr.online/titel/?type=movie&q=Am%C3%A9lie&y=2001")
    eq("id 0 telt niet", S.url(true, 0, "Dune", null), "https://bingr.online/titel/?type=movie&q=Dune")
    eq("onzinjaar weg", S.url(false, null, "Dark", 99), "https://bingr.online/titel/?type=tv&q=Dark")
    eq("geen spatie als +", S.url(true, null, "The Batman", 2022).contains("+"), false)
    // terug te lezen zoals TitleLink.parse dat doet
    val p = params(S.url(true, 42, "Fast & Furious? #1 / 100%", 2001))
    eq("round-trip q", p["q"], "Fast & Furious? #1 / 100%")
    eq("round-trip type", p["type"], "movie")
    eq("round-trip id", p["id"], "42")
    eq("round-trip y", p["y"], "2001")
    eq("lange titel ingekort", params(S.url(true, null, "x".repeat(300), null))["q"]?.length, 200)
    eq("lege titel", S.url(null, null, "  ", null), "https://bingr.online/titel/")
    // deeltekst en jaar
    eq("label met jaar", S.label("Reacher", 2022), "Reacher (2022)")
    eq("label zonder jaar", S.label("Reacher", null), "Reacher")
    eq("jaar uit datum", S.year("2024-03-01"), 2024)
    eq("jaar uit tekst", S.year(" 1999 "), 1999)
    eq("geen jaar", S.year("onbekend"), null)
    eq("geen jaar (null)", S.year(null), null)

    println(if (failures == 0) "TitleShare: alle $checks controles geslaagd" else "TitleShare: $failures van $checks controles mislukt")
    if (failures > 0) kotlin.system.exitProcess(1)
}
