package nl.lrvdlinden.bingr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Build 140: "Nieuw op Bingr" (docs/ACCOUNT.md → "Build 140"): de titels die het laatst aan de bibliotheek van het
 * account (alle servers) zijn toegevoegd, nieuwste eerst. Datum: die van de provider (Xtream `added`, Plex/Jellyfin
 * `addedAt`/`DateCreated`, in [Movie.addedAt]/[Series.addedAt]), anders het moment waarop deze app de titel voor het eerst
 * zag ([FirstSeen], lokaal per apparaat).
 *
 * Tot build 139 kwam de rij uit TMDB: wat net nieuw was op streamingdiensten in de regio (TMDB watch providers,
 * gegevens van JustWatch, alleen "flatrate"), gekoppeld aan je bibliotheek op titel + jaar. Dat zei niets over wat er in
 * je eigen bibliotheek nieuw was.
 */
object NewOnBingr {
    private const val MAX = 30

    /** Op de achtergrond aanroepen. [active] = gefilterde bibliotheek van de actieve server; andere servers uit de cache. */
    suspend fun build(active: Library, activeRaw: Library, pid: String?): List<DetailTarget> {
        val out = ArrayList<Pair<DetailTarget, Long>>()
        if (pid != null) collect(active, FirstSeen.known(pid, System.identityHashCode(activeRaw).toLong())
            ?: FirstSeen.update(pid, activeRaw, System.identityHashCode(activeRaw).toLong()), null, out)
        val others = withContext(Dispatchers.Main) {
            Store.playlists.filter { !it.id.equals(pid, ignoreCase = true) }.map { it to Playlists.context(it) }
        }
        for ((p, ctx) in others) {
            // eerst gezien alleen opnieuw als de cache van die server veranderde (niet bij elke opening van schijf lezen)
            val stamp = withContext(Dispatchers.IO) { Playlists.cacheStamp(p) }
            if (stamp == 0L) continue
            val seen = FirstSeen.known(p.id, stamp) ?: run {
                val raw = Playlists.catalogOf(p, allowNetwork = false) ?: return@run null
                FirstSeen.update(p.id, raw, stamp)
            } ?: continue
            val lib = AllServers.otherLibrary(p, ctx, allowNetwork = false) ?: continue
            collect(lib, seen, p.id, out)
        }
        return withContext(Dispatchers.Default) {
            val seen = HashSet<String>()
            out.sortedWith(compareByDescending<Pair<DetailTarget, Long>> { it.second }.thenBy { it.first.title })
                .filter { (t, _) -> seen.add(AllServersLogic.dedupKey(if (t.isSeries) "series" else "movie", t.title, t.year, null)) }
                .take(MAX).map { it.first }
        }
    }

    private suspend fun collect(lib: Library, seen: Map<String, Long>, sid: String?, out: MutableList<Pair<DetailTarget, Long>>) =
        withContext(Dispatchers.Default) {
            for (m in lib.movies) {
                val d = Build140Logic.Picks.addedDate(m.addedAt, seen["m:" + m.id])
                if (d > 0) out += DetailTarget.MovieT(if (sid != null) m.copy(sourceID = sid) else m) to d
            }
            for (s in lib.series) {
                val d = Build140Logic.Picks.addedDate(s.addedAt, seen["s:" + s.id])
                if (d > 0) out += DetailTarget.SeriesT(if (sid != null) s.copy(sourceID = sid) else s) to d
            }
        }
}

/**
 * Build 140: wanneer deze app een film of serie van een server voor het eerst zag (lokaal per apparaat, niet gesynct).
 * De eerste keer dat een server gezien wordt krijgt alles 0 (anders was na een installatie alles "nieuw").
 */
object FirstSeen {
    private val mem = ConcurrentHashMap<String, Map<String, Long>>()
    /** Van welke versie van de catalogus (cachetijd of object) [mem] is bijgewerkt. */
    private val version = ConcurrentHashMap<String, Long>()

    /** Al bijgewerkt voor deze versie van de catalogus van [sid]? */
    fun known(sid: String, stamp: Long): Map<String, Long>? {
        val k = sid.lowercase(Locale.ROOT)
        return if (version[k] == stamp) mem[k] else null
    }
    private val ser = MapSerializer(String.serializer(), Long.serializer())
    private fun file(sid: String) = File(File(AppState.appContext.filesDir, "firstseen").apply { mkdirs() }, sid.lowercase(Locale.ROOT) + ".json")

    suspend fun update(sid: String, raw: Library, stamp: Long): Map<String, Long> = withContext(Dispatchers.IO) {
        if (raw.movies.isEmpty() && raw.series.isEmpty()) return@withContext mem[sid.lowercase(Locale.ROOT)] ?: emptyMap()
        val k = sid.lowercase(Locale.ROOT)
        val f = file(sid)
        val known = mem[k] ?: if (f.exists()) runCatching { Http.json.decodeFromString(ser, f.readText()) }.getOrNull() else null
        val ids = ArrayList<String>(raw.movies.size + raw.series.size)
        raw.movies.forEach { ids += "m:" + it.id }
        raw.series.forEach { ids += "s:" + it.id }
        val n = Build140Logic.Picks.firstSeen(known, ids, System.currentTimeMillis())
        mem[k] = n
        version[k] = stamp
        if (n != known) runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(Http.json.encodeToString(ser, n))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
        n
    }

    fun wipe() {
        mem.clear(); version.clear()
        runCatching { File(AppState.appContext.filesDir, "firstseen").deleteRecursively() }
    }
}
