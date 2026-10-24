package nl.lrvdlinden.bingr

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Build 146: startdiagnose, het equivalent van StartDiagnostics.swift (Apple, build 143). Zo is te zien waar een start
 * misgaat, ook zonder logcat.
 *
 * - Mijlpalen bij het starten (start, activity, splash, account, profiles, home, servers_loaded, ready; ook pin), met de tijd
 *   sinds de start van het proces en het geheugen van de app, gaan meteen naar SharedPreferences.
 * - Kwam de vorige start niet bij "ready", stond de main thread langer dan 10 s vast (ANR-vermoeden) of crashte de app,
 *   dan bewaart de volgende start dat als rapport ([lastProblem]). Instellingen → Diagnose toont het; Support biedt aan
 *   het mee te sturen.
 * - Crash: [Thread.setDefaultUncaughtExceptionHandler] (type, reden, stack), daarna de vorige handler (Android toont
 *   en logt de crash zoals altijd).
 * - Geen persoonsgegevens: alleen mijlpalen, tijden, build, systeem en stackregels (opgeschoond, [Build146Logic.Diag.scrub]).
 */
object StartDiagnostics {
    private const val FILE = "bingr.diag"
    private const val K_CURRENT = "start.current.v146"
    private const val K_PROBLEM = "start.problem.v146"

    private var prefs: SharedPreferences? = null
    private val lock = Any()
    private var record = Build146Logic.Diag.Record(0, "", 0L)
    @Volatile private var started = false
    @Volatile private var mainAlive = 0L

    /** Rapport van de vorige start (null = die ging goed of is weggeklikt). */
    var lastProblem by mutableStateOf<Build146Logic.Diag.Problem?>(null)
        private set

    /** Mijlpalen van deze start (voor de diagnose-bijlage). */
    fun marks(): List<Build146Logic.Diag.Mark> = synchronized(lock) { record.marks }

    /** Milliseconden sinds de start van het proces. */
    fun uptimeMs(): Long = android.os.SystemClock.uptimeMillis() - android.os.Process.getStartUptimeMillis()

    /** Geheugen van de app in MB (Java-heap + native heap). */
    fun appMB(): Int = runCatching {
        val rt = Runtime.getRuntime()
        (((rt.totalMemory() - rt.freeMemory()) + android.os.Debug.getNativeHeapAllocatedSize()) / 1_048_576L).toInt()
    }.getOrDefault(0)

    /** Zo vroeg mogelijk (Application.onCreate): de vorige start beoordelen, de crash-handler zetten en de bewaking starten. */
    fun start(ctx: Context) {
        if (started) return
        started = true
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        val prev = runCatching { p.getString(K_CURRENT, null)?.let { decode(Http.json.parseToJsonElement(it)) } }.getOrNull()
        val problem = Build146Logic.Diag.analyze(prev)
        if (problem != null) {
            lastProblem = problem
            runCatching { p.edit().putString(K_PROBLEM, encodeProblem(problem).toString()).apply() }
        } else {
            lastProblem = runCatching { p.getString(K_PROBLEM, null)?.let { decodeProblem(Http.json.parseToJsonElement(it)) } }.getOrNull()
        }
        val os = "Android " + (android.os.Build.VERSION.RELEASE ?: "") + " (API ${android.os.Build.VERSION.SDK_INT})"
        synchronized(lock) { record = Build146Logic.Diag.Record(BuildConfig.VERSION_CODE, os, System.currentTimeMillis()) }
        installHandler()
        mark(Build146Logic.Diag.FIRST_MARK)
        startWatch()
    }

    /** Mijlpaal (alleen de eerste keer per naam), meteen weggeschreven. */
    fun mark(name: String) {
        if (!started) return
        val r = synchronized(lock) {
            val n = Build146Logic.Diag.mark(record, name, uptimeMs(), appMB())
            if (n === record) return
            record = n
            n
        }
        save(r, sync = false)
    }

    fun clearProblem() {
        lastProblem = null
        runCatching { prefs?.edit()?.remove(K_PROBLEM)?.apply() }
    }

    private fun save(r: Build146Logic.Diag.Record, sync: Boolean) {
        val text = runCatching { encode(r).toString() }.getOrNull() ?: return
        runCatching {
            val e = prefs?.edit()?.putString(K_CURRENT, text) ?: return
            if (sync) e.commit() else e.apply()
        }
    }

    // MARK: crash

    private fun installHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { noteCrash(e) }
            if (previous != null) previous.uncaughtException(t, e)
            else { android.os.Process.killProcess(android.os.Process.myPid()); kotlin.system.exitProcess(10) }
        }
    }

    private fun noteCrash(e: Throwable) {
        val root = generateSequence(e) { it.cause }.take(6).last()
        val lines = ArrayList<String>()
        lines += e.stackTrace.take(30).map { "at ${it.className}.${it.methodName}(${it.fileName ?: "?"}:${it.lineNumber})" }
        if (root !== e) {
            lines += "Caused by: ${root.javaClass.name}"
            lines += root.stackTrace.take(18).map { "at ${it.className}.${it.methodName}(${it.fileName ?: "?"}:${it.lineNumber})" }
        }
        val crash = Build146Logic.Diag.Crash(
            at = System.currentTimeMillis(),
            type = e.javaClass.name.take(80),
            reason = Build146Logic.Diag.scrub(e.message ?: root.message ?: ""),
            stack = Build146Logic.Diag.stack(lines),
            build = BuildConfig.VERSION_CODE,
        )
        val r = synchronized(lock) { record = record.copy(crash = crash); record }
        save(r, sync = true)
    }

    // MARK: main-thread-bewaking (ANR-vermoeden), alleen de eerste 10 minuten

    private fun startWatch() {
        val main = Handler(Looper.getMainLooper())
        mainAlive = android.os.SystemClock.uptimeMillis()
        val t = Thread({
            val t0 = android.os.SystemClock.uptimeMillis()
            while (true) {
                main.post { mainAlive = android.os.SystemClock.uptimeMillis() }
                try { Thread.sleep(1000) } catch (_: InterruptedException) { return@Thread }
                val now = android.os.SystemClock.uptimeMillis()
                val stuck = now - mainAlive
                var changed: Build146Logic.Diag.Record? = null
                synchronized(lock) {
                    val mb = appMB()
                    if (mb >= (record.peakMB ?: 0) + 25) { record = record.copy(peakMB = mb); changed = record }
                    if (stuck >= Build146Logic.Diag.HANG_LIMIT_MS && stuck > (record.hangMs ?: 0L) + 2000) {
                        record = record.copy(hangMs = stuck, hangAt = record.marks.lastOrNull()?.name)
                        changed = record
                    }
                }
                changed?.let { save(it, sync = false) }
                if (now - t0 > 600_000L) return@Thread
            }
        }, "bingr.diag.watch")
        t.isDaemon = true
        t.priority = Thread.MIN_PRIORITY
        runCatching { t.start() }
    }

    // MARK: opslaan (JSON)

    private fun markJson(m: Build146Logic.Diag.Mark) = buildJsonObject { put("name", m.name); put("ms", m.ms); m.mb?.let { put("mb", it) } }
    private fun crashJson(c: Build146Logic.Diag.Crash) = buildJsonObject {
        put("at", c.at); put("type", c.type); put("reason", c.reason); put("build", c.build)
        put("stack", JsonArray(c.stack.map { JsonPrimitive(it) }))
    }

    private fun encode(r: Build146Logic.Diag.Record): JsonObject = buildJsonObject {
        put("build", r.build); put("os", r.os); put("started", r.started); put("finished", r.finished)
        put("marks", JsonArray(r.marks.map { markJson(it) }))
        r.crash?.let { put("crash", crashJson(it)) }
        r.peakMB?.let { put("peakMB", it) }
        r.hangMs?.let { put("hangMs", it) }
        r.hangAt?.let { put("hangAt", it) }
    }

    private fun decodeMarks(e: JsonElement?): List<Build146Logic.Diag.Mark> = e.jList("marks").mapNotNull { m ->
        val n = m.jS("name") ?: return@mapNotNull null
        Build146Logic.Diag.Mark(n, m.jL("ms") ?: 0L, m.jI("mb"))
    }

    private fun decodeCrash(e: JsonElement?): Build146Logic.Diag.Crash? {
        val c = e as? JsonObject ?: return null
        return Build146Logic.Diag.Crash(c.jL("at") ?: 0L, c.jS("type") ?: "?", c.jS("reason") ?: "",
            c.jList("stack").mapNotNull { it.jFlex }, c.jI("build") ?: 0)
    }

    private fun decode(e: JsonElement): Build146Logic.Diag.Record? {
        val o = e as? JsonObject ?: return null
        return Build146Logic.Diag.Record(
            build = o.jI("build") ?: 0, os = o.jS("os") ?: "", started = o.jL("started") ?: 0L,
            marks = decodeMarks(o), finished = o.jB("finished") == true, crash = decodeCrash(o["crash"]),
            peakMB = o.jI("peakMB"), hangMs = o.jL("hangMs"), hangAt = o.jS("hangAt"),
        )
    }

    private fun encodeProblem(p: Build146Logic.Diag.Problem): JsonObject = buildJsonObject {
        put("kind", p.kind.name); put("build", p.build); put("os", p.os); put("at", p.at); put("lastMark", p.lastMark); put("ms", p.ms)
        put("marks", JsonArray(p.marks.map { markJson(it) }))
        put("crash", p.crash?.let { crashJson(it) } ?: JsonNull)
        p.peakMB?.let { put("peakMB", it) }
    }

    private fun decodeProblem(e: JsonElement): Build146Logic.Diag.Problem? {
        val o = e as? JsonObject ?: return null
        val kind = Build146Logic.Diag.Kind.entries.firstOrNull { it.name == o.jS("kind") } ?: return null
        return Build146Logic.Diag.Problem(kind, o.jI("build") ?: 0, o.jS("os") ?: "", o.jL("at") ?: 0L, o.jS("lastMark") ?: "",
            o.jL("ms") ?: 0L, decodeMarks(o), decodeCrash(o["crash"]), o.jI("peakMB"))
    }
}
