package nl.lrvdlinden.bingr

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Build 172: Instellingen > server > "Categorieën opschonen". Zoekt op het apparaat naar categorieën die bij elkaar horen
 * (merken, zelfde begin, landvarianten; [Build172Logic]) en voegt ze pas samen na akkoord, in het bestaande formaat van
 * [Customization] (`merged`, `renamed`, `groupOrder`). Alleen het hoofdaccount. Goed te bedienen met de afstandsbediening:
 * elke actie is een eigen focusbare rij.
 */
@Composable
fun CategoryCleanupScreen(onClose: () -> Unit) {
    var kind by remember { mutableStateOf(S2Kind.MOVIES) }
    var suggestions by remember { mutableStateOf<List<Build172Logic.Suggestion>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var searchRun by remember { mutableIntStateOf(0) }
    val picked = remember { mutableStateMapOf<String, Boolean>() }
    val unchecked = remember { mutableStateMapOf<String, Boolean>() }
    val names = remember { mutableStateMapOf<String, String>() }
    var renaming by remember { mutableStateOf<Build172Logic.Suggestion?>(null) }
    var confirming by remember { mutableStateOf<List<Build172Logic.Suggestion>?>(null) }
    var undo by remember { mutableStateOf<Pair<Customization, Long>?>(null) }

    val pid = Playlists.source?.id
    val c = pid?.let { Store.customizations[it] } ?: Customization()
    val section = when (kind) { S2Kind.LIVE -> "live"; S2Kind.MOVIES -> "movie"; S2Kind.SERIES -> "series" }
    val groupCounts: List<Pair<String, Int>> = remember(kind, Playlists.rawLibrary) {
        val raw = Playlists.rawLibrary
        val names0 = when (kind) {
            S2Kind.LIVE -> raw.channels.map { it.group }
            S2Kind.MOVIES -> raw.movies.map { it.group }
            S2Kind.SERIES -> raw.series.map { it.group }
        }
        val counts = LinkedHashMap<String, Int>()
        for (g in names0) counts[g] = (counts[g] ?: 0) + 1
        for (g in raw.hiddenGroupNames(section)) if (g !in counts) counts[g] = 0
        counts.toList()
    }
    val isLive = kind == S2Kind.LIVE

    fun display(g: String) = c.renamed[Customization.groupKey(g)] ?: ContentLanguage.categoryTitle(g)
    fun countText(n: Int) = if (isLive) L("%d channels", n) else L("%d titles", n)
    fun nameOf(s: Build172Logic.Suggestion) = names[s.id] ?: s.target
    fun checkedOf(s: Build172Logic.Suggestion) = s.groups.filter { unchecked[s.id + "|" + it] != true }
    fun isPicked(s: Build172Logic.Suggestion) = picked[s.id] ?: s.on
    val pickedList = suggestions.filter { isPicked(it) && checkedOf(it).size >= 2 }

    LaunchedEffect(kind, pid, searchRun) {
        val p = pid ?: return@LaunchedEffect
        searching = true
        val cu = Store.customization(p)
        val hidden = groupCounts.map { it.first }.filter { Store.isAccountCategoryHidden(p, it) }.toMutableSet()
        for (h in cu.hidden) if (h.startsWith("group:")) hidden += h.removePrefix("group:")
        val ignored = Store.mergeIgnored(p, section)
        val input = groupCounts.map { Build172Logic.Group(it.first, it.second) }
        val out = withContext(Dispatchers.Default) { Build172Logic.suggest(input, cu.merged, cu.renamed, hidden, ignored) }
        suggestions = out
        picked.clear(); unchecked.clear(); names.clear()
        searching = false; searched = true
    }

    fun apply(picks: List<Build172Logic.Suggestion>) {
        val p = pid ?: return
        val before = Store.customization(p)
        val done = HashSet<String>()
        for (s in picks) {
            val gs = checkedOf(s)
            if (gs.size < 2) continue
            val target = if (s.targetGroup in gs) s.targetGroup else gs[0]
            val nm = nameOf(s).trim()
            val clean = ContentLanguage.categoryTitle(target)
            Store.updateCustomization(p) { cu ->
                val r = Build172Logic.apply(Build172Logic.Custom(cu.merged, cu.renamed, if (isLive) cu.groupOrder else emptyList()),
                    gs, target, nm, clean)
                cu.copy(merged = r.merged, renamed = r.renamed, groupOrder = if (isLive) r.groupOrder else cu.groupOrder)
            }
            Store.setMergeMade(p, target, if (nm.isNotEmpty() && nm != clean) nm else null)
            done += s.id
        }
        if (done.isEmpty()) return
        suggestions = suggestions.filter { it.id !in done }
        undo = before to System.currentTimeMillis()
    }

    fun detach(target: String, sources: List<String>?) {
        val p = pid ?: return
        val made = Store.mergeMade(p, target)
        Store.updateCustomization(p) { cu ->
            val r = Build172Logic.detach(Build172Logic.Custom(cu.merged, cu.renamed, cu.groupOrder), target, sources, made)
            cu.copy(merged = r.merged, renamed = r.renamed)
        }
        if (!Store.customization(p).merged.containsValue(target)) Store.setMergeMade(p, target, null)
    }

    // "Ongedaan maken" staat 10 s
    undo?.let { u -> LaunchedEffect(u.second) { delay(10_000); if (undo?.second == u.second) undo = null } }

    S2Page(L("Clean up categories"), onClose = onClose) {
        item { S2Segments(listOf(S2Kind.LIVE to L("Live TV"), S2Kind.MOVIES to L("Movies"), S2Kind.SERIES to L("Series")), kind) { kind = it } }
        undo?.let { u ->
            item {
                S2Section {
                    S2Row(L("Categories merged."), icon = Icons.Default.CheckCircle, tint = Color.White, onClick = {
                        pid?.let { p -> Store.updateCustomization(p) { u.first } }
                        undo = null
                        searchRun++
                    }, trailing = { Text(L("Undo"), color = Theme.red, fontSize = tsp(14), fontWeight = FontWeight.SemiBold) })
                }
            }
        }
        item {
            S2Section(footer = L("Bingr looks for categories that belong together, such as Netflix, Netflix Movies and Netflix Documentaries. Nothing changes until you tap Merge. Brands are selected by default; country variants and same-beginning suggestions are not.")) {
                if (searching) S2Row(L("Searching for categories that belong together…"), tint = Theme.secondary)
                else if (searched && suggestions.isEmpty()) S2Row(L("No suggestions. Your categories look tidy."), tint = Theme.secondary)
                if (suggestions.isNotEmpty()) {
                    S2Row(L("Apply all (%d)", pickedList.size), icon = Icons.Default.CheckCircle, enabled = pickedList.isNotEmpty(),
                        onClick = { confirming = pickedList })
                }
                S2Row(L("Search again"), icon = Icons.Default.Search, enabled = !searching, onClick = {
                    pid?.let { Store.clearMergeIgnored(it, section) }
                    searchRun++
                })
            }
        }
        for (s in suggestions) {
            item(key = "s:" + s.id) {
                val on = isPicked(s)
                val chosen = checkedOf(s)
                val kindText = when (s.kind) { "brand" -> L("Brand"); "country" -> L("Country variants"); else -> L("Same beginning") }
                S2Section(header = nameOf(s)) {
                    S2Row(nameOf(s), subtitle = L("%d categories", chosen.size) + " · " + countText(chosen.sumOf { s.counts[it] ?: 0 }),
                        value = kindText, onClick = { picked[s.id] = !on }, leading = {
                            Icon(if (on) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null,
                                tint = if (on) Theme.red else Theme.secondary, modifier = Modifier.size(tvs(20)))
                        })
                    for (g in s.groups) {
                        val key = s.id + "|" + g
                        val isOn = unchecked[key] != true
                        S2Divider()
                        S2Row(g, value = countText(s.counts[g] ?: 0),
                            subtitle = if (g in s.renamed) "⚠ " + L("You renamed this category yourself (%@)", c.renamed[Customization.groupKey(g)] ?: "") else null,
                            onClick = { if (isOn) unchecked[key] = true else unchecked.remove(key) }, leading = {
                                Icon(if (isOn) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, null,
                                    tint = if (isOn) Theme.red else Theme.secondary, modifier = Modifier.size(tvs(20)))
                            })
                    }
                    S2Divider()
                    S2Row(L("Merge"), icon = Icons.AutoMirrored.Filled.MergeType, enabled = chosen.size >= 2, onClick = { confirming = listOf(s) })
                    S2Row(L("Change target name"), icon = Icons.Default.Edit, onClick = { renaming = s })
                    S2Row(L("Skip"), icon = Icons.Default.Close, tint = Theme.secondary, onClick = {
                        pid?.let { Store.ignoreMerge(it, section, s.id) }
                        suggestions = suggestions.filter { it.id != s.id }
                    })
                }
            }
        }
        val here = groupCounts.map { it.first }
        val hereSet = here.toHashSet()
        val targets = Build172Logic.mergedTargets(c.merged, here).filter { t -> t.target in hereSet || t.sources.any { it in hereSet } }
        if (targets.isNotEmpty()) {
            item { S2Header(L("Merged")) }
            items(targets, key = { "m:" + it.target }) { t ->
                Column(Modifier.s2Cell(0, 1)) {
                    S2Row(display(t.target), subtitle = L("%d categories", t.sources.size + 1), onClick = { detach(t.target, null) },
                        trailing = { Text(L("Detach all"), color = Theme.red, fontSize = tsp(14), fontWeight = FontWeight.SemiBold) })
                    for (src in t.sources) {
                        S2Divider()
                        S2Row("↳ $src", onClick = { detach(t.target, listOf(src)) },
                            trailing = { Text(L("Take out"), color = Theme.red, fontSize = tsp(14), fontWeight = FontWeight.SemiBold) })
                    }
                }
                androidx.compose.foundation.layout.Spacer(Modifier.size(tvs(10)))
            }
        }
    }

    renaming?.let { s ->
        S2RenameDialog(nameOf(s), onDismiss = { renaming = null }) { new -> if (new.isEmpty()) names.remove(s.id) else names[s.id] = new }
    }
    confirming?.let { picks ->
        val sum = Build172Logic.summary(picks.map { it.copy(groups = checkedOf(it)) })
        AlertDialog(
            onDismissRequest = { confirming = null },
            containerColor = Theme.elevated,
            title = { Text(L("Merge categories?"), color = Color.White) },
            text = { Text(L("%d categories become %d. You can always undo this.", sum.first, sum.second), color = Theme.secondary) },
            confirmButton = { TextButton(onClick = { confirming = null; apply(picks) }) { Text(L("Merge"), color = Theme.red) } },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(L("Cancel"), color = Color.White) } }
        )
    }
}
