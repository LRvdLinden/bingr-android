package nl.lrvdlinden.bingr

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Fout van het Bingr-account; [message] is al vertaald en kan direct getoond worden. */
class AccountException(message: String) : Exception(message)

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
    /** Bezig met een aanvraag (knoppen uit, spinner). */
    var busy by mutableStateOf(false)
        private set

    private var sp: SharedPreferences? = null
    /** Apart bestand: blijft staan na uitloggen (device_id). */
    private var deviceSp: SharedPreferences? = null
    private var accessToken: String? = null
    private var refreshToken: String? = null
    private var expiresAt: Long = 0L // epoch-seconden
    private var storedUserId: String? = null
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
        sp?.edit()?.apply {
            putString("access_token", access); putString("refresh_token", refresh)
            putLong("expires_at", exp); if (mail != null) putString("email", mail) else remove("email")
            if (uid != null) putString("user_id", uid) else remove("user_id")
        }?.apply()
        isSignedIn = true
        return true
    }

    private fun clear() {
        accessToken = null; refreshToken = null; expiresAt = 0L; email = null; storedUserId = null
        sp?.edit()?.clear()?.apply()
        deviceRegistered = false
        deviceLimit = null
        isSignedIn = false
        ProfileSync.reset()
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
    }

    private suspend fun post(path: String, body: JsonElement, bearer: String? = null): Reply {
        if (!configured) throw AccountException(L("Accounts are not configured in this build."))
        val headers = buildMap {
            put("apikey", Secrets.supabaseAnonKey)
            put("Accept", "application/json")
            if (bearer != null) put("Authorization", "Bearer $bearer")
        }
        val r = try {
            Http.request(Secrets.supabaseUrl + path, method = "POST", headers = headers, jsonBody = body.toString())
        } catch (e: IOException) {
            throw AccountException(L("No internet connection."))
        } catch (e: IllegalArgumentException) {
            throw AccountException(L("Something went wrong. Please try again."))
        }
        val json = r.body.takeIf { it.isNotBlank() }?.let { runCatching { Http.json.parseToJsonElement(it) }.getOrNull() }
        return Reply(r.code, json)
    }

    private fun generic() = AccountException(L("Something went wrong. Please try again."))

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

    /**
     * Account maken. true = direct ingelogd; false = eerst het e-mailadres bevestigen
     * (toon dan "Check your inbox to confirm your email address, then sign in.").
     */
    suspend fun signUp(email: String, password: String): Boolean = working {
        validate(email, password)
        val r = post("/auth/v1/signup", buildJsonObject {
            put("email", email.trim()); put("password", password)
            // taal van de bevestigings- en resetmails
            put("data", buildJsonObject { put("lang", Localizer.language) })
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
    suspend fun refresh(): RefreshResult = refreshLock.withLock { refreshLocked() }

    private suspend fun refreshLocked(): RefreshResult {
        val token = refreshToken ?: return RefreshResult.INVALID
        if (!configured) return RefreshResult.OFFLINE
        val r = try {
            post("/auth/v1/token?grant_type=refresh_token", buildJsonObject { put("refresh_token", token) })
        } catch (e: AccountException) {
            return RefreshResult.OFFLINE
        }
        if (r.ok && r.json != null && store(r.json)) return RefreshResult.OK
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
     */
    suspend fun signOut() = working {
        if (configured && accessToken != null) {
            runCatching { deviceCall("remove_device", deviceId) }
        }
        val token = accessToken
        if (token != null && configured) runCatching { post("/auth/v1/logout", buildJsonObject { }, bearer = token) }
        clear()
    }

    /** Dit apparaat is op een ander apparaat verwijderd: lokaal uitloggen zonder logout-aanroep, terug naar het inlogscherm. */
    fun revokedSignOut() {
        clear()
        AppState.signedOut()
    }

    /** Account verwijderen (device-auth delete_account), daarna lokaal uitloggen. */
    suspend fun deleteAccount() = working {
        val token = validAccessToken()
        val r = post("/functions/v1/device-auth", buildJsonObject { put("action", "delete_account") }, bearer = token)
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

    /** Naam van dit apparaat voor het koppelscherm op de telefoon. */
    val deviceName: String
        get() = when {
            Build.MANUFACTURER.equals("Amazon", ignoreCase = true) -> "Fire TV"
            AppState.isTV -> "Android TV"
            else -> "Android"
        }

    // MARK: gekoppelde apparaten (max. 10)

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

    /** Android, Android tablet, Android TV of Fire TV. */
    val devicePlatform: String
        get() = when {
            Build.MANUFACTURER.equals("Amazon", ignoreCase = true) -> "Fire TV"
            AppState.isTV -> "Android TV"
            runCatching { AppState.appContext.resources.configuration.smallestScreenWidthDp }.getOrDefault(0) >= 600 -> "Android tablet"
            else -> "Android"
        }

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
    )

    /** Antwoord van register_device met 403 device_limit. */
    data class DeviceLimit(val limit: Int, val devices: List<Device>)

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
            )
        }
    }

    private suspend fun deviceCall(action: String, id: String, extra: Map<String, String> = emptyMap()): Reply {
        val token = validAccessToken()
        return post("/functions/v1/device-auth", buildJsonObject {
            put("action", action); put("device_id", id)
            extra.forEach { (k, v) -> put(k, v) }
        }, bearer = token)
    }

    /** register_device; 403 device_limit → [DeviceLimitException]. */
    suspend fun registerDevice() {
        val r = deviceCall("register_device", deviceId, mapOf("name" to deviceDisplayName, "platform" to devicePlatform))
        if (r.code == 403 || r.errorCode == "device_limit") {
            if (r.errorCode == "device_limit" || r.json.jObj?.containsKey("devices") == true) {
                throw DeviceLimitException(DeviceLimit(r.json.jI("limit") ?: 10, parseDevices(r.json.jList("devices"))))
            }
        }
        if (!r.ok) throw generic()
        setRegistered(true)
    }

    /** heartbeat; false = dit apparaat is verwijderd (revoked). */
    suspend fun heartbeat(): Boolean {
        val r = deviceCall("heartbeat", deviceId)
        if (r.json.jB("revoked") == true) return false
        if (!r.ok) throw generic()
        return true
    }

    /** list_devices: limiet en apparaten (nieuwste gebruik eerst). */
    suspend fun listDevices(): DeviceLimit {
        val r = deviceCall("list_devices", deviceId)
        if (!r.ok || r.json == null) throw generic()
        return DeviceLimit(r.json.jI("limit") ?: 10, parseDevices(r.json.jList("devices")))
    }

    /** remove_device: het apparaat wordt op de server uitgelogd. */
    suspend fun removeDevice(id: String) = working {
        val r = deviceCall("remove_device", id)
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
                else ProfileSync.refreshFromServer()
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
                            heartbeatJob?.cancel()
                            heartbeatJob = AppScope.launch {
                                while (true) { delay(5 * 60_000L); deviceCheck() }
                            }
                        }
                        androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> { heartbeatJob?.cancel(); heartbeatJob = null }
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
            code = code, pollSecret = j.jS("poll_secret") ?: "", url = j.jS("url") ?: "https://bingr.online/tv?code=$code",
            expiresIn = j.jI("expires_in") ?: 600, interval = (j.jI("interval") ?: 3).coerceAtLeast(1),
        )
    }

    suspend fun pollTv(c: TvCode): TvPoll {
        val r = post("/functions/v1/device-auth", buildJsonObject {
            put("action", "poll"); put("code", c.code); put("poll_secret", c.pollSecret)
        })
        if (!r.ok) throw generic()
        return when (r.json.jS("status")) {
            "approved" -> r.json.jS("token_hash")?.let { TvPoll.Approved(it, r.json.jS("email")) } ?: TvPoll.Expired
            "expired" -> TvPoll.Expired
            else -> TvPoll.Pending
        }
    }

    suspend fun verifyTv(tokenHash: String, email: String?) {
        val r = post("/auth/v1/verify", buildJsonObject { put("type", "magiclink"); put("token_hash", tokenHash) })
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
                val p = try { pollTv(c) } catch (e: AccountException) { onError(e.message); continue }
                onError(null)
                when (p) {
                    TvPoll.Pending -> {}
                    TvPoll.Expired -> restart = true
                    is TvPoll.Approved -> {
                        try { verifyTv(p.tokenHash, p.email); return } catch (e: AccountException) {
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
        val values = Secrets.Key.entries.mapNotNull { k ->
            if (!obj.containsKey(k.serverField)) null else k to (obj.jS(k.serverField) ?: "")
        }.toMap()
        if (values.isEmpty()) return false
        Secrets.storeServerKeys(values)
        return true
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
            // apparaat: heartbeat, of registreren als dat nog niet gebeurd is
            deviceCheck()
            // profielen van het account ophalen/samenvoegen (stil, ook offline geen probleem)
            ProfileSync.launchSync()
        }
    }
}
