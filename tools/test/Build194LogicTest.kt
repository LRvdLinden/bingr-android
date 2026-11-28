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

    println("$checks controles, $failures fout(en)")
    if (failures > 0) kotlin.system.exitProcess(1)
}
