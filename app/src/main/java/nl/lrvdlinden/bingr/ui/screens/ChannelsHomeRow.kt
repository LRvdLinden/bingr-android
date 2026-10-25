package nl.lrvdlinden.bingr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Build 151: rij "Zenders" op Home, na "Ontdek categorieën": de zenderlogo's die de eigen server levert (stream_icon /
 * tvg-logo), zoals een rij streamingdiensten. Recente zenders en favorieten eerst, aangevuld uit de grootste zichtbare
 * live-categorieën, hooguit 15 ([Build151Logic.Channels]). Tegel = logo op een neutrale achtergrond (zonder logo de
 * opgeschoonde naam), eronder klein wat er nu speelt. Klikken start de zender meteen. Kinderprofiel: alleen zenders
 * die het kinderfilter toelaat. Op tv dezelfde focus als de andere rijen (optillen + witte rand).
 */
@Composable
fun ChannelsHomeRow(recent: List<Channel>, favorites: List<Channel>) {
    val lib = Playlists.library
    val kids = Store.activeProfile?.kids == true
    val libID = System.identityHashCode(lib)
    val tiles = remember(libID, recent, favorites, kids) {
        if (lib.channels.isEmpty() && recent.isEmpty() && favorites.isEmpty()) emptyList()
        else {
            val groups = LinkedHashMap<String, ArrayList<Channel>>()
            for (c in lib.channels) groups.getOrPut(c.group) { ArrayList() }.add(c)
            Build151Logic.Channels.pick(
                recent + favorites, groups.map { it.key to it.value },
                key = { "${it.sourceID ?: ""}|${it.id}" }, name = { it.name }, hasLogo = { !it.logo.isNullOrBlank() },
                allowed = { !kids || Build140Logic.Kids.isKidsCategory(it.group) },
            )
        }
    }
    if (tiles.size < Build151Logic.Channels.MIN) return
    val favKeys = remember(favorites) { favorites.map { "${it.sourceID ?: ""}|${it.id}" }.toHashSet() }
    val width = tvs(if (AppState.isTV) 170 else 140)
    MediaRow(L("Channels")) {
        items(tiles, key = { "zr|${it.sourceID ?: ""}|${it.id}" }) { c ->
            val now = if (c.sourceID == null) lib.nowPlaying(c) else null
            ChannelLogoTile(c, now?.title, Modifier.width(width)) {
                val field = if ("${c.sourceID ?: ""}|${c.id}" in favKeys) AllServers.Field.FAVORITES else AllServers.Field.RECENT
                AllServers.playChannel(c, tiles, field)
            }
        }
    }
}

@Composable
private fun ChannelLogoTile(c: Channel, now: String?, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(tvs(10))
    val name = remember(c.name) { Build151Logic.Channels.name(c.name) }
    val label = L("Watch %@ live", name) + if (now.isNullOrBlank()) "" else ", $now"
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = label }) {
        Box(
            Modifier.fillMaxWidth().height(tvs(if (AppState.isTV) 96 else 80))
                .onFocusChanged { focused = it.isFocused }
                .focusLift(1.06f) { onClick() }
                .clip(shape)
                // neutrale achtergrond (logo's zijn vaak transparant, licht of donker)
                .background(Color(0xFF2A2A2E))
                .border(if (focused) 2.5.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape),
            contentAlignment = Alignment.Center
        ) {
            var failed by remember(c.logo) { mutableStateOf(false) }
            if (!c.logo.isNullOrBlank() && !failed) {
                coil.compose.AsyncImage(
                    model = c.logo, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    onError = { failed = true }, modifier = Modifier.fillMaxSize().padding(tvs(12))
                )
            } else {
                // geen logo (of het laadt niet): de opgeschoonde naam
                Text(name, color = Color.White, fontSize = tsp(15), fontWeight = FontWeight.Bold, maxLines = 2, textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = tvs(8)))
            }
        }
        if (!now.isNullOrBlank()) {
            Spacer(Modifier.height(tvs(5)))
            Text(now, color = Theme.secondary, fontSize = tsp(11), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
