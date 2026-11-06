package nl.lrvdlinden.bingr

import java.util.TimeZone

// Build 164: nieuwe tv-gids (dagbalk, NU-index, venster per dag, tv-kolom, stilstaand beeld, instelling live voorbeeld).
// Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/features/Build164Logic.kt tools/test/Build164LogicTest.kt -include-runtime -d /tmp/b164.jar
//   java -jar /tmp/b164.jar

private var failures = 0
private var checks = 0
private fun eq(name: String, got: Any?, want: Any?) {
    checks++
    if (got != want) { failures++; println("FOUT $name kreeg <$got>, verwacht <$want>"); println("::error title=Build164LogicTest::$name") }
}
private fun ok(name: String, v: Boolean) = eq(name, v, true)

private data class P(val start: Long, val end: Long, val title: String)

fun main() {
    val D = Build164Logic.Days
    val I = Build164Logic.Index
    val S = Build164Logic.Stills
    val V = Build164Logic.Preview
    val ams = TimeZone.getTimeZone("Europe/Amsterdam")
    val min = 60_000L
    val hour = 60 * min

    // 1. dagbalk: gisteren t/m 7 vooruit; verder terug alleen met terugkijken, max. 14
    eq("dagbalk zonder terugkijken", D.offsets(0), (-1..7).toList())
    eq("dagbalk 1 terugkijkdag", D.offsets(1), (-1..7).toList())
    eq("dagbalk 3 terugkijkdagen", D.offsets(3), (-3..7).toList())
    eq("dagbalk max 14 terug", D.offsets(30).first(), -14)
    eq("dagbalk negatief = gisteren", D.offsets(-5).first(), -1)
    eq("dag binnen balk", D.clamp(-5, D.offsets(2)), -2)
    eq("dag binnen balk vooruit", D.clamp(12, D.offsets(0)), 7)
    eq("labels", listOf(-1, 0, 1, 2).map { D.label(it) },
        listOf(Build164Logic.Days.Label.YESTERDAY, Build164Logic.Days.Label.TODAY, Build164Logic.Days.Label.TOMORROW, Build164Logic.Days.Label.DATE))

    // 2. dagbegin in de lokale tijd, ook over de zomertijd heen (25 okt 2026: 25 uur)
    val fri = 1_793_962_800_000L   // vr 6 nov 2026 12:00 Amsterdam (11:00 UTC)
    val midnight = D.start(fri, 0, ams)
    eq("middernacht vandaag", (fri - midnight) / hour, 12L)
    eq("morgen = +24 u", D.start(fri, 1, ams) - midnight, 24 * hour)
    eq("einde vandaag = begin morgen", D.end(fri, 0, ams), D.start(fri, 1, ams))
    val sun = D.start(fri, -12, ams)   // zo 25 okt (zomertijd eindigt)
    eq("dag met zomertijdwissel = 25 u", D.start(fri, -11, ams) - sun, 25 * hour)
    eq("anker vandaag = nu", D.anchor(fri, 0, ams), fri)
    eq("anker morgen = zelfde tijd", D.anchor(fri, 1, ams) - D.start(fri, 1, ams), 12 * hour)

    // 3. soort en terugkijken
    eq("voorbij", Build164Logic.kind(0, 10, 10), Build164Logic.Kind.PAST)
    eq("nu", Build164Logic.kind(0, 10, 5), Build164Logic.Kind.NOW)
    eq("nu op de begintijd", Build164Logic.kind(5, 10, 5), Build164Logic.Kind.NOW)
    eq("toekomst", Build164Logic.kind(6, 10, 5), Build164Logic.Kind.FUTURE)
    ok("terugkijken binnen bereik", Build164Logic.canReplay(fri - 3 * hour, fri - 2 * hour, fri, 1))
    eq("terugkijken zonder terugkijkdagen", Build164Logic.canReplay(fri - 3 * hour, fri - 2 * hour, fri, 0), false)
    eq("terugkijken te oud", Build164Logic.canReplay(fri - 50 * hour, fri - 49 * hour, fri, 2), false)
    eq("lopend programma is geen terugkijken", Build164Logic.canReplay(fri - hour, fri + hour, fri, 7), false)

    // 4. index: binair zoeken op begintijd
    val day0 = D.start(fri, 0, ams)
    // gids van 3 dagen (gisteren t/m morgen), elk half uur een programma
    val list = (0 until 144).map { i -> P(day0 - 24 * hour + i * 30 * min, day0 - 24 * hour + (i + 1) * 30 * min, "p$i") }
    val st: (P) -> Long = { it.start }
    val en: (P) -> Long = { it.end }
    val nowIdx = I.at(list, fri, st, en)
    eq("NU-index", list[nowIdx].start, fri)
    eq("NU-index midden in een programma", I.at(list, fri + 10 * min, st, en), nowIdx)
    eq("NU-index vóór de gids", I.at(list, day0 - 30 * hour, st, en), -1)
    eq("NU-index na de gids", I.at(list, day0 + 60 * hour, st, en), -1)
    eq("lege lijst", I.at(emptyList<P>(), fri, st, en), -1)
    eq("eerste na: vóór de gids = 0", I.firstEndingAfter(list, day0 - 30 * hour, st, en), 0)
    eq("eerste na: na de gids = -1", I.firstEndingAfter(list, day0 + 60 * hour, st, en), -1)
    // gat in de gids
    val gap = listOf(P(0, 10, "a"), P(20, 30, "b"), P(30, 40, "c"))
    eq("gat: niets op 15", I.at(gap, 15, st, en), -1)
    eq("gat: eerstvolgende na 15", I.firstEndingAfter(gap, 15, st, en), 1)
    // overlappend programma (lang programma met een kort erin)
    val ov = listOf(P(0, 100, "lang"), P(10, 20, "kort"), P(100, 110, "na"))
    eq("overlap: 50 = het lange", I.at(ov, 50, st, en), 0)
    eq("overlap: 15 = het korte", I.at(ov, 15, st, en), 1)
    // ongesorteerd: valt terug op lineair zoeken
    val unsorted = listOf(P(20, 30, "b"), P(0, 10, "a"), P(40, 50, "c"), P(10, 20, "x"))
    eq("ongesorteerd: at", I.at(unsorted, 25, st, en), 0)
    eq("ongesorteerd: window", I.window(unsorted, 0, 15, st, en), 1..3)

    // 5. venster van één dag (alleen het zichtbare stuk)
    val w = I.window(list, D.start(fri, 0, ams), D.end(fri, 0, ams), st, en)
    eq("vandaag = 48 programma's", w.count(), 48)
    eq("vandaag begint om middernacht", list[w.first].start, day0)
    val wy = I.window(list, D.start(fri, -1, ams), D.end(fri, -1, ams), st, en)
    eq("gisteren = 48", wy.count(), 48)
    eq("overmorgen = leeg", I.window(list, D.start(fri, 2, ams), D.end(fri, 2, ams), st, en).isEmpty(), true)
    // programma over middernacht hoort bij beide dagen
    val late = listOf(P(day0 - hour, day0 + hour, "nacht"), P(day0 + hour, day0 + 2 * hour, "ochtend"))
    eq("over middernacht: vandaag", I.window(late, day0, day0 + 24 * hour, st, en), 0..1)
    eq("over middernacht: gisteren", I.window(late, day0 - 24 * hour, day0, st, en), 0..0)
    eq("NU-scroll: één regel erboven", I.scrollTarget(5), 4)
    eq("NU-scroll: bovenaan", I.scrollTarget(0), 0)

    // 6. tv-kolom: 2 eerder, NU, 4 later
    val col = I.column(list, fri, st, en)
    eq("kolom: NU", col.anchor, nowIdx)
    eq("kolom: 2 eerder", col.before, listOf(nowIdx - 2, nowIdx - 1))
    eq("kolom: 4 later", col.after, listOf(nowIdx + 1, nowIdx + 2, nowIdx + 3, nowIdx + 4))
    val colStart = I.column(list, list[0].start, st, en)
    eq("kolom aan het begin: niets eerder", colStart.before, emptyList<Int>())
    val colEnd = I.column(list, list.last().start, st, en)
    eq("kolom aan het eind: niets later", colEnd.after, emptyList<Int>())
    val colGap = I.column(gap, 15, st, en)
    eq("kolom in een gat: geen NU", colGap.anchor, -1)
    eq("kolom in een gat: eerder", colGap.before, listOf(0))
    eq("kolom in een gat: later", colGap.after, listOf(1, 2))
    eq("kolom zonder gids", I.column(emptyList<P>(), fri, st, en).anchor, -1)
    // prestaties: 12.000 zenders × 7 dagen gids, alleen binair zoeken
    val big = (0 until 7 * 48).map { i -> P(day0 + i * 30 * min, day0 + (i + 1) * 30 * min, "") }
    val t0 = System.nanoTime()
    var sum = 0
    repeat(12_000) { sum += I.column(big, fri + it, st, en).after.size }
    val ms = (System.nanoTime() - t0) / 1_000_000
    println("kolommen: 12.000 zenders in $ms ms")
    ok("kolommen snel genoeg (< 1500 ms)", ms < 1500 && sum > 0)

    // 7. stilstaand beeld
    eq("sleutel", S.key(" SRV1 ", "123"), "srv1|123")
    eq("sleutel zonder server", S.key(null, "123"), "|123")
    val f = S.fileName("srv1|123")
    ok("bestandsnaam alleen hex + .jpg", Regex("^[0-9a-f]{24}\\.jpg$").matches(f))
    eq("bestandsnaam vast", S.fileName("srv1|123"), f)
    ok("andere zender = ander bestand", S.fileName("srv2|123") != f)
    ok("vers beeld bruikbaar", S.usable(fri - 5 * min, fri))
    ok("29 min oud bruikbaar", S.usable(fri - 29 * min, fri))
    eq("31 min oud niet", S.usable(fri - 31 * min, fri), false)
    eq("geen beeld", S.usable(null, fri), false)
    eq("beeld uit de toekomst niet", S.usable(fri + 10 * min, fri), false)
    val entries = (0 until 205).map { Build164Logic.Stills.Entry("e$it", 1_000L + it) }
    eq("200 blijven: 5 weg", S.evict(entries).size, 5)
    eq("de oudste eerst weg", S.evict(entries), listOf("e0", "e1", "e2", "e3", "e4"))
    eq("onder de grens niets weg", S.evict(entries.take(200)), emptyList<String>())
    eq("formaat 16:9", S.size(1920, 1080), 320 to 180)
    eq("formaat 4:3", S.size(720, 540), 320 to 240)
    eq("formaat onbekend = 16:9", S.size(0, 0), 320 to 180)
    eq("live alleen bij het voorbeeld", S.show(true, null, fri), Build164Logic.Stills.Show.LIVE)
    eq("anders het stilstaande beeld", S.show(false, fri - min, fri), Build164Logic.Stills.Show.STILL)
    eq("te oud = logo", S.show(false, fri - 40 * min, fri), Build164Logic.Stills.Show.LOGO)
    eq("geen beeld = logo", S.show(false, null, fri), Build164Logic.Stills.Show.LOGO)

    // 8. instelling Live voorbeeld in de gids
    eq("standaard = Alleen wifi", V.parse(null, null), Build164Logic.Preview.Mode.WIFI)
    eq("oude schakelaar uit = Nooit", V.parse(null, false), Build164Logic.Preview.Mode.NEVER)
    eq("oude schakelaar aan = Alleen wifi", V.parse(null, true), Build164Logic.Preview.Mode.WIFI)
    eq("nieuwe waarde", V.parse("always", true), Build164Logic.Preview.Mode.ALWAYS)
    eq("oude schakelaar elders uitgezet = Nooit", V.parse("always", false), Build164Logic.Preview.Mode.NEVER)
    eq("oude schakelaar elders weer aan = Alleen wifi", V.parse("never", true), Build164Logic.Preview.Mode.WIFI)
    eq("Nooit zonder oude schakelaar", V.parse("never", null), Build164Logic.Preview.Mode.NEVER)
    eq("onbekende waarde = oude schakelaar", V.parse("??", true), Build164Logic.Preview.Mode.WIFI)
    eq("Nooit = uit (ook op tv)", V.start(Build164Logic.Preview.Mode.NEVER, isTV = true, metered = false), Build164Logic.Preview.Start.OFF)
    eq("tv + Alleen wifi = aan", V.start(Build164Logic.Preview.Mode.WIFI, isTV = true, metered = true), Build164Logic.Preview.Start.AUTO)
    eq("telefoon wifi = aan", V.start(Build164Logic.Preview.Mode.WIFI, isTV = false, metered = false), Build164Logic.Preview.Start.AUTO)
    eq("telefoon mobiel = na een tik", V.start(Build164Logic.Preview.Mode.WIFI, isTV = false, metered = true), Build164Logic.Preview.Start.TAP)
    eq("Altijd op mobiel = aan", V.start(Build164Logic.Preview.Mode.ALWAYS, isTV = false, metered = true), Build164Logic.Preview.Start.AUTO)
    eq("tik nodig, geen tik", V.mayStart(Build164Logic.Preview.Start.TAP, false), false)
    ok("tik nodig, getikt", V.mayStart(Build164Logic.Preview.Start.TAP, true))
    eq("uit, ook met tik", V.mayStart(Build164Logic.Preview.Start.OFF, true), false)
    ok("nooit geblokkeerd", V.mayTry(null, fri))
    eq("net geblokkeerd", V.mayTry(fri - 10_000, fri), false)
    ok("na een minuut weer", V.mayTry(fri - 61_000, fri))
    eq("soort voor de streamlimiet", V.STREAM_KIND, "preview")

    println(if (failures == 0) "Build164LogicTest: $checks controles geslaagd" else "Build164LogicTest: $failures van $checks controles mislukt")
    if (failures > 0) kotlin.system.exitProcess(1)
}
