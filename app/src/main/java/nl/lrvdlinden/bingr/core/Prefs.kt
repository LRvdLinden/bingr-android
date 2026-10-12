package nl.lrvdlinden.bingr

import android.content.Context
import android.content.SharedPreferences

/** Kleine sleutel-waardeopslag (instellingen, caches). */
object Prefs {
    private lateinit var sp: SharedPreferences
    fun init(ctx: Context) { sp = ctx.getSharedPreferences("bingr", Context.MODE_PRIVATE) }
    fun string(k: String): String? = sp.getString(k, null)
    fun put(k: String, v: String?) {
        val old = if (k in StateSync.settingKeys) sp.all[k] else null
        sp.edit().apply { if (v == null) remove(k) else putString(k, v) }.apply()
        changed(k, old, v)
    }
    fun bool(k: String, def: Boolean = false) = sp.getBoolean(k, def)
    fun put(k: String, v: Boolean) {
        val old = if (k in StateSync.settingKeys) sp.all[k] else null
        sp.edit().putBoolean(k, v).apply()
        changed(k, old, v)
    }
    fun int(k: String, def: Int = 0) = sp.getInt(k, def)
    fun put(k: String, v: Int) {
        val old = if (k in StateSync.settingKeys) sp.all[k] else null
        sp.edit().putInt(k, v).apply()
        changed(k, old, v)
    }
    /** Ruwe waarde (String, Boolean, Int, Long …) of null. */
    fun raw(k: String): Any? = runCatching { sp.all[k] }.getOrNull()
    fun has(k: String): Boolean = sp.contains(k)
    /** Instelling gewist (bijv. bij een ander account). */
    fun remove(k: String) { sp.edit().remove(k).apply() }

    /** Instellingen die met het account meegaan (build 44): na een wijziging uploaden. */
    private fun changed(k: String, old: Any?, new: Any?) {
        if (old != new && k in StateSync.settingKeys) StateSync.settingsChanged()
    }
    fun long(k: String, def: Long = 0) = sp.getLong(k, def)
    fun put(k: String, v: Long) { sp.edit().putLong(k, v).apply() }
}
