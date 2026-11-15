package nl.lrvdlinden.bingr

import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Build 171: radio. Radiozenders zijn zenders met de vlag van de server (Xtream `radio_streams`, M3U `radio="true"`) of in
 * een categorie met "radio" in de naam ([Build171Logic.Radio]). Afspelen alleen audio, met een mini-speler onderaan die
 * blijft spelen terwijl je verder bladert, en op de achtergrond met de bediening op het vergrendelscherm en in het
 * meldingencentrum (MediaSession, [RadioService]). Telt als stream (`stream_start` met kind "radio").
 *
 * Android Auto: [RadioService] is een MediaLibraryService met de bladerboom Favorieten / Alle zenders / per categorie.
 * Let op: Android Auto toont apps van buiten de Play Store alleen met "Onbekende bronnen" aan in de ontwikkelaarsopties
 * van Android Auto.
 */
@OptIn(UnstableApi::class)
object Radio {
    /** Zender die nu speelt (of gepauzeerd is); null = mini-speler weg. */
    var current by mutableStateOf<Channel?>(null); private set
    var playing by mutableStateOf(false); private set
    var buffering by mutableStateOf(false); private set
    /** stream_start gaf 403 stream_limit: Main toont "Te veel streams". */
    var blocked by mutableStateOf<Account.StreamList?>(null)

    private var exo: ExoPlayer? = null
    private var owner: StreamSession.Owner? = null

    fun isRadio(c: Channel): Boolean = Build171Logic.Radio.isRadio(c.radio, c.group)

    /** Radiozenders van de actieve bibliotheek (al gefilterd voor dit profiel: kinderprofiel, verborgen zenders). */
    fun channels(): List<Channel> {
        val pid = Playlists.source?.id ?: ""
        return Build171Logic.Radio.order(Playlists.library.channels.filter { isRadio(it) },
            { Store.isFavorite(pid, it.id) }, { it.number }, { it.displayName })
    }

    val player: ExoPlayer
        get() = exo ?: run {
            val ctx = AppState.appContext
            val http = OkHttpDataSource.Factory(Http.client).setUserAgent(Http.USER_AGENT)
            ExoPlayer.Builder(ctx)
                .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, http)))
                .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build().also { p ->
                    p.addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
                        override fun onPlaybackStateChanged(state: Int) { buffering = state == Player.STATE_BUFFERING }
                        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                            val c = item?.mediaId?.let { channelFor(it) } ?: return
                            if (c.id != current?.id) { current = c; beginStream(c) }
                        }
                    })
                    exo = p
                }
        }

    fun mediaID(c: Channel): String = "ch:" + Build171Logic.ProfileSets.channelKey(c.sourceID ?: Playlists.source?.id ?: "", c.id)

    fun channelFor(mediaID: String): Channel? {
        val key = mediaID.removePrefix("ch:")
        val (_, cid) = Build171Logic.ProfileSets.split(key) ?: return null
        return Playlists.library.channels.firstOrNull { it.id == cid }
    }

    /** MediaItem met de echte link (MAC-portal: nu aangevraagd). */
    suspend fun item(c: Channel): MediaItem? {
        val url = runCatching { Playlists.resolve(Playables.channel(c, null)) }.getOrNull() ?: return null
        return MediaItem.Builder().setMediaId(mediaID(c)).setUri(Uri.parse(url))
            .setMediaMetadata(meta(c, playable = true)).build()
    }

    fun meta(c: Channel, playable: Boolean): MediaMetadata = MediaMetadata.Builder()
        .setTitle(c.displayName).setStation(c.displayName).setArtist(ContentLanguage.categoryTitle(c.group))
        .setArtworkUri(c.logo?.let { Uri.parse(it) })
        .setIsPlayable(playable).setIsBrowsable(false)
        .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION).build()

    /** Vanuit de app: zender afspelen (video stopt, er is één stream per apparaat). */
    fun play(c: Channel) {
        AppScope.launch {
            val it = item(c) ?: return@launch
            if (AppState.playing != null) AppState.playing = null
            sleptChannel = null
            current = c
            val p = player
            p.setMediaItem(it); p.prepare(); p.play()
            beginStream(c)
            ensureService()
        }
    }

    fun togglePause() { val p = exo ?: return; if (p.isPlaying) p.pause() else p.play() }

    // MARK: build 173: slaaptimer (30 / 60 / 90 minuten / uit)

    /** Einde van de slaaptimer (epoch-ms); null = uit. */
    var sleepAt by mutableStateOf<Long?>(null); private set
    /** Slaaptimer afgegaan: de zender om met "Verder luisteren" weer te starten. */
    var sleptChannel by mutableStateOf<Channel?>(null); private set
    private var sleepJob: Job? = null

    fun setSleep(s: Build173PlayerLogic.Sleep) {
        sleepJob?.cancel(); sleepJob = null
        sleptChannel = null
        val at = Build173PlayerLogic.sleepDeadline(s, System.currentTimeMillis())
        sleepAt = at
        if (at == null) return
        sleepJob = AppScope.launch {
            while (System.currentTimeMillis() < at) delay(1_000)
            val c = current
            sleepAt = null
            sleepJob = null
            // stoppen en `stream_stop`: de stream komt vrij
            halt()
            sleptChannel = c
        }
    }

    fun continueAfterSleep() {
        val c = sleptChannel ?: return
        sleptChannel = null
        play(c)
    }

    fun dismissSleep() { sleptChannel = null }

    /** Mini-speler sluiten: stoppen en `stream_stop`. */
    fun stop() {
        sleepJob?.cancel(); sleepJob = null; sleepAt = null   // build 173
        halt()
    }

    private fun halt() {
        exo?.let { it.stop(); it.clearMediaItems() }
        current = null; playing = false
        owner?.let { StreamSession.end(it) }; owner = null
    }

    /** Video start: radio stoppen. */
    fun stopForVideo() { if (current != null) stop() }

    private fun beginStream(c: Channel) {
        owner?.let { StreamSession.end(it) }
        val o = StreamSession.Owner(
            info = { StreamSession.Info(c.displayName, "radio", c.id) },
            onBlocked = { l -> exo?.stop(); current = null; playing = false; owner = null; blocked = l },
            onActivation = { exo?.stop(); current = null; playing = false; owner = null },
            background = true,
            onRemoteStop = { exo?.stop(); current = null; playing = false; owner = null },
        )
        owner = o
        StreamSession.begin(o)
    }

    private fun ensureService() {
        runCatching { AppState.appContext.startService(Intent(AppState.appContext, RadioService::class.java)) }
    }
}

/**
 * Build 171: achtergrondspeler en Android Auto voor de radio. Media3 zet de dienst zelf op de voorgrond (melding met
 * logo, naam en bediening) zolang er gespeeld wordt.
 */
@OptIn(UnstableApi::class)
class RadioService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null

    private companion object {
        const val ROOT = "root"; const val FAV = "fav"; const val ALL = "all"; const val CAT = "cat:"
    }

    override fun onCreate() {
        super.onCreate()
        session = MediaLibrarySession.Builder(this, Radio.player, Callback()).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.release(); session = null
        super.onDestroy()
    }

    private fun folder(id: String, title: String): MediaItem = MediaItem.Builder().setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS).build()).build()

    private fun station(c: Channel): MediaItem = MediaItem.Builder().setMediaId(Radio.mediaID(c)).setMediaMetadata(Radio.meta(c, true)).build()

    private fun children(parent: String): List<MediaItem> {
        val all = Radio.channels()
        val pid = Playlists.source?.id ?: ""
        return when {
            parent == ROOT -> buildList {
                if (all.any { Store.isFavorite(pid, it.id) }) add(folder(FAV, L("Favorites")))
                add(folder(ALL, L("All stations")))
                all.map { it.group }.distinct().forEach { add(folder(CAT + it, ContentLanguage.categoryTitle(it))) }
            }
            parent == FAV -> all.filter { Store.isFavorite(pid, it.id) }.map { station(it) }
            parent == ALL -> all.map { station(it) }
            parent.startsWith(CAT) -> parent.removePrefix(CAT).let { g -> all.filter { it.group == g }.map { station(it) } }
            else -> emptyList()
        }
    }

    private inner class Callback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
                                      params: MediaLibraryService.LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, "Bingr"), params))

        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
                                   page: Int, pageSize: Int, params: MediaLibraryService.LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val list = children(parentId)
            val from = (page * pageSize).coerceAtMost(list.size)
            val to = (from + pageSize).coerceAtMost(list.size)
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(list.subList(from, to)), params))
        }

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> {
            val c = Radio.channelFor(mediaId)
            return Futures.immediateFuture(if (c != null) LibraryResult.ofItem(station(c), null) else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
        }

        /** Android Auto en het vergrendelscherm sturen alleen het id: hier de echte link erbij. */
        override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
                                     mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> {
            val f = SettableFuture.create<MutableList<MediaItem>>()
            AppScope.launch {
                val out = withTimeoutOrNull(10_000) {
                    mediaItems.mapNotNull { m ->
                        if (m.localConfiguration != null) m else Radio.channelFor(m.mediaId)?.let { Radio.item(it) }
                    }.toMutableList()
                } ?: mutableListOf()
                f.set(out)
            }
            return f
        }
    }
}
