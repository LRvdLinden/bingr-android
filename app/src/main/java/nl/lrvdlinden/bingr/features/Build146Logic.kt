package nl.lrvdlinden.bingr

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Build 146: pure logica (zonder Android, getest in tools/test/Build146LogicTest.kt en, voor Samsung TV, in
 * tizen/test/build146_test.js met dezelfde gevallen). Contract: docs/ACCOUNT.md in iptv-ios-appletv, "Build 146".
 *
 * - [Pin]: pincode als hash (`pinHash`): PBKDF2-HMAC-SHA256, 100.000 iteraties, salt 16 bytes, hash 32 bytes
 *   (referentie: supabase/functions/_shared/pin.ts, met dezelfde testvectoren).
 * - [PinChoice]: welke pincode geldt (server of lokaal) en wanneer een oude `pin` meteen gehasht en geüpload wordt.
 * - [ProfileMerge]: de profiellijst van `get_profiles` samenvoegen met de lokale profielen (tombstones, `updatedAt`).
 * - [Support]: groepen (Open / In behandeling / Gesloten), het label "Wacht op jouw reactie", de deeplink
 *   `bingr://support/ticket?n=` en de controles van het formulier.
 * - [Diag]: apparaattype, opschonen (geen persoonsgegevens), namen van mijlpalen en de startdiagnose (vorige start).
 * - [Epg]: is de tv-gids verlopen (geen programma's meer voor nu of de komende uren)?
 */
object Build146Logic {

    // MARK: base64 (standaard, RFC 4648 §4, met opvulling; java.util.Base64 bestaat pas vanaf API 26)

    object B64 {
        private const val ABC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

        fun encode(b: ByteArray): String {
            val sb = StringBuilder((b.size + 2) / 3 * 4)
            var i = 0
            while (i + 2 < b.size) {
                val n = ((b[i].toInt() and 255) shl 16) or ((b[i + 1].toInt() and 255) shl 8) or (b[i + 2].toInt() and 255)
                sb.append(ABC[n shr 18 and 63]).append(ABC[n shr 12 and 63]).append(ABC[n shr 6 and 63]).append(ABC[n and 63])
                i += 3
            }
            val rest = b.size - i
            if (rest == 1) {
                val n = (b[i].toInt() and 255) shl 16
                sb.append(ABC[n shr 18 and 63]).append(ABC[n shr 12 and 63]).append("==")
            } else if (rest == 2) {
                val n = ((b[i].toInt() and 255) shl 16) or ((b[i + 1].toInt() and 255) shl 8)
                sb.append(ABC[n shr 18 and 63]).append(ABC[n shr 12 and 63]).append(ABC[n shr 6 and 63]).append('=')
            }
            return sb.toString()
        }

        /** null = geen geldige standaard-base64 (lengte geen veelvoud van 4, verkeerde tekens of opvulling). */
        fun decode(s: String?): ByteArray? {
            if (s == null || s.length % 4 != 0) return null
            if (!Regex("^[A-Za-z0-9+/]*={0,2}$").matches(s)) return null
            val pad = s.takeLastWhile { it == '=' }.length
            val out = ByteArray(s.length / 4 * 3 - pad)
            var o = 0
            var i = 0
            while (i < s.length) {
                var n = 0
                for (j in 0 until 4) {
                    val c = s[i + j]
                    val v = if (c == '=') 0 else ABC.indexOf(c)
                    n = (n shl 6) or v
                }
                for (j in 0 until 3) {
                    if (o < out.size) out[o++] = (n shr (16 - 8 * j) and 255).toByte()
                }
                i += 4
            }
            return out
        }
    }

    // MARK: pincode als hash

    object Pin {
        const val ALG = "pbkdf2-sha256"
        const val ITER = 100_000
        const val SALT_BYTES = 16
        const val HASH_BYTES = 32
        /** Grenzen voor `iter` bij het lezen (een latere build mag het aantal verhogen). */
        const val ITER_MIN = 10_000
        const val ITER_MAX = 2_000_000

        /** Een geldige pinHash: [salt] en [hash] als standaard-base64. */
        data class Hash(val iter: Int, val salt: String, val hash: String) {
            /** Vaste vorm (zelfde volgorde van de sleutels), zodat dezelfde hash overal dezelfde tekst is. */
            fun json(): String = "{\"alg\":\"$ALG\",\"iter\":$iter,\"salt\":\"$salt\",\"hash\":\"$hash\"}"
        }

        /** 4 t/m 8 cijfers (ASCII), precies zoals ingetypt. */
        fun valid(pin: String?): Boolean = pin != null && Regex("^[0-9]{4,8}$").matches(pin)

        /** Velden van een pinHash-object (uit JSON) → [Hash], of null als het geen geldige hash is. */
        fun parse(alg: Any?, iter: Any?, salt: Any?, hash: Any?): Hash? {
            if (alg != ALG) return null
            val it = when (iter) {
                is Int -> iter
                is Long -> iter.toInt().takeIf { it.toLong() == iter }
                is Double -> iter.toInt().takeIf { it.toDouble() == iter }
                is String -> iter.toIntOrNull()
                else -> null
            } ?: return null
            if (it < ITER_MIN || it > ITER_MAX) return null
            val s = salt as? String ?: return null
            val h = hash as? String ?: return null
            val sb = B64.decode(s) ?: return null
            val hb = B64.decode(h) ?: return null
            if (sb.size < 8 || sb.size > 64 || hb.size != HASH_BYTES) return null
            return Hash(it, s, h)
        }

        private val fieldRe = Regex("\"(alg|iter|salt|hash)\"\\s*:\\s*(\"([^\"]*)\"|(-?[0-9.]+))")

        /** Lokaal bewaarde tekst (de vorm van [Hash.json], of JSON met de sleutels in een andere volgorde) → [Hash]. */
        fun fromStored(text: String?): Hash? {
            val t = text?.trim() ?: return null
            if (!t.startsWith("{")) return null
            val m = HashMap<String, String>()
            for (r in fieldRe.findAll(t)) m[r.groupValues[1]] = r.groups[3]?.value ?: r.groupValues[4]
            return parse(m["alg"], m["iter"], m["salt"], m["hash"])
        }

        /** Is dit een oude, leesbare pincode (alleen cijfers)? */
        fun isLegacy(stored: String?): Boolean = valid(stored)

        /** PBKDF2-HMAC-SHA256 met de standaardimplementatie; ontbreekt die (Android 7), dan [derivePortable]. */
        fun derive(pin: String, salt: ByteArray, iter: Int, dkLen: Int = HASH_BYTES): ByteArray = try {
            val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val spec = PBEKeySpec(pin.toCharArray(), salt, iter, dkLen * 8)
            try { f.generateSecret(spec).encoded } finally { spec.clearPassword() }
        } catch (e: java.security.GeneralSecurityException) {
            derivePortable(pin, salt, iter, dkLen)
        }

        /** PBKDF2-HMAC-SHA256 met alleen `Mac` (HmacSHA256 bestaat op elke Android-versie); één blok (dkLen ≤ 32). */
        fun derivePortable(pin: String, salt: ByteArray, iter: Int, dkLen: Int = HASH_BYTES): ByteArray {
            require(dkLen <= 32) { "dkLen > 32" }
            val mac = Mac.getInstance("HmacSHA256")
            // een lege sleutel mag SecretKeySpec niet; een pincode is nooit leeg (zie [valid])
            require(pin.isNotEmpty()) { "bad_pin" }
            mac.init(SecretKeySpec(pin.toByteArray(Charsets.US_ASCII), "HmacSHA256"))
            mac.update(salt)
            mac.update(byteArrayOf(0, 0, 0, 1))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (n in 1 until iter) {
                u = mac.doFinal(u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            return t.copyOf(dkLen)
        }

        private fun derive3(pin: String, salt: ByteArray, iter: Int): ByteArray = derive(pin, salt, iter)

        private val random by lazy { SecureRandom() }

        /** Nieuwe hash (nieuwe salt, tenzij meegegeven voor een test). */
        fun make(pin: String, salt: ByteArray? = null, derive: (String, ByteArray, Int) -> ByteArray = ::derive3): Hash {
            require(valid(pin)) { "bad_pin" }
            val s = salt ?: ByteArray(SALT_BYTES).also { random.nextBytes(it) }
            return Hash(ITER, B64.encode(s), B64.encode(derive(pin, s, ITER)))
        }

        /** Klopt de pincode? Vergelijkt in vaste tijd; een ongeldige hash of pincode = false. */
        fun verify(pin: String, h: Hash?, derive: (String, ByteArray, Int) -> ByteArray = ::derive3): Boolean {
            if (h == null || !valid(pin)) return false
            val salt = B64.decode(h.salt) ?: return false
            val want = B64.decode(h.hash) ?: return false
            val got = derive(pin, salt, h.iter)
            return constantTimeEquals(got, want)
        }

        fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
            var d = a.size xor b.size
            for (i in 0 until minOf(a.size, b.size)) d = d or (a[i].toInt() xor b[i].toInt())
            return d == 0
        }
    }

    // MARK: welke pincode geldt

    /**
     * Regels (ACCOUNT.md, "Pincode als hash" → "Regels voor apps vanaf build 146"):
     * - `pin`, `pinHash` en `pinUpdatedAt` zijn samen één geheel: het geheel met de nieuwste tijd wint;
     * - is `pinHash` er (geldig), dan geldt alleen de hash; ontbreekt hij en is `pin` een tekst, dan is dat een pincode van
     *   een oude app: meteen hashen met dezelfde `pinUpdatedAt` en uploaden;
     * - `pinHash: null` of `pin: null` (zonder hash) = geen pincode.
     */
    object PinChoice {
        sealed class Server {
            /** De sectie zegt niets over de pincode (geen `pin`, geen `pinHash`). */
            object Absent : Server()
            /** Geen pincode (verwijderd, of nooit gezet). */
            object Cleared : Server()
            data class Hashed(val hash: Pin.Hash) : Server()
            /** Leesbare pincode van een oude app: meteen hashen en uploaden. */
            data class Legacy(val pin: String) : Server()
            /** `pinHash` is er maar ongeldig (onbekende alg, te weinig iteraties): als geen pincode behandelen. */
            object Invalid : Server()
        }

        /**
         * Wat de server heeft. [hasPinHashKey]/[pinHash] = de sleutel `pinHash` en zijn velden (null = JSON-null),
         * [hasPinKey]/[pin] = de sleutel `pin` als tekst.
         */
        fun read(hasPinHashKey: Boolean, pinHash: Pin.Hash?, pinHashIsNull: Boolean, hasPinKey: Boolean, pin: String?): Server {
            if (hasPinHashKey && !pinHashIsNull) return if (pinHash != null) Server.Hashed(pinHash) else Server.Invalid
            if (hasPinKey && Pin.valid(pin)) return Server.Legacy(pin!!)
            if (hasPinHashKey || hasPinKey) return Server.Cleared
            return Server.Absent
        }

        enum class Side { LOCAL, SERVER }

        /**
         * Lokaal of server? Nieuwste tijd wint. Bij gelijke tijd: lokaal, behalve als allebei een (andere) hash hebben: dan
         * de server, zodat twee apparaten die dezelfde oude pincode elk met een eigen salt hashten niet om en om uploaden.
         * Een lokale oude (leesbare) pincode verliest bij gelijke tijd van een hash van de server.
         */
        fun pick(localAt: Long, localValue: String?, serverAt: Long, serverValue: String?): Side = when {
            serverAt > localAt -> Side.SERVER
            serverAt < localAt -> Side.LOCAL
            localValue != null && serverValue != null && localValue != serverValue &&
                Pin.fromStored(serverValue) != null && (Pin.fromStored(localValue) != null || Pin.isLegacy(localValue)) -> Side.SERVER
            else -> Side.LOCAL
        }
    }

    // MARK: profielen samenvoegen (get_profiles / put_profiles)

    /**
     * Samenvoegen van de serverlijst met de lokale profielen ("Profielen volledig synchroniseren", build 41; de server voegt
     * sinds build 146 zelf ook samen, dit is de kant van de app):
     * - server-tombstone en lokaal aanwezig → lokaal verwijderen (nooit het hoofdprofiel van dit apparaat);
     * - server actief en lokaal ontbreekt (en niet lokaal verwijderd) → toevoegen;
     * - allebei actief → is de server strikt nieuwer dan de lokale tijd: overnemen; lokaal nieuwer: uploaden;
     * - lokaal actief en niet op de server → houden en uploaden; lokale tombstones die de server nog niet kent → uploaden.
     * Sortering op `order` (lokaal behouden: eigen plek), daarna de volgorde van de lijst.
     */
    object ProfileMerge {
        const val EPOCH = "1970-01-01T00:00:00.000Z"

        data class Local(val key: String, val updatedAt: String?)
        data class Row(val key: String, val updatedAt: String?, val deleted: Boolean, val isMain: Boolean, val order: Int?)

        enum class Kind { KEEP, TAKE, ADD }
        /** [local] = index in de lokale lijst (KEEP/TAKE), [row] = sleutel van de serverregel (TAKE/ADD, KEEP als die er is). */
        data class Entry(val kind: Kind, val local: Int?, val row: String?)

        data class Plan(
            val entries: List<Entry>,
            /** indexen van lokale profielen die weg moeten */
            val removed: List<Int>,
            /** nieuwe lokale tijd per profiel (overgenomen of toegevoegd) */
            val stamps: Map<String, String>,
            val needUpload: Boolean,
            /** sleutel van het hoofdprofiel van de server als dat hier overgenomen moet worden */
            val adoptMain: String?,
        )

        /** [newer] = is a strikt nieuwer dan b (ISO-8601 met milliseconden). */
        fun plan(local: List<Local>, rows: List<Row>, tombs: Set<String>, mainKey: String?, newer: (String, String) -> Boolean): Plan {
            val byKey = LinkedHashMap<String, Row>()
            rows.forEach { byKey[it.key] = it }
            var needUpload = false
            val serverMain = rows.firstOrNull { !it.deleted && it.isMain }?.key
            fun rowNewer(key: String?): Boolean {
                val r = key?.let { byKey[it] } ?: return false
                if (r.deleted) return false
                val lu = local.firstOrNull { it.key == key }?.updatedAt ?: EPOCH
                return newer(r.updatedAt?.takeIf { it.isNotBlank() } ?: EPOCH, lu)
            }
            val adoptMain = serverMain != null && serverMain != mainKey && serverMain !in tombs && (rowNewer(serverMain) || rowNewer(mainKey))

            val stamps = LinkedHashMap<String, String>()
            val removed = ArrayList<Int>()
            val merged = ArrayList<Pair<Entry, Int>>()
            local.forEachIndexed { i, p ->
                val o = byKey[p.key]
                when {
                    o == null -> { needUpload = true; merged += Entry(Kind.KEEP, i, null) to i }
                    o.deleted -> {
                        if (p.key == mainKey) { needUpload = true; merged += Entry(Kind.KEEP, i, null) to i }
                        else removed += i
                    }
                    else -> {
                        val su = o.updatedAt?.takeIf { it.isNotBlank() } ?: EPOCH
                        val lu = p.updatedAt ?: EPOCH
                        if (newer(su, lu)) {
                            merged += Entry(Kind.TAKE, i, o.key) to (o.order ?: i)
                            stamps[p.key] = su
                        } else {
                            if (newer(lu, su)) needUpload = true
                            merged += Entry(Kind.KEEP, i, o.key) to i
                        }
                    }
                }
            }
            val present = local.map { it.key }.toSet()
            for (o in rows) {
                if (o.deleted || o.key in present) continue
                if (o.key in tombs) { needUpload = true; continue }
                merged += Entry(Kind.ADD, null, o.key) to (o.order ?: Int.MAX_VALUE)
                stamps[o.key] = o.updatedAt?.takeIf { it.isNotBlank() } ?: EPOCH
            }
            if (tombs.any { k -> byKey[k]?.let { !it.deleted } ?: true }) needUpload = true
            val sorted = merged.withIndex().sortedWith(compareBy<IndexedValue<Pair<Entry, Int>>>({ it.value.second }, { it.index })).map { it.value.first }
            val keyOf = { e: Entry -> e.row ?: e.local?.let { local[it].key } }
            val newMain = if (adoptMain) serverMain?.takeIf { m -> sorted.any { keyOf(it) == m } } else null
            return Plan(sorted, removed, stamps, needUpload, newMain)
        }
    }

    // MARK: support

    object Support {
        enum class Group(val raw: String) { OPEN("open"), IN_PROGRESS("in_progress"), CLOSED("closed") }

        val categories = listOf("account", "afspelen", "apps", "website", "overig")

        /** Tab van een ticket: `open` = new; `in_progress` = open en pending; `closed` = resolved en closed. */
        fun group(status: String?, group: String? = null): Group {
            Group.entries.firstOrNull { it.raw == group }?.let { return it }
            return when (status?.lowercase()) {
                "open", "pending" -> Group.IN_PROGRESS
                "resolved", "closed" -> Group.CLOSED
                else -> Group.OPEN
            }
        }

        /** Engelse sleutel (Translations.json) van een status, zoals op support.bingr.online. */
        fun statusKey(status: String?): String = when (status?.lowercase()) {
            "new" -> "New"
            "open" -> "In progress"
            "pending" -> "Awaiting your reply"
            "resolved" -> "Resolved"
            "closed" -> "Closed"
            else -> "Open"
        }

        fun categoryKey(c: String?): String = when (c) {
            "account" -> "Account and sign-in"
            "afspelen" -> "Playback and streams"
            "apps" -> "Apps and installing"
            "website" -> "Website and web player"
            else -> "Other"
        }

        /** Badge: aantal ongelezen, boven 99 "99+"; 0 = geen badge (null). */
        fun badge(unread: Int?): String? = when {
            unread == null || unread <= 0 -> null
            unread > 99 -> "99+"
            else -> unread.toString()
        }

        /** `bingr://support` (lijst, ticket null) of `bingr://support/ticket?n=<nummer>`; null = geen supportlink. */
        data class Link(val ticket: Int?)
        fun parseLink(url: String?): Link? {
            val u = url?.trim() ?: return null
            val m = Regex("^bingr://support(/[^?#]*)?(\\?[^#]*)?", RegexOption.IGNORE_CASE).find(u) ?: return null
            val path = m.groupValues[1].trimEnd('/').lowercase()
            val query = m.groupValues[2].removePrefix("?")
            if (path.isEmpty()) return Link(null)
            if (path != "/ticket") return Link(null)
            val n = query.split('&').firstNotNullOfOrNull { kv ->
                val p = kv.split('=', limit = 2)
                if (p.size == 2 && p[0] == "n") p[1].trim().toIntOrNull()?.takeIf { it > 0 } else null
            }
            return Link(n)
        }

        /** Controle zoals het formulier en de server; null = goed, anders de foutcode van de server. */
        fun checkNew(subject: String, message: String): String? {
            val s = subject.trim(); val m = message.trim()
            return when {
                s.isEmpty() -> "subject_missing"
                s.length < 3 -> "subject_missing"
                s.length > 120 -> "subject_too_long"
                m.isEmpty() -> "message_missing"
                m.length < 10 -> "message_too_short"
                m.length > 5000 -> "message_too_long"
                links(m) > 6 -> "too_many_links"
                else -> null
            }
        }

        fun checkReply(message: String): String? {
            val m = message.trim()
            return when {
                m.isEmpty() -> "message_missing"
                m.length > 5000 -> "message_too_long"
                links(m) > 6 -> "too_many_links"
                else -> null
            }
        }

        fun links(s: String): Int = Regex("(https?://|www\\.)", RegexOption.IGNORE_CASE).findAll(s).count()

        /** Engelse sleutel bij een foutcode van `support`. */
        fun errorKey(code: String?): String = when (code) {
            "subject_missing" -> "Enter a subject (at least 3 characters)."
            "subject_too_long" -> "The subject is too long (max. 120 characters)."
            "message_missing", "message_too_short" -> "Describe your question in at least 10 characters."
            "message_too_long" -> "Your message is too long (max. 5000 characters)."
            "too_many_links" -> "Your message contains too many links (max. 6)."
            "rate_limited" -> "Too many requests. Wait a moment and try again."
            "closed" -> "This ticket is closed. Create a new ticket."
            "not_found" -> "This ticket wasn't found."
            "diagnostics_too_large", "too_large" -> "The diagnostics are too large to send."
            else -> "Something went wrong. Please try again."
        }
    }

    // MARK: diagnose

    object Diag {
        /** `phone`, `tablet` of `tv` (Android); Fire TV / Google TV / Android TV = tv. */
        fun deviceType(isTv: Boolean, smallestWidthDp: Int): String = when {
            isTv -> "tv"
            smallestWidthDp >= 600 -> "tablet"
            else -> "phone"
        }

        private val nameRe = Regex("^[a-z][a-z0-9_.-]{0,39}$")
        fun validMilestone(name: String): Boolean = nameRe.matches(name)

        private val scrubRules = listOf(
            Regex("[A-Za-z][A-Za-z0-9+.-]*://\\S+") to "<url>",
            Regex("[^\\s\"'<>]+@[^\\s\"'<>]+\\.[A-Za-z]{2,}") to "<e>",
            Regex("\\b([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\\b") to "<mac>",
            Regex("\\b(\\d{1,3}\\.){3}\\d{1,3}\\b") to "<ip>",
            Regex("/(Users|home|storage/emulated/\\d+|data/user/\\d+|sdcard)/[^/\\s]+") to "/~",
            Regex("(?i)(username|user|password|pass|pwd|token|key|mac)=[^&\\s]*") to "$1=…",
            Regex("\"[^\"]{24,}\"") to "\"…\"",
        )

        /** Geen persoonsgegevens: URL's, e-mail, IP- en MAC-adressen, paden met een gebruikersmap en geheimen weg. */
        fun scrub(s: String, max: Int = 300): String {
            var t = s
            for ((re, r) in scrubRules) t = re.replace(t, r)
            return t.take(max)
        }

        /** Stack: hooguit 50 regels van hooguit 200 tekens, opgeschoond. */
        fun stack(lines: List<String>): List<String> =
            lines.map { scrub(it.trim(), 200) }.filter { it.isNotBlank() }.take(50)

        // startdiagnose (= StartDiag in iptv-ios-appletv/Shared/Account/Build141.swift)

        data class Mark(val name: String, val ms: Long, val mb: Int? = null)
        data class Crash(val at: Long, val type: String, val reason: String, val stack: List<String>, val build: Int)
        data class Record(
            val build: Int,
            val os: String,
            val started: Long,
            val marks: List<Mark> = emptyList(),
            val finished: Boolean = false,
            val crash: Crash? = null,
            val peakMB: Int? = null,
            /** hoofdthread langer dan [HANG_LIMIT_MS] vast (gezien door de bewaking), in ms */
            val hangMs: Long? = null,
            val hangAt: String? = null,
        )
        enum class Kind { CRASH, HANG, NOT_FINISHED }
        data class Problem(
            val kind: Kind, val build: Int, val os: String, val at: Long, val lastMark: String, val ms: Long,
            val marks: List<Mark>, val crash: Crash?, val peakMB: Int?,
        )

        const val FINAL_MARK = "ready"
        const val FIRST_MARK = "start"
        const val HANG_LIMIT_MS = 10_000L
        const val MAX_MARKS = 40

        /** Mijlpaal toevoegen (alleen de eerste keer per naam, max. 40, niet na "klaar"); ongeldige naam = niets. */
        fun mark(r: Record, name: String, ms: Long, mb: Int?): Record {
            if (r.finished || !validMilestone(name) || r.marks.any { it.name == name } || r.marks.size >= MAX_MARKS) return r
            val t = ms.coerceIn(0L, 600_000L)
            return r.copy(
                marks = r.marks + Mark(name, t, mb),
                peakMB = if (mb != null) maxOf(r.peakMB ?: 0, mb) else r.peakMB,
                finished = name == FINAL_MARK,
            )
        }

        /**
         * Wat er mis was met de vorige start (null = die kwam bij "klaar", zonder crash of lange hang). [requireMark]: een
         * start zonder die mijlpaal (Android: "activity"; het proces startte zonder scherm, bijv. voor een pushmelding, na een
         * herstart van het toestel of voor een download) telt alleen bij een crash.
         */
        fun analyze(r: Record?, requireMark: String? = null): Problem? {
            r ?: return null
            if (requireMark != null && r.crash == null && r.marks.none { it.name == requireMark }) return null
            val last = r.marks.lastOrNull()?.name ?: FIRST_MARK
            val dur = r.marks.lastOrNull()?.ms ?: 0L
            r.crash?.let { c ->
                return Problem(Kind.CRASH, r.build, r.os, r.started, last, dur, r.marks, c, r.peakMB)
            }
            val h = r.hangMs
            if (h != null && h >= HANG_LIMIT_MS) return Problem(Kind.HANG, r.build, r.os, r.started, r.hangAt ?: last, h, r.marks, null, r.peakMB)
            if (!r.finished) return Problem(Kind.NOT_FINISHED, r.build, r.os, r.started, last, dur, r.marks, null, r.peakMB)
            return null
        }

        private fun sec(ms: Long) = String.format(java.util.Locale.ROOT, "%.1f", ms / 1000.0)

        /** Tekst voor het scherm en het supportticket (zoals op Apple). */
        fun text(p: Problem): String {
            val out = ArrayList<String>()
            when (p.kind) {
                Kind.CRASH -> out += "Vorige start: gestopt (crash) na ${sec(p.ms)} s, bij \"${p.lastMark}\""
                Kind.HANG -> out += "Vorige start: hoofdthread ${sec(p.ms)} s vast bij \"${p.lastMark}\" (ANR-vermoeden)"
                Kind.NOT_FINISHED -> out += "Vorige start: niet klaar, laatste mijlpaal \"${p.lastMark}\" na ${sec(p.ms)} s (gestopt door het systeem, bijv. geheugen of ANR)"
            }
            p.peakMB?.let { out += "Geheugen: hoogste $it MB" }
            out += "Build ${p.build} · ${p.os}"
            out += "Mijlpalen: " + p.marks.joinToString(" · ") { m -> "${m.name} ${sec(m.ms)}s" + (m.mb?.let { " ${it}MB" } ?: "") }
            p.crash?.let { c ->
                out += "Fout: ${c.type}: ${c.reason}"
                if (c.stack.isNotEmpty()) { out += "Stack:"; out += c.stack }
            }
            return out.joinToString("\n")
        }

        /** Netwerkcheck: `ok` (< 1,5 s), `slow`, `fail`. */
        fun speed(ms: Long?, ok: Boolean): String = when {
            !ok || ms == null -> "fail"
            ms < 1500 -> "ok"
            else -> "slow"
        }
    }

    // MARK: tv-gids

    object Epg {
        /** Hoe ver vooruit de gids nog moet lopen (3 uur). */
        const val HORIZON_MS = 3 * 3_600_000L

        /**
         * Verlopen = er is een gids, maar geen enkel programma loopt nu of begint binnen [HORIZON_MS].
         * [programs] = (start, eind) van een steekproef van zenders met een gids. Lege lijst = geen gids (niet verlopen).
         */
        fun expired(now: Long, programs: List<Pair<Long, Long>>): Boolean {
            if (programs.isEmpty()) return false
            return programs.none { (s, e) -> e > now && s < now + HORIZON_MS }
        }
    }
}
