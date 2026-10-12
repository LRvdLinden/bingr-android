package nl.lrvdlinden.bingr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Tabblad "Downloads" (build 52, alleen telefoon/tablet): voortgang, pauzeren/hervatten/verwijderen,
 * gebruikte opslag en offline afspelen (hervat op de opgeslagen positie).
 */
@Composable
fun DownloadsScreen() {
    val list = Downloads.visible
    var used by remember { mutableLongStateOf(0L) }
    var deleting by remember { mutableStateOf<DownloadItem?>(null) }
    // opslag opnieuw tellen als er iets verandert (bytes lopen op tijdens het downloaden)
    LaunchedEffect(Downloads.items) { used = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Downloads.storageUsed() } }

    S2Page(L("Downloads"), onClose = null) {
        item {
            S2Section(footer = L("Downloads are kept on this device and play without an internet connection.")) {
                S2Row(L("Storage used"), icon = Icons.Default.Download, value = Downloads.formatBytes(used))
            }
        }
        if (list.isEmpty()) {
            item {
                Column(
                    Modifier.widthIn(max = S2MaxWidth).fillMaxWidth().padding(horizontal = Theme.edge, vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Download, null, tint = Theme.secondary, modifier = Modifier.size(48.dp))
                    Text(L("No downloads yet"), color = Color.White, fontSize = tsp(17), fontWeight = FontWeight.Bold)
                    Text(L("Tap the download button on a movie or episode to watch it offline."), color = Theme.secondary,
                        fontSize = tsp(13), textAlign = TextAlign.Center)
                }
            }
        } else {
            item { Spacer(Modifier.height(12.dp)) }
            items(list, key = { it.key }) { d -> DownloadRow(d, onDelete = { deleting = d }) }
        }
    }

    deleting?.let { d ->
        S2ActionDialog(d.subtitle?.let { "${d.seriesTitle ?: d.title} · $it" } ?: d.title, listOf(
            S2Action(L("Delete download"), Icons.Default.Delete, destructive = true) { Downloads.delete(d.key) },
        ), onDismiss = { deleting = null })
    }
}

@Composable
private fun DownloadRow(d: DownloadItem, onDelete: () -> Unit) {
    val title = if (d.type == MediaType.EPISODE) d.seriesTitle ?: d.title else d.title
    val status = when (d.status) {
        Downloads.DONE -> Downloads.formatBytes(d.totalBytes.takeIf { it > 0 } ?: d.bytes)
        Downloads.DOWNLOADING -> if (d.totalBytes > 0) L("%@ of %@", Downloads.formatBytes(d.bytes), Downloads.formatBytes(d.totalBytes))
            else Downloads.formatBytes(d.bytes)
        Downloads.PAUSED -> L("Paused") + if (d.totalBytes > 0) " · ${(d.progress * 100).toInt()}%" else ""
        Downloads.FAILED -> L("Download failed") + (d.error?.let { " · $it" } ?: "")
        else -> L("Waiting…")
    }
    val resume = Store.progress[d.id]?.fraction
    Row(
        Modifier.widthIn(max = S2MaxWidth).fillMaxWidth().padding(horizontal = Theme.edge, vertical = 5.dp)
            .clip(RoundedCornerShape(10.dp)).background(Theme.card)
            .focusLift(1.02f) { if (d.isDone) AppState.play(Downloads.playable(d)) }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(tvs(64)).height(tvs(90)).clip(RoundedCornerShape(6.dp))) {
            RemoteImage(d.poster, Modifier.fillMaxWidth().height(tvs(90)), placeholder = title.take(12))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = Color.White, fontSize = tsp(15), fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            d.subtitle?.takeIf { d.type == MediaType.EPISODE }?.let {
                Text(it, color = Theme.secondary, fontSize = tsp(12), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(status, color = if (d.status == Downloads.FAILED) Theme.red else Theme.secondary, fontSize = tsp(12), maxLines = 2)
            if (!d.isDone && d.totalBytes > 0) ProgressBar(d.progress, Modifier.padding(top = 4.dp))
            if (d.isDone && resume != null && resume > 0.02) ProgressBar(resume, Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.width(6.dp))
        when (d.status) {
            Downloads.DONE -> S2IconButton(Icons.Default.PlayArrow, L("Play")) { AppState.play(Downloads.playable(d)) }
            Downloads.DOWNLOADING, Downloads.QUEUED -> S2IconButton(Icons.Default.Pause, L("Pause")) { Downloads.pause(d.key) }
            else -> S2IconButton(Icons.Default.Refresh, L("Resume")) { Downloads.resume(d.key) }
        }
        S2IconButton(Icons.Default.Delete, L("Delete download"), tint = Theme.secondary) { onDelete() }
    }
}
