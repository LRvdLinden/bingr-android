package nl.lrvdlinden.bingr

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Servers en afspeellijsten van het Bingr-account (build 43), volgens "Servers en afspeellijsten via het account"
 * in docs/ACCOUNT.md van de iOS-repo. Opslag via device-auth `get_sources` / `put_sources` (versleuteld door de
 * Edge Function). Zelfde patroon als [ProfileSync]:
 * - server leeg, lokaal servers → uploaden (migratie, inclusief wachtwoorden en tokens);
 * - lokaal leeg, server servers → lokaal aanmaken met hetzelfde id, geheimen terugzetten, het "android"-blok
 *   toepassen, mainSourceID instellen en de bibliotheek laden;
 * - allebei → per id wint de nieuwste `updatedAt`; tombstones verwijderen lokaal (met geheimen en cache);
 *   daarna uploaden als lokaal iets nieuwer is of ontbreekt.
 * Lokale wijzigingen (server toevoegen, wijzigen, ook wachtwoord, verwijderen, aanpassingen en vergrendelde
 * groepen) zetten `updatedAt` = nu en gaan na ~2 s naar de server. Het "apple"-blok blijft onaangeroerd.
 * Fouten zijn stil: de app werkt verder met wat lokaal staat.
 *
 * Gedeeld formaat per bron: id, name, kind (raw value), avatar {symbol, colorIndex, emoji}, m3uURL, epgURL,
 * serverURL, username, macAddress, preferHLS, secrets (alle geheimen onder "playlist.<id>.<naam>"),
 * android {customization, lockedGroups}, apple (niet aanraken), updatedAt, deleted/deletedAt.
 */
object SourceSync {
    /** Bezig met de eerste synchronisatie (na inloggen of bij het starten). */
    var syncing by mutableStateOf(false)
        private set

    private var synced = false
    /** Bronnen zoals de server ze kent (kleine letters id → object), voor het apple-blok en extra velden. */
    private var serverObjects = mapOf<String, JsonObject>()
    private var lastUploaded: String? = null
    private var syncJob: Job? = null
    private var uploadJob: Job? = null
    private var refreshJob: Job? = null
    /** Tijdens het overnemen van de server geen updatedAt zetten en niet uploaden. */
    private var applying = false

    private val active: Boolean get() = Account.isSignedIn && Account.configured

    private const val EPOCH = "1970-01-01T00:00:00.000Z"
    private const val TOMBSTONE_DAYS = 90L

    // MARK: lokale stand

    /** Privé: "upd.<key>" = updatedAt van de versie op dit apparaat; "tomb.<key>" = "<id>|<deletedAt>". */
    private val syncPrefs get() = AppState.appContext.getSharedPreferences("bingr.sourcesync", Context.MODE_PRIVATE)
    /** Blijft staan na uitloggen: welk account het laatst ingelogd was op dit apparaat. */
    private val ownerPrefs get() = AppState.appContext.getSharedPreferences("bingr.accountowner", Context.MODE_PRIVATE)

    private fun idKey(id: String) = id.lowercase()

    private fun localUpdatedAt(key: String): String? = runCatching { syncPrefs.getString("upd.$key", null) }.getOrNull()
    private fun setLocalUpdatedAt(key: String, v: String?) {
        runCatching { syncPrefs.edit().apply { if (v == null) remove("upd.$key") else putString("upd.$key", v) }.apply() }
    }

    private fun localTombstones(): Map<String, Pair<String, String>> = runCatching {
        syncPrefs.all.mapNotNull { (k, v) ->
            if (!k.startsWith("tomb.") || v !is String) return@mapNotNull null
            val parts = v.split('|', limit = 2)
            if (parts.size != 2) null else k.removePrefix("tomb.") to (parts[0] to parts[1])
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun tooOld(deletedAt: String?): Boolean {
        val ms = Net.parseISODate(deletedAt) ?: return false
        return System.currentTimeMillis() - ms > TOMBSTONE_DAYS * 24 * 3_600_000L
    }

    private fun pruneTombstones() {
        val old = localTombstones().filter { tooOld(it.value.second) }.keys
        if (old.isEmpty()) return
        runCatching { syncPrefs.edit().apply { old.forEach { remove("tomb.$it") } }.apply() }
    }

    private fun isTombstone(o: JsonObject) = o.jB("deleted") == true

    /** updatedAt van de lokale versie: wat de sync onthield, anders [PlaylistSource.updatedAt]. */
    private fun localVersion(p: PlaylistSource): String = localUpdatedAt(idKey(p.id)) ?: iso(p.updatedAt)

    private fun serverVersion(o: JsonObject): String = o.jS("updatedAt")?.takeIf { it.isNotBlank() } ?: EPOCH

    // MARK: hooks vanuit Store

    /** Server toegevoegd of gewijzigd (ook wachtwoord, aanpassingen of vergrendelde groepen): updatedAt = nu. */
    fun sourceEdited(id: String) {
        if (applying) return
        val key = idKey(id)
        runCatching { syncPrefs.edit().putString("upd.$key", isoNow()).remove("tomb.$key").apply() }
    }

    /** Server verwijderd: tombstone, zodat andere apparaten hem ook verwijderen. */
    fun sourceDeleted(id: String) {
        if (applying) return
        val key = idKey(id)
        runCatching { syncPrefs.edit().putString("tomb.$key", "$id|${isoNow()}").remove("upd.$key").apply() }
    }

    /** Een geheim onder "playlist.<id>." is gewijzigd (wachtwoord, token, sessie). */
    fun secretChanged(key: String) {
        if (applying) return
        val id = key.removePrefix("playlist.").substringBefore('.')
        if (id.isEmpty() || Store.playlists.none { it.id == id }) return
        sourceEdited(id)
        sourcesChanged()
    }

    /** Elke lokale wijziging aan de servers (of de hoofdserver): na ~2 s de hele lijst uploaden. */
    fun sourcesChanged() {
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

    /** Uitgelogd: sync-stand vergeten. De lokale servers blijven staan. */
    fun reset() {
        syncJob?.cancel(); syncJob = null
        uploadJob?.cancel(); uploadJob = null
        refreshJob?.cancel(); refreshJob = null
        synced = false
        serverObjects = emptyMap()
        lastUploaded = null
        syncing = false
        runCatching { syncPrefs.edit().clear().apply() }
    }

    // MARK: accountwissel

    private fun owner(): String? = runCatching { ownerPrefs.getString("user_id", null) }.getOrNull()
    private fun setOwner(uid: String) { runCatching { ownerPrefs.edit().putString("user_id", uid).apply() } }

    /** Bij het starten met een geldige sessie: het ingelogde account onthouden (sessies van vóór build 43). */
    fun rememberOwner() {
        val uid = Account.userId ?: return
        if (owner() == null) setOwner(uid)
    }

    /**
     * Na inloggen, vóór de sync: logt een **ander** account in dan het vorige, wis dan eerst de lokale servers,
     * profielen en gegevens van het vorige account. Daarna wordt het nieuwe account geïmporteerd.
     */
    fun checkAccountSwitch() {
        val uid = Account.userId ?: return
        val last = owner()
        if (last != null && !last.equals(uid, ignoreCase = true)) wipePreviousAccount()
        setOwner(uid)
    }

    private fun wipePreviousAccount() {
        syncJob?.cancel(); uploadJob?.cancel(); refreshJob?.cancel()
        Store.playlists.forEach { Playlists.clearCache(it) }
        Playlists.unload()
        Collections.wipe()
        CatalogMeta.wipe()   // build 128
        AllServers.wipe()
        NewOnBingr.wipe(); FirstSeen.wipe()   // build 141: ook "eerst gezien" en de Nieuw op Bingr-cache van het vorige account
        StateSync.applyingChanges {
            Reminders.cancelAll()
            Store.wipeAccountData()
            // Trakt en OpenSubtitles horen sinds build 44 bij het account: alleen lokaal wissen (geen revoke,
            // andere apparaten van het vorige account blijven gekoppeld)
            Trakt.clearLocal()
            OpenSubtitles.clearLocal()
        }
        ProfileSync.reset()
        reset()
        // instellingen, pincodes en de sync-stand van het vorige account (build 44)
        StateSync.wipeLocal()
        AppState.lastPlaylistID = null
    }

    // MARK: synchroniseren

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

    private suspend fun sync() {
        val data = Account.getSources() ?: return
        if (!active) return
        serverObjects = data.sources.associateBy { idKey(it.jS("id")!!) }
        val needUpload = merge(data)
        synced = true
        if (needUpload) upload()
    }

    /** Bij het starten, bij terugkeer naar de voorgrond en bij elke heartbeat: opnieuw lezen en samenvoegen. */
    fun refreshFromServer() {
        if (!active) return
        if (!synced) { launchSync(); return }
        if (syncJob?.isActive == true || refreshJob?.isActive == true) return
        refreshJob = AppScope.launch {
            quietly {
                val data = Account.getSources() ?: return@quietly
                if (!active) return@quietly
                serverObjects = data.sources.associateBy { idKey(it.jS("id")!!) }
                // lokale wijziging die nog op upload wacht: die gaat eerst
                if (uploadJob?.isActive == true) return@quietly
                if (merge(data)) upload()
            }
        }
    }

    /**
     * Server toepassen (per id, zie de uitleg bovenaan). true = er moet geüpload worden (lokaal nieuwer,
     * ontbreekt op de server, of lokale tombstones die de server nog niet kent).
     */
    private fun merge(data: Account.SourcesData): Boolean {
        pruneTombstones()
        val all = data.sources
        val byKey = all.associateBy { idKey(it.jS("id")!!) }
        val tombs = localTombstones()
        val local = Store.playlists
        val importing = local.isEmpty()
        var needUpload = false

        val removed = mutableListOf<PlaylistSource>()
        val merged = mutableListOf<PlaylistSource>()
        /** Overgenomen serverversies (lokaal id → object): geheimen en android-blok toepassen. */
        val incoming = linkedMapOf<String, JsonObject>()
        for (p in local) {
            val key = idKey(p.id)
            val o = byKey[key]
            when {
                o == null -> { needUpload = true; merged += p }
                isTombstone(o) -> removed += p
                else -> {
                    val su = serverVersion(o)
                    val lu = localVersion(p)
                    if (newerStrict(su, lu)) {
                        merged += toSource(o, p) ?: p
                        incoming[p.id] = o
                        setLocalUpdatedAt(key, su)
                    } else if (newerStrict(lu, su)) {
                        needUpload = true
                        merged += p
                    } else {
                        // zelfde versie: velden die een oudere app-versie nog niet kende (catchupOffsets, build 51) overnemen
                        val offsets = if (o.containsKey("catchupOffsets")) catchupOffsetsFrom(o["catchupOffsets"]) else p.catchupOffsets
                        merged += if (offsets != p.catchupOffsets) p.copy(catchupOffsets = offsets) else p
                    }
                }
            }
        }
        val present = local.map { idKey(it.id) }.toSet()
        for (o in all) {
            val key = idKey(o.jS("id")!!)
            if (isTombstone(o) || key in present) continue
            if (key in tombs) { needUpload = true; continue }   // hier verwijderd, de server weet het nog niet
            val s = toSource(o, null) ?: continue
            merged += s
            incoming[s.id] = o
            setLocalUpdatedAt(key, serverVersion(o))
        }
        if (tombs.keys.any { k -> byKey[k]?.let { !isTombstone(it) } ?: true }) needUpload = true

        // hoofdserver van het account: bij importeren, of als de lokale hoofdserver niet meer bestaat
        val localMain = Store.mainSourceID
        val takeMain = importing || localMain == null || merged.none { it.id == localMain }
        val mainID = if (takeMain) data.mainSourceID else null
        if (!takeMain && data.mainSourceID == null && merged.isNotEmpty()) needUpload = true

        val before = Playlists.source
        val oldPasswords = incoming.keys.associateWith { Store.secret("playlist.$it.password") }
        if (merged != local || removed.isNotEmpty() || (mainID != null && !mainID.equals(localMain, ignoreCase = true))) {
            withoutUpload { Store.applySyncedSources(merged, removed.map { it.id }, mainID) }
        }
        withoutUpload { incoming.forEach { (id, o) -> applyExtras(id, o) } }
        removed.forEach { Playlists.clearCache(it) }

        // open bibliotheek bijwerken
        if (before != null && removed.any { it.id == before.id }) {
            Playlists.unload()
            if (AppState.stage == AppState.Stage.MAIN) AppState.switchPlaylist()
        } else if (before != null && before.id in incoming) {
            val now = Store.playlists.firstOrNull { it.id == before.id }
            val passwordChanged = oldPasswords[before.id] != Store.secret("playlist.${before.id}.password")
            if (now != null && (connectionChanged(before, now) || passwordChanged)) {
                // build 144: in "Wie kijkt er?" of het pincodescherm niets ophalen: de bewaarde lijst hoort niet meer bij de
                // server, dus weg; het kiezen van een profiel laadt hem dan opnieuw (AppState.open → Playlists.select)
                if (AppState.stage == AppState.Stage.MAIN) AppScope.launch { quietly { Playlists.select(now, forceRefresh = true) } }
                else { Playlists.unload(); Playlists.clearCache(now) }
            }
        }
        // geïmporteerd (herinstallatie, nieuw apparaat): de bibliotheek van de hoofdserver laden
        // build 144: alleen in het hoofdscherm; in "Wie kijkt er?" of het pincodescherm niets laden of ophalen (Apple build 143),
        // dat doet het kiezen van een profiel (AppState.open → Playlists.select)
        if (Playlists.source == null && incoming.isNotEmpty()) {
            val main = Store.mainSource
            if (main != null && AppState.stage == AppState.Stage.MAIN) AppState.open(main)
        }
        return needUpload
    }

    private fun connectionChanged(a: PlaylistSource, b: PlaylistSource) =
        a.kind != b.kind || a.m3uURL != b.m3uURL || a.epgURL != b.epgURL || a.serverURL != b.serverURL ||
            a.username != b.username || a.macAddress != b.macAddress || a.preferHLS != b.preferHLS

    /** Geheimen terugzetten en het eigen platformblok ("android") toepassen. */
    private fun applyExtras(id: String, o: JsonObject) {
        o.jO("secrets")?.forEach { (name, v) ->
            val value = (v as? JsonPrimitive)?.jFlex ?: return@forEach
            if (name.isBlank()) return@forEach
            Store.setSecret("playlist.$id.$name", value)
        }
        val a = o.jO("android") ?: return
        val custom = a["customization"]?.let { e ->
            runCatching { Http.json.decodeFromJsonElement(Customization.serializer(), e) }.getOrNull()
        }
        val locked = (a["lockedGroups"] as? JsonArray)?.mapNotNull { it.jFlex }?.toSet()
        Store.applySyncedSourceSettings(id, custom, locked)
    }

    private fun toSource(o: JsonObject, existing: PlaylistSource?): PlaylistSource? {
        val kind = PlaylistKind.entries.firstOrNull { it.raw == o.jS("kind") } ?: existing?.kind ?: return null
        val su = serverVersion(o)
        fun text(k: String, cur: String?) = o.jS(k) ?: cur ?: ""
        val currentAvatar = existing?.avatar ?: PlaylistAvatar.random()
        return PlaylistSource(
            id = existing?.id ?: o.jS("id")!!,
            name = o.jS("name")?.takeIf { it.isNotBlank() } ?: existing?.name ?: kind.title,
            kind = kind,
            avatar = if (o["avatar"] != null) ProfileSync.avatar(o["avatar"], false, currentAvatar) else currentAvatar,
            m3uURL = text("m3uURL", existing?.m3uURL),
            epgURL = text("epgURL", existing?.epgURL),
            serverURL = text("serverURL", existing?.serverURL),
            username = text("username", existing?.username),
            macAddress = text("macAddress", existing?.macAddress),
            preferHLS = o.jB("preferHLS") ?: existing?.preferHLS ?: false,
            ageLimit = existing?.ageLimit,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            updatedAt = preciseMillis(su).takeIf { it > 0 } ?: System.currentTimeMillis(),
            catchupOffsets = if (o.containsKey("catchupOffsets")) catchupOffsetsFrom(o["catchupOffsets"]) else existing?.catchupOffsets ?: emptyMap(),
        )
    }

    /** `catchupOffsets: {"<channelID>": minuten}` (build 51), getallen tussen −120 en 120. */
    private fun catchupOffsetsFrom(e: kotlinx.serialization.json.JsonElement?): Map<String, Int> {
        val o = e as? JsonObject ?: return emptyMap()
        return o.mapNotNull { (k, v) ->
            val m = v.jFlex?.trim()?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() } ?: return@mapNotNull null
            if (k.isBlank() || m == 0) null else k to m.coerceIn(-120, 120)
        }.toMap()
    }

    private inline fun withoutUpload(block: () -> Unit) {
        applying = true
        try { block() } finally { applying = false }
    }

    // MARK: uploaden

    /** Alle geheimen van deze bron (naam zonder "playlist.<id>."), gesorteerd voor een stabiele vergelijking. */
    private fun secretsOf(id: String): JsonObject {
        val prefix = "playlist.$id."
        return JsonObject(Store.secretsWithPrefix(prefix)
            .mapKeys { it.key.removePrefix(prefix) }
            .filterKeys { it.isNotEmpty() }
            .toSortedMap()
            .mapValues { JsonPrimitive(it.value) })
    }

    /** Eigen platformblok: aanpassingen (hernoemd, verborgen, volgorde, samengevoegd) en vergrendelde groepen. */
    private fun androidBlock(id: String, old: JsonObject?): JsonObject {
        val custom = Http.json.encodeToJsonElement(Customization.serializer(), Store.customization(id))
        val locked = JsonArray(Store.lockedGroups(id).sorted().map { JsonPrimitive(it) })
        return JsonObject((old ?: JsonObject(emptyMap())) + mapOf("customization" to custom, "lockedGroups" to locked))
    }

    private fun sourcesJson(): JsonArray {
        val activeList = Store.playlists.map { p ->
            val key = idKey(p.id)
            val base = (serverObjects[key] ?: JsonObject(emptyMap())).let { b ->
                if (isTombstone(b)) JsonObject(b - setOf("deleted", "deletedAt")) else b
            }
            JsonObject(base + mapOf(
                "id" to JsonPrimitive(p.id),
                "name" to JsonPrimitive(p.name),
                "kind" to JsonPrimitive(p.kind.raw),
                "avatar" to buildJsonObject {
                    put("symbol", AvatarMap.toShared(p.avatar.symbol)); put("colorIndex", p.avatar.colorIndex)
                    if (!p.avatar.isIllustration) put("emoji", p.avatar.symbol)
                },
                "m3uURL" to JsonPrimitive(p.m3uURL),
                "epgURL" to JsonPrimitive(p.epgURL),
                "serverURL" to JsonPrimitive(p.serverURL),
                "username" to JsonPrimitive(p.username),
                "macAddress" to JsonPrimitive(p.macAddress),
                "preferHLS" to JsonPrimitive(p.preferHLS),
                "secrets" to secretsOf(p.id),
                "catchupOffsets" to JsonObject(p.catchupOffsets.toSortedMap().mapValues { JsonPrimitive(it.value) }),
                "android" to androidBlock(p.id, base["android"] as? JsonObject),
                "updatedAt" to JsonPrimitive(localVersion(p)),
            ))
        }
        val activeKeys = Store.playlists.map { idKey(it.id) }.toSet()
        val tombs = linkedMapOf<String, JsonObject>()
        for ((key, o) in serverObjects) {
            if (key in activeKeys || !isTombstone(o) || tooOld(o.jS("deletedAt"))) continue
            val deletedAt = o.jS("deletedAt") ?: EPOCH
            tombs[key] = tombstone(o.jS("id")!!, deletedAt, o.jS("updatedAt") ?: deletedAt)
        }
        for ((key, v) in localTombstones()) {
            if (key in activeKeys || key in tombs || tooOld(v.second)) continue
            tombs[key] = tombstone(v.first, v.second, v.second)
        }
        return JsonArray(activeList + tombs.values)
    }

    private fun tombstone(id: String, deletedAt: String, updatedAt: String) = buildJsonObject {
        put("id", id); put("deleted", true); put("deletedAt", deletedAt); put("updatedAt", updatedAt)
    }

    private suspend fun upload() {
        if (!active || !synced) return
        val list = sourcesJson()
        if (list.isEmpty()) return
        val main = Store.mainSourceID?.takeIf { id -> Store.playlists.any { it.id == id } }
        val key = "$list|$main"
        if (key == lastUploaded) return
        if (Account.putSources(list, main)) {
            lastUploaded = key
            serverObjects = list.mapNotNull { it as? JsonObject }.associateBy { idKey(it.jS("id") ?: "") }
        }
    }

    // MARK: tijden

    private fun isoFormat() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private fun isoNow(): String = isoFormat().format(Date())
    private fun iso(ms: Long): String = isoFormat().format(Date(ms))

    /** Is [a] strikt nieuwer dan [b] (ISO-8601, op de milliseconde)? */
    private fun newerStrict(a: String, b: String): Boolean = preciseMillis(a) > preciseMillis(b)

    /** ISO-8601 → milliseconden, inclusief de fractie van de seconde. */
    private fun preciseMillis(s: String): Long {
        val base = Net.parseISODate(s) ?: return 0L
        val dot = s.indexOf('.')
        if (dot < 0) return base
        val digits = s.substring(dot + 1).takeWhile { it.isDigit() }
        val ms = (digits + "000").take(3).toIntOrNull() ?: 0
        return base + ms
    }
}
