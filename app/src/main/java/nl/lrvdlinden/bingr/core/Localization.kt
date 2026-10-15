package nl.lrvdlinden.bingr

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Zelfde vertaalsysteem als iOS: Engelse tekst is de sleutel, vertalingen komen uit
 * Shared/Resources/Translations.json (meegebouwd als asset). Gebruik: L("Play"), L("%d seasons", n).
 * %@ (iOS) wordt %s.
 */
object Localizer {
    val supported = listOf(
        "en" to "English", "nl" to "Nederlands", "de" to "Deutsch", "fr" to "Français",
        "es" to "Español", "it" to "Italiano", "pt" to "Português", "tr" to "Türkçe",
        "pl" to "Polski", "ar" to "العربية", "zh-Hans" to "简体中文"
    )
    /**
     * build 97: per taal, pas geladen als die taal nodig is (bij het starten alleen de actieve taal; vroeger werden
     * alle 10 talen, 572 KB JSON, bij het starten in Application.onCreate ingelezen).
     */
    private val tables = ConcurrentHashMap<String, Map<String, String>>()
    private var appContext: Context? = null

    /** "" = volg het systeem */
    // (geen `var override … private set`: die setter botst op de JVM met setOverride())
    private var overrideState by mutableStateOf("")
    val override: String get() = overrideState
    var language by mutableStateOf("en")
        private set

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        overrideState = Prefs.string("app.language") ?: ""
        language = resolve(override)
        table(language)
    }

    /** Vertalingen van één taal ("en" = geen tabel: de Engelse tekst is de sleutel). */
    private fun table(lang: String): Map<String, String> {
        if (lang == "en") return emptyMap()
        tables[lang]?.let { return it }
        val ctx = appContext ?: return emptyMap()
        val t = runCatching { readLanguage(ctx, lang) }.getOrDefault(emptyMap())
        tables[lang] = t
        return t
    }

    /**
     * Alleen het blok van [lang] uit Translations.json lezen (streaming; de andere talen worden overgeslagen zonder
     * ze op te bouwen). Formaat: `{"<taal>": {"<Engels>": "<vertaling>", …}, …}`.
     */
    private fun readLanguage(ctx: Context, lang: String): Map<String, String> {
        val out = HashMap<String, String>(4096)
        ctx.assets.open("Translations.json").use { input ->
            android.util.JsonReader(java.io.InputStreamReader(input.buffered(), Charsets.UTF_8)).use { r ->
                r.beginObject()
                while (r.hasNext()) {
                    if (r.nextName() != lang) { r.skipValue(); continue }
                    r.beginObject()
                    while (r.hasNext()) {
                        val k = r.nextName()
                        if (r.peek() == android.util.JsonToken.STRING) out[k] = r.nextString() else r.skipValue()
                    }
                    r.endObject()
                    return out
                }
            }
        }
        return out
    }

    fun setOverride(code: String) {
        overrideState = code
        Prefs.put("app.language", code)
        language = resolve(code)
    }

    fun resolve(o: String): String {
        if (o.isNotEmpty()) return o
        val codes = supported.map { it.first }
        val l = Locale.getDefault()
        if (l.language == "zh") return "zh-Hans"
        return if (l.language in codes) l.language else "en"
    }

    fun text(key: String): String { val l = language; return if (l == "en") key else table(l)[key] ?: key }
    val locale: Locale get() = if (language == "zh-Hans") Locale.SIMPLIFIED_CHINESE else Locale(language)
    val isRTL: Boolean get() = language == "ar"
}

/** iOS-formaat → Java: %@ → %s, %1$@ → %1$s, %ld/%lld/%lu/%i/%u → %d (positie-argumenten blijven staan). */
private val iosSpec = Regex("%(\\d+\\$)?(ll|l|q|z|t|j|h)?([@dDiIuUxXofeEgGcs%])")
private fun iosToJava(f: String): String = iosSpec.replace(f) { m ->
    val pos = m.groupValues[1]
    when (val conv = m.groupValues[3]) {
        "%" -> "%%"
        "@" -> "%${pos}s"
        "d", "D", "i", "I", "u", "U" -> "%${pos}d"
        else -> "%$pos$conv"
    }
}

fun L(key: String): String = iosToJava(Localizer.text(key)).replace("%%", "%")
fun L(key: String, vararg args: Any?): String {
    val loc = Localizer.locale
    // vertaling kapot (verkeerde of te weinig %-tekens)? dan de Engelse tekst, en anders de sleutel zelf
    return runCatching { String.format(loc, iosToJava(Localizer.text(key)), *args) }
        .recoverCatching { String.format(loc, iosToJava(key), *args) }
        .getOrElse { key }
}
