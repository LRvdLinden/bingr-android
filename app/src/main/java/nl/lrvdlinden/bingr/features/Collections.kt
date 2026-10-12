package nl.lrvdlinden.bingr

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

// Collecties en het startscherm per profiel (build 51), volgens "Build 51: collecties, startscherm,
// terugkijk-correctie en webconsole" in docs/ACCOUNT.md van de iOS-repo.

/** Afbeelding van een collectie: symbool + kleur (zoals een avatar), of een eigen plaatje ([url]). */
@Serializable
data class CollectionImage(val symbol: String = "⭐", val colorIndex: Int = 0, val url: String? = null) {
    val avatar: PlaylistAvatar get() = PlaylistAvatar(symbol, colorIndex)
}

/** Eén item in een handmatige collectie: soort ("channel", "movie", "series") en de lokale id. */
@Serializable
data class CollectionRef(val type: String, val id: String)

/**
 * Eigen rij ("collectie"), voor het hele account. [kind] "manual" (vaste lijst [items]) of "smart"
 * (zoekwoorden [query], soorten [types], optioneel beperkt tot [group] en [sourceID]).
 * Tijden in epoch-ms; [deleted] = tombstone (zelfde regels als bij profielen).
 */
@Serializable
data class BingrCollection(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val image: CollectionImage = CollectionImage(),
    val kind: String = MANUAL,
    val items: List<CollectionRef> = emptyList(),
    val query: String = "",
    val types: List<String> = ALL_TYPES,
    val group: String? = null,
    val sourceID: String? = null,
    val order: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
    val deletedAt: Long? = null,
) {
    val isSmart: Boolean get() = kind == SMART
    val homeKey: String get() = "collection:$id"
    /** Zoekwoorden (gescheiden door komma's), gevouwen. */
    val keywords: List<String> get() = query.split(',').map { Net.fold(it.trim()) }.filter { it.isNotEmpty() }

    companion object {
        const val MANUAL = "manual"
        const val SMART = "smart"
        const val CHANNEL = "channel"
        const val MOVIE = "movie"
        const val SERIES = "series"
        val ALL_TYPES = listOf(CHANNEL, MOVIE, SERIES)
    }
}

/** Inhoud van een collectie in de geladen lijst. */
sealed class CollectionEntry {
    data class C(val channel: Channel) : CollectionEntry()
    data class M(val movie: Movie) : CollectionEntry()
    data class S(val series: Series) : CollectionEntry()

    val target: DetailTarget? get() = when (this) {
        is M -> DetailTarget.MovieT(movie)
        is S -> DetailTarget.SeriesT(series)
        is C -> null
    }
}

/** Alle collecties van het account (lokaal in files/store/collections.json, sync via StateSync "collections"). */
object Collections {
    /** Alles, ook tombstones. */
    var all by mutableStateOf(listOf<BingrCollection>()); private set
    private var loaded = false

    /** Zichtbare collecties in hun volgorde. */
    val visible: List<BingrCollection>
        get() { ensureLoaded(); return all.filter { !it.deleted }.sortedWith(compareBy({ it.order }, { it.name.lowercase() })) }

    private val file: File get() = File(AppState.appContext.filesDir, "store/collections.json")

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        all = runCatching {
            Http.json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(BingrCollection.serializer()), file.readText())
        }.getOrDefault(emptyList())
    }

    private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(Http.json.encodeToString(kotlinx.serialization.builtins.ListSerializer(BingrCollection.serializer()), all))
        }
    }

    fun byID(id: String): BingrCollection? = visible.firstOrNull { it.id.equals(id, ignoreCase = true) }

    /** Toevoegen of bijwerken (updatedAt = nu). Nieuwe collecties komen onderaan. */
    fun upsert(c: BingrCollection) {
        ensureLoaded()
        val exists = all.any { it.id == c.id }
        val order = if (exists) c.order else (visible.maxOfOrNull { it.order } ?: -1) + 1
        val n = c.copy(order = order, updatedAt = System.currentTimeMillis(), deleted = false, deletedAt = null)
        all = if (exists) all.map { if (it.id == n.id) n else it } else all + n
        save()
        StateSync.collectionsChanged()
    }

    /** Verwijderen: tombstone, zodat andere apparaten hem ook verwijderen. */
    fun delete(id: String) {
        ensureLoaded()
        val now = System.currentTimeMillis()
        all = all.map { if (it.id == id) it.copy(deleted = true, deletedAt = now, updatedAt = now, items = emptyList()) else it }
        save()
        StateSync.collectionsChanged()
    }

    /** Volgorde opslaan (lijst met id's). */
    fun reorder(ids: List<String>) {
        ensureLoaded()
        val now = System.currentTimeMillis()
        all = all.map { c ->
            val i = ids.indexOf(c.id)
            if (i >= 0 && c.order != i) c.copy(order = i, updatedAt = now) else c
        }
        save()
        StateSync.collectionsChanged()
    }

    fun contains(c: BingrCollection, ref: CollectionRef): Boolean = c.items.any { it.id == ref.id && it.type == ref.type }

    /** Item in een handmatige collectie zetten of eruit halen. */
    fun toggle(c: BingrCollection, ref: CollectionRef) {
        val items = if (contains(c, ref)) c.items.filterNot { it.id == ref.id && it.type == ref.type } else c.items + ref
        upsert(c.copy(items = items))
    }

    /** Uit de sync (roept geen sync-hooks aan). */
    fun applySynced(list: List<BingrCollection>) {
        ensureLoaded()
        if (list == all) return
        all = list
        save()
    }

    /** Ander account op dit apparaat. */
    fun wipe() {
        all = emptyList(); loaded = true
        runCatching { file.delete() }
    }

    fun ref(c: Channel) = CollectionRef(BingrCollection.CHANNEL, c.id)
    fun ref(t: DetailTarget) = when (t) {
        is DetailTarget.MovieT -> CollectionRef(BingrCollection.MOVIE, t.id)
        is DetailTarget.SeriesT -> CollectionRef(BingrCollection.SERIES, t.id)
    }

    /**
     * Inhoud van een collectie in deze (gefilterde) lijst [lib] van bron [sourceID]. Handmatig: in de opgeslagen
     * volgorde; slim: zenders, films en series waarvan de naam een van de zoekwoorden bevat. Pure functie (achtergrond).
     */
    fun resolve(c: BingrCollection, lib: Library, sourceID: String?, limit: Int = 200): List<CollectionEntry> {
        if (c.sourceID != null && sourceID != null && !c.sourceID.equals(sourceID, ignoreCase = true)) return emptyList()
        val group = c.group?.takeIf { it.isNotBlank() }
        if (!c.isSmart) {
            val ch = HashMap<String, Channel>(); lib.channels.forEach { ch.putIfAbsent(it.id, it) }
            val mv = HashMap<String, Movie>(); lib.movies.forEach { mv.putIfAbsent(it.id, it) }
            val se = HashMap<String, Series>(); lib.series.forEach { se.putIfAbsent(it.id, it) }
            return c.items.mapNotNull { r ->
                when (r.type) {
                    BingrCollection.CHANNEL -> ch[r.id]?.let { CollectionEntry.C(it) }
                    BingrCollection.MOVIE -> mv[r.id]?.let { CollectionEntry.M(it) }
                    BingrCollection.SERIES -> se[r.id]?.let { CollectionEntry.S(it) }
                    else -> null
                }
            }.take(limit)
        }
        val words = c.keywords
        if (words.isEmpty()) return emptyList()
        fun hit(name: String, g: String): Boolean {
            if (group != null && g != group) return false
            val f = Net.fold(name)
            return words.any { f.contains(it) }
        }
        val out = ArrayList<CollectionEntry>()
        val types = c.types.ifEmpty { BingrCollection.ALL_TYPES }
        if (BingrCollection.CHANNEL in types) for (x in lib.channels) { if (out.size >= limit) break; if (hit(x.name, x.group)) out.add(CollectionEntry.C(x)) }
        if (BingrCollection.MOVIE in types) for (x in lib.movies) { if (out.size >= limit) break; if (hit(x.title, x.group)) out.add(CollectionEntry.M(x)) }
        if (BingrCollection.SERIES in types) for (x in lib.series) { if (out.size >= limit) break; if (hit(x.title, x.group)) out.add(CollectionEntry.S(x)) }
        return out
    }

    /** Kan deze collectie zenders bevatten (voor Live TV)? */
    fun mayHaveChannels(c: BingrCollection) = if (c.isSmart) BingrCollection.CHANNEL in c.types.ifEmpty { BingrCollection.ALL_TYPES }
        else c.items.any { it.type == BingrCollection.CHANNEL }
    fun mayHaveMovies(c: BingrCollection) = if (c.isSmart) BingrCollection.MOVIE in c.types.ifEmpty { BingrCollection.ALL_TYPES }
        else c.items.any { it.type == BingrCollection.MOVIE }
    fun mayHaveSeries(c: BingrCollection) = if (c.isSmart) BingrCollection.SERIES in c.types.ifEmpty { BingrCollection.ALL_TYPES }
        else c.items.any { it.type == BingrCollection.SERIES }
}

// MARK: startscherm per profiel ------------------------------------------------------------

@Serializable
data class HomeRow(val key: String, val visible: Boolean = true)

/** `homeLayout` in `profile.<id>`: rijen in volgorde met zichtbaarheid; het hele object wint op [updatedAt]. */
@Serializable
data class HomeLayout(val rows: List<HomeRow> = emptyList(), val updatedAt: Long = 0L)

object HomeLayouts {
    /** Vaste rij-sleutels in de standaardvolgorde. */
    val fixedKeys = listOf("continue", "mylist", "top10", "foryou", "recent", "live", "movies", "series", "categories")

    fun title(key: String): String = when (key) {
        "continue" -> L("Continue watching")
        "mylist" -> L("My List")
        "top10" -> "Top 10"
        "foryou" -> L("For you")
        "recent" -> L("Recently added")
        "live" -> L("Live TV")
        "movies" -> L("Movies")
        "series" -> L("Series")
        "categories" -> L("Categories")
        else -> if (key.startsWith("collection:")) Collections.byID(key.removePrefix("collection:"))?.name ?: key else key
    }

    private fun known(key: String, collections: Set<String>): Boolean =
        key in fixedKeys || (key.startsWith("collection:") && key.removePrefix("collection:").lowercase() in collections)

    /**
     * Rijen zoals ze getoond worden: opgeslagen volgorde (onbekende sleutels genegeerd), daarna ontbrekende vaste rijen
     * op hun standaardplek en nieuwe collecties zichtbaar onderaan.
     */
    fun effective(layout: HomeLayout?, collections: List<BingrCollection> = Collections.visible): List<HomeRow> {
        val ids = collections.map { it.id.lowercase() }.toSet()
        val out = ArrayList<HomeRow>()
        val seen = HashSet<String>()
        for (r in layout?.rows ?: emptyList()) {
            val k = normalize(r.key)
            if (!known(k, ids) || !seen.add(k.lowercase())) continue
            out.add(HomeRow(k, r.visible))
        }
        // ontbrekende vaste rijen: na de vorige vaste rij uit de standaardvolgorde
        fixedKeys.forEachIndexed { i, k ->
            if (k in seen) return@forEachIndexed
            val prev = fixedKeys.take(i).lastOrNull { p -> out.any { it.key == p } }
            val at = if (prev == null) 0 else out.indexOfFirst { it.key == prev } + 1
            out.add(at, HomeRow(k, true))
            seen.add(k)
        }
        for (c in collections) {
            if (c.homeKey.lowercase() in seen) continue
            out.add(HomeRow(c.homeKey, true))
            seen.add(c.homeKey.lowercase())
        }
        return out
    }

    private fun normalize(k: String): String = if (k.startsWith("collection:")) {
        val id = k.removePrefix("collection:")
        Collections.visible.firstOrNull { it.id.equals(id, ignoreCase = true) }?.homeKey ?: k
    } else k

    /** Opslaan voor het actieve profiel; onbekende sleutels uit de oude versie blijven achteraan bewaard. */
    fun save(rows: List<HomeRow>) {
        val id = Store.activeProfileID ?: return
        val old = Store.homeLayout(id)
        val ids = Collections.visible.map { it.id.lowercase() }.toSet()
        val unknown = old.rows.filter { !known(normalize(it.key), ids) && rows.none { r -> r.key.equals(it.key, ignoreCase = true) } }
        Store.setHomeLayout(id, HomeLayout(rows + unknown, System.currentTimeMillis()))
    }
}
