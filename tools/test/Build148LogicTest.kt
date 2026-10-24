package nl.lrvdlinden.bingr

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

// Build 148: unit- en logica-tests van features/Build148Logic.kt (codes, bijwerken in fasen, geleverd en zichtbaar,
// haperingen) en een belastingtest met 37.000 films, 12.000 zenders en 9.000 series (plus categorieën):
// - inlezen en toepassen op de achtergrond (zoals Dispatchers.Default), de hoofdthread krijgt alleen het eindresultaat
//   en zit nooit langer dan 250 ms vast (op een gedeelde CI-runner is de tijdsgrens een waarschuwing, de uitkomst is hard);
// - vóór/na: Compose-state met structurele gelijkheid (vóór build 148) tegen referentiële gelijkheid (build 148);
// - vóór/na: geheugen bij alle ruwe lijsten tegelijk (vóór) tegen per soort omzetten en loslaten (build 148).
// Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/features/Build147Logic.kt app/src/main/java/nl/lrvdlinden/bingr/features/Build148Logic.kt \
//     tools/test/Build148LogicTest.kt -include-runtime -d /tmp/b148.jar
//   java -Xmx512m -jar /tmp/b148.jar
// Zelfde gevallen als tizen/test/build148_test.js.

private var failures = 0
private var checks = 0
private var warnings = 0
private fun eq(name: String, got: Any?, want: Any?) {
    checks++
    if (got != want) { failures++; println("FOUT $name kreeg <$got>, verwacht <$want>"); println("::error title=Build148LogicTest::$name") }
}
private fun ok(name: String, v: Boolean) = eq(name, v, true)
/** Tijdsgrens: op een gedeelde runner alleen een waarschuwing. */
private fun soft(name: String, v: Boolean) {
    checks++
    if (!v) { warnings++; println("WAARSCHUWING $name"); println("::warning title=Build148LogicTest::$name") }
}

private data class Film(val id: String, val title: String, val group: String, val url: String, val year: Int?, val rating: Double?)
private data class Zender(val id: String, val name: String, val group: String, val url: String, val number: Int?)
private data class Serie(val id: String, val title: String, val group: String, val plot: String?, val year: Int?)
private data class Lib(val channels: List<Zender>, val movies: List<Film>, val series: List<Serie>)

/** Ruwe JSON zoals kotlinx die inleest: per item een map met strings (JsonObject/JsonPrimitive). */
private typealias Raw = List<Map<String, String>>

private fun rawMovies(n: Int, cats: Int) = (0 until n).map { i ->
    hashMapOf("stream_id" to "$i", "name" to "Film titel ${i * 7919 % 1_000_003} (${1980 + i % 45})", "category_id" to "${i % cats}",
        "stream_icon" to "http://img.example/p/$i.jpg", "rating" to "${(i % 100) / 10.0}", "added" to "${1_700_000_000 + i}",
        "container_extension" to "mkv", "year" to "${1980 + i % 45}")
}
private fun rawChannels(n: Int, cats: Int) = (0 until n).map { i ->
    hashMapOf("stream_id" to "$i", "name" to "Zender $i HD", "category_id" to "${i % cats}", "stream_icon" to "http://img.example/l/$i.png",
        "epg_channel_id" to "z$i.nl", "num" to "$i", "tv_archive" to "${i % 2}", "tv_archive_duration" to "3")
}
private fun rawSeries(n: Int, cats: Int) = (0 until n).map { i ->
    hashMapOf("series_id" to "$i", "name" to "Serie ${i * 31 % 100_003}", "category_id" to "${i % cats}", "cover" to "http://img.example/s/$i.jpg",
        "plot" to "Een korte beschrijving van serie $i met wat tekst.", "year" to "${1990 + i % 30}")
}
private fun cats(n: Int, prefix: String) = (0 until n).associate { "$it" to "$prefix ${it}" }

private fun parseMovies(r: Raw, c: Map<String, String>) = r.mapNotNull { v ->
    val id = v["stream_id"] ?: return@mapNotNull null
    Film("xv$id", v["name"] ?: "", c[v["category_id"]] ?: "Other", "http://srv/movie/u/p/$id.${v["container_extension"] ?: "mp4"}",
        v["year"]?.toIntOrNull(), v["rating"]?.toDoubleOrNull())
}
private fun parseChannels(r: Raw, c: Map<String, String>) = r.mapNotNull { l ->
    val id = l["stream_id"] ?: return@mapNotNull null
    Zender("x$id", l["name"] ?: "", c[l["category_id"]] ?: "Other", "http://srv/live/u/p/$id.ts", l["num"]?.toIntOrNull())
}
private fun parseSeries(r: Raw, c: Map<String, String>) = r.mapNotNull { s ->
    val id = s["series_id"] ?: return@mapNotNull null
    Serie("xs$id", s["name"] ?: "", c[s["category_id"]] ?: "Other", s["plot"], s["year"]?.toIntOrNull())
}

private fun usedMB(): Long {
    val rt = Runtime.getRuntime()
    repeat(3) { System.gc(); Thread.sleep(40) }
    return (rt.totalMemory() - rt.freeMemory()) / 1_048_576L
}

/** "Hoofdthread" met frames elke 16 ms en een meting van het langste gat (zoals in Build147LogicTest). */
private class FakeMain {
    val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "main") }
    private val lastFrame = AtomicLong(now())
    val maxGap = AtomicLong(0)
    @Volatile private var running = true
    private val frames = Thread {
        while (running) {
            exec.execute { val t = now(); val g = t - lastFrame.getAndSet(t); if (g > maxGap.get()) maxGap.set(g) }
            Thread.sleep(16)
        }
    }
    init { frames.isDaemon = true; frames.start() }
    fun resetGap() { lastFrame.set(now()); maxGap.set(0) }
    fun stop() { running = false; exec.shutdown(); exec.awaitTermination(5, TimeUnit.SECONDS) }
    companion object { fun now() = System.nanoTime() / 1_000_000 }
}

private fun parseAllAtOnce(nm: Int, nc: Int, ns: Int, mc: Map<String, String>, cc: Map<String, String>, sc: Map<String, String>): Pair<Lib, Long> {
    val base = usedMB()
    val rm = rawMovies(nm, 400); val rc = rawChannels(nc, 300); val rs = rawSeries(ns, 200)
    val lib = Lib(parseChannels(rc, cc), parseMovies(rm, mc), parseSeries(rs, sc))
    val peak = usedMB() - base   // ruwe lijsten én resultaat tegelijk
    check(rm.size + rc.size + rs.size > 0 && lib.movies.isNotEmpty())
    return lib to peak
}

private fun parsePerKind(nm: Int, nc: Int, ns: Int, mc: Map<String, String>, cc: Map<String, String>, sc: Map<String, String>): Pair<Lib, Long> {
    val base = usedMB()
    var peak = 0L
    fun <R, T> one(raw: () -> R, parse: (R) -> T): T { val r = raw(); val x = parse(r); peak = maxOf(peak, usedMB() - base); check(r != null); return x }
    val ch = one({ rawChannels(nc, 300) }) { parseChannels(it, cc) }
    val mv = one({ rawMovies(nm, 400) }) { parseMovies(it, mc) }
    val se = one({ rawSeries(ns, 200) }) { parseSeries(it, sc) }
    return Lib(ch, mv, se) to peak
}

fun main() {
    val C = Build148Logic.Codes
    // MARK: codes
    eq("codes: vaste lijst (26)", C.FIXED.size, 26)
    ok("codes: alle codes in de juiste stijl", C.ALL.all { C.valid(it) })
    eq("codes: start → started", C.of("start"), "started")
    eq("codes: activity → first_frame", C.of("activity"), "first_frame")
    eq("codes: profiles → profile_picker", C.of("profiles"), "profile_picker")
    eq("codes: pin → pin_screen", C.of("pin"), "pin_screen")
    eq("codes: servers_loaded → servers", C.of("servers_loaded"), "servers")
    eq("codes: ready blijft", C.of("ready"), "ready")
    eq("codes: oude taak sync toepassen", C.of("sync toepassen"), "sync_apply")
    eq("codes: oude taak rijen opbouwen", C.of("rijen opbouwen"), "rows_build")
    eq("codes: onbekende code in de juiste stijl komt door", C.of("foo_bar"), "foo_bar")
    eq("codes: tekst zonder code valt weg", C.of("Iets Anders!"), null)
    eq("codes: label", C.label("catalog_sort"), "Sorting catalog")
    eq("codes: onbekend label = code", C.label("foo_bar"), "foo_bar")
    ok("codes: elke code een label", C.ALL.all { C.label(it) != it })
    eq("codes: dubbele code één keer", C.dedupe(listOf("start", "activity", "splash", "ready")) { it }.map { it.first },
        listOf("started", "first_frame", "ready"))

    // MARK: bijwerken in fasen
    run {
        val r = Build148Logic.Updates.Recorder("s1", 1_000L, 0L, 100)
        r.phase("fetch", 3_900, 210); r.phase("parse", 10_100, 455); r.phase("fetch", 10_600, 300)
        r.phase("save", 14_400, 320); r.phase("index", 16_500, 430); r.phase("apply", 22_000, 440)
        r.phase("onzin", 23_000, 999)
        val u = r.finish("ok", 22_000)
        eq("bijwerken: fasen in vaste volgorde", u.phases.map { it.code }, listOf("fetch", "parse", "index", "apply", "save"))
        eq("bijwerken: dezelfde fase telt op", u.phases.first { it.code == "fetch" }.ms, 4_400L)
        eq("bijwerken: geheugen aan het eind van de fase", u.phases.first { it.code == "parse" }.memMB, 455)
        eq("bijwerken: totaal", u.totalMs, 22_000L)
        eq("bijwerken: piek", u.peakMB, 455)
        eq("bijwerken: onbekende fase telt niet", u.phases.size, 5)
        eq("bijwerken: uitkomst", u.result, "ok")
        eq("bijwerken: onbekende uitkomst = failed", r.finish("raar", 22_000).result, "failed")
        var list = emptyList<Build148Logic.Updates.Update>()
        repeat(7) { i -> list = Build148Logic.Updates.push(list, u.copy(server = "s${i + 1}")) }
        eq("bijwerken: hooguit 5, nieuwste eerst", list.map { it.server }, listOf("s7", "s6", "s5", "s4", "s3"))
    }

    // MARK: geleverd en zichtbaar
    run {
        val H = Build148Logic.Hidden
        eq("verborgen: voorbeeld uit de diagnose", H.of(36_593, 10_286, 26_307, 0, 0), Build148Logic.Hidden.Counts(36_593, 10_286, 26_307, 0, 0, 0))
        eq("verborgen: rest = overig", H.of(100, 50, 30, 5, 0), Build148Logic.Hidden.Counts(100, 50, 30, 5, 0, 15))
        eq("verborgen: nooit negatief", H.of(10, 20, 5, 5, 5), Build148Logic.Hidden.Counts(10, 10, 0, 0, 0, 0))
        val items = (0 until 1000).map { "g${it % 10}" }
        val c = H.count(items, 600, { it }, { it == "g1" || it == "g2" }, { it == "g3" })
        eq("verborgen: tellen per groep", c, Build148Logic.Hidden.Counts(1000, 600, 200, 100, 0, 100))
    }

    // MARK: haperingen
    run {
        val t = Build148Logic.Stalls.Tracker()
        eq("hapering: bufferen vóór het eerste beeld telt niet", t.update(0, false, true, true), null)
        eq("hapering: eerste beeld", t.update(1_000, true, false, true), null)
        eq("hapering: begint", t.update(5_000, false, true, true), null)
        val s = t.update(6_500, true, false, true)
        eq("hapering: duur en moment", s, Build148Logic.Stalls.Stall(1_500, 4, true))
        t.update(7_000, false, true, true)
        eq("hapering: korter dan 0,5 s telt niet", t.update(7_300, true, false, true), null)
        var l = emptyList<Build148Logic.Stalls.Stall>()
        repeat(12) { l = Build148Logic.Stalls.push(l, s!!) }
        eq("hapering: hooguit 10", l.size, 10)
    }

    // MARK: 37.000 films, 12.000 zenders, 9.000 series
    val nm = 37_000; val nc = 12_000; val ns = 9_000
    val mc = cats(400, "Films"); val cc = cats(300, "Zenders"); val sc = cats(200, "Series")
    val hidden = (0 until 290).map { "Films $it" }.toSet() + (0 until 270).map { "Zenders $it" } + (0 until 120).map { "Series $it" }

    // vóór: alle ruwe lijsten tegelijk in het geheugen, dan omzetten; na (build 148): per soort omzetten en loslaten.
    // Elk in een eigen functie (geen inline-blok: lokale variabelen van main() blijven anders bereikbaar).
    val (oldLib, oldPeak) = parseAllAtOnce(nm, nc, ns, mc, cc, sc)
    val (newLib, newPeak) = parsePerKind(nm, nc, ns, mc, cc, sc)
    println("geheugen 37k/12k/9k: vóór (alle ruwe lijsten tegelijk) +$oldPeak MB, na (per soort, ruwe lijst los) +$newPeak MB")
    eq("geheugen: zelfde uitkomst", newLib, oldLib)
    soft("geheugen: per soort minder piek ($newPeak < $oldPeak MB)", newPeak < oldPeak)

    // vóór/na: Compose-state. Structureel = oude en nieuwe lijst item voor item vergelijken op de hoofdthread.
    val copy = Lib(oldLib.channels.map { it.copy() }, oldLib.movies.map { it.copy() }, oldLib.series.map { it.copy() })
    val t0 = System.nanoTime()
    var same = false
    repeat(5) { same = copy == newLib }
    val structMs = (System.nanoTime() - t0) / 5_000_000.0
    val t1 = System.nanoTime()
    var ident = true
    repeat(5) { ident = copy === newLib }
    val refMs = (System.nanoTime() - t1) / 5_000_000.0
    println("toewijzen aan Compose-state (37k/12k/9k, zelfde inhoud): structureel %.1f ms, referentieel %.3f ms".format(structMs, refMs))
    ok("state: structureel vergelijkt alles (zelfde inhoud)", same)
    ok("state: referentieel vergelijkt alleen het object", !ident)

    val main = FakeMain()
    try {
        Thread.sleep(200)
        main.resetGap()
        val bg = Executors.newFixedThreadPool(3)
        val tStart = System.nanoTime()
        // zoals de app: drie soorten parallel inlezen, dan filteren en indexeren op de achtergrond
        val fc = bg.submit<List<Zender>> { parseChannels(rawChannels(nc, 300), cc).filter { it.group !in hidden } }
        val fm = bg.submit<List<Film>> { parseMovies(rawMovies(nm, 400), mc).filter { it.group !in hidden } }
        val fs = bg.submit<List<Serie>> { parseSeries(rawSeries(ns, 200), sc).filter { it.group !in hidden } }
        val lib = Lib(fc.get(), fm.get(), fs.get())
        val index = bg.submit<Map<String, Any>> { HashMap<String, Any>().apply { lib.movies.forEach { put(it.id, it) }; lib.series.forEach { put(it.id, it) }; lib.channels.forEach { put(it.id, it) } } }.get()
        val bgMs = (System.nanoTime() - tStart) / 1_000_000
        // toepassen op de hoofdthread: alleen toewijzen (referentieel)
        val applyMs = main.exec.submit<Long> { val a = FakeMain.now(); check(lib !== oldLib); FakeMain.now() - a }.get()
        Thread.sleep(150)
        val gap = main.maxGap.get()
        println("37k/12k/9k op de achtergrond: $bgMs ms; toepassen op de hoofdthread $applyMs ms; langste gat $gap ms")
        soft("37k: hoofdthread nooit langer dan 250 ms vast (gat $gap ms)", gap <= 250)
        soft("37k: toepassen op de hoofdthread ≤ 250 ms ($applyMs ms)", applyMs <= 250)
        eq("37k: films zichtbaar", lib.movies.size, oldLib.movies.count { it.group !in hidden })
        eq("37k: zenders zichtbaar", lib.channels.size, oldLib.channels.count { it.group !in hidden })
        eq("37k: series zichtbaar", lib.series.size, oldLib.series.count { it.group !in hidden })
        ok("37k: catalogus volledig", oldLib.movies.size == nm && oldLib.channels.size == nc && oldLib.series.size == ns)
        eq("37k: index", index.size, lib.movies.size + lib.series.size + lib.channels.size)
        val counts = Build148Logic.Hidden.count(oldLib.movies, lib.movies.size, { it.group }, { it in hidden }, { false })
        eq("37k: verborgen door categorieën", counts.categories, nm - lib.movies.size)

        // in stukken óp de hoofdthread (zoals een tv zonder threads): geen stuk langer dan 250 ms
        main.resetGap()
        val longest = AtomicLong(0)
        val out = ArrayList<Film>()
        val done = java.util.concurrent.CountDownLatch(1)
        val all = oldLib.movies
        fun slice(from: Int) {
            main.exec.execute {
                val s0 = FakeMain.now()
                var i = from
                while (i < all.size) {
                    val m = all[i]; if (m.group !in hidden) out.add(m)
                    i++
                    if ((i and 63) == 0 && FakeMain.now() - s0 >= Build147Logic.Slices.BUDGET_MS) break
                }
                longest.set(maxOf(longest.get(), FakeMain.now() - s0))
                if (i < all.size) slice(i) else done.countDown()
            }
        }
        slice(0)
        ok("stukjes: klaar", done.await(60, TimeUnit.SECONDS))
        soft("stukjes: langste stuk ${longest.get()} ms ≤ 250 ms", longest.get() <= 250)
        eq("stukjes: alles gefilterd", out.size, lib.movies.size)
        bg.shutdown()
    } finally {
        main.stop()
    }

    val w = if (warnings > 0) " ($warnings waarschuwingen)" else ""
    println(if (failures == 0) "Build148Logic: alle $checks controles geslaagd$w" else "Build148Logic: $failures van $checks controles mislukt$w")
    kotlin.system.exitProcess(if (failures > 0) 1 else 0)
}
