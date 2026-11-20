package nl.lrvdlinden.bingr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

// Build 179, punten 7 en 8: live scores en standen van ESPN (geen sleutel) bij de wedstrijden uit de gids, wedstrijden
// die de gids niet heeft ("Niet in je gids"), en kaarten in de kleuren van de teams. Regels: [Build179Logic.Scores] en
// [Build179Logic.TeamColor]. Cache: scores 60 s (Data besparen 300 s), standen 1 uur; fouten stil.

object Scores179 {
    private val S = Build179Logic.Scores

    data class Side(val names: List<String>, val score: String?, val color: String?, val alt: String?, val logo: String?)
    data class Ev(val id: String, val league: String, val start: Long, val state: String, val clock: String, val home: Side, val away: Side) {
        val text: String? get() = Build179Logic.Scores.text(state, home.score, away.score, clock)
        val title: String get() = (home.names.firstOrNull() ?: "") + " - " + (away.names.firstOrNull() ?: "")
    }
    data class Row(val rank: Int, val team: String, val played: String, val points: String, val logo: String?)
    data class Table(val name: String, val rows: List<Row>)

    /** Telt op als er nieuwe scores of standen zijn (Compose). */
    var version by mutableIntStateOf(0); private set

    private val boards = ConcurrentHashMap<String, Pair<Long, List<Ev>>>()
    private val tables = ConcurrentHashMap<String, Pair<Long, List<Table>>>()
    private val busy = ConcurrentHashMap.newKeySet<String>()

    private fun now() = System.currentTimeMillis() / 1000
    private fun saver() = runCatching { DataSaver.active() }.getOrDefault(false)

    /** ESPN-competities van deze wedstrijden en van de gevolgde competities. */
    fun leaguesFor(groups: List<Build173Logic.Group>): Set<String> =
        (groups.mapNotNull { S.league(it.match.competitionId) } + Store.favoriteCompetitions.mapNotNull { S.league(it) }).toSet()

    /** Scores van [leagues] verversen als de cache verlopen is (op de achtergrond). */
    fun refresh(leagues: Set<String>) {
        val ttl = if (saver()) S.SCORES_TTL_SAVER else S.SCORES_TTL
        for (l in leagues) {
            if (S.fresh(boards[l]?.first, now(), ttl) || !busy.add("sb|$l")) continue
            AppScope.launch {
                try {
                    val day = S.day(System.currentTimeMillis())
                    val list = parseBoard(l, Net.json(S.scoreboardURL(l, day)))
                    boards[l] = now() to list
                    version++
                } catch (e: CancellationException) { throw e } catch (_: Exception) {
                    boards[l] = now() to (boards[l]?.second ?: emptyList())
                } finally { busy.remove("sb|$l") }
            }
        }
    }

    fun events(leagues: Set<String>): List<Ev> = leagues.flatMap { boards[it]?.second ?: emptyList() }

    /** De ESPN-wedstrijd bij deze wedstrijd uit de gids (`espnLink`), of null. */
    fun eventFor(g: Build173Logic.Group): Ev? {
        if (!g.match.isDuel) return null
        val l = S.league(g.match.competitionId)
        val pool = if (l != null) boards[l]?.second.orEmpty() else boards.values.flatMap { it.second }
        if (pool.isEmpty()) return null
        val id = S.link(g.match.home, g.match.away, g.start / 1000, pool.map { Build179Logic.Scores.Link(it.id, it.home.names, it.away.names, it.start / 1000) })
            ?: return null
        return pool.firstOrNull { it.id == id }
    }

    /** Stand voor de wedstrijdmelding (alleen uit de cache; titel "A - B"). */
    fun cachedText(title: String, start: Long): String? {
        val d = Build173Logic.parseDuel(title) ?: return null
        val pool = boards.values.flatMap { it.second }
        val id = S.link(d.first, d.second, start / 1000, pool.map { Build179Logic.Scores.Link(it.id, it.home.names, it.away.names, it.start / 1000) }) ?: return null
        return pool.firstOrNull { it.id == id }?.text
    }

    private fun str(e: JsonElement?, k: String) = e.jS(k)?.trim()?.takeIf { it.isNotEmpty() }

    private fun parseBoard(league: String, root: JsonElement): List<Ev> = root.jList("events").mapNotNull { ev ->
        val comp = ev.jList("competitions").firstOrNull() ?: return@mapNotNull null
        val sides = comp.jList("competitors")
        fun side(where: String): Side? {
            val c = sides.firstOrNull { it.jS("homeAway") == where } ?: return null
            val t = c.jO("team")
            val names = listOf("displayName", "shortDisplayName", "name", "location", "abbreviation").mapNotNull { str(t, it) }.distinct()
            val score = (c as? JsonObject)?.get("score")?.let { s -> (s as? JsonObject)?.let { str(it, "displayValue") } ?: s.jFlex }
            return Side(names, score, str(t, "color"), str(t, "alternateColor"), str(t, "logo"))
        }
        val home = side("home") ?: return@mapNotNull null
        val away = side("away") ?: return@mapNotNull null
        val status = ev.jO("status")
        val start = Build179Logic.Iso.parse(str(ev, "date")) ?: return@mapNotNull null
        Ev(str(ev, "id") ?: "$league|$start", league, start, status.jO("type").jS("state") ?: "pre", status.jS("displayClock") ?: "", home, away)
    }

    // MARK: standen

    fun cachedTables(league: String): List<Table>? = tables[league]?.second

    fun loadTables(league: String) {
        if (S.fresh(tables[league]?.first, now(), S.STANDINGS_TTL) || !busy.add("st|$league")) return
        if (saver() && tables[league] != null) { busy.remove("st|$league"); return }
        AppScope.launch {
            try {
                tables[league] = now() to parseTables(Net.json(S.standingsURL(league)))
                version++
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                tables[league] = now() to (tables[league]?.second ?: emptyList()); version++
            } finally { busy.remove("st|$league") }
        }
    }

    private fun parseTables(root: JsonElement): List<Table> {
        val groups = root.jList("children").ifEmpty { listOf(root) }
        return groups.mapNotNull { g ->
            val entries = g.jO("standings").jList("entries")
            if (entries.isEmpty()) return@mapNotNull null
            val rows = entries.mapIndexed { i, e ->
                val t = e.jO("team")
                val stats = e.jList("stats").associate { (str(it, "name") ?: str(it, "type") ?: "") to (str(it, "displayValue") ?: str(it, "value") ?: "") }
                val logo = (t?.get("logos") as? JsonArray)?.firstOrNull()?.jS("href")
                Row(stats["rank"]?.toDoubleOrNull()?.toInt() ?: (i + 1), str(t, "displayName") ?: str(t, "name") ?: "",
                    stats["gamesPlayed"] ?: "", stats["points"] ?: stats["wins"]?.let { w -> "$w-${stats["losses"] ?: "0"}" } ?: "", logo)
            }.sortedBy { it.rank }
            Table(str(g, "name") ?: "", rows)
        }
    }
}

// MARK: 8. Teamkleuren

object TeamColors179 {
    private val avg = ConcurrentHashMap<String, String>()

    /** Kleur van een team: ESPN color/alternateColor, gemiddelde van het logo, anders de vaste kleur van build 172. */
    fun color(name: String, side: Scores179.Side?, logoBitmap: androidx.compose.ui.graphics.ImageBitmap?): Color {
        val logoAvg = logoBitmap?.let { b ->
            avg.getOrPut(name) {
                runCatching {
                    val bmp = b.asAndroidBitmap()
                    val small = android.graphics.Bitmap.createScaledBitmap(bmp, 24, 24, true)
                    val px = IntArray(24 * 24); small.getPixels(px, 0, 24, 0, 0, 24, 24)
                    Build179Logic.TeamColor.average(px) ?: ""
                }.getOrDefault("")
            }.takeIf { it.isNotEmpty() }
        }
        val fallback = Build179Logic.TeamColor.hex(Build173Logic.color(name).toInt())
        val hex = Build179Logic.TeamColor.pick(listOf(side?.color, side?.alt, logoAvg), fallback)
        return Color(0xFF000000 or (Build179Logic.TeamColor.parse(hex)?.toLong() ?: 0x333333L))
    }

    /** Achtergrond van een wedstrijdkaart: thuis → uit (tekst wit). */
    @Composable
    fun brush(m: Build173Logic.Match, ev: Scores179.Ev?): Brush? {
        if (!m.isDuel) return null
        val hb by produceState(SportLogos.memory(SportArt.Team(m.home, m.sport, m.competitionId)), m.home) {
            if (value == null) value = SportLogos.bitmap(SportArt.Team(m.home, m.sport, m.competitionId))
        }
        val ab by produceState(SportLogos.memory(SportArt.Team(m.away, m.sport, m.competitionId)), m.away) {
            if (value == null) value = SportLogos.bitmap(SportArt.Team(m.away, m.sport, m.competitionId))
        }
        val homeFirst = ev == null || Build179Logic.Scores.teamMatches(m.home, ev.home.names)
        val h = color(m.home, if (homeFirst) ev?.home else ev?.away, hb)
        val a = color(m.away, if (homeFirst) ev?.away else ev?.home, ab)
        return Brush.horizontalGradient(listOf(h, a))
    }
}

// MARK: weergave

/** Score in de volgorde van de gids ("2–1 · 67'"), of null. */
fun scoreFor179(g: Build173Logic.Group): String? {
    @Suppress("UNUSED_VARIABLE") val v = Scores179.version
    val ev = Scores179.eventFor(g) ?: return null
    val t = ev.text ?: return null
    val homeFirst = Build179Logic.Scores.teamMatches(g.match.home, ev.home.names)
    if (homeFirst) return t
    // omgedraaid: de stand omdraaien, de klok blijft
    val (score, rest) = t.split(" · ", limit = 2).let { it[0] to it.getOrNull(1) }
    val p = score.split("–")
    val flipped = if (p.size == 2) "${p[1]}–${p[0]}" else score
    return if (rest != null) "$flipped · $rest" else flipped
}

@Composable
fun ScoreBadge179(text: String, final: Boolean) {
    Row(Modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text, color = Color.White, fontSize = tsp(14), fontWeight = FontWeight.ExtraBold)
        if (final) Text(L("Full time"), color = Color.White.copy(alpha = 0.8f), fontSize = tsp(11))
    }
}

/** Eén wedstrijd van ESPN die niet in de gids staat (of afgelopen is): teams, stand/tijd, label. */
@Composable
fun EspnMatchRow179(ev: Scores179.Ev, label: String?) {
    val tc = Build179Logic.TeamColor
    val h = Color(0xFF000000 or (tc.parse(tc.pick(listOf(ev.home.color, ev.home.alt), tc.hex(Build173Logic.color(ev.home.names.firstOrNull() ?: "").toInt())))?.toLong() ?: 0x333333L))
    val a = Color(0xFF000000 or (tc.parse(tc.pick(listOf(ev.away.color, ev.away.alt), tc.hex(Build173Logic.color(ev.away.names.firstOrNull() ?: "").toInt())))?.toLong() ?: 0x333333L))
    Row(Modifier.padding(horizontal = Theme.edge).fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(Brush.horizontalGradient(listOf(h, a))).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(ev.text ?: SportCenter.time(ev.start), color = Color.White, fontSize = tsp(15), fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.widthIn(min = 64.dp))
        Text(ev.title, color = Color.White, fontSize = tsp(15), fontWeight = FontWeight.Bold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (label != null) Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = tsp(11))
    }
}

/** Stand van een competitie (positie, team, gespeeld, punten). */
@Composable
fun StandingsTable179(league: String) {
    @Suppress("UNUSED_VARIABLE") val v = Scores179.version
    androidx.compose.runtime.LaunchedEffect(league) { Scores179.loadTables(league) }
    val tables = Scores179.cachedTables(league)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        when {
            tables == null -> Text(L("Loading…"), color = Theme.secondary, fontSize = tsp(13))
            tables.isEmpty() -> Text(L("No standings available."), color = Theme.secondary, fontSize = tsp(13))
            else -> tables.forEach { t ->
                if (tables.size > 1 && t.name.isNotEmpty()) Text(t.name, color = Color.White, fontSize = tsp(13), fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text("#", color = Theme.secondary, fontSize = tsp(11), modifier = Modifier.width(28.dp))
                    Text(L("Team"), color = Theme.secondary, fontSize = tsp(11), modifier = Modifier.weight(1f))
                    Text(L("P"), color = Theme.secondary, fontSize = tsp(11), modifier = Modifier.width(36.dp))
                    Text(L("Pts"), color = Theme.secondary, fontSize = tsp(11), modifier = Modifier.width(44.dp))
                }
                t.rows.forEach { r ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color(0xFF161616)).padding(vertical = 5.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(r.rank.toString(), color = Theme.secondary, fontSize = tsp(13), modifier = Modifier.width(24.dp))
                        Box(Modifier.weight(1f)) {
                            Text(r.team, color = Color.White, fontSize = tsp(13), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(r.played, color = Theme.secondary, fontSize = tsp(13), modifier = Modifier.width(36.dp))
                        Text(r.points, color = Color.White, fontSize = tsp(13), fontWeight = FontWeight.Bold, modifier = Modifier.width(44.dp))
                    }
                }
            }
        }
    }
}
