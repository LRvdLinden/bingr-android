package nl.lrvdlinden.bingr

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Accentkleur (build 52): rood, blauw, groen, paars, oranje of roze. Gesynchroniseerd via het account als
 * `settings.shared.accentColor` (zelfde sleutel en waarden als iOS). [Theme.red] is de accentkleur van de hele app.
 */
object Accent {
    const val PREF = "accentColor"

    data class Option(val key: String, val color: Color, val titleKey: String)

    val options = listOf(
        Option("red", Color(0xFFE50914), "Red"),
        Option("blue", Color(0xFF2F7BF5), "Blue"),
        Option("green", Color(0xFF1DB954), "Green"),
        Option("purple", Color(0xFF9B51E0), "Purple"),
        Option("orange", Color(0xFFF5821F), "Orange"),
        Option("pink", Color(0xFFE6448C), "Pink"),
    )

    private var keyState by mutableStateOf("red")
    val key: String get() = keyState
    val color: Color get() = (options.firstOrNull { it.key == keyState } ?: options[0]).color

    /** Waarde uit Prefs lezen (bij het starten en na een sync). */
    fun reload() {
        val v = Prefs.string(PREF)
        keyState = if (options.any { it.key == v }) v!! else "red"
    }

    fun set(k: String) {
        if (options.none { it.key == k }) return
        keyState = k
        Prefs.put(PREF, k)
    }
}

/**
 * Alternatief app-icoon op telefoon/tablet via activity-aliassen in het manifest (build 52). Alleen lokaal,
 * niet gesynchroniseerd. Op TV is er alleen de vaste TV-banner.
 */
object AppIcon {
    private const val PREF = "ui.appIcon"

    data class Option(val key: String, val alias: String, val titleKey: String, val res: Int)

    val options: List<Option>
        get() = listOf(
            Option("default", "LauncherDefault", "Standard", R.mipmap.ic_launcher),
            Option("dark", "LauncherDark", "Dark", R.mipmap.ic_launcher_dark),
            Option("light", "LauncherLight", "Light", R.mipmap.ic_launcher_light),
            Option("blue", "LauncherBlue", "Blue", R.mipmap.ic_launcher_blue),
            Option("gold", "LauncherGold", "Gold", R.mipmap.ic_launcher_gold),
        )

    var current by mutableStateOf("default")
        private set

    fun init() {
        current = Prefs.string(PREF)?.takeIf { k -> options.any { it.key == k } } ?: "default"
        // eerder halverwege gestopt (de launcher kan de app sluiten bij het wisselen): alsnog gelijkzetten
        if (!AppState.isTV) runCatching { apply(current) }
    }

    /**
     * Ander icoon kiezen. Eerst de keuze bewaren, dan de aliassen omzetten: sommige launchers sluiten de app
     * daarbij even af; bij de volgende start klopt alles dan toch (zie [init]).
     */
    fun set(k: String) {
        if (AppState.isTV || options.none { it.key == k } || k == current) return
        current = k
        Prefs.put(PREF, k)
        runCatching { apply(k) }
    }

    private fun apply(k: String) {
        val ctx = AppState.appContext
        val pm = ctx.packageManager
        val pkg = ctx.packageName
        // eerst het nieuwe icoon aan, daarna de andere uit (er is altijd minstens één launcher-ingang)
        val wanted = options.first { it.key == k }
        fun comp(o: Option) = ComponentName(pkg, "$pkg.${o.alias}")
        val target = if (wanted.key == "default") PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        if (pm.getComponentEnabledSetting(comp(wanted)) != target) {
            pm.setComponentEnabledSetting(comp(wanted), target, PackageManager.DONT_KILL_APP)
        }
        for (o in options) {
            if (o.key == k) continue
            val state = if (o.key == "default") PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            if (pm.getComponentEnabledSetting(comp(o)) != state) pm.setComponentEnabledSetting(comp(o), state, PackageManager.DONT_KILL_APP)
        }
    }
}
