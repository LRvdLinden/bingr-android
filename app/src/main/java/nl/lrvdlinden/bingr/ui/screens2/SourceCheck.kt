package nl.lrvdlinden.bingr

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

/** Oranje voor verschillen tussen server, Bingr en wat zichtbaar is. */
private val CheckOrange = Color(0xFFFF9F0A)

/** Eén telling: wat de server stuurt (null = onbekend), wat Bingr inleest en wat zichtbaar is. */
data class SourceCount(val label: String, val server: Int?, val read: Int, val visible: Int?) {
    val unread: Int get() = server?.let { (it - read).coerceAtLeast(0) } ?: 0
    val hidden: Int get() = visible?.let { (read - it).coerceAtLeast(0) } ?: 0
}

/** Resultaat van "Bron controleren" (zonder gebruikersnamen, wachtwoorden of URL's). */
data class SourceReport(
    val sourceName: String,
    val kind: PlaylistKind,
    val items: List<SourceCount>,
    val categories: List<SourceCount>,
    val accountStatus: String? = null,
    val expires: String? = null,
    val connections: String? = null,
    /** Steekproef series: (met afleveringen, getest) */
    val sample: Pair<Int, Int>? = null,
    val error: String? = null,
)

/**
 * Verse diagnose van de actieve bron: ruwe aantallen van de server, wat Bingr daarvan inleest en wat er
 * zichtbaar blijft na categorie-instellingen, kinderslot en de leeftijdsgrens van het profiel.
 */
object SourceCheck {
    /** Op de main thread aanroepen (leest de filtercontext uit Compose-state); zwaar werk gebeurt op de achtergrond. */
    suspend fun run(src: PlaylistSource, progress: (String) -> Unit): SourceReport {
        val ctx = Playlists.context(src)
        val password = Store.secret(src.passwordKey) ?: ""
        return try {
            when (src.kind) {
                PlaylistKind.XTREAM -> xtream(src, password, ctx, progress)
                PlaylistKind.M3U -> m3u(src, ctx, progress)
                else -> other(src, ctx, progress)
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            SourceReport(src.name, src.kind, emptyList(), emptyList(), error = Net.message(e))
        }
    }

    private fun counts(rawCounts: Triple<Int?, Int?, Int?>, read: Library, visible: Library): List<SourceCount> = listOf(
        SourceCount(L("Live channels"), rawCounts.first, read.channels.size, visible.channels.size),
        SourceCount(L("Movies"), rawCounts.second, read.movies.size, visible.movies.size),
        SourceCount(L("Series"), rawCounts.third, read.series.size, visible.series.size),
    )

    private fun categoryCounts(raw: Triple<Int?, Int?, Int?>, read: Library, visible: Library): List<SourceCount> = listOf(
        SourceCount(L("Live channels"), raw.first, read.channelGroups.size, visible.channelGroups.size),
        SourceCount(L("Movies"), raw.second, read.movieGroups.size, visible.movieGroups.size),
        SourceCount(L("Series"), raw.third, read.seriesGroups.size, visible.seriesGroups.size),
    )

    private suspend fun xtream(src: PlaylistSource, password: String, ctx: Playlists.FilterContext, progress: (String) -> Unit): SourceReport {
        val x = XtreamClient(src, password)
        progress(L("Signing in to server…"))
        val auth = x.accountInfo()
        val ui = auth.jO("user_info")
        progress(L("Fetching live TV, movies and series…"))
        val raw = withContext(Dispatchers.Default) { x.rawLists() }
        progress(L("Checking…"))
        val (read, visible) = withContext(Dispatchers.Default) {
            val lib = x.parse(raw)
            lib to Playlists.apply(lib, ctx)
        }
        val items = listOf(
            SourceCount(L("Live channels"), raw.live.size, read.channels.size, visible.channels.size),
            SourceCount(L("Movies"), raw.vod.size, read.movies.size, visible.movies.size),
            SourceCount(L("Series"), raw.ser.size, read.series.size, visible.series.size),
        )
        val cats = categoryCounts(Triple(raw.liveCats.size, raw.vodCats.size, raw.serCats.size), read, visible)

        // steekproef: tot 5 willekeurige series → get_series_info
        val picks = read.series.shuffled().take(5)
        var withEpisodes = 0
        for ((i, s) in picks.withIndex()) {
            progress("${L("Checking…")} ${i + 1}/${picks.size}")
            val eps = try { x.episodes(s) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
            if (eps.isNotEmpty()) withEpisodes++
        }

        val expires = ui.jL("exp_date")?.takeIf { it > 0 }?.let {
            DateFormat.getDateInstance(DateFormat.MEDIUM, Localizer.locale).format(Date(it * 1000))
        }
        val max = ui.jS("max_connections")?.takeIf { it.isNotBlank() }
        val active = ui.jS("active_cons")?.takeIf { it.isNotBlank() }
        val conns = when {
            active != null && max != null -> "$active / $max"
            max != null -> max
            else -> active
        }
        return SourceReport(src.name, src.kind, items, cats,
            accountStatus = ui.jS("status")?.takeIf { it.isNotBlank() },
            expires = expires, connections = conns,
            sample = if (picks.isEmpty()) null else withEpisodes to picks.size)
    }

    private suspend fun m3u(src: PlaylistSource, ctx: Playlists.FilterContext, progress: (String) -> Unit): SourceReport {
        progress(L("Downloading playlist…"))
        val u = Net.urlValue(src.m3uURL) ?: throw Net.badURL()
        val text = Net.text(u)
        progress(L("Checking…"))
        return withContext(Dispatchers.Default) {
            val extinf = text.lineSequence().count { it.trimStart().startsWith("#EXTINF") }
            val entries = M3UParser.entries(text).size
            val read = M3UParser.library(text, src.id)
            val visible = Playlists.apply(read, ctx)
            val none = Triple<Int?, Int?, Int?>(null, null, null)
            val items = listOf(SourceCount("#EXTINF", extinf, entries, null)) + counts(none, read, visible)
            SourceReport(src.name, src.kind, items, categoryCounts(none, read, visible))
        }
    }

    private suspend fun other(src: PlaylistSource, ctx: Playlists.FilterContext, progress: (String) -> Unit): SourceReport {
        progress(L("Fetching your library…"))
        val read: Library = when (src.kind) {
            PlaylistKind.STALKER -> StalkerClient(src).also { it.connect() }.library()
            PlaylistKind.JELLYFIN, PlaylistKind.EMBY -> JellyfinClient(src, Playlists.session(src)).library()
            PlaylistKind.PLEX -> PlexClient(Playlists.session(src) ?: throw JellyfinClient.notLoggedIn).library()
            PlaylistKind.STREMIO -> StremioClient(Playlists.loadStremioAddons(src)).library()
            else -> Library()
        }
        progress(L("Checking…"))
        val visible = withContext(Dispatchers.Default) { Playlists.apply(read, ctx) }
        val none = Triple<Int?, Int?, Int?>(null, null, null)
        return SourceReport(src.name, src.kind, counts(none, read, visible), categoryCounts(none, read, visible))
    }

    private fun n(v: Int?): String = v?.let { NumberFormat.getIntegerInstance(Localizer.locale).format(it) } ?: "–"

    /** Uitleg bij een verschil (oranje), of null. */
    fun notes(c: SourceCount): List<String> = buildList {
        if (c.unread > 0) add(L("%d items could not be read", c.unread))
        if (c.hidden > 0) add(L("%d hidden by your category settings or profile", c.hidden))
    }

    /** Platte tekst om te kopiëren: geen gebruikersnaam, wachtwoord of server-URL. */
    fun text(r: SourceReport): String = buildString {
        appendLine("Bingr · ${L("Check source")}")
        appendLine("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android")
        appendLine("${r.kind.title} · ${r.sourceName}")
        r.error?.let { appendLine(it) }
        if (r.accountStatus != null || r.expires != null || r.connections != null) {
            appendLine()
            r.accountStatus?.let { appendLine("${L("Account status")}: $it") }
            r.expires?.let { appendLine("${L("Expires")}: $it") }
            r.connections?.let { appendLine("${L("Connections")}: $it") }
        }
        fun block(title: String?, list: List<SourceCount>, withNotes: Boolean) {
            if (list.isEmpty()) return
            appendLine()
            title?.let { appendLine(it) }
            appendLine("${L("Server")} / ${L("Read by Bingr")} / ${L("Visible")}")
            for (c in list) {
                appendLine("${c.label}: ${n(c.server)} / ${n(c.read)} / ${n(c.visible)}")
                if (withNotes) notes(c).forEach { appendLine("  ! $it") }
            }
        }
        block(null, r.items, true)
        block(L("Categories"), r.categories, false)
        r.sample?.let { (ok, total) -> appendLine(); appendLine(L("%d/%d series have episodes", ok, total)) }
    }.trimEnd()

    internal fun fmt(v: Int?): String = n(v)
}

/** "Bron controleren": verse diagnose van de actieve bron (telefoon/tablet en TV). */
@Composable
fun SourceCheckScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val src = Playlists.source?.let { s -> Store.playlists.firstOrNull { it.id == s.id } ?: s }
    var report by remember { mutableStateOf<SourceReport?>(null) }
    var status by remember { mutableStateOf(L("Checking…")) }
    var run by remember { mutableIntStateOf(0) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(src?.id, run) {
        report = null; copied = false
        if (src == null) return@LaunchedEffect
        status = L("Checking…")
        report = SourceCheck.run(src) { status = it }
    }

    S2Page(L("Check source"), onClose = onClose) {
        val r = report
        if (src == null) {
            item { S2Section { S2Row("–", tint = Theme.secondary) } }
            return@S2Page
        }
        if (r == null) {
            item {
                S2Section {
                    S2Row(status, subtitle = src.name, leading = {
                        CircularProgressIndicator(color = Theme.red, strokeWidth = 2.dp, modifier = Modifier.size(tvs(20)))
                    })
                }
            }
            return@S2Page
        }

        item {
            S2Section {
                S2Row(r.sourceName, subtitle = r.kind.title, leading = { AvatarCircle(src, tvs(34)) })
                r.error?.let { S2Row(it, tint = Theme.red) }
                if (!AppState.isTV) {
                    S2Row(L("Copy report"), icon = Icons.Default.ContentCopy, onClick = {
                        s2Copy(context, SourceCheck.text(r)); copied = true
                    }, trailing = {
                        if (copied) Icon(Icons.Default.Check, null, tint = Theme.green, modifier = Modifier.size(tvs(18)))
                    })
                }
                S2Row(L("Refresh now"), icon = Icons.Default.Refresh, onClick = { run++ })
            }
        }

        if (r.accountStatus != null || r.expires != null || r.connections != null) {
            item {
                S2Section(L("Server")) {
                    r.accountStatus?.let { CheckInfoRow(L("Account status"), it) }
                    r.expires?.let { CheckInfoRow(L("Expires"), it) }
                    r.connections?.let { CheckInfoRow(L("Connections"), it) }
                }
            }
        }

        if (r.items.isNotEmpty()) {
            item {
                S2Section(r.kind.title) {
                    CheckHeaderRow()
                    r.items.forEach { CheckCountRow(it, highlight = true) }
                }
            }
        }
        if (r.categories.isNotEmpty()) {
            item {
                S2Section(L("Categories")) {
                    CheckHeaderRow()
                    r.categories.forEach { CheckCountRow(it, highlight = false) }
                }
            }
        }
        r.sample?.let { (ok, total) ->
            item {
                S2Section(L("Series")) {
                    CheckInfoRow(L("%d/%d series have episodes", ok, total), null, warn = ok < total)
                }
            }
        }
    }
}

/** Focusbaar op TV (zodat de lijst met de afstandsbediening scrolt), met witte rand bij focus. */
@Composable
private fun checkRowModifier(): Modifier {
    if (!AppState.isTV) return Modifier.fillMaxWidth()
    var focused by remember { mutableStateOf(false) }
    return Modifier.fillMaxWidth()
        .onFocusChanged { focused = it.isFocused }
        .focusable()
        .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(10.dp)) else Modifier)
}

@Composable
private fun CheckInfoRow(title: String, value: String?, warn: Boolean = false) {
    Row(checkRowModifier().heightIn(min = tvs(44)).padding(horizontal = 16.dp, vertical = tvs(10)),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = if (warn) CheckOrange else Color.White, fontSize = tsp(15), modifier = Modifier.weight(1f))
        if (value != null) {
            Spacer(Modifier.width(8.dp))
            Text(value, color = Theme.secondary, fontSize = tsp(14), textAlign = TextAlign.End)
        }
    }
}

@Composable
private fun CheckHeaderRow() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = tvs(8)), verticalAlignment = Alignment.Bottom) {
        Spacer(Modifier.weight(1.3f))
        listOf(L("Server"), L("Read by Bingr"), L("Visible")).forEach {
            Text(it, color = S2Grey, fontSize = tsp(11), textAlign = TextAlign.End, maxLines = 2, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun CheckCountRow(c: SourceCount, highlight: Boolean) {
    val notes = if (highlight) SourceCheck.notes(c) else emptyList()
    Column(checkRowModifier().padding(horizontal = 16.dp, vertical = tvs(8)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.label, color = Color.White, fontSize = tsp(15), maxLines = 1, modifier = Modifier.weight(1.3f))
            val readColor = if (highlight && c.unread > 0) CheckOrange else Color.White
            val visColor = if (highlight && c.hidden > 0) CheckOrange else Color.White
            Text(SourceCheck.fmt(c.server), color = Theme.secondary, fontSize = tsp(14), textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            Text(SourceCheck.fmt(c.read), color = readColor, fontSize = tsp(14), fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            Text(SourceCheck.fmt(c.visible), color = visColor, fontSize = tsp(14), fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
        notes.forEach { Text(it, color = CheckOrange, fontSize = tsp(12)) }
    }
}
