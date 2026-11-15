package nl.lrvdlinden.bingr

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Build 171: eigen crashrapporten, zonder derde partij (docs/ACCOUNT.md "Build 171", device-auth `crash_report`).
 *
 * - Een crash ([Thread.setDefaultUncaughtExceptionHandler]) wordt meteen (synchroon) in `files/crash/queue.json` gezet en
 *   bij de volgende start verstuurd.
 * - ANR's en crashes in native code komen via [ApplicationExitInfo] (Android 11+) bij de volgende start.
 * - Vóór het opslaan gaat alles wat persoonlijk kan zijn eruit ([Build171Logic.Crash.scrub]): URL's (IPTV-links bevatten
 *   gebruikersnaam en wachtwoord), IP-adressen, e-mailadressen en lange tokens. message ≤ 500 tekens, stack ≤ 16 KB.
 * - Hooguit 20 per uur; een server zonder `crash_report` (404) of geen netwerk: bewaren en later opnieuw, max. 7 dagen.
 * - Instelling "Crashrapporten delen" (Instellingen > Over > Privacy), standaard aan.
 */
object CrashReports {
    private const val PREF = "privacy.crashReports"
    private const val SEEN = "crash.exitSeenAt"
    private const val SENT = "crash.sentTimes"

    var enabled: Boolean
        get() = Prefs.bool(PREF, true)
        set(v) { Prefs.put(PREF, v); if (!v) runCatching { file().delete() } }

    @Serializable
    private data class Stored(
        val kind: String, val signature: String, val message: String, val stack: String,
        val at: Long, val count: Int = 1, val appVersion: String = "", val build: Int = 0,
    )

    private lateinit var dir: File
    private val lock = Mutex()
    private val ser = ListSerializer(Stored.serializer())

    private fun file() = File(dir, "queue.json")

    fun install(ctx: Context) {
        dir = File(ctx.filesDir, "crash").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { if (enabled) record("crash", e) }
            if (previous != null) previous.uncaughtException(t, e)
            else { android.os.Process.killProcess(android.os.Process.myPid()); kotlin.system.exitProcess(10) }
        }
    }

    private fun stackOf(e: Throwable): String {
        val sb = StringBuilder()
        var cur: Throwable? = e
        var depth = 0
        while (cur != null && depth < 5) {
            if (depth > 0) sb.append("Caused by: ")
            sb.append(cur.javaClass.name).append('\n')
            for (f in cur.stackTrace.take(40)) sb.append("\tat ").append(f.className).append('.').append(f.methodName)
                .append('(').append(f.fileName ?: "?").append(':').append(f.lineNumber).append(")\n")
            cur = cur.cause.takeIf { it !== cur }; depth++
        }
        return sb.toString()
    }

    /** Synchroon opslaan (het proces stopt meteen daarna). */
    private fun record(kind: String, e: Throwable) {
        val C = Build171Logic.Crash
        val stack = C.stack(stackOf(e))
        val r = Build171Logic.Crash.Report(kind, C.signature(kind, e.javaClass.name, stack),
            C.message("${e.javaClass.name}: ${e.message ?: ""}"), stack, System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME, build = BuildConfig.VERSION_CODE)
        store(C.enqueue(load(), r, r.at))
    }

    private fun load(): List<Build171Logic.Crash.Report> = runCatching {
        Http.json.decodeFromString(ser, file().readText()).map {
            Build171Logic.Crash.Report(it.kind, it.signature, it.message, it.stack, it.at, it.count, it.appVersion, it.build)
        }
    }.getOrDefault(emptyList())

    private fun store(list: List<Build171Logic.Crash.Report>) {
        runCatching {
            val tmp = File(dir, "queue.json.tmp")
            tmp.writeText(Http.json.encodeToString(ser, list.map { Stored(it.kind, it.signature, it.message, it.stack, it.at, it.count, it.appVersion, it.build) }))
            if (!tmp.renameTo(file())) { file().delete(); tmp.renameTo(file()) }
        }
    }

    /** ANR's en native crashes van vorige keren (Android 11+). */
    private fun collectExitInfo(ctx: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return
        val seen = Prefs.long(SEEN, 0L)
        val infos = runCatching { am.getHistoricalProcessExitReasons(ctx.packageName, 0, 10) }.getOrNull() ?: return
        var newest = seen
        var list = load()
        val C = Build171Logic.Crash
        for (i in infos) {
            if (i.timestamp <= seen) continue
            newest = maxOf(newest, i.timestamp)
            val kind = when (i.reason) {
                ApplicationExitInfo.REASON_ANR -> "anr"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash"
                else -> continue
            }
            val trace = runCatching {
                i.traceInputStream?.use { s -> val b = ByteArray(64 * 1024); val n = s.read(b); if (n > 0) String(b, 0, n) else "" }
            }.getOrNull() ?: ""
            // ANR-trace: alleen de main thread ("main" … tot de volgende lege regel)
            val main = trace.substringAfter("\"main\"", trace).substringBefore("\n\n")
            val stack = C.stack(main)
            val name = if (kind == "anr") "ANR" else "NativeCrash"
            list = C.enqueue(list, Build171Logic.Crash.Report(kind, C.signature(kind, name, stack),
                C.message("$name: ${i.description ?: ""}"), stack, i.timestamp,
                appVersion = BuildConfig.VERSION_NAME, build = BuildConfig.VERSION_CODE), System.currentTimeMillis())
        }
        if (newest != seen) { Prefs.put(SEEN, newest); store(list) }
    }

    /** Bij het starten (en na inloggen): ANR's ophalen en de wachtrij versturen. */
    fun flush(ctx: Context = AppState.appContext) {
        if (!::dir.isInitialized) return
        AppScope.launch(Dispatchers.IO) {
            lock.withLock {
                if (!enabled) { runCatching { file().delete() }; return@withLock }
                if (Prefs.long(SEEN, 0L) == 0L) Prefs.put(SEEN, System.currentTimeMillis())   // eerste keer: niets van vóór build 171
                else runCatching { collectExitInfo(ctx) }
                val now = System.currentTimeMillis()
                val queue = Build171Logic.Crash.prune(load(), now)
                if (queue.isEmpty()) { store(queue); return@withLock }
                val sent = (Prefs.string(SENT) ?: "").split(',').mapNotNull { it.toLongOrNull() }.filter { now - it < 3_600_000L }.toMutableList()
                val left = ArrayList<Build171Logic.Crash.Report>()
                var stop = false
                for (r in queue) {
                    if (stop || !Build171Logic.Crash.allowed(sent, now)) { left += r; continue }
                    val code = Account.crashReport(buildJsonObject {
                        put("action", "crash_report")
                        put("device_id", Account.deviceId)
                        put("platform", Account.devicePlatform)
                        put("app_version", r.appVersion.ifEmpty { BuildConfig.VERSION_NAME })
                        put("build", r.build.takeIf { it > 0 } ?: BuildConfig.VERSION_CODE)
                        put("os_version", Account.osVersion.trim())
                        put("device_model", ((Build.MANUFACTURER ?: "") + " " + (Build.MODEL ?: "")).trim().take(80))
                        put("kind", r.kind)
                        put("signature", r.signature)
                        put("message", r.message)
                        put("stack", r.stack)
                        put("at", Build149Logic.Dates.formatIso(r.at))
                        put("count", r.count)
                    })
                    when (Build171Logic.Crash.outcome(code)) {
                        Build171Logic.Crash.Outcome.SENT -> sent += now
                        Build171Logic.Crash.Outcome.DROP -> {}
                        Build171Logic.Crash.Outcome.RETRY -> { left += r; stop = true }
                    }
                }
                Prefs.put(SENT, sent.joinToString(","))
                store(left)
            }
        }
    }
}
