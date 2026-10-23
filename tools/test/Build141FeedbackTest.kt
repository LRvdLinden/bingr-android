package nl.lrvdlinden.bingr

// Build 141: unit-tests van de feedback-logica (features/Build141Feedback.kt). Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/features/AllServersLogic.kt app/src/main/java/nl/lrvdlinden/bingr/features/Build140Logic.kt \
//     app/src/main/java/nl/lrvdlinden/bingr/features/Build141Feedback.kt tools/test/Build141FeedbackTest.kt -include-runtime -d /tmp/b141f.jar
//   java -jar /tmp/b141f.jar
// De workflow Build doet dit vóór het bouwen van de APK; een fout laat de build mislukken.
// Zelfde gevallen als tizen/test/build141_test.js (deel "feedback").

private var failures = 0
private var checks = 0
private fun eq(name: String, got: Any?, want: Any?) {
    checks++
    if (got != want) { failures++; println("FOUT $name kreeg <$got>, verwacht <$want>") }
}

private data class T(val id: String, val title: String, val at: Long)
private data class Row(val key: String, val visible: Boolean = true)

fun main() {

    // MARK: Nieuw op Bingr: de nieuwste N zonder alles te sorteren
    val items = listOf(T("a", "Alpha", 5), T("b", "Bravo", 0), T("c", "Charlie", 9), T("d", "Delta", 9), T("e", "Echo", 1), T("f", "Foxtrot", 7))
    val top3 = Build141Feedback.Recent.top(items.asSequence(), 3, { it.at }, { it.title }).map { it.first.id }
    eq("top: nieuwste eerst, gelijke datum alfabetisch", top3, listOf("c", "d", "f"))
    eq("top: zonder datum nooit", Build141Feedback.Recent.top(items.asSequence(), 10, { it.at }, { it.title }).any { it.first.id == "b" }, false)
    eq("top: k = 0", Build141Feedback.Recent.top(items.asSequence(), 0, { it.at }, { it.title }), emptyList<Pair<T, Long>>())
    eq("top: minder dan k", Build141Feedback.Recent.top(items.asSequence(), 50, { it.at }, { it.title }).size, 5)
    // groot: 100.000 titels, de top 30 klopt met volledig sorteren
    val big = (0 until 100_000).map { T("m$it", "Title $it", ((it * 7919L) % 100_003L)) }
    val want = big.filter { it.at > 0 }.sortedWith(compareByDescending<T> { it.at }.thenBy { it.title }).take(30).map { it.id }
    eq("top: 100.000 titels gelijk aan sorteren", Build141Feedback.Recent.top(big.asSequence(), 30, { it.at }, { it.title }).map { it.first.id }, want)
    val merged = Build141Feedback.Recent.merge(listOf(listOf(T("a1", "Same", 0) to 10L, T("a2", "Old", 0) to 2L), listOf(T("b1", "Same", 0) to 8L, T("b2", "New", 0) to 12L)),
        3, { it.title }) { it.title }
    eq("merge: nieuwste eerst, dubbele titel één keer (nieuwste)", merged.map { it.id }, listOf("b2", "a1", "a2"))

    // MARK: Home: "Nieuw toegevoegd" weg, Nieuw op Bingr op die plek
    val H = Build141Feedback.HomeRows
    val def = listOf("continue", "mylist", "top10", "foryou", "recent", "upcoming", "streaming", "live").map { Row(it) }
    eq("home: recent weg, streaming op die plek", H.displayed(def) { it.key }.map { it.key },
        listOf("continue", "mylist", "top10", "foryou", "streaming", "upcoming", "live"))
    eq("home: precies één Nieuw op Bingr", H.displayed(def) { it.key }.count { it.key == "streaming" }, 1)
    val moved = listOf(Row("streaming", false), Row("continue"), Row("recent", true), Row("live"))
    eq("home: zichtbaarheid van Nieuw op Bingr blijft", H.displayed(moved) { it.key }, listOf(Row("continue"), Row("streaming", false), Row("live")))
    eq("home: zonder recent ongewijzigd", H.displayed(listOf(Row("streaming"), Row("live"))) { it.key }, listOf(Row("streaming"), Row("live")))
    eq("home: zonder streaming alleen recent weg", H.displayed(listOf(Row("recent"), Row("live"))) { it.key }, listOf(Row("live")))
    val edited = listOf(Row("live"), Row("streaming"), Row("continue"))
    eq("home: opslaan zet recent terug vóór streaming", H.forSave(edited, { it.key }, { Row("recent", it) }, false),
        listOf(Row("live"), Row("recent", false), Row("streaming"), Row("continue")))
    eq("home: opslaan zonder oude stand = aan", H.forSave(edited, { it.key }, { Row("recent", it) }, null)[1], Row("recent", true))
    eq("home: heen en terug", H.displayed(H.forSave(H.displayed(def) { it.key }, { it.key }, { Row("recent", it) }, true)) { it.key },
        H.displayed(def) { it.key })
    eq("home: al met recent ongewijzigd", H.forSave(def, { it.key }, { Row("recent", it) }, false), def)

    // MARK: Mijn lijst
    val K = Build141Feedback.MyListKeys
    eq("lijst: TMDB-titel op een andere server", K.externalID("SERVER-B|tmdb-movie-603"), "tmdb-movie-603")
    eq("lijst: gewone titel is geen TMDB", K.externalID("srv|xv123"), null)
    eq("lijst: item-id", K.itemOf("srv|xs|weird"), "xs|weird")
    eq("lijst: server", K.serverOf("Srv1|xv1"), "Srv1")
    eq("lijst: tmdb-id", K.externalFor(1399, true), "tmdb-tv-1399")
    eq("lijst: aanbeveling met TMDB-id", K.storableID("rec-tt0133093", 603, false), "tmdb-movie-603")
    eq("lijst: aanbeveling zonder TMDB-id niet zo opslaan", K.storableID("rec-The Matrix", null, false), null)
    eq("lijst: gewone id blijft", K.storableID("xv12", null, false), "xv12")
    eq("lijst: bestaande TMDB-sleutel hergebruiken", K.keyFor(setOf("OTHER|tmdb-movie-1", "a|xv1"), "active", "tmdb-movie-1"), "OTHER|tmdb-movie-1")
    eq("lijst: nieuwe sleutel op de actieve server", K.keyFor(setOf("a|xv1"), "active", "tmdb-movie-2"), "active|tmdb-movie-2")

    // Mijn lijst via het account: toevoegen uit een zoekresultaat (andere server) → na sync aanwezig → tweede apparaat.
    // Zelfde regels als myList (docs/ACCOUNT.md, build 60): per sleutel wint de nieuwste `at`; een (her)toevoeging krijgt
    // altijd een tijd ná de laatst bekende verwijdering (Build140Logic.Stamp.after), ook als de klok van dit apparaat achterloopt.
    val M = Build140Logic.CatMarks
    val key = "server-b|xv77"
    val removedEarlier = mapOf(key to Build140Logic.Mark(5_000L, false))           // eerder verwijderd, op een ander apparaat
    val deviceA = mapOf(key to Build140Logic.Mark(Build140Logic.Stamp.after(1_000L, 5_000L), true))   // klok A loopt achter
    val server = M.merge(removedEarlier, deviceA)
    eq("sync: toegevoegd na verwijdering blijft aanwezig", server[key]?.on, true)
    val deviceB = M.merge(mapOf(key to Build140Logic.Mark(4_000L, false)), server)
    eq("sync: tweede apparaat ziet hem ook", M.alive(deviceB).contains(key), true)
    eq("sync: nog een ronde verandert niets", M.merge(deviceB, server), deviceB)
    val removedLater = M.merge(deviceB, mapOf(key to Build140Logic.Mark(9_000L, false)))
    eq("sync: latere verwijdering komt door", M.alive(removedLater).contains(key), false)

    // MARK: Verder kijken: poster van de titel, niet de still
    val A = Build141Feedback.ContinueArt
    eq("verder: poster gaat voor still", A.pick("poster.jpg", null, "still.jpg"), "poster.jpg")
    eq("verder: meta-poster (serie) als er geen titelposter is", A.pick(null, "series.jpg", "still.jpg"), "series.jpg")
    eq("verder: anders de bewaarde afbeelding", A.pick(null, null, "still.jpg"), "still.jpg")
    eq("verder: lege poster telt niet", A.pick(" ", "", "still.jpg"), "still.jpg")
    eq("verder: niets", A.pick(null, null, null), null)

    // MARK: achtergrondwerk
    val B = Build141Feedback.Background
    eq("achtergrond: niet direct na het kiezen", B.mayRun(false, false, false, false, 2_000L), false)
    eq("achtergrond: na het wachten", B.mayRun(false, false, false, false, B.SETTLE_MS), true)
    eq("achtergrond: niet tijdens afspelen", B.mayRun(true, false, false, false, 60_000L), false)
    eq("achtergrond: niet tijdens laden", B.mayRun(false, true, false, false, 60_000L), false)
    eq("achtergrond: niet tijdens verversen", B.mayRun(false, false, true, false, 60_000L), false)
    eq("achtergrond: niet tijdens filteren", B.mayRun(false, false, false, true, 60_000L), false)
    eq("achtergrond: kleine heap één catalogus", B.otherLibrariesToKeep(256), 1)
    eq("achtergrond: grote heap twee", B.otherLibrariesToKeep(512), 2)

    println(if (failures == 0) "Build 141 feedback: alle $checks controles geslaagd" else "Build 141 feedback: $failures van $checks controles mislukt")
    if (failures > 0) kotlin.system.exitProcess(1)
}
