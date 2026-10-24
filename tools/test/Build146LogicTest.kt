package nl.lrvdlinden.bingr

// Build 146: unit-tests van features/Build146Logic.kt (pincode als hash met de testvectoren uit docs/ACCOUNT.md,
// welke pincode geldt, profielen samenvoegen, support en de startdiagnose). Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/features/Build146Logic.kt tools/test/Build146LogicTest.kt \
//     -include-runtime -d /tmp/b146.jar
//   java -jar /tmp/b146.jar
// De workflow Build doet dit vóór het bouwen van de APK; een fout laat de build mislukken.
// Zelfde gevallen als tizen/test/build146_test.js.

private var failures = 0
private var checks = 0
private fun eq(name: String, got: Any?, want: Any?) {
    checks++
    if (got != want) { failures++; println("FOUT $name kreeg <$got>, verwacht <$want>") }
}
private fun ok(name: String, v: Boolean) = eq(name, v, true)

private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

fun main() {
    val P = Build146Logic.Pin
    val B = Build146Logic.B64

    // MARK: base64
    eq("b64 leeg", B.encode(ByteArray(0)), "")
    eq("b64 f", B.encode("f".toByteArray()), "Zg==")
    eq("b64 fo", B.encode("fo".toByteArray()), "Zm8=")
    eq("b64 foo", B.encode("foo".toByteArray()), "Zm9v")
    eq("b64 foobar", B.encode("foobar".toByteArray()), "Zm9vYmFy")
    eq("b64 terug", B.decode("Zm9vYmE=")?.toString(Charsets.US_ASCII), "fooba")
    eq("b64 ongeldig teken", B.decode("Zm9v-mE="), null)
    eq("b64 verkeerde lengte", B.decode("Zm9"), null)
    eq("b64 salt 1", B.encode(hex("000102030405060708090a0b0c0d0e0f")), "AAECAwQFBgcICQoLDA0ODw==")
    eq("b64 salt 2", B.encode("Bingr-pin-test-1".toByteArray()), "QmluZ3ItcGluLXRlc3QtMQ==")
    eq("b64 salt 3", B.encode(hex("fffefdfcfbfaf9f8f7f6f5f4f3f2f1f0")), "//79/Pv6+fj39vX08/Lx8A==")

    // MARK: testvectoren (ACCOUNT.md, iter 100.000, dkLen 32): standaard (PBKDF2WithHmacSHA256) en de terugval (Mac)
    val vectors = listOf(
        Triple("1234", "AAECAwQFBgcICQoLDA0ODw==", "hp5sg1DFvrCsw5n7qsO2DSIEM4lrJqZHc00NjxWG4fo="),
        Triple("0000", "QmluZ3ItcGluLXRlc3QtMQ==", "+fGTeA7fJpt28Rrq3DjQxUFemZFqDW4eAb85/ZRYHg8="),
        Triple("98765432", "//79/Pv6+fj39vX08/Lx8A==", "ZPbmm6a1FOjvmJWC0XNDHIDHyxK4739iaGZkx4Oid7c="),
    )
    eq("iteraties", P.ITER, 100_000)
    for ((pin, salt, hash) in vectors) {
        val s = B.decode(salt)!!
        eq("vector $pin (standaard)", B.encode(P.derive(pin, s, P.ITER)), hash)
        eq("vector $pin (terugval)", B.encode(P.derivePortable(pin, s, P.ITER)), hash)
        ok("verify $pin", P.verify(pin, Build146Logic.Pin.Hash(100_000, salt, hash)))
        eq("make met vaste salt $pin", P.make(pin, s).hash, hash)
    }
    val s0 = B.decode(vectors[0].second)!!
    eq("1 iteratie", B.encode(P.derivePortable("1234", s0, 1)), "i/F57D1qcIBBiv5AzlKB8LqqXqCNwubIJuZH8P6K1uI=")
    eq("2 iteraties", B.encode(P.derivePortable("1234", s0, 2)), "oNCs4eO5peWpZXM8MGUdRebd/Uv8TI0RC7CQ7Ngh0N4=")
    eq("1 iteratie (standaard)", B.encode(P.derive("1234", s0, 1)), "i/F57D1qcIBBiv5AzlKB8LqqXqCNwubIJuZH8P6K1uI=")

    // MARK: verkeerde pincode, voorloopnullen, ongeldige objecten
    val h = Build146Logic.Pin.Hash(100_000, vectors[0].second, vectors[0].third)
    ok("andere pin fout", !P.verify("1235", h))
    ok("extra voorloopnul fout", !P.verify("01234", h))
    ok("geen cijfers fout", !P.verify("12a4", h))
    ok("null fout", !P.verify("1234", null))
    eq("parse ander alg", P.parse("sha256", 100000, h.salt, h.hash), null)
    eq("parse te weinig iteraties", P.parse(P.ALG, 1000, h.salt, h.hash), null)
    eq("parse te veel iteraties", P.parse(P.ALG, 3_000_000, h.salt, h.hash), null)
    eq("parse hash te kort", P.parse(P.ALG, 100000, h.salt, "AAAA"), null)
    eq("parse salt te kort", P.parse(P.ALG, 100000, "AAAA", h.hash), null)
    eq("parse iter als double", P.parse(P.ALG, 100000.0, h.salt, h.hash), h)
    eq("parse geldig", P.parse(P.ALG, 100000, h.salt, h.hash), h)
    ok("validPin", P.valid("0000") && P.valid("12345678") && !P.valid("123") && !P.valid("123456789") && !P.valid("12 4") && !P.valid(null))

    // MARK: vaste vorm en lokaal lezen
    eq("json", h.json(), "{\"alg\":\"pbkdf2-sha256\",\"iter\":100000,\"salt\":\"${h.salt}\",\"hash\":\"${h.hash}\"}")
    eq("fromStored eigen vorm", P.fromStored(h.json()), h)
    eq("fromStored andere volgorde", P.fromStored("{\"hash\":\"${h.hash}\", \"salt\":\"${h.salt}\",\"iter\":100000,\"alg\":\"pbkdf2-sha256\",\"x\":1}"), h)
    eq("fromStored oude pincode", P.fromStored("1234"), null)
    ok("oude pincode herkend", P.isLegacy("1234") && !P.isLegacy(h.json()) && !P.isLegacy(null))

    // MARK: make: eigen salt per keer
    val a = P.make("4321"); val b = P.make("4321")
    eq("make salt 16 bytes", B.decode(a.salt)?.size, 16)
    eq("make hash 32 bytes", B.decode(a.hash)?.size, 32)
    ok("make nieuwe salt", a.salt != b.salt && a.hash != b.hash)
    ok("make beide kloppen", P.verify("4321", a) && P.verify("4321", b))
    ok("vaste tijd vergelijken", P.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2)) && !P.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 3)) &&
        !P.constantTimeEquals(byteArrayOf(1), byteArrayOf(1, 0)))

    // MARK: welke pincode geldt (keuzelogica)
    val C = Build146Logic.PinChoice
    eq("server: geldige hash", C.read(true, h, false, true, "1234"), Build146Logic.PinChoice.Server.Hashed(h))
    eq("server: hash null, pin tekst = oude app", C.read(true, null, true, true, "1234"), Build146Logic.PinChoice.Server.Legacy("1234"))
    eq("server: alleen pin = oude app", C.read(false, null, false, true, "0123"), Build146Logic.PinChoice.Server.Legacy("0123"))
    eq("server: pin null = geen pincode", C.read(false, null, false, true, null), Build146Logic.PinChoice.Server.Cleared)
    eq("server: hash null en pin null = verwijderd", C.read(true, null, true, true, null), Build146Logic.PinChoice.Server.Cleared)
    eq("server: niets", C.read(false, null, false, false, null), Build146Logic.PinChoice.Server.Absent)
    eq("server: ongeldige hash", C.read(true, null, false, true, "1234"), Build146Logic.PinChoice.Server.Invalid)
    eq("server: pin geen cijfers", C.read(false, null, false, true, "abcd"), Build146Logic.PinChoice.Server.Cleared)
    val hj = h.json(); val other = Build146Logic.Pin.Hash(100_000, B.encode(ByteArray(16) { 7 }), h.hash).json()
    eq("kies: server nieuwer", C.pick(1, hj, 2, other), Build146Logic.PinChoice.Side.SERVER)
    eq("kies: lokaal nieuwer", C.pick(3, hj, 2, other), Build146Logic.PinChoice.Side.LOCAL)
    eq("kies: gelijk en zelfde hash = lokaal", C.pick(2, hj, 2, hj), Build146Logic.PinChoice.Side.LOCAL)
    eq("kies: gelijk, twee hashes = server (geen om-en-om)", C.pick(2, hj, 2, other), Build146Logic.PinChoice.Side.SERVER)
    eq("kies: gelijk, lokaal nog oud en server hash = server", C.pick(2, "1234", 2, hj), Build146Logic.PinChoice.Side.SERVER)
    eq("kies: gelijk, server leeg = lokaal", C.pick(2, hj, 2, null), Build146Logic.PinChoice.Side.LOCAL)
    eq("kies: gelijk, lokaal leeg = lokaal", C.pick(2, null, 2, hj), Build146Logic.PinChoice.Side.LOCAL)

    // MARK: profielen samenvoegen
    val M = Build146Logic.ProfileMerge
    fun newer(x: String, y: String) = x > y   // ISO-8601 met vaste vorm
    val t1 = "2026-10-01T10:00:00.000Z"; val t2 = "2026-10-02T10:00:00.000Z"; val t3 = "2026-10-03T10:00:00.000Z"
    // lokaal A (hoofd) en B; server A ouder, B nieuwer, C nieuw, D tombstone
    var plan = M.plan(
        listOf(Build146Logic.ProfileMerge.Local("a", t2), Build146Logic.ProfileMerge.Local("b", t1)),
        listOf(Build146Logic.ProfileMerge.Row("a", t1, false, true, 0), Build146Logic.ProfileMerge.Row("b", t2, false, false, 1), Build146Logic.ProfileMerge.Row("c", t2, false, false, 2), Build146Logic.ProfileMerge.Row("d", t1, true, false, null)),
        emptySet(), "a", ::newer)
    eq("samenvoegen: volgorde en soorten", plan.entries, listOf(Build146Logic.ProfileMerge.Entry(Build146Logic.ProfileMerge.Kind.KEEP, 0, "a"), Build146Logic.ProfileMerge.Entry(Build146Logic.ProfileMerge.Kind.TAKE, 1, "b"), Build146Logic.ProfileMerge.Entry(Build146Logic.ProfileMerge.Kind.ADD, null, "c")))
    eq("samenvoegen: lokaal nieuwer → uploaden", plan.needUpload, true)
    eq("samenvoegen: tijden", plan.stamps, mapOf("b" to t2, "c" to t2))
    eq("samenvoegen: niets weg", plan.removed, emptyList<Int>())
    eq("samenvoegen: hoofdprofiel blijft", plan.adoptMain, null)
    // server-tombstone verwijdert lokaal B, nooit het hoofdprofiel A
    plan = M.plan(listOf(Build146Logic.ProfileMerge.Local("a", t1), Build146Logic.ProfileMerge.Local("b", t1)),
        listOf(Build146Logic.ProfileMerge.Row("a", t2, true, false, null), Build146Logic.ProfileMerge.Row("b", t2, true, false, null)), emptySet(), "a", ::newer)
    eq("tombstone: B weg", plan.removed, listOf(1))
    eq("tombstone: hoofdprofiel blijft", plan.entries, listOf(Build146Logic.ProfileMerge.Entry(Build146Logic.ProfileMerge.Kind.KEEP, 0, null)))
    eq("tombstone op hoofdprofiel → uploaden", plan.needUpload, true)
    // lokale tombstone die de server nog niet kent: niet terughalen, wel uploaden
    plan = M.plan(listOf(Build146Logic.ProfileMerge.Local("a", t1)), listOf(Build146Logic.ProfileMerge.Row("a", t1, false, true, 0), Build146Logic.ProfileMerge.Row("x", t1, false, false, 1)), setOf("x"), "a", ::newer)
    eq("lokale tombstone: x niet terug", plan.entries, listOf(Build146Logic.ProfileMerge.Entry(Build146Logic.ProfileMerge.Kind.KEEP, 0, "a")))
    eq("lokale tombstone: uploaden", plan.needUpload, true)
    // alles gelijk: niets te doen
    plan = M.plan(listOf(Build146Logic.ProfileMerge.Local("a", t1)), listOf(Build146Logic.ProfileMerge.Row("a", t1, false, true, 0)), emptySet(), "a", ::newer)
    eq("gelijk: geen upload", plan.needUpload, false)
    eq("gelijk: geen tijden", plan.stamps, emptyMap<String, String>())
    // ander hoofdprofiel op de server (nieuwer) → overnemen
    plan = M.plan(listOf(Build146Logic.ProfileMerge.Local("a", t1), Build146Logic.ProfileMerge.Local("b", t1)),
        listOf(Build146Logic.ProfileMerge.Row("a", t2, false, false, 1), Build146Logic.ProfileMerge.Row("b", t3, false, true, 0)), emptySet(), "a", ::newer)
    eq("hoofdprofiel overnemen", plan.adoptMain, "b")
    eq("volgorde volgt order van de server", plan.entries.map { it.row }, listOf("b", "a"))
    // nieuw account op dit apparaat: alleen lokaal → uploaden
    plan = M.plan(listOf(Build146Logic.ProfileMerge.Local("a", null)), emptyList(), emptySet(), "a", ::newer)
    eq("alleen lokaal → uploaden", plan.needUpload, true)
    // tombstone van de server voor een profiel dat hier niet is: niets
    plan = M.plan(listOf(Build146Logic.ProfileMerge.Local("a", t1)), listOf(Build146Logic.ProfileMerge.Row("a", t1, false, true, 0), Build146Logic.ProfileMerge.Row("z", t2, true, false, null)), emptySet(), "a", ::newer)
    eq("onbekende tombstone: niets", plan.entries.size to plan.needUpload, 1 to false)

    // MARK: support
    val S = Build146Logic.Support
    eq("groep new", S.group("new"), Build146Logic.Support.Group.OPEN)
    eq("groep open", S.group("open"), Build146Logic.Support.Group.IN_PROGRESS)
    eq("groep pending", S.group("pending"), Build146Logic.Support.Group.IN_PROGRESS)
    eq("groep resolved", S.group("resolved"), Build146Logic.Support.Group.CLOSED)
    eq("groep closed", S.group("closed"), Build146Logic.Support.Group.CLOSED)
    eq("groep van de server gaat voor", S.group("new", "closed"), Build146Logic.Support.Group.CLOSED)
    eq("label pending", S.statusKey("pending"), "Awaiting your reply")
    eq("badge 0", S.badge(0), null)
    eq("badge 3", S.badge(3), "3")
    eq("badge 120", S.badge(120), "99+")
    eq("link lijst", S.parseLink("bingr://support"), Build146Logic.Support.Link(null))
    eq("link ticket", S.parseLink("bingr://support/ticket?n=1042"), Build146Logic.Support.Link(1042))
    eq("link ticket hoofdletters", S.parseLink("BINGR://Support/Ticket?x=1&n=7"), Build146Logic.Support.Link(7))
    eq("link ticket zonder nummer = lijst", S.parseLink("bingr://support/ticket?n=abc"), Build146Logic.Support.Link(null))
    eq("link ander", S.parseLink("bingr://live?channel=1"), null)
    eq("formulier goed", S.checkNew("Hallo daar", "Mijn stream start niet."), null)
    eq("formulier onderwerp kort", S.checkNew("Hi", "Mijn stream start niet."), "subject_missing")
    eq("formulier bericht kort", S.checkNew("Hallo", "kort"), "message_too_short")
    eq("formulier onderwerp lang", S.checkNew("x".repeat(121), "Mijn stream start niet."), "subject_too_long")
    eq("formulier te veel links", S.checkNew("Links", (1..7).joinToString(" ") { "https://a$it.nl" }), "too_many_links")
    eq("reactie leeg", S.checkReply("  "), "message_missing")
    eq("reactie 1 teken", S.checkReply("!"), null)
    eq("categorieën", S.categories, listOf("account", "afspelen", "apps", "website", "overig"))

    // MARK: diagnose
    val D = Build146Logic.Diag
    eq("type tv", D.deviceType(true, 960), "tv")
    eq("type tablet", D.deviceType(false, 720), "tablet")
    eq("type telefoon", D.deviceType(false, 411), "phone")
    ok("mijlpaalnamen", D.validMilestone("servers_loaded") && D.validMilestone("first_frame") && !D.validMilestone("Gestart") && !D.validMilestone("9x"))
    eq("opschonen url", D.scrub("fout bij http://user:pw@host.tv:8080/get.php?x=1 klaar"), "fout bij <url> klaar")
    eq("opschonen e-mail", D.scrub("mail a.b@example.com"), "mail <e>")
    eq("opschonen ip", D.scrub("verbinding met 192.168.1.20 mislukt"), "verbinding met <ip> mislukt")
    eq("opschonen mac", D.scrub("mac 00:1A:79:12:34:56"), "mac <mac>")
    eq("opschonen pad", D.scrub("/storage/emulated/0/Download/x"), "/~/x")
    eq("opschonen geheim", D.scrub("password=geheim&x"), "password=…&x")
    eq("stack max 50", D.stack((1..80).map { "at x.y$it" }).size, 50)
    var r = Build146Logic.Diag.Record(146, "Android 15", 1000L)
    r = D.mark(r, "start", 0, 120); r = D.mark(r, "splash", 400, 140); r = D.mark(r, "splash", 900, 999); r = D.mark(r, "Fout", 5, 1)
    eq("mijlpalen eenmalig en geldig", r.marks.map { it.name }, listOf("start", "splash"))
    eq("hoogste geheugen", r.peakMB, 140)
    eq("niet klaar", D.analyze(r)?.kind, Build146Logic.Diag.Kind.NOT_FINISHED)
    eq("niet klaar: laatste mijlpaal", D.analyze(r)?.lastMark, "splash")
    val done = D.mark(r, "ready", 2000, 150)
    eq("klaar = geen probleem", D.analyze(done), null)
    eq("na klaar geen mijlpalen meer", D.mark(done, "late", 3000, 1).marks.size, 3)
    val crashed = r.copy(crash = Build146Logic.Diag.Crash(5000L, "java.lang.IllegalStateException", "x", listOf("at a.b(C.kt:1)"), 146))
    eq("crash", D.analyze(crashed)?.kind, Build146Logic.Diag.Kind.CRASH)
    eq("hang", D.analyze(done.copy(hangMs = 12_000, hangAt = "home"))?.kind, Build146Logic.Diag.Kind.HANG)
    eq("korte hang telt niet", D.analyze(done.copy(hangMs = 4_000)), null)
    eq("zonder scherm (geen activity) telt niet", D.analyze(r, requireMark = "activity"), null)
    eq("zonder scherm maar met crash telt wel", D.analyze(crashed, requireMark = "activity")?.kind, Build146Logic.Diag.Kind.CRASH)
    eq("met scherm niet klaar telt", D.analyze(D.mark(r, "activity", 500, null), requireMark = "activity")?.kind, Build146Logic.Diag.Kind.NOT_FINISHED)
    ok("tekst", D.text(D.analyze(crashed)!!).contains("Stack:\nat a.b(C.kt:1)"))
    eq("snelheid ok", D.speed(300, true), "ok")
    eq("snelheid traag", D.speed(2500, true), "slow")
    eq("snelheid fout", D.speed(null, false), "fail")

    // MARK: tv-gids verlopen
    val E = Build146Logic.Epg
    val now = 1_000_000_000L
    eq("gids: geen gids = niet verlopen", E.expired(now, emptyList()), false)
    eq("gids: loopt nu", E.expired(now, listOf(now - 1000 to now + 1000)), false)
    eq("gids: begint straks", E.expired(now, listOf(now + 3_600_000 to now + 7_200_000)), false)
    eq("gids: alles voorbij = verlopen", E.expired(now, listOf(now - 7_200_000 to now - 3_600_000)), true)
    eq("gids: pas over 4 uur = verlopen", E.expired(now, listOf(now + 4 * 3_600_000L to now + 5 * 3_600_000L)), true)

    println(if (failures == 0) "Build146Logic: alle $checks controles geslaagd" else "Build146Logic: $failures van $checks controles mislukt")
    if (failures > 0) kotlin.system.exitProcess(1)
}
