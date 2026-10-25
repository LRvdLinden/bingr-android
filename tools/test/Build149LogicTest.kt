package nl.lrvdlinden.bingr

import java.util.concurrent.atomic.AtomicLong

// Build 149: unit- en logica-tests van features/Build149Logic.kt (datums, vouwen, streamend inlezen, verborgen
// categorieën overslaan, Ontdek categorieën, zoeken) en een belastingtest met 123.000 films, 30.000 series en 12.000
// zenders waarvan 80% in verborgen categorieën:
// - vóór (build 148): hele JSON-boom van elke lijst (per item een map met alle velden), alles omzetten en bewaren;
// - na (build 149): per item alleen de nodige velden, items uit verborgen categorieën alleen tellen.
// Gemeten: piekgeheugen (heap, bemonsterd) en de duur van het inlezen (op de achtergrond; de hoofdthread krijgt alleen
// het resultaat).
// Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/features/Build149Logic.kt tools/test/Build149LogicTest.kt \
//     -include-runtime -d /tmp/b149.jar
//   java -Xmx1500m -jar /tmp/b149.jar
// Zelfde gevallen als tizen/test/build149_test.js.

private var failures = 0
private var checks = 0
private fun eq(name: String, got: Any?, want: Any?) {
    checks++
    if (got != want) { failures++; println("FOUT $name kreeg <$got>, verwacht <$want>"); println("::error title=Build149LogicTest::$name") }
}
private fun ok(name: String, v: Boolean) = eq(name, v, true)

private data class Film(val id: String, val title: String, val group: String, val url: String, val poster: String?, val rating: Double?, val added: Long?)

private fun genList(n: Int, cats: Int, kind: String): String {
    val sb = StringBuilder(n * 420)
    sb.append('[')
    for (i in 0 until n) {
        if (i > 0) sb.append(',')
        val cat = i % cats
        sb.append("{\"num\":").append(i + 1).append(",\"name\":\"NL | Titel nummer ").append(i).append(" (20").append(10 + i % 15).append(")\",")
        sb.append("\"stream_type\":\"").append(kind).append("\",\"").append(if (kind == "series") "series_id" else "stream_id").append("\":").append(100000 + i).append(',')
        sb.append("\"").append(if (kind == "series") "cover" else "stream_icon").append("\":\"https://img.example.invalid/posters/").append(kind).append('/').append(i).append(".jpg\",")
        sb.append("\"rating\":\"").append(5 + i % 5).append(".").append(i % 10).append("\",\"rating_5based\":3.5,\"added\":\"").append(1600000000 + i).append("\",")
        sb.append("\"category_id\":\"").append(cat).append("\",\"category_ids\":[").append(cat).append("],")
        sb.append("\"container_extension\":\"mkv\",\"custom_sid\":null,\"direct_source\":\"\",\"is_adult\":0,")
        if (kind == "series") sb.append("\"plot\":\"Een verhaal over iets dat gebeurt, met mensen en plaatsen, aflevering na aflevering.\",\"backdrop_path\":[\"https://img.example.invalid/b/").append(i).append(".jpg\"],")
        if (kind == "live") sb.append("\"epg_channel_id\":\"ch").append(i).append(".nl\",\"tv_archive\":0,\"tv_archive_duration\":0,")
        sb.append("\"youtube_trailer\":\"\",\"tmdb\":\"").append(i).append("\"}")
    }
    sb.append(']')
    return sb.toString()
}

/** Bemonstert de heap tot [stop]. */
private class Peak {
    private val rt = Runtime.getRuntime()
    val peak = AtomicLong(0)
    @Volatile var run = true
    private val t = Thread { while (run) { val u = rt.totalMemory() - rt.freeMemory(); if (u > peak.get()) peak.set(u); Thread.sleep(2) } }
    fun start(): Peak { System.gc(); Thread.sleep(50); peak.set(rt.totalMemory() - rt.freeMemory()); t.isDaemon = true; t.start(); return this }
    fun stop(): Long { run = false; t.join(); return peak.get() / (1024 * 1024) }
}

/** "Vóór": minimale JSON-boom (per item een map met alle eenvoudige velden), zoals kotlinx parseToJsonElement. */
private fun treeParse(text: String): List<Map<String, String?>> {
    val keys = listOf("num", "name", "stream_type", "stream_id", "series_id", "stream_icon", "cover", "rating", "rating_5based", "added",
        "category_id", "container_extension", "custom_sid", "direct_source", "is_adult", "plot", "epg_channel_id", "tv_archive",
        "tv_archive_duration", "youtube_trailer", "tmdb")
    val out = ArrayList<Map<String, String?>>()
    Build149Logic.Stream.forEach(text, keys) { r -> val m = HashMap<String, String?>(32); for (k in keys) m[k] = r[k]; out += m }
    return out
}

fun main() {
    val D = Build149Logic.Dates
    // MARK: datums
    eq("iso: Z met ms", D.parseIso("2026-10-12T18:00:00.000Z"), 1791828000000L)
    eq("iso: zonder ms", D.parseIso("2026-10-12T18:00:00Z"), 1791828000000L)
    eq("iso: 7 decimalen (Jellyfin)", D.parseIso("2026-10-12T18:00:00.1234567Z"), 1791828000123L)
    eq("iso: +02:00", D.parseIso("2026-10-12T20:00:00+02:00"), 1791828000000L)
    eq("iso: +0200", D.parseIso("2026-10-12T20:00:00+0200"), 1791828000000L)
    eq("iso: zonder zone = UTC", D.parseIso("2026-10-12T18:00:00"), 1791828000000L)
    eq("iso: spatie", D.parseIso("2026-10-12 18:00:00"), 1791828000000L)
    eq("iso: alleen datum", D.parseIso("2026-10-12"), 1791763200000L)
    eq("iso: onzin", D.parseIso("gisteren"), null)
    eq("iso: maand 13", D.parseIso("2026-13-01"), null)
    eq("iso: voor 1970", D.parseIso("1969-12-31T23:59:59.000Z"), -1000L)
    eq("iso: opmaken", D.formatIso(1791828000123L), "2026-10-12T18:00:00.123Z")
    eq("iso: opmaken 0", D.formatIso(0L), "1970-01-01T00:00:00.000Z")
    eq("iso: opmaken schrikkeldag", D.formatIso(D.parseIso("2024-02-29T23:59:59.999Z")!!), "2024-02-29T23:59:59.999Z")
    eq("iso: seconden", D.formatIsoSeconds(1791828000123L), "2026-10-12T18:00:00Z")
    var rt = true
    val rnd = java.util.Random(149)
    repeat(20000) { val ms = (rnd.nextLong() % 4_000_000_000_000L); if (D.parseIso(D.formatIso(ms)) != ms) rt = false }
    ok("iso: heen en terug (20.000 willekeurige tijden)", rt)
    val ref = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
    var same = true
    repeat(5000) { val ms = Math.abs(rnd.nextLong() % 3_000_000_000_000L); if (ref.format(java.util.Date(ms)) != D.formatIso(ms)) same = false }
    ok("iso: gelijk aan SimpleDateFormat (5.000 tijden)", same)
    eq("xmltv: met zone", D.parseXmltv("20261012200000 +0200"), 1791828000000L)
    eq("xmltv: zonder zone", D.parseXmltv("20261012180000"), 1791828000000L)
    eq("xmltv: -0500", D.parseXmltv("20261012130000 -0500"), 1791828000000L)
    eq("xmltv: onzin", D.parseXmltv("2026"), null)
    val before = D.cachedCount()
    repeat(1000) { D.fmt("yyyy-MM-dd").format(java.util.Date(it * 86_400_000L)) }
    eq("fmt: één opmaker per patroon (1.000 keer gebruikt)", D.cachedCount() - before, 1)
    // snelheid: 100.000 keer parsen en opmaken (vóór: per item een SimpleDateFormat)
    var t0 = System.nanoTime()
    repeat(100_000) { java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(it * 1000L)) }
    val oldMs = (System.nanoTime() - t0) / 1_000_000
    t0 = System.nanoTime()
    repeat(100_000) { D.formatIso(it * 1000L) }
    val newMs = (System.nanoTime() - t0) / 1_000_000
    println("datums: 100.000 keer opmaken vóór $oldMs ms (opmaker per item), na $newMs ms")
    ok("datums: sneller dan een opmaker per item", newMs <= oldMs)

    // MARK: vouwen
    val F = Build149Logic.Fold
    ok("fold: ascii", F.isAscii("The Batman 2022"))
    ok("fold: geen ascii", !F.isAscii("Amélie"))
    eq("fold: kleine letters", F.lowerAscii("The Batman"), "the batman")
    eq("fold: norm", F.normAscii("The Batman: Part II"), "thebatmanpartii")

    // MARK: streamend inlezen
    val S = Build149Logic.Stream
    val got = ArrayList<String>()
    val n = S.forEach("""[{"stream_id":1,"name":"A \"x\" é","nested":{"a":[1,2,{"b":"]"}]},"category_id":"5"},{"stream_id":"2","name":"B","rating":null,"ok":true}]""",
        listOf("stream_id", "name", "category_id", "ok")) { r -> got += "${r["stream_id"]}|${r["name"]}|${r["category_id"]}|${r["ok"]}" }
    eq("stream: aantal", n, 2)
    eq("stream: velden", got, listOf("1|A \"x\" é|5|null", "2|B|null|1"))
    val got2 = ArrayList<String>()
    S.forEach("""{"10":{"stream_id":10,"name":"X"},"11":{"stream_id":11,"name":"Y"}}""", listOf("stream_id")) { got2 += it["stream_id"]!! }
    eq("stream: object met objecten", got2, listOf("10", "11"))
    eq("stream: leeg", S.forEach("[]", listOf("a")) {}, 0)
    var bad = false
    try { S.forEach("[{\"a\":1,}", listOf("a")) {} } catch (e: IllegalArgumentException) { bad = true }
    ok("stream: kapotte JSON → fout", bad)
    var stopped = false
    try { S.forEach(genList(5000, 10, "movie"), listOf("name"), S.memoryCheck(0.5, { 90 }, { 100 })) {} } catch (e: Build149Logic.TooLarge) { stopped = true }
    ok("geheugengrens: stopt netjes (TooLarge)", stopped)

    // MARK: overslaan
    val sk = Build149Logic.Skip { it.startsWith("Verborgen") }
    listOf("Verborgen A", "Zichtbaar", "Verborgen A", "Verborgen B").forEach { sk.skip(it) }
    eq("skip: tellingen", sk.counts, mapOf("Verborgen A" to 2, "Verborgen B" to 1))
    eq("skip: totaal", sk.total, 3)

    // MARK: Ontdek categorieën
    val DC = Build149Logic.Discover
    eq("discover: sleutel", DC.key("Disney+ Series"), "disneyplusseries")
    val prov = mapOf("netflix" to "n.png", "disneyplus" to "d.png", "hbomax" to "h.png", "amazonprimevideo" to "p.png", "videoland" to "v.png")
    eq("discover: Netflix", DC.providerFor("Netflix", prov), "netflix")
    eq("discover: Netflix Films 4K", DC.providerFor("Netflix Films 4K", prov), "netflix")
    eq("discover: Disney+ Series", DC.providerFor("Disney+ Series", prov), "disneyplus")
    eq("discover: HBO", DC.providerFor("HBO", prov), "hbomax")
    eq("discover: Prime Video", DC.providerFor("Prime Video", prov), "amazonprimevideo")
    eq("discover: Actie (geen dienst)", DC.providerFor("Actie", prov), null)
    eq("discover: kleur stabiel", DC.colorFor("Netflix"), DC.colorFor("netflix"))
    eq("discover: hash gelijk aan JS", DC.hash("netflix"), -381125525)
    data class M(val g: String, val p: String?)
    val films = (0 until 60).map { M(listOf("NL | Netflix", "NL | Actie", "Kids", "Klein")[if (it < 57) it % 3 else 3], "p$it") }
    val rows = DC.rows(films, emptyList<M>(), { it.g }, { it.g }, { it.p }, { it.p }, { it.removePrefix("NL | ") }, false, { it == "Kids" })
    eq("discover: rijen (klein telt niet)", rows.map { it.title }, listOf("Netflix", "Actie", "Kids"))
    eq("discover: 4 posters", rows[0].posters.size, 4)
    eq("discover: kinderprofiel met andere categorieën → niets", DC.rows(films, emptyList<M>(), { it.g }, { it.g }, { it.p }, { it.p }, { it }, true, { it == "Kids" }).size, 0)
    ok("discover: gedempte kleur", DC.mutedAverage(intArrayOf(0xFFFF0000.toInt(), 0xFFFFFFFF.toInt())) != null)

    // MARK: zoeken
    val Q = Build149Logic.Search
    eq("zoeken: sleutel accenten", Q.key("Amélie"), "amelie")
    eq("zoeken: sleutel voorvoegsel", Q.key("NL | The Lord of the Rings"), "the lord of the rings")
    eq("zoeken: sleutel 4K -", Q.key("4K - Dune: Part Two"), "dune part two")
    eq("zoeken: sleutel [NL]", Q.key("[NL] Fast & Furious"), "fast and furious")
    eq("zoeken: apostrof", Q.key("Grey's Anatomy"), "greys anatomy")
    val titles = listOf("NL | The Lord of the Rings: The Two Towers", "Rings", "Lord of War", "Amélie", "Fast & Furious 7", "[NL] Ringo", "Breaking Bad", "Stranger Things")
    eq("zoeken: woordvolgorde", Q.find(titles, "rings lord", 5) { it }, listOf("NL | The Lord of the Rings: The Two Towers"))
    eq("zoeken: accenten", Q.find(titles, "amelie", 5) { it }, listOf("Amélie"))
    eq("zoeken: & en and", Q.find(titles, "fast and furious", 5) { it }, listOf("Fast & Furious 7"))
    eq("zoeken: ranking exact eerst", Q.find(titles, "rings", 5) { it }.first(), "Rings")
    eq("zoeken: deelwoord", Q.find(titles, "ring", 5) { it }.toSet(), setOf("Rings", "[NL] Ringo", "NL | The Lord of the Rings: The Two Towers"))
    eq("zoeken: woordbegin vóór bevat", Q.find(listOf("Unbreakable", "Breaking Bad"), "break", 5) { it }, listOf("Breaking Bad", "Unbreakable"))
    eq("zoeken: één typefout (≥ 5 letters)", Q.find(titles, "strnger things", 5) { it }, listOf("Stranger Things"))
    eq("zoeken: typefout kort woord niet", Q.find(titles, "bad breakng", 5) { it }, listOf("Breaking Bad"))
    eq("zoeken: geen typefout bij korte woorden", Q.find(titles, "bda", 5) { it }, emptyList<String>())
    ok("zoeken: oneEdit omgedraaid", Q.oneEdit("strnager", "stranger"))

    // MARK: belastingtest 123k films, 30k series, 12k zenders, 80% verborgen
    println("belastingtest: lijsten maken …")
    val vod = genList(123_340, 400, "movie"); val ser = genList(30_322, 120, "series"); val live = genList(11_929, 60, "live")
    println("belastingtest: tekst ${(vod.length + ser.length + live.length) / (1024 * 1024)} MB")
    fun hiddenCat(id: String) = (id.toIntOrNull() ?: 0) % 5 != 0   // 80% van de categorieën verborgen
    System.gc()
    // vóór
    var oldPeak = -1L; var oldMs2 = -1L; var oldCount = 0
    try {
        val p = Peak().start(); val t = System.nanoTime()
        val trees = listOf(treeParse(vod), treeParse(ser), treeParse(live))
        val all = trees.flatMap { l -> l.map { m -> Film(m["stream_id"] ?: m["series_id"] ?: "", m["name"] ?: "", "Cat " + m["category_id"], "http://x/" + m["stream_id"] + ".mkv", m["stream_icon"] ?: m["cover"], m["rating"]?.toDoubleOrNull(), m["added"]?.toLongOrNull()) } }
        oldCount = all.size
        oldMs2 = (System.nanoTime() - t) / 1_000_000; oldPeak = p.stop()
    } catch (e: OutOfMemoryError) { println("vóór: geheugen op (OOM)") }
    System.gc(); Thread.sleep(100)
    // na
    val p2 = Peak().start(); val t2 = System.nanoTime()
    val sv = Build149Logic.Skip { hiddenCat(it.removePrefix("Cat ")) }
    val kept = ArrayList<Film>()
    for ((text, idKey, posterKey) in listOf(Triple(vod, "stream_id", "stream_icon"), Triple(ser, "series_id", "cover"), Triple(live, "stream_id", "stream_icon"))) {
        S.forEach(text, listOf(idKey, "name", posterKey, "category_id", "rating", "added", "container_extension")) { r ->
            val g = "Cat " + r["category_id"]
            if (sv.skip(g)) return@forEach
            kept += Film(r[idKey] ?: "", r["name"] ?: "", g, "http://x/" + r[idKey] + "." + r["container_extension"], r[posterKey], r.double("rating"), r["added"]?.toLongOrNull())
        }
    }
    val newMs2 = (System.nanoTime() - t2) / 1_000_000; val newPeak = p2.stop()
    val textMB = (vod.length + ser.length + live.length) * 2L / (1024 * 1024)
    println("geheugen vóór: piek ${oldPeak} MB (alles ingelezen, $oldCount items, ${oldMs2} ms) · na: piek ${newPeak} MB (${kept.size} ingelezen, ${sv.total} verborgen geteld, ${newMs2} ms) · tekst zelf $textMB MB")
    eq("belasting: ingelezen + verborgen = geleverd", kept.size + sv.total, 123_340 + 30_322 + 11_929)
    ok("belasting: ~20% ingelezen", kept.size in 25_000..40_000)
    ok("belasting: piek na < 500 MB", newPeak < 500)
    if (oldPeak > 0) ok("belasting: piek na lager dan vóór", newPeak < oldPeak)
    // zoeken over de ingelezen titels, vooraf berekende sleutels
    t0 = System.nanoTime(); Q.find(kept, "titel 1234", 50) { it.title }; val firstMs = (System.nanoTime() - t0) / 1_000_000
    t0 = System.nanoTime(); val hits = Q.find(kept, "nummer 999", 50) { it.title }; val againMs = (System.nanoTime() - t0) / 1_000_000
    println("zoeken: eerste keer $firstMs ms (sleutels maken), daarna $againMs ms (${kept.size} titels, ${hits.size} treffers)")
    ok("zoeken: tweede keer sneller (vooraf berekend)", againMs <= firstMs)

    println(if (failures == 0) "Build149Logic: alle $checks controles geslaagd" else "Build149Logic: $failures van $checks controles mislukt")
    kotlin.system.exitProcess(if (failures > 0) 1 else 0)
}
