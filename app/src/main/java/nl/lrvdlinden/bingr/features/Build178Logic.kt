package nl.lrvdlinden.bingr

import java.util.Calendar
import java.util.TimeZone

/**
 * Build 178 (1.0.8): terugkijken in de tv-gids (zoals NLZIET). Pure logica (zonder Android), gelijk aan Apple
 * `Shared/Account/Build178.swift` en web `web/js/build178.js`. Gedeelde testgevallen in docs/build178_cases.json van
 * iptv-ios-appletv (kopie: tools/test/build178_cases.json; niet hier aanpassen). Unittest: tools/test/Build178LogicTest.kt.
 * Tijden in milliseconden.
 *
 * - Herkennen per zender: Xtream/Stalker tv_archive + tv_archive_duration (getal of tekst, dagen of uren boven de 31),
 *   M3U catchup, catchup-type, catchup-days, catchup-source, tvg-rec, timeshift, tv_archive(_duration), ook als
 *   standaard op #EXTM3U.
 * - De terugkijk-URL: Xtream in de tijdzone van de server (server_info.timezone), M3U volgens het catchup-type.
 * - De gids van vroeger: Xtream get_simple_data_table (has_archive), samengevoegd met de gewone gids.
 */
object Build178Logic {
    const val MAX_BACK = 14
    const val MAX_AHEAD = 7
    private const val DAY = 86_400_000L

    /** Waarde van een JSON-veld als tekst (true/false = 1/0, 3.0 = 3) */
    fun str(v: Any?): String? = when (v) {
        null -> null
        is Boolean -> if (v) "1" else "0"
        is Double -> if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()
        is Float -> str(v.toDouble())
        else -> v.toString()
    }

    // MARK: 1. Herkennen

    /** Dagen uit een duurveld: boven de 31 uren (72 = 3), "72h"/"3d" mag ook; naar boven afgerond. */
    fun days(raw: Any?): Int {
        var s = str(raw)?.trim()?.lowercase() ?: return 0
        if (s.isEmpty()) return 0
        var hours = false
        var explicitDays = false
        if (s.endsWith("h")) { hours = true; s = s.dropLast(1) } else if (s.endsWith("d")) { explicitDays = true; s = s.dropLast(1) }
        val v = s.trim().toDoubleOrNull() ?: return 0
        if (v.isNaN() || v.isInfinite() || v <= 0) return 0
        return if (hours || (!explicitDays && v > 31)) Math.ceil(v / 24).toInt() else Math.ceil(v).toInt()
    }

    /** Aan/uit: null = ontbreekt of leeg */
    fun flag(raw: Any?): Boolean? {
        val s = str(raw)?.trim()?.lowercase() ?: return null
        if (s.isEmpty()) return null
        return when (s) {
            "0", "false", "no", "off", "none", "disabled", "null" -> false
            "1", "true", "yes", "on" -> true
            else -> s.toDoubleOrNull()?.let { it != 0.0 } ?: true
        }
    }

    /** Xtream get_live_streams: aan zonder duur = 1 dag; zonder tv_archive-veld telt de duur. */
    fun xtreamDays(archive: Any?, duration: Any?): Int {
        val d = days(duration)
        return when (flag(archive)) {
            false -> 0
            true -> maxOf(d, 1)
            null -> d
        }
    }

    /** Stalker-portal: tv_archive (of archive) en tv_archive_duration, die altijd in uren is */
    fun stalkerDays(archive: Any?, duration: Any?): Int {
        val d = str(duration)?.trim().orEmpty()
        return xtreamDays(archive, if (d.isEmpty()) null else d + "h")
    }

    data class M3UCatchup(val days: Int, val type: String?, val source: String?)

    /** M3U: attributen van #EXTINF en (standaard) van #EXTM3U */
    fun m3u(attrs: Map<String, String>, header: Map<String, String>): M3UCatchup {
        val a = attrs.mapKeys { it.key.lowercase() }
        val h = header.mapKeys { it.key.lowercase() }
        fun v(k: String, d: Map<String, String>): String? = d[k]?.trim()?.ifEmpty { null }
        val off = M3UCatchup(0, null, null)
        val chType = v("catchup", a) ?: v("catchup-type", a)
        if (chType != null && flag(chType) == false) return off
        if (flag(v("tv_archive", a)) == false) return off
        val keys = listOf("catchup-days", "tv_archive_duration", "tvg-rec", "timeshift")
        fun firstDays(d: Map<String, String>): Int = keys.asSequence().map { days(v(it, d)) }.firstOrNull { it > 0 } ?: 0
        var days = firstDays(a)
        var type = chType
        var source = v("catchup-source", a)
        val chOn = chType != null || source != null || flag(v("tv_archive", a)) == true
        val hType = v("catchup", h) ?: v("catchup-type", h)
        val headerOff = (hType != null && flag(hType) == false) || flag(v("tv_archive", h)) == false
        if (!headerOff) {
            if (type == null) type = hType
            if (source == null) source = v("catchup-source", h)
            if (days == 0) days = firstDays(h)
        }
        val hOn = !headerOff && (hType != null || v("catchup-source", h) != null || flag(v("tv_archive", h)) == true)
        if (days == 0 && (chOn || hOn)) days = 1
        if (days <= 0) return off
        return M3UCatchup(days, type?.lowercase(), source)
    }

    // MARK: 2. Terugkijk-URL

    private fun parts(ms: Long, tz: TimeZone): IntArray {
        val c = Calendar.getInstance(tz)
        c.timeInMillis = ms
        return intArrayOf(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND))
    }

    private fun two(v: Int) = if (v < 10) "0$v" else "$v"

    /** Tijdzone uit een naam; onbekend = die van het apparaat */
    fun zone(name: String?): TimeZone {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) return TimeZone.getDefault()
        val tz = TimeZone.getTimeZone(n)
        return if (tz.id == "GMT" && n != "GMT" && n != "UTC" && !n.startsWith("GMT")) TimeZone.getDefault() else tz
    }

    /** Xtream: /timeshift/{user}/{pass}/{minuten}/{YYYY-MM-DD:HH-MM}/{id}.ts in de tijdzone van de server */
    fun xtreamURL(server: String, user: String, pass: String, id: String, start: Long, end: Long, tz: TimeZone): String {
        val base = server.trimEnd('/')
        val minutes = maxOf(1L, Math.ceil((end - start) / 60_000.0).toLong())
        val p = parts(start, tz)
        return "$base/timeshift/$user/$pass/$minutes/${p[0]}-${two(p[1])}-${two(p[2])}:${two(p[3])}-${two(p[4])}/$id.ts"
    }

    data class XtreamParts(val server: String, val user: String, val pass: String, val id: String)

    /** Xtream-achtige stream-URL (http://host/live/user/pass/123.ts of http://host/user/pass/123) */
    fun xtreamParts(url: String): XtreamParts? {
        val m = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*://[^/]+)(/[^?#]*)").find(url) ?: return null
        val segs = m.groupValues[2].split('/').filter { it.isNotEmpty() }.toMutableList()
        if (segs.size == 4 && segs[0].lowercase() == "live") segs.removeAt(0)
        if (segs.size != 3) return null
        val id = segs[2].replace(Regex("\\.[^.]*$"), "")
        if (id.isEmpty() || !id.all { it in '0'..'9' }) return null
        return XtreamParts(m.groupValues[1], segs[0], segs[1], id)
    }

    private fun format(fmt: String, ms: Long): String {
        val p = parts(ms, TimeZone.getTimeZone("UTC"))
        val sb = StringBuilder()
        for (ch in fmt) when (ch) {
            'Y' -> sb.append(p[0]); 'm' -> sb.append(two(p[1])); 'd' -> sb.append(two(p[2]))
            'H' -> sb.append(two(p[3])); 'M' -> sb.append(two(p[4])); 'S' -> sb.append(two(p[5]))
            else -> sb.append(ch)
        }
        return sb.toString()
    }

    /** Sjabloon invullen (zie Build178.swift `fill`) */
    fun fill(template: String, start: Long, end: Long, now: Long): String {
        val s = start / 1000; val e = end / 1000; val n = now / 1000; val dur = maxOf(0L, e - s)
        return Regex("\\$?\\{([^}:]*)(?::([^}]*))?\\}").replace(template) { m ->
            val name = m.groupValues[1]
            val hasArg = m.groups[2] != null
            val arg = m.groups[2]?.value
            when (name.lowercase()) {
                "utc", "start" -> if (hasArg) format(arg!!, start) else "$s"
                "utcend", "end" -> if (hasArg) format(arg!!, end) else "$e"
                "lutc", "now", "timestamp" -> if (hasArg) format(arg!!, now) else "$n"
                "duration" -> { val dv = if (hasArg) arg!!.toLongOrNull() ?: 0 else 1; "${if (dv > 0) dur / dv else dur}" }
                "offset" -> { val ov = if (hasArg) arg!!.toLongOrNull() ?: 0 else 1; val off = maxOf(0L, n - s); "${if (ov > 0) off / ov else off}" }
                else -> if (!hasArg && name.length == 1 && name in listOf("Y", "m", "d", "H", "M", "S")) format(name, start) else m.value
            }
        }
    }

    private fun append(url: String, part: String): String {
        if (part.isEmpty()) return url
        if (part.startsWith("?") && url.contains('?')) return url + "&" + part.substring(1)
        if (part.startsWith("&") && !url.contains('?')) return url + "?" + part.substring(1)
        return url + part
    }

    /** De terugkijk-URL van een M3U-zender (null = kan niet) */
    fun m3uURL(stream: String, type: String?, source: String?, start: Long, end: Long, now: Long, tz: TimeZone): String? {
        val t = type?.lowercase().orEmpty()
        val src = source?.trim()?.ifEmpty { null }
        fun xc(): String? = xtreamParts(stream)?.let { xtreamURL(it.server, it.user, it.pass, it.id, start, end, tz) }
        return when (t) {
            "xc" -> xc()
            "", "default", "append" -> {
                if (src != null) {
                    val filled = fill(src, start, end, now)
                    if (t != "append" && filled.contains("://")) filled
                    else if (stream.isEmpty()) null
                    else append(stream, filled)
                } else xc()
            }
            "shift", "timeshift" -> if (stream.isEmpty()) null else append(stream, "?utc=${start / 1000}&lutc=${now / 1000}")
            "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> {
                if (stream.isEmpty()) return null
                val q = stream.indexOf('?')
                val path = if (q >= 0) stream.substring(0, q) else stream
                val query = if (q >= 0) stream.substring(q) else ""
                val sl = path.lastIndexOf('/')
                if (sl < 0) return null
                val dir = path.substring(0, sl + 1)
                val last = path.substring(sl + 1)
                val ss = start / 1000
                val d = maxOf(0L, end / 1000 - ss)
                if (last.lowercase() == "mpegts" || last.lowercase().endsWith(".ts")) "${dir}timeshift_abs-$ss.ts$query"
                else {
                    val name = if (last.lowercase().endsWith(".m3u8")) last.dropLast(5) else last.ifEmpty { "index" }
                    "$dir$name-$ss-$d.m3u8$query"
                }
            }
            else -> null
        }
    }

    // MARK: 3. Gids van vroeger (Xtream get_simple_data_table)

    data class TableProgram(val title: String, val description: String?, val start: Long, val end: Long, val archive: Boolean?)

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private fun base64(s: String): ByteArray? {
        if (s.length % 4 != 0) return null
        val out = java.io.ByteArrayOutputStream(s.length * 3 / 4)
        var i = 0
        while (i < s.length) {
            val c = IntArray(4)
            var pad = 0
            for (j in 0 until 4) {
                val ch = s[i + j]
                if (ch == '=') { pad++; c[j] = 0 } else {
                    if (pad > 0) return null
                    val x = B64.indexOf(ch); if (x < 0) return null; c[j] = x
                }
            }
            if (pad > 2 || (pad > 0 && i + 4 != s.length)) return null
            val v = (c[0] shl 18) or (c[1] shl 12) or (c[2] shl 6) or c[3]
            out.write((v shr 16) and 0xFF)
            if (pad < 2) out.write((v shr 8) and 0xFF)
            if (pad < 1) out.write(v and 0xFF)
            i += 4
        }
        return out.toByteArray()
    }

    /** Base64-tekst van Xtream uitpakken (anders de tekst zelf) */
    fun text(raw: Any?): String {
        val s = str(raw)?.trim() ?: return ""
        if (s.isEmpty()) return ""
        val bytes = base64(s) ?: return s
        val dec = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        val out = try { dec.decode(java.nio.ByteBuffer.wrap(bytes)).toString() } catch (_: Exception) { return s }
        if (out.any { it.code < 0x20 && it != '\n' && it != '\r' && it != '\t' }) return s
        return out.trim()
    }

    /** get_simple_data_table (al ingelezen: de lijst `epg_listings` als maps), op begintijd, zonder tijden overgeslagen */
    fun simpleTable(listings: List<Map<String, Any?>>): List<TableProgram> =
        listings.mapNotNull { it ->
            val s = str(it["start_timestamp"])?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
            val e = str(it["stop_timestamp"] ?: it["end_timestamp"])?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
            if (e <= s) return@mapNotNull null
            val d = text(it["description"])
            TableProgram(text(it["title"]), d.ifEmpty { null }, (s * 1000).toLong(), (e * 1000).toLong(), flag(it["has_archive"]))
        }.sortedBy { it.start }

    data class Slot(val title: String, val start: Long, val end: Long, val archive: Boolean?, val source: Int)

    /** Gids en archief samen: zelfde begin (binnen 2 minuten) = zelfde programma; `source` ≥ 0 = gids, anders −1 − archief */
    fun merge(guide: List<Triple<String, Long, Long>>, archive: List<TableProgram>): List<Slot> {
        val out = guide.mapIndexed { i, g -> Slot(g.first, g.second, g.third, null, i) }.sortedBy { it.start }.toMutableList()
        val starts = out.map { it.start }
        val extra = ArrayList<Slot>()
        archive.forEachIndexed { j, a ->
            var lo = 0; var hi = starts.size
            while (lo < hi) { val m = (lo + hi) / 2; if (starts[m] < a.start - 120_000) lo = m + 1 else hi = m }
            if (lo < starts.size && Math.abs(starts[lo] - a.start) <= 120_000) {
                if (a.archive != null) out[lo] = out[lo].copy(archive = a.archive)
            } else extra += Slot(a.title, a.start, a.end, a.archive, -1 - j)
        }
        if (extra.isEmpty()) return out
        return (out + extra).sortedBy { it.start }
    }

    // MARK: 4. Gids

    /** Terug te kijken: voorbij, en volgens de server (has_archive) of binnen de termijn */
    fun replayable(start: Long, end: Long, now: Long, catchupDays: Int, archive: Boolean?): Boolean {
        if (end > now) return false
        if (archive != null) return archive
        return catchupDays > 0 && start > now - catchupDays * DAY
    }

    /** Dagbalk als offsets: terug tot de termijn (hooguit 14), vooruit alleen met gids (hooguit 7) */
    fun dayOffsets(maxCatchupDays: Int, futureWithGuide: List<Int>): List<Int> {
        val back = maxCatchupDays.coerceIn(0, MAX_BACK)
        return (-back..0).toList() + futureWithGuide.filter { it in 1..MAX_AHEAD }.distinct().sorted()
    }

    fun keep(start: Long, end: Long, now: Long, back: Int, ahead: Int): Boolean =
        end > now - back * DAY && start < now + ahead * DAY

    /** Diagnose: (zenders met terugkijken, grootste aantal dagen) */
    fun summary(days: List<Int>): Pair<Int, Int> {
        var n = 0; var m = 0
        for (d in days) if (d > 0) { n++; m = maxOf(m, d) }
        return n to m
    }
}
