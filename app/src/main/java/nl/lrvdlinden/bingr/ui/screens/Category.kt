package nl.lrvdlinden.bingr

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Een categorie die volledig getoond wordt ("Bekijk allemaal"). */
data class CategoryTarget(val title: String, val items: List<DetailTarget>)

/** Build 179: sorteren per categorie (docs/ACCOUNT.md "Build 179", `sort`), per profiel gesynct (`categorySort`). */
private fun sortTitle179(mode: String): String = when (mode) {
    "name" -> L("Name A–Z")
    "year" -> L("Year (newest)")
    "added" -> L("Recently added")
    "rating" -> L("Highest rated")
    else -> L("Default")
}

/** Sleutel `"<server>|<movie|series>|<categorie>"` (`*` = over alle servers), of null bij een gemengde lijst. */
private fun sortKey179(t: CategoryTarget): String? {
    val items = t.items
    if (items.isEmpty()) return null
    val kind = when { items.all { it is DetailTarget.MovieT } -> "movie"; items.all { it is DetailTarget.SeriesT } -> "series"; else -> return null }
    val sids = items.map { it.sourceID }.toSet()
    val server = if (sids.size == 1) (sids.first() ?: Playlists.source?.id ?: return null) else "*"
    val groups = items.map { it.group }.toSet()
    val cat = if (groups.size == 1 && groups.first().isNotBlank()) groups.first() else t.title
    return Build179Logic.Sort.key(server, kind, cat)
}

private fun categoryRating(t: DetailTarget): Double? {
    val imdb = when (t) {
        is DetailTarget.MovieT -> Metadata.cached(t.movie)?.imdbRating
        is DetailTarget.SeriesT -> Metadata.cached(t.series)?.imdbRating
    }
    imdb?.toDoubleOrNull()?.let { return it }
    return t.rating?.takeIf { it > 0 }
}

private fun categoryPoster(t: DetailTarget): String? = when (t) {
    is DetailTarget.MovieT -> t.movie.poster ?: Metadata.cached(t.movie)?.poster
    is DetailTarget.SeriesT -> t.series.poster ?: Metadata.cached(t.series)?.poster
}

/** Categoriepagina zoals in UHF: terug, titel in hoofdletters, sorteren en een raster met score (= CategoryView.swift). */
@Composable
fun CategoryScreen(target: CategoryTarget, onClose: () -> Unit) {
    val tv = AppState.isTV
    BackHandler { onClose() }
    val sortKey = remember(target) { sortKey179(target) }
    var sortLocal by remember { mutableStateOf(Build179Logic.Sort.mode(Prefs.string("category.sort179"))) }
    val sort = sortKey?.let { Store.categorySortOf(it) } ?: sortLocal
    var menu by remember { mutableStateOf(false) }
    val metaVersion = Metadata.version
    val sorted = remember(target, sort, metaVersion) {
        val items = target.items
        val order = Build179Logic.Sort.order(sort, items.map { t ->
            Build179Logic.Sort.Item(t.title, t.year, when (t) { is DetailTarget.MovieT -> t.movie.addedAt; is DetailTarget.SeriesT -> t.series.addedAt },
                categoryRating(t))
        })
        order.map { items[it] }
    }
    val firstFocus = rememberInitialFocus()

    Column(Modifier.fillMaxSize().background(Theme.bg).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = Theme.edge - tvs(6), end = Theme.edge - tvs(6), top = tvs(6)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(tvs(12))
        ) {
            if (!tv) {
                Box(Modifier.size(40.dp).focusLift { onClose() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, L("Back"), tint = Color.White, modifier = Modifier.size(24.dp))
                }
            }
            Text(
                target.title.uppercase(), color = Color.White, fontSize = Theme.titleSize, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            Box {
                Box(
                    Modifier.size(tvs(40)).focusLift(1.12f) { menu = true }.clip(CircleShape)
                        .background(if (tv) Color.White.copy(alpha = 0.1f) else Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.SwapVert, L("Sort"), tint = Color.White, modifier = Modifier.size(tvs(22)))
                }
                // keuzemenu: het gekozen item krijgt focus (afstandsbediening)
                S2Menu(menu, Build179Logic.Sort.MODES.map { it to sortTitle179(it) }, sort, onDismiss = { menu = false }) { m ->
                    menu = false
                    if (sortKey != null) Store.setCategorySort(sortKey, m) else { sortLocal = m; Prefs.put("category.sort179", m) }
                }
            }
        }

        LazyVerticalGrid(
            columns = if (tv) GridCells.Fixed(6) else GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(horizontal = Theme.edge, vertical = tvs(12)),
            horizontalArrangement = Arrangement.spacedBy(if (tv) 28.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(tvs(18)),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(sorted) { i, t ->
                CategoryCell(
                    t,
                    Modifier
                        .then(if (i == 0) Modifier.focusRequester(firstFocus) else Modifier)
                        .focusLift(1.06f) { ScreensNav.detail(t) }
                )
            }
        }
    }
}

@Composable
internal fun CategoryCell(t: DetailTarget, modifier: Modifier) {
    val watched = (t as? DetailTarget.MovieT)?.let { Playables.isWatched(it.movie, Trakt.imdb(it.movie)) } ?: false
    val watchedEpisodes = (t as? DetailTarget.SeriesT)?.let { Playables.seriesCardCount(it.series) } ?: 0
    val rating = categoryRating(t)
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(tvs(6)))) {
            RemoteImage(categoryPoster(t), Modifier.matchParentSize(), placeholder = ContentLanguage.displayTitle(t.title))
            // taalstrook (build 59)
            languageLabel(t)?.let { LanguageStrip(it, Modifier.align(Alignment.BottomCenter)) }
            if (watched) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(6.dp).size(tvs(20)).clip(CircleShape).background(Theme.red),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Check, null, tint = Theme.onAccent, modifier = Modifier.size(tvs(13))) }
            }
            // series (build 75): rood label "N BEKEKEN" linksboven
            if (watchedEpisodes > 0) PosterLabel(L("%d WATCHED", watchedEpisodes), Modifier.align(Alignment.TopStart).padding(6.dp))
            if (rating != null) {
                Row(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(Theme.imdb)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Star, null, tint = Color.Black, modifier = Modifier.size(tvs(10)))
                    Spacer(Modifier.width(3.dp))
                    Text(String.format(java.util.Locale.US, "%.1f", rating), color = Color.Black, fontSize = tsp(11), fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(tvs(6)))
        Text(
            ContentLanguage.displayTitle(t.title), color = Color.White, fontSize = tsp(13), fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis
        )
    }
}
