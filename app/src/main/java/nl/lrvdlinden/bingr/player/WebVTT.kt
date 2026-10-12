package nl.lrvdlinden.bingr

import java.util.Locale

/**
 * Ondertitels omzetten naar WebVTT voor de Chromecast (build 57). De standaard-ontvanger kan alleen WebVTT als losse
 * ondertitel tonen; OpenSubtitles levert SRT. Leest SRT en WebVTT, houdt <i>, <b> en <u> aan, haalt de rest van de
 * opmaak weg ({\an8}, <font>) en verschuift alle tijden met de ingestelde vertraging.
 */
object WebVTT {
    private val braceRegex = Regex("\\{[^}]*\\}")
    private val tagRegex = Regex("<(/?)([a-zA-Z]+)[^>]*>")
    private val entityRegex = Regex("&(?!(amp|lt|gt|nbsp|lrm|rlm|#\\d+|#x[0-9a-fA-F]+);)")

    /** WebVTT-tekst uit SRT- of WebVTT-tekst; [shiftSeconds] = + is later tonen. */
    fun from(text: String, shiftSeconds: Double = 0.0): String {
        val clean = text.replace("\uFEFF", "").replace("\r\n", "\n").replace("\r", "\n")
        val isVTT = clean.trimStart().startsWith("WEBVTT")
        val blocks = mutableListOf<List<String>>()
        var cur = mutableListOf<String>()
        for (line in clean.split("\n")) {
            if (line.isBlank()) {
                if (cur.isNotEmpty()) { blocks.add(cur); cur = mutableListOf() }
            } else cur.add(line)
        }
        if (cur.isNotEmpty()) blocks.add(cur)

        val sb = StringBuilder("WEBVTT\n\n")
        for (lines in blocks) {
            val first = lines.first().trim()
            if (first.startsWith("WEBVTT") || first.startsWith("NOTE") || first.startsWith("STYLE") || first.startsWith("REGION")) continue
            val ti = lines.indexOfFirst { it.contains("-->") }
            if (ti < 0) continue
            val parts = lines[ti].split("-->", limit = 2)
            val start = time(parts[0]) ?: continue
            val endTokens = parts[1].trim().split(Regex("\\s+"))
            val end = time(endTokens.firstOrNull() ?: "") ?: continue
            // instellingen achter de eindtijd alleen uit WebVTT overnemen (SRT heeft daar X1:… Y1:…)
            val settings = if (isVTT) endTokens.drop(1).filter { it.contains(':') && !it.startsWith("X", true) && !it.startsWith("Y", true) }
                .joinToString(" ") else ""
            val s = start + shiftSeconds
            val e = end + shiftSeconds
            if (e <= 0.0 || e <= s) continue
            val body = lines.drop(ti + 1).joinToString("\n") { cleanLine(it) }.trim()
            if (body.isEmpty()) continue
            sb.append(stamp(s.coerceAtLeast(0.0))).append(" --> ").append(stamp(e))
            if (settings.isNotEmpty()) sb.append(' ').append(settings)
            sb.append('\n').append(body).append("\n\n")
        }
        return sb.toString()
    }

    /** Eén tekstregel: alleen <i>, <b> en <u>, en losse & en < veilig voor WebVTT. */
    private fun cleanLine(line: String): String {
        var t = line.replace(braceRegex, "")
        val kept = mutableListOf<String>()
        t = tagRegex.replace(t) { m ->
            val name = m.groupValues[2].lowercase(Locale.ROOT)
            if (name == "i" || name == "b" || name == "u") {
                kept += "<${m.groupValues[1]}$name>"
                "\u0000${kept.size - 1}\u0000"
            } else ""
        }
        t = t.replace(entityRegex, "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("-->", "--&gt;")
        kept.forEachIndexed { i, tag -> t = t.replace("\u0000$i\u0000", tag) }
        return t.trimEnd()
    }

    /** "01:02:03,456", "01:02:03.456" of "02:03.456" → seconden. */
    internal fun time(raw: String): Double? {
        val t = raw.trim().split(Regex("\\s+")).firstOrNull()?.replace(',', '.') ?: return null
        val p = t.split(':')
        if (p.size !in 2..3) return null
        val nums = p.map { it.toDoubleOrNull() ?: return null }
        return if (nums.size == 3) nums[0] * 3600 + nums[1] * 60 + nums[2] else nums[0] * 60 + nums[1]
    }

    /** Seconden → "hh:mm:ss.mmm". */
    internal fun stamp(seconds: Double): String {
        val ms = Math.round(seconds * 1000).coerceAtLeast(0L)
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", ms / 3_600_000, (ms / 60_000) % 60, (ms / 1000) % 60, ms % 1000)
    }
}
