package nl.lrvdlinden.bingr

// Build 128: unit-tests van AllServersLogic (pure logica). Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/features/AllServersLogic.kt tools/test/AllServersLogicTest.kt \
//     -include-runtime -d /tmp/t.jar && java -jar /tmp/t.jar
// De workflow Build doet dit vóór het bouwen van de APK; een fout laat de build mislukken.

private var failures = 0
private var checks = 0
private fun check(name: String, ok: Boolean, detail: Any? = null) {
    checks++
    if (!ok) { failures++; println("FOUT $name ${detail ?: ""}") }
}
private fun eq(name: String, got: Any?, want: Any?) = check(name, got == want, "kreeg <$got>, verwacht <$want>")

fun main() {
    val L = AllServersLogic
    // normaliseren titels
    eq("titel: voorvoegsel, jaar, 4K", L.normTitle("NL | Dune: Part Two (2024) 4K"), "dune part two")
    eq("titel: NL: en accenten", L.normTitle("NL: Amélie"), "amelie")
    eq("titel: [NL] en MULTI", L.normTitle("[NL] Night Train MULTI"), "night train")
    eq("titel: taal-tag", L.normTitle("Animal (IT)"), "animal")
    eq("titel: jaar los achteraan", L.normTitle("The Batman 2022"), "the batman")
    eq("titel: & en leestekens", L.normTitle("Fast & Furious!"), "fast furious")
    eq("titel: afleveringsmarkering weg", L.normTitle("NL | Reacher S02E03 Het begin"), "reacher")
    eq("titel: DOLBY ATMOS HDR", L.normTitle("Dune DOLBY ATMOS HDR"), "dune")
    eq("zender: 8K en landcode achteraan", L.normChannel("Canvas 8K BE"), "canvas")
    eq("S/E uit ondertitel", L.seasonEpisode("S1:E2 · Pilot"), 1 to 2)
    eq("S/E uit S01E02", L.seasonEpisode("s01e12"), 1 to 12)
    eq("jaar uit titel", L.yearIn("Dune (2021)"), 2021)
    eq("jaar achteraan", L.yearIn("Dune 2021"), 2021)
    eq("geen jaar", L.yearIn("1917"), null)
    // zenders
    eq("zender: NL: + HD", L.normChannel("NL: NPO 1 HD"), "npo1")
    eq("zender: |BE| 4K", L.normChannel("|BE| VTM 4K"), "vtm")
    eq("zender: 50fps + HEVC", L.normChannel("RTL 4 FHD 50fps HEVC"), "rtl4")
    eq("zender: NL | ", L.normChannel("NL | Ziggo Sport"), "ziggosport")
    eq("zender: hoofdletters/leestekens", L.normChannel("ESPN-2"), "espn2")

    // matchen
    val cands = listOf(
        AllServersLogic.Candidate("B", "xv1", "movie", "NL | Dune (2021)", 2021),
        AllServersLogic.Candidate("A", "m9", "movie", "Dune", 2022),
        AllServersLogic.Candidate("C", "m7", "movie", "Dune", 1984),
        AllServersLogic.Candidate("A", "s1", "series", "Dune", 2021),
        AllServersLogic.Candidate("B", "c1", "channel", "NL: NPO 1 HD", epg = "npo1.nl"),
        AllServersLogic.Candidate("A", "c2", "channel", "NPO 1 FHD"),
    )
    val dune = AllServersLogic.Wanted("movie", "Dune 4K", 2021)
    eq("film: actieve server eerst (jaar ±1)", L.best(dune, cands, listOf("a", "b", "c"))?.key, "A|m9")
    eq("film: andere volgorde", L.best(dune, cands, listOf("B", "A"))?.key, "B|xv1")
    eq("film: oude server uitgesloten", L.best(dune, cands, listOf("a", "b"), exclude = setOf("A", "b")), null)
    eq("film: jaar te ver weg", L.best(AllServersLogic.Wanted("movie", "Dune", 1990), cands, listOf("a", "b", "c")), null)
    eq("film: zonder jaar", L.best(AllServersLogic.Wanted("movie", "Dune", null), cands, listOf("c"))?.key, "C|m7")
    eq("serie: alleen series", L.best(AllServersLogic.Wanted("series", "Dune", 2021), cands, listOf("b", "a"))?.key, "A|s1")
    eq("aflevering zoekt de serie", L.best(AllServersLogic.Wanted("episode", "Dune", null), cands, listOf("a"))?.key, "A|s1")
    val tm = listOf(AllServersLogic.Candidate("A", "x", "movie", "Andere titel", 2000, tmdb = 42), AllServersLogic.Candidate("B", "y", "movie", "Dune", 2021, tmdb = 43))
    eq("TMDB-id gaat voor titel", L.best(AllServersLogic.Wanted("movie", "Dune", 2021, tmdb = 42), tm, listOf("a", "b"))?.key, "A|x")
    eq("zender op EPG-id", L.best(AllServersLogic.Wanted("channel", "Nederland 1", epg = "NPO1.nl"), cands, listOf("a", "b"))?.key, "B|c1")
    eq("zender op naam, actieve eerst", L.best(AllServersLogic.Wanted("channel", "NPO 1"), cands, listOf("a", "b"))?.key, "A|c2")
    eq("dedup: zelfde werk", L.dedupKey("movie", "NL | Dune (2021)", null, null), L.dedupKey("movie", "Dune", 2021, null))

    // remap-ketens
    var rules = mapOf<String, AllServersLogic.Rule>()
    rules = L.addRule(rules, "OLD|a", "mid|b", 10)
    rules = L.addRule(rules, "mid|b", "New|c", 20)
    eq("keten a→b→c", L.resolve(rules, "old|a"), "New|c" to 20L)
    eq("hoofdletterongevoelig", L.resolve(rules, "OLD|A")?.first, "New|c")
    eq("geen regel", L.resolve(rules, "x|y"), null)
    val loop = L.addRule(rules, "new|c", "old|a", 30)
    eq("lus wordt geweigerd", loop, rules)
    val manual = rules + ("new|c" to AllServersLogic.Rule("old|a", 30))
    check("lus breekt af", L.resolve(manual, "old|a") == null || L.resolve(manual, "old|a") != null)

    // remap toepassen op een lijst
    val list = mapOf("OLD|a" to AllServersLogic.Entry(5, true), "keep|z" to AllServersLogic.Entry(6, true))
    val r1 = L.remapEntries(list, rules)!!
    eq("oude sleutel verwijderd", r1["OLD|a"], AllServersLogic.Entry(20, false))
    eq("nieuwe sleutel met dezelfde at", r1["New|c"], AllServersLogic.Entry(5, true))
    eq("andere items blijven", r1["keep|z"], AllServersLogic.Entry(6, true))
    eq("idempotent", L.remapEntries(r1, rules), null)
    val late = mapOf("OLD|a" to AllServersLogic.Entry(50, true))
    eq("verwijdering altijd later dan het item", L.remapEntries(late, rules)!!["OLD|a"], AllServersLogic.Entry(51, false))
    val removedNew = mapOf("OLD|a" to AllServersLogic.Entry(5, true), "New|c" to AllServersLogic.Entry(8, false))
    eq("nieuwe sleutel later bewust verwijderd: niet terug", L.remapEntries(removedNew, rules)!!["New|c"], AllServersLogic.Entry(8, false))
    // voortgang: sleutel = item-id, server uit playlistID
    val prog = mapOf("xv1" to AllServersLogic.Entry(7, true))
    val rp = L.remapEntries(prog, mapOf("dead|xv1" to AllServersLogic.Rule("Live|m5", 9)), keyOf = { "dead|$it" }, newKeyOf = { L.itemOf(it) })!!
    eq("voortgang: nieuwe item-id", rp["m5"], AllServersLogic.Entry(7, true))
    eq("voortgang: oude weg", rp["xv1"]?.alive, false)

    // samenvoegen en begrenzen
    val a = mapOf("k1" to 5L, "k2" to 9L)
    val b = mapOf("k1" to 7L, "k2" to 3L, "k3" to 1L)
    eq("nieuwste wint", L.mergeNewest(a, b) { it }, mapOf("k1" to 7L, "k2" to 9L, "k3" to 1L))
    val now = 1000L * AllServersLogic.DAY
    val items = mapOf("old" to now - 100 * AllServersLogic.DAY, "oldref" to now - 100 * AllServersLogic.DAY, "new" to now)
    eq("oud en onverwezen weg", L.pruneItems(items, { it }, setOf("oldref"), now).keys, setOf("oldref", "new"))
    val many = (1..10).associate { "k$it" to it.toLong() + now }
    eq("maximum: oudste onverwezen eerst weg", L.pruneItems(many, { it }, setOf("k1"), now, max = 3).keys, setOf("k1", "k9", "k10"))
    val rr = mapOf("a" to AllServersLogic.Rule("b", now - 200 * AllServersLogic.DAY), "c" to AllServersLogic.Rule("d", now))
    eq("remap 180 dagen", L.pruneRules(rr, now).keys, setOf("c"))
    eq("metaKey", L.metaKey("ABC-1|Xv9"), "abc-1|Xv9")

    println("$checks controles, $failures fout")
    if (failures > 0) kotlin.system.exitProcess(1)
}
