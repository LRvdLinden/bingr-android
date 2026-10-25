package nl.lrvdlinden.bingr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Xtream Codes player_api.php */
class XtreamClient(source: PlaylistSource, val password: String) {
    val server: String = Net.normalizedServer(source.serverURL)
    val username: String = source.username
    val preferHLS: Boolean = source.preferHLS

    private fun api(action: String? = null, extra: List<Pair<String, String>> = emptyList()): String? {
        val q = mutableListOf("username" to username, "password" to password)
        if (action != null) q.add("action" to action)
        q.addAll(extra)
        return Net.url("$server/player_api.php", q)
    }

    companion object {
        // build 149: alleen deze velden per item (zelfde als Xtream.KEYS in tizen/js/providers.js)
        val KEYS_LIVE = listOf("stream_id", "name", "stream_icon", "category_id", "epg_channel_id", "num", "tv_archive", "tv_archive_duration")
        val KEYS_VOD = listOf("stream_id", "name", "stream_icon", "category_id", "container_extension", "rating", "added", "year")
        val KEYS_SERIES = listOf("series_id", "name", "year", "releaseDate", "cover", "category_id", "plot", "rating", "last_modified")

        val authError: ServiceException
            get() = ServiceException(L("Couldn't sign in to the server. Check the server, username and password."))

        /** Afleveringen per seizoen: object {"1": [...]} of (bij sommige panelen) een lijst met lijsten. */
        fun episodeGroups(info: JsonElement?): List<Pair<String, List<JsonElement>>> =
            when (val eps = info.jObj?.get("episodes")) {
                // {"1": [ep, ep]} of {"1": {"0": ep, "1": ep}}
                is JsonObject -> eps.entries.map { (k, v) ->
                    k to when (v) {
                        is JsonObject -> v.entries.sortedBy { it.key.toIntOrNull() ?: 0 }.map { it.value }
                        else -> v.jItems
                    }
                }
                is JsonArray -> eps.mapIndexed { i, v ->
                    when (v) {
                        is JsonArray -> (i + 1).toString() to v.toList()
                        else -> (v.jS("season") ?: (i + 1).toString()) to listOf(v)
                    }
                }
                else -> emptyList()
            }
    }

    // MARK: calls

    suspend fun authenticate() {
        val u = api() ?: throw Net.badURL()
        val r = Net.json(u)
        if (r.jO("user_info").jI("auth") != 1) throw authError
        ProviderConnections.from(r.jO("user_info"))?.let { ProviderLimit.store(server, username, it) }
    }

    /**
     * Verbindingslimiet van de provider (user_info.max_connections / active_cons), of null als de server die niet
     * meestuurt. Korte time-out: alleen om bij een mislukte stream de juiste foutmelding te kiezen.
     */
    suspend fun connections(): ProviderConnections? {
        val u = api() ?: return null
        val r = runCatching { withTimeoutOrNull(6_000) { Net.json(u) } }.getOrNull() ?: return null
        val c = ProviderConnections.from(r.jO("user_info")) ?: return null
        ProviderLimit.store(server, username, c)
        return c
    }

    /**
     * Build 147 (diagnose): korte foutcode per mislukte lijst (`get_series` → "timeout", "http_500", …; nooit een URL).
     * Een mislukte lijst blijft leeg en raakt de andere lijsten niet (ze worden los en parallel opgehaald).
     */
    val errors = java.util.concurrent.ConcurrentHashMap<String, String>()

    private suspend fun list(action: String): List<JsonElement> {
        val u = api(action) ?: return emptyList()
        return try {
            Net.json(u).jItems.also { errors.remove(action) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            errors[action] = Build147Logic.Fetch.errorCode(e.javaClass.simpleName, e.message, (e as? StatusException)?.code)
            emptyList()
        }
    }

    /** Build 147: foutcode per soort (live, movie, series) van de laatste [rawLists]; de lijst zelf telt, niet de categorieën. */
    fun kindErrors(): Map<String, String> = buildMap {
        errors["get_live_streams"]?.let { put("live", it) }
        errors["get_vod_streams"]?.let { put("movie", it) }
        errors["get_series"]?.let { put("series", it) }
    }

    /** Ruwe Xtream-lijsten (zoals de server ze stuurt), voor [parse] en het bron-controlescherm. */
    data class RawLists(
        val liveCats: List<JsonElement>, val live: List<JsonElement>,
        val vodCats: List<JsonElement>, val vod: List<JsonElement>,
        val serCats: List<JsonElement>, val ser: List<JsonElement>,
    )

    /** Volledig antwoord van de inlog-aanroep (user_info/server_info), voor het bron-controlescherm. */
    suspend fun accountInfo(): JsonElement {
        val u = api() ?: throw Net.badURL()
        val r = Net.json(u)
        if (r.jO("user_info").jI("auth") != 1) throw authError
        return r
    }

    /** De zes lijsten parallel ophalen (een mislukte lijst = leeg). */
    suspend fun rawLists(): RawLists = coroutineScope {
        val liveCats = async { list("get_live_categories") }
        val live = async { list("get_live_streams") }
        val vodCats = async { list("get_vod_categories") }
        val vod = async { list("get_vod_streams") }
        val serCats = async { list("get_series_categories") }
        val ser = async { list("get_series") }
        RawLists(liveCats.await(), live.await(), vodCats.await(), vod.await(), serCats.await(), ser.await())
    }

    /**
     * Live, films en series in één keer (parallel); omzetten op Dispatchers.Default. Series-info pas bij openen ([episodes]).
     * Build 148: elke lijst wordt meteen na het ophalen omgezet en de ruwe JSON daarna losgelaten (niet eerst alle zes de
     * ruwe lijsten tegelijk in het geheugen, dan pas omzetten). [onFetched] = alle lijsten zijn binnen (einde van "ophalen").
     */
    suspend fun library(onFetched: () -> Unit = {}, hidden: ((String) -> Boolean)? = null): Library = withContext(Dispatchers.Default) {
        coroutineScope {
            val arrived = java.util.concurrent.atomic.AtomicInteger(0)
            fun got() { if (arrived.incrementAndGet() == 3) runCatching { onFetched() } }
            val lc = async { categoryMap(list("get_live_categories")) }
            val vc = async { categoryMap(list("get_vod_categories")) }
            val sc = async { categoryMap(list("get_series_categories")) }
            val other = L("Other")
            val liveExt = if (preferHLS) "m3u8" else "ts"
            // build 149: streamend per item inlezen (alleen de nodige velden, geen JSON-boom van de hele lijst); items uit
            // verborgen categorieën worden alleen geteld (Library.hiddenGroups) en pas bij weer aanzetten opgehaald
            val skipL = hidden?.let { Build149Logic.Skip(it) }
            val skipM = hidden?.let { Build149Logic.Skip(it) }
            val skipS = hidden?.let { Build149Logic.Skip(it) }
            val ch = async { val t = listText("get_live_streams"); got(); streamLive(t, lc.await(), other, liveExt, skipL) }
            val mv = async { val t = listText("get_vod_streams"); got(); streamVod(t, vc.await(), other, skipM) }
            val se = async { val t = listText("get_series"); got(); streamSeries(t, sc.await(), other, skipS) }
            val hg = LinkedHashMap<String, Int>()
            val channels = ch.await(); val movies = mv.await(); val series = se.await()
            skipL?.counts?.forEach { (g, n) -> hg["live|$g"] = n }
            skipM?.counts?.forEach { (g, n) -> hg["movie|$g"] = n }
            skipS?.counts?.forEach { (g, n) -> hg["series|$g"] = n }
            Library(channels = channels, movies = movies, series = series, hiddenGroups = hg)
        }
    }

    /** Build 149: ruwe tekst van een lijst (een mislukte lijst = null, met de foutcode in [errors]). */
    private suspend fun listText(action: String): String? {
        val u = api(action) ?: return null
        return try {
            Net.text(u).also { errors.remove(action) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            errors[action] = Build147Logic.Fetch.errorCode(e.javaClass.simpleName, e.message, (e as? StatusException)?.code)
            null
        }
    }

    private fun stream(text: String?, action: String, keys: List<String>, block: (Build149Logic.Row) -> Unit) {
        if (text.isNullOrEmpty()) return
        try {
            Build149Logic.Stream.forEach(text, keys, MemoryGuard.check, block)
        } catch (e: Build149Logic.TooLarge) {
            throw MemoryGuard.tooLarge()
        } catch (e: IllegalArgumentException) {
            errors[action] = "bad_json"
        }
    }

    private fun streamLive(text: String?, lcMap: Map<String, String>, other: String, liveExt: String, skip: Build149Logic.Skip?): List<Channel> {
        val out = ArrayList<Channel>()
        stream(text, "get_live_streams", KEYS_LIVE) { l ->
            val sid = l["stream_id"] ?: return@stream
            val group = lcMap[l["category_id"] ?: ""] ?: other
            if (skip != null && skip.skip(group)) return@stream
            out += Channel(id = "x$sid", name = l["name"] ?: "Channel", logo = Net.urlValue(l["stream_icon"]), group = group,
                streamURL = "$server/live/$username/$password/$sid.$liveExt",
                epgID = l["epg_channel_id"]?.takeIf { it.isNotEmpty() },
                number = l.int("num"),
                catchupDays = if (l.int("tv_archive") == 1) (l.int("tv_archive_duration") ?: 0) else 0)
        }
        out.trimToSize(); return out
    }

    private fun streamVod(text: String?, vcMap: Map<String, String>, other: String, skip: Build149Logic.Skip?): List<Movie> {
        val out = ArrayList<Movie>()
        stream(text, "get_vod_streams", KEYS_VOD) { v ->
            val sid = v["stream_id"] ?: return@stream
            val group = vcMap[v["category_id"] ?: ""] ?: other
            if (skip != null && skip.skip(group)) return@stream
            val p = TitleCleaner.parse(v["name"] ?: "")
            out += Movie(id = "xv$sid", title = p.title, year = v.int("year") ?: p.year, poster = Net.urlValue(v["stream_icon"]),
                group = group,
                streamURL = "$server/movie/$username/$password/$sid.${v["container_extension"] ?: "mp4"}",
                rating = v.double("rating"),
                addedAt = v.double("added")?.let { (it * 1000).toLong() },
                xtreamVodID = sid.toIntOrNull())
        }
        out.trimToSize(); return out
    }

    private fun streamSeries(text: String?, scMap: Map<String, String>, other: String, skip: Build149Logic.Skip?): List<Series> {
        val out = ArrayList<Series>()
        stream(text, "get_series", KEYS_SERIES) { s ->
            val sid = s.int("series_id") ?: return@stream
            val group = scMap[s["category_id"] ?: ""] ?: other
            if (skip != null && skip.skip(group)) return@stream
            val p = TitleCleaner.parse(s["name"] ?: "")
            val y = s.int("year") ?: s["releaseDate"]?.take(4)?.toIntOrNull() ?: p.year
            out += Series(id = "xs$sid", title = p.title, year = y, poster = Net.urlValue(s["cover"]), group = group,
                plot = s["plot"], rating = s.double("rating"), xtreamSeriesID = sid,
                addedAt = s.double("last_modified")?.takeIf { it > 0 }?.let { (it * 1000).toLong() })
        }
        out.trimToSize(); return out
    }

    /** Categorie-id → naam (zoals [parse] ze gebruikt). */
    fun categoryMap(cats: List<JsonElement>): Map<String, String> {
        val other = L("Other")
        val m = HashMap<String, String>()
        for (c in cats) { val id = c.jS("category_id") ?: continue; if (id !in m) m[id] = c.jS("category_name") ?: other }
        return m
    }

    /** Ruwe lijsten omzetten naar een [Library] (zwaar: buiten de main thread aanroepen). */
    fun parse(raw: RawLists): Library {
        val other = L("Other")
        val liveExt = if (preferHLS) "m3u8" else "ts"
        return Library(channels = parseLive(raw.live, categoryMap(raw.liveCats), other, liveExt),
            movies = parseVod(raw.vod, categoryMap(raw.vodCats), other), series = parseSeries(raw.ser, categoryMap(raw.serCats), other))
    }

    private fun parseLive(list: List<JsonElement>, lcMap: Map<String, String>, other: String, liveExt: String): List<Channel> =
        list.mapNotNull { l ->
            val sid = l.jS("stream_id") ?: return@mapNotNull null
            Channel(id = "x$sid", name = l.jS("name") ?: "Channel", logo = Net.urlValue(l.jS("stream_icon")),
                group = lcMap[l.jS("category_id") ?: ""] ?: other,
                streamURL = "$server/live/$username/$password/$sid.$liveExt",
                epgID = l.jS("epg_channel_id")?.takeIf { it.isNotEmpty() },
                number = l.jI("num"),
                catchupDays = if (l.jI("tv_archive") == 1) (l.jI("tv_archive_duration") ?: 0) else 0)
        }

    private fun parseVod(list: List<JsonElement>, vcMap: Map<String, String>, other: String): List<Movie> =
        list.mapNotNull { v ->
            val sid = v.jS("stream_id") ?: return@mapNotNull null
            val p = TitleCleaner.parse(v.jS("name") ?: "")
            Movie(id = "xv$sid", title = p.title, year = v.jI("year") ?: p.year, poster = Net.urlValue(v.jS("stream_icon")),
                group = vcMap[v.jS("category_id") ?: ""] ?: other,
                streamURL = "$server/movie/$username/$password/$sid.${v.jS("container_extension") ?: "mp4"}",
                rating = v.jD("rating"),
                addedAt = v.jD("added")?.let { (it * 1000).toLong() },
                xtreamVodID = sid.toIntOrNull())
        }

    private fun parseSeries(list: List<JsonElement>, scMap: Map<String, String>, other: String): List<Series> =
        list.mapNotNull { s ->
            val sid = s.jI("series_id") ?: return@mapNotNull null
            val p = TitleCleaner.parse(s.jS("name") ?: "")
            val y = s.jI("year") ?: s.jS("releaseDate")?.take(4)?.toIntOrNull() ?: p.year
            Series(id = "xs$sid", title = p.title, year = y, poster = Net.urlValue(s.jS("cover")),
                group = scMap[s.jS("category_id") ?: ""] ?: other,
                plot = s.jS("plot"), rating = s.jD("rating"), xtreamSeriesID = sid,
                addedAt = s.jD("last_modified")?.takeIf { it > 0 }?.let { (it * 1000).toLong() })
        }

    suspend fun episodes(series: Series): List<Episode> {
        val sid = series.xtreamSeriesID ?: return emptyList()
        val u = api("get_series_info", listOf("series_id" to sid.toString())) ?: return emptyList()
        val info = Net.json(u)
        val out = ArrayList<Episode>()
        val seen = HashSet<String>()
        for ((seasonKey, eps) in episodeGroups(info)) {
            for (e in eps) {
                val eid = e.jS("id") ?: e.jS("stream_id") ?: continue
                if (!seen.add(eid)) continue
                val i = e.jO("info")
                out.add(Episode(id = "xe$eid", seriesID = series.id,
                    season = e.jI("season") ?: seasonKey.toIntOrNull() ?: 1,
                    number = e.jI("episode_num") ?: 0,
                    title = e.jS("title") ?: e.jS("name") ?: L("Episode"),
                    streamURL = "$server/series/$username/$password/$eid.${e.jS("container_extension") ?: "mp4"}",
                    still = Net.urlValue(i.jS("movie_image")), plot = i.jS("plot"),
                    durationSeconds = i.jI("duration_secs")))
            }
        }
        return out.sortedWith(compareBy<Episode>({ it.season }, { it.number }))
    }

    /** Ruwe get_series_info (voor de detailpagina). */
    suspend fun seriesInfo(seriesID: Int): JsonObject? {
        val u = api("get_series_info", listOf("series_id" to seriesID.toString())) ?: return null
        return runCatching { Net.json(u).jObj }.getOrNull()
    }

    /** "info" uit get_vod_info (voor de detailpagina). */
    suspend fun vodInfo(vodID: Int): JsonObject? {
        val u = api("get_vod_info", listOf("vod_id" to vodID.toString())) ?: return null
        return runCatching { Net.json(u).jO("info") }.getOrNull()
    }

    val xmltvURL: String? get() = Net.url("$server/xmltv.php", listOf("username" to username, "password" to password))

    /** Catch-up / terugkijken (tv_archive). */
    fun catchupURL(channel: Channel, start: Long, end: Long): String? {
        if (!channel.id.startsWith("x")) return null
        val sid = channel.id.drop(1)
        val f = Build149Logic.Dates.fmt("yyyy-MM-dd:HH-mm")
        val minutes = ((end - start) / 60_000).toInt()
        return "$server/timeshift/$username/$password/$minutes/${f.format(java.util.Date(start))}/$sid.ts"
    }
}

/**
 * Limiet van de IPTV-provider (Xtream). Staat los van de streamlimiet van Bingr (eigen telling op de server, zie
 * CONTRACT.md); die wordt hier nooit uit afgeleid. Alleen om bij een mislukte stream te kunnen zeggen dat de provider
 * vol zit, in plaats van een algemene fout. Niet vooraf blokkeren: active_cons loopt bij providers achter.
 */
data class ProviderConnections(
    /** max_connections; 0 = onbekend of onbeperkt */
    val max: Int,
    /** active_cons */
    val active: Int,
) {
    /** Alle verbindingen van de provider in gebruik */
    val atLimit: Boolean get() = max > 0 && active >= max

    /** Melding als het afspelen mislukt en de provider vol zit */
    val message: String
        get() = if (max == 1)
            L("Your IPTV subscription allows 1 connection at a time and it is in use. This is a limit of your provider, not of Bingr.")
        else L("Your IPTV subscription allows up to %d connections at the same time and they are all in use. This is a limit of your provider, not of Bingr.", max)

    companion object {
        fun from(userInfo: JsonObject?): ProviderConnections? {
            val m = userInfo.jI("max_connections") ?: return null
            return ProviderConnections(m, userInfo.jI("active_cons") ?: 0)
        }
    }
}

/** Laatst bekende providerlimiet per server + gebruikersnaam (geheugen, niet bewaard). */
object ProviderLimit {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<ProviderConnections, Long>>()

    fun store(server: String, username: String, c: ProviderConnections) {
        cache["$server|$username"] = c to System.currentTimeMillis()
    }

    private fun client(s: PlaylistSource?): XtreamClient? =
        s?.takeIf { it.kind == PlaylistKind.XTREAM }?.let { XtreamClient(it, Store.secret(it.passwordKey) ?: "") }

    /** Vóór het afspelen: de limiet van de provider alvast ophalen (alleen Xtream; blokkeert niets). */
    suspend fun prime(s: PlaylistSource?) {
        val x = client(s) ?: return
        val t = cache["${x.server}|${x.username}"]?.second
        if (t != null && System.currentTimeMillis() - t < 60_000) return
        x.connections()
    }

    /**
     * Na een mislukte stream: opnieuw controleren. Geeft de melding als alle verbindingen van de provider in gebruik
     * zijn, anders null (dan blijft de gewone foutmelding staan). M3U e.d.: altijd null.
     */
    suspend fun failureMessage(s: PlaylistSource?): String? {
        val x = client(s) ?: return null
        val c = x.connections() ?: cache["${x.server}|${x.username}"]?.first ?: return null
        return if (c.atLimit) c.message + "\n\n" + tip else null
    }

    /** Tip onder de providermelding: een net gesloten verbinding telt bij de provider vaak nog even mee. */
    val tip: String
        get() = L("Stop the stream on your other device, or wait a moment: your provider sometimes only releases a connection you just closed after a minute.")
}
