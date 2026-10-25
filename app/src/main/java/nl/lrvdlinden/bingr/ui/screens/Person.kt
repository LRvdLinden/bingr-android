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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Personenpagina in Netflix-stijl (= PersonView.swift): grote ronde foto, naam, bekend van,
 * geboortedatum/-plaats, biografie, dan "Beschikbaar in Bingr" (titels uit je afspeellijst) en
 * "Meer met …" (de rest, alleen ter info). Kinderprofielen zien alleen wat in hun lijst staat.
 */
@Composable
fun PersonScreen(ref: PersonRef, onClose: () -> Unit) {
    val tv = AppState.isTV
    // kinderprofiel of leeftijdsgrens: alleen wat (gefilterd) in de eigen lijst staat
    val kids = Store.activeProfile?.effectiveAgeLimit != null
    var person by remember(ref) { mutableStateOf<TMDB.Person?>(null) }
    var available by remember(ref) { mutableStateOf(emptyList<Pair<TMDB.Credit, DetailTarget>>()) }
    var more by remember(ref) { mutableStateOf(emptyList<TMDB.Credit>()) }
    var loading by remember(ref) { mutableStateOf(true) }
    var bioOpen by remember(ref) { mutableStateOf(false) }
    var info by remember { mutableStateOf<TMDB.Credit?>(null) }

    val lib = Playlists.library
    val pid = Playlists.source?.id
    LaunchedEffect(ref, System.identityHashCode(lib)) {
        loading = true
        val id = screensSafe { People.resolve(ref) }
        if (id != null) {
            person = screensSafe { People.person(id) } ?: person
            val all = screensSafe { People.credits(id) } ?: emptyList()
            val split = screensSafe { People.split(all, lib, "${pid ?: ""}-${lib.movies.size}-${lib.series.size}") }
            available = split?.inLibrary ?: emptyList()
            more = split?.other ?: emptyList()
        }
        loading = false
    }

    BackHandler(enabled = info == null) { onClose() }

    val p = person
    val name = p?.name?.takeIf { it.isNotBlank() } ?: ref.name
    val photo = p?.photo ?: ref.photo
    val bio = p?.biography
    val firstFocus = rememberInitialFocus(key = ref)
    val photoSize = if (tv) 170.dp else 180.dp

    Box(Modifier.fillMaxSize().background(Theme.bg)) {
        Column(
            Modifier.fillMaxSize().blockFocus(info != null).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(tvs(22))
        ) {
            // MARK: kop
            val header: @Composable () -> Unit = {
                Box(Modifier.size(photoSize).clip(CircleShape).background(Theme.elevated), contentAlignment = Alignment.Center) {
                    Text(initials(name), color = Theme.secondary, fontSize = tsp(40), fontWeight = FontWeight.Bold)
                    if (photo != null) RemoteImage(photo, Modifier.matchParentSize())
                }
            }
            val facts: @Composable (Alignment.Horizontal, TextAlign) -> Unit = { align, ta ->
                Column(horizontalAlignment = align, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(name, color = Color.White, fontSize = tsp(30), fontWeight = FontWeight.Black, textAlign = ta, lineHeight = tsp(34))
                    p?.department?.takeIf { it.isNotBlank() }?.let {
                        Text("${L("Known for")}: ${L(it)}", color = Theme.secondary, fontSize = tsp(15), textAlign = ta)
                    }
                    bornLine(p)?.let { Text(it, color = Theme.secondary, fontSize = tsp(15), textAlign = ta) }
                }
            }
            if (tv) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Theme.edge).padding(top = 36.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(32.dp)
                ) {
                    header()
                    facts(Alignment.Start, TextAlign.Start)
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Theme.edge).padding(top = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    header()
                    facts(Alignment.CenterHorizontally, TextAlign.Center)
                }
            }

            // MARK: biografie (op TV focusbaar: OK klapt open)
            if (bio != null) {
                var focused by remember { mutableStateOf(false) }
                Column(
                    Modifier.padding(horizontal = Theme.edge).widthIn(max = 820.dp).fillMaxWidth()
                        .focusRequester(firstFocus)
                        .onFocusChanged { focused = it.isFocused }
                        .focusLift(1.01f) { bioOpen = !bioOpen }
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (tv && focused) Color.White.copy(alpha = 0.10f) else Color.Transparent)
                        .padding(if (tv) 12.dp else 0.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(L("Biography").uppercase(), color = Theme.secondary, fontSize = tsp(14), fontWeight = FontWeight.SemiBold)
                    Text(
                        bio, color = Color.White.copy(alpha = 0.92f), fontSize = tsp(15),
                        maxLines = if (bioOpen) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (loading && available.isEmpty() && more.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(top = 20.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(tvs(26)))
                }
            }

            // MARK: beschikbaar in Bingr
            if (available.isNotEmpty()) {
                PersonRow(L("Available in Bingr")) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = Theme.edge, vertical = if (tv) 14.dp else 0.dp),
                        horizontalArrangement = Arrangement.spacedBy(Theme.rowSpacing)
                    ) {
                        itemsIndexed(available, key = { _, x -> x.first.key }) { i, (c, t) ->
                            PosterCard(
                                t.title, t.poster ?: c.poster,
                                modifier = Modifier
                                    .then(if (i == 0 && bio == null) Modifier.focusRequester(firstFocus) else Modifier)
                                    .focusLift { ScreensNav.detail(t) },
                                watched = (t as? DetailTarget.MovieT)?.let { Playables.isWatched(it.movie, Trakt.imdb(it.movie)) } ?: false,
                                language = languageLabel(t),
                                watchedEpisodes = (t as? DetailTarget.SeriesT)?.let { Playables.watchedEpisodeCount(it.series) } ?: 0
                            )
                        }
                    }
                }
            }

            // MARK: meer met … (niet voor kinderprofielen en profielen met een leeftijdsgrens)
            if (!kids && more.isNotEmpty()) {
                PersonRow(L("More with %@", name)) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = Theme.edge, vertical = if (tv) 14.dp else 0.dp),
                        horizontalArrangement = Arrangement.spacedBy(Theme.rowSpacing)
                    ) {
                        itemsIndexed(more.take(60), key = { _, x -> x.key }) { i, c ->
                            PosterCard(
                                c.title, c.poster,
                                modifier = Modifier
                                    .then(if (i == 0 && bio == null && available.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier)
                                    .focusLift { info = c }
                            )
                        }
                    }
                }
            }

            if (!loading && p == null && available.isEmpty() && more.isEmpty()) {
                Text(
                    L("Nothing found for “%@”.", name), color = Theme.secondary, fontSize = tsp(15), textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Theme.edge, vertical = 20.dp)
                )
            }
            Spacer(Modifier.navigationBarsPadding().height(40.dp))
        }

        // sluitknop (telefoon)
        if (!tv) {
            Box(
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 8.dp, end = 16.dp)
                    .size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))
                    .focusLift { onClose() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Close, L("Close"), tint = Color.White, modifier = Modifier.size(20.dp)) }
        }

        info?.let { c -> CreditInfoDialog(c) { info = null } }
    }
}

@Composable
private fun PersonRow(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(tvs(8))) {
        Text(
            title, color = Color.White, fontSize = Theme.titleSize, fontWeight = FontWeight.Bold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = Theme.edge)
        )
        content()
    }
}

/** Titel die niet in je afspeellijsten staat: alleen info, geen afspelen. */
@Composable
private fun CreditInfoDialog(c: TMDB.Credit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        val fr = remember { FocusRequester() }
        Column(
            Modifier.widthIn(min = 260.dp, max = 520.dp).clip(RoundedCornerShape(16.dp)).background(Theme.elevated)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (c.poster != null) {
                    RemoteImage(c.poster, Modifier.width(tvs(80)).height(tvs(120)).clip(RoundedCornerShape(6.dp)))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(c.title, color = Color.White, fontSize = tsp(18), fontWeight = FontWeight.Bold)
                    c.year?.let { Text("$it", color = Theme.secondary, fontSize = tsp(14)) }
                    Text(L("Not in your playlists"), color = Theme.red, fontSize = tsp(13), fontWeight = FontWeight.SemiBold)
                }
            }
            c.overview?.let {
                Text(
                    it, color = Color.White.copy(alpha = 0.9f), fontSize = tsp(14),
                    modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())
                )
            }
            Text(
                L("Close"), color = Color.White, fontSize = tsp(15), fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.End).focusRequester(fr).focusLift(1.08f) { onDismiss() }.padding(8.dp)
            )
            LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
        }
    }
}

private fun initials(name: String): String =
    name.split(" ").filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1) }

/** "Geboren 12 maart 1970, Londen" (datum in de app-taal). */
private fun bornLine(p: TMDB.Person?): String? {
    p ?: return null
    val date = p.birthday?.let { b ->
        runCatching { Build149Logic.Dates.fmt("yyyy-MM-dd").parse(b) }.getOrNull()
            ?.let { DateFormat.getDateInstance(DateFormat.LONG, Localizer.locale).format(it) } ?: b
    }
    val place = p.birthplace?.takeIf { it.isNotBlank() }
    val text = listOfNotNull(date, place).joinToString(", ").ifEmpty { return null }
    return L("Born %@", text)
}
