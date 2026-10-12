package nl.lrvdlinden.bingr

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Profielen van het Bingr-account (tabel `account_data`, PostgREST), volgens "Profielen migreren en delen"
 * in docs/ACCOUNT.md van de iOS-repo:
 * - server leeg, lokaal profielen → migreren (uploaden)
 * - lokaal geen profielen (alleen het automatische hoofdprofiel of niets), server wel → importeren (zelfde id, zonder pincode)
 * - allebei → samenvoegen op id (lokaal blijft, alleen-op-server wordt toegevoegd) en uploaden
 * Daarna gaat elke wijziging (hele lijst) na ~2 s naar de server. Pincodes gaan nooit mee.
 * Eigen profielfoto's (build 40, "Profielfoto's delen"): JPEG in de Storage-bucket `avatars`, met per profiel
 * het veld `photo` ({path, updated} of null). Lokaal per profiel de *photoVersion* (= `updated` van de foto op dit apparaat).
 * Fouten zijn stil: synchroniseren blokkeert de app nooit.
 */
object ProfileSync {
    /** Bezig met de eerste synchronisatie na inloggen. */
    var syncing by mutableStateOf(false)
        private set

    /** account_data is gelezen en verwerkt in deze sessie. */
    private var synced = false
    /** Profielobjecten zoals de server ze kent (kleine letters id → object), voor extra velden. */
    private var serverObjects = mapOf<String, JsonObject>()
    private var lastUploaded: String? = null
    private var syncJob: Job? = null
    private var uploadJob: Job? = null
    /** Tijdens het overnemen van serverprofielen geen upload starten. */
    private var applying = false
    private var refreshJob: Job? = null

    // MARK: foto's: lokale stand

    /** Lokale wijzigingen van `photo` die nog niet in een geüploade lijst zitten (kleine letters id → object of JsonNull). */
    private val photoOverrides = mutableMapOf<String, JsonElement>()
    /** Lopende foto-upload/-verwijdering per profiel (kleine letters id). */
    private val photoJobs = mutableMapOf<String, Job>()

    private val photoPrefs get() = AppState.appContext.getSharedPreferences("bingr.photosync", android.content.Context.MODE_PRIVATE)
    private fun photoVersion(key: String): String? = runCatching { photoPrefs.getString(key, null) }.getOrNull()
    private fun setPhotoVersion(key: String, v: String?) {
        runCatching { photoPrefs.edit().apply { if (v == null) remove(key) else putString(key, v) }.apply() }
    }

    private val active: Boolean get() = Account.isSignedIn && Account.configured

    /** Uitgelogd / account weg: alles vergeten. */
    fun reset() {
        syncJob?.cancel(); syncJob = null
        uploadJob?.cancel(); uploadJob = null
        synced = false
        serverObjects = emptyMap()
        lastUploaded = null
        syncing = false
        refreshJob?.cancel(); refreshJob = null
        photoJobs.values.forEach { it.cancel() }; photoJobs.clear()
        photoOverrides.clear()
        // photoVersions horen bij dit account
        runCatching { photoPrefs.edit().clear().apply() }
    }

    /** Aangeroepen door [Store] bij elke wijziging in de profielen. */
    fun profilesChanged() {
        if (applying || !active) return
        if (!synced) { launchSync(); return }
        scheduleUpload()
    }

    private fun scheduleUpload() {
        if (!active || !synced) return
        uploadJob?.cancel()
        uploadJob = AppScope.launch {
            delay(2_000)
            quietly { upload() }
        }
    }

    /** Synchronisatie starten (of de lopende teruggeven). */
    fun launchSync(): Job? {
        if (!active) return null
        syncJob?.takeIf { it.isActive }?.let { return it }
        val job = AppScope.launch {
            syncing = true
            try { quietly { sync() } } finally { syncing = false }
        }
        syncJob = job
        return job
    }

    /** Synchroniseren en wachten tot het klaar is (na inloggen, vóór "Wie kijkt er?"). */
    suspend fun syncNow() { launchSync()?.join() }

    private suspend fun quietly(block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }

    // MARK: netwerk

    private fun headers(token: String) = mapOf(
        "apikey" to Secrets.supabaseAnonKey,
        "Authorization" to "Bearer $token",
        "Accept" to "application/json",
    )

    /** Aanvraag met een geldig access_token; bij 401 één keer vernieuwen en opnieuw proberen. */
    private suspend fun call(path: String, method: String, body: String? = null, extra: Map<String, String> = emptyMap()): Http.Response? {
        var token = Account.validAccessToken()
        repeat(2) { attempt ->
            val r = Http.request(Secrets.supabaseUrl + path, method = method, headers = headers(token) + extra, jsonBody = body)
            if (r.code != 401 || attempt == 1) return r
            when (Account.refresh()) {
                Account.RefreshResult.OK -> token = Account.validAccessToken()
                Account.RefreshResult.INVALID -> { AppState.signedOut(); return null }
                Account.RefreshResult.OFFLINE -> return null
            }
        }
        return null
    }

    // MARK: synchroniseren

    private fun idKey(id: String) = id.lowercase()

    private suspend fun sync() {
        val r = call("/rest/v1/account_data?select=profiles,updated_at", "GET") ?: return
        if (!r.ok) return
        val rows = Http.json.parseToJsonElement(r.body.ifBlank { "[]" }) as? JsonArray ?: return
        val server = rows.firstOrNull().jList("profiles")
            .mapNotNull { it as? JsonObject }
            .filter { !it.jS("id").isNullOrBlank() }
            .sortedBy { it.jI("order") ?: Int.MAX_VALUE }
        if (!active) return
        serverObjects = server.associateBy { idKey(it.jS("id")!!) }

        val local = Store.profiles
        val serverIDs = serverObjects.keys
        // "lokaal geen profielen": niets, of alleen het automatische hoofdprofiel (dat nog niet op de server staat)
        val localNone = local.isEmpty() || (local.size == 1 && idKey(local[0].id) !in serverIDs)

        var uploadNeeded = false
        when {
            server.isEmpty() && local.isEmpty() -> {}
            server.isEmpty() -> uploadNeeded = true                         // migreren
            localNone -> {                                                  // importeren
                val list = server.map(::toProfile)
                val main = server.firstOrNull { it.jB("isMain") == true }?.jS("id") ?: list.first().id
                withoutUpload { Store.applyAccountProfiles(list, main, adoptFrom = local.firstOrNull()?.id) }
                lastUploaded = payload()
            }
            else -> {                                                       // samenvoegen
                val localIDs = local.map { idKey(it.id) }.toSet()
                val extra = server.filter { idKey(it.jS("id")!!) !in localIDs }.map(::toProfile)
                if (extra.isNotEmpty()) withoutUpload { Store.applyAccountProfiles(local + extra, Store.mainProfileID) }
                uploadNeeded = true
            }
        }
        synced = true
        if (applyPhotos()) uploadNeeded = true
        if (uploadNeeded) upload()
    }

    /**
     * Bij het starten en bij elke heartbeat: account_data opnieuw lezen en de fotoregels toepassen
     * (zo verschijnt een nieuwe foto ook op apparaten die al open staan). Het profielenlijstje zelf blijft zoals het is.
     */
    fun refreshFromServer() {
        if (!active) return
        if (!synced) { launchSync(); return }
        if (syncJob?.isActive == true || refreshJob?.isActive == true) return
        refreshJob = AppScope.launch {
            quietly {
                val r = call("/rest/v1/account_data?select=profiles,updated_at", "GET") ?: return@quietly
                if (!r.ok) return@quietly
                val rows = Http.json.parseToJsonElement(r.body.ifBlank { "[]" }) as? JsonArray ?: return@quietly
                val server = rows.firstOrNull().jList("profiles").mapNotNull { it as? JsonObject }.filter { !it.jS("id").isNullOrBlank() }
                if (!active || server.isEmpty()) return@quietly
                serverObjects = server.associateBy { idKey(it.jS("id")!!) }
                if (applyPhotos()) scheduleUpload()
            }
        }
    }

    // MARK: foto's synchroniseren

    private fun isoMillis(s: String?): Long? = Net.parseISODate(s)

    /** Is [a] nieuwer dan [b] (ISO-8601)? */
    private fun newer(a: String, b: String?): Boolean {
        if (b == null) return true
        val ma = isoMillis(a); val mb = isoMillis(b)
        return if (ma != null && mb != null) ma > mb else a > b
    }

    private fun photoObject(path: String, updated: String) = buildJsonObject { put("path", path); put("updated", updated) }

    /**
     * Regels uit ACCOUNT.md toepassen op alle lokale profielen (tegen [serverObjects]).
     * true = er is een `photo` gewijzigd en de lijst moet opnieuw geüpload worden.
     */
    private suspend fun applyPhotos(): Boolean {
        var changed = false
        for (p in Store.profiles.toList()) {
            if (!active) return false
            val key = idKey(p.id)
            if (photoJobs[key]?.isActive == true || key in photoOverrides) continue // lokale wijziging gaat voor
            val photo = serverObjects[key]?.get("photo")
            val path = (photo as? JsonObject)?.jS("path")?.takeIf { it.isNotBlank() }
            val updated = (photo as? JsonObject)?.jS("updated")?.takeIf { it.isNotBlank() }
            val version = photoVersion(key)
            val hasLocal = Store.photoFile(p.id).exists()
            try {
                when {
                    path != null && updated != null -> {
                        if (!hasLocal || newer(updated, version)) {
                            val bytes = Account.downloadPhoto(path) ?: continue
                            if (!active || photoJobs[key]?.isActive == true || key in photoOverrides) continue
                            Store.setPhoto(p.id, bytes, fromSync = true)
                            setPhotoVersion(key, updated)
                        } else if (version == null) setPhotoVersion(key, updated)
                    }
                    photo is JsonNull && version != null -> {
                        // foto kwam uit de sync en is elders verwijderd
                        if (hasLocal) Store.setPhoto(p.id, null, fromSync = true)
                        setPhotoVersion(key, null)
                    }
                    version == null && hasLocal -> {
                        // migratie: lokale foto, op de server (nog) niet
                        val bytes = runCatching { Store.photoFile(p.id).readBytes() }.getOrNull() ?: continue
                        val up = Account.uploadPhoto(p.id, bytes) ?: continue
                        val now = isoNow()
                        setPhotoVersion(key, now)
                        photoOverrides[key] = photoObject(up, now)
                        changed = true
                    }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        return changed
    }

    /**
     * Aangeroepen door [Store.setPhoto] als de gebruiker op dit apparaat een foto kiest, wijzigt of verwijdert
     * (ook bij het verwijderen van een profiel). Uploaden/verwijderen gebeurt op de achtergrond, fouten zijn stil.
     */
    fun photoChanged(id: String, hasPhoto: Boolean) {
        if (!active) return
        val key = idKey(id)
        val isProfile = Store.profiles.any { idKey(it.id) == key }
        // foto's van servers (afspeellijsten) horen niet bij het account
        if (!isProfile && serverObjects[key] == null && photoVersion(key) == null) return
        photoJobs[key]?.cancel()
        val job = AppScope.launch {
            try {
                quietly {
                    if (!synced) launchSync()?.join()
                    if (!synced || !active) return@quietly
                    val stillProfile = Store.profiles.any { idKey(it.id) == key }
                    val file = Store.photoFile(id)
                    if (hasPhoto && stillProfile && file.exists()) {
                        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@quietly
                        val path = Account.uploadPhoto(id, bytes) ?: return@quietly
                        val now = isoNow()
                        setPhotoVersion(key, now)
                        photoOverrides[key] = photoObject(path, now)
                    } else if (!file.exists()) {
                        val serverPath = (serverObjects[key]?.get("photo") as? JsonObject)?.jS("path")?.takeIf { it.isNotBlank() }
                        val path = serverPath ?: Account.photoPath(id)
                        if (path != null) Account.deletePhoto(path)
                        setPhotoVersion(key, null)
                        photoOverrides[key] = JsonNull
                    } else return@quietly
                    scheduleUpload()
                }
            } finally {
                if (photoJobs[key] === coroutineContext[Job]) photoJobs.remove(key)
            }
        }
        if (job.isActive) photoJobs[key] = job
    }

    private inline fun withoutUpload(block: () -> Unit) {
        applying = true
        try { block() } finally { applying = false }
    }

    /** Serverprofiel → lokaal profiel (zelfde id, zonder pincode of foto). */
    private fun toProfile(o: JsonObject): ViewerProfile {
        val kids = o.jB("kids") ?: false
        return ViewerProfile(
            id = o.jS("id")!!,
            name = o.jS("name")?.takeIf { it.isNotBlank() } ?: L("Profile"),
            avatar = avatar(o["avatar"], kids),
            ageLimit = o.jI("ageLimit"),
            isKids = if (kids) true else null,
        )
    }

    private fun avatar(e: JsonElement?, kids: Boolean): PlaylistAvatar {
        val fallback = if (kids) PlaylistAvatar.randomIllustration() else PlaylistAvatar.random()
        val palettes = PlaylistAvatar.palettes.size
        return when (e) {
            is JsonObject -> {
                val sym = (e.jS("symbol") ?: e.jS("figure"))?.takeIf { it.isNotBlank() }
                // gedeeld formaat = iOS-symboolnaam; "emoji" is de exacte Android-keuze (alleen als die nog bij het symbool past)
                val emoji = e.jS("emoji")?.takeIf { it.isNotBlank() && sym != null && AvatarMap.toShared(it) == sym }
                val symbol = when {
                    sym == null -> e.jS("emoji")?.takeIf { it.isNotBlank() } ?: fallback.symbol
                    sym.startsWith("kid") -> sym
                    emoji != null -> emoji
                    else -> AvatarMap.toEmoji(sym) ?: (if (sym in PlaylistAvatar.symbols) sym else fallback.symbol)
                }
                val color = (e.jI("colorIndex") ?: e.jI("color") ?: fallback.colorIndex).let { ((it % palettes) + palettes) % palettes }
                PlaylistAvatar(symbol, color)
            }
            is JsonPrimitive -> if (e is JsonNull || e.content.isBlank()) fallback else fallback.copy(symbol = AvatarMap.toEmoji(e.content) ?: e.content)
            else -> fallback
        }
    }

    // MARK: uploaden

    /** De hele profiellijst zoals hij naar de server gaat (extra serververvelden blijven bewaard). */
    private fun profilesJson(): JsonArray {
        val mainID = Store.mainProfileID
        return JsonArray(Store.profiles.mapIndexed { i, p ->
            val base = serverObjects[idKey(p.id)] ?: JsonObject(emptyMap())
            JsonObject(base + mapOf(
                "id" to JsonPrimitive(p.id),
                "name" to JsonPrimitive(p.name),
                // alleen emoji/figuur + kleur; een eigen foto gaat nooit mee
                "avatar" to buildJsonObject {
                    put("symbol", AvatarMap.toShared(p.avatar.symbol)); put("colorIndex", p.avatar.colorIndex)
                    if (!p.avatar.isIllustration) put("emoji", p.avatar.symbol)
                },
                "kids" to JsonPrimitive(p.kids),
                "ageLimit" to (p.ageLimit?.let { JsonPrimitive(it) } ?: JsonNull),
                "isMain" to JsonPrimitive(p.id == mainID),
                "order" to JsonPrimitive(i),
                "photo" to photoField(p.id, base),
            ))
        })
    }

    /** `photo` voor de upload: lokale wijziging, anders wat de server had, anders null. */
    private fun photoField(id: String, base: JsonObject): JsonElement {
        val key = idKey(id)
        photoOverrides[key]?.let { return it }
        base["photo"]?.let { if (it is JsonObject || it is JsonNull) return it }
        val version = photoVersion(key)
        val path = Account.photoPath(id)
        if (version != null && path != null && Store.photoFile(id).exists()) return photoObject(path, version)
        return JsonNull
    }

    private fun payload(): String = profilesJson().toString()

    private suspend fun upload() {
        if (!active || !synced) return
        val profiles = profilesJson()
        if (profiles.isEmpty()) return
        val key = profiles.toString()
        if (key == lastUploaded) return
        val userID = Account.userId ?: return
        val body = JsonObject(mapOf(
            "user_id" to JsonPrimitive(userID),
            "profiles" to profiles,
            "updated_at" to JsonPrimitive(isoNow()),
        )).toString()
        val r = call("/rest/v1/account_data", "POST", body, mapOf("Prefer" to "resolution=merge-duplicates,return=minimal")) ?: return
        if (r.ok) {
            lastUploaded = key
            // lokale foto-wijzigingen die in deze lijst zaten zijn nu op de server
            val sent = profiles.mapNotNull { it as? JsonObject }.associate { idKey(it.jS("id") ?: "") to it["photo"] }
            photoOverrides.entries.removeAll { (k, v) -> k !in sent || sent[k] == v }
            serverObjects = profiles.mapNotNull { it as? JsonObject }.associateBy { idKey(it.jS("id") ?: "") }
        }
    }

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
}

/**
 * Profielicoon tussen platforms: de server bewaart de iOS-symboolnaam (SF Symbol) of kidNN;
 * Android toont emoji. Beide kanten gebruiken dezelfde kleurindex.
 */
internal object AvatarMap {
    private val sfToEmoji = linkedMapOf(
        "tv" to "📺", "globe" to "🌐", "sportscourt" to "⚽", "crown" to "👑", "flame" to "🔥", "film" to "🎬",
        "popcorn" to "🍿", "star" to "⭐", "gamecontroller" to "🎮", "moon.stars" to "🌙", "bolt" to "⚡", "heart" to "❤️",
        "waveform.path.ecg" to "🎧", "hare" to "🐰", "forward" to "🚀", "sparkle" to "🌈", "link" to "🔗",
        "bubble.left" to "💬", "wifi" to "📶", "umbrella" to "☂️", "play" to "▶️", "book.closed" to "📕",
        "person" to "🧸", "paintbrush" to "🎭",
    )
    private val emojiToSf: Map<String, String> = sfToEmoji.entries.associate { (k, v) -> v to k } + mapOf(
        "🎵" to "waveform.path.ecg", "🏆" to "crown", "🦁" to "hare", "🦄" to "sparkle", "🐼" to "hare", "☕" to "heart",
    )
    fun toEmoji(sf: String): String? = sfToEmoji[sf]
    fun toShared(symbol: String): String = when {
        symbol.startsWith("kid") -> symbol
        symbol in sfToEmoji -> symbol
        else -> emojiToSf[symbol] ?: "tv"
    }
}
