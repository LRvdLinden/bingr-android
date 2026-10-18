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

    // build 129: zoeken en slimme collecties over alle servers (één lijst, ontdubbeld zoals build 128)
    data class T(val sid: String, val title: String, val year: Int? = null)
    val tk: (T) -> AllServersLogic.Cross? = { L.crossKey("movie", it.title, it.year) }
    val active = listOf(T("A", "Dune (2021)"), T("A", "NL | Dune 2021 4K"), T("A", "Arrival"))
    val other1 = listOf(T("B", "Dune", 2021), T("B", "Heat (1995)"), T("B", "Heat (1995) HD"))
    val other2 = listOf(T("C", "Arrival (2016)"), T("C", "Heat", 1995), T("C", "Tenet"))
    val merged = L.mergeAcross(listOf(active, other1, other2), tk)
    eq("samenvoegen: actieve server helemaal, ook twee versies", merged.take(3), active)
    eq("samenvoegen: dubbele van andere servers weg (onbekend jaar = titel), volgorde blijft", merged.drop(3), listOf(T("B", "Heat (1995)"), T("C", "Tenet")))
    eq("samenvoegen: jaar ±1 is hetzelfde, ±2 niet", L.mergeAcross(listOf(listOf(T("A", "Heat", 1995)), listOf(T("B", "Heat", 1996), T("B", "Heat", 1997))), tk),
        listOf(T("A", "Heat", 1995), T("B", "Heat", 1997)))
    eq("samenvoegen: limiet", L.mergeAcross(listOf(active, other1), tk, limit = 4).size, 4)
    eq("samenvoegen: lege naam wordt niet samengevoegd", L.mergeAcross(listOf(listOf(T("A", "4K")), listOf(T("B", "HD"))), tk).size, 2)
    eq("crossKey: jaar uit de titel", L.crossKey("movie", "Dune (2021)", null), L.crossKey("movie", "NL: Dune", 2021))
    eq("crossKey: film ≠ serie", L.crossKey("movie", "Fargo", null) == L.crossKey("series", "Fargo", null), false)
    eq("crossKey: TMDB gaat voor", L.crossKey("movie", "Anders", 2000, tmdb = 603), AllServersLogic.Cross("m:tmdb:603", null))
    eq("crossKey: zender op naam", L.crossKey("channel", "NL: NPO 1 HD", null), L.crossKey("channel", "|NL| NPO 1 FHD", null))
    eq("crossKey: lege zender", L.crossKey("channel", "HD", null), null)
    val chans = L.mergeAcross(listOf(listOf("NPO 1 HD", "RTL 4"), listOf("NL: NPO 1 FHD", "SBS 6"), listOf("RTL 4 50fps", "Veronica")),
        { L.crossKey("channel", it, null) })
    eq("zenders over servers: één per zender", chans, listOf("NPO 1 HD", "RTL 4", "SBS 6", "Veronica"))
    eq("slim: zoekwoord in naam", L.smartHit("nl: marvel avengers", "Films", listOf("marvel"), null), true)
    eq("slim: één van de woorden", L.smartHit("star wars", "Films", listOf("marvel", "star"), null), true)
    eq("slim: geen woord", L.smartHit("dune", "Films", listOf("marvel"), null), false)
    eq("slim: andere categorie", L.smartHit("marvel", "Kids", listOf("marvel"), "Films"), false)
    eq("slim: leeg woord telt niet", L.smartHit("dune", "Films", listOf(""), null), false)

    // build 129: terugkijken op een MAC-portal (link pas bij afspelen)
    val au = L.archiveURL("Srv-1/a b", "st123", 1_700_000_000_000, 1_700_003_600_000)
    eq("archief: voorvoegsel", au.startsWith(L.ARCHIVE_SCHEME), true)
    eq("archief: heen en terug", L.parseArchive(au), AllServersLogic.Archive("Srv-1/a b", "st123", 1_700_000_000_000, 1_700_003_600_000))
    eq("archief: geen archief-adres", L.parseArchive("http://x/y.ts"), null)
    eq("archief: kapot", L.parseArchive(L.ARCHIVE_SCHEME + "a/b/c"), null)
    val start = 1_700_000_000_000
    val progs = listOf(Triple(start - 30 * 60_000L, true, "p0"), Triple(start + 2 * 60_000L, false, "p1"),
        Triple(start + 3 * 60_000L, true, "p2"), Triple(start + 4 * 60_000L, true, "p3"))
    eq("archief: programma binnen 5 min en in het archief", L.archiveProgram(progs, start), "p2")
    eq("archief: niets in de buurt", L.archiveProgram(progs, start + 3_600_000L), null)

    // build 130: achtergrondwerk voor de andere servers (niet lang "Bijwerken" na openen)
    val h = 3_600_000L
    val t0 = 1_700_000_000_000L
    eq("ophalen: nooit geprobeerd", L.mayFetch(null, t0), true)
    eq("ophalen: 0 = nooit", L.mayFetch(0L, t0), true)
    eq("ophalen: net geprobeerd", L.mayFetch(t0 - 60_000, t0), false)
    eq("ophalen: 5 uur geleden", L.mayFetch(t0 - 5 * h, t0), false)
    eq("ophalen: 6 uur geleden", L.mayFetch(t0 - 6 * h, t0), true)
    eq("ophalen: klok teruggezet", L.mayFetch(t0 + h, t0), true)
    eq("rust: alles klaar", L.idle(false, false, false, false, 20_000), true)
    eq("rust: tijdens afspelen", L.idle(true, false, false, false, 20_000), false)
    eq("rust: actieve laadt", L.idle(false, true, false, false, 20_000), false)
    eq("rust: actieve ververst (Bijwerken)", L.idle(false, false, true, false, 20_000), false)
    eq("rust: actieve filtert", L.idle(false, false, false, true, 20_000), false)
    eq("rust: net geopend", L.idle(false, false, false, false, 2_000), false)
    eq("vernieuwen: actieve eerst", L.refreshOrder(listOf("a", "B", "c"), "b"), listOf("B", "a", "c"))
    eq("vernieuwen: geen actieve", L.refreshOrder(listOf("a", "b"), null), listOf("a", "b"))
    eq("vernieuwen: dubbel één keer", L.refreshOrder(listOf("a", "A", "b"), "x"), listOf("a", "b"))
    eq("scan: volgorde maakt niet uit", L.scanSig(listOf("s|1", "s|2"), 5, "f") == L.scanSig(listOf("s|2", "s|1"), 5, "f"), true)
    eq("scan: nieuwe cache = opnieuw", L.scanSig(listOf("s|1"), 5, "f") == L.scanSig(listOf("s|1"), 6, "f"), false)
    eq("scan: andere filters = opnieuw", L.scanSig(listOf("s|1"), 5, "f") == L.scanSig(listOf("s|1"), 5, "g"), false)
    eq("scan: ander item = opnieuw", L.scanSig(listOf("s|1"), 5, "f") == L.scanSig(listOf("s|1", "s|3"), 5, "f"), false)

    println("$checks controles, $failures fout")
    if (failures > 0) kotlin.system.exitProcess(1)
}
