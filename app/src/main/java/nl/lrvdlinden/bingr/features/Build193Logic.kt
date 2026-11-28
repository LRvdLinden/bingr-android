package nl.lrvdlinden.bingr

/**
 * Build 193 (versie 1.1.2): de betaaltrechter meten. De app meldt alleen stappen (betaalscherm getoond, aankoop gestart,
 * gelukt, mislukt of afgebroken) met het platform, de build, het gekozen plan en een korte foutcode. Nooit een account,
 * e-mailadres, betaal-id of vrije tekst. Pure logica zonder Android, met unittests (tools/test/Build193LogicTest.kt).
 * Contract: `POST /functions/v1/billing` `{action:"funnel", step, platform, build, product?, error?}`.
 */
object Build193Logic {
    const val PAYWALL_SHOWN = "paywall_shown"
    const val PURCHASE_STARTED = "purchase_started"
    const val PURCHASE_OK = "purchase_ok"
    const val PURCHASE_FAILED = "purchase_failed"
    const val PURCHASE_CANCELLED = "purchase_cancelled"
    const val RESTORE_STARTED = "restore_started"
    const val RESTORE_OK = "restore_ok"
    const val RESTORE_FAILED = "restore_failed"

    val STEPS = setOf(PAYWALL_SHOWN, PURCHASE_STARTED, PURCHASE_OK, PURCHASE_FAILED, PURCHASE_CANCELLED,
        RESTORE_STARTED, RESTORE_OK, RESTORE_FAILED)
    val PRODUCTS = setOf("month", "half", "year", "lifetime")
    val PLATFORMS = setOf("android", "androidtv")

    /** Plan van de app naar het product in het contract; onbekend = null (dan gaat het veld niet mee). */
    fun product(plan: String?): String? = plan?.trim()?.lowercase()?.takeIf { it in PRODUCTS }

    /**
     * Foutcode voor het veld `error`: kleine letters, cijfers en `_`, hooguit 40 tekens. Alles wat daar niet in past
     * (spaties, id's met hoofdletters of leestekens, vrije tekst) wordt `server_error`; leeg of null = `network`
     * (de server gaf geen antwoord).
     */
    fun errorCode(error: String?): String {
        val e = error?.trim()?.lowercase().orEmpty()
        if (e.isEmpty()) return "network"
        if (e == "error") return "server_error"
        return if (e.length <= 40 && e.all { it in 'a'..'z' || it in '0'..'9' || it == '_' }) e else "server_error"
    }

    /** Body van de melding als JSON, of null als de stap of het platform niet in het contract staat. */
    fun body(step: String, platform: String, build: Int, product: String? = null, error: String? = null): String? {
        if (step !in STEPS || platform !in PLATFORMS || build <= 0) return null
        val sb = StringBuilder("{\"action\":\"funnel\",\"step\":\"").append(step).append("\",\"platform\":\"").append(platform)
            .append("\",\"build\":").append(build)
        this.product(product)?.let { sb.append(",\"product\":\"").append(it).append('"') }
        if (error != null) sb.append(",\"error\":\"").append(errorCode(error)).append('"')
        return sb.append('}').toString()
    }

    /**
     * Ontdubbelen: dezelfde stap met hetzelfde product telt binnen [windowMs] maar één keer (een scherm dat opnieuw
     * tekent, twee keer tikken). Niet thread-safe: de aanroeper synchroniseert.
     */
    class Dedup(private val windowMs: Long = 2_000) {
        private val last = HashMap<String, Long>()
        fun shouldSend(step: String, product: String?, now: Long): Boolean {
            val key = step + "|" + (product ?: "")
            val prev = last[key]
            if (prev != null && now - prev in 0 until windowMs) return false
            last[key] = now
            return true
        }
    }

    /** Wat er gemeld wordt als de app terug is van de betaalpagina. */
    enum class Outcome { OK, CANCELLED, NONE }

    /**
     * Een gestarte aankoop afronden. [paid] = het account heeft nu Premium of Lifetime; [returned] = de gebruiker is terug
     * in de app en de server is opnieuw gevraagd. Betaald = OK (één keer); terug zonder betaling = CANCELLED (één keer,
     * de aankoop blijft openstaan: de betaling kan later nog binnenkomen); niets gestart of te lang geleden = NONE.
     */
    class Pending(private val maxAgeMs: Long = 30 * 60_000L) {
        var product: String? = null
            private set
        private var startedAt = 0L
        private var open = false
        private var cancelSent = false

        fun start(product: String?, now: Long) { this.product = product; startedAt = now; open = true; cancelSent = false }

        fun resolve(paid: Boolean, returned: Boolean, now: Long): Outcome {
            if (!open) return Outcome.NONE
            if (now - startedAt > maxAgeMs) { open = false; return Outcome.NONE }
            if (paid) { open = false; return Outcome.OK }
            if (returned && !cancelSent) { cancelSent = true; return Outcome.CANCELLED }
            return Outcome.NONE
        }
    }
}
