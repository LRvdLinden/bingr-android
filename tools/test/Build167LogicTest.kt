package nl.lrvdlinden.bingr

import java.util.Locale
import java.util.TimeZone

// Build 167: Bingr wordt alleen Lifetime (access, lifetime_only, aankondigingskaart, link met ?code=, datums, knoppen).
// Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/features/Build149Logic.kt app/src/main/java/nl/lrvdlinden/bingr/features/Build150Logic.kt \
//     app/src/main/java/nl/lrvdlinden/bingr/features/Build167Logic.kt tools/test/Build167LogicTest.kt -include-runtime -d /tmp/b167.jar
//   java -jar /tmp/b167.jar

private typealias Btn = Build167Logic.Screen.Button

private var failures = 0
private var checks = 0
private fun eq(name: String, got: Any?, want: Any?) {
    checks++
    if (got != want) { failures++; println("FOUT $name kreeg <$got>, verwacht <$want>"); println("::error title=Build167LogicTest::$name") }
}

fun main() {
    val iso: (String?) -> Long? = { Build149Logic.Dates.parseIso(it) }
    val ams = TimeZone.getTimeZone("Europe/Amsterdam")
    val cut = iso("2026-12-07T00:00:00+01:00")!!
    val day = 86_400_000L

    // 1. access: ontbreekt of onbekend = full
    eq("access ontbreekt", Build167Logic.Access.parse(null), "full")
    eq("access full", Build167Logic.Access.parse("full"), "full")
    eq("access activate", Build167Logic.Access.parse("activate"), "activate")
    eq("access hoofdletters", Build167Logic.Access.parse(" Activate "), "activate")
    eq("access onbekend", Build167Logic.Access.parse("blocked"), "full")
    eq("access leeg", Build167Logic.Access.parse(""), "full")
    eq("moet activeren", Build167Logic.Access.mustActivate(true, "activate", false), true)
    eq("niet ingelogd nooit", Build167Logic.Access.mustActivate(false, "activate", true), false)
    eq("full niet", Build167Logic.Access.mustActivate(true, "full", false), false)
    eq("oudere server niet", Build167Logic.Access.mustActivate(true, null, false), false)
    eq("403 stream_start telt", Build167Logic.Access.mustActivate(true, null, true), true)
    eq("403 activation_required", Build167Logic.Access.isActivationRequired(403, "activation_required"), true)
    eq("403 stream_limit niet", Build167Logic.Access.isActivationRequired(403, "stream_limit"), false)
    eq("400 niet", Build167Logic.Access.isActivationRequired(400, "activation_required"), false)
    eq("403 zonder fout niet", Build167Logic.Access.isActivationRequired(403, null), false)

    // 2. lifetime_only lezen
    val lo = Build167Logic.Notice.parse(true, true, true, "2026-12-07T00:00:00+01:00", "bingr-7k2q-9fxm", "2026-12-07T00:00:00+01:00", 30, null, iso)
    eq("aan", lo?.enabled, true)
    eq("cutover", lo?.cutoverAt, cut)
    eq("code in hoofdletters", lo?.code, "BINGR-7K2Q-9FXM")
    eq("geldig tot", lo?.codeValidUntil, cut)
    eq("korting", lo?.discountPct, 30)
    eq("geen proef", lo?.trialUntil, null)
    eq("ontbreekt = null", Build167Logic.Notice.parse(false, null, null, null, null, null, null, null, iso), null)
    eq("enabled false = null", Build167Logic.Notice.parse(true, false, true, null, "BINGR-AAAA-BBBB", null, 30, null, iso), null)
    eq("rare code weg", Build167Logic.Notice.cleanCode("<script>"), null)
    eq("lege code weg", Build167Logic.Notice.cleanCode("  "), null)
    eq("korting buiten bereik weg", Build167Logic.Notice.parse(true, true, true, null, null, null, 250, null, iso)?.discountPct, null)
    eq("kapotte datum = null", Build167Logic.Notice.parse(true, true, true, "morgen", null, null, null, null, iso)?.cutoverAt, null)

    // 3. code tonen tot de einddatum
    eq("code zichtbaar", Build167Logic.Notice.visibleCode(lo, cut - day), "BINGR-7K2Q-9FXM")
    eq("code verlopen", Build167Logic.Notice.visibleCode(lo, cut), null)
    eq("code zonder einddatum", Build167Logic.Notice.visibleCode(lo!!.copy(codeValidUntil = null), cut + day), "BINGR-7K2Q-9FXM")
    eq("geen blok geen code", Build167Logic.Notice.visibleCode(null, 0), null)

    // 4. aankondigingskaart
    val now = cut - 20 * day
    eq("kaart gratis", Build167Logic.Notice.showCard(lo, true, "full", now, null), true)
    eq("kaart oudere server zonder access", Build167Logic.Notice.showCard(lo, true, null, now, null), true)
    eq("geen kaart voor Lifetime", Build167Logic.Notice.showCard(lo, false, "full", now, null), false)
    eq("geen kaart zonder blok", Build167Logic.Notice.showCard(null, true, "full", now, null), false)
    eq("geen kaart vóór announce_at", Build167Logic.Notice.showCard(lo.copy(announced = false), true, "full", now, null), false)
    eq("geen kaart na overgang", Build167Logic.Notice.showCard(lo, true, "full", cut, null), false)
    eq("geen kaart bij activate", Build167Logic.Notice.showCard(lo, true, "activate", now, null), false)
    eq("kaart zonder overgangsdatum", Build167Logic.Notice.showCard(lo.copy(cutoverAt = null), true, "full", now, null), true)
    eq("weggeklikt 1 dag", Build167Logic.Notice.showCard(lo, true, "full", now, now - day), false)
    eq("weggeklikt 3 dagen terug", Build167Logic.Notice.showCard(lo, true, "full", now, now - 3 * day), true)
    eq("laatste week elke dag terug", Build167Logic.Notice.showCard(lo, true, "full", cut - 5 * day, cut - 5 * day - day), true)
    eq("laatste week 12 uur weg", Build167Logic.Notice.showCard(lo, true, "full", cut - 5 * day, cut - 5 * day - day / 2), false)
    eq("klok teruggezet", Build167Logic.Notice.showCard(lo, true, "full", now, now + day), true)
    eq("proef loopt", Build167Logic.Notice.inTrial(lo.copy(trialUntil = cut + day), cut), true)
    eq("proef voorbij", Build167Logic.Notice.inTrial(lo.copy(trialUntil = cut - day), cut), false)
    eq("geen proef", Build167Logic.Notice.inTrial(lo, cut), false)

    // 5. link met ?code=
    val base = "https://bingr.online/upgrade/"
    eq("met code", Build167Logic.Links.upgradeUrl(base, "BINGR-7K2Q-9FXM"), "https://bingr.online/upgrade/?code=BINGR-7K2Q-9FXM")
    eq("zonder code", Build167Logic.Links.upgradeUrl(base, null), base)
    eq("lege code", Build167Logic.Links.upgradeUrl(base, " "), base)
    eq("bestaande parameter blijft", Build167Logic.Links.upgradeUrl("$base?src=app", "BINGR-AAAA-BBBB"), "$base?src=app&code=BINGR-AAAA-BBBB")
    eq("oude code vervangen", Build167Logic.Links.upgradeUrl("$base?code=OUD&x=1", "BINGR-AAAA-BBBB"), "$base?x=1&code=BINGR-AAAA-BBBB")
    eq("fragment blijft achteraan", Build167Logic.Links.upgradeUrl("$base#koop", "BINGR-AAAA-BBBB"), "$base?code=BINGR-AAAA-BBBB#koop")
    eq("vreemde tekens gecodeerd", Build167Logic.Links.upgradeUrl(base, "A B&c"), "$base?code=A%20B%26c")
    eq("weergave", Build167Logic.Links.display("https://bingr.online/upgrade/"), "bingr.online/upgrade")
    eq("weergave met code", Build167Logic.Links.display("https://bingr.online/upgrade/?code=BINGR-AAAA-BBBB"), "bingr.online/upgrade?code=BINGR-AAAA-BBBB")

    // 6. datums (Europe/Amsterdam)
    eq("overgang NL", Build167Logic.Dates.day(cut, ams, Locale("nl")), "7 december 2026")
    eq("overgang EN", Build167Logic.Dates.day(cut, ams, Locale.US), "December 7, 2026")
    eq("t/m middernacht = dag ervoor", Build167Logic.Dates.through(cut, ams, Locale("nl")), "6 december 2026")
    eq("t/m midden op de dag = die dag", Build167Logic.Dates.through(cut + 12 * 3_600_000L, ams, Locale("nl")), "7 december 2026")
    eq("middernacht in een andere zone telt niet", Build167Logic.Dates.through(cut, TimeZone.getTimeZone("UTC"), Locale("nl")), "6 december 2026")
    eq("lastDay", Build167Logic.Dates.lastDay(cut, ams), cut - 1)

    // 7. knoppen van het scherm Activeer Lifetime
    val S = Build167Logic.Screen
    eq("telefoon", S.buttons(tv = false, link = true),
        listOf(Btn.OPEN_UPGRADE, Btn.CHECK_AGAIN, Btn.SUPPORT, Btn.SIGN_OUT, Btn.DELETE_ACCOUNT))
    eq("tv zonder betaalknop", S.buttons(tv = true, link = true).contains(Btn.OPEN_UPGRADE), false)
    eq("link uit: geen betaalknop", S.buttons(tv = false, link = false).contains(Btn.OPEN_UPGRADE), false)
    eq("altijd uitloggen, support, verwijderen", S.buttons(tv = true, link = false).containsAll(listOf(Btn.SUPPORT, Btn.SIGN_OUT, Btn.DELETE_ACCOUNT)), true)
    eq("qr op tv", S.showQR(tv = true, link = true, noBrowser = false), true)
    eq("geen qr op telefoon", S.showQR(tv = false, link = true, noBrowser = false), false)
    eq("qr op telefoon zonder browser", S.showQR(tv = false, link = true, noBrowser = true), true)
    eq("geen qr als link uit", S.showQR(tv = true, link = false, noBrowser = true), false)

    // 8. welkomstscherm vóór het inloggen (aanvulling 1: eerst betalen, dan het account)
    val W = Build167Logic.Welcome
    val wc = W.parse(true, true, "2026-12-07T00:00:00+01:00", false, null, iso)
    eq("welkom: config gelezen", wc?.cutoverAt, cut)
    eq("welkom: proef standaard 0", wc?.trialDays, 0)
    eq("welkom: geen config = zoals nu", W.buyFirst(null, cut + day), false)
    eq("welkom: ontbreekt = null", W.parse(false, true, null, true, 0, iso), null)
    eq("welkom: vóór de overgang zoals nu", W.buyFirst(wc, cut - 1), false)
    eq("welkom: na de overgang (klok)", W.buyFirst(wc, cut), true)
    eq("welkom: server zegt na de overgang", W.buyFirst(wc!!.copy(afterCutover = true, cutoverAt = null), 0), true)
    eq("welkom: uit = zoals nu", W.buyFirst(wc.copy(enabled = false, afterCutover = true), cut + day), false)
    eq("welkom: zonder datum en niet na = zoals nu", W.buyFirst(wc.copy(cutoverAt = null), cut + day), false)
    eq("welkom: proefdagen begrensd", W.parse(true, true, null, false, 9999, iso)?.trialDays, 365)
    eq("welkom: gast-url", W.GUEST_URL, "https://bingr.online/upgrade/?guest=1")
    eq("welkom: qr op tv", W.showQR(tv = true, noBrowser = false), true)
    eq("welkom: knop op telefoon", W.showQR(tv = false, noBrowser = false), false)
    eq("welkom: qr zonder browser", W.showQR(tv = false, noBrowser = true), true)

    println("lifetime: $checks controles")
    if (failures > 0) { println("Build167LogicTest: $failures van $checks MISLUKT"); kotlin.system.exitProcess(1) }
    println("Build167LogicTest: alle $checks controles geslaagd")
}
