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
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Build 171: mini-speler van de radio onderaan (logo, naam, afspelen/pauze, sluiten = stoppen). */
@Composable
fun RadioMiniPlayer() {
    // build 173: slaaptimer afgegaan: "Slaaptimer: gestopt" met Verder luisteren
    val slept = Radio.sleptChannel
    if (Radio.current == null && slept != null) {
        Row(
            Modifier.fillMaxWidth().background(Theme.elevated).padding(horizontal = tvs(12), vertical = tvs(8)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Bedtime, null, tint = Theme.secondary, modifier = Modifier.size(tvs(22)))
            Spacer(Modifier.width(tvs(12)))
            Column(Modifier.weight(1f)) {
                Text(L("Sleep timer: stopped"), color = Color.White, fontSize = tsp(14), fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(slept.displayName, color = Theme.secondary, fontSize = tsp(12), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            S2IconButton(Icons.Default.PlayArrow, L("Continue listening")) { Radio.continueAfterSleep() }
            S2IconButton(Icons.Default.Close, L("Close")) { Radio.dismissSleep() }
        }
        return
    }
    val c = Radio.current ?: return
    var sleepMenu by remember { mutableStateOf(false) }
    val sleepAt = Radio.sleepAt
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sleepAt) { while (sleepAt != null) { now = System.currentTimeMillis(); delay(1_000) } }
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
        // build 173: slaaptimer (30 / 60 / 90 minuten / uit) met aftellen
        if (sleepAt != null) {
            Text(Build173PlayerLogic.countdown(sleepAt - now), color = Theme.secondary, fontSize = tsp(12), fontFamily = FontFamily.Monospace)
        }
        Box {
            S2IconButton(Icons.Default.Bedtime, L("Sleep timer"), tint = if (sleepAt != null) Theme.red else Color.White) { sleepMenu = true }
            DropdownMenu(expanded = sleepMenu, onDismissRequest = { sleepMenu = false }) {
                for (s in Build173PlayerLogic.Sleep.choices.filter { it != Build173PlayerLogic.Sleep.EndOfEpisode }) {
                    DropdownMenuItem(text = { Text(sleepTitle173(s, live = true)) }, onClick = { sleepMenu = false; Radio.setSleep(s) })
                }
            }
        }
        S2IconButton(if (Radio.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (Radio.playing) L("Pause") else L("Play")) { Radio.togglePause() }
        S2IconButton(Icons.Default.Close, L("Stop")) { Radio.stop() }
    }
}
