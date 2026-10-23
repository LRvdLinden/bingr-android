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
import kotlinx.coroutines.flow.conflate
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
 * - Build 130: pas als de actieve server klaar is (laden, verversen, filteren) en minstens 10 s na het openen; elke
 *   server wordt alleen opnieuw ingelezen als zijn cache, de filters of de verwijzingen veranderd zijn ([scans]), en
 *   een server zonder cache wordt hooguit eens per 6 uur van het netwerk gehaald (Playlists.catalogOf). Zo staat er na
 *   het openen niet lang "Bijwerken…" en loopt het niet steeds opnieuw.
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
    private var startedAt = 0L
    private var job: Job? = null

    private val X = AllServersLogic

    fun wipe() {
        found = emptyMap(); absent = emptySet(); tried.clear(); notice = null; smart = emptyMap(); smartTried.clear(); libCache.clear()
        synchronized(libOrder) { libOrder.clear() }
        scans.clear(); smartDone = null
        synchronized(seenChannels) { seenChannels.clear(); seenEpg.clear() }
        runCatching { Prefs.remove(MIGRATED) }; version++
    }

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
        startedAt = System.currentTimeMillis()
        CatalogMeta.ensureLoaded()
        job = AppScope.launch {
            snapshotFlow {
                listOf(Store.activeProfileID, Store.myList, Store.favorites, Store.recentChannels, Store.progress.keys,
                    Store.history.size, Collections.byProfile, Store.playlists.map { it.id }, activeID(),
                    System.identityHashCode(Playlists.library), CatalogMeta.version, Playlists.catalogVersion)
            }.debounce(2_000).conflate().collect { quietly { refresh() } }
        }
        // build 129: slimme collecties over alle servers
        AppScope.launch {
            snapshotFlow {
                listOf(Store.activeProfileID, Collections.visible.filter { it.isSmart }.map { it.id + smartSig(it) },
                    Store.playlists.map { it.id }, activeID(), System.identityHashCode(Playlists.library), Playlists.catalogVersion)
            }.debounce(3_000).conflate().collect { quietly { refreshSmart() } }
        }
    }

    /**
     * Build 141: een profiel is (opnieuw) gekozen. Het werk aan de andere servers wacht dan weer even (de actieve server
     * eerst: laden, filteren, Home), zie [waitForIdle].
     */
    fun profileOpened() { startedAt = System.currentTimeMillis() }

    /**
     * Build 141: zwaar werk aan een andere server (catalogus inlezen of van het netwerk halen): pas als het rustig is, en
     * één tegelijk met [refresh], de slimme collecties en Nieuw op Bingr. Zo staat er nooit meer dan één catalogus van een
     * andere server tegelijk in het geheugen. Tot build 140 liepen die los van elkaar (en Nieuw op Bingr per tabblad), op
     * een tv met een kleine heap tot de app vastliep en afsloot.
     */
    suspend fun <T> heavy(block: suspend () -> T): T = lock.withLock {
        waitForIdle()
        block()
    }

    /**
     * Build 130: na "alles vernieuwen" (nieuwe catalogi): items die niet gevonden werden en servers zonder cache meteen
     * opnieuw proberen (de rondes zelf starten via Playlists.catalogVersion).
     */
    fun catalogsRefreshed() {
        tried.clear(); smartTried.clear(); smartDone = null
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
    private suspend fun filtered(p: PlaylistSource, allowNetwork: Boolean, background: Boolean = false): Library? {
        if (isActive(p.id)) return Playlists.library.takeIf { !it.isEmpty }
        val ctx = withContext(Dispatchers.Main) { Playlists.context(p) }
        return otherLibrary(p, ctx, allowNetwork, background)
    }

    // MARK: build 129: andere servers zonder te wisselen (zoeken, "Andere bronnen", slimme collecties)

    /** Gefilterde catalogus van een andere server, zolang zijn cache en de filters gelijk blijven (zacht: mag weg). */
    private class Cached(val stamp: Long, val ctx: Playlists.FilterContext, val lib: Library)
    private val libCache = java.util.concurrent.ConcurrentHashMap<String, java.lang.ref.SoftReference<Cached>>()
    /** Build 141: volgorde van gebruik van [libCache] (nieuwste achteraan). */
    private val libOrder = ArrayList<String>()

    /**
     * Build 141: hooguit 1 (kleine heap, bijv. tv) of 2 gefilterde catalogi van andere servers bewaren. Tot build 140 bleven
     * ze allemaal (zacht) staan, mét gids; samen met de actieve catalogus en het verversen liep de heap van een tv vol.
     */
    private fun keep(k: String, c: Cached) {
        val max = Build141Feedback.Background.otherLibrariesToKeep(Runtime.getRuntime().maxMemory() / (1024 * 1024))
        synchronized(libOrder) {
            libCache[k] = java.lang.ref.SoftReference(c)
            libOrder.remove(k); libOrder.add(k)
            while (libOrder.size > max) libCache.remove(libOrder.removeAt(0))
        }
    }

    /**
     * Gefilterde catalogus van server [p] (niet de actieve) voor het actieve profiel ([ctx], op de main thread gemaakt
     * met Playlists.context): uit het geheugen, anders de cache op dit apparaat, anders (alleen met [allowNetwork]) stil
     * van het netwerk. Zo leest zoeken niet bij elke letter de hele cache opnieuw in.
     */
    suspend fun otherLibrary(p: PlaylistSource, ctx: Playlists.FilterContext, allowNetwork: Boolean, background: Boolean = false,
                             raw: Library? = null): Library? {
        val k = p.id.lowercase(Locale.ROOT)
        val stamp = withContext(Dispatchers.IO) { Playlists.cacheStamp(p) }
        libCache[k]?.get()?.let { if (stamp != 0L && it.stamp == stamp && it.ctx == ctx) return it.lib }
        // build 141: [raw] = al ingelezen ruwe catalogus (niet nog een keer van schijf)
        val raw = raw ?: Playlists.catalogOf(p, allowNetwork, background) ?: return null
        val lib = withContext(Dispatchers.Default) { runCatching { Playlists.apply(raw, ctx) }.getOrNull() } ?: return null
        val now = withContext(Dispatchers.IO) { Playlists.cacheStamp(p) }
        if (now != 0L) keep(k, Cached(now, ctx, lib))
        return lib
    }

    /** Zenders van andere servers die via zoeken of een slimme collectie in beeld kwamen (gids en terugkijken). */
    private val seenChannels = object : LinkedHashMap<String, Channel>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Channel>?): Boolean = size > 400
    }
    private val seenEpg = object : LinkedHashMap<String, List<EPGProgram>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<EPGProgram>>?): Boolean = size > 200
    }

    /** Zender van een andere server onthouden (met zijn gids van max. 8 dagen terug), zodat gids en terugkijken werken. */
    fun remember(c: Channel, programs: List<EPGProgram>?) {
        val sid = c.sourceID ?: return
        val mk = X.metaKey("$sid|${c.id}")
        val cutoff = System.currentTimeMillis() - 8 * 86_400_000L
        val p = programs?.filter { it.end > cutoff }?.takeIf { it.isNotEmpty() }
        synchronized(seenChannels) {
            seenChannels[mk] = c
            if (p != null) seenEpg[mk] = p
        }
    }

    /** Slimme collecties op de andere servers: collectie-id (kleine letters) → (handtekening, items met sourceID). */
    @Volatile private var smart: Map<String, Pair<String, List<CollectionEntry>>> = emptyMap()
    private val smartTried = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun smartSig(c: BingrCollection) = "${c.query}|${c.types}|${c.group}|${c.sourceID}|${c.updatedAt}"

    /** Treffers van een slimme collectie op de andere servers (leeg tot ze op de achtergrond doorzocht zijn). */
    fun smartEntries(c: BingrCollection): List<CollectionEntry> =
        smart[c.id.lowercase(Locale.ROOT)]?.takeIf { it.first == smartSig(c) }?.second ?: emptyList()

    /** Sleutel om dezelfde titel/zender van twee servers samen te voegen. */
    fun crossKey(e: CollectionEntry): AllServersLogic.Cross? = when (e) {
        is CollectionEntry.C -> X.crossKey("channel", e.channel.name, null)
        is CollectionEntry.M -> X.crossKey("movie", e.movie.title, e.movie.year)
        is CollectionEntry.S -> X.crossKey("series", e.series.title, e.series.year)
    }

    /** Eén slimme collectie in de (gefilterde) catalogus van server [sid]; zelfde regels als Collections.resolve. */
    private fun smartIn(c: BingrCollection, lib: Library, sid: String, limit: Int): List<CollectionEntry> {
        val words = c.keywords
        if (words.isEmpty() || limit <= 0) return emptyList()
        val group = c.group?.takeIf { it.isNotBlank() }
        val types = c.types.ifEmpty { BingrCollection.ALL_TYPES }
        val out = ArrayList<CollectionEntry>()
        if (BingrCollection.CHANNEL in types) for (x in lib.channels) { if (out.size >= limit) break; if (X.smartHit(Net.fold(x.name), x.group, words, group)) out += CollectionEntry.C(x.copy(sourceID = sid)) }
        if (BingrCollection.MOVIE in types) for (x in lib.movies) { if (out.size >= limit) break; if (X.smartHit(Net.fold(x.title), x.group, words, group)) out += CollectionEntry.M(x.copy(sourceID = sid)) }
        if (BingrCollection.SERIES in types) for (x in lib.series) { if (out.size >= limit) break; if (X.smartHit(Net.fold(x.title), x.group, words, group)) out += CollectionEntry.S(x.copy(sourceID = sid)) }
        return out
    }

    /**
     * Slimme collecties van het actieve profiel op de andere servers doorzoeken (één catalogus tegelijk, rustig, niet
     * tijdens het afspelen). Een collectie met `sourceID` zoekt alleen op die server.
     */
    @Volatile private var smartDone: String? = null
    private suspend fun refreshSmart() {
        val (cols, servers, ctxs) = withContext(Dispatchers.Main) {
            val cs = Collections.visible.filter { it.isSmart && it.keywords.isNotEmpty() }
            val ps = order().filter { !isActive(it.id) }
            Triple(cs, ps, ps.associate { it.id to Playlists.context(it) })
        }
        if (cols.isEmpty()) {
            if (smart.isNotEmpty()) { smart = emptyMap(); withContext(Dispatchers.Main) { version++ } }
            smartDone = null
            return
        }
        // build 130: zelfde collecties, servers, caches en filters als de vorige ronde → niets te doen
        val sig = withContext(Dispatchers.IO) {
            cols.joinToString(";") { it.id + "=" + smartSig(it) } + "#" +
                servers.joinToString(";") { it.id + "@" + Playlists.cacheStamp(it) + "@" + ctxs[it.id].hashCode() }
        }
        if (sig == smartDone) return
        // één catalogus tegelijk (samen met [refresh]) en pas als de actieve server klaar is
        lock.withLock { smartRound(cols, servers, ctxs, sig) }
    }

    private suspend fun smartRound(cols: List<BingrCollection>, servers: List<PlaylistSource>, ctxs: Map<String, Playlists.FilterContext>, sig: String) {
        waitForIdle()
        var complete = true
        val out = HashMap<String, MutableList<CollectionEntry>>()
        for (p in servers) {
            val mine = cols.filter { it.sourceID == null || it.sourceID.equals(p.id, ignoreCase = true) }
            if (mine.isEmpty()) continue
            val ctx = ctxs[p.id] ?: continue
            val lib = otherLibrary(p, ctx, allowNetwork = false) ?: run {
                // nog geen cache van die server: hooguit eens per 6 uur stil van het netwerk
                val k = p.id.lowercase(Locale.ROOT)
                if (System.currentTimeMillis() - (smartTried[k] ?: 0L) < RETRY) { complete = false; return@run null }
                smartTried[k] = System.currentTimeMillis()
                waitForIdle()
                withTimeoutOrNull(120_000) { otherLibrary(p, ctx, allowNetwork = true, background = true) }
            } ?: run { complete = false; null } ?: continue
            val chans = withContext(Dispatchers.Default) {
                val cs = ArrayList<Channel>()
                for (c in mine) {
                    val list = out.getOrPut(c.id.lowercase(Locale.ROOT)) { ArrayList() }
                    val hits = smartIn(c, lib, p.id, 200 - list.size)
                    hits.forEach { e -> if (e is CollectionEntry.C) cs += e.channel }
                    list += hits
                }
                cs
            }
            // build 141: alleen de gids van deze zenders (niet de hele gids van die server)
            if (chans.isNotEmpty()) {
                val g = withContext(Dispatchers.IO) { Playlists.epgOf(p, chans.mapTo(HashSet()) { CatalogFiles.epgKey(it) }) }
                chans.forEach { remember(it, g[CatalogFiles.epgKey(it)]) }
            }
        }
        val n = cols.associate { it.id.lowercase(Locale.ROOT) to (smartSig(it) to (out[it.id.lowercase(Locale.ROOT)]?.toList() ?: emptyList())) }
        if (complete) smartDone = sig
        if (n != smart) {
            smart = n
            withContext(Dispatchers.Main) { version++ }
        }
    }

    /**
     * Build 130: wachten tot de achtergrond aan de andere servers mag werken: niet tijdens het afspelen, niet zolang de
     * actieve server laadt, ververst ("Bijwerken…") of filtert, en niet in de eerste seconden na het openen.
     */
    private suspend fun waitForIdle() {
        while (true) {
            // build 144: alleen op het hoofdscherm, niet in "Wie kijkt er?" of het pincodescherm (na een profielwissel)
            val ok = withContext(Dispatchers.Main) {
                AppState.stage == AppState.Stage.MAIN &&
                X.idle(AppState.playing != null, Playlists.isLoading, Playlists.updating, Playlists.isFiltering,
                    System.currentTimeMillis() - startedAt)
            }
            if (ok) return
            delay(3_000)
        }
    }

    /** Build 130: uitkomst van één server in [refresh], zolang zijn cache, de filters en de verwijzingen gelijk blijven. */
    private class Scan(val sig: String, val found: Map<String, Any>, val epg: Map<String, List<EPGProgram>>,
                       val absent: Set<String>, val needs: List<Ref>)
    private val scans = java.util.concurrent.ConcurrentHashMap<String, Scan>()
    private fun refSig(r: Ref) = "${r.key}\u0003${r.field}\u0003${r.kind}\u0003${r.title}"

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
        // build 130: eerst de actieve server (snel klaar na het openen), daarna pas de rest
        waitForIdle()
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
        val servers = Store.playlists.toList()
        scans.keys.retainAll(servers.map { it.id.lowercase(Locale.ROOT) }.toSet())
        for (p in servers) {
            val pk = p.id.lowercase(Locale.ROOT)
            val list = byServer[pk] ?: run { scans.remove(pk); null } ?: continue
            // build 130: niets veranderd voor deze server (cache, filters, verwijzingen) → vorige uitkomst
            val active = isActive(p.id)
            val stamp = if (active) System.identityHashCode(Playlists.library).toLong() else withContext(Dispatchers.IO) { Playlists.cacheStamp(p) }
            val filters = if (active) "active" else withContext(Dispatchers.Main) { Playlists.context(p) }.hashCode().toString()
            val sig = X.scanSig(list.map { refSig(it) }, stamp, filters)
            val prev = scans[pk]
            if (prev != null && prev.sig == sig && stamp != 0L) {
                newFound.putAll(prev.found); newEpg.putAll(prev.epg); newAbsent.addAll(prev.absent); needs.addAll(prev.needs)
                continue
            }
            val sFound = HashMap<String, Any>()
            val sEpg = HashMap<String, List<EPGProgram>>()
            val sAbsent = HashSet<String>()
            val sNeeds = ArrayList<Ref>()
            // geen cache: Playlists.catalogOf haalt hem hooguit eens per 6 uur stil op, met een time-out
            val lib = filtered(p, allowNetwork = false) ?: run {
                waitForIdle()
                filtered(p, allowNetwork = true, background = true)
            }
            if (lib == null) {
                complete = false
                // build 141: catalogus (nu) niet te lezen: wat we van deze server al wisten blijven tonen. Tot build 140 viel
                // het dan weg, en verdwenen titels van die server uit Mijn lijst tot de volgende geslaagde ronde.
                val pre = "$pk|"
                prev?.let { newFound.putAll(it.found); newEpg.putAll(it.epg); newAbsent.addAll(it.absent) }
                    ?: run { newFound.putAll(found.filterKeys { it.startsWith(pre) }); newEpg.putAll(epg.filterKeys { it.startsWith(pre) }) }
                continue
            }
            // build 141: het doorlopen van de verwijzingen (en de meta) op de achtergrond, niet op de main thread
            withContext(Dispatchers.Default) {
            val ix = Ix(lib)
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
                    if (series != null) { if (!isActive(p.id)) sFound[mk] = series.copy(sourceID = p.id); continue }
                    if (r.field == Field.CONTINUE || r.field == Field.HISTORY) {
                        // voortgang/geschiedenis zonder item in de catalogus (bijv. aflevering): alleen als we weten wat het is
                        if (CatalogMeta.wanted(r.key) == null) continue
                    }
                    sAbsent += mk; sNeeds += r
                } else if (!active) sFound[mk] = when (item) {
                    is Channel -> item.copy(sourceID = p.id)
                    is Movie -> item.copy(sourceID = p.id)
                    is Series -> item.copy(sourceID = p.id)
                    else -> item
                }
            }
            }
            // build 128: gids van de gevonden zenders (alleen wat er nog toe doet: max. 8 dagen terug), voor gids en terugkijken.
            // Build 141: alleen die zenders uit de gids van die server lezen, niet de hele gids.
            if (!active) {
                val chans = sFound.filterValues { it is Channel }.mapValues { it.value as Channel }
                if (chans.isNotEmpty()) {
                    val g = withContext(Dispatchers.IO) { Playlists.epgOf(p, chans.values.mapTo(HashSet()) { CatalogFiles.epgKey(it) }) }
                    val cutoff = System.currentTimeMillis() - 8 * 86_400_000L
                    for ((mk, c) in chans) g[CatalogFiles.epgKey(c)]?.filter { it.end > cutoff }?.takeIf { it.isNotEmpty() }?.let { sEpg[mk] = it }
                }
            }
            // stamp opnieuw lezen: alleen bewaren als de cache intussen niet veranderd is
            val after = if (active) System.identityHashCode(Playlists.library).toLong() else withContext(Dispatchers.IO) { Playlists.cacheStamp(p) }
            if (after == stamp) scans[pk] = Scan(sig, sFound, sEpg, sAbsent, sNeeds)
            newFound.putAll(sFound); newEpg.putAll(sEpg); newAbsent.addAll(sAbsent); needs.addAll(sNeeds)
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
        // build 140: kinderprofiel: wat het kinderfilter verbergt staat niet in de gefilterde catalogus, maar is niet weg;
        // dus niet op andere servers zoeken en geen remap-regels maken (die gelden voor het hele account)
        if (withContext(Dispatchers.Main) { Store.activeProfile?.kids == true }) return@withLock
        // alleen voor het actieve profiel zoeken (de regels gelden daarna voor alle profielen)
        val active = withContext(Dispatchers.Main) { activeRefs().map { X.metaKey(it.key) }.toSet() }
        val now = System.currentTimeMillis()
        val todo = needs.filter { X.metaKey(it.key) in active && now - (tried[X.metaKey(it.key)] ?: 0L) > RETRY }
            .distinctBy { X.metaKey(it.key) }
        if (todo.isEmpty() || Store.playlists.isEmpty()) return@withLock
        waitForIdle()
        val moved = match(todo, allowNetwork = true, background = true)
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
    private suspend fun match(refs: List<Ref>, allowNetwork: Boolean, background: Boolean = false): Map<String, String> {
        val pending = refs.mapNotNull { r -> wantedFor(r)?.let { r to it } }.toMutableList()
        if (pending.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (p in order()) {
            if (pending.isEmpty()) break
            val mine = pending.filter { (r, _) -> !X.sameServer(X.serverOf(r.key), p.id) }
            if (mine.isEmpty()) continue
            val lib = (withTimeoutOrNull(120_000) { filtered(p, allowNetwork, background) }) ?: continue
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
    fun programs(c: Channel): List<EPGProgram> = c.sourceID?.let { sid ->
        val mk = X.metaKey("$sid|${c.id}")
        epg[mk] ?: synchronized(seenChannels) { seenEpg[mk] }
    } ?: emptyList()

    /** Film/serie/zender van een andere server (gevonden, of via zoeken/slimme collectie in beeld gekomen), of null. */
    fun item(key: String): Any? = found[X.metaKey(key)] ?: synchronized(seenChannels) { seenChannels[X.metaKey(key)] }

    /** Staat dit item op een andere server die er niet meer is, of niet meer op zijn server? */
    fun isOrphan(key: String): Boolean {
        val sid = X.serverOf(key) ?: return false
        if (isActive(sid)) return false
        // build 140: een kinderprofiel ziet een item alleen als het in de (gefilterde) bibliotheek van zijn server staat;
        // geen "Onbekende titel"-tegels en niet zoeken/verhuizen (wat het filter verbergt is niet "weg")
        if (Store.activeProfile?.kids == true) return false
        return server(sid) == null || X.metaKey(key) in absent
    }

    /**
     * Build 141: tegels uit de meta voor items van een bestaande andere server die (nog) niet in [found] staan, bijv. vlak
     * na het starten, op een tweede apparaat of terwijl de catalogus van die server nog niet gelezen is. Tot build 140 stond
     * zo'n titel dan niet in Mijn lijst (tot de achtergrond klaar was, en soms nooit). Aanklikken opent het echte item van
     * die server ([intercept] → [openStub]).
     */
    private val stubs = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    private fun stub(key: String, kind: String): Boolean {
        val sid = X.serverOf(key) ?: return false
        if (isActive(sid) || server(sid) == null || Store.activeProfile?.kids == true) return false
        if (CatalogMeta.kind(key) != kind || CatalogMeta.title(key).isNullOrBlank()) return false
        stubs += X.metaKey(key)
        return true
    }

    /** Tegel voor een item van een andere server: het echte item, anders uit de meta ("Onbekende titel"). */
    fun movieTile(key: String): Movie? {
        val sid = X.serverOf(key) ?: return null
        (found[X.metaKey(key)] as? Movie)?.let { return it }
        if (found[X.metaKey(key)] != null) return null
        if (!isOrphan(key)) return if (stub(key, "movie")) Movie(id = X.itemOf(key), title = CatalogMeta.title(key)!!,
            year = CatalogMeta.year(key), poster = CatalogMeta.image(key), sourceID = sid) else null
        val k = CatalogMeta.kind(key)
        if (k != null && k != "movie") return null
        return Movie(id = X.itemOf(key), title = CatalogMeta.title(key) ?: L("Unknown title"), poster = CatalogMeta.image(key), sourceID = sid)
    }
    fun seriesTile(key: String): Series? {
        val sid = X.serverOf(key) ?: return null
        (found[X.metaKey(key)] as? Series)?.let { return it }
        if (found[X.metaKey(key)] != null) return null
        if (!isOrphan(key)) return if (stub(key, "series")) Series(id = X.itemOf(key), title = CatalogMeta.title(key)!!,
            year = CatalogMeta.year(key), poster = CatalogMeta.image(key), sourceID = sid) else null
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
        // build 141: TMDB-titels (niet in een bibliotheek) ook als ze onder een andere server staan (op een ander apparaat of
        // met een andere actieve server toegevoegd); niet voor profielen met een leeftijdsgrens (alleen hun bibliotheek)
        val external = Store.activeProfile?.effectiveAgeLimit == null
        for (k in sorted.sortedBy { if (X.sameServer(X.serverOf(it), pid)) 0 else 1 }) {
            val ext = Build141Feedback.MyListKeys.externalID(k)
            val t = if (ext != null) (if (external) DiscoverKnown.item(ext)?.target else null)
                else if (X.sameServer(X.serverOf(k), pid)) local(k) else
                movieTile(k)?.let { DetailTarget.MovieT(it) } ?: seriesTile(k)?.let { DetailTarget.SeriesT(it) }
                    ?: (found[X.metaKey(k)] as? Series)?.let { DetailTarget.SeriesT(it) }
            t ?: continue
            val d = X.dedupKey(if (t.isSeries) "series" else "movie", t.title, t.year, null)
            if (seen.add(d)) picked[k] = t
        }
        for (k in sorted) picked[k]?.let { out += it }
        return out
    }

    /**
     * Build 140: Mijn lijst aan/uit voor een titel, altijd met de server van die titel (niet de actieve). Een titel van een
     * andere server komt meteen in [found], zodat hij direct in de rij Mijn lijst staat (niet pas na de volgende ronde).
     * Tot build 139 zetten de lang-indrukken-menu's op Home en Categorieën de actieve server in de sleutel; een titel van
     * een andere server werd dan nergens gevonden en verscheen niet in Mijn lijst.
     */
    fun setMyList(t: DetailTarget, on: Boolean, fallbackSid: String?) {
        val sid = t.sourceID ?: fallbackSid ?: return
        if (on && !isActive(sid)) {
            val item: Any = when (t) { is DetailTarget.MovieT -> t.movie.copy(sourceID = sid); is DetailTarget.SeriesT -> t.series.copy(sourceID = sid) }
            val k = "$sid|${t.id}"
            found = found + (X.metaKey(k) to item)
            recordMeta(k, sid, server(sid)?.name ?: "", item, null)
            version++
        }
        Store.setMyList(sid, t.id, on)
    }
    fun inMyList(t: DetailTarget, fallbackSid: String?): Boolean {
        val sid = t.sourceID ?: fallbackSid ?: return false
        return Store.inMyList(sid, t.id)
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
        // build 141: tegel uit de meta (het echte item is nog niet geladen): eerst het item van die server ophalen
        if (X.metaKey(key) in stubs && found[X.metaKey(key)] == null && !isActive(sid)) { openStub(t); return true }
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

    /** Build 141: tegel uit de meta: het echte item van zijn server openen; lukt dat niet, dan de detailpagina van de tegel. */
    private fun openStub(t: DetailTarget) {
        val sid = t.sourceID ?: return
        AppScope.launch {
            val p = server(sid)
            val lib = if (p != null) screensSafe { withTimeoutOrNull(60_000) { filtered(p, allowNetwork = true) } } else null
            val real: DetailTarget? = when (t) {
                is DetailTarget.MovieT -> lib?.movies?.firstOrNull { it.id == t.id }?.let { DetailTarget.MovieT(it.copy(sourceID = p!!.id)) }
                is DetailTarget.SeriesT -> lib?.series?.firstOrNull { it.id == t.id }?.let { DetailTarget.SeriesT(it.copy(sourceID = p!!.id)) }
            }
            if (real != null) {
                found = found + (X.metaKey("$sid|${t.id}") to (if (real is DetailTarget.MovieT) real.movie else (real as DetailTarget.SeriesT).series))
                version++
            }
            ScreensNav.push(ScreenEntry.Detail(real ?: t))
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
