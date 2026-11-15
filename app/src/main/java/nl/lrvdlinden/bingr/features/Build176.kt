package nl.lrvdlinden.bingr

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable

/**
 * Build 176 (1.0.8): Android-kant van docs/ACCOUNT.md "Build 176" (pure regels in [Build176Logic]).
 */

/** `profile.<id>.autoplayNext` = {value, at}. */
@Serializable
data class AutoplayPref176(val value: Boolean, val at: Long)

/** Punt 1: automatisch de volgende aflevering starten (per profiel, gesynct). */
object AutoplayNext {
    /** De instelling van het actieve profiel (niet ingesteld: aan, kinderprofiel uit). */
    val enabled: Boolean get() = Build176Logic.Autoplay.effective(Store.autoplayNext?.value, Store.activeProfile?.kids == true)
    /** Mag de aftelknop nu vanzelf starten? "Kijk je nog?" gaat voor. */
    fun start(stillWatchingDue: Boolean): Boolean =
        Build176Logic.Autoplay.start(Store.autoplayNext?.value, Store.activeProfile?.kids == true, stillWatchingDue)
}

/** Punt 6: data besparen op een mobiel netwerk (per apparaat, lokale sleutel `dataMode`, niet gesynct). */
object DataSaver {
    const val PREF = "dataMode"
    var mode: String
        get() = Build176Logic.DataMode.Mode.of(Prefs.string(PREF)).raw
        set(v) = Prefs.put(PREF, v)
    val options: List<Pair<String, String>> get() = listOf(
        "auto" to L("Automatic"), "saver" to L("Save data"), "max" to L("Highest quality"))

    /** `wifi` / `cellular` / `unknown` (bekabeld en wifi = `wifi`). */
    fun net(ctx: Context = AppState.appContext): String = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "unknown"
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "unknown"
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "unknown"
        }
    }.getOrDefault("unknown")

    /** Systeem-databesparing (Instellingen > Netwerk > Databesparing) staat aan voor deze app. */
    fun constrained(ctx: Context = AppState.appContext): Boolean = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    }.getOrDefault(false)

    /** Nu besparen? Alleen op telefoon/tablet via een mobiel netwerk (zie [Build176Logic.DataMode.saver]). */
    fun active(ctx: Context = AppState.appContext): Boolean =
        !AppState.isTV && Build176Logic.DataMode.saver(mode, net(ctx), constrained(ctx))

    /** ExoPlayer: max. 1,5 Mbit/s en ≤ 576 lijnen (anders de laagste variant); wifi: geen grens. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun apply(p: androidx.media3.exoplayer.ExoPlayer, ctx: Context = AppState.appContext) {
        val save = active(ctx)
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().apply {
            if (save) setMaxVideoBitrate(Build176Logic.DataMode.MAX_BITRATE).setMaxVideoSize(Int.MAX_VALUE, Build176Logic.DataMode.MAX_HEIGHT)
            else setMaxVideoBitrate(Int.MAX_VALUE).setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
        }.build()
    }
}

/** Punt 7: voorvertoning op een titel (per apparaat, lokale sleutel `posterPreview`, standaard aan). */
object PosterPreview {
    const val PREF = "posterPreview"
    var enabled: Boolean
        get() = Prefs.bool(PREF, true)
        set(v) = Prefs.put(PREF, v)
    fun allowed(locked: Boolean = false, kidsBlocked: Boolean = false): Boolean =
        Build176Logic.Preview.allowed(enabled, DataSaver.active(), locked, kidsBlocked)
}

/**
 * Punt 2: vergrendelen met pincode. Openen van een vergrendelde zender of titel vraagt de pincode van het hoofdprofiel;
 * daarna is dat item 4 uur open op dit apparaat (lokaal, niet gesynct).
 */
object Locks {
    private val sp get() = AppState.appContext.getSharedPreferences("bingr.unlocked176", Context.MODE_PRIVATE)

    class Request(val key: String, val action: () -> Unit)
    /** Wacht op de pincode (één keer in [MainScreen] getoond via [LockGateHost]). */
    var request by mutableStateOf<Request?>(null)
    /** "Stel eerst een pincode in voor het hoofdprofiel" */
    var notice by mutableStateOf<String?>(null)

    fun hasMainPin(): Boolean = Store.mainProfileID?.let { ParentalControl.hasPIN(it) } ?: ParentalControl.hasPIN

    private fun now() = System.currentTimeMillis() / 1000
    private fun unlockedAt(key: String): Long? = runCatching { sp.getLong(key.lowercase(), -1L) }.getOrDefault(-1L).takeIf { it >= 0 }
    fun unlock(key: String) {
        runCatching {
            val n = now()
            val e = sp.edit()
            // oude ontgrendelingen opruimen
            sp.all.forEach { (k, v) -> if (v is Long && n - v >= Build176Logic.Lock.UNLOCK_SECONDS) e.remove(k) }
            e.putLong(key.lowercase(), n).apply()
        }
    }

    fun channelKey(sid: String?, channelID: String): String? =
        (sid ?: Playlists.source?.id)?.let { Build171Logic.ProfileSets.channelKey(it, channelID) }
    fun titleKey(sid: String?, series: Boolean, id: String): String? =
        (sid ?: Playlists.source?.id)?.let { Build171Logic.ProfileSets.titleKey(it, series, id) }

    fun channelLocked(c: Channel): Boolean = Store.isChannelLocked(c.sourceID, c.id)
    fun titleLocked(t: DetailTarget): Boolean = when (t) {
        is DetailTarget.MovieT -> Store.isTitleLocked(t.movie.sourceID, false, t.movie.id)
        is DetailTarget.SeriesT -> Store.isTitleLocked(t.series.sourceID, true, t.series.id)
    }

    /** Sleutel en vergrendeld-stand van wat er gaat spelen (zender, film, of de serie van een aflevering). */
    fun forItem(item: PlayableItem): Pair<String?, Boolean> = when (item.type) {
        MediaType.LIVE -> {
            val cid = item.channelID ?: item.id
            channelKey(item.sourceID, cid) to Store.isChannelLocked(item.sourceID, cid)
        }
        MediaType.MOVIE -> titleKey(item.sourceID, false, item.id) to Store.isTitleLocked(item.sourceID, false, item.id)
        MediaType.EPISODE, MediaType.SERIES -> {
            val sid = item.seriesID ?: (if (item.type == MediaType.SERIES) item.id else null)
            if (sid == null) null to false else titleKey(item.sourceID, true, sid) to Store.isTitleLocked(item.sourceID, true, sid)
        }
    }

    fun needsPin(key: String?, locked: Boolean): Boolean =
        key != null && Build176Logic.Lock.ask(locked, hasMainPin(), unlockedAt(key), now())

    /** [action] meteen, of eerst de pincode van het hoofdprofiel als het item vergrendeld is. */
    fun guard(key: String?, locked: Boolean, action: () -> Unit) {
        if (!needsPin(key, locked) || key == null) { action(); return }
        request = Request(key, action)
    }
    fun guardChannel(c: Channel, action: () -> Unit) = guard(channelKey(c.sourceID, c.id), channelLocked(c), action)
    fun guardTitle(t: DetailTarget, action: () -> Unit) = when (t) {
        is DetailTarget.MovieT -> guard(titleKey(t.movie.sourceID, false, t.movie.id), titleLocked(t), action)
        is DetailTarget.SeriesT -> guard(titleKey(t.series.sourceID, true, t.series.id), titleLocked(t), action)
    }

    /** Vergrendelen mag alleen als het hoofdprofiel een pincode heeft (in een kinderprofiel mag het ook zonder). */
    fun mayLock(): Boolean = hasMainPin() || Store.activeProfile?.kids == true

    /** Zelfde volgorde als hierboven, voor een vergrendelen-knop in een menu. */
    fun lockOrExplain(lock: () -> Unit) {
        if (mayLock()) lock() else notice = L("Set a PIN for the main profile first")
    }

    /** Ontgrendelen (uit de lijst halen) vraagt altijd de pincode van het hoofdprofiel (als die er is). */
    fun unlockWithPin(action: () -> Unit) {
        if (!hasMainPin()) { action(); return }
        request = Request("", action)
    }
}

/** Menu-actie "Vergrendelen met pincode" / "Ontgrendelen" voor een zender (null zonder server). */
fun channelLockAction(c: Channel, pid: String?): S2Action? {
    val sid = c.sourceID ?: pid ?: return null
    val locked = Store.isChannelLocked(sid, c.id)
    return if (locked) S2Action(L("Unlock"), Icons.Default.LockOpen) { Locks.unlockWithPin { Store.setChannelLocked(sid, c.id, false) } }
    else S2Action(L("Lock with PIN"), Icons.Default.Lock) { Locks.lockOrExplain { Store.setChannelLocked(sid, c.id, true) } }
}

/** Menu-actie "Vergrendelen met pincode" / "Ontgrendelen" voor een film of serie (null zonder server). */
fun titleLockAction(t: DetailTarget, pid: String?): S2Action? {
    val sid = t.sourceID ?: pid ?: return null
    val series = t is DetailTarget.SeriesT
    val locked = Store.isTitleLocked(sid, series, t.id)
    return if (locked) S2Action(L("Unlock"), Icons.Default.LockOpen) { Locks.unlockWithPin { Store.setTitleLocked(sid, series, t.id, false) } }
    else S2Action(L("Lock with PIN"), Icons.Default.Lock) { Locks.lockOrExplain { Store.setTitleLocked(sid, series, t.id, true) } }
}

/** De pincodevraag voor [Locks] en de melding als er geen pincode is (één keer in het hoofdscherm). */
@Composable
fun LockGateHost() {
    Locks.request?.let { r ->
        MainPinGate(L("Enter PIN"), onDismiss = { Locks.request = null }) {
            if (r.key.isNotEmpty()) Locks.unlock(r.key)
            Locks.request = null
            r.action()
        }
    }
    Locks.notice?.let { msg ->
        S2ActionDialog(msg, listOf(S2Action(L("OK"), Icons.Default.Check) { }), onDismiss = { Locks.notice = null })
    }
}

/** Punt 8: helpcentrum (telefoon/tablet: Custom Tabs; tv: QR-code). */
object HelpCenter {
    val url: String get() = Build176Logic.Help.url(Localizer.locale.language)

    fun open(ctx: Context) {
        val u = url
        if (AppState.isTV) { Support.openUrl(ctx, u); return }
        // Custom Tabs via het gewone protocol (extra's), zonder extra bibliotheek: browsers zonder Custom Tabs openen
        // de pagina gewoon; geen browser → QR-code
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(u)).addCategory(Intent.CATEGORY_BROWSABLE)
        i.putExtras(Bundle().apply { putBinder("android.support.customtabs.extra.SESSION", null) })
        i.putExtra("android.support.customtabs.extra.TOOLBAR_COLOR", 0xFF000000.toInt())
        i.putExtra("androidx.browser.customtabs.extra.SHARE_STATE", 1)
        if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { ctx.startActivity(i) }.isFailure) Support.openUrl(ctx, u)
    }
}
