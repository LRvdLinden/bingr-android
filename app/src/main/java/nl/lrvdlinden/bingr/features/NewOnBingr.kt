package nl.lrvdlinden.bingr

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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

    /** Build 141: verhoogt als de titels van de andere servers (op de achtergrond) klaar of veranderd zijn. */
    var version by mutableStateOf(0); private set

    /** Nieuwste titels van één server, met waarvoor ze berekend zijn (cache, filters, eerst gezien). */
    private class Part(val sig: String, val items: List<Pair<DetailTarget, Long>>)
    /** Andere servers (kleine letters id) → hun nieuwste titels (alleen [MAX] per server, geen catalogus). */
    private val parts = ConcurrentHashMap<String, Part>()
    @Volatile private var activePart: Part? = null
    private var warmJob: Job? = null

    fun wipe() { parts.clear(); activePart = null }

    /**
     * Op de main thread aanroepen. Build 141: meteen de actieve server (de nieuwste 30 zonder de hele lijst te sorteren
     * of te kopiëren) plus wat er van de andere servers al klaarstaat; de andere servers zelf op de achtergrond
     * ([warmOthers], één catalogus tegelijk, pas als het rustig is), daarna verhoogt [version] en bouwt Home de rij opnieuw.
     * Tot build 140 las elke aanroep (per tabblad en bij elke wijziging van de bibliotheek) de catalogus van álle andere
     * servers van schijf, met de hele gids, en maakte van elke titel een kopie: na het kiezen van een profiel liep het
     * geheugen op een tv met grote catalogi vol.
     */
    suspend fun build(active: Library, activeRaw: Library, pid: String?): List<DetailTarget> {
        val lists = ArrayList<List<Pair<DetailTarget, Long>>>()
        if (pid != null) lists += activeTop(active, activeRaw, pid)
        val others = Store.playlists.filter { !it.id.equals(pid, ignoreCase = true) }.map { it to Playlists.context(it) }
        for ((p, ctx) in others) {
            val part = parts[p.id.lowercase(Locale.ROOT)] ?: continue
            if (part.sig.endsWith("#" + ctx.hashCode())) lists += part.items
        }
        if (others.isNotEmpty()) warmOthers()
        return withContext(Dispatchers.Default) {
            Build141Feedback.Recent.merge(lists, MAX, { it.title }) { t ->
                AllServersLogic.dedupKey(if (t.isSeries) "series" else "movie", t.title, t.year, null)
            }
        }
    }

    private suspend fun activeTop(active: Library, activeRaw: Library, pid: String): List<Pair<DetailTarget, Long>> {
        val stamp = System.identityHashCode(activeRaw).toLong()
        val sig = "$pid|$stamp|${System.identityHashCode(active)}"
        activePart?.takeIf { it.sig == sig }?.let { return it.items }
        val seen = FirstSeen.known(pid, stamp) ?: FirstSeen.update(pid, activeRaw, stamp)
        val items = withContext(Dispatchers.Default) { top(active, seen, null) }
        activePart = Part(sig, items)
        return items
    }

    /** De nieuwste [MAX] titels van één (gefilterde) bibliotheek; alleen die krijgen een kopie met de server. */
    private fun top(lib: Library, seen: Map<String, Long>, sid: String?): List<Pair<DetailTarget, Long>> {
        val P = Build140Logic.Picks
        val all: Sequence<Any> = lib.movies.asSequence() + lib.series.asSequence()
        return Build141Feedback.Recent.top(all, MAX, { x ->
            when (x) {
                is Movie -> P.addedDate(x.addedAt, seen["m:" + x.id])
                is Series -> P.addedDate(x.addedAt, seen["s:" + x.id])
                else -> 0L
            }
        }, { x -> (x as? Movie)?.title ?: (x as? Series)?.title ?: "" }).map { (x, d) ->
            val t: DetailTarget = when (x) {
                is Movie -> DetailTarget.MovieT(if (sid != null) x.copy(sourceID = sid) else x)
                else -> (x as Series).let { DetailTarget.SeriesT(if (sid != null) it.copy(sourceID = sid) else it) }
            }
            t to d
        }
    }

    /** De andere servers bijwerken (één taak tegelijk; via AllServers.heavy, dus rustig en één catalogus tegelijk). */
    private fun warmOthers() {
        if (warmJob?.isActive == true) return
        warmJob = AppScope.launch {
            var changed = false
            try {
                AllServers.heavy {
                    val others = withContext(Dispatchers.Main) {
                        val pid = Playlists.source?.id
                        Store.playlists.filter { !it.id.equals(pid, ignoreCase = true) }.map { it to Playlists.context(it) }
                    }
                    for ((p, ctx) in others) {
                        val k = p.id.lowercase(Locale.ROOT)
                        val stamp = withContext(Dispatchers.IO) { Playlists.cacheStamp(p) }
                        if (stamp == 0L) continue
                        val sig = "$stamp#${ctx.hashCode()}"
                        if (parts[k]?.sig == sig) continue
                        // eerst gezien alleen opnieuw als de cache van die server veranderde
                        var raw: Library? = null
                        val seen = FirstSeen.known(p.id, stamp) ?: run {
                            raw = Playlists.catalogOf(p, allowNetwork = false) ?: return@run null
                            FirstSeen.update(p.id, raw!!, stamp)
                        } ?: continue
                        val lib = AllServers.otherLibrary(p, ctx, allowNetwork = false, raw = raw) ?: continue
                        raw = null
                        parts[k] = Part(sig, withContext(Dispatchers.Default) { top(lib, seen, p.id) })
                        changed = true
                    }
                    parts.keys.retainAll(others.map { it.first.id.lowercase(Locale.ROOT) }.toSet())
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { }
            if (changed) withContext(Dispatchers.Main) { version++ }
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
        // build 152: (id, categorie), zodat een categorie die bij de vorige keer was overgeslagen (verborgen, build 149) en nu
        // weer aan staat niet in één keer als "nieuw" in Nieuw op Bingr komt; zolang er categorieën overgeslagen zijn,
        // blijven de bekende id's daaruit bewaard (tot build 151 vielen ze weg en kwamen ze later als nieuw terug)
        val items = ArrayList<Pair<String, String>>(raw.movies.size + raw.series.size)
        raw.movies.forEach { items += ("m:" + it.id) to ("movie|" + it.group) }
        raw.series.forEach { items += ("s:" + it.id) to ("series|" + it.group) }
        val sk = File(f.parentFile, k + ".skipped.json")
        val skipSer = kotlinx.serialization.builtins.ListSerializer(String.serializer())
        val wasSkipped = if (sk.exists()) runCatching { Http.json.decodeFromString(skipSer, sk.readText()) }.getOrNull()?.toHashSet() ?: hashSetOf() else hashSetOf()
        val nowSkipped = raw.hiddenGroups.keys.filter { it.startsWith("movie|") || it.startsWith("series|") }
        val n = Build152Logic.HiddenCategories.firstSeen(known, items, System.currentTimeMillis(), wasSkipped, nowSkipped.isNotEmpty())
        runCatching {
            if (nowSkipped.isEmpty()) { if (sk.exists()) sk.delete() } else sk.writeText(Http.json.encodeToString(skipSer, nowSkipped.sorted()))
        }
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
