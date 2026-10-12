package nl.lrvdlinden.bingr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Reservebron voor live-tv en films (build 52): dezelfde zender (of film) op de andere servers van de gebruiker,
 * uit de cache van die servers (zoals globaal zoeken). Zenders: eerst op tvg-id / EPG-id, dan op de genormaliseerde
 * naam (zonder HD/FHD/4K/UHD en zonder landvoorvoegsel zoals "NL:", "|NL|" of "[BE]").
 */
object BackupSources {
    data class Hit(
        val source: PlaylistSource,
        val name: String,
        val url: String?,
        val stalkerCmd: String?,
        /** true = gevonden op EPG-id (zekerder dan op naam) */
        val byEpg: Boolean,
    )

    // "nl: ", "uk | ", "|nl| ", "[be] ", "(de) ", "nl - "
    private val prefix = Regex("^\\s*[a-z]{2,3}\\s*[:|]\\s*|^\\s*[\\[(|]\\s*[a-z]{2,3}\\s*[\\])|]\\s*|^\\s*[a-z]{2}\\s+-\\s+")
    private val quality = Regex("\\b(?:hd|fhd|uhd|4k|sd|hevc|h\\.?265|h\\.?264|1080[pi]?|720p|2160p|50fps|60fps|backup|raw)\\b")

    /** "NL: NPO 1 HD" → "npo1"; "|BE| VTM 4K" → "vtm". */
    fun normalize(name: String): String {
        var s = Net.fold(name).lowercase(Locale.ROOT).trim()
        repeat(2) { s = prefix.replace(s, "") }
        s = quality.replace(s, " ")
        s = s.replace("ᴴᴰ", " ").replace("ʰᵈ", " ")
        return s.filter { it.isLetterOrDigit() || it == '+' }
    }

    /** Zelfde zender op de andere servers (de huidige server telt niet mee). */
    suspend fun channels(c: Channel): List<Hit> {
        val current = Playlists.source?.id
        val others = Store.playlists.filter { it.id != current }
        val epg = c.epgID?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        val norm = normalize(c.name)
        if (epg == null && norm.length < 2) return emptyList()
        // build 60: zelfde verbergen/kinderslot/leeftijdsgrens als het actieve profiel (zoals globaal zoeken)
        val contexts = others.associate { it.id to Playlists.context(it) }
        return withContext(Dispatchers.Default) {
            val out = ArrayList<Hit>()
            for (p in others) {
                val lib = filtered(p, contexts[p.id]) ?: continue
                val byEpg = if (epg != null) lib.channels.firstOrNull { it.epgID?.trim()?.lowercase(Locale.ROOT) == epg } else null
                val hit = byEpg ?: lib.channels.firstOrNull { normalize(it.name) == norm }
                if (hit != null && (hit.streamURL != null || hit.stalkerCmd != null)) {
                    out.add(Hit(p, hit.name, hit.streamURL, hit.stalkerCmd, byEpg != null))
                }
            }
            out.sortedByDescending { it.byEpg }
        }
    }

    /** Zelfde film (titel + jaar) op de andere servers; alleen titels met een directe stream. */
    suspend fun movies(title: String, year: Int?): List<Hit> {
        val current = Playlists.source?.id
        val norm = Net.normTitle(TitleCleaner.parse(title).title.ifEmpty { title })
        if (norm.isEmpty()) return emptyList()
        val others = Store.playlists.filter { it.id != current }
        val contexts = others.associate { it.id to Playlists.context(it) }
        return withContext(Dispatchers.Default) {
            val out = ArrayList<Hit>()
            for (p in others) {
                val lib = filtered(p, contexts[p.id]) ?: continue
                val m = lib.movies.firstOrNull {
                    Net.normTitle(TitleCleaner.parse(it.title).title.ifEmpty { it.title }) == norm &&
                        (year == null || it.year == null || it.year == year)
                } ?: continue
                if (m.streamURL != null || m.stalkerCmd != null) out.add(Hit(p, m.title, m.streamURL, m.stalkerCmd, false))
            }
            out
        }
    }

    /** Cache van een andere server, gefilterd voor het actieve profiel (kinderprofiel, verborgen en vergrendelde groepen). */
    private suspend fun filtered(p: PlaylistSource, ctx: Playlists.FilterContext?): Library? {
        val raw = withContext(Dispatchers.IO) { runCatching { Playlists.readCache(p) }.getOrNull() } ?: return null
        return if (ctx != null) runCatching { Playlists.apply(raw, ctx) }.getOrNull() else raw
    }

    /** Afspeel-URL van een treffer (MAC-portal: eerst een link aanmaken). */
    suspend fun resolve(h: Hit, live: Boolean): String? {
        val cmd = h.stalkerCmd
        if (cmd != null && h.source.kind == PlaylistKind.STALKER) {
            return try {
                StalkerClient(h.source).createLink(cmd, if (live) MediaType.LIVE else MediaType.MOVIE) ?: h.url
            } catch (e: CancellationException) { throw e } catch (_: Exception) { h.url }
        }
        return h.url
    }
}
