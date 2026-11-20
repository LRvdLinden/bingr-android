package nl.lrvdlinden.bingr

import java.io.File
import java.time.OffsetDateTime
import java.util.Locale
import nl.lrvdlinden.bingr.Build181Logic as B

// Build 181 (Bingr Premium): pure logica (Build181Logic.kt) tegen de gedeelde gevallen uit docs/build181_cases.json van
// iptv-ios-appletv (kopie: tools/test/build181_cases.json; niet hier aanpassen), groep `client`. Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/features/Build181Logic.kt tools/test/Build181LogicTest.kt \
//     -include-runtime -d /tmp/b181.jar
//   java -jar /tmp/b181.jar tools/test/build181_cases.json

private var failures = 0
private var checks = 0
private fun eq(name: String, got: Any?, want: Any?) {
    checks++
    if (got != want) { failures++; println("FOUT $name kreeg <$got>, verwacht <$want>"); println("::error title=Build181LogicTest::$name") }
}

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


@Suppress("UNCHECKED_CAST")
private fun M(v: Any?): Map<String, Any?>? = v as Map<String, Any?>?
private fun L(v: Any?): List<Any?> = v as List<Any?>
private fun S(v: Any?): String? = v as String?
private fun Bo(v: Any?): Boolean? = v as Boolean?
private fun I(v: Any?): Int? = (v as Double?)?.toInt()
private fun time(s: String?): Long? = s?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

private fun premium(o: Map<String, Any?>?): B.Premium? {
    val pr = M(o?.get("prices"))
    return B.parse(
        present = o != null, enabled = Bo(o?.get("enabled")), active = Bo(o?.get("active")), source = S(o?.get("source")),
        plan = S(o?.get("plan")), status = S(o?.get("status")), expiresAt = S(o?.get("expires_at")), trialUntil = S(o?.get("trial_until")),
        willRenew = Bo(o?.get("will_renew")), required = Bo(o?.get("required")), trialEligible = Bo(o?.get("trial_eligible")),
        legacyOffer = Bo(o?.get("legacy_offer")), manage = S(o?.get("manage")), test = Bo(o?.get("test")),
        priceMonth = I(pr?.get("month")), priceHalf = I(pr?.get("half")), priceYear = I(pr?.get("year")), priceLifetime = I(pr?.get("lifetime")),
        parseTime = ::time,
    )
}

fun main(args: Array<String>) {
    val root = M(J(File(args.getOrElse(0) { "tools/test/build181_cases.json" }).readText()).value())!!
    val client = L(root["client"])
    eq("client: gevallen aanwezig", client.size >= 5, true)
    for (c in client) {
        val m = M(c)!!
        val n = m["name"] as String
        val ent = M(m["entitlements"])!!
        val got = B.screen(S(ent["access"]), premium(M(ent["premium"])), time(S(m["now"]))!!, m["offline"] == true)
        val want = M(m["expect"])!!
        eq("$n: blocked", got.blocked, want["blocked"])
        eq("$n: paywall", got.paywall, want["paywall"])
        eq("$n: trial_badge", got.trialBadge, want["trial_badge"])
        eq("$n: manage", got.manage, want["manage"])
    }

    // access-gevallen: het premium-object dat de server verwacht te geven, gelezen zoals de app het leest
    for (c in L(root["access"])) {
        val m = M(c)!!
        val want = M(m["expect"])!!
        val p = premium(want)!!
        val n = "access " + (m["name"] as String)
        eq("$n: enabled", p.enabled, want["enabled"])
        eq("$n: active", p.active, want["active"])
        eq("$n: source", p.source, want["source"])
        eq("$n: plan", p.plan, want["plan"])
        eq("$n: manage", p.manage, want["manage"])
        val s = B.screen(S(want["access"]), p, time(S(m["now"]))!!, false)
        eq("$n: blocked = access activate", s.blocked, want["access"] == "activate")
        if (s.blocked) eq("$n: paywall", s.paywall, if (p.enabled) (if (p.trialEligible) "trial" else "plans") else "legacy")
    }

    // standaardprijzen = defaults van de gedeelde gevallen
    val dp = M(M(root["defaults"])!!["prices"])!!
    val d = B.Prices()
    eq("prijs maand", d.month, I(dp["month"])); eq("prijs half", d.half, I(dp["half"]))
    eq("prijs jaar", d.year, I(dp["year"])); eq("prijs lifetime", d.lifetime, I(dp["lifetime"]))

    // aanbod
    eq("voordeel half", B.Offer.savings(d, "half"), 33)
    eq("voordeel jaar", B.Offer.savings(d, "year"), 50)
    eq("voordeel maand", B.Offer.savings(d, "month"), 0)
    eq("voordeel lifetime", B.Offer.savings(d, "lifetime"), 0)
    eq("per maand jaar", B.Offer.perMonth(d, "year"), 250)
    eq("per maand half", B.Offer.perMonth(d, "half"), 333)
    eq("per maand maand", B.Offer.perMonth(d, "month"), null)
    eq("euro nl", B.Offer.euro(499, Locale.forLanguageTag("nl-NL")).replace('\u00A0', ' '), "€ 4,99")
    eq("euro en", B.Offer.euro(2999, Locale.forLanguageTag("en-GB")), "€29.99")
    eq("plan geldig", B.Offer.isPlan("half"), true)
    eq("plan ongeldig", B.Offer.isPlan("week"), false)

    // adressen
    eq("tv-url", B.Links.tvUrl("year"), "https://bingr.online/upgrade/?plan=year")
    eq("tv-url lifetime", B.Links.tvUrl("lifetime"), "https://bingr.online/upgrade/?plan=lifetime")
    eq("tv-url onbekend", B.Links.tvUrl("x&y"), "https://bingr.online/upgrade/?plan=year")
    eq("checkout ok", B.Links.isCheckout("https://checkout.stripe.com/c/pay/cs_test_a1"), true)
    eq("checkout vreemd", B.Links.isCheckout("https://checkout.stripe.com.evil.example/x"), false)
    eq("checkout http", B.Links.isCheckout("http://checkout.stripe.com/x"), false)
    eq("portal ok", B.Links.isPortal("https://billing.stripe.com/p/session/test_1"), true)
    eq("portal vreemd", B.Links.isPortal("https://bingr.online/"), false)

    // onbekende waarden worden null; prijzen vallen terug
    val odd = B.parse(true, true, true, "paypal", "week", "weird", null, null, null, null, null, null, "google", null,
        priceMonth = 0, priceHalf = null, priceYear = -5, priceLifetime = 5999, parseTime = ::time)!!
    eq("onbekende bron", odd.source, null); eq("onbekend plan", odd.plan, null)
    eq("onbekende status", odd.status, null); eq("onbekend manage", odd.manage, null)
    eq("prijs terugval", odd.prices, B.Prices(499, 1999, 2999, 5999))
    eq("geen blok", B.parse(false, true, true, null, null, null, null, null, null, null, null, null, null, null, parseTime = ::time), null)

    // abonnement of Lifetime; plannen tonen
    val sub = B.Premium(true, true, "stripe", "year", "active", 2_000_000_000_000L, null, true, true, false, false, "stripe", false)
    val life = sub.copy(source = "legacy", plan = "lifetime", status = "lifetime", expiresAt = null, willRenew = false, manage = null)
    eq("abonnement", B.isSubscription(sub), true)
    eq("lifetime geen abonnement", B.isSubscription(life), false)
    eq("plannen: geen premium", B.offerPlans(sub.copy(active = false)), true)
    eq("plannen: actief", B.offerPlans(sub), false)
    eq("plannen: uit", B.offerPlans(sub.copy(enabled = false, active = false)), false)

    // statusregel
    eq("status verlengt", B.Status.line(sub).first, B.Status.Kind.RENEWS)
    eq("status loopt af", B.Status.line(sub.copy(status = "canceled", willRenew = false)).first, B.Status.Kind.ENDS)
    eq("status proef", B.Status.line(sub.copy(status = "trialing", trialUntil = 5L)), B.Status.Kind.TRIAL to 5L)
    eq("status mislukt", B.Status.line(sub.copy(status = "past_due")).first, B.Status.Kind.PAYMENT_FAILED)
    eq("status lifetime", B.Status.line(life).first, B.Status.Kind.NONE)

    // fouten
    eq("fout al premium", B.Errors.kind("already_premium"), B.Errors.Kind.ALREADY)
    eq("fout niet klaar", B.Errors.kind("premium_not_configured"), B.Errors.Kind.NOT_READY)
    eq("fout uit", B.Errors.kind("premium_off"), B.Errors.Kind.OFF)
    eq("fout onbekend", B.Errors.kind(null), B.Errors.Kind.GENERIC)

    println("Build181LogicTest: $checks controles, $failures fouten")
    if (failures > 0) kotlin.system.exitProcess(1)
}
