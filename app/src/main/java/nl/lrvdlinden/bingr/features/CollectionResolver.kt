package nl.lrvdlinden.bingr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import java.lang.ref.WeakReference

// Collecties snel en zonder haperen uitrekenen (build 56).
//
// Waarom: elke collectierij rekende zijn inhoud opnieuw uit bij elke nieuwe bibliotheek (na een sync, na nieuwe
// leeftijdsclassificaties elke 40 titels, bij wisselen van tab), en een slimme collectie vouwde daarbij voor élke titel
// in de bibliotheek de naam opnieuw (Normalizer + regex). Bij tienduizenden titels en meerdere collecties kostte dat
// seconden rekenwerk, en elke uitkomst (ook een gelijke) liet de rij opnieuw tekenen terwijl je scrolde.
//
// Nu: één zoekindex per lijst ([CollectionIndex]), uitkomsten onthouden per (profiel, collectie, updatedAt, lijst),
// rekenen op Dispatchers.Default met debounce, en alleen een nieuwe lijst doorgeven als de inhoud echt anders is.

/** Zoekindex van één geladen lijst: gevouwen namen (zelfde volgorde als de lijsten) en id-tabellen. */
class CollectionIndex private constructor(
    val lib: Library,
    val channelNames: Array<String>,
    val movieNames: Array<String>,
    val seriesNames: Array<String>,
    val channelByID: Map<String, Channel>,
    val movieByID: Map<String, Movie>,
    val seriesByID: Map<String, Series>,
    /** naam → gevouwen naam, om bij de volgende lijst (meestal dezelfde titels) niet opnieuw te vouwen */
    private val folded: HashMap<String, String>,
) {
    companion object {
        @Volatile private var current: CollectionIndex? = null
        private val lock = Any()

        /** Index van deze lijst (gebouwd bij de eerste vraag, daarna hergebruikt). Niet op de main thread aanroepen. */
        fun of(lib: Library): CollectionIndex {
            current?.let { if (it.lib === lib) return it }
            synchronized(lock) {
                val prev = current
                if (prev != null && prev.lib === lib) return prev
                return build(lib, prev?.folded).also { current = it }
            }
        }

        private fun build(lib: Library, prevFolded: Map<String, String>?): CollectionIndex {
            val folded = HashMap<String, String>(lib.channels.size + lib.movies.size + lib.series.size)
            fun fold(s: String): String = folded[s] ?: (prevFolded?.get(s) ?: Net.fold(s)).also { folded[s] = it }
            val ch = Array(lib.channels.size) { fold(lib.channels[it].name) }
            val mv = Array(lib.movies.size) { fold(lib.movies[it].title) }
            val se = Array(lib.series.size) { fold(lib.series[it].title) }
            val chID = HashMap<String, Channel>(lib.channels.size * 2); lib.channels.forEach { chID.putIfAbsent(it.id, it) }
            val mvID = HashMap<String, Movie>(lib.movies.size * 2); lib.movies.forEach { mvID.putIfAbsent(it.id, it) }
            val seID = HashMap<String, Series>(lib.series.size * 2); lib.series.forEach { seID.putIfAbsent(it.id, it) }
            return CollectionIndex(lib, ch, mv, se, chID, mvID, seID, folded)
        }
    }
}

object CollectionResolver {
    /** Invoer van één collectierij. De lijst wordt op identiteit vergeleken (een diepe vergelijking is te duur). */
    class Input(val c: BingrCollection, val lib: Library, val pid: String?, val profile: String?) {
        override fun equals(other: Any?): Boolean =
            other is Input && other.lib === lib && other.c == c && other.pid == pid && other.profile == profile
        override fun hashCode(): Int = (c.hashCode() * 31 + System.identityHashCode(lib)) * 31 + (pid?.hashCode() ?: 0)
    }

    private data class Key(val profile: String?, val id: String, val updatedAt: Long, val c: BingrCollection, val pid: String?, val limit: Int)
    private class Entry(val lib: WeakReference<Library>, val result: List<CollectionEntry>)

    /** Laatst gebruikte uitkomsten (LRU); een andere lijst (identiteit) telt als niet gevonden. */
    private val cache = object : LinkedHashMap<Key, Entry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>?): Boolean = size > 64
    }

    private fun key(i: Input, limit: Int) = Key(i.profile?.lowercase(), i.c.id.lowercase(), i.c.updatedAt, i.c, i.pid, limit)

    /** Al uitgerekend voor precies deze invoer? (goedkoop, ook op de main thread) */
    fun peek(i: Input, limit: Int = 200): List<CollectionEntry>? = synchronized(cache) {
        cache[key(i, limit)]?.takeIf { it.lib.get() === i.lib }?.result
    }

    /** Uitrekenen of uit het geheugen. Op de achtergrond aanroepen. */
    fun resolve(i: Input, limit: Int = 200): List<CollectionEntry> {
        peek(i, limit)?.let { return it }
        val r = Collections.resolve(i.c, i.lib, i.pid, limit)
        synchronized(cache) { cache[key(i, limit)] = Entry(WeakReference(i.lib), r) }
        return r
    }

    /**
     * Inhoud als Flow: eerste keer meteen, daarna met debounce (meerdere wijzigingen kort na elkaar = één keer rekenen),
     * op Dispatchers.Default, en alleen doorgeven als de inhoud anders is dan de vorige keer (een sync of opnieuw
     * filteren met dezelfde uitkomst tekent de rij dus niet opnieuw).
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun entries(input: StateFlow<Input>, limit: Int = 200): Flow<List<CollectionEntry>> {
        var first = true
        return input
            .debounce { if (first) { first = false; 0L } else 300L }
            .mapLatest { resolve(it, limit) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
    }

    /** Ander account of uitloggen. */
    fun clear() = synchronized(cache) { cache.clear() }
}

/** Stabiele sleutel van een item in een collectierij (soort + id). */
val CollectionEntry.key: String get() = when (this) {
    is CollectionEntry.C -> "c:" + channel.id
    is CollectionEntry.M -> "m:" + movie.id
    is CollectionEntry.S -> "s:" + series.id
}
