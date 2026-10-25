package nl.lrvdlinden.bingr

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.Normalizer

// Gedeelde hulpmiddelen voor de schermen van deel C (navigatie-lagen, focus, tekst).

/** Eén scherm dat over het hoofdscherm heen ligt (detail, categorie, zoeken). */
sealed class ScreenEntry {
    data class Detail(val target: DetailTarget) : ScreenEntry()
    data class Category(val target: CategoryTarget) : ScreenEntry()
    data object Search : ScreenEntry()
    /** Build 120: zoekscherm met een zoekterm (titel-link zonder eenduidige match). */
    data class SearchFor(val query: String) : ScreenEntry()
    data class Person(val ref: PersonRef) : ScreenEntry()
    /** Build 85: overzicht Categorieën (telefoon/tablet) en één genre of servercategorie. */
    data class Categories(val kind: Filter) : ScreenEntry()
    data class CategoryPage(val ref: CategoryRef, val kind: Filter) : ScreenEntry()
}

/**
 * Stapel schermen boven [MainScreen] (tegenhanger van sheets/fullScreenCovers op iOS).
 * Wordt getekend door [ScreensStackHost]; terug-knop sluit de bovenste.
 */
object ScreensNav {
    data class Item(val id: Long, val entry: ScreenEntry)

    val stack = mutableStateListOf<Item>()
    private var counter = 0L
    /** build 97: per geopend scherm het element dat de focus had toen het openging (TV, zie [FocusRestore]). */
    private val returnFocus = HashMap<Long, FocusRequester>()

    fun push(e: ScreenEntry) {
        counter += 1
        FocusRestore.current?.let { returnFocus[counter] = it }
        stack.add(Item(counter, e))
    }
    /** Build 128: een titel van een verwijderde server (of die er niet meer op staat) wordt eerst gezocht ([AllServers]). */
    fun detail(t: DetailTarget) { if (!AllServers.intercept(t)) push(ScreenEntry.Detail(t)) }
    fun category(t: CategoryTarget) = push(ScreenEntry.Category(t))
    fun search() = push(ScreenEntry.Search)
    fun person(r: PersonRef) = push(ScreenEntry.Person(r))
    fun close(id: Long) {
        val wasTop = stack.lastOrNull()?.id == id
        val back = returnFocus.remove(id)
        stack.removeAll { it.id == id }
        // build 97: TV: focus terug op de kaart (of knop) die dit scherm opende, ook in de rijen van Home
        if (wasTop && back != null) FocusRestore.restore(back)
    }
    fun clear() { stack.clear(); returnFocus.clear() }
}

/**
 * build 97: focus terugzetten na het sluiten van een scherm (Detail, categorie, zoeken …) op TV. Elk element met
 * [focusLift] heeft een eigen FocusRequester en meldt hier wanneer het de focus krijgt; [ScreensNav.push] onthoudt
 * het element dat de focus had, [ScreensNav.close] vraagt de focus daar weer op (een paar pogingen, want het scherm
 * eronder is pas na het sluiten weer focusbaar). Staat het element er niet meer, dan gebeurt er niets (zoals vroeger).
 */
object FocusRestore {
    /** Het element dat nu de focus heeft (alleen op de main thread). */
    var current: FocusRequester? = null
        private set

    fun focusChanged(r: FocusRequester, focused: Boolean) {
        if (focused) current = r else if (current === r) current = null
    }

    fun restore(r: FocusRequester) {
        if (!AppState.isTV) return
        AppScope.launch {
            repeat(10) {
                delay(80)
                if (current === r) return@launch
                runCatching { r.requestFocus() }
            }
        }
    }
}

/** Tekent de schermstapel volledig over het scherm; alleen de bovenste krijgt focus. */
@Composable
fun ScreensStackHost() {
    val items = ScreensNav.stack.toList()
    items.forEachIndexed { i, item ->
        key(item.id) {
            FullLayer(Modifier.blockFocus(i < items.size - 1)) {
                when (val e = item.entry) {
                    is ScreenEntry.Detail -> DetailScreen(e.target, onClose = { ScreensNav.close(item.id) }, isTop = i == items.size - 1)
                    is ScreenEntry.Category -> CategoryScreen(e.target, onClose = { ScreensNav.close(item.id) })
                    ScreenEntry.Search -> SearchScreen(modal = true, onClose = { ScreensNav.close(item.id) })
                    is ScreenEntry.SearchFor -> SearchScreen(modal = true, onClose = { ScreensNav.close(item.id) }, initialQuery = e.query)
                    is ScreenEntry.Person -> PersonScreen(e.ref, onClose = { ScreensNav.close(item.id) })
                    is ScreenEntry.Categories -> CategoriesScreen(e.kind, onClose = { ScreensNav.close(item.id) })
                    is ScreenEntry.CategoryPage -> CategoryPageScreen(e.ref, e.kind, onClose = { ScreensNav.close(item.id) })
                }
            }
        }
    }
    AllServersNotice()
    AllServersBusy()
}

/** Build 128: melding als een titel/zender op geen enkele server (meer) gevonden is: Verwijderen / Zoeken / Annuleren. */
@Composable
fun AllServersNotice() {
    val n = AllServers.notice ?: return
    val actions = buildList {
        val remove = n.remove
        if (remove != null && n.removeLabel != null) add(S2Action(n.removeLabel, Icons.Default.Close, destructive = true) { remove() })
        add(S2Action(L("Search"), Icons.Default.Search) { ScreensNav.push(ScreenEntry.SearchFor(n.searchFor)) })
        add(S2Action(L("Cancel")) { })
    }
    S2ActionDialog(n.title, actions, onDismiss = { AllServers.notice = null }, message = n.message)
}

/** Build 128: "Zoeken op je andere servers…" terwijl een aangeklikte titel gezocht wordt. */
@Composable
fun AllServersBusy() {
    if (!AllServers.busy) return
    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
        androidx.compose.material3.Text(
            L("Looking for this title on your other servers…"), color = androidx.compose.ui.graphics.Color.White, fontSize = tsp(13),
            modifier = Modifier.padding(bottom = tvs(40))
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.8f), androidx.compose.foundation.shape.RoundedCornerShape(50))
                .padding(horizontal = tvs(16), vertical = tvs(10))
        )
    }
}

/** Volledig scherm, zwart, en tikken gaan niet door naar wat eronder ligt. */
@Composable
fun FullLayer(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            .background(Theme.bg)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
        content = content
    )
}

/** Afstandsbediening: focus kan niet in deze groep komen zolang [blocked] (iets ligt erover). */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.blockFocus(blocked: Boolean): Modifier =
    this.focusProperties { if (blocked) enter = { FocusRequester.Cancel } }.focusGroup()

/** Op TV meteen focus op dit element (een paar pogingen, lazy lijsten komen iets later). */
@Composable
fun rememberInitialFocus(enabled: Boolean = AppState.isTV, key: Any? = Unit): FocusRequester {
    val r = remember { FocusRequester() }
    LaunchedEffect(key, enabled) {
        if (!enabled) return@LaunchedEffect
        repeat(8) {
            delay(90)
            if (runCatching { r.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    return r
}

/** Laat een element (rij) buiten de horizontale padding van zijn ouder uitsteken. */
fun Modifier.bleedHorizontal(h: Dp): Modifier = this.layout { measurable, constraints ->
    val extra = h.roundToPx() * 2
    val maxW = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else constraints.maxWidth
    val p = measurable.measure(constraints.copy(minWidth = maxW.coerceAtMost(constraints.minWidth + extra), maxWidth = maxW))
    val w = if (constraints.hasBoundedWidth) constraints.maxWidth else p.width
    layout(w, p.height) { p.place(-extra / 2, 0) }
}

/** Netwerk/diensten aanroepen zonder dat een fout het scherm laat crashen (annuleren gaat wel door). */
suspend fun <T> screensSafe(block: suspend () -> T): T? = try {
    block()
} catch (c: kotlinx.coroutines.CancellationException) {
    throw c
} catch (_: Throwable) {
    null
}

/** Zoeken zonder accenten/hoofdletters. */
// build 149: regex één keer, en gewone ASCII-tekst zonder Normalizer
private val screensMarks = Regex("\\p{M}+")
fun screensFold(s: String): String =
    if (s.all { it.code < 0x80 }) s.lowercase()
    else screensMarks.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase()

/** Titel vergelijken: zonder accenten, leestekens en spaties. */
fun screensNormTitle(s: String): String = screensFold(s).filter { it.isLetterOrDigit() }

/** Waarde van onbekend type (String/Int/List) als tekst. */
fun screensText(v: Any?): String? = when (v) {
    null -> null
    is String -> v.ifBlank { null }
    is Collection<*> -> v.filterNotNull().joinToString(", ").ifBlank { null }
    is java.util.Date -> DateFormat.getDateInstance(DateFormat.LONG, Localizer.locale).format(v)
    else -> v.toString().ifBlank { null }
}

fun screensInt(v: Any?): Int? = when (v) {
    null -> null
    is Number -> v.toInt()
    is String -> v.trim().take(4).toIntOrNull() ?: v.filter { it.isDigit() }.toIntOrNull()
    else -> null
}

/** Datum (epoch-ms, Date of tekst) leesbaar. */
fun screensDate(v: Any?): String? = when (v) {
    null -> null
    is Long -> if (v <= 0) null else DateFormat.getDateInstance(DateFormat.LONG, Localizer.locale).format(java.util.Date(v))
    is java.util.Date -> DateFormat.getDateInstance(DateFormat.LONG, Localizer.locale).format(v)
    is String -> v.ifBlank { null }
    else -> v.toString()
}

/**
 * build 131: scrollen naar een gefocust element zoals op een telefoon (alleen als het (deels) buiten beeld valt), maar
 * met een veilige marge boven en onder (TV-overscan). Vervangt op TV het standaardgedrag van Compose dat het gefocuste
 * element op ~30% van de hoogte zet (daardoor viel de titel van het detailscherm tegen de bovenrand).
 */
@androidx.compose.foundation.ExperimentalFoundationApi
class SafeMarginBringIntoViewSpec(private val leading: Float, private val trailing: Float) :
    androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val start = leading.coerceAtMost(containerSize / 4)
        val end = trailing.coerceAtMost(containerSize / 4)
        val room = containerSize - start - end
        val trailingEdge = offset + size
        return when {
            offset >= start && trailingEdge <= containerSize - end -> 0f
            size > room || offset < start -> offset - start
            else -> trailingEdge - (containerSize - end)
        }
    }
}
