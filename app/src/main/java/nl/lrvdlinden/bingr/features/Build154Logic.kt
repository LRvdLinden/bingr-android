package nl.lrvdlinden.bingr

import java.io.File
import java.text.Normalizer
import java.util.Base64
import java.util.Locale

/**
 * Build 154: pure logica (zonder Android, getest in tools/test/Build154LogicTest.kt met de gedeelde gevallen uit
 * iptv-ios-appletv/docs/watched_keys_cases.json). Contract: docs/ACCOUNT.md van iptv-ios-appletv, sectie
 * "Build 154: gezien over alle servers en betrouwbare Trakt-sync".
 *
 * - [WatchKeys]: inhoudssleutels voor "gezien" (`w:m:`, `w:e:`, `w:s:`), normaliseren, lezen met jaar ±1, uitvinken,
 *   migreren en de grootte-grens.
 * - [Token]: wanneer het Trakt-token ververst wordt en wat er na een mislukte verversing overblijft (nooit null).
 * - [Queue]: blijvende wachtrij voor `/sync/history` en `/sync/history/remove` met backoff en `Retry-After`.
 * - [Threshold]: de gezien-grens (gelijk voor lokaal en Trakt).
 * - [Search]: een zoektreffer van Trakt alleen met de juiste titel en het juiste jaar.
 */
object Build154Logic {

    object WatchKeys {
        private val trailingYear = Regex("\\s*[\\(\\[]\\s*(?:19|20)\\d{2}\\s*[\\)\\]]\\s*$")

        /** Titel-normalisatie (contract stap 2–4) op een al opgeschoonde weergavetitel; een jaartal tussen haakjes achteraan gaat eraf. */
        fun norm(title: String): String {
            val s0 = trailingYear.replace(title, "")
            val d = Normalizer.normalize(s0, Normalizer.Form.NFKD)
            val sb = StringBuilder(d.length)
            var i = 0
            while (i < d.length) {
                val cp = d.codePointAt(i)
                i += Character.charCount(cp)
                val t = Character.getType(cp)
                if (t == Character.NON_SPACING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt() || t == Character.ENCLOSING_MARK.toInt()) continue
                when {
                    cp == '&'.code -> sb.append("and")
                    cp == '+'.code -> sb.append("plus")
                    Character.isLetterOrDigit(cp) -> sb.append(String(Character.toChars(cp)).lowercase(Locale.ROOT))
                }
            }
            return sb.toString()
        }

        /** Opgeschoonde titel + jaar (jaar uit de titel als het niet apart bekend is). */
        fun cleaned(title: String, year: Int?): Pair<String, Int?> {
            val p = TitleCleaner.parse(title)
            return p.title to (year?.takeIf { it > 0 } ?: p.year)
        }

        private fun yearPart(y: Int?): String = if (y != null && y in 1000..9999) y.toString() else "0"
        private fun clean(id: String?): String? = id?.trim()?.takeIf { it.isNotEmpty() && it != "0" && it != "null" }
        private fun imdb(id: String?): String? = clean(id)?.takeIf { it.startsWith("tt") }

        /** `<show>`-vormen (tmdb, imdb, titel), elk één keer, in die volgorde. */
        fun showForms(title: String, year: Int?, tmdb: String?, imdb: String?): List<String> {
            val out = ArrayList<String>(3)
            clean(tmdb)?.let { out += "tmdb:$it" }
            imdb(imdb)?.let { out += "imdb:$it" }
            val (t, y) = cleaned(title, year)
            val n = norm(t)
            if (n.isNotEmpty()) out += "t:$n|${yearPart(y)}"
            return out
        }

        fun movieKeys(title: String, year: Int?, tmdb: String?, imdb: String?): List<String> =
            showForms(title, year, tmdb, imdb).map { "w:m:$it" }

        fun episodeKeys(show: String, year: Int?, tmdb: String?, imdb: String?, season: Int?, episode: Int?): List<String> {
            if (season == null || episode == null || season < 0 || episode < 0) return emptyList()
            return showForms(show, year, tmdb, imdb).map { "w:e:$it|$season-$episode" }
        }

        fun showKeys(show: String, year: Int?, tmdb: String?, imdb: String?): List<String> =
            showForms(show, year, tmdb, imdb).map { "w:s:$it" }

        /**
         * Alle sleutels die bij het lezen tellen: de eigen sleutels, en bij de titelsleutel met een jaar ook jaar −1 en +1.
         * tmdb en imdb eerst; de titel als laatste. [prefix] = "w:m:", "w:s:" of "w:e:"; [suffix] = "" of "|s-e".
         */
        fun lookupKeys(prefix: String, title: String, year: Int?, tmdb: String?, imdb: String?, suffix: String = ""): List<String> {
            val out = ArrayList<String>(5)
            clean(tmdb)?.let { out += "${prefix}tmdb:$it$suffix" }
            imdb(imdb)?.let { out += "${prefix}imdb:$it$suffix" }
            val (t, y) = cleaned(title, year)
            val n = norm(t)
            if (n.isNotEmpty()) {
                if (y != null && y in 1000..9999) {
                    out += "${prefix}t:$n|$y$suffix"; out += "${prefix}t:$n|${y - 1}$suffix"; out += "${prefix}t:$n|${y + 1}$suffix"
                } else out += "${prefix}t:$n|0$suffix"
            }
            return out
        }

        fun movieWatched(stored: Set<String>, title: String, year: Int?, tmdb: String?, imdb: String?, ownKey: String? = null): Boolean {
            if (ownKey != null && ownKey in stored) return true
            for (k in lookupKeys("w:m:", title, year, tmdb, imdb)) if (k in stored) return true
            return false
        }

        fun episodeWatched(stored: Set<String>, show: String, year: Int?, tmdb: String?, imdb: String?, season: Int?, episode: Int?,
                           ownKeys: List<String> = emptyList()): Boolean {
            for (k in ownKeys) if (k in stored) return true
            if (season == null || episode == null) return false
            for (k in lookupKeys("w:e:", show, year, tmdb, imdb, "|$season-$episode")) if (k in stored) return true
            return false
        }

        fun showWatched(stored: Set<String>, show: String, year: Int?, tmdb: String?, imdb: String?): Boolean {
            for (k in lookupKeys("w:s:", show, year, tmdb, imdb)) if (k in stored) return true
            return false
        }

        /**
         * Uitvinken: de oude id-sleutel(s) en alle inhoudssleutels van het item, ook de opgeslagen titelsleutel die via jaar ±1
         * matchte (anders bleef het item gezien). Alles wat hieruit in de set staat, gaat weg (en dus met dezelfde tijd naar
         * `removed.watched`).
         */
        fun removalKeys(stored: Set<String>, own: List<String>, lookup: List<String>): Set<String> {
            val out = LinkedHashSet<String>()
            for (k in own) out += k
            for (k in lookup) if (k in stored) out += k
            return out
        }

        /** Ingang voor de migratie: een bestaande id-sleutel en de inhoudssleutels van het item waar die bij hoort. */
        fun interface Resolver { fun keysFor(idKey: String): List<String>? }

        /**
         * Migratie (eenmalig per profiel en apparaat): id-sleutels die bij een bekend item horen krijgen hun inhoudssleutels
         * erbij, met dezelfde `at`. Wat niet te vinden is, blijft zoals het is. [entries] = sleutel → at (epoch-ms, null = onbekend).
         */
        fun migrate(entries: Map<String, Long?>, resolver: Resolver): Map<String, Long?> {
            val out = LinkedHashMap(entries)
            for ((k, at) in entries) {
                if (k.startsWith("w:")) continue
                val keys = resolver.keysFor(k) ?: continue
                for (n in keys) if (n !in out) out[n] = at
            }
            return out
        }

        /** Geschatte grootte van één `{id, at}` in de section (JSON). */
        fun entryBytes(key: String): Int = key.toByteArray(Charsets.UTF_8).size + 44

        /**
         * Grootte-grens: komt de set boven [maxBytes], dan vallen eerst de titelsleutels van films weg die ook een tmdb- of
         * imdb-sleutel hebben (de oudste eerst). [hasIdKey] = heeft deze titelsleutel een tmdb/imdb-broer (per item bekend).
         */
        fun prune(entries: Map<String, Long?>, maxBytes: Int, overhead: Int, hasIdKey: (String) -> Boolean): Map<String, Long?> {
            var size = overhead
            for (k in entries.keys) size += entryBytes(k)
            if (size <= maxBytes) return entries
            val candidates = entries.entries.filter { it.key.startsWith("w:m:t:") && hasIdKey(it.key) }
                .sortedBy { it.value ?: 0L }
            val out = LinkedHashMap(entries)
            for (c in candidates) {
                if (size <= maxBytes) break
                out.remove(c.key); size -= entryBytes(c.key)
            }
            return out
        }
    }

    object Token {
        const val REFRESH_BEFORE_MS = 3_600_000L

        /** Verversen pas als het token minder dan 1 uur geldig is, of na een 401 (dan één keer). */
        fun needsRefresh(expiresAt: Long, now: Long, got401: Boolean = false): Boolean =
            got401 || expiresAt - now < REFRESH_BEFORE_MS

        /**
         * Single-flight: wie wacht op de lock en daarna ziet dat het token al vervangen is (door een andere aanroep of een
         * ander apparaat), ververst niet opnieuw.
         */
        fun stillNeeded(current: String?, failedWith: String?, expiresAt: Long, now: Long): Boolean =
            if (failedWith != null) current == failedWith else needsRefresh(expiresAt, now)

        /**
         * Na een mislukte verversing: een nieuwer token uit het account overnemen, anders het oude houden. Nooit null
         * (alleen een bewuste uitlog zet `trakt: null`).
         */
        fun <T : Any> afterFailure(local: T, server: T?, serverIsNewer: Boolean): T =
            if (server != null && serverIsNewer && server != local) server else local
    }

    object Queue {
        data class Entry(
            val id: String,
            /** "add" (/sync/history) of "remove" (/sync/history/remove) */
            val op: String,
            /** Doel ("m:tmdb:1", "e:imdb:tt1|1-2"): een nieuwere opdracht voor hetzelfde doel vervangt een oudere */
            val target: String,
            /** JSON-body voor Trakt (met `ids` en `watched_at`) */
            val body: String,
            val label: String,
            val attempts: Int = 0,
            val nextAt: Long = 0L,
        )

        /** Toevoegen; een wachtende opdracht voor hetzelfde doel valt weg (de nieuwste wint). */
        fun add(q: List<Entry>, e: Entry): List<Entry> = q.filterNot { it.target == e.target } + e

        fun due(q: List<Entry>, now: Long): List<Entry> = q.filter { it.nextAt <= now }

        /** Backoff: 30 s, 1, 2, 4 … minuten, hooguit 6 uur; bij een 429 de `Retry-After` (seconden) als die langer is. */
        fun backoffMs(attempts: Int, retryAfterSec: Long? = null): Long {
            val base = (30_000L shl attempts.coerceIn(0, 10)).coerceAtMost(6 * 3_600_000L)
            val ra = retryAfterSec?.takeIf { it > 0 }?.times(1000) ?: 0L
            return maxOf(base, ra)
        }

        fun failed(q: List<Entry>, id: String, now: Long, retryAfterSec: Long? = null): List<Entry> = q.map {
            if (it.id != id) it else it.copy(attempts = it.attempts + 1, nextAt = now + backoffMs(it.attempts, retryAfterSec))
        }

        fun done(q: List<Entry>, id: String): List<Entry> = q.filterNot { it.id == id }

        /** Alles nu opnieuw proberen (start, voorgrond, na een verversing). */
        fun wakeAll(q: List<Entry>): List<Entry> = q.map { it.copy(nextAt = 0L) }

        private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
        private fun unb64(s: String) = String(Base64.getDecoder().decode(s), Charsets.UTF_8)

        /** Eén regel per opdracht; tekstvelden base64 (geen tokens: die staan nooit in de wachtrij). */
        fun encode(q: List<Entry>): String = q.joinToString("\n") {
            listOf("1", b64(it.id), it.op, b64(it.target), b64(it.body), b64(it.label), it.attempts.toString(), it.nextAt.toString()).joinToString("\t")
        }

        fun decode(text: String): List<Entry> = text.split('\n').mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 8 || f[0] != "1") return@mapNotNull null
            runCatching {
                Entry(unb64(f[1]), f[2], unb64(f[3]), unb64(f[4]), unb64(f[5]), f[6].toInt(), f[7].toLong())
            }.getOrNull()
        }

        fun save(file: File, q: List<Entry>) {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(encode(q))
            if (!tmp.renameTo(file)) { file.writeText(encode(q)); tmp.delete() }
        }

        fun load(file: File): List<Entry> = runCatching { decode(file.readText()) }.getOrDefault(emptyList())
    }

    object Threshold {
        /** Gezien: 85%, of meer dan 70% met minder dan 3 minuten over (films/afleveringen > 10 min), of in de aftiteling. */
        fun watched(position: Double, durationSec: Double, currentSec: Double, creditsStart: Double?): Boolean {
            if (durationSec <= 60) return false
            val left = durationSec - currentSec
            val inCredits = creditsStart?.let { c -> c > 60 && currentSec >= c } == true
            return position >= 0.85 || (durationSec > 600 && left < 180 && position > 0.7) || inCredits
        }
    }

    object Search {
        /** Een treffer alleen als de titel (norm) klopt en het jaar ±1 (zonder eigen jaar: alleen de titel). */
        fun accept(title: String, year: Int?, hitTitle: String?, hitYear: Int?): Boolean {
            if (hitTitle == null) return false
            val a = WatchKeys.norm(WatchKeys.cleaned(title, null).first)
            if (a.isEmpty() || a != WatchKeys.norm(hitTitle)) return false
            if (year == null || year <= 0) return true
            return hitYear != null && kotlin.math.abs(hitYear - year) <= 1
        }

        /** Uit de treffers de eerste die past; geen blinde `list[0]`. Zonder jaar alleen als er precies één titel past. */
        fun <T> pick(title: String, year: Int?, hits: List<T>, t: (T) -> String?, y: (T) -> Int?): T? {
            val ok = hits.filter { accept(title, year, t(it), y(it)) }
            if (year == null || year <= 0) return ok.singleOrNull()
            return ok.firstOrNull()
        }
    }
}
