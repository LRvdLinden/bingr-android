package nl.lrvdlinden.bingr

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

// Build 147: unit- en logica-tests van features/Build147Logic.kt (eerlijke starttijden, samenvatting, hang-detectie, vorige
// start, ophalen per soort, opslag, soort verbinding, werk in stukken) en een test met een catalogus van 10.000 films en
// 1.000 zenders: na "klaar" mag de hoofdthread nooit langer dan 250 ms vastzitten. Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/features/Build146Logic.kt \
//     app/src/main/java/nl/lrvdlinden/bingr/features/Build147Logic.kt tools/test/Build147LogicTest.kt -include-runtime -d /tmp/b147.jar
//   java -jar /tmp/b147.jar
// Zelfde gevallen als tizen/test/build147_test.js.

private var failures = 0
private var checks = 0
private fun eq(name: String, got: Any?, want: Any?) {
    checks++
    if (got != want) { failures++; println("FOUT $name kreeg <$got>, verwacht <$want>"); println("::error title=Build147LogicTest::$name") }
}
private fun ok(name: String, v: Boolean) = eq(name, v, true)

private data class Film(val id: String, val title: String, val group: String, val addedAt: Long, val rating: Double)
private data class Zender(val id: String, val name: String, val group: String, val number: Int)

/** Wat de app na het laden met een catalogus doet: filteren (verborgen categorieën), sorteren, indexeren, rijen. */
private data class Prepared(val movies: List<Film>, val channels: List<Zender>, val byId: Map<String, Film>,
                            val groups: Map<String, List<Film>>, val recent: List<Film>, val top: List<Film>)

private fun prepare(movies: List<Film>, channels: List<Zender>, hidden: Set<String>): Prepared {
    val m = movies.filter { it.group !in hidden }.sortedBy { it.title.lowercase() }
    val c = channels.filter { it.group !in hidden }.sortedWith(compareBy({ it.group }, { it.number }))
    val byId = HashMap<String, Film>(m.size * 2).apply { m.forEach { put(it.id, it) } }
    val groups = m.groupBy { it.group }
    val recent = m.sortedByDescending { it.addedAt }.take(30)
    val top = m.sortedByDescending { it.rating }.take(10)
    return Prepared(m, c, byId, groups, recent, top)
}

/**
 * Een "hoofdthread" (één thread, zoals de main looper) die elke 16 ms een frame tekent, met de bewaking van de app
 * ([Build147Logic.Hang.Tracker], ping elke seconde, 2 s = hang) en een meting van het langste gat tussen twee frames.
 */
private class FakeMain {
    val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "main") }
    val mainThread: Thread = exec.submit<Thread> { Thread.currentThread() }.get()
    val tracker = Build147Logic.Hang.Tracker()
    @Volatile var task: String? = null
    private val lastFrame = AtomicLong(now())
    val maxGap = AtomicLong(0)
    @Volatile private var running = true
    private val frames = Thread {
        while (running) {
            exec.execute {
                val t = now()
                val gap = t - lastFrame.getAndSet(t)
                if (gap > maxGap.get()) maxGap.set(gap)
            }
            Thread.sleep(16)
        }
    }
    private val watch = Thread {
        var lastPing = 0L
        while (running) {
            val n = now()
            synchronized(tracker) { if (!tracker.pending && n - lastPing >= Build147Logic.Hang.PING_MS) { tracker.sent(n); lastPing = n; exec.execute { synchronized(tracker) { tracker.answered(now()) } } } }
            Thread.sleep(100)
            synchronized(tracker) {
                tracker.check(now(), true, task, true, "ready") {
                    mainThread.stackTrace.take(20).map { "at ${it.className}.${it.methodName}(${it.fileName ?: "?"}:${it.lineNumber})" }
                }
            }
        }
    }
    init { frames.isDaemon = true; watch.isDaemon = true; frames.start(); watch.start() }
    fun resetGap() { lastFrame.set(now()); maxGap.set(0) }
    fun stop() { running = false; exec.shutdown(); exec.awaitTermination(5, TimeUnit.SECONDS) }
    companion object { fun now() = System.nanoTime() / 1_000_000 }
}

fun main() {
    val S = Build147Logic.Start

    // MARK: eerlijke starttijden (de mijlpalen van ticket #1.030)
    val ticket = listOf("start" to 0L, "account" to 200L, "first_frame" to 300L, "splash" to 4100L, "profiles" to 4100L,
        "profile_picked" to 6800L, "pin" to 6900L, "pin_ok" to 12000L, "ready" to 14400L)
    val t = S.timing(ticket)
    eq("timing: totaal = ready", t.totalMs, 14_400L)
    eq("timing: wachten = profielkeuze + pincode", t.userWaitMs, (6800L - 4100L) + (12000L - 6900L))
    eq("timing: app-tijd = rest", t.appMs, 14_400L - 7_800L)
    eq("timing: klaar", t.finished, true)
    val noPin = S.timing(listOf("start" to 0L, "profiles" to 1000L, "profile_picked" to 3000L, "ready" to 4000L))
    eq("timing zonder pincode: wachten", noPin.userWaitMs, 2000L)
    eq("timing zonder pincode: app", noPin.appMs, 2000L)
    val waiting = S.timing(listOf("start" to 0L, "profiles" to 1000L, "home" to 1500L))
    eq("timing: wacht nog op de gebruiker (geen ready)", waiting.userWaitMs, 500L)
    eq("timing: niet klaar", waiting.finished, false)
    eq("timing: leeg", S.timing(emptyList()).totalMs, 0L)
    eq("timing: dubbele naam telt één keer", S.timing(listOf("profiles" to 100L, "profiles" to 900L, "profile_picked" to 1100L, "ready" to 1200L)).userWaitMs, 1000L)
    eq("timing: eind vóór begin = 0", S.timing(listOf("pin" to 900L, "pin_ok" to 800L, "ready" to 1000L)).userWaitMs, 0L)

    // MARK: samenvatting
    eq("samenvatting goed", S.summary(4000, 0, false), "good")
    eq("samenvatting traag (app-tijd)", S.summary(5001, 0, false), "slow")
    eq("samenvatting goed op 5 s", S.summary(5000, 2000, false), "good")
    eq("samenvatting traag (hang > 2 s)", S.summary(1000, 2001, false), "slow")
    eq("samenvatting vastgelopen (hang > 10 s)", S.summary(1000, 10_001, false), "hung")
    eq("samenvatting vastgelopen (ticket: 80,1 s)", S.summary(t.appMs, 80_100, false), "hung")
    eq("samenvatting vastgelopen (crash)", S.summary(100, 0, true), "hung")
    eq("label", S.label("slow"), "Start: slow")

    // MARK: hang-detectie (pure tracker)
    run {
        val tr = Build147Logic.Hang.Tracker()
        tr.sent(1000)
        eq("hang: binnen 2 s niets", tr.check(2999, true, "x", true, "ready") { listOf("s") }, null)
        val e = tr.check(3000, true, "catalogus sorteren", true, "ready") { listOf("at a.b(C.kt:1)") }
        eq("hang: na 2 s", e?.ms, 2000L)
        eq("hang: taak", e?.task, "catalogus sorteren")
        eq("hang: stack", e?.stack, listOf("at a.b(C.kt:1)"))
        eq("hang: na klaar", e?.afterReady, true)
        var calls = 0
        tr.check(5000, true, null, true, "ready") { calls++; listOf("anders") }
        eq("hang: stack niet elke keer opnieuw", calls, 0)
        val deep = tr.check(11_500, true, null, true, "ready") { calls++; listOf("at diep(X.kt:9)") }
        eq("hang: stack opnieuw na 10 s", deep?.stack, listOf("at diep(X.kt:9)"))
        eq("hang: taak blijft", deep?.task, "catalogus sorteren")
        eq("hang: langste op de voorgrond", tr.maxForegroundMs, 10_500L)
        eq("hang: afgerond bij antwoord", tr.answered(11_600)?.ms, 10_500L)
        eq("hang: geen ping, geen hang", tr.check(20_000, true, null, true, "ready") { emptyList() }, null)
        eq("hang: teller", tr.count, 1)
        // achtergrond telt niet
        val bg = Build147Logic.Hang.Tracker()
        bg.sent(0)
        bg.check(30_000, false, "sync toepassen", true, "ready") { emptyList() }
        eq("hang achtergrond: telt niet als voorgrond", bg.maxForegroundMs, 0L)
        eq("hang achtergrond: wel vastgelegd", bg.worst?.foreground, false)
        eq("samenvatting met achtergrondhang", S.summary(1000, bg.maxForegroundMs, false), "good")
    }

    // MARK: vorige start
    val PV = Build147Logic.Previous
    eq("vorige: crash", PV.of(true, listOf("at x"), false, "home", 5000, null, true, null, null, emptyList()).problem, "crash")
    val hung = PV.of(false, emptyList(), true, "ready", 14_400, 80_100, true, "ready", "catalogus sorteren", listOf("at y"))
    eq("vorige: hang", hung.problem, "hang")
    eq("vorige: hang seconden", hung.seconds, 80.1)
    eq("vorige: hang taak", hung.task, "catalogus sorteren")
    eq("vorige: hang stack", hung.stack, listOf("at y"))
    eq("vorige: hang op de achtergrond = geen probleem", PV.of(false, emptyList(), true, "ready", 1000, 30_000, false, "ready", "x", emptyList()).problem, "none")
    eq("vorige: korte hang (≤ 2 s) = geen probleem", PV.of(false, emptyList(), true, "ready", 1000, 2000, true, "ready", "x", emptyList()).problem, "none")
    eq("vorige: niet klaar", PV.of(false, emptyList(), false, "home", 9000, null, true, null, null, emptyList()).problem, "not_finished")
    eq("vorige: goed", PV.of(false, emptyList(), true, "ready", 3000, null, true, null, null, emptyList()).problem, "none")

    // MARK: ophalen per server en soort
    val F = Build147Logic.Fetch
    eq("fetch: geleverd", F.status(10259, 10259, null), "ok")
    eq("fetch: provider leverde niets (ticket: 0 series)", F.status(0, 0, null), "empty")
    eq("fetch: mislukt", F.status(0, null, "timeout"), "failed")
    eq("fetch: nooit opgehaald = leeg", F.status(null, null, null), "empty")
    eq("fetch: alles verborgen", F.status(500, 0, null), "hidden")
    eq("fetch: geleverd ondanks oude fout", F.status(5, 5, "timeout"), "ok")
    eq("foutcode: timeout", F.errorCode("SocketTimeoutException", "timeout"), "timeout")
    eq("foutcode: http", F.errorCode("StatusException", "Serverfout 502: …", 502), "http_502")
    eq("foutcode: http uit de tekst", F.errorCode("IOException", "HTTP 404 Not Found"), "http_404")
    eq("foutcode: json", F.errorCode("JsonParseException", "Kon het antwoord niet lezen"), "parse")
    eq("foutcode: netwerk", F.errorCode("UnknownHostException", "Unable to resolve host"), "network")
    eq("foutcode: nooit een URL", F.errorCode("Exception", "http://user:pass@host.example/x?password=1").contains("http"), false)
    eq("foutcode: onbekend", F.errorCode("IllegalStateException", "x"), "error")
    eq("server anoniem", F.serverName(0), "s1")
    eq("soorten", F.KINDS, listOf("live", "movie", "series"))

    // MARK: opslag (totaal telt precies op) en verbinding
    val st = Build147Logic.Storage.of(156L * 1_048_576, 132L * 1_048_576 + 400_000, 0L, 18_000L * 1_048_576)
    eq("opslag: totaal = som", st.totalMB, st.cacheMB + st.catalogMB + st.downloadsMB)
    eq("opslag: delen", listOf(st.cacheMB, st.catalogMB, st.downloadsMB, st.freeMB), listOf(156, 132, 0, 18_000))
    val st2 = Build147Logic.Storage.of(700_000, 700_000, 700_000, null)
    eq("opslag: afronden per deel", st2.totalMB, 3)
    eq("opslag: vrij onbekend", st2.freeMB, null)
    val N = Build147Logic.NetType
    eq("net: ethernet gaat voor wifi", N.of(ethernet = true, wifi = true, cellular = false), "ethernet")
    eq("net: wifi", N.of(false, true, false), "wifi")
    eq("net: mobiel", N.of(false, false, true), "cellular")
    eq("net: onbekend", N.of(false, false, false), "unknown")

    // MARK: werk in stukken
    run {
        var clock = 0L
        var yields = 0
        val list = (1..10_000).toList()
        var sum = 0L
        val slices = Build147Logic.Slices.run(list, 12, clock = { clock }, yieldNow = { yields++ }) { sum += it; clock += 1 }
        eq("stukken: alles verwerkt", sum, 50_005_000L)
        ok("stukken: in meerdere stukken", slices > 100)
        eq("stukken: tussen elk stuk een pauze", yields, slices - 1)
    }

    // MARK: 10.000 films en 1.000 zenders: na "klaar" zit de hoofdthread nooit langer dan 250 ms vast
    val rnd = java.util.Random(147)
    val groups = (0 until 60).map { "Groep $it" }
    val movies = (0 until 10_000).map { i ->
        Film("xv$i", "Titel ${rnd.nextInt(1_000_000)} ${i % 97}", groups[rnd.nextInt(groups.size)], 1_700_000_000_000L + rnd.nextInt(1_000_000_000), rnd.nextDouble() * 10)
    }
    val channels = (0 until 1_000).map { i -> Zender("x$i", "Zender $i", groups[i % 20], rnd.nextInt(2000)) }
    val hidden = setOf("Groep 3", "Groep 7")
    val main = FakeMain()
    try {
        Thread.sleep(300)
        main.resetGap()
        // zoals de app: zwaar werk op een achtergrondthread (Dispatchers.Default), de UI krijgt alleen het eindresultaat
        val bg = Executors.newFixedThreadPool(2)
        val t0 = System.nanoTime()
        var result: Prepared? = null
        val jobs = (0 until 5).map { bg.submit<Prepared> { prepare(movies, channels, hidden) } } +
            // pincodes van drie profielen hashen (PBKDF2, 100.000 iteraties) ook buiten de main thread
            (0 until 3).map { n -> bg.submit<Prepared> { Build146Logic.Pin.derive("12${n}4", ByteArray(16) { it.toByte() }, Build146Logic.Pin.ITER); null } }
        jobs.forEach { j -> j.get()?.let { r -> result = r } }
        val heavyMs = (System.nanoTime() - t0) / 1_000_000
        val r = result!!
        // het resultaat toepassen op de "hoofdthread" (alleen toewijzen)
        main.exec.submit { main.task = "resultaat toepassen"; check(r.movies.isNotEmpty()); main.task = null }.get()
        Thread.sleep(200)
        val gap = main.maxGap.get()
        println("10k films + 1k zenders (5×) en 3 pincodes op de achtergrond: $heavyMs ms; langste gat op de hoofdthread: $gap ms")
        ok("10k: hoofdthread nooit langer dan 250 ms vast (gat $gap ms)", gap <= 250)
        eq("10k: geen hang", main.tracker.count, 0)
        eq("10k: films gefilterd", r.movies.size, movies.count { it.group !in hidden })
        eq("10k: gesorteerd", r.movies.zipWithNext().all { (a, b) -> a.title.lowercase() <= b.title.lowercase() }, true)
        eq("10k: index", r.byId.size, r.movies.size)
        eq("10k: zenders gesorteerd", r.channels.zipWithNext().all { (a, b) -> a.group < b.group || (a.group == b.group && a.number <= b.number) }, true)
        eq("10k: rijen", r.recent.size to r.top.size, 30 to 10)

        // en in stukjes óp de hoofdthread (zoals een Tizen-tv zonder threads): elk stuk ruim onder 250 ms
        main.resetGap()
        val longest = AtomicLong(0)
        val out = ArrayList<Film>()
        val done = java.util.concurrent.CountDownLatch(1)
        fun slice(from: Int) {
            main.exec.execute {
                val s0 = FakeMain.now()
                var i = from
                while (i < movies.size) {
                    val m = movies[i]; if (m.group !in hidden) out.add(m)
                    i++
                    if ((i and 63) == 0 && FakeMain.now() - s0 >= Build147Logic.Slices.BUDGET_MS) break
                }
                longest.set(maxOf(longest.get(), FakeMain.now() - s0))
                if (i < movies.size) slice(i) else done.countDown()
            }
        }
        slice(0)
        done.await(30, TimeUnit.SECONDS)
        Thread.sleep(100)
        ok("stukjes: langste stuk ${longest.get()} ms ≤ 250 ms", longest.get() <= 250)
        ok("stukjes: hoofdthread nooit langer dan 250 ms vast (gat ${main.maxGap.get()} ms)", main.maxGap.get() <= 250)
        eq("stukjes: alles gefilterd", out.size, movies.count { it.group !in hidden })

        // tegenproef: hetzelfde werk 3,5 s lang óp de hoofdthread → de bewaking ziet een hang, met taak en stack
        // (ping elke seconde, 2 s zonder antwoord = hang: een blok van 3 s of langer wordt dus altijd gezien)
        main.exec.submit {
            main.task = "catalogus sorteren"
            val until = FakeMain.now() + 3_500
            var n = 0
            while (FakeMain.now() < until) { prepare(movies, channels, hidden); n++ }
            main.task = null
        }.get()
        Thread.sleep(1_300)
        val w = synchronized(main.tracker) { main.tracker.worst }
        ok("tegenproef: hang gezien", w != null && w.ms >= 2_000)
        eq("tegenproef: taak", w?.task, "catalogus sorteren")
        ok("tegenproef: stack van de hoofdthread (${w?.stack?.firstOrNull()})", w?.stack?.any { "Build147LogicTest" in it || "prepare" in it || "sort" in it.lowercase() } == true)
        eq("tegenproef: samenvatting traag", S.summary(1000, main.tracker.maxForegroundMs, false), "slow")
        bg.shutdown()
    } finally {
        main.stop()
    }

    println(if (failures == 0) "Build147Logic: alle $checks controles geslaagd" else "Build147Logic: $failures van $checks controles mislukt")
    kotlin.system.exitProcess(if (failures > 0) 1 else 0)
}
