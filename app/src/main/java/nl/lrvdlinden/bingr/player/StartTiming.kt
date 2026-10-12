package nl.lrvdlinden.bingr

import android.os.SystemClock
import android.util.Log

/**
 * Starttijd-log (build 59): hoe lang het duurt van "Afspelen" tot het eerste beeld, per stap.
 * Te volgen met `adb logcat -s BingrStart`. Voorbeeld:
 * ```
 * BingrStart: [Animal] +0 ms play
 * BingrStart: [Animal] +4 ms player screen
 * BingrStart: [Animal] +6 ms url resolved
 * BingrStart: [Animal] +9 ms engine load (VLC, resume 1834 s)
 * BingrStart: [Animal] +1420 ms first frame
 * ```
 */
object StartTiming {
    private const val TAG = "BingrStart"
    @Volatile private var t0 = 0L
    @Volatile private var label = ""
    @Volatile private var done = true

    /** Begin van een nieuwe meting (afspeelknop, zappen, volgende aflevering). */
    fun begin(title: String) {
        t0 = SystemClock.elapsedRealtime()
        label = title.take(40)
        done = false
        Log.i(TAG, "[$label] +0 ms play")
    }

    /** Nieuwe meting als er nog geen loopt (zappen en de volgende aflevering komen niet via de afspeelknop). */
    fun ensure(title: String) { if (done) begin(title) }

    /** Tussenstap; zonder lopende meting (bijv. herverbinden) niets. */
    fun mark(step: String) {
        if (done || t0 == 0L) return
        Log.i(TAG, "[$label] +${SystemClock.elapsedRealtime() - t0} ms $step")
    }

    /** Laatste stap: het eerste beeld (of een fout). */
    fun end(step: String) {
        if (done || t0 == 0L) return
        mark(step)
        done = true
    }
}
