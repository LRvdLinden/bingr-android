package nl.lrvdlinden.bingr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

// Kijkstatistieken zoals Trakt (build 68): week, maand of jaar, per profiel, via device-auth watch_stats.
// Zie docs/ACCOUNT.md in de iOS-repo. Films in de accentkleur, series blauw, live tv oranje.

private val StatsBlue = Color(0xFF3B82F6)
private val StatsOrange = Color(0xFFF59E0B)

/** Sinds wanneer de server de statistieken bijhoudt (als het antwoord geen `since` heeft). */
private const val STATS_SINCE_DEFAULT = "2026-10-13"

/** Kijktijd: "45 min", "2,5 u" of "38 u" (getallen in de taal van de app). */
internal fun statsTime(minutes: Double): String {
    val m = minutes.roundToInt()
    if (m < 60) return L("%d min", m)
    val h = minutes / 60.0
    val nf = NumberFormat.getNumberInstance(Localizer.locale).apply {
        maximumFractionDigits = if (h < 10) 1 else 0
        minimumFractionDigits = 0
    }
    return L("%@ h", nf.format(h))
}

private fun statsCount(n: Int, one: String, many: String): String = if (n == 1) L(one) else L(many, n)

private fun statsParseDay(s: String?): Calendar? {
    if (s.isNullOrBlank() || s.length < 10) return null
    val d = runCatching { Build149Logic.Dates.fmt("yyyy-MM-dd", Locale.US).parse(s.take(10)) }.getOrNull() ?: return null
    return Calendar.getInstance().apply { time = d }
}

private fun statsMidnight(c: Calendar): Calendar = (c.clone() as Calendar).apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}

/** Begin en einde (beide inclusief) van de periode op het apparaat; weken beginnen op maandag. */
private fun statsRange(period: Account.StatsPeriod, offset: Int): Pair<Calendar, Calendar> {
    val start = statsMidnight(Calendar.getInstance())
    val end: Calendar
    when (period) {
        Account.StatsPeriod.WEEK -> {
            val back = (start.get(Calendar.DAY_OF_WEEK) + 5) % 7 // maandag = 0 … zondag = 6
            start.add(Calendar.DAY_OF_MONTH, -back + offset * 7)
            end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 6) }
        }
        Account.StatsPeriod.MONTH -> {
            start.set(Calendar.DAY_OF_MONTH, 1)
            start.add(Calendar.MONTH, offset)
            end = (start.clone() as Calendar).apply { add(Calendar.MONTH, 1); add(Calendar.DAY_OF_MONTH, -1) }
        }
        Account.StatsPeriod.YEAR -> {
            start.set(Calendar.DAY_OF_YEAR, 1)
            start.add(Calendar.YEAR, offset)
            end = (start.clone() as Calendar).apply { add(Calendar.YEAR, 1); add(Calendar.DAY_OF_MONTH, -1) }
        }
    }
    return start to end
}

private fun statsCapitalize(s: String): String = s.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Localizer.locale) else it.toString() }

/** "Deze maand · Oktober 2026", "6 – 12 okt. 2026", "2025". */
private fun statsPeriodLabel(period: Account.StatsPeriod, offset: Int, range: Pair<Calendar, Calendar>): String {
    val loc = Localizer.locale
    val (a, b) = range
    val text = when (period) {
        Account.StatsPeriod.WEEK -> {
            val sameYear = a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
            val left = Build149Logic.Dates.fmt(if (sameYear) "d MMM" else "d MMM yyyy", loc).format(a.time)
            left + " – " + Build149Logic.Dates.fmt("d MMM yyyy", loc).format(b.time)
        }
        Account.StatsPeriod.MONTH -> statsCapitalize(Build149Logic.Dates.fmt("LLLL yyyy", loc).format(a.time))
        Account.StatsPeriod.YEAR -> a.get(Calendar.YEAR).toString()
    }
    if (offset != 0) return text
    val now = when (period) {
        Account.StatsPeriod.WEEK -> L("This week")
        Account.StatsPeriod.MONTH -> L("This month")
        Account.StatsPeriod.YEAR -> L("This year")
    }
    return "$now · $text"
}

/** Eén staaf in de grafiek, ook voor dagen/maanden zonder kijktijd. */
private data class StatsSlot(val key: String, val date: Long, val movie: Double, val episode: Double, val live: Double) {
    val total: Double get() = movie + episode + live
}

private fun statsSlots(period: Account.StatsPeriod, range: Pair<Calendar, Calendar>, buckets: List<Account.StatsBucket>): List<StatsSlot> {
    val year = period == Account.StatsPeriod.YEAR
    val byKey = buckets.associateBy { it.key.take(if (year) 7 else 10) }
    val fmt = Build149Logic.Dates.fmt(if (year) "yyyy-MM" else "yyyy-MM-dd", Locale.US)
    val c = range.first.clone() as Calendar
    val out = ArrayList<StatsSlot>()
    while (!c.after(range.second) && out.size < 400) {
        val k = fmt.format(c.time)
        val b = byKey[k]
        var mv = b?.movieMinutes ?: 0.0
        val ep = b?.episodeMinutes ?: 0.0
        val lv = b?.liveMinutes ?: 0.0
        // alleen een totaal zonder verdeling (onbekende soort): als film tellen, zodat de dag niet leeg lijkt
        if (b != null && mv + ep + lv <= 0.0 && b.minutes > 0.0) mv = b.minutes
        out += StatsSlot(k, c.timeInMillis, mv, ep, lv)
        if (year) c.add(Calendar.MONTH, 1) else c.add(Calendar.DAY_OF_MONTH, 1)
    }
    return out
}

/** Bovenkant van de y-as: een rond aantal uren (of 1 uur). */
private fun statsNiceMax(maxMinutes: Double): Double {
    if (maxMinutes <= 60.0) return 60.0
    val h = maxMinutes / 60.0
    val steps = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0, 12.0, 15.0, 20.0, 24.0, 30.0, 40.0, 50.0, 60.0, 80.0,
        100.0, 120.0, 150.0, 200.0, 250.0, 300.0, 400.0, 500.0, 600.0, 800.0, 1000.0)
    val step = steps.firstOrNull { it >= h } ?: (ceil(h / 100.0) * 100.0)
    return step * 60.0
}

private fun statsSlotTitle(s: StatsSlot, period: Account.StatsPeriod): String {
    val loc = Localizer.locale
    return if (period == Account.StatsPeriod.YEAR) statsCapitalize(Build149Logic.Dates.fmt("LLLL yyyy", loc).format(s.date))
    else statsCapitalize(Build149Logic.Dates.fmt("EEEE d MMMM", loc).format(s.date))
}

/** Labels onder de grafiek: (tekst, aantal staven dat het label beslaat). */
private fun statsAxisLabels(slots: List<StatsSlot>, period: Account.StatsPeriod): List<Pair<String, Int>> {
    val loc = Localizer.locale
    return when (period) {
        Account.StatsPeriod.WEEK -> slots.map { statsCapitalize(Build149Logic.Dates.fmt("EEE", loc).format(it.date)).trimEnd('.').take(3) to 1 }
        Account.StatsPeriod.YEAR -> slots.map { statsCapitalize(Build149Logic.Dates.fmt("LLL", loc).format(it.date)).trimEnd('.').take(3) to 1 }
        Account.StatsPeriod.MONTH -> slots.indices.step(7).map { i ->
            val day = Calendar.getInstance().apply { timeInMillis = slots[i].date }.get(Calendar.DAY_OF_MONTH)
            day.toString() to minOf(7, slots.size - i)
        }
    }
}

/** Wat er geladen is, met de aanvraag erbij (de grafiek hoort bij de periode van de gegevens, niet van de knoppen). */
private data class StatsLoaded(val period: Account.StatsPeriod, val offset: Int, val profileId: String?, val stats: Account.WatchStats)

/**
 * Mijn Bingr → Statistieken. Hoofdprofiel: filter op alle profielen of één profiel, plus de verdeling per profiel.
 * Andere profielen (ook kinderen): alleen hun eigen statistieken. Op TV werkt alles met de pijltjes; terug sluit.
 */
@Composable
fun StatsScreen(onClose: () -> Unit) {
    val tv = AppState.isTV
    val isMain = Store.canManage
    val ownId = Store.activeProfileID
    var period by remember { mutableStateOf(Account.StatsPeriod.MONTH) }
    var offset by remember { mutableIntStateOf(0) }
    /** null = alle profielen (alleen hoofdprofiel) */
    var filter by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf<StatsLoaded?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var unavailable by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<DetailTarget?>(null) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val scope = rememberCoroutineScope()
    val firstFocus = remember { FocusRequester() }
    val requestProfile: String? = if (isMain) filter else ownId

    LaunchedEffect(period, offset, requestProfile, reload) {
        loading = true
        try {
            val s = Account.watchStats(period, offset, requestProfile)
            loaded = StatsLoaded(period, offset, requestProfile, s)
            error = null; unavailable = false
        } catch (e: CancellationException) { throw e }
        catch (e: Account.StatsUnavailable) { unavailable = true; error = null }
        catch (e: AccountException) { error = e.message }
        catch (e: Exception) { error = L("Something went wrong. Please try again.") }
        loading = false
    }
    // TV: de focus begint op de gekozen periode
    LaunchedEffect(Unit) {
        if (!tv) return@LaunchedEffect
        repeat(8) {
            kotlinx.coroutines.delay(90)
            if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    val shownDetail = detail
    if (shownDetail != null) {
        DetailScreen(shownDetail, { detail = null })
        return
    }

    val lib = Playlists.library
    val data = loaded
    val stats = data?.stats
    val range = remember(data, period, offset) {
        val local = statsRange(period, offset)
        // het antwoord van de server gaat voor (zelfde tijdzone, maar de server bepaalt de periode)
        if (data != null && data.period == period && data.offset == offset) {
            val f = statsParseDay(data.stats.from); val t = statsParseDay(data.stats.to)
            if (f != null && t != null && !t.before(f)) f to t else local
        } else local
    }
    val slots = remember(data) {
        if (data == null) emptyList()
        else {
            val r = statsParseDay(data.stats.from)?.let { f -> statsParseDay(data.stats.to)?.takeIf { !it.before(f) }?.let { f to it } }
                ?: statsRange(data.period, data.offset)
            statsSlots(data.period, r, data.stats.buckets)
        }
    }

    fun openSeries(t: Account.StatsTop): (() -> Unit) {
        val s = lib.series.firstOrNull { it.id == t.itemKey } ?: lib.series.firstOrNull { it.title.equals(t.title, ignoreCase = true) }
        return { if (s != null) detail = DetailTarget.SeriesT(s) }
    }
    fun openMovie(t: Account.StatsTop): (() -> Unit) {
        val m = lib.movies.firstOrNull { it.id == t.itemKey } ?: lib.movies.firstOrNull { it.title.equals(t.title, ignoreCase = true) }
        return { if (m != null) detail = DetailTarget.MovieT(m) }
    }
    fun openChannel(t: Account.StatsTop): (() -> Unit) {
        val c = lib.channels.firstOrNull { it.id == t.itemKey } ?: lib.channels.firstOrNull { it.name.equals(t.title, ignoreCase = true) }
        return { if (c != null) AppState.play(Playables.channel(c, lib.nowPlaying(c)), zap = lib.channels) }
    }
    val posters = remember(stats, lib) {
        val out = HashMap<String, String?>()
        stats?.topSeries?.forEach { t ->
            out["s:" + t.itemKey + t.title] = (lib.series.firstOrNull { it.id == t.itemKey }
                ?: lib.series.firstOrNull { it.title.equals(t.title, ignoreCase = true) })?.poster
        }
        stats?.topMovies?.forEach { t ->
            out["m:" + t.itemKey + t.title] = (lib.movies.firstOrNull { it.id == t.itemKey }
                ?: lib.movies.firstOrNull { it.title.equals(t.title, ignoreCase = true) })?.poster
        }
        stats?.topChannels?.forEach { t ->
            out["c:" + t.itemKey + t.title] = (lib.channels.firstOrNull { it.id == t.itemKey }
                ?: lib.channels.firstOrNull { it.name.equals(t.title, ignoreCase = true) })?.logo
        }
        out
    }

    fun LazyListScope.topSection(id: String, title: String, list: List<Account.StatsTop>, live: Boolean,
                                 detailText: (Account.StatsTop) -> String?, open: (Account.StatsTop) -> (() -> Unit)) {
        if (list.isEmpty()) return
        val more = expanded[id] == true
        val shown = list.take(if (more) 10 else 5)
        val rows = shown.size + (if (list.size > 5) 1 else 0)
        item(key = "h-$id") { S2Header(title) }
        itemsIndexed(shown, key = { i, t -> "$id-$i-${t.itemKey}" }) { i, t ->
            Column(Modifier.s2Cell(i, rows)) {
                if (i > 0) S2Divider()
                S2Row(
                    title = ContentLanguage.shownTitle(t.title, live).ifBlank { "—" },
                    subtitle = detailText(t),
                    value = statsTime(t.minutes),
                    onClick = open(t),
                    leading = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}", color = Theme.secondary, fontSize = tsp(15), fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center, modifier = Modifier.width(tvs(22)))
                            Spacer(Modifier.width(tvs(8)))
                            RemoteImage(posters[id.take(1) + ":" + t.itemKey + t.title],
                                Modifier.width(tvs(if (live) 56 else 40)).height(tvs(if (live) 36 else 60)).clip(RoundedCornerShape(4.dp)),
                                placeholder = ContentLanguage.shownTitle(t.title, live).take(2), fit = live)
                        }
                    }
                )
            }
        }
        if (list.size > 5) item(key = "more-$id") {
            Column(Modifier.s2Cell(rows - 1, rows)) {
                S2Divider()
                S2Row(if (more) L("Show less") else L("Show more"), tint = Theme.red, onClick = { expanded[id] = !more })
            }
        }
    }

    S2Page(L("Statistics"), onClose = onClose) {
        // MARK: periode
        item(key = "period") {
            StatsSegments(
                listOf(Account.StatsPeriod.WEEK to L("Week"), Account.StatsPeriod.MONTH to L("Month"), Account.StatsPeriod.YEAR to L("Year")),
                period, firstFocus
            ) { if (it != period) { period = it; offset = 0 } }
        }
        item(key = "nav") {
            Row(
                Modifier.widthIn(max = S2MaxWidth).fillMaxWidth().padding(horizontal = Theme.edge - 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                S2IconButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, L("Previous period")) { offset -= 1 }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text(statsPeriodLabel(period, offset, range), color = Color.White, fontSize = tsp(16), fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    if (loading && data != null) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(color = Theme.red, strokeWidth = 2.dp, modifier = Modifier.size(tvs(14)))
                    }
                }
                // volgende periode: alleen tot nu (blijft focusbaar, zodat de focus niet wegvalt)
                S2IconButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, L("Next period"),
                    tint = if (offset < 0) Color.White else Color.White.copy(alpha = 0.25f)) { if (offset < 0) offset += 1 }
            }
        }

        // MARK: profielfilter (alleen hoofdprofiel)
        if (isMain && Store.profiles.size > 1) {
            item(key = "profiles") {
                Row(
                    Modifier.widthIn(max = S2MaxWidth).fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(horizontal = Theme.edge, vertical = tvs(6)),
                    horizontalArrangement = Arrangement.spacedBy(tvs(8)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatsChip(L("All profiles"), filter == null, leading = {
                        Box(Modifier.size(tvs(26)).clip(CircleShape).background(Theme.elevated), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Groups, null, tint = Color.White, modifier = Modifier.size(tvs(16)))
                        }
                    }) { filter = null }
                    Store.profiles.forEach { p ->
                        StatsChip(p.name, filter == p.id, leading = { ProfileAvatar(p, tvs(26)) }) { filter = p.id }
                    }
                }
            }
        }

        if (unavailable) {
            item(key = "unavailable") {
                S2Section { S2Row(L("Statistics are not available yet. Please try again later."), tint = Theme.secondary) }
            }
            return@S2Page
        }
        if (data == null) {
            if (loading) item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Theme.red, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                }
            }
            error?.let { e -> item(key = "error") { S2Section { S2Row(e, tint = Theme.secondary, onClick = { reload++ }) } } }
            return@S2Page
        }
        error?.let { e -> item(key = "error") { S2Footer(e) } }

        val s = data.stats
        if (s.isEmpty) {
            item(key = "empty") {
                val since = statsParseDay(s.since ?: STATS_SINCE_DEFAULT) ?: statsParseDay(STATS_SINCE_DEFAULT)
                val date = since?.let { DateFormat.getDateInstance(DateFormat.LONG, Localizer.locale).format(it.time) } ?: ""
                S2Section {
                    S2Row(L("No viewing statistics yet. Bingr keeps track from %@.", date), tint = Theme.secondary,
                        onClick = if (tv) ({}) else null)
                }
            }
        } else {
            // MARK: kerncijfers
            item(key = "kpi") { StatsTiles(s) }
            // MARK: grafiek
            item(key = "chart-${data.period}-${data.offset}-${data.profileId}") { StatsChart(slots, data.period) }

            // MARK: toplijsten
            topSection("series", L("Top series"), s.topSeries, live = false,
                detailText = { t -> if (t.episodes > 0) statsCount(t.episodes, "1 episode", "%d episodes") else null }, open = { t -> openSeries(t) })
            topSection("movies", L("Top movies"), s.topMovies, live = false, detailText = { null }, open = { t -> openMovie(t) })
            topSection("channels", L("Top channels"), s.topChannels, live = true, detailText = { null }, open = { t -> openChannel(t) })

            // MARK: per profiel (hoofdprofiel, alle profielen)
            val shares = s.profiles.filter { it.minutes > 0.0 }.sortedByDescending { it.minutes }
            if (isMain && data.profileId == null && shares.isNotEmpty()) {
                val total = shares.sumOf { it.minutes }.takeIf { it > 0.0 } ?: 1.0
                item(key = "h-profiles") { S2Header(L("Per profile")) }
                itemsIndexed(shares, key = { i, p -> "p-$i-${p.profileId}" }) { i, p ->
                    Column(Modifier.s2Cell(i, shares.size)) {
                        if (i > 0) S2Divider()
                        val known = p.profileId.isNotBlank() && Store.profiles.any { it.id.equals(p.profileId, ignoreCase = true) }
                        val name = p.profileName.ifBlank { Store.profiles.firstOrNull { it.id.equals(p.profileId, ignoreCase = true) }?.name ?: "—" }
                        S2Row(
                            title = name,
                            subtitle = "${(p.minutes / total * 100).roundToInt()}%",
                            value = statsTime(p.minutes),
                            onClick = {
                                if (known) filter = Store.profiles.first { it.id.equals(p.profileId, ignoreCase = true) }.id
                            },
                            leading = { RowProfileAvatar(p.profileId, name) },
                        )
                        // aandeel als dunne balk onder de rij
                        Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = tvs(10)).height(3.dp)
                            .clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.08f))) {
                            Box(Modifier.fillMaxWidth((p.minutes / total).toFloat().coerceIn(0.02f, 1f)).fillMaxHeight().background(Theme.red))
                        }
                    }
                }
            }
        }

        // MARK: wissen
        item(key = "clear") {
            S2Section(footer = if (isMain && requestProfile == null) L("Clears the viewing statistics of all profiles.") else null) {
                S2Row(L("Clear statistics"), tint = Theme.red, onClick = { confirmClear = true })
            }
        }
    }

    if (confirmClear) {
        val who = if (isMain && requestProfile == null) null
            else Store.profiles.firstOrNull { it.id == requestProfile }?.name ?: Store.activeProfile?.name
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = Theme.elevated,
            title = { Text(L("Clear statistics"), color = Color.White) },
            text = {
                Text(if (who == null) L("Clear the viewing statistics of all profiles?") else L("Clear the viewing statistics of %@?", who),
                    color = Theme.secondary)
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    val pid = requestProfile
                    scope.launch {
                        try { Account.deleteWatchStats(pid); reload++ }
                        catch (e: CancellationException) { throw e }
                        catch (e: Account.StatsUnavailable) { unavailable = true }
                        catch (e: AccountException) { error = e.message }
                        catch (e: Exception) { error = L("Something went wrong. Please try again.") }
                    }
                }) { Text(L("Clear statistics"), color = Theme.red) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(L("Cancel"), color = Color.White) } }
        )
    }
}

/** Week / Maand / Jaar; de gekozen knop krijgt op TV de eerste focus. */
@Composable
private fun StatsSegments(
    options: List<Pair<Account.StatsPeriod, String>>,
    selected: Account.StatsPeriod,
    focus: FocusRequester,
    onSelect: (Account.StatsPeriod) -> Unit,
) {
    Row(
        Modifier.widthIn(max = S2MaxWidth).fillMaxWidth().padding(horizontal = Theme.edge, vertical = 8.dp)
            .clip(RoundedCornerShape(10.dp)).background(Theme.card).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        options.forEach { (v, label) ->
            var focused by remember { mutableStateOf(false) }
            val sel = v == selected
            Box(
                Modifier.weight(1f)
                    .then(if (sel) Modifier.focusRequester(focus) else Modifier)
                    .onFocusChanged { focused = it.isFocused }
                    .focusLift(1.04f) { onSelect(v) }
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (sel) Theme.elevated else Color.Transparent)
                    .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
                    .padding(vertical = tvs(8)),
                contentAlignment = Alignment.Center
            ) {
                Text(label, color = if (sel) Color.White else Theme.secondary, fontSize = tsp(14), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Profielknop in het filter: ronde avatar met naam. */
@Composable
private fun StatsChip(label: String, selected: Boolean, leading: @Composable () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.onFocusChanged { focused = it.isFocused }
            .focusLift(1.06f, onClick = onClick)
            .clip(shape)
            .background(if (selected) Color.White.copy(alpha = 0.16f) else Theme.card)
            .border(if (focused) 2.dp else 1.5.dp, when {
                focused -> Color.White
                selected -> Theme.red
                else -> Color.Transparent
            }, shape)
            .padding(start = tvs(5), end = tvs(14), top = tvs(5), bottom = tvs(5)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading()
        Spacer(Modifier.width(tvs(8)))
        Text(label, color = if (selected) Color.White else Theme.secondary, fontSize = tsp(14),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
    }
}

/** Kerncijfers: uren gekeken, films, series en live tv. Op TV één focusbaar blok (zodat de lijst meescrolt). */
@Composable
private fun StatsTiles(s: Account.WatchStats) {
    val tv = AppState.isTV
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier.widthIn(max = S2MaxWidth).fillMaxWidth().padding(horizontal = Theme.edge).padding(top = tvs(10))
            .then(if (tv) Modifier.onFocusChanged { focused = it.isFocused }.focusable() else Modifier)
            .clip(RoundedCornerShape(14.dp))
            .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(14.dp)) else Modifier),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val rows = if (tv) listOf(listOf(0, 1, 2, 3)) else listOf(listOf(0, 1), listOf(2, 3))
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { i -> StatsKpi(s, i, Modifier.weight(1f).fillMaxHeight()) }
            }
        }
    }
}

/** Tegel [i]: 0 = uren gekeken, 1 = films, 2 = series, 3 = live tv. */
@Composable
private fun StatsKpi(s: Account.WatchStats, i: Int, modifier: Modifier) {
    when (i) {
        0 -> StatsTile(L("Hours watched"), statsTime(s.totalMinutes), null, null, modifier)
        1 -> StatsTile(L("Movies"), statsTime(s.movieMinutes), statsCount(s.movieCount, "1 movie", "%d movies"), Theme.red, modifier)
        2 -> StatsTile(L("Series"), statsTime(s.seriesMinutes),
            statsCount(s.seriesShows, "1 series", "%d series") + " · " + statsCount(s.seriesEpisodes, "1 episode", "%d episodes"),
            StatsBlue, modifier)
        else -> StatsTile(L("Live TV"), statsTime(s.liveMinutes), statsCount(s.liveChannels, "1 channel", "%d channels"), StatsOrange, modifier)
    }
}

@Composable
private fun StatsTile(title: String, value: String, detail: String?, color: Color?, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Theme.card).padding(tvs(14))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (color != null) {
                Box(Modifier.size(tvs(8)).clip(CircleShape).background(color))
                Spacer(Modifier.width(6.dp))
            }
            Text(title.uppercase(), color = S2Grey, fontSize = tsp(11), fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(tvs(6)))
        Text(value, color = Color.White, fontSize = tsp(24), fontWeight = FontWeight.Bold, maxLines = 1)
        if (detail != null) {
            Spacer(Modifier.height(2.dp))
            Text(detail, color = Theme.secondary, fontSize = tsp(12), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun StatsLegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(tvs(10)).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Theme.secondary, fontSize = tsp(12))
    }
}

/**
 * Gestapelde staafgrafiek per dag (week/maand) of per maand (jaar). Tikken (telefoon) of links/rechts met focus op
 * de grafiek (TV) kiest een staaf; daarboven staat dan de kijktijd van die dag of maand.
 */
@Composable
private fun StatsChart(slots: List<StatsSlot>, period: Account.StatsPeriod) {
    val tv = AppState.isTV
    var sel by remember(slots) { mutableIntStateOf(-1) }
    var focused by remember { mutableStateOf(false) }
    val accent = Theme.red
    val top = statsNiceMax(slots.maxOfOrNull { it.total } ?: 0.0)
    val axisW = tvs(44)
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.widthIn(max = S2MaxWidth).fillMaxWidth().padding(horizontal = Theme.edge).padding(top = tvs(10))
            .then(if (tv) Modifier
                .onFocusChanged {
                    focused = it.isFocused
                    // eerste keer: de laatste dag met kijktijd (of de laatste staaf)
                    if (it.isFocused && sel < 0 && slots.isNotEmpty()) sel = slots.indexOfLast { s -> s.total > 0.0 }.takeIf { i -> i >= 0 } ?: (slots.size - 1)
                }
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (e.key) {
                        Key.DirectionLeft -> if (sel > 0) { sel -= 1; true } else false
                        Key.DirectionRight -> if (sel < slots.size - 1) { sel += 1; true } else false
                        else -> false
                    }
                }
                .focusable() else Modifier)
            .clip(shape).background(Theme.card)
            .then(if (focused) Modifier.border(2.dp, Color.White, shape) else Modifier)
            .padding(tvs(14))
    ) {
        val cur = slots.getOrNull(sel)
        Text(if (cur != null) statsSlotTitle(cur, period) + " · " + statsTime(cur.total) else L("Hours watched"),
            color = Color.White, fontSize = tsp(14), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val parts = if (cur != null) listOf(L("Movies") to cur.movie, L("Series") to cur.episode, L("Live TV") to cur.live)
            .filter { it.second > 0.0 }.joinToString(" · ") { it.first + " " + statsTime(it.second) } else ""
        Text(parts.ifEmpty { " " }, color = Theme.secondary, fontSize = tsp(12), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(tvs(8)))
        Row(Modifier.fillMaxWidth().height(tvs(170))) {
            Column(Modifier.width(axisW).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                Text(statsTime(top), color = S2Grey, fontSize = tsp(10), maxLines = 1)
                Text(statsTime(top / 2), color = S2Grey, fontSize = tsp(10), maxLines = 1)
                Text("0", color = S2Grey, fontSize = tsp(10), maxLines = 1)
            }
            Canvas(
                Modifier.weight(1f).fillMaxHeight().pointerInput(slots) {
                    detectTapGestures { o ->
                        if (slots.isEmpty()) return@detectTapGestures
                        val i = (o.x / (size.width.toFloat() / slots.size)).toInt().coerceIn(0, slots.size - 1)
                        sel = if (sel == i) -1 else i
                    }
                }
            ) {
                val n = slots.size
                val h = size.height
                // rasterlijnen op 0, de helft en de bovenkant
                listOf(0f, 0.5f, 1f).forEach { f ->
                    val y = h - h * f
                    drawLine(Color.White.copy(alpha = 0.1f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }
                if (n == 0) return@Canvas
                val slotW = size.width / n
                val barW = (slotW * (if (n > 12) 0.64f else 0.5f)).coerceAtLeast(2f)
                slots.forEachIndexed { i, s ->
                    if (s.total <= 0.0) return@forEachIndexed
                    val alpha = if (sel >= 0 && sel != i) 0.4f else 1f
                    val x = i * slotW + (slotW - barW) / 2f
                    var y = h
                    for ((v, c) in listOf(s.movie to accent, s.episode to StatsBlue, s.live to StatsOrange)) {
                        if (v <= 0.0) continue
                        val bh = (v / top * h).toFloat().coerceAtMost(y)
                        drawRect(c.copy(alpha = alpha), Offset(x, y - bh), Size(barW, bh))
                        y -= bh
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().padding(start = axisW)) {
            statsAxisLabels(slots, period).forEach { (label, span) ->
                Text(label, color = S2Grey, fontSize = tsp(10), maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                    textAlign = if (period == Account.StatsPeriod.MONTH) TextAlign.Start else TextAlign.Center,
                    modifier = Modifier.weight(span.toFloat()))
            }
        }
        Spacer(Modifier.height(tvs(10)))
        Row(horizontalArrangement = Arrangement.spacedBy(tvs(16)), verticalAlignment = Alignment.CenterVertically) {
            StatsLegendDot(accent, L("Movies"))
            StatsLegendDot(StatsBlue, L("Series"))
            StatsLegendDot(StatsOrange, L("Live TV"))
        }
    }
}
