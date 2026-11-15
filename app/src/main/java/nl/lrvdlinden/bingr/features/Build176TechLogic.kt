package nl.lrvdlinden.bingr

/**
 * Build 176 (versie 1.0.8), techniek op Android: Google TV (Watch Next en preview-kanaal), Cronet, versleutelde opslag
 * van servergegevens en de kiezer voor de audio-uitvoer. Pure logica zonder Android, met unittests
 * (tools/test/Build176TechLogicTest.kt).
 */
object Build176TechLogic {

    // MARK: 1. Google TV / Android TV: Watch Next-rij en preview-kanaal

    object TvHome {
        /** Maximaal aantal tegels in de Watch Next-rij en in het preview-kanaal. */
        const val MAX_WATCH_NEXT = 20
        const val MAX_CHANNEL = 15

        /** Een tegel die Bingr in Watch Next wil hebben (sleutel = de sleutel van Verder kijken, met de server ervoor). */
        data class Want(val key: String, val updated: Long)

        /** Een rij die al in de TvProvider staat (alleen die van Bingr zelf zijn zichtbaar). */
        data class Existing(val rowID: Long, val key: String?, val engaged: Long, val browsable: Boolean)

        data class Plan(val insert: List<String>, val update: List<Pair<Long, String>>, val delete: List<Long>)

        /** Interne sleutel van een tegel: "<server>|<item>" (server leeg = de actieve). */
        fun key(sourceID: String?, itemID: String): String = "${sourceID ?: ""}|$itemID"

        /**
         * Wat er in de TvProvider moet veranderen. Weg wat niet meer in Verder kijken staat (gezien, verwijderd, ander
         * profiel); dubbele rijen weg. Heeft de gebruiker een tegel zelf uit de rij gehaald (niet meer browsable), dan
         * komt hij pas terug als hij daarna opnieuw gekeken is (nieuwere voortgang).
         */
        fun plan(existing: List<Existing>, wanted: List<Want>): Plan {
            val want = LinkedHashMap<String, Want>()
            for (w in wanted.sortedByDescending { it.updated }) if (w.key !in want && want.size < MAX_WATCH_NEXT) want[w.key] = w
            val insert = ArrayList<String>()
            val update = ArrayList<Pair<Long, String>>()
            val delete = ArrayList<Long>()
            val kept = HashSet<String>()
            for (e in existing) {
                val k = e.key
                val w = if (k != null) want[k] else null
                when {
                    k == null || w == null || k in kept -> delete += e.rowID
                    !e.browsable && w.updated <= e.engaged -> kept += k                 // zelf weggehaald: zo laten
                    !e.browsable -> { delete += e.rowID; insert += k; kept += k }       // daarna verder gekeken: opnieuw
                    w.updated != e.engaged -> { update += e.rowID to k; kept += k }
                    else -> kept += k
                }
            }
            for (k in want.keys) if (k !in kept) insert += k
            return Plan(insert, update, delete)
        }

        /**
         * Zenders voor het preview-kanaal "Bingr": de favorieten (in de volgorde van de bibliotheek), anders de laatst
         * bekeken zenders, anders de eerste zenders met een programma nu. Alleen zenders uit de (voor het profiel
         * gefilterde) bibliotheek.
         */
        fun channels(library: List<String>, favorites: Set<String>, recent: List<String>, withNow: Set<String>,
                     max: Int = MAX_CHANNEL): List<String> {
            val inLib = library.toHashSet()
            val fav = library.filter { it in favorites }
            if (fav.isNotEmpty()) return fav.take(max)
            val rec = recent.filter { it in inLib }.distinct()
            if (rec.isNotEmpty()) return rec.take(max)
            return library.filter { it in withNow }.take(max)
        }

        /** Handtekening van de inhoud van het kanaal: alleen opnieuw schrijven als die verandert. */
        fun signature(rows: List<Pair<String, String?>>): String = rows.joinToString("\u0001") { "${it.first}\u0002${it.second ?: ""}" }

        private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        /** Deeplink van een Watch Next-tegel: bingr://continue?id=&src=&title=&profile= */
        fun continueLink(itemID: String, sourceID: String?, title: String, profileID: String?): String = buildString {
            append("bingr://continue?id=").append(enc(itemID))
            if (!sourceID.isNullOrBlank()) append("&src=").append(enc(sourceID))
            if (title.isNotBlank()) append("&title=").append(enc(title))
            if (!profileID.isNullOrBlank()) append("&profile=").append(enc(profileID))
        }

        /** Deeplink van een zender (zelfde als een herinnering): bingr://live?channel=&profile= */
        fun liveLink(channelID: String, profileID: String?): String =
            "bingr://live?channel=" + enc(channelID) + (if (!profileID.isNullOrBlank()) "&profile=" + enc(profileID) else "")

        data class Continue(val itemID: String, val sourceID: String?, val title: String, val profileID: String?)

        /** Query-parameters van bingr://continue → wat te openen (null = ongeldig). */
        fun parseContinue(q: (String) -> String?): Continue? {
            val id = q("id")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 512 } ?: return null
            return Continue(id, q("src")?.trim()?.takeIf { it.isNotEmpty() }, q("title")?.trim().orEmpty(),
                q("profile")?.trim()?.takeIf { it.isNotEmpty() })
        }
    }

    // MARK: 8. Netwerklaag: Cronet met terugval op OkHttp

    object Net {
        enum class Engine { CRONET, OKHTTP }

        /**
         * Welke netwerklaag: Cronet alleen als die via Google Play services beschikbaar is (Fire TV en andere toestellen
         * zonder Play services: OkHttp, zoals altijd) en niet in de rooktest- of screenshotbuild (die hebben bewust geen
         * netwerk, en dat regelt de OkHttp-client).
         */
        fun engine(available: Boolean, enabled: Boolean, offlineBuild: Boolean): Engine =
            if (available && enabled && !offlineBuild) Engine.CRONET else Engine.OKHTTP

        enum class Failure { TIMEOUT, HTTP_CLIENT, HTTP_SERVER, OTHER }

        /** Na een fout met Cronet bij het openen: meteen nog eens met OkHttp? Niet bij een time-out of serverfout. */
        fun retryWithOkHttp(f: Failure): Boolean = f == Failure.HTTP_CLIENT || f == Failure.OTHER

        fun failureOf(httpCode: Int?, timeout: Boolean): Failure = when {
            timeout -> Failure.TIMEOUT
            httpCode != null && httpCode >= 500 -> Failure.HTTP_SERVER
            httpCode != null && httpCode >= 400 -> Failure.HTTP_CLIENT
            else -> Failure.OTHER
        }

        /** Hosts waar OkHttp het wel deed en Cronet niet: de rest van de sessie via OkHttp. */
        class HostMemory(private val limit: Int = 64) {
            private val hosts = LinkedHashSet<String>()
            @Synchronized fun okHttpOnly(host: String?): Boolean = host != null && host.lowercase() in hosts
            @Synchronized fun remember(host: String?) {
                val h = host?.lowercase() ?: return
                hosts.remove(h); hosts.add(h)
                while (hosts.size > limit) hosts.remove(hosts.first())
            }
            @Synchronized fun size(): Int = hosts.size
        }
    }

    // MARK: 9. Versleutelde opslag van servergegevens

    object Vault {
        const val PREFIX = "enc1:"

        /** Bestanden in files/store met servergegevens (M3U-links met inlog, gebruikersnamen, MAC-adressen, NAS). */
        val SEALED_FILES = setOf("playlists", "nas")

        data class Opened(val text: String?, val needsMigration: Boolean, val unreadable: Boolean)

        fun isSealed(stored: String): Boolean = stored.startsWith(PREFIX)

        /**
         * Inhoud van een bestand of waarde lezen. Platte tekst (van vóór build 176) komt ongewijzigd terug en moet nog
         * versleuteld worden; een versleutelde waarde die niet te openen is (Keystore-sleutel weg) is onleesbaar.
         */
        fun open(stored: String, decrypt: (String) -> String?): Opened =
            if (!isSealed(stored)) Opened(stored, needsMigration = true, unreadable = false)
            else decrypt(stored)?.let { Opened(it, false, false) } ?: Opened(null, false, true)

        /** Versleutelen; werkt de Keystore niet (sommige tv-boxen), dan blijft het platte tekst zoals vroeger. */
        fun seal(plain: String, encrypt: (String) -> String?): String = encrypt(plain)?.takeIf { isSealed(it) } ?: plain

        /**
         * Migratie van een bestand: nieuwe inhoud, of null als er niets te doen is (al versleuteld, leeg, geen geldige
         * inhoud volgens [valid], of de Keystore werkt niet).
         */
        fun migrateFile(stored: String, valid: (String) -> Boolean, encrypt: (String) -> String?): String? {
            if (stored.isBlank() || isSealed(stored) || !valid(stored)) return null
            return encrypt(stored)?.takeIf { isSealed(it) }
        }

        /** Migratie van instellingen (sleutel → waarde): alleen de waarden in platte tekst, versleuteld. */
        fun migrateValues(values: Map<String, Any?>, encrypt: (String) -> String?): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for ((k, v) in values) {
                if (v !is String || v.isEmpty() || isSealed(v)) continue
                val e = encrypt(v)?.takeIf { isSealed(it) } ?: return emptyMap()   // Keystore werkt niet: niets wijzigen
                out[k] = e
            }
            return out
        }
    }

    // MARK: 13. Audio-uitvoer in de speler

    object AudioOut {
        enum class Kind { BLUETOOTH, WIRED, USB, HDMI, SPEAKER }

        // AudioDeviceInfo.TYPE_* (vast in Android)
        private const val WIRED_HEADSET = 3
        private const val WIRED_HEADPHONES = 4
        private const val BLUETOOTH_SCO = 7
        private const val BLUETOOTH_A2DP = 8
        private const val HDMI = 9
        private const val HDMI_ARC = 10
        private const val USB_DEVICE = 11
        private const val USB_ACCESSORY = 12
        private const val LINE_ANALOG = 5
        private const val USB_HEADSET = 22
        private const val HEARING_AID = 23
        private const val BLE_HEADSET = 26
        private const val BLE_SPEAKER = 27
        private const val HDMI_EARC = 29
        private const val BLE_BROADCAST = 30

        fun kindOf(type: Int): Kind? = when (type) {
            BLUETOOTH_A2DP, BLUETOOTH_SCO, BLE_HEADSET, BLE_SPEAKER, BLE_BROADCAST, HEARING_AID -> Kind.BLUETOOTH
            WIRED_HEADSET, WIRED_HEADPHONES, LINE_ANALOG -> Kind.WIRED
            USB_DEVICE, USB_ACCESSORY, USB_HEADSET -> Kind.USB
            HDMI, HDMI_ARC, HDMI_EARC -> Kind.HDMI
            else -> null
        }

        /** Waar media nu heen gaat (de standaardvolgorde van Android): Bluetooth, dan bedraad/USB, dan HDMI, anders de luidspreker. */
        fun active(types: List<Int>): Kind {
            val kinds = types.mapNotNull { kindOf(it) }.toSet()
            return listOf(Kind.BLUETOOTH, Kind.WIRED, Kind.USB, Kind.HDMI).firstOrNull { it in kinds } ?: Kind.SPEAKER
        }
    }
}
