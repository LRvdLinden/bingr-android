package nl.lrvdlinden.bingr

import java.util.Locale
import kotlin.math.abs

/**
 * Build 169: doorspoelen via de tijdbalk (Android TV). Pure logica zonder Android, getest in
 * tools/test/Build169LogicTest.kt.
 *
 * Op Android TV krijgt de tijdbalk focus (↑ vanaf de knoppenrij). Daarna verschuiven ←/→ een voorbeeldpositie,
 * OK (of Afspelen/Pauze) springt erheen, Terug zet hem terug zonder te spoelen. Het afspelen loopt intussen door.
 *
 * - [Scrub.streak]: hoeveelste stap van een reeks (toets ingedrukt houden of snel herhalen).
 * - [Scrub.step]: 10 s, daarna 30 s, daarna 60 s per stap.
 * - [Scrub.move]: nieuwe voorbeeldpositie, begrensd op 0…duur.
 * - [Scrub.expired]: de voorbeeldpositie vervalt 5 s nadat de focus van de balk is.
 * - [Label]: de tijd boven de kop en het verschil ten opzichte van nu ("+4:30" / "−1:10").
 */
object Build169Logic {
    object Scrub {
        /** Twee drukken binnen deze tijd tellen als één reeks (snel herhalen). */
        const val STREAK_GAP_MS = 450L
        /** Zoveel stappen van 10 s, daarna 30 s. */
        const val SLOW_STEPS = 4
        /** Vanaf deze stap 60 s. */
        const val FAST_FROM = 10
        /** Focus zo lang van de balk zonder bevestigen: voorbeeldpositie vervalt. */
        const val EXPIRE_MS = 5_000L

        /**
         * Volgende stapnummer van de reeks: een herhaalde toets (ingedrukt houden, [repeat] > 0) of een druk binnen
         * [STREAK_GAP_MS] na de vorige ([lastMs]) gaat door, anders begint de reeks opnieuw (0). Een andere richting
         * dan de vorige stap begint ook opnieuw.
         */
        fun streak(prev: Int, lastMs: Long?, nowMs: Long, repeat: Int, sameDirection: Boolean = true): Int {
            if (!sameDirection) return 0
            if (repeat > 0) return prev + 1
            if (lastMs != null && nowMs >= lastMs && nowMs - lastMs <= STREAK_GAP_MS) return prev + 1
            return 0
        }

        /** Seconden per stap: 10, na [SLOW_STEPS] stappen 30, vanaf stap [FAST_FROM] 60. */
        fun step(streak: Int): Double = when {
            streak < SLOW_STEPS -> 10.0
            streak < FAST_FROM -> 30.0
            else -> 60.0
        }

        /** Begrensd op 0…duur (onbekende of ongeldige duur: 0). */
        fun clamp(seconds: Double, duration: Double): Double {
            if (!duration.isFinite() || duration <= 0) return 0.0
            if (!seconds.isFinite()) return 0.0
            return seconds.coerceIn(0.0, duration)
        }

        /** Nieuwe voorbeeldpositie in seconden: [from] plus [direction] (−1 of +1) keer de stap van [streak]. */
        fun move(from: Double, direction: Int, streak: Int, duration: Double): Double =
            clamp(from + direction.coerceIn(-1, 1) * step(streak), duration)

        /** Seconden → fractie 0…1 (voor de speler en de balk). */
        fun fraction(seconds: Double, duration: Double): Double =
            if (!duration.isFinite() || duration <= 0) 0.0 else (clamp(seconds, duration) / duration)

        /** Fractie → seconden. */
        fun seconds(fraction: Double, duration: Double): Double =
            if (!duration.isFinite() || duration <= 0 || !fraction.isFinite()) 0.0 else fraction.coerceIn(0.0, 1.0) * duration

        /** Vervalt de voorbeeldpositie? [leftAtMs] = moment dat de focus van de balk ging (null = hij heeft focus). */
        fun expired(leftAtMs: Long?, nowMs: Long): Boolean = leftAtMs != null && nowMs - leftAtMs >= EXPIRE_MS
    }

    object Label {
        /** Tijd als m:ss of u:mm:ss (zoals PlayerController.format). */
        fun time(s: Double): String {
            if (!s.isFinite() || s < 0) return "0:00"
            val t = s.toLong(); val h = t / 3600; val m = (t % 3600) / 60; val sec = t % 60
            return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ROOT, "%d:%02d", m, sec)
        }

        /** Verschil van de voorbeeldpositie ten opzichte van nu: "+4:30", "−1:10" (echt minteken), of "0:00". */
        fun delta(preview: Double, now: Double): String {
            if (!preview.isFinite() || !now.isFinite()) return "0:00"
            val d = (preview - now).toLong()   // naar 0 afgerond: −0,4 s = 0:00
            if (d == 0L) return "0:00"
            return (if (d > 0) "+" else "−") + time(abs(d).toDouble())
        }

        /** Tekst boven de kop: "12:34  +4:30". */
        fun bubble(preview: Double, now: Double): String = time(preview) + "  " + delta(preview, now)
    }
}
