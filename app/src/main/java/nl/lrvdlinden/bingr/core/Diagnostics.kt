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

    fun iso(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    // MARK: onderdelen

    private fun appJson() = buildJsonObject {
        put("platform", platform)
        put("version", BuildConfig.VERSION_NAME.take(20))
        put("build", BuildConfig.VERSION_CODE)
        put("engine", engine)
    }

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

    private fun marksJson(marks: List<Build146Logic.Diag.Mark>) = JsonArray(marks.take(Build146Logic.Diag.MAX_MARKS).map { m ->
        buildJsonObject { put("name", m.name); put("ms", m.ms); put("ok", true) }
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
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
        Triple(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET), type, caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
    }.getOrDefault(Triple(false, "unknown", false))

    /**
     * Korte netwerkcheck: Bingr-server (device-auth `ping`) en de eigen IPTV-server (alleen het adres van de server, een
     * HEAD-aanvraag; niets van de aanvraag of het antwoord wordt bewaard, gelogd of meegestuurd).
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
                        val req = okhttp3.Request.Builder().url(origin).head().header("User-Agent", Http.USER_AGENT).build()
                        Http.client.newBuilder().callTimeout(6, java.util.concurrent.TimeUnit.SECONDS).build()
                            .newCall(req).execute().use { true }
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

    data class Storage(val usedMB: Int, val freeMB: Int, val cacheMB: Int, val dbMB: Int, val downloadsMB: Int)

    private fun dirBytes(f: File?, budget: IntArray): Long {
        if (f == null || !f.exists() || budget[0] <= 0) return 0L
        budget[0]--
        if (f.isFile) return f.length()
        return f.listFiles()?.sumOf { dirBytes(it, budget) } ?: 0L
    }

    fun storage(): Storage {
        val budget = intArrayOf(20_000)
        val cache = dirBytes(ctx.cacheDir, budget)
        val files = dirBytes(ctx.filesDir, budget)
        val dl = dirBytes(ctx.getExternalFilesDir("downloads"), budget)
        val free = runCatching { StatFs(ctx.filesDir.path).availableBytes }.getOrDefault(0L)
        val mb = { b: Long -> (b / 1_048_576L).toInt() }
        return Storage(mb(cache + files + dl), mb(free), mb(cache), mb(files), mb(dl))
    }

    // MARK: de bijlage

    /** Per server (zonder naam of URL): soort en wanneer de lijst het laatst is bijgewerkt (0 = nog nooit). */
    fun serverUpdates(): List<Pair<String, Long>> = Store.playlists.map { it.kind.raw to Playlists.cacheStamp(it) }

    /** App-diagnose (op verzoek): toestand, netwerkcheck, opslag en geheugen. */
    suspend fun app(): JsonObject {
        val net = network()
        val st = withContext(Dispatchers.IO) { storage() }
        val lib = Playlists.library
        return buildJsonObject {
            put("schema", 1); put("kind", "app"); put("createdAt", iso(System.currentTimeMillis()))
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
        }
    }

    /** Startdiagnose: hoe de vorige start verliep (mijlpalen, geheugen, crash). */
    fun start(p: Build146Logic.Diag.Problem): JsonObject = buildJsonObject {
        put("schema", 1); put("kind", "start"); put("createdAt", iso(p.at.takeIf { it > 0 } ?: System.currentTimeMillis()))
        put("app", buildJsonObject {
            put("platform", platform); put("version", BuildConfig.VERSION_NAME.take(20)); put("build", p.build); put("engine", engine)
        })
        put("device", deviceJson())
        put("milestones", marksJson(p.marks))
        put("memory", memoryJson(memory().copy(appMB = p.peakMB ?: 0)))
        p.crash?.let { put("crash", crashJson(it)) }
        put("state", buildJsonObject {
            put("signedIn", Account.isSignedIn); put("profiles", Store.profiles.size); put("servers", Store.playlists.size)
            put("uptimeS", p.ms / 1000)
        })
    }

    /** Leesbaar voor "Bekijken" (zonder iets weg te laten van wat er meegaat). */
    fun pretty(o: JsonObject): String = prettyJson.encodeToString(JsonObject.serializer(), o)

    private val prettyJson = kotlinx.serialization.json.Json { prettyPrint = true }
}
