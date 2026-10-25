package nl.lrvdlinden.bingr

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Build 141: cache van een server in drie bestanden in plaats van één, zodat de app een catalogus kan lezen zonder de
 * hele tv-gids in het geheugen te halen.
 *
 * - `lib-<id>.json`: zenders, films en series (zonder gids);
 * - `epg-<id>.bin`: de gids, per zender een JSON-lijst achter elkaar;
 * - `epg-<id>.idx`: per gidssleutel waar die lijst staat (`{sleutel: [begin, lengte]}`).
 *
 * Tot build 140 stond alles in één bestand (`cache-<id>.json`, nog leesbaar via [readCore] en [readLegacy]). Een andere
 * server (Nieuw op Bingr, Mijn lijst en Verder kijken over alle servers, zoeken, slimme collecties) las daardoor elke keer
 * de hele gids mee: bij 5.000 zenders honderdduizenden programma's, per server, en soms meerdere tegelijk. Op een tv met
 * een kleine heap was dat de crash na het kiezen van een profiel. Nu leest een andere server alleen de titels en zenders,
 * en van de gids alleen de zenders die echt nodig zijn ([readEpg] met sleutels).
 *
 * Pure Kotlin (geen Android), zodat de belastingtest (tools/test/ProfileOpenStressTest.kt) dezelfde code gebruikt.
 */
object CatalogFiles {
    /** Alleen de titels en zenders; een `epg`-veld in een oud bestand wordt overgeslagen (ignoreUnknownKeys). */
    @Serializable
    private class Core(
        val channels: List<Channel> = emptyList(),
        val movies: List<Movie> = emptyList(),
        val series: List<Series> = emptyList(),
        /** build 149: telling van de niet ingelezen items uit verborgen categorieën */
        val hiddenGroups: Map<String, Int> = emptyMap(),
    )

    class Paths(val core: File, val epg: File, val index: File) {
        /** Er staat een cache in het nieuwe formaat. */
        val exists: Boolean get() = core.exists()
        fun delete() { core.delete(); epg.delete(); index.delete() }
    }

    private val programs = ListSerializer(EPGProgram.serializer())
    private val indexSer = MapSerializer(String.serializer(), ListSerializer(Long.serializer()))

    /** Alles wegschrijven (eerst naar tijdelijke bestanden; de catalogus als laatste, die bepaalt de tijd van de cache). */
    @OptIn(ExperimentalSerializationApi::class)
    fun write(lib: Library, p: Paths, json: Json) {
        val dir = p.core.parentFile ?: return
        dir.mkdirs()
        val tag = "." + System.nanoTime() + ".tmp"
        val tEpg = File(dir, p.epg.name + tag)
        val tIdx = File(dir, p.index.name + tag)
        val tCore = File(dir, p.core.name + tag)
        try {
            val index = LinkedHashMap<String, List<Long>>(lib.epg.size * 2)
            tEpg.outputStream().buffered(64 * 1024).use { out ->
                var at = 0L
                for ((k, list) in lib.epg) {
                    if (list.isEmpty()) continue
                    val bytes = json.encodeToString(programs, list).toByteArray(Charsets.UTF_8)
                    out.write(bytes)
                    index[k] = listOf(at, bytes.size.toLong())
                    at += bytes.size
                }
            }
            tIdx.writeText(json.encodeToString(indexSer, index))
            tCore.outputStream().buffered(64 * 1024).use { json.encodeToStream(Core.serializer(), Core(lib.channels, lib.movies, lib.series, lib.hiddenGroups), it) }
            replace(tEpg, p.epg); replace(tIdx, p.index); replace(tCore, p.core)
        } finally {
            tEpg.delete(); tIdx.delete(); tCore.delete()
        }
    }

    private fun replace(tmp: File, target: File) {
        if (!tmp.renameTo(target)) { target.delete(); if (!tmp.renameTo(target)) throw java.io.IOException("rename") }
    }

    /** Titels en zenders, zonder gids (ook uit een oud bestand met gids). null = geen of onleesbare cache. */
    @OptIn(ExperimentalSerializationApi::class)
    fun readCore(f: File, json: Json): Library? {
        if (!f.exists()) return null
        return runCatching {
            f.inputStream().buffered(64 * 1024).use { json.decodeFromStream(Core.serializer(), it) }
        }.getOrNull()?.let { Library(it.channels, it.movies, it.series, hiddenGroups = it.hiddenGroups) }
    }

    /** Oud formaat (tot build 140): alles in één bestand. */
    @OptIn(ExperimentalSerializationApi::class)
    fun readLegacy(f: File, json: Json): Library? {
        if (!f.exists()) return null
        return runCatching { f.inputStream().buffered(64 * 1024).use { json.decodeFromStream(Library.serializer(), it) } }.getOrNull()
    }

    /** Oud formaat (tot build 140) schrijven: alleen voor de belastingtest en de rooktest (omzetting meten). */
    @OptIn(ExperimentalSerializationApi::class)
    fun writeLegacy(lib: Library, f: File, json: Json) {
        f.parentFile?.mkdirs()
        f.outputStream().buffered(64 * 1024).use { json.encodeToStream(Library.serializer(), lib, it) }
    }

    /** Waar elke gidslijst staat. */
    fun readIndex(p: Paths, json: Json): Map<String, List<Long>> =
        if (!p.index.exists()) emptyMap()
        else runCatching { json.decodeFromString(indexSer, p.index.readText()) }.getOrNull() ?: emptyMap()

    /**
     * De gids: alles ([keys] null) of alleen deze sleutels (epgID of zender-id). Een sleutel die er niet is, ontbreekt
     * gewoon in het antwoord.
     */
    fun readEpg(p: Paths, json: Json, keys: Set<String>? = null): Map<String, List<EPGProgram>> {
        if (!p.epg.exists()) return emptyMap()
        val index = readIndex(p, json)
        if (index.isEmpty()) return emptyMap()
        val wanted = if (keys == null) index.entries.toList() else keys.mapNotNull { k -> index[k]?.let { java.util.AbstractMap.SimpleEntry(k, it) } }
        if (wanted.isEmpty()) return emptyMap()
        val out = HashMap<String, List<EPGProgram>>(wanted.size * 2)
        runCatching {
            RandomAccessFile(p.epg, "r").use { raf ->
                var buf = ByteArray(64 * 1024)
                for (e in wanted.sortedBy { it.value.firstOrNull() ?: 0L }) {
                    val at = e.value.getOrNull(0) ?: continue
                    val len = e.value.getOrNull(1)?.toInt() ?: continue
                    if (len <= 0 || at < 0 || at + len > raf.length()) continue
                    if (buf.size < len) buf = ByteArray(len)
                    raf.seek(at)
                    raf.readFully(buf, 0, len)
                    val list = runCatching { json.decodeFromString(programs, String(buf, 0, len, Charsets.UTF_8)) }.getOrNull() ?: continue
                    out[e.key] = list
                }
            }
        }
        return out
    }

    /** Gidssleutel van een zender (zoals [Library.nowPlaying] hem gebruikt). */
    fun epgKey(c: Channel): String = c.epgID ?: c.id

    /**
     * De gids van een server als [DiskEpg]: per zender van schijf gelezen als hij nodig is (met een kleine cache), in plaats
     * van alles in het geheugen. null = geen gids in het nieuwe formaat.
     */
    fun openEpg(p: Paths, json: Json, cacheSize: Int = 400): DiskEpg? {
        if (!p.epg.exists()) return null
        val index = readIndex(p, json)
        if (index.isEmpty()) return null
        val raf = runCatching { RandomAccessFile(p.epg, "r") }.getOrNull() ?: return null
        val pos = HashMap<String, LongArray>(index.size * 2)
        for ((k, v) in index) if (v.size >= 2) pos[k] = longArrayOf(v[0], v[1])
        return DiskEpg(DiskEpg.Source(raf, pos, json), emptyMap(), cacheSize)
    }

    internal fun decodePrograms(json: Json, text: String): List<EPGProgram>? = runCatching { json.decodeFromString(programs, text) }.getOrNull()
}

/**
 * Build 141: de gids van de geopende server, op schijf (`epg-<id>.bin` + index) in plaats van in het geheugen. Gedraagt zich
 * als een gewone Map (`epg[sleutel]`, `containsKey`, `keys`), maar leest een zender pas als hij gevraagd wordt en houdt
 * de laatst gebruikte [cacheSize] zenders vast. Tot build 140 stond de hele gids in het geheugen: bij 5.000 zenders
 * honderdduizenden programma's, en het inlezen daarvan was het lange laadscherm na het kiezen van een profiel.
 *
 * Het bestand blijft open ([Source.raf]): wordt de cache intussen vervangen (verversen), dan leest deze gids nog de oude
 * versie tot de nieuwe bibliotheek er is. Vergelijken gaat op identiteit (twee gidsen zijn alleen gelijk als het dezelfde
 * zijn), zodat niemand per ongeluk de hele gids inleest. Thread-veilig.
 */
class DiskEpg internal constructor(
    private val src: Source,
    /** Losse aanvullingen (bijv. de korte gids van een MAC-portal), gaan voor. */
    private val extra: Map<String, List<EPGProgram>>,
    private val cacheSize: Int,
) : AbstractMap<String, List<EPGProgram>>() {

    class Source(val raf: RandomAccessFile, val pos: Map<String, LongArray>, val json: Json) {
        @Volatile var closed = false
        private val lock = Any()
        private var buf = ByteArray(64 * 1024)

        fun read(key: String): List<EPGProgram>? {
            val p = pos[key] ?: return null
            val text = bytes(p) ?: return null
            return CatalogFiles.decodePrograms(json, text)
        }

        /** Ruwe tekst van één zender (voor zoeken: eerst op tekst filteren, pas dan omzetten). */
        fun bytes(p: LongArray): String? = synchronized(lock) {
            if (closed) return null
            val at = p[0]; val len = p[1].toInt()
            runCatching {
                if (len <= 0 || at < 0 || at + len > raf.length()) return null
                if (buf.size < len) buf = ByteArray(len)
                raf.seek(at)
                raf.readFully(buf, 0, len)
                String(buf, 0, len, Charsets.UTF_8)
            }.getOrNull()
        }

        fun close() = synchronized(lock) { if (!closed) { closed = true; runCatching { raf.close() } } }
    }

    private val lru = object : LinkedHashMap<String, List<EPGProgram>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<EPGProgram>>?): Boolean = size > cacheSize
    }

    override val size: Int get() = src.pos.size + extra.keys.count { it !in src.pos }
    override fun isEmpty(): Boolean = src.pos.isEmpty() && extra.isEmpty()
    override fun containsKey(key: String): Boolean = key in extra || key in src.pos
    override val keys: Set<String> get() = if (extra.isEmpty()) src.pos.keys else src.pos.keys + extra.keys

    override fun get(key: String): List<EPGProgram>? {
        extra[key]?.let { return it }
        if (key !in src.pos) return null
        synchronized(lru) { lru[key] }?.let { return it }
        val list = src.read(key) ?: return null
        synchronized(lru) { lru[key] = list }
        return list
    }

    /** Alles doorlopen leest de hele gids (één zender tegelijk, zonder cache); alleen voor uitzonderingen. */
    override val entries: Set<Map.Entry<String, List<EPGProgram>>>
        get() = object : AbstractSet<Map.Entry<String, List<EPGProgram>>>() {
            override val size: Int get() = this@DiskEpg.size
            override fun iterator(): Iterator<Map.Entry<String, List<EPGProgram>>> = keys.asSequence()
                .mapNotNull { k -> (extra[k] ?: src.read(k))?.let { java.util.AbstractMap.SimpleImmutableEntry(k, it) } }
                .iterator()
        }

    /** Deze gids plus één zender (de korte gids van een MAC-portal), zonder iets in te lezen. */
    fun with(key: String, list: List<EPGProgram>): DiskEpg = DiskEpg(src, extra + (key to list), cacheSize)

    /**
     * Zoeken in de titels zonder de gids in het geheugen te halen: per zender eerst op de ruwe tekst ([prefilter], bijv.
     * een stukje van de zoekterm in kleine letters), pas bij een treffer omzetten en aan [block] geven. [block] geeft
     * false om te stoppen.
     */
    fun scan(keys: List<String>, prefilter: (String) -> Boolean, block: (String, List<EPGProgram>) -> Boolean) {
        for (k in keys) {
            val list = extra[k] ?: run {
                val p = src.pos[k] ?: return@run null
                val text = src.bytes(p) ?: return@run null
                if (!prefilter(text)) return@run null
                CatalogFiles.decodePrograms(src.json, text)
            } ?: continue
            if (!block(k, list)) return
        }
    }

    /** Leest [other] uit hetzelfde bestand (bijv. een aanvulling via [with])? */
    fun sharesSource(other: Any?): Boolean = other is DiskEpg && other.src === src

    /** Bestand dicht (na het vervangen door een nieuwere gids); daarna geeft [get] null. */
    fun close() = src.close()

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
    override fun toString(): String = "DiskEpg(${src.pos.size} zenders)"
}
