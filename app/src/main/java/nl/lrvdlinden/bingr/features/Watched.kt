package nl.lrvdlinden.bingr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Build 154: "gezien" over alle servers (docs/ACCOUNT.md van iptv-ios-appletv, "Build 154"). Naast de id-sleutels
 * (`xv123`, `ep:<serie>|s-e`) staan inhoudssleutels (`w:m:`, `w:e:`, `w:s:`, [Build154Logic.WatchKeys]) in dezelfde set
 * `profile.<id>.watched`; de account-sync neemt ze mee (en bij uitvinken in `removed.watched`). Trakt-gezien staat in
 * het geheugen als dezelfde sleutels ([Trakt.watchedKeys], [Trakt.showEpisodes]) en gaat niet naar het account.
 */
object Watched {
    private val W = Build154Logic.WatchKeys
    private val cache = ConcurrentHashMap<String, List<String>>()

    private fun cached(k: String, f: () -> List<String>): List<String> =
        cache[k] ?: f().also { if (cache.size > 40_000) cache.clear(); cache[k] = it }

    /** Opgeschoonde weergavetitel (zonder eigen hernoeming), het begin van de normalisatie. */
    private fun shown(raw: String): String = ContentLanguage.displayTitle(raw)

    // MARK: tmdb/imdb

    private fun metaTmdb(id: String, sid: String?): String? {
        val s = sid ?: Store.activePlaylistID ?: return null
        return runCatching { CatalogMeta.item("$s|$id").jS("tmdb") }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() && it != "0" }
    }
    /** TMDB-id: van de server (Xtream), anders uit catalogmeta. */
    fun tmdb(m: Movie): String? = m.tmdbID ?: metaTmdb(m.id, m.sourceID)
    fun tmdb(s: Series): String? = s.tmdbID ?: metaTmdb(s.id, s.sourceID)

    // MARK: series bij een aflevering

    @Volatile private var idxLib: Library? = null
    @Volatile private var idx: Map<String, Series> = emptyMap()
    fun series(id: String): Series? {
        val lib = Playlists.library
        if (lib !== idxLib) { idx = lib.series.associateBy { it.id }; idxLib = lib }
        return idx[id]
    }

    // MARK: lezen

    private fun movieLookup(m: Movie, imdb: String?): List<String> {
        val i = imdb ?: m.imdbID
        return cached("m\u0000${m.title}\u0000${m.year}\u0000${m.tmdbID}\u0000$i") { W.lookupKeys("w:m:", shown(m.title), m.year, m.tmdbID, i) }
    }

    /** `<show>`-vormen om op te zoeken (tmdb, imdb, titel met jaar ±1). */
    fun showForms(s: Series, imdb: String?): List<String> {
        val i = imdb ?: s.imdbID
        return cached("s\u0000${s.title}\u0000${s.year}\u0000${s.tmdbID}\u0000$i") { W.lookupKeys("", shown(s.title), s.year, s.tmdbID, i) }
    }

    private val traktOn: Boolean get() = Trakt.profileAllowed && Trakt.isLoggedIn

    /**
     * Lokaal gezien? Over alle kandidaatsleutels (id-sleutel en inhoudssleutels, titel met jaar ±1) wint de nieuwste
     * gebeurtenis: een toevoeging (in `watched`) = gezien, een verwijdering (`removed.watched`) = niet gezien. Een sleutel in
     * de set waarvan de tijd nog niet vastligt, is net hier toegevoegd (= de nieuwste).
     */
    private fun localDecide(cands: List<String>): Boolean {
        val lw = Store.localWatched
        var any = false
        for (k in cands) if (k in lw) { any = true; break }
        if (!any) return false
        val (present, removed) = StateSync.watchedEvents(Store.activeProfileID)
        if (removed.isEmpty()) return true
        val wm = HashMap<String, Long>()
        for (k in cands) if (k in lw) wm[k] = present[k] ?: Long.MAX_VALUE
        return W.latest(wm, removed, cands)
    }

    fun isWatched(m: Movie, imdb: String?): Boolean {
        val keys = movieLookup(m, imdb)
        if (localDecide(listOf(m.id) + keys)) return true
        if (traktOn) { val t = Trakt.watchedKeys; if (t.isNotEmpty()) for (k in keys) if (k in t) return true }
        return false
    }

    fun isWatched(e: Episode, imdb: String?): Boolean {
        val own = listOf(e.id, Store.episodeKey(e.seriesID, e.season, e.number))
        val s = series(e.seriesID) ?: return localDecide(own)
        val forms = showForms(s, imdb)
        val se = "${e.season}-${e.number}"
        if (localDecide(own + forms.map { "w:e:$it|$se" })) return true
        if (traktOn) { val t = Trakt.showEpisodes; for (f in forms) if (t[f]?.contains(se) == true) return true }
        return false
    }

    /** Seizoen-aflevering ("1-2") van alles wat van deze serie gezien is (Trakt + lokaal, alle servers). */
    fun episodeSet(s: Series, imdb: String?): Set<String> {
        val forms = showForms(s, imdb)
        val out = HashSet<String>()
        if (traktOn) { val t = Trakt.showEpisodes; for (f in forms) t[f]?.let { out.addAll(it) } }
        val lw = Store.localWatched
        if (lw.isEmpty()) return out
        val prefix = "ep:${s.id}|"
        val fs = forms.toHashSet()
        for (k in lw) {
            if (k.startsWith(prefix)) out.add(k.substring(prefix.length))
            else if (k.startsWith("w:e:")) {
                val bar = k.lastIndexOf('|')
                if (bar > 4 && k.substring(4, bar) in fs) out.add(k.substring(bar + 1))
            }
        }
        if (s.episodes.isNotEmpty()) for (e in s.episodes) if (e.id in lw) out.add("${e.season}-${e.number}")
        return out
    }

    fun isSeriesWatched(s: Series, imdb: String? = Trakt.imdb(s)): Boolean {
        val forms = showForms(s, imdb)
        if (localDecide(forms.map { "w:s:$it" })) return true
        if (traktOn) {
            val aired = Trakt.aired; val t = Trakt.showEpisodes
            for (f in forms) { val a = aired[f] ?: continue; if (a > 0 && (t[f]?.count { !it.startsWith("0-") } ?: 0) >= a) return true }
        }
        return false
    }

    /** Aflevering van een andere server (Verder kijken): op titel/jaar/tmdb van de serie. */
    fun isEpisodeWatched(showTitle: String, year: Int?, tmdb: String?, season: Int, episode: Int): Boolean {
        return localDecide(W.lookupKeys("w:e:", shown(showTitle), year, tmdb, null, "|$season-$episode"))
    }

    // MARK: schrijven

    fun setWatched(m: Movie, imdb: String?, watched: Boolean) {
        val i = imdb ?: m.imdbID
        val own = listOf(m.id) + W.movieKeys(shown(m.title), m.year, tmdb(m), i)
        if (watched) Store.updateWatched(own, emptyList())
        else {
            val look = movieLookup(m, i) + W.lookupKeys("w:m:", shown(m.title), m.year, tmdb(m), i)
            Store.updateWatched(emptyList(), W.removalKeys(Store.localWatched, own, look))
        }
    }

    private fun episodeOwn(e: Episode, s: Series?, imdb: String?): List<String> {
        val base = listOf(e.id, Store.episodeKey(e.seriesID, e.season, e.number))
        if (s == null) return base
        return base + W.episodeKeys(shown(s.title), s.year, tmdb(s), imdb ?: s.imdbID, e.season, e.number)
    }

    /** Eén of meer afleveringen (af)vinken; daarna `w:s:` bijwerken. [all] = alle afleveringen van de serie (als bekend). */
    fun setWatched(episodes: List<Episode>, imdb: String?, watched: Boolean, all: List<Episode>? = null) {
        if (episodes.isEmpty()) return
        val s = series(episodes.first().seriesID)
        val add = ArrayList<String>(); val remove = LinkedHashSet<String>()
        val local = Store.localWatched
        for (e in episodes) {
            val own = episodeOwn(e, s, imdb)
            if (watched) add += own
            else {
                remove += own
                if (s != null) for (f in showForms(s, imdb)) "w:e:$f|${e.season}-${e.number}".let { if (it in local) remove += it }
            }
        }
        Store.updateWatched(add, remove)
        if (s != null) updateSeries(s, imdb, all?.size)
    }

    /** Uit de speler: lokaal als gezien gemarkeerd (gezien-grens). true = was nog niet gezien. */
    fun markPlayed(item: PlayableItem, seriesID: String?): Boolean {
        val s = item.season; val ep = item.episode
        if (item.type == MediaType.EPISODE && s != null && ep != null) {
            val ser = seriesID?.let { series(it) }
            val before = item.id in Store.localWatched || (seriesID != null && Store.episodeKey(seriesID, s, ep) in Store.localWatched)
            val keys = ArrayList<String>()
            keys += item.id
            if (seriesID != null) keys += Store.episodeKey(seriesID, s, ep)
            keys += W.episodeKeys(shown(ser?.title ?: item.title), ser?.year ?: item.year, ser?.let { tmdb(it) } ?: item.tmdbID,
                item.imdbID ?: ser?.imdbID, s, ep)
            Store.updateWatched(keys, emptyList())
            if (ser != null) updateSeries(ser, item.imdbID, null)
            return !before
        }
        val before = item.id in Store.localWatched
        val m = Playlists.library.movies.firstOrNull { it.id == item.id }
        val keys = listOf(item.id) + W.movieKeys(shown(m?.title ?: item.title), m?.year ?: item.year ?: item.subtitle?.trim()?.toIntOrNull(),
            m?.let { tmdb(it) } ?: item.tmdbID, item.imdbID ?: m?.imdbID)
        Store.updateWatched(keys, emptyList())
        return !before
    }

    /**
     * `w:s:` schrijven als alle uitgezonden afleveringen gezien zijn (Trakt `aired_episodes`, anders het aantal op de
     * server); is er daarna een niet meer gezien, dan gaat `w:s:` weg (en dus naar `removed`).
     */
    fun updateSeries(s: Series, imdb: String?, knownCount: Int?) {
        val i = imdb ?: s.imdbID ?: Trakt.imdb(s)
        val forms = showForms(s, i)
        val aired = forms.firstNotNullOfOrNull { Trakt.aired[it] }?.takeIf { it > 0 }
            ?: knownCount?.takeIf { it > 0 } ?: s.episodes.size.takeIf { it > 0 } ?: return
        // specials (seizoen 0) tellen niet mee voor aired_episodes
        val done = episodeSet(s, i).count { !it.startsWith("0-") } >= aired
        val own = W.showKeys(shown(s.title), s.year, tmdb(s), i)
        if (done) Store.updateWatched(own, emptyList())
        else {
            val local = Store.localWatched
            Store.updateWatched(emptyList(), W.removalKeys(local, emptyList(), own + forms.map { "w:s:$it" }))
        }
    }

    // MARK: migratie (eenmalig per profiel en apparaat)

    private fun migratedKey(pid: String) = "watched.migrated154.${pid.lowercase()}"

    /**
     * Bestaande id-sleutels die in de catalogus van een van je servers (of in catalogmeta) bij een item horen, krijgen hun
     * inhoudssleutels erbij, met dezelfde `at`. Wat niet te vinden is, blijft zoals het is. Pas als er een bibliotheek is.
     */
    fun migrateIfNeeded() {
        val pid = Store.activeProfileID ?: return
        if (Prefs.bool(migratedKey(pid), false)) return
        val lib = Playlists.library
        if (lib.isEmpty) return
        val local = Store.localWatched
        if (local.none { !it.startsWith("w:") }) { Prefs.put(migratedKey(pid), true); return }
        AppScope.launch(Dispatchers.Main.immediate) {
            val ats = StateSync.watchedAt(pid)
            val servers = Store.playlists.map { it.id }
            val result = withContext(Dispatchers.Default) {
                val movies = lib.movies.associateBy { it.id }
                val seriesByID = lib.series.associateBy { it.id }
                val entries = local.associateWith { ats[it] }
                fun fromMeta(id: String): List<String>? {
                    for (sid in servers) {
                        val k = "$sid|$id"
                        val o = runCatching { CatalogMeta.item(k) }.getOrNull() ?: continue
                        val t = o.jS("t")?.takeIf { it.isNotBlank() } ?: continue
                        val y = o.jI("y"); val tm = o.jS("tmdb")?.takeIf { it != "0" }
                        when (o.jS("k")) {
                            "movie" -> return W.movieKeys(shown(t), y, tm, null)
                            "series" -> return null
                            "episode" -> {
                                val ep = CatalogMeta.episode(k) ?: continue
                                val st = ep.seriesTitle ?: continue
                                val sm = ep.series?.let { runCatching { CatalogMeta.item(it) }.getOrNull() }
                                return W.episodeKeys(shown(st), sm.jI("y"), sm.jS("tmdb")?.takeIf { it != "0" }, null, ep.season, ep.episode)
                            }
                        }
                    }
                    return null
                }
                W.migrate(entries) { key ->
                    movies[key]?.let { m -> return@migrate W.movieKeys(shown(m.title), m.year, tmdb(m), m.imdbID) }
                    if (key.startsWith("ep:")) {
                        val sid = key.substring(3).substringBefore('|')
                        val se = key.substringAfterLast('|').split('-')
                        val sn = se.getOrNull(0)?.toIntOrNull(); val en = se.getOrNull(1)?.toIntOrNull()
                        val s = seriesByID[sid]
                        if (s != null && sn != null && en != null) return@migrate W.episodeKeys(shown(s.title), s.year, tmdb(s), s.imdbID, sn, en)
                        return@migrate null
                    }
                    fromMeta(key)
                }
            }
            val added = result.filterKeys { it !in local }
            if (added.isNotEmpty()) {
                val now = System.currentTimeMillis()
                val accepted = StateSync.seedWatchedAt(pid, added.mapValues { it.value ?: now })
                Store.updateWatched(accepted, emptyList(), notify = false)
                StateSync.profileDataChanged(pid)
            }
            Prefs.put(migratedKey(pid), true)
        }
    }

    // MARK: grootte

    /**
     * De section `profile.<id>` mag hooguit 1 MB zijn: daarboven vallen eerst de titelsleutels van films weg die ook een
     * tmdb- of imdb-sleutel hebben (zelfde `at` = zelfde item), de oudste eerst.
     */
    fun pruneIfNeeded() {
        val local = Store.localWatched
        if (local.size < 8_000) return
        val pid = Store.activeProfileID ?: return
        val ats = StateSync.watchedAt(pid)
        val entries = local.associateWith { ats[it] }
        val idAts = HashSet<Long>()
        for ((k, at) in entries) if (at != null && (k.startsWith("w:m:tmdb:") || k.startsWith("w:m:imdb:"))) idAts += at
        val pruned = W.prune(entries, maxBytes = 1_000_000, overhead = 200_000) { k -> entries[k]?.let { it in idAts } == true }
        if (pruned.size < entries.size) Store.updateWatched(emptyList(), entries.keys - pruned.keys)
    }
}
