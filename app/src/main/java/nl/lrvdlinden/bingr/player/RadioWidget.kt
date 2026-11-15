package nl.lrvdlinden.bingr

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Build 174: thuisscherm-widget "Bingr Radio" (telefoon/tablet): de zender die nu speelt (of de eerste favoriet) met
 * afspelen/pauze, en tot vier favoriete zenders als knoppen. In een kinderprofiel alleen toegestane zenders (de lijst komt
 * uit [Radio.channels], al gefilterd voor het actieve profiel). De lijst wordt bewaard, zodat de widget ook zonder
 * draaiende app iets toont; is de bibliotheek nog niet geladen, dan opent een tik de app.
 */
class RadioWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, views(context))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_PLAY -> {
                val c = intent.getStringExtra(EXTRA_MEDIA)?.let { Radio.channelFor(it) }
                if (c != null) Radio.play(c) else openApp(context)
            }
            ACTION_TOGGLE -> {
                if (Radio.current != null) Radio.togglePause()
                else {
                    val list = Radio.channels()
                    val f = Build174Logic.featured(list.map { Build174Logic.Station(it.id, it.displayName, isFav(it)) }, null)
                    val c = f?.let { s -> list.firstOrNull { it.id == s.id } }
                    if (c != null) Radio.play(c) else openApp(context)
                }
            }
        }
    }

    companion object {
        private const val ACTION_PLAY = "nl.lrvdlinden.bingr.RADIO_WIDGET_PLAY"
        private const val ACTION_TOGGLE = "nl.lrvdlinden.bingr.RADIO_WIDGET_TOGGLE"
        private const val EXTRA_MEDIA = "media"
        private const val PREFS = "radio_widget"
        private val BUTTONS = intArrayOf(R.id.radio_w_s1, R.id.radio_w_s2, R.id.radio_w_s3, R.id.radio_w_s4)

        private fun isFav(c: Channel): Boolean = Store.isFavorite(c.sourceID ?: Playlists.source?.id ?: "", c.id)

        private var started = false

        /** Volgt de zender, afspelen/pauze, favorieten en de bibliotheek, en werkt de widget bij (hooguit elke 0,8 s). */
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        fun start() {
            if (started) return
            started = true
            AppScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                androidx.compose.runtime.snapshotFlow {
                    listOf<Any?>(Playlists.library, Store.favorites, Radio.current?.id, Radio.playing)
                }.debounce(800).collect { refresh() }
            }
        }

        /** Bijwerken na een wijziging (andere zender, afspelen/pauze, stoppen, andere bibliotheek). */
        fun refresh(ctx: Context = AppState.appContext) {
            runCatching {
                save(ctx)
                val m = AppWidgetManager.getInstance(ctx)
                val ids = m.getAppWidgetIds(ComponentName(ctx, RadioWidget::class.java))
                if (ids.isNotEmpty()) for (id in ids) m.updateAppWidget(id, views(ctx))
            }
        }

        private data class Snap(val stations: List<Pair<Build174Logic.Station, String>>, val current: String?, val playing: Boolean, val live: Boolean)

        /** Lijst bewaren (alleen als de bibliotheek er is), zodat de widget hem ook toont als de app niet draait. */
        private fun save(ctx: Context) {
            val list = Radio.channels()
            if (list.isEmpty()) return
            val arr = JSONArray()
            for (c in list.take(40)) arr.put(JSONObject().put("id", c.id).put("name", c.displayName).put("fav", isFav(c)).put("media", Radio.mediaID(c)))
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("stations", arr.toString()).apply()
        }

        private fun snapshot(ctx: Context): Snap {
            val live = Radio.channels()
            val stations = if (live.isNotEmpty()) live.take(40).map { Build174Logic.Station(it.id, it.displayName, isFav(it)) to Radio.mediaID(it) }
            else runCatching {
                val arr = JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("stations", "[]"))
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    Build174Logic.Station(o.getString("id"), o.getString("name"), o.optBoolean("fav")) to o.getString("media")
                }
            }.getOrDefault(emptyList())
            return Snap(stations, Radio.current?.id, Radio.playing, live.isNotEmpty())
        }

        private fun broadcast(ctx: Context, action: String, media: String?, code: Int): PendingIntent {
            val i = Intent(ctx, RadioWidget::class.java).setAction(action)
            if (media != null) i.putExtra(EXTRA_MEDIA, media)
            return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun appIntent(ctx: Context): PendingIntent? {
            val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return null
            return PendingIntent.getActivity(ctx, 0, i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun openApp(ctx: Context) {
            runCatching { appIntent(ctx)?.send() }
        }

        private fun views(ctx: Context): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_radio)
            val s = snapshot(ctx)
            val stations = s.stations.map { it.first }
            val featured = Build174Logic.featured(stations, s.current)
            val app = appIntent(ctx)
            if (featured == null) {
                v.setTextViewText(R.id.radio_w_title, "Bingr Radio")
                v.setTextViewText(R.id.radio_w_sub, L("Open Bingr to choose your radio stations."))
                v.setViewVisibility(R.id.radio_w_toggle, View.GONE)
                for (b in BUTTONS) v.setViewVisibility(b, View.GONE)
                app?.let { v.setOnClickPendingIntent(R.id.radio_w_root, it) }
                return v
            }
            val isCurrent = featured.id == s.current
            v.setTextViewText(R.id.radio_w_title, featured.name)
            val now = if (isCurrent) Radio.current?.let { c -> Playlists.library.nowPlaying(c)?.title } else null
            v.setTextViewText(R.id.radio_w_sub, Build174Logic.cleanStreamTitle(now, featured.name) ?: L("Radio"))
            v.setViewVisibility(R.id.radio_w_toggle, View.VISIBLE)
            v.setImageViewResource(R.id.radio_w_toggle, if (isCurrent && s.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            v.setContentDescription(R.id.radio_w_toggle, if (isCurrent && s.playing) L("Pause") else L("Play"))
            val toggle = if (s.live) broadcast(ctx, ACTION_TOGGLE, null, 1) else app
            toggle?.let { v.setOnClickPendingIntent(R.id.radio_w_toggle, it) }
            app?.let { v.setOnClickPendingIntent(R.id.radio_w_root, it) }
            val others = Build174Logic.widgetStations(stations.filter { it.id != featured.id }, BUTTONS.size)
            for ((i, b) in BUTTONS.withIndex()) {
                val st = others.getOrNull(i)
                if (st == null) { v.setViewVisibility(b, View.GONE); continue }
                v.setViewVisibility(b, View.VISIBLE)
                v.setTextViewText(b, st.name)
                val media = s.stations.firstOrNull { it.first.id == st.id }?.second
                val pi = if (s.live && media != null) broadcast(ctx, ACTION_PLAY, media, 10 + i) else app
                pi?.let { v.setOnClickPendingIntent(b, it) }
            }
            return v
        }
    }
}
