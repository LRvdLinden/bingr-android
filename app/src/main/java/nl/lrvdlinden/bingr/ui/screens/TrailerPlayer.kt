package nl.lrvdlinden.bingr

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Build 174: trailer in de app (telefoon, tablet, Android TV, Google TV, Fire TV) met de officiële YouTube IFrame-player in
 * een WebView, zoals iOS (geen streams uit YouTube halen: dat mag niet volgens de YouTube-voorwaarden). Geen WebView of
 * weigert YouTube de embed, dan de YouTube-app of de browser ([Trailer.open]). Sluiten (terug) = terug naar de
 * detailpagina; een trailer telt niet als stream.
 */
object TrailerPlayer {
    fun html(id: String): String = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
        <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{position:absolute;inset:0;width:100%;height:100%;border:0}</style>
        </head><body><div id="p"></div>
        <script>
        function post(m){try{BingrTrailer.event(m)}catch(e){}}
        function onYouTubeIframeAPIReady(){new YT.Player('p',{host:'https://www.youtube-nocookie.com',videoId:'${HeroTrailer.safeID(id)}',
        playerVars:{autoplay:1,playsinline:1,controls:1,fs:1,rel:0,modestbranding:1,iv_load_policy:3,enablejsapi:1,
        origin:'${HeroTrailer.ORIGIN}',widget_referrer:'${HeroTrailer.ORIGIN}',hl:'${Localizer.language.take(2)}'},
        events:{onReady:function(e){e.target.playVideo()},onStateChange:function(e){if(e.data===1){post('playing')}else if(e.data===0){post('ended')}},
        onError:function(e){post('error')}}})}
        </script><script src="https://www.youtube.com/iframe_api" onerror="post('error')"></script>
        </body></html>
    """.trimIndent()
}

@Composable
fun TrailerPlayerDialog(youtubeID: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val id = Build174Logic.youtubeID(youtubeID)
    var failed by remember(youtubeID) { mutableStateOf(id == null) }
    var playing by remember(youtubeID) { mutableStateOf(false) }
    // embed lukt niet → YouTube-app of browser (Build174Logic.opening), en het blad dicht
    LaunchedEffect(failed) {
        if (failed) { Trailer.open(ctx, id ?: youtubeID); onClose() }
    }
    // speelt hij na 20 s nog niet (YouTube geblokkeerd, geen netwerk), dan ook naar de YouTube-app
    LaunchedEffect(youtubeID) {
        kotlinx.coroutines.delay(if (AppState.isTV) 25_000 else 20_000)
        if (!playing && !failed) failed = true
    }
    if (id == null || failed) return
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true)) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { c -> trailerWebView(c, id, onEvent = { e -> when (e) { "playing" -> playing = true; "error" -> if (!playing) failed = true } }) ?: run { failed = true; android.view.View(c) } },
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                onRelease = { v -> (v as? WebView)?.let { w -> runCatching { w.removeJavascriptInterface("BingrTrailer"); w.stopLoading(); w.loadUrl("about:blank"); w.destroy() } } }
            )
            if (!AppState.isTV) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(16.dp).size(44.dp).background(Color.White.copy(alpha = 0.15f), androidx.compose.foundation.shape.CircleShape)
                        .focusLift(1.08f) { onClose() },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Close, L("Close"), tint = Color.White) }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun trailerWebView(ctx: Context, id: String, onEvent: (String) -> Unit): WebView? = runCatching {
    val main = Handler(Looper.getMainLooper())
    WebView(ctx).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(AndroidColor.BLACK)
        settings.javaScriptEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.domStorageEnabled = true
        webChromeClient = WebChromeClient()
        webViewClient = object : WebViewClient() {
            // links (YouTube-logo, "Bekijken op YouTube") niet in de speler openen
            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean =
                request?.isForMainFrame == true
        }
        addJavascriptInterface(object {
            @JavascriptInterface
            fun event(name: String) { main.post { onEvent(name) } }
            @JavascriptInterface
            fun diag(msg: String) { HeroTrailer.log(msg) }
        }, "BingrTrailer")
        isFocusable = true
        isFocusableInTouchMode = true
        loadDataWithBaseURL(HeroTrailer.ORIGIN + "/", TrailerPlayer.html(id), "text/html", "utf-8", null)
        post { requestFocus() }
    }
}.getOrNull()
