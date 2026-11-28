package nl.lrvdlinden.bingr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Build 195: "Nieuwe aflevering" in Verder kijken (regels in [Build195Logic]). Alleen series in Mijn lijst van het actieve
 * profiel. Schrijft NOOIT naar de voortgang of de sync (Store.progress / StateSync worden alleen gelezen); alleen lokale Prefs
 * "hid" en "at". De tegels worden afgeleid (niet opgeslagen): uit Mijn lijst, de gezien-sleutels en de afleveringen van de
 * server. Lokaal bewaard (per profiel en serie): [Build195Logic.prefKey] "hid" (weggehaald: de aflevering van de tegel) en "at" (wanneer de tegel voor het eerst verscheen, voor de volgorde).
 */
object NewEpisodes {
    var version by mutableIntStateOf(0); private set
    private var fresh by mutableStateOf(mapOf<String, List<Episode>>())
    private val fetchedAt = HashMap<String, Long>()
    private const val REFRESH_MS = 30 * 60 * 1000L
    private const val MAX_SERIES = 25

    private fun skey(s: Series) = "${s.sourceID ?: ""}|${s.id}"

    private fun epsOf(s: Series): List<Episode> =
        if (s.episodes.isNotEmpty()) s.episodes else fresh[skey(s)] ?: Playlists.episodeCache[s.id] ?: emptyList()

    private fun ep(e: Episode) = Build195Logic.Ep(e.season, e.number)

    private fun watchedFn(s: Series, eps: List<Episode>, progress: Map<String, ProgressEntry>): (Build195Logic.Ep) -> Boolean {
        val imdb = Trakt.imdb(s)
        val byEp = HashMap<Build195Logic.Ep, Episode>()
        for (e in eps) byEp.putIfAbsent(ep(e), e)
        // grotendeels gekeken (vanaf 95%) telt als gezien
        return { k -> byEp[k]?.let { Playables.isWatched(it, imdb) || (progress[it.id]?.fraction ?: 0.0) >= 0.95 } ?: false }
    }

    /** Afleveringen van de series in Mijn lijst verversen bij het openen van Home (niet vaker dan elk halfuur per serie). */
    suspend fun refresh(series: List<Series>) {
        var changed = false
        for (s in series.take(MAX_SERIES)) {
            val k = skey(s)
            val now = System.currentTimeMillis()
            if (now - (fetchedAt[k] ?: 0L) < REFRESH_MS) continue
            val eps = withTimeoutOrNull(20_000) { screensSafe { Playlists.freshEpisodes(s) } } ?: continue
            fetchedAt[k] = now
            if (eps.isNotEmpty() && fresh[k] != eps) { fresh = fresh + (k to eps); changed = true }
        }
        if (changed) version++
    }

    /** De tegels. [existing] = genormaliseerde titels die al een gewone tegel hebben (geen dubbele items). */
    fun items(series: List<Series>, progress: Map<String, ProgressEntry>, filter: Filter, pid: String?,
              existing: Set<String>): List<ContinueItem> {
        if (filter == Filter.MOVIES) return emptyList()
        val profile = Store.activeProfileID
        val out = ArrayList<ContinueItem>()
        for (s in series) {
            if (Store.blockedTitles.isNotEmpty() && Store.isTitleBlocked(s.sourceID ?: pid, true, s.id)) continue
            if (AllServersLogic.normTitle(s.title) in existing) continue
            val eps = epsOf(s); if (eps.isEmpty()) continue
            val list = eps.map(::ep)
            // nooit naast of in plaats van een lopende aflevering: de voortgang (alleen gelezen) gaat voor
            val fractions = progress.filter { Store.belongsToActive(it.value.profileID) }.mapValues { it.value.fraction }
            val next = Build195Logic.tile(eps.map { it.id to ep(it) }, fractions, watchedFn(s, eps, progress),
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

/** De "Nieuwe aflevering"-tegels voor Verder kijken (build 195); ververst de afleveringen bij het openen van Home. */
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
    LaunchedEffect(mySeries, Store.activeProfileID) { NewEpisodes.refresh(mySeries) }
    return remember(mySeries, progress, filter, pid, existing, NewEpisodes.version, Store.localWatched, Store.activeProfileID, Store.blockedTitles) {
        NewEpisodes.items(mySeries, progress, filter, pid, existing)
    }
}
