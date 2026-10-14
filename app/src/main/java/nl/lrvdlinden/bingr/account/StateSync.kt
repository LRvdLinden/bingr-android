package nl.lrvdlinden.bingr

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Alle overige gegevens via het Bingr-account (build 44), volgens "Alle overige gegevens via het account" in
 * docs/ACCOUNT.md van de iOS-repo. Versleuteld per onderdeel via device-auth `get_state` / `put_state` /
 * `delete_state`. Zelfde patroon als [ProfileSync] en [SourceSync].
 *
 * Onderdelen:
 * - `connections`: Trakt-tokens en de OpenSubtitles-sessie (geheel, nieuwste `updatedAt` wint).
 * - `settings`: `shared` (zelfde naam en betekenis als op iOS, zie [sharedSettings]), `android` (ruwe waarden van
 *   [settingKeys]) en `apple` (onaangeroerd). Geheel, nieuwste `updatedAt` wint.
 * - `profile.<id>`: Mijn lijst, favorieten, bekeken, recente zenders (per item `{id, at}` plus `removed`), voortgang
 *   (per sleutel nieuwste), kijkgeschiedenis (max. 500), kijkvoorkeuren en pincode (geheel, nieuwste wint).
 * - `reminders`: herinneringen (geheel, nieuwste wint).
 *
 * Lokaal: per onderdeel de `updatedAt` van de versie op dit apparaat ("upd.<onderdeel>", epoch-ms) en per profiel
 * een metabestand (`files/statesync/<id>.json`) met per item wanneer het lokaal toegevoegd of verwijderd is.
 * Lokale wijzigingen gaan na ~3 s naar de server, voortgang tijdens het afspelen hooguit eens per 30 s en bij
 * stoppen. Fouten zijn altijd stil: lokaal blijft leidend tot er weer verbinding is.
 */
object StateSync {
    /** Instellingen (Prefs-sleutels) die met het account meegaan. */
    val settingKeys = setOf(
        "app.language", "player.engine", "subtitle.size", "player.audioLanguage", "player.subtitleLanguage",
        "autoSubtitles", TrackPreferences.autoSubtitlesKey, "os.language", "os.languages", "ui.startTab", "ui.showLive", "ui.showMovies", "ui.showSeries",
        "playlist.refresh", "category.sort", "trakt.scrobble", "trakt.askRating",
        // build 51
        "player.zapDelay", "player.channelPreview", "player.matchFrameRate", "player.externalDisplay",
        // build 52 (zelfde sleutels als iOS, in `shared`); accentColor is sinds build 54 per profiel (zie [Accent])
        "deinterlace", "resumeLastChannel", IntroMarkers.PREF,
    )
    /** Niet als ruwe Android-waarde meesturen (staat al in `shared`). */
    private val sharedOnlyKeys = setOf(IntroMarkers.PREF)
    /** Na het toepassen: lokale intro-markeringen die de server nog niet had → opnieuw uploaden. */
    private var markersDiffer = false
    private val boolKeys = setOf("autoSubtitles", "ui.showLive", "ui.showMovies", "ui.showSeries", "trakt.scrobble", "trakt.askRating",
        "player.channelPreview", "player.matchFrameRate", "player.externalDisplay", "resumeLastChannel")
    private val intKeys = setOf("subtitle.size", "ui.startTab", "player.zapDelay")

    private const val CONNECTIONS = "connections"
    private const val SETTINGS = "settings"
    private const val REMINDERS = "reminders"
    private const val COLLECTIONS = "collections"
    private const val PROFILE = "profile."
    private const val UPLOAD_DELAY = 3_000L
    private const val PROGRESS_INTERVAL = 30_000L
    private const val REMOVED_DAYS = 90L
    private const val MAX_HISTORY = 500
    private const val MAX_PROGRESS = 300
    private const val MAX_RECENT = 20

    /** Velden met losse items (per item toegevoegd/verwijderd bijhouden). */
    private val setFields = listOf("myList", "favorites", "watched", "recentChannels")

    private val active: Boolean get() = Account.isSignedIn && Account.configured

    private var synced = false
    /** > 0: wijzigingen komen uit de sync (of worden gewist), niet terugsturen. */
    private var applying = 0
    /** > 0: Prefs-wijzigingen zijn het toepassen van kijkvoorkeuren van een profiel, geen instelling. */
    private var settingsQuiet = 0
    /** Onderdelen met lokale wijzigingen: "connections", "settings", "reminders" of "profile:<id>". */
    private val dirty = linkedSetOf<String>()
    private var timer: Job? = null
    private var syncJob: Job? = null
    private var refreshJob: Job? = null
    private var lastProgressUpload = 0L
    /** Alles wat de server leest/schrijft, één tegelijk. */
    private val lock = Mutex()
    /** Laatst gelezen serverversies (voor het apple-blok en onbekende velden). */
    private var serverSettings: JsonObject? = null
    private var serverConnections: JsonObject? = null
    private var serverReminders: JsonObject? = null
    /** Collecties zoals de server ze kent (build 54: profiel → id → object, kleine letters), voor velden die deze app niet kent. */
    private var serverCollectionItems = mapOf<String, Map<String, JsonObject>>()
    /** Sectienaam op de server per profiel (kleine letters id → naam), want iOS gebruikt hoofdletters. */
    private val sectionNames = mutableMapOf<String, String>()

    // MARK: lokale stand

    private val prefs get() = AppState.appContext.getSharedPreferences("bingr.statesync", Context.MODE_PRIVATE)

    // MARK: build 91: kijkvoorkeuren van het account (settings.shared)

    /** build 91: de kijkvoorkeuren in `settings.shared` (alleen het hoofdprofiel schrijft ze). */
    private val sharedViewingKeys = listOf("audioLanguage", "subtitleLanguage", "autoSubtitles", "openSubtitlesLanguage")
    /** build 91: dezelfde kijkvoorkeuren als ruwe Android-sleutels (`settings.android.prefs`). */
    private val rawViewingKeys = setOf("player.audioLanguage", "player.subtitleLanguage", "autoSubtitles",
        TrackPreferences.autoSubtitlesKey, "os.language")

    /**
     * build 91: kijkvoorkeur van het account (`settings.shared.<k>`): de laatst gelezen serverversie, anders de lokaal
     * bewaarde kopie (ook offline en vóór de eerste sync); null = onbekend. Voor profielen zonder eigen keuze (`prefs` null).
     */
    fun sharedViewing(k: String): String? {
        serverSettings?.get("shared")?.jS(k)?.let { return it }
        return runCatching { prefs.getString("shared.$k", null) }.getOrNull()
    }

    /** build 91: kijkvoorkeuren uit `settings.shared` van de server lokaal bewaren. */
    private fun rememberSharedViewing(settings: JsonObject?) {
        val s = settings?.get("shared") as? JsonObject ?: return
        runCatching {
            prefs.edit().apply { sharedViewingKeys.forEach { k -> s.jS(k)?.let { putString("shared.$k", it) } } }.apply()
        }
    }

    /** build 91: de huidige keuzes van het hoofdprofiel (of een account zonder profielen) als kijkvoorkeuren van het account bewaren. */
    fun rememberLocalViewing() {
        if (!Store.canManage) return
        runCatching {
            prefs.edit()
                .putString("shared.audioLanguage", TrackPreferences.audio)
                .putString("shared.subtitleLanguage", TrackPreferences.subtitle)
                .putString("shared.autoSubtitles", TrackPreferences.autoSubtitles)
                .putString("shared.openSubtitlesLanguage", OpenSubtitles.defaultLanguage)
                .apply()
        }
    }

    private fun localAt(section: String): Long? = runCatching {
        prefs.getLong("upd.$section", -1L).takeIf { it >= 0 }
    }.getOrNull()
    private fun setLocalAt(section: String, v: Long?) {
        runCatching { prefs.edit().apply { if (v == null) remove("upd.$section") else putLong("upd.$section", v) }.apply() }
    }

    @Serializable
    private data class Meta(
        /** veld → item → wanneer het lokaal (het laatst) toegevoegd is */
        val present: Map<String, Map<String, Long>> = emptyMap(),
        /** veld → item → wanneer het verwijderd is */
        val removed: Map<String, Map<String, Long>> = emptyMap(),
        val prefsAt: Long? = null,
        val pinAt: Long? = null,
    )

    private val metaDir: File get() = File(AppState.appContext.filesDir, "statesync").apply { mkdirs() }
    private fun metaFile(id: String) = File(metaDir, "${id.lowercase()}.json")
    private fun readMeta(id: String): Meta =
        runCatching { Http.json.decodeFromString(Meta.serializer(), metaFile(id).readText()) }.getOrDefault(Meta())
    private fun writeMeta(id: String, m: Meta) {
        runCatching { metaFile(id).writeText(Http.json.encodeToString(Meta.serializer(), m)) }
    }

    // MARK: hooks

    /** Wijzigingen uit de sync toepassen zonder ze terug te sturen. */
    inline fun <T> applyingChanges(block: () -> T): T {
        beginApplying()
        try { return block() } finally { endApplying() }
    }
    fun beginApplying() { applying++ }
    fun endApplying() { applying-- }

    /** Kijkvoorkeuren van een profiel toepassen is geen wijziging van de instellingen. */
    inline fun <T> withoutSettingsChange(block: () -> T): T {
        beginQuiet()
        try { return block() } finally { endQuiet() }
    }
    fun beginQuiet() { settingsQuiet++ }
    fun endQuiet() { settingsQuiet-- }

    /** Een instelling uit [settingKeys] (of het kinderslot) is gewijzigd. */
    fun settingsChanged() {
        if (applying > 0 || settingsQuiet > 0) return
        setLocalAt(SETTINGS, System.currentTimeMillis())
        markDirty(SETTINGS)
    }

    /** Een geheim is gewijzigd: Trakt, OpenSubtitles of een pincode. */
    fun secretChanged(key: String) {
        if (applying > 0) return
        when {
            key == "trakt.tokens" || key == "opensubtitles.session" -> {
                setLocalAt(CONNECTIONS, System.currentTimeMillis())
                markDirty(CONNECTIONS)
            }
            key == "parental.pin" -> Store.mainProfileID?.let { pinChanged(it) }
            key.startsWith("profile.") && key.endsWith(".pin") -> pinChanged(key.removePrefix("profile.").removeSuffix(".pin"))
        }
    }

    private fun pinChanged(id: String) {
        if (Store.profiles.none { it.id == id }) return
        val m = readMeta(id)
        writeMeta(id, m.copy(pinAt = System.currentTimeMillis()))
        markDirty("profile:$id")
    }

    /** Kijkvoorkeuren van dit profiel gewijzigd. */
    fun prefsChanged(id: String) {
        if (applying > 0) return
        val m = readMeta(id)
        writeMeta(id, m.copy(prefsAt = System.currentTimeMillis()))
        markDirty("profile:$id")
    }

    /** Lijsten, bekeken, recente zenders of geschiedenis van dit profiel gewijzigd. */
    fun profileDataChanged(id: String?) {
        if (applying > 0 || id == null) return
        recordLocal(id)
        markDirty("profile:$id")
    }

    /** Voortgang gewijzigd (tijdens het afspelen): hooguit eens per 30 s uploaden. */
    fun progressChanged(id: String?) {
        if (applying > 0 || id == null) return
        recordLocal(id)
        dirty += "profile:$id"
        if (!active) return
        if (!synced) { launchSync(); return }
        if (timer?.isActive == true) return
        val wait = maxOf(UPLOAD_DELAY, lastProgressUpload + PROGRESS_INTERVAL - System.currentTimeMillis())
        schedule(wait)
    }

    /** Een collectie is toegevoegd, gewijzigd of verwijderd (build 51), of een profiel met collecties is weg (build 54). */
    fun collectionsChanged() {
        if (applying > 0) return
        markDirty(COLLECTIONS)
    }

    fun remindersChanged() {
        if (applying > 0) return
        setLocalAt(REMINDERS, System.currentTimeMillis())
        markDirty(REMINDERS)
    }

    /** Profiel verwijderd (tombstone): ook de gegevens op de server verwijderen. */
    fun profileDeleted(id: String) {
        val key = id.lowercase()
        dirty.remove("profile:$id")
        runCatching { metaFile(id).delete() }
        val names = (listOfNotNull(sectionNames.remove(key)) + "$PROFILE$id").distinct()
        if (!active) return
        AppScope.launch { quietly { lock.withLock { names.forEach { Account.deleteState(it) } } } }
    }

    /** Trakt-tokens zijn net ververst: meteen (zonder wachten) naar het account. */
    fun traktRefreshed() {
        setLocalAt(CONNECTIONS, System.currentTimeMillis())
        dirty.remove(CONNECTIONS)
        if (!active) return
        AppScope.launch { quietly { lock.withLock { uploadSimple(CONNECTIONS) } } }
    }

    private fun markDirty(key: String) {
        dirty += key
        if (!active) return
        if (!synced) { launchSync(); return }
        schedule(UPLOAD_DELAY)
    }

    private fun schedule(wait: Long) {
        timer?.cancel()
        timer = AppScope.launch {
            delay(wait)
            timer = null
            runUpload()
        }
    }

    /** Wachtende wijzigingen nu uploaden (app naar de achtergrond, afspelen gestopt). */
    fun flush() {
        timer?.cancel(); timer = null
        if (dirty.isEmpty() || !active || !synced) return
        AppScope.launch { runUpload() }
    }

    /** Uitgelogd: sync-stand vergeten. Lokale gegevens en tijdstempels blijven (ze horen bij dit apparaat). */
    fun reset() {
        timer?.cancel(); timer = null
        syncJob?.cancel(); syncJob = null
        refreshJob?.cancel(); refreshJob = null
        synced = false
        serverSettings = null; serverConnections = null; serverReminders = null
        serverCollectionItems = emptyMap()
        sectionNames.clear()
    }

    /** Ander account op dit apparaat: ook de instellingen, tijdstempels en metabestanden van het vorige account wissen. */
    fun wipeLocal() {
        reset()
        dirty.clear()
        applyingChanges {
            // standaardwaarden (ook in het geheugen), daarna de opgeslagen waarden weg
            Localizer.setOverride("")
            SubtitleSize.current = SubtitleSize.SMALL
            TrackPreferences.audio = ""
            TrackPreferences.subtitle = ""
            OpenSubtitles.preferredLanguages = listOf("nl", "en")
            OpenSubtitles.defaultLanguage = Localizer.language.take(2)
            Trakt.scrobbleEnabled = true
            Trakt.askRating = true
            settingKeys.forEach { Prefs.remove(it) }
            Accent.resetMigration()
            IntroMarkers.reload()
        }
        runCatching { prefs.edit().clear().apply() }
        runCatching { metaDir.listFiles()?.forEach { it.delete() } }
    }

    // MARK: synchroniseren

    fun launchSync(): Job? {
        if (!active) return null
        syncJob?.takeIf { it.isActive }?.let { return it }
        val job = AppScope.launch { quietly { sync() } }
        syncJob = job
        return job
    }

    /** Synchroniseren en wachten tot het klaar is (na inloggen, vóór "Wie kijkt er?"). */
    suspend fun syncNow() { launchSync()?.join() }

    /** Bij het starten, bij terugkeer naar de voorgrond en bij elke heartbeat: opnieuw lezen en samenvoegen. */
    fun refreshFromServer() {
        if (!active) return
        if (!synced) { launchSync(); return }
        if (syncJob?.isActive == true || refreshJob?.isActive == true) return
        refreshJob = AppScope.launch { quietly { sync() } }
    }

    private suspend fun quietly(block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }

    private suspend fun sync() {
        // profielen eerst (nieuwe profielen moeten bestaan voor hun gegevens)
        ProfileSync.awaitRunning()
        lock.withLock {
            val all = Account.getState() ?: return
            if (!active) return
            mergeConnections(all[CONNECTIONS])
            mergeSettings(all[SETTINGS])
            mergeReminders(all[REMINDERS])
            mergeCollections(all[COLLECTIONS], all.containsKey(COLLECTIONS))
            mergeProfiles(all)
            synced = true
        }
        migrateAccent()
        // lokale wijzigingen van vóór de sync
        if (dirty.isNotEmpty()) runUpload()
    }

    /**
     * Build 54: accentkleur van vóór build 54 (`settings.shared.accentColor`) eenmalig naar alle profielen zonder
     * eigen kleur. Pas als de profielen met het account samengevoegd zijn, anders zou een automatisch hoofdprofiel
     * de kleur krijgen in plaats van de echte profielen.
     */
    private suspend fun migrateAccent() {
        if (!ProfileSync.isSynced) return
        val shared = (serverSettings?.get("shared") as? JsonObject)?.jS("accentColor")
        withContext(Dispatchers.Main) { Accent.migrate(shared) }
    }

    private suspend fun runUpload() {
        quietly {
            lock.withLock {
                if (!active || !synced) return@withLock
                val keys = dirty.toList()
                dirty.clear()
                val failed = mutableListOf<String>()
                for (k in keys) {
                    val ok = try {
                        when {
                            k.startsWith("profile:") -> uploadProfile(k.removePrefix("profile:"))
                            k == COLLECTIONS -> uploadCollections()
                            else -> uploadSimple(k)
                        }
                    } catch (e: CancellationException) { dirty += keys; throw e } catch (_: Exception) { false }
                    if (!ok) failed += k
                    if (k.startsWith("profile:")) lastProgressUpload = System.currentTimeMillis()
                }
                dirty += failed
            }
        }
    }

    private suspend fun uploadSimple(section: String): Boolean {
        val data = when (section) {
            CONNECTIONS -> connectionsJson()
            SETTINGS -> settingsJson()
            REMINDERS -> remindersJson()
            else -> return true
        }
        val ok = Account.putState(section, data) != null
        if (ok) when (section) {
            CONNECTIONS -> serverConnections = data
            SETTINGS -> { serverSettings = data; rememberSharedViewing(data) }
            REMINDERS -> serverReminders = data
        }
        return ok
    }

    /** Profiel uploaden: eerst de serverversie lezen en samenvoegen (zodat wijzigingen van elders niet verloren gaan). */
    private suspend fun uploadProfile(id: String): Boolean {
        if (Store.profiles.none { it.id == id }) return true
        val names = listOfNotNull(sectionNames[id.lowercase()], "$PROFILE$id").distinct()
        val got = Account.getState(names) ?: return false
        return mergeProfile(id, got.filterKeys { it in names }.toList())
    }

    // MARK: tijden

    private fun isoFormat() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private fun iso(ms: Long): String = isoFormat().format(Date(ms))

    /** Tijd als epoch-ms: ISO-8601 (met milliseconden) of een getal (ms, of seconden). */
    private fun time(e: JsonElement?): Long? {
        val p = e as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        val s = p.content.trim()
        if (s.isEmpty()) return null
        if (!p.isString || s.all { it.isDigit() || it == '.' || it == '-' }) {
            val d = s.toDoubleOrNull() ?: return null
            return if (d > 100_000_000_000.0) d.toLong() else (d * 1000).toLong()
        }
        val base = Net.parseISODate(s) ?: return null
        val dot = s.indexOf('.')
        if (dot < 0) return base
        val digits = s.substring(dot + 1).takeWhile { it.isDigit() }
        return base + ((digits + "000").take(3).toIntOrNull() ?: 0)
    }

    private fun sectionAt(data: JsonObject?, rowAt: String?): Long =
        time(data?.get("updatedAt")) ?: time(rowAt?.let { JsonPrimitive(it) }) ?: 0L

    // MARK: connections

    private fun connectionsJson(): JsonObject {
        val t = Trakt.currentTokens
        val os = OpenSubtitles.currentSession
        val base = serverConnections ?: JsonObject(emptyMap())
        return JsonObject(base + mapOf(
            "trakt" to (t?.let {
                JsonObject(mapOf(
                    "accessToken" to JsonPrimitive(it.accessToken),
                    "refreshToken" to JsonPrimitive(it.refreshToken),
                    "expiresAt" to JsonPrimitive(iso(it.expiresAt)),
                    "username" to (it.username?.let { u -> JsonPrimitive(u) } ?: JsonNull),
                ))
            } ?: JsonNull),
            "opensubtitles" to (os?.let { (token, user, url) ->
                JsonObject(mapOf(
                    "token" to JsonPrimitive(token),
                    "username" to JsonPrimitive(user),
                    "baseURL" to JsonPrimitive(url),
                ))
            } ?: JsonNull),
            "updatedAt" to JsonPrimitive(iso(localAt(CONNECTIONS) ?: System.currentTimeMillis())),
        ))
    }

    private fun traktFrom(o: JsonObject?): Trakt.Tokens? {
        val at = o.jS("accessToken")?.takeIf { it.isNotEmpty() } ?: return null
        val rt = o.jS("refreshToken")?.takeIf { it.isNotEmpty() } ?: return null
        return Trakt.Tokens(at, rt, time(o?.get("expiresAt")) ?: 0L, o.jS("username")?.takeIf { it.isNotEmpty() })
    }

    private fun applyConnections(o: JsonObject, keepLocalWhereServerEmpty: Boolean): Boolean {
        var keptLocal = false
        applyingChanges {
            val t = traktFrom(o["trakt"] as? JsonObject)
            if (t != null || !keepLocalWhereServerEmpty || Trakt.currentTokens == null) Trakt.applySyncedTokens(t)
            else { keptLocal = true }
            val os = o["opensubtitles"] as? JsonObject
            val token = os.jS("token")?.takeIf { it.isNotEmpty() }
            if (token != null || !keepLocalWhereServerEmpty || OpenSubtitles.currentSession == null) {
                OpenSubtitles.applySyncedSession(token, os.jS("username"), os.jS("baseURL") ?: os.jS("baseUrl"))
            } else { keptLocal = true }
            Unit
        }
        return keptLocal
    }

    private suspend fun mergeConnections(sec: Account.StateSection?) {
        val data = sec?.data as? JsonObject
        val local = localAt(CONNECTIONS)
        val hasLocal = Trakt.currentTokens != null || OpenSubtitles.currentSession != null
        if (data == null) {
            if (hasLocal || local != null) {
                if (local == null) setLocalAt(CONNECTIONS, System.currentTimeMillis())
                uploadSimple(CONNECTIONS)
            }
            return
        }
        serverConnections = data
        val at = sectionAt(data, sec.updatedAt)
        when {
            local == null -> {
                // eerste keer op dit apparaat (of van vóór build 44): server overnemen, maar lokale koppelingen
                // die de server nog niet heeft niet weggooien
                val kept = withContext(Dispatchers.Main) { applyConnections(data, keepLocalWhereServerEmpty = true) }
                if (kept) { setLocalAt(CONNECTIONS, System.currentTimeMillis()); uploadSimple(CONNECTIONS) }
                else setLocalAt(CONNECTIONS, at)
            }
            at > local -> {
                withContext(Dispatchers.Main) { applyConnections(data, keepLocalWhereServerEmpty = false) }
                setLocalAt(CONNECTIONS, at)
            }
            local > at -> uploadSimple(CONNECTIONS)
        }
    }

    /**
     * Vóór het verversen van een Trakt-token (eenmalige refresh-tokens): `connections` van de server lezen.
     * Heeft de server nieuwere tokens, dan worden die overgenomen (true) en hoeft er niet ververst te worden.
     */
    suspend fun adoptNewerServerConnections(): Boolean {
        if (!active) return false
        return try {
            val got = withTimeoutOrNull(6_000) { Account.getState(listOf(CONNECTIONS)) } ?: return false
            val sec = got[CONNECTIONS] ?: return false
            val data = sec.data as? JsonObject ?: return false
            val at = sectionAt(data, sec.updatedAt)
            val local = localAt(CONNECTIONS)
            if (local != null && at <= local) return false
            val server = traktFrom(data["trakt"] as? JsonObject)
            if (server == null && local == null) return false
            if (server == Trakt.currentTokens) { setLocalAt(CONNECTIONS, at); return false }
            serverConnections = data
            withContext(Dispatchers.Main) { applyConnections(data, keepLocalWhereServerEmpty = false) }
            setLocalAt(CONNECTIONS, at)
            true
        } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
    }

    // MARK: settings

    private fun engineToShared(v: String?) = when (v) { "vlc" -> "vlc"; "apple", "exo" -> "exo"; else -> "auto" }
    private fun engineFromShared(v: String?) = when (v) { "vlc" -> "vlc"; "apple", "exo" -> "apple"; else -> "auto" }
    private val tabNames = listOf("home", "live", "movies", "series")
    private val sizeNames = listOf("small", "medium", "large")

    private fun rawJson(k: String): JsonElement? = when (val v = Prefs.raw(k)) {
        is String -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Int -> JsonPrimitive(v)
        is Long -> JsonPrimitive(v)
        else -> null
    }

    /**
     * Gedeelde instellingen (zelfde naam en betekenis op iOS): appLanguage, playerEngine (auto/apple/exo/vlc),
     * subtitleSize (small/medium/large), audioLanguage, subtitleLanguage, autoSubtitles, openSubtitlesLanguage,
     * openSubtitlesLanguages, startScreen (home/live/movies/series), hiddenTabs, refreshInterval, categorySort,
     * traktScrobble, traktAskRating, parentalControl en parentalAutoLockAdult.
     */
    private fun sharedSettings(): Map<String, JsonElement> {
        val hidden = buildList {
            if (!Prefs.bool("ui.showLive", true)) add("live")
            if (!Prefs.bool("ui.showMovies", true)) add("movies")
            if (!Prefs.bool("ui.showSeries", true)) add("series")
        }
        // build 91: de talen en de ondertitelstand in Prefs zijn die van het actieve profiel. Alleen het hoofdprofiel
        // (of een account zonder profielen) schrijft ze in `shared`; anders blijft de serverversie staan.
        val main = Store.canManage
        if (main) rememberLocalViewing()
        return mapOf(
            "appLanguage" to JsonPrimitive(Prefs.string("app.language") ?: ""),
            "playerEngine" to JsonPrimitive(engineToShared(Prefs.string("player.engine"))),
            "subtitleSize" to JsonPrimitive(sizeNames.getOrElse(Prefs.int("subtitle.size", 0)) { "small" }),
            "audioLanguage" to JsonPrimitive(TrackPreferences.audio),
            "subtitleLanguage" to JsonPrimitive(TrackPreferences.subtitle),
            // build 59: tekst ("off" / "whenNeeded" / "always") in plaats van aan/uit
            "autoSubtitles" to JsonPrimitive(TrackPreferences.autoSubtitles),
            "openSubtitlesLanguage" to JsonPrimitive(OpenSubtitles.defaultLanguage),
            "openSubtitlesLanguages" to JsonArray(OpenSubtitles.preferredLanguages.map { JsonPrimitive(it) }),
            "startScreen" to JsonPrimitive(tabNames.getOrElse(Prefs.int("ui.startTab", 0)) { "home" }),
            "hiddenTabs" to JsonArray(hidden.map { JsonPrimitive(it) }),
            "refreshInterval" to JsonPrimitive(RefreshPolicy.current.raw),
            "categorySort" to JsonPrimitive(Prefs.string("category.sort") ?: "standard"),
            "traktScrobble" to JsonPrimitive(Prefs.bool("trakt.scrobble", true)),
            "traktAskRating" to JsonPrimitive(Prefs.bool("trakt.askRating", true)),
            "parentalControl" to JsonPrimitive(Store.parental.enabled),
            "parentalAutoLockAdult" to JsonPrimitive(Store.parental.autoLockAdult),
            // build 52
            // accentColor: sinds build 54 per profiel; de oude waarde in `shared` blijft onaangeroerd (via de serverversie)
            "deinterlace" to JsonPrimitive(PlayerSettings.deinterlace),
            "resumeLastChannel" to JsonPrimitive(PlayerSettings.resumeLastChannel),
            "introMarkers" to IntroMarkers.toJson(IntroMarkers.all),
        ).filterKeys { main || it !in sharedViewingKeys }
    }

    private fun settingsJson(): JsonObject {
        val base = serverSettings ?: JsonObject(emptyMap())
        val shared = (base["shared"] as? JsonObject ?: JsonObject(emptyMap())) + sharedSettings()
        val oldAndroid = base["android"] as? JsonObject ?: JsonObject(emptyMap())
        var raw = settingKeys.filter { it !in sharedOnlyKeys }.mapNotNull { k -> rawJson(k)?.let { k to it } }.toMap()
        // build 91: een ander profiel dan het hoofdprofiel laat ook de ruwe kijkvoorkeuren zoals de server ze had
        if (!Store.canManage) {
            val oldPrefs = oldAndroid["prefs"] as? JsonObject ?: JsonObject(emptyMap())
            raw = raw.filterKeys { it !in rawViewingKeys } + oldPrefs.filterKeys { it in rawViewingKeys }
        }
        val android = oldAndroid + mapOf("prefs" to JsonObject(raw))
        return JsonObject(base + mapOf(
            "shared" to JsonObject(shared),
            "android" to JsonObject(android),
            "updatedAt" to JsonPrimitive(iso(localAt(SETTINGS) ?: System.currentTimeMillis())),
        ))
    }

    private fun hasLocalSettings(): Boolean =
        settingKeys.any { Prefs.has(it) } || Store.parental.enabled || !Store.parental.autoLockAdult

    /** Instellingen van de server toepassen: eerst de ruwe Android-waarden, dan de gedeelde (die kunnen van iOS komen). */
    private fun applySettings(o: JsonObject) {
        val values = linkedMapOf<String, Any>()
        (o.jO("android")?.get("prefs") as? JsonObject)?.forEach { (k, v) ->
            if (k !in settingKeys || k in sharedOnlyKeys) return@forEach
            val p = v as? JsonPrimitive ?: return@forEach
            if (p is JsonNull) return@forEach
            values[k] = when (k) {
                in boolKeys -> p.content.equals("true", true) || p.content == "1"
                in intKeys -> p.content.toDoubleOrNull()?.toInt() ?: return@forEach
                else -> p.content
            }
        }
        val s = o.jO("shared")
        if (s != null) {
            fun str(k: String) = (s[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            fun bool(k: String) = str(k)?.let { it.equals("true", true) || it == "1" }
            str("appLanguage")?.let { values["app.language"] = it }
            str("playerEngine")?.let { values["player.engine"] = engineFromShared(it) }
            str("subtitleSize")?.let { v ->
                val i = sizeNames.indexOf(v.lowercase()).takeIf { it >= 0 } ?: v.toDoubleOrNull()?.toInt()
                if (i != null) values["subtitle.size"] = i.coerceIn(0, 2)
            }
            str("audioLanguage")?.let { values["player.audioLanguage"] = it }
            str("subtitleLanguage")?.let { values["player.subtitleLanguage"] = it }
            val autoRaw = str("autoSubtitles")
            TrackPreferences.normalizedMode(autoRaw)?.let { values[TrackPreferences.autoSubtitlesKey] = it }
            // oud formaat (build 60, docs/ACCOUNT.md build 59): subtitleLanguage "off" zonder nieuwe stand = autoSubtitles "off"
            if (str("subtitleLanguage") == "off" && (autoRaw == null || autoRaw == "false" || autoRaw == "0")) {
                values[TrackPreferences.autoSubtitlesKey] = TrackPreferences.AUTO_OFF
            }
            str("openSubtitlesLanguage")?.let { values["os.language"] = it }
            (s["openSubtitlesLanguages"] as? JsonArray)?.let { a ->
                values["os.languages"] = Http.json.encodeToString(ListSerializer(String.serializer()), a.mapNotNull { it.jFlex })
            }
            str("startScreen")?.let { v ->
                val i = tabNames.indexOf(v.lowercase()).takeIf { it >= 0 } ?: v.toDoubleOrNull()?.toInt()
                if (i != null && i in 0..3) values["ui.startTab"] = i
            }
            (s["hiddenTabs"] as? JsonArray)?.let { a ->
                val hidden = a.mapNotNull { it.jFlex?.lowercase() }.toSet()
                values["ui.showLive"] = "live" !in hidden
                values["ui.showMovies"] = "movies" !in hidden
                values["ui.showSeries"] = "series" !in hidden
            }
            str("refreshInterval")?.let { v -> if (RefreshPolicy.entries.any { it.raw == v }) values["playlist.refresh"] = v }
            str("categorySort")?.let { values["category.sort"] = it }
            bool("traktScrobble")?.let { values["trakt.scrobble"] = it }
            bool("traktAskRating")?.let { values["trakt.askRating"] = it }
            // build 52
            // accentColor wordt sinds build 54 genegeerd, behalve voor de eenmalige migratie (zie [migrateAccent])
            str("deinterlace")?.let { v -> if (v in listOf("off", "auto", "on")) values["deinterlace"] = v }
            bool("resumeLastChannel")?.let { values["resumeLastChannel"] = it }
            (s["introMarkers"] as? JsonObject)?.let { o ->
                val incoming = IntroMarkers.parse(o)
                val merged = IntroMarkers.merged(incoming)
                markersDiffer = merged != incoming
                values[IntroMarkers.PREF] = IntroMarkers.toJson(merged).toString()
            }
            val pc = bool("parentalControl")
            val lock = bool("parentalAutoLockAdult")
            if (pc != null || lock != null) {
                applyingChanges { Store.applySyncedParental(pc ?: Store.parental.enabled, lock ?: Store.parental.autoLockAdult) }
            }
        }
        // kijkvoorkeuren die het actieve profiel zelf heeft ingesteld gaan voor
        val prof = Store.activeProfile
        if (prof?.autoSubtitles != null) values.remove("autoSubtitles")
        if (prof?.subtitleMode != null) values.remove(TrackPreferences.autoSubtitlesKey)
        if (prof?.audioLanguage != null) values.remove("player.audioLanguage")
        if (prof?.streamSubtitleLanguage != null) values.remove("player.subtitleLanguage")
        if (prof?.subtitleLanguage != null) values.remove("os.language")
        applyingChanges {
            for ((k, v) in values) {
                if (Prefs.raw(k) == v) continue
                when (k) {
                    "app.language" -> Localizer.setOverride(v as String)
                    "subtitle.size" -> SubtitleSize.current = SubtitleSize.from(v as Int)
                    "player.audioLanguage" -> TrackPreferences.audio = v as String
                    "player.subtitleLanguage" -> TrackPreferences.subtitle = v as String
                    TrackPreferences.autoSubtitlesKey -> TrackPreferences.autoSubtitles = v as String
                    "os.language" -> OpenSubtitles.defaultLanguage = v as String
                    "os.languages" -> runCatching {
                        OpenSubtitles.preferredLanguages = Http.json.decodeFromString(ListSerializer(String.serializer()), v as String)
                    }
                    "trakt.scrobble" -> Trakt.scrobbleEnabled = v as Boolean
                    "trakt.askRating" -> Trakt.askRating = v as Boolean
                    else -> when (v) {
                        is Boolean -> Prefs.put(k, v)
                        is Int -> Prefs.put(k, v)
                        is String -> Prefs.put(k, v)
                    }
                }
            }
            IntroMarkers.reload()
            Unit
        }
    }

    private suspend fun mergeSettings(sec: Account.StateSection?) {
        val data = sec?.data as? JsonObject
        val local = localAt(SETTINGS)
        if (data == null) {
            if (hasLocalSettings() || local != null) {
                if (local == null) setLocalAt(SETTINGS, System.currentTimeMillis())
                uploadSimple(SETTINGS)
            }
            return
        }
        serverSettings = data
        rememberSharedViewing(data)
        val at = sectionAt(data, sec.updatedAt)
        when {
            local == null || at > local -> {
                markersDiffer = false
                withContext(Dispatchers.Main) { applySettings(data) }
                setLocalAt(SETTINGS, at)
                // intro-markeringen die alleen hier stonden: meteen meesturen
                if (markersDiffer) { markersDiffer = false; setLocalAt(SETTINGS, System.currentTimeMillis()); uploadSimple(SETTINGS) }
            }
            local > at -> uploadSimple(SETTINGS)
        }
    }

    // MARK: reminders

    private fun remindersJson(): JsonObject {
        val base = serverReminders ?: JsonObject(emptyMap())
        val items = Store.reminders.map { r ->
            JsonObject(mapOf(
                "id" to JsonPrimitive(r.id),
                "playlistID" to JsonPrimitive(r.playlistID),
                "channelID" to JsonPrimitive(r.channelID),
                "channelName" to JsonPrimitive(r.channelName),
                "title" to JsonPrimitive(r.title),
                "start" to JsonPrimitive(iso(r.start)),
            ))
        }
        return JsonObject(base + mapOf(
            "items" to JsonArray(items),
            "updatedAt" to JsonPrimitive(iso(localAt(REMINDERS) ?: System.currentTimeMillis())),
        ))
    }

    private fun parseReminders(o: JsonObject): List<ProgramReminder> {
        val list = (o["items"] as? JsonArray) ?: (o["reminders"] as? JsonArray) ?: (o["list"] as? JsonArray) ?: return emptyList()
        return list.mapNotNull { e ->
            val r = e as? JsonObject ?: return@mapNotNull null
            val id = r.jS("id")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            ProgramReminder(
                id = id, playlistID = r.jS("playlistID") ?: "", channelID = r.jS("channelID") ?: "",
                channelName = r.jS("channelName") ?: "", title = r.jS("title") ?: "",
                start = time(r["start"]) ?: return@mapNotNull null,
            )
        }.distinctBy { it.id }
    }

    private suspend fun mergeReminders(sec: Account.StateSection?) {
        val data = sec?.data as? JsonObject
        val local = localAt(REMINDERS)
        if (data == null) {
            if (Store.reminders.isNotEmpty() || local != null) {
                if (local == null) setLocalAt(REMINDERS, System.currentTimeMillis())
                uploadSimple(REMINDERS)
            }
            return
        }
        serverReminders = data
        val at = sectionAt(data, sec.updatedAt)
        when {
            local == null && Store.reminders.isNotEmpty() && parseReminders(data).isEmpty() -> {
                setLocalAt(REMINDERS, System.currentTimeMillis()); uploadSimple(REMINDERS)
            }
            local == null || at > local -> {
                val list = parseReminders(data)
                withContext(Dispatchers.Main) { applyingChanges { Reminders.applySynced(list) } }
                setLocalAt(REMINDERS, at)
            }
            local > at -> uploadSimple(REMINDERS)
        }
    }

    // MARK: collecties (build 51)

    /** Collecties uploaden: eerst de serverversie lezen en per item samenvoegen. */
    private suspend fun uploadCollections(): Boolean {
        val got = Account.getState(listOf(COLLECTIONS)) ?: return false
        return mergeCollections(got[COLLECTIONS], got.containsKey(COLLECTIONS))
    }

    private fun collectionFrom(o: JsonObject): BingrCollection? {
        val id = o.jS("id")?.takeIf { it.isNotBlank() } ?: return null
        val deleted = o.jB("deleted") == true
        val img = o["image"] as? JsonObject
        val url = img.jS("url")?.takeIf { it.isNotBlank() }
        val av = if (img != null && url == null) ProfileSync.avatar(img, false, PlaylistAvatar("⭐", 0)) else PlaylistAvatar("⭐", 0)
        val items = (o["items"] as? JsonArray)?.mapNotNull { e ->
            val r = e as? JsonObject ?: return@mapNotNull null
            val t = r.jS("type")?.lowercase() ?: return@mapNotNull null
            val iid = r.jS("id")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            CollectionRef(t, iid)
        } ?: emptyList()
        val types = (o["types"] as? JsonArray)?.mapNotNull { it.jFlex?.lowercase() } ?: BingrCollection.ALL_TYPES
        return BingrCollection(
            id = id,
            name = o.jS("name") ?: "",
            image = CollectionImage(av.symbol, av.colorIndex, url),
            kind = if (o.jS("kind")?.lowercase() == BingrCollection.SMART) BingrCollection.SMART else BingrCollection.MANUAL,
            items = items,
            query = o.jS("query") ?: "",
            types = types,
            group = o.jS("group")?.takeIf { it.isNotEmpty() },
            sourceID = o.jS("sourceID")?.takeIf { it.isNotEmpty() },
            order = o.jI("order") ?: 0,
            updatedAt = time(o["updatedAt"]) ?: 0L,
            deleted = deleted,
            deletedAt = if (deleted) time(o["deletedAt"]) ?: time(o["updatedAt"]) else null,
        )
    }

    /** Collectie → JSON voor profiel [profileKey] (kleine letters); velden die deze app niet kent blijven. */
    private fun collectionJson(profileKey: String, c: BingrCollection): JsonObject {
        val base = serverCollectionItems[profileKey]?.get(c.id.lowercase()) ?: JsonObject(emptyMap())
        val m = base.toMutableMap()
        m["id"] = JsonPrimitive(c.id)
        m["name"] = JsonPrimitive(c.name)
        m["image"] = c.image.url?.let { JsonObject(mapOf("url" to JsonPrimitive(it))) } ?: JsonObject(buildMap {
            put("symbol", JsonPrimitive(AvatarMap.toShared(c.image.symbol)))
            put("colorIndex", JsonPrimitive(c.image.colorIndex))
            if (!c.image.avatar.isIllustration) put("emoji", JsonPrimitive(c.image.symbol))
        })
        m["kind"] = JsonPrimitive(c.kind)
        m["items"] = JsonArray(c.items.map { JsonObject(mapOf("type" to JsonPrimitive(it.type), "id" to JsonPrimitive(it.id))) })
        m["query"] = JsonPrimitive(c.query)
        m["types"] = JsonArray(c.types.map { JsonPrimitive(it) })
        m["group"] = c.group?.let { JsonPrimitive(it) } ?: JsonNull
        m["sourceID"] = c.sourceID?.let { JsonPrimitive(it) } ?: JsonNull
        m["order"] = JsonPrimitive(c.order)
        m["updatedAt"] = JsonPrimitive(iso(c.updatedAt))
        if (c.deleted) {
            m["deleted"] = JsonPrimitive(true)
            m["deletedAt"] = JsonPrimitive(iso(c.deletedAt ?: c.updatedAt))
        } else { m.remove("deleted"); m.remove("deletedAt") }
        return JsonObject(m)
    }

    /**
     * Build 54 ("Collecties per profiel" in docs/ACCOUNT.md van de iOS-repo). Section `collections` =
     * `{version: 2, profiles: {"<profielID>": {items:[…], updatedAt}}, items:[oud], migratedAt, updatedAt}`; elk
     * item heeft het formaat van build 51. Onbekende velden en de oude `items`-lijst (build 51–53) blijven onaangeroerd.
     *
     * - Migratie: is `version` < 2, dan krijgt het hoofdprofiel de oude collecties uit `items` (zelfde id, order en
     *   updatedAt; wat het profiel al had blijft staan). Daarna `version: 2` en `migratedAt`. (Tot build 73 kreeg elk
     *   profiel een kopie; sinds build 74 worden kopieën in subprofielen tombstones, zie Collections.withoutSharedCopies.)
     *   (Een eerste versie van build 54 schreef `byProfile` en `migrated: true`; dat wordt ook gelezen en omgezet.)
     * - Per profiel en per collectie wint de nieuwste `updatedAt` (ook tombstones); tombstones ouder dan 90 dagen vervallen.
     * - Profielen die hier verwijderd zijn, gaan ook van de server. Profielen die alleen op de server staan, blijven daar.
     * Lokaal toepassen en uploaden als het resultaat afwijkt van de server. true = gelukt.
     */
    private suspend fun mergeCollections(sec: Account.StateSection?, present: Boolean): Boolean {
        val data = sec?.data as? JsonObject
        val cutoff = System.currentTimeMillis() - REMOVED_DAYS * 24 * 3_600_000L
        fun alive(c: BingrCollection) = !c.deleted || (c.deletedAt ?: c.updatedAt) >= cutoff
        fun parseList(a: JsonElement?): List<JsonObject> = ((a as? JsonArray) ?: JsonArray(emptyList()))
            .mapNotNull { it as? JsonObject }.filter { !it.jS("id").isNullOrBlank() }

        // server: per profiel (kleine letters → oorspronkelijke sleutel, object, collecties)
        val earlyBy = (data?.get("byProfile") as? JsonObject) ?: JsonObject(emptyMap())
        val serverBy = (data?.get("profiles") as? JsonObject) ?: JsonObject(emptyMap())
        // vroege build-54-vorm (`byProfile`): per profiel de collecties die nog niet in `profiles` staan erbij
        val earlyLists = earlyBy.mapNotNull { (k, v) ->
            (v as? JsonObject)?.let { k.lowercase() to parseList(it["items"]).mapNotNull { o -> collectionFrom(o) } }
        }.toMap()
        val serverKeys = serverBy.keys.associateBy { it.lowercase() }
        val serverEntries = serverBy.mapNotNull { (k, v) -> (v as? JsonObject)?.let { k.lowercase() to it } }.toMap()
        val serverLists = serverEntries.mapValues { (_, e) -> parseList(e["items"]).mapNotNull { collectionFrom(it) } }
        serverCollectionItems = serverEntries.mapValues { (_, e) -> parseList(e["items"]).associateBy { it.jS("id")!!.lowercase() } }
        // gemigreerd (contract): `version >= 2`, of `migrated: true` (vroege build 54), of er is al een object `profiles`
        val migrated = (data?.jI("version") ?: 0) >= 2 || data?.jB("migrated") == true || data?.get("profiles") is JsonObject
        val legacy = parseList(data?.get("items")).mapNotNull { collectionFrom(it) }

        val (local, profiles, mainID) = withContext(Dispatchers.Main) {
            Collections.ensureLoaded(); Collections.distributeLegacy()
            Triple(Collections.snapshot(), Store.profiles.map { it.id }, Store.mainProfileID)
        }
        val mainKey = mainID?.lowercase()
        val removed = local.removedProfiles.filterValues { it >= cutoff }
            .filterKeys { id -> profiles.none { it.equals(id, ignoreCase = true) } }
        val removedKeys = removed.keys.map { it.lowercase() }.toSet()

        // samenvoegen per profiel (sleutel = kleine letters; de naam komt van het lokale profiel of de server)
        val names = LinkedHashMap<String, String>()
        serverKeys.forEach { (k, orig) -> names[k] = orig }
        earlyBy.keys.forEach { names.putIfAbsent(it.lowercase(), it) }
        local.byProfile.keys.forEach { names.putIfAbsent(it.lowercase(), it) }
        profiles.forEach { names[it.lowercase()] = it }
        val merged = LinkedHashMap<String, List<BingrCollection>>()
        for ((k, _) in names) {
            if (k in removedKeys) continue
            val out = LinkedHashMap<String, BingrCollection>()
            local.byProfile.entries.firstOrNull { it.key.lowercase() == k }?.value?.forEach { out[it.id.lowercase()] = it }
            for (c in (serverLists[k] ?: emptyList()) + (earlyLists[k] ?: emptyList())) {
                val l = out[c.id.lowercase()]
                if (l == null || c.updatedAt > l.updatedAt) out[c.id.lowercase()] = c
            }
            // migratie van de collecties voor het hele account: alleen naar het hoofdprofiel (build 74; daarvoor elk profiel)
            if (!migrated && k == mainKey) {
                for (c in legacy) {
                    val l = out[c.id.lowercase()]
                    if (l == null || c.updatedAt > l.updatedAt) out[c.id.lowercase()] = c
                }
            }
            val list = out.values.filter { alive(it) }
            if (list.isNotEmpty() || k in serverKeys || profiles.any { it.lowercase() == k }) merged[k] = list
        }
        // build 74: kopieën van collecties van een ander profiel (migratie tot build 73) uit de subprofielen halen
        Collections.withoutSharedCopies(merged, mainKey).let { if (it !== merged) { merged.clear(); merged.putAll(it) } }

        // lokaal: alleen profielen die op dit apparaat bestaan
        val localMap = merged.filterKeys { k -> profiles.any { it.lowercase() == k } }
            .mapKeys { (k, _) -> profiles.first { it.lowercase() == k } }
        withContext(Dispatchers.Main) { applyingChanges { Collections.applySynced(localMap, removed) } }

        // moet de server bijgewerkt worden?
        val canMigrate = profiles.isNotEmpty()
        val anything = merged.values.any { it.isNotEmpty() } || legacy.isNotEmpty()
        if (!present && !anything) return true
        var same = ((data?.jI("version") ?: 0) >= 2 || !canMigrate) && earlyBy.isEmpty() && serverKeys.keys.none { it in removedKeys }
        if (same) for ((k, list) in merged) {
            val srv = (serverLists[k] ?: emptyList()).filter { alive(it) }.associateBy { it.id.lowercase() }
            if (k !in serverKeys && list.isEmpty()) continue
            if (srv.size != list.size || list.any { srv[it.id.lowercase()] != it }) { same = false; break }
        }
        if (same) return true

        val by = LinkedHashMap<String, JsonElement>()
        for ((k, list) in merged) {
            if (list.isEmpty() && k !in serverKeys) continue
            val base = serverEntries[k] ?: JsonObject(emptyMap())
            val items = list.sortedWith(compareBy({ it.order }, { it.name.lowercase() })).map { collectionJson(k, it) }
            by[names[k] ?: k] = JsonObject(base + mapOf(
                "items" to JsonArray(items),
                "updatedAt" to JsonPrimitive(iso(list.maxOfOrNull { it.updatedAt } ?: 0L)),
            ))
        }
        val now = iso(System.currentTimeMillis())
        val fields = mutableMapOf<String, JsonElement>(
            "profiles" to JsonObject(by),
            "updatedAt" to JsonPrimitive(now),
        )
        if (migrated || canMigrate) {
            fields["version"] = JsonPrimitive(2)
            fields["migratedAt"] = data?.get("migratedAt")?.takeIf { it is JsonPrimitive && it !is JsonNull } ?: JsonPrimitive(now)
        }
        val json = JsonObject((data ?: JsonObject(emptyMap())) - setOf("byProfile", "migrated") + fields)
        val ok = Account.putState(COLLECTIONS, json) != null
        if (ok) serverCollectionItems = merged.mapValues { (k, list) -> list.associate { it.id.lowercase() to collectionJson(k, it) } }
        return ok
    }

    // MARK: per profiel

    /** Eén item met wanneer het (het laatst) gezet is; [value] null = verwijderd. */
    private data class Item<T>(val at: Long, val value: T?)

    private data class KijkPrefs(
        /** build 59: "off" / "whenNeeded" / "always" (oudere apps: true/false) */
        val autoSubtitles: String?, val subtitleLanguage: String?, val audioLanguage: String?, val streamSubtitleLanguage: String?,
    )

    private data class PState(
        val sets: Map<String, Map<String, Item<Boolean>>>,
        val progress: Map<String, Item<ProgressEntry>>,
        val history: Map<String, Item<HistoryEntry>>,
        val prefs: Item<KijkPrefs>?,
        val pin: Item<String>?,
        /** Startscherm (build 51): het hele object wint op updatedAt */
        val home: Item<HomeLayout>? = null,
    )

    /** Huidige lokale stand van een profiel vergelijken met het metabestand en verschillen vastleggen (met tijd). */
    private fun recordLocal(id: String): Meta {
        val now = System.currentTimeMillis()
        val d = Store.profileData(id)
        val m = readMeta(id)
        val present = m.present.toMutableMap()
        val removed = m.removed.toMutableMap()
        fun record(field: String, current: Map<String, Long?>) {
            val p = (present[field] ?: emptyMap()).toMutableMap()
            val r = (removed[field] ?: emptyMap()).toMutableMap()
            for ((k, at) in current) {
                val known = p[k]
                if (known == null || (at != null && at > known)) { p[k] = at ?: now; r.remove(k) }
            }
            for (k in p.keys.toList()) if (k !in current) { p.remove(k); r[k] = now }
            present[field] = p; removed[field] = r
        }
        record("myList", d.myList.associateWith { null })
        record("favorites", d.favorites.associateWith { null })
        record("watched", d.watched.associateWith { null })
        // recente zenders: volgorde = tijd (nieuwste eerst)
        val known = present["recentChannels"] ?: emptyMap()
        val order = known.entries.sortedByDescending { it.value }.map { it.key }.filter { it in d.recent }
        if (order != d.recent.filter { it in known }) {
            record("recentChannels", d.recent.withIndex().associate { (i, k) -> k to now - i })
        } else record("recentChannels", d.recent.associateWith { null })
        record("progress", d.progress.mapValues { it.value.updated })
        record("history", d.history.associate { it.id to it.date })
        val cutoff = now - REMOVED_DAYS * 24 * 3_600_000L
        val n = m.copy(present = present, removed = removed.mapValues { (_, v) -> v.filterValues { it >= cutoff } })
        if (n != m) writeMeta(id, n)
        return n
    }

    private fun prefsOf(p: ViewerProfile?): KijkPrefs? = p?.let {
        KijkPrefs(it.subtitleMode, it.subtitleLanguage, it.audioLanguage, it.streamSubtitleLanguage)
    }

    private fun localState(id: String, m: Meta): PState {
        val d = Store.profileData(id)
        val sets = setFields.associateWith { f ->
            val out = HashMap<String, Item<Boolean>>()
            m.removed[f]?.forEach { (k, at) -> out[k] = Item(at, null) }
            m.present[f]?.forEach { (k, at) -> out[k] = Item(at, true) }
            out
        }
        val progress = HashMap<String, Item<ProgressEntry>>()
        m.removed["progress"]?.forEach { (k, at) -> progress[k] = Item(at, null) }
        d.progress.forEach { (k, e) -> progress[k] = Item(e.updated, e.copy(profileID = id)) }
        val history = HashMap<String, Item<HistoryEntry>>()
        m.removed["history"]?.forEach { (k, at) -> history[k] = Item(at, null) }
        d.history.forEach { e -> history[e.id] = Item(e.date, e.copy(profileID = id)) }
        val prof = Store.profiles.firstOrNull { it.id == id }
        val kp = prefsOf(prof)
        val prefs = when {
            m.prefsAt != null -> Item(m.prefsAt, kp)
            kp != null && kp != KijkPrefs(null, null, null, null) -> Item(-1L, kp)   // van vóór build 44
            else -> null
        }
        val stored = ParentalControl.storedPIN(id)
        val pin = when {
            m.pinAt != null -> Item(m.pinAt, stored)
            stored != null -> Item(-1L, stored)
            else -> null
        }
        val hl = Store.homeLayout(id)
        val home = if (hl.rows.isEmpty() && hl.updatedAt == 0L) null else Item(hl.updatedAt, hl)
        return PState(sets, progress, history, prefs, pin, home)
    }

    private fun parseHomeLayout(e: JsonElement?): Item<HomeLayout>? {
        val o = e as? JsonObject ?: return null
        val rows = (o["rows"] as? JsonArray)?.mapNotNull { r ->
            val ro = r as? JsonObject ?: return@mapNotNull null
            val key = ro.jS("key")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            HomeRow(key, ro.jB("visible") ?: true)
        } ?: return null
        val at = time(o["updatedAt"]) ?: 0L
        return Item(at, HomeLayout(rows, at))
    }

    private fun parseSetField(e: JsonElement?, removed: JsonObject?): Map<String, Item<Boolean>> {
        val out = HashMap<String, Item<Boolean>>()
        fun put(k: String, it: Item<Boolean>) { val c = out[k]; if (c == null || it.at > c.at) out[k] = it }
        fun parseList(a: JsonArray) {
            for (x in a) when (x) {
                is JsonPrimitive -> x.jFlex?.takeIf { it.isNotEmpty() }?.let { put(it, Item(0L, true)) }
                is JsonObject -> {
                    val k = (x.jS("id") ?: x.jS("key"))?.takeIf { it.isNotEmpty() } ?: continue
                    val at = time(x["at"]) ?: time(x["updatedAt"]) ?: 0L
                    val gone = x.jB("removed") == true || x.jB("deleted") == true
                    put(k, Item(at, if (gone) null else true))
                }
                else -> {}
            }
        }
        when (e) {
            is JsonArray -> parseList(e)
            is JsonObject -> {
                val items = (e["items"] as? JsonArray) ?: (e["ids"] as? JsonArray)
                if (items != null) {
                    parseList(items)
                    (e["removed"] as? JsonObject)?.forEach { (k, v) -> put(k, Item(time(v) ?: 0L, null)) }
                } else e.forEach { (k, v) ->
                    when {
                        v is JsonObject -> put(k, Item(time(v["at"]) ?: time(v["updatedAt"]) ?: 0L,
                            if (v.jB("removed") == true || v.jB("deleted") == true) null else true))
                        v is JsonPrimitive && !v.isString && (v.content == "true" || v.content == "false") ->
                            put(k, Item(0L, if (v.content == "true") true else null))
                        else -> put(k, Item(time(v) ?: 0L, true))
                    }
                }
            }
            else -> {}
        }
        removed?.forEach { (k, v) -> put(k, Item(time(v) ?: 0L, null)) }
        return out
    }

    private fun parseProgress(id: String, o: JsonObject): ProgressEntry? {
        val pos = o.jD("position") ?: 0.0
        val dur = o.jD("duration")
        val frac = o.jD("fraction") ?: if (dur != null && dur > 0) pos / dur else return null
        return ProgressEntry(
            fraction = frac, position = pos, updated = time(o["updatedAt"]) ?: time(o["updated"]) ?: 0L,
            title = o.jS("title") ?: "", artwork = o.jS("artwork")?.takeIf { it.isNotEmpty() },
            playlistID = o.jS("playlistID")?.takeIf { it.isNotEmpty() }, profileID = id,
        )
    }

    private fun parseHistory(id: String, o: JsonObject): HistoryEntry? {
        val itemID = o.jS("itemID")?.takeIf { it.isNotEmpty() } ?: return null
        val type = o.jS("type")?.let { t -> MediaType.entries.firstOrNull { it.name.equals(t, ignoreCase = true) } } ?: MediaType.MOVIE
        return HistoryEntry(
            itemID = itemID, playlistID = o.jS("playlistID")?.takeIf { it.isNotEmpty() }, title = o.jS("title") ?: "",
            subtitle = o.jS("subtitle")?.takeIf { it.isNotEmpty() }, artwork = o.jS("artwork")?.takeIf { it.isNotEmpty() },
            type = type, channelID = o.jS("channelID")?.takeIf { it.isNotEmpty() },
            date = time(o["date"]) ?: time(o["at"]) ?: return null, profileID = id,
        )
    }

    private fun serverState(id: String, o: JsonObject): PState {
        val removedObj = o["removed"] as? JsonObject
        // Verwijderingen per veld: `removed.<veld>` plus de oudere iOS-velden `<veld>Removed`
        // (`progressRemoved`, `historyRemoved`, …) van Apple-builds vóór 60; per sleutel wint de nieuwste tijd.
        fun removedFor(f: String): JsonObject? {
            val a = removedObj?.get(f) as? JsonObject
            val b = o[f + "Removed"] as? JsonObject
            if (a == null || b == null) return a ?: b
            val out = a.toMutableMap()
            b.forEach { (k, v) -> val c = out[k]; if (c == null || (time(v) ?: 0L) > (time(c) ?: 0L)) out[k] = v }
            return JsonObject(out)
        }
        val sets = setFields.associateWith { f -> parseSetField(o[f], removedFor(f)) }
        val progress = HashMap<String, Item<ProgressEntry>>()
        (o["progress"] as? JsonObject)?.forEach { (k, v) ->
            val p = v as? JsonObject ?: return@forEach
            if (p.jB("removed") == true || p.jB("deleted") == true) {
                progress[k] = Item(time(p["updatedAt"]) ?: 0L, null)
            } else parseProgress(id, p)?.let { progress[k] = Item(it.updated, it) }
        }
        removedFor("progress")?.forEach { (k, v) ->
            val at = time(v) ?: 0L
            val c = progress[k]
            if (c == null || at > c.at) progress[k] = Item(at, null)
        }
        val history = HashMap<String, Item<HistoryEntry>>()
        (o["history"] as? JsonArray)?.forEach { v ->
            val h = (v as? JsonObject)?.let { parseHistory(id, it) } ?: return@forEach
            val c = history[h.id]
            if (c == null || h.date > c.at) history[h.id] = Item(h.date, h)
        }
        removedFor("history")?.forEach { (k, v) ->
            val at = time(v) ?: 0L
            val c = history[k]
            if (c == null || at >= c.at) history[k] = Item(at, null)
        }
        val prefs = (o["prefs"] as? JsonObject)?.let { p ->
            fun s(k: String) = (p[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            Item(time(p["updatedAt"]) ?: time(o["prefsUpdatedAt"]) ?: 0L, KijkPrefs(
                s("autoSubtitles")?.let { v -> TrackPreferences.normalizedMode(v).takeIf { v != "false" && v != "0" } }, s("subtitleLanguage"),
                s("audioLanguage"), s("streamSubtitleLanguage"),
            ))
        }
        val pin = if (!o.containsKey("pin")) null else {
            val v = o["pin"]
            val value = when (v) {
                is JsonObject -> (v.jS("pin") ?: v.jS("value") ?: v.jS("code"))?.takeIf { it.isNotEmpty() }
                else -> v.jFlex?.takeIf { it.isNotEmpty() }
            }
            val at = time(o["pinUpdatedAt"]) ?: (v as? JsonObject)?.let { time(it["updatedAt"]) } ?: 0L
            Item(at, value)
        }
        return PState(sets, progress, history, prefs, pin, parseHomeLayout(o["homeLayout"]))
    }

    private fun <T> mergeItems(local: Map<String, Item<T>>, server: Map<String, Item<T>>): Map<String, Item<T>> {
        val out = HashMap(local)
        for ((k, v) in server) { val l = out[k]; if (l == null || v.at > l.at) out[k] = v }
        return out
    }

    private fun <T> newest(local: Item<T>?, server: Item<T>?): Item<T>? = when {
        local == null -> server
        server == null -> local
        server.at > local.at -> server
        else -> local
    }

    /** Opschonen: oude verwijderingen weg, en de maximale lengtes (20 recente zenders, 300 voortgang, 500 geschiedenis). */
    private fun normalize(s: PState): PState {
        val cutoff = System.currentTimeMillis() - REMOVED_DAYS * 24 * 3_600_000L
        fun <T> prune(m: Map<String, Item<T>>) = m.filter { (_, v) -> v.value != null || v.at >= cutoff }
        val sets = s.sets.mapValues { (f, m) ->
            val p = prune(m)
            if (f != "recentChannels") p else {
                val keep = p.filter { it.value.value != null }.entries.sortedByDescending { it.value.at }.take(MAX_RECENT).map { it.key }.toSet()
                p.filter { it.value.value == null || it.key in keep }
            }
        }
        val prog = prune(s.progress).let { p ->
            val keep = p.filter { it.value.value != null }.entries.sortedByDescending { it.value.at }.take(MAX_PROGRESS).map { it.key }.toSet()
            p.filter { it.value.value == null || it.key in keep }
        }
        val hist = prune(s.history).let { p ->
            val keep = p.filter { it.value.value != null }.entries.sortedByDescending { it.value.at }.take(MAX_HISTORY).map { it.key }.toSet()
            p.filter { it.value.value == null || it.key in keep }
        }
        return s.copy(sets = sets, progress = prog, history = hist)
    }

    private fun profileJson(base: JsonObject?, s: PState): JsonObject {
        val now = System.currentTimeMillis()
        val out = (base ?: JsonObject(emptyMap())).toMutableMap()
        val removed = linkedMapOf<String, JsonElement>()
        for (f in setFields) {
            val m = s.sets[f] ?: emptyMap()
            out[f] = JsonArray(m.filter { it.value.value != null }.entries.sortedWith(compareByDescending<Map.Entry<String, Item<Boolean>>> { it.value.at }.thenBy { it.key })
                .map { JsonObject(mapOf("id" to JsonPrimitive(it.key), "at" to JsonPrimitive(iso(it.value.at)))) })
            removed[f] = JsonObject(m.filter { it.value.value == null }.toSortedMap().mapValues { JsonPrimitive(iso(it.value.at)) })
        }
        out["progress"] = JsonObject(s.progress.filter { it.value.value != null }.toSortedMap().mapValues { (_, item) ->
            val e = item.value!!
            JsonObject(buildMap {
                put("position", JsonPrimitive(e.position))
                put("duration", JsonPrimitive(if (e.fraction > 0) e.position / e.fraction else 0.0))
                put("fraction", JsonPrimitive(e.fraction))
                put("updatedAt", JsonPrimitive(iso(e.updated)))
                put("title", JsonPrimitive(e.title))
                e.artwork?.let { a -> put("artwork", JsonPrimitive(a)) }
                e.playlistID?.let { p -> put("playlistID", JsonPrimitive(p)) }
            })
        })
        removed["progress"] = JsonObject(s.progress.filter { it.value.value == null }.toSortedMap().mapValues { JsonPrimitive(iso(it.value.at)) })
        out["history"] = JsonArray(s.history.values.mapNotNull { it.value }.sortedByDescending { it.date }.map { e ->
            JsonObject(buildMap {
                put("id", JsonPrimitive(e.id))
                put("itemID", JsonPrimitive(e.itemID))
                e.playlistID?.let { put("playlistID", JsonPrimitive(it)) }
                put("title", JsonPrimitive(e.title))
                e.subtitle?.let { put("subtitle", JsonPrimitive(it)) }
                e.artwork?.let { put("artwork", JsonPrimitive(it)) }
                put("type", JsonPrimitive(e.type.name.lowercase()))
                e.channelID?.let { put("channelID", JsonPrimitive(it)) }
                // `at` en `date` zijn dezelfde tijd (iOS las `at`, Android/Tizen `date`): schrijf allebei
                put("at", JsonPrimitive(iso(e.date)))
                put("date", JsonPrimitive(iso(e.date)))
            })
        })
        removed["history"] = JsonObject(s.history.filter { it.value.value == null }.toSortedMap().mapValues { JsonPrimitive(iso(it.value.at)) })
        // onbekende sleutels in `removed` (van nieuwere apps) blijven staan
        out["removed"] = JsonObject(((base?.get("removed") as? JsonObject) ?: JsonObject(emptyMap())) + removed)
        s.prefs?.let { p ->
            val v = p.value ?: KijkPrefs(null, null, null, null)
            fun j(x: String?) = x?.let { JsonPrimitive(it) } ?: JsonNull
            out["prefs"] = JsonObject(mapOf(
                "autoSubtitles" to j(v.autoSubtitles),
                "subtitleLanguage" to j(v.subtitleLanguage),
                "audioLanguage" to j(v.audioLanguage),
                "streamSubtitleLanguage" to j(v.streamSubtitleLanguage),
                "updatedAt" to JsonPrimitive(iso(p.at)),
            ))
        }
        s.pin?.let { p ->
            out["pin"] = p.value?.let { JsonPrimitive(it) } ?: JsonNull
            out["pinUpdatedAt"] = JsonPrimitive(iso(p.at))
        }
        s.home?.value?.let { h ->
            out["homeLayout"] = JsonObject(mapOf(
                "rows" to JsonArray(h.rows.map { r -> JsonObject(mapOf("key" to JsonPrimitive(r.key), "visible" to JsonPrimitive(r.visible))) }),
                "updatedAt" to JsonPrimitive(iso(h.updatedAt)),
            ))
        }
        out["updatedAt"] = JsonPrimitive(iso(now))
        return JsonObject(out)
    }

    /** Alle profielen: serveronderdelen (hoofdletterongevoelig) samenvoegen met de lokale gegevens. */
    private suspend fun mergeProfiles(all: Map<String, Account.StateSection>) {
        val byId = all.entries.filter { it.key.startsWith(PROFILE) }.groupBy { it.key.removePrefix(PROFILE).lowercase() }
        // metabestanden van profielen die hier niet meer bestaan
        val ids = Store.profiles.map { it.id.lowercase() }.toSet()
        runCatching { metaDir.listFiles()?.forEach { f -> if (f.nameWithoutExtension !in ids) f.delete() } }
        for (p in Store.profiles.toList()) {
            val list = byId[p.id.lowercase()]?.map { it.key to it.value } ?: emptyList()
            try { mergeProfile(p.id, list) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }

    /**
     * Eén profiel samenvoegen (zie de regels bovenaan) en lokaal toepassen. Upload als het resultaat afwijkt van de
     * server. true = gelukt.
     */
    private suspend fun mergeProfile(id: String, sections: List<Pair<String, Account.StateSection>>): Boolean {
        val key = id.lowercase()
        // voorkeur: de naam die de server al heeft (exact, anders een andere schrijfwijze)
        val name = sections.firstOrNull { it.first == "$PROFILE$id" }?.first ?: sections.firstOrNull()?.first
            ?: sectionNames[key] ?: "$PROFILE$id"
        sectionNames[key] = name
        val meta = recordLocal(id)
        val local = localState(id, meta)
        var server: PState? = null
        var base: JsonObject? = null
        for ((_, sec) in sections) {
            val o = sec.data as? JsonObject ?: continue
            if (base == null) base = o
            val s = serverState(id, o)
            server = server?.let { a ->
                PState(
                    setFields.associateWith { f -> mergeItems(a.sets[f] ?: emptyMap(), s.sets[f] ?: emptyMap()) },
                    mergeItems(a.progress, s.progress), mergeItems(a.history, s.history),
                    newest(a.prefs, s.prefs), newest(a.pin, s.pin), newest(a.home, s.home),
                )
            } ?: s
        }
        val srv = server?.let { normalize(it) }
        val merged0 = PState(
            setFields.associateWith { f -> mergeItems(local.sets[f] ?: emptyMap(), srv?.sets?.get(f) ?: emptyMap()) },
            mergeItems(local.progress, srv?.progress ?: emptyMap()),
            mergeItems(local.history, srv?.history ?: emptyMap()),
            newest(local.prefs, srv?.prefs), newest(local.pin, srv?.pin), newest(local.home, srv?.home),
        )
        // van vóór build 44 (tijd -1) en de server heeft niets: nu stempelen
        val now = System.currentTimeMillis()
        val merged = normalize(merged0.copy(
            prefs = merged0.prefs?.let { if (it.at < 0) it.copy(at = now) else it },
            pin = merged0.pin?.let { if (it.at < 0) it.copy(at = now) else it },
        ))

        // lokaal toepassen
        val sets = merged.sets
        fun present(f: String) = (sets[f] ?: emptyMap()).filter { it.value.value != null }
        val data = Store.ProfileData(
            favorites = present("favorites").keys,
            myList = present("myList").keys,
            progress = merged.progress.mapNotNull { (k, v) -> v.value?.let { k to it } }.toMap(),
            watched = present("watched").keys,
            recent = present("recentChannels").entries.sortedByDescending { it.value.at }.map { it.key },
            history = merged.history.values.mapNotNull { it.value }.sortedByDescending { it.date },
        )
        withContext(Dispatchers.Main) {
            if (Store.profiles.any { it.id == id }) applyingChanges {
                val cur = Store.profileData(id)
                // profiel-id in de items houden zoals lokaal (oude items zonder profiel horen bij het hoofdaccount)
                val curProgress = cur.progress
                val curHistory = cur.history.associateBy { it.id }
                val fixed = data.copy(
                    progress = data.progress.mapValues { (k, e) -> curProgress[k]?.takeIf { it.copy(profileID = id) == e } ?: e },
                    history = data.history.map { e -> curHistory[e.id]?.takeIf { it.copy(profileID = id) == e } ?: e },
                )
                Store.applySyncedProfileData(id, fixed)
                val prof = Store.profiles.firstOrNull { it.id == id }
                val mp = merged.prefs?.value
                if (merged.prefs != null && mp != null && prefsOf(prof) != mp) {
                    Store.applySyncedPrefs(id, mp.autoSubtitles, mp.subtitleLanguage, mp.audioLanguage, mp.streamSubtitleLanguage)
                }
                merged.pin?.let { p -> if (ParentalControl.storedPIN(id) != p.value) ParentalControl.applySyncedPIN(p.value, id) }
                merged.home?.value?.let { h -> Store.applySyncedHomeLayout(id, h) }
                Unit
            }
        }
        // metabestand bijwerken naar het resultaat
        writeMeta(id, Meta(
            present = (setFields.associateWith { f -> present(f).mapValues { it.value.at } } +
                mapOf("progress" to merged.progress.filter { it.value.value != null }.mapValues { it.value.at },
                    "history" to merged.history.filter { it.value.value != null }.mapValues { it.value.at })),
            removed = (setFields.associateWith { f -> (sets[f] ?: emptyMap()).filter { it.value.value == null }.mapValues { it.value.at } } +
                mapOf("progress" to merged.progress.filter { it.value.value == null }.mapValues { it.value.at },
                    "history" to merged.history.filter { it.value.value == null }.mapValues { it.value.at })),
            prefsAt = merged.prefs?.at,
            pinAt = merged.pin?.at,
        ))

        val empty = merged.sets.values.all { it.isEmpty() } && merged.progress.isEmpty() && merged.history.isEmpty() &&
            merged.prefs == null && merged.pin == null && merged.home == null
        if (srv == null && empty) return true
        if (srv == merged) return true
        val json = profileJson(base, merged)
        var ok = Account.putState(name, json) != null
        // andere schrijfwijze van hetzelfde profiel-id (iOS: hoofdletters) ook bijwerken, zodat elk platform alles ziet
        for (other in sections.map { it.first }.filter { it != name }) {
            if (Account.putState(other, json) == null) ok = false
        }
        return ok
    }
}
