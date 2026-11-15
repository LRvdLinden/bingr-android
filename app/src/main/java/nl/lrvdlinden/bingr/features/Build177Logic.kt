package nl.lrvdlinden.bingr

/**
 * Build 177 (1.0.8): tv-gids per zender. Pure logica (zonder Android), gelijk aan Apple `Shared/Account/Build177.swift`
 * en web `web/js/build177.js`. Gedeelde testgevallen in docs/build177_cases.json van iptv-ios-appletv
 * (kopie: tools/test/build177_cases.json; niet hier aanpassen). Unittest: tools/test/Build177LogicTest.kt.
 */
object Build177Logic {

    // MARK: 1. Hele dag in de kolom, scrollen en focus

    /** Zoveel programma's vóór NU staan bovenaan als de lijst naar NU scrolt */
    const val BEFORE = 2

    /** Een regel in een gidskolom: een programma (index in de dag) of de NU-kaart (`index` -1 = er loopt nu niets). */
    data class Row(val now: Boolean, val index: Int)

    /** Regels van een kolom (altijd de hele dag), de regel met de focus en de regel die bij openen bovenaan staat. */
    data class Column(val rows: List<Row>, val focus: Int?, val anchor: Int?)

    fun column(count: Int, nowIndex: Int?, firstFuture: Int?, today: Boolean, nowCard: Boolean): Column {
        val n0 = count.coerceAtLeast(0)
        if (!today) {
            val rows = (0 until n0).map { Row(false, it) }
            return Column(rows, if (rows.isEmpty()) null else 0, if (rows.isEmpty()) null else 0)
        }
        val rows = ArrayList<Row>(n0 + 1)
        val at: Int
        if (nowIndex != null && nowIndex in 0 until n0) {
            at = nowIndex
            for (i in 0 until nowIndex) rows += Row(false, i)
            rows += Row(nowCard, nowIndex)
            for (i in nowIndex + 1 until n0) rows += Row(false, i)
        } else {
            val f = (firstFuture ?: n0).coerceIn(0, n0)
            for (i in 0 until f) rows += Row(false, i)
            if (nowCard) {
                at = f
                rows += Row(true, -1)
            } else {
                at = if (n0 == 0) -1 else minOf(f, n0 - 1)
            }
            for (i in f until n0) rows += Row(false, i)
        }
        if (at < 0 || rows.isEmpty()) return Column(rows, null, null)
        return Column(rows, at, (at - BEFORE).coerceAtLeast(0))
    }

    // MARK: 2. Programma's per zender: op volgorde, zonder dubbelen

    /** Zelfde titel die binnen zoveel ms na een al getoond programma begint = dubbel */
    const val MERGE_MS = 300_000L
    /** Overlapt een programma hooguit zoveel ms met het vorige, dan begint het pas als dat afloopt (erbinnen = weg) */
    const val OVERLAP_MS = 300_000L

    fun titleKey(s: String): String = s.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    /** Een opgeschoonde regel: index in de invoer, met mogelijk een ander begin of einde (ms) */
    data class Cleaned(val index: Int, val start: Long, val end: Long)

    fun clean(titles: List<String>, starts: List<Long>, ends: List<Long>): List<Cleaned> {
        val n = minOf(titles.size, starts.size, ends.size)
        if (n == 0) return emptyList()
        val order = (0 until n).sortedWith(compareBy<Int>({ starts[it] }, { -ends[it] }, { it }))
        val out = ArrayList<Cleaned>(n)
        val keys = ArrayList<String>(n)
        for (i in order) {
            var start = starts[i]
            val end = ends[i]
            val key = titleKey(titles[i])
            var merged = false
            var j = out.size - 1
            while (j >= 0 && out[j].start >= start - MERGE_MS) {
                if (keys[j] == key) {
                    out[j] = out[j].copy(end = maxOf(out[j].end, end))
                    merged = true
                    break
                }
                j--
            }
            if (merged) continue
            val k = out.lastOrNull()
            if (k != null && start < k.end && k.end - start <= OVERLAP_MS) {
                if (end <= k.end) continue
                start = k.end
            }
            out += Cleaned(i, start, end)
            keys += key
        }
        return out
    }

    /** [clean] op een lijst met programma's; [copy] maakt een programma met een ander begin en einde. */
    fun <T> clean(list: List<T>, title: (T) -> String, start: (T) -> Long, end: (T) -> Long, copy: (T, Long, Long) -> T): List<T> {
        if (list.size < 2) return list
        return clean(list.map(title), list.map(start), list.map(end)).map { c ->
            val p = list[c.index]
            if (c.start == start(p) && c.end == end(p)) p else copy(p, c.start, c.end)
        }
    }

    // MARK: 3. Live voorbeeld bij focus

    const val RELEASE_GRACE_MS = 300L

    fun releaseStops(releasing: String, pending: String?, playing: String?): Boolean {
        if (pending != null && pending != releasing) return false
        return pending == releasing || playing == releasing
    }

    // MARK: 4. Klok van de gids

    /** Seconden tot de volgende hele minuut, minstens 1 */
    fun secondsToNextMinute(nowSeconds: Double): Double {
        var r = nowSeconds % 60.0
        if (r < 0) r += 60.0
        return maxOf(1.0, 60.0 - r)
    }
}
