package nl.lrvdlinden.bingr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Build 149: rij "Ontdek categorieën" op Home (zoals "Ontdek kanalen en apps" op de Apple TV): grote tegels per
 * categorie van de server, bovenin een collage van 3–4 posters, eronder een gekleurde balk met de naam (of het logo van
 * de streamingdienst, uit TMDB). Hooguit 10, de grootste eerst; klikken opent de categorie. Niet bij een kinderprofiel,
 * behalve als alle categorieën kindercategorieën zijn.
 */
@Composable
fun DiscoverCategoriesRow() {
    val index = rememberCategoryIndex() ?: return
    val kids = Store.activeProfile?.kids == true
    val tiles = remember(index, kids) {
        val ok = index.server.filter { it.second.count(Filter.ALL) >= Build149Logic.Discover.MIN_ITEMS && it.second.posters(Filter.ALL).size >= 3 }
        // build 150: index.server is al samengevoegd op de opgeschoonde naam (één tegel "Netflix"); kinderprofiel per bron
        if (kids && ok.any { (n, m) -> m.sources.ifEmpty { listOf(n) }.any { !Build140Logic.Kids.isKidsCategory(it) } }) emptyList()
        else ok.sortedByDescending { it.second.count(Filter.ALL) }.take(Build149Logic.Discover.MAX)
    }
    if (tiles.isEmpty()) return
    val logos by produceState<Map<String, String>>(ProviderLogos.cached(), Unit) { value = ProviderLogos.load() }
    val width = tvs(if (AppState.isTV) 260 else 220)
    MediaRow(L("Discover categories")) {
        items(tiles, key = { "dc|" + it.first }) { (name, m) ->
            val title = ContentLanguage.categoryTitle(name).ifBlank { name }
            // build 150: logo op de opgeschoonde naam, met de betere alias-tabel
            val logo = Build150Logic.Logos.providerFor(title, logos)?.let { logos[it] }
            DiscoverCategoryTile(title, m.posters(Filter.ALL), logo, Color(Build149Logic.Discover.colorFor(title)), Modifier.width(width),
                chips = Build150Logic.Merge.chips(m.tags, 2)) {
                ScreensNav.push(ScreenEntry.CategoryPage(CategoryRef(null, name), Filter.ALL))
            }
        }
    }
}

@Composable
private fun DiscoverCategoryTile(title: String, posters: List<String>, logo: String?, bar: Color, modifier: Modifier, chips: String = "", onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(tvs(10))
    Column(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .focusLift(1.06f) { onClick() }
            .clip(shape)
            .background(bar)
            .border(if (focused) 2.5.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape)
    ) {
        // collage: 3–4 posters naast elkaar
        Row(Modifier.fillMaxWidth().height(tvs(if (AppState.isTV) 150 else 130))) {
            posters.take(4).forEach { p -> Box(Modifier.weight(1f).fillMaxHeight()) { RemoteImage(p, Modifier.fillMaxSize()) } }
        }
        Box(
            Modifier.fillMaxWidth().height(tvs(54))
                .background(Brush.verticalGradient(listOf(bar, Color(bar.red * 0.75f, bar.green * 0.75f, bar.blue * 0.75f)))),
            contentAlignment = Alignment.CenterStart
        ) {
            if (logo != null) {
                // logo van de dienst (TMDB); de naam blijft voor TalkBack
                RemoteImage(logo, Modifier.padding(horizontal = tvs(12), vertical = tvs(8)).height(tvs(38)).width(tvs(38)).clip(RoundedCornerShape(tvs(8))),
                    fit = true, contentDescription = title)
                Text(title, color = Color.White, fontSize = tsp(17), fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = tvs(60), end = tvs(if (chips.isNotEmpty()) 70 else 10)))
            } else {
                Text(title, color = Color.White, fontSize = tsp(19), fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = tvs(12), end = tvs(if (chips.isNotEmpty()) 70 else 12)))
            }
            // build 150: talen van de samengevoegde bronnen als klein label ("NL · Multi")
            if (chips.isNotEmpty()) Text(chips, color = Color.White.copy(alpha = 0.8f), fontSize = tsp(11), fontWeight = FontWeight.Bold, maxLines = 1,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = tvs(10)).clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.25f)).padding(horizontal = tvs(7), vertical = tvs(2)))
        }
    }
}

/**
 * Build 149: logo's van streamingdiensten uit TMDB (`/watch/providers/movie` en `/tv`, regio NL), als
 * key(provider_name) → `https://image.tmdb.org/t/p/w300{logo_path}`. Een week bewaard; geen logo-bestanden in de app.
 * Geen netwerk of geen sleutel: leeg (de tegel toont dan de naam).
 */
object ProviderLogos {
    private const val KEY = "b149.providerLogos"
    private const val AT = "b149.providerLogos.at"
    private const val WEEK = 7L * 86_400_000L
    @Volatile private var mem: Map<String, String>? = null

    fun cached(): Map<String, String> = mem ?: runCatching {
        val o = Http.json.parseToJsonElement(Prefs.string(KEY) ?: "{}").jsonObject
        o.mapValues { it.value.jsonPrimitive.content }
    }.getOrDefault(emptyMap()).also { mem = it }

    suspend fun load(): Map<String, String> {
        val cur = cached()
        val at = Prefs.string(AT)?.toLongOrNull() ?: 0L
        if (cur.isNotEmpty() && System.currentTimeMillis() - at < WEEK) return cur
        val out = LinkedHashMap<String, String>()
        for (kind in listOf("movie", "tv")) {
            val r = runCatching { TMDB.request("/watch/providers/$kind", listOf("watch_region" to "NL")) }.getOrNull() ?: continue
            for (p in r.jList("results")) {
                val name = p.jS("provider_name") ?: continue
                val path = p.jS("logo_path") ?: continue
                val k = Build149Logic.Discover.key(name)
                if (k.isNotEmpty() && k !in out) out[k] = "https://image.tmdb.org/t/p/w300$path"
            }
        }
        if (out.isEmpty()) return cur
        withContext(Dispatchers.IO) {
            Prefs.put(KEY, JsonObject(out.mapValues { JsonPrimitive(it.value) }).toString())
            Prefs.put(AT, System.currentTimeMillis().toString())
        }
        mem = out
        return out
    }
}
