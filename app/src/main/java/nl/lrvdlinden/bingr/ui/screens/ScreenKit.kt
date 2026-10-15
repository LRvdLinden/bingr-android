package nl.lrvdlinden.bingr

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
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
    fun detail(t: DetailTarget) = push(ScreenEntry.Detail(t))
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
                    is ScreenEntry.Person -> PersonScreen(e.ref, onClose = { ScreensNav.close(item.id) })
                    is ScreenEntry.Categories -> CategoriesScreen(e.kind, onClose = { ScreensNav.close(item.id) })
                    is ScreenEntry.CategoryPage -> CategoryPageScreen(e.ref, e.kind, onClose = { ScreensNav.close(item.id) })
                }
            }
        }
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
fun screensFold(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

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
