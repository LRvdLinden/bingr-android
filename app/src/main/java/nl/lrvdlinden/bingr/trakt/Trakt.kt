package nl.lrvdlinden.bingr

import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.addJsonObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Trakt API v2 (= TraktClient.swift + TraktSync.swift).
 * Inloggen via koppelcode (device flow, ook op TV). Nieuwe Trakt-apps hebben alleen een Client ID:
 * PKCE (code_challenge S256 / code_verifier); client_secret gaat alleen mee als die ingesteld is.
 * Houdt bij wat er bekeken is (2 kanten op): vinkjes, voortgang, watchlist, scrobble.
 */
object Trakt {
    private const val BASE = "https://api.trakt.tv"
    private const val TOKEN_KEY = "trakt.tokens"
    private val oauthHosts = listOf("https://api.trakt.tv", "https://auth.trakt.tv")
    /** Redirect-URI's zoals ze bij de Trakt-app geregistreerd kunnen zijn (nodig bij verversen). */
    val redirectURIs = listOf("https://github.com/LRvdLinden/iptv-ios-appletv", "urn:ietf:wg:oauth:2.0:oob")

    @Serializable
    data class Tokens(val accessToken: String, val refreshToken: String, val expiresAt: Long, val username: String? = null)

    data class DeviceCode(val deviceCode: String, val userCode: String, val verificationUrl: String, val expiresIn: Int, val interval: Int)

    data class TraktIDs(val trakt: Int? = null, val slug: String? = null, val imdb: String? = null, val tmdb: Int? = null, val tvdb: Int? = null)
    data class TraktMedia(val title: String?, val year: Int?, val ids: TraktIDs, val trailer: String?)
    data class PlaybackItem(val id: Long, val progress: Double, val type: String, val imdb: String?, val title: String,
                            val season: Int? = null, val episode: Int? = null)

    enum class Scrobble { START, PAUSE, STOP }

    /** Titel(+jaar) → IMDb-ID, zodat vinkjes direct op IPTV-titels passen zonder per titel op te zoeken. */
    @Serializable
    data class TitleIndex(
        val movies: Map<String, String> = emptyMap(),
        val shows: Map<String, String> = emptyMap(),
        /** imdb → jaar volgens Trakt (build 75): titel zonder jaar-match niet aan een remake uit een ander jaar koppelen */
        val years: Map<String, Int> = emptyMap(),
        /** TMDB-id → imdb (build 75), voor titels waarvan we alleen de TMDB-id kennen (Xtream-info / TMDB) */
        val tmdbMovies: Map<String, String> = emptyMap(),
        val tmdbShows: Map<String, String> = emptyMap(),
    ) {
        fun imdb(title: String, year: Int?, show: Boolean): String? {
            val n = Net.normTitle(title)
            if (n.isEmpty()) return null
            val d = if (show) shows else movies
            if (year != null) {
                d["$n|$year"]?.let { return it }
                // IPTV-jaartallen wijken vaak één jaar af
                d["$n|${year - 1}"]?.let { return it }
                d["$n|${year + 1}"]?.let { return it }
            }
            val i = d[n] ?: return null
            // films: bekend jaar dat duidelijk anders is (bijv. "Dune" 1984 vs. 2021) → geen match.
            // Niet bij series: aanbieders zetten daar vaak het jaar van het laatste seizoen.
            if (!show && year != null) years[i]?.let { y -> if (kotlin.math.abs(y - year) > 1) return null }
            return i
        }
        fun imdb(tmdb: Int, show: Boolean): String? = (if (show) tmdbShows else tmdbMovies)[tmdb.toString()]
    }
    private class IndexBuilder(from: TitleIndex) {
        val movies = HashMap(from.movies)
        val shows = HashMap(from.shows)
        val years = HashMap(from.years)
        val tmdbMovies = HashMap(from.tmdbMovies)
        val tmdbShows = HashMap(from.tmdbShows)
        fun add(title: String?, year: Int?, imdb: String, show: Boolean, tmdb: Int? = null) {
            if (tmdb != null) (if (show) tmdbShows else tmdbMovies)[tmdb.toString()] = imdb
            if (year != null) years[imdb] = year
            if (title == null) return
            val n = Net.normTitle(title)
            if (n.isEmpty()) return
            val d = if (show) shows else movies
            d[n] = imdb
            if (year != null) d["$n|$year"] = imdb
        }
        fun build() = TitleIndex(movies, shows, years, tmdbMovies, tmdbShows)
    }

    private class NotLoggedIn : Exception(L("Not signed in to Trakt."))

    // MARK: state

    var isLoggedIn by mutableStateOf(false); private set
    var username by mutableStateOf<String?>(null); private set
    var displayName by mutableStateOf<String?>(null); private set
    var avatar by mutableStateOf<String?>(null); private set
    /** imdb */
    var watchedMovies by mutableStateOf(setOf<String>()); private set
    /** show-imdb → "s-e" */
    var watchedEpisodes by mutableStateOf(mapOf<String, Set<String>>()); private set
    var watchlist by mutableStateOf(setOf<String>()); private set
    var playback by mutableStateOf(listOf<PlaybackItem>()); private set
    var lastRefresh by mutableStateOf<Long?>(null); private set
    var lastError by mutableStateOf<String?>(null)
    var titleIndex by mutableStateOf(TitleIndex()); private set
    /** Build 154: Trakt-gezien films als inhoudssleutels (`w:m:tmdb:…`, `w:m:imdb:…`, `w:m:t:<norm>|<jaar>`), ook zonder IMDb-id. */
    var watchedKeys by mutableStateOf(setOf<String>()); private set
    /** Build 154: show-vorm ("tmdb:1399", "imdb:tt…", "t:<norm>|<jaar>") → gezien "s-e". Niet naar het account. */
    var showEpisodes by mutableStateOf(mapOf<String, Set<String>>()); private set
    /** Build 154: show-vorm → `aired_episodes` van Trakt (voor "serie gezien"). */
    var aired by mutableStateOf(mapOf<String, Int>()); private set
    /** Build 154: laatste melding uit een Trakt-antwoord (`not_found` enz.) voor de diagnose. */
    var diagnostics by mutableStateOf<String?>(null); private set
    /** Bezig met synchroniseren (draaiende pijltjes in Mijn Bingr); minstens ~0,8 s zichtbaar. */
    var isSyncing by mutableStateOf(false); private set
    /** Kort (2,5 s) true na een geslaagde sync ("Bijgewerkt ✓"). */
    var justSynced by mutableStateOf(false); private set
    /** Laatste melding over doorgeven aan Trakt ("Gezien op Trakt: …"). */
    var lastScrobble by mutableStateOf<String?>(null); private set
    /** Tijdstip (epoch-ms) van [lastScrobble]. */
    var lastScrobbleDate by mutableStateOf<Long?>(null); private set

    /** Uitkomst van een scrobble (voor een melding in de speler en de status in Mijn Bingr). */
    enum class ScrobbleResult { OK, NOT_FOUND, FAILED, SKIPPED }

    private var justSyncedJob: Job? = null
    /** Opgeschoonde IPTV-titels ("NL | Reacher (2022) 4K" → "Reacher", 2022), zodat dat niet bij elk scrollen opnieuw hoeft. */
    private val cleanCache = ConcurrentHashMap<String, TitleCleaner.Parsed>()

    private var scrobbleState = mutableStateOf(Prefs.bool("trakt.scrobble", true))
    var scrobbleEnabled: Boolean
        get() = scrobbleState.value
        set(v) { scrobbleState.value = v; Prefs.put("trakt.scrobble", v) }
    private var askRatingState = mutableStateOf(Prefs.bool("trakt.askRating", true))
    var askRating: Boolean
        get() = askRatingState.value
        set(v) { askRatingState.value = v; Prefs.put("trakt.askRating", v) }

    private var lastActivity: String? = null
    private var verifier = ""
    private val tokenLock = Mutex()

    private val tokens: Tokens?
        get() = Store.secret(TOKEN_KEY)?.let { runCatching { Http.json.decodeFromString(Tokens.serializer(), it) }.getOrNull() }
    private fun saveTokens(t: Tokens?) =
        Store.setSecret(TOKEN_KEY, t?.let { Http.json.encodeToString(Tokens.serializer(), it) })

    // build 141: alleen api.trakt.tv / auth.trakt.tv (tokens en client-id gaan nooit naar een andere host)
    private var oauthBase: String
        get() = Build141Logic.TrustedHosts.traktOAuthBase(Prefs.string("trakt.oauthHost"))
        set(v) = Prefs.put("trakt.oauthHost", Build141Logic.TrustedHosts.traktOAuthBase(v))

    /**
     * Trakt hoort bij het hoofdaccount: andere kijkprofielen zien geen Trakt-vinkjes, -voortgang of -aanbevelingen
     * en scrobblen niet naar het Trakt-account van het hoofdaccount. Gezet via [applyProfile] (AppState.open(profile)).
     */
    var profileAllowed by mutableStateOf(true); private set

    init {
        profileAllowed = Store.isMainActive || Store.profiles.size <= 1
        if (profileAllowed) {
            val t = tokens
            isLoggedIn = t != null
            username = t?.username
            loadCache()
            if (isLoggedIn) AppScope.launch { flushQueue(wake = true); refresh(); loadProfile() }
        }
    }

    /** Ander kijkprofiel gekozen: Trakt alleen actief voor het hoofdaccount. */
    fun applyProfile(isMain: Boolean) {
        if (isMain == profileAllowed) return
        profileAllowed = isMain
        if (isMain) {
            val t = tokens
            isLoggedIn = t != null
            username = t?.username
            loadCache()
            if (isLoggedIn) AppScope.launch { runCatching { refresh() } }
        } else {
            isLoggedIn = false
            watchedMovies = emptySet(); watchedEpisodes = emptyMap(); watchlist = emptySet(); playback = emptyList()
            watchedKeys = emptySet(); showEpisodes = emptyMap(); aired = emptyMap()
        }
    }

    // MARK: inloggen (device flow + PKCE)

    private fun randomVerifier(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val rnd = SecureRandom()
        return String(CharArray(64) { chars[rnd.nextInt(chars.length)] })
    }
    private fun challenge(v: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(v.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(hash, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private val apiHeaders: Map<String, String>
        get() = mapOf("trakt-api-version" to "2", "trakt-api-key" to Secrets.traktClientID)

    /** Body met client_secret alleen als de app er (nog) een heeft. */
    private fun oauthBody(extra: Map<String, String>): JsonObject = buildJsonObject {
        extra.forEach { (k, v) -> put(k, v) }
        put("client_id", Secrets.traktClientID)
        if (Secrets.isSet(Secrets.traktClientSecret)) put("client_secret", Secrets.traktClientSecret)
    }

    private suspend fun oauthPost(path: String, body: JsonObject, host: String? = null): Http.Response =
        Http.request((host ?: oauthBase) + path, method = "POST", headers = apiHeaders, jsonBody = body.toString())

    suspend fun startLogin(): DeviceCode {
        verifier = randomVerifier()
        val body = buildJsonObject {
            put("client_id", Secrets.traktClientID)
            put("code_challenge", challenge(verifier)); put("code_challenge_method", "S256")
        }
        var lastStatus = 0
        var lastError: Exception? = null
        for (h in oauthHosts) {
            try {
                val r = oauthPost("/oauth/device/code", body, h)
                if (r.ok) {
                    val j = runCatching { Http.json.parseToJsonElement(r.body) }.getOrNull()
                    val dc = j.jS("device_code"); val uc = j.jS("user_code")
                    if (dc != null && uc != null) {
                        oauthBase = h
                        return DeviceCode(dc, uc, j.jS("verification_url") ?: "https://trakt.tv/activate",
                            j.jI("expires_in") ?: 600, j.jI("interval") ?: 5)
                    }
                }
                lastStatus = r.code
            } catch (e: CancellationException) { throw e } catch (e: Exception) { lastError = e }
        }
        if (lastStatus == 0 && lastError != null) throw lastError
        throw Net.statusError(lastStatus, "Trakt")
    }

    /** Pollt tot de gebruiker de code heeft bevestigd op trakt.tv/activate; daarna ingelogd + gesynchroniseerd. */
    suspend fun finishLogin(c: DeviceCode) {
        val deadline = System.currentTimeMillis() + c.expiresIn * 1000L
        var interval = maxOf(c.interval, 5).toLong()
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000)
            // netwerkfout (bijv. app op de achtergrond terwijl je op trakt.tv/activate bent): gewoon opnieuw proberen
            val r = try {
                oauthPost("/oauth/device/token", oauthBody(mapOf("code" to c.deviceCode, "code_verifier" to verifier)))
            } catch (e: java.io.IOException) { continue }
            when (r.code) {
                200 -> {
                    var tok = parseTokens(r.body) ?: throw Net.decoding("token")
                    tok = tok.copy(username = runCatching { fetchUsername(tok.accessToken) }.getOrNull())
                    saveTokens(tok)
                    isLoggedIn = true; username = tok.username
                    refresh(force = true)
                    loadProfile()
                    return
                }
                400 -> continue                    // nog niet bevestigd
                429 -> interval += 1               // te snel
                404, 409, 410, 418 -> throw ServiceException(L("Trakt refused the connection (%d).", r.code))
                else -> continue
            }
        }
        throw ServiceException(L("The code has expired. Please try again."))
    }

    fun logout() {
        tokens?.let { t ->
            AppScope.launch { runCatching { oauthPost("/oauth/revoke", oauthBody(mapOf("token" to t.accessToken))) } }
        }
        clearLocal()
    }

    /** Huidige tokens (voor de account-sync, build 44). */
    internal val currentTokens: Tokens? get() = tokens

    /**
     * Tokens uit het Bingr-account (build 44): opslaan en Trakt meteen als gekoppeld tonen; null = elders
     * ontkoppeld (lokaal uitloggen zonder revoke, dat deed het andere apparaat al).
     */
    fun applySyncedTokens(t: Tokens?) {
        val cur = tokens
        if (cur == t) return
        if (t == null) { clearLocal(); return }
        saveTokens(t)
        if (!profileAllowed) return
        val fresh = !isLoggedIn || cur?.username != t.username
        isLoggedIn = true; username = t.username
        if (fresh) {
            loadCache()
            AppScope.launch { runCatching { refresh(force = true) }; runCatching { loadProfile() } }
        }
    }

    /** Alleen lokaal uitloggen (geen revoke): bij een ander account op dit apparaat of als het account Trakt ontkoppelde. */
    fun clearLocal() {
        saveTokens(null)
        isLoggedIn = false; username = null; displayName = null; avatar = null
        watchedMovies = emptySet(); watchedEpisodes = emptyMap(); watchlist = emptySet(); playback = emptyList()
        watchedKeys = emptySet(); showEpisodes = emptyMap(); aired = emptyMap(); diagnostics = null
        titleIndex = TitleIndex()
        // de wachtrij hoort bij dit Trakt-account: na uitloggen niets meer doorgeven
        synchronized(queueSync) { queue = emptyList() }
        saveQueue()
        lastActivity = null
        lastRefresh = null; lastError = null; lastScrobble = null; lastScrobbleDate = null; justSynced = false
        saveCache()
    }

    private fun parseTokens(body: String): Tokens? {
        val j = runCatching { Http.json.parseToJsonElement(body) }.getOrNull() ?: return null
        val at = j.jS("access_token") ?: return null
        val rt = j.jS("refresh_token") ?: return null
        val created = j.jL("created_at") ?: (System.currentTimeMillis() / 1000)
        val expires = j.jL("expires_in") ?: 0
        return Tokens(at, rt, (created + expires) * 1000)
    }

    private suspend fun fetchUsername(token: String): String? {
        val r = Http.request("$BASE/users/settings", headers = headers(token))
        return runCatching { Http.json.parseToJsonElement(r.body).jO("user").jS("username") }.getOrNull()
    }

    /**
     * Build 154: geldig access-token. Verversen pas als het minder dan 1 uur geldig is, of na een 401 ([failed] = het token
     * dat de 401 kreeg; dan één keer). Eén verversing tegelijk (Mutex; wie daarna komt en ziet dat het token al vervangen
     * is, ververst niet opnieuw). Refresh-tokens zijn eenmalig: eerst `connections` uit het account lezen (een ander
     * apparaat kan al ververst hebben). Mislukt het verversen, dan opnieuw `connections` lezen en een nieuwer token
     * overnemen; een mislukte verversing zet **nooit** `trakt: null` (alleen een bewuste uitlog doet dat).
     */
    private suspend fun validToken(failed: String? = null): String = tokenLock.withLock {
        val T = Build154Logic.Token
        var t = tokens ?: throw NotLoggedIn()
        val now = System.currentTimeMillis()
        if (!T.stillNeeded(t.accessToken, failed, t.expiresAt, now)) return@withLock t.accessToken
        if (StateSync.adoptNewerServerConnections()) {
            val n = tokens ?: run { withContext(Dispatchers.Main) { isLoggedIn = false }; throw NotLoggedIn() }
            if (n.accessToken != t.accessToken && !T.needsRefresh(n.expiresAt, now)) return@withLock n.accessToken
            t = n
        }
        var lastCode = 0
        for (redirect in redirectURIs) {
            val r = try {
                oauthPost("/oauth/token", oauthBody(mapOf(
                    "refresh_token" to t.refreshToken, "redirect_uri" to redirect, "grant_type" to "refresh_token")))
            } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            lastCode = r?.code ?: lastCode
            val n = if (r?.code == 200) parseTokens(r.body) else null
            if (n != null) {
                saveTokens(n.copy(username = t.username))
                // meteen naar het account, zodat andere apparaten het nieuwe paar gebruiken
                StateSync.traktRefreshed()
                // na een verversing: de wachtrij opnieuw proberen
                AppScope.launch { flushQueue(wake = true) }
                return@withLock n.accessToken
            }
        }
        // mislukt: misschien ververste een ander apparaat net (eenmalig refresh-token) → dat token overnemen
        val before = t
        if (StateSync.adoptNewerServerConnections()) {
            val n = tokens
            if (n != null) {
                val pick = T.afterFailure(before, n, serverIsNewer = n.accessToken != before.accessToken)
                if (pick !== before) return@withLock pick.accessToken
            }
        }
        // nooit null in het account: de koppeling blijft staan, alleen deze aanvraag mislukt
        withContext(Dispatchers.Main) { lastError = L("Trakt refused the connection (%d).", lastCode) }
        if (failed == null && t.expiresAt > now) return@withLock t.accessToken
        throw ServiceException(L("Trakt refused the connection (%d).", lastCode))
    }

    /** Verzoek met token; bij een 401 één keer verversen en opnieuw. */
    private suspend fun authed(send: suspend (String) -> Http.Response): Http.Response {
        val tok = validToken()
        val r = send(tok)
        if (r.code != 401) return r
        return send(validToken(failed = tok))
    }

    // MARK: requests

    private fun headers(token: String?): Map<String, String> {
        val h = HashMap(apiHeaders)
        h["Content-Type"] = "application/json"
        if (token != null) h["Authorization"] = "Bearer $token"
        return h
    }

    /** GET op api.trakt.tv → JSON (gooit bij een foutstatus). */
    suspend fun get(path: String, query: List<Pair<String, String>> = emptyList(), auth: Boolean = true): JsonElement {
        val u = Net.url(BASE + path, query) ?: throw Net.badURL()
        val r = if (auth) authed { Http.request(u, headers = headers(it)) } else Http.request(u, headers = headers(null))
        if (!r.ok) throw Net.statusError(r.code, r.body)
        // grote lijsten (bekeken series) niet op de main thread parsen
        return withContext(Dispatchers.Default) { Net.parse(r.body) }
    }

    /** POST (409 = al gedaan, telt als gelukt). */
    suspend fun post(path: String, body: JsonElement, auth: Boolean = true): String {
        val r = postRaw(path, body, auth)
        if (!r.ok && r.code != 409) throw Net.statusError(r.code, r.body)
        return r.body
    }

    /** Build 154: POST met het hele antwoord (status, `Retry-After`), voor de wachtrij en de scrobble. */
    private suspend fun postRaw(path: String, body: JsonElement, auth: Boolean = true): Http.Response =
        if (auth) authed { Http.request(BASE + path, method = "POST", headers = headers(it), jsonBody = body.toString()) }
        else Http.request(BASE + path, method = "POST", headers = headers(null), jsonBody = body.toString())

    suspend fun delete(path: String) {
        authed { Http.request(BASE + path, method = "DELETE", headers = headers(it)) }
    }

    // MARK: ophalen

    private fun ids(e: JsonElement?): TraktIDs {
        val i = e.jO("ids")
        return TraktIDs(trakt = i.jI("trakt"), slug = i.jS("slug"), imdb = i.jS("imdb"), tmdb = i.jI("tmdb"), tvdb = i.jI("tvdb"))
    }
    private fun media(e: JsonElement?): TraktMedia? {
        if (e == null || e !is JsonObject) return null
        return TraktMedia(title = e.jS("title"), year = e.jI("year"), ids = ids(e), trailer = e.jS("trailer"))
    }

    /**
     * Haalt bekeken films/afleveringen, voortgang en watchlist op. Zonder [force] wordt eerst /sync/last_activities
     * gecontroleerd: niets veranderd → niets opnieuw ophalen. [isSyncing] blijft minstens ~0,8 s zichtbaar,
     * daarna kort [justSynced] als het gelukt is.
     */
    suspend fun refresh(force: Boolean = false): Unit = withContext(Dispatchers.Main.immediate) {
        if (!isLoggedIn) {
            if (force) lastError = L("Not signed in to Trakt.")
            return@withContext
        }
        if (isSyncing) return@withContext
        isSyncing = true
        justSyncedJob?.cancel(); justSynced = false
        val started = System.currentTimeMillis()
        try {
            val act = get("/sync/last_activities").jS("all")
            if (!force && act != null && act == lastActivity && (watchedMovies.isNotEmpty() || watchedEpisodes.isNotEmpty())) {
                lastRefresh = System.currentTimeMillis()
                lastError = null
                return@withContext
            }
            val fetched = coroutineScope {
                val m = async { get("/sync/watched/movies") }
                // build 154: extended=full voor aired_episodes (serie gezien)
                val s = async { get("/sync/watched/shows", listOf("extended" to "full")) }
                val p = async { get("/sync/playback") }
                val w = async { get("/sync/watchlist") }
                Fetched(m.await(), s.await(), p.await(), w.await())
            }
            val base = titleIndex
            val r = withContext(Dispatchers.Default) { build(fetched, base) }
            watchedMovies = r.movies; watchedEpisodes = r.episodes; playback = r.playback; watchlist = r.watchlist
            titleIndex = r.index
            watchedKeys = r.keys; showEpisodes = r.showEps; aired = r.aired
            lastActivity = act
            lastRefresh = System.currentTimeMillis()
            lastError = null
            saveCache()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = Net.message(e)
            if (e is NotLoggedIn) isLoggedIn = false
        } finally {
            // spinner minstens even laten zien, en daarna kort "Bijgewerkt ✓"
            val wait = (800L - (System.currentTimeMillis() - started)).coerceAtLeast(0L)
            justSyncedJob = AppScope.launch {
                delay(wait)
                isSyncing = false
                if (lastError == null) {
                    justSynced = true
                    delay(2_500)
                    justSynced = false
                }
            }
        }
        Unit
    }

    private class Fetched(val movies: JsonElement, val shows: JsonElement, val playback: JsonElement, val watchlist: JsonElement)
    private class Built(val movies: Set<String>, val episodes: Map<String, Set<String>>, val playback: List<PlaybackItem>,
                        val watchlist: Set<String>, val index: TitleIndex,
                        val keys: Set<String>, val showEps: Map<String, Set<String>>, val aired: Map<String, Int>)

    /** Antwoorden van Trakt omzetten (op de achtergrond: bij veel bekeken series zijn dit grote lijsten). */
    private fun build(f: Fetched, base: TitleIndex): Built {
        val idx = IndexBuilder(base)
        val wm = HashSet<String>()
        val W = Build154Logic.WatchKeys
        // build 154: inhoudssleutels (tmdb, imdb en titel + jaar uit het Trakt-antwoord); zonder IMDb-id niet meer weg
        val keys = HashSet<String>()
        for (x in f.movies.jItems) {
            val mv = x.jO("movie")
            val mi = ids(mv)
            val title = mv.jS("title") ?: ""
            keys.addAll(W.movieKeys(title, mv.jI("year"), mi.tmdb?.toString(), mi.imdb))
            val i = mi.imdb ?: continue
            wm.add(i); idx.add(mv.jS("title"), mv.jI("year"), i, false, mi.tmdb)
        }
        val eps = HashMap<String, Set<String>>()
        val showEps = HashMap<String, MutableSet<String>>()
        val airedMap = HashMap<String, Int>()
        for (show in f.shows.jItems) {
            val sh = show.jO("show")
            val si = ids(sh)
            val set = HashSet<String>()
            for (season in show.jList("seasons")) {
                val sn = season.jI("number") ?: continue
                if (sn == 0) continue   // specials tellen niet mee voor aired_episodes
                for (e in season.jList("episodes")) e.jI("number")?.let { set.add("$sn-$it") }
            }
            for (season in show.jList("seasons")) if (season.jI("number") == 0)
                for (e in season.jList("episodes")) e.jI("number")?.let { set.add("0-$it") }
            val forms = W.showForms(sh.jS("title") ?: "", sh.jI("year"), si.tmdb?.toString(), si.imdb)
            val a = sh.jI("aired_episodes")
            for (fm in forms) {
                showEps.getOrPut(fm) { HashSet() }.addAll(set)
                if (a != null && a > 0) airedMap[fm] = a
            }
            val imdb = si.imdb ?: continue
            idx.add(sh.jS("title"), sh.jI("year"), imdb, true, si.tmdb)
            eps[imdb] = set
        }
        val pb = f.playback.jItems.mapNotNull { x ->
            val id = x.jL("id") ?: return@mapNotNull null
            val progress = x.jD("progress") ?: 0.0
            val type = x.jS("type") ?: "movie"
            if (type == "episode") {
                val ep = x.jO("episode")
                PlaybackItem(id, progress, type, ids(x.jO("show")).imdb, x.jO("show").jS("title") ?: "",
                    ep.jI("season"), ep.jI("number"))
            } else PlaybackItem(id, progress, type, ids(x.jO("movie")).imdb, x.jO("movie").jS("title") ?: "")
        }
        val wset = HashSet<String>()
        for (x in f.watchlist.jItems) {
            val mm = x.jO("movie"); val ss = x.jO("show")
            (ids(mm).imdb ?: ids(ss).imdb)?.let { wset.add(it) }
            if (mm != null) ids(mm).let { mi -> mi.imdb?.let { idx.add(mm.jS("title"), mm.jI("year"), it, false, mi.tmdb) } }
            if (ss != null) ids(ss).let { si -> si.imdb?.let { idx.add(ss.jS("title"), ss.jI("year"), it, true, si.tmdb) } }
        }
        return Built(wm, eps, pb, wset, idx.build(), keys, showEps, airedMap)
    }

    // MARK: status in de UI

    /**
     * IMDb-ID van een IPTV-titel: eerst uit de Trakt-index, dan uit de metadata-cache.
     * Ook de schone titel proberen ("NL | Reacher (2022) 4K" → "Reacher", 2022), anders vinden we je Trakt-vinkjes niet.
     */
    fun imdb(m: Movie): String? = m.imdbID ?: indexed(m.title, m.year, false) ?: Metadata.cached(m)?.imdbID
    fun imdb(s: Series): String? = s.imdbID ?: indexed(s.title, s.year, true) ?: Metadata.cached(s)?.imdbID

    /**
     * Details van een titel geladen (build 75): de IMDb-id uit Xtream-info/TMDB (of via de TMDB-id uit de Trakt-index)
     * onthouden voor deze IPTV-titel. Zo krijgt de poster in Home/Series/Zoeken hetzelfde vinkje of "N BEKEKEN" als de
     * detailpagina, ook als de titel van de aanbieder niet op de Trakt-titel lijkt (bijv. een Nederlandse titel).
     */
    fun learn(t: DetailTarget, imdbID: String?, tmdb: Int?) {
        if (!isLoggedIn) return
        AppScope.launch(Dispatchers.Main.immediate) {
            if (!isLoggedIn) return@launch
            val show = t.isSeries
            val known = when (t) { is DetailTarget.MovieT -> imdb(t.movie); is DetailTarget.SeriesT -> imdb(t.series) }
            val id = imdbID?.takeIf { it.startsWith("tt") } ?: tmdb?.let { titleIndex.imdb(it, show) } ?: return@launch
            if (known == id) return@launch
            // alleen als het iets toevoegt aan wat we van Trakt weten (bekeken of op de watchlist)
            val relevant = id in watchlist || (if (show) watchedEpisodes.containsKey(id) else id in watchedMovies)
            if (!relevant) return@launch
            val b = IndexBuilder(titleIndex)
            b.add(t.title, null, id, show)   // geen jaar: dat van Trakt blijft leidend
            titleIndex = b.build()
            saveCache()
        }
    }

    private fun indexed(raw: String, year: Int?, show: Boolean): String? {
        val idx = titleIndex
        // niets in de index (niet ingelogd / ander profiel)? dan ook niet elke titel opschonen — scheelt rekenwerk bij scrollen
        if (if (show) idx.shows.isEmpty() else idx.movies.isEmpty()) return null
        idx.imdb(raw, year, show)?.let { return it }
        val c = cleaned(raw)
        return idx.imdb(c.title, year ?: c.year, show)
    }

    private fun cleaned(raw: String): TitleCleaner.Parsed {
        cleanCache[raw]?.let { return it }
        val p = TitleCleaner.parse(raw)
        if (cleanCache.size > 20_000) cleanCache.clear()
        cleanCache[raw] = p
        return p
    }

    /** IMDb-ID opzoeken bij Trakt als de titel er nog geen heeft (zodat kijken toch als "gezien" wordt doorgegeven). */
    private suspend fun resolveIMDb(item: PlayableItem): String? {
        item.imdbID?.let { return it }
        val show = item.type == MediaType.EPISODE
        val c = cleaned(item.title)
        titleIndex.imdb(item.title, null, show)?.let { return it }
        titleIndex.imdb(c.title, c.year, show)?.let { return it }
        val year = if (show) c.year else (c.year ?: item.subtitle?.trim()?.toIntOrNull())
        val hit = lookup(c.title, year, show) ?: return null
        val i = hit.ids.imdb ?: return null
        withContext(Dispatchers.Main.immediate) {
            val b = IndexBuilder(titleIndex)
            b.add(item.title, null, i, show)
            b.add(hit.title, hit.year, i, show)
            titleIndex = b.build()
            saveCache()
        }
        return i
    }

    fun isWatched(movieImdb: String?): Boolean = movieImdb != null && movieImdb in watchedMovies
    fun isWatched(showImdb: String?, season: Int, episode: Int): Boolean =
        showImdb != null && watchedEpisodes[showImdb]?.contains("$season-$episode") == true
    fun watchedCount(showImdb: String?): Int = showImdb?.let { watchedEpisodes[it]?.size } ?: 0

    fun playbackProgress(movieImdb: String?): Double? {
        if (movieImdb == null) return null
        return playback.firstOrNull { it.type == "movie" && it.imdb == movieImdb }?.let { it.progress / 100 }
    }

    // MARK: schrijven (build 154: via de blijvende wachtrij)

    /** tmdb en/of imdb, wat bekend is. */
    data class Ids(val tmdb: Int? = null, val imdb: String? = null) {
        val isEmpty: Boolean get() = tmdb == null && imdb == null
        fun json(): JsonObject = buildJsonObject {
            tmdb?.let { put("tmdb", it) }
            imdb?.let { put("imdb", it) }
        }
    }

    private fun idsFrom(j: JsonElement?): Ids? {
        val o = j.jO("ids") ?: return null
        val i = Ids(o.jI("tmdb")?.takeIf { it > 0 }, o.jS("imdb")?.takeIf { it.startsWith("tt") })
        return i.takeIf { !it.isEmpty }
    }

    /**
     * ids van een item: tmdb van de server en imdb van het item of de index; zoeken op titel alleen zonder id, en een
     * treffer alleen met de juiste titel (norm) en het juiste jaar (±1).
     */
    private suspend fun resolveIds(title: String, year: Int?, tmdb: String?, imdb: String?, show: Boolean): Ids? {
        val tm = tmdb?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        val c = cleaned(title)
        val im = imdb?.takeIf { it.startsWith("tt") }
            ?: titleIndex.imdb(title, year, show) ?: titleIndex.imdb(c.title, year ?: c.year, show)
            ?: tm?.let { titleIndex.imdb(it, show) }
        if (tm != null || im != null) return Ids(tm, im)
        if (!Secrets.isSet(Secrets.traktClientID)) return null
        // zoeken (een netwerkfout gooit: dan later opnieuw); alleen een treffer met de juiste titel en het juiste jaar
        val y = year ?: c.year
        val q = mutableListOf("query" to c.title, "extended" to "full", "limit" to "10")
        val hits = get("/search/${if (show) "show" else "movie"}", q, auth = false).jItems.mapNotNull { media(it.jO("movie") ?: it.jO("show")) }
        val hit = Build154Logic.Search.pick(c.title, y, hits, { it.title }, { it.year }) ?: return null
        return Ids(hit.ids.tmdb, hit.ids.imdb).takeIf { !it.isEmpty }
    }

    private fun isoNow(at: Long = System.currentTimeMillis()): String =
        java.time.Instant.ofEpochMilli(at).toString()

    /** Body voor /sync/history(/remove): één film, of afleveringen van één serie. */
    private fun historyBody(ids: Ids, eps: List<Pair<Int, Int>>?, add: Boolean, at: String): JsonObject =
        if (eps != null) buildJsonObject {
            putJsonArray("shows") { addJsonObject {
                put("ids", ids.json())
                putJsonArray("seasons") {
                    eps.groupBy({ it.first }, { it.second }).forEach { (sn, list) ->
                        addJsonObject {
                            put("number", sn)
                            putJsonArray("episodes") { list.forEach { n -> addJsonObject { put("number", n); if (add) put("watched_at", at) } } }
                        }
                    }
                }
            } }
        } else buildJsonObject {
            putJsonArray("movies") { addJsonObject { put("ids", ids.json()); if (add) put("watched_at", at) } }
        }

    /** Opdracht in de wachtrij: wat er naar Trakt moet (ids worden pas bij het versturen opgezocht als ze er niet zijn). */
    private fun task(title: String, year: Int?, tmdb: String?, imdb: String?, eps: List<Pair<Int, Int>>?, at: Long): String =
        buildJsonObject {
            put("title", title); year?.let { put("year", it) }
            tmdb?.let { put("tmdb", it) }; imdb?.let { put("imdb", it) }
            put("at", isoNow(at))
            if (eps != null) putJsonArray("eps") { eps.forEach { (sn, n) -> addJsonObject { put("s", sn); put("e", n) } } }
        }.toString()

    private fun targetOf(item: PlayableItem): String {
        val s = item.season; val e = item.episode
        return if (item.type == MediaType.EPISODE && s != null && e != null) "e:${item.seriesID ?: item.title}|$s-$e"
        else "m:${item.sourceID ?: ""}|${item.id}"
    }

    /** Lokaal (in het geheugen) als gezien/niet-gezien zetten, zodat het vinkje meteen klopt. */
    private fun applyLocal(title: String, year: Int?, tmdb: String?, imdb: String?, eps: List<Pair<Int, Int>>?, watched: Boolean) {
        val W = Build154Logic.WatchKeys
        if (eps == null) {
            val k = W.movieKeys(title, year, tmdb, imdb)
            watchedKeys = if (watched) watchedKeys + k else watchedKeys - W.lookupKeys("w:m:", title, year, tmdb, imdb).toSet() - k.toSet()
            imdb?.let { watchedMovies = if (watched) watchedMovies + it else watchedMovies - it }
        } else {
            val se = eps.map { "${it.first}-${it.second}" }
            val forms = if (watched) W.showForms(title, year, tmdb, imdb) else W.lookupKeys("", title, year, tmdb, imdb)
            val m = showEpisodes.toMutableMap()
            for (f in forms) { val cur = m[f] ?: if (watched) emptySet() else continue; m[f] = if (watched) cur + se else cur - se.toSet() }
            showEpisodes = m
            imdb?.let { i -> val cur = watchedEpisodes[i] ?: emptySet(); watchedEpisodes = watchedEpisodes + (i to if (watched) cur + se else cur - se.toSet()) }
        }
        saveCache()
    }

    private fun enqueue(add: Boolean, target: String, body: String, label: String, delayMs: Long = 0L) {
        val e = Build154Logic.Queue.Entry(java.util.UUID.randomUUID().toString(), if (add) "add" else "remove", target, body, label,
            nextAt = System.currentTimeMillis() + delayMs)
        synchronized(queueSync) { queue = Build154Logic.Queue.add(queue, e) }
        saveQueue()
        if (delayMs == 0L) AppScope.launch { flushQueue() }
    }

    /** Film of aflevering (af)vinken (via de wachtrij). */
    suspend fun setWatched(item: PlayableItem, watched: Boolean): Unit = withContext(Dispatchers.Main.immediate) {
        if (!isLoggedIn) return@withContext
        val s = item.season; val e = item.episode
        val eps = if (item.type == MediaType.EPISODE && s != null && e != null) listOf(s to e) else null
        val c = cleaned(item.title)
        val year = item.year ?: c.year ?: if (eps == null) item.subtitle?.trim()?.toIntOrNull() else null
        applyLocal(c.title, year, item.tmdbID, item.imdbID, eps, watched)
        val label = if (eps != null) "${item.displayTitle} S${s}E${e}" else item.displayTitle
        enqueue(watched, targetOf(item), task(c.title, year, item.tmdbID, item.imdbID, eps, System.currentTimeMillis()), label)
    }

    /** Hele seizoen of serie in één keer (af)vinken (via de wachtrij). */
    suspend fun setWatched(series: Series, imdb: String?, episodes: List<Episode>, watched: Boolean): Unit = withContext(Dispatchers.Main.immediate) {
        if (!isLoggedIn || episodes.isEmpty()) return@withContext
        val eps = episodes.map { it.season to it.number }
        val c = cleaned(series.title)
        val tm = Watched.tmdb(series)
        val im = imdb ?: series.imdbID
        applyLocal(c.title, series.year ?: c.year, tm, im, eps, watched)
        enqueue(watched, "s:${series.id}|" + eps.joinToString(",") { "${it.first}-${it.second}" },
            task(c.title, series.year ?: c.year, tm, im, eps, System.currentTimeMillis()), series.displayTitle)
    }

    /**
     * Build 154: Bingr markeerde iets lokaal als gezien (gezien-grens): ook naar Trakt via de wachtrij. Tijdens het kijken
     * wacht de opdracht op de scrobble-stop: telde Trakt het daar al als gezien (≥ 80%), dan vervalt hij (geen dubbele
     * keer in je geschiedenis); anders gaat hij meteen.
     */
    fun markedWatched(item: PlayableItem) {
        if (!isLoggedIn || !profileAllowed || item.type == MediaType.LIVE) return
        val s = item.season; val e = item.episode
        val eps = if (item.type == MediaType.EPISODE && s != null && e != null) listOf(s to e) else null
        val c = cleaned(item.title)
        val year = item.year ?: c.year ?: if (eps == null) item.subtitle?.trim()?.toIntOrNull() else null
        applyLocal(c.title, year, item.tmdbID, item.imdbID, eps, true)
        val label = if (eps != null) "${item.displayTitle} S${s}E${e}" else item.displayTitle
        enqueue(true, targetOf(item), task(c.title, year, item.tmdbID, item.imdbID, eps, System.currentTimeMillis()), label,
            delayMs = 10 * 60_000L)
    }

    private fun pending(target: String): Build154Logic.Queue.Entry? =
        synchronized(queueSync) { queue.firstOrNull { it.target == target && it.op == "add" } }

    /** Zet een titel op of van de Trakt-watchlist (spiegelt Mijn lijst van het hoofdaccount). */
    suspend fun setWatchlist(imdb: String, isShow: Boolean, on: Boolean) {
        if (!isLoggedIn || (imdb in watchlist) == on) return
        toggleWatchlist(imdb, isShow)
    }

    suspend fun toggleWatchlist(imdb: String, isShow: Boolean): Unit = withContext(Dispatchers.Main.immediate) {
        if (!isLoggedIn) return@withContext
        val add = imdb !in watchlist
        watchlist = if (add) watchlist + imdb else watchlist - imdb
        saveCache()
        val body = buildJsonObject {
            putJsonArray(if (isShow) "shows" else "movies") { addJsonObject { putJsonObject("ids") { put("imdb", imdb) } } }
        }
        try { post(if (add) "/sync/watchlist" else "/sync/watchlist/remove", body) }
        catch (ex: CancellationException) { throw ex } catch (ex: Exception) { lastError = Net.message(ex) }
        Unit
    }

    // MARK: wachtrij (build 154)

    private val queueSync = Any()
    private val queueFile: File get() = File(AppState.appContext.filesDir, "trakt-queue.txt")
    @Volatile private var queue: List<Build154Logic.Queue.Entry> = emptyList()
    @Volatile private var queueLoaded = false
    private val flushLock = Mutex()

    private fun ensureQueue() {
        if (queueLoaded) return
        synchronized(queueSync) {
            if (queueLoaded) return
            queue = runCatching { Build154Logic.Queue.load(queueFile) }.getOrDefault(emptyList())
            queueLoaded = true
        }
    }

    private fun saveQueue() {
        val q = synchronized(queueSync) { queue }
        AppScope.launch(Dispatchers.IO) { runCatching { synchronized(queueSync) { Build154Logic.Queue.save(queueFile, q) } } }
    }

    /** Aantal opdrachten dat nog naar Trakt moet (diagnose). */
    val queued: Int get() { ensureQueue(); return queue.size }

    /**
     * Wachtrij versturen: bij de start, bij terugkeer naar de voorgrond, na een verversing en elke 15 minuten. Backoff, en
     * `Retry-After` bij een 429. Het antwoord wordt gelezen: wat Trakt als `not_found` teruggeeft, komt in de diagnose en
     * telt niet als gelukt.
     */
    suspend fun flushQueue(wake: Boolean = false) {
        ensureQueue()
        if (wake) { synchronized(queueSync) { queue = Build154Logic.Queue.wakeAll(queue) }; saveQueue() }
        if (!isLoggedIn || !profileAllowed) return
        if (!flushLock.tryLock()) return
        try {
            val Q = Build154Logic.Queue
            for (e in Q.due(synchronized(queueSync) { queue }, System.currentTimeMillis())) {
                val t = runCatching { Http.json.parseToJsonElement(e.body) }.getOrNull() as? JsonObject
                if (t == null) { synchronized(queueSync) { queue = Q.done(queue, e.id) }; continue }
                val eps = t["eps"]?.jItems?.mapNotNull { x -> val sn = x.jI("s"); val n = x.jI("e"); if (sn != null && n != null) sn to n else null }
                val title = t.jS("title") ?: ""
                var resolveFailed = false
                val ids = try { resolveIds(title, t.jI("year"), t.jS("tmdb"), t.jS("imdb"), eps != null) }
                    catch (ex: CancellationException) { throw ex } catch (ex: Exception) { resolveFailed = true; null }
                if (ids == null) {
                    if (resolveFailed) synchronized(queueSync) { queue = Q.failed(queue, e.id, System.currentTimeMillis()) }
                    else {
                        // zonder id en zonder passende zoektreffer: niet als "gelukt", wel in de diagnose
                        withContext(Dispatchers.Main) {
                            diagnostics = L("Not found on Trakt: %@", e.label)
                            lastScrobble = diagnostics; lastScrobbleDate = System.currentTimeMillis()
                        }
                        synchronized(queueSync) { queue = Q.done(queue, e.id) }
                    }
                    saveQueue()
                    continue
                }
                val add = e.op == "add"
                val body = historyBody(ids, eps, add, t.jS("at") ?: isoNow())
                val r = try { postRaw(if (add) "/sync/history" else "/sync/history/remove", body) }
                    catch (ex: CancellationException) { throw ex } catch (ex: Exception) { null }
                when {
                    r == null -> synchronized(queueSync) { queue = Q.failed(queue, e.id, System.currentTimeMillis()) }
                    r.code == 429 -> synchronized(queueSync) { queue = Q.failed(queue, e.id, System.currentTimeMillis(), r.retryAfter) }
                    r.ok || r.code == 409 -> {
                        val j = runCatching { Http.json.parseToJsonElement(r.body) }.getOrNull()
                        val nf = j.jO("not_found")
                        val missing = listOf("movies", "shows", "seasons", "episodes").sumOf { k -> (nf as? JsonObject)?.get(k).jItems.size }
                        withContext(Dispatchers.Main) {
                            if (missing > 0) diagnostics = L("Not found on Trakt: %@", e.label)
                            else if (add) { lastScrobble = L("Marked as watched on Trakt: %@", e.label); lastScrobbleDate = System.currentTimeMillis() }
                            if (missing > 0) { lastScrobble = diagnostics; lastScrobbleDate = System.currentTimeMillis() }
                        }
                        synchronized(queueSync) { queue = Q.done(queue, e.id) }
                    }
                    r.code in 500..599 || r.code == 401 || r.code == 403 -> synchronized(queueSync) { queue = Q.failed(queue, e.id, System.currentTimeMillis()) }
                    else -> {
                        // 400/404/422: niet opnieuw (zou altijd mislukken), wel in de diagnose
                        withContext(Dispatchers.Main) { diagnostics = L("Trakt refused the connection (%d).", r.code) }
                        synchronized(queueSync) { queue = Q.done(queue, e.id) }
                    }
                }
                saveQueue()
            }
        } finally {
            flushLock.unlock()
        }
    }

    // MARK: voorgrond / achtergrond (build 154)

    private var loopJob: Job? = null

    /** App in beeld: wachtrij opnieuw, ophalen (eerst /sync/last_activities), en elke 15 minuten zolang de app open is. */
    fun onForeground() {
        if (!isLoggedIn || !profileAllowed) return
        AppScope.launch { runCatching { flushQueue(wake = true) }; runCatching { refresh() } }
        loopJob?.cancel()
        loopJob = AppScope.launch {
            while (true) {
                delay(15 * 60_000L)
                if (!isLoggedIn || !profileAllowed) continue
                runCatching { flushQueue() }
                // niet tijdens het afspelen (geen werk naast de stream)
                if (PlayerController.current == null) runCatching { refresh() }
            }
        }
    }

    fun onBackground() {
        loopJob?.cancel(); loopJob = null
    }

    /**
     * Scrobble. [progress] 0…1. Build 154: dezelfde gezien-grens als lokaal. Telt Bingr het (nog) niet als gezien, dan gaat
     * een stop hooguit tot 79,9% (Trakt markeert pas vanaf 80%); telde Bingr het wel (opdracht in de wachtrij), dan de echte
     * positie, en telde Trakt het, dan vervalt de opdracht. Anders gaat de opdracht meteen. Na een stop worden de vinkjes
     * 3 seconden later opnieuw opgehaald.
     */
    suspend fun scrobble(a: Scrobble, item: PlayableItem, progress: Double): ScrobbleResult = withContext(Dispatchers.Main.immediate) {
        if (!isLoggedIn || !scrobbleEnabled || item.type == MediaType.LIVE) return@withContext ScrobbleResult.SKIPPED
        val s = item.season; val e = item.episode
        val isEpisode = item.type == MediaType.EPISODE && s != null && e != null
        val c = cleaned(item.title)
        val year = item.year ?: c.year ?: if (!isEpisode) item.subtitle?.trim()?.toIntOrNull() else null
        val ids = try { resolveIds(c.title, year, item.tmdbID, item.imdbID, isEpisode) }
            catch (ex: CancellationException) { throw ex } catch (ex: Exception) { null }
        val target = targetOf(item)
        if (ids == null) {
            lastScrobble = L("Not found on Trakt: %@", item.displayTitle)
            lastScrobbleDate = System.currentTimeMillis()
            if (a == Scrobble.STOP) AppScope.launch { flushQueue(wake = true) }
            return@withContext ScrobbleResult.NOT_FOUND
        }
        val queuedWatch = pending(target)
        var p = (progress * 100).coerceIn(0.0, 100.0)
        if (a == Scrobble.STOP && queuedWatch == null) p = p.coerceAtMost(79.9)
        val body = buildJsonObject {
            put("progress", p)
            put("app_version", "1.0.3")
            if (isEpisode) {
                put("show", buildJsonObject { put("ids", ids.json()) })
                putJsonObject("episode") { put("season", s); put("number", e) }
            } else {
                put("movie", buildJsonObject { put("ids", ids.json()) })
            }
        }
        val name = if (isEpisode) "${item.displayTitle} S${s}E${e}" else item.displayTitle
        var result = ScrobbleResult.OK
        var countedByTrakt = false
        try {
            val r = postRaw("/scrobble/${a.name.lowercase()}", body)
            when {
                r.ok -> countedByTrakt = a == Scrobble.STOP && p >= 80.0 &&
                    runCatching { Http.json.parseToJsonElement(r.body).jS("action") }.getOrNull() == "scrobble"
                r.code == 409 -> countedByTrakt = a == Scrobble.STOP && p >= 80.0   // net al gescrobbeld
                r.code == 404 -> result = ScrobbleResult.NOT_FOUND
                else -> { result = ScrobbleResult.FAILED; lastError = Net.message(Net.statusError(r.code, r.body)) }
            }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            result = ScrobbleResult.FAILED
            lastError = Net.message(ex)
        }
        if (a == Scrobble.STOP && queuedWatch != null) {
            if (countedByTrakt) { synchronized(queueSync) { queue = Build154Logic.Queue.done(queue, queuedWatch.id) }; saveQueue() }
            else AppScope.launch { flushQueue(wake = true) }
        }
        val watched = queuedWatch != null
        when {
            result == ScrobbleResult.FAILED -> lastScrobble = L("Could not reach Trakt for %@", name)
            result == ScrobbleResult.NOT_FOUND -> lastScrobble = L("Not found on Trakt: %@", name)
            a == Scrobble.STOP -> lastScrobble =
                if (watched) L("Marked as watched on Trakt: %@", name) else L("Progress saved on Trakt: %@", name)
            a == Scrobble.START -> lastScrobble = L("Watching on Trakt: %@", name)
            else -> {}
        }
        lastScrobbleDate = System.currentTimeMillis()
        // na het kijken de vinkjes van Trakt ophalen (ook wat je intussen elders keek); niet wachten op het resultaat
        if (a == Scrobble.STOP) {
            AppScope.launch {
                delay(3_000)
                runCatching { refresh(force = true) }
            }
        }
        result
    }

    /** Waardering 1–10 naar Trakt sturen. */
    suspend fun rate(item: PlayableItem, rating: Int) {
        if (!isLoggedIn) return
        val imdb = resolveIMDb(item) ?: return
        val s = item.season; val e = item.episode
        val body = if (item.type == MediaType.EPISODE && s != null && e != null) buildJsonObject {
            putJsonArray("shows") { addJsonObject {
                putJsonObject("ids") { put("imdb", imdb) }
                putJsonArray("seasons") { addJsonObject {
                    put("number", s)
                    putJsonArray("episodes") { addJsonObject { put("number", e); put("rating", rating) } }
                } }
            } }
        } else buildJsonObject {
            putJsonArray("movies") { addJsonObject { putJsonObject("ids") { put("imdb", imdb) }; put("rating", rating) } }
        }
        runCatching { post("/sync/ratings", body) }
    }

    /** Verwijder een half-gekeken item uit Trakt's "verder kijken". */
    suspend fun removePlayback(id: Long) {
        playback = playback.filterNot { it.id == id }
        runCatching { delete("/sync/playback/$id") }
    }

    // MARK: metadata via Trakt (IMDb-id en trailer)

    suspend fun lookup(title: String, year: Int?, isShow: Boolean): TraktMedia? {
        if (!Secrets.isSet(Secrets.traktClientID)) return null
        val q = mutableListOf("query" to title, "extended" to "full", "limit" to "5")
        if (year != null) q.add("years" to "${year - 1}-${year + 1}")
        val hits = runCatching { get("/search/${if (isShow) "show" else "movie"}", q, auth = false) }.getOrNull()
        // build 154: niet blind de eerste treffer; alleen de juiste titel (norm) en het juiste jaar (±1)
        val list = hits.jItems.mapNotNull { h -> media(h.jO("movie") ?: h.jO("show")) }
        Build154Logic.Search.pick(title, year, list, { it.title }, { it.year })?.let { return it }
        return null
    }

    suspend fun lookup(imdb: String): TraktMedia? {
        if (!Secrets.isSet(Secrets.traktClientID)) return null
        val hits = runCatching { get("/search/imdb/$imdb", listOf("extended" to "full"), auth = false) }.getOrNull()
        return hits.jItems.firstOrNull()?.let { media(it.jO("movie") ?: it.jO("show")) }
    }

    /** YouTube-id van de trailer (via Trakt). */
    suspend fun trailer(imdb: String, isShow: Boolean): String? {
        val m = lookup(imdb) ?: return null
        return m.trailer?.let { Metadata.youtubeID(it) }
    }

    /** Profiel (naam + avatar) voor het Trakt-scherm. */
    suspend fun loadProfile() {
        if (!isLoggedIn) return
        val s = runCatching { get("/users/settings", listOf("extended" to "full")) }.getOrNull() ?: return
        val u = s.jO("user")
        displayName = u.jS("name")?.takeIf { it.isNotEmpty() } ?: u.jS("username")
        username = u.jS("username") ?: username
        avatar = Net.urlValue(u.jO("images").jO("avatar").jS("full"))
    }

    private fun titles(list: List<TraktMedia>): List<ExternalTitle> =
        list.mapNotNull { m -> m.title?.let { ExternalTitle(listOf(it), m.year) } }

    /** Titels die lijken op een film/serie (werkt ook zonder inloggen). */
    suspend fun relatedMedia(imdb: String, shows: Boolean): List<TraktMedia> {
        if (!Secrets.isSet(Secrets.traktClientID)) return emptyList()
        val r = runCatching { get("/${if (shows) "shows" else "movies"}/$imdb/related", listOf("limit" to "20"), auth = false) }.getOrNull()
        return r.jItems.mapNotNull { media(it) }
    }
    suspend fun related(imdb: String, shows: Boolean): List<ExternalTitle> = titles(relatedMedia(imdb, shows))

    /** Persoonlijke aanbevelingen van Trakt (alleen ingelogd). */
    suspend fun recommendationsMedia(shows: Boolean): List<TraktMedia> {
        if (!isLoggedIn) return emptyList()
        val r = runCatching {
            get(if (shows) "/recommendations/shows" else "/recommendations/movies", listOf("limit" to "50", "ignore_collected" to "false"))
        }.getOrNull()
        return r.jItems.mapNotNull { media(it) }
    }
    suspend fun recommendations(shows: Boolean): List<ExternalTitle> = titles(recommendationsMedia(shows))

    suspend fun trendingMedia(shows: Boolean): List<TraktMedia> {
        if (!Secrets.isSet(Secrets.traktClientID)) return emptyList()
        val r = runCatching {
            get(if (shows) "/shows/trending" else "/movies/trending", listOf("limit" to "100"), auth = false)
        }.getOrNull()
        return r.jItems.mapNotNull { media(it.jO("movie") ?: it.jO("show")) }
    }
    suspend fun trending(shows: Boolean): List<ExternalTitle> = titles(trendingMedia(shows))

    // MARK: cache (zodat vinkjes direct zichtbaar zijn bij opstarten)

    @Serializable
    private data class Cache(val m: Set<String>, val e: Map<String, Set<String>>, val w: Set<String>, val a: String? = null,
                             val t: TitleIndex? = null,
                             /** build 154: inhoudssleutels, show-vormen en aired_episodes; [v] < 154 = opnieuw ophalen */
                             val k: Set<String> = emptySet(), val se: Map<String, Set<String>> = emptyMap(),
                             val ai: Map<String, Int> = emptyMap(), val v: Int = 0)

    private val cacheFile: File get() = File(AppState.appContext.cacheDir, "trakt-cache.json")

    private fun saveCache() {
        val c = Cache(watchedMovies, watchedEpisodes, watchlist, lastActivity, titleIndex, watchedKeys, showEpisodes, aired, 154)
        AppScope.launch(Dispatchers.IO) {
            runCatching { cacheFile.writeText(Http.json.encodeToString(Cache.serializer(), c)) }
        }
    }

    private fun loadCache() {
        if (!isLoggedIn) return
        val c = runCatching { Http.json.decodeFromString(Cache.serializer(), cacheFile.readText()) }.getOrNull() ?: return
        watchedMovies = c.m; watchedEpisodes = c.e; watchlist = c.w; titleIndex = c.t ?: TitleIndex()
        watchedKeys = c.k; showEpisodes = c.se; aired = c.ai
        // cache van vóór build 154 (zonder inhoudssleutels): de volgende keer alles ophalen
        lastActivity = if (c.v >= 154) c.a else null
    }
}
