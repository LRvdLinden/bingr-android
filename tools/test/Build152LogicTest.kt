package nl.lrvdlinden.bingr

// Build 152: regressietests voor verborgen categorieën (account-set build 140, overslaan build 149, samenvoegen build 150)
// en de rij "Nu in de bioscoop". Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/features/AllServersLogic.kt app/src/main/java/nl/lrvdlinden/bingr/features/Build140Logic.kt app/src/main/java/nl/lrvdlinden/bingr/features/Build149Logic.kt \
//     app/src/main/java/nl/lrvdlinden/bingr/features/Build150Logic.kt app/src/main/java/nl/lrvdlinden/bingr/features/Build152Logic.kt \
//     tools/test/Build152LogicTest.kt -include-runtime -d /tmp/b152.jar
//   java -jar /tmp/b152.jar

private var failures = 0
private var checks = 0
private fun eq(name: String, got: Any?, want: Any?) {
    checks++
    if (got != want) { failures++; println("FOUT $name kreeg <$got>, verwacht <$want>"); println("::error title=Build152LogicTest::$name") }
}
private fun ok(name: String, v: Boolean) = eq(name, v, true)

fun main() {
    val H = Build152Logic.HiddenCategories
    val C = Build140Logic.CatMarks
    val M = Build150Logic.Merge

    // 1. samengevoegde categorie: één regel "Netflix", sleutels per bron met de exacte ruwe naam
    val nl = "| NL | NETFLIX"; val multi = "| MULTI / LINGO | NETFLIX"
    val rows = M.groups(listOf(nl, multi, "| NL | SPORT"))
    val netflix = rows.first { it.name == "Netflix" }
    eq("samenvoegen: één regel Netflix", netflix.sources, listOf(nl, multi))
    val byName = mapOf(nl to listOf("SRV-A", "srv-b"), multi to listOf("SRV-A"))
    val keys = H.keysFor(netflix.sources) { byName[it] ?: emptyList() }
    eq("verbergen: sleutels per bron en server", keys, listOf("srv-a|$nl", "srv-b|$nl", "srv-a|$multi"))
    ok("verbergen: nooit de opgeschoonde naam", keys.none { it.endsWith("|Netflix") })

    // aan/uit zet alle bronnen, en de index vindt ze op de ruwe naam
    var marks = C.set(emptyMap(), keys, true, 1_000)
    var idx = C.index(C.alive(marks))
    ok("verborgen: NL op server a", idx["srv-a"]?.contains(nl) == true)
    ok("verborgen: MULTI op server a", idx["srv-a"]?.contains(multi) == true)
    ok("verborgen: NL op server b", idx["srv-b"]?.contains(nl) == true)
    ok("verborgen: opgeschoonde naam niet in de index", idx["srv-a"]?.contains("Netflix") != true)
    marks = C.set(marks, keys, false, 2_000)
    eq("weer tonen: alle bronnen aan", C.alive(marks), emptySet<String>())
    eq("weer tonen: verwijderingen blijven voor de sync", marks.keys, keys.toSet())

    // 2. sync: een sleutel van Apple (server-id in hoofdletters, build 128) betekent hier hetzelfde, en andersom
    val apple = mapOf("SRV-A|$nl" to Build140Logic.Mark(3_000, true))
    val merged = C.merge(marks, apple)
    ok("sync: Apple-sleutel (hoofdletters) wint op tijd", C.alive(merged) == setOf("srv-a|$nl"))
    eq("sync: server-id naar kleine letters", C.norm("SRV-A|$nl"), "srv-a|$nl")
    eq("sync: categorienaam exact (hoofdletters blijven)", C.norm("Srv-A|| NL | NETFLIX"), "srv-a|| NL | NETFLIX")
    // oude sleutel van vóór 150 (ruwe naam) is gelijk aan de nieuwe (build 150 veranderde de sleutels niet)
    eq("oud: sleutel vóór 150 = sleutel nu", C.key("srv-a", nl), H.key("SRV-A", nl))
    // een latere verwijdering van een ander apparaat wint
    val later = C.merge(merged, mapOf("srv-a|$nl" to Build140Logic.Mark(4_000, false)))
    eq("sync: latere verwijdering wint", C.alive(later), emptySet<String>())

    // 3. overslaan en terugkomen (ook voor andere servers)
    val skipped = listOf("movie|$nl", "series|$multi", "live|NL | Sport")
    val hiddenNow = setOf(nl, multi, "NL | Sport")
    ok("terug: niets aangezet = niet ophalen", !H.comeBack(skipped, { it in hiddenNow }))
    ok("terug: één bron aan = ophalen", H.comeBack(skipped, { it in hiddenNow - multi }))
    ok("terug: kinderprofiel (niets verborgen) = ophalen", H.comeBack(skipped, { false }))
    ok("terug: naam met | erin", H.comeBack(listOf("live|| NL | NETFLIX"), { it != nl }))
    ok("terug: geen overgeslagen categorieën", !H.comeBack(emptyList(), { false }))

    // 4. verborgen is niet weg (geen tegel uit de meta, geen remap)
    // (enum zonder alias)
    eq("aanwezig: in de gefilterde lijst", H.presence(true, true, false), Build152Logic.HiddenCategories.Presence.FOUND)
    eq("aanwezig: alleen in de ruwe lijst = gefilterd", H.presence(false, true, false), Build152Logic.HiddenCategories.Presence.FILTERED)
    eq("aanwezig: overgeslagen categorie = gefilterd", H.presence(false, false, true), Build152Logic.HiddenCategories.Presence.FILTERED)
    eq("aanwezig: ruwe lijst onbekend = gefilterd", H.presence(false, null, false), Build152Logic.HiddenCategories.Presence.FILTERED)
    eq("aanwezig: nergens en niets overgeslagen = weg", H.presence(false, false, false), Build152Logic.HiddenCategories.Presence.GONE)

    // 5. Nieuw op Bingr: een categorie die weer aan gaat, is niet ineens nieuw
    val known = mapOf("m:1" to 10L, "m:2" to 20L)
    val whileHidden = H.firstSeen(known, listOf("m:1" to "movie|A"), 100, emptySet(), skippedNow = true)
    eq("eerst gezien: bekende id uit verborgen categorie blijft", whileHidden["m:2"], 20L)
    val back = H.firstSeen(whileHidden, listOf("m:1" to "movie|A", "m:2" to "movie|$nl", "m:3" to "movie|$nl", "m:4" to "movie|B"),
        200, setOf("movie|$nl"), skippedNow = false)
    eq("eerst gezien: oude tijd terug", back["m:2"], 20L)
    eq("eerst gezien: nieuw uit eerder overgeslagen categorie = 0 (niet nieuw)", back["m:3"], 0L)
    eq("eerst gezien: echt nieuw = nu", back["m:4"], 200L)
    eq("eerst gezien: zonder overslaan vallen verdwenen id's weg",
        H.firstSeen(known, listOf("m:1" to "movie|A"), 300, emptySet(), false).keys, setOf("m:1"))
    eq("eerst gezien: eerste keer alles 0", H.firstSeen(null, listOf("m:9" to "movie|A"), 300, emptySet(), false), mapOf("m:9" to 0L))

    // 6. kinderprofiel: kindercategorieën via de eigen regels (los van de account-set)
    ok("kinderen: NL | Kinderfilms", Build140Logic.Kids.isKidsCategory("NL | Kinderfilms"))
    ok("kinderen: nooit adult", !Build140Logic.Kids.isKidsCategory("Kids Adult XXX"))

    // 7. Nu in de bioscoop: populairste eerst, max. 20, zonder poster/adult/dubbel
    val CI = Build152Logic.Cinema
    val films = (1..30).map { Build152Logic.Cinema.Item(it, "Film $it", "/p$it.jpg", it.toDouble()) } +
        listOf(Build152Logic.Cinema.Item(99, "Geen poster", null, 999.0), Build152Logic.Cinema.Item(98, "Adult", "/a.jpg", 998.0, adult = true), Build152Logic.Cinema.Item(30, "Film 30", "/p30.jpg", 30.0))
    val pick = CI.pick(films)
    eq("bioscoop: max 20", pick.size, 20)
    eq("bioscoop: populairste eerst", pick.first().id, 30)
    ok("bioscoop: geen poster/adult", pick.none { it.id == 99 || it.id == 98 })
    eq("bioscoop: geen dubbele", pick.map { it.id }.distinct().size, pick.size)
    eq("bioscoop: leeg blijft leeg", CI.pick(emptyList()), emptyList<Build152Logic.Cinema.Item>())

    println(if (failures == 0) "Build152Logic: alle $checks controles geslaagd" else "Build152Logic: $failures van $checks controles mislukt")
    kotlin.system.exitProcess(if (failures > 0) 1 else 0)
}
