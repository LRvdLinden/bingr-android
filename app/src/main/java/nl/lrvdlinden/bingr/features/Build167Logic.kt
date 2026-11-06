package nl.lrvdlinden.bingr

import java.text.DateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Build 167: Bingr wordt alleen Lifetime (contract: docs/ACCOUNT.md in iptv-ios-appletv, sectie "Build 167: alleen
 * Lifetime"). Pure logica zonder Android, getest in tools/test/Build167LogicTest.kt.
 *
 * - [Access]: `access` uit `entitlements` ("full" of "activate"); ontbreekt het veld (oudere server) of is het onbekend,
 *   dan "full". We sluiten nooit iets af door een fout: alleen de server zet een account op "activate".
 * - [Notice]: het blok `lifetime_only` (alleen als `enabled`) en wanneer de aankondigingskaart te zien is.
 * - [Links]: het adres van de betaalpagina met `?code=`.
 * - [Dates]: datums voor de kaart en het scherm (overgangsdatum; "geldig t/m" bij een eindtijd om middernacht = de dag ervoor).
 * - [Screen]: welke knoppen het scherm Activeer Lifetime heeft (telefoon of tv).
 */
object Build167Logic {
    const val FULL = "full"
    const val ACTIVATE = "activate"
    /** 403 van `stream_start` na de overgangsdatum voor een account dat niet Lifetime is. */
    const val ACTIVATION_REQUIRED = "activation_required"

    object Access {
        /** `access` van de server; ontbreekt of onbekend = "full". */
        fun parse(raw: String?): String = when (raw?.trim()?.lowercase()) {
            ACTIVATE -> ACTIVATE
            else -> FULL
        }

        /** Alleen het scherm Activeer Lifetime: de server zegt "activate", of `stream_start` gaf 403 activation_required. */
        fun mustActivate(signedIn: Boolean, access: String?, streamBlocked: Boolean): Boolean =
            signedIn && (parse(access) == ACTIVATE || streamBlocked)

        /** 403 met `error` (of `error_code`) activation_required. */
        fun isActivationRequired(httpCode: Int, error: String?): Boolean =
            httpCode == 403 && error?.trim()?.lowercase() == ACTIVATION_REQUIRED
    }

    /** `lifetime_only` uit `entitlements` (alleen aanwezig als `enabled` true is). Tijden in epoch-ms. */
    data class LifetimeOnly(
        val enabled: Boolean,
        val announced: Boolean,
        val cutoverAt: Long?,
        /** Alleen voor een gratis account met een ongebruikte, geldige code. */
        val code: String?,
        val codeValidUntil: Long?,
        val discountPct: Int?,
        val trialUntil: Long?,
    )

    object Notice {
        /** Vorm van een code: BINGR-XXXX-XXXX (hoofdletters en cijfers); iets anders tonen we niet. */
        private val codeShape = Regex("^[A-Z0-9]{2,12}(-[A-Z0-9]{2,12}){0,4}$")

        fun cleanCode(raw: String?): String? = raw?.trim()?.uppercase()?.takeIf { it.length in 4..40 && codeShape.matches(it) }

        /**
         * Het blok `lifetime_only` lezen. [parseTime] zet een ISO-tijd om naar epoch-ms (in de app Build149Logic.Dates.parseIso).
         * null = niet aanwezig of niet aan.
         */
        fun parse(
            present: Boolean, enabled: Boolean?, announced: Boolean?, cutoverAt: String?, code: String?, codeValidUntil: String?,
            discountPct: Int?, trialUntil: String?, parseTime: (String?) -> Long?,
        ): LifetimeOnly? {
            if (!present || enabled != true) return null
            return LifetimeOnly(
                enabled = true,
                announced = announced == true,
                cutoverAt = parseTime(cutoverAt),
                code = cleanCode(code),
                codeValidUntil = parseTime(codeValidUntil),
                discountPct = discountPct?.takeIf { it in 1..100 },
                trialUntil = parseTime(trialUntil),
            )
        }

        /** Code die we nog mogen tonen: aanwezig en (als er een einddatum is) nog niet verlopen. */
        fun visibleCode(lo: LifetimeOnly?, now: Long): String? {
            val c = lo?.code ?: return null
            val until = lo.codeValidUntil
            return if (until != null && now >= until) null else c
        }

        /**
         * De aankondigingskaart: aan, aangekondigd, gratis account, nog volledige toegang, vóór de overgangsdatum en niet
         * (recent) weggeklikt. Lifetime-accounts zien hem nooit.
         */
        fun showCard(lo: LifetimeOnly?, isFree: Boolean, access: String?, now: Long, dismissedAt: Long?): Boolean {
            if (lo == null || !lo.enabled || !lo.announced || !isFree) return false
            if (Access.parse(access) != FULL) return false
            val cut = lo.cutoverAt
            if (cut != null && now >= cut) return false
            return !dismissed(dismissedAt, now, cut)
        }

        /** Na wegklikken [SNOOZE_MS] niet meer; in de laatste [LAST_DAYS_MS] vóór de overgang komt hij elke dag terug. */
        const val SNOOZE_MS = 3L * 86_400_000L
        const val LAST_DAYS_MS = 7L * 86_400_000L
        const val DAY_MS = 86_400_000L

        fun dismissed(dismissedAt: Long?, now: Long, cutoverAt: Long?): Boolean {
            val d = dismissedAt ?: return false
            if (d > now) return false   // klok teruggezet: gewoon weer tonen
            val snooze = if (cutoverAt != null && cutoverAt - now <= LAST_DAYS_MS) DAY_MS else SNOOZE_MS
            return now - d < snooze
        }

        /** Proefperiode loopt nog (dan geen activatiescherm; de server bepaalt dat ook zelf via `access`). */
        fun inTrial(lo: LifetimeOnly?, now: Long): Boolean = lo?.trialUntil?.let { now < it } == true
    }

    object Links {
        /** Alleen tekens die in een code horen; de rest wordt gecodeerd. */
        private fun enc(s: String): String = buildString {
            for (ch in s) {
                if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.') append(ch)
                else for (b in ch.toString().toByteArray(Charsets.UTF_8)) append('%').append(String.format(Locale.ROOT, "%02X", b.toInt() and 0xFF))
            }
        }

        /**
         * Betaalpagina met de code: `https://bingr.online/upgrade/?code=BINGR-…`. Een bestaande `code=` wordt vervangen,
         * andere parameters en een #fragment blijven staan. Zonder code het adres zoals het is.
         */
        fun upgradeUrl(base: String, code: String?): String {
            val c = code?.trim()?.takeIf { it.isNotEmpty() } ?: return base
            val hashAt = base.indexOf('#')
            val main = if (hashAt >= 0) base.substring(0, hashAt) else base
            val frag = if (hashAt >= 0) base.substring(hashAt) else ""
            val q = main.indexOf('?')
            val path = if (q >= 0) main.substring(0, q) else main
            val params = if (q >= 0) main.substring(q + 1).split('&').filter { it.isNotEmpty() && !it.startsWith("code=") } else emptyList()
            val all = params + "code=${enc(c)}"
            return path + "?" + all.joinToString("&") + frag
        }

        /** Adres voor in beeld (tv): zonder https:// en zonder / aan het eind van het pad. */
        fun display(url: String): String {
            val s = url.removePrefix("https://")
            val q = s.indexOf('?')
            return if (q < 0) s.removeSuffix("/") else s.substring(0, q).removeSuffix("/") + s.substring(q)
        }
    }

    object Dates {
        private fun cal(ms: Long, tz: TimeZone): Calendar = Calendar.getInstance(tz).apply { timeInMillis = ms }

        private fun atMidnight(ms: Long, tz: TimeZone): Boolean {
            val c = cal(ms, tz)
            return c.get(Calendar.HOUR_OF_DAY) == 0 && c.get(Calendar.MINUTE) == 0 && c.get(Calendar.SECOND) == 0
        }

        /** Datum van de overgang ("7 december 2026"). */
        fun day(ms: Long, tz: TimeZone, locale: Locale): String =
            DateFormat.getDateInstance(DateFormat.LONG, locale).apply { timeZone = tz }.format(java.util.Date(ms))

        /**
         * Laatste geldige dag van een eindtijd: om precies middernacht is dat de dag ervoor (07-12 00:00 = t/m 6 december),
         * anders de dag zelf.
         */
        fun lastDay(endMs: Long, tz: TimeZone): Long = if (atMidnight(endMs, tz)) endMs - 1 else endMs

        fun through(endMs: Long, tz: TimeZone, locale: Locale): String = day(lastDay(endMs, tz), tz, locale)
    }

    /**
     * Aanvulling 1 (eerst betalen, dan het account): het welkomstscherm vóór het inloggen. Na `cutover_at` (publieke
     * `billing config`, `lifetime_only`) eerst Koop Lifetime (gastaankoop op [GUEST_URL]) en daaronder Ik heb al een
     * account / Inloggen. Zonder (bewaarde) config, met `enabled` false of vóór de overgang: het welkomstscherm zoals nu.
     */
    object Welcome {
        const val GUEST_URL = "https://bingr.online/upgrade/?guest=1"

        /** `lifetime_only` uit de publieke `billing config`; tijden in epoch-ms. */
        data class Config(val enabled: Boolean, val cutoverAt: Long?, val afterCutover: Boolean, val trialDays: Int)

        fun parse(present: Boolean, enabled: Boolean?, cutoverAt: String?, afterCutover: Boolean?, trialDays: Int?,
                  parseTime: (String?) -> Long?): Config? {
            if (!present) return null
            return Config(enabled == true, parseTime(cutoverAt), afterCutover == true, (trialDays ?: 0).coerceIn(0, 365))
        }

        /**
         * Eerst kopen? Alleen als het aan staat en de overgang voorbij is: volgens de server (`after_cutover`, op het moment
         * van ophalen) of volgens de klok (een bewaarde config van vóór de overgang).
         */
        fun buyFirst(c: Config?, now: Long): Boolean {
            if (c == null || !c.enabled) return false
            if (c.afterCutover) return true
            val cut = c.cutoverAt ?: return false
            return now >= cut
        }

        /** Telefoon: knop naar de betaalpagina; tv (of geen browser): QR-code met het adres. */
        fun showQR(tv: Boolean, noBrowser: Boolean): Boolean = tv || noBrowser
    }

    object Screen {
        enum class Button { OPEN_UPGRADE, CHECK_AGAIN, SUPPORT, SIGN_OUT, DELETE_ACCOUNT }

        /**
         * Telefoon: de knop naar de betaalpagina (als de server `upgrade.link` niet uit zet). Tv: geen knop maar een QR-code
         * met het adres ([showQR]). Altijd: opnieuw kijken, Support, Uitloggen en Account verwijderen.
         */
        fun buttons(tv: Boolean, link: Boolean): List<Button> = buildList {
            if (link && !tv) add(Button.OPEN_UPGRADE)
            add(Button.CHECK_AGAIN)
            add(Button.SUPPORT)
            add(Button.SIGN_OUT)
            add(Button.DELETE_ACCOUNT)
        }

        /** QR-code met het adres: op tv, of op een telefoon zonder browser. Nooit als de server de link uit zet. */
        fun showQR(tv: Boolean, link: Boolean, noBrowser: Boolean): Boolean = link && (tv || noBrowser)

        /** Zo vaak opnieuw ophalen terwijl het scherm openstaat (betalen gebeurt op een ander apparaat). */
        const val REFRESH_MS = 15_000L
    }
}
