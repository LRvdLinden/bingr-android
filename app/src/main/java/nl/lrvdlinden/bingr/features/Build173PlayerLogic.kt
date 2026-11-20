package nl.lrvdlinden.bingr

import kotlin.math.max

/**
 * Build 173 (versie 1.0.7): bediening in de speler (Netflix, NLZIET) en betrouwbare Trakt/OpenSubtitles.
 * Pure logica zonder Android, met unittests (tools/test/Build173PlayerLogicTest.kt). Dezelfde regels als Apple
 * (`Shared/Player/Build173Player.swift`, `Shared/Account/Build173Sync.swift`) en de webspeler (`web/js/player173.js`).
 */
object Build173PlayerLogic {

    // MARK: 1. De belangrijkste knop van dat moment (tv: grootst, eerste focus)

    enum class Primary { SKIP_INTRO, NEXT_EPISODE, START_OVER, GO_LIVE }

    const val BEHIND_THRESHOLD = 30.0

    /** Intro overslaan, anders Volgende aflevering, anders (live) Terug naar live (als je achterloopt) of Vanaf het begin. */
    fun primary(inIntro: Boolean, nextEpisode: Boolean, isLive: Boolean, canStartOver: Boolean, behindLiveSec: Double): Primary? = when {
        inIntro -> Primary.SKIP_INTRO
        nextEpisode -> Primary.NEXT_EPISODE
        !isLive -> null
        behindLiveSec > BEHIND_THRESHOLD -> Primary.GO_LIVE
        canStartOver -> Primary.START_OVER
        else -> null
    }

    // MARK: 2. Vanaf het begin / Terug naar live (tijden in ms)

    /** Knop "Vanaf het begin": terugkijken kan, het programma loopt al minstens een minuut en je zit niet al aan het begin. */
    fun canStartOver(hasCatchup: Boolean, programStart: Long?, now: Long, behindLiveSec: Double): Boolean {
        if (!hasCatchup || programStart == null || programStart >= now) return false
        val elapsed = (now - programStart) / 1000.0
        return elapsed >= 60 && behindLiveSec < elapsed - 30
    }

    /** Vraag "Vanaf het begin" of "Live kijken": alleen met terugkijken, als het programma ≥ 2 min bezig is en nog ≥ 1 min duurt. */
    fun askStartOver(hasCatchup: Boolean, programStart: Long?, programEnd: Long?, now: Long): Boolean {
        if (!hasCatchup || programStart == null || programEnd == null || programStart >= programEnd) return false
        return now - programStart >= 120_000 && programEnd - now >= 60_000
    }

    // MARK: 3. Zappen als lijst en Vorige zender

    /** Volgorde (indexen): favorieten, dan de huidige categorie, dan de rest; binnen elk deel de volgorde van de server. */
    fun zapOrder(groups: List<String>, favorite: List<Boolean>, currentGroup: String?): List<Int> {
        val fav = ArrayList<Int>(); val same = ArrayList<Int>(); val rest = ArrayList<Int>()
        for (i in groups.indices) {
            when {
                favorite.getOrElse(i) { false } -> fav += i
                currentGroup != null && groups[i] == currentGroup -> same += i
                else -> rest += i
            }
        }
        return fav + same + rest
    }

    /** Onthoudt de vorige zender: één tik terug, en nog een tik weer terug. */
    data class PreviousChannel(val current: String? = null, val previous: String? = null) {
        fun tuned(id: String): PreviousChannel = if (id == current) this else PreviousChannel(id, current)
    }

    // MARK: 4. Dubbeltikken

    /** Dubbeltik = 10 s; elke volgende tik binnen [WINDOW_MS] aan dezelfde kant telt 10 s op (20, 30 …). */
    data class DoubleTap(val forward: Boolean = true, val total: Int = 0, val lastAt: Long = Long.MIN_VALUE / 2) {
        fun tap(f: Boolean, at: Long): DoubleTap =
            if (f == forward && total != 0 && at - lastAt <= WINDOW_MS) copy(total = total + if (f) STEP else -STEP, lastAt = at)
            else DoubleTap(f, if (f) STEP else -STEP, at)
        fun active(at: Long) = total != 0 && at - lastAt <= WINDOW_MS
        companion object { const val STEP = 10; const val WINDOW_MS = 800L }
    }

    // MARK: 6. Eindtijd

    /** "Eindigt om …": nu + resterende tijd gedeeld door de afspeelsnelheid (ms). Null zonder bekende resterende tijd. */
    fun endsAt(now: Long, remainingMs: Long, rate: Float): Long? {
        if (remainingMs <= 0) return null
        val r = if (rate > 0f) rate else 1f
        return now + (remainingMs / r).toLong()
    }

    // MARK: 7. Ondertitels even aan na terugspoelen

    fun rewindSubtitles(jumpSec: Double, subtitlesOn: Boolean, hasTrack: Boolean, enabled: Boolean): Boolean =
        enabled && !subtitlesOn && hasTrack && jumpSec <= -10 && jumpSec >= -30

    /** Weer voorbij het punt waar je terugspoelde, of daar ver vandaan gesprongen: ondertitels weer uit (ms). */
    fun rewindSubtitlesDone(positionMs: Long, untilMs: Long, startedAtMs: Long): Boolean =
        positionMs >= untilMs || positionMs < startedAtMs - 5_000

    /** Eerste spoor in een van de gewenste talen. `tracks` = taalcode of naam per spoor. */
    fun subtitleTrack(tracks: List<String>, wanted: List<String>): Int? {
        for (w in wanted.map { it.lowercase() }.filter { it.isNotEmpty() }) {
            val w2 = w.take(2)
            val i = tracks.indexOfFirst { t ->
                val x = t.lowercase()
                x == w || x == w2 || x.startsWith("$w2-") || x.startsWith("${w2}_") || languageWords(w2).any { x.contains(it) }
            }
            if (i >= 0) return i
        }
        return null
    }

    fun languageWords(code: String): List<String> = when (code) {
        "nl" -> listOf("dutch", "nederlands", "nld", "dut")
        "en" -> listOf("english", "engels", "eng")
        "de" -> listOf("german", "deutsch", "duits", "ger", "deu")
        "fr" -> listOf("french", "français", "francais", "frans", "fre", "fra")
        "es" -> listOf("spanish", "español", "espanol", "spaans", "spa")
        "it" -> listOf("italian", "italiano", "italiaans", "ita")
        "pt" -> listOf("portuguese", "português", "portugues", "por")
        "tr" -> listOf("turkish", "türkçe", "turkce", "turks", "tur")
        "pl" -> listOf("polish", "polski", "pools", "pol")
        "ar" -> listOf("arabic", "العربية", "arabisch", "ara")
        "zh" -> listOf("chinese", "中文", "chinees", "chi", "zho")
        else -> emptyList()
    }

    // MARK: 9. Slaaptimer

    sealed class Sleep(val code: String) {
        object Off : Sleep("off")
        object EndOfEpisode : Sleep("episode")
        data class Minutes(val m: Int) : Sleep("m$m")
        companion object {
            /**
             * Build 179 (X1): een getter, geen vaste lijst. Als vaste lijst werd hij gevuld tijdens het initialiseren van
             * Sleep; raakte de app eerst [Off] aan (PlayerController begint met Sleep.Off), dan was Off op dat moment nog
             * null en stond er null in de lijst: het Opties-paneel van de speler crashte (NullPointerException in sleepTitle173).
             */
            val choices: List<Sleep> get() = listOf(EndOfEpisode, Minutes(30), Minutes(60), Minutes(90), Off)
        }
    }

    fun sleepDeadline(s: Sleep, now: Long): Long? = if (s is Sleep.Minutes && s.m > 0) now + s.m * 60_000L else null

    fun sleepDue(s: Sleep, deadline: Long?, now: Long, titleEnded: Boolean): Boolean = when (s) {
        Sleep.Off -> false
        Sleep.EndOfEpisode -> titleEnded
        is Sleep.Minutes -> deadline != null && now >= deadline
    }

    /** Aftellen als "m:ss" of "u:mm:ss" */
    fun countdown(ms: Long): String {
        val s = max(0L, (ms + 999) / 1000)
        val h = s / 3600; val m = (s % 3600) / 60; val r = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
    }

    // MARK: 0c. Trakt

    enum class TraktFailure { TRANSIENT, REAUTH, LOCKED }

    /** Soort fout bij een aanroep (null = netwerkfout). 401 komt hier pas als verversen ook niet hielp. */
    fun traktClassify(status: Int?): TraktFailure = when (status) {
        401, 403 -> TraktFailure.REAUTH
        423 -> TraktFailure.LOCKED
        else -> TraktFailure.TRANSIENT
    }

    /** Mislukte tokenverversing: alleen als Trakt het refresh-token echt weigert (400/401/403) opnieuw inloggen. */
    fun traktRefreshFailure(statuses: List<Int?>): TraktFailure =
        if (statuses.isNotEmpty() && statuses.all { it != null && it in setOf(400, 401, 403) }) TraktFailure.REAUTH else TraktFailure.TRANSIENT

    /** Rode waarschuwing alleen bij een blijvende fout, of een tijdelijke die al ≥ 24 uur aanhoudt. */
    fun traktShowWarning(kind: TraktFailure?, failingSince: Long?, now: Long): Boolean = when (kind) {
        null -> false
        TraktFailure.REAUTH, TraktFailure.LOCKED -> true
        TraktFailure.TRANSIENT -> failingSince != null && now - failingSince >= 24 * 3_600_000L
    }

    /** Trakt-token uit het account alleen overnemen als het minstens zo lang geldig is (anders een al gebruikt refresh-token). */
    fun traktAdoptRemote(localAccess: String?, localExpires: Long?, remoteAccess: String, remoteExpires: Long): Boolean {
        if (localAccess == null || localExpires == null) return true
        if (localAccess == remoteAccess) return true
        return remoteExpires >= localExpires
    }

    // MARK: 0c. OpenSubtitles

    const val OS_MAX_TOKEN_AGE_MS = 23 * 3_600_000L

    fun osShouldRenew(loggedInAt: Long?, now: Long, hasPassword: Boolean): Boolean {
        if (!hasPassword) return false
        if (loggedInAt == null) return true
        return now - loggedInAt >= OS_MAX_TOKEN_AGE_MS || now < loggedInAt - 300_000
    }

    enum class OsOnResponse { KEEP, RELOGIN, EXPIRED }

    /** Alleen een 401 bij een aanroep die echt het token gebruikt; netwerk, 429 en 5xx: niets doen. */
    fun osOnResponse(status: Int?, authenticated: Boolean, hasPassword: Boolean): OsOnResponse =
        if (!authenticated || status != 401) OsOnResponse.KEEP else if (hasPassword) OsOnResponse.RELOGIN else OsOnResponse.EXPIRED

    enum class OsLogin { OK, BAD_CREDENTIALS, TRANSIENT }

    /** Alleen 400/401 = foute gegevens (uitloggen met melding); 403, 429, 5xx en netwerk zijn tijdelijk. */
    fun osLoginResult(status: Int?): OsLogin = when {
        status == null -> OsLogin.TRANSIENT
        status in 200..299 -> OsLogin.OK
        status == 400 || status == 401 -> OsLogin.BAD_CREDENTIALS
        else -> OsLogin.TRANSIENT
    }
}
