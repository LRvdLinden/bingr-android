package nl.lrvdlinden.bingr

import android.content.Intent
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

// MARK: - Belastingmodus (rooktest in de emulator)
//
// Build 141. Alleen in een build met -PBINGR_STRESS=true (BuildConfig.STRESS); die maakt alleen de workflow Rooktest
// (.github/workflows/smoke.yml), als kortlevend artefact voor de emulator. In gewone builds is BuildConfig.STRESS false
// en doet niets hieronder iets.
//
// In deze modus: geen netwerk (Http weigert alles), geen account ("doorgaan zonder account") en grote nepgegevens:
// 3 servers met elk 30.000 films, 20.000 series en 5.000 zenders met 4 dagen gids (480.000 programma's), 3 profielen
// (hoofd, kind, gast) met Mijn lijst, Verder kijken, geschiedenis en favorieten over alle servers. "Verversen" van een
// server levert na een paar seconden een nieuwe, iets grotere catalogus (zoals een echte server). Server 3 staat in het
// oude cacheformaat (van vóór build 141), zodat ook de omzetting meetelt.
//
// tools/smoke/stress.sh start de app met `--ez bingr.stress.prepare true` (gegevens klaarzetten, daarna stopt de app),
// en daarna met `--ei bingr.stress.profile <n>` (en eventueel `--ei bingr.stress.switch <n>`): de app kiest dan zelf dat
// profiel in "Wie kijkt er?" (zoals een tik) en logt met de tag BingrStress hoe lang het duurt tot Home bruikbaar is, hoe
// lang de main thread hooguit vastzat en het geheugen. Het script stuurt intussen toetsen (zoals de afstandsbediening) en
// controleert dat er geen ANR of crash is.

object StressMode {
    const val TAG = "BingrStress"
    val isActive: Boolean get() = BuildConfig.STRESS

    private const val SERVERS = 3
    private const val MOVIES = 30_000
    private const val SERIES = 20_000
    private const val CHANNELS = 5_000
    private const val EPG_DAYS_BACK = 1
    private const val EPG_DAYS_AHEAD = 3
    private const val MARKER = "stress-ready"

    private fun sid(i: Int) = "57e55000-0000-4000-8000-00000000000$i"
    private val mainID = "57e55000-0000-4000-8000-0000000000a1"
    private val kidsID = "57e55000-0000-4000-8000-0000000000a2"
    private val guestID = "57e55000-0000-4000-8000-0000000000a3"

    private var pick: Int? = null
    private var switchTo: Int? = null
    private var openedAt = 0L
    private var openedName = ""
    private var round = 0

    private fun log(msg: String) = Log.i(TAG, "BINGR_STRESS $msg")

    /** Bij het starten van de app (vóór het inloggen): geen account, en de main-thread-wachter. */
    fun initApp() {
        if (!isActive) return
        AppState.skippedAccount = true
        startWatchdog()
    }

    /** MainActivity: wat het script vraagt (gegevens klaarzetten, of een profiel kiezen). */
    fun handle(intent: Intent?) {
        if (!isActive || intent == null) return
        if (intent.getBooleanExtra("bingr.stress.prepare", false)) { AppScope.launch { prepare() }; return }
        pick = intent.getIntExtra("bingr.stress.profile", -1).takeIf { it >= 0 }
        switchTo = intent.getIntExtra("bingr.stress.switch", -1).takeIf { it >= 0 }
        if (pick != null) AppScope.launch { autoPick() }
    }

    // MARK: profiel kiezen zoals een tik in "Wie kijkt er?"

    private suspend fun autoPick() {
        while (AppState.stage != AppState.Stage.PICKER || Store.profiles.isEmpty()) delay(200)
        delay(2_000)
        open(pick ?: 0)
        val sw = switchTo ?: return
        delay(25_000)
        log("switch to profile index=$sw")
        AppState.switchPlaylist()
        delay(2_000)
        open(sw)
    }

    private fun open(i: Int) {
        val list = Store.profiles
        val p = list.firstOrNull { it.id == listOf(mainID, kidsID, guestID).getOrNull(i) } ?: list.getOrNull(i) ?: list.first()
        log("pick profile=${p.name} kids=${p.kids}")
        AppState.open(p)
    }

    /** AppState.open: tijd starten en Home in de gaten houden tot het bruikbaar is. */
    fun profileOpened(p: ViewerProfile) {
        if (!isActive) return
        openedAt = SystemClock.elapsedRealtime()
        openedName = p.name
        val r = ++round
        AppScope.launch {
            while (r == round) {
                val lib = Playlists.library
                val ready = AppState.stage == AppState.Stage.MAIN && !lib.isEmpty && !Playlists.isLoading && !Playlists.isFiltering &&
                    HomeIndexCache.peek(lib) != null
                if (ready) {
                    log("home_ready profile=$openedName ms=${SystemClock.elapsedRealtime() - openedAt} movies=${lib.movies.size} series=${lib.series.size} channels=${lib.channels.size}")
                    break
                }
                if (SystemClock.elapsedRealtime() - openedAt > 120_000) { log("home_ready profile=$openedName TIMEOUT"); break }
                delay(100)
            }
        }
    }

    // MARK: main thread en geheugen

    @Volatile private var maxBlock = 0L

    /** Meet hoe lang de main thread hooguit niet reageerde (elke 50 ms een taakje), en logt elke 5 s het geheugen. */
    private fun startWatchdog() {
        val main = Handler(Looper.getMainLooper())
        Thread({
            var lastLog = SystemClock.elapsedRealtime()
            var windowMax = 0L
            while (true) {
                val sent = SystemClock.elapsedRealtime()
                val done = java.util.concurrent.CountDownLatch(1)
                main.post { done.countDown() }
                done.await()
                val waited = SystemClock.elapsedRealtime() - sent
                if (waited > maxBlock) maxBlock = waited
                if (waited > windowMax) windowMax = waited
                if (waited > 700) log("main_blocked ms=$waited stage=${AppState.stage}")
                val now = SystemClock.elapsedRealtime()
                if (now - lastLog >= 5_000) {
                    val rt = Runtime.getRuntime()
                    val used = (rt.totalMemory() - rt.freeMemory()) / 1_048_576
                    val pss = runCatching { Debug.getPss() / 1024 }.getOrDefault(-1L)
                    log("mem used_mb=$used max_mb=${rt.maxMemory() / 1_048_576} pss_mb=$pss main_max_ms=$windowMax main_max_total_ms=$maxBlock stage=${AppState.stage} updating=${Playlists.updating}")
                    windowMax = 0L
                    lastLog = now
                }
                Thread.sleep(50)
            }
        }, "bingr-stress-watchdog").apply { isDaemon = true }.start()
    }

    // MARK: nepgegevens

    /** "Verversen" van een server in deze modus: na 4 s een nieuwe catalogus met 200 nieuwe titels (zoals een echte server). */
    suspend fun fetch(s: PlaylistSource): Library {
        delay(4_000)
        val i = (1..SERVERS).firstOrNull { sid(it) == s.id } ?: 1
        return withContext(Dispatchers.Default) { library(i, extra = 200) }
    }

    private val movieGroups = listOf("NL | Films Actie", "NL | Films Drama", "NL | Films Komedie", "EN | Movies Thriller", "EN | Movies 4K",
        "NL | Kinderfilms", "Disney+ Films", "Kids Movies", "NL | Films Horror", "EN | Movies Sci-Fi", "Adult XXX", "Documentaires")
    private val seriesGroups = listOf("NL | Series Drama", "NL | Series Misdaad", "EN | TV Shows", "NL | Kinderseries", "Kids Series",
        "Netflix Series", "HBO Series", "Anime", "Reality")
    private val channelGroups = (1..40).map { g -> if (g % 8 == 0) "NL | Kinderzenders $g" else "NL | Zenders $g" }

    private fun library(server: Int, extra: Int = 0): Library {
        val r = Random(1000 + server)
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val movies = ArrayList<Movie>(MOVIES + extra)
        for (n in 0 until MOVIES + extra) {
            val fresh = n >= MOVIES
            movies += Movie(
                id = "xv$n", title = "Stress Movie $n", year = 1980 + (n % 45),
                poster = "https://stress.invalid/s$server/m$n.jpg", group = movieGroups[n % movieGroups.size],
                streamURL = "http://stress.invalid/movie/u/p/$n.mp4", rating = (n % 100) / 10.0,
                plot = "Plot of stress movie $n on server $server.",
                addedAt = if (fresh) now - r.nextLong(0, day) else if (n % 10 < 7) now - r.nextLong(day, 400 * day) else null,
                xtreamVodID = n,
            )
        }
        val series = ArrayList<Series>(SERIES)
        for (n in 0 until SERIES) {
            series += Series(
                id = "xs$n", title = "Stress Series $n", year = 1990 + (n % 35), poster = "https://stress.invalid/s$server/s$n.jpg",
                group = seriesGroups[n % seriesGroups.size], plot = "Plot of stress series $n.", rating = (n % 90) / 10.0,
                xtreamSeriesID = n, addedAt = if (n % 10 < 6) now - r.nextLong(day, 400 * day) else null,
            )
        }
        val channels = ArrayList<Channel>(CHANNELS)
        for (n in 0 until CHANNELS) {
            channels += Channel(
                id = "x$n", name = "NL: Stress Channel $n HD", logo = "https://stress.invalid/s$server/c$n.png",
                group = channelGroups[n % channelGroups.size], streamURL = "http://stress.invalid/live/u/p/$n.ts",
                epgID = "ch$n.stress", number = n + 1, catchupDays = if (n % 5 == 0) 7 else 0,
            )
        }
        val hour = 3_600_000L
        val start0 = (now / hour) * hour - EPG_DAYS_BACK * day
        val count = ((EPG_DAYS_BACK + EPG_DAYS_AHEAD) * 24).toInt()
        val epg = HashMap<String, List<EPGProgram>>(CHANNELS * 2)
        for (c in channels) {
            val key = c.epgID!!
            epg[key] = List(count) { k ->
                EPGProgram(key, "Program ${k % 37} on ${c.number}", "Description of program $k on channel ${c.number}: a long text as real guides have, with cast and story.",
                    start0 + k * hour, start0 + (k + 1) * hour)
            }
        }
        return Library(channels, movies, series, epg)
    }

    /** Alles klaarzetten (eenmalig per installatie), daarna stopt de app (het script start hem opnieuw). */
    private suspend fun prepare() {
        val ctx = AppState.appContext
        val marker = File(ctx.filesDir, MARKER)
        if (marker.exists()) { log("prepare already done"); finish(); return }
        val t0 = SystemClock.elapsedRealtime()
        log("prepare start")
        // profielen
        val main = ViewerProfile(id = mainID, name = "Stress", avatar = PlaylistAvatar("📺", 0))
        val kids = ViewerProfile(id = kidsID, name = "Kids", avatar = PlaylistAvatar("🧸", 3), isKids = true)
        val guest = ViewerProfile(id = guestID, name = "Guest", avatar = PlaylistAvatar("⭐", 5))
        Store.upsertProfile(main); Store.upsertProfile(kids); Store.upsertProfile(guest)
        // servers
        val servers = (1..SERVERS).map { i ->
            PlaylistSource(id = sid(i), name = "Stress server $i", kind = PlaylistKind.XTREAM, serverURL = "http://stress.invalid",
                username = "u", avatar = PlaylistAvatar("🌐", i))
        }
        servers.forEach { Store.upsert(it) }
        Store.setMainSource(servers.first().id)
        // "Elke keer dat de app opent": alle servers worden verversd (zwaarste geval)
        // build 145: "Servers laden bij openen" = Altijd (alle servers meteen bijwerken, zonder vraag)
        Playlists.loadOnOpen = Build145Logic.LoadOnOpen.Mode.ALWAYS
        // catalogi (één tegelijk in het geheugen); server 3 in het oude formaat
        for ((i, p) in servers.withIndex()) {
            withContext(Dispatchers.Default) {
                val lib = library(i + 1)
                if (i == SERVERS - 1) Playlists.writeLegacyCacheForStress(p, lib) else Playlists.writeCacheForStress(p, lib)
            }
            log("prepare server ${i + 1} written")
        }
        // gegevens per profiel, over alle servers
        val now = System.currentTimeMillis()
        for (prof in listOf(main, kids, guest)) {
            Store.selectProfile(prof)
            val r = Random(prof.id.hashCode())
            for (k in 0 until 40) {
                val s = servers[k % SERVERS].id
                if (k % 2 == 0) Store.setMyList(s, "xv${r.nextInt(MOVIES)}", true) else Store.setMyList(s, "xs${r.nextInt(SERIES)}", true)
            }
            for (k in 0 until 30) {
                val s = servers[k % SERVERS].id
                val m = r.nextInt(MOVIES)
                Store.setProgress("xv$m", ProgressEntry(fraction = 0.3, position = 1_800.0, updated = now - k * 60_000L,
                    title = "Stress Movie $m", artwork = "https://stress.invalid/still/$m.jpg", playlistID = s, profileID = prof.id))
            }
            for (k in 0 until 120) {
                val s = servers[k % SERVERS].id
                val m = r.nextInt(MOVIES)
                Store.addHistory(HistoryEntry(itemID = "xv$m", playlistID = s, title = "Stress Movie $m", type = MediaType.MOVIE,
                    date = now - k * 3_600_000L, profileID = prof.id))
            }
            for (k in 0 until 20) Store.toggleFavorite(servers[k % SERVERS].id, "x${r.nextInt(CHANNELS)}")
        }
        // een paar categorieën uit voor het hele account
        Store.setCategoriesHidden(listOf("${servers[0].id.lowercase()}|Adult XXX", "${servers[1].id.lowercase()}|Anime"), true)
        Store.flushWrites()
        withContext(Dispatchers.IO) { marker.writeText("ok") }
        log("prepare done ms=${SystemClock.elapsedRealtime() - t0}")
        finish()
    }

    private suspend fun finish() {
        delay(1_000)
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
