package nl.lrvdlinden.bingr

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/**
 * Build 141 (versie 1.0.1): beveiliging. Pure logica zonder Android, zodat hij los te testen is
 * (tools/test/Build141LogicTest.kt, ook in de workflow Build). Zelfde regels als de Samsung TV-app (tizen/js/build141.js).
 *
 * - [PinLockout]: oplopende wachttijd na 5 foute pincodes op rij (30 s, 1 min, 5 min, 15 min, 1 uur).
 * - [RelayRegistry]: wat het Cast-doorgeefluik mag serveren (alleen geregistreerde items, token per Cast-sessie,
 *   vast aan één ontvanger).
 * - [TrustedHosts]: vaste lijst voor Trakt (OAuth) en OpenSubtitles, en alleen http(s) naar de browser.
 */
object Build141Logic {

    // MARK: pincode-limiet

    object PinLockout {
        /** Zoveel foute pogingen op rij mogen zonder wachten. */
        const val FREE_ATTEMPTS = 5
        private val waits = longArrayOf(30_000L, 60_000L, 5 * 60_000L, 15 * 60_000L, 60 * 60_000L)

        /** Opgeslagen stand: aantal fouten op rij en tijd (epoch-ms) van de laatste fout. */
        data class State(val failures: Int = 0, val lastFailureAt: Long = 0L)

        /** Wachttijd na [failures] fouten op rij (0 = meteen opnieuw). */
        fun waitAfter(failures: Int): Long =
            if (failures < FREE_ATTEMPTS) 0L else waits[minOf(failures - FREE_ATTEMPTS, waits.size - 1)]

        /**
         * Resterende wachttijd in ms. Staat de klok vóór de laatste fout (klok teruggezet), dan begint de wachttijd
         * opnieuw vanaf nu, zodat terugzetten niet helpt.
         */
        fun remaining(s: State, now: Long): Long {
            val w = waitAfter(s.failures)
            if (w == 0L) return 0L
            if (now < s.lastFailureAt) return w
            return (s.lastFailureAt + w - now).coerceAtLeast(0L)
        }

        fun failed(s: State, now: Long): State = State(s.failures + 1, now)

        fun encode(s: State): String = "${s.failures}|${s.lastFailureAt}"
        fun decode(raw: String?): State {
            val p = raw?.split('|') ?: return State()
            val f = p.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 10_000) ?: return State()
            val t = p.getOrNull(1)?.toLongOrNull() ?: 0L
            return State(f, t)
        }

        /** "30 s", "1 min", "15 min", "1 h" (afgerond naar boven). */
        fun shortText(ms: Long): String {
            val s = (ms + 999) / 1000
            return when {
                s < 60 -> "$s s"
                s < 3600 -> "${(s + 59) / 60} min"
                else -> "${(s + 3599) / 3600} h"
            }
        }
    }

    // MARK: Cast-doorgeefluik

    /** Iets dat de Chromecast mag ophalen: een adres op internet/het LAN, of een bestand in de downloadmap. */
    sealed class RelayItem {
        data class Remote(val url: String) : RelayItem()
        data class Local(val path: String) : RelayItem()
    }

    /**
     * Wat het doorgeefluik mag serveren. Alleen items die de app zelf registreert (de stream die gecast wordt, en de
     * adressen in een HLS-playlist van die stream); nooit een adres of bestand uit het pad van de aanvraag.
     * Het token is nieuw per Cast-sessie ([begin]) en vervalt met [end]. De eerste ontvanger die met een geldig token
     * iets ophaalt (of het adres van de Chromecast, als dat bekend is), is daarna de enige die nog iets krijgt.
     */
    class RelayRegistry(private val maxItems: Int = 4096, private val newToken: () -> String = ::randomToken) {
        private val lock = Any()
        private var token: String? = null
        private var peer: String? = null
        private var counter = 0L
        private val byId = object : LinkedHashMap<String, RelayItem>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RelayItem>?): Boolean {
                val drop = size > maxItems
                if (drop && eldest != null) idByItem.remove(eldest.value)
                return drop
            }
        }
        private val idByItem = HashMap<RelayItem, String>()

        val active: Boolean get() = synchronized(lock) { token != null }
        val currentToken: String? get() = synchronized(lock) { token }

        /** Nieuwe sessie: nieuw token, alles van de vorige sessie vergeten. */
        fun begin(): String = synchronized(lock) {
            clear()
            newToken().also { token = it }
        }

        /** Sessie voorbij: token vervalt, niets meer te halen. */
        fun end() {
            synchronized(lock) { clear(); token = null }
        }

        private fun clear() { byId.clear(); idByItem.clear(); peer = null; counter = 0 }

        /** Adres van de Chromecast (als de Cast-SDK het geeft): dan alleen dat adres. */
        fun expectPeer(address: String?) {
            if (address.isNullOrBlank()) return
            synchronized(lock) { peer = address }
        }

        /**
         * Mag dit adres iets ophalen? Het eerste adres met een geldig token wordt vastgezet; daarna alleen dat adres
         * (en de telefoon zelf niet: die haalt niets via het doorgeefluik op).
         */
        fun allowPeer(address: String?): Boolean = synchronized(lock) {
            if (token == null || address.isNullOrBlank()) return false
            val p = peer
            if (p == null) { peer = address; return true }
            p == address
        }

        /** Registreren; geeft de id (zelfde item = zelfde id), of null als er geen sessie is of het item niet mag. */
        fun register(item: RelayItem): String? = synchronized(lock) {
            if (token == null) return null
            if (item is RelayItem.Remote && !isRelayableRemote(item.url)) return null
            if (item is RelayItem.Local && item.path.isBlank()) return null
            idByItem[item]?.let { id -> byId[id]; return id }   // ook als "recent gebruikt" markeren
            val id = (++counter).toString(36) + randomToken().take(8)
            byId[id] = item
            idByItem[item] = id
            id
        }

        /** Item bij een id (null = onbekend of verlopen). */
        fun item(id: String?): RelayItem? = synchronized(lock) { id?.let { byId[it] } }

        /** Klopt het token in het pad? (vergelijken in vaste tijd) */
        fun tokenMatches(candidate: String?): Boolean {
            val t = synchronized(lock) { token } ?: return false
            if (candidate == null) return false
            return MessageDigest.isEqual(t.toByteArray(), candidate.toByteArray())
        }

        val size: Int get() = synchronized(lock) { byId.size }
    }

    /** Pad van een aanvraag: `/<token>/i/<id>/s.<ext>` of `/<token>/sub/<n>.vtt`. */
    data class RelayPath(val token: String, val kind: String, val id: String)

    fun parseRelayPath(path: String): RelayPath? {
        val segs = path.substringBefore('?').split('/').filter { it.isNotEmpty() }
        if (segs.size < 3) return null
        return when (segs[1]) {
            "i" -> RelayPath(segs[0], "i", segs[2])
            "sub" -> RelayPath(segs[0], "sub", segs[2].substringBefore('.'))
            else -> null
        }
    }

    /** Alleen http(s) en nooit de telefoon zelf (localhost, 127.x, ::1, 0.0.0.0). LAN-adressen mogen wel (eigen server). */
    fun isRelayableRemote(url: String): Boolean {
        val u = url.trim()
        val scheme = u.substringBefore("://", "").lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return false
        val host = hostOf(u) ?: return false
        if (host == "localhost" || host.endsWith(".localhost")) return false
        if (host.startsWith("127.") || host == "0.0.0.0" || host == "::1" || host == "[::1]" || host == "::" || host == "[::]") return false
        return true
    }

    /** Host uit een http(s)-adres (kleine letters, zonder poort, gebruiker of pad); null = geen host. */
    fun hostOf(url: String): String? {
        val rest = url.substringAfter("://", "").takeIf { it.isNotEmpty() } ?: return null
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        val host = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
        return host.lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }
    }

    /** Ligt [path] in een van de [roots]? (paden al canonical; geen "..".) */
    fun isInside(path: String, roots: List<String>): Boolean {
        if (path.contains("/../") || path.endsWith("/..")) return false
        return roots.any { r -> r.isNotEmpty() && path.startsWith(r.trimEnd('/') + "/") }
    }

    fun randomToken(): String {
        val b = ByteArray(16)
        rng.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }
    private val rng = SecureRandom()

    // MARK: vaste hosts

    object TrustedHosts {
        val traktOAuth = listOf("https://api.trakt.tv", "https://auth.trakt.tv")
        const val OPENSUBTITLES_DEFAULT = "api.opensubtitles.com"

        /** Trakt-OAuth-host: alleen een uit [traktOAuth], anders de standaard. */
        fun traktOAuthBase(stored: String?): String {
            val s = stored?.trim()?.trimEnd('/')?.lowercase(Locale.ROOT)
            return traktOAuth.firstOrNull { it == s } ?: traktOAuth[0]
        }

        /**
         * OpenSubtitles-host (zonder https://, zoals de API `base_url` geeft): alleen opensubtitles.com of een subdomein
         * daarvan ("vip-api.opensubtitles.com"); anders de standaard.
         */
        fun openSubtitlesHost(raw: String?): String {
            var s = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (s.isEmpty()) return OPENSUBTITLES_DEFAULT
            if (s.contains("://")) {
                if (!s.startsWith("https://")) return OPENSUBTITLES_DEFAULT
                s = s.removePrefix("https://")
            }
            s = s.substringBefore('/').trimEnd('.')
            if (s.isEmpty() || s.contains('@') || s.contains(':')) return OPENSUBTITLES_DEFAULT
            if (!s.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' }) return OPENSUBTITLES_DEFAULT
            return if (s == "opensubtitles.com" || s.endsWith(".opensubtitles.com")) s else OPENSUBTITLES_DEFAULT
        }

        /** Mag dit adres naar de browser? Alleen http(s) met een host. */
        fun isWebURL(url: String?): Boolean {
            val u = url?.trim() ?: return false
            val scheme = u.substringBefore("://", "").lowercase(Locale.ROOT)
            if (scheme != "http" && scheme != "https") return false
            return hostOf(u) != null
        }
    }
}
