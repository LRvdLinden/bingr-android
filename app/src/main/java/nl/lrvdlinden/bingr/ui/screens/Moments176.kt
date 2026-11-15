package nl.lrvdlinden.bingr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Build 176 (punt 10, docs/ACCOUNT.md "Build 176", 3): rij "Momenten" met bewaarde momenten. Tik = afspelen vanaf dat
 * moment; lang indrukken = verwijderen. Op de detailpagina die van de titel (op tijd oplopend), op Home onder Mijn lijst
 * alle (nieuwste eerst).
 */
@Composable
fun MomentsRow(list: List<Bookmark176>, showTitle: Boolean, onPlay: (Bookmark176) -> Unit) {
    if (list.isEmpty()) return
    var removing by remember { mutableStateOf<Bookmark176?>(null) }
    MediaRow(L("Moments")) {
        items(list, key = { it.id }) { b ->
            Row(
                Modifier.width(tvs(if (showTitle) 220 else 150)).focusLift(1.05f, onLongClick = { removing = b }) { onPlay(b) }
                    .clip(RoundedCornerShape(10.dp)).background(Theme.card)
                    .padding(horizontal = tvs(12), vertical = tvs(10)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(tvs(10)),
            ) {
                Icon(Icons.Default.Bookmark, null, tint = Theme.red, modifier = Modifier.size(tvs(20)))
                Column(Modifier.weight(1f)) {
                    Text(Build176Logic.Bookmarks.clock(b.t), color = Color.White, fontSize = tsp(15), fontWeight = FontWeight.Bold, maxLines = 1)
                    if (showTitle) Text(b.title ?: "", color = Theme.secondary, fontSize = tsp(12), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    else b.title?.substringAfter(" · ", "")?.takeIf { it.isNotEmpty() }?.let {
                        Text(it, color = Theme.secondary, fontSize = tsp(12), maxLines = 1)
                    }
                }
            }
        }
    }
    removing?.let { b ->
        S2ActionDialog("${b.title ?: ""} · ${Build176Logic.Bookmarks.clock(b.t)}", listOf(
            S2Action(L("Play"), Icons.Default.PlayArrow, primary = true) { onPlay(b) },
            S2Action(L("Delete"), Icons.Default.Delete, destructive = true) { Store.removeBookmark(b.id) },
        ), onDismiss = { removing = null })
    }
}

/** Alle momenten van het actieve profiel (nieuwste eerst); kinderprofiel: alleen titels uit zijn (gefilterde) bibliotheek. */
@Composable
fun AllMomentsRow() {
    val all = Store.bookmarks
    val lib = Playlists.library
    val list = remember(all, lib, Store.activeProfileID) {
        if (Store.activeProfile?.kids != true) all else {
            val movies = lib.movies.mapTo(HashSet()) { it.id }
            val eps = lib.series.flatMapTo(HashSet()) { s -> s.episodes.map { it.id } }
            all.filter { it.key in movies || it.key in eps }
        }
    }
    MomentsRow(list, showTitle = true) { b -> playMoment(b) }
}

/** Afspelen vanaf het moment (film of aflevering op de actieve server; anders via de server van het moment). */
fun playMoment(b: Bookmark176) {
    val key = b.key ?: return
    val title = (b.title ?: "").substringBefore(" · ")
    AppScope.launch {
        screensSafe { ResumePlayer.play(key, title, restart = false, sourceID = b.playlistID, startAt = b.t.toDouble()) }?.let { ScreensNav.detail(it) }
    }
}
