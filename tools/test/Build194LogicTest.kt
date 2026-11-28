package nl.lrvdlinden.bingr

// Build 194: groep Terugkijken in Live TV (Build194Logic.kt). Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/features/Build194Logic.kt tools/test/Build194LogicTest.kt -include-runtime -d /tmp/b194.jar
//   java -jar /tmp/b194.jar

private var failures = 0
private var checks = 0
private fun eq(name: String, got: Any?, want: Any?) {
    checks++
    if (got != want) { failures++; println("FOUT $name kreeg <$got>, verwacht <$want>"); println("::error title=Build194LogicTest::$name") }
}

private data class Ch(val id: String, val days: Int)

fun main() {
    val L = Build194Logic
    fun list(a: List<Ch>, f: List<Ch> = emptyList()) = L.channels(a, f, { it.id }, { it.days }).map { it.id }

    eq("dagen 0 = geen", L.hasCatchup(0), false)
    eq("dagen negatief = geen", L.hasCatchup(-1), false)
    eq("dagen 1 = wel", L.hasCatchup(1), true)

    eq("alleen zenders met terugkijken", list(listOf(Ch("a", 0), Ch("b", 3), Ch("c", 1))), listOf("b", "c"))
    eq("volgorde van de lijst blijft", list(listOf(Ch("z", 2), Ch("a", 5))), listOf("z", "a"))
    eq("geen zenders", list(emptyList()), emptyList<String>())
    eq("andere server erachter", list(listOf(Ch("a", 3)), listOf(Ch("x", 5))), listOf("a", "x"))
    eq("zonder terugkijken elders", list(listOf(Ch("a", 3)), listOf(Ch("x", 0))), listOf("a"))
    eq("dubbele zender eenmaal", list(listOf(Ch("a", 3), Ch("a", 3)), listOf(Ch("a", 3), Ch("x", 2))), listOf("a", "x"))
    eq("alleen elders", list(emptyList(), listOf(Ch("x", 2))), listOf("x"))

    eq("groep zichtbaar met zenders", L.showGroup(1), true)
    eq("groep verborgen zonder zenders", L.showGroup(0), false)
    eq("sleutel verschilt van andere groepen", L.GROUP_KEY !in setOf("★", "◷", "*", "⚑", "♫", "▣"), true)

    // tweeling-koppeling
    val T = Build194Logic.Twin
    fun it(key: String, epg: String?, name: String, days: Int) = Build194Logic.Twin.Item(key, epg, name, days)
    eq("normaliseren hd", T.normalize("RTL 4 HD"), "rtl4")
    eq("normaliseren uhd", T.normalize("NL | RTL 4 UHD"), "nlrtl4")
    eq("normaliseren 4k fhd", T.normalize("RTL 4 FHD 4K"), "rtl4")
    eq("normaliseren catch-up tag", T.normalize("RTL 4 [Catch-up]"), "rtl4")
    eq("normaliseren haakjes terugkijken", T.normalize("RTL 4 (Terugkijken) HD"), "rtl4")
    eq("normaliseren andere haakjes blijven", T.normalize("Eurosport 2 (NL)"), "eurosport2nl")
    eq("normaliseren catch up met spatie", T.normalize("RTL 4 [Catch up]"), "rtl4")
    eq("normaliseren pipe-deel catch-up", T.normalize("RTL 4 | Catch-up"), "rtl4")
    eq("normaliseren pipe-deel blijft", T.normalize("NL | RTL 4"), "nlrtl4")
    eq("normaliseren accenten", T.normalize("Café TV"), "cafetv")
    eq("normaliseren hoofdletters", T.normalize("NPO 1"), "npo1")

    fun links(vararg items: Build194Logic.Twin.Item) = T.link(items.toList())
    eq("epg match", links(it("a", "rtl4.nl", "RTL 4 UHD", 0), it("b", "rtl4.nl", "RTL 4 HD", 3)), mapOf("a" to "b"))
    eq("epg hoofdletters", links(it("a", " RTL4.NL ", "X", 0), it("b", "rtl4.nl", "Y", 3)), mapOf("a" to "b"))
    eq("epg twee kandidaten = geen", links(it("a", "e1", "RTL 4", 0), it("b", "e1", "RTL 4 HD", 3), it("c", "e1", "RTL 4 FHD", 2)), emptyMap<String, String>())
    eq("epg twee kandidaten valt niet door naar naam", links(it("a", "e1", "RTL 4", 0), it("b", "e1", "RTL 4 HD", 3), it("c", "e1", "RTL 4 [Catch-up]", 2)), emptyMap<String, String>())
    eq("naam match zonder epg", links(it("a", null, "RTL 4 UHD", 0), it("b", null, "RTL 4 HD", 3)), mapOf("a" to "b"))
    eq("naam match epg onbekend bij kandidaten", links(it("a", "eenid", "RTL 4 UHD", 0), it("b", null, "RTL 4 HD", 3)), mapOf("a" to "b"))
    eq("naam twee kandidaten = geen", links(it("a", null, "RTL 4", 0), it("b", null, "RTL 4 HD", 3), it("c", null, "RTL 4 [Catch-up]", 3)), emptyMap<String, String>())
    eq("andere naam = geen", links(it("a", null, "RTL 4", 0), it("b", null, "RTL 5", 3)), emptyMap<String, String>())
    eq("te korte naam = geen", links(it("a", null, "TV", 0), it("b", null, "TV HD", 3)), emptyMap<String, String>())
    eq("eigen catch-up blijft zichzelf", links(it("a", "e1", "RTL 4", 5), it("b", "e1", "RTL 4 HD", 3)), emptyMap<String, String>())
    eq("niemand met catch-up", links(it("a", "e1", "RTL 4", 0), it("b", "e1", "RTL 4 HD", 0)), emptyMap<String, String>())
    eq("niemand zonder catch-up", links(it("a", "e1", "RTL 4", 2)), emptyMap<String, String>())
    eq("kandidaat is nooit zichzelf", links(it("a", "e1", "RTL 4", 0)), emptyMap<String, String>())
    eq("meerdere zenders", links(it("a", "e1", "A", 0), it("b", "e1", "A HD", 3), it("c", null, "NPO 1 UHD", 0), it("d", null, "NPO 1 HD", 5)), mapOf("a" to "b", "c" to "d"))

    val R = Build194Logic.Replay
    val s0 = 1_000_000_000_000L
    eq("replay duur", R.duration(s0, s0 + 3_600_000L), 3600.0)
    eq("replay duur ongeldig", R.duration(s0, s0 - 5), 0.0)
    eq("replay geldig", R.valid(s0, s0 + 3_600_000L), true)
    eq("replay te kort", R.valid(s0, s0 + 5_000L), false)
    eq("replay zonder eind", R.valid(s0, null), false)
    eq("replay positie na start", R.offset(s0, s0, 125.0, 3600.0), 125.0)
    eq("replay positie na herstart", R.offset(s0, s0 + 600_000L, 30.0, 3600.0), 630.0)
    eq("replay positie niet voorbij einde", R.offset(s0, s0 + 3_599_000L, 50.0, 3600.0), 3600.0)
    eq("replay positie niet negatief", R.offset(s0, s0 - 60_000L, 0.0, 3600.0), 0.0)
    eq("replay vooruit", R.target(100.0, 30.0, 3595.0), 130.0)
    eq("replay terug", R.target(100.0, -30.0, 3595.0), 70.0)
    eq("replay terug voorbij begin", R.target(10.0, -30.0, 3595.0), 0.0)
    eq("replay vooruit voorbij einde", R.target(3590.0, 30.0, 3595.0), 3595.0)
    eq("replay korter dan marge", R.target(0.0, 30.0, 0.0), 0.0)
    eq("replay slepen midden", R.fromFraction(0.5, 3600.0, 3595.0), 1800.0)
    eq("replay slepen einde", R.fromFraction(1.0, 3600.0, 3595.0), 3595.0)
    eq("replay slepen buiten bereik", R.fromFraction(-0.2, 3600.0, 3595.0), 0.0)
    eq("replay limiet afgelopen programma", R.limit(s0, s0 + 3_600_000L, s0 + 9_000_000L), 3595.0)
    eq("replay limiet lopend programma", R.limit(s0, s0 + 3_600_000L, s0 + 1_200_000L), 1197.0)
    eq("replay limiet net begonnen", R.limit(s0, s0 + 3_600_000L, s0 + 1_000L), 0.0)
    eq("replay limiet kort programma", R.limit(s0, s0 + 12_000L, s0 + 9_000_000L), 7.0)
    eq("replay vooruit niet in de toekomst", R.target(1100.0, 600.0, R.limit(s0, s0 + 3_600_000L, s0 + 1_200_000L)), 1197.0)
    eq("replay slepen niet in de toekomst", R.fromFraction(0.9, 3600.0, 1197.0), 1197.0)
    eq("replay streambegin", R.streamStart(s0, 630.0), s0 + 630_000L)
    eq("replay streambegin afronden", R.streamStart(s0, 0.0004), s0)
    eq("replay voortgang", R.fraction(900.0, 3600.0), 0.25)
    eq("replay voortgang zonder duur", R.fraction(5.0, 0.0), 0.0)
    eq("replay native: passende duur", R.nativeSeekable(3650.0, 3600.0), true)
    eq("replay native: onbekend", R.nativeSeekable(0.0, 3600.0), false)
    eq("replay native: groeiende duur", R.nativeSeekable(240.0, 3600.0), false)
    eq("replay native: veel te lang", R.nativeSeekable(9000.0, 3600.0), false)
    eq("replay native: zonder programma", R.nativeSeekable(3600.0, 0.0), false)

    println("$checks controles, $failures fout(en)")
    if (failures > 0) kotlin.system.exitProcess(1)
}
