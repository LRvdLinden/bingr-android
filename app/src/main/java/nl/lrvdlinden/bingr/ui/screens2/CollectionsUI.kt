package nl.lrvdlinden.bingr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

// MARK: - Afbeelding van een collectie

/** Afbeelding van een collectie: eigen plaatje (poster), of symbool op kleurverloop zoals een avatar. */
@Composable
fun CollectionArtwork(c: BingrCollection, size: Dp) = CollectionArtwork(c.image, c.name, size)

@Composable
fun CollectionArtwork(image: CollectionImage, name: String, size: Dp) {
    val shape = RoundedCornerShape(size * 0.18f)
    val url = image.url
    if (url != null) {
        RemoteImage(url, Modifier.size(size).clip(shape), placeholder = name.take(2))
        return
    }
    val pal = PlaylistAvatar.palettes[Math.floorMod(image.colorIndex, PlaylistAvatar.palettes.size)]
    Box(
        Modifier.size(size).clip(shape).background(Brush.linearGradient(listOf(Color(pal.first), Color(pal.second)))),
        contentAlignment = Alignment.Center
    ) {
        val res = avatarDrawable(image.symbol)
        if (res != null) androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(res), null, Modifier.size(size))
        else Text(image.symbol, fontSize = (size.value * 0.45f).sp)
    }
}

/** Kaart voor een collectie in een rij (Home/Films/Series). */
@Composable
private fun CollectionTitleCard(c: BingrCollection) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CollectionArtwork(c, tvs(26))
        Spacer(Modifier.width(tvs(10)))
        Text(c.name, color = Color.White, fontSize = Theme.titleSize, fontWeight = FontWeight.Bold, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

// MARK: - Inhoud

/**
 * Inhoud van een collectie in de geladen lijst (op de achtergrond uitgerekend).
 *
 * Build 56: via [CollectionResolver] (Dispatchers.Default, debounce, cache per profiel/collectie/updatedAt/lijst,
 * alleen een nieuwe lijst bij andere inhoud). De vorige inhoud blijft staan tot de nieuwe klaar is; eerder werd de
 * rij bij elke nieuwe bibliotheek eerst leeg (rij weg, alles eronder verspringt) en daarna opnieuw gevuld.
 */
@Composable
fun rememberCollectionEntries(c: BingrCollection, lib: Library, pid: String?): List<CollectionEntry> {
    val input = CollectionResolver.Input(c, lib, pid, Store.activeProfileID)
    val flow = remember { MutableStateFlow(input) }
    SideEffect { flow.value = input }
    val initial = remember { CollectionResolver.peek(input) ?: emptyList() }
    val entries = remember(flow) { CollectionResolver.entries(flow) }
    return entries.collectAsStateWithLifecycle(initial).value
}

/**
 * Rij van een collectie (Home, Films, Series). [only]: alleen dit soort (null = alles). Lege collecties tonen niets.
 * Lang indrukken op een item in een handmatige collectie = eruit halen.
 */
@Composable
fun CollectionRow(c: BingrCollection, only: String? = null) {
    val lib = Playlists.library
    val pid = Playlists.source?.id
    val all = rememberCollectionEntries(c, lib, pid)
    // afgeleide lijsten alleen opnieuw als de inhoud verandert (niet bij elke recompositie)
    val entries = remember(all, only) {
        when (only) {
            BingrCollection.MOVIE -> all.filterIsInstance<CollectionEntry.M>()
            BingrCollection.SERIES -> all.filterIsInstance<CollectionEntry.S>()
            BingrCollection.CHANNEL -> all.filterIsInstance<CollectionEntry.C>()
            else -> all
        }.distinctBy { it.key }
    }
    if (entries.isEmpty()) return
    val targets = remember(entries) { entries.mapNotNull { it.target } }
    val channels = remember(entries) { entries.filterIsInstance<CollectionEntry.C>().map { it.channel } }
    var menuFor by remember { mutableStateOf<CollectionEntry?>(null) }
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.padding(horizontal = Theme.edge)) { CollectionTitleCard(c) }
        Spacer(Modifier.height(tvs(8)))
        LazyRow(
            contentPadding = PaddingValues(horizontal = Theme.edge, vertical = if (AppState.isTV) 14.dp else 0.dp),
            horizontalArrangement = Arrangement.spacedBy(Theme.rowSpacing),
        ) {
            items(entries, key = { it.key }) { e ->
                val long: (() -> Unit)? = if (!c.isSmart) ({ menuFor = e }) else null
                when (e) {
                    is CollectionEntry.C -> {
                        val now = if (e.channel.sourceID == null) lib.nowPlaying(e.channel) else null
                        ChannelCard(e.channel, now, modifier = Modifier.focusLift(onLongClick = long) {
                            // build 128: zender van een andere server: daar spelen (of zoeken als hij weg is)
                            if (e.channel.sourceID != null) AllServers.playChannel(e.channel, channels, AllServers.Field.COLLECTION)
                            else {
                                pid?.let { Store.pushRecentChannel(it, e.channel.id) }
                                AppState.play(Playables.channel(e.channel, now), channels)
                            }
                        })
                    }
                    is CollectionEntry.M -> PosterCard(e.movie.title, e.movie.poster,
                        modifier = Modifier.focusLift(onLongClick = long) { ScreensNav.detail(DetailTarget.MovieT(e.movie)) },
                        watched = Playables.isWatched(e.movie, Trakt.imdb(e.movie)), language = languageLabel(e.movie))
                    is CollectionEntry.S -> PosterCard(e.series.title, e.series.poster,
                        modifier = Modifier.focusLift(onLongClick = long) { ScreensNav.detail(DetailTarget.SeriesT(e.series)) },
                        language = languageLabel(e.series), watchedEpisodes = Playables.watchedEpisodeCount(e.series))
                }
            }
            if (targets.size > 6) item {
                Box(
                    Modifier.width(Theme.posterWidth).height(Theme.posterWidth * 1.5f)
                        .focusLift { ScreensNav.category(CategoryTarget(c.name, targets)) }
                        .clip(RoundedCornerShape(4.dp)).background(Theme.card),
                    contentAlignment = Alignment.Center
                ) { Text(L("See all"), color = Color.White, fontSize = tsp(14), fontWeight = FontWeight.SemiBold) }
            }
        }
    }
    menuFor?.let { e ->
        val ref = when (e) {
            is CollectionEntry.C -> Collections.ref(e.channel)
            is CollectionEntry.M -> Collections.ref(DetailTarget.MovieT(e.movie))
            is CollectionEntry.S -> Collections.ref(DetailTarget.SeriesT(e.series))
        }
        val title = when (e) { is CollectionEntry.C -> e.channel.name; is CollectionEntry.M -> e.movie.title; is CollectionEntry.S -> e.series.title }
        S2ActionDialog(title, listOf(
            S2Action(L("Remove from collection") + " · " + c.name, Icons.Default.Close, destructive = true) { Collections.toggle(c, ref) },
        ), onDismiss = { menuFor = null })
    }
}

// MARK: - "Add to collection…"

/** Kiezen in welke (handmatige) collectie dit item hoort; "New collection" maakt er meteen een met dit item. */
@Composable
fun AddToCollectionDialog(ref: CollectionRef, title: String, poster: String?, onDismiss: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    var closed by remember { mutableStateOf(false) }
    LaunchedEffect(closed, creating) { if (closed && !creating) onDismiss() }
    if (creating) {
        CollectionEditScreen(existing = null, initialItem = ref, initialPoster = poster, onClose = { creating = false; onDismiss() })
        return
    }
    if (closed) return
    val manual = Collections.visible.filter { !it.isSmart }
    val actions = manual.map { c ->
        val inIt = Collections.contains(c, ref)
        S2Action(if (inIt) "✓  ${c.name}" else c.name, if (inIt) Icons.Default.Check else Icons.Default.Add) { Collections.toggle(c, ref) }
    } + S2Action(L("New collection"), Icons.AutoMirrored.Filled.PlaylistAdd) { creating = true }
    // S2ActionDialog sluit bij elke keuze; "New collection" opent daarna het bewerkscherm
    S2ActionDialog(L("Add to collection…") + "  ·  " + title, actions, onDismiss = { closed = true })
}

// MARK: - Mijn Bingr → Collections

/** Beheren: lijst met collecties, nieuw, bewerken, verwijderen en volgorde. */
@Composable
fun CollectionsScreen(onClose: () -> Unit) {
    var editing by remember { mutableStateOf<BingrCollection?>(null) }
    var adding by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<BingrCollection?>(null) }
    val e = editing
    if (e != null) { CollectionEditScreen(existing = e, onClose = { editing = null }); return }
    if (adding) { CollectionEditScreen(existing = null, onClose = { adding = false }); return }
    val list = Collections.visible
    fun move(c: BingrCollection, step: Int) {
        val ids = list.map { it.id }.toMutableList()
        val i = ids.indexOf(c.id); val j = i + step
        if (i < 0 || j !in ids.indices) return
        ids[i] = ids[j]; ids[j] = c.id
        Collections.reorder(ids)
    }
    S2Page(L("Collections"), onClose = onClose) {
        item {
            S2Section(footer = L("Every profile has its own collections, on all your devices. They appear as rows on Home, and in Live TV, Movies and Series. Hold a channel, movie or series to add it.")) {
                if (list.isEmpty()) S2Row(L("No collections yet. Make your own rows with your favorite channels, movies and series."), tint = Theme.secondary)
                list.forEach { c ->
                    S2Row(c.name,
                        subtitle = if (c.isSmart) L("Smart collection") + " · " + c.query else L("%d titles", c.items.size),
                        leading = { CollectionArtwork(c, tvs(36)) },
                        onLongClick = { menuFor = c },
                        onClick = { editing = c },
                        trailing = {
                            if (c.isSmart) Icon(Icons.Default.AutoAwesome, null, tint = Theme.secondary, modifier = Modifier.size(tvs(16)))
                            S2MoreButton { menuFor = c }
                        })
                }
                S2Row(L("New collection"), icon = Icons.Default.Add, onClick = { adding = true })
            }
        }
    }
    menuFor?.let { c ->
        val i = list.indexOfFirst { it.id == c.id }
        S2ActionDialog(c.name, buildList {
            add(S2Action(L("Edit collection"), Icons.Default.Edit) { editing = c })
            if (i > 0) add(S2Action(L("Move up"), Icons.Default.KeyboardArrowUp) { move(c, -1) })
            if (i in 0 until list.size - 1) add(S2Action(L("Move down"), Icons.Default.KeyboardArrowDown) { move(c, 1) })
            add(S2Action(L("Delete collection"), Icons.Default.Delete, destructive = true) { Collections.delete(c.id) })
        }, onDismiss = { menuFor = null })
    }
}

/** Nieuwe collectie of bewerken: naam, afbeelding, handmatig of slim (zoekwoorden, soorten, groep, server). */
@Composable
fun CollectionEditScreen(existing: BingrCollection?, onClose: () -> Unit, initialItem: CollectionRef? = null, initialPoster: String? = null) {
    val start = remember(existing?.id) {
        existing ?: BingrCollection(
            name = "",
            image = initialPoster?.let { CollectionImage(url = it) }
                ?: CollectionImage(PlaylistAvatar.symbols.random(), PlaylistAvatar.palettes.indices.random()),
            items = listOfNotNull(initialItem),
        )
    }
    var name by remember { mutableStateOf(start.name) }
    var image by remember { mutableStateOf(start.image) }
    var smart by remember { mutableStateOf(start.isSmart) }
    var query by remember { mutableStateOf(start.query) }
    var types by remember { mutableStateOf(start.types.toSet()) }
    var group by remember { mutableStateOf(start.group ?: "") }
    var sourceID by remember { mutableStateOf(start.sourceID ?: "") }
    var items by remember { mutableStateOf(start.items) }
    var confirmDelete by remember { mutableStateOf(false) }
    val lib = Playlists.library
    val pid = Playlists.source?.id

    fun draft() = start.copy(
        name = name.trim(), image = image, kind = if (smart) BingrCollection.SMART else BingrCollection.MANUAL,
        items = items, query = query.trim(), types = BingrCollection.ALL_TYPES.filter { it in types },
        group = group.ifBlank { null }, sourceID = sourceID.ifBlank { null },
    )
    fun save() {
        if (name.isBlank()) return
        Collections.upsert(draft())
        onClose()
    }

    // voorbeeld van de inhoud (en posters om als afbeelding te kiezen)
    val preview = rememberCollectionEntries(draft(), lib, pid)
    val posters = remember(preview) {
        preview.mapNotNull { e -> when (e) { is CollectionEntry.M -> e.movie.poster; is CollectionEntry.S -> e.series.poster; is CollectionEntry.C -> e.channel.logo } }
            .distinct().take(30)
    }

    S2FullDialog(onClose) {
        S2Page(if (existing == null) L("New collection") else L("Edit collection"), onClose = onClose, actions = {
            S2IconButton(Icons.Default.Check, L("Save"), tint = if (name.isBlank()) Theme.secondary else Color.White) { save() }
        }) {
            item {
                Column(Modifier.widthIn(max = S2MaxWidth).fillMaxWidth().padding(horizontal = Theme.edge, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CollectionArtwork(image, name, tvs(96))
                    S2TextField(name, { name = it }, L("Name"), plain = false)
                }
            }
            // afbeelding: symbool + kleur, of een poster uit de collectie
            item {
                S2Section(L("Picture")) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(tvs(10)), contentPadding = PaddingValues(vertical = 6.dp)) {
                            items(PlaylistAvatar.symbols) { s ->
                                val pal = PlaylistAvatar.palettes[Math.floorMod(image.colorIndex, PlaylistAvatar.palettes.size)]
                                val selected = image.url == null && image.symbol == s
                                Box(
                                    Modifier.size(tvs(44)).focusLift(1.1f) { image = CollectionImage(s, image.colorIndex) }
                                        .clip(CircleShape)
                                        .background(if (selected) Brush.linearGradient(listOf(Color(pal.first), Color(pal.second)))
                                            else Brush.linearGradient(listOf(Theme.elevated, Theme.elevated))),
                                    contentAlignment = Alignment.Center
                                ) { Text(s, fontSize = tsp(20)) }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(tvs(12))) {
                            PlaylistAvatar.palettes.forEachIndexed { i, pal ->
                                Box(
                                    Modifier.size(tvs(28)).focusLift(1.15f) { image = CollectionImage(image.symbol, i) }
                                        .clip(CircleShape)
                                        .background(Brush.linearGradient(listOf(Color(pal.first), Color(pal.second))))
                                        .then(if (image.url == null && image.colorIndex == i) Modifier.border(3.dp, Color.White, CircleShape) else Modifier)
                                )
                            }
                        }
                        if (posters.isNotEmpty()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(tvs(8)), contentPadding = PaddingValues(vertical = 6.dp)) {
                                items(posters) { u ->
                                    Box(
                                        Modifier.width(tvs(54)).height(tvs(80)).focusLift(1.08f) { image = image.copy(url = u) }
                                            .clip(RoundedCornerShape(4.dp))
                                            .then(if (image.url == u) Modifier.border(3.dp, Color.White, RoundedCornerShape(4.dp)) else Modifier)
                                    ) { RemoteImage(u, Modifier.matchParentSize(), placeholder = "") }
                                }
                            }
                        }
                    }
                }
            }
            item {
                S2Section {
                    S2Toggle(L("Smart collection"), smart, icon = Icons.Default.AutoAwesome) { smart = it }
                }
            }
            if (smart) {
                item {
                    S2Section(L("Keywords"), L("Keywords, e.g. sport, football, F1")) {
                        Box(Modifier.padding(12.dp)) { S2TextField(query, { query = it }, L("Keywords"), plain = false) }
                        S2Toggle(L("Live TV"), BingrCollection.CHANNEL in types) { types = if (it) types + BingrCollection.CHANNEL else types - BingrCollection.CHANNEL }
                        S2Toggle(L("Movies"), BingrCollection.MOVIE in types) { types = if (it) types + BingrCollection.MOVIE else types - BingrCollection.MOVIE }
                        S2Toggle(L("Series"), BingrCollection.SERIES in types) { types = if (it) types + BingrCollection.SERIES else types - BingrCollection.SERIES }
                        val groups = remember(System.identityHashCode(lib), types) {
                            buildList {
                                if (BingrCollection.CHANNEL in types) addAll(lib.channelGroups)
                                if (BingrCollection.MOVIE in types) addAll(lib.movieGroups)
                                if (BingrCollection.SERIES in types) addAll(lib.seriesGroups)
                            }.filter { it.isNotBlank() }.distinct()
                        }
                        S2Dropdown(L("Categories"), listOf("" to L("Optional")) + groups.map { it to it } +
                            (if (group.isNotBlank() && group !in groups) listOf(group to group) else emptyList()), group) { group = it }
                        if (Store.playlists.size > 1 || sourceID.isNotBlank()) {
                            S2Dropdown(L("Server"), listOf("" to L("Optional")) + Store.playlists.map { it.id to it.name }, sourceID) { sourceID = it }
                        }
                    }
                }
            } else {
                item {
                    S2Section(L("%d titles", items.size), L("Add channels, movies and series yourself: hold an item and choose “Add to collection…”.")) {
                        val byRef = preview.associateBy { e ->
                            when (e) { is CollectionEntry.C -> Collections.ref(e.channel); is CollectionEntry.M -> Collections.ref(DetailTarget.MovieT(e.movie)); is CollectionEntry.S -> Collections.ref(DetailTarget.SeriesT(e.series)) }
                        }
                        items.forEach { r ->
                            val e = byRef[r]
                            val title = when (e) { is CollectionEntry.C -> e.channel.name; is CollectionEntry.M -> e.movie.title; is CollectionEntry.S -> e.series.title; null -> r.id }
                            S2Row(title, subtitle = when (r.type) { BingrCollection.CHANNEL -> L("Live TV"); BingrCollection.MOVIE -> L("Movies"); else -> L("Series") },
                                tint = if (e == null) Theme.secondary else Color.White,
                                onClick = {}, trailing = {
                                    S2IconButton(Icons.Default.Close, L("Remove"), tint = Theme.secondary, size = tvs(18)) { items = items - r }
                                })
                        }
                        if (items.isEmpty()) S2Row(L("Nothing in this collection yet."), tint = Theme.secondary)
                    }
                }
            }
            if (smart && preview.isNotEmpty()) item {
                S2Section(L("%d titles", preview.size)) {
                    preview.take(12).forEach { e ->
                        val t = when (e) { is CollectionEntry.C -> e.channel.name; is CollectionEntry.M -> e.movie.title; is CollectionEntry.S -> e.series.title }
                        S2Row(t, tint = Theme.secondary)
                    }
                }
            }
            if (existing != null) item {
                S2Section {
                    S2Row(L("Delete collection"), icon = Icons.Default.Delete, tint = Theme.red, onClick = { confirmDelete = true })
                }
            }
            item {
                S2Section {
                    S2Row(L("Save"), icon = Icons.Default.Check, enabled = name.isNotBlank(), onClick = { save() })
                }
            }
        }
    }
    if (confirmDelete && existing != null) {
        S2ActionDialog(existing.name, listOf(
            S2Action(L("Delete collection"), Icons.Default.Delete, destructive = true) { Collections.delete(existing.id); onClose() }
        ), onDismiss = { confirmDelete = false })
    }
}

// MARK: - Mijn Bingr → Home screen

/** Startscherm van dit profiel: rijen aan/uit en volgorde (slepen op touch, omhoog/omlaag op TV). */
@Composable
fun HomeScreenSettings(onClose: () -> Unit) {
    val tv = AppState.isTV
    // build 86: de rij "Categorieën" hier alleen aan/uit, apart van de volgorde. Standaard staat hij direct na "live"
    // (= op Home vijf rijen tussen "Nu live" en Categorieën); is hij op een ander apparaat verplaatst, dan blijft hij
    // na dezelfde rij staan.
    val initial = remember { HomeLayouts.effective(Store.activeHomeLayout) }
    var rows by remember { mutableStateOf(initial.filter { it.key != HomeLayouts.CATEGORY_TILES }) }
    var tiles by remember { mutableStateOf(initial.firstOrNull { it.key == HomeLayouts.CATEGORY_TILES } ?: HomeRow(HomeLayouts.CATEGORY_TILES)) }
    // de rij waar Categorieën na staat ("" = bovenaan)
    val tilesAfter = remember {
        val i = initial.indexOfFirst { it.key == HomeLayouts.CATEGORY_TILES }
        if (i < 0) "live" else if (i == 0) "" else initial[i - 1].key
    }
    fun saveAll(n: List<HomeRow>) {
        if (tilesAfter.isEmpty()) { HomeLayouts.save(listOf(tiles) + n); return }
        val i = n.indexOfFirst { it.key == tilesAfter }.takeIf { it >= 0 } ?: n.indexOfFirst { it.key == "live" }
        HomeLayouts.save(if (i < 0) n + tiles else n.take(i + 1) + tiles + n.drop(i + 1))
    }
    fun commit(n: List<HomeRow>) { rows = n; saveAll(n) }
    fun move(i: Int, step: Int) {
        val j = i + step
        if (i !in rows.indices || j !in rows.indices) return
        val n = rows.toMutableList(); val x = n[i]; n[i] = n[j]; n[j] = x
        commit(n)
    }
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableIntStateOf(1) }

    S2Page(L("Home screen"), onClose = onClose) {
        item {
            S2Section(footer = Store.activeProfile?.name) {
                rows.forEachIndexed { i, r ->
                    val title = HomeLayouts.title(r.key)
                    val isCollection = r.key.startsWith("collection:")
                    val dragging = i == dragIndex
                    Box(
                        Modifier.fillMaxWidth()
                            .onSizeChanged { if (it.height > 0) rowHeight = it.height }
                            .graphicsLayer { if (dragging) { translationY = dragOffset; shadowElevation = 12f } }
                            .background(if (dragging) Theme.elevated else Color.Transparent)
                    ) {
                        S2Row(title,
                            icon = if (isCollection) Icons.Default.ViewAgenda else null,
                            tint = if (r.visible) Color.White else Theme.secondary,
                            onClick = { commit(rows.mapIndexed { k, x -> if (k == i) x.copy(visible = !x.visible) else x }) },
                            leading = {
                                if (!tv) Icon(Icons.Default.DragHandle, null, tint = Theme.secondary,
                                    modifier = Modifier.size(tvs(22)).pointerInput(rows.size) {
                                        detectDragGestures(
                                            onDragStart = { dragIndex = i; dragOffset = 0f },
                                            onDragEnd = { if (dragIndex >= 0) saveAll(rows); dragIndex = -1; dragOffset = 0f },
                                            onDragCancel = { dragIndex = -1; dragOffset = 0f },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                dragOffset += amount.y
                                                val cur = dragIndex
                                                if (cur < 0) return@detectDragGestures
                                                val h = rowHeight.toFloat()
                                                if (dragOffset > h / 2 && cur < rows.size - 1) {
                                                    val n = rows.toMutableList(); val x = n[cur]; n[cur] = n[cur + 1]; n[cur + 1] = x
                                                    rows = n; dragIndex = cur + 1; dragOffset -= h
                                                } else if (dragOffset < -h / 2 && cur > 0) {
                                                    val n = rows.toMutableList(); val x = n[cur]; n[cur] = n[cur - 1]; n[cur - 1] = x
                                                    rows = n; dragIndex = cur - 1; dragOffset += h
                                                }
                                            }
                                        )
                                    })
                            },
                            trailing = {
                                if (tv) {
                                    if (i > 0) S2IconButton(Icons.Default.KeyboardArrowUp, L("Move up"), size = tvs(18)) { move(i, -1) }
                                    if (i < rows.size - 1) S2IconButton(Icons.Default.KeyboardArrowDown, L("Move down"), size = tvs(18)) { move(i, 1) }
                                }
                                // build 97: TalkBack zegt of de rij zichtbaar of verborgen is
                                Icon(if (r.visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    if (r.visible) L("Visible") else L("Hidden"),
                                    tint = if (r.visible) Theme.red else Theme.secondary, modifier = Modifier.size(tvs(20)))
                            })
                    }
                    if (i < rows.size - 1) S2Divider()
                }
            }
        }
        item {
            S2Section(footer = if (tilesAfter == "live") L("Always 5 rows below the Live TV row.") else null) {
                S2Row(HomeLayouts.title(HomeLayouts.CATEGORY_TILES),
                    icon = Icons.Default.GridView,
                    tint = if (tiles.visible) Color.White else Theme.secondary,
                    onClick = { tiles = tiles.copy(visible = !tiles.visible); saveAll(rows) },
                    trailing = {
                        Icon(if (tiles.visible) Icons.Default.Visibility else Icons.Default.VisibilityOff, null,
                            tint = if (tiles.visible) Theme.red else Theme.secondary, modifier = Modifier.size(tvs(20)))
                    })
            }
        }
    }
}
