package nl.lrvdlinden.bingr

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Fout van het Bingr-account; [message] is al vertaald en kan direct getoond worden. */
open class AccountException(message: String) : Exception(message)

/** build 97: het account is geblokkeerd ([Account.bannedMessage]); de tv-koppeling stopt dan met pollen. */
class BannedException(message: String) : AccountException(message)

/**
 * Bingr-account (Supabase, gewone REST zonder SDK), volgens docs/ACCOUNT.md in de iOS-repo.
 * De sessie staat alleen op dit apparaat (eigen SharedPreferences-bestand, niet in de profielgegevens of export).
 */
object Account {
    /** Ingelogd (er is een refresh_token). */
    var isSignedIn by mutableStateOf(false)
        private set
    var email by mutableStateOf<String?>(null)
        private set
    /**
     * Build 82: het account is door de beheerder geblokkeerd (Supabase `user_banned`). Het inlogscherm toont dan
     * [bannedMessage] tot er weer iemand inlogt.
     */
    var blockedNotice by mutableStateOf(false)
    /** Bezig met een aanvraag (knoppen uit, spinner). */
    var busy by mutableStateOf(false)
        private set

    private var sp: SharedPreferences? = null
    /** Apart bestand: blijft staan na uitloggen (device_id). */
    private var deviceSp: SharedPreferences? = null
    // build 97: @Volatile, de sync leest ze ook buiten de main thread (schrijven gebeurt alleen op main)
    @Volatile private var accessToken: String? = null
    @Volatile private var refreshToken: String? = null
    @Volatile private var expiresAt: Long = 0L // epoch-seconden
    @Volatile private var storedUserId: String? = null
    /** Refresh tokens zijn eenmalig: nooit twee vernieuwingen tegelijk. */
    private val refreshLock = Mutex()

    val configured: Boolean get() = Secrets.accountsConfigured

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("bingr.account", Context.MODE_PRIVATE)
        sp = p
        refreshToken = p.getString("refresh_token", null)
        accessToken = p.getString("access_token", null)
        expiresAt = p.getLong("expires_at", 0L)
        email = p.getString("email", null)
        storedUserId = p.getString("user_id", null)
        isSignedIn = !refreshToken.isNullOrEmpty()
        deviceSp = ctx.getSharedPreferences("bingr.device", Context.MODE_PRIVATE)
        deviceRegistered = p.getBoolean("device_registered", false)
        // abonnement (build 88): cache van dit account
        Entitlements.init(ctx)
        if (isSignedIn) Entitlements.onAccount(userId)
        installLifecycleObserver()
    }

    // MARK: sessie opslaan

    private fun store(session: JsonElement, fallbackEmail: String? = null): Boolean {
        val access = session.jS("access_token")?.takeIf { it.isNotEmpty() } ?: return false
        val refresh = session.jS("refresh_token")?.takeIf { it.isNotEmpty() } ?: return false
        val now = System.currentTimeMillis() / 1000
        val exp = session.jL("expires_at") ?: (now + (session.jL("expires_in") ?: 3600L))
        val mail = session.jO("user").jS("email")?.takeIf { it.isNotEmpty() } ?: fallbackEmail ?: email
        val uid = session.jO("user").jS("id")?.takeIf { it.isNotEmpty() } ?: jwtSubject(access) ?: storedUserId
        accessToken = access; refreshToken = refresh; expiresAt = exp; email = mail; storedUserId = uid
        blockedNotice = false
        sp?.edit()?.apply {
            putString("access_token", access); putString("refresh_token", refresh)
            putLong("expires_at", exp); if (mail != null) putString("email", mail) else remove("email")
            if (uid != null) putString("user_id", uid) else remove("user_id")
        }?.apply()
        isSignedIn = true
        Entitlements.onAccount(uid)
        return true
    }

    private fun clear() {
        accessToken = null; refreshToken = null; expiresAt = 0L; email = null; storedUserId = null
        sp?.edit()?.clear()?.apply()
        deviceRegistered = false
        deviceLimit = null
        isSignedIn = false
        Entitlements.signedOut()
        ProfileSync.reset()
        SourceSync.reset()
        StateSync.reset()
    }

    /** user.id uit de sessie (of het `sub` van het access_token). */
    val userId: String? get() = storedUserId ?: accessToken?.let { jwtSubject(it) }

    private fun jwtSubject(token: String): String? = runCatching {
        val part = token.split('.').getOrNull(1) ?: return null
        val bytes = android.util.Base64.decode(part, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
        Http.json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jS("sub")?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    // MARK: aanvragen

    private class Reply(val code: Int, val json: JsonElement?) {
        val ok get() = code in 200..299
        val errorCode: String? get() = json.jS("error_code") ?: json.jS("code")?.takeIf { it.toIntOrNull() == null } ?: json.jS("error")
        /** Build 88: 403 feature_locked (Gratis). */
        val featureLocked: Boolean get() = code == 403 && (errorCode == "feature_locked" || json.jS("error") == "feature_locked")
    }

    /** 403 feature_locked → abonnement bijwerken en [FeatureLockedException] gooien. */
    private fun checkLocked(r: Reply) {
        if (!r.featureLocked) return
        val f = r.json.jS("feature")
        Entitlements.locked(f)
        throw FeatureLockedException(f)
    }

    /** Veld `entitlements` in een antwoord (config, heartbeat) meenemen als het er is. */
    private fun takeEntitlements(r: Reply) {
        if (!r.ok) return
        r.json.jO("entitlements")?.let { Entitlements.apply(it) }
    }

    private suspend fun post(path: String, body: JsonElement, bearer: String? = null): Reply {
        if (!configured) throw AccountException(L("Accounts are not configured in this build."))
        val headers = buildMap {
            put("apikey", Secrets.supabaseAnonKey)
            put("Accept", "application/json")
            if (bearer != null) put("Authorization", "Bearer $bearer")
        }
        // build 97: JSON maken en lezen buiten de main thread (get_state kan groot zijn)
        val text = withContext(Dispatchers.Default) { body.toString() }
        val r = try {
            Http.request(Secrets.supabaseUrl + path, method = "POST", headers = headers, jsonBody = text)
        } catch (e: IOException) {
            throw AccountException(L("No internet connection."))
        } catch (e: IllegalArgumentException) {
            throw AccountException(L("Something went wrong. Please try again."))
        }
        val json = r.body.takeIf { it.isNotBlank() }?.let { b ->
            withContext(Dispatchers.Default) { runCatching { Http.json.parseToJsonElement(b) }.getOrNull() }
        }
        return Reply(r.code, json)
    }

    private fun generic() = AccountException(L("Something went wrong. Please try again."))

    /** Melding bij een geblokkeerd account (build 82, = iOS). */
    val bannedMessage: String
        get() = L("Your account has been blocked. You received an email with the reason and until when. Questions? Go to support.bingr.online.")

    /**
     * Geblokkeerd account? Supabase Auth: `error_code` `user_banned` (400/403; oudere versies "User is banned" in de tekst);
     * eigen functies mogen `error: "user_banned"` of `banned: true` geven (= iOS).
     */
    private fun isBanned(r: Reply): Boolean {
        if (r.json.jB("banned") == true) return true
        if (r.ok) return false
        val codes = listOf("error_code", "error", "code", "reason").mapNotNull { r.json.jS(it)?.lowercase() }
        if (codes.any { it == "user_banned" || it == "banned" || it == "account_blocked" }) return true
        val msg = (r.json.jS("msg") ?: r.json.jS("message") ?: r.json.jS("error_description") ?: "").lowercase()
        return msg.contains("banned")
    }

    /** Geblokkeerd (inloggen of tv-koppeling): melding laten staan en een fout met de tekst geven. */
    private fun banned(): AccountException {
        blockedNotice = true
        return BannedException(bannedMessage)
    }

    /**
     * build 97: geblokkeerd tijdens een gewone aanroep (heartbeat, apparaten, sync): net als bij het vernieuwen van de
     * sessie lokaal uitloggen (zonder logout-aanroep) en de melding op het inlogscherm tonen.
     */
    private suspend fun bannedSignOut() {
        withContext(Dispatchers.Main.immediate) {
            blockedNotice = true
            if (isSignedIn) clear()
            AppState.signedOut()
        }
    }

    private suspend fun <T> working(block: suspend () -> T): T {
        busy = true
        try { return block() } finally { busy = false }
    }

    private val emailRegex = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    fun validate(email: String, password: String?) {
        if (!emailRegex.matches(email.trim())) throw AccountException(L("Enter a valid email address."))
        if (password != null && password.length < 6) throw AccountException(L("Password must be at least 6 characters."))
    }

    private fun creds(email: String, password: String) = buildJsonObject {
        put("email", email.trim()); put("password", password)
    }

    /** Max. lengte van voor- en achternaam (user_metadata first_name / last_name). */
    const val NAME_MAX = 50
    private val nameJunk = Regex("[\\u0000-\\u001F\\u007F<>]")
    private val spaces = Regex("\\s+")

    /**
     * Voor- of achternaam opschonen: stuurtekens en < > weg, witruimte samengevoegd en getrimd, max. [NAME_MAX] tekens
     * (gelijk aan personName() in supabase/functions/_shared/mail.ts van de iOS-repo).
     */
    fun cleanName(s: String): String {
        val v = s.replace(nameJunk, "").replace(spaces, " ").trim()
        if (v.codePointCount(0, v.length) <= NAME_MAX) return v
        return v.substring(0, v.offsetByCodePoints(0, NAME_MAX)).trim()
    }

    /**
     * Account maken. true = direct ingelogd; false = eerst het e-mailadres bevestigen
     * (toon dan "Check your inbox to confirm your email address, then sign in.").
     * Voor- en achternaam zijn verplicht; ze gaan mee als user_metadata first_name / last_name (aanhef "Hoi <voornaam>," in de mails).
     */
    suspend fun signUp(email: String, password: String, firstName: String, lastName: String): Boolean = working {
        val first = cleanName(firstName)
        val last = cleanName(lastName)
        if (first.isEmpty()) throw AccountException(L("Enter your first name."))
        if (last.isEmpty()) throw AccountException(L("Enter your last name."))
        validate(email, password)
        val r = post("/auth/v1/signup", buildJsonObject {
            put("email", email.trim()); put("password", password)
            // taal van de bevestigings- en resetmails; naam voor de aanhef
            put("data", buildJsonObject {
                put("lang", Localizer.language)
                put("first_name", first)
                put("last_name", last)
            })
        })
        if (r.ok) {
            val json = r.json ?: throw generic()
            if (store(json, email.trim())) return@working true
            // bestaand maar onbevestigd adres: Supabase geeft een user zonder identiteiten terug
            val identities = json.jList("identities")
            if (json.jO("user") == null && json.jObj?.containsKey("identities") == true && identities.isEmpty())
                throw AccountException(L("An account with this email already exists."))
            return@working false
        }
        throw when (r.errorCode) {
            "user_already_exists", "email_exists" -> AccountException(L("An account with this email already exists."))
            "weak_password" -> AccountException(L("Password must be at least 6 characters."))
            "email_address_invalid", "validation_failed" -> AccountException(L("Enter a valid email address."))
            else -> generic()
        }
    }

    suspend fun signIn(email: String, password: String) = working {
        validate(email, password)
        val r = post("/auth/v1/token?grant_type=password", creds(email, password))
        if (r.ok) { if (!store(r.json ?: throw generic(), email.trim())) throw generic(); return@working }
        if (isBanned(r)) throw banned()
        throw when {
            r.errorCode == "email_not_confirmed" -> AccountException(L("Confirm your email address first. Check your inbox."))
            r.errorCode == "invalid_credentials" || r.errorCode == "invalid_grant" || r.code == 400 ->
                AccountException(L("Incorrect email or password."))
            else -> generic()
        }
    }

    enum class RefreshResult { OK, INVALID, OFFLINE }

    /**
     * Sessie vernieuwen. 400/401 → uitgelogd (INVALID); netwerk- of serverfout → sessie houden (OFFLINE).
     */
    // build 97: altijd op de main thread (clear() en AppState wijzigen state), ook als de sync van elders aanroept
    suspend fun refresh(): RefreshResult = withContext(Dispatchers.Main.immediate) { refreshLock.withLock { refreshLocked() } }

    private suspend fun refreshLocked(): RefreshResult {
        val token = refreshToken ?: return RefreshResult.INVALID
        if (!configured) return RefreshResult.OFFLINE
        val r = try {
            post("/auth/v1/token?grant_type=refresh_token", buildJsonObject { put("refresh_token", token) })
        } catch (e: AccountException) {
            return RefreshResult.OFFLINE
        }
        if (r.ok && r.json != null && store(r.json)) return RefreshResult.OK
        // build 82: geblokkeerd → lokaal uitloggen (geen logout-aanroep, die mag toch niet meer) en de melding tonen
        if (isBanned(r)) {
            clear()
            withContext(Dispatchers.Main) { blockedNotice = true; AppState.signedOut() }
            return RefreshResult.INVALID
        }
        if (r.code == 400 || r.code == 401) { clear(); return RefreshResult.INVALID }
        return RefreshResult.OFFLINE
    }

    /** Geldig access_token (zo nodig eerst vernieuwen). */
    internal suspend fun validAccessToken(): String {
        val now = System.currentTimeMillis() / 1000
        if (accessToken.isNullOrEmpty() || expiresAt - now < 60) {
            when (refresh()) {
                RefreshResult.INVALID -> throw AccountException(L("Something went wrong. Please try again."))
                RefreshResult.OFFLINE -> if (accessToken.isNullOrEmpty()) throw AccountException(L("No internet connection."))
                RefreshResult.OK -> {}
            }
        }
        return accessToken ?: throw generic()
    }

    /** Wachtwoord vergeten: altijd dezelfde melding (verraadt niet of het account bestaat). */
    suspend fun recover(email: String): String = working {
        validate(email, null)
        post("/auth/v1/recover?redirect_to=https%3A%2F%2Fbingr.online%2Freset", buildJsonObject { put("email", email.trim()) })
        L("If an account exists, you'll receive an email to reset your password.")
    }

    /**
     * Uitloggen: eerst dit apparaat uit de lijst halen (remove_device, mag mislukken),
     * dan de server op de hoogte brengen (mag mislukken), daarna altijd lokaal wissen.
     * Alleen dit apparaat: andere apparaten met hetzelfde account blijven ingelogd.
     */
    suspend fun signOut() = working {
        if (configured && accessToken != null) {
            runCatching { deviceCall("remove_device", deviceId) }
        }
        val token = accessToken
        // build 74: scope=local, alleen de sessie van dit apparaat (zonder scope logt Supabase overal uit)
        if (token != null && configured) runCatching { post("/auth/v1/logout?scope=local", buildJsonObject { }, bearer = token) }
        clear()
    }

    /** Dit apparaat is op een ander apparaat verwijderd: lokaal uitloggen zonder logout-aanroep, terug naar het inlogscherm. */
    fun revokedSignOut() {
        clear()
        AppState.signedOut()
    }

    /**
     * Account verwijderen (device-auth delete_account), daarna lokaal uitloggen.
     * H1: alleen met het huidige wachtwoord; de server controleert het (403 wrong_password, 429 rate_limited).
     */
    suspend fun deleteAccount(password: String) = working {
        if (password.isEmpty()) throw AccountException(L("Incorrect password."))
        val token = validAccessToken()
        val r = post("/functions/v1/device-auth", buildJsonObject { put("action", "delete_account"); put("password", password) }, bearer = token)
        if (r.code == 403 && r.json.jS("error") == "wrong_password") throw AccountException(L("Incorrect password."))
        if (r.code == 429) throw AccountException(L("Too many attempts. Wait a few minutes and try again."))
        if (!r.ok || r.json.jB("ok") != true) throw generic()
        clear()
    }

    /** Tv koppelen met de code op de tv; geeft de apparaatnaam terug. */
    suspend fun approveTv(code: String): String = working {
        val clean = normalizeCode(code)
        if (clean.isEmpty()) throw AccountException(L("That code is invalid or has expired."))
        val token = validAccessToken()
        val r = post("/functions/v1/device-auth", buildJsonObject { put("action", "approve"); put("code", clean) }, bearer = token)
        if (r.code == 404 || r.errorCode == "invalid_code") throw AccountException(L("That code is invalid or has expired."))
        if (!r.ok || r.json.jB("ok") != true) throw generic()
        r.json.jS("device")?.takeIf { it.isNotBlank() } ?: "TV"
    }

    // MARK: tv-login (apparaatcode)

    data class TvCode(val code: String, val pollSecret: String, val url: String, val expiresIn: Int, val interval: Int) {
        /** K7Q-4MX */
        val display: String get() = if (code.length == 6) code.take(3) + "-" + code.drop(3) else code
    }

    sealed class TvPoll {
        data object Pending : TvPoll()
        data object Expired : TvPoll()
        data class Approved(val tokenHash: String, val email: String?) : TvPoll()
    }

    /** Hoofdletters, zonder streepjes en spaties. */
    fun normalizeCode(s: String): String = s.uppercase().filter { it.isLetterOrDigit() }

    /** build 97: één plek voor het soort apparaat ([deviceName] en [devicePlatform] gebruiken dit allebei). */
    private enum class DeviceKind { FIRE_TV, GOOGLE_TV, ANDROID_TV, TABLET, PHONE }

    private val deviceKind: DeviceKind
        get() = when {
            Build.MANUFACTURER.equals("Amazon", ignoreCase = true) -> DeviceKind.FIRE_TV
            AppState.isTV && isGoogleTV -> DeviceKind.GOOGLE_TV
            AppState.isTV -> DeviceKind.ANDROID_TV
            runCatching { AppState.appContext.resources.configuration.smallestScreenWidthDp }.getOrDefault(0) >= 600 -> DeviceKind.TABLET
            else -> DeviceKind.PHONE
        }

    /** Naam van dit apparaat voor het koppelscherm op de telefoon (Google TV heet hier ook "Android TV", zoals altijd). */
    val deviceName: String
        get() = when (deviceKind) {
            DeviceKind.FIRE_TV -> "Fire TV"
            DeviceKind.GOOGLE_TV, DeviceKind.ANDROID_TV -> "Android TV"
            DeviceKind.TABLET, DeviceKind.PHONE -> "Android"
        }

    // MARK: gekoppelde apparaten (limiet van de server)

    /** Vaste UUID van dit apparaat; één keer gemaakt, alleen lokaal, blijft na uitloggen. */
    val deviceId: String
        get() {
            val p = deviceSp ?: AppState.appContext.getSharedPreferences("bingr.device", Context.MODE_PRIVATE).also { deviceSp = it }
            p.getString("device_id", null)?.takeIf { it.isNotEmpty() }?.let { return it }
            val id = java.util.UUID.randomUUID().toString()
            p.edit().putString("device_id", id).apply()
            return id
        }

    /** Naam voor de apparatenlijst: Settings.Global DEVICE_NAME, anders Build.MODEL. */
    val deviceDisplayName: String
        get() = runCatching {
            android.provider.Settings.Global.getString(AppState.appContext.contentResolver, android.provider.Settings.Global.DEVICE_NAME)
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: Build.MODEL ?: "Android"

    /** Android, Android tablet, Android TV, Google TV of Fire TV (zie docs/ACCOUNT.md, build 45). */
    val devicePlatform: String
        get() = when (deviceKind) {
            DeviceKind.FIRE_TV -> "Fire TV"
            DeviceKind.GOOGLE_TV -> "Google TV"
            DeviceKind.ANDROID_TV -> "Android TV"
            DeviceKind.TABLET -> "Android tablet"
            DeviceKind.PHONE -> "Android"
        }

    /** Google TV: GOOGLE_EXPERIENCE, of een Chromecast, of merk "google". */
    private val isGoogleTV: Boolean
        get() = runCatching {
            AppState.appContext.packageManager.hasSystemFeature("com.google.android.feature.GOOGLE_EXPERIENCE")
        }.getOrDefault(false) ||
            (Build.MODEL ?: "").contains("Chromecast", ignoreCase = true) ||
            (Build.BRAND ?: "").equals("google", ignoreCase = true)

    /** register_device is gelukt (lokaal onthouden; gewist bij uitloggen). */
    var deviceRegistered by mutableStateOf(false)
        private set

    private fun setRegistered(v: Boolean) {
        deviceRegistered = v
        sp?.edit()?.putBoolean("device_registered", v)?.apply()
    }

    data class Device(
        val id: String, val name: String, val platform: String,
        val createdAt: Long?, val lastSeenAt: Long?, val current: Boolean,
        val signedInAt: Long? = null,
    )

    /** Antwoord van register_device met 403 device_limit. */
    data class DeviceLimit(val limit: Int, val devices: List<Device>, val plan: String? = null) {
        /** Build 88: Gratis (van de server, anders uit het bewaarde abonnement). */
        val isFree: Boolean get() = plan?.lowercase()?.let { it == "free" } ?: Entitlements.isFree
    }

    /** Alleen als de server geen `limit` meestuurt; normaal komt de limiet altijd van de server. */
    private const val DEFAULT_DEVICE_LIMIT = 15

    class DeviceLimitException(val info: DeviceLimit) : Exception("device_limit")

    /** Niet-null: het scherm "Too many devices" staat open. */
    var deviceLimit by mutableStateOf<DeviceLimit?>(null)

    private fun parseDevices(list: List<JsonElement>): List<Device> {
        val own = deviceId
        return list.mapNotNull { d ->
            val id = d.jS("device_id")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Device(
                id = id,
                name = d.jS("name")?.takeIf { it.isNotBlank() } ?: d.jS("platform") ?: "?",
                platform = d.jS("platform") ?: "",
                createdAt = Net.parseISODate(d.jS("created_at")),
                lastSeenAt = Net.parseISODate(d.jS("last_seen_at")),
                current = d.jB("current") == true || id == own,
                signedInAt = Net.parseISODate(d.jS("signed_in_at")),
            )
        }
    }

    private suspend fun deviceCall(action: String, id: String, extra: Map<String, String> = emptyMap()): Reply {
        val body = buildJsonObject {
            put("action", action); put("device_id", id)
            extra.forEach { (k, v) -> put(k, v) }
        }
        var r = post("/functions/v1/device-auth", body, bearer = validAccessToken())
        // build 97: gewone 401 (token net verlopen of ingetrokken): één keer vernieuwen en opnieuw proberen
        if (r.code == 401 && !isBanned(r) && refresh() == RefreshResult.OK) {
            val token = accessToken
            if (token != null) r = post("/functions/v1/device-auth", body, bearer = token)
        }
        // build 97: geblokkeerd (401 user_banned of banned: true) telde bij de heartbeat als "nog in gebruik"
        if (isBanned(r)) {
            bannedSignOut()
            throw BannedException(bannedMessage)
        }
        return r
    }

    /** App-versie voor de beheerder: "1.0.0 (62)" (versionName + buildnummer). */
    val appVersion: String get() = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    /** Systeemversie voor de beheerder: "Android 14" of "Android TV 12". */
    val osVersion: String get() = (if (AppState.isTV) "Android TV " else "Android ") + (Build.VERSION.RELEASE ?: "")

    /** Extra velden bij register_device en heartbeat (build 62); oudere backends negeren ze. */
    private fun versionFields(): Map<String, String> = mapOf("app_version" to appVersion, "os_version" to osVersion.trim())

    data class Announcement(val id: String, val title: String, val body: String, val createdAt: Long?)

    /** announcements {lang}: berichten van de beheerder; fouten → lege lijst (stil). */
    suspend fun announcements(lang: String): List<Announcement> {
        if (!isSignedIn || !configured) return emptyList()
        val r = try {
            deviceCall("announcements", deviceId, mapOf("lang" to lang))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return emptyList()
        }
        if (!r.ok) return emptyList()
        return r.json.jList("items").mapNotNull { a ->
            val id = a.jS("id")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val title = a.jS("title")?.trim().orEmpty()
            val body = a.jS("body")?.trim().orEmpty()
            if (title.isEmpty() && body.isEmpty()) return@mapNotNull null
            Announcement(id, title, body, Net.parseISODate(a.jS("created_at")))
        }
    }

    /** register_device; 403 device_limit → [DeviceLimitException]. */
    suspend fun registerDevice() {
        val r = deviceCall("register_device", deviceId, mapOf("name" to deviceDisplayName, "platform" to devicePlatform) + versionFields())
        if (r.code == 403 || r.errorCode == "device_limit") {
            if (r.errorCode == "device_limit" || r.json.jObj?.containsKey("devices") == true) {
                throw DeviceLimitException(DeviceLimit(r.json.jI("limit") ?: DEFAULT_DEVICE_LIMIT, parseDevices(r.json.jList("devices")), r.json.jS("plan")))
            }
        }
        if (!r.ok) throw generic()
        setRegistered(true)
    }

    /** heartbeat; false = dit apparaat is verwijderd (revoked). */
    suspend fun heartbeat(): Boolean {
        val r = deviceCall("heartbeat", deviceId, versionFields())
        if (r.json.jB("revoked") == true) return false
        if (!r.ok) throw generic()
        takeEntitlements(r)
        return true
    }

    /** list_devices: limiet en apparaten (nieuwste gebruik eerst). */
    suspend fun listDevices(): DeviceLimit {
        val r = deviceCall("list_devices", deviceId)
        if (!r.ok || r.json == null) throw generic()
        return DeviceLimit(r.json.jI("limit") ?: DEFAULT_DEVICE_LIMIT, parseDevices(r.json.jList("devices")))
    }

    /** remove_device: het apparaat wordt op de server uitgelogd. */
    suspend fun removeDevice(id: String) = working {
        val r = deviceCall("remove_device", id)
        if (!r.ok) throw generic()
    }

    /** rename_device: eigen naam voor een apparaat (max. 60 tekens); register_device overschrijft die daarna niet meer. */
    suspend fun renameDevice(id: String, name: String) = working {
        val clean = name.trim().take(60)
        if (clean.isEmpty()) throw generic()
        val r = deviceCall("rename_device", id, mapOf("name" to clean))
        if (!r.ok) throw generic()
    }

    enum class RegisterResult { OK, LIMIT, FAILED }

    /**
     * Zorgen dat dit apparaat geregistreerd is (na inloggen of voor een sessie van vóór build 39).
     * Limiet → [deviceLimit] wordt gezet (scherm "Too many devices"). Netwerkfouten blokkeren nooit.
     */
    suspend fun ensureRegistered(): RegisterResult {
        if (!isSignedIn || !configured) return RegisterResult.FAILED
        if (deviceRegistered) return RegisterResult.OK
        return try {
            registerDevice(); RegisterResult.OK
        } catch (e: DeviceLimitException) {
            deviceLimit = e.info; RegisterResult.LIMIT
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            RegisterResult.FAILED
        }
    }

    private var checking = false

    /** Bij het starten, bij terugkeer naar de voorgrond en elke 5 minuten: heartbeat (of registreren). */
    fun deviceCheck() {
        if (!isSignedIn || !configured || checking || deviceLimit != null) return
        if (AppState.stage == AppState.Stage.SPLASH || AppState.stage == AppState.Stage.AUTH) return
        checking = true
        AppScope.launch {
            try {
                if (deviceRegistered) {
                    val alive = try { heartbeat() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { true }
                    if (!alive && isSignedIn) revokedSignOut()
                } else {
                    ensureRegistered()
                }
                // vernieuwen mislukt met 400/401 tijdens de aanroep → sessie is al gewist
                if (!isSignedIn) AppState.signedOut()
                // account_data opnieuw lezen (o.a. nieuwe profielfoto's van andere apparaten)
                else { ProfileSync.refreshFromServer(); SourceSync.refreshFromServer(); StateSync.refreshFromServer() }
            } finally {
                checking = false
            }
        }
    }

    private var heartbeatJob: kotlinx.coroutines.Job? = null

    private fun installLifecycleObserver() {
        runCatching {
            androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
                androidx.lifecycle.LifecycleEventObserver { _, e ->
                    when (e) {
                        androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                            deviceCheck()
                            Announcements.check()
                            // abonnement (build 88): na een upgrade op bingr.online gaan de sloten meteen open
                            Entitlements.launchFetch()
                            heartbeatJob?.cancel()
                            heartbeatJob = AppScope.launch {
                                while (true) { delay(5 * 60_000L); deviceCheck() }
                            }
                        }
                        androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> {
                            heartbeatJob?.cancel(); heartbeatJob = null
                            // wachtende wijzigingen (instellingen, voortgang, lijsten) meteen naar het account
                            StateSync.flush()
                        }
                        else -> {}
                    }
                }
            )
        }
    }

    suspend fun startTv(device: String = deviceName): TvCode {
        val r = post("/functions/v1/device-auth", buildJsonObject { put("action", "start"); put("device", device) })
        val j = r.json
        val code = j.jS("code")
        if (!r.ok || code.isNullOrEmpty()) throw generic()
        return TvCode(
            code = code, pollSecret = j.jS("poll_secret") ?: "", url = j.jS("url") ?: "https://bingr.online/koppelen?code=$code",
            expiresIn = j.jI("expires_in") ?: 600, interval = (j.jI("interval") ?: 3).coerceAtLeast(1),
        )
    }

    suspend fun pollTv(c: TvCode): TvPoll {
        val r = post("/functions/v1/device-auth", buildJsonObject {
            put("action", "poll"); put("code", c.code); put("poll_secret", c.pollSecret)
        })
        if (isBanned(r)) throw banned()
        if (!r.ok) throw generic()
        return when (r.json.jS("status")) {
            "approved" -> r.json.jS("token_hash")?.let { TvPoll.Approved(it, r.json.jS("email")) } ?: TvPoll.Expired
            "expired" -> TvPoll.Expired
            else -> TvPoll.Pending
        }
    }

    suspend fun verifyTv(tokenHash: String, email: String?) {
        val r = post("/auth/v1/verify", buildJsonObject { put("type", "magiclink"); put("token_hash", tokenHash) })
        if (isBanned(r)) throw banned()
        if (!r.ok || r.json == null || !store(r.json, email)) throw generic()
    }

    /**
     * Volledige tv-flow: code starten, om de `interval` seconden pollen, bij verlopen automatisch
     * een nieuwe code. [onCode] krijgt elke (nieuwe) code; [onError] een tijdelijke fout (er wordt opnieuw geprobeerd).
     * Keert terug zodra de tv is ingelogd. Annuleren via de coroutine.
     */
    suspend fun runTvFlow(onCode: (TvCode?) -> Unit, onError: (String?) -> Unit) {
        while (true) {
            val c = try { startTv() } catch (e: AccountException) {
                onError(e.message); delay(5_000); continue
            }
            onError(null); onCode(c)
            val deadline = System.currentTimeMillis() + c.expiresIn * 1000L
            var restart = false
            while (!restart) {
                delay(c.interval * 1000L)
                if (System.currentTimeMillis() > deadline) { restart = true; break }
                // build 97: geblokkeerd → stoppen (geen nieuwe codes meer aanvragen of pollen); de melding blijft staan
                val p = try { pollTv(c) } catch (e: BannedException) { onError(e.message); onCode(null); return } catch (e: AccountException) { onError(e.message); continue }
                onError(null)
                when (p) {
                    TvPoll.Pending -> {}
                    TvPoll.Expired -> restart = true
                    is TvPoll.Approved -> {
                        try { verifyTv(p.tokenHash, p.email); return } catch (e: BannedException) {
                            onError(e.message); onCode(null); return
                        } catch (e: AccountException) {
                            onError(e.message); restart = true
                        }
                    }
                }
            }
            onCode(null)
        }
    }

    // MARK: profielfoto's (Storage-bucket `avatars`, build 40)

    /** Pad van de foto van dit profiel: `<user_id>/<profiel_id>.jpg`. */
    fun photoPath(profileId: String): String? = userId?.let { "$it/$profileId.jpg" }

    /**
     * Storage-aanvraag met `apikey` en `Authorization`; bij 401 één keer vernieuwen en opnieuw proberen.
     * Geeft (statuscode, body-bytes) terug, of null bij een netwerkfout of ongeldige sessie.
     */
    private suspend fun storage(method: String, path: String, body: ByteArray? = null, extra: Map<String, String> = emptyMap()): Pair<Int, ByteArray>? {
        if (!configured || !isSignedIn) return null
        var token = runCatching { validAccessToken() }.getOrNull() ?: return null
        repeat(2) { attempt ->
            val result = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val b = okhttp3.Request.Builder()
                        .url(Secrets.supabaseUrl + path)
                        .header("User-Agent", Http.USER_AGENT)
                        .header("apikey", Secrets.supabaseAnonKey)
                        .header("Authorization", "Bearer $token")
                    extra.forEach { (k, v) -> b.header(k, v) }
                    when {
                        body != null -> b.method(method, body.toRequestBody("image/jpeg".toMediaType()))
                        method != "GET" -> b.method(method, ByteArray(0).toRequestBody(null))
                    }
                    Http.client.newCall(b.build()).execute().use { r -> r.code to (r.body?.bytes() ?: ByteArray(0)) }
                }
            }.getOrNull() ?: return null
            if (result.first != 401 || attempt == 1) return result
            if (refresh() != RefreshResult.OK) return null
            token = accessToken ?: return null
        }
        return null
    }

    /** Pad in de bucket zonder spaties of vreemde tekens in de URL. */
    private fun encodePath(path: String): String = path.split('/').joinToString("/") { Http.enc(it) }

    /**
     * Foto van een profiel uploaden of vervangen (vierkant bijgesneden, max. 512×512, JPEG-kwaliteit 80).
     * Geeft het pad in de bucket terug, of null als het mislukt.
     */
    suspend fun uploadPhoto(profileId: String, jpegBytes: ByteArray): String? {
        val path = photoPath(profileId) ?: return null
        val jpeg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { squareJpeg(jpegBytes) } ?: return null
        val r = storage("POST", "/storage/v1/object/avatars/" + encodePath(path), jpeg, mapOf("Content-Type" to "image/jpeg", "x-upsert" to "true"))
            ?: return null
        return if (r.first in 200..299) path else null
    }

    /** Foto downloaden (pad uit `photo.path`); null bij een fout. */
    suspend fun downloadPhoto(path: String): ByteArray? {
        val r = storage("GET", "/storage/v1/object/authenticated/avatars/" + encodePath(path)) ?: return null
        if (r.first !in 200..299 || r.second.isEmpty()) return null
        // alleen echte afbeeldingen bewaren
        val ok = runCatching {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(r.second, 0, r.second.size, o)
            o.outWidth > 0 && o.outHeight > 0
        }.getOrDefault(false)
        return if (ok) r.second else null
    }

    /** Foto verwijderen; fouten worden genegeerd. */
    suspend fun deletePhoto(path: String) {
        runCatching { storage("DELETE", "/storage/v1/object/avatars/" + encodePath(path)) }
    }

    /** Vierkant uit het midden, hooguit 512×512, JPEG-kwaliteit 80 (max. 1 MB). */
    internal fun squareJpeg(bytes: ByteArray): ByteArray? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 512) sample *= 2
        val src = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val side = minOf(src.width, src.height)
        val cropped = android.graphics.Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val target = minOf(side, 512)
        val scaled = if (target == side) cropped else android.graphics.Bitmap.createScaledBitmap(cropped, target, target, true)
        // JPEG heeft geen transparantie: op wit tekenen
        val out = android.graphics.Bitmap.createBitmap(target, target, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(scaled, 0f, 0f, null) }
        var quality = 80
        var data: ByteArray
        do {
            data = java.io.ByteArrayOutputStream().use { s -> out.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, s); s.toByteArray() }
            quality -= 10
        } while (data.size > 1_000_000 && quality >= 30)
        data
    }.getOrNull()

    // MARK: app-sleutels van de server (build 41)

    /**
     * device-auth `{"action":"config"}`: Trakt-, OpenSubtitles-, OMDb- en TMDB-sleutels van de Bingr-server.
     * Alleen lokaal bewaard (privé-instellingen, zie [Secrets.storeServerKeys]); blijven staan na uitloggen.
     * true = opgehaald en opgeslagen.
     */
    suspend fun fetchConfig(): Boolean {
        if (!isSignedIn || !configured) return false
        val token = validAccessToken()
        val r = post("/functions/v1/device-auth", buildJsonObject { put("action", "config") }, bearer = token)
        val obj = r.json.jObj ?: return false
        if (!r.ok) return false
        takeEntitlements(r)
        val values = Secrets.Key.entries.mapNotNull { k ->
            if (!obj.containsKey(k.serverField)) null else k to (obj.jS(k.serverField) ?: "")
        }.toMap()
        if (values.isEmpty()) return false
        Secrets.storeServerKeys(values)
        return true
    }

    /**
     * Build 88: device-auth `entitlements` {platform}. null = netwerkfout, geen sessie of een oude backend
     * zonder deze actie (dan blijft alles open).
     */
    suspend fun entitlementsJson(platform: String): JsonElement? {
        val r = authedDeviceAuth(buildJsonObject { put("action", "entitlements"); put("platform", platform) }) ?: return null
        if (!r.ok || isUnknownAction(r)) return null
        return r.json
    }

    private var configJob: kotlinx.coroutines.Job? = null

    /** Config op de achtergrond ophalen (na elke login en bij het starten met een geldige sessie); fouten zijn stil. */
    fun launchConfigFetch() {
        if (!isSignedIn || !configured) return
        if (configJob?.isActive == true) return
        configJob = AppScope.launch {
            try { fetchConfig() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
        }
    }

    // MARK: bij het starten

    /** Achtergrond-vernieuwing bij het starten; ongeldig → terug naar het inlogscherm. */
    fun refreshOnLaunch() {
        if (!isSignedIn || !configured) return
        AppScope.launch {
            if (refresh() == RefreshResult.INVALID) { AppState.signedOut(); return@launch }
            // app-sleutels (Trakt, OpenSubtitles, OMDb, TMDB) van de server
            launchConfigFetch()
            // abonnement (build 88)
            Entitlements.launchFetch(force = true)
            // apparaat: heartbeat, of registreren als dat nog niet gebeurd is
            deviceCheck()
            // profielen van het account ophalen/samenvoegen (stil, ook offline geen probleem)
            ProfileSync.launchSync()
            // servers en afspeellijsten van het account (build 43)
            SourceSync.rememberOwner()
            SourceSync.launchSync()
            // alle overige gegevens: Trakt, OpenSubtitles, instellingen, per profiel, herinneringen (build 44)
            StateSync.launchSync()
        }
    }

    // MARK: servers en afspeellijsten via het account (build 43)

    /** Antwoord van get_sources: alle bronnen (ook tombstones), de actieve server en wanneer de server het laatst gewijzigd is. */
    data class SourcesData(val sources: List<JsonObject>, val mainSourceID: String?, val updatedAt: String?)

    /** device-auth-aanvraag met een geldig access_token; bij 401 één keer vernieuwen. null = netwerkfout of geen sessie. */
    private suspend fun authedDeviceAuth(body: JsonElement): Reply? {
        if (!isSignedIn || !configured) return null
        var token = runCatching { validAccessToken() }.getOrNull() ?: return null
        repeat(2) { attempt ->
            val r = try { post("/functions/v1/device-auth", body, bearer = token) } catch (e: AccountException) { return null }
            // build 97: geblokkeerd → uitloggen met de melding (zoals bij de heartbeat)
            if (isBanned(r)) { bannedSignOut(); return null }
            if (r.code != 401 || attempt == 1) return r
            when (refresh()) {
                RefreshResult.OK -> token = accessToken ?: return null
                RefreshResult.INVALID -> { withContext(Dispatchers.Main.immediate) { AppState.signedOut() }; return null }
                RefreshResult.OFFLINE -> return null
            }
        }
        return null
    }

    /** get_sources (versleuteld bewaard door de Edge Function). null = mislukt. */
    suspend fun getSources(): SourcesData? {
        val r = authedDeviceAuth(buildJsonObject { put("action", "get_sources") }) ?: return null
        if (!r.ok) return null
        val j = r.json.jObj ?: return null
        if (j["sources"] !is JsonArray && j["sources"] != null && j["sources"] !is JsonNull) return null
        return SourcesData(
            sources = j.jList("sources").mapNotNull { it as? JsonObject }.filter { !it.jS("id").isNullOrBlank() },
            mainSourceID = j.jS("mainSourceID")?.takeIf { it.isNotBlank() },
            updatedAt = j.jS("updatedAt")?.takeIf { it.isNotBlank() },
        )
    }

    // MARK: alle overige gegevens via het account (build 44)

    /** Eén onderdeel van get_state: de (ontsleutelde) gegevens en wanneer de server ze kreeg. */
    data class StateSection(val data: JsonElement?, val updatedAt: String?)

    /**
     * get_state: de gevraagde onderdelen (leeg/null = alles). Ontbrekende onderdelen staan niet in het antwoord.
     * null = mislukt (netwerk, geen sessie).
     */
    suspend fun getState(sections: List<String>? = null): Map<String, StateSection>? {
        val body = buildJsonObject {
            put("action", "get_state")
            if (!sections.isNullOrEmpty()) put("sections", JsonArray(sections.map { JsonPrimitive(it) }))
        }
        val r = authedDeviceAuth(body) ?: return null
        if (!r.ok) return null
        val j = r.json.jObj ?: return null
        val obj = j["sections"] as? JsonObject ?: return if (j["sections"] == null || j["sections"] is JsonNull) emptyMap() else null
        return obj.mapNotNull { (name, v) ->
            val o = v as? JsonObject ?: return@mapNotNull null
            name to StateSection(o["data"]?.takeIf { it !is JsonNull }, o.jS("updatedAt")?.takeIf { it.isNotBlank() })
        }.toMap()
    }

    /** put_state: één onderdeel opslaan (max. 1 MB). Geeft `updatedAt` van de server terug, of null als het mislukt. */
    suspend fun putState(section: String, data: JsonElement): String? {
        val body = JsonObject(mapOf(
            "action" to JsonPrimitive("put_state"),
            "section" to JsonPrimitive(section),
            "data" to data,
        ))
        val r = authedDeviceAuth(body) ?: return null
        if (!r.ok || r.json.jB("ok") == false) return null
        return r.json.jS("updatedAt")?.takeIf { it.isNotBlank() } ?: ""
    }

    /** delete_state: onderdeel verwijderen (bijv. `profile.<id>` na het verwijderen van een profiel). */
    suspend fun deleteState(section: String): Boolean {
        val r = authedDeviceAuth(buildJsonObject { put("action", "delete_state"); put("section", section) }) ?: return false
        return r.ok && r.json.jB("ok") != false
    }

    /** put_sources: altijd de hele lijst (met tombstones). true = opgeslagen. */
    suspend fun putSources(sources: JsonArray, mainSourceID: String?): Boolean {
        val body = JsonObject(mapOf(
            "action" to JsonPrimitive("put_sources"),
            "sources" to sources,
            "mainSourceID" to (mainSourceID?.let { JsonPrimitive(it) } ?: JsonNull),
        ))
        val r = authedDeviceAuth(body) ?: return false
        return r.ok && r.json.jB("ok") != false
    }

    // MARK: tegelijk kijken, wie kijkt er nu, kijkgeschiedenis (build 51)

    /** Eén lopende stream uit list_streams of het 403-antwoord van stream_start. */
    data class StreamInfo(
        val deviceId: String, val deviceName: String, val platform: String, val profileName: String,
        /** profile_id (build 57); oudere regels hebben hem niet: dan "" */
        val profileId: String = "",
        val title: String, val kind: String, val startedAt: Long?, val lastPing: Long?, val signedInAt: Long?,
    ) {
        val current: Boolean get() = deviceId == Account.deviceId
    }

    data class StreamList(val limit: Int, val streams: List<StreamInfo>, val plan: String? = null) {
        /** Build 88: Gratis (van de server, anders uit het bewaarde abonnement). */
        val isFree: Boolean get() = plan?.lowercase()?.let { it == "free" } ?: Entitlements.isFree
    }

    /** Eén regel uit watch_log. */
    data class WatchEntry(
        val deviceName: String, val platform: String, val profileName: String, val title: String, val kind: String,
        val startedAt: Long, val endedAt: Long?, val minutes: Int,
        /** profile_id (build 57); oudere regels hebben hem niet: dan "" */
        val profileId: String = "",
    )

    sealed class StreamStart {
        data object Ok : StreamStart()
        data class Limit(val list: StreamList) : StreamStart()
        /** Netwerkfout of geen sessie: gewoon afspelen. */
        data object Failed : StreamStart()
    }

    private const val DEFAULT_STREAM_LIMIT = 6

    private fun parseStreams(list: List<JsonElement>): List<StreamInfo> = list.mapNotNull { s ->
        val id = s.jS("device_id")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val platform = s.jS("platform") ?: ""
        StreamInfo(
            deviceId = id,
            deviceName = s.jS("device_name")?.takeIf { it.isNotBlank() } ?: platform.ifEmpty { "?" },
            platform = platform,
            profileName = s.jS("profile_name") ?: "",
            profileId = s.jS("profile_id") ?: "",
            title = s.jS("title") ?: "",
            kind = s.jS("kind") ?: "",
            startedAt = Net.parseISODate(s.jS("started_at")),
            lastPing = Net.parseISODate(s.jS("last_ping")),
            signedInAt = Net.parseISODate(s.jS("signed_in_at")),
        )
    }

    /**
     * stream_start; 403 stream_limit = [StreamStart.Limit].
     * Build 68 (statistieken): bij een aflevering ook `series_id` en `series_title`, en altijd `tz` (tijdzone van het apparaat).
     */
    suspend fun streamStart(
        profileId: String, profileName: String, title: String, kind: String, contentId: String,
        seriesId: String? = null, seriesTitle: String? = null,
    ): StreamStart {
        val r = authedDeviceAuth(buildJsonObject {
            put("action", "stream_start"); put("device_id", deviceId)
            put("profile_id", profileId); put("profile_name", profileName)
            put("title", title); put("kind", kind); put("content_id", contentId)
            if (!seriesId.isNullOrBlank()) put("series_id", seriesId)
            if (!seriesTitle.isNullOrBlank()) put("series_title", seriesTitle.take(200))
            put("tz", timeZoneId())
        }) ?: return StreamStart.Failed
        if (r.code == 403 && (r.errorCode == "stream_limit" || r.json.jObj?.containsKey("streams") == true)) {
            return StreamStart.Limit(StreamList(r.json.jI("limit") ?: DEFAULT_STREAM_LIMIT, parseStreams(r.json.jList("streams")), r.json.jS("plan")))
        }
        return if (r.ok) StreamStart.Ok else StreamStart.Failed
    }

    /** stream_ping: true = telt nog, false = gestopt (opnieuw stream_start), null = netwerkfout. */
    suspend fun streamPing(title: String?): Boolean? {
        val r = authedDeviceAuth(buildJsonObject {
            put("action", "stream_ping"); put("device_id", deviceId)
            if (!title.isNullOrBlank()) put("title", title)
        }) ?: return null
        if (r.json.jB("stopped") == true) return false
        return if (r.ok) true else null
    }

    suspend fun streamStop() {
        authedDeviceAuth(buildJsonObject { put("action", "stream_stop"); put("device_id", deviceId) })
    }

    /** list_streams: limiet en wie er nu kijkt. */
    suspend fun listStreams(): StreamList {
        val r = authedDeviceAuth(buildJsonObject { put("action", "list_streams") }) ?: throw AccountException(L("No internet connection."))
        checkLocked(r)
        if (!r.ok || r.json == null) throw generic()
        return StreamList(r.json.jI("limit") ?: DEFAULT_STREAM_LIMIT, parseStreams(r.json.jList("streams")))
    }

    /** watch_log over de laatste 7 dagen, nieuwste eerst. */
    suspend fun watchLog(days: Int = 7): List<WatchEntry> {
        val r = authedDeviceAuth(buildJsonObject { put("action", "watch_log"); put("days", days) })
            ?: throw AccountException(L("No internet connection."))
        checkLocked(r)
        if (!r.ok || r.json == null) throw generic()
        return r.json.jList("entries").mapNotNull { e ->
            val start = Net.parseISODate(e.jS("started_at")) ?: return@mapNotNull null
            val platform = e.jS("platform") ?: ""
            WatchEntry(
                deviceName = e.jS("device_name")?.takeIf { it.isNotBlank() } ?: platform.ifEmpty { "?" },
                platform = platform,
                profileName = e.jS("profile_name") ?: "",
                title = e.jS("title") ?: "",
                kind = e.jS("kind") ?: "",
                startedAt = start,
                endedAt = Net.parseISODate(e.jS("ended_at")),
                minutes = e.jI("minutes") ?: 0,
                profileId = e.jS("profile_id") ?: "",
            )
        }.sortedByDescending { it.startedAt }
    }

    // MARK: kijkstatistieken (build 68), zie docs/ACCOUNT.md in de iOS-repo

    /** IANA-tijdzone van het apparaat ("Europe/Amsterdam"). */
    fun timeZoneId(): String = java.util.TimeZone.getDefault().id ?: "Europe/Amsterdam"

    enum class StatsPeriod(val key: String) { WEEK("week"), MONTH("month"), YEAR("year") }

    /** Eén staaf: dag ("yyyy-MM-dd") bij week/maand, maand ("yyyy-MM") bij jaar. */
    data class StatsBucket(val key: String, val minutes: Double, val movieMinutes: Double, val episodeMinutes: Double, val liveMinutes: Double)

    /** Eén titel in een toplijst; [itemKey] = series_id, content_id van de film of id van de zender (of "t:<titel>"). */
    data class StatsTop(val title: String, val itemKey: String, val minutes: Double, val episodes: Int)

    data class StatsProfile(val profileId: String, val profileName: String, val minutes: Double)

    /** Antwoord van watch_stats; ontbrekende velden worden 0 of leeg. */
    data class WatchStats(
        val period: String = "",
        val from: String? = null,
        val to: String? = null,
        val profileId: String? = null,
        val totalMinutes: Double = 0.0,
        val movieCount: Int = 0, val movieMinutes: Double = 0.0,
        val seriesShows: Int = 0, val seriesEpisodes: Int = 0, val seriesMinutes: Double = 0.0,
        val liveChannels: Int = 0, val liveMinutes: Double = 0.0,
        val buckets: List<StatsBucket> = emptyList(),
        val topSeries: List<StatsTop> = emptyList(),
        val topMovies: List<StatsTop> = emptyList(),
        val topChannels: List<StatsTop> = emptyList(),
        val profiles: List<StatsProfile> = emptyList(),
        val since: String? = null,
    ) {
        val isEmpty: Boolean get() = totalMinutes <= 0.0 && movieMinutes <= 0.0 && seriesMinutes <= 0.0 && liveMinutes <= 0.0 &&
            buckets.none { it.minutes > 0.0 } && topSeries.isEmpty() && topMovies.isEmpty() && topChannels.isEmpty()
    }

    /** Oude backend zonder watch_stats: vriendelijke melding in plaats van een fout. */
    class StatsUnavailable : Exception()

    /** Getal uit JSON: nummer of tekst, nooit negatief; anders 0. */
    private fun num(v: JsonElement?): Double {
        val p = v as? JsonPrimitive ?: return 0.0
        if (p is JsonNull) return 0.0
        val d = p.content.trim().toDoubleOrNull() ?: return 0.0
        return if (d.isNaN() || d < 0) 0.0 else d
    }

    private fun str(v: JsonElement?): String {
        val p = v as? JsonPrimitive ?: return ""
        if (p is JsonNull) return ""
        return p.content
    }

    private fun isUnknownAction(r: Reply): Boolean =
        r.code == 404 || r.json.jS("error") == "unknown_action" || r.errorCode == "unknown_action"

    fun parseStats(j: JsonElement?): WatchStats {
        val o = j.jObj ?: return WatchStats()
        val totals = o["totals"] as? JsonObject
        val movies = totals?.get("movies") as? JsonObject
        val series = totals?.get("series") as? JsonObject
        val live = totals?.get("live") as? JsonObject
        val mvMin = num(movies?.get("minutes")); val epMin = num(series?.get("minutes")); val lvMin = num(live?.get("minutes"))
        fun top(v: JsonElement?): List<StatsTop> = ((v as? JsonArray) ?: emptyList()).mapNotNull { e ->
            val x = e as? JsonObject ?: return@mapNotNull null
            val t = str(x["title"]); val k = str(x["item_key"])
            if (t.isBlank() && k.isBlank()) null else StatsTop(t, k, num(x["minutes"]), num(x["episodes"]).toInt())
        }
        val topObj = o["top"] as? JsonObject
        return WatchStats(
            period = str(o["period"]),
            from = str(o["from"]).takeIf { it.isNotBlank() },
            to = str(o["to"]).takeIf { it.isNotBlank() },
            profileId = str(o["profile_id"]).takeIf { it.isNotBlank() },
            totalMinutes = if (totals?.get("minutes") != null) num(totals["minutes"]) else mvMin + epMin + lvMin,
            movieCount = num(movies?.get("count")).toInt(), movieMinutes = mvMin,
            seriesShows = num(series?.get("shows")).toInt(), seriesEpisodes = num(series?.get("episodes")).toInt(), seriesMinutes = epMin,
            liveChannels = num(live?.get("channels")).toInt(), liveMinutes = lvMin,
            buckets = ((o["by_bucket"] as? JsonArray) ?: emptyList()).mapNotNull { e ->
                val b = e as? JsonObject ?: return@mapNotNull null
                val key = str(b["key"]).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val mv = num(b["movie_minutes"]); val ep = num(b["episode_minutes"]); val lv = num(b["live_minutes"])
                StatsBucket(key, if (b["minutes"] != null) num(b["minutes"]) else mv + ep + lv, mv, ep, lv)
            },
            topSeries = top(topObj?.get("series")),
            topMovies = top(topObj?.get("movies")),
            topChannels = top(topObj?.get("channels")),
            profiles = ((o["profiles"] as? JsonArray) ?: emptyList()).mapNotNull { e ->
                val x = e as? JsonObject ?: return@mapNotNull null
                StatsProfile(str(x["profile_id"]), str(x["profile_name"]), num(x["minutes"]))
            },
            since = str(o["since"]).takeIf { it.isNotBlank() },
        )
    }

    /**
     * watch_stats: kijkstatistieken van een week, maand of jaar ([offset] 0 = nu, -1 = vorige …).
     * [profileId] null = alle profielen (alleen het hoofdprofiel). Oude backend → [StatsUnavailable].
     */
    suspend fun watchStats(period: StatsPeriod, offset: Int, profileId: String?): WatchStats {
        val r = authedDeviceAuth(buildJsonObject {
            put("action", "watch_stats"); put("period", period.key); put("offset", offset)
            if (!profileId.isNullOrBlank()) put("profile_id", profileId)
            put("tz", timeZoneId())
        }) ?: throw AccountException(L("No internet connection."))
        checkLocked(r)
        if (isUnknownAction(r)) throw StatsUnavailable()
        if (!r.ok || r.json == null) throw generic()
        return parseStats(r.json)
    }

    /** delete_watch_stats: statistieken wissen ([profileId] null = alle profielen). */
    suspend fun deleteWatchStats(profileId: String?) {
        val r = authedDeviceAuth(buildJsonObject {
            put("action", "delete_watch_stats")
            if (!profileId.isNullOrBlank()) put("profile_id", profileId)
        }) ?: throw AccountException(L("No internet connection."))
        checkLocked(r)
        if (isUnknownAction(r)) throw StatsUnavailable()
        if (!r.ok || r.json.jB("ok") == false) throw generic()
    }
}
