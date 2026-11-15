package nl.lrvdlinden.bingr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.ConcurrentHashMap

// Build 178: club- en competitielogo's op de Sport-pagina, zoals Weekdot. Niets in de app: logo's worden live opgehaald
// (maak-agenda.nl, ESPN, TheSportsDB, favicons via Google) en in cacheDir/sportlogos bewaard (indexen `crests2-….json`,
// zoekresultaten `crest-search-….json`, afbeeldingen `img-…`, niet gevonden `miss-…`). Pure regels: [SportLogos178Logic].
// Zolang een logo laadt of ontbreekt: de cirkel met initialen. Data besparen (176) op mobiel: alleen uit de cache.

/** Wat er getoond wordt. */
sealed class SportArt {
    /** Competitie (Build173Logic-id); [small] = lijstje (favicon eerst). */
    data class Competition(val id: String, val small: Boolean) : SportArt()
    /** Team in een wedstrijd: vlag (landenteam), clublogo of niets. */
    data class Team(val name: String, val sport: String, val competitionId: String) : SportArt()
}

object SportLogos {
    private val G = SportLogos178Logic
    private val dir: File by lazy { File(AppState.appContext.cacheDir, "sportlogos").apply { mkdirs() } }
    private val bitmaps = ConcurrentHashMap<SportArt, ImageBitmap>()
    private val images = ConcurrentHashMap<String, ByteArray>()
    private val misses = ConcurrentHashMap.newKeySet<String>()
    private val indexes = ConcurrentHashMap<String, Map<String, String>>()
    private val crests = ConcurrentHashMap<String, String>()
    private val inflight = HashMap<String, Deferred<Any?>>()

    /** Nieuwe logo's ophalen mag niet met Data besparen op een mobiel netwerk. */
    fun network(): Boolean = !runCatching { DataSaver.active() }.getOrDefault(false)

    fun memory(art: SportArt): ImageBitmap? = bitmaps[art]

    // MARK: schijf

    private fun read(name: String, kind: String, saver: Boolean): ByteArray? = runCatching {
        val f = File(dir, name)
        if (!f.exists()) return null
        val age = (System.currentTimeMillis() - f.lastModified()) / 1000.0
        if (!G.usable(kind, age, saver)) null else f.readBytes()
    }.getOrNull()

    private fun write(name: String, data: ByteArray) {
        runCatching {
            val tmp = File(dir, "$name.tmp")
            tmp.writeBytes(data)
            tmp.renameTo(File(dir, name))
        }
    }

    /** Eén keer tegelijk per sleutel ophalen; de rest wacht op hetzelfde resultaat. */
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> once(key: String, work: suspend () -> T): T {
        val d = synchronized(inflight) {
            inflight[key] ?: AppScope.async(Dispatchers.IO) { work() as Any? }.also { inflight[key] = it }
        }
        return try { d.await() as T } finally { synchronized(inflight) { if (inflight[key] === d) inflight.remove(key) } }
    }

    // MARK: openbaar

    /** Logo als bitmap (of null); haalt op als het netwerk het toestaat. */
    suspend fun bitmap(art: SportArt): ImageBitmap? {
        bitmaps[art]?.let { return it }
        val net = network()
        val bytes = withContext(Dispatchers.IO) { data(art, net) } ?: return null
        val bmp = withContext(Dispatchers.Default) { runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() }
            ?: return null
        return bmp.asImageBitmap().also { bitmaps[art] = it }
    }

    /** Alleen uit de cache (geen netwerk), voor de melding. */
    fun cachedBitmapBlocking(art: SportArt): Bitmap? = runCatching {
        val urls = urlsCached(art)
        for (u in urls) {
            val b = images[u] ?: read(G.cacheName("img", u, ""), "image", true) ?: continue
            BitmapFactory.decodeByteArray(b, 0, b.size)?.let { return it }
        }
        null
    }.getOrNull()

    private fun urlsCached(art: SportArt): List<String> = when (art) {
        is SportArt.Competition -> G.competitionURLs(art.id, art.small)
        is SportArt.Team -> {
            val kind = G.teamArt(art.name, art.sport, art.competitionId)
            when {
                kind.startsWith("flag:") -> listOf(G.flagURL(kind.removePrefix("flag:")))
                kind == "club" -> {
                    val wanted = G.keys(art.name)
                    val hit = G.espnLeagues(art.sport, art.competitionId).firstNotNullOfOrNull { l ->
                        (indexes[l] ?: readIndex(G.cacheName("crests2", l, "json"), true))?.let { G.match(wanted, it) }
                    } ?: wanted.firstOrNull()?.let { k -> readIndex(G.cacheName("crest-search", k, "json"), true)?.let { G.match(wanted, it) } }
                    listOfNotNull(hit)
                }
                else -> emptyList()
            }
        }
    }

    private suspend fun data(art: SportArt, net: Boolean): ByteArray? {
        for (u in urls(art, net)) image(u, net)?.let { return it }
        return null
    }

    private suspend fun urls(art: SportArt, net: Boolean): List<String> = when (art) {
        is SportArt.Competition -> G.competitionURLs(art.id, art.small)
        is SportArt.Team -> {
            val kind = G.teamArt(art.name, art.sport, art.competitionId)
            when {
                kind.startsWith("flag:") -> listOf(G.flagURL(kind.removePrefix("flag:")))
                kind == "club" -> listOfNotNull(crest(art.name, art.sport, art.competitionId, net))
                else -> emptyList()
            }
        }
    }

    // MARK: afbeeldingen

    private suspend fun image(address: String, net: Boolean): ByteArray? {
        images[address]?.let { return it }
        if (address in misses) return null
        val file = G.cacheName("img", address, "")
        val miss = G.cacheName("miss", address, "")
        read(file, "image", !net)?.let { images[address] = it; return it }
        if (read(miss, "miss", !net) != null) { misses += address; return null }
        if (!net) return null
        val got = once("img|$address") {
            runCatching {
                val r = Http.request(address, raw = true)
                val b = r.bytes
                if (r.ok && b != null && b.size > 64 && BitmapFactory.decodeByteArray(b, 0, b.size) != null) b else null
            }.getOrNull()
        }
        if (got != null) { images[address] = got; write(file, got) } else { misses += address; write(miss, byteArrayOf(1)) }
        return got
    }

    // MARK: clublogo's

    private suspend fun crest(name: String, sport: String, comp: String, net: Boolean): String? {
        val leagues = G.espnLeagues(sport, comp)
        val key = name + "|" + sport + "|" + leagues.joinToString(",")
        crests[key]?.let { return it }
        val wanted = G.keys(name)
        if (wanted.isEmpty()) return null
        for (l in leagues) {
            G.match(wanted, index(l, net))?.let { crests[key] = it; return it }
        }
        return search(name, sport, wanted, net)?.also { crests[key] = it }
    }

    private fun readIndex(file: String, saver: Boolean): Map<String, String>? {
        val b = read(file, "index", saver) ?: return null
        return runCatching {
            Http.json.parseToJsonElement(String(b, Charsets.UTF_8)).jsonObject.mapValues { (it.value as JsonPrimitive).content }
        }.getOrNull()
    }

    private fun writeIndex(file: String, index: Map<String, String>) {
        val o = buildJsonObject { index.forEach { (k, v) -> put(k, v) } }
        write(file, o.toString().toByteArray(Charsets.UTF_8))
    }

    /** ESPN-teamlijst van één competitie (7 dagen bewaard). */
    private suspend fun index(league: String, net: Boolean): Map<String, String> {
        indexes[league]?.let { return it }
        val file = G.cacheName("crests2", league, "json")
        val miss = G.cacheName("miss-crests2", league, "")
        readIndex(file, !net)?.let { indexes[league] = it; return it }
        if (read(miss, "miss", !net) != null) { indexes[league] = emptyMap(); return emptyMap() }
        if (!net) return emptyMap()
        val result: Map<String, String> = once("idx|$league") {
            runCatching {
                val root = Net.json(G.espnTeamsURL(league)) as JsonObject
                val teams = (((root["sports"] as JsonArray)[0] as JsonObject)["leagues"] as JsonArray)[0].jsonObject["teams"] as JsonArray
                G.buildIndex(teams.mapNotNull { w ->
                    val t = (w as? JsonObject)?.get("team") as? JsonObject ?: return@mapNotNull null
                    val logo = ((t["logos"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("href").str() ?: ""
                    val names = listOf("displayName", "shortDisplayName", "name", "location", "nickname").mapNotNull { t[it].str() }
                    SportLogos178Logic.Team(names, logo)
                })
            }.getOrDefault(emptyMap())
        }
        indexes[league] = result
        if (result.isEmpty()) write(miss, byteArrayOf(1)) else writeIndex(file, result)
        return result
    }

    /** TheSportsDB: zoeken op de clubnaam (alleen records van deze sport), als ESPN hem niet kent. */
    private suspend fun search(name: String, sport: String, wanted: List<String>, net: Boolean): String? {
        val k = wanted[0]
        val file = G.cacheName("crest-search", k, "json")
        val miss = G.cacheName("miss-search", k, "")
        readIndex(file, !net)?.let { return G.match(wanted, it) }
        if (read(miss, "miss", !net) != null) return null
        if (!net) return null
        val want = G.sportsDBSport(sport)
        val idx: Map<String, String> = once("search|$k") {
            runCatching {
                val root = Net.json(G.sportsDBSearchURL(name)) as JsonObject
                val recs = (root["teams"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                    .filter { (it["strSport"].str() ?: "Soccer") == want }
                G.buildIndex(recs.map { r ->
                    val names = listOfNotNull(r["strTeam"].str(), r["strTeamShort"].str()) +
                        (r["strTeamAlternate"].str()?.split(",") ?: emptyList())
                    SportLogos178Logic.Team(names, G.sportsDBThumb(r["strBadge"].str() ?: "") ?: "")
                })
            }.getOrDefault(emptyMap())
        }
        val found = G.match(wanted, idx)
        if (found == null) write(miss, byteArrayOf(1)) else writeIndex(file, idx)
        return found
    }

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    /** Melding: logo van de thuisploeg, anders de uitploeg of de competitie; alleen uit de cache. */
    fun notificationIcon(r: ProgramReminder): Bitmap? {
        if (!r.isSport) return null
        val comp = Build173Logic.COMPETITIONS.firstOrNull { it.name == r.competition }
        val sport = comp?.sport ?: "football"
        val arts = ArrayList<SportArt>()
        Build173Logic.parseDuel(r.title)?.let { d ->
            arts += SportArt.Team(d.first, sport, comp?.id ?: "")
            arts += SportArt.Team(d.second, sport, comp?.id ?: "")
        }
        if (comp != null) arts += SportArt.Competition(comp.id, false)
        return arts.firstNotNullOfOrNull { cachedBitmapBlocking(it) }
    }
}

// MARK: - Weergaven

/** Logo, vlag of clublogo; zolang het laadt of ontbreekt de cirkel met initialen (of niets bij [placeholder] = false). */
@Composable
fun SportLogo(art: SportArt, title: String, size: Int, placeholder: Boolean = true) {
    val bmp by produceState(SportLogos.memory(art), art) { if (value == null) value = SportLogos.bitmap(art) }
    val b = bmp
    if (b != null) {
        Image(b, null, Modifier.size(tvs(size)), contentScale = ContentScale.Fit)
    } else if (placeholder) {
        SportInitials(title, size)
    }
}

/** De cirkel met initialen en een vaste kleur per team (build 172). */
@Composable
fun SportInitials(name: String, size: Int) {
    Box(Modifier.size(tvs(size)).clip(CircleShape).background(Color(0xFF000000 or Build173Logic.color(name))),
        contentAlignment = Alignment.Center) {
        Text(Build173Logic.short(name), color = Color.White, fontSize = tsp((size * 0.36f).toInt()), fontWeight = FontWeight.ExtraBold,
            maxLines = 1)
    }
}

/** Logo van de competitie van een wedstrijd (niets zonder competitie of logo). */
@Composable
fun SportCompetitionLogo(m: Build173Logic.Match, size: Int) {
    if (m.competitionId.isNotEmpty()) SportLogo(SportArt.Competition(m.competitionId, size < 24), m.competition, size, placeholder = false)
}

/** Vermelding onderaan het sportoverzicht (tekst van Weekdot). */
@Composable
fun SportLogoNotice() {
    Text(L("Logos and crests are trademarks of their respective owners and are shown for identification only."),
        color = Color(0xFF7A7A7A), fontSize = tsp(11), modifier = Modifier.padding(horizontal = Theme.edge).padding(top = tvs(18)))
}
