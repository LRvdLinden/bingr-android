package nl.lrvdlinden.bingr

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Build 164: stilstaand beeld per zender voor de tv-gids. Een kleine momentopname (jpg, 320 breed) als die zender
 * speelde of als voorbeeld draaide, in cacheDir/guide-stills (nooit in SharedPreferences, zie build 158). Hooguit
 * 30 min oud voor gebruik en hooguit 200 zenders (de oudste eerst weg): [Build164Logic.Stills].
 * Kan er geen frame gepakt worden (VLC, beveiligd beeld, zwart beeld), dan niets: de gids toont het logo.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object ChannelStills {
    /** Telt op bij elke nieuwe momentopname (de gids tekent dan het nieuwe beeld). */
    var version by mutableIntStateOf(0); private set

    private val dir: File by lazy { File(AppState.appContext.cacheDir, "guide-stills").apply { mkdirs() } }

    fun file(key: String): File = File(dir, Build164Logic.Stills.fileName(key))

    /** Het bestand als het bruikbaar is (bestaat en hooguit 30 min oud), anders null. */
    fun usable(key: String, now: Long = System.currentTimeMillis()): File? {
        val f = file(key)
        val at = runCatching { if (f.isFile) f.lastModified() else null }.getOrNull()
        return if (Build164Logic.Stills.usable(at, now)) f else null
    }

    /** Momentopname van het beeld in [view] bewaren onder [key] (alleen op de hoofdthread aanroepen). */
    fun capture(view: PlayerView?, key: String) {
        val v = view ?: return
        val vs = runCatching { v.player?.videoSize }.getOrNull()
        val (w, h) = Build164Logic.Stills.size(vs?.width ?: 0, vs?.height ?: 0)
        when (val surface = v.videoSurfaceView) {
            is TextureView -> {
                if (!surface.isAvailable) return
                val bmp = runCatching { surface.getBitmap(w, h) }.getOrNull() ?: return
                save(bmp, key)
            }
            is SurfaceView -> {
                if (!surface.holder.surface.isValid) return
                val bmp = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return
                runCatching {
                    PixelCopy.request(surface, bmp, { r ->
                        if (r == PixelCopy.SUCCESS) save(bmp, key) else bmp.recycle()
                    }, Handler(Looper.getMainLooper()))
                }.onFailure { bmp.recycle() }
            }
            else -> {}
        }
    }

    /** Zwart (of bijna zwart) beeld, bijv. nog geen frame of beveiligd: niet bewaren. */
    private fun blank(b: Bitmap): Boolean {
        var bright = 0
        for (i in 0 until 5) for (j in 0 until 5) {
            val p = b.getPixel((b.width * (2 * i + 1)) / 10, (b.height * (2 * j + 1)) / 10)
            val l = ((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)
            if (l > 60) bright++
        }
        return bright < 2
    }

    private fun save(bmp: Bitmap, key: String) {
        AppScope.launch(Dispatchers.IO) {
            try {
                if (blank(bmp)) return@launch
                val f = file(key)
                val tmp = File(dir, f.name + ".tmp")
                tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 72, it) }
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
                f.setLastModified(System.currentTimeMillis())
                evict()
                withContext(Dispatchers.Main) { version++ }
            } catch (_: Throwable) {
            } finally {
                runCatching { bmp.recycle() }
            }
        }
    }

    /** Boven de 200 zenders de oudste weg (en achtergebleven .tmp-bestanden). */
    private fun evict() {
        val files = dir.listFiles() ?: return
        files.filter { it.name.endsWith(".tmp") && System.currentTimeMillis() - it.lastModified() > 60_000L }.forEach { it.delete() }
        val jpgs = files.filter { it.name.endsWith(".jpg") }
        val gone = Build164Logic.Stills.evict(jpgs.map { Build164Logic.Stills.Entry(it.name, it.lastModified()) }).toHashSet()
        jpgs.filter { it.name in gone }.forEach { it.delete() }
    }
}
