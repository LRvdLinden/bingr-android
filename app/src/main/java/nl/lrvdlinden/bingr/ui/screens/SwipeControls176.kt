package nl.lrvdlinden.bingr

import android.content.Context
import android.media.AudioManager
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Build 176 (punt 12): helderheid (links) en volume (rechts) met verticaal vegen in de speler op telefoon/tablet.
 * Helderheid alleen voor dit venster (het systeem blijft ongemoeid) en terug naar de systeemstand bij het sluiten.
 */
object SwipeControls {
    class Shown(val side: Build176Logic.Swipe.Side, val value: Float)
    var shown by mutableStateOf<Shown?>(null)
        private set
    private var hide: Job? = null
    private var changed = false

    private val audio: AudioManager? get() = AppState.appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    fun brightness(): Float {
        val w = MainActivity.current?.window ?: return 0.5f
        val b = w.attributes.screenBrightness
        if (b >= 0f) return b
        return runCatching {
            android.provider.Settings.System.getInt(AppState.appContext.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f
        }.getOrDefault(0.5f).coerceIn(0f, 1f)
    }

    fun setBrightness(v: Float) {
        val w = MainActivity.current?.window ?: return
        val a = w.attributes
        a.screenBrightness = v.coerceIn(0.01f, 1f)
        w.attributes = a
        changed = true
        show(Build176Logic.Swipe.Side.BRIGHTNESS, v)
    }

    fun volume(): Float {
        val am = audio ?: return 0f
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()
    }

    fun setVolume(v: Float) {
        val am = audio ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val idx = Math.round(v * max)
        runCatching { if (am.getStreamVolume(AudioManager.STREAM_MUSIC) != idx) am.setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0) }
        show(Build176Logic.Swipe.Side.VOLUME, v)
    }

    private fun show(side: Build176Logic.Swipe.Side, v: Float) { hide?.cancel(); shown = Shown(side, v) }

    fun endSoon() {
        hide?.cancel()
        hide = AppScope.launch { delay(700); shown = null }
    }

    /** Speler dicht: helderheid weer volgens het systeem. */
    fun restoreBrightness() {
        hide?.cancel(); shown = null
        if (!changed) return
        changed = false
        val w = MainActivity.current?.window ?: return
        val a = w.attributes
        a.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        w.attributes = a
    }
}

/** Icoon met een balkje (helderheid of volume) midden in beeld tijdens het vegen. */
@Composable
fun SwipeIndicator(modifier: Modifier = Modifier) {
    val s = SwipeControls.shown
    AnimatedVisibility(s != null, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        val cur = SwipeControls.shown ?: return@AnimatedVisibility
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(if (cur.side == Build176Logic.Swipe.Side.BRIGHTNESS) Icons.Default.BrightnessMedium else Icons.AutoMirrored.Filled.VolumeUp,
                null, tint = Color.White, modifier = Modifier.size(22.dp))
            Box(Modifier.width(120.dp).height(4.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.25f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(cur.value.coerceIn(0f, 1f)).background(Color.White))
            }
        }
    }
}
