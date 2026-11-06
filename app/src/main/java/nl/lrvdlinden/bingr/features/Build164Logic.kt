package nl.lrvdlinden.bingr

import java.security.MessageDigest
import java.util.Calendar
import java.util.TimeZone

/**
 * Build 164: pure logica (zonder Android, getest in tools/test/Build164LogicTest.kt) van de nieuwe tv-gids in
 * NL-Ziet-stijl (telefoon: live vak + zenderstrook + programmalijst; Android TV: dagbalk + zenderkolommen).
 *
 * - [Days]: de dagbalk (gisteren t/m 7 dagen vooruit; verder terug alleen met terugkijken, max. de terugkijkdagen) en
 *   het begin/einde van een dag.
 * - [Index]: binair zoeken op begintijd (alleen het zichtbare stuk per zender), de NU-index, het venster van één dag
 *   en de tv-kolom (2 eerder, NU, 4 later).
 * - [Stills]: het stilstaande beeld per zender (max. 30 min oud, max. 200 zenders, de oudste eerst weg).
 * - [Preview]: de instelling "Live voorbeeld in de gids" (Altijd / Alleen wifi / Nooit) en wanneer het vak start.
 */
object Build164Logic {
    const val DAY_MS = 86_400_000L

    object Days {
        /** Zo ver vooruit toont de dagbalk dagen. */
        const val AHEAD = 7
        /** Nooit verder terug dan dit (ook als een server meer terugkijkdagen opgeeft). */
        const val MAX_BACK = 14

        /**
         * Dagen in de balk als verschil met vandaag: altijd gisteren t/m [AHEAD] vooruit; verder terug alleen als een
         * zender in beeld terugkijken heeft ([maxCatchupDays] = het grootste aantal terugkijkdagen in beeld).
         */
        fun offsets(maxCatchupDays: Int): List<Int> {
            val back = maxCatchupDays.coerceIn(1, MAX_BACK)
            return (-back..AHEAD).toList()
        }

        /** Gekozen dag binnen de balk houden (bijv. als de zenders in beeld minder terugkijkdagen hebben). */
        fun clamp(offset: Int, offsets: List<Int>): Int =
            if (offsets.isEmpty()) 0 else offset.coerceIn(offsets.first(), offsets.last())

        enum class Label { YESTERDAY, TODAY, TOMORROW, DATE }

        fun label(offset: Int): Label = when (offset) {
            -1 -> Label.YESTERDAY
            0 -> Label.TODAY
            1 -> Label.TOMORROW
            else -> Label.DATE
        }

        /** Middernacht (lokale tijd) van de dag [offset] dagen na de dag van [now]; houdt rekening met zomertijd. */
        fun start(now: Long, offset: Int, tz: TimeZone = TimeZone.getDefault()): Long = Calendar.getInstance(tz).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, offset)
        }.timeInMillis

        fun end(now: Long, offset: Int, tz: TimeZone = TimeZone.getDefault()): Long = start(now, offset + 1, tz)

        /**
         * Ankertijd van een dag: vandaag = nu; een andere dag = hetzelfde tijdstip op die dag ("wat is er morgen om deze
         * tijd"), zodat de lijst en de kolommen daar beginnen.
         */
        fun anchor(now: Long, offset: Int, tz: TimeZone = TimeZone.getDefault()): Long {
            if (offset == 0) return now
            return Calendar.getInstance(tz).apply { timeInMillis = now; add(Calendar.DAY_OF_MONTH, offset) }.timeInMillis
        }
    }

    /** Soort regel in de programmalijst. */
    enum class Kind { PAST, NOW, FUTURE }

    fun kind(start: Long, end: Long, now: Long): Kind = when {
        end <= now -> Kind.PAST
        start <= now -> Kind.NOW
        else -> Kind.FUTURE
    }

    /** Terugkijk-icoon: voorbij, de zender heeft terugkijken en het programma valt nog binnen de terugkijkdagen. */
    fun canReplay(start: Long, end: Long, now: Long, catchupDays: Int): Boolean =
        catchupDays > 0 && end <= now && start >= now - catchupDays * DAY_MS

    object Index {
        /** Valt de lijst gesorteerd op begintijd? (Steekproef, zoals EPGIndex.isSorted.) */
        fun <T> sorted(list: List<T>, start: (T) -> Long): Boolean =
            list.size < 2 || (start(list.first()) <= start(list[list.size / 2]) && start(list[list.size / 2]) <= start(list.last()))

        /** Laatste index met begintijd ≤ [t] (binair zoeken), of -1. */
        fun <T> lastStartingAtOrBefore(list: List<T>, t: Long, start: (T) -> Long): Int {
            var lo = 0; var hi = list.size - 1; var best = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (start(list[mid]) <= t) { best = mid; lo = mid + 1 } else hi = mid - 1
            }
            return best
        }

        /** Index van het programma dat op [t] loopt (ook bij een paar overlappende programma's), of -1. */
        fun <T> at(list: List<T>, t: Long, start: (T) -> Long, end: (T) -> Long): Int {
            if (list.isEmpty()) return -1
            if (!sorted(list, start)) return list.indexOfFirst { start(it) <= t && t < end(it) }
            val best = lastStartingAtOrBefore(list, t, start)
            var j = best
            while (j >= 0 && j >= best - 3) { if (start(list[j]) <= t && t < end(list[j])) return j; j-- }
            return -1
        }

        /** Eerste programma dat na [t] eindigt (dat op [t] loopt, of het eerstvolgende), of -1. */
        fun <T> firstEndingAfter(list: List<T>, t: Long, start: (T) -> Long, end: (T) -> Long): Int {
            if (list.isEmpty()) return -1
            if (!sorted(list, start)) return list.indexOfFirst { end(it) > t }
            val a = at(list, t, start, end)
            if (a >= 0) return a
            val next = lastStartingAtOrBefore(list, t, start) + 1
            return if (next < list.size) next else -1
        }

        /**
         * Programma's die met [from, to) overlappen, als indexbereik (leeg = `IntRange.EMPTY`). Gesorteerd: alleen het
         * zichtbare stuk (binair zoeken, dan doorlopen tot de eerste die na [to] begint).
         */
        fun <T> window(list: List<T>, from: Long, to: Long, start: (T) -> Long, end: (T) -> Long): IntRange {
            if (list.isEmpty() || to <= from) return IntRange.EMPTY
            if (!sorted(list, start)) {
                val first = list.indexOfFirst { end(it) > from && start(it) < to }
                if (first < 0) return IntRange.EMPTY
                val last = list.indexOfLast { end(it) > from && start(it) < to }
                return first..last
            }
            val first = firstEndingAfter(list, from, start, end)
            if (first < 0 || start(list[first]) >= to) return IntRange.EMPTY
            var last = first
            while (last + 1 < list.size && start(list[last + 1]) < to) last++
            return first..last
        }

        /** Tv-kolom: [before] eerdere programma's, het programma op het anker (NU) en [after] latere. */
        data class Column(val before: List<Int>, val anchor: Int, val after: List<Int>)

        fun <T> column(list: List<T>, t: Long, start: (T) -> Long, end: (T) -> Long, before: Int = 2, after: Int = 4): Column {
            if (list.isEmpty()) return Column(emptyList(), -1, emptyList())
            val a = at(list, t, start, end)
            if (a >= 0) {
                return Column(((a - before).coerceAtLeast(0) until a).toList(), a,
                    ((a + 1)..minOf(a + after, list.size - 1)).toList())
            }
            // niets op het anker (gat in de gids): eerdere en latere programma's rond dat moment, zonder NU
            val next = firstEndingAfter(list, t, start, end)
            val split = if (next < 0) list.size else next
            return Column(((split - before).coerceAtLeast(0) until split).toList(), -1,
                (split..minOf(split + after - 1, list.size - 1)).toList())
        }

        /** Bij openen naar NU scrollen: één regel erboven, zodat het vorige programma nog zichtbaar is. */
        fun scrollTarget(anchorIndexInWindow: Int): Int = (anchorIndexInWindow - 1).coerceAtLeast(0)
    }

    object Stills {
        /** Hooguit zo oud mag een stilstaand beeld zijn om het te tonen. */
        const val MAX_AGE_MS = 30 * 60_000L
        /** Hooguit zoveel zenders in de cache; de oudste gaan eerst weg. */
        const val MAX_COUNT = 200
        /** Breedte van de momentopname (jpg). */
        const val WIDTH = 320
        /** Na het eerste beeld zo lang wachten voor de eerste momentopname, daarna om de [EVERY_MS]. */
        const val FIRST_MS = 3_000L
        const val EVERY_MS = 60_000L

        /** Sleutel van een zender: server + zender-id (zenders van andere servers botsen niet). */
        fun key(sourceID: String?, channelID: String): String = (sourceID ?: "").trim().lowercase() + "|" + channelID

        /** Bestandsnaam voor de sleutel (alleen [0-9a-f], vast en zonder tekens die in een pad niet mogen). */
        fun fileName(key: String): String {
            val d = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            return d.take(12).joinToString("") { "%02x".format(it) } + ".jpg"
        }

        /** Mag dit beeld (gemaakt op [at]) nu getoond worden? Max. 30 min oud; een beeld uit de toekomst (klok) niet. */
        fun usable(at: Long?, now: Long): Boolean = at != null && at > 0 && at <= now + 60_000L && now - at <= MAX_AGE_MS

        data class Entry(val name: String, val at: Long)

        /** Welke bestanden weg moeten: boven [max] de oudste eerst. */
        fun evict(entries: List<Entry>, max: Int = MAX_COUNT): List<String> {
            if (entries.size <= max) return emptyList()
            return entries.sortedWith(compareBy<Entry>({ it.at }, { it.name })).take(entries.size - max).map { it.name }
        }

        /** Formaat van de momentopname: [WIDTH] breed met de verhouding van de video (16:9 als die onbekend is). */
        fun size(videoWidth: Int, videoHeight: Int, width: Int = WIDTH): Pair<Int, Int> {
            if (videoWidth <= 0 || videoHeight <= 0) return width to (width * 9 / 16)
            val h = (width.toLong() * videoHeight / videoWidth).toInt().coerceIn(1, width * 2)
            return width to h
        }

        enum class Show { LIVE, STILL, LOGO }

        /** Wat het beeld van een NU-kaart toont: live (alleen de ene met het voorbeeld), anders het stilstaande beeld of het logo. */
        fun show(liveHere: Boolean, stillAt: Long?, now: Long): Show = when {
            liveHere -> Show.LIVE
            usable(stillAt, now) -> Show.STILL
            else -> Show.LOGO
        }
    }

    object Preview {
        enum class Mode(val raw: String) { ALWAYS("always"), WIFI("wifi"), NEVER("never") }

        /**
         * Instelling lezen. De oude schakelaar "Zendervoorbeeld" ([legacyOn], wordt meegeschreven en gesynct) gaat voor:
         * uit = Nooit (ook als een ander apparaat hem uitzette); weer aan terwijl hier Nooit staat = Alleen wifi.
         * Zonder (geldige) nieuwe waarde: Alleen wifi.
         */
        fun parse(raw: String?, legacyOn: Boolean?): Mode {
            if (legacyOn == false) return Mode.NEVER
            val m = Mode.entries.firstOrNull { it.raw == raw?.trim()?.lowercase() }
            return when {
                m == null -> Mode.WIFI
                m == Mode.NEVER && legacyOn == true -> Mode.WIFI
                else -> m
            }
        }

        enum class Start { AUTO, TAP, OFF }

        /**
         * Starten van het live voorbeeld: Nooit = uit; tv's zijn altijd wifi of kabel (Alleen wifi = aan); op een
         * netwerk met datalimiet (mobiel) bij Alleen wifi pas na een tik.
         */
        fun start(mode: Mode, isTV: Boolean, metered: Boolean): Start = when {
            mode == Mode.NEVER -> Start.OFF
            mode == Mode.ALWAYS || isTV -> Start.AUTO
            metered -> Start.TAP
            else -> Start.AUTO
        }

        /** Mag het voorbeeld nu starten? [tapped] = de gebruiker tikte op het vak. */
        fun mayStart(start: Start, tapped: Boolean): Boolean = when (start) {
            Start.OFF -> false
            Start.TAP -> tapped
            Start.AUTO -> true
        }

        /** Na een streamlimiet (403) zo lang geen nieuw voorbeeld proberen (geen foutmelding, het stilstaande beeld blijft). */
        const val BACKOFF_MS = 60_000L

        fun mayTry(blockedAt: Long?, now: Long): Boolean = blockedAt == null || now - blockedAt >= BACKOFF_MS || now < blockedAt

        /**
         * Soort voor `stream_start`: telt mee voor de streamlimiet, maar een onbekende soort telt de server niet in de
         * statistieken (`bingr_stats_item`: onbekende soort = kind null).
         */
        const val STREAM_KIND = "preview"
    }
}
