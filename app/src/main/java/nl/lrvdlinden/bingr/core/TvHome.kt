package nl.lrvdlinden.bingr

import android.content.ContentUris
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.runtime.snapshotFlow
import androidx.tvprovider.media.tv.ChannelLogoUtils
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.tvprovider.media.tv.Channel as TvChannel
import nl.lrvdlinden.bingr.Build176TechLogic.TvHome as Logic

/**
 * Build 176: Bingr op het beginscherm van Android TV en Google TV.
 * - Watch Next-rij ("Verder kijken" van het systeem): de titels uit Verder kijken van het actieve profiel (films en
 *   afleveringen), bijgewerkt bij voortgang, weg als ze gezien of verwijderd zijn. Tik = verder kijken in Bingr
 *   (bingr://continue). Gratis (zonder Verder kijken) en Free: geen rij.
 * - Preview-kanaal "Bingr" (Android TV-beginscherm; Google TV toont alleen Watch Next): favoriete zenders van het
 *   profiel (anders recent bekeken, anders zenders met een programma nu) met wat er nu op is. Tik = zender
 *   (bingr://live, dezelfde profielcontrole als een herinnering).
 * Alleen wat het actieve profiel mag: de lijsten komen uit de (voor kinderprofielen gefilterde) bibliotheek en Verder
 * kijken van dat profiel, en de links dragen het profiel mee ([Reminders.canOpenFor] bij het openen).
 * De systeemgids (TIF-zenders) vraagt een TvInputService met eigen tuner; die heeft Bingr niet.
 * Alleen Android TV (API 26+); werkt de TvProvider niet (Fire TV), dan gebeurt er niets.
 */
object TvHome {
    private const val TAG = "BingrTvHome"
    private const val PREF_CHANNEL = "tv.previewChannelID"
    private var started = false
    private var lastSignature: String? = null
    private lateinit var app: Context

    private val supported: Boolean get() = AppState.isTV && Build.VERSION.SDK_INT >= 26

    /** Tegel uit Watch Next die geopend moet worden zodra het hoofdscherm en de bibliotheek er zijn. */
    @Volatile private var pending: Logic.Continue? = null

    fun start(ctx: Context) {
        if (!supported || started) return
        started = true
        app = ctx.applicationContext
        // preview-kanaal: bij een andere bibliotheek, andere favorieten of een ander profiel, en elke 10 minuten (wat er nu op is)
        AppScope.launch {
            snapshotFlow {
                if (AppState.stage != AppState.Stage.MAIN) null
                // identiteit van de bibliotheek (niet equals: die vergelijkt alle zenders)
                else "${System.identityHashCode(Playlists.library)}|${Store.favorites.hashCode()}|${Store.recentChannels.hashCode()}|${Store.activeProfileID}"
            }.collectLatest { k ->
                if (k == null) return@collectLatest
                delay(4_000)
                while (true) {
                    runCatching { publishChannel() }.onFailure { Log.w(TAG, "preview-kanaal: ${it.javaClass.simpleName}") }
                    delay(10 * 60_000L)
                }
            }
        }
        // tegel uit Watch Next openen zodra het hoofdscherm er is
        AppScope.launch {
            snapshotFlow { AppState.stage == AppState.Stage.MAIN }.collectLatest { main -> if (main) consume() }
        }
    }

    // MARK: Watch Next

    /** Verder kijken (Home, filter Alles) → Watch Next. Leeg = alles weg (bijv. Gratis). */
    fun publishContinue(items: List<ContinueItem>) {
        if (!supported || !::app.isInitialized) return
        val list = if (Entitlements.continueWatching) items else emptyList()
        val profile = Store.activeProfileID
        AppScope.launch(Dispatchers.IO) {
            runCatching { writeWatchNext(list, profile) }.onFailure { Log.w(TAG, "Watch Next: ${it.javaClass.simpleName}") }
        }
    }

    @Synchronized
    private fun writeWatchNext(items: List<ContinueItem>, profile: String?) {
        val cr = app.contentResolver
        val byKey = items.associateBy { Logic.key(it.sourceID, it.id) }
        val existing = ArrayList<Logic.Existing>()
        cr.query(TvContractCompat.WatchNextPrograms.CONTENT_URI, WatchNextProgram.PROJECTION, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val p = WatchNextProgram.fromCursor(c)
                existing += Logic.Existing(p.id, p.internalProviderId, p.lastEngagementTimeUtcMillis, p.isBrowsable)
            }
        }
        val plan = Logic.plan(existing, items.map { Logic.Want(Logic.key(it.sourceID, it.id), it.updated) })
        for (id in plan.delete) runCatching { cr.delete(TvContractCompat.buildWatchNextProgramUri(id), null, null) }
        for ((id, k) in plan.update) byKey[k]?.let { runCatching { cr.update(TvContractCompat.buildWatchNextProgramUri(id), program(k, it, profile).toContentValues(), null, null) } }
        for (k in plan.insert) byKey[k]?.let { runCatching { cr.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, program(k, it, profile).toContentValues()) } }
    }

    private fun program(key: String, c: ContinueItem, profile: String?): WatchNextProgram {
        val posMs = ((c.position ?: 0.0) * 1000).toLong()
        val durMs = if (c.fraction > 0.01 && posMs > 0) (posMs / c.fraction).toLong() else 0L
        val b = WatchNextProgram.Builder()
        b.setType(if (c.isMovie) TvContractCompat.WatchNextPrograms.TYPE_MOVIE else TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE)
            .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            .setLastEngagementTimeUtcMillis(c.updated)
            .setTitle(ContentLanguage.displayTitle(c.title))
            .setDescription(c.subtitle)
            .setPosterArtAspectRatio(TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_2_3)
            .setIntentUri(Uri.parse(Logic.continueLink(c.id, c.sourceID, c.title, profile)))
            .setInternalProviderId(key)
        c.artwork?.takeIf { it.startsWith("http") }?.let { b.setPosterArtUri(Uri.parse(it)) }
        if (posMs in 1..Int.MAX_VALUE.toLong()) b.setLastPlaybackPositionMillis(posMs.toInt())
        if (durMs in 1..Int.MAX_VALUE.toLong()) b.setDurationMillis(durMs.toInt())
        return b.build()
    }

    /** bingr://continue?id=&src=&title=&profile= (PushNav). */
    fun open(u: Uri) {
        pending = Logic.parseContinue { u.getQueryParameter(it) } ?: return
        AppState.playing = null
        AppState.showMultiview = false
        AppScope.launch { consume() }
    }

    private suspend fun consume() {
        val t = pending ?: return
        if (AppState.stage != AppState.Stage.MAIN) return
        pending = null
        // zelfde profielcontrole als een herinnering: geen titel van een ander profiel op een kinderprofiel
        if (!Reminders.canOpenFor(t.profileID)) return
        // de bibliotheek kan nog laden (koude start vanaf het beginscherm)
        if (t.sourceID == null || AllServers.isActive(t.sourceID)) {
            withTimeoutOrNull(60_000) { snapshotFlow { Playlists.library.isEmpty }.first { !it } }
        }
        ResumePlayer.play(t.itemID, t.title, restart = false, sourceID = t.sourceID)?.let { ScreensNav.detail(it) }
    }

    // MARK: preview-kanaal

    private suspend fun publishChannel() {
        val lib = Playlists.library
        val profile = Store.activeProfileID
        val favorites = Store.favorites
        val recent = Store.recentChannels
        val rows = withContext(Dispatchers.Default) {
            val pid = Playlists.source?.id ?: ""
            // sleutel "<server>|<zender>" van de actieve server (hoofdletters in de server-id maken niet uit)
            fun favID(k: String): String? =
                if (k.length > pid.length + 1 && k[pid.length] == '|' && k.regionMatches(0, pid, 0, pid.length, ignoreCase = true)) k.substring(pid.length + 1) else null
            val ids = lib.channels.map { it.id }
            val withNow = lib.channels.asSequence().filter { lib.nowPlaying(it) != null }.take(Logic.MAX_CHANNEL).map { it.id }.toSet()
            val pick = Logic.channels(ids, favorites.mapNotNullTo(HashSet()) { favID(it) }, recent.mapNotNull { favID(it) }, withNow)
            val byID = lib.channels.associateBy { it.id }
            pick.mapNotNull { byID[it] }.map { it to lib.nowPlaying(it)?.title }
        }
        val sig = (profile ?: "") + "\u0003" + Logic.signature(rows.map { it.first.id to it.second })
        if (sig == lastSignature) return
        withContext(Dispatchers.IO) {
            val id = ensureChannel() ?: return@withContext
            val cr = app.contentResolver
            cr.query(TvContractCompat.buildPreviewProgramsUriForChannel(id), arrayOf(android.provider.BaseColumns._ID), null, null, null)?.use { c ->
                val old = ArrayList<Long>()
                while (c.moveToNext()) old += c.getLong(0)
                for (p in old) runCatching { cr.delete(TvContractCompat.buildPreviewProgramUri(p), null, null) }
            }
            rows.forEachIndexed { i, (ch, now) ->
                val b = PreviewProgram.Builder()
                b.setChannelId(id)
                    .setType(TvContractCompat.PreviewPrograms.TYPE_CHANNEL)
                    .setTitle(ContentLanguage.displayTitle(ch.name))
                    .setLive(true)
                    .setPosterArtAspectRatio(TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_16_9)
                    .setPosterArtUri(Uri.parse(ch.logo?.takeIf { it.startsWith("http") } ?: "android.resource://${app.packageName}/drawable/tv_banner"))
                    .setIntentUri(Uri.parse(Logic.liveLink(ch.id, profile)))
                    .setInternalProviderId(ch.id)
                    .setWeight(rows.size - i)
                now?.let { b.setDescription(it) }
                runCatching { cr.insert(TvContractCompat.PreviewPrograms.CONTENT_URI, b.build().toContentValues()) }
            }
            lastSignature = sig
        }
    }

    /** Het kanaal "Bingr" (één keer aanmaken; weg in de TvProvider = opnieuw). Null als de TvProvider niet werkt. */
    private fun ensureChannel(): Long? {
        val cr = app.contentResolver
        val saved = Prefs.long(PREF_CHANNEL, -1L)
        if (saved > 0) {
            val exists = runCatching {
                cr.query(TvContractCompat.buildChannelUri(saved), arrayOf(android.provider.BaseColumns._ID), null, null, null)?.use { it.count > 0 }
            }.getOrNull() == true
            if (exists) return saved
        }
        val ch = TvChannel.Builder()
            .setType(TvContractCompat.Channels.TYPE_PREVIEW)
            .setDisplayName("Bingr")
            .setAppLinkIntentUri(Uri.parse("bingr://open"))
            .setInternalProviderId("bingr-live")
            .build()
        val uri = runCatching { cr.insert(TvContractCompat.Channels.CONTENT_URI, ch.toContentValues()) }.getOrNull() ?: return null
        val id = ContentUris.parseId(uri)
        Prefs.put(PREF_CHANNEL, id)
        runCatching { BitmapFactory.decodeResource(app.resources, R.mipmap.ic_launcher)?.let { ChannelLogoUtils.storeChannelLogo(app, id, it) } }
        // het eerste kanaal van een app staat meteen op het beginscherm; anders vraagt dit het systeem
        runCatching { TvContractCompat.requestChannelBrowsable(app, id) }
        lastSignature = null
        return id
    }
}
