package nl.lrvdlinden.bingr

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import nl.lrvdlinden.bingr.Build176TechLogic.AudioOut as A
import nl.lrvdlinden.bingr.Build176TechLogic.Net as N
import nl.lrvdlinden.bingr.Build176TechLogic.TvHome as T
import nl.lrvdlinden.bingr.Build176TechLogic.Vault as V

// Build 176 (techniek): Watch Next, preview-kanaal, Cronet-keuze, versleutelde opslag (migratie) en audio-uitvoer.
// Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/features/Build176TechLogic.kt tools/test/Build176TechLogicTest.kt -include-runtime -d /tmp/b176t.jar
//   java -jar /tmp/b176t.jar

private var failures = 0
private var checks = 0
private fun eq(name: String, got: Any?, want: Any?) {
    checks++
    if (got != want) { failures++; println("FOUT $name kreeg <$got>, verwacht <$want>"); println("::error title=Build176TechLogicTest::$name") }
}

/** Zelfde vorm als SecureBox (AES-256-GCM, "enc1:" + Base64(iv ‖ ciphertext)), met een sleutel in het geheugen. */
private class FakeBox(private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()) {
    var broken = false
    fun encrypt(plain: String): String? {
        if (broken) return null
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key)
        return V.PREFIX + Base64.getEncoder().encodeToString(c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }
    fun decrypt(stored: String): String? = runCatching {
        if (broken) return null
        val all = Base64.getDecoder().decode(stored.removePrefix(V.PREFIX))
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, all, 0, 12))
        String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
    }.getOrNull()
}

fun main() {
    // --- 1. Watch Next ---
    val wants = listOf(T.Want("|a", 100), T.Want("|b", 200), T.Want("s2|c", 300), T.Want("|a", 50))
    var p = T.plan(emptyList(), wants)
    eq("leeg: alles invoegen, nieuwste eerst, dubbel weg", p.insert, listOf("s2|c", "|b", "|a"))
    p = T.plan(listOf(
        T.Existing(1, "|a", 100, true),           // gelijk → niets
        T.Existing(2, "|b", 150, true),           // nieuwere voortgang → bijwerken
        T.Existing(3, "|x", 10, true),            // niet meer in Verder kijken (gezien) → weg
        T.Existing(4, null, 10, true),            // zonder sleutel → weg
        T.Existing(5, "|a", 100, true),           // dubbel → weg
    ), wants)
    eq("bijwerken", p.update, listOf(2L to "|b"))
    eq("verwijderen (gezien, zonder sleutel, dubbel)", p.delete, listOf(3L, 4L, 5L))
    eq("alleen nieuwe invoegen", p.insert, listOf("s2|c"))
    p = T.plan(listOf(T.Existing(7, "|b", 200, false)), listOf(T.Want("|b", 200)))
    eq("zelf weggehaald blijft weg", Triple(p.insert, p.update, p.delete), Triple(emptyList<String>(), emptyList<Pair<Long, String>>(), emptyList<Long>()))
    p = T.plan(listOf(T.Existing(7, "|b", 200, false)), listOf(T.Want("|b", 260)))
    eq("weggehaald en daarna verder gekeken: opnieuw", p.delete to p.insert, listOf(7L) to listOf("|b"))
    p = T.plan(listOf(T.Existing(1, "|a", 1, true)), emptyList())
    eq("ander profiel / Gratis: rij leeg", p.delete, listOf(1L))
    p = T.plan(emptyList(), (1..30).map { T.Want("|$it", it.toLong()) })
    eq("hooguit 20", p.insert.size, 20); eq("hooguit 20: de nieuwste", p.insert.first(), "|30")
    eq("sleutel", T.key(null, "123"), "|123"); eq("sleutel met server", T.key("s1", "9"), "s1|9")

    // deeplinks
    val link = T.continueLink("ep 1&2", "srv", "Dé titel", "kid")
    eq("continue-link", link, "bingr://continue?id=ep%201%262&src=srv&title=D%C3%A9%20titel&profile=kid")
    val q = link.substringAfter('?').split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
    eq("continue terug", T.parseContinue { q[it] }, T.Continue("ep 1&2", "srv", "Dé titel", "kid"))
    eq("continue zonder id", T.parseContinue { null }, null)
    eq("continue minimaal", T.continueLink("5", null, "", null), "bingr://continue?id=5")
    eq("live-link", T.liveLink("c 1", "p1"), "bingr://live?channel=c%201&profile=p1")
    eq("live-link zonder profiel", T.liveLink("c1", null), "bingr://live?channel=c1")

    // preview-kanaal
    val lib = listOf("c1", "c2", "c3", "c4")
    eq("favorieten in bibliotheekvolgorde", T.channels(lib, setOf("c3", "c1", "weg"), listOf("c2"), emptySet()), listOf("c1", "c3"))
    eq("geen favorieten: recent", T.channels(lib, emptySet(), listOf("c4", "x", "c2", "c4"), emptySet()), listOf("c4", "c2"))
    eq("niets: zenders met nu", T.channels(lib, emptySet(), emptyList(), setOf("c2", "c3")), listOf("c2", "c3"))
    eq("kinderprofiel: alleen uit de gefilterde bibliotheek", T.channels(listOf("k1"), setOf("c1"), listOf("c2"), setOf("k1")), listOf("k1"))
    eq("hooguit max", T.channels((1..40).map { "c$it" }, emptySet(), emptyList(), (1..40).map { "c$it" }.toSet()).size, 15)
    eq("handtekening verandert met programma", T.signature(listOf("c1" to "Nieuws")) != T.signature(listOf("c1" to "Sport")), true)
    eq("handtekening gelijk", T.signature(listOf("c1" to null)), T.signature(listOf("c1" to "")))

    // --- 8. Cronet ---
    eq("Cronet beschikbaar", N.engine(available = true, enabled = true, offlineBuild = false), N.Engine.CRONET)
    eq("Fire TV zonder Play services", N.engine(false, true, false), N.Engine.OKHTTP)
    eq("rooktest/screenshots", N.engine(true, true, true), N.Engine.OKHTTP)
    eq("uitgezet", N.engine(true, false, false), N.Engine.OKHTTP)
    eq("time-out: niet opnieuw", N.retryWithOkHttp(N.failureOf(null, true)), false)
    eq("500: niet opnieuw", N.retryWithOkHttp(N.failureOf(503, false)), false)
    eq("403: opnieuw met OkHttp", N.retryWithOkHttp(N.failureOf(403, false)), true)
    eq("protocol/TLS: opnieuw", N.retryWithOkHttp(N.failureOf(null, false)), true)
    val hm = N.HostMemory(limit = 2)
    hm.remember("A.example"); eq("host onthouden (hoofdletters)", hm.okHttpOnly("a.example"), true)
    eq("andere host", hm.okHttpOnly("b.example"), false); eq("null-host", hm.okHttpOnly(null), false)
    hm.remember("b"); hm.remember("c"); eq("limiet", hm.size() to hm.okHttpOnly("a.example"), 2 to false)

    // --- 9. Versleutelde opslag en migratie ---
    val box = FakeBox()
    val plain = """[{"id":"s1","url":"http://host/get.php?username=jan&password=geheim","mac":"00:1A:79:00:00:01"}]"""
    val valid: (String) -> Boolean = { it.trimStart().startsWith("[") || it.trimStart().startsWith("{") }
    val migrated = V.migrateFile(plain, valid, box::encrypt)
    eq("migratie: versleuteld", migrated?.let { V.isSealed(it) }, true)
    eq("migratie: geen inlog meer leesbaar", migrated?.contains("geheim"), false)
    eq("migratie: zelfde inhoud terug (niet opnieuw inloggen)", migrated?.let { V.open(it, box::decrypt) }, V.Opened(plain, false, false))
    eq("migratie: al versleuteld = niets", V.migrateFile(migrated!!, valid, box::encrypt), null)
    eq("migratie: kapot bestand niet aanraken", V.migrateFile("{kapot", { false }, box::encrypt), null)
    eq("migratie: leeg", V.migrateFile("", valid, box::encrypt), null)
    eq("oude platte tekst lezen", V.open(plain, box::decrypt), V.Opened(plain, true, false))
    eq("andere sleutel: onleesbaar", V.open(migrated, FakeBox()::decrypt), V.Opened(null, false, true))
    val dead = FakeBox().apply { broken = true }
    eq("Keystore werkt niet: platte tekst blijft", V.seal(plain, dead::encrypt), plain)
    eq("Keystore werkt niet: geen migratie", V.migrateFile(plain, valid, dead::encrypt), null)
    eq("seal + open", V.open(V.seal("x", box::encrypt), box::decrypt).text, "x")
    val prefs = mapOf<String, Any?>("tmdbAPIKey" to "abc", "omdbAPIKey" to "", "traktClientID" to box.encrypt("t"), "n" to 3)
    val mv = V.migrateValues(prefs, box::encrypt)
    eq("instellingen: alleen platte tekst", mv.keys, setOf("tmdbAPIKey"))
    eq("instellingen: waarde terug", mv["tmdbAPIKey"]?.let { box.decrypt(it) }, "abc")
    eq("instellingen: Keystore kapot = niets", V.migrateValues(prefs, dead::encrypt), emptyMap<String, String>())
    eq("bestanden", V.SEALED_FILES, setOf("playlists", "nas"))

    // --- 13. Audio-uitvoer ---
    eq("alleen luidspreker", A.active(listOf(2, 1)), A.Kind.SPEAKER)
    eq("Bluetooth gaat voor", A.active(listOf(2, 4, 8)), A.Kind.BLUETOOTH)
    eq("LE Audio", A.active(listOf(2, 26)), A.Kind.BLUETOOTH)
    eq("koptelefoon", A.active(listOf(2, 3)), A.Kind.WIRED)
    eq("USB-headset", A.active(listOf(2, 22)), A.Kind.USB)
    eq("HDMI", A.active(listOf(9)), A.Kind.HDMI)
    eq("bedraad voor HDMI", A.active(listOf(9, 4)), A.Kind.WIRED)

    println("Build176TechLogicTest: ${checks - failures}/$checks geslaagd")
    if (failures > 0) kotlin.system.exitProcess(1)
}
