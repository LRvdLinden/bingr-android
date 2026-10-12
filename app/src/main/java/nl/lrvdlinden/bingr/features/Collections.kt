package nl.lrvdlinden.bingr

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

// Collecties en het startscherm per profiel (build 51), volgens "Build 51: collecties, startscherm,
// terugkijk-correctie en webconsole" in docs/ACCOUNT.md van de iOS-repo. Sinds build 54 horen de collecties
// zelf ook bij een profiel (zie [Collections]).

/** Afbeelding van een collectie: symbool + kleur (zoals een avatar), of een eigen plaatje ([url]). */
@Serializable
data class CollectionImage(val symbol: String = "⭐", val colorIndex: Int = 0, val url: String? = null) {
    val avatar: PlaylistAvatar get() = PlaylistAvatar(symbol, colorIndex)
}

/** Eén item in een handmatige collectie: soort ("channel", "movie", "series") en de lokale id. */
@Serializable
data class CollectionRef(val type: String, val id: String)

/**
 * Eigen rij ("collectie") van een profiel (build 54; daarvoor voor het hele account). [kind] "manual" (vaste lijst [items]) of "smart"
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

/**
 * Collecties per profiel (build 54). Lokaal in files/store/collections-profiles.json, sync via StateSync
 * "collections" (`byProfile`). Elk profiel (ook een kinderprofiel) maakt en beheert zijn eigen collecties;
 * de inhoud loopt altijd door de (leeftijds)gefilterde bibliotheek van het actieve profiel.
 * Migratie: de collecties van vóór build 54 (voor het hele account, files/store/collections.json) gaan naar
 * elk bestaand profiel, met dezelfde id's, zodat er niets verdwijnt.
 */
object Collections {
    /** Lokale stand: per profiel-id alle collecties (ook tombstones), en verwijderde profielen (id → tijd). */
    @Serializable
    data class LocalState(
        val byProfile: Map<String, List<BingrCollection>> = emptyMap(),
        val removedProfiles: Map<String, Long> = emptyMap(),
        /** Collecties van vóór build 54 die nog naar de profielen moeten (als er nog geen profielen waren). */
        val legacy: List<BingrCollection> = emptyList(),
    )

    /** Per profiel (id zoals in [Store.profiles]) alle collecties, ook tombstones. */
    var byProfile by mutableStateOf(mapOf<String, List<BingrCollection>>()); private set
    private var removedProfiles = mapOf<String, Long>()
    private var legacy = listOf<BingrCollection>()
    private var loaded = false

    /** Profiel waar de collecties nu bij horen: het actieve profiel, anders het hoofdprofiel. */
    private val profileKey: String? get() = Store.activeProfileID ?: Store.mainProfileID

    /** Sleutel in [byProfile] voor dit profiel-id (hoofdletters maken niet uit, iOS gebruikt hoofdletters). */
    private fun keyFor(id: String): String = byProfile.keys.firstOrNull { it.equals(id, ignoreCase = true) } ?: id

    /** Alle collecties van een profiel, ook tombstones. */
    fun of(profileID: String?): List<BingrCollection> {
        ensureLoaded()
        val id = profileID ?: return emptyList()
        return byProfile[keyFor(id)] ?: emptyList()
    }

    /** Alle collecties van het actieve profiel, ook tombstones. */
    val all: List<BingrCollection> get() = of(profileKey)

    /** Zichtbare collecties van het actieve profiel in hun volgorde. */
    val visible: List<BingrCollection>
        get() = all.filter { !it.deleted }.sortedWith(compareBy({ it.order }, { it.name.lowercase() }))

    private val legacyFile: File get() = File(AppState.appContext.filesDir, "store/collections.json")
    private val file: File get() = File(AppState.appContext.filesDir, "store/collections-profiles.json")

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val st = runCatching { Http.json.decodeFromString(LocalState.serializer(), file.readText()) }.getOrNull()
        if (st != null) {
            byProfile = st.byProfile; removedProfiles = st.removedProfiles; legacy = st.legacy
        } else {
            // eerste start met build 54: de oude collecties (voor het hele account) overnemen
            legacy = runCatching {
                Http.json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(BingrCollection.serializer()), legacyFile.readText())
            }.getOrDefault(emptyList())
            save()
        }
        distributeLegacy()
    }

    /** Oude collecties naar elk bestaand profiel (zodra er profielen zijn). Bestaande id's blijven staan. */
    fun distributeLegacy() {
        if (legacy.isEmpty() || Store.profiles.isEmpty()) return
        val out = byProfile.toMutableMap()
        for (p in Store.profiles) {
            val k = keyFor(p.id)
            val cur = out[k] ?: emptyList()
            val ids = cur.map { it.id.lowercase() }.toSet()
            out[k] = cur + legacy.filter { it.id.lowercase() !in ids }
        }
        byProfile = out
        legacy = emptyList()
        save()
        // de oude lijst blijft als reservekopie staan, onder een andere naam
        runCatching { if (legacyFile.exists()) legacyFile.renameTo(File(legacyFile.parentFile, "collections-pre54.json")) }
    }

    private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(Http.json.encodeToString(LocalState.serializer(), LocalState(byProfile, removedProfiles, legacy)))
        }
    }

    private fun setActive(list: List<BingrCollection>) {
        val id = profileKey ?: return
        byProfile = byProfile + (keyFor(id) to list)
        save()
        StateSync.collectionsChanged()
    }

    fun byID(id: String): BingrCollection? = visible.firstOrNull { it.id.equals(id, ignoreCase = true) }

    /** Toevoegen of bijwerken (updatedAt = nu) voor het actieve profiel. Nieuwe collecties komen onderaan. */
    fun upsert(c: BingrCollection) {
        ensureLoaded()
        val cur = all
        val exists = cur.any { it.id == c.id }
        val order = if (exists) c.order else (visible.maxOfOrNull { it.order } ?: -1) + 1
        val n = c.copy(order = order, updatedAt = System.currentTimeMillis(), deleted = false, deletedAt = null)
        setActive(if (exists) cur.map { if (it.id == n.id) n else it } else cur + n)
    }

    /** Verwijderen: tombstone, zodat andere apparaten hem ook verwijderen. */
    fun delete(id: String) {
        ensureLoaded()
        val now = System.currentTimeMillis()
        setActive(all.map { if (it.id == id) it.copy(deleted = true, deletedAt = now, updatedAt = now, items = emptyList()) else it })
    }

    /** Volgorde opslaan (lijst met id's). */
    fun reorder(ids: List<String>) {
        ensureLoaded()
        val now = System.currentTimeMillis()
        setActive(all.map { c ->
            val i = ids.indexOf(c.id)
            if (i >= 0 && c.order != i) c.copy(order = i, updatedAt = now) else c
        })
    }

    fun contains(c: BingrCollection, ref: CollectionRef): Boolean = c.items.any { it.id == ref.id && it.type == ref.type }

    /** Item in een handmatige collectie zetten of eruit halen. */
    fun toggle(c: BingrCollection, ref: CollectionRef) {
        val items = if (contains(c, ref)) c.items.filterNot { it.id == ref.id && it.type == ref.type } else c.items + ref
        upsert(c.copy(items = items))
    }

    /** Lokale stand voor de sync. */
    fun snapshot(): LocalState { ensureLoaded(); return LocalState(byProfile, removedProfiles, legacy) }

    /** Uit de sync (roept geen sync-hooks aan): per profiel de samengevoegde lijst. */
    fun applySynced(map: Map<String, List<BingrCollection>>, removed: Map<String, Long>) {
        ensureLoaded()
        if (map == byProfile && removed == removedProfiles) return
        byProfile = map
        removedProfiles = removed
        save()
    }

    /**
     * Profiel verwijderd: zijn collecties weg, en onthouden (90 dagen) zodat de sync ze ook van de server haalt.
     * [fromSync]: het profiel is elders verwijderd; dan niet zelf een upload starten.
     */
    fun profileDeleted(id: String, fromSync: Boolean = false) {
        ensureLoaded()
        val k = byProfile.keys.filter { it.equals(id, ignoreCase = true) }
        byProfile = byProfile - k.toSet()
        removedProfiles = removedProfiles + (id to System.currentTimeMillis())
        save()
        if (!fromSync) StateSync.collectionsChanged()
    }

    /** Ander account op dit apparaat. */
    fun wipe() {
        byProfile = emptyMap(); removedProfiles = emptyMap(); legacy = emptyList(); loaded = true
        runCatching { file.delete() }
        runCatching { legacyFile.delete() }
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
