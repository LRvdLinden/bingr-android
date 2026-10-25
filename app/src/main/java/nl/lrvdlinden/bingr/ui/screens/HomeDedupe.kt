package nl.lrvdlinden.bingr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Build 151: elke rij op Home ontdubbelen over servers heen ([Build151Logic.Rows]): dezelfde titel (IMDb-id, of anders
 * genormaliseerde titel + jaar) maar één tegel. De bron die blijft: in de audio- of app-taal, dan de actieve server, dan
 * de rest. De andere servers blijven bruikbaar via "Andere bronnen" op de detailpagina.
 */
object HomeDedupe {
    private fun active(sid: String?) = sid == null || AllServers.isActive(sid)
    private fun rank(group: String, title: String, sid: String?): Long =
        Build151Logic.Rows.rank(Build151Logic.Rows.tags(group, title), active(sid), ContentLanguage.preferredSourceLanguages)

    fun movies(l: List<Movie>): List<Movie> =
        Build151Logic.Rows.dedupe(l, { it.imdbID }, { it.title }, { it.year }) { rank(it.group, it.title, it.sourceID) }
    fun series(l: List<Series>): List<Series> =
        Build151Logic.Rows.dedupe(l, { it.imdbID }, { it.title }, { it.year }) { rank(it.group, it.title, it.sourceID) }
    fun targets(l: List<DetailTarget>): List<DetailTarget> = Build151Logic.Rows.dedupe(l,
        { when (it) { is DetailTarget.MovieT -> it.movie.imdbID; is DetailTarget.SeriesT -> it.series.imdbID } },
        { it.title }, { it.year }) { rank(it.group, it.title, it.sourceID) }
    /** Zenders: op de opgeschoonde naam ("NL | NPO 1 HD" = "NPO 1 FHD"). */
    fun channels(l: List<Channel>): List<Channel> =
        Build151Logic.Rows.dedupe(l, { "c:" + Build151Logic.Channels.sameKey(it.name) }, { "" }, { null }) { rank(it.group, it.name, it.sourceID) }
}

/** Rij één keer per lijst ontdubbelen (niet bij elke hertekening). */
@Composable
fun <T> rememberDeduped(list: List<T>, f: (List<T>) -> List<T>): List<T> = remember(list) { f(list) }
