package nl.lrvdlinden.bingr

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * App-sleutels (zelfde als iOS, "App-sleutels van de server (build 41)" in docs/ACCOUNT.md).
 * Volgorde bij het opzoeken: eerst wat de gebruiker zelf heeft ingevuld (Mijn Bingr → Koppelingen instellen,
 * in [Store.secret]), dan de sleutel van de Bingr-server (device-auth "config", privé-instellingen),
 * dan de ingebouwde ([BuildConfig], via GitHub Secrets).
 */
object Secrets {
    enum class Key(val builtIn: () -> String, val serverField: String) {
        TRAKT_CLIENT_ID({ BuildConfig.TRAKT_CLIENT_ID }, "traktClientID"),
        TRAKT_CLIENT_SECRET({ BuildConfig.TRAKT_CLIENT_SECRET }, "traktClientSecret"),
        OPENSUBTITLES_API_KEY({ BuildConfig.OPENSUBTITLES_API_KEY }, "openSubtitlesAPIKey"),
        OMDB_API_KEY({ BuildConfig.OMDB_API_KEY }, "omdbAPIKey"),
        TMDB_API_KEY({ BuildConfig.TMDB_API_KEY }, "tmdbAPIKey"),
    }

    /** Verandert als sleutels wijzigen (server of gebruiker), zodat schermen die [traktReady] e.d. lezen verversen. */
    var revision by mutableStateOf(0)
        private set

    private var serverSp: SharedPreferences? = null

    /** Privé-instellingen met de sleutels van de server; blijven staan na uitloggen (zo werkt de app ook offline). */
    fun init(ctx: Context) {
        serverSp = ctx.getSharedPreferences("bingr.serverkeys", Context.MODE_PRIVATE)
    }

    /** Zelf ingevulde sleutel (alleen die; leeg = niet ingevuld). */
    fun userValue(k: Key): String = Store.secret("apikey.${k.name}")?.takeIf { it.isNotBlank() } ?: ""
    /** Sleutel van de Bingr-server (leeg = niet opgehaald of leeg op de server). */
    fun serverValue(k: Key): String = runCatching { serverSp?.getString(k.serverField, null) }.getOrNull()?.trim().orEmpty()

    fun value(k: Key): String {
        if (revision < 0) return "" // lezen = Compose-afhankelijkheid
        userValue(k).takeIf { isSet(it) }?.let { return it }
        serverValue(k).takeIf { isSet(it) }?.let { return it }
        return k.builtIn()
    }

    fun set(k: Key, v: String) {
        Store.setSecret("apikey.${k.name}", v.trim().ifEmpty { null })
        reload()
    }

    /** Sleutels van de server opslaan (lege waarden ook, zodat een ingetrokken sleutel verdwijnt) en verversen. */
    fun storeServerKeys(values: Map<Key, String>) {
        val sp = serverSp ?: return
        runCatching {
            sp.edit().apply { values.forEach { (k, v) -> putString(k.serverField, v.trim()) } }.apply()
        }
        reload()
    }

    /** Schermen en diensten opnieuw laten lezen (na het ophalen van de config of zelf invullen). */
    fun reload() { revision++ }

    val traktClientID get() = value(Key.TRAKT_CLIENT_ID)
    val traktClientSecret get() = value(Key.TRAKT_CLIENT_SECRET)
    val openSubtitlesAPIKey get() = value(Key.OPENSUBTITLES_API_KEY)
    val omdbAPIKey get() = value(Key.OMDB_API_KEY)
    val tmdbAPIKey get() = value(Key.TMDB_API_KEY)

    /** Nieuwe Trakt-apps hebben alleen een Client ID (PKCE). */
    val traktReady get() = isSet(traktClientID)
    val openSubtitlesReady get() = isSet(openSubtitlesAPIKey)
    /** Ontbreekt er een sleutel voor Trakt of OpenSubtitles? Dan is "Koppelingen instellen" nodig. */
    val needsSetup get() = !traktReady || !openSubtitlesReady

    fun isSet(v: String) = v.isNotBlank() && !v.startsWith("VUL_")

    /** Bingr-account (Supabase): alleen uit BuildConfig, niet in de app aan te passen. */
    val supabaseUrl: String get() = BuildConfig.SUPABASE_URL.trim().trimEnd('/')
    val supabaseAnonKey: String get() = BuildConfig.SUPABASE_ANON_KEY.trim()
    val accountsConfigured: Boolean get() = !BuildConfig.STRESS && isSet(supabaseUrl) && isSet(supabaseAnonKey)
}
