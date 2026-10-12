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
