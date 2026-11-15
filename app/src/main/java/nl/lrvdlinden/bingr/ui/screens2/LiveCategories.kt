package nl.lrvdlinden.bingr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Build 171: "Alle categorieën" van Live TV als raster: opgeschoonde naam, aantal zenders en een paar logo's, met
 * filterchips per land/regio (voorvoegsel, build 153) plus "Alle" en "Favoriete categorieën". Lang indrukken (of OK
 * vasthouden op de tv) = vastpinnen of verbergen (verbergen = de account-set hiddenCategories).
 */
@Composable
fun LiveCategoriesScreen(merged: Map<String, Set<String>>, channels: List<Channel>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val pid = Playlists.source?.id ?: ""
    val pinned = Store.pinnedCategories
    var chip by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    val byGroup = remember(channels) { channels.groupBy { it.group } }
    fun rawOf(name: String) = merged[name]?.firstOrNull() ?: name
    fun pinnedName(name: String) = Store.isCategoryPinned(pid, rawOf(name))
    val all = remember(merged, byGroup) {
        merged.keys.map { n -> Triple(n, merged[n].orEmpty().sumOf { byGroup[it]?.size ?: 0 },
            merged[n].orEmpty().asSequence().flatMap { byGroup[it].orEmpty().asSequence() }.mapNotNull { it.logo }.distinct().take(4).toList()) }
            .filter { it.second > 0 }
    }
    val chips = remember(all) { Build171Logic.Countries.chips(all.flatMap { merged[it.first].orEmpty().ifEmpty { setOf(it.first) } }) }
    val shown = remember(all, chip, pinned) {
        val f = when (chip) {
            null -> all
            "★" -> all.filter { pinnedName(it.first) }
            else -> all.filter { t -> merged[t.first].orEmpty().ifEmpty { setOf(t.first) }.any { Build171Logic.Countries.matches(it, chip) } }
        }
        Build171Logic.ProfileSets.pinnedFirst(f, pinned) { Build171Logic.ProfileSets.categoryKey(pid, rawOf(it.first)) }
    }
    val tv = AppState.isTV
    val first = rememberInitialFocus(key = chip)
    S2FullDialog(onDismiss) {
        Column(Modifier.fillMaxSize().background(Theme.bg).systemBarsPadding()) {
            S2TopBar(L("All categories"), onClose = onDismiss)
            LazyRow(contentPadding = PaddingValues(horizontal = Theme.edge), horizontalArrangement = Arrangement.spacedBy(tvs(8))) {
                item { LiveChip(L("All"), chip == null) { chip = null } }
                item { LiveChip("★ " + L("Favorite categories"), chip == "★") { chip = "★" } }
                items(chips) { c -> LiveChip(c, chip == c) { chip = c } }
            }
            Spacer(Modifier.height(tvs(10)))
            LazyVerticalGrid(
                columns = if (tv) GridCells.Fixed(4) else GridCells.Adaptive(170.dp),
                contentPadding = PaddingValues(start = Theme.edge, end = Theme.edge, bottom = tvs(40)),
                horizontalArrangement = Arrangement.spacedBy(tvs(10)), verticalArrangement = Arrangement.spacedBy(tvs(10)),
                modifier = Modifier.fillMaxSize()
            ) {
                items(shown, key = { it.first }) { (name, count, logos) ->
                    var focused by remember { mutableStateOf(false) }
                    val shape = RoundedCornerShape(tvs(10))
                    Column(
                        Modifier.fillMaxWidth()
                            .then(if (shown.firstOrNull()?.first == name) Modifier.focusRequester(first) else Modifier)
                            .onFocusChanged { focused = it.isFocused }
                            .focusLift(1.05f, onLongClick = { menuFor = name }) { onPick(name); onDismiss() }
                            .clip(shape).background(Theme.elevated)
                            .then(if (focused) Modifier.border(2.dp, Color.White, shape) else Modifier)
                            .padding(tvs(12)),
                        verticalArrangement = Arrangement.spacedBy(tvs(6))
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(tvs(6))) {
                            logos.forEach { l ->
                                Box(Modifier.size(tvs(34)).clip(RoundedCornerShape(6.dp)).background(Color.Black)) { RemoteImage(l, Modifier.size(tvs(34)), fit = true) }
                            }
                        }
                        Text((if (pinnedName(name)) "★ " else "") + ContentLanguage.categoryTitle(name), color = Color.White, fontSize = tsp(15),
                            fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (count == 1) L("1 channel") else L("%d channels", count), color = Theme.secondary, fontSize = tsp(12))
                    }
                }
            }
        }
    }
    menuFor?.let { name ->
        val on = pinnedName(name)
        val kids = Store.activeProfile?.kids == true
        S2ActionDialog(ContentLanguage.categoryTitle(name), listOfNotNull(
            S2Action(if (on) L("Unpin category") else L("Pin category"), Icons.Default.PushPin) { Store.setCategoryPinned(pid, rawOf(name), !on) },
            if (kids) null else S2Action(L("Hide category"), Icons.Default.VisibilityOff, destructive = true) {
                Store.setCategoriesHidden(merged[name].orEmpty().ifEmpty { setOf(name) }.map { "${pid.lowercase()}|$it" }, true)
            },
        ), onDismiss = { menuFor = null })
    }
}

@Composable
private fun LiveChip(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val lit = selected || focused
    Text(label, color = if (lit) Color.Black else Color.White, fontSize = tsp(13), fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.onFocusChanged { focused = it.isFocused }.focusLift(1.05f) { onClick() }
            .clip(RoundedCornerShape(50)).background(if (lit) Color.White else Theme.elevated)
            .padding(horizontal = tvs(14), vertical = tvs(7)))
}
