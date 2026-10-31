package nl.lrvdlinden.bingr

import java.io.File
import java.nio.file.Files

// Build 158: grenzen van de lokale opslag (grote Prefs-waarden naar bestanden, migratie) en de grens van 1 MB voor de
// section profile.<id> (contract build 154). Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/features/Build158Logic.kt tools/test/Build158LogicTest.kt -include-runtime -d /tmp/b158.jar
//   java -jar /tmp/b158.jar

private var failures = 0
private var checks = 0
private fun eq(name: String, got: Any?, want: Any?) {
    checks++
    if (got != want) { failures++; println("FOUT $name kreeg <$got>, verwacht <$want>"); println("::error title=Build158LogicTest::$name") }
}
private fun ok(name: String, v: Boolean) = eq(name, v, true)

private fun overview(i: Int) = "Een lange beschrijving van titel $i. ".repeat(12)

/** Een `profile.<id>`-achtige JSON met [watched] (sleutel → at) en wat vaste ruimte voor de rest. */
private fun sectionChars(watched: Map<String, Long>, rest: Int): Int {
    var n = rest + "\"watched\":[]".length
    for (k in watched.keys) n += Build158Logic.SectionCap.entryChars(k)
    return n
}

fun main() {
    val B = Build158Logic.Blobs
    val FILES = Build158Logic.Blobs.Where.FILES
    val CACHE = Build158Logic.Blobs.Where.CACHE
    val C = Build158Logic.SectionCap

    // 1. welke sleutels een bestand krijgen
    eq("discover.known → files", B.whereFor("discover.known", 10), FILES)
    eq("discover-rij → cache", B.whereFor("discover.upcoming.kids.nl.NL", 10), CACHE)
    eq("trending → cache", B.whereFor("trending.tv.nl", 10), CACHE)
    eq("providerLogos → cache", B.whereFor("b149.providerLogos", 10), CACHE)
    eq("providerLogos.at blijft in Prefs", B.whereFor("b149.providerLogos.at", 13), null)
    eq("skipped152 → files", B.whereFor("skipped152.0f3a", 10), FILES)
    eq("kleine instelling blijft in Prefs", B.whereFor("ui.startTab", 1), null)
    eq("introMarkers klein blijft", B.whereFor("introMarkers", 2_000), null)
    eq("onbekend en groot → files", B.whereFor("introMarkers", Build158Logic.MAX_PREF_VALUE + 1), FILES)
    eq("precies op de grens blijft", B.whereFor("x", Build158Logic.MAX_PREF_VALUE), null)

    // 2. bestandsnamen
    eq("veilige naam", B.fileName("discover.known"), "discover.known.txt")
    eq("pipe gecodeerd", B.fileName("a|b"), "a%7Cb.txt")
    eq("slash gecodeerd", B.fileName("../x"), "..%2Fx.txt")
    ok("unicode gecodeerd en anders", B.fileName("é") != B.fileName("e") && B.fileName("é").all { it.code < 128 })
    ok("geen botsing", B.fileName("a b") != B.fileName("a_b"))

    // 3. bestanden schrijven, lezen, verplaatsen, wissen
    val root = Files.createTempDirectory("b158").toFile()
    val files = File(root, "files"); val cache = File(root, "cache")
    val d = Build158Logic.BlobDir(files, cache)
    eq("leeg", d.read("k"), null)
    d.write("k", "één", FILES)
    eq("gelezen", d.read("k"), "één")
    ok("bestaat", d.exists("k"))
    d.write("k", "twee", CACHE)
    eq("nieuwe plek wint", d.read("k"), "twee")
    ok("oude kopie weg", !File(files, B.fileName("k")).exists())
    ok("geen tijdelijk bestand", cache.listFiles()!!.none { it.name.endsWith(".tmp") })
    d.delete("k")
    eq("gewist", d.read("k"), null)
    ok("bestaat niet meer", !d.exists("k"))

    // 4. migratie uit SharedPreferences, met realistische groottes
    val known = (1..400).joinToString(",", "{", "}") { i ->
        "\"tmdb-movie-$i\":{\"item\":{\"tmdbID\":$i,\"isShow\":false,\"title\":\"Titel $i\",\"date\":\"2026-01-01\"," +
            "\"posterPath\":\"/abcdefghijklmnop$i.jpg\",\"overview\":\"${overview(i)}\",\"kind\":\"upcoming\"},\"seen\":1}"
    }
    val row = (1..40).joinToString(",", "{\"date\":1,\"items\":[", "]}") { i ->
        "{\"tmdbID\":$i,\"isShow\":false,\"title\":\"Titel $i\",\"posterPath\":\"/p$i.jpg\",\"overview\":\"${overview(i)}\"}"
    }
    val skipped = (1..3_000).joinToString(",", "[", "]") { "\"movies|Categorie nummer $it\"" }
    val prefs = linkedMapOf<String, Any?>(
        "discover.known" to known,
        "discover.upcoming.nl.NL" to row, "discover.streaming.nl.NL" to row, "discover.nowplaying.nl.NL" to row,
        "discover.upcoming.kids.nl.NL" to row, "discover.upcoming.en.US" to row,
        "trending.movie.nl" to row.take(4_000), "trending.tv.nl" to row.take(4_000),
        "b149.providerLogos" to (1..500).joinToString(",", "{", "}") { "\"p$it\":\"https://image.tmdb.org/t/p/w300/logo$it.png\"" },
        "b149.providerLogos.at" to "1730000000000",
        "skipped152.server-a" to skipped,
        "ui.startTab" to 2, "activeProfileID" to "abc", "trakt.scrobble" to true,
        "introMarkers" to "{\"a:1\":{\"introEnd\":90}}",
    )
    fun chars(m: Map<String, Any?>) = m.entries.sumOf { (k, v) -> k.length + (v?.toString()?.length ?: 0) }
    val before = chars(prefs)
    val moved = d.migrate(prefs, skip = setOf("trending.tv.nl"))
    val after = chars(prefs.filterKeys { it !in moved })
    println("groottes: Prefs vóór ${before / 1024} KB, na ${after / 1024} KB (${moved.size} waarden naar bestanden)")
    ok("Prefs vóór > 400 KB (zoals op een tv met veel gebruik)", before > 400 * 1024)
    ok("Prefs na < 16 KB", after < 16 * 1024)
    ok("discover.known verplaatst", "discover.known" in moved)
    ok("overgeslagen sleutel blijft (nieuwer geschreven)", "trending.tv.nl" !in moved)
    ok("kleine waarden blijven", "ui.startTab" !in moved && "activeProfileID" !in moved && "introMarkers" !in moved)
    ok("tijd van de logo's blijft", "b149.providerLogos.at" !in moved)
    eq("inhoud gelijk na migratie", d.read("discover.known"), known)
    eq("skipped in filesDir", File(files, B.fileName("skipped152.server-a")).readText(), skipped)
    ok("rij in cacheDir", File(cache, B.fileName("discover.upcoming.nl.NL")).exists())
    eq("tweede migratie verandert niets aan de inhoud", d.migrate(prefs.filterKeys { it !in moved }, skip = setOf("trending.tv.nl")).size, 0)
    root.deleteRecursively()

    // 5. grens van de section profile.<id>
    eq("onder de grens: niets weg", C.drop(mapOf("w:m:t:a|2000" to 1L, "w:m:tmdb:1" to 1L), 500), emptySet<String>())
    val w = LinkedHashMap<String, Long>()
    for (i in 0 until 9_000) {
        val at = 1_000L + i
        w["w:m:tmdb:$i"] = at
        w["w:m:t:eenlangefilmtitelnummer$i|2010"] = at
        if (i % 3 == 0) w["w:e:tmdb:$i|1-${i % 20 + 1}"] = at
    }
    w["w:m:t:zonderid|1999"] = 5L                         // geen tmdb/imdb-broer: blijft altijd
    val rest = 150_000                                     // voortgang, geschiedenis, lijsten, removed
    val size = sectionChars(w, rest)
    ok("testsection boven 1 MB", size > C.MAX_SECTION)
    val drop = C.drop(w, size)
    val afterSize = sectionChars(w - drop, rest)
    println("section: vóór ${size / 1024} KB, na ${afterSize / 1024} KB (${drop.size} titelsleutels weg)")
    ok("na het wegvallen ≤ 1 MB", afterSize <= C.MAX_SECTION)
    ok("alleen titelsleutels van films", drop.all { it.startsWith("w:m:t:") })
    ok("titel zonder id-broer blijft", "w:m:t:zonderid|1999" !in drop)
    ok("tmdb-sleutels blijven", w.keys.filter { it.startsWith("w:m:tmdb:") }.none { it in drop })
    ok("afleveringen blijven", drop.none { it.startsWith("w:e:") })
    val oldestKept = (w - drop).filterKeys { it.startsWith("w:m:t:eenlange") }.values.minOrNull() ?: Long.MAX_VALUE
    ok("de oudste eerst", drop.all { w[it]!! < oldestKept })
    ok("niet meer dan nodig", sectionChars(w - drop + (drop.last() to w[drop.last()]!!), rest) > C.MAX_SECTION)
    // ander `at` = ander item: de titelsleutel heeft dan geen broer
    eq("ander at = geen broer", C.drop(mapOf("w:m:t:a|2000" to 1L, "w:m:tmdb:9" to 2L), 2_000_000), emptySet<String>())
    eq("imdb telt als broer", C.drop(mapOf("w:m:t:a|2000" to 1L, "w:m:imdb:tt1" to 1L), 2_000_000), setOf("w:m:t:a|2000"))
    eq("geen kandidaten: leeg (upload wordt dan overgeslagen)", C.drop(mapOf("w:e:t:x|0|1-1" to 1L), 2_000_000), emptySet<String>())

    println("${checks - failures}/$checks geslaagd")
    if (failures > 0) kotlin.system.exitProcess(1)
}
