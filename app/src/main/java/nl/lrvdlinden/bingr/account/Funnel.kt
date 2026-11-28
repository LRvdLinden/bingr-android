package nl.lrvdlinden.bingr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Build 193: de betaaltrechter melden aan de server (`billing`, actie `funnel`). Alleen de stap, het platform, de build,
 * het gekozen plan en een korte foutcode; geen account, geen id's. Elke melding gaat één keer weg op de achtergrond: het
 * antwoord wordt genegeerd, er wordt nooit opnieuw geprobeerd en het scherm wacht er nooit op. Pure logica in
 * Build193Logic.kt.
 */
object Funnel {
    private const val TIMEOUT_MS = 6_000L
    private val dedup = Build193Logic.Dedup()
    private val pending = Build193Logic.Pending()
    private val lock = Any()

    /** Het betaal-, Premium- of activeerscherm staat in beeld. */
    fun paywallShown() = send(Build193Logic.PAYWALL_SHOWN, null, null)

    /** De gebruiker gaat naar de betaalpagina (Checkout, de browser of de QR-code van dit plan). */
    fun started(plan: String?) {
        val product = Build193Logic.product(plan)
        val now = System.currentTimeMillis()
        val first = synchronized(lock) {
            val ok = dedup.shouldSend(Build193Logic.PURCHASE_STARTED, product, now)
            if (ok) pending.start(product, now)
            ok
        }
        if (first) post(Build193Logic.PURCHASE_STARTED, product, null)
    }

    /** De betaalpagina kon niet geopend worden of de controle faalde; [error] = de foutcode van de server (null = geen antwoord). */
    fun failed(plan: String?, error: String?) = send(Build193Logic.PURCHASE_FAILED, Build193Logic.product(plan), Build193Logic.errorCode(error))

    /**
     * Het abonnement is opnieuw opgehaald. [returned] = de gebruiker is net terug van de betaalpagina. Meldt purchase_ok als
     * het account nu Premium of Lifetime heeft, en anders (alleen na terugkomst) één keer purchase_cancelled.
     */
    fun checked(returned: Boolean) {
        val paid = Entitlements.isLifetime || Entitlements.premium?.active == true
        val (outcome, product) = synchronized(lock) { pending.resolve(paid, returned, System.currentTimeMillis()) to pending.product }
        when (outcome) {
            Build193Logic.Outcome.OK -> post(Build193Logic.PURCHASE_OK, product, null)
            Build193Logic.Outcome.CANCELLED -> post(Build193Logic.PURCHASE_CANCELLED, product, null)
            Build193Logic.Outcome.NONE -> Unit
        }
    }

    private fun send(step: String, product: String?, error: String?) {
        val first = synchronized(lock) { dedup.shouldSend(step, product, System.currentTimeMillis()) }
        if (first) post(step, product, error)
    }

    private fun post(step: String, product: String?, error: String?) {
        if (!Secrets.accountsConfigured) return
        val body = Build193Logic.body(step, Entitlements.platform, BuildConfig.VERSION_CODE, product, error) ?: return
        AppScope.launch(Dispatchers.IO) {
            runCatching {
                withTimeoutOrNull(TIMEOUT_MS) {
                    Http.request(Secrets.supabaseUrl + "/functions/v1/billing", method = "POST",
                        headers = mapOf("apikey" to Secrets.supabaseAnonKey, "Accept" to "application/json"), jsonBody = body)
                }
            }
        }
    }
}
