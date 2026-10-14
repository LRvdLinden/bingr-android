package nl.lrvdlinden.bingr

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

// Collecties en het startscherm per profiel (build 51), volgens "Build 51: collecties, startscherm,
// terugkijk-correctie en webconsole" in docs/ACCOUNT.md van de iOS-repo. Sinds build 54 horen de collecties
// zelf ook bij een profiel (zie [Collections]); sinds build 74 ziet een subprofiel ook geen kopieën meer van
// collecties van het hoofdprofiel of van andere subprofielen.

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
 * het hoofdprofiel, met dezelfde id's, zodat er niets verdwijnt (tot build 73 naar elk profiel; die kopieën in
 * subprofielen ruimt de sync sinds build 74 op, zie [withoutSharedCopies]).
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

    /**
     * Zichtbare collecties van het actieve profiel in hun volgorde. Build 74: een subprofiel ziet nooit een kopie
     * van een collectie van een ander profiel (zie [sharedIDs]), ook niet voordat de sync die kopie heeft opgeruimd.
     */
    val visible: List<BingrCollection>
        get() {
            // build 56: onthouden zolang de lijst (identiteit) gelijk blijft; dit wordt tijdens het tekenen vaak gelezen
            val src = all
            val by = byProfile
            val main = Store.mainProfileID
            visibleCache?.let { c -> if (c.src === src && c.by === by && c.main == main) return c.list }
            val hide = profileKey?.let { k -> if (main != null && k.equals(main, ignoreCase = true)) null else sharedIDs(by) }
            val v = src.filter { !it.deleted && (hide == null || it.id.lowercase() !in hide) }
                .sortedWith(compareBy({ it.order }, { it.name.lowercase() }))
            visibleCache = VisibleCache(src, by, main, v)
            return v
        }
    private class VisibleCache(val src: List<BingrCollection>, val by: Map<String, List<BingrCollection>>, val main: String?, val list: List<BingrCollection>)
    @Volatile private var visibleCache: VisibleCache? = null

    /**
     * Collectie-id's (kleine letters) die in meer dan één profiel staan (ook als tombstone). Nieuwe collecties krijgen
     * altijd een eigen UUID; een gedeelde id is dus een kopie van een collectie van vóór build 54 (toen voor het hele
     * account), die de migratie naar elk profiel kopieerde. Die horen bij het hoofdprofiel, niet bij subprofielen.
     */
    fun sharedIDs(map: Map<String, List<BingrCollection>>): Set<String> {
        val count = HashMap<String, Int>()
        for (list in map.values) for (id in list.map { it.id.lowercase() }.toSet()) count[id] = (count[id] ?: 0) + 1
        return count.filterValues { it > 1 }.keys
    }

    /**
     * Build 74: in subprofielen de kopieën van collecties die ook in een ander profiel staan ([sharedIDs]) als tombstone
     * markeren, zodat ze ook op andere apparaten (iOS, web) uit dat subprofiel verdwijnen. Het hoofdprofiel houdt
     * zijn versie. Sleutels worden hoofdletterongevoelig met [main] vergeleken. Geeft dezelfde map terug als er niets verandert.
     */
    fun withoutSharedCopies(map: Map<String, List<BingrCollection>>, main: String?): Map<String, List<BingrCollection>> {
        if (main == null || map.keys.none { it.equals(main, ignoreCase = true) }) return map
        val shared = sharedIDs(map)
        if (shared.isEmpty()) return map
        val now = System.currentTimeMillis()
        var changed = false
        val out = map.mapValues { (k, list) ->
            if (k.equals(main, ignoreCase = true) || list.none { !it.deleted && it.id.lowercase() in shared }) list
            else {
                changed = true
                list.map { c -> if (!c.deleted && c.id.lowercase() in shared) c.copy(deleted = true, deletedAt = now, updatedAt = now, items = emptyList()) else c }
            }
        }
        return if (changed) out else map
    }

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

    /**
     * Oude collecties (voor het hele account) naar het hoofdprofiel (zodra er profielen zijn). Bestaande id's blijven
     * staan. Build 74: alleen nog naar het hoofdprofiel; subprofielen zien geen collecties van een ander profiel.
     */
    fun distributeLegacy() {
        if (legacy.isEmpty() || Store.profiles.isEmpty()) return
        val main = Store.mainProfileID ?: return
        val out = byProfile.toMutableMap()
        val k = keyFor(main)
        val cur = out[k] ?: emptyList()
        val ids = cur.map { it.id.lowercase() }.toSet()
        out[k] = cur + legacy.filter { it.id.lowercase() !in ids }
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
        // ongewijzigde profielen: de oude lijst (identiteit) houden, zodat rijen en caches daar niets opnieuw doen
        val cur = byProfile
        if (map != cur) byProfile = map.mapValues { (k, v) -> cur[k]?.takeIf { it == v } ?: v }
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
        // build 56: id-tabellen en gevouwen namen één keer per lijst ([CollectionIndex]) in plaats van per collectie
        val ix = CollectionIndex.of(lib)
        if (!c.isSmart) {
            return c.items.mapNotNull { r ->
                when (r.type) {
                    BingrCollection.CHANNEL -> ix.channelByID[r.id]?.let { CollectionEntry.C(it) }
                    BingrCollection.MOVIE -> ix.movieByID[r.id]?.let { CollectionEntry.M(it) }
                    BingrCollection.SERIES -> ix.seriesByID[r.id]?.let { CollectionEntry.S(it) }
                    else -> null
                }
            }.take(limit)
        }
        val words = c.keywords
        if (words.isEmpty()) return emptyList()
        fun hit(folded: String, g: String): Boolean {
            if (group != null && g != group) return false
            return words.any { folded.contains(it) }
        }
        val out = ArrayList<CollectionEntry>()
        val types = c.types.ifEmpty { BingrCollection.ALL_TYPES }
        if (BingrCollection.CHANNEL in types) for (i in lib.channels.indices) { if (out.size >= limit) break; val x = lib.channels[i]; if (hit(ix.channelNames[i], x.group)) out.add(CollectionEntry.C(x)) }
        if (BingrCollection.MOVIE in types) for (i in lib.movies.indices) { if (out.size >= limit) break; val x = lib.movies[i]; if (hit(ix.movieNames[i], x.group)) out.add(CollectionEntry.M(x)) }
        if (BingrCollection.SERIES in types) for (i in lib.series.indices) { if (out.size >= limit) break; val x = lib.series[i]; if (hit(ix.seriesNames[i], x.group)) out.add(CollectionEntry.S(x)) }
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
    /**
     * Build 86: rij "Categorieën" met tegels (genres en servercategorieën). Alleen aan/uit; de plek is vast:
     * vijf rijen onder "Nu live" (zie HomeScreen), dus de volgorde in de lijst telt voor deze rij niet.
     */
    const val CATEGORY_TILES = "categorytiles"

    /** Vaste rij-sleutels in de standaardvolgorde. */
    val fixedKeys = listOf("continue", "mylist", "top10", "foryou", "recent", "live", CATEGORY_TILES, "movies", "series", "categories")

    fun title(key: String): String = when (key) {
        "continue" -> L("Continue watching")
        "mylist" -> L("My List")
        "top10" -> "Top 10"
        "foryou" -> L("For you")
        "recent" -> L("Recently added")
        "live" -> L("Live TV")
        "movies" -> L("Movies")
        "series" -> L("Series")
        "categories" -> L("Rows per category")
        CATEGORY_TILES -> L("Categories")
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
