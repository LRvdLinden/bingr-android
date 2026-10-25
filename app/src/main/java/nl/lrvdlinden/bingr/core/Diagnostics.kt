package nl.lrvdlinden.bingr

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Build 146: diagnose-bijlage (schema 1, docs/ACCOUNT.md → "Diagnose-bijlage"): de startdiagnose (`start`) en de
 * app-diagnose (`app`). Geen persoonsgegevens: geen e-mail, namen (ook niet de apparaatnaam), gebruikersnamen,
 * wachtwoorden, tokens, URL's of servernamen, IP-/MAC-adressen, titels of zenders.
 *
 * Build 147: schema 2 (docs/ACCOUNT.md → "Build 147"). Elke bijlage (`start` en `app`) heeft altijd de startdiagnose:
 * `start` (samenvatting goed/traag/vastgelopen, app-tijd, wachten op de gebruiker, de vorige start met zijn probleem),
 * `fetch` (per server s1, s2, … en soort: geleverd, leeg, mislukt, verborgen), `network.type` en `storage` (één totaal
 * met cache, catalogus en downloads die samen optellen, plus vrij op het apparaat).
 */
object Diagnostics {
    private val ctx: Context get() = AppState.appContext

    val platform: String get() = if (AppState.isTV) "androidtv" else "android"

    val deviceType: String
        get() = Build146Logic.Diag.deviceType(AppState.isTV,
            runCatching { ctx.resources.configuration.smallestScreenWidthDp }.getOrDefault(0))

    /** `Build.MODEL` ("SM-S921B", "AFTKA"), zoals het contract vraagt. */
    val model: String get() = (Build.MODEL ?: "").trim().take(80)
    val manufacturer: String get() = (Build.MANUFACTURER ?: "").trim().take(40)

    /** Leesbaar voor het scherm: "Samsung SM-S921B" (merk niet dubbel). */
    val modelLabel: String
        get() {
            val m = model; val b = manufacturer.replaceFirstChar { it.titlecase(Locale.ROOT) }
            return when {
                b.isEmpty() -> m
                m.startsWith(b, ignoreCase = true) -> m
                else -> "$b $m"
            }.ifEmpty { "Android" }
        }

    fun deviceTypeLabel(t: String = deviceType): String = when (t) {
        "tv" -> L("TV"); "tablet" -> L("Tablet"); "phone" -> L("Phone"); "desktop" -> L("Desktop"); else -> L("Browser")
    }

    val osName: String get() = if (AppState.isTV) "Android TV" else "Android"
    val osVersion: String get() = (Build.VERSION.RELEASE ?: "").take(30)

    /** Taal als BCP 47: de app-taal met het land van het systeem ("nl-NL"). */
    val language: String
        get() {
            val lang = Localizer.language
            val country = Locale.getDefault().country
            return if (lang.contains('-') || country.isNullOrEmpty()) lang else "$lang-$country"
        }

    val screen: String
        get() = runCatching {
            val dm = ctx.resources.displayMetrics
            "${maxOf(dm.widthPixels, dm.heightPixels)}x${minOf(dm.widthPixels, dm.heightPixels)}"
        }.getOrDefault("")

    val engine: String
        get() = when (Prefs.string("player.engine") ?: "auto") { "vlc" -> "vlc"; "apple" -> "exo"; else -> "auto" }

    fun iso(ms: Long): String = Build149Logic.Dates.formatIso(ms)  // build 149: geen opmaker per aanroep

    // MARK: onderdelen

    private fun appJson() = buildJsonObject {
        put("platform", platform)
        put("version", BuildConfig.VERSION_NAME.take(20))
        put("build", BuildConfig.VERSION_CODE)
        put("engine", engine)
        put("lang", appLang)
    }

    /** Build 148: de app-taal (`app.lang`), niet die van het systeem. */
    val appLang: String get() = Localizer.language.takeIf { Regex("^[a-z]{2,3}(-[A-Za-z0-9]{2,8})?$").matches(it) && it.length <= 12 } ?: "en"

    private fun deviceJson() = buildJsonObject {
        put("type", deviceType)
        if (model.isNotEmpty()) put("model", model)
        if (manufacturer.isNotEmpty()) put("manufacturer", manufacturer)
        put("os", osName)
        put("osVersion", osVersion)
        put("language", language)
        if (screen.isNotEmpty()) put("screen", screen)
    }

    data class Memory(val usedMB: Int, val totalMB: Int, val appMB: Int, val low: Boolean)

    fun memory(): Memory {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo()
        runCatching { am?.getMemoryInfo(mi) }
        val total = (mi.totalMem / 1_048_576L).toInt()
        val used = ((mi.totalMem - mi.availMem) / 1_048_576L).toInt().coerceAtLeast(0)
        return Memory(used, total, StartDiagnostics.appMB(), mi.lowMemory)
    }

    private fun memoryJson(m: Memory = memory()) = buildJsonObject {
        put("usedMB", m.usedMB); put("totalMB", m.totalMB); put("appMB", m.appMB); put("lowMemory", m.low)
    }

    /** Build 148 (schema 3): mijlpalen als code (elke code één keer), de oude naam mag mee. */
    private fun marksJson(marks: List<Build146Logic.Diag.Mark>) =
        JsonArray(Build148Logic.Codes.dedupe(marks.take(Build146Logic.Diag.MAX_MARKS)) { it.name }.map { (code, m) ->
            buildJsonObject { put("code", code); put("name", m.name); put("ms", m.ms); put("ok", true) }
        })

    private fun crashJson(c: Build146Logic.Diag.Crash) = buildJsonObject {
        put("at", iso(c.at)); put("type", c.type.take(80)); put("reason", c.reason.take(300)); put("build", c.build)
        put("stack", JsonArray(c.stack.take(50).map { JsonPrimitive(it.take(200)) }))
    }

    // MARK: netwerk en opslag

    data class Network(val online: Boolean, val type: String, val vpn: Boolean, val backend: String, val backendMs: Long?,
                       val provider: String, val providerMs: Long?)

    private fun connection(): Triple<Boolean, String, Boolean> = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching Triple(false, "none", false)
        // build 147: wifi, cellular of ethernet (een VPN erboven: het onderliggende transport); anders wat de bewaking zag
        val type = Build147Logic.NetType.of(
            ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
        ).let { if (it == "unknown") StartDiagnostics.netType else it }
        Triple(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET), type, caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
    }.getOrDefault(Triple(false, StartDiagnostics.netType, false))

    /** Build 147: soort verbinding voor `network.type` (`wifi`, `cellular`, `ethernet` of `unknown`). */
    fun networkType(): String = connection().second.let { if (it in setOf("wifi", "cellular", "ethernet")) it else "unknown" }

    /**
     * Korte netwerkcheck: Bingr-server (device-auth `ping`) en de eigen IPTV-server (alleen het adres van de server, een
     * kleine GET (build 149; was HEAD); niets van de aanvraag of het antwoord wordt bewaard, gelogd of meegestuurd).
     */
    suspend fun network(): Network {
        val (online, type, vpn) = connection()
        val backendMs = runCatching { Account.pingBackend() }.getOrNull()
        val src = Playlists.source
        val base = src?.let { s -> (s.serverURL.ifBlank { s.m3uURL }).trim() }?.takeIf { it.startsWith("http", ignoreCase = true) }
        var providerMs: Long? = null
        var providerOk = false
        if (base != null) {
            val origin = runCatching { java.net.URI(base).let { u -> "${u.scheme}://${u.authority}/" } }.getOrNull()
            if (origin != null) {
                val t0 = android.os.SystemClock.elapsedRealtime()
                providerOk = withContext(Dispatchers.IO) {
                    runCatching {
                        // build 149: kleine GET in plaats van HEAD (veel IPTV-servers weigeren HEAD); bij Xtream de
                        // login van player_api.php. Hooguit 4 KB van het antwoord wordt gelezen en meteen weggegooid.
                        val url = if (src != null && src.kind == PlaylistKind.XTREAM)
                            Net.url(Net.normalizedServer(src.serverURL) + "/player_api.php",
                                listOf("username" to src.username, "password" to (Store.secret(src.passwordKey) ?: ""))) ?: origin
                        else origin
                        val req = okhttp3.Request.Builder().url(url).get().header("User-Agent", Http.USER_AGENT)
                            .header("Range", "bytes=0-4095").build()
                        Http.client.newBuilder().callTimeout(6, java.util.concurrent.TimeUnit.SECONDS).build()
                            .newCall(req).execute().use { r -> runCatching { r.body?.source()?.request(4096) }; true }
                    }.getOrDefault(false)
                }
                providerMs = android.os.SystemClock.elapsedRealtime() - t0
            }
        }
        return Network(
            online, type, vpn,
            backend = if (!Account.configured) "skipped" else Build146Logic.Diag.speed(backendMs, backendMs != null), backendMs = backendMs,
            provider = if (base == null) "skipped" else Build146Logic.Diag.speed(providerMs, providerOk), providerMs = providerMs.takeIf { providerOk },
        )
    }

    /** Build 147: [usedMB] = cache + catalogus ([dbMB]: catalogi en app-gegevens) + downloads, precies opgeteld. */
    data class Storage(val usedMB: Int, val freeMB: Int, val cacheMB: Int, val dbMB: Int, val downloadsMB: Int)

    private fun dirBytes(f: File?, budget: IntArray): Long {
        if (f == null || !f.exists() || budget[0] <= 0) return 0L
        budget[0]--
        if (f.isFile) return f.length()
        return f.listFiles()?.sumOf { dirBytes(it, budget) } ?: 0L
    }

    fun storage(): Storage {
        val budget = intArrayOf(20_000)
        val cache = dirBytes(ctx.cacheDir, budget) + dirBytes(ctx.externalCacheDir, budget)
        // catalogus = de bewaarde catalogi (files/servercache) en de andere app-gegevens (profielen, gids, databases)
        val files = dirBytes(ctx.filesDir, budget) + dirBytes(runCatching { ctx.getDatabasePath("x").parentFile }.getOrNull(), budget)
        val dl = dirBytes(ctx.getExternalFilesDir("downloads"), budget)
        val free = runCatching { StatFs(ctx.filesDir.path).availableBytes }.getOrNull()
        val p = Build147Logic.Storage.of(cache, files, dl, free)
        return Storage(p.totalMB, p.freeMB ?: 0, p.cacheMB, p.catalogMB, p.downloadsMB)
    }

    private fun storageJson(st: Storage) = buildJsonObject {
        put("totalMB", st.usedMB); put("cacheMB", st.cacheMB); put("catalogMB", st.dbMB); put("downloadsMB", st.downloadsMB)
        put("freeMB", st.freeMB)
    }

    // MARK: build 147: startdiagnose (deze start en de vorige)

    data class StartInfo(val summary: String, val timing: Build147Logic.Start.Timing, val maxHangMs: Long,
                         val previous: Build147Logic.Previous.Info?)

    private fun timingOf(marks: List<Build146Logic.Diag.Mark>) = Build147Logic.Start.timing(marks.map { it.name to it.ms })

    fun previousInfo(r: Build146Logic.Diag.Record? = StartDiagnostics.previous): Build147Logic.Previous.Info? {
        r ?: return null
        val last = r.marks.lastOrNull()
        return Build147Logic.Previous.of(
            crashed = r.crash != null, crashStack = r.crash?.stack ?: emptyList(), finished = r.finished,
            lastMark = last?.name, lastMs = last?.ms ?: 0L,
            hangMs = r.hangMs, hangFg = r.hangFg, hangAt = r.hangAt, hangTask = r.hangTask, hangStack = r.hangStack,
        )
    }

    /** Deze start: samenvatting, app-tijd en wachten op de gebruiker; plus de vorige start. */
    fun startInfo(): StartInfo {
        val t = timingOf(StartDiagnostics.marks())
        val hang = StartDiagnostics.maxHangMs()
        return StartInfo(Build147Logic.Start.summary(t.appMs, hang, crashed = false), t, hang, previousInfo())
    }

    private fun previousJson(p: Build147Logic.Previous.Info) = buildJsonObject {
        put("problem", p.problem)
        Build148Logic.Codes.of(p.milestone)?.let { put("milestone", it) }
        put("seconds", p.seconds)
        put("foreground", p.foreground)
        Build148Logic.Codes.of(p.task)?.let { put("task", it) }
        put("stack", JsonArray(p.stack.take(50).map { JsonPrimitive(it.take(200)) }))
    }

    private fun startJson(i: StartInfo = startInfo()) = buildJsonObject {
        put("summary", i.summary)
        put("appMs", i.timing.appMs)
        put("userWaitMs", i.timing.userWaitMs)
        put("finished", i.timing.finished)
        if (i.maxHangMs > 0) { put("hangMs", i.maxHangMs); put("maxHangMs", i.maxHangMs) }
        // build 148: langste hang van deze start (duur, taak, mijlpaal, voorgrond, stack)
        StartDiagnostics.longestHang()?.let { h ->
            put("longestHang", buildJsonObject {
                put("ms", h.ms.coerceIn(0L, 3_600_000L))
                Build148Logic.Codes.of(h.task)?.let { put("task", it) }
                Build148Logic.Codes.of(h.milestone)?.let { put("milestone", it) }
                put("foreground", h.foreground)
                put("stack", JsonArray(h.stack.take(30).map { JsonPrimitive(it.take(200)) }))
            })
        }
        i.previous?.let { put("previous", previousJson(it)) }
    }

    // MARK: build 147: per server en soort

    fun fetchRows(): List<Build147Logic.Fetch.Row> {
        val active = Playlists.source?.id
        val raw = Playlists.rawLibrary
        val lib = Playlists.library
        return Store.playlists.flatMapIndexed { i, p ->
            val log = FetchLog.get(p.id)
            val isActive = p.id.equals(active, ignoreCase = true) && !raw.isEmpty
            Build147Logic.Fetch.KINDS.map { kind ->
                val e = log?.get(kind)
                val rawN = if (isActive) when (kind) { "live" -> raw.channels.size; "movie" -> raw.movies.size; else -> raw.series.size } else e?.first
                val vis = if (isActive && !Playlists.isFiltering) when (kind) { "live" -> lib.channels.size; "movie" -> lib.movies.size; else -> lib.series.size } else null
                val err = e?.second
                val st = Build147Logic.Fetch.status(rawN, vis, err)
                Build147Logic.Fetch.Row(Build147Logic.Fetch.serverName(i), kind, st, rawN ?: 0, err.takeIf { st == "failed" })
            }
        }
    }

    /**
     * Build 148: per soort van de open server: geleverd, zichtbaar en waarom verborgen (categorieën, kinderfilter, dubbel,
     * overig). Op de achtergrond (tienduizenden titels); andere servers alleen "geleverd" (uit [FetchLog]).
     */
    suspend fun visibility(): Map<String, Build148Logic.Hidden.Counts> {
        val src = Playlists.source ?: return emptyMap()
        val raw = Playlists.rawLibrary
        val lib = Playlists.library
        if (raw.isEmpty || Playlists.isFiltering) return emptyMap()
        val ctx = Playlists.context(src)
        val c = ctx.custom
        return withContext(Dispatchers.Default) {
            val kidsOn = ctx.kids && !ctx.unlocked
            fun hiddenCat(g: String) = g in ctx.hiddenCategories || (c.merged[g] ?: g) in ctx.hiddenCategories
            fun notKids(g: String) = kidsOn && !Build140Logic.Kids.isKidsCategory(g)
            val H = Build148Logic.Hidden
            mapOf(
                // build 149: plus wat niet is ingelezen omdat de categorie verborgen was (geleverd én verborgen)
                "live" to plusSkipped(H.count(raw.channels, lib.channels.size, { it.group }, ::hiddenCat, ::notKids), raw.hiddenCount("live")),
                "movie" to plusSkipped(H.count(raw.movies, lib.movies.size, { it.group }, ::hiddenCat, ::notKids), raw.hiddenCount("movie")),
                "series" to plusSkipped(H.count(raw.series, lib.series.size, { it.group }, ::hiddenCat, ::notKids), raw.hiddenCount("series")),
            )
        }
    }

    private fun plusSkipped(c: Build148Logic.Hidden.Counts, n: Int): Build148Logic.Hidden.Counts =
        if (n <= 0) c else Build148Logic.Hidden.of(c.delivered + n, c.visible, c.categories + n, c.kids, c.duplicates)

    private suspend fun fetchJson(): JsonArray {
        val vis = runCatching { visibility() }.getOrDefault(emptyMap())
        val activeName = Store.playlists.indexOfFirst { it.id.equals(Playlists.source?.id, ignoreCase = true) }
            .takeIf { it >= 0 }?.let { Build147Logic.Fetch.serverName(it) }
        return JsonArray(fetchRows().map { r ->
            buildJsonObject {
                put("server", r.server); put("kind", r.kind); put("status", r.status); put("count", r.count)
                r.error?.let { put("error", it) }
                val v = if (r.server == activeName) vis[r.kind] else null
                if (v != null) {
                    put("delivered", v.delivered); put("visible", v.visible)
                    put("hidden", buildJsonObject {
                        put("categories", v.categories); put("kids", v.kids); put("duplicates", v.duplicates); put("other", v.other)
                    })
                } else if (r.status != "failed") put("delivered", r.count)
            }
        })
    }

    // MARK: de bijlage

    /** Per server (zonder naam of URL): soort en wanneer de lijst het laatst is bijgewerkt (0 = nog nooit). */
    fun serverUpdates(): List<Pair<String, Long>> = Store.playlists.map { it.kind.raw to Playlists.cacheStamp(it) }

    /** App-diagnose (op verzoek): toestand, netwerkcheck, opslag en geheugen. */
    suspend fun app(): JsonObject {
        val net = network()
        val st = withContext(Dispatchers.IO) { storage() }
        val lib = Playlists.library
        val fetch = fetchJson()
        return buildJsonObject {
            put("schema", 3); put("kind", "app"); put("createdAt", iso(System.currentTimeMillis()))
            put("app", appJson()); put("device", deviceJson())
            put("milestones", marksJson(StartDiagnostics.marks()))
            put("memory", memoryJson())
            put("state", buildJsonObject {
                put("signedIn", Account.isSignedIn)
                put("profiles", Store.profiles.size)
                put("servers", Store.playlists.size)
                put("serverKinds", JsonArray(Store.playlists.map { it.kind.raw }.distinct().map { JsonPrimitive(it) }))
                StateSync.lastSyncAt.takeIf { it > 0 }?.let { put("lastSync", iso(it)) }
                StateSync.lastSyncOk?.let { put("lastSyncOk", it) }
                Entitlements.current?.let { put("plan", if (it.isFree) "free" else "lifetime") }
                put("pushEnabled", Push.active)
                put("channels", lib.channels.size); put("movies", lib.movies.size); put("series", lib.series.size)
                put("uptimeS", StartDiagnostics.uptimeMs() / 1000)
                put("network", buildJsonObject {
                    put("online", net.online); put("type", net.type)
                    put("backend", net.backend); net.backendMs?.let { put("backendMs", it) }
                    put("provider", net.provider); net.providerMs?.let { put("providerMs", it) }
                    put("vpn", net.vpn)
                })
                put("storage", buildJsonObject {
                    put("usedMB", st.usedMB); put("freeMB", st.freeMB); put("cacheMB", st.cacheMB); put("dbMB", st.dbMB)
                })
            })
            put("start", startJson())
            put("fetch", fetch)
            put("updates", UpdateLog.attachment())
            if (PlaybackStalls.list.isNotEmpty()) put("playback", PlaybackStalls.attachment())
            put("network", buildJsonObject { put("type", if (net.type in setOf("wifi", "cellular", "ethernet")) net.type else "unknown") })
            put("storage", storageJson(st))
        }
    }

    /** Startdiagnose: hoe de vorige start verliep (mijlpalen, geheugen, crash); build 147 ook deze start, servers en opslag. */
    suspend fun start(p: Build146Logic.Diag.Problem): JsonObject {
        val st = withContext(Dispatchers.IO) { storage() }
        val fetch = fetchJson()
        return startOf(p, st, fetch)
    }

    private fun startOf(p: Build146Logic.Diag.Problem, st: Storage, fetch: JsonArray): JsonObject = buildJsonObject {
        put("schema", 3); put("kind", "start"); put("createdAt", iso(p.at.takeIf { it > 0 } ?: System.currentTimeMillis()))
        put("app", buildJsonObject {
            put("platform", platform); put("version", BuildConfig.VERSION_NAME.take(20)); put("build", p.build); put("engine", engine)
            put("lang", appLang)
        })
        put("device", deviceJson())
        put("milestones", marksJson(p.marks))
        put("memory", memoryJson(memory().copy(appMB = p.peakMB ?: 0)))
        p.crash?.let { put("crash", crashJson(it)) }
        put("state", buildJsonObject {
            put("signedIn", Account.isSignedIn); put("profiles", Store.profiles.size); put("servers", Store.playlists.size)
            put("uptimeS", p.ms / 1000)
        })
        put("start", startJson())
        put("fetch", fetch)
        put("updates", UpdateLog.attachment())
        if (PlaybackStalls.list.isNotEmpty()) put("playback", PlaybackStalls.attachment())
        put("network", buildJsonObject { put("type", networkType()) })
        put("storage", storageJson(st))
    }

    /** Leesbaar voor "Bekijken" (zonder iets weg te laten van wat er meegaat). */
    fun pretty(o: JsonObject): String = prettyJson.encodeToString(JsonObject.serializer(), o)

    private val prettyJson = kotlinx.serialization.json.Json { prettyPrint = true }
}

/**
 * Build 147: wat elke server bij het laatste ophalen per soort leverde (aantal) of waarom niet (korte foutcode), bewaard
 * per apparaat (geen namen of URL's; sleutel = id van de server in kleine letters).
 */
object FetchLog {
    private const val KEY = "diag.fetch.v147"
    private val lock = Any()

    private fun all(): MutableMap<String, JsonObject> = runCatching {
        (Prefs.string(KEY)?.let { Http.json.parseToJsonElement(it) } as? JsonObject)?.toMutableMap()
            ?.mapValues { it.value as? JsonObject ?: JsonObject(emptyMap()) }?.toMutableMap()
    }.getOrNull() ?: HashMap()

    private fun save(m: Map<String, JsonObject>) { runCatching { Prefs.put(KEY, JsonObject(m).toString()) } }

    private fun entry(n: Int, err: String?) = buildJsonObject { put("n", n); err?.let { put("err", it) } }

    fun delivered(serverID: String, live: Int, movies: Int, series: Int, errors: Map<String, String>) = synchronized(lock) {
        val m = all()
        val ids = Store.playlists.map { it.id.lowercase(Locale.ROOT) }.toSet()
        m.keys.retainAll(ids + serverID.lowercase(Locale.ROOT))
        m[serverID.lowercase(Locale.ROOT)] = buildJsonObject {
            put("live", entry(live, errors["live"].takeIf { live == 0 }))
            put("movie", entry(movies, errors["movie"].takeIf { movies == 0 }))
            put("series", entry(series, errors["series"].takeIf { series == 0 }))
            put("at", System.currentTimeMillis())
        }
        save(m)
    }

    fun failed(serverID: String, code: String) = synchronized(lock) {
        val m = all()
        m[serverID.lowercase(Locale.ROOT)] = buildJsonObject {
            for (k in Build147Logic.Fetch.KINDS) put(k, entry(0, code))
            put("at", System.currentTimeMillis())
        }
        save(m)
    }

    /** soort → (aantal, foutcode) van het laatste ophalen, of null als het nog niet is vastgelegd. */
    fun get(serverID: String): Map<String, Pair<Int, String?>>? = synchronized(lock) {
        val o = all()[serverID.lowercase(Locale.ROOT)] ?: return null
        Build147Logic.Fetch.KINDS.associateWith { k -> val e = o[k] as? JsonObject; (e.jI("n") ?: 0) to e.jS("err") }
    }
}

/**
 * Build 148: de laatste 5 keer bijwerken van een server (`updates` in schema 3), met per fase (ophalen, inlezen,
 * indexeren, toepassen, bewaren) de duur en het geheugen aan het eind. Server geanonimiseerd (s1, s2, … op volgorde in de
 * app); bewaard per apparaat, geen namen of URL's.
 */
object UpdateLog {
    private const val KEY = "diag.updates.v148"
    private val lock = Any()

    class Rec(val rec: Build148Logic.Updates.Recorder)

    private fun now() = android.os.SystemClock.elapsedRealtime()

    fun begin(p: PlaylistSource): Rec {
        val i = Store.playlists.indexOfFirst { it.id.equals(p.id, ignoreCase = true) }.coerceAtLeast(0)
        return Rec(Build148Logic.Updates.Recorder(Build147Logic.Fetch.serverName(i), System.currentTimeMillis(), now(), StartDiagnostics.appMB()))
    }

    fun phase(r: Rec?, code: String) { r ?: return; synchronized(r) { r.rec.phase(code, now(), StartDiagnostics.appMB()) } }

    fun finish(r: Rec?, result: String) {
        r ?: return
        val u = synchronized(r) { r.rec.sample(StartDiagnostics.appMB()); r.rec.finish(result, now()) }
        synchronized(lock) {
            last = Build148Logic.Updates.push(all(), u)
            runCatching { Prefs.put(KEY, JsonArray(last!!.map { json(it) }).toString()) }
        }
    }

    @Volatile private var last: List<Build148Logic.Updates.Update>? = null

    fun all(): List<Build148Logic.Updates.Update> = last ?: synchronized(lock) {
        last ?: runCatching {
            (Prefs.string(KEY)?.let { Http.json.parseToJsonElement(it) } as? JsonArray)?.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                Build148Logic.Updates.Update(o.jS("server") ?: return@mapNotNull null, o.jL("atMs") ?: 0L,
                    o.jList("phases").mapNotNull { f -> f.jS("code")?.let { Build148Logic.Updates.Phase(it, f.jL("ms") ?: 0L, f.jI("memMB") ?: 0) } },
                    o.jL("totalMs") ?: 0L, o.jI("peakMB") ?: 0, o.jS("result") ?: "ok")
            }
        }.getOrNull().orEmpty().also { last = it }
    }

    private fun json(u: Build148Logic.Updates.Update) = buildJsonObject {
        put("server", u.server); put("atMs", u.at); put("totalMs", u.totalMs); put("peakMB", u.peakMB); put("result", u.result)
        put("phases", JsonArray(u.phases.map { f -> buildJsonObject { put("code", f.code); put("ms", f.ms); put("memMB", f.memMB) } }))
    }

    /** Voor de bijlage (contract: `at` als ISO-8601). */
    fun attachment(): JsonArray = JsonArray(all().take(Build148Logic.Updates.MAX).map { u ->
        buildJsonObject {
            put("server", u.server); put("at", Diagnostics.iso(u.at))
            put("phases", JsonArray(u.phases.take(5).map { f -> buildJsonObject { put("code", f.code); put("ms", f.ms); put("memMB", f.memMB) } }))
            put("totalMs", u.totalMs); put("peakMB", u.peakMB); put("result", u.result)
        }
    })
}

/** Build 148: haperingen tijdens het afspelen (code `playback_stall`), de laatste 10 van dit proces. */
object PlaybackStalls {
    private val lock = Any()
    @Volatile var list: List<Build148Logic.Stalls.Stall> = emptyList(); private set
    fun add(s: Build148Logic.Stalls.Stall) = synchronized(lock) { list = Build148Logic.Stalls.push(list, s) }
    fun attachment(): JsonObject = buildJsonObject {
        put("code", "playback_stall")
        put("count", list.size)
        list.maxOfOrNull { it.ms }?.let { put("longestMs", it) }
        put("stalls", JsonArray(list.map { s -> buildJsonObject { put("ms", s.ms); put("atS", s.atS); put("live", s.live) } }))
    }
}
