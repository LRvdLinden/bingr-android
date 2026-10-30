package nl.lrvdlinden.bingr

import java.io.File

// Build 154: inhoudssleutels voor "gezien", titelopschoning, Trakt-token, wachtrij, migratie en uitvinken.
// Gedeelde gevallen: docs/watched_keys_cases.json in LRvdLinden/iptv-ios-appletv (niet veranderen; de workflow haalt het
// bestand daar op, lokaal ../iptv-ios-appletv/docs/watched_keys_cases.json). Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/meta/TitleCleaner.kt app/src/main/java/nl/lrvdlinden/bingr/features/Build150Logic.kt \
//     app/src/main/java/nl/lrvdlinden/bingr/features/Build154Logic.kt tools/test/Build154LogicTest.kt -include-runtime -d /tmp/b154.jar
//   java -jar /tmp/b154.jar ../iptv-ios-appletv/docs/watched_keys_cases.json

private var failures = 0
private var checks = 0
private fun eq(name: String, got: Any?, want: Any?) {
    checks++
    if (got != want) { failures++; println("FOUT $name kreeg <$got>, verwacht <$want>"); println("::error title=Build154LogicTest::$name") }
}
private fun ok(name: String, v: Boolean) = eq(name, v, true)

/** Kleine JSON-lezer (alleen voor de testgevallen): String, Double, Boolean, null, List, Map. */
private class J(val s: String) {
    var i = 0
    fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
    fun value(): Any? {
        ws()
        return when (s[i]) {
            '{' -> { i++; val m = LinkedHashMap<String, Any?>(); ws(); if (s[i] == '}') { i++; return m }
                while (true) { ws(); val k = str(); ws(); i++; m[k] = value(); ws(); if (s[i++] == '}') break }; m }
            '[' -> { i++; val l = ArrayList<Any?>(); ws(); if (s[i] == ']') { i++; return l }
                while (true) { l += value(); ws(); if (s[i++] == ']') break }; l }
            '"' -> str()
            't' -> { i += 4; true }
            'f' -> { i += 5; false }
            'n' -> { i += 4; null }
            else -> { val st = i; while (i < s.length && (s[i].isDigit() || s[i] in "-+.eE")) i++; s.substring(st, i).toDouble() }
        }
    }
    fun str(): String {
        i++
        val sb = StringBuilder()
        while (s[i] != '"') {
            if (s[i] == '\\') { i++; when (s[i]) { 'n' -> sb.append('\n'); 'u' -> { sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }; else -> sb.append(s[i]) } }
            else sb.append(s[i])
            i++
        }
        i++
        return sb.toString()
    }
}


private fun s(v: Any?): String? = v as String?
private fun i(v: Any?): Int? = (v as Double?)?.toInt()

@Suppress("UNCHECKED_CAST")
fun main(args: Array<String>) {
    val path = args.firstOrNull() ?: "../iptv-ios-appletv/docs/watched_keys_cases.json"
    val cases = J(File(path).readText()).value() as Map<String, Any?>
    val W = Build154Logic.WatchKeys

    // 1. gedeelde gevallen
    var n = 0
    for (c in cases["norm"] as List<List<Any?>>) { eq("norm ${c[0]}", W.norm(c[0] as String), c[1]); n++ }
    for (c in cases["clean"] as List<List<Any?>>) {
        val p = TitleCleaner.parse(c[0] as String)
        eq("opschonen ${c[0]}", p.title to p.year, (c[1] as String) to i(c[2])); n++
    }
    for (c in cases["movieKeys"] as List<Map<String, Any?>>) {
        eq("film ${c["title"]}", W.movieKeys(s(c["title"])!!, i(c["year"]), s(c["tmdb"]), s(c["imdb"])), c["keys"]); n++
    }
    for (c in cases["episodeKeys"] as List<Map<String, Any?>>) {
        eq("aflevering ${c["show"]}", W.episodeKeys(s(c["show"])!!, i(c["year"]), s(c["tmdb"]), s(c["imdb"]), i(c["season"]), i(c["episode"])), c["keys"]); n++
    }
    for (c in cases["showKeys"] as List<Map<String, Any?>>) {
        eq("serie ${c["show"]}", W.showKeys(s(c["show"])!!, i(c["year"]), s(c["tmdb"]), s(c["imdb"])), c["keys"]); n++
    }
    for ((idx, c) in (cases["lookup"] as List<Map<String, Any?>>).withIndex()) {
        val stored = (c["stored"] as List<String>).toSet()
        eq("lezen $idx ${c["title"]} ${c["year"]}", W.movieWatched(stored, s(c["title"])!!, i(c["year"]), s(c["tmdb"]), s(c["imdb"])), c["watched"]); n++
    }
    for ((idx, c) in (cases["latest"] as List<Map<String, Any?>>? ?: emptyList()).withIndex()) {
        val wm = (c["watched"] as Map<String, Any?>).mapValues { (it.value as Double).toLong() }
        val rm = (c["removed"] as Map<String, Any?>).mapValues { (it.value as Double).toLong() }
        val cand = W.lookupKeys("w:m:", s(c["title"])!!, i(c["year"]), s(c["tmdb"]), s(c["imdb"]))
        eq("nieuwste wint $idx", W.latest(wm, rm, cand), c["result"]); n++
    }
    println("gedeelde gevallen: $n")

    // aflevering lezen (show-jaar ±1) en serie gezien
    val got = W.episodeKeys("Game of Thrones", 2011, null, null, 1, 2).toSet()
    ok("aflevering: jaar +1 telt", W.episodeWatched(got, "Game of Thrones", 2012, null, null, 1, 2))
    ok("aflevering: andere aflevering niet", !W.episodeWatched(got, "Game of Thrones", 2011, null, null, 1, 3))
    ok("serie: w:s: via tmdb", W.showWatched(setOf("w:s:tmdb:1399"), "Iets anders", null, "1399", null))

    // 2. token
    val T = Build154Logic.Token
    val now = 1_000_000_000_000L
    ok("token: > 1 uur geldig = niet verversen", !T.needsRefresh(now + 2 * 3_600_000L, now))
    ok("token: 23 uur geldig = niet verversen (was: < 24 uur)", !T.needsRefresh(now + 23 * 3_600_000L, now))
    ok("token: < 1 uur geldig = verversen", T.needsRefresh(now + 30 * 60_000L, now))
    ok("token: na een 401 wel verversen", T.needsRefresh(now + 30 * 24 * 3_600_000L, now, got401 = true))
    ok("token: single-flight, al vervangen = niet opnieuw", !T.stillNeeded("nieuw", "oud", now + 90 * 24 * 3_600_000L, now))
    ok("token: single-flight, nog hetzelfde = verversen", T.stillNeeded("oud", "oud", now + 90 * 24 * 3_600_000L, now))
    eq("mislukt: oude token blijft (nooit null)", T.afterFailure("oud", null, false), "oud")
    eq("mislukt: server ouder = oude token blijft", T.afterFailure("oud", "ander", false), "oud")
    eq("mislukt: nieuwer token uit het account overnemen", T.afterFailure("oud", "nieuw", true), "nieuw")

    // 3. wachtrij overleeft een herstart
    val Q = Build154Logic.Queue
    var q = emptyList<Build154Logic.Queue.Entry>()
    q = Q.add(q, Build154Logic.Queue.Entry("1", "add", "m:tmdb:1", "{\"movies\":[{\"ids\":{\"tmdb\":1}}]}", "Dune\tmet tab"))
    q = Q.add(q, Build154Logic.Queue.Entry("2", "add", "e:tmdb:9|1-2", "{}", "Serie S1E2"))
    q = Q.add(q, Build154Logic.Queue.Entry("3", "remove", "m:tmdb:1", "{}", "Dune"))
    eq("wachtrij: nieuwste opdracht per doel wint", q.map { it.id }, listOf("2", "3"))
    q = Q.failed(q, "2", now, retryAfterSec = 120)
    eq("wachtrij: 429 Retry-After", q.first { it.id == "2" }.nextAt, now + 120_000L)
    eq("wachtrij: due", Q.due(q, now).map { it.id }, listOf("3"))
    ok("wachtrij: backoff groeit", Q.backoffMs(3) > Q.backoffMs(1))
    ok("wachtrij: backoff hooguit 6 uur", Q.backoffMs(40) <= 6 * 3_600_000L)
    val f = File.createTempFile("trakt-queue", ".txt"); f.deleteOnExit()
    Q.save(f, q)
    eq("wachtrij: na herstart gelijk", Q.load(f), q)
    eq("wachtrij: wake", Q.due(Q.wakeAll(Q.load(f)), now).size, 2)
    eq("wachtrij: done", Q.done(q, "3").map { it.id }, listOf("2"))
    eq("wachtrij: kapot bestand = leeg", Q.decode("rommel\n2\tx"), emptyList<Build154Logic.Queue.Entry>())

    // 4. migratie van oude id-sleutels
    val old = mapOf<String, Long?>("xv123" to 5_000L, "ep:xs9|1-2" to 6_000L, "xv999" to 7_000L)
    val mig = W.migrate(old) { k ->
        when (k) {
            "xv123" -> W.movieKeys("Dune", 2021, "438631", null)
            "ep:xs9|1-2" -> W.episodeKeys("Game of Thrones", 2011, "1399", null, 1, 2)
            else -> null
        }
    }
    eq("migratie: film krijgt sleutels met dezelfde at", mig["w:m:tmdb:438631"], 5_000L)
    eq("migratie: titelsleutel met dezelfde at", mig["w:m:t:dune|2021"], 5_000L)
    eq("migratie: aflevering", mig["w:e:tmdb:1399|1-2"], 6_000L)
    ok("migratie: oude sleutels blijven", mig.keys.containsAll(old.keys))
    eq("migratie: onbekend blijft zoals het is", mig.size, 3 + 2 + 2)
    eq("migratie: nogmaals = gelijk", W.migrate(mig) { k -> if (k == "xv123") W.movieKeys("Dune", 2021, "438631", null) else null }, mig)

    // 5. uitvinken haalt alle sleutels weg (ook de titelsleutel via jaar ±1)
    val stored = setOf("xv123", "w:m:tmdb:438631", "w:m:t:dune|2021", "w:m:imdb:tt1160419", "w:m:t:anders|2000")
    val own = listOf("xv123") + W.movieKeys("Dune", 2022, "438631", "tt1160419")
    val rm = W.removalKeys(stored, own, W.lookupKeys("w:m:", "Dune", 2022, "438631", "tt1160419"))
    val after = stored - rm
    eq("uitvinken: alleen de andere titel blijft", after, setOf("w:m:t:anders|2000"))
    ok("uitvinken: daarna niet meer gezien", !W.movieWatched(after, "Dune", 2022, "438631", "tt1160419", "xv123"))

    // 6. grootte-grens: eerst titelsleutels van films met een tmdb/imdb-sleutel, oudste eerst
    val big = linkedMapOf<String, Long?>("w:m:t:a|2000" to 1L, "w:m:t:b|2000" to 2L, "w:m:tmdb:1" to 3L, "w:m:t:c|2000" to 0L)
    val pruned = W.prune(big, maxBytes = 3 * 56 + 10, overhead = 0) { it != "w:m:t:c|2000" }
    ok("grens: zonder id-sleutel blijft", "w:m:t:c|2000" in pruned)
    ok("grens: oudste eerst weg", "w:m:t:a|2000" !in pruned && "w:m:t:b|2000" in pruned)

    // 7. zoeken: alleen de juiste titel en het juiste jaar
    val S = Build154Logic.Search
    ok("zoeken: titel en jaar ±1", S.accept("Dune", 2021, "Dune", 2022))
    ok("zoeken: ander jaar niet", !S.accept("Dune", 2021, "Dune", 1984))
    ok("zoeken: andere titel niet", !S.accept("Dune", 2021, "Dune: Part Two", 2021))
    eq("zoeken: niet blind de eerste", S.pick("Dune", 2021, listOf("Dune" to 1984, "Dune" to 2021), { it.first }, { it.second }), "Dune" to 2021)
    eq("zoeken: zonder jaar en twee treffers = geen", S.pick("Dune", null, listOf("Dune" to 1984, "Dune" to 2021), { it.first }, { it.second }), null)

    // 8. gezien-grens
    val G = Build154Logic.Threshold
    ok("grens: 85%", G.watched(0.85, 6000.0, 5100.0, null))
    ok("grens: 75% met 2 min over", G.watched(0.75, 600.5 + 0.0, 600.5 - 100, null) || G.watched(0.98, 700.0, 690.0, null))
    ok("grens: 80% met 10 min over = niet", !G.watched(0.80, 3000.0, 2400.0, null))
    ok("grens: aftiteling", G.watched(0.5, 3000.0, 2600.0, 2500.0))

    println("$checks controles, $failures fout")
    if (failures > 0) kotlin.system.exitProcess(1)
}
