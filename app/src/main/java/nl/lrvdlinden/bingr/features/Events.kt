package nl.lrvdlinden.bingr

import android.content.Context
import android.net.ConnectivityManager
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Evenementzenders (build 51, alleen lokaal): PPV/EVENT-groepen of zendernamen met een datum/tijd en twee namen
 * met "vs"/"v"/"-" ertussen. Uit de naam worden de wedstrijd, de datum en de tijd gehaald.
 */
data class EventInfo(val title: String, val start: Long?) {
    val isLive: Boolean get() = start != null && start <= System.currentTimeMillis()
    /** Meer dan 6 uur geleden begonnen */
    val isExpired: Boolean get() = start != null && System.currentTimeMillis() - start > 6 * 3_600_000L
}

object EventParser {
    private val groupWords = Regex("\\b(PPV|EVENTS?|LIVE EVENTS?|SPORTS? EVENTS?)\\b|UFC|WWE|BOXING", RegexOption.IGNORE_CASE)
    private val versus = Regex("\\s(vs\\.?|v\\.?|-|–|@)\\s", RegexOption.IGNORE_CASE)
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val monthsNL = listOf("jan", "feb", "mrt", "apr", "mei", "jun", "jul", "aug", "sep", "okt", "nov", "dec")

    // 2026-10-12 20:45
    private val isoDate = Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})")
    // 12/10 of 12/10/2026 of 12.10.
    private val dmDate = Regex("(?<![\\d:])(\\d{1,2})[/.](\\d{1,2})(?:[/.](\\d{2,4}))?(?![\\d:])")
    // Oct 12 / 12 Oct
    private val monDay = Regex("\\b([A-Za-z]{3})[a-z]*\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b")
    private val dayMon = Regex("\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+([A-Za-z]{3})[a-z]*\\b")
    // 20:45, 8:45pm, 8pm, 20h45
    private val time = Regex("(?<![\\d/.-])(\\d{1,2})(?:[:.h](\\d{2}))?\\s*(am|pm)?(?![\\d/])", RegexOption.IGNORE_CASE)
    private val zone = Regex("\\b(UTC|GMT|BST|CET|CEST|EET|EEST|WET|WEST|ET|EST|EDT|CT|CST|CDT|MT|MST|MDT|PT|PST|PDT|UK)\\b|([+-]\\d{2}):?(\\d{2})")

    private val zones = mapOf(
        "UTC" to 0, "GMT" to 0, "UK" to Int.MIN_VALUE, "BST" to 60, "WET" to 0, "WEST" to 60, "CET" to 60, "CEST" to 120,
        "EET" to 120, "EEST" to 180, "EST" to -300, "EDT" to -240, "CST" to -360, "CDT" to -300, "MST" to -420,
        "MDT" to -360, "PST" to -480, "PDT" to -420,
        // zonder zomer/winter: de huidige stand van die zone
        "ET" to Int.MAX_VALUE, "CT" to Int.MAX_VALUE, "MT" to Int.MAX_VALUE, "PT" to Int.MAX_VALUE,
    )
    private val zoneIDs = mapOf("UK" to "Europe/London", "ET" to "America/New_York", "CT" to "America/Chicago",
        "MT" to "America/Denver", "PT" to "America/Los_Angeles")

    fun isEventGroup(group: String): Boolean = groupWords.containsMatchIn(group)

    /** Evenement-info van deze zender, of null als het geen evenement is. */
    fun parse(c: Channel): EventInfo? {
        val inGroup = isEventGroup(c.group)
        // snel afwijzen (grote lijsten): buiten een evenementgroep alleen namen met "vs"/"-" én een cijfer
        if (!inGroup && (!c.name.any { it.isDigit() } || !versus.containsMatchIn(c.name))) return null
        val start = parseDate(c.name)
        if (!inGroup && start == null) return null
        return EventInfo(cleanTitle(c.name), start)
    }

    /** "PPV 03: Ajax vs PSV 12/10 20:45 CET" → "Ajax – PSV". */
    fun cleanTitle(name: String): String {
        var t = name
        t = isoDate.replace(t, " ")
        t = monDay.replace(t) { m -> if (monthIndex(m.groupValues[1]) != null) " " else m.value }
        t = dayMon.replace(t) { m -> if (monthIndex(m.groupValues[2]) != null) " " else m.value }
        t = dmDate.replace(t, " ")
        t = Regex("(?<![\\d])(\\d{1,2})[:.h](\\d{2})\\s*(am|pm)?", RegexOption.IGNORE_CASE).replace(t, " ")
        t = Regex("\\b\\d{1,2}\\s*(am|pm)\\b", RegexOption.IGNORE_CASE).replace(t, " ")
        t = zone.replace(t, " ")
        // voorvoegsels zoals "PPV 03:", "EVENT |", "US:", "[UK]"
        t = t.replace(Regex("^\\s*(?:[A-Z]{2,3}\\s*[:|]\\s*)?(?:PPV|EVENTS?|LIVE)\\s*\\d*\\s*[:|\\-]?\\s*", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("^\\s*[A-Z]{2,3}\\s*[:|]\\s*"), "")
        t = t.replace(Regex("[\\[(]\\s*[)\\]]"), " ")
        t = t.replace(Regex("\\s(vs\\.?|v\\.?)\\s", RegexOption.IGNORE_CASE), " – ")
        t = t.replace(Regex("\\s+"), " ").trim().trim('-', '|', ':', '@', '–', ' ', ',')
        return t.ifEmpty { name.trim() }
    }

    private fun monthIndex(s: String): Int? {
        val k = s.lowercase(Locale.ROOT).take(3)
        val i = months.indexOf(k)
        if (i >= 0) return i
        val j = monthsNL.indexOf(k)
        return if (j >= 0) j else null
    }

    /** Datum/tijd uit de naam (lokale tijd, tenzij er een tijdzone bij staat). */
    fun parseDate(name: String, now: Long = System.currentTimeMillis()): Long? {
        var year: Int? = null; var month: Int? = null; var day: Int? = null
        var rest = name
        isoDate.find(name)?.let { m ->
            year = m.groupValues[1].toInt(); month = m.groupValues[2].toInt() - 1; day = m.groupValues[3].toInt()
            rest = rest.replace(m.value, " ")
        }
        if (day == null) monDay.findAll(name).firstOrNull { monthIndex(it.groupValues[1]) != null }?.let { m ->
            month = monthIndex(m.groupValues[1]); day = m.groupValues[2].toInt(); rest = rest.replace(m.value, " ")
        }
        if (day == null) dayMon.findAll(name).firstOrNull { monthIndex(it.groupValues[2]) != null }?.let { m ->
            month = monthIndex(m.groupValues[2]); day = m.groupValues[1].toInt(); rest = rest.replace(m.value, " ")
        }
        if (day == null) dmDate.find(name)?.let { m ->
            val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt()
            // dag/maand (Europees); is het eerste getal > 12 of het tweede > 12, dan is de volgorde duidelijk
            val (d, mo) = if (b > 12 && a <= 12) b to a else a to b
            if (mo in 1..12 && d in 1..31) {
                day = d; month = mo - 1
                m.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull()?.let { y -> year = if (y < 100) 2000 + y else y }
                rest = rest.replace(m.value, " ")
            }
        }
        // tijd: met dubbele punt (of am/pm)
        var hour: Int? = null; var minute = 0
        for (m in time.findAll(rest)) {
            val h = m.groupValues[1].toIntOrNull() ?: continue
            val mm = m.groupValues[2]
            val ap = m.groupValues[3].lowercase(Locale.ROOT)
            if (mm.isEmpty() && ap.isEmpty()) continue
            var hh = h
            if (ap == "pm" && hh < 12) hh += 12
            if (ap == "am" && hh == 12) hh = 0
            if (hh !in 0..23) continue
            hour = hh; minute = mm.toIntOrNull()?.takeIf { it in 0..59 } ?: 0
            break
        }
        if (hour == null) return null
        val z = zone.find(rest)
        val tz: TimeZone = when {
            z == null -> TimeZone.getDefault()
            z.groupValues[2].isNotEmpty() -> {
                val sign = if (z.groupValues[2].startsWith("-")) -1 else 1
                val off = sign * (z.groupValues[2].drop(1).toInt() * 60 + z.groupValues[3].toInt())
                TimeZone.getTimeZone("GMT").apply { rawOffset = off * 60_000 }
            }
            else -> {
                val k = z.groupValues[1].uppercase(Locale.ROOT)
                val off = zones[k]
                when {
                    off == null -> TimeZone.getDefault()
                    off == Int.MAX_VALUE || off == Int.MIN_VALUE -> TimeZone.getTimeZone(zoneIDs[k] ?: "UTC")
                    else -> TimeZone.getTimeZone("GMT").apply { rawOffset = off * 60_000 }
                }
            }
        }
        val cal = Calendar.getInstance(tz).apply { timeInMillis = now }
        val nowCal = Calendar.getInstance(tz).apply { timeInMillis = now }
        year?.let { cal.set(Calendar.YEAR, it) }
        month?.let { cal.set(Calendar.MONTH, it) }
        day?.let { cal.set(Calendar.DAY_OF_MONTH, it) }
        cal.set(Calendar.HOUR_OF_DAY, hour!!); cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        // zonder jaar: het dichtstbijzijnde (een datum ver in het verleden hoort bij volgend jaar)
        if (year == null && month != null && cal.timeInMillis < now - 180L * 86_400_000L) cal.add(Calendar.YEAR, 1)
        if (year == null && month != null && cal.timeInMillis > now + 180L * 86_400_000L) cal.add(Calendar.YEAR, -1)
        // alleen een tijd: vandaag
        if (day == null) { cal.set(Calendar.YEAR, nowCal.get(Calendar.YEAR)); cal.set(Calendar.DAY_OF_YEAR, nowCal.get(Calendar.DAY_OF_YEAR)) }
        return cal.timeInMillis
    }

    /** Evenementen in Live TV: op begintijd, verlopen (meer dan 6 uur oud) en zonder tijd onderaan. */
    fun sorted(channels: List<Channel>): List<Pair<Channel, EventInfo>> {
        val list = channels.mapNotNull { c -> parse(c)?.let { c to it } }
        return list.sortedWith(compareBy<Pair<Channel, EventInfo>>(
            { when { it.second.start == null -> 2; it.second.isExpired -> 1; else -> 0 } },
            { it.second.start ?: Long.MAX_VALUE },
        ))
    }

    /** Begintijd in de lokale tijd: "Today 20:45", "Yesterday 20:45", of de datum. */
    fun startText(ms: Long): String {
        val today = s2StartOfDay(System.currentTimeMillis())
        val day = s2StartOfDay(ms)
        val t = s2Time(ms)
        return when (day) {
            today -> "${L("Today")} $t"
            today - 86_400_000L -> "${L("Yesterday")} $t"
            else -> "${s2DateShort(ms)} $t"
        }
    }
}

/** Instellingen van de speler die in build 51 bijkwamen. */
object PlayerSettings {
    /** Zapvertraging in ms: 0 (uit), 500, 1000, 2000 */
    var zapDelay: Int
        get() = Prefs.int("player.zapDelay", 0).let { if (it in listOf(0, 500, 1000, 2000)) it else 0 }
        set(v) = Prefs.put("player.zapDelay", v)
    val zapDelayOptions: List<Pair<Int, String>> get() = listOf(0 to L("Off"), 500 to "0.5 s", 1000 to "1 s", 2000 to "2 s")

    var channelPreview: Boolean
        get() = Prefs.bool("player.channelPreview", true)
        set(v) = Prefs.put("player.channelPreview", v)

    /** Voorbeeld nu tonen: instelling aan, en op een telefoon niet via een netwerk met datalimiet. */
    fun previewAllowed(ctx: Context = AppState.appContext): Boolean {
        if (!channelPreview) return false
        if (AppState.isTV) return true
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        return !runCatching { cm.isActiveNetworkMetered }.getOrDefault(false)
    }

    var matchFrameRate: Boolean
        get() = Prefs.bool("player.matchFrameRate", true)
        set(v) = Prefs.put("player.matchFrameRate", v)

    var externalDisplay: Boolean
        get() = Prefs.bool("player.externalDisplay", true)
        set(v) = Prefs.put("player.externalDisplay", v)
}
