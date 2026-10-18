package nl.lrvdlinden.bingr

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Build 128: "Eén lijst over alle servers" (contract in docs/ACCOUNT.md van de iOS-repo, sectie Build 128).
 *
 * Per profiel één Verder kijken, Mijn lijst, favorieten, recente zenders, geschiedenis en collecties over alle servers:
 * - items van een **andere bestaande server** worden uit de catalogus van die server gehaald (cache op dit apparaat,
 *   zo nodig stil van het netwerk) en openen/spelen daar, zonder de actieve server te wisselen ([Movie.sourceID] e.d.);
 * - items van een **verwijderde server** (of die niet meer op hun server staan) worden op de andere servers gezocht
 *   (actieve eerst): gevonden → remap-regel in [CatalogMeta] en alle lijsten/collecties van alle profielen verhuizen
 *   ([StateSync.remapNow]); niet gevonden → het item blijft staan, en bij aanklikken komt de melding met
 *   Verwijderen / Zoeken / Annuleren ([notice]).
 * - Op de achtergrond: alleen als er items zijn die het nodig hebben, niet tijdens het afspelen, met time-outs, één
 *   catalogus tegelijk in het geheugen; elke server hooguit eens per 6 uur opnieuw doorzocht voor dezelfde items.
 * - Migratie (eerste start van build 128, vlag per apparaat): meta van alle items van alle profielen aanvullen uit de
 *   catalogus van hun eigen server (voortgang: eerst de titel/artwork die er al is).
 */
@OptIn(FlowPreview::class)
object AllServers {
    /** Verhoogt als er items van andere servers gevonden/verhuisd zijn (rijen tekenen opnieuw). */
    var version by mutableStateOf(0); private set
    /** Bezig met zoeken na aanklikken. */
    var busy by mutableStateOf(false); private set
    /** Melding "niet gevonden" (Verwijderen / Zoeken / Annuleren). */
    var notice by mutableStateOf<Notice?>(null)

    enum class Field { CONTINUE, MYLIST, FAVORITES, RECENT, HISTORY, COLLECTION }

    class Notice(
        val title: String, val message: String, val field: Field?, val removeLabel: String?,
        val remove: (() -> Unit)?, val searchFor: String,
    )

    /** Items van andere bestaande servers (metaKey → Movie/Series/Channel met sourceID). */
    @Volatile private var found: Map<String, Any> = emptyMap()
    /** Gids van zenders van andere servers (metaKey → programma's, gesorteerd), voor gids en terugkijken. */
    @Volatile private var epg: Map<String, List<EPGProgram>> = emptyMap()
    /** Server bestaat, maar het item staat niet (meer) in zijn catalogus (metaKey). */
    @Volatile private var absent: Set<String> = emptySet()
    /** Laatste zoekpoging per sleutel (metaKey → tijd), zodat de achtergrond niet steeds opnieuw zoekt. */
    private val tried = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val RETRY = 6 * 3_600_000L
    private const val MIGRATED = "allservers.migrated128"
    private val lock = Mutex()
    private var started = false
    private var job: Job? = null

    private val X = AllServersLogic

    fun wipe() { found = emptyMap(); absent = emptySet(); tried.clear(); notice = null; runCatching { Prefs.remove(MIGRATED) }; version++ }

    // MARK: servers

    private fun activeID(): String? = Playlists.source?.id
    fun isActive(sid: String?): Boolean = sid != null && sid.equals(activeID(), ignoreCase = true)
    fun server(sid: String?): PlaylistSource? = sid?.let { s -> Store.playlists.firstOrNull { it.id.equals(s, ignoreCase = true) } }
    private fun serverName(sid: String, key: String?): String =
        server(sid)?.name ?: CatalogMeta.serverName(sid, key) ?: L("a removed server")
    /** Servers op volgorde van voorrang: actieve eerst, dan de overige zoals in de lijst. */
    private fun order(): List<PlaylistSource> {
        val a = activeID()
        return Store.playlists.sortedBy { if (it.id.equals(a, ignoreCase = true)) 0 else 1 }
    }

    // MARK: start en verversen

    /** Eén keer starten (Main): bij wijzigingen in lijsten, servers, profiel of bibliotheek rustig opnieuw kijken. */
    fun start() {
        if (started) return
        started = true
        CatalogMeta.ensureLoaded()
        job = AppScope.launch {
            snapshotFlow {
                listOf(Store.activeProfileID, Store.myList, Store.favorites, Store.recentChannels, Store.progress.keys,
                    Store.history.size, Collections.byProfile, Store.playlists.map { it.id }, activeID(),
                    System.identityHashCode(Playlists.library), CatalogMeta.version)
            }.debounce(2_000).collectLatest { quietly { refresh() } }
        }
    }

    private suspend fun quietly(block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }

    /** Wat een profiel bewaart (sleutel "<server>|<item>"), met wat we er al van weten. */
    private class Ref(val key: String, val field: Field, val kind: String?, val title: String?, val image: String?,
                      val se: Pair<Int, Int>? = null)

    /** Verwijzingen van het actieve profiel (Main). */
    private fun activeRefs(): List<Ref> {
        val out = ArrayList<Ref>()
        Store.myList.forEach { out += Ref(it, Field.MYLIST, null, null, null) }
        Store.favorites.forEach { out += Ref(it, Field.FAVORITES, "channel", null, null) }
        Store.recentChannels.forEach { out += Ref(it, Field.RECENT, "channel", null, null) }
        Store.progress.forEach { (k, e) ->
            if (Store.belongsToActive(e.profileID)) out += Ref("${e.playlistID ?: activeID() ?: return@forEach}|$k", Field.CONTINUE, null, e.title, e.artwork)
        }
        Store.history.forEach { h ->
            val pid = h.playlistID ?: return@forEach
            val kind = when (h.type) { MediaType.LIVE -> "channel"; MediaType.MOVIE -> "movie"; MediaType.EPISODE -> "episode"; else -> null }
            out += Ref("$pid|${h.itemID}", Field.HISTORY, kind, h.title, h.artwork, X.seasonEpisode(h.subtitle))
        }
        for (c in Collections.visible) if (!c.isSmart) for (r in c.items) {
            Collections.keyOf(c, r, activeID())?.let { out += Ref(it, Field.COLLECTION, r.type, null, null) }
        }
        return out
    }

    /** Verwijzingen van de andere profielen (alleen voor de migratie van de meta). */
    private fun otherProfileRefs(): List<Ref> {
        val out = ArrayList<Ref>()
        for (p in Store.profiles) {
            if (p.id == Store.activeProfileID) continue
            val d = Store.profileData(p.id)
            (d.myList).forEach { out += Ref(it, Field.MYLIST, null, null, null) }
            (d.favorites + d.recent).forEach { out += Ref(it, Field.FAVORITES, "channel", null, null) }
            d.progress.forEach { (k, e) -> e.playlistID?.let { out += Ref("$it|$k", Field.CONTINUE, null, e.title, e.artwork) } }
        }
        for (list in Collections.byProfile.values) for (c in list) if (!c.deleted && !c.isSmart) for (r in c.items) {
            Collections.keyOf(c, r)?.let { out += Ref(it, Field.COLLECTION, r.type, null, null) }
        }
        return out
    }

    /** Opzoektabellen van één (gefilterde) catalogus. */
    private class Ix(val lib: Library) {
        val movies = HashMap<String, Movie>().apply { lib.movies.forEach { putIfAbsent(it.id, it) } }
        val series = HashMap<String, Series>().apply { lib.series.forEach { putIfAbsent(it.id, it) } }
        val channels = HashMap<String, Channel>().apply { lib.channels.forEach { putIfAbsent(it.id, it) } }
        val seriesByTitle: Map<String, Series> by lazy { HashMap<String, Series>().apply { lib.series.forEach { putIfAbsent(AllServersLogic.normTitle(it.title), it) } } }
        fun candidates(): Sequence<AllServersLogic.Candidate> = sequence {
            for (m in lib.movies) yield(AllServersLogic.Candidate("", m.id, "movie", m.title, m.year))
            for (s in lib.series) yield(AllServersLogic.Candidate("", s.id, "series", s.title, s.year))
            for (c in lib.channels) yield(AllServersLogic.Candidate("", c.id, "channel", c.name, epg = c.epgID))
        }
    }

    /** Gefilterde catalogus van [p] (kinderslot, verborgen groepen, leeftijdsgrens van het actieve profiel). */
    private suspend fun filtered(p: PlaylistSource, allowNetwork: Boolean): Library? {
        if (isActive(p.id)) return Playlists.library.takeIf { !it.isEmpty }
        val raw = Playlists.catalogOf(p, allowNetwork) ?: return null
        val ctx = withContext(Dispatchers.Main) { Playlists.context(p) }
        return withContext(Dispatchers.Default) { runCatching { Playlists.apply(raw, ctx) }.getOrNull() }
    }

    private suspend fun waitForIdle() {
        // niet tijdens het afspelen (en niet terwijl de actieve server nog laadt)
        while (AppState.playing != null || Playlists.isLoading) delay(5_000)
    }

    private fun recordMeta(key: String, sid: String, sname: String, item: Any?, ref: Ref?) {
        when (item) {
            is Movie -> CatalogMeta.record(key, "movie", item.title, item.year ?: X.yearIn(item.title), img = item.poster, sid = sid, sname = sname)
            is Series -> CatalogMeta.record(key, "series", item.title, item.year ?: X.yearIn(item.title), img = item.poster, sid = sid, sname = sname)
            is Channel -> CatalogMeta.record(key, "channel", item.name, null, img = item.logo, sid = sid, sname = sname, channelName = item.name, epg = item.epgID)
            // voortgang/geschiedenis: titel en artwork staan er al (geschiedenis van een aflevering: S1:E2 uit de ondertitel)
            null -> if ((ref?.field == Field.CONTINUE || ref?.field == Field.HISTORY) && ref.title != null && !CatalogMeta.hasMeta(key)) {
                val se = ref.se
                val kind = ref.kind ?: if (se != null || ref.field == Field.CONTINUE) "episode" else "movie"
                if (kind != "channel") CatalogMeta.record(key, kind, ref.title, if (kind == "movie") X.yearIn(ref.title) else null, img = ref.image, sid = sid, sname = sname,
                    ep = se?.let { CatalogMeta.Ep(null, ref.title, it.first, it.second) })
            }
        }
    }

    /** Eén ronde: items van andere servers ophalen, meta aanvullen, verhuizen wat nergens meer staat. */
    private suspend fun refresh() = lock.withLock {
        val (refs, migrating) = withContext(Dispatchers.Main) {
            val m = !Prefs.bool(MIGRATED, false)
            (activeRefs() + if (m) otherProfileRefs() else emptyList()) to m
        }
        if (refs.isEmpty()) {
            if (found.isNotEmpty() || absent.isNotEmpty()) { found = emptyMap(); absent = emptySet(); withContext(Dispatchers.Main) { version++ } }
            if (migrating) withContext(Dispatchers.Main) { Prefs.put(MIGRATED, true) }
            return@withLock
        }
        val byServer = refs.groupBy { X.serverOf(it.key)?.lowercase(Locale.ROOT) ?: "" }
        val newFound = HashMap<String, Any>()
        val newEpg = HashMap<String, List<EPGProgram>>()
        val newAbsent = HashSet<String>()
        val needs = ArrayList<Ref>()
        var complete = true
        for (p in Store.playlists.toList()) {
            val list = byServer[p.id.lowercase(Locale.ROOT)] ?: continue
            val lib = filtered(p, allowNetwork = false) ?: run {
                waitForIdle()
                filtered(p, allowNetwork = true)
            }
            if (lib == null) { complete = false; continue }
            val ix = withContext(Dispatchers.Default) { Ix(lib) }
            for (r in list) {
                val id = X.itemOf(r.key)
                val item: Any? = when (r.kind) {
                    "channel" -> ix.channels[id]
                    "movie" -> ix.movies[id]
                    "series" -> ix.series[id]
                    else -> ix.movies[id] ?: ix.series[id] ?: ix.channels[id]
                }
                val mk = X.metaKey(r.key)
                recordMeta(r.key, p.id, p.name, item, r)
                if (item == null) {
                    // voortgang van een aflevering: de serie (op titel) bestaat nog → niet "weg"
                    val series = if (r.field == Field.CONTINUE && r.title != null) ix.seriesByTitle[X.normTitle(r.title)] else null
                    if (series != null) { if (!isActive(p.id)) newFound[mk] = series.copy(sourceID = p.id); continue }
                    if (r.field == Field.CONTINUE || r.field == Field.HISTORY) {
                        // voortgang/geschiedenis zonder item in de catalogus (bijv. aflevering): alleen als we weten wat het is
                        if (CatalogMeta.wanted(r.key) == null) continue
                    }
                    newAbsent += mk; needs += r
                } else if (!isActive(p.id)) newFound[mk] = when (item) {
                    // build 128: gids van deze zender (alleen wat er nog toe doet: max. 8 dagen terug), voor gids en terugkijken
                    is Channel -> item.copy(sourceID = p.id).also { c ->
                        val cutoff = System.currentTimeMillis() - 8 * 86_400_000L
                        lib.epg[c.epgID ?: c.id]?.filter { it.end > cutoff }?.takeIf { it.isNotEmpty() }?.let { newEpg[mk] = it }
                    }
                    is Movie -> item.copy(sourceID = p.id)
                    is Series -> item.copy(sourceID = p.id)
                    else -> item
                }
            }
        }
        // verwijzingen naar een server die er niet meer is (tombstone of onbekend)
        for ((sid, list) in byServer) {
            if (sid.isEmpty() || server(sid) != null) continue
            needs += list
        }
        // alleen opnieuw tekenen als er echt iets anders is
        val changed = newFound != found || newAbsent != absent || newEpg.keys != epg.keys
        found = newFound; absent = newAbsent; epg = newEpg
        withContext(Dispatchers.Main) {
            if (changed) version++
            if (migrating && complete) Prefs.put(MIGRATED, true)
        }
        // alleen voor het actieve profiel zoeken (de regels gelden daarna voor alle profielen)
        val active = withContext(Dispatchers.Main) { activeRefs().map { X.metaKey(it.key) }.toSet() }
        val now = System.currentTimeMillis()
        val todo = needs.filter { X.metaKey(it.key) in active && now - (tried[X.metaKey(it.key)] ?: 0L) > RETRY }
            .distinctBy { X.metaKey(it.key) }
        if (todo.isEmpty() || Store.playlists.isEmpty()) return@withLock
        waitForIdle()
        val moved = match(todo, allowNetwork = true)
        todo.forEach { tried[X.metaKey(it.key)] = now }
        if (moved.isNotEmpty()) withContext(Dispatchers.Main) { StateSync.remapNow() }
    }

    // MARK: zoeken op de andere servers

    private fun wantedFor(r: Ref): AllServersLogic.Wanted? {
        CatalogMeta.wanted(r.key)?.let { return it }
        val t = r.title ?: return null
        // voortgang/geschiedenis zonder meta: de titel die er al staat (films; afleveringen vragen seizoen/aflevering)
        return if (r.field == Field.CONTINUE || r.field == Field.HISTORY) AllServersLogic.Wanted(r.kind ?: "movie", t, X.yearIn(t)) else null
    }

    /**
     * Items in [refs] op de servers zoeken (actieve eerst, de oude server niet). Gevonden → remap-regel en meta van de
     * nieuwe sleutel. Geeft de verhuizingen (oud → nieuw).
     */
    private suspend fun match(refs: List<Ref>, allowNetwork: Boolean): Map<String, String> {
        val pending = refs.mapNotNull { r -> wantedFor(r)?.let { r to it } }.toMutableList()
        if (pending.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (p in order()) {
            if (pending.isEmpty()) break
            val mine = pending.filter { (r, _) -> !X.sameServer(X.serverOf(r.key), p.id) }
            if (mine.isEmpty()) continue
            val lib = (withTimeoutOrNull(120_000) { filtered(p, allowNetwork) }) ?: continue
            val cands = withContext(Dispatchers.Default) { Ix(lib).candidates().map { it.copy(sid = p.id) }.toList() }
            for ((r, w) in mine) {
                val hit = withContext(Dispatchers.Default) { X.best(w, cands, listOf(p.id)) } ?: continue
                var newKey = hit.key
                if (w.kind == "episode") {
                    // aflevering: serie gevonden, dan seizoen + aflevering op die server
                    val ep = CatalogMeta.episode(r.key) ?: continue
                    val s = lib.series.firstOrNull { it.id == hit.id } ?: continue
                    val eps = withTimeoutOrNull(30_000) { Playlists.episodes(s.copy(sourceID = p.id)) } ?: continue
                    val e = eps.firstOrNull { it.season == ep.season && it.number == ep.episode } ?: continue
                    newKey = "${p.id}|${e.id}"
                    CatalogMeta.record(newKey, "episode", CatalogMeta.title(r.key) ?: s.title, s.year, img = e.still ?: s.poster, sid = p.id, sname = p.name,
                        ep = CatalogMeta.Ep("${p.id}|${s.id}", s.title, ep.season, ep.episode))
                } else {
                    val item: Any? = when (hit.kind) {
                        "movie" -> lib.movies.firstOrNull { it.id == hit.id }
                        "series" -> lib.series.firstOrNull { it.id == hit.id }
                        else -> lib.channels.firstOrNull { it.id == hit.id }
                    }
                    recordMeta(newKey, p.id, p.name, item, null)
                }
                if (CatalogMeta.addRemap(r.key, newKey) || CatalogMeta.target(r.key) != null) out[r.key] = newKey
                pending.removeAll { it.first.key == r.key }
            }
        }
        return out
    }

    // MARK: voor de rijen

    /** Gids van een zender van een andere server (leeg als die server geen gids heeft). */
    fun programs(c: Channel): List<EPGProgram> = c.sourceID?.let { epg[X.metaKey("$it|${c.id}")] } ?: emptyList()

    /** Film/serie/zender van een andere server (gevonden), of null. */
    fun item(key: String): Any? = found[X.metaKey(key)]

    /** Staat dit item op een andere server die er niet meer is, of niet meer op zijn server? */
    fun isOrphan(key: String): Boolean {
        val sid = X.serverOf(key) ?: return false
        if (isActive(sid)) return false
        return server(sid) == null || X.metaKey(key) in absent
    }

    /** Tegel voor een item van een andere server: het echte item, anders uit de meta ("Onbekende titel"). */
    fun movieTile(key: String): Movie? {
        val sid = X.serverOf(key) ?: return null
        (found[X.metaKey(key)] as? Movie)?.let { return it }
        if (found[X.metaKey(key)] != null) return null
        if (!isOrphan(key)) return null
        val k = CatalogMeta.kind(key)
        if (k != null && k != "movie") return null
        return Movie(id = X.itemOf(key), title = CatalogMeta.title(key) ?: L("Unknown title"), poster = CatalogMeta.image(key), sourceID = sid)
    }
    fun seriesTile(key: String): Series? {
        val sid = X.serverOf(key) ?: return null
        (found[X.metaKey(key)] as? Series)?.let { return it }
        if (found[X.metaKey(key)] != null || !isOrphan(key)) return null
        if (CatalogMeta.kind(key) != "series") return null
        return Series(id = X.itemOf(key), title = CatalogMeta.title(key) ?: L("Unknown title"), poster = CatalogMeta.image(key), sourceID = sid)
    }
    fun channelTile(key: String): Channel? {
        val sid = X.serverOf(key) ?: return null
        (found[X.metaKey(key)] as? Channel)?.let { return it }
        if (found[X.metaKey(key)] != null || !isOrphan(key)) return null
        return Channel(id = X.itemOf(key), name = CatalogMeta.title(key) ?: L("Unknown title"), logo = CatalogMeta.image(key), sourceID = sid)
    }

    /** Item in een handmatige collectie van een andere (of verwijderde) server. */
    fun collectionEntry(type: String, key: String): CollectionEntry? = when (type) {
        BingrCollection.CHANNEL -> channelTile(key)?.let { CollectionEntry.C(it) }
        BingrCollection.MOVIE -> movieTile(key)?.let { CollectionEntry.M(it) }
        BingrCollection.SERIES -> (seriesTile(key) ?: (found[X.metaKey(key)] as? Series))?.let { CollectionEntry.S(it) }
            ?: if (isOrphan(key)) Series(id = X.itemOf(key), title = CatalogMeta.title(key) ?: L("Unknown title"),
                poster = CatalogMeta.image(key), sourceID = X.serverOf(key)).let { CollectionEntry.S(it) } else null
        else -> null
    }

    /**
     * Mijn lijst over alle servers: van de actieve server via [local], van andere uit [found] of de meta. Hetzelfde
     * werk op twee servers = één tegel (de actieve server gaat voor); volgorde = laatst toegevoegd eerst.
     */
    fun myList(keys: Set<String>, pid: String?, local: (String) -> DetailTarget?): List<DetailTarget> {
        @Suppress("UNUSED_VARIABLE") val v = version
        val times = StateSync.addedTimes(Store.activeProfileID, "myList")
        val sorted = keys.sortedByDescending { times[it] ?: 0L }
        val seen = HashSet<String>()
        val out = ArrayList<DetailTarget>()
        // eerst de actieve server (voorrang bij dubbele), daarna de rest; volgorde komt van [sorted]
        val picked = HashMap<String, DetailTarget>()
        for (k in sorted.sortedBy { if (X.sameServer(X.serverOf(it), pid)) 0 else 1 }) {
            val t = if (X.sameServer(X.serverOf(k), pid)) local(k) else
                movieTile(k)?.let { DetailTarget.MovieT(it) } ?: seriesTile(k)?.let { DetailTarget.SeriesT(it) }
                    ?: (found[X.metaKey(k)] as? Series)?.let { DetailTarget.SeriesT(it) }
            t ?: continue
            val d = X.dedupKey(if (t.isSeries) "series" else "movie", t.title, t.year, null)
            if (seen.add(d)) picked[k] = t
        }
        for (k in sorted) picked[k]?.let { out += it }
        return out
    }

    /** Zenders (favorieten of recent) over alle servers; dezelfde zender op twee servers = één (actieve eerst). */
    fun channels(keys: List<String>, pid: String?, local: (String) -> Channel?): List<Channel> {
        @Suppress("UNUSED_VARIABLE") val v = version
        val seen = HashSet<String>()
        val byKey = HashMap<String, Channel>()
        for (k in keys.sortedBy { if (X.sameServer(X.serverOf(it), pid)) 0 else 1 }) {
            val c = if (X.sameServer(X.serverOf(k), pid)) local(k) else channelTile(k)
            c ?: continue
            if (seen.add(X.normChannel(c.name).ifEmpty { k })) byKey[k] = c
        }
        return keys.mapNotNull { byKey[it] }
    }

    // MARK: aanklikken

    /**
     * Een titel openen (ScreensNav.detail). true = afgehandeld: item van een verwijderde server of dat niet meer op zijn
     * server staat (eerst zoeken, dan verhuizen en openen, anders de melding).
     */
    fun intercept(t: DetailTarget): Boolean {
        val sid = t.sourceID ?: return false
        val key = "$sid|${t.id}"
        if (!isOrphan(key)) return false
        val field = fieldOf(key)
        rescue(key, if (t.isSeries) "series" else "movie", field, CatalogMeta.title(key) ?: t.title) { nk -> openKey(nk) }
        return true
    }

    /** Zender aanklikken (favorieten, recent, collectie): van de actieve of een andere bestaande server meteen spelen. */
    fun playChannel(c: Channel, zap: List<Channel>?, field: Field) {
        val sid = c.sourceID
        if (sid != null && !isActive(sid)) {
            val key = "$sid|${c.id}"
            if (isOrphan(key)) {
                rescue(key, "channel", field, CatalogMeta.title(key) ?: c.name) { nk -> openKey(nk, play = true) }
                return
            }
            Store.pushRecentChannel(sid, c.id)
            AppState.play(Playables.channel(c, null), zap = zap?.filter { it.sourceID.equals(sid, ignoreCase = true) } ?: emptyList())
            return
        }
        Store.pushRecentChannel(Playlists.source?.id ?: return, c.id)
        AppState.play(Playables.channel(c, Playlists.library.nowPlaying(c)), zap = zap?.filter { it.sourceID == null } ?: emptyList())
    }

    /** Verder kijken van een andere server (of verwijderde): op hetzelfde punt verder. */
    suspend fun resume(sid: String, key: String, title: String, restart: Boolean): DetailTarget? {
        val full = "$sid|$key"
        if (isOrphan(full)) {
            withContext(Dispatchers.Main) {
                rescue(full, CatalogMeta.kind(full) ?: "movie", Field.CONTINUE, title) { nk ->
                    AppScope.launch { ResumePlayer.play(X.itemOf(nk), title, restart, X.serverOf(nk)) }
                }
            }
            return null
        }
        val p = server(sid) ?: return null
        val lib = filtered(p, allowNetwork = true) ?: return null
        lib.movies.firstOrNull { it.id == key }?.let { m ->
            val mm = m.copy(sourceID = p.id)
            var item = Playables.movie(mm, Trakt.imdb(mm))
            if (restart) item = item.copy(resumeAt = 0.0)
            withContext(Dispatchers.Main) { AppState.play(item) }
            return null
        }
        val norm = X.normTitle(title)
        val candidates = lib.series.filter { X.normTitle(it.title) == norm || it.episodes.any { e -> e.id == key } }.map { it.copy(sourceID = p.id) }
        for (s in candidates) {
            val eps = if (s.episodes.any { it.id == key }) s.episodes else withTimeoutOrNull(30_000) { Playlists.episodes(s) } ?: emptyList()
            val e = eps.firstOrNull { it.id == key } ?: continue
            var item = Playables.episode(e, s, Trakt.imdb(s))
            if (restart) item = item.copy(resumeAt = 0.0)
            withContext(Dispatchers.Main) { AppState.play(item) }
            return null
        }
        return candidates.firstOrNull()?.let { DetailTarget.SeriesT(it) }
    }

    /** Waar dit item in het actieve profiel staat (voor de knop Verwijderen uit …). */
    private fun fieldOf(key: String): Field? {
        val sid = X.serverOf(key) ?: return null
        val id = X.itemOf(key)
        return when {
            Store.inMyList(sid, id) -> Field.MYLIST
            Store.isFavorite(sid, id) -> Field.FAVORITES
            Collections.visible.any { c -> !c.isSmart && c.items.any { r -> r.id == id && X.sameServer(r.sid ?: c.sourceID, sid) } } -> Field.COLLECTION
            Store.progress[id]?.playlistID?.let { X.sameServer(it, sid) } == true -> Field.CONTINUE
            Store.recentChannels.any { Store.sameKey(it, sid, id) } -> Field.RECENT
            else -> null
        }
    }

    /** Sleutel na een verhuizing openen (detail of afspelen), zonder van server te wisselen. */
    private fun openKey(key: String, play: Boolean = false) {
        AppScope.launch {
            val sid = X.serverOf(key) ?: return@launch
            val p = server(sid) ?: return@launch
            val lib = filtered(p, allowNetwork = true) ?: return@launch
            val id = X.itemOf(key)
            val other = !isActive(sid)
            lib.channels.firstOrNull { it.id == id }?.let { c ->
                val ch = if (other) c.copy(sourceID = p.id) else c
                Store.pushRecentChannel(p.id, c.id)
                AppState.play(Playables.channel(ch, if (other) null else lib.nowPlaying(c)))
                return@launch
            }
            val t: DetailTarget = lib.movies.firstOrNull { it.id == id }?.let { DetailTarget.MovieT(if (other) it.copy(sourceID = p.id) else it) }
                ?: lib.series.firstOrNull { it.id == id }?.let { DetailTarget.SeriesT(if (other) it.copy(sourceID = p.id) else it) }
                ?: return@launch
            if (play && t is DetailTarget.MovieT) AppState.play(Playables.movie(t.movie, Trakt.imdb(t.movie)))
            else ScreensNav.push(ScreenEntry.Detail(t))
        }
    }

    /**
     * Niet (meer) beschikbaar: nu zoeken op de andere servers (ook als dat op de achtergrond nog niet gebeurd is).
     * Gevonden → verhuizen en [then] met de nieuwe sleutel; anders de melding.
     */
    private fun rescue(key: String, kind: String, field: Field?, title: String, then: (String) -> Unit) {
        CatalogMeta.target(key)?.let { nk -> if (server(X.serverOf(nk)) != null) { then(nk); return } }
        if (busy) return
        busy = true
        AppScope.launch {
            val ref = Ref(key, field ?: Field.MYLIST, kind, title, null)
            val moved = try { match(listOf(ref), allowNetwork = true) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyMap() }
            busy = false
            val nk = moved[key]
            if (nk != null) {
                StateSync.remapNow()
                then(nk)
                return@launch
            }
            tried[X.metaKey(key)] = System.currentTimeMillis()
            notice = noticeFor(key, field, title)
        }
    }

    private fun noticeFor(key: String, field: Field?, title: String): Notice {
        val sid = X.serverOf(key) ?: ""
        val exists = server(sid) != null
        val name = serverName(sid, key)
        val others = Store.playlists.filter { !it.id.equals(sid, ignoreCase = true) }.map { it.name }
        val msg = when {
            !exists && others.isNotEmpty() -> L("“%@” was on “%@”, but that server has been removed. This title wasn't found on your other servers (%@).", title, name, others.joinToString(", "))
            !exists -> L("“%@” was on “%@”, but that server has been removed, and you have no other server to search.", title, name)
            others.isNotEmpty() -> L("“%@” is no longer on “%@” and wasn't found on %@ either.", title, name, others.joinToString(", "))
            else -> L("“%@” is no longer on “%@”, and you have no other server to search.", title, name)
        }
        val id = X.itemOf(key)
        val (label, remove) = when (field) {
            Field.MYLIST -> L("Remove from My List") to { if (Store.inMyList(sid, id)) Store.toggleMyList(sid, id) }
            Field.FAVORITES -> L("Remove from favorites") to { if (Store.isFavorite(sid, id)) Store.toggleFavorite(sid, id) }
            Field.CONTINUE -> L("Remove from Continue watching") to { Store.clearProgress(id) }
            Field.RECENT -> L("Remove") to { Store.removeRecentChannel(sid, id) }
            Field.HISTORY -> L("Remove from history") to { Store.history.filter { it.itemID == id && X.sameServer(it.playlistID, sid) }.forEach { Store.removeHistory(it) } }
            Field.COLLECTION -> L("Remove from collection") to {
                Collections.visible.filter { c -> !c.isSmart && c.items.any { r -> r.id == id && X.sameServer(r.sid ?: c.sourceID, sid) } }
                    .forEach { c -> Collections.upsert(c.copy(items = c.items.filterNot { r -> r.id == id && X.sameServer(r.sid ?: c.sourceID, sid) })) }
            }
            null -> null to null
        }
        return Notice(title, msg, field, label, remove, TitleCleaner.parse(title).title.ifBlank { title })
    }

    // MARK: vastleggen bij afspelen

    /** Bij afspelen (na een minuut kijken): meta van dit item, met serie + seizoen/aflevering voor afleveringen. */
    fun recordPlayed(item: PlayableItem) {
        val sid = item.sourceID ?: activeID() ?: return
        val p = server(sid) ?: return
        val key = "$sid|${item.channelID ?: item.id}"
        when (item.type) {
            MediaType.LIVE -> {
                val c = if (isActive(sid)) Playlists.library.channels.firstOrNull { it.id == (item.channelID ?: item.id) } else found[X.metaKey(key)] as? Channel
                CatalogMeta.record(key, "channel", item.title, null, img = item.artwork, sid = p.id, sname = p.name, channelName = item.title, epg = c?.epgID)
            }
            MediaType.MOVIE -> CatalogMeta.record(key, "movie", item.title, item.subtitle?.toIntOrNull(), img = item.artwork, sid = p.id, sname = p.name)
            MediaType.EPISODE, MediaType.SERIES -> {
                val s = item.season; val e = item.episode
                CatalogMeta.record(key, "episode", item.title, null, img = item.artwork, sid = p.id, sname = p.name,
                    ep = if (s != null && e != null) CatalogMeta.Ep(item.seriesID?.let { "$sid|$it" }, item.title, s, e) else null)
                item.seriesID?.let { sr ->
                    if (!CatalogMeta.hasMeta("$sid|$sr")) CatalogMeta.record("$sid|$sr", "series", item.title, null, img = item.artwork, sid = p.id, sname = p.name)
                }
            }
        }
    }
}
