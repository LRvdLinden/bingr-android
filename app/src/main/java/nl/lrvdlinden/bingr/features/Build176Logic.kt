package nl.lrvdlinden.bingr

import java.text.Normalizer
import java.util.Calendar
import java.util.TimeZone

/**
 * Build 176 (1.0.8): pure logica (zonder Android), gelijk aan Apple `Shared/Build176.swift` en web `web/js/build176.js`.
 * Contract: docs/ACCOUNT.md "Build 176" in iptv-ios-appletv; gedeelde testgevallen in docs/build176_cases.json
 * (kopie: tools/test/build176_cases.json). Unittest: tools/test/Build176LogicTest.kt.
 */
object Build176Logic {

    // MARK: 1. Automatisch volgende aflevering

    object Autoplay {
        /** `start = (value ?? !kids) && !stillWatchingDue` */
        fun start(value: Boolean?, kids: Boolean, stillWatchingDue: Boolean): Boolean = (value ?: !kids) && !stillWatchingDue
        /** De stand die de instelling toont (niet ingesteld = aan, kinderprofiel uit). */
        fun effective(value: Boolean?, kids: Boolean): Boolean = value ?: !kids
    }

    // MARK: 2. Vergrendelen met pincode

    object Lock {
        const val UNLOCK_SECONDS = 14_400L
        /** `ask = locked && hasMainPin && !(unlockedAt != null && now - unlockedAt < 14400)` (seconden) */
        fun ask(locked: Boolean, hasMainPin: Boolean, unlockedAt: Long?, now: Long): Boolean =
            locked && hasMainPin && !(unlockedAt != null && now - unlockedAt < UNLOCK_SECONDS)
    }

    // MARK: 6. Data besparen

    object DataMode {
        enum class Mode(val raw: String) { AUTO("auto"), SAVER("saver"), MAX("max");
            companion object { fun of(raw: String?): Mode = entries.firstOrNull { it.raw == raw } ?: AUTO } }
        const val MAX_BITRATE = 1_500_000
        const val MAX_HEIGHT = 576
        /** `saver = net == "cellular" && (mode == "saver" || (mode == "auto" && constrained))`; onbekende mode = auto. */
        fun saver(mode: String?, net: String, constrained: Boolean): Boolean {
            if (net != "cellular") return false
            return when (Mode.of(mode)) { Mode.SAVER -> true; Mode.AUTO -> constrained; Mode.MAX -> false }
        }
        data class Variant(val bandwidth: Long, val height: Int?)
        /** Index van de variant bij `saver` (hoogste bandwidth ≤ 1,5 Mbit/s en hoogte ≤ 576 of onbekend; anders de laagste). */
        fun variant(saver: Boolean, variants: List<Variant>): Int? {
            if (!saver || variants.isEmpty()) return null
            var best = -1
            for ((i, v) in variants.withIndex()) {
                if (v.bandwidth <= MAX_BITRATE && (v.height == null || v.height <= MAX_HEIGHT)) {
                    if (best < 0 || v.bandwidth > variants[best].bandwidth) best = i
                }
            }
            if (best >= 0) return best
            var low = 0
            for ((i, v) in variants.withIndex()) if (v.bandwidth < variants[low].bandwidth) low = i
            return low
        }
    }

    // MARK: 5. Zoeken in programmabeschrijvingen

    object EpgSearch {
        const val MAX_RESULTS = 100
        const val DAYS_AHEAD = 7
        const val DEBOUNCE_MS = 250L
        private val cache = java.util.concurrent.ConcurrentHashMap<Char, Char>()

        /** Eén genormaliseerd teken per origineel teken: kleine letters, zonder accenten, geen letter/cijfer = spatie. */
        fun foldChar(ch: Char): Char {
            if (ch.code < 128) return if (ch.isLetterOrDigit()) ch.lowercaseChar() else ' '
            return cache.getOrPut(ch) {
                val d = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)
                val base = d.firstOrNull { Character.getType(it) != Character.NON_SPACING_MARK.toInt() } ?: ' '
                if (base.isLetterOrDigit()) base.lowercaseChar() else ' '
            }
        }

        fun fold(s: String): String {
            val sb = StringBuilder(s.length)
            for (c in s) sb.append(foldChar(c))
            return sb.toString()
        }

        /** Woorden van de zoekterm (≥ 2 tekens na normaliseren); null = niet zoeken (minder dan 3 tekens in totaal). */
        fun words(q: String): List<String>? {
            if (q.trim().length < 3) return null
            val w = fold(q).split(' ').filter { it.length >= 2 }.distinct()
            return w.ifEmpty { null }
        }

        enum class Field { TITLE, DESCRIPTION }
        data class Hit(val field: Field, val start: Int, val length: Int) {
            fun highlight(title: String, desc: String?): String? {
                val src = if (field == Field.TITLE) title else desc ?: return null
                return if (start >= 0 && start + length <= src.length) src.substring(start, start + length) else null
            }
        }

        /** Treffer op al genormaliseerde tekst ([ft] = fold(titel), [fd] = fold(beschrijving)). */
        fun matchFolded(words: List<String>, ft: String, fd: String?): Hit? {
            for (w in words) if (ft.indexOf(w) < 0 && (fd == null || fd.indexOf(w) < 0)) return null
            if (words.all { ft.indexOf(it) >= 0 }) {
                val w = words.first(); return Hit(Field.TITLE, ft.indexOf(w), w.length)
            }
            for (w in words) {
                val i = fd?.indexOf(w) ?: -1
                if (i >= 0) return Hit(Field.DESCRIPTION, i, w.length)
            }
            return null
        }

        fun match(q: String, title: String, desc: String?): Hit? {
            val w = words(q) ?: return null
            return matchFolded(w, fold(title), desc?.let { fold(it) })
        }

        /** Telt mee: nog niet voorbij en begint binnen 7 dagen. */
        fun inWindow(start: Long, end: Long, now: Long): Boolean = end > now && start < now + DAYS_AHEAD * 86_400_000L

        /** Volgorde: titel vóór beschrijving, dan starttijd; één resultaat per (zender, start); max. 100. */
        fun <T> order(hits: List<T>, field: (T) -> Field, start: (T) -> Long, key: (T) -> String): List<T> {
            val seen = HashSet<String>()
            return hits.sortedWith(compareBy<T>({ if (field(it) == Field.TITLE) 0 else 1 }, { start(it) }))
                .filter { seen.add(key(it)) }.take(MAX_RESULTS)
        }
    }

    // MARK: 4. Herinnering op de releasedatum

    object Release {
        const val NIL_PLAYLIST = "00000000-0000-0000-0000-000000000000"
        /** `release:<movie|tv>:<tmdb>[:s<seizoen>]`, met `p:<profiel>:` ervoor bij een ander profiel dan het hoofdprofiel. */
        fun key(mediaType: String, tmdb: Int, season: Int?): String =
            "release:${if (mediaType == "tv") "tv" else "movie"}:$tmdb" + (season?.let { ":s$it" } ?: "")
        fun id(mediaType: String, tmdb: Int, season: Int?, profileID: String?): String =
            (profileID?.let { "p:${it.lowercase()}:" } ?: "") + key(mediaType, tmdb, season)

        /** Releasedatum "yyyy-MM-dd" om 09:00 lokale tijd ([zone]) als epoch-ms; null = onleesbaar. */
        fun start(date: String, zone: TimeZone): Long? {
            val p = date.take(10).split('-').mapNotNull { it.toIntOrNull() }
            if (p.size != 3) return null
            val c = Calendar.getInstance(zone)
            c.clear()
            c.set(p[0], p[1] - 1, p[2], 9, 0, 0)
            return c.timeInMillis
        }
    }

    // MARK: 12. Vegen in de speler (helderheid links, volume rechts)

    object Swipe {
        enum class Side { BRIGHTNESS, VOLUME, MIDDLE }
        /** Linker derde = helderheid, rechter derde = volume, midden = zappen (live, zoals voorheen). */
        fun side(x: Float, width: Float): Side = when {
            x < width / 3f -> Side.BRIGHTNESS
            x > width * 2f / 3f -> Side.VOLUME
            else -> Side.MIDDLE
        }
        /** Nieuwe waarde (0…1) na een verticale veeg van [dy] px (omhoog = negatief = meer); hele schermhoogte = 0→1. */
        fun apply(start: Float, dy: Float, height: Float): Float =
            if (height <= 0f) start else (start - dy / (height * 0.8f)).coerceIn(0f, 1f)
    }

    // MARK: 14. Postermaat van TMDB

    object Posters {
        private val sizes = listOf(92, 154, 185, 342, 500, 780)
        /** Kleinste TMDB-maat die minstens [px] breed is (anders w780). */
        fun size(px: Int): String = "w" + (sizes.firstOrNull { it >= px } ?: 780)
        /** Een TMDB-afbeeldings-URL naar de passende maat (andere URL's ongewijzigd). */
        fun sized(url: String?, px: Int): String? {
            if (url == null) return null
            val m = Regex("""^(https?://image\.tmdb\.org/t/p/)(w\d+|original)(/.+)$""").find(url) ?: return url
            val (base, cur, path) = m.destructured
            val want = size(px)
            if (cur != "original" && (cur.removePrefix("w").toIntOrNull() ?: 0) <= want.removePrefix("w").toInt()) return url
            return base + want + path
        }
    }

    // MARK: 8. Helpcentrum

    object Help {
        const val URL = "https://support.bingr.online/hulp/"
        fun url(lang: String): String = if (lang.lowercase().startsWith("nl")) URL else "$URL?lang=en"
    }

    // MARK: 7. Voorvertoning

    object Preview {
        const val DELAY_MS = 2_000L
        fun allowed(setting: Boolean, saver: Boolean, locked: Boolean, kidsBlocked: Boolean): Boolean =
            setting && !saver && !locked && !kidsBlocked
    }
}
