package nl.lrvdlinden.bingr

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/*
 * Build 173 (versie 1.0.7): bediening in de speler, zoals Netflix en NLZIET. Panelen onderin over het beeld (pauzeren
 * niet): Audio en ondertitels, Zappen en Afleveringen; verder de vraag "Vanaf het begin", het scherm "Slaaptimer: gestopt"
 * en de animatie bij dubbeltikken. Logica: [Build173PlayerLogic].
 */

private val panelBG = Color(0xF2141414)

/** Tijd als "22:14" in de taal van de app. */
fun clock173(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT, Localizer.locale).format(Date(ms))

/** Wat er nu op de zender van de speler is (volgens de gids); null bij films/series of zonder gids. */
fun currentProgram173(player: PlayerController): EPGProgram? {
    if (!player.isLive) return null
    val cid = player.item.channelID ?: return null
    val c = Playlists.library.channels.firstOrNull { it.id == cid } ?: return null
    return Playlists.nowOf(c)
}

// MARK: - Slaaptimer

/** Keuzes: na deze aflevering (live: na dit programma, alleen met gids) / 30 / 60 / 90 minuten / uit. */
fun sleepChoices173(player: PlayerController, programEnd: Long?): List<Build173PlayerLogic.Sleep> =
    Build173PlayerLogic.Sleep.choices.filter {
        it != Build173PlayerLogic.Sleep.EndOfEpisode || player.item.type == MediaType.EPISODE || (player.isLive && programEnd != null)
    }

fun sleepTitle173(s: Build173PlayerLogic.Sleep, live: Boolean): String = when (s) {
    Build173PlayerLogic.Sleep.Off -> L("Off")
    Build173PlayerLogic.Sleep.EndOfEpisode -> if (live) L("After this programme") else L("After this episode")
    is Build173PlayerLogic.Sleep.Minutes -> L("%d minutes", s.m)
}

/** Aftellen van de slaaptimer ("12:34"), elke seconde bijgewerkt; null zonder minutentimer. */
@Composable
fun sleepCountdown173(player: PlayerController): String? {
    val at = player.sleepAt ?: return null
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(at) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
    return Build173PlayerLogic.countdown(at - now)
}

/** "Stopt over 12:34" / "Stopt na deze aflevering"; null = slaaptimer uit. */
@Composable
fun sleepLabel173(player: PlayerController): String? {
    val c = sleepCountdown173(player)
    return when {
        c != null -> L("Stops in %@", c)
        player.sleep == Build173PlayerLogic.Sleep.EndOfEpisode ->
            if (player.isLive) L("Stops after this programme") else L("Stops after this episode")
        else -> null
    }
}

// MARK: - Paneel onderin over het beeld

/**
 * Paneel onderin over de video (geen dialoog, de speler loopt door). Tik ernaast of Terug sluit het. Op de tv krijgt
 * [initialFocus] (of anders Sluiten) de eerste focus.
 */
@Composable
fun BottomPanel173(
    title: String,
    onClose: () -> Unit,
    initialFocus: FocusRequester? = null,
    state: LazyListState = rememberLazyListState(),
    header: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val cfg = LocalConfiguration.current
    val tv = AppState.isTV
    val closeFocus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize()) {
        // naast het paneel tikken = sluiten (niet de bediening eronder)
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onClose() } })
        Column(
            Modifier
                .align(if (tv) Alignment.BottomStart else Alignment.BottomCenter)
                .padding(start = if (tv) Theme.edge else 0.dp)
                .widthIn(max = if (tv) 820.dp else 640.dp)
                .fillMaxWidth(if (tv) 0.62f else 1f)
                .heightIn(max = (cfg.screenHeightDp * (if (tv) 0.58f else 0.62f)).dp)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(panelBG)
                .pointerInput(Unit) { detectTapGestures { } }
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = tsp(17), modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                PlayerFocusText(L("Close"), Modifier.focusRequester(closeFocus), bold = true, color = Theme.red, onClick = onClose)
            }
            header?.invoke()
            LazyColumn(Modifier.fillMaxWidth().padding(bottom = 10.dp), state = state, content = content)
        }
    }
    LaunchedEffect(Unit) {
        if (tv) {
            delay(150)
            val ok = initialFocus?.let { runCatching { it.requestFocus() }.isSuccess } ?: false
            if (!ok) runCatching { closeFocus.requestFocus() }
        }
    }
}

// MARK: - A. Audio en ondertitels

/**
 * Eén paneel voor audio (sporen en andere taalversies), ondertitels (uit, sporen van de stream, OpenSubtitles) en de
 * weergave (grootte, vertraging). Vervangt de losse knoppen; de keuzes werken zoals voorheen.
 */
@Composable
fun AudioSubsPanel173(
    player: PlayerController,
    versions: List<ContentLanguage.Version>,
    onVersion: (ContentLanguage.Version) -> Unit,
    onOpenSubtitles: (() -> Unit)?,
    onClose: () -> Unit,
) {
    val audio = player.audioTracks
    val subs = player.subtitleTracks
    val curAudio = player.currentAudio
    val curSub = player.currentSubtitle
    val ext = player.externalSubtitleName
    val offset = player.subtitleDelay
    val single = player.hasSingleAudio
    val first = remember { FocusRequester() }
    BottomPanel173(L("Audio & subtitles"), onClose, initialFocus = first) {
        item { PlayerSheetHeader(L("Audio")) }
        if (single) {
            val lang = player.currentAudioLanguage
            item {
                PlayerSheetRow(lang?.let { TrackPreferences.label(it) } ?: audio.firstOrNull()?.let { flaggedTrackName(it) } ?: L("Audio"),
                    selected = true, modifier = Modifier.focusRequester(first)) {}
            }
            item { PlayerSheetNote(L("This stream has one audio language")) }
        } else {
            audio.forEachIndexed { i, t ->
                item {
                    PlayerSheetRow(flaggedTrackName(t), selected = t.id == curAudio,
                        modifier = if (t.id == curAudio || (curAudio == null && i == 0)) Modifier.focusRequester(first) else Modifier) {
                        player.selectAudio(t)
                    }
                }
            }
        }
        if (versions.isNotEmpty()) {
            item { PlayerSheetHeader(L("Other language")) }
            for (v in versions) item {
                PlayerSheetRow(listOfNotNull(v.info?.longName ?: "?", ContentLanguage.displayTitle(v.target.title)).joinToString(" · "),
                    icon = Icons.Filled.Translate) { onVersion(v) }
            }
        }

        item { PlayerSheetHeader(L("Subtitles")) }
        item { PlayerSheetRow(L("Off"), selected = curSub == null) { player.selectSubtitle(null) } }
        if (ext != null && player.hasExternalSubtitle) item { PlayerSheetRow("OpenSubtitles · $ext", selected = true) {} }
        for (t in subs) item { PlayerSheetRow(flaggedTrackName(t), selected = t.id == curSub) { player.selectSubtitle(t) } }
        if (onOpenSubtitles != null) item { PlayerSheetRow(L("Search OpenSubtitles…"), icon = Icons.Filled.Search) { onOpenSubtitles() } }

        item { PlayerSheetHeader(L("Subtitle display")) }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                for (sz in SubtitleSize.entries) {
                    val sel = SubtitleSize.current == sz
                    PlayerFocusText(sz.title, bold = sel, color = if (sel) Theme.red else Color.White) {
                        if (SubtitleSize.current != sz) { SubtitleSize.current = sz; player.applySubtitleSize() }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    PlayerSheetItem(onClick = { player.subtitleDelay = offset - 0.5 }) {
                        Icon(Icons.Filled.RemoveCircle, null, tint = Color.White, modifier = Modifier.size(tvs(24)))
                    }
                }
                Text(
                    if (offset == 0.0) L("In sync") else String.format(Locale.ROOT, "%+.1f s", offset),
                    color = Color.White, fontSize = tsp(16), fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1.4f)
                )
                Box(Modifier.weight(1f)) {
                    PlayerSheetItem(onClick = { player.subtitleDelay = offset + 0.5 }) {
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Filled.AddCircle, null, tint = Color.White, modifier = Modifier.size(tvs(24)))
                    }
                }
            }
        }
        if (offset != 0.0) item { PlayerSheetRow(L("Reset")) { player.subtitleDelay = 0.0 } }
    }
}

// MARK: - 3. Zappen als lijst

/** Zenders met wat er nu op is (titel, voortgang); de huidige zender heeft de eerste focus. Kiezen = overschakelen. */
@Composable
fun ZapPanel173(channels: List<Channel>, currentID: String?, onPick: (Channel) -> Unit, onClose: () -> Unit) {
    val start = channels.indexOfFirst { it.id == currentID }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (start - 1).coerceAtLeast(0))
    val cur = remember { FocusRequester() }
    val firstRow = remember { FocusRequester() }
    BottomPanel173(L("Zap"), onClose, initialFocus = if (start >= 0) cur else firstRow, state = state) {
        if (channels.isEmpty()) item { PlayerSheetNote(L("No channels.")) }
        items(channels.size, key = { channels[it].id }) { i ->
            val c = channels[i]
            val isCurrent = c.id == currentID
            val now = Playlists.nowOf(c)
            PlayerSheetItem(onClick = { onPick(c) },
                modifier = when { isCurrent -> Modifier.focusRequester(cur); i == 0 -> Modifier.focusRequester(firstRow); else -> Modifier }) {
                Text(c.number?.toString() ?: "", color = Theme.secondary, fontSize = tsp(13), fontFamily = FontFamily.Monospace,
                    modifier = Modifier.widthIn(min = tvs(30)))
                RemoteImage(c.logo, Modifier.width(tvs(56)).height(tvs(34)).clip(RoundedCornerShape(4.dp)),
                    placeholder = c.displayName.take(3), fit = true)
                Column(Modifier.weight(1f)) {
                    Text(c.displayName, color = if (isCurrent) Theme.red else Color.White, fontSize = tsp(15),
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (now != null) {
                        Text(now.displayTitle, color = Theme.secondary, fontSize = tsp(12), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.fillMaxWidth(0.6f).clip(RoundedCornerShape(2.dp))) { ProgressBar(now.progress) }
                    }
                }
                if (isCurrent) Icon(Icons.Filled.Sensors, null, tint = Theme.red, modifier = Modifier.size(tvs(18)))
            }
        }
    }
}

// MARK: - 5. Afleveringen in de speler

/** Seizoenen en afleveringen met voortgang; kiezen speelt direct af (de speler blijft open). */
@Composable
fun EpisodesPanel173(series: Series?, episodes: List<Episode>, currentID: String, onPick: (Episode) -> Unit, onClose: () -> Unit) {
    val seasons = remember(episodes) { episodes.map { it.season }.distinct().sorted() }
    val currentSeason = episodes.firstOrNull { it.id == currentID }?.season
    var season by remember(series?.id, currentSeason, seasons) { mutableStateOf(currentSeason ?: seasons.firstOrNull() ?: 0) }
    val list = remember(episodes, season) { episodes.filter { it.season == season } }
    val start = list.indexOfFirst { it.id == currentID }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (start - 1).coerceAtLeast(0))
    val cur = remember { FocusRequester() }
    val firstRow = remember { FocusRequester() }
    BottomPanel173(series?.displayTitle ?: L("Episodes"), onClose, initialFocus = if (start >= 0) cur else firstRow, state = state,
        header = {
            if (seasons.size > 1) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (s in seasons) {
                        val sel = s == season
                        PlayerFocusText(L("Season %d", s), bold = sel, color = if (sel) Theme.red else Color.White) { season = s }
                    }
                }
            }
        }
    ) {
        if (series == null) item {
            Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(26.dp), strokeWidth = 2.5.dp)
            }
        }
        items(list.size, key = { list[it].id }) { i ->
            val e = list[i]
            val isCurrent = e.id == currentID
            val progress = Store.progress[e.id]?.fraction ?: 0.0
            val watched = remember(e.id) { runCatching { Playables.isWatched(e, series?.imdbID) }.getOrDefault(false) }
            PlayerSheetItem(onClick = { onPick(e) },
                modifier = when { isCurrent -> Modifier.focusRequester(cur); i == 0 -> Modifier.focusRequester(firstRow); else -> Modifier }) {
                Box(Modifier.width(tvs(96)).height(tvs(54)).clip(RoundedCornerShape(4.dp)).background(Color.Black)) {
                    RemoteImage(e.still ?: series?.poster, Modifier.fillMaxSize(), placeholder = "${e.number}")
                    if (progress in 0.01..0.97) {
                        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth()) { ProgressBar(progress) }
                    }
                    if (isCurrent) {
                        Icon(Icons.Filled.PlayArrow, null, tint = Color.White,
                            modifier = Modifier.align(Alignment.Center).size(tvs(22)).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text("${e.number}. ${e.displayTitle}", color = if (isCurrent) Theme.red else Color.White, fontSize = tsp(14),
                        fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val mins = e.durationSeconds?.takeIf { it > 0 }?.let { (it + 59) / 60 }
                    if (mins != null) Text(L("%d min", mins), color = Theme.secondary, fontSize = tsp(12))
                }
                if (watched) Icon(Icons.Filled.CheckCircle, null, tint = Theme.secondary, modifier = Modifier.size(tvs(18)))
            }
        }
    }
}

// MARK: - 2. Vraag bij een lopend programma

/** "Dit programma is al bezig": Vanaf het begin (eerste focus) of Live kijken. */
@Composable
fun StartOverPrompt173(program: EPGProgram, onStartOver: () -> Unit, onLive: () -> Unit, modifier: Modifier = Modifier) {
    val first = remember { FocusRequester() }
    Column(
        modifier
            .widthIn(max = 460.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.86f))
            .padding(tvs(16)),
        verticalArrangement = Arrangement.spacedBy(tvs(10))
    ) {
        Text(L("This programme has already started"), color = Color.White, fontSize = tsp(16), fontWeight = FontWeight.Bold)
        Text("${clock173(program.start)}  ${program.displayTitle}", color = Theme.secondary, fontSize = tsp(13), maxLines = 2,
            overflow = TextOverflow.Ellipsis)
        Box(Modifier.fillMaxWidth(0.7f).clip(RoundedCornerShape(2.dp))) { ProgressBar(program.progress) }
        Row(horizontalArrangement = Arrangement.spacedBy(tvs(10))) {
            NetflixButton(L("From the start"), Icons.Filled.SkipPrevious, modifier = Modifier.focusRequester(first)) { onStartOver() }
            NetflixButton(L("Watch live"), Icons.Filled.Sensors, kind = NetflixKind.SECONDARY) { onLive() }
        }
    }
    LaunchedEffect(program.id) { if (AppState.isTV) { delay(150); runCatching { first.requestFocus() } } }
}

// MARK: - 9. Slaaptimer afgegaan

/** Speler gestopt door de slaaptimer (stream afgemeld): Verder kijken of Sluiten. */
@Composable
fun SleepStopped173(radio: Boolean = false, onContinue: () -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.88f)).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(tvs(14))) {
            Icon(Icons.Filled.Bedtime, null, tint = Theme.secondary, modifier = Modifier.size(tvs(52)))
            Text(L("Sleep timer: stopped"), color = Color.White, fontSize = tsp(20), fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(tvs(12))) {
                NetflixButton(if (radio) L("Continue listening") else L("Continue watching"), Icons.Filled.PlayArrow,
                    modifier = Modifier.focusRequester(first)) { onContinue() }
                NetflixButton(L("Close"), kind = NetflixKind.SECONDARY) { onClose() }
            }
        }
    }
    LaunchedEffect(Unit) { delay(150); runCatching { first.requestFocus() } }
}

// MARK: - 4. Dubbeltikken

/** Kleine animatie bij dubbeltikken: "−20 s" links of "+30 s" rechts, groeit mee met het aantal tikken. */
@Composable
fun DoubleTapBadge173(total: Int, modifier: Modifier = Modifier) {
    val forward = total > 0
    val target = 1f + ((kotlin.math.abs(total) / 10f - 1f).coerceIn(0f, 3f)) * 0.08f
    val scale by animateFloatAsState(target, label = "doubleTap")
    Box(modifier.fillMaxSize().padding(horizontal = 48.dp), contentAlignment = if (forward) Alignment.CenterEnd else Alignment.CenterStart) {
        Row(
            Modifier.scale(scale).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (!forward) Icon(Icons.Filled.FastRewind, null, tint = Color.White, modifier = Modifier.size(20.dp))
            Text("${kotlin.math.abs(total)} s", color = Color.White, fontSize = tsp(17), fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace)
            if (forward) Icon(Icons.Filled.FastForward, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}
