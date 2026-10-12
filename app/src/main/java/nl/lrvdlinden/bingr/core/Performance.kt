package nl.lrvdlinden.bingr

import android.os.SystemClock
import kotlinx.coroutines.delay

// Hulpmiddelen voor soepel scrollen (build 56).
//
// Waarom: zware achtergrondklussen (opnieuw filteren na nieuwe leeftijdsclassificaties, collecties uitrekenen,
// het zendervoorbeeld starten) gaven een nieuwe bibliotheek of een nieuwe speler terwijl de gebruiker aan het
// scrollen was. Dat gaf haperingen. Met [UserActivity] wachten die klussen tot de invoer even stil is.

/**
 * Laatste invoer van de gebruiker (toets, D-pad, aanraking), bijgehouden door [MainActivity].
 * Thread-veilig (alleen een @Volatile tijdstempel), dus ook vanaf Dispatchers.Default te lezen.
 */
object UserActivity {
    @Volatile private var lastInputAt = 0L
    @Volatile private var lastRepeatAt = 0L

    /** Invoer gezien; [repeat] = ingedrukt gehouden toets (snel scrollen met de afstandsbediening). */
    fun touch(repeat: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        lastInputAt = now
        if (repeat) lastRepeatAt = now
    }

    /** Zoveel ms geleden was de laatste invoer. */
    fun quietFor(): Long = SystemClock.uptimeMillis() - lastInputAt

    /** Wordt er op dit moment snel gescrold (toets ingedrukt gehouden)? */
    val isRepeating: Boolean get() = SystemClock.uptimeMillis() - lastRepeatAt < 400

    /** Wachten tot er [quietMs] geen invoer meer is geweest (scrollen/fling is dan voorbij). */
    suspend fun awaitIdle(quietMs: Long = 1_500) {
        while (true) {
            val since = quietFor()
            if (since >= quietMs) return
            delay((quietMs - since).coerceAtLeast(50))
        }
    }
}

/**
 * Unieke, stabiele sleutels voor een Lazy-lijst (build 56): de id, en bij dubbele id's (dezelfde zender of titel in
 * meerdere groepen) "id#2", "id#3". Zonder sleutels tekent een rij bij elke nieuwe lijst alle kaarten opnieuw en
 * springt de focus; met dubbele sleutels crasht Compose.
 */
fun <T> uniqueKeys(list: List<T>, id: (T) -> String): List<String> {
    val seen = HashMap<String, Int>(list.size * 2)
    return list.map { x -> val k = id(x); val n = seen.merge(k, 1, Int::plus)!!; if (n == 1) k else "$k#$n" }
}

/** [uniqueKeys], onthouden zolang de lijst gelijk blijft. */
@androidx.compose.runtime.Composable
fun <T> rememberKeys(list: List<T>, id: (T) -> String): List<String> =
    androidx.compose.runtime.remember(list) { uniqueKeys(list, id) }

/** items() met sleutels uit [rememberKeys]. */
inline fun <T> androidx.compose.foundation.lazy.LazyListScope.keyedItems(
    list: List<T>,
    keys: List<String>,
    crossinline content: @androidx.compose.runtime.Composable androidx.compose.foundation.lazy.LazyItemScope.(T) -> Unit,
) = items(list.size, key = { keys[it] }) { content(list[it]) }
