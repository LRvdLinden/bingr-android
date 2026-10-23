package nl.lrvdlinden.bingr

import java.text.Normalizer
import java.util.Locale

/**
 * Build 140 (versie 1.0.1, docs/ACCOUNT.md van de iOS-repo → "Build 140"): pure logica zonder Android, zodat hij los te
 * testen is (tools/test/Build140LogicTest.kt, ook in de workflow Build).
 *
 * - [Kids]: is een categorie een kindercategorie (zelfde regels als iOS, web en Samsung TV)?
 * - [CatMarks]: de account-brede set verborgen categorieën (`account.hiddenCategories`): sleutels, samenvoegen, migratie.
 * - [Stamp]: tijd van een lokale wijziging die altijd na de vorige (ook een verwijdering van een ander apparaat) valt.
 * - [Gate]: de rekensom van de ouderpoort.
 * - [Picks]: Voor jou (genres), Nieuw op Bingr (eerst gezien), uitgelicht (vaste roulerende selectie).
 * - [AutoRefresh]: wanneer een server volgens "Afspeellijsten bijwerken" ververst moet worden.
 */
object Build140Logic {
    const val DAY = 86_400_000L

    // MARK: kindercategorieën

    object Kids {
        private val never = listOf("adult", "xxx", "18+", "+18", "porn", "erotic", "erotiek")
        /** Woordbegin: het woord mag doorlopen ("kinderfilms", "cartoons", "disney+"). */
        private val starts = listOf("kinder", "kids", "children", "familie", "family", "jeugd", "cartoon", "animatie", "animation",
            "disney", "pixar", "nickelodeon", "peuter", "kleuter", "ketnet", "zappelin", "jetix", "boomerang")
        /** Heel woord: er mag geen letter of cijfer achter staan ("kidnap" telt niet). */
        private val whole = listOf("kid", "child", "baby", "junior", "nick jr", "z@pp")
        private val prefix = Regex("^\\s*(?:\\|\\s*[a-z]{2,3}\\s*\\||\\[\\s*[a-z]{2,3}\\s*]|\\(\\s*[a-z]{2,3}\\s*\\)|[a-z]{2,3}\\s*(?:\\||:|-|–))\\s*")

        fun fold(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)

        /** Kleine letters, accenten weg en het landvoorvoegsel vooraan weg (ook herhaald): "NL | Kinderfilms" → "kinderfilms". */
        fun normalize(name: String): String {
            var s = fold(name).trim()
            while (true) {
                val n = prefix.replaceFirst(s, "")
                if (n == s || n.isEmpty()) break
                s = n
            }
            return s.trim()
        }

        private fun boundaryBefore(s: String, i: Int) = i == 0 || !s[i - 1].isLetterOrDigit()

        private fun hasStart(s: String, w: String): Boolean {
            var i = s.indexOf(w)
            while (i >= 0) {
                if (boundaryBefore(s, i)) return true
                i = s.indexOf(w, i + 1)
            }
            return false
        }

        private fun hasWhole(s: String, w: String): Boolean {
            var i = s.indexOf(w)
            while (i >= 0) {
                val end = i + w.length
                if (boundaryBefore(s, i) && (end == s.length || !s[end].isLetterOrDigit())) return true
                i = s.indexOf(w, i + 1)
            }
            return false
        }

        /** Nooit voor kinderen (adult, xxx, 18+, porn, erotic …), ook als er een kinderwoord in staat. */
        fun isAdult(name: String): Boolean { val n = normalize(name); return never.any { n.contains(it) } }

        /** Kindercategorie? Naam zoals de server hem levert (vóór hernoemen/samenvoegen). */
        fun isKidsCategory(name: String): Boolean {
            val n = normalize(name)
            if (n.isEmpty() || never.any { n.contains(it) }) return false
            return starts.any { hasStart(n, it) } || whole.any { hasWhole(n, it) }
        }

        /**
         * Leeftijd binnen een kindercategorie: een bekende classificatie boven de grens = niet; zonder bekende
         * classificatie wel (hij komt uit een kindercategorie).
         */
        fun ageAllowed(age: Int?, limit: Int?): Boolean = age == null || limit == null || age <= limit
    }

    // MARK: account-brede verborgen categorieën

    /** Eén sleutel: wanneer (at, epoch-ms) en of hij aan staat (on = categorie verborgen) of verwijderd is. */
    data class Mark(val at: Long, val on: Boolean)

    object CatMarks {
        /** Sleutel "<server-id kleine letters>|<categorienaam exact>". */
        fun norm(key: String): String {
            val bar = key.indexOf('|')
            return if (bar <= 0) key else key.substring(0, bar).lowercase(Locale.ROOT) + key.substring(bar)
        }
        fun key(sid: String, name: String) = sid.lowercase(Locale.ROOT) + "|" + name

        /** Sleutels hoofdletterongevoelig op de server-id samenvoegen: per sleutel wint de nieuwste tijd. */
        fun normalizeAll(m: Map<String, Mark>): Map<String, Mark> {
            val out = HashMap<String, Mark>()
            for ((k, v) in m) { val n = norm(k); val c = out[n]; if (c == null || v.at > c.at) out[n] = v }
            return out
        }

        /** Samenvoegen zoals myList: per sleutel wint de nieuwste `at` (bij gelijke tijd blijft [a]). */
        fun merge(a: Map<String, Mark>, b: Map<String, Mark>): Map<String, Mark> {
            val out = HashMap(normalizeAll(a))
            for ((k, v) in normalizeAll(b)) { val c = out[k]; if (c == null || v.at > c.at) out[k] = v }
            return out
        }

        /** Verwijderingen ouder dan [days] dagen weg. */
        fun prune(m: Map<String, Mark>, now: Long, days: Long = 90): Map<String, Mark> {
            val cutoff = now - days * DAY
            return m.filter { it.value.on || it.value.at >= cutoff }
        }

        fun alive(m: Map<String, Mark>): Set<String> = m.filter { it.value.on }.keys

        /** Per server-id (kleine letters) de verborgen namen, voor snel filteren. */
        fun index(alive: Collection<String>): Map<String, Set<String>> {
            val out = HashMap<String, MutableSet<String>>()
            for (k in alive) {
                val bar = k.indexOf('|')
                if (bar <= 0) continue
                out.getOrPut(k.substring(0, bar).lowercase(Locale.ROOT)) { HashSet() }.add(k.substring(bar + 1))
            }
            return out
        }

        /**
         * Categorieën aan of uit: [hidden] = verbergen. Nieuwe tijd na de vorige van die sleutel ([Stamp.after]), zodat een
         * eerdere keuze van een apparaat met een voorlopende klok niet wint.
         */
        fun set(m: Map<String, Mark>, keys: Collection<String>, hidden: Boolean, now: Long): Map<String, Mark> {
            val out = HashMap(normalizeAll(m))
            for (raw in keys) {
                val k = norm(raw)
                val cur = out[k]
                if (cur?.on == hidden) continue
                if (cur == null && !hidden) continue
                out[k] = Mark(Stamp.after(now, cur?.at), hidden)
            }
            return out
        }

        /** Server verwijderd: alle sleutels van die server als verwijdering (at = nu). */
        fun removeServer(m: Map<String, Mark>, sid: String, now: Long): Map<String, Mark> {
            val pre = sid.lowercase(Locale.ROOT) + "|"
            var changed = false
            val out = HashMap(normalizeAll(m))
            for ((k, v) in out.entries.toList()) if (v.on && k.startsWith(pre)) { out[k] = Mark(Stamp.after(now, v.at), false); changed = true }
            return if (changed) out else m
        }

        /**
         * Migratie (eenmalig per apparaat): de unie van de per-profiel-sets van alle niet-kinderprofielen met `at` = 0, alleen
         * waar de account-set die sleutel nog niet kent (een keuze of verwijdering daar wint dus altijd).
         */
        fun migrate(m: Map<String, Mark>, perProfile: Collection<String>): Map<String, Mark> {
            val out = HashMap(normalizeAll(m))
            for (raw in perProfile) { val k = norm(raw); if (k !in out && k.indexOf('|') > 0) out[k] = Mark(0L, true) }
            return out
        }
    }

    // MARK: tijden van lokale wijzigingen

    object Stamp {
        /** Nu, maar altijd na [previous] (de laatst bekende tijd van die sleutel, ook als die van een ander apparaat komt). */
        fun after(now: Long, previous: Long?): Long = if (previous == null) now else maxOf(now, previous + 1)
    }

    // MARK: ouderpoort

    object Gate {
        data class Sum(val a: Int, val b: Int, val multiply: Boolean) {
            val answer: Int get() = if (multiply) a * b else a + b
            val text: String get() = if (multiply) "$a × $b = ?" else "$a + $b = ?"
        }
        const val MAX_DIGITS = 3
        const val MAX_TRIES = 3

        /** Helft vermenigvuldigen (6…12 × 3…9), helft optellen (11…49 + 11…49). */
        fun newSum(r: kotlin.random.Random = kotlin.random.Random.Default): Sum =
            if (r.nextBoolean()) Sum(r.nextInt(6, 13), r.nextInt(3, 10), true) else Sum(r.nextInt(11, 50), r.nextInt(11, 50), false)

        enum class Result { OK, WRONG, NEW_SUM, WAIT }

        /** Invoer controleren: goed; fout (opnieuw); na 3 keer fout een nieuwe som; of nog niet genoeg cijfers ([WAIT]). */
        class State(var sum: Sum, private val random: kotlin.random.Random = kotlin.random.Random.Default) {
            var tries = 0; private set
            fun check(input: String, force: Boolean): Result {
                val want = sum.answer.toString()
                if (!force && input.length < want.length) return Result.WAIT
                if (input == want) return Result.OK
                tries++
                if (tries >= MAX_TRIES) { sum = newSum(random); tries = 0; return Result.NEW_SUM }
                return Result.WRONG
            }
        }
    }

    // MARK: Voor jou, Nieuw op Bingr en uitgelicht

    object Picks {
        /** Genres als losse woorden in kleine letters ("Action, Sci-Fi" → [action, sci-fi]). */
        fun genres(s: String?): List<String> =
            s?.split(',', '/', '|', '&')?.map { it.trim().lowercase(Locale.ROOT) }?.filter { it.isNotEmpty() && it != "n/a" } ?: emptyList()

        /** Gewicht per genre: recentere titels tellen zwaarder (eerste seed = 1.0, dan 0.85, 0.7 …). */
        fun genreWeights(seeds: List<List<String>>): Map<String, Double> {
            val out = HashMap<String, Double>()
            seeds.forEachIndexed { i, gs -> val w = maxOf(0.3, 1.0 - 0.15 * i); gs.forEach { g -> out[g] = (out[g] ?: 0.0) + w } }
            return out
        }

        data class Cand(val key: String, val genres: List<String>, val rating: Double?, val addedAt: Long?)

        /** Kandidaten met de meeste overlap in genres eerst, dan beoordeling, dan nieuwste; deterministisch. */
        fun byGenres(cands: List<Cand>, weights: Map<String, Double>, exclude: Set<String>, limit: Int): List<String> {
            if (weights.isEmpty()) return emptyList()
            return cands.asSequence().filter { it.key !in exclude }
                .map { c -> c to c.genres.sumOf { weights[it] ?: 0.0 } }
                .filter { it.second > 0 }
                .sortedWith(compareByDescending<Pair<Cand, Double>> { it.second }
                    .thenByDescending { it.first.rating ?: 0.0 }.thenByDescending { it.first.addedAt ?: 0L }.thenBy { it.first.key })
                .take(limit).map { it.first.key }.toList()
        }

        /**
         * Eerst gezien (lokaal per apparaat): een server die nog nooit gezien is, krijgt voor alles 0 (geen "nieuw" na een
         * eerste installatie); daarna krijgt een nieuwe id de tijd [now]. Ids die er niet meer zijn gaan weg.
         */
        fun firstSeen(known: Map<String, Long>?, ids: Collection<String>, now: Long): Map<String, Long> {
            if (known == null) return ids.associateWith { 0L }
            val out = HashMap<String, Long>(ids.size * 2)
            for (id in ids) out[id] = known[id] ?: now
            return out
        }

        /** Datum voor Nieuw op Bingr: die van de provider, anders eerst gezien; 0 = onbekend (niet tonen). */
        fun addedDate(provider: Long?, seen: Long?): Long = provider?.takeIf { it > 0 } ?: seen ?: 0L

        /** Uitgelicht: om en om uit Voor jou en Nieuw op Bingr, dubbele weg, max. [max]. */
        fun <T> heroPool(forYou: List<T>, fresh: List<T>, key: (T) -> String, max: Int = 8): List<T> {
            val out = ArrayList<T>()
            val seen = HashSet<String>()
            var i = 0
            while (out.size < max && (i < forYou.size || i < fresh.size)) {
                forYou.getOrNull(i)?.let { if (seen.add(key(it)) && out.size < max) out += it }
                fresh.getOrNull(i)?.let { if (seen.add(key(it)) && out.size < max) out += it }
                i++
            }
            return out
        }

        /** Dagnummer in de tijdzone van het apparaat. */
        fun dayNumber(now: Long, offsetMs: Int): Long = Math.floorDiv(now + offsetMs, DAY)

        /** Startplek van de selectie: schuift elke dag één plek op. */
        fun heroStart(day: Long, size: Int): Int = if (size <= 0) 0 else Math.floorMod(day, size.toLong()).toInt()

        const val HERO_INTERVAL = 8_000L
    }

    // MARK: automatisch vernieuwen

    object AutoRefresh {
        const val SIX_HOURS = 6 * 3_600_000L
        /** Hoe vaak de app kijkt of een server aan de beurt is (zolang hij draait). */
        const val CHECK_EVERY = 15 * 60_000L

        /**
         * Moet een server nu ververst worden? [policy] = raw value ("manual", "launch", "sixHours", "daily", "weekly"),
         * [ageMs] = leeftijd van zijn cache (null = geen cache), [doneThisLaunch] = in deze sessie al ververst.
         */
        fun due(policy: String, ageMs: Long?, doneThisLaunch: Boolean): Boolean = when (policy) {
            "manual" -> false
            "launch" -> !doneThisLaunch
            "sixHours" -> ageMs == null || ageMs > SIX_HOURS
            "weekly" -> ageMs == null || ageMs > 7 * DAY
            else -> ageMs == null || ageMs > DAY   // daily (standaard)
        }

        /** Welke servers nu aan de beurt zijn (actieve eerst). */
        fun dueServers(ids: List<String>, active: String?, policy: String, age: (String) -> Long?, done: Set<String>): List<String> =
            AllServersLogic.refreshOrder(ids, active).filter { due(policy, age(it), it.lowercase(Locale.ROOT) in done) }
    }
}
