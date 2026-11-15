package nl.lrvdlinden.bingr

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Build 171: bediening vergrendelen (alleen telefoon en tablet). Vergrendeld worden alle tikken en vegen genegeerd (ook
 * dubbeltikken, helderheid en de zijkant). Een tik laat een klein slot zien; ontgrendelen = het slot 1 s vasthouden
 * ("Houd vast om te ontgrendelen", met een ringetje dat volloopt). De systeemknoppen (terug, volume, aan/uit) blijven werken.
 */
@Composable
fun ControlsLockLayer(onUnlock: () -> Unit) {
    var shown by remember { mutableIntStateOf(1) }
    var visible by remember { mutableStateOf(true) }
    var progress by remember { mutableFloatStateOf(0f) }
    var holding by remember { mutableStateOf(false) }
    LaunchedEffect(holding) {
        if (!holding) { progress = 0f; return@LaunchedEffect }
        val start = System.currentTimeMillis()
        while (true) {
            progress = ((System.currentTimeMillis() - start) / 1_000f).coerceIn(0f, 1f)
            if (progress >= 1f) { onUnlock(); break }
            delay(16)
        }
    }
    LaunchedEffect(shown) { visible = true; delay(2_500); if (progress == 0f) visible = false }
    // alles opvangen: niets gaat naar de speler eronder
    Box(Modifier.fillMaxSize().pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                if (e.changes.any { it.pressed && !it.previousPressed }) shown++
                e.changes.forEach { it.consume() }
            }
        }
    }) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart).safeDrawingPadding().padding(start = 28.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f))
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                holding = true
                                do {
                                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                                } while (ev.changes.any { it.pressed })
                                holding = false
                                shown++
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (progress > 0f) CircularProgressIndicator(progress = { progress }, color = Color.White, strokeWidth = 3.dp,
                        modifier = Modifier.size(60.dp))
                    Icon(Icons.Filled.Lock, L("Locked"), tint = Color.White, modifier = Modifier.size(26.dp))
                }
                Text(L("Hold to unlock"), color = Color.White, fontSize = tsp(12), fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
    }
}
