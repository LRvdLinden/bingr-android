package nl.lrvdlinden.bingr

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.StreamKey
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.hls.offline.HlsDownloader
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Build 171: HLS-downloads (.m3u8) met Media3 ([HlsDownloader]) in een eigen cache (`downloads/hls`). Van een
 * multivariant-lijst één variant (hoogste bitrate tot 6 Mbit/s) plus de eerste audiogroep. Offline afspelen: ExoPlayer
 * leest via [readThrough] eerst uit deze cache (zelfde URL als bij het downloaden).
 */
@OptIn(UnstableApi::class)
object HlsDownloads {
    @Volatile private var cache: SimpleCache? = null

    private fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir("downloads") ?: File(ctx.filesDir, "downloads"), "hls").apply { mkdirs() }

    @Synchronized
    fun cache(ctx: Context): SimpleCache = cache ?: SimpleCache(dir(ctx), NoOpCacheEvictor(), StandaloneDatabaseProvider(ctx)).also { cache = it }

    /** Is er al een HLS-download (anders blijft het afspelen zoals het was, zonder cache)? */
    fun exists(ctx: Context): Boolean = cache != null || (dir(ctx).list()?.isNotEmpty() == true)

    private fun upstream(): OkHttpDataSource.Factory = OkHttpDataSource.Factory(
        Http.client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()).setUserAgent(Http.USER_AGENT)

    private fun factory(ctx: Context): CacheDataSource.Factory =
        CacheDataSource.Factory().setCache(cache(ctx)).setUpstreamDataSourceFactory(upstream())

    /** Afspelen: eerst de downloadcache, dan het netwerk (schrijft niets bij). */
    fun readThrough(ctx: Context, upstream: DataSource.Factory): DataSource.Factory =
        if (!exists(ctx)) upstream else CacheDataSource.Factory().setCache(cache(ctx)).setUpstreamDataSourceFactory(upstream)
            .setCacheWriteDataSinkFactory(null).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** Welke variant (en audio) downloaden. */
    private fun keys(url: String): List<StreamKey> {
        val text = runCatching {
            Http.client.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", Http.USER_AGENT).build()).execute().use { it.body?.bytes() }
        }.getOrNull() ?: return emptyList()
        val pl = runCatching { HlsPlaylistParser().parse(android.net.Uri.parse(url), text.inputStream()) }.getOrNull()
        val mv = pl as? HlsMultivariantPlaylist ?: return emptyList()
        if (mv.variants.isEmpty()) return emptyList()
        val rates = mv.variants.map { it.format.bitrate }
        val okIdx = rates.withIndex().filter { it.value in 1..6_000_000 }.maxByOrNull { it.value }?.index
            ?: rates.withIndex().minByOrNull { if (it.value <= 0) Int.MAX_VALUE else it.value }!!.index
        val out = mutableListOf(StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_VARIANT, okIdx))
        if (mv.audios.isNotEmpty()) out += StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_AUDIO, 0)
        return out
    }

    /** Downloaden (blokkerend). [progress]: (procent, bytes) → false = stoppen (pauze, verwijderd, systeem). */
    fun download(ctx: Context, url: String, progress: (Float, Long) -> Boolean) {
        val item = MediaItem.Builder().setUri(url).setStreamKeys(keys(url)).build()
        val dl = HlsDownloader(item, factory(ctx))
        var stop = false
        dl.download { _, bytes, percent ->
            if (!stop && !progress(percent, bytes)) { stop = true; dl.cancel() }
        }
    }

    fun remove(ctx: Context, url: String) {
        runCatching { HlsDownloader(MediaItem.fromUri(url), factory(ctx)).remove() }
    }

    fun bytes(ctx: Context): Long = runCatching { dir(ctx).walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
}
