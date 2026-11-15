package nl.lrvdlinden.bingr

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cronet.CronetDataSource
import androidx.media3.datasource.cronet.CronetUtil
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.google.android.gms.net.CronetProviderInstaller
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.buffer
import okio.source
import org.chromium.net.CronetEngine
import org.chromium.net.NetworkException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Build 176: snellere netwerklaag. Cronet (de netwerkstack van Chrome: HTTP/2, QUIC/HTTP/3, betere verbindingen) via
 * Google Play services, voor Media3 (films, series en zenders in ExoPlayer) en voor posters en logo's (Coil).
 * Zonder Play services (Fire TV, sommige tv-boxen) of als Cronet niet start: de bestaande OkHttp-stack, zoals altijd.
 * Lukt het openen van een stream of afbeelding met Cronet niet, dan meteen nog eens met OkHttp; lukt het dan wel, dan
 * gaat die host de rest van de sessie via OkHttp ([Build176TechLogic.Net]).
 * De rooktest- en screenshotbuild gebruiken Cronet niet (geen netwerk, dat regelt de OkHttp-client), maar starten
 * hem wel, zodat de rooktest meet of hij beschikbaar is en hoe lang het starten duurt.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object NetEngine {
    private const val TAG = "BingrNet"
    /** Per apparaat; standaard aan (alleen voor noodgevallen via de diagnose uit te zetten). */
    const val PREF = "net.cronet"

    @Volatile private var engine: CronetEngine? = null
    @Volatile var initMs: Long = -1; private set
    @Volatile private var started = false
    private val hosts = Build176TechLogic.Net.HostMemory()
    private val executor by lazy { Executors.newFixedThreadPool(4) { r -> Thread(r, "bingr-cronet").apply { isDaemon = true } } }

    private val offlineBuild: Boolean get() = BuildConfig.STRESS || BuildConfig.SCREENSHOTS

    val active: Build176TechLogic.Net.Engine
        get() = Build176TechLogic.Net.engine(engine != null, runCatching { Prefs.bool(PREF, true) }.getOrDefault(true), offlineBuild)

    /** Voor de diagnose: "cronet" of "okhttp". */
    val label: String get() = active.name.lowercase()

    /** Op de achtergrond starten (BingrApplication); tot dan gaat alles via OkHttp. */
    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        Thread({
            val t0 = SystemClock.elapsedRealtime()
            val e = runCatching {
                // Cronet uit Google Play services laden (lukt niet zonder Play services: dan null en OkHttp)
                com.google.android.gms.tasks.Tasks.await(CronetProviderInstaller.installProvider(app), 8, TimeUnit.SECONDS)
                // preferGooglePlayServices; nooit de trage "Fallback"-provider (die gebruikt HttpURLConnection)
                CronetUtil.buildCronetEngine(app, Http.USER_AGENT, true)
            }.getOrNull()
            engine = e
            initMs = SystemClock.elapsedRealtime() - t0
            val msg = "net engine=$label cronet_available=${e != null} init_ms=$initMs"
            Log.i(TAG, msg)
            if (BuildConfig.STRESS) Log.i(StressMode.TAG, "BINGR_STRESS $msg")
        }, "bingr-cronet-init").apply { isDaemon = true }.start()
    }

    private fun failure(e: Throwable): Build176TechLogic.Net.Failure {
        var c: Throwable? = e
        var timeout = false
        var code: Int? = null
        while (c != null) {
            if (c is HttpDataSource.InvalidResponseCodeException) code = c.responseCode
            if (c is SocketTimeoutException) timeout = true
            if (c is NetworkException && (c.errorCode == NetworkException.ERROR_TIMED_OUT ||
                    c.errorCode == NetworkException.ERROR_CONNECTION_TIMED_OUT)) timeout = true
            c = c.cause
        }
        return Build176TechLogic.Net.failureOf(code, timeout)
    }

    private fun useCronet(host: String?): CronetEngine? =
        engine?.takeIf { active == Build176TechLogic.Net.Engine.CRONET && !hosts.okHttpOnly(host) }

    // MARK: Media3

    /** HTTP-bron voor ExoPlayer: Cronet met terugval op [okhttp], of alleen OkHttp. */
    fun mediaFactory(okhttp: OkHttpClient, connectTimeoutMs: Int, readTimeoutMs: Int): DataSource.Factory {
        val ok = OkHttpDataSource.Factory(okhttp).setUserAgent(Http.USER_AGENT)
        val e = engine
        if (e == null || active != Build176TechLogic.Net.Engine.CRONET) return ok
        val cr = CronetDataSource.Factory(e, executor)
            .setUserAgent(Http.USER_AGENT)
            .setConnectionTimeoutMs(connectTimeoutMs)
            .setReadTimeoutMs(readTimeoutMs)
            .setHandleSetCookieRequests(true)
        return DataSource.Factory { FallbackSource(cr.createDataSource(), ok.createDataSource()) }
    }

    /** Opent met Cronet; mislukt dat (niet door een time-out of serverfout), dan meteen met OkHttp. */
    private class FallbackSource(private val cronet: DataSource, private val okhttp: DataSource) : DataSource {
        private var current: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            cronet.addTransferListener(transferListener)
            okhttp.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val host = dataSpec.uri.host
            if (useCronet(host) != null) {
                try {
                    current = cronet
                    return cronet.open(dataSpec)
                } catch (e: IOException) {
                    runCatching { cronet.close() }
                    if (!Build176TechLogic.Net.retryWithOkHttp(failure(e))) throw e
                    current = okhttp
                    val n = okhttp.open(dataSpec)
                    hosts.remember(host)
                    Log.i(TAG, "media: Cronet faalde (${e.javaClass.simpleName}), OkHttp wel: deze host verder via OkHttp")
                    return n
                }
            }
            current = okhttp
            return okhttp.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current?.read(buffer, offset, length) ?: -1
        override fun getUri(): Uri? = current?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()
        override fun close() { val c = current; current = null; c?.close() }
    }

    // MARK: posters en logo's (Coil)

    /**
     * OkHttp-interceptor voor de afbeeldingen: GET-verzoeken via Cronet (HttpURLConnection van de CronetEngine), bij een
     * fout (niet time-out/serverfout) gewoon verder met OkHttp. Komt na de bestaande interceptors (geen netwerk in de
     * rooktest/screenshots, User-Agent).
     */
    val imageInterceptor = Interceptor { chain ->
        val req = chain.request()
        val e = if (req.method == "GET" && req.body == null) useCronet(req.url.host) else null
        if (e == null) return@Interceptor chain.proceed(req)
        try {
            cronetFetch(e, chain)
        } catch (ex: IOException) {
            if (!Build176TechLogic.Net.retryWithOkHttp(failure(ex))) throw ex
            val r = chain.proceed(req)
            if (r.isSuccessful) hosts.remember(req.url.host)
            r
        }
    }

    private fun cronetFetch(e: CronetEngine, chain: Interceptor.Chain): Response {
        val req = chain.request()
        val conn = e.openConnection(URL(req.url.toString())) as HttpURLConnection
        conn.connectTimeout = chain.connectTimeoutMillis()
        conn.readTimeout = chain.readTimeoutMillis()
        conn.instanceFollowRedirects = true
        conn.requestMethod = "GET"
        for (i in 0 until req.headers.size) conn.addRequestProperty(req.headers.name(i), req.headers.value(i))
        val code = try { conn.responseCode } catch (ex: IOException) { conn.disconnect(); throw ex }
        val b = Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message(conn.responseMessage ?: "")
        // Cronet pakt gzip/br zelf uit: lengte en codering van de server kloppen dan niet meer met de body
        conn.headerFields?.forEach { (k, vs) ->
            if (k != null && !k.equals("Content-Length", true) && !k.equals("Content-Encoding", true))
                vs?.forEach { v -> if (v != null) b.addHeader(k, v) }
        }
        val stream = (if (code >= 400) conn.errorStream else conn.inputStream)
        val body = (stream?.source()?.buffer() ?: okio.Buffer()).asResponseBody(conn.contentType?.toMediaTypeOrNull(), -1L)
        return b.body(body).build()
    }
}
