package nl.lrvdlinden.bingr

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
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
 * Build 128: state-section `catalogmeta` (voor het hele account, docs/ACCOUNT.md van de iOS-repo):
 * `{items: {"<server-id kleine letters>|<item>": {t, y, k, tmdb, img, sid, sname, ep, ch, at}}, remap: {"<oude sleutel
 * kleine letters>": {to, at}}, updatedAt}`. Per sleutel wint de nieuwste `at`; items die nergens meer naar verwezen worden
 * en ouder zijn dan 90 dagen gaan weg (max. 6000), remap-regels 180 dagen. Onbekende velden in een item blijven staan
 * (het hele object wordt bewaard). Lokaal in `files/store/catalogmeta.json`; samenvoegen en uploaden doet [StateSync].
 */
object CatalogMeta {
    /** Verhoogt bij elke wijziging (Compose leest dit, zodat tegels hun titel/poster krijgen). */
    var version by mutableStateOf(0); private set

    @Volatile private var items: Map<String, JsonObject> = emptyMap()
    @Volatile private var rules: Map<String, AllServersLogic.Rule> = emptyMap()
    /** Kleine letters sleutel → sleutel in [items] (sleutels hoofdletterongevoelig opzoeken). */
    @Volatile private var lowerIndex: Map<String, String> = emptyMap()
    private var loaded = false
    private val lock = Any()

    private val file: File get() = File(AppState.appContext.filesDir, "store/catalogmeta.json")

    // MARK: tijden

    // build 149: geen opmaker per item
    fun iso(ms: Long): String = Build149Logic.Dates.formatIso(ms)
    fun time(e: JsonElement?): Long? {
        val p = e as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        val s = p.content.trim()
        if (s.isEmpty()) return null
        if (!p.isString || s.all { it.isDigit() || it == '.' }) {
            val d = s.toDoubleOrNull() ?: return null
            return if (d > 100_000_000_000.0) d.toLong() else (d * 1000).toLong()
        }
        val base = Net.parseISODate(s) ?: return null
        val dot = s.indexOf('.')
        if (dot < 0) return base
        val digits = s.substring(dot + 1).takeWhile { it.isDigit() }
        return base + ((digits + "000").take(3).toIntOrNull() ?: 0)
    }
    private fun atOf(o: JsonObject): Long = time(o["at"]) ?: 0L

    // MARK: laden en bewaren

    fun ensureLoaded() {
        synchronized(lock) {
            if (loaded) return
            loaded = true
            val o = runCatching { Http.json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull() ?: return
            setFrom(o)
        }
    }

    private fun setFrom(o: JsonObject) {
        items = (o["items"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonObject)?.let { AllServersLogic.metaKey(k) to it } }?.toMap() ?: emptyMap()
        rules = parseRules(o["remap"] as? JsonObject)
        lowerIndex = items.keys.associateBy { it.lowercase(Locale.ROOT) }
    }

    private fun parseRules(o: JsonObject?): Map<String, AllServersLogic.Rule> =
        o?.mapNotNull { (k, v) ->
            val r = v as? JsonObject ?: return@mapNotNull null
            val to = r.jS("to")?.takeIf { it.contains('|') } ?: return@mapNotNull null
            AllServersLogic.remapKey(k) to AllServersLogic.Rule(to, time(r["at"]) ?: 0L)
        }?.toMap() ?: emptyMap()

    private val savePending = java.util.concurrent.atomic.AtomicBoolean(false)
    /** Bewaren (samengevoegd: veel wijzigingen kort na elkaar = één keer schrijven). */
    private fun save() {
        if (!savePending.compareAndSet(false, true)) return
        AppScope.launch {
            kotlinx.coroutines.delay(1_500)
            savePending.set(false)
            val json = toJson()
            Store.enqueueIO {
                val f = file
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(json.toString())
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            }
        }
    }

    fun toJson(): JsonObject = JsonObject(mapOf(
        "items" to JsonObject(items.toSortedMap()),
        "remap" to JsonObject(rules.toSortedMap().mapValues { (_, r) ->
            JsonObject(mapOf("to" to JsonPrimitive(r.to), "at" to JsonPrimitive(iso(r.at))))
        }),
        "updatedAt" to JsonPrimitive(iso(System.currentTimeMillis())),
    ))

    /** Ander account op dit apparaat. */
    fun wipe() {
        synchronized(lock) { items = emptyMap(); rules = emptyMap(); lowerIndex = emptyMap(); loaded = true }
        runCatching { file.delete() }
        version++
    }

    // MARK: lezen

    fun item(key: String): JsonObject? {
        ensureLoaded()
        val k = AllServersLogic.metaKey(key)
        return items[k] ?: lowerIndex[k.lowercase(Locale.ROOT)]?.let { items[it] }
    }
    fun title(key: String): String? = item(key).jS("t")?.takeIf { it.isNotBlank() }
    /** Build 141: jaar uit de meta (getal, of een getal als tekst). */
    fun year(key: String): Int? = item(key).jI("y") ?: item(key).jS("y")?.toIntOrNull()
    fun image(key: String): String? = item(key).jS("img")?.takeIf { it.isNotBlank() }
    fun kind(key: String): String? = item(key).jS("k")
    fun serverName(key: String): String? = item(key).jS("sname")?.takeIf { it.isNotBlank() }
    /** Naam van een server (ook een verwijderde) uit de meta van een van zijn items. */
    fun serverName(sid: String, fallbackKey: String?): String? =
        fallbackKey?.let { serverName(it) } ?: items.values.firstOrNull { it.jS("sid").equals(sid, ignoreCase = true) }?.jS("sname")

    fun rulesNow(): Map<String, AllServersLogic.Rule> { ensureLoaded(); return rules }
    /** Waar staat [key] nu (na remap)? null = niet verhuisd. */
    fun target(key: String): String? = AllServersLogic.resolve(rulesNow(), key)?.first

    /** Wat er voor [key] gezocht moet worden (uit de meta; null = niets bekend). */
    fun wanted(key: String): AllServersLogic.Wanted? {
        val o = item(key) ?: return null
        val t = o.jS("t")?.takeIf { it.isNotBlank() } ?: return null
        val k = o.jS("k") ?: return null
        return AllServersLogic.Wanted(k, t, o.jI("y"), o.jI("tmdb"), o.jO("ch").jS("epg"))
    }
    /** Aflevering: {series, st, s, e}. */
    data class Ep(val series: String?, val seriesTitle: String?, val season: Int, val episode: Int)
    fun episode(key: String): Ep? {
        val e = item(key).jO("ep") ?: return null
        val s = e.jI("s") ?: return null
        val n = e.jI("e") ?: return null
        return Ep(e.jS("series"), e.jS("st"), s, n)
    }

    // MARK: schrijven

    /**
     * Meta van een item vastleggen (bij toevoegen, afspelen en de migratie). Alleen als er iets anders is dan wat er al
     * staat; dan `at` = nu. Velden die we niet kennen (null) worden niet over een bekende waarde heen gezet.
     */
    fun record(key: String, kind: String, title: String?, year: Int?, tmdb: Int? = null, img: String?, sid: String, sname: String?,
               ep: Ep? = null, channelName: String? = null, epg: String? = null): Boolean {
        if (title.isNullOrBlank() || !key.contains('|')) return false
        ensureLoaded()
        val k = AllServersLogic.metaKey(key)
        val cur = item(k)
        val m = LinkedHashMap<String, JsonElement>(cur ?: emptyMap())
        fun set(f: String, v: JsonElement?) { if (v != null) m[f] = v }
        set("t", JsonPrimitive(title))
        set("y", year?.let { JsonPrimitive(it) } ?: cur?.get("y") ?: JsonNull)
        set("k", JsonPrimitive(kind))
        set("tmdb", tmdb?.let { JsonPrimitive(it) } ?: cur?.get("tmdb") ?: JsonNull)
        set("img", img?.takeIf { it.isNotBlank() }?.let { JsonPrimitive(it) } ?: cur?.get("img") ?: JsonNull)
        set("sid", JsonPrimitive(sid))
        set("sname", sname?.let { JsonPrimitive(it) } ?: cur?.get("sname") ?: JsonNull)
        set("ep", ep?.let { e ->
            JsonObject(buildMap {
                put("series", e.series?.let { JsonPrimitive(it) } ?: JsonNull)
                put("st", e.seriesTitle?.let { JsonPrimitive(it) } ?: JsonNull)
                put("s", JsonPrimitive(e.season)); put("e", JsonPrimitive(e.episode))
            })
        } ?: cur?.get("ep") ?: JsonNull)
        set("ch", if (kind == "channel") JsonObject(mapOf(
            "name" to JsonPrimitive(channelName ?: title),
            "epg" to (epg?.takeIf { it.isNotBlank() }?.let { JsonPrimitive(it) } ?: (cur?.get("ch") as? JsonObject)?.get("epg") ?: JsonNull),
        )) else cur?.get("ch") ?: JsonNull)
        val same = cur != null && m.filterKeys { it != "at" } == cur.filterKeys { it != "at" }
        if (same) return false
        m["at"] = JsonPrimitive(iso(System.currentTimeMillis()))
        synchronized(lock) {
            items = items + (k to JsonObject(m))
            lowerIndex = lowerIndex + (k.lowercase(Locale.ROOT) to k)
        }
        changed()
        return true
    }

    /** Item verhuisd: regel oud → nieuw (geen lus). true = nieuwe regel. */
    fun addRemap(from: String, to: String): Boolean {
        ensureLoaded()
        val n = AllServersLogic.addRule(rules, from, to, System.currentTimeMillis())
        if (n == rules) return false
        synchronized(lock) { rules = n }
        changed()
        return true
    }

    private fun changed() {
        save()
        AppScope.launch(kotlinx.coroutines.Dispatchers.Main) { version++; StateSync.catalogMetaChanged() }
    }

    // MARK: samenvoegen met de server

    /**
     * Serverversie [data] samenvoegen met de lokale (per sleutel nieuwste `at`), begrenzen ([referenced]: sleutels
     * waarnaar lijsten/collecties nog verwijzen, kleine-letter-server) en lokaal bewaren. Geeft de samengevoegde JSON
     * en of die afwijkt van de server (dan uploaden).
     */
    fun merge(data: JsonObject?, referenced: Set<String>): Pair<JsonObject, Boolean> {
        ensureLoaded()
        val now = System.currentTimeMillis()
        val srvItems = (data?.get("items") as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonObject)?.let { AllServersLogic.metaKey(k) to it } }?.toMap() ?: emptyMap()
        val srvRules = parseRules(data?.get("remap") as? JsonObject)
        val mergedItems = AllServersLogic.pruneItems(AllServersLogic.mergeNewest(items, srvItems) { atOf(it) }, { atOf(it) }, referenced, now)
        val mergedRules = AllServersLogic.pruneRules(AllServersLogic.mergeNewest(rules, srvRules) { it.at }, now)
        val changedLocal = mergedItems != items || mergedRules != rules
        synchronized(lock) {
            items = mergedItems; rules = mergedRules
            lowerIndex = items.keys.associateBy { it.lowercase(Locale.ROOT) }
        }
        if (changedLocal) { save(); AppScope.launch(kotlinx.coroutines.Dispatchers.Main) { version++ } }
        val differs = data == null || mergedItems != srvItems || mergedRules != srvRules
        val base = data ?: JsonObject(emptyMap())
        val json = JsonObject(base + toJson())
        return json to differs
    }

    /** Alle sleutels waarvoor meta bestaat (voor de migratie). */
    fun hasMeta(key: String): Boolean = item(key) != null
}
