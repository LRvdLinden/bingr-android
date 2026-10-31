package nl.lrvdlinden.bingr

import java.io.File

/**
 * Build 158: pure logica (zonder Android, getest in tools/test/Build158LogicTest.kt) voor de grenzen van de lokale opslag.
 * Aanleiding: op tvOS crashte de app doordat UserDefaults boven 1 MB kwam. Op Android laadt SharedPreferences het hele
 * XML-bestand bij de eerste toegang (in `BingrApplication.onCreate`, hoofdthread) en schrijft het bij elke wijziging
 * helemaal opnieuw; `apply()` laat `onPause`/`onStop` wachten tot dat klaar is (ANR op trage tv's).
 *
 * - [Blobs]: welke Prefs-waarden een eigen bestand krijgen (bekende caches, en alles boven [MAX_PREF_VALUE] tekens), in
 *   `cacheDir` (opnieuw op te halen) of `filesDir`, met een eenmalige migratie uit SharedPreferences.
 * - [SectionCap]: de section `profile.<id>` hooguit 1 MB (contract build 154): titelsleutels van films met een tmdb- of
 *   imdb-broer vallen weg, de oudste eerst, **zonder** verwijdering (een verwijdering zou het item uitvinken, want de
 *   nieuwste gebeurtenis over alle sleutels wint).
 */
object Build158Logic {
    /** Grootste waarde (tekens) die nog in SharedPreferences mag; daarboven gaat hij naar een bestand. */
    const val MAX_PREF_VALUE = 16 * 1024

    object Blobs {
        enum class Where { CACHE, FILES }

        /** [exact] = alleen deze sleutel, anders een voorvoegsel. */
        data class Rule(val key: String, val exact: Boolean, val where: Where)

        /** Bekende grote waarden. De eerste regel die past, telt. */
        val rules = listOf(
            // TMDB-titels die in Mijn lijst kunnen staan (max. 400, met beschrijving): niet zomaar weg
            Rule("discover.known", exact = true, where = Where.FILES),
            // TMDB-rijen per soort, kinderprofiel, taal en regio (12 uur bewaard)
            Rule("discover.", exact = false, where = Where.CACHE),
            // Trending per soort en taal (6 uur bewaard)
            Rule("trending.", exact = false, where = Where.CACHE),
            // logo's van streamingdiensten (een week bewaard); de tijd ("….at") blijft klein in Prefs
            Rule("b149.providerLogos", exact = true, where = Where.CACHE),
            // overgeslagen categorieën per server (hoort bij de catalogus in filesDir/servercache)
            Rule("skipped152.", exact = false, where = Where.FILES),
        )

        fun rule(key: String): Rule? = rules.firstOrNull { if (it.exact) key == it.key else key.startsWith(it.key) }

        /** Waar deze waarde hoort: een bestand ([Where]) of null = gewoon in SharedPreferences. */
        fun whereFor(key: String, length: Int): Where? =
            rule(key)?.where ?: if (length > MAX_PREF_VALUE) Where.FILES else null

        /** Veilige bestandsnaam: letters, cijfers, `.`, `_` en `-` blijven; de rest wordt `%XX` (UTF-8). */
        fun fileName(key: String): String {
            val sb = StringBuilder()
            for (b in key.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xff
                val ch = c.toChar()
                if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '.' || ch == '_' || ch == '-') sb.append(ch)
                else sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
            }
            return "$sb.txt"
        }

        /**
         * Wat de migratie verplaatst: alle tekstwaarden die een bestand horen te krijgen, behalve [skip] (sleutels die na de
         * start al opnieuw geschreven zijn: die versie is nieuwer).
         */
        fun plan(all: Map<String, *>, skip: Set<String> = emptySet()): Map<String, Pair<String, Where>> {
            val out = LinkedHashMap<String, Pair<String, Where>>()
            for ((k, v) in all) {
                if (v !is String || k in skip) continue
                val w = whereFor(k, v.length) ?: continue
                out[k] = v to w
            }
            return out
        }
    }

    /** De bestanden zelf (synchroon; de app roept dit aan op één schrijf-thread). */
    class BlobDir(private val files: File, private val cache: File) {
        private fun dir(w: Blobs.Where) = if (w == Blobs.Where.FILES) files else cache
        private fun file(key: String, w: Blobs.Where) = File(dir(w), Blobs.fileName(key))

        fun read(key: String): String? {
            for (w in Blobs.Where.values()) {
                val f = file(key, w)
                if (f.exists()) return runCatching { f.readText() }.getOrNull()
            }
            return null
        }

        fun exists(key: String): Boolean = Blobs.Where.values().any { file(key, it).exists() }

        /** Atomair (tijdelijk bestand, dan hernoemen); een oude kopie in de andere map gaat weg. */
        fun write(key: String, value: String, w: Blobs.Where) {
            val d = dir(w).apply { mkdirs() }
            val f = file(key, w)
            val tmp = File(d, f.name + ".tmp")
            tmp.writeText(value)
            if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) { tmp.delete(); f.writeText(value) } }
            for (o in Blobs.Where.values()) if (o != w) file(key, o).delete()
        }

        fun delete(key: String) { for (w in Blobs.Where.values()) file(key, w).delete() }

        /**
         * Eenmalige migratie: de waarden uit [all] (SharedPreferences) die een bestand horen te krijgen, wegschrijven.
         * Geeft de sleutels terug die daarna uit SharedPreferences weg mogen (alleen wat echt geschreven is).
         */
        fun migrate(all: Map<String, *>, skip: Set<String> = emptySet()): Set<String> {
            val moved = LinkedHashSet<String>()
            for ((k, p) in Blobs.plan(all, skip)) {
                if (runCatching { write(k, p.first, p.second) }.isSuccess) moved += k
            }
            return moved
        }
    }

    object SectionCap {
        /** De server weigert `data` boven 1 MiB (`JSON.stringify(...).length`); een marge eronder. */
        const val MAX_SECTION = 1_000_000

        /** Tekens van één `{"id":"<sleutel>","at":"2026-10-31T12:00:00.000Z"},` in `watched`. */
        fun entryChars(key: String): Int = key.length + 42

        /**
         * Welke sleutels uit `watched` ([watched]: sleutel → at, alleen toevoegingen) weg moeten zodat de section
         * ([sectionChars] tekens) onder [limit] komt: titelsleutels van films (`w:m:t:`) die ook een tmdb- of imdb-sleutel
         * hebben (zelfde `at` = hetzelfde item, zo schrijven markeren en migreren ze), de oudste eerst. Niets anders.
         */
        fun drop(watched: Map<String, Long>, sectionChars: Int, limit: Int = MAX_SECTION): Set<String> {
            if (sectionChars <= limit) return emptySet()
            val idAts = HashSet<Long>()
            for ((k, at) in watched) if (k.startsWith("w:m:tmdb:") || k.startsWith("w:m:imdb:")) idAts += at
            val candidates = watched.entries.filter { it.key.startsWith("w:m:t:") && it.value in idAts }
                .sortedWith(compareBy<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            var size = sectionChars
            val out = LinkedHashSet<String>()
            for (c in candidates) {
                if (size <= limit) break
                out += c.key; size -= entryChars(c.key)
            }
            return out
        }
    }
}
