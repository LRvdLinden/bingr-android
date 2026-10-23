package nl.lrvdlinden.bingr

// Build 141: belastingtest van "profiel kiezen" zonder emulator (de echte app draait in de Rooktest, smoke.yml).
//
//   java -Xmx2g -cp … nl.lrvdlinden.bingr.ProfileOpenStressTestKt prepare <map>     (nepgegevens, eenmalig)
//   java -Xmx256m -cp … nl.lrvdlinden.bingr.ProfileOpenStressTestKt new|old <map>
//
// Zelfde nepgegevens als core/StressMode.kt: 3 servers × (30.000 films, 20.000 series, 5.000 zenders met 4 dagen gids =
// 480.000 programma's). Daarna wat de app na het kiezen van een profiel doet, met de echte cachecode (features/CatalogFiles.kt)
// en de echte Nieuw op Bingr-logica (features/Build141Feedback.kt):
//
// - old (zoals build 140): de actieve server uit één bestand met gids; filteren maakt van elke titel een kopie; tegelijk
//   (los van elkaar) Nieuw op Bingr en "alle servers" (Mijn lijst/Verder kijken) die van elke andere server de hele cache
//   mét gids inlezen (Nieuw op Bingr zelfs twee keer: eerst gezien + gefilterd) en van elke titel een kopie maken, en het
//   verversen van de actieve server dat meteen begint (tweede volledige catalogus met gids);
// - new (build 141): catalogus en gids apart; de actieve server eerst (Home bruikbaar), filteren deelt de titels; daarna
//   pas, één tegelijk, de andere servers zonder gids (alleen de gids van de gevonden zenders) en Nieuw op Bingr met een
//   top-30 zonder kopieën; het verversen pas daarna.
//
// Meet de tijd tot Home bruikbaar, de totale tijd en het hoogste geheugengebruik na een GC (levende objecten). Met een
// heap zoals een tv (256 MB) moet "new" slagen; "old" loopt daar vast (OutOfMemoryError), dat is de crash na het kiezen
// van een profiel. Exit 0 = gelukt.

import java.io.File
import java.lang.management.ManagementFactory
import java.lang.ref.SoftReference
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

private const val SERVERS = 3
private const val MOVIES = 30_000
private const val SERIES = 20_000
private const val CHANNELS = 5_000
private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }

private val movieGroups = listOf("NL | Films Actie", "NL | Films Drama", "NL | Films Komedie", "EN | Movies Thriller", "EN | Movies 4K",
    "NL | Kinderfilms", "Disney+ Films", "Kids Movies", "NL | Films Horror", "EN | Movies Sci-Fi", "Adult XXX", "Documentaires")
private val seriesGroups = listOf("NL | Series Drama", "NL | Series Misdaad", "EN | TV Shows", "NL | Kinderseries", "Kids Series",
    "Netflix Series", "HBO Series", "Anime", "Reality")
private val hidden = setOf("Adult XXX", "Anime")

private fun library(server: Int, extra: Int = 0): Library {
    val r = Random(1000 + server)
    val now = System.currentTimeMillis()
    val day = 86_400_000L
    val movies = ArrayList<Movie>(MOVIES + extra)
    for (n in 0 until MOVIES + extra) {
        movies += Movie(id = "xv$n", title = "Stress Movie $n", year = 1980 + (n % 45), poster = "https://stress.invalid/s$server/m$n.jpg",
            group = movieGroups[n % movieGroups.size], streamURL = "http://stress.invalid/movie/u/p/$n.mp4", rating = (n % 100) / 10.0,
            plot = "Plot of stress movie $n on server $server.",
            addedAt = if (n >= MOVIES) now - r.nextLong(0, day) else if (n % 10 < 7) now - r.nextLong(day, 400 * day) else null, xtreamVodID = n)
    }
    val series = ArrayList<Series>(SERIES)
    for (n in 0 until SERIES) series += Series(id = "xs$n", title = "Stress Series $n", year = 1990 + (n % 35),
        poster = "https://stress.invalid/s$server/s$n.jpg", group = seriesGroups[n % seriesGroups.size], plot = "Plot of stress series $n.",
        rating = (n % 90) / 10.0, xtreamSeriesID = n, addedAt = if (n % 10 < 6) now - r.nextLong(day, 400 * day) else null)
    val channels = ArrayList<Channel>(CHANNELS)
    for (n in 0 until CHANNELS) channels += Channel(id = "x$n", name = "NL: Stress Channel $n HD", logo = "https://stress.invalid/s$server/c$n.png",
        group = "NL | Zenders ${n % 40}", streamURL = "http://stress.invalid/live/u/p/$n.ts", epgID = "ch$n.stress", number = n + 1)
    val hour = 3_600_000L
    val start0 = (now / hour) * hour - day
    val epg = HashMap<String, List<EPGProgram>>(CHANNELS * 2)
    for (c in channels) {
        val key = c.epgID!!
        epg[key] = List(96) { k ->
            EPGProgram(key, "Program ${k % 37} on ${c.number}", "Description of program $k on channel ${c.number}: a long text as real guides have, with cast and story.",
                start0 + k * hour, start0 + (k + 1) * hour)
        }
    }
    return Library(channels, movies, series, epg)
}

private fun paths(dir: File, i: Int) = CatalogFiles.Paths(File(dir, "lib-$i.json"), File(dir, "epg-$i.bin"), File(dir, "epg-$i.idx"))
private fun legacy(dir: File, i: Int) = File(dir, "cache-$i.json")

// MARK: filteren zoals Playlists.apply (verborgen categorieën), oud = altijd kopiëren, nieuw = delen

private fun filterOld(raw: Library): Library = raw.copy(
    channels = raw.channels.map { it.copy(group = it.group, name = it.name) },
    movies = raw.movies.filter { it.group !in hidden }.map { it.copy(group = it.group, title = it.title) },
    series = raw.series.filter { it.group !in hidden }.map { it.copy(group = it.group, title = it.title) },
)
private fun filterNew(raw: Library): Library = raw.copy(
    channels = raw.channels.filter { true },
    movies = raw.movies.filter { it.group !in hidden },
    series = raw.series.filter { it.group !in hidden },
)

/** Opzoektabellen zoals HomeIndex. */
private class Index(lib: Library) {
    val movieByID = HashMap<String, Movie>(lib.movies.size * 2).apply { lib.movies.forEach { putIfAbsent(it.id, it) } }
    val seriesByID = HashMap<String, Series>(lib.series.size * 2).apply { lib.series.forEach { putIfAbsent(it.id, it) } }
    val channelByID = HashMap<String, Channel>(lib.channels.size * 2).apply { lib.channels.forEach { putIfAbsent(it.id, it) } }
    val groups = lib.movies.groupBy { it.group }
}

// MARK: geheugen

private object Peak {
    /** Hoogste heapgebruik (inclusief nog niet opgeruimd afval), elke 20 ms gemeten. */
    @Volatile var maxUsedMb = 0L
    /** Hoogste levend geheugen op de meetpunten (na een volledige GC). */
    @Volatile var maxLiveMb = 0L
    fun used(): Long { val r = Runtime.getRuntime(); return (r.totalMemory() - r.freeMemory()) / 1_048_576 }
    fun sample() { val u = used(); if (u > maxUsedMb) maxUsedMb = u }
    fun live(): Long { System.gc(); val l = used(); if (l > maxLiveMb) maxLiveMb = l; return l }
    fun start(): Thread = Thread {
        while (!Thread.currentThread().isInterrupted) { sample(); try { Thread.sleep(20) } catch (_: InterruptedException) { break } }
    }.apply { isDaemon = true; start() }
}

private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000

/** Oude code: een onleesbare cache (runCatching ving de OutOfMemoryError) telt als geheugen vol. */
private fun <T> need(x: T?): T = x ?: throw OutOfMemoryError("cache niet te lezen: geheugen vol")

/** Hoogste levend geheugen tot nu, per fase. */
private fun phase(name: String) { println("  fase $name: levend geheugen ${Peak.live()} MB (piek tot nu ${Peak.maxUsedMb} MB)") }

fun main(args: Array<String>) {
    val mode = args.getOrNull(0) ?: "new"
    val dir = File(args.getOrNull(1) ?: "build/stress").apply { mkdirs() }
    val maxMb = Runtime.getRuntime().maxMemory() / 1_048_576
    println("belastingtest $mode, heap max $maxMb MB, $SERVERS servers × ($MOVIES films, $SERIES series, $CHANNELS zenders, 96 programma's per zender)")

    // nepgegevens (eenmalig, `prepare` met een grote heap): beide formaten, zodat oud en nieuw dezelfde gegevens lezen
    if (mode == "prepare") {
        for (i in 1..SERVERS) {
            if (paths(dir, i).exists && legacy(dir, i).exists()) continue
            val lib = library(i)
            CatalogFiles.write(lib, paths(dir, i), json)
            CatalogFiles.writeLegacy(lib, legacy(dir, i), json)
        }
        println("nepgegevens klaar in $dir")
        return
    }
    if ((1..SERVERS).any { !paths(dir, it).exists || !legacy(dir, it).exists() }) { println("MISLUKT: eerst `prepare`"); kotlin.system.exitProcess(2) }
    System.gc()
    println("cache per server: nieuw ${paths(dir, 1).core.length() / 1024} KB catalogus + ${paths(dir, 1).epg.length() / 1024} KB gids; oud ${legacy(dir, 1).length() / 1024} KB")

    // sanity: het nieuwe formaat geeft hetzelfde terug als het oude
    run {
        val a = CatalogFiles.readLegacy(legacy(dir, 2), json)!!
        val core = CatalogFiles.readCore(paths(dir, 2).core, json)!!
        val legacyCore = CatalogFiles.readCore(legacy(dir, 2), json)!!
        check(core.movies == a.movies && core.series == a.series && core.channels == a.channels) { "catalogus anders dan het oude formaat" }
        check(legacyCore.movies.size == a.movies.size && legacyCore.epg.isEmpty()) { "oud bestand zonder gids lezen mislukt" }
        val some = setOf("ch1.stress", "ch4999.stress", "bestaat-niet")
        val e = CatalogFiles.readEpg(paths(dir, 2), json, some)
        check(e.keys == setOf("ch1.stress", "ch4999.stress") && e["ch1.stress"] == a.epg["ch1.stress"]) { "gids per zender anders" }
        println("formaat: nieuw = oud (catalogus, gids per zender)")
    }
    System.gc(); Peak.maxLiveMb = 0; Peak.maxUsedMb = 0
    val sampler = Peak.start()
    val t0 = System.nanoTime()
    var homeMs = -1L
    val ok = try {
        if (mode == "old") {
            // de actieve server: één bestand met gids, filteren met kopieën, Home-index
            val activeRaw = need(CatalogFiles.readLegacy(legacy(dir, 1), json))
            val active = filterOld(activeRaw)
            val index = Index(active)
            homeMs = ms(t0)
            println("Home bruikbaar na $homeMs ms")
            phase("Home")
            check((0 until 20).count { active.nowPlaying(active.channels[it * 50]) != null } == 20)
            val pool = Executors.newFixedThreadPool(3)
            val libCache = HashMap<Int, SoftReference<Library>>()
            val firstSeen = HashMap<Int, Map<String, Long>>()   // zoals FirstSeen.mem
            val newOnBingr = pool.submit<Int> {
                var n = 0
                for (i in 2..SERVERS) {
                    val raw = need(CatalogFiles.readLegacy(legacy(dir, i), json))           // eerst gezien
                    firstSeen[i] = (raw.movies.map { "m:" + it.id } + raw.series.map { "s:" + it.id }).associateWith { 0L }
                    val raw2 = need(CatalogFiles.readLegacy(legacy(dir, i), json))          // otherLibrary leest opnieuw
                    val lib = filterOld(raw2)
                    val all = lib.movies.map { DetailTarget.MovieT(it.copy(sourceID = "s$i")) to (it.addedAt ?: 0L) } +
                        lib.series.map { DetailTarget.SeriesT(it.copy(sourceID = "s$i")) to (it.addedAt ?: 0L) }
                    n += all.sortedByDescending { it.second }.take(30).size
                }
                n
            }
            val allServers = pool.submit<Int> {
                var n = 0
                for (i in 2..SERVERS) {
                    val raw = need(CatalogFiles.readLegacy(legacy(dir, i), json))
                    val lib = filterOld(raw)
                    synchronized(libCache) { libCache[i] = SoftReference(lib) }
                    val ix = Index(lib)
                    n += (0 until 40).count { ix.movieByID["xv${it * 7}"] != null }
                }
                n
            }
            val refresh = pool.submit<Int> {
                val fresh = library(1, extra = 200)                                   // "van het netwerk"
                CatalogFiles.writeLegacy(fresh, File(dir, "refresh-old.json"), json)
                val f = filterOld(fresh)
                Index(f).movieByID.size
            }
            println("nieuw op Bingr ${newOnBingr.get()}, alle servers ${allServers.get()}, verversen ${refresh.get()}; index ${index.movieByID.size}, gids ${activeRaw.epg.size}")
            phase("klaar")
            pool.shutdown(); pool.awaitTermination(1, TimeUnit.MINUTES)
        } else {
            // de actieve server eerst: catalogus + gids (apart), filteren deelt de titels, Home-index
            val activeRaw = CatalogFiles.readCore(paths(dir, 1).core, json)!!.let { it.copy(epg = CatalogFiles.openEpg(paths(dir, 1), json)!!) }
            val active = filterNew(activeRaw)
            val index = Index(active)
            // Home: "Nu live" van 20 favoriete zenders (de gids per zender van schijf)
            val live = (0 until 20).count { active.nowPlaying(active.channels[it * 50]) != null }
            check(live == 20) { "gids op schijf: nu live ontbreekt ($live van 20)" }
            homeMs = ms(t0)
            println("Home bruikbaar na $homeMs ms")
            phase("Home")
            // daarna, één tegelijk (AllServers.heavy): de andere servers zonder gids
            val parts = ArrayList<List<Pair<DetailTarget, Long>>>()
            var found = 0
            var epgFound = 0
            for (i in 2..SERVERS) {
                val raw = CatalogFiles.readCore(paths(dir, i).core, json)!!
                val lib = filterNew(raw)
                // Nieuw op Bingr: top 30 zonder kopieën (Build141Feedback.Recent.top)
                val seq: Sequence<Any> = lib.movies.asSequence() + lib.series.asSequence()
                parts += Build141Feedback.Recent.top(seq, 30, { x -> (x as? Movie)?.addedAt ?: (x as? Series)?.addedAt ?: 0L },
                    { x -> (x as? Movie)?.title ?: (x as Series).title }).map { (x, d) ->
                    (if (x is Movie) DetailTarget.MovieT(x.copy(sourceID = "s$i")) else DetailTarget.SeriesT((x as Series).copy(sourceID = "s$i"))) to d
                }
                // alle servers: verwijzingen opzoeken, en alleen de gids van de gevonden zenders
                val ix = Index(lib)
                found += (0 until 40).count { ix.movieByID["xv${it * 7}"] != null }
                epgFound += CatalogFiles.readEpg(paths(dir, i), json, (0 until 20).map { "ch${it * 13}.stress" }.toSet()).size
            }
            val merged = Build141Feedback.Recent.merge(parts, 30, { it.title }) { it.title }
            phase("andere servers")
            // pas daarna verversen (nieuwe catalogus, weggeschreven in het nieuwe formaat)
            var fresh: Library? = library(1, extra = 200)
            CatalogFiles.write(fresh!!, paths(dir, 9), json)
            // daarna de gids van schijf (zoals Playlists.refresh), de opgehaalde gids gaat uit het geheugen
            fresh = fresh.copy(epg = CatalogFiles.openEpg(paths(dir, 9), json)!!)
            val f = filterNew(fresh)
            check(f.nowPlaying(f.channels[123]) != null) { "na verversen geen gids" }
            println("nieuw op Bingr ${merged.size}, alle servers $found (gids van $epgFound zenders), verversen ${Index(f).movieByID.size}; index ${index.movieByID.size}, gids ${activeRaw.epg.size}")
            phase("klaar")
        }
        true
    } catch (e: Throwable) {
        val oom = e is OutOfMemoryError || e.cause is OutOfMemoryError
        println("MISLUKT: ${if (oom) "OutOfMemoryError (geheugen vol)" else e.toString()}")
        false
    }
    Peak.sample(); sampler.interrupt()
    println("resultaat $mode: ${if (ok) "gelukt" else "MISLUKT"}, Home ${homeMs} ms, totaal ${ms(t0)} ms, levend max ${Peak.maxLiveMb} MB, piek ${Peak.maxUsedMb} MB van $maxMb MB")
    if (!ok) kotlin.system.exitProcess(1)
}
