package nl.lrvdlinden.bingr

// Build 141: unit-tests van de beveiligingslogica (features/Build141Logic.kt). Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/features/Build141Logic.kt tools/test/Build141LogicTest.kt \
//     -include-runtime -d /tmp/b141.jar && java -jar /tmp/b141.jar
// De workflow Build doet dit vóór het bouwen van de APK; een fout laat de build mislukken.
// Zelfde gevallen als tizen/test/build141_test.js.

private var failures = 0
private var checks = 0
private fun eq(name: String, got: Any?, want: Any?) {
    checks++
    if (got != want) { failures++; println("FOUT $name kreeg <$got>, verwacht <$want>") }
}

fun main() {
    val P = Build141Logic.PinLockout
    // MARK: pincode-limiet
    eq("pin: 4 fouten geen wachttijd", P.waitAfter(4), 0L)
    eq("pin: 5 fouten 30 s", P.waitAfter(5), 30_000L)
    eq("pin: 6 fouten 1 min", P.waitAfter(6), 60_000L)
    eq("pin: 7 fouten 5 min", P.waitAfter(7), 300_000L)
    eq("pin: 8 fouten 15 min", P.waitAfter(8), 900_000L)
    eq("pin: 9 fouten 1 uur", P.waitAfter(9), 3_600_000L)
    eq("pin: 50 fouten max 1 uur", P.waitAfter(50), 3_600_000L)
    var s = P.State()
    repeat(5) { s = P.failed(s, 1_000L) }
    eq("pin: telt fouten", s.failures, 5)
    eq("pin: resterend direct", P.remaining(s, 1_000L), 30_000L)
    eq("pin: resterend na 10 s", P.remaining(s, 11_000L), 20_000L)
    eq("pin: voorbij", P.remaining(s, 40_000L), 0L)
    eq("pin: klok teruggezet", P.remaining(s, 500L), 30_000L)
    eq("pin: onder de grens niets", P.remaining(P.State(3, 1_000L), 1_000L), 0L)
    eq("pin: encode/decode", P.decode(P.encode(P.State(7, 123456789L))), P.State(7, 123456789L))
    eq("pin: decode leeg", P.decode(null), P.State())
    eq("pin: decode onzin", P.decode("x|y"), P.State())
    eq("pin: tekst s", P.shortText(29_500L), "30 s")
    eq("pin: tekst min", P.shortText(61_000L), "2 min")
    eq("pin: tekst uur", P.shortText(3_600_000L), "1 h")

    // MARK: Cast-doorgeefluik
    var n = 0
    val R = Build141Logic.RelayRegistry(maxItems = 3, newToken = { "tok${++n}" })
    val remote = Build141Logic.RelayItem.Remote("http://iptv.example:8080/live/a/b/1.m3u8")
    eq("relay: zonder sessie niets", R.register(remote), null)
    eq("relay: geen token", R.tokenMatches("tok1"), false)
    val t1 = R.begin()
    eq("relay: token per sessie", t1, "tok1")
    val id = R.register(remote)
    eq("relay: geregistreerd", id != null, true)
    eq("relay: zelfde item zelfde id", R.register(remote), id)
    eq("relay: item terug", R.item(id), remote)
    eq("relay: onbekende id", R.item("zzz"), null)
    eq("relay: token klopt", R.tokenMatches("tok1"), true)
    eq("relay: verkeerd token", R.tokenMatches("tok2"), false)
    eq("relay: localhost niet", R.register(Build141Logic.RelayItem.Remote("http://127.0.0.1:8080/x")), null)
    eq("relay: localhost-naam niet", R.register(Build141Logic.RelayItem.Remote("http://localhost/x")), null)
    eq("relay: ::1 niet", R.register(Build141Logic.RelayItem.Remote("http://[::1]:80/x")), null)
    eq("relay: file niet als remote", R.register(Build141Logic.RelayItem.Remote("file:///data/data/x")), null)
    eq("relay: LAN mag", R.register(Build141Logic.RelayItem.Remote("http://192.168.1.10:8096/v.mp4")) != null, true)
    // vaste ontvanger
    eq("relay: eerste ontvanger", R.allowPeer("192.168.1.50"), true)
    eq("relay: zelfde ontvanger", R.allowPeer("192.168.1.50"), true)
    eq("relay: ander apparaat niet", R.allowPeer("192.168.1.66"), false)
    // maximaal aantal items: oudste valt eraf
    R.register(Build141Logic.RelayItem.Remote("http://a.example/1.ts"))
    R.register(Build141Logic.RelayItem.Remote("http://a.example/2.ts"))
    R.register(Build141Logic.RelayItem.Remote("http://a.example/3.ts"))
    eq("relay: max items", R.size, 3)
    // sessie voorbij
    R.end()
    eq("relay: na einde geen token", R.tokenMatches("tok1"), false)
    eq("relay: na einde geen item", R.item(id), null)
    eq("relay: na einde geen ontvanger", R.allowPeer("192.168.1.50"), false)
    val t2 = R.begin()
    eq("relay: nieuw token", t2, "tok2")
    eq("relay: oud token werkt niet meer", R.tokenMatches("tok1"), false)
    R.expectPeer("192.168.1.77")
    eq("relay: bekende Chromecast", R.allowPeer("192.168.1.77"), true)
    eq("relay: andere dan de Chromecast", R.allowPeer("192.168.1.50"), false)
    // paden
    eq("pad: item", Build141Logic.parseRelayPath("/abc/i/1x/s.m3u8"), Build141Logic.RelayPath("abc", "i", "1x"))
    eq("pad: ondertitel", Build141Logic.parseRelayPath("/abc/sub/3.vtt?x=1"), Build141Logic.RelayPath("abc", "sub", "3"))
    eq("pad: oud formaat (base64-adres) niet", Build141Logic.parseRelayPath("/abc/aHR0cDovL3g/s.ts"), null)
    eq("pad: te kort", Build141Logic.parseRelayPath("/abc"), null)
    // bestanden
    eq("map: binnen", Build141Logic.isInside("/sdcard/Android/data/nl/files/downloads/a.mp4", listOf("/sdcard/Android/data/nl/files/downloads")), true)
    eq("map: filesDir niet", Build141Logic.isInside("/data/user/0/nl/files/cache-1.json", listOf("/sdcard/Android/data/nl/files/downloads")), false)
    eq("map: prefix-truc niet", Build141Logic.isInside("/x/downloads-evil/a", listOf("/x/downloads")), false)
    eq("map: .. niet", Build141Logic.isInside("/x/downloads/../files/a", listOf("/x/downloads")), false)
    eq("host: poort en gebruiker", Build141Logic.hostOf("http://u:p@Host.Example:8080/a"), "host.example")
    eq("token: 32 tekens", Build141Logic.randomToken().length, 32)
    eq("token: willekeurig", Build141Logic.randomToken() != Build141Logic.randomToken(), true)

    // MARK: vaste hosts
    val H = Build141Logic.TrustedHosts
    eq("trakt: api", H.traktOAuthBase("https://api.trakt.tv"), "https://api.trakt.tv")
    eq("trakt: auth", H.traktOAuthBase("https://auth.trakt.tv/"), "https://auth.trakt.tv")
    eq("trakt: vreemde host", H.traktOAuthBase("https://evil.example"), "https://api.trakt.tv")
    eq("trakt: http niet", H.traktOAuthBase("http://api.trakt.tv"), "https://api.trakt.tv")
    eq("trakt: leeg", H.traktOAuthBase(null), "https://api.trakt.tv")
    eq("os: standaard", H.openSubtitlesHost(null), "api.opensubtitles.com")
    eq("os: vip", H.openSubtitlesHost("vip-api.opensubtitles.com"), "vip-api.opensubtitles.com")
    eq("os: met https", H.openSubtitlesHost("https://api.opensubtitles.com/"), "api.opensubtitles.com")
    eq("os: vreemde host", H.openSubtitlesHost("evil.example"), "api.opensubtitles.com")
    eq("os: lijkt erop", H.openSubtitlesHost("opensubtitles.com.evil.example"), "api.opensubtitles.com")
    eq("os: achtervoegsel-truc", H.openSubtitlesHost("evilopensubtitles.com"), "api.opensubtitles.com")
    eq("os: met poort", H.openSubtitlesHost("api.opensubtitles.com:8443"), "api.opensubtitles.com")
    eq("os: met gebruiker", H.openSubtitlesHost("x@evil.example"), "api.opensubtitles.com")
    eq("os: http niet", H.openSubtitlesHost("http://api.opensubtitles.com"), "api.opensubtitles.com")
    eq("web: https", H.isWebURL("https://bingr.online/upgrade"), true)
    eq("web: http", H.isWebURL("http://example.com"), true)
    eq("web: intent niet", H.isWebURL("intent://scan/#Intent;scheme=zxing;end"), false)
    eq("web: javascript niet", H.isWebURL("javascript:alert(1)"), false)
    eq("web: file niet", H.isWebURL("file:///sdcard/x"), false)
    eq("web: content niet", H.isWebURL("content://x/y"), false)
    eq("web: leeg", H.isWebURL(""), false)

    println(if (failures == 0) "Build 141: alle $checks controles geslaagd" else "Build 141: $failures van $checks controles mislukt")
    if (failures > 0) kotlin.system.exitProcess(1)
}
