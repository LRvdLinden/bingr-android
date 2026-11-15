package nl.lrvdlinden.bingr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Build 176 (punt 3): zoeken in de beschrijvingen van de tv-gids (docs/ACCOUNT.md "Build 176", 5). Een index op de
 * achtergrond: per zender één genormaliseerde tekst ([Build176Logic.EpgSearch.fold], één teken per origineel teken) met
 * titel en beschrijving van elk programma dat nog niet voorbij is en binnen 7 dagen begint, plus de grenzen per
 * programma. Zoeken = per zender eerst snel afwijzen (ontbreekt een woord in de hele tekst), dan per programma. De
 * programma's zelf worden pas bij een treffer opgehaald (uit het geheugen of de gids op schijf), zodat de index klein
 * blijft. Opnieuw opgebouwd bij een nieuwe gids of na een uur.
 */
object GuideIndex176 {
    /** Bovengrens van de index (tekens), zodat een enorme gids op een tv niet te veel geheugen vraagt. */
    private const val MAX_CHARS = 12_000_000
    /** Per beschrijving hooguit zoveel tekens in de index. */
    private const val MAX_DESC = 400

    private class ChannelIndex(val channel: Channel, val text: String, val starts: LongArray, val ends: LongArray, val bounds: IntArray, val titleEnds: IntArray)
    private class Index(val epg: Any, val builtAt: Long, val channels: List<ChannelIndex>)

    class Hit(val channel: Channel, val program: EPGProgram, val field: Build176Logic.EpgSearch.Field, val start: Int, val length: Int) {
        /** Het stuk tekst om te markeren (in de titel of de beschrijving). */
        val highlightText: String? get() = Build176Logic.EpgSearch.Hit(field, start, length).highlight(program.title, program.description)
    }

    @Volatile private var index: Index? = null
    private val lock = Mutex()

    private suspend fun indexFor(lib: Library): Index? = lock.withLock {
        val now = System.currentTimeMillis()
        index?.let { if (it.epg === lib.epg && now - it.builtAt < 3_600_000L) return it }
        val built = withContext(Dispatchers.Default) { build(lib, now) }
        index = built
        built
    }

    private suspend fun build(lib: Library, now: Long): Index? {
        if (lib.epg.isEmpty()) return null
        val byKey = LinkedHashMap<String, MutableList<Channel>>()
        for (c in lib.channels) byKey.getOrPut(c.epgID ?: c.id) { ArrayList(1) }.add(c)
        val out = ArrayList<ChannelIndex>()
        var total = 0
        val ctx = coroutineContext
        fun add(k: String, list: List<EPGProgram>): Boolean {
            val chans = byKey[k] ?: return true
            val sb = StringBuilder()
            val starts = ArrayList<Long>(); val ends = ArrayList<Long>(); val bounds = ArrayList<Int>(); val tEnds = ArrayList<Int>()
            for (p in list) {
                if (!Build176Logic.EpgSearch.inWindow(p.start, p.end, now)) continue
                bounds += sb.length
                for (ch in p.title) sb.append(Build176Logic.EpgSearch.foldChar(ch))
                tEnds += sb.length
                sb.append('\u0001')
                p.description?.let { d -> val n = minOf(d.length, MAX_DESC); for (i in 0 until n) sb.append(Build176Logic.EpgSearch.foldChar(d[i])) }
                sb.append('\u0002')
                starts += p.start; ends += p.end
            }
            if (starts.isEmpty()) return true
            val text = sb.toString()
            total += text.length
            // dezelfde gids voor meerdere zenders (zelfde epgID): één tekst, één item per zender
            for (c in chans) out += ChannelIndex(c, text, starts.toLongArray(), ends.toLongArray(), bounds.toIntArray(), tEnds.toIntArray())
            return total < MAX_CHARS
        }
        val disk = lib.epg as? DiskEpg
        if (disk != null) {
            disk.scan(byKey.keys.toList(), { true }) { k, list -> ctx.ensureActive(); add(k, list) }
        } else for (k in byKey.keys) {
            ctx.ensureActive()
            val list = lib.epg[k] ?: continue
            if (!add(k, list)) break
        }
        return Index(lib.epg, now, out)
    }

    /** Zoeken (na 250 ms stilte, door de aanroeper): titel vóór beschrijving, dan starttijd; max. 100, één per (zender, start). */
    suspend fun search(q: String, lib: Library): List<Hit> {
        val words = Build176Logic.EpgSearch.words(q) ?: return emptyList()
        val idx = indexFor(lib) ?: return emptyList()
        return withContext(Dispatchers.Default) {
            val now = System.currentTimeMillis()
            data class Raw(val ci: ChannelIndex, val i: Int, val f: Build176Logic.EpgSearch.Field, val pos: Int, val len: Int)
            val raw = ArrayList<Raw>()
            for (ci in idx.channels) {
                ensureActive()
                val t = ci.text
                if (words.any { t.indexOf(it) < 0 }) continue
                for (i in ci.starts.indices) {
                    if (ci.ends[i] <= now) continue
                    val b = ci.bounds[i]; val te = ci.titleEnds[i]
                    val e = if (i + 1 < ci.bounds.size) ci.bounds[i + 1] else t.length
                    val region = t.substring(b, e)
                    val ft = region.substring(0, te - b)
                    val fd = region.substring(te - b + 1).trimEnd('\u0002')
                    val h = Build176Logic.EpgSearch.matchFolded(words, ft, fd) ?: continue
                    raw += Raw(ci, i, h.field, h.start, h.length)
                }
                if (raw.size > 2_000) break
            }
            val ordered = Build176Logic.EpgSearch.order(raw, { it.f }, { it.ci.starts[it.i] }, { "${it.ci.channel.id}|${it.ci.starts[it.i]}" })
            ordered.mapNotNull { r ->
                val c = r.ci.channel
                val start = r.ci.starts[r.i]
                val p = lib.epg[c.epgID ?: c.id]?.let { list ->
                    val j = EPGIndex.indexAt(list, start)
                    list.getOrNull(j)?.takeIf { it.start == start } ?: list.firstOrNull { it.start == start }
                } ?: return@mapNotNull null
                Hit(c, p, r.f, r.pos, r.len)
            }
        }
    }

    /** Index weggooien (bijv. bij weinig geheugen). */
    fun clear() { index = null }
}
