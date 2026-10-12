package nl.lrvdlinden.bingr

import android.app.Presentation
import android.content.Context
import android.graphics.Color as AndroidColor
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// MARK: - Zendervoorbeeld (build 51) ----------------------------------------------------------

/**
 * Klein, gedempt live-voorbeeld in Live TV en de gids: na ~1 s focus (of keuze) speelt de zender in één gedeelde,
 * lichte ExoPlayer. Stopt bij weggaan, bij het openen van de speler en als de app naar de achtergrond gaat.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object ChannelPreview {
    var channel by mutableStateOf<Channel?>(null); private set
    var player by mutableStateOf<ExoPlayer?>(null); private set
    var playing by mutableStateOf(false); private set
    private var pending: String? = null
    private var job: Job? = null

    /** Deze zender kreeg focus (of is gekozen): na ~1 s het voorbeeld starten. */
    fun focus(c: Channel) {
        if (!PlayerSettings.previewAllowed() || AppState.playing != null) return
        if (pending == c.id) return
        pending = c.id
        job?.cancel()
        job = AppScope.launch {
            delay(1_000)
            if (pending != c.id || AppState.playing != null) return@launch
            val url = runCatching { Playlists.resolve(Playables.channel(c, null)) }.getOrNull()
            if (pending != c.id || AppState.playing != null) return@launch
            if (url == null || !PlayerController.canUseExo(url)) { stop(); return@launch }
            start(c, url)
        }
    }

    private fun build(): ExoPlayer {
        val ctx = AppState.appContext
        val http = OkHttpDataSource.Factory(Http.client).setUserAgent(Http.USER_AGENT)
        return ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, http)))
            .build().apply {
                volume = 0f
                setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
                    override fun onPlayerError(error: PlaybackException) { playing = false }
                })
            }
    }

    private fun start(c: Channel, url: String) {
        val p = player ?: build().also { player = it }
        channel = c
        playing = false
        val lower = url.lowercase(Locale.ROOT)
        val b = MediaItem.Builder().setUri(url)
        if (lower.contains(".m3u8")) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        p.setMediaItem(b.build())
        p.volume = 0f
        p.prepare()
        p.playWhenReady = true
    }

    /** Voorbeeld stoppen en de speler vrijgeven (decoder vrij voor de echte speler). */
    fun stop() {
        job?.cancel(); job = null
        pending = null
        channel = null
        playing = false
        player?.let { runCatching { it.release() } }
        player = null
    }
}

/** Het voorbeeldvenster (16:9) met zenderlogo tot het beeld er is. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun ChannelPreviewView(modifier: Modifier = Modifier) {
    val c = ChannelPreview.channel ?: return
    val p = ChannelPreview.player
    Box(modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
        if (p != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        setShutterBackgroundColor(AndroidColor.BLACK)
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        isFocusable = false
                        isClickable = false
                        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        player = p
                    }
                },
                update = { v -> if (v.player !== p) v.player = p },
                onRelease = { v -> v.player = null },
                modifier = Modifier.matchParentSize(),
            )
        }
        if (!ChannelPreview.playing) {
            RemoteImage(c.logo, Modifier.align(Alignment.Center).fillMaxWidth(0.4f).aspectRatio(16f / 9f),
                placeholder = c.name.take(3), fit = true)
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(16.dp))
        }
        Text(c.name, color = Color.White, fontSize = tsp(11), fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                .padding(horizontal = 8.dp, vertical = 5.dp))
    }
}

// MARK: - Beeldfrequentie volgen (Android TV / Google TV / Fire TV) ---------------------------

/**
 * Zet het scherm op de beeldfrequentie van de video (25/50, 24, 30/60 Hz). API 31+: Surface.setFrameRate
 * (ook als het niet naadloos kan); daaronder de beeldschermmodus via Window.preferredDisplayModeId.
 * Instelling "Match frame rate" (standaard aan, alleen TV).
 */
object FrameRateMatcher {
    private var applied: Float = 0f
    private var changedMode = false

    val enabled: Boolean get() = AppState.isTV && PlayerSettings.matchFrameRate

    fun apply(fps: Float, surface: Surface?) {
        if (!enabled || fps <= 1f || !fps.isFinite()) return
        if (abs(applied - fps) < 0.01f && (surface == null || Build.VERSION.SDK_INT < 31)) return
        applied = fps
        if (Build.VERSION.SDK_INT >= 31 && surface != null && surface.isValid) {
            runCatching { surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ALWAYS) }
            return
        }
        switchMode(fps)
    }

    /** Beeldschermmodus met dezelfde resolutie en een verversingsfrequentie die een veelvoud is van [fps]. */
    private fun switchMode(fps: Float) {
        if (Build.VERSION.SDK_INT < 23) return
        val act = MainActivity.current ?: return
        runCatching {
            @Suppress("DEPRECATION")
            val display = act.window.decorView.display ?: act.windowManager.defaultDisplay ?: return
            val cur = display.mode
            val best = display.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .mapNotNull { m ->
                    val n = (m.refreshRate / fps).roundToInt()
                    if (n < 1 || n > 4) return@mapNotNull null
                    val err = abs(m.refreshRate - n * fps) / m.refreshRate
                    if (err > 0.012f) null else Triple(m, n, err)
                }
                // eerst de nauwkeurigste, dan het laagste veelvoud boven 40 Hz (50 voor 25 fps), dan de hoogste
                .sortedWith(compareBy<Triple<Display.Mode, Int, Float>>({ (it.third * 1000).roundToInt() },
                    { if (it.first.refreshRate >= 40f) 0 else 1 }, { it.second }))
                .firstOrNull()?.first ?: return
            if (best.modeId == cur.modeId) return
            val lp = act.window.attributes
            lp.preferredDisplayModeId = best.modeId
            act.window.attributes = lp
            changedMode = true
        }
    }

    /** Speler dicht: het scherm mag weer zijn eigen stand kiezen. */
    fun reset(surface: Surface? = null) {
        applied = 0f
        if (Build.VERSION.SDK_INT >= 31 && surface != null && surface.isValid) {
            runCatching { surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS) }
        }
        if (!changedMode || Build.VERSION.SDK_INT < 23) return
        changedMode = false
        val act = MainActivity.current ?: return
        runCatching {
            val lp = act.window.attributes
            lp.preferredDisplayModeId = 0
            act.window.attributes = lp
        }
    }

    /** Surface van een PlayerView (SurfaceView), voor setFrameRate. */
    @androidx.annotation.OptIn(UnstableApi::class)
    fun surfaceOf(v: PlayerView?): Surface? = (v?.videoSurfaceView as? SurfaceView)?.holder?.surface
}

// MARK: - Extern scherm (telefoon/tablet) ------------------------------------------------------

/**
 * Tweede scherm (HDMI/USB-C, Miracast) via DisplayManager: de video gaat schermvullend naar dat scherm
 * (Presentation), de bediening blijft op het apparaat. Instelling "Show on external display" (standaard aan).
 */
object ExternalDisplay {
    var display by mutableStateOf<Display?>(null); private set
    var enabled by mutableStateOf(true); private set
    private var registered = false
    private var dm: DisplayManager? = null

    fun init(ctx: Context) {
        if (registered) return
        enabled = PlayerSettings.externalDisplay
        val m = ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        dm = m
        registered = true
        refresh()
        m.registerDisplayListener(object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) = refresh()
            override fun onDisplayRemoved(id: Int) = refresh()
            override fun onDisplayChanged(id: Int) = refresh()
        }, Handler(Looper.getMainLooper()))
    }

    private fun refresh() {
        display = runCatching { dm?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)?.firstOrNull() }.getOrNull()
    }

    fun use(v: Boolean) { enabled = v; PlayerSettings.externalDisplay = v }

    /** Het scherm waar de video nu naartoe gaat (null = op het apparaat zelf). */
    val target: Display? get() = if (AppState.isTV || !enabled) null else display
}

/** Laat de video van [controller] schermvullend zien op [display] zolang dit in de compositie staat. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun ExternalPresentation(controller: PlayerController, display: Display) {
    val engine = controller.engine
    DisposableEffect(engine, display) {
        val act = MainActivity.current
        var pres: Presentation? = null
        var exoView: PlayerView? = null
        var vlcView: VLCVideoLayout? = null
        if (act != null) {
            val created = object : Presentation(act, display) {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    val root = FrameLayout(context).apply { setBackgroundColor(AndroidColor.BLACK) }
                    val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    when (engine) {
                        is ExoEngine -> {
                            val v = PlayerView(context).apply {
                                useController = false
                                setShutterBackgroundColor(AndroidColor.BLACK)
                                isFocusable = false
                                player = engine.player
                            }
                            engine.view = v
                            exoView = v
                            root.addView(v, lp)
                        }
                        is VlcEngine -> {
                            val v = VLCVideoLayout(context).apply {
                                setBackgroundColor(AndroidColor.BLACK)
                                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            }
                            root.addView(v, lp)
                            engine.attach(v)
                            vlcView = v
                        }
                        else -> {}
                    }
                    setContentView(root)
                }
            }
            if (runCatching { created.show() }.isSuccess) pres = created
        }
        onDispose {
            exoView?.let { v -> (engine as? ExoEngine)?.let { e -> if (e.view === v) e.view = null }; v.player = null }
            vlcView?.let { v -> (engine as? VlcEngine)?.detach(v) }
            runCatching { pres?.dismiss() }
        }
    }
}
