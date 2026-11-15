package nl.lrvdlinden.bingr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Build 179, punt 2 (Android): geen Live Activity, wel een mediasessie met melding bij live tv: de zender als titel,
 * "Nu: …" en "Straks: …", voortgang van het programma en Pauze/Afspelen. Lokaal, zonder push: bijgewerkt bij zappen, bij
 * de programmawissel en bij pauzeren; weg bij het sluiten van de speler. Niet bij voorbeelden, Multiview, radio of
 * films/series. Instelling per apparaat `liveActivity.tv` (standaard aan).
 */
object LiveSession179 {
    const val PREF = "liveActivity.tv"
    private const val CHANNEL = "bingr.live179"
    private const val NOTIF_ID = 17_901
    const val ACTION_TOGGLE = "nl.lrvdlinden.bingr.LIVE179_TOGGLE"

    var enabled: Boolean
        get() = Prefs.bool(PREF, true)
        set(v) { Prefs.put(PREF, v); if (!v) stop() }

    private var session: MediaSession? = null
    private var logoURL: String? = null
    private var logo: Bitmap? = null
    private var lastSig = ""

    private val ctx: Context get() = AppState.appContext
    private fun nm() = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureSession(): MediaSession {
        session?.let { return it }
        val s = MediaSession(ctx, "bingr-live")
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { PlayerController.current?.let { if (!it.isPlaying) it.togglePlay() } }
            override fun onPause() { PlayerController.current?.let { if (it.isPlaying) it.togglePlay() } }
        })
        s.isActive = true
        session = s
        return s
    }

    /** Bijwerken voor zender [c] (op de main thread); [playing] = speelt nu. */
    fun update(c: Channel, playing: Boolean) {
        if (!enabled || Radio.isRadio(c)) { stop(); return }
        runCatching {
            val now = System.currentTimeMillis()
            val list = runCatching { Playlists.epgFor(c) }.getOrDefault(emptyList())
            val (ni, xi) = Build179Logic.NowNext.of(list.map { it.start }, list.map { it.end }, now)
            val cur = ni?.let { list[it] }
            val next = xi?.let { list[it] }
            val nowText = cur?.let { L("Now: %@", it.displayTitle) }
            val nextText = next?.let { L("Next: %@ (%@)", it.displayTitle, clock173(it.start)) }
            val s = ensureSession()
            val md = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, c.displayName)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, c.displayName)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, nowText ?: L("Live TV"))
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, nowText ?: L("Live TV"))
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, nextText ?: "")
            if (cur != null && cur.end > cur.start) md.putLong(MediaMetadata.METADATA_KEY_DURATION, cur.end - cur.start)
            logo?.takeIf { logoURL == c.logo }?.let { md.putBitmap(MediaMetadata.METADATA_KEY_ART, it); md.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, it) }
            s.setMetadata(md.build())
            s.setPlaybackState(PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    cur?.let { now - it.start } ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (playing) 1f else 0f)
                .build())
            if (!AppState.isTV) notify(c, playing, nowText, nextText, s)
            // logo één keer per zender ophalen (klein), daarna opnieuw tekenen
            val url = c.logo
            if (url != null && url != logoURL) {
                logoURL = url; logo = null
                AppScope.launch {
                    val bmp = withContext(Dispatchers.IO) {
                        runCatching { Http.bytes(url).let { b -> BitmapFactory.decodeByteArray(b, 0, b.size) } }.getOrNull()
                    }
                    if (bmp != null && logoURL == url && session != null) { logo = bmp; lastSig = ""; update(c, PlayerController.current?.isPlaying ?: playing) }
                }
            }
        }
    }

    private fun notify(c: Channel, playing: Boolean, nowText: String?, nextText: String?, s: MediaSession) {
        val sig = listOf(c.id, playing, nowText, nextText, logo != null).joinToString("|")
        if (sig == lastSig) return
        lastSig = sig
        val nm = nm()
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, L("Live TV"), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false); setSound(null, null)
            })
        }
        val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val contentPI = open?.let { PendingIntent.getActivity(ctx, NOTIF_ID, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        val togglePI = PendingIntent.getBroadcast(ctx, NOTIF_ID, Intent(ctx, LiveSession179Receiver::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL) else Notification.Builder(ctx)
        val action = Notification.Action.Builder(
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (playing) L("Pause") else L("Play"), togglePI).build()
        b.setSmallIcon(R.drawable.ic_stat_bingr)
            .setContentTitle(c.displayName)
            .setContentText(nowText ?: L("Live TV"))
            .setSubText(nextText)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action)
            .setStyle(Notification.MediaStyle().setMediaSession(s.sessionToken).setShowActionsInCompactView(0))
        logo?.let { b.setLargeIcon(it) }
        contentPI?.let { b.setContentIntent(it) }
        runCatching { nm.notify(NOTIF_ID, b.build()) }
    }

    fun stop() {
        runCatching { nm().cancel(NOTIF_ID) }
        runCatching { session?.isActive = false; session?.release() }
        session = null
        lastSig = ""
    }
}

/** Pauze/Afspelen uit de melding van [LiveSession179]. */
class LiveSession179Receiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LiveSession179.ACTION_TOGGLE) return
        AppScope.launch { PlayerController.current?.togglePlay() }
    }
}
