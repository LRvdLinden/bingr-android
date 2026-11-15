package nl.lrvdlinden.bingr

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.mediarouter.app.SystemOutputSwitcherDialogController
import nl.lrvdlinden.bingr.Build176TechLogic.AudioOut

/**
 * Build 176: kiezer voor de audio-uitvoer in de speler (telefoon/tablet), naast Chromecast: Bluetooth-koptelefoon of
 * -speaker, bedraad, USB of de luidspreker. Opent de systeemkiezer (Output Switcher, Android 11+; via MediaRouter),
 * anders de Bluetooth-instellingen. Het icoon volgt waar het geluid nu heen gaat.
 */
object AudioOutput {
    fun current(ctx: Context): AudioOut.Kind {
        if (Build.VERSION.SDK_INT < 23) return AudioOut.Kind.SPEAKER
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return AudioOut.Kind.SPEAKER
        return runCatching { AudioOut.active(am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }) }
            .getOrDefault(AudioOut.Kind.SPEAKER)
    }

    fun icon(k: AudioOut.Kind): ImageVector = when (k) {
        AudioOut.Kind.BLUETOOTH -> Icons.Filled.BluetoothAudio
        AudioOut.Kind.WIRED -> Icons.Filled.Headphones
        AudioOut.Kind.USB -> Icons.Filled.Cable
        AudioOut.Kind.HDMI -> Icons.Filled.Tv
        AudioOut.Kind.SPEAKER -> Icons.Filled.SpeakerGroup
    }

    /** Systeemkiezer openen; lukt dat niet (oudere Android of fabrikant), dan de Bluetooth-instellingen. */
    fun show(ctx: Context) {
        val shown = runCatching { SystemOutputSwitcherDialogController.showDialog(ctx) }.getOrDefault(false)
        if (shown) return
        runCatching { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** Huidige uitvoer, bijgewerkt als er een apparaat bij komt of weggaat (Bluetooth aan/uit, koptelefoon in/uit). */
@Composable
fun rememberAudioOutput(): AudioOut.Kind {
    val ctx = LocalContext.current
    var kind by remember { mutableStateOf(AudioOutput.current(ctx)) }
    DisposableEffect(Unit) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val cb = if (Build.VERSION.SDK_INT >= 23 && am != null) object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { kind = AudioOutput.current(ctx) }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { kind = AudioOutput.current(ctx) }
        } else null
        if (cb != null) runCatching { am!!.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper())) }
        onDispose { if (cb != null) runCatching { am!!.unregisterAudioDeviceCallback(cb) } }
    }
    return kind
}
