package nl.lrvdlinden.bingr

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Gedeeld aan/uit-icoon voor Mijn lijst én favoriete zenders: plus (+) als de titel/zender er niet in staat, vinkje (✓)
 * als hij er wel in staat (was een hartje / ster). `inList` = staat in Mijn lijst / is favoriet. Bij wisselen draait de plus 90° en krimpt hij weg terwijl het vinkje zich tekent (300 ms), met een lichte
 * schaal-bounce bij het toevoegen; bij verwijderen andersom. Met animatieschaal 0 ("Animaties verwijderen") wisselt
 * hij zonder animatie. Zelfde maten als de Material-iconen (24-raster), zodat hij past naast de andere knoppen.
 */
@Composable
fun PlusCheckIcon(inList: Boolean, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val progress = remember { Animatable(if (inList) 1f else 0f) }
    val scale = remember { Animatable(1f) }
    LaunchedEffect(inList) {
        val target = if (inList) 1f else 0f
        if (progress.value == target) return@LaunchedEffect
        if (HeroTrailer.reduceMotion(ctx)) {
            progress.snapTo(target)
            scale.snapTo(1f)
            return@LaunchedEffect
        }
        if (inList) launch {
            scale.animateTo(0.9f, tween(60))
            scale.animateTo(1.15f, tween(120, easing = FastOutSlowInEasing))
            scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        } else scale.snapTo(1f)
        progress.animateTo(target, tween(300, easing = FastOutSlowInEasing))
    }
    val check = remember { Path() }
    val part = remember { Path() }
    val measure = remember { PathMeasure() }
    Canvas(
        modifier.size(size).graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
    ) {
        val p = progress.value.coerceIn(0f, 1f)
        val s = min(this.size.width, this.size.height)
        val left = (this.size.width - s) / 2f
        val top = (this.size.height - s) / 2f
        val c = Offset(left + s / 2f, top + s / 2f)
        val width = s * 2.2f / 24f

        // plus: 0…0,55 draait hij 90° en krimpt hij naar het midden
        val plusPhase = min(1f, p / 0.55f)
        val arm = s * 7f / 24f * (1f - plusPhase)
        if (arm > 0.01f) {
            val turn = (PI / 2).toFloat() * plusPhase
            for (base in floatArrayOf(0f, (PI / 2).toFloat())) {
                val a = base + turn
                val dx = cos(a) * arm
                val dy = sin(a) * arm
                drawLine(tint, Offset(c.x - dx, c.y - dy), Offset(c.x + dx, c.y + dy), strokeWidth = width, cap = StrokeCap.Round)
            }
        }

        // vinkje: 0,35…1 tekent het zich van links naar rechts (verhoudingen van Icons.Default.Check)
        val checkPhase = max(0f, (p - 0.35f) / 0.65f)
        if (checkPhase > 0.001f) {
            check.reset()
            check.moveTo(left + s * 4.6f / 24f, top + s * 12.6f / 24f)
            check.lineTo(left + s * 9f / 24f, top + s * 17f / 24f)
            check.lineTo(left + s * 19.6f / 24f, top + s * 6.4f / 24f)
            measure.setPath(check, false)
            part.reset()
            measure.getSegment(0f, measure.length * min(1f, checkPhase), part, true)
            drawPath(part, tint, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** Zelfde plus/vinkje als Material-icoon, voor menu's (S2Action) die een ImageVector nodig hebben. */
object PlusCheck {
    fun icon(on: Boolean): ImageVector = if (on) Icons.Default.Check else Icons.Default.Add
}
