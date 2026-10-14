package nl.lrvdlinden.bingr

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Build 82: trailer automatisch (zonder geluid) in de kop van de detailpagina, zoals bingr.online/movie/… en iOS
 * (TrailerPlayer.swift, HeroTrailerModel). YouTube-embed (youtube-nocookie, IFrame-API) in een WebView zonder knoppen;
 * pas zichtbaar zodra hij echt speelt. Speelt hij niet (geen WebView, YouTube weigert de embed, te traag), dan blijft
 * de kopafbeelding staan, met een rustige Ken Burns-beweging (build 85, [KenBurns]).
 */
object HeroTrailer {
    /** Zelfde sleutel als iOS (`Trailer.autoplayKey`); standaard aan. */
    const val PREF = "ui.autoplayTrailers"
    var enabled: Boolean
        get() = Prefs.bool(PREF, true)
        set(v) = Prefs.put(PREF, v)

    /**
     * Herkomst van de pagina in de WebView (build 85). YouTube wil sinds 2025 weten wie de speler insluit (Referer);
     * zonder herkomst geeft hij fout 153, en met een YouTube-adres als herkomst (zoals build 82 deed:
     * youtube-nocookie.com) fout 152 ("YouTube sluit zichzelf in"). De Google-richtlijn voor apps:
     * https://<app-id>. De speler zelf komt wel van youtube-nocookie.com.
     */
    const val ORIGIN = "https://nl.lrvdlinden.bingr"

    /** Diagnose (alleen debug-builds): `adb logcat -s BingrTrailer`. */
    fun log(msg: String) {
        if (BuildConfig.DEBUG) android.util.Log.d("BingrTrailer", msg)
    }

    /** Animaties uitgezet (toegankelijkheid "Animaties verwijderen" / animatieschaal 0). */
    fun reduceMotion(ctx: Context): Boolean = runCatching {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f) <= 0f

    /**
     * Nu automatisch afspelen? Instelling aan; op telefoon/tablet ook geen datalimiet (mobiele data) en animaties
     * niet uitgezet. Build 85: op tv's tellen die twee niet mee. Veel Android TV-/Fire TV-kastjes staan (fabriek
     * of "sneller maken"-tips) op animatieschaal 0, en sommige melden hun wifi als verbinding met datalimiet;
     * daardoor startte de trailer daar nooit. Op tv geldt alleen de eigen instelling.
     */
    fun allowed(ctx: Context): Boolean {
        if (!enabled) { log("uit: instelling"); return false }
        if (AppState.isTV) return true
        val metered = runCatching {
            (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.isActiveNetworkMetered ?: false
        }.getOrDefault(false)
        if (metered) { log("uit: datalimiet"); return false }
        if (reduceMotion(ctx)) { log("uit: animaties uit"); return false }
        return true
    }

    /** Hoe lang wachten tot hij speelt: tv-chips zijn trager (WebView + IFrame-API laden duurt daar soms > 10 s). */
    val timeoutMs: Long get() = if (AppState.isTV) 20_000 else 12_000

    /** Alleen een geldig YouTube-id in de pagina. */
    fun safeID(id: String): String = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(32)

    /** Pagina met de YouTube IFrame-API (= iOS): beeldvullend bijgesneden, seintjes `playing`, `ended`, `error`. */
    fun html(id: String): String = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
        <style>html,body{margin:0;height:100%;overflow:hidden;background:#000}
        #w{position:absolute;top:50%;left:50%;width:100vw;height:56.25vw;min-height:100vh;min-width:177.78vh;
        transform:translate(-50%,-50%) scale(1.15);pointer-events:none}#p{width:100%;height:100%;border:0}</style>
        </head><body><div id="w"><div id="p"></div></div>
        <script>
        var player=null,timer=null;
        function post(m){try{BingrTrailer.event(m)}catch(e){}}
        function diag(m){try{BingrTrailer.diag(String(m))}catch(e){}}
        function setMuted(m){if(!player)return;try{if(m){player.mute()}else{player.unMute();player.setVolume(100)}}catch(e){}}
        function stopIt(){try{clearInterval(timer);player&&player.stopVideo()}catch(e){}}
        function watch(){clearInterval(timer);timer=setInterval(function(){try{var d=player.getDuration(),c=player.getCurrentTime();
        if(d>0&&d-c<1.2){clearInterval(timer);player.pauseVideo();post('ended')}}catch(e){}},400)}
        function onYouTubeIframeAPIReady(){player=new YT.Player('p',{host:'https://www.youtube-nocookie.com',videoId:'${safeID(id)}',
        playerVars:{autoplay:1,mute:1,playsinline:1,controls:0,disablekb:1,fs:0,rel:0,modestbranding:1,iv_load_policy:3,
        cc_load_policy:0,enablejsapi:1,origin:'$ORIGIN',widget_referrer:'$ORIGIN'},
        events:{onReady:function(e){diag('ready');e.target.mute();e.target.playVideo()},
        onStateChange:function(e){diag('state '+e.data);if(e.data===1){post('playing');watch()}else if(e.data===0){post('ended')}},
        onError:function(e){diag('error '+(e&&e.data));post('error')}}})}
        </script><script src="https://www.youtube.com/iframe_api" onerror="diag('iframe_api niet geladen');post('error')"></script>
        </body></html>
    """.trimIndent()
}

/**
 * Stand van de trailer in de kop. De WebView bestaat alleen zolang [videoID] gezet is; [stop] haalt hem weg
 * (geen geluid of data meer).
 */
class HeroTrailerState {
    enum class Phase { IDLE, LOADING, PLAYING, FINISHED }
    /** Build 85: speelde niet (fout, geen WebView of te traag) → de kop krijgt een rustige Ken Burns-beweging. */
    var failed by mutableStateOf(false)
        private set
    var phase by mutableStateOf(Phase.IDLE)
        private set
    var videoID by mutableStateOf<String?>(null)
        private set
    var muted by mutableStateOf(true)
        private set
    /** Nieuwe waarde = nieuwe WebView. */
    var token by mutableStateOf(0)
        private set
    /** Al eens gestart voor deze pagina (niet opnieuw vanzelf na stoppen of het einde). */
    var startedID: String? = null
        private set
    internal var webView: WebView? = null
    private var timeout: Job? = null

    val visible: Boolean get() = phase == Phase.PLAYING

    fun start(id: String) {
        startedID = id
        muted = true
        videoID = id
        token += 1
        phase = Phase.LOADING
        failed = false
        HeroTrailer.log("start $id (tv=${AppState.isTV})")
        timeout?.cancel()
        timeout = AppScope.launch {
            delay(HeroTrailer.timeoutMs)
            if (phase == Phase.LOADING) { HeroTrailer.log("speelt niet binnen ${HeroTrailer.timeoutMs} ms"); failed = true; stop() }
        }
    }

    /** Stoppen (afspelen, ander scherm, achtergrond, einde). */
    fun stop() {
        timeout?.cancel(); timeout = null
        if (videoID == null) return
        runCatching { webView?.evaluateJavascript("stopIt()", null) }
        videoID = null
        webView = null
        phase = if (startedID == null) Phase.IDLE else Phase.FINISHED
    }

    fun toggleMute() {
        muted = !muted
        runCatching { webView?.evaluateJavascript("setMuted(${muted})", null) }
    }

    internal fun event(name: String) {
        when (name) {
            "playing" -> if (videoID != null) { timeout?.cancel(); phase = Phase.PLAYING }
            "ended" -> stop()
            "error" -> { if (phase != Phase.PLAYING) failed = true; stop() }
        }
    }
}

/**
 * Build 85: rustige Ken Burns-beweging (langzaam inzoomen en schuiven, 24 s heen en terug) op de kopafbeelding,
 * als de trailer niet kan spelen. Niet als animaties uit staan.
 */
@Composable
fun Modifier.kenBurns(active: Boolean): Modifier {
    if (!active) return this
    val t = rememberInfiniteTransition(label = "kenburns")
    val p by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Reverse), label = "kb"
    )
    return this.clipToBounds().graphicsLayer {
        val s = 1.04f + 0.10f * p
        scaleX = s; scaleY = s
        translationX = (p - 0.5f) * size.width * 0.04f
        translationY = (0.5f - p) * size.height * 0.02f
    }
}

/** De WebView met de trailer; onzichtbaar (kopafbeelding blijft) tot hij echt speelt. */
@Composable
fun HeroTrailerView(state: HeroTrailerState, modifier: Modifier = Modifier) {
    val id = state.videoID ?: return
    // build 85: nooit helemaal 0. Met alpha 0 slaat Compose het tekenen van de WebView over; op tv's (synchrone
    // compositor) kwamen er dan geen beeldjes en bleef de YouTube-speler in "bufferen" hangen → nooit "playing".
    val alpha by animateFloatAsState(if (state.visible) 1f else 0.01f, tween(if (state.visible) 700 else 250), label = "trailer")
    key(state.token) {
        // de WebView staat er al tijdens het laden (anders start hij niet), maar doorzichtig tot hij speelt
        AndroidView(
            factory = { ctx -> heroWebView(ctx, state, id) ?: View(ctx).also { v -> v.post { state.event("error") } } },
            modifier = modifier.graphicsLayer { this.alpha = alpha },
            onRelease = { v -> (v as? WebView)?.let { tearDown(it) } }
        )
    }
    DisposableEffect(Unit) { onDispose { state.stop() } }
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
private fun heroWebView(ctx: Context, state: HeroTrailerState, id: String): WebView? = runCatching {
    val main = Handler(Looper.getMainLooper())
    WebView(ctx).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(AndroidColor.BLACK)
        // nooit focus of aanraking: de afstandsbediening en tikken blijven bij de pagina
        isFocusable = false
        isFocusableInTouchMode = false
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        setOnTouchListener { _, _ -> true }
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        settings.javaScriptEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage?): Boolean {
                if (m != null) HeroTrailer.log("console ${m.messageLevel()}: ${m.message()}")
                return true
            }
        }
        webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                HeroTrailer.log("laadfout ${request?.url?.host}: ${error?.errorCode} ${error?.description}")
            }
            override fun onReceivedHttpError(view: WebView?, request: android.webkit.WebResourceRequest?, r: android.webkit.WebResourceResponse?) {
                HeroTrailer.log("http ${r?.statusCode} ${request?.url?.host}${request?.url?.path}")
            }
            // links (YouTube-logo e.d.) niet binnen de kop openen
            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean =
                request?.isForMainFrame == true
        }
        addJavascriptInterface(object {
            @JavascriptInterface
            fun event(name: String) { main.post { state.event(name) } }
            @JavascriptInterface
            fun diag(msg: String) { HeroTrailer.log(msg) }
        }, "BingrTrailer")
        if (BuildConfig.DEBUG && android.os.Build.VERSION.SDK_INT >= 26) {
            HeroTrailer.log("WebView ${WebView.getCurrentWebViewPackage()?.let { it.packageName + " " + it.versionName } ?: "?"}")
        }
        state.webView = this
        loadDataWithBaseURL(HeroTrailer.ORIGIN + "/", HeroTrailer.html(id), "text/html", "utf-8", null)
    }
}.onFailure { HeroTrailer.log("geen WebView: $it") }.getOrNull()

private fun tearDown(w: WebView) {
    runCatching {
        w.removeJavascriptInterface("BingrTrailer")
        w.stopLoading()
        w.loadUrl("about:blank")
        (w.parent as? ViewGroup)?.removeView(w)
        w.destroy()
    }
}
