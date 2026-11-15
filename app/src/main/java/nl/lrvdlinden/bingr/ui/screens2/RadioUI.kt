package nl.lrvdlinden.bingr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Build 171: mini-speler van de radio onderaan (logo, naam, afspelen/pauze, sluiten = stoppen). */
@Composable
fun RadioMiniPlayer() {
    val c = Radio.current ?: return
    Row(
        Modifier.fillMaxWidth().background(Theme.elevated).padding(horizontal = tvs(12), vertical = tvs(8)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(tvs(40)).clip(RoundedCornerShape(8.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
            val logo = c.logo
            if (logo != null) RemoteImage(logo, Modifier.size(tvs(40)))
            else Icon(Icons.Default.Radio, null, tint = Color.White, modifier = Modifier.size(tvs(22)))
        }
        Spacer(Modifier.width(tvs(12)))
        Column(Modifier.weight(1f)) {
            Text(c.displayName, color = Color.White, fontSize = tsp(14), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(L("Radio"), color = Theme.secondary, fontSize = tsp(12), maxLines = 1)
        }
        if (Radio.buffering && !Radio.playing) {
            CircularProgressIndicator(color = Theme.red, strokeWidth = 2.dp, modifier = Modifier.size(tvs(20)))
            Spacer(Modifier.width(tvs(8)))
        }
        S2IconButton(if (Radio.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (Radio.playing) L("Pause") else L("Play")) { Radio.togglePause() }
        S2IconButton(Icons.Default.Close, L("Stop")) { Radio.stop() }
    }
}
