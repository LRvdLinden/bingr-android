package nl.lrvdlinden.bingr

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Abonnement (build 88): Gratis of Lifetime, via device-auth `entitlements` (en eventueel `entitlements` in het
 * antwoord van `config` / `heartbeat`). Bewaard per account (eigen SharedPreferences-bestand), zodat de sloten ook
 * offline kloppen. Onbekend (nog nooit opgehaald, niet ingelogd of netwerkfout zonder cache) = alles open, zoals
 * Lifetime: we sluiten nooit iets af door een fout.
 */
object Entitlements {
    data class Info(
        val plan: String,
        val streams: Int?,
        val devices: Int?,
        /** null = onbeperkt */
        val servers: Int?,
        val stats: Boolean,
        val nowWatching: Boolean,
        val trakt: Boolean,
        val multiServer: Boolean,
        val themes: Boolean,
        val upgradeUrl: String,
        val upgradeLink: Boolean,
        val price: String,
        val priceEn: String,
    ) {
        val isFree: Boolean get() = plan == "free"
    }

    enum class Feature(val key: String) {
        STATS("stats"), NOW_WATCHING("now_watching"), TRAKT("trakt"), MULTI_SERVER("multi_server"), THEMES("themes"),
        STREAMS("streams"), DEVICES("devices");

        companion object {
            fun from(k: String?): Feature? = entries.firstOrNull { it.key == k?.lowercase() }
                ?: when (k?.lowercase()) {
                    "now-watching", "watching", "list_streams", "watch_log", "history" -> NOW_WATCHING
                    "watch_stats", "statistics" -> STATS
                    "servers" -> MULTI_SERVER
                    "theme", "accent", "accent_color" -> THEMES
                    else -> null
                }
        }
    }

    const val DEFAULT_URL = "https://bingr.online/upgrade/"
    const val DEFAULT_PRICE = "€ 4,99"
    const val DEFAULT_PRICE_EN = "€4.99"

    /** Limieten van de abonnementen voor de vergelijking (de server is leidend voor je eigen plan). */
    // gelijktijdige kijkers: Bingr-telling op de server, los van de limiet van de IPTV-provider
    const val FREE_STREAMS = 1
    const val FREE_DEVICES = 8
    const val FREE_SERVERS = 1
    const val LIFETIME_STREAMS = 3
    const val LIFETIME_DEVICES = 15

    /** Huidig abonnement; null = onbekend (gedraagt zich als Lifetime). */
    var current by mutableStateOf<Info?>(null)
        private set

    private var sp: SharedPreferences? = null
    private var owner: String? = null
    private var lastFetch = 0L
    private var job: kotlinx.coroutines.Job? = null

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("bingr.entitlements", Context.MODE_PRIVATE)
    }

    // MARK: wat mag er

    val isFree: Boolean get() = current?.isFree == true
    val isLifetime: Boolean get() = current?.isFree == false
    val stats: Boolean get() = current?.stats ?: true
    val nowWatching: Boolean get() = current?.nowWatching ?: true
    val trakt: Boolean get() = current?.trakt ?: true
    val themes: Boolean get() = current?.themes ?: true
    val upgradeUrl: String get() = current?.upgradeUrl ?: DEFAULT_URL
    val upgradeLink: Boolean get() = current?.upgradeLink ?: true

    /** Prijs in de taal van de app ("€ 4,99" in het Nederlands, anders "€4.99"). */
    val price: String
        get() = if (Localizer.language.startsWith("nl")) current?.price ?: DEFAULT_PRICE else current?.priceEn ?: DEFAULT_PRICE_EN

    /** Mag er nog een server bij? Bestaande extra servers blijven gewoon werken. */
    fun canAddServer(existing: Int): Boolean {
        val c = current ?: return true
        if (c.multiServer) return true
        val max = c.servers ?: return true
        return existing < max
    }

    fun allowed(f: Feature): Boolean = when (f) {
        Feature.STATS -> stats
        Feature.NOW_WATCHING -> nowWatching
        Feature.TRAKT -> trakt
        Feature.THEMES -> themes
        Feature.MULTI_SERVER -> current?.multiServer ?: true
        Feature.STREAMS, Feature.DEVICES -> !isFree
    }

    // MARK: per account bewaren

    /** Ander (of geen) account: cache van dat account laden. */
    fun onAccount(userId: String?) {
        if (userId == owner && (userId == null || current != null)) return
        owner = userId
        lastFetch = 0L
        current = userId?.let { uid -> sp?.getString("e.$uid", null)?.let { parseStored(it) } }
    }

    fun signedOut() {
        owner = null; current = null; lastFetch = 0L
        job?.cancel(); job = null
    }

    private fun parseStored(s: String): Info? = runCatching { parse(Http.json.parseToJsonElement(s)) }.getOrNull()

    private fun save(raw: JsonElement) {
        val uid = owner ?: Account.userId ?: return
        sp?.edit()?.putString("e.$uid", raw.toString())?.apply()
    }

    // MARK: lezen

    fun parse(j: JsonElement?): Info? {
        val o = j.jObj ?: return null
        val plan = o.jS("plan")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val free = plan == "free"
        val limits = o["limits"] as? JsonObject
        val features = o["features"] as? JsonObject
        val upgrade = o["upgrade"] as? JsonObject
        fun feature(k: String): Boolean = features.jB(k) ?: !free
        val servers = if (limits?.containsKey("servers") == true) limits.jI("servers") else (if (free) FREE_SERVERS else null)
        return Info(
            plan = plan,
            streams = limits.jI("streams"),
            devices = limits.jI("devices"),
            servers = servers,
            stats = feature("stats"),
            nowWatching = feature("now_watching"),
            trakt = feature("trakt"),
            multiServer = feature("multi_server"),
            themes = feature("themes"),
            upgradeUrl = upgrade.jS("url")?.takeIf { it.startsWith("https://") } ?: DEFAULT_URL,
            upgradeLink = upgrade.jB("link") ?: true,
            price = upgrade.jS("price")?.takeIf { it.isNotBlank() } ?: DEFAULT_PRICE,
            priceEn = upgrade.jS("price_en")?.takeIf { it.isNotBlank() } ?: DEFAULT_PRICE_EN,
        )
    }

    /** Antwoord van `entitlements` (of het veld `entitlements` in config/heartbeat) overnemen en bewaren. */
    fun apply(j: JsonElement?): Boolean {
        val info = parse(j) ?: return false
        if (owner == null) owner = Account.userId
        val wasFree = current?.isFree
        current = info
        lastFetch = System.currentTimeMillis()
        save(j!!)
        if (wasFree == true && !info.isFree) Upgrade.upgraded()
        return true
    }

    /** Server meldt 403 feature_locked: dat onderdeel meteen dicht en het abonnement opnieuw ophalen. */
    fun locked(feature: String?) {
        val c = current ?: Info(
            plan = "free", streams = FREE_STREAMS, devices = FREE_DEVICES, servers = FREE_SERVERS,
            stats = true, nowWatching = true, trakt = true, multiServer = true, themes = true,
            upgradeUrl = DEFAULT_URL, upgradeLink = true, price = DEFAULT_PRICE, priceEn = DEFAULT_PRICE_EN,
        )
        current = when (Feature.from(feature)) {
            Feature.STATS -> c.copy(plan = "free", stats = false)
            Feature.NOW_WATCHING -> c.copy(plan = "free", nowWatching = false)
            Feature.TRAKT -> c.copy(plan = "free", trakt = false)
            Feature.MULTI_SERVER -> c.copy(plan = "free", multiServer = false)
            Feature.THEMES -> c.copy(plan = "free", themes = false)
            else -> c.copy(plan = "free")
        }
        launchFetch(force = true)
    }

    // MARK: ophalen

    val platform: String get() = if (AppState.isTV) "androidtv" else "android"

    /** device-auth `entitlements`; false = niet gelukt (cache blijft staan, of alles open zonder cache). */
    suspend fun fetch(): Boolean {
        if (!Account.isSignedIn || !Account.configured) return false
        onAccount(Account.userId)
        val j = Account.entitlementsJson(platform) ?: return false
        return apply(j)
    }

    /** Op de achtergrond ophalen (na inloggen, bij het starten, bij terugkeer naar de voorgrond); hooguit eens per minuut. */
    fun launchFetch(force: Boolean = false) {
        if (!Account.isSignedIn || !Account.configured) return
        if (job?.isActive == true) return
        if (!force && System.currentTimeMillis() - lastFetch < 60_000L) return
        job = AppScope.launch {
            try { fetch() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
        }
    }
}

/** 403 feature_locked van de server (Gratis). */
class FeatureLockedException(val feature: String?) : Exception("feature_locked")
