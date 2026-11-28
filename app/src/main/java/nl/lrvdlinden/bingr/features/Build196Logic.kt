package nl.lrvdlinden.bingr

/**
 * Build 196 (versie 1.1.3): het verversen van de afleveringen voor "Nieuwe aflevering" (Build195Logic) in de achtergrond.
 * Pure planning zonder Android, met unittests (tools/test/Build196LogicTest.kt). Regels gelijk aan Apple:
 * - geen limiet op het aantal series: de hele lijst Mijn lijst van alle servers van het profiel;
 * - de afleveringslijst per serie hooguit elke 2 uur (7200 s) ververst (tot build 195: elk halfuur, met een maximum van 25 series);
 * - beperkte gelijktijdigheid ([CONCURRENCY]); een mislukte serie stopt de rest niet en wordt pas na [RETRY_MS] opnieuw
 *   geprobeerd; het laatste goede resultaat blijft staan;
 * - opnieuw uitvoeren zodra de 2 uur om is terwijl de app open staat ([nextDelay]) of bij terugkeer naar Home.
 */
object Build196Logic {
    const val REFRESH_MS = 2 * 3_600_000L
    const val RETRY_MS = 10 * 60_000L
    const val CONCURRENCY = 3
    const val TIMEOUT_MS = 20_000L
    const val MIN_WAIT_MS = 60_000L

    /** Moment waarop deze serie weer aan de beurt is: 2 uur na de laatste goede ronde, en niet eerder dan [RETRY_MS] na een mislukte. */
    fun dueAt(okAt: Long?, failAt: Long?): Long = maxOf(okAt?.plus(REFRESH_MS) ?: 0L, failAt?.plus(RETRY_MS) ?: 0L)

    fun isDue(now: Long, okAt: Long?, failAt: Long?): Boolean = dueAt(okAt, failAt) <= now

    /** Wachttijd tot de volgende controle terwijl de app open staat (kortste moment van alle series; minstens een minuut, hoogstens 2 uur). */
    fun nextDelay(now: Long, dues: Collection<Long>): Long? =
        if (dues.isEmpty()) null else (dues.min() - now).coerceIn(MIN_WAIT_MS, REFRESH_MS)

    /** Dezelfde serie niet twee keer (sleutel = server en serie-id). */
    fun <T> unique(items: List<T>, key: (T) -> String): List<T> { val seen = HashSet<String>(); return items.filter { seen.add(key(it)) } }
}
