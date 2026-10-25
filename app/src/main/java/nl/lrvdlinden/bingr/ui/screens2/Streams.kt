package nl.lrvdlinden.bingr

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date

// Tegelijk kijken, wie kijkt er nu en kijkgeschiedenis van 7 dagen (build 51), zie docs/ACCOUNT.md in de iOS-repo.

// MARK: profielavatar per regel (build 57)

/** Profielfoto's voor de lijsten, per profiel-id + photoVersion, zodat elke foto maar één keer wordt ingelezen. */
private val rowPhotoCache = android.util.LruCache<String, android.graphics.Bitmap>(24)

/** Profiel van het account bij een regel: eerst op profile_id, anders (oudere regels) op profile_name. */
private fun rowProfile(profileId: String, profileName: String): ViewerProfile? {
    val profiles = Store.profiles
    if (profileId.isNotBlank()) profiles.firstOrNull { it.id.equals(profileId, ignoreCase = true) }?.let { return it }
    val name = profileName.trim()
    if (name.isEmpty()) return null
    return profiles.firstOrNull { it.name.trim().equals(name, ignoreCase = true) }
}

/**
 * Kleine ronde avatar vóór een regel in "Wie kijkt er nu" en de kijkgeschiedenis: zelfde weergave als bij
 * "Wie kijkt er?" (foto uit de bucket `avatars`, figuurtje of emoji). Onbekend profiel: eerste letter in een gekleurde cirkel.
 */
@Composable
internal fun RowProfileAvatar(profileId: String, profileName: String, size: androidx.compose.ui.unit.Dp = tvs(30)) {
    val v = Store.photoVersion
    val profile = remember(profileId, profileName, Store.profiles) { rowProfile(profileId, profileName) }
    if (profile != null) {
        val key = profile.id + ":" + v
        var photo by remember(key) { mutableStateOf(rowPhotoCache.get(key)) }
        LaunchedEffect(key) {
            if (photo == null) {
                photo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Store.photo(profile.id) }
                    ?.also { rowPhotoCache.put(key, it) }
            }
        }
        AvatarView(profile.avatar, photo, size)
        return
    }
    val name = profileName.trim()
    val pal = PlaylistAvatar.palettes[Math.floorMod(name.lowercase().hashCode(), PlaylistAvatar.palettes.size)]
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(pal.first), Color(pal.second)))),
        contentAlignment = Alignment.Center
    ) {
        Text(name.take(1).uppercase().ifEmpty { "?" }, color = Color.White, fontWeight = FontWeight.Bold,
            fontSize = androidx.compose.ui.unit.TextUnit(size.value * 0.45f, androidx.compose.ui.unit.TextUnitType.Sp))
    }
}

/** "42 min" vanaf het begin van de stream. */
private fun streamDuration(startedAt: Long?): String? {
    val s = startedAt ?: return null
    val min = ((System.currentTimeMillis() - s) / 60_000L).coerceAtLeast(0L).toInt()
    return L("%d min", min)
}

/** Eén stream: apparaat-icoon en naam, profiel en titel, en optioneel hoe lang al en sinds wanneer ingelogd. */
@Composable
private fun StreamRow(s: Account.StreamInfo, details: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = tvs(12)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(tvs(42)).clip(CircleShape).background(Theme.elevated), contentAlignment = Alignment.Center) {
            Icon(deviceIcon(s.platform), null, tint = Color.White, modifier = Modifier.size(tvs(22)))
        }
        Spacer(Modifier.width(tvs(12)))
        RowProfileAvatar(s.profileId, s.profileName)
        Spacer(Modifier.width(tvs(12)))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(deviceNameWithLabel(s.deviceName, s.platform), color = Color.White, fontSize = tsp(15), fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (s.current) {
                    Spacer(Modifier.width(8.dp))
                    Text(L("This device"), color = Theme.onAccent, fontSize = tsp(11), fontWeight = FontWeight.Bold, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Theme.red).padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
            val what = listOf(s.profileName, s.title).filter { it.isNotBlank() }.joinToString(" · ")
            if (what.isNotEmpty()) Text(what, color = Color.White.copy(alpha = 0.85f), fontSize = tsp(13), maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            if (details) {
                val parts = listOfNotNull(
                    streamDuration(s.startedAt)?.let { L("Watching for %@", it) },
                    s.signedInAt?.let { L("Signed in since %@", deviceDate(it)) },
                )
                if (parts.isNotEmpty()) Text(parts.joinToString(" · "), color = Theme.secondary, fontSize = tsp(12), maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * "Too many people are watching": stream_start gaf 403 stream_limit. Lijst van wie er kijkt, met Try again en Close.
 * Try again kijkt eerst via list_streams of er plek is; zo niet, dan wordt alleen de lijst bijgewerkt.
 */
@Composable
fun StreamLimitScreen(info: Account.StreamList, onRetry: () -> Unit, onClose: () -> Unit) {
    var data by remember(info) { mutableStateOf(info) }
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    // TV (build 60): na de spinner van "Try again" de focus terug op de knop (anders geen focus meer)
    val firstButton = rememberInitialFocus(key = info to working)
    BackHandler { onClose() }

    fun retry() {
        if (working) return
        working = true
        scope.launch {
            val fresh = try { Account.listStreams() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            working = false
            if (fresh != null && fresh.streams.count { !it.current } >= fresh.limit) data = fresh
            else onRetry()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(vertical = tvs(28)),
        ) {
            item {
                Column(Modifier.widthIn(max = S2MaxWidth).fillMaxWidth().padding(horizontal = Theme.edge),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(tvs(8))) {
                    Text(L("Too many people are watching"), color = Color.White, fontSize = tsp(26), fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center)
                    Text(if (data.limit == 1) L("Your account can be used on 1 screen at a time.")
                         else L("Your account can be used on up to %d screens at the same time.", data.limit),
                        color = Theme.secondary, fontSize = tsp(15), textAlign = TextAlign.Center)
                    // build 88: Gratis → met Lifetime meer schermen tegelijk
                    if (data.isFree) {
                        Text(L("Upgrade to Lifetime to watch on up to %d screens at the same time.", Entitlements.LIFETIME_STREAMS) +
                            " " + L("How many you can watch at once also depends on your IPTV provider."),
                            color = Color.White, fontSize = tsp(14), textAlign = TextAlign.Center)
                    }
                }
            }
            if (data.streams.isNotEmpty()) {
                item { S2Header(L("Watching now")) }
                itemsIndexed(data.streams, key = { _, s -> s.deviceId }) { i, s ->
                    Column(Modifier.s2Cell(i, data.streams.size)) {
                        if (i > 0) S2Divider()
                        StreamRow(s, details = false)
                    }
                }
            }
            item {
                Row(
                    Modifier.widthIn(max = S2MaxWidth).fillMaxWidth().padding(horizontal = Theme.edge, vertical = tvs(20)),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (working) CircularProgressIndicator(color = Theme.red)
                    else {
                        NetflixButton(L("Try again"), Icons.Filled.Refresh, modifier = Modifier.focusRequester(firstButton)) { retry() }
                        NetflixButton(L("Close"), Icons.Filled.Close, kind = NetflixKind.SECONDARY) { onClose() }
                    }
                }
            }
            // build 88: Gratis → upgraden naar Lifetime (meer schermen tegelijk)
            if (data.isFree && !working) item {
                UpgradeLimitButton(Entitlements.Feature.STREAMS, Modifier.padding(bottom = tvs(20)))
            }
        }
    }
}

/** Mijn Bingr → Account → "Watching now": per stream apparaat, profiel, titel, hoe lang al en sinds wanneer ingelogd. Ververst elke 30 s. */
@Composable
fun WatchingNowScreen(onClose: () -> Unit) {
    var data by remember { mutableStateOf<Account.StreamList?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        while (true) {
            try { data = Account.listStreams(); error = null }
            catch (e: CancellationException) { throw e }
            catch (e: AccountException) { error = e.message }
            catch (e: Exception) { error = L("Something went wrong. Please try again.") }
            loading = false
            delay(30_000L)
        }
    }

    S2Page(L("Watching now"), onClose = onClose) {
        val d = data
        if (d != null) {
            item { S2Header(L("%d of %d watching now", d.streams.size, d.limit)) }
            if (d.streams.isEmpty()) item { S2Section { S2Row("—", tint = Theme.secondary) } }
            itemsIndexed(d.streams, key = { _, s -> s.deviceId }) { i, s ->
                Column(Modifier.s2Cell(i, d.streams.size)) {
                    if (i > 0) S2Divider()
                    StreamRow(s, details = true)
                }
            }
        }
        if (loading) item {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Theme.red, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            }
        }
        error?.let { e -> item { S2Footer(e) } }
    }
}

private fun watchDayTitle(day: Long): String {
    val today = s2StartOfDay(System.currentTimeMillis())
    val yesterday = s2StartOfDay(today - 12 * 3_600_000L)
    return when (day) {
        today -> L("Today")
        yesterday -> L("Yesterday")
        else -> Build149Logic.Dates.fmt("EEEE d MMMM", Localizer.locale).format(Date(day))
    }
}

/** Film of serie uit de bibliotheek bij een regel uit watch_log (op titel; bij een aflevering het deel vóór " · "). */
private fun watchTarget(e: Account.WatchEntry): DetailTarget? {
    val lib = Playlists.library
    return when (e.kind) {
        "movie" -> lib.movies.firstOrNull { it.title.equals(e.title, ignoreCase = true) }?.let { DetailTarget.MovieT(it) }
        "episode" -> {
            val name = e.title.substringBefore(" · ").trim()
            lib.series.firstOrNull { it.title.equals(name, ignoreCase = true) }?.let { DetailTarget.SeriesT(it) }
        }
        else -> null
    }
}

/** Mijn Bingr → Account → "Watch history (7 days)": alles wat er op het account gekeken is, per dag. */
@Composable
fun WatchLogScreen(onClose: () -> Unit) {
    var entries by remember { mutableStateOf<List<Account.WatchEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<DetailTarget?>(null) }

    LaunchedEffect(Unit) {
        try { entries = Account.watchLog(7); error = null }
        catch (e: CancellationException) { throw e }
        catch (e: AccountException) { error = e.message }
        catch (e: Exception) { error = L("Something went wrong. Please try again.") }
    }

    val shown = detail
    if (shown != null) {
        DetailScreen(shown, { detail = null })
        return
    }

    S2Page(L("Watch history (7 days)"), onClose = onClose) {
        val list = entries
        if (list == null && error == null) item {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Theme.red, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            }
        }
        if (list != null && list.isEmpty()) item { S2Section { S2Row("—", tint = Theme.secondary) } }
        if (list != null) {
            val days = list.groupBy { s2StartOfDay(it.startedAt) }.entries.sortedByDescending { it.key }
            days.forEach { (day, rows) ->
                item { S2Header(watchDayTitle(day)) }
                itemsIndexed(rows) { i, e ->
                    Column(Modifier.s2Cell(i, rows.size)) {
                        if (i > 0) S2Divider()
                        val target = remember(e) { watchTarget(e) }
                        S2Row(
                            title = e.title.ifBlank { "—" },
                            subtitle = listOf(e.profileName, deviceNameWithLabel(e.deviceName, e.platform)).filter { it.isNotBlank() }.joinToString(" · "),
                            onClick = target?.let { t -> { detail = t } },
                            leading = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(s2Time(e.startedAt), color = Theme.secondary, fontSize = tsp(13),
                                        modifier = Modifier.widthIn(min = tvs(44)))
                                    Spacer(Modifier.width(tvs(8)))
                                    RowProfileAvatar(e.profileId, e.profileName)
                                }
                            },
                            trailing = {
                                Column(horizontalAlignment = Alignment.End) {
                                    Icon(deviceIcon(e.platform), null, tint = Theme.secondary, modifier = Modifier.size(tvs(16)))
                                    Spacer(Modifier.height(2.dp))
                                    Text(L("%d min", e.minutes), color = Theme.secondary, fontSize = tsp(11))
                                }
                            }
                        )
                    }
                }
            }
        }
        error?.let { e -> item { S2Footer(e) } }
    }
}
