package nl.lrvdlinden.bingr

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Kleine sleutel-waardeopslag (instellingen, caches).
 *
 * Build 158: grote waarden (TMDB-rijen, Trending, Mijn lijst-titels, logo's, overgeslagen categorieën, en alles boven
 * [Build158Logic.MAX_PREF_VALUE] tekens) staan niet meer in SharedPreferences maar elk in een eigen bestand
 * (`cacheDir/prefs/` als het opnieuw op te halen is, anders `filesDir/prefs/`), zie [Build158Logic.Blobs]. Zo blijft het
 * XML-bestand klein: dat wordt bij de start in één keer gelezen en bij elke wijziging helemaal herschreven. De API blijft
 * gelijk; de eerste start na de update verplaatst de oude waarden op de achtergrond.
 */
object Prefs {
    private lateinit var sp: SharedPreferences
    private lateinit var blobs: Build158Logic.BlobDir
    private const val MIGRATED = "prefs.blobs.v158"
    /** Sleutels buiten [Build158Logic.Blobs.rules] die om hun grootte een bestand hebben (klein lijstje in Prefs zelf). */
    private const val BIG = "prefs.blobs.big"
    private val bigKeys = ConcurrentHashMap.newKeySet<String>()

    /** Eén schrijf-thread voor de bestanden: opdrachten op volgorde, de laatste versie wint. */
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "bingr-prefs").apply { isDaemon = true } }
    /** Nog niet geschreven bestandswaarden (sleutel → waarde, of [GONE]). */
    private val pending = ConcurrentHashMap<String, Any>()
    private val GONE = Any()
    /** Sleutels die sinds de start geschreven zijn (de migratie laat die met rust: die versie is nieuwer). */
    private val touched = ConcurrentHashMap.newKeySet<String>()

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("bingr", Context.MODE_PRIVATE)
        blobs = Build158Logic.BlobDir(File(ctx.filesDir, "prefs"), File(ctx.cacheDir, "prefs"))
        runCatching { sp.getStringSet(BIG, null)?.let { bigKeys.addAll(it) } }
        if (!sp.getBoolean(MIGRATED, false)) io.execute { runCatching { migrate() } }
    }

    /** Build 158: eenmalig de grote waarden uit SharedPreferences naar bestanden (achtergrond). */
    private fun migrate() {
        val moved = blobs.migrate(sp.all, skip = touched.toSet())
        for (k in moved) if (Build158Logic.Blobs.rule(k) == null) bigKeys += k
        sp.edit().apply {
            for (k in moved) if (k !in touched) remove(k)
            putStringSet(BIG, HashSet(bigKeys))
            putBoolean(MIGRATED, true)
        }.commit()
    }

    private fun isBlob(k: String, v: String?) = Build158Logic.Blobs.whereFor(k, v?.length ?: 0) != null

    private fun blobRead(k: String): String? {
        pending[k]?.let { return it as? String }
        return blobs.read(k)
    }

    /** Heeft [k] een bestand? Zonder bestandstoegang voor gewone sleutels (alleen [bigKeys]). */
    private fun blobExists(k: String): Boolean {
        pending[k]?.let { return it !== GONE }
        if (Build158Logic.Blobs.rule(k) == null && k !in bigKeys) return false
        return blobs.exists(k)
    }

    private fun setBig(k: String, on: Boolean) {
        if (Build158Logic.Blobs.rule(k) != null) return
        val changed = if (on) bigKeys.add(k) else bigKeys.remove(k)
        if (changed) sp.edit().putStringSet(BIG, HashSet(bigKeys)).apply()
    }

    private fun blobWrite(k: String, v: String?) {
        val w = v?.let { Build158Logic.Blobs.whereFor(k, it.length) ?: Build158Logic.Blobs.Where.FILES }
        val marker: Any = v ?: GONE
        pending[k] = marker
        io.execute {
            runCatching { if (v == null || w == null) blobs.delete(k) else blobs.write(k, v, w) }
            pending.remove(k, marker)
        }
    }

    fun string(k: String): String? {
        if (Build158Logic.Blobs.rule(k) != null) return blobRead(k) ?: sp.getString(k, null)
        return sp.getString(k, null) ?: if (k in bigKeys) blobRead(k) else null
    }

    fun put(k: String, v: String?) {
        touched += k
        val old = if (k in StateSync.settingKeys) sp.all[k] else null
        if (v != null && isBlob(k, v)) {
            // build 158: groot of een bekende cache → eigen bestand, niet in SharedPreferences
            setBig(k, true)
            blobWrite(k, v)
            if (sp.contains(k)) sp.edit().remove(k).apply()
        } else {
            sp.edit().apply { if (v == null) remove(k) else putString(k, v) }.apply()
            if (blobExists(k)) { blobWrite(k, null); setBig(k, false) }
        }
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
    fun raw(k: String): Any? = runCatching { sp.all[k] ?: if (blobExists(k)) blobRead(k) else null }.getOrNull()
    fun has(k: String): Boolean = sp.contains(k) || blobExists(k)
    /** Instelling gewist (bijv. bij een ander account). */
    fun remove(k: String) {
        touched += k
        sp.edit().remove(k).apply()
        if (blobExists(k)) { blobWrite(k, null); setBig(k, false) }
    }

    /** Instellingen die met het account meegaan (build 44): na een wijziging uploaden. */
    private fun changed(k: String, old: Any?, new: Any?) {
        if (old != new && k in StateSync.settingKeys) StateSync.settingsChanged()
    }
    fun long(k: String, def: Long = 0) = sp.getLong(k, def)
    fun put(k: String, v: Long) { sp.edit().putLong(k, v).apply() }
}
