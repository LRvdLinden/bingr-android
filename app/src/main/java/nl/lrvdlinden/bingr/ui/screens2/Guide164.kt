package nl.lrvdlinden.bingr

import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import java.util.Date

// MARK: - Build 164: tv-gids in NL-Ziet-stijl -------------------------------------------------------------
// Telefoon: live vak (16:9) bovenaan, daaronder zenderstrook links en de programmalijst van één zender rechts.
// Android TV: dagbalk en zenderkolommen (2 eerder, NU-kaart met live beeld, 4 later). Pure logica: Build164Logic.

private val G_DIM = Color(0xFF8A8A8A)
private val G_LINE = Color(0xFF1F1F1F)
private val G_RAIL = Color(0xFF0D0D0D)
private val G_NOW_ROW = Color(0xFF1A0405)
private val G_NOW_TITLE = Color(0xFFFF5A62)
private val G_BOX = Color(0xFF141414)

/** Gids van één zender (ook van een andere server), gesorteerd op begintijd. */
private fun guidePrograms(c: Channel): List<EPGProgram> = Playlists.epgFor(c)

private fun guideStillKey(c: Channel): String = Build164Logic.Stills.key(c.sourceID ?: Playlists.source?.id, c.id)

private fun guideDayLabel(offset: Int, now: Long): String = when (Build164Logic.Days.label(offset)) {
    Build164Logic.Days.Label.YESTERDAY -> L("Yesterday")
    Build164Logic.Days.Label.TODAY -> L("Today")
    Build164Logic.Days.Label.TOMORROW -> L("Tomorrow")
    Build164Logic.Days.Label.DATE -> s2DateShort(Build164Logic.Days.start(now, offset))
}

/** "Vandaag · vrijdag 6 november" */
private fun guideDayLine(offset: Int, now: Long): String {
    val full = Build149Logic.Dates.fmt("EEEE d MMMM", Localizer.locale).format(Date(Build164Logic.Days.start(now, offset)))
    return if (Build164Logic.Days.label(offset) == Build164Logic.Days.Label.DATE) full else guideDayLabel(offset, now) + " · " + full
}

/** Programma's van [c] op dag [offset] (alleen dat stuk, binair zoeken) en de index van NU/het anker daarin. */
private fun guideDay(list: List<EPGProgram>, offset: Int, now: Long): Pair<List<EPGProgram>, Int> {
    val r = Build164Logic.Index.window(list, Build164Logic.Days.start(now, offset), Build164Logic.Days.end(now, offset),
        { it.start }, { it.end })
    if (r.isEmpty()) return emptyList<EPGProgram>() to -1
    val day = list.subList(r.first, r.last + 1)
    val anchor = Build164Logic.Days.anchor(now, offset)
    val i = Build164Logic.Index.firstEndingAfter(day, anchor, { it.start }, { it.end })
    return day to i
}

/**
 * Build 166: dagen in de dagbalk/dagkiezer: alleen dagen waarop de zenders van [sample] programma's hebben (vandaag altijd;
 * gisteren en eerder alleen met terugkijken). Pure logica: Build166Logic.GuideDays.
 */
private fun guideAvailableDays(sample: List<Channel>, now: Long): List<Int> {
    val lists = sample.map { it to guidePrograms(it) }
    val candidates = Build164Logic.Days.offsets(sample.maxOfOrNull { it.catchupDays } ?: 0)
    val bounds = candidates.associateWith { Build164Logic.Days.start(now, it) to Build164Logic.Days.end(now, it) }
    return Build166Logic.GuideDays.available(candidates, lists,
        hasGuide = { (_, l), o ->
            val (a, b) = bounds.getValue(o)
            !Build164Logic.Index.window(l, a, b, { it.start }, { it.end }).isEmpty()
        },
        catchupDays = { it.first.catchupDays })
}

/** Zenderlogo van de server op een donker vlak met een dunne rand; zonder (werkend) logo de opgeschoonde zendernaam. */
@Composable
fun GuideLogo(c: Channel, modifier: Modifier, fontSize: TextUnit = 11.sp, radius: Dp = 6.dp) {
    val shape = RoundedCornerShape(radius)
    Box(modifier.clip(shape).background(Color(0xFF1F1F1F)).border(1.dp, Color(0xFF2C2C2C), shape).padding(3.dp),
        contentAlignment = Alignment.Center) {
        var failed by remember(c.logo) { mutableStateOf(c.logo.isNullOrBlank()) }
        if (failed) {
            Text(c.displayName, color = Color.White, fontSize = fontSize, fontWeight = FontWeight.ExtraBold, maxLines = 2,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, lineHeight = fontSize * 1.1f)
        } else {
            AsyncImage(model = c.logo, contentDescription = c.displayName, contentScale = ContentScale.Fit,
                onError = { failed = true }, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Stilstaand beeld van de zender (max. 30 min oud), anders het logo groot op een donker vlak. */
@Composable
private fun GuideStill(c: Channel, now: Long, modifier: Modifier, logoFraction: Float = 0.42f, framed: Boolean = true) {
    val v = ChannelStills.version
    val key = guideStillKey(c)
    val f = remember(key, v, now / 60_000L) { ChannelStills.usable(key, now) }
    val at = remember(f, v) { f?.lastModified() ?: 0L }
    Box(modifier.background(G_BOX), contentAlignment = Alignment.Center) {
        if (f != null) {
            val ctx = LocalContext.current
            val req = remember(f, at) {
                ImageRequest.Builder(ctx).data(f).memoryCacheKey("still:${f.name}@$at")
                    .diskCachePolicy(CachePolicy.DISABLED).build()
            }
            AsyncImage(model = req, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        } else if (framed) {
            GuideLogo(c, Modifier.fillMaxWidth(logoFraction).aspectRatio(16f / 9f), fontSize = 14.sp)
        } else {
            GuideBareLogo(c, Modifier.fillMaxWidth(logoFraction).aspectRatio(16f / 9f))
        }
    }
}

/** Build 166: zenderlogo zonder vlak of rand (gecentreerd op de donkere NU-kaart); zonder logo de opgeschoonde naam. */
@Composable
private fun GuideBareLogo(c: Channel, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        var failed by remember(c.logo) { mutableStateOf(c.logo.isNullOrBlank()) }
        if (failed) {
            Text(c.displayName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        } else {
            AsyncImage(model = c.logo, contentDescription = c.displayName, contentScale = ContentScale.Fit,
                onError = { failed = true }, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Het live voorbeeld (gedeelde speler van [ChannelPreview]) in een TextureView, zonder bediening en ondertitels. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun GuideLiveSurface(modifier: Modifier) {
    val p = ChannelPreview.player ?: return
    AndroidView(
        factory = { ctx ->
            (LayoutInflater.from(ctx).inflate(R.layout.guide_live_view, null) as PlayerView).apply {
                useController = false
                subtitleView?.visibility = View.GONE
                player = p
                ChannelPreview.view = this
            }
        },
        update = { v -> if (v.player !== p) v.player = p; ChannelPreview.view = v },
        onRelease = { v -> if (ChannelPreview.view === v) ChannelPreview.view = null; v.player = null },
        modifier = modifier,
    )
}

/** Beeld van de zender: live als het voorbeeld van deze zender draait, anders het stilstaande beeld of het logo. */
@Composable
private fun GuidePicture(c: Channel, now: Long, modifier: Modifier, live: Boolean, logoFraction: Float = 0.42f, framed: Boolean = true) {
    val here = live && ChannelPreview.channel?.id == c.id && ChannelPreview.player != null
    Box(modifier.background(G_BOX)) {
        if (here) GuideLiveSurface(Modifier.matchParentSize())
        // tot het eerste beeld er is: het stilstaande beeld of het logo eroverheen
        if (!here || !ChannelPreview.playing) GuideStill(c, now, Modifier.matchParentSize(), logoFraction, framed)
    }
}

@Composable
private fun GuideBadge(text: String, modifier: Modifier = Modifier, size: TextUnit = 11.sp) {
    Text(text, color = Color.White, fontSize = size, fontWeight = FontWeight.ExtraBold, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(4.dp)).background(Theme.red).padding(horizontal = 7.dp, vertical = 3.dp))
}

@Composable
private fun GuideRoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier = Modifier,
                             onClick: () -> Unit) {
    Box(modifier.size(44.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.6f)).focusLift(1.06f, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

/** Dag kiezen (telefoon): alleen dagen met gids-info (build 166); gisteren en eerder alleen met terugkijken. */
@Composable
private fun GuideDayDialog(offsets: List<Int>, current: Int, now: Long, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    S2ActionDialog(L("Choose a day"), offsets.map { o ->
        S2Action(guideDayLabel(o, now) + if (o == current) "  ✓" else "", if (o == 0) Icons.Default.Today else null, primary = o == current) {
            onPick(o)
        }
    }, onDismiss = onDismiss)
}

// MARK: telefoon

/**
 * Tv-gids op de telefoon (en Android-tablet): live vak over de volle breedte, het programma van nu, en daaronder de
 * zenderstrook (76 dp) en de programmalijst van de gekozen zender op de gekozen dag.
 */
@Composable
fun GuidePhone(
    channels: List<Channel>,
    query: String,
    onQuery: (String) -> Unit,
    groupTitle: String,
    onGroups: () -> Unit,
    onList: () -> Unit,
    onPlay: (Channel) -> Unit,
) {
    // build 165: de gids is de startweergave van Live TV; terug werkt dus als op elk tabblad (geen BackHandler meer)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    var selID by rememberSaveable { mutableStateOf<String?>(null) }
    val sel = channels.firstOrNull { it.id == selID } ?: channels.firstOrNull()
    var dayOffset by rememberSaveable { mutableIntStateOf(0) }
    var dayPicker by remember { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(query.isNotEmpty()) }
    var program by remember { mutableStateOf<Pair<Channel, EPGProgram>?>(null) }
    // build 166: alleen dagen met gids-info voor de gekozen zender (vandaag altijd)
    val phoneDays = remember(sel?.id, Playlists.library.epg, AllServers.version, now / 3_600_000L) {
        sel?.let { guideAvailableDays(listOf(it), now) } ?: listOf(0)
    }

    // voorbeeld van de gekozen zender (vanzelf op wifi; op mobiel netwerk pas na een tik, zie Build164Logic.Preview)
    // (ook weer na terugkomen uit de speler: die stopt het voorbeeld)
    val idle = AppState.playing == null
    LaunchedEffect(sel?.id, idle) { if (idle) sel?.let { ChannelPreview.focus(it) } }
    DisposableEffect(Unit) { onDispose { ChannelPreview.stop() } }
    // kantelen naar liggend = volledig scherm met de gekozen zender
    val orientation = LocalConfiguration.current.orientation
    var lastOrientation by remember { mutableIntStateOf(orientation) }
    LaunchedEffect(orientation) {
        if (orientation != lastOrientation) {
            lastOrientation = orientation
            if (orientation == Configuration.ORIENTATION_LANDSCAPE && AppState.playing == null) sel?.let(onPlay)
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
        // kop: titel + groep, lijst (build 165: de bestaande wisselknop), groep kiezen, zoeken en dag kiezen
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(L("TV guide"), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                Text(if (query.isNotEmpty()) L("All channels") else groupTitle, color = Theme.secondary, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            S2LiveHeaderButton(L("List"), Icons.AutoMirrored.Filled.List, onClick = onList)
            S2IconButton(Icons.Default.FilterList, L("Categories"), size = 20.dp, onClick = onGroups)
            S2IconButton(Icons.Default.Search, L("Search"), size = 20.dp) {
                searchOpen = !searchOpen; if (!searchOpen) onQuery("")
            }
            S2IconButton(Icons.Default.CalendarMonth, L("Choose a day"), size = 20.dp) { dayPicker = true }
        }
        if (searchOpen) {
            S2TextField(query, onQuery, L("Search channel or program"),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), plain = false,
                leading = { Icon(Icons.Default.Search, null, tint = Theme.secondary) },
                trailing = {
                    if (query.isNotEmpty()) S2IconButton(Icons.Default.Close, L("Clear search"), tint = Theme.secondary, size = 18.dp) { onQuery("") }
                })
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(G_LINE))

        if (sel == null) {
            Text(if (query.isNotEmpty()) L("No channels found.") else L("No channels."), color = Theme.secondary,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 40.dp))
            return@Column
        }
        GuidePhoneLiveBox(sel, now, onPlay)
        val all = remember(sel.id, Playlists.library.epg, AllServers.version) { guidePrograms(sel) }
        val nowProg = remember(all, now) { Build164Logic.Index.at(all, now, { it.start }, { it.end }).let { if (it >= 0) all[it] else null } }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(nowProg?.displayTitle ?: sel.displayName, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (nowProg != null) {
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${s2Time(nowProg.start)} – ${s2Time(nowProg.end)}", color = Theme.secondary, fontSize = 12.sp)
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(2.dp))) { ProgressBar(nowProg.progress) }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(G_LINE))

        Row(Modifier.fillMaxSize()) {
            GuidePhoneRail(channels, sel) { selID = it.id }
            Box(Modifier.width(1.dp).fillMaxHeight().background(G_LINE))
            GuidePhoneList(sel, all, Build166Logic.GuideDays.keep(dayOffset, phoneDays), now) { p -> program = sel to p }
        }
    }

    if (dayPicker && sel != null) {
        GuideDayDialog(phoneDays, Build166Logic.GuideDays.keep(dayOffset, phoneDays), now, onPick = { dayOffset = it; dayPicker = false }, onDismiss = { dayPicker = false })
    }
    program?.let { (c, p) -> ProgramSheet(c, p, onDismiss = { program = null }, fromGuide = true) }
}

@Composable
private fun GuidePhoneLiveBox(sel: Channel, now: Long, onPlay: (Channel) -> Unit) {
    val live = ChannelPreview.channel?.id == sel.id && ChannelPreview.player != null
    val start = remember(sel.id, now / 60_000L) { PlayerSettings.previewStart() }
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(G_BOX)
        .focusLift(1f) {
            if (!live && start == Build164Logic.Preview.Start.TAP) ChannelPreview.focus(sel, tapped = true) else onPlay(sel)
        }) {
        GuidePicture(sel, now, Modifier.matchParentSize(), live = true, logoFraction = 0.36f)
        Row(Modifier.align(Alignment.TopStart).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            GuideLogo(sel, Modifier.width(44.dp).height(30.dp), fontSize = 9.sp)
            Spacer(Modifier.width(8.dp))
            GuideBadge(L("LIVE"))
        }
        if (!live && start == Build164Logic.Preview.Start.TAP) {
            Row(Modifier.align(Alignment.Center).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(L("Tap to watch live"), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (live) {
                val muted = ChannelPreview.muted
                GuideRoundButton(if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    if (muted) L("Sound on") else L("Sound off")) { ChannelPreview.mute(!muted) }
            }
            GuideRoundButton(Icons.Default.Fullscreen, L("Full screen")) { onPlay(sel) }
        }
    }
}

@Composable
private fun GuidePhoneRail(channels: List<Channel>, sel: Channel, onPick: (Channel) -> Unit) {
    val state = rememberLazyListState()
    LaunchedEffect(Unit) {
        val i = channels.indexOfFirst { it.id == sel.id }
        if (i > 2) runCatching { state.scrollToItem(i - 2) }
    }
    val keys = remember(channels) {
        val seen = HashMap<String, Int>()
        channels.map { c -> val n = seen.merge(c.id, 1, Int::plus)!!; if (n == 1) c.id else "${c.id}#$n" }
    }
    val red = Theme.red
    LazyColumn(Modifier.width(76.dp).fillMaxHeight().background(G_RAIL), state = state) {
        items(channels.size, key = { keys[it] }, contentType = { "rail" }) { i ->
            val c = channels[i]
            val on = c.id == sel.id
            Box(Modifier.width(76.dp).height(64.dp)
                .background(if (on) Theme.elevated else Color.Transparent)
                .then(if (on) Modifier.drawBehind { drawRect(red, Offset.Zero, Size(3.dp.toPx(), size.height)) } else Modifier)
                .focusLift(1.04f) { onPick(c) },
                contentAlignment = Alignment.Center) {
                GuideLogo(c, Modifier.width(52.dp).height(36.dp))
            }
        }
    }
}

@Composable
private fun GuidePhoneList(c: Channel, all: List<EPGProgram>, dayOffset: Int, now: Long, onProgram: (EPGProgram) -> Unit) {
    // eigen server zonder gids in de lijst: de korte gids van de server ophalen (zoals het oude gidsvenster)
    var short by remember(c.id) { mutableStateOf<List<EPGProgram>?>(null) }
    LaunchedEffect(c.id, all.isEmpty()) {
        if (all.isEmpty() && c.sourceID == null) short = runCatching { Playlists.shortEPG(c) }.getOrDefault(emptyList())
    }
    val source = if (all.isEmpty()) short ?: emptyList() else all
    val (day, anchor) = remember(source, dayOffset, now / 60_000L) { guideDay(source, dayOffset, now) }
    Column(Modifier.fillMaxSize()) {
        Text(guideDayLine(dayOffset, now), color = Theme.secondary, fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 1,
            modifier = Modifier.fillMaxWidth().background(G_RAIL).padding(horizontal = 12.dp, vertical = 8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(G_LINE))
        if (day.isEmpty()) {
            Text(L("No guide information"), color = Theme.secondary, fontSize = 13.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 32.dp))
            return@Column
        }
        val state = rememberLazyListState()
        // bij openen (en bij een andere zender of dag) naar NU scrollen
        LaunchedEffect(c.id, dayOffset, day.size) {
            if (anchor >= 0) runCatching { state.scrollToItem(Build164Logic.Index.scrollTarget(anchor)) }
        }
        LazyColumn(Modifier.fillMaxSize(), state = state) {
            items(day.size, key = { day[it].id + "#" + it }, contentType = { "prog" }) { i ->
                GuidePhoneRow(c, day[i], now) { onProgram(day[i]) }
            }
        }
    }
}

@Composable
private fun GuidePhoneRow(c: Channel, p: EPGProgram, now: Long, onClick: () -> Unit) {
    val kind = Build164Logic.kind(p.start, p.end, now)
    val replay = kind == Build164Logic.Kind.PAST && Build164Logic.canReplay(p.start, p.end, now, c.catchupDays)
    val bell = kind == Build164Logic.Kind.FUTURE && Store.hasReminder(Reminders.idFor(c.id, p.start))
    Column(Modifier.fillMaxWidth().focusLift(1f, onClick = onClick)) {
        Row(Modifier.fillMaxWidth().height(54.dp).background(if (kind == Build164Logic.Kind.NOW) G_NOW_ROW else Color.Transparent)) {
            Box(Modifier.width(62.dp).fillMaxHeight().background(if (kind == Build164Logic.Kind.NOW) Theme.red else Color.Transparent),
                contentAlignment = Alignment.Center) {
                Text(if (kind == Build164Logic.Kind.NOW) L("NOW") else s2Time(p.start), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, color = when (kind) {
                        Build164Logic.Kind.NOW -> Theme.onAccent
                        Build164Logic.Kind.PAST -> G_DIM
                        Build164Logic.Kind.FUTURE -> Color.White
                    })
            }
            Row(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(p.displayTitle, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (kind == Build164Logic.Kind.NOW) FontWeight.Bold else FontWeight.Normal,
                    color = when (kind) {
                        Build164Logic.Kind.NOW -> G_NOW_TITLE
                        Build164Logic.Kind.PAST -> G_DIM
                        Build164Logic.Kind.FUTURE -> Color.White
                    }, modifier = Modifier.weight(1f))
                if (replay) Icon(Icons.Default.History, L("Watch from the start"), tint = Theme.secondary, modifier = Modifier.size(16.dp))
                if (bell) Icon(Icons.Default.Notifications, L("Reminder set"), tint = Theme.secondary, modifier = Modifier.size(16.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1A1A1A)))
    }
}

// MARK: Android TV

/**
 * Tv-gids op Android TV (build 166: over de hele breedte, geen zijkolom). Eén regel bovenaan: Categorie (menu met Alle
 * zenders, Favorieten, Recent, Evenementen en de categorieën van de server), Zoeken (opent het zenderzoekveld), de dagpillen
 * (alleen dagen met gids-info) en rechts Lijst. Daaronder zenderkolommen (±6 in beeld, horizontaal scrollen; lazy, dus nooit
 * honderden kolommen tegelijk). Per kolom: compacte kop met logo, 2 eerdere programma's, de NU-kaart met beeld en 4 latere.
 * Focus op een NU-kaart: na ±1 s speelt daar de zender live zonder geluid; OK = volledig scherm. OK op een ander programma =
 * het programmablad.
 */
@Composable
fun GuideTV(
    channels: List<Channel>,
    query: String,
    onQuery: (String) -> Unit,
    groupTitle: String,
    onGroups: () -> Unit,
    onList: () -> Unit,
    onPlay: (Channel) -> Unit,
) {
    // build 165: de gids is de startweergave van Live TV; terug werkt dus als op elk tabblad (geen BackHandler meer)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    var dayOffset by rememberSaveable { mutableIntStateOf(0) }
    var program by remember { mutableStateOf<Pair<Channel, EPGProgram>?>(null) }
    var searchOpen by rememberSaveable { mutableStateOf(query.isNotEmpty()) }
    val searchFocus = remember { FocusRequester() }
    DisposableEffect(Unit) { onDispose { ChannelPreview.stop() } }
    val rowState = rememberLazyListState()
    // build 166: alleen dagen met gids-info; steekproef = de zenders in beeld plus de eerste uit de lijst (max. 50)
    val inView by remember { derivedStateOf { rowState.layoutInfo.visibleItemsInfo.map { it.index } } }
    val offsets = remember(channels, inView, Playlists.library.epg, AllServers.version, now / 3_600_000L) {
        val idx = Build166Logic.GuideDays.sample(channels.size, inView)
        guideAvailableDays(idx.map { channels[it] }, now)
    }
    val day = Build166Logic.GuideDays.keep(dayOffset, offsets)
    val keys = remember(channels) {
        val seen = HashMap<String, Int>()
        channels.map { c -> val n = seen.merge(c.id, 1, Int::plus)!!; if (n == 1) c.id else "${c.id}#$n" }
    }
    // ±6 kolommen in beeld (1920 breed: ±270 pt per kolom), de rest horizontaal scrollen
    val screenW = LocalConfiguration.current.screenWidthDp
    val colW = ((screenW - 2 * 36 - 5 * 12) / 6.3f).coerceIn(118f, 220f).dp

    Column(Modifier.fillMaxSize().background(Color.Black).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // Categorie: de gekozen naam met ▾; opent het menu met alle groepen
            S2LiveHeaderButton((if (query.isNotEmpty()) L("All channels") else groupTitle) + " ▾", Icons.Default.FilterList,
                onClick = onGroups)
            // Zoeken: opent het bestaande zenderzoekveld
            S2LiveHeaderButton(L("Search"), Icons.Default.Search) {
                if (searchOpen && query.isEmpty()) searchOpen = false else searchOpen = true
            }
            if (searchOpen) {
                LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
                S2TextField(query, onQuery, L("Search channel"), modifier = Modifier.width(240.dp).focusRequester(searchFocus), plain = false,
                    leading = { Icon(Icons.Default.Search, null, tint = Theme.secondary) },
                    trailing = {
                        S2IconButton(Icons.Default.Close, L("Clear search"), tint = Theme.secondary, size = 18.dp) {
                            onQuery(""); searchOpen = false
                        }
                    })
            }
            Box(Modifier.width(1.dp).height(24.dp).background(G_LINE))
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                offsets.forEach { o ->
                    var focused by remember { mutableStateOf(false) }
                    val on = o == day
                    Text(guideDayLabel(o, now), fontSize = 14.sp, maxLines = 1,
                        fontWeight = if (on) FontWeight.ExtraBold else FontWeight.Medium,
                        color = if (on || focused) Color.Black else Theme.secondary,
                        modifier = Modifier.onFocusChanged { focused = it.isFocused }
                            .focusLift(1.08f) { dayOffset = o }
                            .clip(RoundedCornerShape(50))
                            .background(if (on) Color.White else if (focused) Color(0xFFE6E6E6) else Color(0xFF1C1C1C))
                            .padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
            // Lijst: de bestaande wisselknop naar de zenderlijst (met de zijkolom met categorieën)
            S2LiveHeaderButton(L("List"), Icons.AutoMirrored.Filled.ViewList, onClick = onList)
        }
        if (channels.isEmpty()) {
            Text(if (query.isNotEmpty()) L("No channels found.") else L("No channels."), color = Theme.secondary,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 40.dp))
            return@Column
        }
        LazyRow(Modifier.fillMaxSize(), state = rowState, contentPadding = PaddingValues(horizontal = 36.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(channels.size, key = { keys[it] }, contentType = { "col" }) { i ->
                val c = channels[i]
                GuideTVColumn(c, day, now, colW, onPlay = { onPlay(c) }, onProgram = { p -> program = c to p })
            }
        }
    }
    program?.let { (c, p) -> ProgramSheet(c, p, onDismiss = { program = null }, fromGuide = true) }
}

@Composable
private fun GuideTVColumn(c: Channel, dayOffset: Int, now: Long, width: Dp, onPlay: () -> Unit, onProgram: (EPGProgram) -> Unit) {
    val all = remember(c.id, Playlists.library.epg, AllServers.version) { guidePrograms(c) }
    val anchorT = Build164Logic.Days.anchor(now, dayOffset)
    val col = remember(all, dayOffset, now / 60_000L) { Build164Logic.Index.column(all, anchorT, { it.start }, { it.end }) }
    val today = dayOffset == 0
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // build 166: compacte kop (±56 pt): logo op een donker vlak met een dunne rand, of de opgeschoonde naam
        GuideLogo(c, Modifier.fillMaxWidth().height(30.dp).padding(bottom = 2.dp), fontSize = 11.sp, radius = 6.dp)
        col.before.forEach { idx -> GuideTVLine(all[idx], dim = true) { onProgram(all[idx]) } }
        val anchor = if (col.anchor >= 0) all[col.anchor] else null
        GuideTVNowCard(c, anchor, now, live = today && anchor != null,
            onClick = { if (today && (anchor == null || anchor.isNow)) onPlay() else if (anchor != null) onProgram(anchor) })
        col.after.forEach { idx -> GuideTVLine(all[idx], dim = false) { onProgram(all[idx]) } }
    }
}

@Composable
private fun GuideTVLine(p: EPGProgram, dim: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.focusLift(1.04f, onClick = onClick)
        .clip(RoundedCornerShape(6.dp)).background(if (focused) Color.White else Color.Transparent)
        .padding(horizontal = 4.dp, vertical = 3.dp)) {
        Text(s2Time(p.start), fontSize = 11.sp, maxLines = 1, modifier = Modifier.width(36.dp),
            color = if (focused) Color.DarkGray else if (dim) G_DIM else Theme.secondary)
        Text(p.displayTitle, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (focused) Color.Black else if (dim) G_DIM else Color(0xFFE6E6E6))
    }
}

@Composable
private fun GuideTVNowCard(c: Channel, p: EPGProgram?, now: Long, live: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    // build 166: het beeld vult de bovenkant van rand tot rand (16:9), geen kader in een kader; focus = de standaard
    // tv-lift en schaduw (focusLift)
    Column(Modifier.fillMaxWidth()
        .onFocusChanged {
            val was = focused
            focused = it.isFocused
            // ±1 s na de focus speelt de zender live (ChannelPreview wacht zelf tot de focus stilstaat); weg = stoppen
            if (live) { if (it.isFocused) ChannelPreview.focus(c) else if (was) ChannelPreview.blur(c) }
        }
        .focusLift(1.08f, onClick = onClick)
        .clip(shape).background(if (focused) Color(0xFF262626) else Color(0xFF161616))) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            GuidePicture(c, now, Modifier.matchParentSize(), live = live && focused, logoFraction = 0.5f, framed = false)
            if (live) GuideBadge(L("LIVE"), Modifier.align(Alignment.TopStart).padding(6.dp), size = 9.sp)
            GuideStillAge(c, now, focused && live)
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (p != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s2Time(p.start), color = G_NOW_TITLE, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                    Spacer(Modifier.width(6.dp))
                    Text(p.displayTitle, color = if (focused) Color.White else Color(0xFFE6E6E6), fontSize = 12.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (live) Box(Modifier.clip(RoundedCornerShape(2.dp))) { ProgressBar(p.progress) }
            } else {
                Text(c.displayName, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(L("No guide information"), color = Theme.secondary, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

/** "speelt zonder geluid" bij het live beeld; anders de tijd van het stilstaande beeld. */
@Composable
private fun BoxScope.GuideStillAge(c: Channel, now: Long, liveFocused: Boolean) {
    if (liveFocused && ChannelPreview.channel?.id == c.id && ChannelPreview.playing) {
        Text(L("Playing without sound"), color = Color.White, fontSize = 9.sp, maxLines = 1,
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 6.dp, vertical = 2.dp))
        return
    }
    val v = ChannelStills.version
    val f = remember(c.id, v, now / 60_000L) { ChannelStills.usable(guideStillKey(c), now) } ?: return
    val at = remember(f, v) { f.lastModified() }
    Text(s2Time(at), color = Theme.secondary, fontSize = 9.sp, maxLines = 1,
        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 6.dp, vertical = 2.dp))
}
