package nl.lrvdlinden.bingr

// Build 140: unit-tests van Build140Logic (pure logica). Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/features/AllServersLogic.kt \
//     app/src/main/java/nl/lrvdlinden/bingr/features/Build140Logic.kt tools/test/Build140LogicTest.kt \
//     -include-runtime -d /tmp/b140.jar && java -jar /tmp/b140.jar
// De workflow Build doet dit vóór het bouwen van de APK; een fout laat de build mislukken.

private var failures = 0
private var checks = 0
private fun check(name: String, ok: Boolean, detail: Any? = null) {
    checks++
    if (!ok) { failures++; println("FOUT $name ${detail ?: ""}") }
}
private fun eq(name: String, got: Any?, want: Any?) = check(name, got == want, "kreeg <$got>, verwacht <$want>")

fun main() {
    val B = Build140Logic
    val K = Build140Logic.Kids
    val M = Build140Logic.CatMarks

    // MARK: 2. kindercategorieën
    eq("kids: NL | Kinderfilms", K.isKidsCategory("NL | Kinderfilms"), true)
    eq("kids: |EN| Kids", K.isKidsCategory("|EN| Kids"), true)
    eq("kids: [NL] Disney+", K.isKidsCategory("[NL] Disney+"), true)
    eq("kids: Cartoon Network", K.isKidsCategory("Cartoon Network"), true)
    eq("kids: NL - Familiefilms", K.isKidsCategory("NL - Familiefilms"), true)
    eq("kids: NL: Nick Jr", K.isKidsCategory("NL: Nick Jr"), true)
    eq("kids: Z@pp", K.isKidsCategory("NL | Z@pp"), true)
    eq("kids: Ketnet", K.isKidsCategory("BE | Ketnet"), true)
    eq("kids: kid heel woord", K.isKidsCategory("Kid Movies"), true)
    eq("kids: baby heel woord", K.isKidsCategory("Baby TV"), true)
    eq("kids: accenten", K.isKidsCategory("FR | Animación"), false)
    eq("kids: animation", K.isKidsCategory("US | Animation"), true)
    eq("kids: herhaald voorvoegsel", K.isKidsCategory("NL | VOD | Kinderseries"), true)
    eq("kids: niet anime", K.isKidsCategory("JP | Anime"), false)
    eq("kids: niet kidnap", K.isKidsCategory("Kidnap Thrillers"), false)
    eq("kids: niet babylon", K.isKidsCategory("Babylon Berlin"), false)
    eq("kids: niet sport", K.isKidsCategory("NL | Sport"), false)
    eq("kids: adult wint", K.isKidsCategory("Kids XXX"), false)
    eq("kids: 18+ wint", K.isKidsCategory("Family 18+"), false)
    eq("kids: for adults", K.isKidsCategory("Cartoons for adults"), false)
    eq("kids: erotiek", K.isKidsCategory("NL | Erotiek"), false)
    eq("kids: leeg", K.isKidsCategory(""), false)
    eq("kids: normaliseren", K.normalize("NL | Kinderfilms"), "kinderfilms")
    eq("kids: leeftijd onbekend mag", K.ageAllowed(null, 12), true)
    eq("kids: leeftijd boven grens niet", K.ageAllowed(16, 12), false)
    eq("kids: leeftijd gelijk mag", K.ageAllowed(12, 12), true)

    // MARK: 1. account-brede categorieën
    eq("cat: norm sleutel", M.norm("ABC-1|NL | Sport"), "abc-1|NL | Sport")
    val m0 = mapOf("ABC|Sport" to Build140Logic.Mark(5, true), "abc|Sport" to Build140Logic.Mark(3, false))
    eq("cat: hoofdletters samen, nieuwste wint", M.normalizeAll(m0), mapOf("abc|Sport" to Build140Logic.Mark(5, true)))
    val local = mapOf("abc|Sport" to Build140Logic.Mark(10, true), "abc|News" to Build140Logic.Mark(10, true))
    val server = mapOf("abc|Sport" to Build140Logic.Mark(20, false), "abc|Kids" to Build140Logic.Mark(15, true))
    val merged = M.merge(local, server)
    eq("cat: merge verwijdering nieuwer wint", merged["abc|Sport"], Build140Logic.Mark(20, false))
    eq("cat: merge lokaal blijft", merged["abc|News"], Build140Logic.Mark(10, true))
    eq("cat: merge server erbij", merged["abc|Kids"], Build140Logic.Mark(15, true))
    eq("cat: alive", M.alive(merged), setOf("abc|News", "abc|Kids"))
    eq("cat: index", M.index(setOf("ABC|News", "abc|Kids", "x")), mapOf("abc" to setOf("News", "Kids")))
    // verbergen na een verwijdering met een latere tijd (klok van een ander apparaat loopt voor): toch nieuwer
    val set1 = M.set(mapOf("abc|Sport" to Build140Logic.Mark(1_000, false)), listOf("ABC|Sport"), hidden = true, now = 500)
    eq("cat: set na latere verwijdering", set1["abc|Sport"], Build140Logic.Mark(1_001, true))
    eq("cat: set aan → uit", M.set(set1, listOf("abc|Sport"), hidden = false, now = 2_000)["abc|Sport"], Build140Logic.Mark(2_000, false))
    eq("cat: uit zonder sleutel = niets", M.set(emptyMap(), listOf("abc|X"), hidden = false, now = 1), emptyMap<String, Build140Logic.Mark>())
    eq("cat: server weg", M.removeServer(mapOf("abc|A" to Build140Logic.Mark(1, true), "def|A" to Build140Logic.Mark(1, true)), "ABC", 50),
        mapOf("abc|A" to Build140Logic.Mark(50, false), "def|A" to Build140Logic.Mark(1, true)))
    // migratie: unie met at = 0, een bestaande keuze of verwijdering wint
    val mig = M.migrate(mapOf("abc|Sport" to Build140Logic.Mark(7, false)), listOf("ABC|Sport", "ABC|News", "def|Kids", "zonderbar"))
    eq("cat: migratie wint niet van verwijdering", mig["abc|Sport"], Build140Logic.Mark(7, false))
    eq("cat: migratie nieuw met at 0", mig["abc|News"], Build140Logic.Mark(0, true))
    eq("cat: migratie andere server", mig["def|Kids"], Build140Logic.Mark(0, true))
    eq("cat: migratie zonder server-id weg", mig.containsKey("zonderbar"), false)
    // tweede apparaat: migratie met at 0 verliest altijd van een keuze die elders al gemaakt is
    eq("cat: migratie vs keuze elders", M.merge(mig, mapOf("abc|News" to Build140Logic.Mark(1, false)))["abc|News"], Build140Logic.Mark(1, false))
    eq("cat: prune oude verwijdering", M.prune(mapOf("a|x" to Build140Logic.Mark(0, false), "a|y" to Build140Logic.Mark(0, true)), now = 100 * B.DAY), mapOf("a|y" to Build140Logic.Mark(0, true)))

    // MARK: 8. Mijn lijst: toevoegen na een verwijdering met een latere tijd (klokverschil) blijft staan
    eq("stamp: geen vorige", Build140Logic.Stamp.after(100, null), 100L)
    eq("stamp: vorige ouder", Build140Logic.Stamp.after(100, 50), 100L)
    eq("stamp: vorige nieuwer (klok)", Build140Logic.Stamp.after(100, 500), 501L)
    // simulatie: apparaat A verwijdert om 500 (klok voor), apparaat B voegt toe om 100 → na samenvoegen aanwezig
    val removedOnA = mapOf("srv|m1" to Build140Logic.Mark(500, false))
    val addedOnB = M.set(removedOnA, listOf("srv|m1"), hidden = true, now = 100)
    eq("mijn lijst: toevoegen → sync → aanwezig", M.alive(M.merge(addedOnB, removedOnA)), setOf("srv|m1"))
    eq("mijn lijst: tweede apparaat ziet het", M.alive(M.merge(removedOnA, addedOnB)), setOf("srv|m1"))

    // MARK: ouderpoort
    val r = kotlin.random.Random(42)
    repeat(200) {
        val s = Build140Logic.Gate.newSum(r)
        if (s.multiply) check("som: vermenigvuldiging in bereik", s.a in 6..12 && s.b in 3..9, s)
        else check("som: optelling in bereik", s.a in 11..49 && s.b in 11..49, s)
        check("som: max 3 cijfers", s.answer.toString().length <= Build140Logic.Gate.MAX_DIGITS, s)
    }
    eq("som: tekst ×", Build140Logic.Gate.Sum(7, 8, true).text, "7 × 8 = ?")
    eq("som: tekst +", Build140Logic.Gate.Sum(23, 46, false).text, "23 + 46 = ?")
    val st = Build140Logic.Gate.State(Build140Logic.Gate.Sum(7, 8, true), kotlin.random.Random(1))
    eq("poort: te kort wacht", st.check("5", force = false), Build140Logic.Gate.Result.WAIT)
    eq("poort: goed", st.check("56", force = false), Build140Logic.Gate.Result.OK)
    val st2 = Build140Logic.Gate.State(Build140Logic.Gate.Sum(7, 8, true), kotlin.random.Random(1))
    eq("poort: fout 1", st2.check("55", false), Build140Logic.Gate.Result.WRONG)
    eq("poort: fout 2", st2.check("54", false), Build140Logic.Gate.Result.WRONG)
    eq("poort: fout 3 = nieuwe som", st2.check("53", false), Build140Logic.Gate.Result.NEW_SUM)
    eq("poort: pogingen opnieuw", st2.tries, 0)
    eq("poort: OK met te weinig cijfers = fout", Build140Logic.Gate.State(Build140Logic.Gate.Sum(7, 8, true)).check("5", force = true), Build140Logic.Gate.Result.WRONG)

    // MARK: 3. Voor jou
    val P = Build140Logic.Picks
    eq("genres", P.genres("Action, Sci-Fi / Drama"), listOf("action", "sci-fi", "drama"))
    val w = P.genreWeights(listOf(listOf("animation", "family"), listOf("animation")))
    check("genre-gewicht: recent zwaarder", (w["animation"] ?: 0.0) > (w["family"] ?: 0.0), w)
    val cands = listOf(
        Build140Logic.Picks.Cand("a", listOf("animation", "family"), 7.0, 1),
        Build140Logic.Picks.Cand("b", listOf("animation"), 8.0, 1),
        Build140Logic.Picks.Cand("c", listOf("horror"), 9.0, 1),
        Build140Logic.Picks.Cand("d", listOf("family"), 6.0, 2),
        Build140Logic.Picks.Cand("e", listOf("animation", "family"), 9.0, 1),
    )
    eq("voor jou: genres, beoordeling, bekeken weg", P.byGenres(cands, w, exclude = setOf("e"), limit = 10), listOf("a", "b", "d"))
    eq("voor jou: zonder geschiedenis leeg", P.byGenres(cands, emptyMap(), emptySet(), 10), emptyList<String>())

    // MARK: 4. Nieuw op Bingr en uitgelicht
    eq("eerst gezien: nieuwe server = 0", P.firstSeen(null, listOf("x", "y"), 99), mapOf("x" to 0L, "y" to 0L))
    eq("eerst gezien: nieuwe id = nu", P.firstSeen(mapOf("x" to 0L), listOf("x", "z"), 99), mapOf("x" to 0L, "z" to 99L))
    eq("eerst gezien: weg = weg", P.firstSeen(mapOf("x" to 0L, "q" to 5L), listOf("x"), 99), mapOf("x" to 0L))
    eq("datum: provider eerst", P.addedDate(500, 900), 500L)
    eq("datum: anders eerst gezien", P.addedDate(null, 900), 900L)
    eq("datum: onbekend", P.addedDate(null, null), 0L)
    eq("hero: om en om, dubbel weg, max 8",
        P.heroPool(listOf("a", "b", "c", "d", "e"), listOf("b", "x", "y", "z", "w", "v"), { it }),
        listOf("a", "b", "x", "c", "y", "d", "z", "e"))
    eq("hero: alleen nieuw", P.heroPool(emptyList(), listOf("x", "y"), { it }), listOf("x", "y"))
    eq("hero: start dag 0", P.heroStart(0, 8), 0)
    eq("hero: start schuift per dag", P.heroStart(9, 8), 1)
    eq("hero: start lege lijst", P.heroStart(5, 0), 0)
    eq("hero: dagnummer met tijdzone", P.dayNumber(B.DAY - 1000, 3_600_000), 1L)

    // MARK: 7. automatisch vernieuwen
    val A = Build140Logic.AutoRefresh
    eq("vernieuwen: handmatig nooit", A.due("manual", 10 * B.DAY, false), false)
    eq("vernieuwen: dagelijks na 25 uur", A.due("daily", 25 * 3_600_000L, true), true)
    eq("vernieuwen: dagelijks na 2 uur niet", A.due("daily", 2 * 3_600_000L, false), false)
    eq("vernieuwen: zonder cache altijd", A.due("weekly", null, true), true)
    eq("vernieuwen: wekelijks na 6 dagen niet", A.due("weekly", 6 * B.DAY, false), false)
    eq("vernieuwen: 6 uur", A.due("sixHours", 7 * 3_600_000L, false), true)
    eq("vernieuwen: bij openen één keer", A.due("launch", 1000, false) to A.due("launch", 1000, true), true to false)
    eq("vernieuwen: onbekend = dagelijks", A.due("??", 25 * 3_600_000L, false), true)
    // ALLE servers van het account (zoals de knop sinds build 130), de actieve eerst
    val ages = mapOf("A" to 30 * 3_600_000L, "B" to 1 * 3_600_000L, "C" to 48 * 3_600_000L)
    eq("vernieuwen: alle servers die aan de beurt zijn, actieve eerst",
        A.dueServers(listOf("A", "B", "C"), "C", "daily", { ages[it] }, emptySet()), listOf("C", "A"))
    eq("vernieuwen: bij openen alle servers één keer",
        A.dueServers(listOf("A", "B"), "B", "launch", { ages[it] }, setOf("a")), listOf("B"))

    println(if (failures == 0) "OK: $checks controles geslaagd" else "MISLUKT: $failures van $checks controles")
    if (failures > 0) kotlin.system.exitProcess(1)
}
