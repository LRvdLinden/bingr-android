package nl.lrvdlinden.bingr

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Accentkleur: rood, blauw, groen, paars, oranje of roze. [Theme.red] is de accentkleur van de hele app.
 * Build 52/53: één kleur voor het hele account (`settings.shared.accentColor`).
 * Build 54: per profiel, als veld `accentColor` op het gesynchroniseerde profielobject (ontbreekt = "red").
 * De kleur volgt het actieve profiel (Compose-state), dus bij het wisselen van profiel verandert hij meteen.
 */
object Accent {
    /** Oude sleutel (build 52/53), alleen nog voor de eenmalige migratie naar de profielen. */
    const val PREF = "accentColor"
    /** Lokaal: de migratie naar de profielen is gedaan (of was niet nodig). */
    private const val MIGRATED = "accent.perProfile"
    const val DEFAULT = "red"

    data class Option(val key: String, val color: Color, val titleKey: String)

    val options = listOf(
        Option("red", Color(0xFFE50914), "Red"),
        Option("blue", Color(0xFF2F7BF5), "Blue"),
        Option("green", Color(0xFF1DB954), "Green"),
        Option("purple", Color(0xFF9B51E0), "Purple"),
        Option("orange", Color(0xFFF5821F), "Orange"),
        Option("pink", Color(0xFFE6448C), "Pink"),
    )

    fun valid(k: String?): String? = k?.lowercase()?.takeIf { v -> options.any { it.key == v } }

    /** Kleur van een profiel (ontbreekt of onbekend = rood). */
    fun keyFor(p: ViewerProfile?): String = valid(p?.accentColor) ?: DEFAULT

    /** Build 88: accentkleuren horen bij Lifetime; met Gratis rood (de gekozen kleur blijft bewaard voor later). */
    val key: String get() = if (Entitlements.themes) keyFor(Store.activeProfile) else DEFAULT
    val color: Color get() = (options.firstOrNull { it.key == key } ?: options[0]).color

    /** Kleur kiezen voor het actieve profiel. */
    fun set(k: String) {
        val v = valid(k) ?: return
        Store.setActiveAccent(v)
    }

    /**
     * Eenmalige migratie per apparaat (build 54): bestaat er een accentkleur van vóór build 54 ([shared] uit
     * `settings.shared.accentColor`, anders de lokale instelling), dan krijgen alle bestaande profielen zonder eigen
     * kleur die kleur. Profielen die al een kleur hebben (bijv. van een ander apparaat) houden die. Daarna wordt
     * de gedeelde sleutel genegeerd.
     */
    fun migrate(shared: String?) {
        if (Prefs.bool(MIGRATED, false)) return
        if (Store.profiles.isEmpty()) return
        val old = valid(shared) ?: valid(Prefs.string(PREF))
        if (old != null) Store.copyAccentToProfiles(old)
        Prefs.put(MIGRATED, true)
    }

    /** Ander account op dit apparaat: migratie opnieuw mogelijk maken. */
    fun resetMigration() {
        Prefs.remove(MIGRATED)
        Prefs.remove(PREF)
    }
}

/**
 * Alternatief app-icoon op telefoon/tablet via activity-aliassen in het manifest (build 52). Alleen lokaal,
 * niet gesynchroniseerd. Op TV is er alleen de vaste TV-banner. Build 54: alleen het hoofdprofiel kan het kiezen
 * ([canChoose]); de keuze blijft op dit apparaat bewaard (ook na updates) en geldt voor alle profielen.
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

    /** Mag het actieve profiel het icoon kiezen? Alleen het hoofdprofiel, en niet op TV. */
    val canChoose: Boolean get() = !AppState.isTV && Store.canManage

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
        if (!canChoose || options.none { it.key == k } || k == current) return
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
