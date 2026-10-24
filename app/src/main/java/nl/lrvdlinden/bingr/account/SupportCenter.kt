package nl.lrvdlinden.bingr

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Build 146: support vanuit de app (functie `support`, acties `app_list`, `app_get`, `app_mark_read`, `app_reply`,
 * `app_create`, `app_close`; docs/ACCOUNT.md → "Support vanuit de app"), altijd met de sessie van het account.
 * De oude manier (een ticket met captcha) gebruikt de app niet.
 *
 * - [refresh]: de lijst en `counts.unread` (badge op de tab Support en op het profielmenu/tandwiel); bij het openen van de
 *   tab, bij terugkeer naar de voorgrond en bij elke heartbeat (Account.deviceCheck, elke 5 minuten). Zo zien ook
 *   apparaten zonder push (Android TV) een nieuw antwoord.
 * - [openRequest]: een tik op de pushmelding `support_reply` of de deeplink `bingr://support/ticket?n=` (MainScreen opent
 *   Instellingen → Support, met de ouderpoort in een kinderprofiel).
 */
object SupportCenter {
    data class Ticket(
        val number: Int, val subject: String, val status: String, val group: Build146Logic.Support.Group,
        val category: String?, val createdAt: Long?, val lastReplyAt: Long?, val closedAt: Long?, val canReply: Boolean,
        val unread: Boolean, val unreadCount: Int,
    )

    data class Message(val author: String, val body: String, val createdAt: Long?, val diagnostics: String?)

    var tickets by mutableStateOf<List<Ticket>>(emptyList()); private set
    /** `counts.unread` van de server (aantal tickets met een ongelezen antwoord). */
    var unread by mutableIntStateOf(0); private set
    var loaded by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    var listError by mutableStateOf<String?>(null); private set

    /** Wat er geopend moet worden (push/deeplink): null = niets, 0 = de lijst, anders het ticketnummer. */
    var openRequest by mutableStateOf<Int?>(null)

    private var lastRefresh = 0L
    private var job: Job? = null

    private fun parseTicket(e: JsonElement): Ticket? {
        val n = e.jI("number") ?: return null
        val status = e.jS("status") ?: "new"
        return Ticket(
            number = n, subject = e.jS("subject") ?: "", status = status,
            group = Build146Logic.Support.group(status, e.jS("group")), category = e.jS("category"),
            createdAt = Net.parseISODate(e.jS("created_at")), lastReplyAt = Net.parseISODate(e.jS("last_reply_at")),
            closedAt = Net.parseISODate(e.jS("closed_at")), canReply = e.jB("can_reply") ?: (status != "closed"),
            unread = e.jB("unread") == true, unreadCount = e.jI("unread_count") ?: 0,
        )
    }

    private fun body(action: String, vararg fields: Pair<String, JsonElement>): JsonObject =
        JsonObject(mapOf<String, JsonElement>("action" to JsonPrimitive(action)) + fields)

    /** Fouttekst bij een antwoord (al vertaald). */
    fun errorText(r: Account.SupportReply?): String = when {
        r == null -> L("No internet connection.")
        r.code == 401 && r.error == "user_banned" -> Account.bannedMessage
        else -> L(Build146Logic.Support.errorKey(r.error))
    }

    /** Lijst en badge vernieuwen (stil; hoogstens elke 10 s, tenzij [force]). */
    fun refresh(force: Boolean = false) {
        if (!Account.isSignedIn || !Account.configured) { reset(); return }
        val now = System.currentTimeMillis()
        if (!force && now - lastRefresh < 10_000L) return
        if (job?.isActive == true) return
        lastRefresh = now
        job = AppScope.launch { runCatching { load() } }
    }

    private suspend fun load() {
        loading = true
        try {
            val r = Account.supportCall(body("app_list", "status" to JsonPrimitive("all")))
            if (r == null || !r.ok) { listError = errorText(r); return }
            tickets = r.json.jList("items").mapNotNull { parseTicket(it) }
            unread = r.json.jO("counts").jI("unread") ?: tickets.count { it.unread }
            listError = null
            loaded = true
        } finally { loading = false }
    }

    /** Uitgelogd (of ander account): alles vergeten. */
    fun reset() {
        job?.cancel(); job = null
        tickets = emptyList(); unread = 0; loaded = false; listError = null; lastRefresh = 0L
    }

    sealed class Loaded {
        data class Ok(val ticket: Ticket, val messages: List<Message>) : Loaded()
        object NotFound : Loaded()
        data class Failed(val message: String) : Loaded()
    }

    /** Ticket met gesprek (zet het op gelezen). */
    suspend fun get(number: Int): Loaded {
        val r = Account.supportCall(body("app_get", "number" to JsonPrimitive(number)))
        if (r?.code == 404) return Loaded.NotFound
        if (r == null || !r.ok) return Loaded.Failed(errorText(r))
        val t = parseTicket(r.json.jO("ticket") ?: return Loaded.Failed(errorText(r))) ?: return Loaded.Failed(errorText(r))
        val msgs = r.json.jList("messages").map { m ->
            Message(m.jS("author") ?: "", m.jS("body") ?: "", Net.parseISODate(m.jS("created_at")), m.jO("diagnostics").jS("kind"))
        }
        // gelezen: de badge meteen bijwerken
        val before = tickets.firstOrNull { it.number == number }
        if (before?.unread == true) {
            tickets = tickets.map { if (it.number == number) it.copy(unread = false, unreadCount = 0) else it }
            unread = (unread - 1).coerceAtLeast(0)
        }
        return Loaded.Ok(t, msgs)
    }

    /** Reageren; null = gelukt, anders de fouttekst. */
    suspend fun reply(number: Int, message: String, diagnostics: JsonObject?): String? {
        Build146Logic.Support.checkReply(message)?.let { return L(Build146Logic.Support.errorKey(it)) }
        val fields = mutableListOf<Pair<String, JsonElement>>("number" to JsonPrimitive(number), "message" to JsonPrimitive(message.trim()))
        diagnostics?.let { fields += "diagnostics" to it }
        val r = Account.supportCall(body("app_reply", *fields.toTypedArray()))
        if (r == null || !r.ok) return errorText(r)
        refresh(force = true)
        return null
    }

    /** Nieuw ticket; geeft het nummer, of de fouttekst. */
    suspend fun create(subject: String, category: String, message: String, diagnostics: JsonObject?): Pair<Int?, String?> {
        Build146Logic.Support.checkNew(subject, message)?.let { return null to L(Build146Logic.Support.errorKey(it)) }
        val fields = mutableListOf<Pair<String, JsonElement>>(
            "subject" to JsonPrimitive(subject.trim()), "message" to JsonPrimitive(message.trim()),
            "category" to JsonPrimitive(category), "device_id" to JsonPrimitive(Account.deviceId),
            "platform" to JsonPrimitive(Diagnostics.platform),
            "app_version" to JsonPrimitive("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"),
            "lang" to JsonPrimitive(if (Localizer.language == "nl") "nl" else "en"),
        )
        diagnostics?.let { fields += "diagnostics" to it }
        val r = Account.supportCall(body("app_create", *fields.toTypedArray()))
        if (r == null || !r.ok) return null to errorText(r)
        refresh(force = true)
        return r.json.jI("number") to null
    }

    /** Ticket sluiten; null = gelukt. */
    suspend fun close(number: Int): String? {
        val r = Account.supportCall(body("app_close", "number" to JsonPrimitive(number)))
        if (r == null || !r.ok) return errorText(r)
        refresh(force = true)
        return null
    }
}
