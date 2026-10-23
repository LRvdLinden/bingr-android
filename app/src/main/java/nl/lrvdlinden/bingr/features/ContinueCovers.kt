package nl.lrvdlinden.bingr

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Build 144: TMDB-posters voor Verder kijken (= ContinueCovers.swift, Apple build 143).
 *
 * De voortgang bewaart het beeld waarmee het afspelen begon (bij een aflevering de scène, bij voortgang van een ander
 * apparaat wat dat apparaat meegaf), en veel IPTV-aanbieders geven bij series geen cover. Heeft de bibliotheek (of de
 * catalogmeta van de server) geen poster, dan zoekt Verder kijken de echte poster op bij TMDB (titel + jaar, film of
 * serie, via de bestaande TMDB-route van de app). De uitkomst blijft op dit apparaat (cachemap, "gezocht, niets gevonden"
 * als lege tekst), zodat elke titel maar één keer wordt opgezocht. Volgorde in de tegel: zie [Build144Logic.ContinueCover.pick].
 */
object ContinueCovers {
    /** Verhoogt als er posters bij zijn gekomen (Compose-state: Verder kijken opnieuw opbouwen). */
    var version by mutableStateOf(0); private set

    /** sleutel ([Build144Logic.ContinueCover.key]) → poster-URL; "" = gezocht, niets gevonden */
    private val posters = ConcurrentHashMap<String, String>()
    private val inFlight = java.util.Collections.synchronizedSet(HashSet<String>())
    private var saveJob: Job? = null
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private val file: File get() = File(AppState.appContext.cacheDir, "continuecovers.v144.json")

    /** Eén keer van schijf lezen (op de achtergrond); [resolve] wacht erop, zodat niets dubbel wordt opgezocht. */
    private val load = AppScope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
        val m = runCatching { Http.json.decodeFromString(serializer, file.readText()) }.getOrNull()
        if (!m.isNullOrEmpty()) {
            m.forEach { (k, v) -> posters.putIfAbsent(k, v) }
            withContext(Dispatchers.Main) { version++ }
        }
    }

    /** Bekende TMDB-poster (null = nog niet bekend of niet gevonden). Veilig vanaf elke thread. */
    fun cover(title: String, year: Int?, isShow: Boolean): String? {
        load.start()
        return posters[Build144Logic.ContinueCover.key(title, year, isShow)]?.takeIf { it.isNotEmpty() }
    }

    /** Ontbrekende posters op de achtergrond opzoeken (hooguit [Build144Logic.ContinueCover.MAX_LOOKUPS] per keer). */
    suspend fun resolve(wants: List<Build144Logic.ContinueCover.Want>) {
        if (wants.isEmpty() || Screenshots.isActive || StressMode.isActive) return
        try { load.await() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        if (!Secrets.isSet(Secrets.tmdbAPIKey)) return
        val todo = synchronized(inFlight) {
            Build144Logic.ContinueCover.pending(wants, { posters.containsKey(it) }, inFlight.toSet()).also { l -> l.forEach { inFlight += it.first } }
        }
        if (todo.isEmpty()) return
        var changed = false
        try {
            for ((k, w) in todo) {
                val (q, y) = Build144Logic.ContinueCover.query(w)
                val params = mutableListOf("query" to q, "language" to TMDB.appLanguage, "include_adult" to "false")
                if (y != null && y > 0) params += (if (w.isShow) "first_air_date_year" else "year") to y.toString()
                val r = try { TMDB.request(if (w.isShow) "/search/tv" else "/search/movie", params) }
                    catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                inFlight -= k
                r ?: continue   // netwerkfout: later opnieuw
                val path = r.jList("results").firstOrNull { !it.jS("poster_path").isNullOrEmpty() }.jS("poster_path")
                posters[k] = TMDB.image(path, "w342") ?: ""
                changed = true
            }
        } finally {
            todo.forEach { inFlight -= it.first }
        }
        if (changed) {
            withContext(Dispatchers.Main) { version++ }
            scheduleSave()
        }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = AppScope.launch(Dispatchers.IO) {
            delay(1_000)
            val snapshot = HashMap(posters)
            runCatching { file.writeText(Http.json.encodeToString(serializer, snapshot)) }
        }
    }
}
