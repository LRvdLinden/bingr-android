package nl.lrvdlinden.bingr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Build 195/196: "Nieuwe aflevering" in Verder kijken (regels in [Build195Logic], verversen in [Build196Logic]). Series in Mijn
 * lijst van het actieve profiel over ALLE servers (via [AllServers.myList]); de afleveringen komen bij de eigen server van elke
 * serie vandaan ([Playlists.freshEpisodes]). Schrijft NOOIT naar de voortgang of de sync (Store.progress / StateSync worden
 * alleen gelezen); alleen lokale Prefs "hid" en "at". De tegels worden afgeleid (niet opgeslagen): uit Mijn lijst, de
 * gezien-sleutels en de afleveringen van de server. Lokaal bewaard (per profiel en serie): [Build195Logic.prefKey] "hid"
 * (weggehaald: de aflevering van de tegel) en "at" (wanneer de tegel voor het eerst verscheen, voor de volgorde).
 */
object NewEpisodes {
    var version by mutableIntStateOf(0); private set
    private var fresh by mutableStateOf(mapOf<String, List<Episode>>())
    // alleen op de main thread (de coroutines van [refresh] draaien op de dispatcher van de aanroeper)
    private val fetchedAt = HashMap<String, Long>()
    private val failedAt = HashMap<String, Long>()
    private val lastSeries = HashMap<String, Series>()
    /** Gewone afleveringen gelezen/gezien: vanaf 85% telt als gezien (gelijk aan Apple en de speler). */
    private const val WATCHED_FRACTION = 0.85

    /** Sleutel per server én serie: dezelfde id op twee servers is niet dezelfde serie. */
    private fun skey(s: Series) = "${(s.sourceID ?: "").lowercase()}|${s.id}"

    /** Afleveringen van de eigen server van de serie; de cache van de actieve server alleen voor series van die server (id's kunnen botsen). */
    private fun epsOf(s: Series, pid: String?): List<Episode> {
        if (s.episodes.isNotEmpty()) return s.episodes
        fresh[skey(s)]?.let { return it }
        val foreign = s.sourceID != null && !AllServersLogic.sameServer(s.sourceID, pid)
        return if (foreign) emptyList() else Playlists.episodeCache[s.id] ?: emptyList()
    }

    private fun ep(e: Episode) = Build195Logic.Ep(e.season, e.number)

    private fun watchedFn(s: Series, eps: List<Episode>, fractions: Map<String, Double>): (Build195Logic.Ep) -> Boolean {
        val imdb = Trakt.imdb(s)
        val byEp = HashMap<Build195Logic.Ep, Episode>()
        for (e in eps) byEp.putIfAbsent(ep(e), e)
        return { k -> byEp[k]?.let { Playables.isWatched(it, imdb) || (fractions[it.id] ?: 0.0) >= WATCHED_FRACTION } ?: false }
    }

    /**
     * Afleveringen van ALLE series in Mijn lijst verversen (build 196: geen limiet op het aantal; per serie hooguit elke 2 uur).
     * Op de achtergrond met hooguit [Build196Logic.CONCURRENCY] tegelijk; een mislukte serie (time-out, serverfout, geen
     * afleveringen) stopt de rest niet, houdt zijn laatste goede lijst en wordt na [Build196Logic.RETRY_MS] opnieuw geprobeerd.
     */
    suspend fun refresh(series: List<Series>) {
        val list = Build196Logic.unique(series, ::skey)
        for (s in list) {   // een serie die veranderd is (bijv. van meta-stub naar echt item) mag meteen opnieuw
            val k = skey(s)
            if (lastSeries[k] != s) { failedAt.remove(k); lastSeries[k] = s }
        }
        val now = System.currentTimeMillis()
        val due = list.filter { Build196Logic.isDue(now, fetchedAt[skey(it)], failedAt[skey(it)]) }
        if (due.isEmpty()) return
        val gate = Semaphore(Build196Logic.CONCURRENCY)
        var pending = 0
        coroutineScope {
            for (s in due) launch {
                gate.withPermit {
                    val k = skey(s)
                    val eps = try {
                        withTimeoutOrNull(Build196Logic.TIMEOUT_MS) { screensSafe { Playlists.freshEpisodes(s) } }
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                    ensureActive()   // geannuleerd (bijv. opnieuw gestart) is geen mislukking
                    val t = System.currentTimeMillis()
                    if (eps.isNullOrEmpty()) { failedAt[k] = t; return@withPermit }   // het laatste goede resultaat blijft
                    fetchedAt[k] = t; failedAt.remove(k)
                    if (fresh[k] != eps) {
                        fresh = fresh + (k to eps)
                        if (++pending >= 5) { pending = 0; version++ }   // niet bij elke serie opnieuw tekenen
                    }
                }
            }
        }
        if (pending > 0) version++
    }

    /** Wachttijd tot de volgende controle (null = niets te doen). */
    fun nextDelay(series: List<Series>): Long? {
        val now = System.currentTimeMillis()
        return Build196Logic.nextDelay(now, series.map { Build196Logic.dueAt(fetchedAt[skey(it)], failedAt[skey(it)]) })
    }

    /** De tegels. [existing] = genormaliseerde titels die al een gewone tegel hebben (geen dubbele items). */
    fun items(series: List<Series>, progress: Map<String, ProgressEntry>, filter: Filter, pid: String?,
              existing: Set<String>): List<ContinueItem> {
        if (filter == Filter.MOVIES) return emptyList()
        val profile = Store.activeProfileID
        val out = ArrayList<ContinueItem>()
        // voortgang per server (aflevering-id's kunnen op twee servers hetzelfde zijn): null = de actieve server
        val byServer = HashMap<String, HashMap<String, Double>>()
        for ((k, e) in progress) {
            if (!Store.belongsToActive(e.profileID)) continue
            byServer.getOrPut((e.playlistID ?: pid ?: "").lowercase()) { HashMap() }[k] = e.fraction
        }
        for (s in series) {
            if (Store.blockedTitles.isNotEmpty() && Store.isTitleBlocked(s.sourceID ?: pid, true, s.id)) continue
            if (AllServersLogic.normTitle(s.title) in existing) continue
            val eps = epsOf(s, pid); if (eps.isEmpty()) continue
            // nooit naast of in plaats van een lopende aflevering: de voortgang (alleen gelezen, alleen van de eigen server van
            // de serie) gaat voor
            val fractions = byServer[(s.sourceID ?: pid ?: "").lowercase()] ?: emptyMap()
            val next = Build195Logic.tile(eps.map { it.id to ep(it) }, fractions, watchedFn(s, eps, fractions),
                Prefs.string(Build195Logic.prefKey("hid", profile, s.title))) ?: continue
            val e = eps.firstOrNull { it.season == next.season && it.number == next.number } ?: continue
            val atKey = Build195Logic.prefKey("at", profile, s.title)
            val saved = Prefs.string(atKey)?.split("|")
            val at = (if (saved != null && saved.size == 2 && saved[0] == next.key) saved[1].toLongOrNull() else null)
                ?: System.currentTimeMillis().also { Prefs.put(atKey, "${next.key}|$it") }
            val (art, lookup) = continueCover(s.title, s.year, true, s.poster, null, e.still)
            val foreign = s.sourceID != null && !s.sourceID.equals(pid, ignoreCase = true)
            out.add(ContinueItem(e.id, s.title, L("New episode") + " " + next.label, art, 0.0, at, false,
                sourceID = if (foreign) s.sourceID else null, coverLookup = lookup,
                newEpisodeHide = Build195Logic.hiddenValue(next)))
        }
        return out
    }

    /** Tegel weghalen: pas weer een tegel bij een nog nieuwere aflevering. */
    fun hide(c: ContinueItem) {
        val v = c.newEpisodeHide ?: return
        Prefs.put(Build195Logic.prefKey("hid", Store.activeProfileID, c.title), v)
        version++
    }
}

/** De "Nieuwe aflevering"-tegels voor Verder kijken (build 195/196); ververst de afleveringen op de achtergrond (elke 2 uur per serie). */
@Composable
fun rememberNewEpisodes(index: HomeIndex, pid: String?, filter: Filter, progress: Map<String, ProgressEntry>,
                        existing: Set<String>): List<ContinueItem> {
    val myList = Store.myList
    val allV = AllServers.version + CatalogMeta.version
    val mySeries = remember(myList, index, pid, allV) {
        if (pid == null) emptyList<Series>()
        else AllServers.myList(myList, pid) { k ->
            index.movie(k, pid)?.let { DetailTarget.MovieT(it) } ?: index.series(k, pid)?.let { DetailTarget.SeriesT(it) }
        }.mapNotNull { (it as? DetailTarget.SeriesT)?.series }
    }
    // build 196: terug in de app (of op Home) = opnieuw kijken wat aan de beurt is
    var resumed by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_START) resumed++ }
        val lc = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle
        lc.addObserver(obs)
        onDispose { lc.removeObserver(obs) }
    }
    // alle series van alle servers, elke 2 uur (per serie) ververst zolang de app open staat
    LaunchedEffect(mySeries, Store.activeProfileID, resumed) {
        while (true) {
            NewEpisodes.refresh(mySeries)
            delay(NewEpisodes.nextDelay(mySeries) ?: break)
        }
    }
    return remember(mySeries, progress, filter, pid, existing, NewEpisodes.version, Store.localWatched, Store.activeProfileID, Store.blockedTitles) {
        NewEpisodes.items(mySeries, progress, filter, pid, existing)
    }
}
