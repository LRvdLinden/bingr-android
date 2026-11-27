package nl.lrvdlinden.bingr

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Detailpagina in UHF/Netflix-stijl (= DetailView.swift): grote afbeelding met logo, score, afspeelknop,
 * Mijn lijst, trailer, bekeken, andere bronnen, beschrijving, afleveringen, beoordelingen, cast en aanbevelingen.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(target: DetailTarget, onClose: () -> Unit, isTop: Boolean? = null) {
    val tv = AppState.isTV
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var d by remember(target.id) { mutableStateOf<TitleDetails?>(null) }
    var episodes by remember(target.id) { mutableStateOf(emptyList<Episode>()) }
    var season by remember(target.id) { mutableIntStateOf(1) }
    var seasonInfo by remember(target.id) { mutableStateOf(mapOf<String, TitleDetails.EpisodeInfo>()) }
    var loading by remember(target.id) { mutableStateOf(true) }
    var fetchedTrailer by remember(target.id) { mutableStateOf<String?>(null) }
    var showSources by remember { mutableStateOf(false) }
    var sourcesLoading by remember { mutableStateOf(false) }
    var sources by remember { mutableStateOf(emptyList<OtherHit>()) }
    var toast by remember { mutableStateOf<String?>(null) }
    var ageGate by remember { mutableStateOf(false) }
    var afterPIN by remember { mutableStateOf<(() -> Unit)?>(null) }
    var seasonMenu by remember { mutableStateOf(false) }
    var addToCollection by remember { mutableStateOf(false) }
    var blockConfirm by remember { mutableStateOf(false) }
    /** Build 175: downloaden vanuit Meer: keuze bij een serie en de bevestiging (hele serie, downloads verwijderen) */
    var seriesDownloads by remember { mutableStateOf(false) }
    var downloadConfirm by remember { mutableStateOf<DownloadConfirm175?>(null) }
    var recMatches by remember(target.id) { mutableStateOf(mapOf<String, DetailTarget>()) }
    /** Trailer per seizoen (TMDB, build 53); null-waarde = opgezocht, geen seizoenstrailer. */
    var seasonTrailers by remember(target.id) { mutableStateOf(mapOf<Int, String?>()) }
    /** Leeftijdsinfo uit TMDB (build 53) voor de leeftijdscontrole bij afspelen. */
    var ageInfo by remember(target.id) { mutableStateOf<AgeRating?>(null) }
    /** TV: na het kiezen van een seizoen de focus op de huidige aflevering zetten. */
    var focusEpisode by remember(target.id) { mutableStateOf(false) }
    /** Andere taalversies van deze titel in de bibliotheek (build 59) en de gekozen versie (menu). */
    var versions by remember(target.id) { mutableStateOf(emptyList<ContentLanguage.Version>()) }
    var versionMenu by remember(target.id) { mutableStateOf<ContentLanguage.Version?>(null) }
    /** Build 174: trailer in de app (YouTube IFrame-player), menu Meer en keuze onder Bronnen (telefoon) */
    var trailerOpen by remember { mutableStateOf<String?>(null) }
    var moreMenu by remember { mutableStateOf(false) }
    var sourcesChoice by remember { mutableStateOf(false) }
    /** Build 174: andere bronnen alvast opgezocht (telefoon: de knop Bronnen alleen als er meer dan deze ene is) */
    var prefetchedSources by remember(target.id) { mutableStateOf<List<OtherHit>?>(null) }
    val compact = Build174Logic.isCompact(androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp, tv)

    // build 128: titel van een andere server: Mijn lijst, downloads en afspelen op die server (zonder te wisselen)
    val pid = target.sourceID ?: Playlists.source?.id
    // build 171: titel geblokkeerd voor dit profiel (deeplink, zoeken): alleen de melding
    var justBlocked by remember(target.id) { mutableStateOf(false) }
    if (!justBlocked && Store.isTitleBlocked(pid, target.isSeries, target.id)) { BlockedTitleNotice(onClose); return }
    val lib = Playlists.library
    val details = d
    val rawTitle = details?.title?.takeIf { it.isNotBlank() } ?: target.title
    // taallabel uit de titel weg (build 59): "Animal (IT)" → "Animal"; de taal staat in de metadata-regel
    val title = remember(rawTitle) { ContentLanguage.displayTitle(rawTitle) }
    val language = ContentLanguage.of(target)
    val imdb: String? = when (target) {
        is DetailTarget.MovieT -> Trakt.imdb(target.movie)
        is DetailTarget.SeriesT -> Trakt.imdb(target.series)
    } ?: screensText(details?.imdbID)
    val heroImage = if (tv) screensText(details?.backdrop) ?: screensText(details?.poster) ?: target.poster
    else screensText(details?.poster) ?: target.poster ?: screensText(details?.backdrop)
    val trailerID = screensText(details?.trailerYouTubeID) ?: fetchedTrailer
    // seizoenstrailer (build 53): trailer van het gekozen seizoen, anders die van de serie
    val seasonTrailer: String? = if (target is DetailTarget.SeriesT) seasonTrailers[season] else null
    val trailerToPlay = seasonTrailer ?: trailerID
    // build 191: tabbladen onder de knoppen: Afleveringen / Meer zoals dit / Trailers (alleen met inhoud)
    var tabWanted191 by remember(target.id) { mutableStateOf<Build191Logic.Tab?>(null) }
    val recs191 = details?.recommendations.orEmpty().let { all ->
        if (Store.activeProfile?.effectiveAgeLimit != null) all.filter { recMatches[it.id] != null } else all
    }
    val trailers191 = listOfNotNull(trailerID, seasonTrailer).distinct()
    val tabs191 = Build191Logic.tabs(target is DetailTarget.SeriesT, recs191.size, trailers191.size)
    val tab191 = Build191Logic.selected(tabs191, tabWanted191)
    // build 82: trailer automatisch in de kop; series: die van het nieuwste seizoen, anders die van de serie
    val newestSeason: Int? = if (target is DetailTarget.SeriesT) episodes.maxOfOrNull { it.season } else null
    val autoplayTrailer: String? = when {
        loading -> null
        target !is DetailTarget.SeriesT -> trailerID
        newestSeason == null || screensInt(details?.tmdbID) == null -> trailerID
        // nog aan het opzoeken: even wachten, anders begint eerst de trailer van de serie
        !seasonTrailers.containsKey(newestSeason) -> null
        else -> seasonTrailers[newestSeason] ?: trailerID
    }
    val isMovie = target is DetailTarget.MovieT
    val seasons = remember(episodes) { episodes.map { it.season }.distinct().sorted() }
    val progress = Store.progress
    val nextEpisode: Episode? = nextEpisodeOf(episodes, imdb)
    val isWatchedTitle = (target as? DetailTarget.MovieT)?.let { Playables.isWatched(it.movie, imdb) } ?: false
    // Mijn lijst van dít profiel (de Trakt-watchlist volgt mee, maar bepaalt niet wat er in je lijst staat)
    val inMyList = pid != null && Store.inMyList(pid, target.id)
    // build 179: duimen (niet voor titels buiten je bibliotheek) en beoordelingen via de server (MDBList)
    val canThumb179 = !target.id.startsWith("rec-") && !DiscoverItem.isExternal(target.id) && Store.activeProfileID != null
    @Suppress("UNUSED_VARIABLE") val thumbsVersion179 = Store.thumbs
    var ratings179 by remember(target.id) { mutableStateOf<List<Ratings179.R>>(emptyList()) }
    LaunchedEffect(target.id) { ratings179 = runCatching { Ratings179.load(target) }.getOrDefault(emptyList()) }
    val resumeFraction: Double? = when (target) {
        is DetailTarget.MovieT -> Playables.resume(target.movie.id, imdb)
        is DetailTarget.SeriesT -> nextEpisode?.let { progress[it.id]?.fraction }
    }
    val playTitle = when (target) {
        is DetailTarget.MovieT -> if ((resumeFraction ?: 0.0) > 0.02) L("Resume") else L("Play")
        is DetailTarget.SeriesT -> nextEpisode?.let { L("Play Season %d: Episode %d", it.season, it.number) } ?: L("Play")
    }

    // MARK: delen
    /** Titel zonder IPTV-rommel ("NL | Reacher (2022) 4K" → "Reacher"). */
    val shareTitle = details?.title?.takeIf { it.isNotBlank() } ?: TitleCleaner.parse(target.title).title.ifBlank { target.title }
    val shareYear = TitleShare.year(screensText(details?.year)) ?: target.year
    /** Build 132: eigen Bingr-link (bingr.online/titel/, preview met cover; opent in de app) i.p.v. IMDb/Trakt/TMDB. */
    val shareURL: String = TitleShare.url(isMovie, details?.tmdbID ?: Details.cachedTMDB(target.id), shareTitle, shareYear)
    val shareText = L(if (isMovie) "Movie tip: %@" else "Series tip: %@", TitleShare.label(shareTitle, shareYear))

    fun flash(t: String) {
        toast = t
        scope.launch { delay(1600); if (toast == t) toast = null }
    }

    // MARK: laden
    LaunchedEffect(target.id) {
        loading = true
        coroutineScope {
            val eps = (target as? DetailTarget.SeriesT)?.let { st -> async { screensSafe { Playlists.episodes(st.series) } ?: emptyList() } }
            d = screensSafe { Details.load(target) }
            episodes = eps?.await() ?: emptyList()
        }
        val id = when (target) {
            is DetailTarget.MovieT -> Trakt.imdb(target.movie)
            is DetailTarget.SeriesT -> Trakt.imdb(target.series)
        } ?: screensText(d?.imdbID)
        // al begonnen met kijken: het seizoen van de volgende aflevering; anders het nieuwste seizoen
        val started = episodes.any { e -> (Store.progress[e.id]?.fraction ?: 0.0) > 0.02 || Playables.isWatched(e, id) }
        val n = nextEpisodeOf(episodes, id)
        season = (if (started) n?.season else null) ?: episodes.maxOfOrNull { it.season } ?: 1
        loading = false
        if (screensText(d?.trailerYouTubeID) == null && id != null) {
            fetchedTrailer = screensSafe { Trakt.trailer(id, target.isSeries) }
        }
    }
    LaunchedEffect(season, details) {
        val tmdb = screensInt(details?.tmdbID) ?: return@LaunchedEffect
        if (target !is DetailTarget.SeriesT) return@LaunchedEffect
        val info = screensSafe { Details.season(tmdb, season) } ?: emptyMap()
        if (info.isNotEmpty()) seasonInfo = seasonInfo + info
    }
    LaunchedEffect(season, details?.tmdbID) {
        if (target !is DetailTarget.SeriesT) return@LaunchedEffect
        val tmdb = screensInt(details?.tmdbID) ?: return@LaunchedEffect
        if (seasonTrailers.containsKey(season)) return@LaunchedEffect
        val s = season
        val key = screensSafe { TMDB.seasonTrailer(tmdb, s) }
        seasonTrailers = seasonTrailers + (s to key)
    }
    // seizoenstrailer van het nieuwste seizoen (voor de trailer in de kop, build 82)
    LaunchedEffect(newestSeason, details?.tmdbID) {
        val n = newestSeason ?: return@LaunchedEffect
        val tmdb = screensInt(details?.tmdbID) ?: return@LaunchedEffect
        if (seasonTrailers.containsKey(n)) return@LaunchedEffect
        val key = screensSafe { TMDB.seasonTrailer(tmdb, n) }
        seasonTrailers = seasonTrailers + (n to key)
    }
    LaunchedEffect(target.id, System.identityHashCode(lib)) {
        versions = screensSafe { ContentLanguage.versions(target) } ?: emptyList()
    }
    LaunchedEffect(target.id) {
        if (Store.activeProfile?.effectiveAgeLimit == null) return@LaunchedEffect
        ageInfo = AgeRatings.cached(target) ?: screensSafe { AgeRatings.lookup(target) }
    }
    // aanbevelingen die in deze afspeellijst staan
    val recKey = details?.recommendations?.map { it.id }
    LaunchedEffect(recKey, System.identityHashCode(lib)) {
        val recs = details?.recommendations
        if (recs.isNullOrEmpty()) { recMatches = emptyMap(); return@LaunchedEffect }
        recMatches = withContext(Dispatchers.Default) {
            // alleen de gezochte titels normaliseren/bewaren (één keer, niet bij elke schermverversing)
            val wanted = recs.mapTo(HashSet()) { screensNormTitle(it.title) }
            val movies = HashMap<String, MutableList<Movie>>()
            for (m in lib.movies) { val n = screensNormTitle(m.title); if (n in wanted) movies.getOrPut(n) { ArrayList() }.add(m) }
            val series = HashMap<String, Series>()
            for (x in lib.series) { val n = screensNormTitle(x.title); if (n in wanted) series.putIfAbsent(n, x) }
            val out = HashMap<String, DetailTarget>()
            for (r in recs) {
                val n = screensNormTitle(r.title)
                if (r.isShow) {
                    series[n]?.let { out[r.id] = DetailTarget.SeriesT(it) }
                } else {
                    val y = r.year
                    movies[n]?.firstOrNull { y == null || it.year == null || it.year == y }?.let { out[r.id] = DetailTarget.MovieT(it) }
                }
            }
            out
        }
    }

    // MARK: acties

    fun ageChecked(action: () -> Unit) {
        val prof = Store.activeProfile
        val limit = prof?.effectiveAgeLimit
        val cached = when (target) {
            is DetailTarget.MovieT -> Metadata.cached(target.movie)
            is DetailTarget.SeriesT -> Metadata.cached(target.series)
        }
        val rated = screensText(details?.certification) ?: cached?.rated
        val genre = cached?.genre ?: details?.genres?.takeIf { it.isNotEmpty() }?.joinToString(",")
        // TMDB-classificatie (Kijkwijzer eerst) gaat vóór de OMDb/Trakt-classificatie (build 53)
        val info = ageInfo ?: AgeRatings.cached(target)
        val strict = prof?.strictAgeFilter ?: false
        if (ParentalControl.allowed(rated, target.group, limit, genre, strict, info)) { action(); return }
        // kinderprofiel zonder TMDB-classificatie in de cache (build 59): niet eerst wachten op TMDB, maar de
        // controle tegelijk met het voorbereiden van de speler laten lopen; alleen het eerste beeld wacht erop
        if (info == null && strict && !ParentalControl.isAdultGroup(target.group)) {
            AppState.pendingAgeCheck = {
                val i = AgeRatings.lookup(target)
                withContext(Dispatchers.Main) { ParentalControl.allowed(rated, target.group, limit, genre, strict, i) }
            }
            action()
            AppState.pendingAgeCheck = null   // geen afspelen (bijv. "Andere bronnen"): niet laten liggen
            return
        }
        if (ParentalControl.hasPIN) { afterPIN = action; ageGate = true }
        else flash(L("Not available on this profile (age limit)."))
    }

    fun openSources() {
        if (sourcesLoading) return
        sourcesLoading = true
        scope.launch {
            val year = screensInt(details?.year) ?: target.year
            sources = screensSafe { Playlists.otherSources(rawTitle, year, target.isSeries, target.id) } ?: emptyList()
            sourcesLoading = false
            showSources = true
        }
    }

    fun play() = ageChecked {
        when (target) {
            is DetailTarget.MovieT -> {
                val m = target.movie
                // build 111: TMDB-titel (niet in deze afspeellijst): in je andere bronnen zoeken
                if (DiscoverItem.isExternal(m.id) || (m.streamURL == null && m.stalkerCmd == null && m.imdbID == null && imdb == null)) openSources()
                else AppState.play(Playables.movie(m, imdb))
            }
            is DetailTarget.SeriesT -> {
                val n = nextEpisode
                if (n != null) AppState.play(Playables.episode(n, target.series, imdb)) else openSources()
            }
        }
    }

    /** Downloaden (build 52, telefoon/tablet): film of aflevering. */
    fun download(item: PlayableItem, seriesTitle: String?) {
        val existing = Downloads.find(pid, item.id)
        existing?.let { Downloads.shareWithActive(it) }
        if (existing?.isDone == true) { flash(L("Downloaded")); return }
        if (existing != null && existing.status != Downloads.FAILED && existing.status != Downloads.PAUSED) { flash(L("Downloading…")); return }
        scope.launch {
            val err = Downloads.start(item, seriesTitle, target.poster)
            flash(err ?: L("Download started"))
        }
    }

    /** Build 175: afleveringen van deze serie met de stand van hun download (null bij een film) */
    fun seriesDownloadModel(): Triple<List<PlayableItem>, List<Build175Logic.Episode>, Build175Logic.SeriesMenu>? {
        val st = target as? DetailTarget.SeriesT ?: return null
        val items = episodes.map { Playables.episode(it, st.series, imdb) }
        val eps = episodes.zip(items).map { (e, it) ->
            Build175Logic.Episode(it.id, e.season, e.number, Downloads.canDownload(it), Build175Logic.state(Downloads.find(pid, it.id)?.status))
        }
        return Triple(items, eps, Build175Logic.seriesMenu(eps, season, nextEpisode?.id))
    }

    /** Build 175: meerdere afleveringen na elkaar in de wachtrij (Downloads werkt ze één voor één af) */
    fun downloadMany(list: List<PlayableItem>) {
        if (list.isEmpty()) return
        val seriesTitle = (target as? DetailTarget.SeriesT)?.series?.title
        scope.launch {
            var err: String? = null
            for (it in list) { val e = Downloads.start(it, seriesTitle, target.poster); if (err == null) err = e }
            flash(err ?: if (list.size == 1) L("Download started") else L("%d episodes added to Downloads", list.size))
        }
    }

    /** Build 175: een keuze uit het seriemenu (seizoen, volgende 3, hele serie) */
    fun downloadScope(scope175: Build175Logic.Scope) {
        val (items, eps, _) = seriesDownloadModel() ?: return
        val ids = Build175Logic.episodes(scope175, eps, season, nextEpisode?.id)
        val byID = items.associateBy { it.id }
        val chosen = ids.mapNotNull { byID[it] }
        if (Build175Logic.needsConfirmation(scope175, chosen.size)) downloadConfirm = DownloadConfirm175(download = chosen)
        else downloadMany(chosen)
    }

    /** Build 175: Downloaden in Meer bij een film: de stand van de download, of uitgegrijsd met de reden */
    fun movieDownloadActions(): List<S2Action> {
        val movie = (target as? DetailTarget.MovieT)?.let { Playables.movie(it.movie, imdb) } ?: return emptyList()
        if (!Downloads.supported) return emptyList()
        val dl = Downloads.find(pid, movie.id)
        return when (Build175Logic.movieMenu(Downloads.canDownload(movie), Build175Logic.state(dl?.status))) {
            Build175Logic.MovieMenu.DOWNLOADED -> listOfNotNull(
                S2Action(L("Downloaded") + "  ✓", Icons.Default.DownloadDone, enabled = false) {},
                dl?.let { x -> S2Action(L("Play offline"), Icons.Default.PlayArrow) { AppState.play(Downloads.playable(x)) } },
                dl?.let { x -> S2Action(L("Delete"), Icons.Default.Delete, destructive = true) { Downloads.delete(x.key) } },
            )
            Build175Logic.MovieMenu.ACTIVE -> listOfNotNull(
                dl?.let { x -> S2Action(L("Pause download") + " · ${(x.progress * 100).toInt()}%", Icons.Default.Pause) { Downloads.pause(x.key) } },
                dl?.let { x -> S2Action(L("Delete"), Icons.Default.Delete, destructive = true) { Downloads.delete(x.key) } },
            )
            Build175Logic.MovieMenu.RESUME -> listOfNotNull(
                dl?.let { x -> S2Action(L("Resume download"), Icons.Default.Download) { Downloads.resume(x.key) } },
                dl?.let { x -> S2Action(L("Delete"), Icons.Default.Delete, destructive = true) { Downloads.delete(x.key) } },
            )
            Build175Logic.MovieMenu.DOWNLOAD -> listOf(S2Action(L("Download"), Icons.Default.Download) { download(movie, null) })
            Build175Logic.MovieMenu.UNAVAILABLE -> listOf(
                S2Action(L("Download"), Icons.Default.Download, enabled = false, subtitle = L("This stream can't be downloaded")) {},
            )
        }
    }

    /** Build 175: Downloaden in Meer bij een serie: opent de keuze, of uitgegrijsd met de reden */
    fun seriesDownloadAction(): S2Action? {
        if (target !is DetailTarget.SeriesT || !Downloads.supported) return null
        val menu = seriesDownloadModel()?.third
        if (menu == null || !menu.available) {
            return S2Action(L("Download"), Icons.Default.Download, enabled = false, subtitle = L("This stream can't be downloaded")) {}
        }
        return if (menu.complete) S2Action(L("Downloaded") + "  ✓", Icons.Default.DownloadDone) { seriesDownloads = true }
        else S2Action(L("Download"), Icons.Default.Download) { seriesDownloads = true }
    }

    fun toggleMyList() {
        val p = pid ?: return
        // build 141: een aanbeveling zonder TMDB-id ("rec-…") kan zo niet in Mijn lijst (geen apparaat vindt die sleutel
        // terug): eerst de titel in je bibliotheken zoeken (alle servers) en die toevoegen
        if (target.id.startsWith(Build141Feedback.MyListKeys.RECOMMENDATION) && !Store.inMyList(p, target.id)) {
            scope.launch {
                val hit = screensSafe { Playlists.otherSources(target.title, target.year, target.isSeries, excludingID = "") }
                    ?.firstOrNull { !it.isLive }
                val t: DetailTarget? = when (val x = hit?.item) {
                    is Movie -> DetailTarget.MovieT(x)
                    is Series -> DetailTarget.SeriesT(x)
                    else -> null
                }
                if (t == null) { flash(L("Not in your playlists")); return@launch }
                AllServers.setMyList(t, true, hit!!.playlist.id)
                flash(L("Added to My List"))
            }
            return
        }
        val on = !Store.inMyList(p, target.id)
        // build 141: TMDB-titel: gegevens (poster, datum) ophalen voor de rij Mijn lijst, als die nog niet bekend zijn
        if (on && DiscoverItem.isExternal(target.id) && !DiscoverKnown.contains(target.id)) {
            AppScope.launch { screensSafe { DiscoverStore.resolve(listOf(target.id)) } }
        }
        // build 140: titel van een andere server meteen in de rij Mijn lijst (niet pas na de volgende ronde)
        AllServers.setMyList(target, on, p)
        // de Trakt-watchlist volgt alleen voor het hoofdaccount
        if (imdb != null && Store.isMainActive && Trakt.isLoggedIn && (imdb in Trakt.watchlist) != on) {
            AppScope.launch { screensSafe { Trakt.toggleWatchlist(imdb, target.isSeries) } }
        }
        flash(if (on) L("Added to My List") else L("Removed from My List"))
    }

    fun share() {
        if (tv) return
        // onderwerp en tekst "Filmtip: Titel (jaar)", daaronder de Bingr-link
        ShareSheet.share(ctx, shareText, shareText, shareURL)
    }

    fun toggleWatchedMovie() {
        val m = (target as? DetailTarget.MovieT)?.movie ?: return
        val w = isWatchedTitle
        Watched.setWatched(m, imdb, !w)
        AppScope.launch { screensSafe { Trakt.setWatched(Playables.movie(m, imdb), !w) } }
    }

    fun openLocal(id: String) {
        lib.movies.firstOrNull { it.id == id }?.let { ScreensNav.detail(DetailTarget.MovieT(it)); return }
        lib.series.firstOrNull { it.id == id }?.let { ScreensNav.detail(DetailTarget.SeriesT(it)) }
    }

    // build 174: telefoon: andere bronnen alvast opzoeken (na het laden), zodat Bronnen alleen verschijnt als er meer is
    LaunchedEffect(target.id, loading, compact) {
        if (loading || !compact || prefetchedSources != null) return@LaunchedEffect
        val year = screensInt(d?.year) ?: target.year
        prefetchedSources = screensSafe { Playlists.otherSources(rawTitle, year, target.isSeries, target.id) } ?: emptyList()
    }

    // MARK: trailer in de kop (build 82)
    val heroTrailer = remember(target.id) { HeroTrailerState() }
    var inForeground by remember { mutableStateOf(true) }
    DisposableEffect(Unit) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> { inForeground = false; heroTrailer.stop() }
                androidx.lifecycle.Lifecycle.Event.ON_START -> inForeground = true
                else -> {}
            }
        }
        val lc = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle
        lc.addObserver(obs)
        onDispose { lc.removeObserver(obs); heroTrailer.stop() }
    }
    // alleen op de bovenste pagina, niet onder de speler, multiview, "Andere bronnen" of de pincode
    val trailerBlocked = AppState.playing != null || AppState.showMultiview || showSources || ageGate ||
        !inForeground || !(isTop ?: ScreensNav.stack.isEmpty())
    LaunchedEffect(autoplayTrailer, trailerBlocked) {
        if (trailerBlocked) { heroTrailer.stop(); return@LaunchedEffect }
        val id = autoplayTrailer ?: return@LaunchedEffect
        // één keer per pagina vanzelf (niet opnieuw na afspelen, het einde of terugkomen)
        if (heroTrailer.startedID != null) return@LaunchedEffect
        delay(1500)
        if (!HeroTrailer.allowed(ctx)) return@LaunchedEffect
        heroTrailer.start(id)
    }

    BackHandler(enabled = !showSources && !ageGate) { onClose() }
    BackHandler(enabled = showSources) { showSources = false }

    val playFocus = rememberInitialFocus(key = target.id)
    val headerHeight: Dp = if (tv) 330.dp else 560.dp
    val contentAlign = if (tv) Alignment.Start else Alignment.CenterHorizontally

    // build 131: TV scrolt de pagina niet meer naar de afspeelknop bij openen (Compose zette het gefocuste element op
    // TV op ~30% van de hoogte, waardoor de titel tegen de bovenrand viel): alleen scrollen als iets buiten beeld valt,
    // met een veilige marge (overscan); de rijen binnen de pagina houden hun eigen gedrag
    val detailScroll = rememberScrollState()
    val innerSpec = LocalBringIntoViewSpec.current
    val density = LocalDensity.current
    val pageSpec = remember(tv, innerSpec, density) {
        if (tv) SafeMarginBringIntoViewSpec(with(density) { 48.dp.toPx() }, with(density) { 48.dp.toPx() }) else innerSpec
    }
    Box(Modifier.fillMaxSize().background(Theme.bg)) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides pageSpec) {
        Column(
            Modifier.fillMaxSize().blockFocus(showSources || ageGate).verticalScroll(detailScroll),
            verticalArrangement = Arrangement.spacedBy(tvs(22))
        ) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides innerSpec) {
            // MARK: kop
            Box(Modifier.fillMaxWidth().height(headerHeight)) {
                // build 85: speelt de trailer niet (YouTube weigert de embed, geen WebView), dan beweegt de afbeelding rustig
                val kb = heroTrailer.failed && !HeroTrailer.reduceMotion(ctx)
                Box(Modifier.matchParentSize().clipToBounds()) {
                    RemoteImage(heroImage, Modifier.matchParentSize().kenBurns(kb))
                }
                HeroTrailerView(heroTrailer, Modifier.matchParentSize())
                Box(
                    Modifier.matchParentSize().background(
                        Brush.verticalGradient(0.35f to Color.Transparent, 0.75f to Color.Black.copy(alpha = 0.65f), 1f to Color.Black)
                    )
                )
                if (tv) {
                    Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.85f), 0.5f to Color.Transparent)))
                }
                if (!tv && heroTrailer.visible) {
                    Box(
                        Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 56.dp, end = 16.dp)
                            .size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f))
                            .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                            .focusLift(1.06f) { heroTrailer.toggleMute() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (heroTrailer.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            if (heroTrailer.muted) L("Sound on") else L("Sound off"), tint = Color.White, modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = Theme.edge).padding(bottom = tvs(8)),
                    contentAlignment = if (tv) Alignment.BottomStart else Alignment.BottomCenter
                ) {
                    val logo = screensText(details?.logo)
                    val titleText: @Composable () -> Unit = {
                        Text(
                            title.uppercase(), color = Color.White, fontSize = tsp(34), fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            textAlign = if (tv) TextAlign.Start else TextAlign.Center, lineHeight = tsp(38)
                        )
                    }
                    if (logo != null) {
                        coil.compose.SubcomposeAsyncImage(
                            model = logo, contentDescription = title,
                            modifier = Modifier.widthIn(max = tvs(300)).heightIn(max = tvs(110)),
                            contentScale = ContentScale.Fit,
                            loading = { titleText() },
                            error = { titleText() }
                        )
                    } else {
                        titleText()
                    }
                }
            }

            // MARK: score, jaar, duur
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Theme.edge),
                horizontalAlignment = contentAlign,
                verticalArrangement = Arrangement.spacedBy(tvs(22))
            ) {
                // build 174: afbreken in plaats van één te brede regel (de pagina schoof eerst buiten het scherm)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(tvs(14), contentAlign),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val main = details?.ratings?.firstOrNull { it.source == "IMDb" } ?: details?.ratings?.firstOrNull()
                    Text(
                        "★ ${main?.value?.toString() ?: "–"}", color = Color.White, fontSize = tsp(15), fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.15f))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                    (screensText(details?.year) ?: target.year?.toString())?.let { Text(it.take(4), color = Color.White, fontSize = tsp(16)) }
                    screensText(details?.certification)?.let {
                        Text(
                            it, color = Color.White, fontSize = tsp(12), fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.border(1.dp, Theme.secondary, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                    if (!isMovie && seasons.isNotEmpty()) {
                        Text(if (seasons.size == 1) L("1 season") else L("%d seasons", seasons.size), color = Color.White, fontSize = tsp(16))
                    } else {
                        screensText(details?.runtime)?.let { Text(it, color = Color.White, fontSize = tsp(16)) }
                    }
                    if (isWatchedTitle) Icon(Icons.Default.CheckCircle, null, tint = Theme.green, modifier = Modifier.size(tvs(18)).align(Alignment.CenterVertically))
                }

                // MARK: knoppen
                Column(
                    Modifier.widthIn(max = if (tv) 460.dp else 700.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(tvs(12))
                ) {
                    // afspeelknop (build 58): gevuld in de accentkleur, wit icoon en witte tekst; op TV bij focus
                    // groter met witte rand en gloed (nooit wit gevuld)
                    var playFocused by remember { mutableStateOf(false) }
                    val playShape = RoundedCornerShape(50)
                    Box(
                        Modifier.fillMaxWidth()
                            .focusRequester(playFocus)
                            .onFocusChanged {
                                playFocused = it.isFocused
                                // TV: op de afspeelknop staat de pagina altijd bovenaan, zodat titel/logo in beeld is
                                if (tv && it.isFocused && detailScroll.value > 0) scope.launch { detailScroll.animateScrollTo(0) }
                            }
                            .focusLift(if (tv) 1.06f else 1.04f) { play() }
                            .playFocusRing(tv && playFocused, playShape)
                            .height(tvs(52)).clip(playShape).background(Theme.red),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PlayArrow, null, tint = Theme.onAccent, modifier = Modifier.size(tvs(22)))
                            Spacer(Modifier.width(6.dp))
                            Text(playTitle, color = Theme.onAccent, fontSize = tsp(16), fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                        val p = resumeFraction ?: 0.0
                        if (p > 0.02) {
                            // voortgang wit op een donkere baan, want de knop zelf is al de accentkleur
                            Box(
                                Modifier.align(Alignment.BottomCenter).padding(start = 24.dp, end = 24.dp, bottom = 6.dp)
                                    .fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.3f))
                            ) {
                                Box(Modifier.fillMaxWidth(p.toFloat().coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(50)).background(Theme.onAccent))
                            }
                        }
                    }
                    // build 190: telefoon: donkergrijze pil Downloaden onder Afspelen; de keuzes
                    // (hele serie, pauzeren, verwijderen) blijven dezelfde als onder Meer
                    // build 191: ook op tablet; bij een serie met "N afleveringen" erbij
                    if (!tv && Downloads.supported) {
                        val movieDl = (target as? DetailTarget.MovieT)?.let { Playables.movie(it.movie, imdb) }
                        val dlState = movieDl?.let { Build175Logic.movieMenu(Downloads.canDownload(it), Build175Logic.state(Downloads.find(pid, it.id)?.status)) }
                        val seriesMenu = if (target is DetailTarget.SeriesT) seriesDownloadModel()?.third else null
                        val dlPair: Pair<String?, () -> Unit> = when {
                            movieDl != null && dlState == Build175Logic.MovieMenu.DOWNLOAD -> L("Download") to { download(movieDl, null) }
                            movieDl != null && dlState == Build175Logic.MovieMenu.DOWNLOADED -> L("Downloaded") to { moreMenu = true }
                            movieDl != null && dlState == Build175Logic.MovieMenu.ACTIVE -> L("Downloading…") to { moreMenu = true }
                            movieDl != null && dlState == Build175Logic.MovieMenu.RESUME -> L("Resume download") to { moreMenu = true }
                            seriesMenu != null && seriesMenu.available -> (
                                if (seriesMenu.complete) L("Downloaded")
                                else if (Build191Logic.showEpisodeCount(seriesMenu.season, false)) L("Download %d episodes", seriesMenu.season)
                                else L("Download")
                            ) to { seriesDownloads = true }
                            else -> null to {}
                        }
                        val dlText = dlPair.first
                        val dlTap = dlPair.second
                        if (dlText != null) {
                            Row(
                                Modifier.fillMaxWidth().height(tvs(46)).focusLift(1.04f) { dlTap() }
                                    .clip(RoundedCornerShape(50)).background(Color(0xFF2B2B2B)),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(Icons.Default.Download, null, tint = Color.White, modifier = Modifier.size(tvs(20)))
                                Spacer(Modifier.width(8.dp))
                                Text(dlText, color = Color.White, fontSize = tsp(15), fontWeight = FontWeight.SemiBold, maxLines = 1)
                            }
                        }
                    }
                    // build 174: telefoon: Mijn lijst · Trailer · Bronnen · Gezien · Meer (de rest onder Meer), gelijk verdeeld
                    if (compact) {
                        val other = prefetchedSources?.size ?: 0
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            for (a in Build174Logic.compactActions(pid != null, trailerToPlay != null, other, versions.size, isMovie)) {
                                when (a) {
                                    Build174Logic.Action.MY_LIST -> DetailAction(L("My List"), if (inMyList) L("In My List") else L("Add to My List"), Modifier.weight(1f), { toggleMyList() }) {
                                        PlusCheckIcon(inMyList, tint = if (inMyList) Theme.red else Color.White, size = 24.dp)
                                    }
                                    Build174Logic.Action.TRAILER -> DetailAction(L("Trailer"), L("Trailer"), Modifier.weight(1f), { heroTrailer.stop(); trailerOpen = trailerToPlay }) {
                                        Icon(Icons.Default.Theaters, null, tint = Color.White, modifier = Modifier.size(24.dp))
                                    }
                                    Build174Logic.Action.SOURCES -> DetailAction(L("Sources"), L("Sources"), Modifier.weight(1f), {
                                        when (Build174Logic.sourcesTarget(other, versions.size)) {
                                            Build174Logic.SourcesTarget.CHOOSE -> sourcesChoice = true
                                            Build174Logic.SourcesTarget.LANGUAGES -> versions.firstOrNull()?.let { v -> if (versions.size == 1 && v.target !is DetailTarget.SeriesT) versionMenu = v else sourcesChoice = true }
                                            else -> { sources = prefetchedSources ?: emptyList(); showSources = true }
                                        }
                                    }) { Icon(BingrIcons.otherSources, null, tint = Color.White, modifier = Modifier.size(24.dp)) }
                                    Build174Logic.Action.WATCHED -> DetailAction(L("Watched"), if (isWatchedTitle) L("Mark as unwatched") else L("Mark as watched"), Modifier.weight(1f), { toggleWatchedMovie() }) {
                                        Icon(if (isWatchedTitle) Icons.Default.CheckCircle else Icons.Default.Check, null, tint = if (isWatchedTitle) Theme.green else Color.White, modifier = Modifier.size(24.dp))
                                    }
                                    Build174Logic.Action.MORE -> DetailAction(L("More"), L("More"), Modifier.weight(1f), { moreMenu = true }) {
                                        Icon(Icons.Default.MoreHoriz, null, tint = Color.White, modifier = Modifier.size(24.dp))
                                    }
                                }
                            }
                        }
                    } else
                    Row(horizontalArrangement = Arrangement.spacedBy(tvs(12)), verticalAlignment = Alignment.CenterVertically) {
                        // Mijn lijst: plus / vinkje in de accentkleur, met animatie (was een hartje)
                        DetailCircleBox(if (inMyList) L("In My List") else L("Add to My List"), onClick = { toggleMyList() }) { lit ->
                            PlusCheckIcon(inMyList, tint = if (lit) Color.Black else if (inMyList) Theme.red else Color.White, size = tvs(22))
                        }
                        // build 111: ook niet voor titels uit TMDB (Binnenkort op Bingr / Nieuw op Bingr)
                        if (!target.id.startsWith("rec-") && !DiscoverItem.isExternal(target.id)) DetailCircleButton(Icons.AutoMirrored.Filled.PlaylistAdd) { addToCollection = true }
                        if (tv && heroTrailer.visible) {
                            DetailCircleButton(if (heroTrailer.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp) {
                                heroTrailer.toggleMute()
                            }
                        }
                        if (trailerToPlay != null) DetailCircleButton(Icons.Default.Theaters) { heroTrailer.stop(); trailerOpen = trailerToPlay }
                        if (isMovie) DetailCircleButton(if (isWatchedTitle) Icons.Default.CheckCircle else Icons.Default.Check) { toggleWatchedMovie() }
                        // build 179: duim omhoog/omlaag (per profiel, gesynct)
                        if (canThumb179) {
                            val th = ForYou179.thumb(target)
                            DetailCircleButton(if (th == 1) Icons.Default.ThumbUp else Icons.Outlined.ThumbUp, tint = if (th == 1) Theme.red else Color.White) { ForYou179.toggle(target, 1) }
                            DetailCircleButton(if (th == -1) Icons.Default.ThumbDown else Icons.Outlined.ThumbDown, tint = if (th == -1) Theme.red else Color.White) { ForYou179.toggle(target, -1) }
                        }
                        // downloaden (build 52): niet op TV
                        val movieItem = (target as? DetailTarget.MovieT)?.let { Playables.movie(it.movie, imdb) }
                        if (tv && movieItem != null && Downloads.canDownload(movieItem)) {
                            val dl = Downloads.find(pid, movieItem.id)
                            DetailCircleButton(
                                when { dl?.isDone == true -> Icons.Default.DownloadDone; dl != null && dl.status != Downloads.FAILED -> Icons.Default.Downloading; else -> Icons.Default.Download },
                                tint = if (dl?.isDone == true) Theme.red else Color.White
                            ) { download(movieItem, null) }
                        }
                        // build 175: tablet: serie downloaden (seizoen, volgende 3, hele serie)
                        if (tv && target is DetailTarget.SeriesT && Downloads.supported && episodes.isNotEmpty()) {
                            val m = seriesDownloadModel()?.third
                            if (m != null && m.available) DetailCircleButton(
                                if (m.complete) Icons.Default.DownloadDone else Icons.Default.Download,
                                tint = if (m.complete) Theme.red else Color.White
                            ) { seriesDownloads = true }
                        }
                        // geen deelknop op Android TV, Google TV en Fire TV (zie docs/ACCOUNT.md, build 45)
                        if (!tv) DetailCircleButton(Icons.Default.Share) { share() }
                        // build 171: "Verbergen voor dit profiel" (niet in een kinderprofiel; dat stelt het hoofdaccount in)
                        if (Store.activeProfile?.kids != true && pid != null) DetailCircleButton(Icons.Default.VisibilityOff) { blockConfirm = true }
                        DetailCapsule(L("Other sources"), Modifier.weight(1f), icon = BingrIcons.otherSources, busy = sourcesLoading) { openSources() }
                    }
                }

                // MARK: voortgang van de hele serie (build 53)
                if (target is DetailTarget.SeriesT && episodes.isNotEmpty()) {
                    val share = watchedShare(episodes, imdb, progress)
                    if (share > 0) {
                        Column(
                            Modifier.widthIn(max = if (tv) 460.dp else 700.dp).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(L("%d%% of the series watched", percent(share)), color = Theme.secondary, fontSize = tsp(13))
                            ThinProgress(share)
                        }
                    }
                }

                val overview = screensText(details?.overview) ?: when (target) {
                    is DetailTarget.MovieT -> target.movie.plot
                    is DetailTarget.SeriesT -> target.series.plot
                }
                if (!overview.isNullOrBlank()) {
                    Text(
                        overview, color = Color.White.copy(alpha = 0.92f), fontSize = tsp(16),
                        modifier = Modifier.widthIn(max = 700.dp).fillMaxWidth()
                    )
                } else if (loading) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(tvs(24)))
                }
                val genres = screensText(details?.genres)
                val director = screensText(details?.director)
                if (genres != null || director != null) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        genres?.let { CreditLine(L("Genres"), it) }
                        director?.let { CreditLine(L("Director"), it) }
                    }
                }
                // taal van deze versie (build 59): uit de titel/categorie, anders de originele taal (TMDB)
                language?.longName?.let { CreditLine(L("Language"), it) }
                // build 111: titel uit TMDB (Binnenkort op Bingr / Nieuw op Bingr): niet in je afspeellijst en de datum
                // (build 112: bronvermelding staat in Instellingen → Over)
                remember(target.id) { DiscoverStore.detailNote(target.id) }?.let {
                    Text(it, color = Theme.secondary, fontSize = tsp(12), modifier = Modifier.widthIn(max = 700.dp).fillMaxWidth())
                }
            }

            // MARK: andere taal (build 59)
            if (versions.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(tvs(10))) {
                    Row(Modifier.padding(horizontal = Theme.edge), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Translate, null, tint = Theme.secondary, modifier = Modifier.size(tvs(16)))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            L("Other language").uppercase(), color = Theme.secondary, fontSize = tsp(14),
                            fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp
                        )
                    }
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = Theme.edge, vertical = if (tv) 8.dp else 0.dp),
                        horizontalArrangement = Arrangement.spacedBy(tvs(10))
                    ) {
                        items(versions, key = { it.id }) { v ->
                            // film: kiezen tussen afspelen en openen; serie: meteen de pagina van die versie
                            LanguageVersionChip(v) { if (v.target is DetailTarget.SeriesT) ScreensNav.detail(v.target) else versionMenu = v }
                        }
                    }
                }
            }

            // build 191: tabbalk (rode lijn boven het gekozen tabblad, grijs de rest) met daaronder de inhoud
            if (tabs191.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Theme.edge),
                    horizontalArrangement = Arrangement.spacedBy(tvs(26))
                ) {
                    tabs191.forEach { tb ->
                        val label = when (tb) {
                            Build191Logic.Tab.EPISODES -> L("Episodes")
                            Build191Logic.Tab.MORE_LIKE_THIS -> L("More like this")
                            Build191Logic.Tab.TRAILERS -> L("Trailers")
                        }
                        DetailTab(label, tb == tab191) { tabWanted191 = tb }
                    }
                }
            }

            // MARK: afleveringen
            if (target is DetailTarget.SeriesT && tab191 == Build191Logic.Tab.EPISODES) {
                val s = target.series
                val list = episodes.filter { it.season == season }
                Column(verticalArrangement = Arrangement.spacedBy(tvs(14))) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = Theme.edge),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (seasons.isNotEmpty()) {
                            Box {
                                Row(
                                    Modifier.focusLift(1.05f) { if (seasons.size > 1) seasonMenu = true }
                                        .clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.12f))
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(L("Season %d", season), color = Color.White, fontSize = tsp(16), fontWeight = FontWeight.SemiBold)
                                    if (seasons.size > 1) {
                                        Spacer(Modifier.width(6.dp))
                                        Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(tvs(18)))
                                    }
                                }
                                S2Menu(seasonMenu, seasons.map { n ->
                                    val sh = watchedShare(episodes.filter { it.season == n }, imdb, progress)
                                    n to (if (sh > 0) L("Season %d · %d%% watched", n, percent(sh)) else L("Season %d", n))
                                }, season,
                                    onDismiss = { seasonMenu = false },
                                    onSelect = { seasonMenu = false; if (season != it) { season = it; focusEpisode = tv } })
                            }
                        }
                        // trailer van dit seizoen (build 53)
                        if (seasonTrailer != null) {
                            Spacer(Modifier.width(tvs(10)))
                            Row(
                                Modifier.focusLift(1.05f) { heroTrailer.stop(); trailerOpen = seasonTrailer }
                                    .clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.12f))
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Theaters, null, tint = Color.White, modifier = Modifier.size(tvs(16)))
                                Spacer(Modifier.width(6.dp))
                                Text(L("Trailer season %d", season), color = Color.White, fontSize = tsp(14), fontWeight = FontWeight.Medium, maxLines = 1)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        if (list.isNotEmpty()) {
                            val all = list.all { Playables.isWatched(it, imdb) }
                            Box(
                                Modifier.size(tvs(40)).focusLift(1.12f) {
                                    Watched.setWatched(list, imdb, !all, episodes)
                                    (target as? DetailTarget.SeriesT)?.series?.let { ser ->
                                        AppScope.launch { screensSafe { Trakt.setWatched(ser, imdb, list, !all) } }
                                    }
                                },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (all) Icons.Default.CheckCircle else Icons.Default.Check, null,
                                    tint = if (all) Theme.red else Theme.secondary, modifier = Modifier.size(tvs(24))
                                )
                            }
                        }
                    }
                    if (loading && episodes.isEmpty()) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(tvs(24)))
                        }
                    }
                    // voortgang van dit seizoen (build 53)
                    if (list.isNotEmpty()) {
                        val sh = watchedShare(list, imdb, progress)
                        if (sh > 0) {
                            Column(
                                Modifier.padding(horizontal = Theme.edge).widthIn(max = if (tv) 460.dp else 700.dp).fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(L("Season %d · %d%% watched", season, percent(sh)), color = Theme.secondary, fontSize = tsp(13))
                                ThinProgress(sh)
                            }
                        }
                    }
                    val rowState = rememberLazyListState()
                    // huidige aflevering van dit seizoen: de volgende aflevering (half gekeken of eerste onbekeken)
                    val currentID: String? = remember(season, episodes, progress) {
                        val n = nextEpisode
                        if (n != null && n.season == season) n.id else currentEpisodeOf(list, imdb)?.id
                    }
                    val currentFocus = remember { FocusRequester() }
                    var currentAttached by remember { mutableStateOf(false) }
                    // bij openen en bij een ander seizoen: de lijst naar de huidige aflevering laten scrollen
                    var firstScroll by remember(target.id) { mutableStateOf(true) }
                    LaunchedEffect(season, episodes.size) {
                        if (list.isEmpty()) return@LaunchedEffect
                        val idx = list.indexOfFirst { it.id == currentID }.coerceAtLeast(0)
                        if (firstScroll) rowState.scrollToItem(idx) else rowState.animateScrollToItem(idx)
                        firstScroll = false
                        // TV: na het kiezen van een seizoen meteen de focus op die aflevering
                        if (focusEpisode) {
                            focusEpisode = false
                            repeat(6) {
                                delay(80)
                                if (currentAttached && runCatching { currentFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                            }
                        }
                    }
                    LazyRow(
                        state = rowState,
                        contentPadding = PaddingValues(horizontal = Theme.edge, vertical = if (tv) 14.dp else 0.dp),
                        horizontalArrangement = Arrangement.spacedBy(tvs(14)),
                        // TV: met de afstandsbediening naar de afleveringen → focus op de huidige aflevering (niet de eerste)
                        modifier = Modifier.episodeEntry(tv) { if (currentAttached) currentFocus else null }
                    ) {
                        items(list) { e ->
                            val info = seasonInfo["${e.season}-${e.number}"] ?: details?.episodeInfo?.get("${e.season}-${e.number}")
                            val isCurrent = e.id == currentID
                            if (isCurrent) {
                                DisposableEffect(e.id) {
                                    currentAttached = true
                                    onDispose { currentAttached = false }
                                }
                            }
                            EpisodeCard(
                                modifier = if (isCurrent) Modifier.focusRequester(currentFocus) else Modifier,
                                e = e, s = s, info = info, imdb = imdb,
                                fallbackStill = screensText(details?.backdrop) ?: s.poster,
                                onPlay = { ageChecked { AppState.play(Playables.episode(e, s, imdb)) } },
                                onDownload = Playables.episode(e, s, imdb).takeIf { Downloads.canDownload(it) }?.let { item -> { download(item, s.title) } },
                                download = Downloads.find(pid, e.id),
                            )
                        }
                    }
                }
            }

            // MARK: aanbevelingen
            // profielen met een leeftijdsgrens zien alleen aanbevelingen die (gefilterd) in hun lijst staan
            // build 191: onder het tabblad Meer zoals dit (recs191 staat bovenaan, met dezelfde leeftijdsfilter)
            val recs = recs191
            if (recs.isNotEmpty() && tab191 == Build191Logic.Tab.MORE_LIKE_THIS) {
                Column(verticalArrangement = Arrangement.spacedBy(tvs(12))) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = Theme.edge, vertical = if (tv) 14.dp else 0.dp),
                        horizontalArrangement = Arrangement.spacedBy(tvs(14))
                    ) {
                        items(recs) { r ->
                            val match = recMatches[r.id]
                            Column(
                                Modifier.width(tvs(140)).focusLift {
                                    if (match != null) {
                                        ScreensNav.detail(match)
                                    } else {
                                        // niet in deze afspeellijst: toon de details (afspelen zoekt in andere bronnen / Stremio)
                                        val y = r.year
                                        val poster = screensText(r.poster)
                                        val rid = screensText(r.imdbID)
                                        // build 141: met een TMDB-id als TMDB-titel ("tmdb-movie-123"): die kan in Mijn lijst
                                        // (ook op je andere apparaten); een "rec-…"-id kon niemand terugvinden
                                        val tid = r.tmdbID?.takeIf { it > 0 }
                                        val id = Build141Feedback.MyListKeys.storableID("rec-${r.id}", tid, r.isShow) ?: "rec-${r.id}"
                                        if (tid != null) DiscoverKnown.remember(listOf(DiscoverItem(tmdbID = tid, isShow = r.isShow, title = r.title,
                                            posterPath = r.posterPath, kind = DiscoverKind.STREAMING.key)))
                                        ScreensNav.detail(
                                            if (r.isShow) DetailTarget.SeriesT(Series(id = id, title = r.title, year = y, poster = poster, imdbID = rid))
                                            else DetailTarget.MovieT(Movie(id = id, title = r.title, year = y, poster = poster, imdbID = rid))
                                        )
                                    }
                                },
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(tvs(12)))) {
                                    RemoteImage(screensText(r.poster), Modifier.matchParentSize(), placeholder = r.title)
                                    if (match != null) {
                                        Box(
                                            Modifier.align(Alignment.TopEnd).padding(6.dp).size(tvs(22)).clip(CircleShape).background(Theme.red),
                                            contentAlignment = Alignment.Center
                                        ) { Icon(Icons.Default.PlayArrow, null, tint = Theme.onAccent, modifier = Modifier.size(tvs(15))) }
                                    }
                                }
                                Text(r.title, color = Color.White, fontSize = tsp(14), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            // build 191: tabblad Trailers: die van de titel en die van het gekozen seizoen
            if (tab191 == Build191Logic.Tab.TRAILERS) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Theme.edge, vertical = if (tv) 14.dp else 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(tvs(14))
                ) {
                    items(trailers191) { t ->
                        val isSeasonTrailer = t == seasonTrailer && t != trailerID
                        Row(
                            Modifier.focusLift(1.05f) { heroTrailer.stop(); trailerOpen = t }
                                .clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.12f))
                                .padding(horizontal = 18.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Theaters, null, tint = Color.White, modifier = Modifier.size(tvs(18)))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (isSeasonTrailer) L("Trailer season %d", season) else L("Trailer"),
                                color = Color.White, fontSize = tsp(15), fontWeight = FontWeight.Medium, maxLines = 1
                            )
                        }
                    }
                }
            }

            // MARK: beoordelingen
            // build 179: één rij met korte namen + score (IMDb · RT · Publiek · MC · Letterboxd · Trakt · TMDB)
            if (ratings179.isNotEmpty()) {
                Text(ratings179.joinToString("  ·  ") { Ratings179.label(it.source) + " " + it.text }, color = Color.White,
                    fontSize = tsp(14), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = Theme.edge))
            }
            val ratings = if (ratings179.isNotEmpty()) emptyList() else details?.ratings.orEmpty()
            if (ratings.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(tvs(12))) {
                    DetailSectionTitle(L("Ratings"))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Theme.edge),
                        horizontalArrangement = Arrangement.spacedBy(tvs(40))
                    ) {
                        ratings.forEach { r ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    when (r.source) {
                                        "IMDb" -> Text(
                                            "IMDb", color = Color.Black, fontSize = tsp(9), fontWeight = FontWeight.Black,
                                            modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(Theme.imdb).padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                        "Trakt" -> Icon(Icons.Default.CheckBox, null, tint = Color(0xFFED1C24), modifier = Modifier.size(tvs(16)))
                                        else -> Icon(Icons.Default.Star, null, tint = Theme.secondary, modifier = Modifier.size(tvs(13)))
                                    }
                                    Text(r.source, color = Theme.secondary, fontSize = tsp(16))
                                }
                                Text(r.value.toString(), color = Color.White, fontSize = tsp(22), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // MARK: cast
            val cast = details?.cast.orEmpty()
            if (cast.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(tvs(12))) {
                    DetailSectionTitle(L("Cast"))
                    LazyHorizontalGrid(
                        rows = GridCells.Fixed(2),
                        contentPadding = PaddingValues(horizontal = Theme.edge),
                        horizontalArrangement = Arrangement.spacedBy(tvs(26)),
                        verticalArrangement = Arrangement.spacedBy(tvs(14)),
                        modifier = Modifier.fillMaxWidth().height(tvs(84) * 2 + tvs(14))
                    ) {
                        items(cast) { c ->
                            // tik/OK → personenpagina (met TMDB; zonder id zoekt die op naam)
                            val canOpen = People.isAvailable
                            Row(
                                (if (canOpen) Modifier.focusLift(1.05f) { ScreensNav.person(PersonRef(c.name, c.tmdbID, screensText(c.photo))) } else Modifier)
                                    .padding(4.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    Modifier.size(tvs(70)).clip(CircleShape).background(Theme.elevated),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val photo = screensText(c.photo)
                                    if (photo != null) RemoteImage(photo, Modifier.matchParentSize())
                                    else Text(
                                        c.name.split(" ").filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1) },
                                        color = Theme.secondary, fontSize = tsp(18), fontWeight = FontWeight.Bold
                                    )
                                }
                                Column(Modifier.width(tvs(170))) {
                                    Text(c.name, color = Color.White, fontSize = tsp(16), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    screensText(c.role)?.let {
                                        Text(it, color = Theme.secondary, fontSize = tsp(14), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.navigationBarsPadding().height(40.dp))
        }
        }
        }

        // sluitknop (telefoon)
        if (!tv) {
            Box(
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 8.dp, end = 16.dp)
                    .size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))
                    .focusLift { onClose() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Close, L("Close"), tint = Color.White, modifier = Modifier.size(20.dp)) }
        }

        toast?.let {
            Text(
                it, color = Color.White, fontSize = tsp(13), fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 40.dp)
                    .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.8f))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }

        if (showSources) {
            SourcesPanel(
                sources = sources,
                currentPlaylistID = pid,
                onClose = { showSources = false },
                onPick = { h ->
                    showSources = false
                    // build 129: op zijn eigen server openen (afspelen gaat daar ook), zonder de actieve server te wisselen
                    val active = AllServers.isActive(h.playlist.id)
                    when (val x = h.item) {
                        is Movie -> ScreensNav.detail(DetailTarget.MovieT(if (active) x.copy(sourceID = null) else x.copy(sourceID = h.playlist.id)))
                        is Series -> ScreensNav.detail(DetailTarget.SeriesT(if (active) x.copy(sourceID = null) else x.copy(sourceID = h.playlist.id)))
                        else -> if (active) openLocal(h.itemID) else Unit
                    }
                }
            )
        }

        // andere taalversie (build 59): afspelen (eigen hervatplek per versie) of de pagina openen
        versionMenu?.let { v ->
            val vt = v.target
            S2ActionDialog(listOfNotNull(v.info?.longName, ContentLanguage.displayTitle(vt.title)).joinToString(" · "), listOf(
                S2Action(if ((Store.progress[vt.id]?.fraction ?: 0.0) > 0.02) L("Resume") else L("Play"), Icons.Default.PlayArrow, primary = true) {
                    (vt as? DetailTarget.MovieT)?.let { m -> ageChecked { AppState.play(Playables.movie(m.movie, Trakt.imdb(m.movie) ?: imdb)) } }
                },
                S2Action(L("Open"), Icons.Default.Translate) { ScreensNav.detail(vt) },
            ), onDismiss = { versionMenu = null })
        }
        // build 174: trailer in de app; Meer en de keuze onder Bronnen (telefoon)
        trailerOpen?.let { t -> TrailerPlayerDialog(t) { trailerOpen = null } }
        if (moreMenu) {
            S2ActionDialog(title, listOfNotNull(
                if (!target.id.startsWith("rec-") && !DiscoverItem.isExternal(target.id)) S2Action(L("Add to collection…"), Icons.AutoMirrored.Filled.PlaylistAdd) { addToCollection = true } else null,
                // build 175: downloaden staat er altijd (serie: keuze; film: uitgegrijsd met de reden als het niet kan)
                seriesDownloadAction(),
            ) + movieDownloadActions() + listOfNotNull(
                S2Action(L("Share"), Icons.Default.Share) { share() },
                // build 179: duim omhoog/omlaag (nog eens = weghalen)
                if (canThumb179) S2Action(if (ForYou179.thumb(target) == 1) L("Remove thumbs up") else L("I like this"), Icons.Default.ThumbUp) { ForYou179.toggle(target, 1) } else null,
                if (canThumb179) S2Action(if (ForYou179.thumb(target) == -1) L("Remove thumbs down") else L("Not for me"), Icons.Default.ThumbDown) { ForYou179.toggle(target, -1) } else null,
                // build 176: vergrendelen met de pincode van het hoofdprofiel
                if (!target.id.startsWith("rec-") && !DiscoverItem.isExternal(target.id)) titleLockAction(target, pid) else null,
                if (Store.activeProfile?.kids != true && pid != null) S2Action(L("Hide for this profile"), Icons.Default.VisibilityOff, destructive = true) { blockConfirm = true } else null,
            ), onDismiss = { moreMenu = false })
        }
        // build 175: downloaden bij een serie: seizoen, volgende 3, hele serie, en de downloads verwijderen
        if (seriesDownloads) {
            val model = seriesDownloadModel()
            if (model == null) seriesDownloads = false else {
                val (_, eps, m) = model
                fun count(n: Int) = if (n == 0) L("Downloaded") else L("%d episodes", n)
                S2ActionDialog(L("Download"), listOfNotNull(
                    if (m.complete) S2Action(L("Downloaded") + "  ✓", Icons.Default.DownloadDone, enabled = false) {} else null,
                    S2Action(L("Download this season"), Icons.Default.Download, enabled = m.season > 0, subtitle = count(m.season)) { downloadScope(Build175Logic.Scope.SEASON) },
                    S2Action(L("Next 3 episodes"), Icons.Default.SkipNext, enabled = m.nextThree > 0, subtitle = count(m.nextThree)) { downloadScope(Build175Logic.Scope.NEXT_THREE) },
                    S2Action(L("Entire series"), Icons.Default.VideoLibrary, enabled = m.all > 0, subtitle = count(m.all)) { downloadScope(Build175Logic.Scope.ALL) },
                    if (m.downloaded > 0) S2Action(L("Delete"), Icons.Default.Delete, destructive = true) {
                        downloadConfirm = DownloadConfirm175(delete = eps.filter { it.state != Build175Logic.State.NONE }.map { it.id })
                    } else null,
                ), onDismiss = { seriesDownloads = false }, message = title)
            }
        }
        downloadConfirm?.let { c ->
            if (c.delete.isEmpty()) {
                S2ActionDialog(L("Download %d episodes?", c.download.size), listOf(
                    S2Action(L("Download"), Icons.Default.Download, primary = true) { downloadMany(c.download) },
                ), onDismiss = { downloadConfirm = null },
                    message = if (Downloads.wifiOnly) L("Downloads only start over Wi-Fi.") else L("Downloads also use mobile data."))
            } else {
                S2ActionDialog(L("Delete %d downloads?", c.delete.size), listOf(
                    S2Action(L("Delete"), Icons.Default.Delete, destructive = true) {
                        c.delete.forEach { id -> Downloads.find(pid, id)?.let { Downloads.delete(it.key) } }
                    },
                ), onDismiss = { downloadConfirm = null }, message = L("The episodes stay available to stream."))
            }
        }
        if (sourcesChoice) {
            S2ActionDialog(L("Sources"), listOfNotNull(
                if (!prefetchedSources.isNullOrEmpty()) S2Action(L("Other sources"), BingrIcons.otherSources) { sources = prefetchedSources ?: emptyList(); showSources = true } else null,
            ) + versions.map { v ->
                S2Action(listOfNotNull(v.info?.longName, ContentLanguage.displayTitle(v.target.title)).joinToString(" · "), Icons.Default.Translate) {
                    if (v.target is DetailTarget.SeriesT) ScreensNav.detail(v.target) else versionMenu = v
                }
            }, onDismiss = { sourcesChoice = false })
        }
        if (addToCollection) {
            AddToCollectionDialog(Collections.ref(target), target.title, target.poster) { addToCollection = false }
        }
    if (blockConfirm) {
        S2ActionDialog(L("Hide for this profile"), listOf(
            S2Action(L("Hide for this profile"), Icons.Default.VisibilityOff, destructive = true) {
                val sid = pid
                if (sid != null) { justBlocked = true; Store.setTitleBlocked(sid, target.isSeries, target.id, true); onClose() }
            },
        ), onDismiss = { blockConfirm = false },
            message = L("This title disappears from rows, search, Top 10, Continue watching and My List of this profile. Undo under Settings > Profile > Blocked titles."))
    }

        if (ageGate) {
            PinPad(
                title = L("This title is above the age limit of this profile. Enter PIN"),
                onDismiss = { ageGate = false; afterPIN = null },
                onSubmit = { pin ->
                    // alleen deze titel toestaan, niet de hele bibliotheek ontgrendelen
                    if (ParentalControl.check(pin)) {
                        val run = afterPIN
                        afterPIN = null
                        ageGate = false
                        scope.launch { delay(300); run?.invoke() }
                        true
                    } else false
                }
            )
        }
    }
}

/**
 * Volgende aflevering: de laatst bekeken aflevering met voortgang (op tijd, niet zomaar de eerste in de lijst),
 * anders de eerste onbekeken, anders de eerste.
 */
private fun nextEpisodeOf(episodes: List<Episode>, imdb: String?): Episode? {
    val progress = Store.progress
    val resumed = episodes.mapNotNull { e ->
        progress[e.id]?.takeIf { it.fraction > 0.02 && it.fraction < 0.95 }?.let { e to it.updated }
    }.maxByOrNull { it.second }?.first
    return resumed ?: episodes.firstOrNull { !Playables.isWatched(it, imdb) } ?: episodes.firstOrNull()
}

/**
 * Huidige aflevering binnen één seizoen: half gekeken (laatst bijgewerkt), anders de eerste onbekeken.
 * null = alles bekeken (dan begint de lijst gewoon vooraan).
 */
private fun currentEpisodeOf(list: List<Episode>, imdb: String?): Episode? {
    val progress = Store.progress
    val resumed = list.mapNotNull { e ->
        progress[e.id]?.takeIf { it.fraction > 0.02 && it.fraction < 0.95 }?.let { e to it.updated }
    }.maxByOrNull { it.second }?.first
    return resumed ?: list.firstOrNull { !Playables.isWatched(it, imdb) }
}

/**
 * Kijkvoortgang 0…1 over [list] (build 53): een bekeken aflevering telt als 1, een half gekeken aflevering
 * als haar fractie (vanaf 95% als bekeken), gedeeld door het aantal bekende afleveringen.
 */
private fun watchedShare(list: List<Episode>, imdb: String?, progress: Map<String, ProgressEntry>): Double {
    if (list.isEmpty()) return 0.0
    var sum = 0.0
    for (e in list) {
        sum += if (Playables.isWatched(e, imdb)) 1.0
        else (progress[e.id]?.fraction ?: 0.0).let { if (it >= 0.95) 1.0 else it.coerceIn(0.0, 1.0) }
    }
    return (sum / list.size).coerceIn(0.0, 1.0)
}

/** Percentage voor weergave: nooit 0% als je begonnen bent en nooit 100% als er nog iets openstaat. */
private fun percent(share: Double): Int {
    val p = Math.round(share * 100).toInt()
    return when {
        share >= 0.9999 -> 100
        p >= 100 -> 99
        p <= 0 && share > 0 -> 1
        else -> p
    }
}

/** Dunne voortgangsbalk (serie / seizoen). */
@Composable
private fun ThinProgress(share: Double) {
    Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.2f))) {
        Box(Modifier.fillMaxWidth(share.toFloat().takeIf { !it.isNaN() }?.coerceIn(0f, 1f) ?: 0f).fillMaxHeight().clip(RoundedCornerShape(50)).background(Theme.red))
    }
}

/** TV: focus die de afleveringenrij binnenkomt gaat naar [target] (als die er is), anders zoals normaal. */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.episodeEntry(tv: Boolean, target: () -> FocusRequester?): Modifier =
    if (!tv) this else this.focusProperties { enter = { target() ?: FocusRequester.Default } }.focusGroup()

/** Build 191: tabblad op de detailpagina: rode lijn erboven als het gekozen is, anders grijs; op TV wit bij focus. */
@Composable
private fun DetailTab(text: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val lit = AppState.isTV && focused
    Column(
        Modifier.width(IntrinsicSize.Max).onFocusChanged { focused = it.isFocused }
            .focusLift(1.05f, onClick = onClick)
            .semantics { role = Role.Tab; this.selected = selected }
            .then(if (lit) Modifier.border(2.dp, Color.White, RoundedCornerShape(6.dp)) else Modifier)
            .padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(if (selected) Theme.red else Color.Transparent))
        Spacer(Modifier.height(10.dp))
        Text(
            text, color = if (selected || lit) Color.White else Theme.secondary, fontSize = tsp(16),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1
        )
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun DetailSectionTitle(t: String) {
    Text(
        t.uppercase(), color = Theme.secondary, fontSize = tsp(14), fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp,
        modifier = Modifier.padding(horizontal = Theme.edge)
    )
}

@Composable
private fun CreditLine(k: String, v: String) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = Theme.secondary)) { append("$k: ") }
            withStyle(SpanStyle(color = Color.White.copy(alpha = 0.85f))) { append(v) }
        },
        fontSize = tsp(13), maxLines = 2, overflow = TextOverflow.Ellipsis
    )
}

/** Build 174: knop in de rij onder Afspelen op de telefoon: icoon met de tekst eronder, even breed als de andere. */
@Composable
private fun DetailAction(text: String, label: String, modifier: Modifier, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Column(
        modifier.focusLift(1.06f, onClick = onClick).semantics { contentDescription = label; role = Role.Button }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.height(26.dp), contentAlignment = Alignment.Center) { icon() }
        Text(text, color = Theme.secondary, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Ronde knop; op TV wit bij focus (zoals Netflix). */
@Composable
private fun DetailCircleButton(icon: ImageVector, tint: Color = Color.White, onClick: () -> Unit) {
    DetailCircleBox(null, onClick) { lit ->
        Icon(icon, null, tint = if (lit) Color.Black else tint, modifier = Modifier.size(tvs(22)))
    }
}

/** Ronde knop met eigen inhoud (`lit` = op TV gefocust, dus witte knop); `label` = toegankelijkheidslabel. */
@Composable
private fun DetailCircleBox(label: String?, onClick: () -> Unit, content: @Composable (lit: Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val lit = AppState.isTV && focused
    Box(
        Modifier.onFocusChanged { focused = it.isFocused }
            .focusLift(1.1f, onClick = onClick)
            .then(if (label != null) Modifier.semantics { contentDescription = label; role = Role.Button } else Modifier)
            .size(tvs(50)).clip(CircleShape)
            .background(if (lit) Color.White else Color.White.copy(alpha = 0.12f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        content(lit)
    }
}

@Composable
private fun DetailCapsule(text: String, modifier: Modifier, icon: ImageVector? = null, busy: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val lit = AppState.isTV && focused
    Box(
        modifier.onFocusChanged { focused = it.isFocused }
            .focusLift(1.05f, onClick = onClick)
            .height(tvs(50)).clip(RoundedCornerShape(50))
            .background(if (lit) Color.White else Color.White.copy(alpha = 0.12f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(50)),
        contentAlignment = Alignment.Center
    ) {
        if (busy) CircularProgressIndicator(color = if (lit) Color.Black else Color.White, strokeWidth = 2.dp, modifier = Modifier.size(tvs(18)))
        else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // icoon (build 58): overal hetzelfde voor "Andere bronnen" ([BingrIcons.otherSources])
            if (icon != null) Icon(icon, null, tint = if (lit) Color.Black else Color.White, modifier = Modifier.size(tvs(18)))
            Text(text, color = if (lit) Color.Black else Color.White, fontSize = tsp(15), fontWeight = FontWeight.Medium, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EpisodeCard(
    modifier: Modifier = Modifier,
    e: Episode,
    s: Series,
    info: TitleDetails.EpisodeInfo?,
    imdb: String?,
    fallbackStill: String?,
    onPlay: () -> Unit,
    onDownload: (() -> Unit)? = null,
    download: DownloadItem? = null,
) {
    val tv = AppState.isTV
    val w = if (tv) 300.dp else 290.dp
    val watched = Playables.isWatched(e, imdb)
    val progress = Store.progress[e.id]?.fraction
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            modifier.width(w).focusLift(1.05f, onLongClick = { menu = true }, onClick = onPlay)
                .clip(RoundedCornerShape(tvs(14))).background(Color.White.copy(alpha = 0.06f))
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(tvs(12)))) {
                RemoteImage(screensText(info?.still) ?: e.still ?: fallbackStill, Modifier.matchParentSize())
                if (progress != null && progress > 0) {
                    Box(
                        Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                            .fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.3f))
                    ) {
                        Box(Modifier.fillMaxWidth(progress.toFloat().coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(50)).background(Color.White))
                    }
                }
                // downloaden per aflevering (build 52, telefoon/tablet)
                if (onDownload != null) {
                    Box(
                        Modifier.align(Alignment.TopStart).padding(6.dp).size(34.dp).clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f)).focusLift(1.1f) { onDownload() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            when { download?.isDone == true -> Icons.Default.DownloadDone; download != null && download.status != Downloads.FAILED -> Icons.Default.Downloading; else -> Icons.Default.Download },
                            L("Download"), tint = if (download?.isDone == true) Theme.red else Color.White, modifier = Modifier.size(18.dp)
                        )
                    }
                }
                if (watched) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(8.dp).size(tvs(22)).clip(CircleShape).background(Theme.red),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.Check, null, tint = Theme.onAccent, modifier = Modifier.size(tvs(14))) }
                }
            }
            Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(String.format(java.util.Locale.US, "S%02dE%02d", e.season, e.number), color = Theme.secondary, fontSize = tsp(13))
                Text(
                    screensText(info?.title) ?: Playables.cleanEpisodeTitle(e),
                    color = Color.White, fontSize = tsp(16), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                val o = screensText(info?.overview) ?: e.plot
                if (!o.isNullOrBlank()) {
                    Text(o, color = Theme.secondary, fontSize = tsp(13), maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                val air = screensDate(info?.airDate)
                val minutes = screensInt(info?.runtime) ?: e.durationSeconds?.let { it / 60 }
                if (air != null || (minutes != null && minutes > 0)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        air?.let { Text(it, color = Color.White.copy(alpha = 0.8f), fontSize = tsp(12)) }
                        if (minutes != null && minutes > 0) Text(L("%d min", minutes), color = Color.White.copy(alpha = 0.8f), fontSize = tsp(12))
                    }
                }
            }
        }
        if (menu) {
            S2ActionDialog(Playables.cleanEpisodeTitle(e), listOf(
                S2Action(L("Play"), Icons.Default.PlayArrow) { onPlay() },
                *(if (onDownload != null && download == null) arrayOf(S2Action(L("Download"), Icons.Default.Download) { onDownload() }) else emptyArray()),
                S2Action(if (watched) L("Mark as unwatched") else L("Mark as watched"),
                    if (watched) Icons.Default.VisibilityOff else Icons.Default.CheckCircle) {
                    Watched.setWatched(listOf(e), imdb, !watched)
                    AppScope.launch { screensSafe { Trakt.setWatched(Playables.episode(e, s, imdb), !watched) } }
                },
            ), onDismiss = { menu = false })
        }
    }
}

/** "Andere bronnen": dezelfde titel in andere groepen of afspeellijsten. */
@Composable
private fun SourcesPanel(
    sources: List<OtherHit>,
    currentPlaylistID: String?,
    onClose: () -> Unit,
    onPick: (OtherHit) -> Unit,
) {
    val first = rememberInitialFocus()
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).pointerInput(Unit) { detectTapGestures { onClose() } },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.padding(Theme.edge).widthIn(max = 560.dp).fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)).background(Theme.elevated)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(vertical = 14.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(BingrIcons.otherSources, null, tint = Color.White, modifier = Modifier.padding(end = 8.dp).size(tvs(20)))
                Text(L("Other sources"), color = Color.White, fontSize = tsp(18), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    L("Close"), color = Theme.red, fontSize = tsp(15), fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.then(if (sources.isEmpty()) Modifier.focusRequester(first) else Modifier)
                        .focusLift(1.08f) { onClose() }.padding(6.dp)
                )
            }
            if (sources.isEmpty()) {
                Text(
                    L("This title isn't available in your other groups or playlists."),
                    color = Theme.secondary, fontSize = tsp(14), modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
                )
            }
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                sources.forEachIndexed { i, h ->
                    Row(
                        Modifier.fillMaxWidth()
                            .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                            .focusLift(1.03f) { onPick(h) }
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        AvatarCircle(h.playlist, tvs(34))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (h.playlist.id == currentPlaylistID) "${h.playlist.name} · ${h.title}" else h.playlist.name,
                                color = Color.White, fontSize = tsp(15), maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(h.subtitle, color = Theme.secondary, fontSize = tsp(12), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Default.PlayCircle, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(tvs(20)))
                    }
                }
            }
        }
    }
}

/** Andere taalversie (build 59): taallabel en titel; op TV wit bij focus. */
@Composable
private fun LanguageVersionChip(v: ContentLanguage.Version, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val lit = AppState.isTV && focused
    Row(
        Modifier.onFocusChanged { focused = it.isFocused }
            .focusLift(1.05f, onClick = onClick)
            .clip(RoundedCornerShape(50))
            .background(if (lit) Color.White else Color.White.copy(alpha = 0.12f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val fg = if (lit) Color.Black else Color.White
        Icon(Icons.Default.Translate, null, tint = fg, modifier = Modifier.size(tvs(16)))
        Text(v.info?.label ?: "?", color = fg, fontSize = tsp(14), fontWeight = FontWeight.Bold, maxLines = 1)
        Text(ContentLanguage.displayTitle(v.target.title), color = fg.copy(alpha = 0.75f), fontSize = tsp(13), maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = tvs(220)))
    }
}

/** Build 175: bevestiging bij Hele serie downloaden ([download]) of de downloads van de serie verwijderen ([delete], id's) */
private data class DownloadConfirm175(val download: List<PlayableItem> = emptyList(), val delete: List<String> = emptyList())
