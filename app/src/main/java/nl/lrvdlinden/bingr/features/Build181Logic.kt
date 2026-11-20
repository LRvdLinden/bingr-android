package nl.lrvdlinden.bingr

import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * Build 181: Bingr Premium (contract: docs/ACCOUNT.md in iptv-ios-appletv, sectie "Build 181: Bingr Premium", §4 en §10).
 * Pure logica zonder Android, getest in tools/test/Build181LogicTest.kt op de gedeelde gevallen (docs/build181_cases.json,
 * groep `client`).
 *
 * - [Premium]: het blok `premium` uit `entitlements` (ontbreekt bij een oudere server = null: alles zoals build 180).
 * - [screen]: wat de app toont (blokkeren, paywall `legacy` / `trial` / `plans`, badge "3 dagen gratis", beheren).
 * - [Offer]: prijzen, voordeel-percentage en bedrag per maand voor de plannenkaarten.
 * - [Links]: QR-adres voor de tv en controle van de Stripe-adressen (Checkout, Customer Portal).
 * - [Status]: de regel onder de naam van het abonnement ("Verlengt op …").
 */
object Build181Logic {
    const val MONTH = "month"
    const val HALF = "half"
    const val YEAR = "year"
    const val LIFETIME = "lifetime"

    /** De drie abonnementen, in de volgorde van de schakelaar. */
    val PLANS = listOf(MONTH, HALF, YEAR)
    private val MONTHS = mapOf(MONTH to 1, HALF to 6, YEAR to 12)
    private val SOURCES = setOf("legacy", "admin", "lifetime", "stripe", "apple")
    private val STATUSES = setOf("incomplete", "trialing", "active", "past_due", "grace", "canceled", "expired", "revoked", "lifetime")

    /** Prijzen in centen (EUR). */
    data class Prices(val month: Int = 499, val half: Int = 1999, val year: Int = 2999, val lifetime: Int = 4999) {
        fun of(plan: String): Int = when (plan) { MONTH -> month; HALF -> half; YEAR -> year; else -> lifetime }
    }

    /** `entitlements.premium`; tijden in epoch-ms. */
    data class Premium(
        val enabled: Boolean,
        val active: Boolean,
        val source: String?,
        val plan: String?,
        val status: String?,
        val expiresAt: Long?,
        val trialUntil: Long?,
        val willRenew: Boolean,
        val required: Boolean,
        val trialEligible: Boolean,
        val legacyOffer: Boolean,
        /** `apple`, `stripe` of null. */
        val manage: String?,
        val test: Boolean,
        val prices: Prices = Prices(),
    )

    private fun cents(v: Int?, d: Int): Int = if (v != null && v > 0) v else d

    /**
     * Het blok lezen; [present] false (oudere server) = null. Onbekende waarden worden null (bron, plan, status, manage),
     * zodat een nieuwe waarde van de server nooit iets openzet of dichtzet wat hij niet bedoelt.
     */
    fun parse(
        present: Boolean, enabled: Boolean?, active: Boolean?, source: String?, plan: String?, status: String?,
        expiresAt: String?, trialUntil: String?, willRenew: Boolean?, required: Boolean?, trialEligible: Boolean?,
        legacyOffer: Boolean?, manage: String?, test: Boolean?,
        priceMonth: Int? = null, priceHalf: Int? = null, priceYear: Int? = null, priceLifetime: Int? = null,
        parseTime: (String?) -> Long?,
    ): Premium? {
        if (!present) return null
        val d = Prices()
        return Premium(
            enabled = enabled == true,
            active = active == true,
            source = source?.takeIf { it in SOURCES },
            plan = plan?.takeIf { it in MONTHS.keys || it == LIFETIME },
            status = status?.takeIf { it in STATUSES },
            expiresAt = parseTime(expiresAt),
            trialUntil = parseTime(trialUntil),
            willRenew = willRenew == true,
            required = required == true,
            trialEligible = trialEligible == true,
            legacyOffer = legacyOffer == true,
            manage = manage?.takeIf { it == "apple" || it == "stripe" },
            test = test == true,
            prices = Prices(cents(priceMonth, d.month), cents(priceHalf, d.half), cents(priceYear, d.year), cents(priceLifetime, d.lifetime)),
        )
    }

    // MARK: scherm

    /** Paywall-varianten: oud scherm (Activeer Lifetime), met proef, of alleen de plannen. */
    const val PAYWALL_LEGACY = "legacy"
    const val PAYWALL_TRIAL = "trial"
    const val PAYWALL_PLANS = "plans"

    data class Screen(val blocked: Boolean, val paywall: String?, val trialBadge: Boolean, val manage: String?)

    /**
     * Wat de app toont (groep `client` van de gedeelde gevallen):
     * - zonder `premium` of met `enabled` false: alleen `access = "activate"` blokkeert, met het oude scherm;
     * - anders blokkeren bij `access = "activate"`, of offline met een abonnement dat niet verlengt en waarvan
     *   `expires_at` voorbij is (tot de server weer antwoordt); Lifetime nooit;
     * - paywall `trial` als de proef mag, anders `plans`; badge "3 dagen gratis" alleen met proef.
     */
    fun screen(access: String?, p: Premium?, now: Long, offline: Boolean): Screen {
        val activate = access?.trim()?.lowercase() == "activate"
        if (p == null || !p.enabled) {
            return Screen(activate, if (activate) PAYWALL_LEGACY else null, false, p?.manage)
        }
        val exp = p.expiresAt
        val lapsed = offline && p.active && p.status != "lifetime" && !p.willRenew && exp != null && exp <= now
        val blocked = activate || lapsed
        return Screen(
            blocked = blocked,
            paywall = if (!blocked) null else if (p.trialEligible) PAYWALL_TRIAL else PAYWALL_PLANS,
            trialBadge = blocked && p.trialEligible,
            manage = p.manage,
        )
    }

    /** Een abonnement (geen Lifetime): heet naar zijn plan, nooit "Lifetime". */
    fun isSubscription(p: Premium?): Boolean = p != null && p.active && (p.source == "stripe" || p.source == "apple")

    /** Het venster met plannen in plaats van de vergelijking: de groep doet mee en het account heeft nog geen Premium. */
    fun offerPlans(p: Premium?): Boolean = p != null && p.enabled && !p.active

    // MARK: aanbod

    object Offer {
        /** Voordeel t.o.v. maandelijks (19,99 per 6 mnd = 33 %, 29,99 per jaar = 50 %); 0 voor maand of Lifetime. */
        fun savings(pr: Prices, plan: String): Int {
            val m = MONTHS[plan] ?: return 0
            if (plan == MONTH) return 0
            val full = pr.month.toLong() * m
            if (full <= 0) return 0
            return Math.round((1.0 - pr.of(plan).toDouble() / full) * 100.0).toInt().coerceAtLeast(0)
        }

        /** Bedrag per maand in centen (afgerond), voor "€ 2,50 per maand" onder halfjaar en jaar; null bij maand en Lifetime. */
        fun perMonth(pr: Prices, plan: String): Int? {
            val m = MONTHS[plan] ?: return null
            if (plan == MONTH) return null
            return Math.round(pr.of(plan).toDouble() / m).toInt()
        }

        /** "€ 4,99" (nl), "€4.99" (en) enz. */
        fun euro(cents: Int, locale: Locale): String = runCatching {
            NumberFormat.getCurrencyInstance(locale).apply { currency = Currency.getInstance("EUR") }.format(cents / 100.0)
        }.getOrElse { "€" + String.format(Locale.ROOT, "%.2f", cents / 100.0) }

        /** Geldig plan voor `checkout` (de drie abonnementen en Lifetime). */
        fun isPlan(plan: String?): Boolean = plan == MONTH || plan == HALF || plan == YEAR || plan == LIFETIME
    }

    // MARK: adressen

    object Links {
        const val UPGRADE = "https://bingr.online/upgrade/"

        /** Tv: QR-code naar de betaalpagina met het gekozen plan (daar inloggen en afrekenen). */
        fun tvUrl(plan: String): String = UPGRADE + "?plan=" + (if (Offer.isPlan(plan)) plan else YEAR)

        /** Alleen een Stripe Checkout-adres openen. */
        fun isCheckout(url: String?): Boolean = url != null && url.startsWith("https://checkout.stripe.com/") && url.none { it.isWhitespace() }

        /** Alleen een Stripe Customer Portal-adres openen. */
        fun isPortal(url: String?): Boolean = url != null && url.startsWith("https://billing.stripe.com/") && url.none { it.isWhitespace() }

        /** Na de Customer Portal terug naar bingr.online (de server staat alleen https://bingr.online/… toe). */
        const val PORTAL_RETURN = "https://bingr.online/upgrade/"
    }

    // MARK: status

    object Status {
        enum class Kind { NONE, TRIAL, PAYMENT_FAILED, RENEWS, ENDS }

        /** Welke regel onder de naam, met de datum (epoch-ms) erbij. */
        fun line(p: Premium?): Pair<Kind, Long?> {
            if (p == null) return Kind.NONE to null
            if (p.status == "trialing" && p.trialUntil != null) return Kind.TRIAL to p.trialUntil
            if ((p.status == "past_due" || p.status == "grace") && p.expiresAt != null) return Kind.PAYMENT_FAILED to p.expiresAt
            if (p.expiresAt != null && isSubscription(p)) return (if (p.willRenew) Kind.RENEWS else Kind.ENDS) to p.expiresAt
            return Kind.NONE to null
        }
    }

    // MARK: fouten van billing

    object Errors {
        enum class Kind { ALREADY, NOT_READY, OFF, RATE_LIMITED, GENERIC }

        /** Foutcode van `billing checkout` / `portal` naar wat we de gebruiker zeggen. */
        fun kind(error: String?): Kind = when (error?.trim()?.lowercase()) {
            "already_lifetime", "already_premium" -> Kind.ALREADY
            "premium_not_configured", "billing_unavailable", "no_customer" -> Kind.NOT_READY
            "premium_off", "plan_required" -> Kind.OFF
            "rate_limited" -> Kind.RATE_LIMITED
            else -> Kind.GENERIC
        }
    }
}
