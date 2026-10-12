package nl.lrvdlinden.bingr

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Intro en aftiteling per serie (build 52). De gebruiker markeert één keer in de speler waar de intro begint en
 * eindigt en waar de aftiteling begint; dat geldt voor alle afleveringen van die serie.
 *
 * Sleutel per serie: "<serverID>:<serieID>" (serverID = id van de PlaylistSource zoals SourceSync hem kent,
 * serieID = Series.id, dezelfde als in "ep:<serieID>|s-e"). Hoofdletters tellen niet mee (iOS gebruikt
 * hoofdletters in UUID's). Gesynchroniseerd als `settings.shared.introMarkers`:
 * `{ "<sleutel>": {"introStart": s, "introEnd": s, "creditsStart": s} }` in seconden; null of ontbrekend = niet gezet.
 * Een item met alle drie de velden null is gewist (blijft staan, zodat het wissen doorkomt op andere apparaten).
 * Lokaal in Prefs onder "introMarkers" (JSON).
 */
object IntroMarkers {
    const val PREF = "introMarkers"

    data class Marker(val introStart: Double? = null, val introEnd: Double? = null, val creditsStart: Double? = null) {
        val isEmpty: Boolean get() = introStart == null && introEnd == null && creditsStart == null
        /** Intro-venster (begin 0 als alleen het einde gezet is). */
        val intro: ClosedFloatingPointRange<Double>?
            get() {
                val e = introEnd ?: return null
                val s = introStart ?: 0.0
                return if (e > s + 1) s..e else null
            }
    }

    /** Alle markeringen (sleutel zoals opgeslagen → markering); telt op bij elke wijziging (Compose). */
    var all by mutableStateOf(mapOf<String, Marker>())
        private set

    fun seriesKey(sourceID: String, seriesID: String) = "$sourceID:$seriesID"

    /** Sleutel van de serie van dit item (null als het geen aflevering is). */
    fun keyFor(item: PlayableItem): String? {
        if (item.type != MediaType.EPISODE) return null
        val sid = item.seriesID ?: Playlists.library.series.firstOrNull { s -> s.episodes.any { it.id == item.id } }?.id ?: return null
        val src = Playlists.source?.id ?: Store.activePlaylistID ?: return null
        return seriesKey(src, sid)
    }

    fun get(key: String?): Marker? {
        if (key == null) return null
        val m = all[key] ?: all.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
        return m?.takeIf { !it.isEmpty }
    }

    fun update(key: String, change: (Marker) -> Marker) {
        val existing = all.keys.firstOrNull { it.equals(key, ignoreCase = true) } ?: key
        val next = change(all[existing] ?: Marker())
        all = all + (existing to next)
        save()
    }

    fun clear(key: String) = update(key) { Marker() }

    // MARK: opslag en sync

    fun reload() {
        all = runCatching { Prefs.string(PREF)?.let { parse(Http.json.parseToJsonElement(it)) } }.getOrNull() ?: emptyMap()
    }

    private fun save() = Prefs.put(PREF, toJson(all).toString())

    fun parse(e: JsonElement?): Map<String, Marker> {
        val o = e as? JsonObject ?: return emptyMap()
        return o.mapNotNull { (k, v) ->
            val m = v as? JsonObject ?: return@mapNotNull null
            k to Marker(m.jD("introStart"), m.jD("introEnd"), m.jD("creditsStart"))
        }.toMap()
    }

    fun toJson(map: Map<String, Marker>): JsonObject = JsonObject(map.mapValues { (_, m) ->
        fun n(d: Double?): JsonElement = d?.let { JsonPrimitive(Math.round(it * 10) / 10.0) } ?: JsonNull
        JsonObject(mapOf("introStart" to n(m.introStart), "introEnd" to n(m.introEnd), "creditsStart" to n(m.creditsStart)))
    })

    /** Lokaal en binnengekomen samenvoegen: per serie wint de binnengekomen versie; lokale extra series blijven. */
    fun merged(incoming: Map<String, Marker>): Map<String, Marker> {
        val out = LinkedHashMap<String, Marker>()
        for ((k, v) in all) out[k] = v
        for ((k, v) in incoming) {
            out.keys.firstOrNull { it.equals(k, ignoreCase = true) && it != k }?.let { out.remove(it) }
            out[k] = v
        }
        return out
    }
}
