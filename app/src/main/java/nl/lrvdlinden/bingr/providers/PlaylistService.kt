package nl.lrvdlinden.bingr

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Laadt, cachet en presenteert de inhoud van de gekozen afspeellijst (= PlaylistService.swift).
 * [library] is de inhoud na jouw aanpassingen (hernoemd, verborgen, volgorde, samengevoegd),
 * zonder groepen op kinderslot en gefilterd op de leeftijdsgrens van het profiel.
 *
 * ## Voor het toevoeg-scherm (AddPlaylistScreen)
 *
 * ```
 * // Plex koppelen met een code (plex.tv/link):
 * val pin: PlexPin = PlexAuth.startPin()            // toon pin.code + "Go to plex.tv/link and enter this code:"
 * val token: String = PlexAuth.waitForToken(pin)    // wacht max ±5 min (gooit ServiceException bij verlopen)
 * //   of zelf pollen: PlexAuth.poll(pin): String?  (null = nog niet bevestigd)
 *
 * // Stremio-account (optioneel): wordt in validate() gedaan met p.username (= e-mail) + password,
 * // maar kan ook los: StremioAuth.login(email, password): String  (authKey)
 *
 * // Opslaan ("Save"): eerst testen en geheimen bewaren, dan pas Store.upsert:
 * val err: String? = Playlists.test(p, password)    // null = gelukt
 * //   of: val saved: PlaylistSource = Playlists.validate(p, password)  (gooit; Plex vult serverURL in)
 * Store.upsert(saved)
 * ```
 * Wat `password` betekent per soort:
 * - XTREAM: wachtwoord → bewaard in Store.secret(p.passwordKey)
 * - JELLYFIN/EMBY: wachtwoord → alleen gebruikt om in te loggen; de sessie (token) komt in
 *   Store.secret(Playlists.sessionKey(p)), Store.secret(p.passwordKey) wordt gewist
 * - PLEX: het Plex-token uit [PlexAuth] (leeg = bestaand token houden bij bewerken)
 * - STREMIO: Stremio-wachtwoord (p.username = e-mail); leeg = geen account/bestaande authKey houden
 * - M3U/STALKER: niet gebruikt
 * Bij succes wordt de cache van die afspeellijst gewist (zoals op iOS).
 *
 * Verwijderen: `Playlists.forget(p)` (Store.delete + cache + sessie wissen).
 * Bewerken: wachtwoord vooraf invullen met `Store.secret(p.passwordKey)` (niet voor Plex/Stremio).
 */
@OptIn(FlowPreview::class)
object Playlists {
    var source by mutableStateOf<PlaylistSource?>(null); private set
    // build 148: referentiële gelijkheid. Met de standaard (structureel) vergeleek Compose bij elke nieuwe bibliotheek de
    // oude en de nieuwe lijst item voor item óp de hoofdthread (37.000 films, 12.000 zenders, 9.000 series: honderden ms
    // bij een catalogus die niet veranderde). Een nieuwe lijst is altijd een nieuw object.
    var library by mutableStateOf(Library(), referentialEqualityPolicy()); private set
    var rawLibrary by mutableStateOf(Library(), referentialEqualityPolicy()); private set
    var isLoading by mutableStateOf(false); private set
    var loadingText by mutableStateOf(""); private set
    var error by mutableStateOf<String?>(null)
    var refreshingAll by mutableStateOf(false); private set
    /** Bezig met verversen op de achtergrond terwijl de lijst uit de cache al zichtbaar is ("Updating…") */
    var updating by mutableStateOf(false); private set
    /** Build 130: voortgang van "alles vernieuwen" ("Server 2 van 3 vernieuwen…"), null = niet bezig. */
    var refreshStatus by mutableStateOf<String?>(null); private set
    /** Build 130: servers die bij de laatste keer "alles vernieuwen" niet lukten (namen). */
    var refreshFailed by mutableStateOf<List<String>>(emptyList()); private set
    /** Build 130: verhoogt als de cache van een server ververst is (de achtergrond van [AllServers] kijkt dan opnieuw). */
    var catalogVersion by mutableStateOf(0); private set
    var episodeCache by mutableStateOf(mapOf<String, List<Episode>>(), referentialEqualityPolicy()); private set
    /**
     * Bezig met filteren op de achtergrond (grote lijsten). Bij een profiel met leeftijdsgrens is [library]
     * dan leeg (niets ongefilterds tonen): laat geen "lege lijst"-melding zien zolang dit true is.
     */
    var isFiltering by mutableStateOf(false); private set
    private var filterGeneration = 0
    private var filterJob: Job? = null

    private var stalker: StalkerClient? = null
    private var stremioAddons: List<StremioClient.Addon> = emptyList()

    init {
        // aanpassingen of kinderslot gewijzigd → opnieuw toepassen
        AppScope.launch {
            snapshotFlow { Store.customizations to Store.parental }.drop(1).collect { applyCustomizations() }
        }
        // build 140: categorieën aan/uit (account-set): niet bij elke tik de hele catalogus opnieuw filteren, maar even
        // wachten tot er niet meer getikt wordt, en dan op de achtergrond (ook bij kleine lijsten)
        AppScope.launch {
            snapshotFlow { Store.hiddenCategories }.drop(1).debounce(450).collect {
                applyCustomizations(background = true)
                // build 149: een categorie die niet was ingelezen (verborgen tijdens het ophalen) staat weer aan → opnieuw ophalen
                reloadUnhidden()
            }
        }
        // build 171: verborgen zenders en geblokkeerde titels van het profiel
        AppScope.launch {
            snapshotFlow { Store.hiddenChannels to Store.blockedTitles }.drop(1).debounce(300).collect {
                applyCustomizations(background = true)
                AllServers.catalogsRefreshed()
            }
        }
        AppScope.launch {
            snapshotFlow { ParentalControl.unlockedUntil }.drop(1).collect { applyCustomizations() }
        }
        // server aangepast (naam, gegevens) → bijwerken
        AppScope.launch {
            snapshotFlow { Store.playlists }.drop(1).collect { list ->
                val s = source ?: return@collect
                val u = list.firstOrNull { it.id == s.id } ?: return@collect
                if (u != s) source = u
            }
        }
        // ander kijkprofiel gekozen of leeftijdsgrens/kinderprofiel gewijzigd → opnieuw filteren
        AppScope.launch {
            snapshotFlow { Store.activeProfile?.let { "${it.effectiveAgeLimit ?: 0}-${it.strictAgeFilter}-${it.kids}" } ?: "" }
                .drop(1).collect { applyCustomizations() }
        }
        AppScope.launch { runCatching { AgeRatings.preload() } }
        // nieuwe IMDb- of TMDB-classificaties binnen → leeftijdsfilter opnieuw toepassen
        AppScope.launch {
            snapshotFlow { Metadata.version to AgeRatings.version }.drop(1).debounce(3_000).collect {
                if (Store.activeProfile?.effectiveAgeLimit != null) applyCustomizations(quiet = true)
            }
        }
        // profiel met leeftijdsgrens: classificaties van de hele (ongefilterde) lijst op de achtergrond bijwerken (build 53)
        AppScope.launch {
            snapshotFlow { System.identityHashCode(rawLibrary) to (Store.activeProfile?.effectiveAgeLimit != null) }
                .debounce(2_000)
                .collectLatest { (_, limited) ->
                    if (!limited) return@collectLatest
                    try { AgeRatings.warm(rawLibrary) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                }
        }
    }

    // MARK: laden

    suspend fun select(p: PlaylistSource, forceRefresh: Boolean = false) {
        source = p
        Store.activePlaylistID = p.id
        // build 145: een andere server (of opnieuw laden) stopt een lopend bijwerken van de vorige keuze
        autoGen++
        // screenshotmodus (alleen de screenshot-build): de demobibliotheek, zonder cache of netwerk
        if (Screenshots.isActive) { error = null; setRaw(ScreenshotDemo.library); return }
        stalker = if (p.kind == PlaylistKind.STALKER) StalkerClient(p) else null
        stremioAddons = emptyList()
        episodeCache = emptyMap()
        error = null
        if (!forceRefresh) {
            // grote lijsten (tienduizenden titels) op de achtergrond inlezen
            isLoading = true
            val cached = try { withContext(Dispatchers.IO) { readCache(p) } } finally { isLoading = false }
            if (source?.id != p.id) return
            if (cached != null) {
                StartDiagnostics.task("apply") { setRaw(cached) }
                StartDiagnostics.mark("servers_loaded")
                // build 152: intussen (vorige keer afgesloten vóór het opnieuw ophalen, of op een ander apparaat) een
                // overgeslagen categorie weer aangezet → die server(s) nu opnieuw ophalen, ook na een herstart
                reloadUnhidden()
                // build 141: eerst het scherm (de lijst uit de cache, gefilterd en met de rijen), pas daarna het netwerk.
                // Build 145: met een bewaarde lijst wordt bij het openen NIETS vanzelf opgehaald (ook niet na 45 s zoals in
                // build 144, en geen vernieuwtermijn meer). Na het kiezen van een profiel: de vraag "Servers bijwerken?",
                // of meteen bijwerken bij "Altijd" ([afterProfileOpen], instelling "Servers laden bij openen").
                afterProfileOpen(savedList = true)
                return
            }
        }
        setRaw(Library())
        refreshedThisLaunch += p.id.lowercase(java.util.Locale.ROOT)
        // geen bewaarde lijst (eerste keer, nieuwe server zonder cache): deze server nu laden, met de laadmelding.
        // Build 145: geen vraag; bij "Altijd" daarna (als deze klaar is) de andere servers ([afterProfileOpen])
        refresh(p, showSpinner = true)
        StartDiagnostics.mark("servers_loaded")
        if (source?.id == p.id) afterProfileOpen(savedList = false)
    }

    // MARK: build 145: servers laden bij openen (Vragen / Altijd / Nooit, alleen handmatig)

    /** Verhoogt bij elke nieuwe server of elk nieuw profiel: een lopend bijwerken stopt dan (bij de volgende server). */
    @Volatile private var autoGen = 0
    private var updateTask: Job? = null
    /** Na het kiezen van een profiel: de keuze (vraag of bijwerken) nog maken zodra de open server er is. */
    private var decisionPending = false
    /** De vraag kwam al bij deze keer openen (proces). */
    private var askedThisLaunch = false
    /** Build 145: de vraag "Servers bijwerken?" staat open (Main toont hem, niet tijdens het afspelen). */
    var askUpdate by mutableStateOf(false); private set
    /** Build 145: bezig met bijwerken na de vraag of bij "Altijd". */
    var updatingAll by mutableStateOf(false); private set

    /** Milliseconden sinds de start van de app (proces). */
    fun uptimeMs(): Long = android.os.SystemClock.uptimeMillis() - android.os.Process.getStartUptimeMillis()

    /** "Servers laden bij openen" op dit apparaat (SharedPreferences, nooit gesynct). */
    var loadOnOpen: Build145Logic.LoadOnOpen.Mode
        get() = Build145Logic.LoadOnOpen.current(Prefs.string(Build145Logic.LoadOnOpen.PREF))
        set(v) = Prefs.put(Build145Logic.LoadOnOpen.PREF, v.raw)

    /** Build 145: profiel gekozen ("Wie kijkt er?" of het pincodescherm is klaar): lopend bijwerken stopt, keuze volgt. */
    fun profileOpened() {
        autoGen++
        askUpdate = false
        decisionPending = !Screenshots.isActive
    }

    /**
     * Build 145: de open server staat er ([savedList] = uit de bewaarde lijst). Alleen direct na het kiezen van een profiel:
     * vragen, meteen bijwerken ("Altijd") of niets (zie [Build145Logic.LoadOnOpen.decide]).
     */
    fun afterProfileOpen(savedList: Boolean) {
        if (!decisionPending) return
        decisionPending = false
        val kids = Store.activeProfile?.kids == true
        // build 179: een automatische verversing (termijn verstreken) vervangt die keer de vraag "Servers bijwerken?"
        val auto = AutoRefresh179.anyDue()
        when (Build145Logic.LoadOnOpen.decide(loadOnOpen, savedList, kids, askedThisLaunch)) {
            Build145Logic.LoadOnOpen.Action.ASK -> if (auto) AutoRefresh179.check() else { askedThisLaunch = true; askUpdate = true }
            Build145Logic.LoadOnOpen.Action.UPDATE_ALL -> updateAll(onlyNotYetThisLaunch = true)
            Build145Logic.LoadOnOpen.Action.NOTHING -> AutoRefresh179.check()
        }
    }

    /** Build 145: antwoord op de vraag. true = "Nu bijwerken", false = "Later" (niets ophalen). */
    fun answerUpdate(now: Boolean) {
        askUpdate = false
        if (now) updateAll(onlyNotYetThisLaunch = false)
    }

    /**
     * Build 145: alle servers van het account bijwerken, één voor één, de open server eerst, op de achtergrond (de
     * bewaarde lijst blijft staan; label "Bijwerken…" / "%@ vernieuwen (1 van 3)…"). Pas als Home klaar is, nooit tijdens
     * het afspelen of buiten het hoofdscherm (dan wachten), andere servers via [AllServers.heavy] (één catalogus tegelijk
     * in het geheugen). Een andere server of een ander profiel stopt het. [onlyNotYetThisLaunch]: servers die bij deze keer
     * openen al bijgewerkt zijn overslaan ("Altijd" na een profielwissel).
     */
    fun updateAll(onlyNotYetThisLaunch: Boolean) {
        if (Screenshots.isActive) return
        val gen = autoGen
        updateTask?.cancel()
        updateTask = AppScope.launch {
            val L145 = Build145Logic.LoadOnOpen
            fun current() = L145.stillCurrent(gen, autoGen)
            kotlinx.coroutines.delay(1_000)
            // eerst het scherm: de open server klaar (laden en filteren), dan pas het netwerk
            while (isLoading || isFiltering || refreshingAll ||
                !L145.mayRun(AppState.stage == AppState.Stage.MAIN, AppState.playing != null)) {
                if (!current()) return@launch
                kotlinx.coroutines.delay(1_000)
            }
            if (!current()) return@launch
            val all = Store.playlists.toList()
            val skip = if (onlyNotYetThisLaunch) synchronized(refreshedThisLaunch) { refreshedThisLaunch.toSet() } else emptySet()
            val order = L145.order(all.map { it.id }, source?.id, skip)
            if (order.isEmpty()) return@launch
            updatingAll = true
            var any = false
            try {
                servers@ for ((i, id) in order.withIndex()) {
                    val p = all.firstOrNull { it.id == id } ?: continue
                    // niet tijdens het afspelen, buiten het hoofdscherm of terwijl de open server laadt: wachten
                    while (isLoading || refreshingAll ||
                        !L145.mayRun(AppState.stage == AppState.Stage.MAIN, AppState.playing != null)) {
                        if (!current()) break@servers
                        kotlinx.coroutines.delay(2_000)
                    }
                    if (!current()) break@servers
                    if (order.size > 1) refreshStatus = L("Refreshing %@ (%d of %d)…", p.name, i + 1, order.size)
                    val ok = if (p.id == source?.id) withTimeoutOrNull(180_000) { refresh(p, showSpinner = false) } ?: false
                        else AllServers.heavy { refreshOther(p) }
                    refreshedThisLaunch += p.id.lowercase(java.util.Locale.ROOT)
                    if (ok) any = true
                }
            } finally {
                updatingAll = false
                if (!refreshingAll) refreshStatus = null
            }
            if (any) AllServers.catalogsRefreshed()
        }
    }

    /**
     * Build 149: categorieën die bij het ophalen verborgen waren, zijn niet ingelezen ([Library.hiddenGroups]). Staat er
     * daarvan weer één aan (of geldt het verbergen niet meer, bijvoorbeeld bij een kinderprofiel), dan de server op de
     * achtergrond opnieuw ophalen. Andere servers krijgen die categorie bij hun volgende keer bijwerken.
     */
    private var reloadJob: Job? = null
    private var reloadOthersJob: Job? = null
    private fun reloadUnhidden() {
        if (Screenshots.isActive || StressMode.isActive) return
        val s = source
        val raw = rawLibrary
        if (s != null && raw.hiddenGroups.isNotEmpty()) {
            val back = Build152Logic.HiddenCategories.comeBack(raw.hiddenGroups.keys, hiddenPredicate(s))
            if (back && reloadJob?.isActive != true) reloadJob = AppScope.launch { refresh(s, showSpinner = false) }
        }
        // build 152: ook de andere servers (tot build 151 pas bij hun volgende keer bijwerken, soms pas na uren of nooit):
        // welke categorieën daar overgeslagen zijn staat klein in Prefs ([rememberSkipped]), zonder hun catalogus te lezen
        val others = Store.playlists.filter { p -> !p.id.equals(s?.id, ignoreCase = true) }
            .filter { p -> Build152Logic.HiddenCategories.comeBack(skippedOf(p), hiddenPredicate(p)) }
        if (others.isNotEmpty() && reloadOthersJob?.isActive != true) reloadOthersJob = AppScope.launch {
            var any = false
            for (p in others) {
                if (Store.playlists.none { it.id == p.id }) continue
                if (refreshOther(p)) any = true
            }
            if (any) AllServers.catalogsRefreshed()
        }
    }

    /** Build 152: overgeslagen categorieën ("live|naam" e.d.) van de laatst bewaarde catalogus van [p] (klein, in Prefs). */
    private fun skippedKey(p: PlaylistSource) = "skipped152." + p.id.lowercase(java.util.Locale.ROOT)
    private val skippedSer = ListSerializer(String.serializer())
    fun skippedOf(p: PlaylistSource): List<String> =
        Prefs.string(skippedKey(p))?.let { runCatching { Http.json.decodeFromString(skippedSer, it) }.getOrNull() } ?: emptyList()
    private fun rememberSkipped(p: PlaylistSource, lib: Library) {
        val k = skippedKey(p)
        if (lib.hiddenGroups.isEmpty()) { if (Prefs.has(k)) Prefs.remove(k) }
        else Prefs.put(k, Http.json.encodeToString(skippedSer, lib.hiddenGroups.keys.toList()))
    }

    /** Build 149: wat bij het ophalen van [s] niet ingelezen hoeft te worden (verborgen categorie, ook via samenvoegen). */
    fun hiddenPredicate(s: PlaylistSource): (String) -> Boolean {
        val hidden = Store.hiddenCategoryNames(s.id)
        if (hidden.isEmpty()) return { false }
        val merged = Store.customizations[s.id]?.merged ?: emptyMap()
        return { g -> g in hidden || (merged[g]?.let { it in hidden } == true) }
    }

    /** true = gelukt. */
    suspend fun refresh(s: PlaylistSource, showSpinner: Boolean): Boolean {
        if (Screenshots.isActive) return true
        if (showSpinner) isLoading = true
        val background = !showSpinner && source?.id == s.id
        if (background) updating = true
        // build 148: fasen van dit bijwerken (ophalen, inlezen, indexeren, toepassen, bewaren) voor de diagnose
        val rec = UpdateLog.begin(s)
        try {
            val fetched = fetch(s, rec = rec)
            // build 179: wat er de vorige keer stond (de open server uit het geheugen, anders de bewaarde lijst)
            val before = if (source?.id == s.id && !rawLibrary.isEmpty) rawLibrary
                else withContext(Dispatchers.IO) { runCatching { readCatalog(s) }.getOrNull() }
            // build 141: eerst wegschrijven, dan de gids van schijf gebruiken (DiskEpg) in plaats van de hele gids in het
            // geheugen te houden; lukt het schrijven niet, dan zoals vroeger in het geheugen
            writeCacheNow(s, fetched)
            UpdateLog.phase(rec, "save")
            val lib = if (fetched.epg.isEmpty()) fetched
                else withContext(Dispatchers.IO) { CatalogFiles.openEpg(cachePaths(s), Http.json) }?.let { fetched.copy(epg = it) } ?: fetched
            UpdateLog.phase(rec, "index")
            if (source?.id == s.id) {
                StartDiagnostics.task("apply") { setRaw(lib) }
                saveSplashPosters(lib)
                // toepassen = filteren (op de achtergrond) tot de nieuwe lijst er staat
                withTimeoutOrNull(60_000) { while (isFiltering) kotlinx.coroutines.delay(50) }
                UpdateLog.phase(rec, "apply")
            }
            UpdateLog.finish(rec, "ok")
            AutoRefresh179.markFull(s.id)
            Cleanup179.afterRefresh(s, before, fetched)
            return true
        } catch (e: CancellationException) {
            UpdateLog.finish(rec, "cancelled")
            throw e
        } catch (e: Exception) {
            UpdateLog.finish(rec, "failed")
            if (source?.id == s.id && rawLibrary.isEmpty) error = Net.message(e)
            return false
        } finally {
            isLoading = false; loadingText = ""
            if (background) updating = false
        }
    }

    /** Inhoud van server [s] ophalen van het netwerk (zonder cache en zonder de stand van de actieve server te wijzigen). */
    private suspend fun fetch(s: PlaylistSource, quiet: Boolean = false, rec: UpdateLog.Rec? = null): Library {
        // build 141: belastingmodus (alleen de rooktest-build): nepcatalogus in plaats van het netwerk
        if (StressMode.isActive) return StressMode.fetch(s).also { UpdateLog.phase(rec, "parse") }
        // build 147 (diagnose): per soort vastleggen wat er geleverd is of waarom niet (zonder URL of namen)
        val errs = HashMap<String, String>()
        return try {
            fetchFrom(s, quiet, errs, rec).also {
                UpdateLog.phase(rec, "parse")
                // build 149: geleverd = ingelezen + niet ingelezen (verborgen categorieën)
                FetchLog.delivered(s.id, it.channels.size + it.hiddenCount("live"), it.movies.size + it.hiddenCount("movie"),
                    it.series.size + it.hiddenCount("series"), errs)
                CatchupLog178.note(s.id, it.channels)   // build 178
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FetchLog.failed(s.id, Build147Logic.Fetch.errorCode(e.javaClass.simpleName, e.message, (e as? StatusException)?.code))
            throw e
        }
    }

    private suspend fun fetchFrom(s: PlaylistSource, quiet: Boolean, errs: MutableMap<String, String>, rec: UpdateLog.Rec? = null): Library {
        fun status(t: String) { if (!quiet) loadingText = t }
        val password = Store.secret(s.passwordKey) ?: ""
        var lib: Library
        when (s.kind) {
            PlaylistKind.M3U -> {
                status(L("Downloading playlist…"))
                val u = Net.urlValue(s.m3uURL) ?: throw Net.badURL()
                var text = Net.text(u)
                UpdateLog.phase(rec, "fetch")
                status(L("Sorting channels and movies…"))
                lib = withContext(Dispatchers.Default) { M3UParser.library(text, s.id) }
                val epg = Net.urlValue(s.epgURL) ?: M3UParser.epgURL(text)
                AutoRefresh179.rememberEpgURL(s.id, epg)   // build 179: gids apart verversen
                // build 148: de tekst van de lijst niet vasthouden tijdens het laden van de gids
                text = ""
                UpdateLog.phase(rec, "parse")
                if (epg != null) { status(L("Loading TV guide…")); lib = lib.copy(epg = XMLTVParser.load(epg, back = catchupBack(lib.channels))); UpdateLog.phase(rec, "fetch") }
            }
            PlaylistKind.XTREAM -> {
                status(L("Signing in to server…"))
                val x = XtreamClient(s, password)
                x.authenticate()
                status(L("Fetching live TV, movies and series…"))
                lib = x.library(onFetched = { UpdateLog.phase(rec, "fetch") }, hidden = hiddenPredicate(s))
                UpdateLog.phase(rec, "parse")
                errs.putAll(x.kindErrors())
                val epg = Net.urlValue(s.epgURL) ?: x.xmltvURL
                if (epg != null) { status(L("Loading TV guide…")); lib = lib.copy(epg = XMLTVParser.load(epg, back = catchupBack(lib.channels))); UpdateLog.phase(rec, "fetch") }
            }
            PlaylistKind.STALKER -> {
                status(L("Connecting to portal…"))
                val st = (if (source?.id == s.id) stalker else null) ?: StalkerClient(s)
                st.connect()
                status(L("Fetching channels and videos…"))
                lib = st.library()
                Net.urlValue(s.epgURL)?.let { lib = lib.copy(epg = XMLTVParser.load(it, back = catchupBack(lib.channels))) }
            }
            PlaylistKind.JELLYFIN, PlaylistKind.EMBY -> {
                status(L("Fetching your library…"))
                lib = JellyfinClient(s, session(s)).library()
            }
            PlaylistKind.PLEX -> {
                status(L("Fetching your library…"))
                var sess = session(s) ?: throw JellyfinClient.notLoggedIn
                // server-adres kan veranderen: bij fout opnieuw zoeken
                lib = try { PlexClient(sess).library() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    val userToken = Store.secret(s.passwordKey) ?: throw e
                    sess = PlexClient.discoverServer(userToken)
                    saveSession(s, sess)
                    PlexClient(sess).library()
                }
            }
            PlaylistKind.WEBDAV -> {
                status(L("Fetching your library…"))
                lib = WebDAVClient(s, password).library()
            }
            PlaylistKind.STREMIO -> {
                status(L("Loading Stremio add-ons…"))
                val addons = loadStremioAddons(s)
                if (source?.id == s.id) stremioAddons = addons
                status(L("Fetching catalogs…"))
                lib = StremioClient(addons).library()
            }
        }
        // build 148: bij de andere soorten zit inlezen in het ophalen (één fase)
        if (s.kind != PlaylistKind.M3U && s.kind != PlaylistKind.XTREAM) UpdateLog.phase(rec, "fetch")
        return lib
    }

    /**
     * Build 128: ruwe catalogus van server [p] (ook een niet-actieve): de actieve uit het geheugen, anders de cache op dit
     * apparaat, anders (alleen met [allowNetwork]) stil van het netwerk met een time-out (de cache wordt dan bijgewerkt).
     */
    suspend fun catalogOf(p: PlaylistSource, allowNetwork: Boolean, background: Boolean = false): Library? {
        if (p.id.equals(source?.id, ignoreCase = true) && !rawLibrary.isEmpty) return rawLibrary
        // build 141: van een andere server alleen titels en zenders, zonder gids (zie [readCatalog])
        withContext(Dispatchers.IO) { runCatching { readCatalog(p) }.getOrNull() }?.let { return it }
        if (!allowNetwork) return null
        // build 130: op de achtergrond ([background]) hooguit eens per 6 uur per server (ook over herstarts heen); altijd
        // met een time-out. Aangeklikt door de gebruiker = meteen proberen.
        if (background && !AllServersLogic.mayFetch(lastFetch(p), System.currentTimeMillis())) return null
        // build 144 (= CrossServer op Apple, build 143): andere servers op de achtergrond niet van het netwerk in de eerste
        // minuut na de start; tot dan alleen bewaarde catalogi
        if (background) {
            val wait = Build144Logic.AutoRefresh.OTHERS_NETWORK_AFTER_MS - uptimeMs()
            if (wait > 0) kotlinx.coroutines.delay(wait)
        }
        markFetch(p)
        return try {
            withTimeoutOrNull(AllServersLogic.FETCH_TIMEOUT) { fetch(p, quiet = true) }?.also { writeCacheNow(p, it, notify = false) }
                ?.copy(epg = emptyMap())
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }

    /** Build 130: laatste poging om server [p] (niet de actieve) op de achtergrond van het netwerk te halen. */
    private fun fetchKey(p: PlaylistSource) = "allservers.fetch." + p.id.lowercase(java.util.Locale.ROOT)
    private fun lastFetch(p: PlaylistSource): Long? = Prefs.string(fetchKey(p))?.toLongOrNull()
    private fun markFetch(p: PlaylistSource) = Prefs.put(fetchKey(p), System.currentTimeMillis().toString())

    /** Build 130: een andere server (niet de actieve) nu verversen, stil en met een time-out. true = gelukt. */
    internal suspend fun refreshOther(p: PlaylistSource): Boolean {
        val rec = UpdateLog.begin(p)
        return try {
            markFetch(p)
            val lib = withTimeoutOrNull(AllServersLogic.FETCH_TIMEOUT) { fetch(p, quiet = true, rec = rec) }
            // build 179: wat er de vorige keer stond, voor het opruimen van verwijderde titels
            val before = if (lib != null) withContext(Dispatchers.IO) { runCatching { readCatalog(p) }.getOrNull() } else null
            // build 141: meteen wegschrijven (niet in een losse taak die de catalogus nog vasthoudt terwijl het volgende begint)
            if (lib != null) { writeCacheNow(p, lib); UpdateLog.phase(rec, "save") }
            if (lib != null) { AutoRefresh179.markFull(p.id); withContext(Dispatchers.Main) { Cleanup179.afterRefresh(p, before, lib) } }
            UpdateLog.finish(rec, if (lib != null) "ok" else "failed")
            lib != null
        } catch (e: CancellationException) { UpdateLog.finish(rec, "cancelled"); throw e } catch (_: Exception) { UpdateLog.finish(rec, "failed"); false }
    }

    /** Build 128: de server van een item ([Movie.sourceID] e.d.); null = de actieve. */
    fun sourceFor(id: String?): PlaylistSource? =
        if (id == null) source else Store.playlists.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: source

    private val otherAddons = java.util.concurrent.ConcurrentHashMap<String, List<StremioClient.Addon>>()
    private suspend fun addonsFor(src: PlaylistSource): List<StremioClient.Addon> {
        if (src.id == source?.id) { if (stremioAddons.isEmpty()) stremioAddons = loadStremioAddons(src); return stremioAddons }
        otherAddons[src.id]?.let { return it }
        return loadStremioAddons(src).also { otherAddons[src.id] = it }
    }

    /** Servers (kleine letters id) die bij deze keer openen al ververst zijn (build 145: "Altijd" na een profielwissel). */
    private val refreshedThisLaunch = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * Build 130: alle servers vernieuwen ("Vernieuwen" bij de server en "Alle afspeellijsten nu vernieuwen"): eerst de
     * actieve (met [spinner] zoals voorheen het volledige laadscherm, anders stil met "Bijwerken…"), daarna de andere één
     * voor één op de achtergrond, elk met een time-out. Een fout bij één server houdt de rest niet tegen; welke niet
     * lukten staat daarna in [refreshFailed] (alleen de naam, niets gelogd). Daarna kijkt [AllServers] opnieuw
     * (catalogmeta, remap en slimme collecties).
     */
    suspend fun refreshAll(spinner: Boolean = false) {
        if (refreshingAll) return
        refreshingAll = true
        // build 145: Vernieuwen neemt een lopend bijwerken (na de vraag of bij "Altijd") over
        updateTask?.cancel(); autoGen++
        refreshFailed = emptyList()
        val failed = ArrayList<String>()
        try {
            val active = source
            val all = Store.playlists.toList()
            val order = AllServersLogic.refreshOrder(all.map { it.id }, active?.id).mapNotNull { id -> all.firstOrNull { it.id == id } }
            for ((i, p) in order.withIndex()) {
                if (order.size > 1) refreshStatus = L("Refreshing %@ (%d of %d)…", p.name, i + 1, order.size)
                val ok = if (active != null && p.id == active.id) {
                    if (spinner) { select(p, forceRefresh = true); error == null && !rawLibrary.isEmpty }
                    else withTimeoutOrNull(180_000) { refresh(p, showSpinner = false) } ?: false
                } else {
                    // de andere niet tijdens het afspelen en pas als de actieve klaar is
                    while (AppState.playing != null || isLoading) kotlinx.coroutines.delay(2_000)
                    refreshOther(p)
                }
                if (!ok) failed += p.name
            }
        } finally {
            refreshingAll = false; refreshStatus = null; refreshFailed = failed
        }
        AllServers.catalogsRefreshed()
    }

    /**
     * Build 179: alleen de tv-gids van [p] opnieuw ophalen (de catalogus blijft staan). true = gelukt. Adres: het ingevulde
     * EPG-adres, bij Xtream xmltv.php, bij M3U het adres uit de lijst (bewaard bij de vorige volledige verversing).
     */
    suspend fun refreshEpg(p: PlaylistSource): Boolean {
        if (Screenshots.isActive || StressMode.isActive) return true
        val url = Net.urlValue(p.epgURL) ?: when (p.kind) {
            PlaylistKind.XTREAM -> XtreamClient(p, Store.secret(p.passwordKey) ?: "").xmltvURL
            PlaylistKind.M3U -> AutoRefresh179.epgURL(p.id)
            else -> null
        } ?: return false
        return try {
            val active = source?.id == p.id && !rawLibrary.isEmpty
            val core = (if (active) rawLibrary else withContext(Dispatchers.IO) { runCatching { readCatalog(p) }.getOrNull() })
                ?: return false
            val epg = XMLTVParser.load(url, back = catchupBack(core.channels))
            if (epg.isEmpty()) return false
            writeCacheNow(p, core.copy(epg = epg))
            if (source?.id == p.id && !rawLibrary.isEmpty) {
                val disk = withContext(Dispatchers.IO) { CatalogFiles.openEpg(cachePaths(p), Http.json) }
                withContext(Dispatchers.Main) { if (source?.id == p.id) setRaw(rawLibrary.copy(epg = disk ?: epg)) }
            }
            AutoRefresh179.markEpg(p.id)
            true
        } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
    }

    /** Build 179: voortgang van een automatische verversing in de bestaande vernieuw-weergave. */
    fun setAutoStatus(text: String?) { refreshStatus = text }

    // MARK: sessies (Plex / Jellyfin / Emby)

    fun sessionKey(p: PlaylistSource) = "playlist.${p.id}.session"
    fun session(p: PlaylistSource): ServerSession? =
        Store.secret(sessionKey(p))?.let { runCatching { Http.json.decodeFromString(ServerSession.serializer(), it) }.getOrNull() }
    private fun saveSession(p: PlaylistSource, s: ServerSession?) =
        Store.setSecret(sessionKey(p), s?.let { Http.json.encodeToString(ServerSession.serializer(), it) })

    suspend fun loadStremioAddons(s: PlaylistSource): List<StremioClient.Addon> {
        var urls = s.m3uURL.split('\n', ',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        val key = Store.secret(s.passwordKey)
        if (!key.isNullOrEmpty()) {
            runCatching { StremioClient.accountAddons(key) }.getOrNull()?.let { urls = it + urls }
        }
        if (urls.none { it.contains("cinemeta") }) urls = listOf(StremioClient.cinemeta) + urls
        val out = ArrayList<StremioClient.Addon>()
        for (u in urls.distinct()) StremioClient.loadAddon(u)?.let { out.add(it) }
        return out
    }

    // MARK: aanpassingen toepassen

    private fun setRaw(lib: Library) {
        // build 141: een vervangen gids op schijf straks dichtdoen
        if (rawLibrary.epg !== lib.epg) retireEpg(rawLibrary.epg)
        source?.id?.let { if (lib.channels.isNotEmpty()) CatchupLog178.note(it, lib.channels) }   // build 178
        rawLibrary = lib
        applyCustomizations()
    }

    /** Alles wat het filteren nodig heeft, als losse waarden — zodat het op de achtergrond kan (grote lijsten). */
    data class FilterContext(
        val custom: Customization,
        val playlist: String,
        val ageLimit: Int?,
        val strict: Boolean,
        val parentalEnabled: Boolean,
        val lockedGroups: Set<String>,
        val autoLockAdult: Boolean,
        val unlocked: Boolean,
        /** build 128: verborgen categorieën (naam zoals de server hem levert) van het actieve profiel voor deze server */
        val hiddenCategories: Set<String> = emptySet(),
        /** build 140: kinderprofiel: alleen inhoud uit kindercategorieën (docs/ACCOUNT.md → "Build 140") */
        val kids: Boolean = false,
        /** build 171: verborgen zenders en geblokkeerde films/series van het actieve profiel op deze server (id's) */
        val hiddenChannelIDs: Set<String> = emptySet(),
        val blockedMovieIDs: Set<String> = emptySet(),
        val blockedSeriesIDs: Set<String> = emptySet(),
    )

    /** Filtercontext voor deze bron en het actieve profiel (op de main thread aanroepen: leest Compose-state). */
    fun context(p: PlaylistSource): FilterContext {
        val prof = Store.activeProfile
        val pc = Store.parental
        return FilterContext(
            custom = Store.customization(p.id), playlist = p.id,
            ageLimit = prof?.effectiveAgeLimit, strict = prof?.strictAgeFilter ?: false,
            parentalEnabled = pc.enabled, lockedGroups = pc.lockedGroups, autoLockAdult = pc.autoLockAdult,
            unlocked = ParentalControl.isUnlocked,
            hiddenCategories = Store.hiddenCategoryNames(p.id),
            kids = prof?.kids == true,
            hiddenChannelIDs = Build171Logic.ProfileSets.channelIDs(Store.hiddenChannels, p.id),
            blockedMovieIDs = Build171Logic.ProfileSets.titleIDs(Store.blockedTitles, p.id).first,
            blockedSeriesIDs = Build171Logic.ProfileSets.titleIDs(Store.blockedTitles, p.id).second,
        )
    }

    /**
     * Past aanpassingen, kinderslot en de leeftijdsgrens van het actieve profiel toe op [rawLibrary] → [library].
     * Grote lijsten worden op Dispatchers.Default gefilterd; bij een profiel met leeftijdsgrens is [library]
     * intussen leeg en [isFiltering] true.
     */
    fun applyCustomizations(quiet: Boolean = false, background: Boolean = false) {
        val s = source
        if (s == null) { filterJob?.cancel(); isFiltering = false; library = rawLibrary; return }
        val ctx = context(s)
        val raw = rawLibrary
        val gen = ++filterGeneration
        filterJob?.cancel()
        // Alleen nieuwe classificaties (build 56): niets leegmaken, pas filteren als er niet gescrold wordt, en alleen een
        // nieuwe bibliotheek doorgeven als de inhoud echt anders is (anders tekende alles opnieuw, elke 40 titels).
        if (quiet) {
            filterJob = AppScope.launch {
                UserActivity.awaitIdle()
                val cur = library
                val lib = withContext(Dispatchers.Default) { reuseUnchanged(cur, apply(raw, ctx)) }
                if (gen != filterGeneration) return@launch
                UserActivity.awaitIdle()
                if (gen != filterGeneration) return@launch
                if (lib !== cur) library = lib
                isFiltering = false
            }
            return
        }
        // kleine lijsten: meteen; grote lijsten (en build 140: categorieën aan/uit) op de achtergrond
        if (!background && raw.channels.size + raw.movies.size + raw.series.size < 3000) {
            library = apply(raw, ctx)
            isFiltering = false
            return
        }
        // kinderprofiel/leeftijdsgrens: niets ongefilterds laten zien terwijl er gefilterd wordt (build 140: bij categorieën
        // aan/uit blijft de vorige, al gefilterde lijst staan; die is nooit minder streng)
        if (ctx.ageLimit != null && !background) library = Library()
        isFiltering = true
        filterJob = AppScope.launch {
            val lib = withContext(Dispatchers.Default) { apply(raw, ctx) }
            if (gen != filterGeneration) return@launch   // intussen opnieuw gefilterd
            library = lib
            isFiltering = false
        }
    }

    /**
     * Zelfde inhoud als [old]? Dan de oude lijsten (identiteit) houden, zodat collecties, rijen en caches die op
     * identiteit vergelijken niets opnieuw doen. Geeft [old] terug als alles gelijk is. (Op de achtergrond aanroepen.)
     */
    private fun reuseUnchanged(old: Library, new: Library): Library {
        val ch = if (old.channels == new.channels) old.channels else new.channels
        val mv = if (old.movies == new.movies) old.movies else new.movies
        val se = if (old.series == new.series) old.series else new.series
        // build 141: een gids op schijf (DiskEpg) niet inhoudelijk vergelijken (dat zou hem helemaal inlezen)
        val epg = if (old.epg === new.epg) old.epg else if (old.epg is DiskEpg || new.epg is DiskEpg) new.epg
            else if (old.epg == new.epg) old.epg else new.epg
        if (ch === old.channels && mv === old.movies && se === old.series && epg === old.epg) return old
        return Library(ch, mv, se, epg)
    }

    /** Oude aanroep: filteren met de huidige kinderslot-instellingen (op de main thread). */
    fun apply(raw: Library, c: Customization, playlist: String, ageLimit: Int? = null, strict: Boolean = false): Library {
        val pc = Store.parental
        return apply(raw, FilterContext(c, playlist, ageLimit, strict, pc.enabled, pc.lockedGroups, pc.autoLockAdult,
            ParentalControl.isUnlocked, Store.hiddenCategoryNames(playlist), kids = Store.activeProfile?.kids == true))
    }

    /** Build 194: de tweeling (zelfde zender met catch-up) per zender zonder eigen catch-up; per ruwe lijst één keer berekend. */
    @Volatile private var twinCache: Pair<List<Channel>, Map<String, Channel>>? = null
    private fun twinsOf(pool: List<Channel>): Map<String, Channel> {
        if (pool.isEmpty()) return emptyMap()
        twinCache?.let { if (it.first === pool) return it.second }
        val byKey = HashMap<String, Channel>()
        val items = pool.map { c -> byKey[c.id] = c; Build194Logic.Twin.Item(c.id, c.epgID, c.name, c.catchupDays) }
        val links = Build194Logic.Twin.link(items)
        val out = links.mapNotNull { (k, t) -> byKey[t]?.let { k to it } }.toMap()
        twinCache = pool to out
        return out
    }

    /** Build 194: zenders zonder eigen catch-up met een ondubbelzinnige tweeling krijgen diens dagen en gegevens voor terugkijken. */
    private fun withReplayTwins(pool: List<Channel>, channels: List<Channel>): List<Channel> {
        val twins = twinsOf(pool)
        if (twins.isEmpty()) return channels
        return channels.map { c ->
            val t = if (c.catchupDays <= 0) twins[c.id] else null
            if (t == null) c else c.copy(catchupDays = t.catchupDays, catchupType = t.catchupType, catchupSource = t.catchupSource,
                replayID = t.id, replayStreamURL = t.streamURL, replayCmd = t.stalkerCmd)
        }
    }

    /** Pure filterfunctie (veilig op de achtergrond; leest alleen de thread-veilige Metadata-cache). */
    fun apply(raw: Library, ctx: FilterContext): Library {
        val c = ctx.custom
        val playlist = ctx.playlist
        val ageLimit = ctx.ageLimit
        fun isLocked(g: String): Boolean {
            if (!ctx.parentalEnabled || ctx.unlocked) return false
            if ("$playlist|$g" in ctx.lockedGroups) return true
            return ctx.autoLockAdult && ParentalControl.isAdultGroup(g)
        }
        fun allowed(rated: String?, group: String, genre: String?, info: AgeRating?): Boolean =
            ctx.unlocked || ParentalControl.allowedFor(rated, group, ageLimit, genre, ctx.strict, info)
        fun group(g: String): String {
            val merged = c.merged[g] ?: g
            return c.renamed[Customization.groupKey(merged)] ?: merged
        }
        // per groep één keer uitrekenen (tienduizenden titels delen maar een paar honderd groepen)
        val groupOK = HashMap<String, Boolean>()
        val kids = ctx.kids && !ctx.unlocked
        fun visible(id: String, g: String): Boolean {
            if (id in c.hidden) return false
            groupOK[g]?.let { return it }
            val m = c.merged[g] ?: g
            // build 128: categorieën aan/uit (build 140: voor het hele account, niet bij een kinderprofiel)
            // build 140: kinderprofiel: alleen kindercategorieën (naam zoals de server hem levert)
            val ok = (!kids || Build140Logic.Kids.isKidsCategory(g)) &&
                g !in ctx.hiddenCategories && m !in ctx.hiddenCategories &&
                !isLocked(g) && !isLocked(m)
            groupOK[g] = ok
            return ok
        }
        // leeftijd: TMDB (Kijkwijzer eerst, AgeRatings) en anders OMDb (Metadata); beide caches zijn thread-veilig.
        // build 140: kinderprofiel: een bekende classificatie boven de grens niet, zonder bekende classificatie wel
        // (het item komt uit een kindercategorie)
        fun ageOK(md: TitleMetadata?, info: AgeRating?, g: String): Boolean = when {
            ageLimit == null -> true
            kids -> Build140Logic.Kids.ageAllowed(info?.age ?: ParentalControl.minimumAge(md?.rated), ageLimit)
            else -> allowed(md?.rated, g, md?.genre, info)
        }

        // build 141: alleen een kopie als er echt iets hernoemd/samengevoegd is; anders hetzelfde object (de gefilterde lijst
        // deelt zo de titels met de ruwe lijst, in plaats van tienduizenden kopieën per filterronde en per server)
        val plain = c.renamed.isEmpty() && c.merged.isEmpty()
        var channels = raw.channels.filter { it.id !in ctx.hiddenChannelIDs && visible(it.id, it.group) }
            .let { l -> if (plain) l else l.map { ch ->
                val g = group(ch.group); val n = c.renamed[ch.id] ?: ch.name
                if (g == ch.group && n == ch.name) ch else ch.copy(group = g, name = n)
            } }
        // leeftijdsgrens: geen volwassenen-zenders (streng: alleen kinderzenders). Build 140: een kinderprofiel volgt alleen
        // de categorie (de zendernaam telt niet mee)
        if (ageLimit != null && !ctx.unlocked && !kids) channels = channels.filter { ParentalControl.channelAllowedFor(it.name, it.group, ageLimit, ctx.strict) }
        val movies = raw.movies.filter {
            it.id !in ctx.blockedMovieIDs && visible(it.id, it.group) && (ageLimit == null || ageOK(Metadata.cached(it), AgeRatings.cached(it), it.group))
        }.let { l -> if (plain) l else l.map { m ->
            val g = group(m.group); val t = c.renamed[m.id] ?: m.title
            if (g == m.group && t == m.title) m else m.copy(group = g, title = t)
        } }
        val series = raw.series.filter {
            it.id !in ctx.blockedSeriesIDs && visible(it.id, it.group) && (ageLimit == null || ageOK(Metadata.cached(it), AgeRatings.cached(it), it.group))
        }.let { l -> if (plain) l else l.map { se ->
            val g = group(se.group); val t = c.renamed[se.id] ?: se.title
            if (g == se.group && t == se.title) se else se.copy(group = g, title = t)
        } }

        // eigen volgorde: groepen, dan kanalen binnen een groep
        if (c.groupOrder.isNotEmpty() || c.channelOrder.isNotEmpty()) {
            val gIndex = HashMap<String, Int>()
            c.groupOrder.forEachIndexed { i, g -> if (g !in gIndex) gIndex[g] = i }
            val natural = HashMap<String, Int>()
            channels.map { it.group }.distinct().forEachIndexed { i, g -> natural[g] = i + 10_000 }
            val cIndex = HashMap<String, Int>()
            for ((_, ids) in c.channelOrder) ids.forEachIndexed { i, id -> cIndex[id] = i }
            val original = HashMap<String, Int>()
            channels.forEachIndexed { i, ch -> if (ch.id !in original) original[ch.id] = i + 100_000 }
            channels = channels.sortedWith(Comparator { a, b ->
                val ga = gIndex[a.group] ?: natural[a.group] ?: 0
                val gb = gIndex[b.group] ?: natural[b.group] ?: 0
                if (ga != gb) ga.compareTo(gb)
                else (cIndex[a.id] ?: original[a.id] ?: 0).compareTo(cIndex[b.id] ?: original[b.id] ?: 0)
            })
        }
        // build 194: terugkijken via een tweeling (zelfde zender met catch-up op deze server), voor élke groep, Favorieten, Recent, zoeken en gids
        channels = withReplayTwins(raw.channels, channels)
        return raw.copy(channels = channels, movies = movies, series = series)
    }

    // MARK: details

    suspend fun episodes(s: Series): List<Episode> {
        if (s.episodes.isNotEmpty()) return s.episodes
        // build 128: serie van een andere server: niet in de cache van de actieve server (id's kunnen botsen)
        val foreign = s.sourceID != null && !s.sourceID.equals(source?.id, ignoreCase = true)
        if (!foreign) episodeCache[s.id]?.let { return it }
        // build 128: serie van een andere server (zonder te wisselen)
        val src = sourceFor(s.sourceID) ?: return emptyList()
        val eps: List<Episode> = try {
            when (src.kind) {
                PlaylistKind.XTREAM -> XtreamClient(src, Store.secret(src.passwordKey) ?: "").episodes(s)
                PlaylistKind.JELLYFIN, PlaylistKind.EMBY -> JellyfinClient(src, session(src)).episodes(s)
                PlaylistKind.PLEX -> session(src)?.let { PlexClient(it).episodes(s) } ?: emptyList()
                PlaylistKind.STREMIO -> StremioClient(addonsFor(src)).episodes(s)
                else -> emptyList()
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
        // alleen bewaren als er iets binnenkwam: bij een time-out of serverfout opnieuw proberen
        if (eps.isNotEmpty() && !foreign) episodeCache = episodeCache + (s.id to eps)
        return eps
    }

    /** Build 195: afleveringen opnieuw bij de server halen (negeert de cache; bij een fout blijft de oude lijst). */
    suspend fun freshEpisodes(s: Series): List<Episode> {
        if (s.episodes.isNotEmpty()) return s.episodes
        val foreign = s.sourceID != null && !s.sourceID.equals(source?.id, ignoreCase = true)
        val old = if (foreign) null else episodeCache[s.id]
        if (old != null) episodeCache = episodeCache - s.id
        val eps = episodes(s)
        if (eps.isEmpty() && old != null) { episodeCache = episodeCache + (s.id to old); return old }
        return eps
    }

    /** Ruwe Xtream get_series_info (detailpagina). */
    suspend fun xtreamSeriesInfo(series: Series): JsonObject? {
        val s = sourceFor(series.sourceID) ?: return null
        val id = series.xtreamSeriesID ?: return null
        if (s.kind != PlaylistKind.XTREAM) return null
        return XtreamClient(s, Store.secret(s.passwordKey) ?: "").seriesInfo(id)
    }

    /** Xtream get_vod_info → "info" (detailpagina). */
    suspend fun xtreamVodInfo(m: Movie): JsonObject? {
        val s = sourceFor(m.sourceID) ?: return null
        val id = m.xtreamVodID ?: return null
        if (s.kind != PlaylistKind.XTREAM) return null
        return XtreamClient(s, Store.secret(s.passwordKey) ?: "").vodInfo(id)
    }

    /** Stremio: alle beschikbare streams voor een titel. */
    suspend fun stremioStreams(item: PlayableItem): List<StremioStream> {
        val ref = item.stremio ?: return emptyList()
        val s = sourceFor(item.sourceID) ?: return emptyList()
        val parts = ref.split("|", limit = 2)
        if (parts.size != 2) return emptyList()
        return StremioClient(addonsFor(s)).streams(parts[0], parts[1])
    }

    /** Geeft de echte afspeel-URL (Stalker maakt per keer een link aan, Stremio zoekt een stream). */
    suspend fun resolve(item: PlayableItem): String? {
        // build 129: terugkijken op een MAC-portal (ook van een andere server)
        if (isArchive(item.url)) return resolveArchive(item.url!!)
        if (item.url != null && item.stalkerCmd == null) return item.url
        val cmd = item.stalkerCmd
        // build 128: item van een andere server: een eigen MAC-portalverbinding
        val other = item.sourceID?.takeIf { !it.equals(source?.id, ignoreCase = true) }?.let { sourceFor(it) }
        val st = if (other != null) (if (other.kind == PlaylistKind.STALKER) StalkerClient(other) else null) else stalker
        if (cmd != null && st != null) {
            val link = try {
                st.createLink(cmd, if (item.type == MediaType.LIVE) MediaType.LIVE else MediaType.MOVIE)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            return link ?: item.url
        }
        if (item.stremio != null) return stremioStreams(item).firstOrNull()?.url
        return item.url
    }

    fun catchupURL(c: Channel, p: EPGProgram): String? = catchupURL(c.id, p.start, p.end, c.sourceID)

    // MARK: build 128: gids en terugkijken van zenders op een andere server (eigen inlog, eigen gids, eigen catch-up-URL)

    private fun foreign(sid: String?): Boolean = sid != null && !sid.equals(source?.id, ignoreCase = true)

    /** Gids van een zender: van de actieve server, of (zender van een andere server) uit de catalogus van die server. */
    fun epgFor(c: Channel): List<EPGProgram> =
        if (foreign(c.sourceID)) AllServers.programs(c) else library.epg[c.epgID ?: c.id] ?: emptyList()

    /** Wat er nu speelt (ook voor een zender van een andere server). */
    fun nowOf(c: Channel): EPGProgram? {
        if (!foreign(c.sourceID)) return library.nowPlaying(c)
        val list = AllServers.programs(c)
        val i = EPGIndex.indexAt(list, System.currentTimeMillis())
        return if (i >= 0 && list[i].isNow) list[i] else null
    }

    /** Wat er straks komt (ook voor een zender van een andere server). */
    fun upcomingOf(c: Channel, count: Int): List<EPGProgram> {
        if (!foreign(c.sourceID)) return library.upcoming(c, count)
        val list = AllServers.programs(c)
        val now = System.currentTimeMillis()
        val i = EPGIndex.firstEndingAfter(list, now)
        if (i < 0) return emptyList()
        return list.subList(i, minOf(list.size, i + count)).filter { it.end > now }
    }

    /**
     * Terugkijk-URL voor een zender van server [sourceID] (null = de actieve): Xtream met de inloggegevens van die
     * server en zijn terugkijk-correctie. Zenders die via remap verhuisd zijn, staan al op hun nieuwe server.
     */
    fun catchupURL(channelID: String, start: Long, end: Long, sourceID: String?): String? {
        if (!foreign(sourceID)) return catchupURL(channelID, start, end)
        val s = sourceFor(sourceID) ?: return null
        val orig = (AllServers.item("${s.id}|$channelID") as? Channel) ?: return null
        if (orig.catchupDays <= 0) return null
        val ch = orig.replayView   // build 194: tweeling als die er is
        val shift = (s.catchupOffsets[channelID] ?: 0) * 60_000L
        // build 129: MAC-portal: de link komt pas bij het afspelen ([resolveArchive]), met een eigen portalverbinding
        if (s.kind == PlaylistKind.STALKER) return AllServersLogic.archiveURL(s.id, ch.id, start + shift, end + shift)
        if (s.kind == PlaylistKind.M3U) return m3uCatchupURL(ch, start + shift, end + shift)   // build 178
        if (s.kind != PlaylistKind.XTREAM) return null
        return XtreamClient(s, Store.secret(s.passwordKey) ?: "").catchupURL(ch, start + shift, end + shift)
    }

    /** Build 178: terugkijken van een M3U-zender (catchup-type/-source, of een Xtream-achtige stream-URL) */
    private fun m3uCatchupURL(ch: Channel, start: Long, end: Long): String? {
        val stream = ch.streamURL ?: return null
        val x = Build178Logic.xtreamParts(stream)
        val tz = if (x != null) XtreamTimeZone178.zone(x.server, x.user) else java.util.TimeZone.getDefault()
        return Build178Logic.m3uURL(stream, ch.catchupType, ch.catchupSource, start, end, System.currentTimeMillis(), tz)
    }

    /** Build 178: per gids-id de terugkijkdagen van de zenders (alleen die met terugkijken) */
    private fun catchupBack(channels: List<Channel>): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (c in channels) if (c.catchupDays > 0) { val k = c.epgID ?: c.id; out[k] = maxOf(out[k] ?: 0, c.catchupDays) }
        return out
    }

    /** Build 178: de gids met archief van één zender (Xtream get_simple_data_table), 20 minuten in het geheugen. */
    private val archiveCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<EPGProgram>>>()

    suspend fun archiveEPG(c: Channel): List<EPGProgram> {
        val s = (if (foreign(c.sourceID)) sourceFor(c.sourceID) else source) ?: return emptyList()
        if (s.kind != PlaylistKind.XTREAM || c.catchupDays <= 0) return emptyList()
        val key = "${s.id}|${c.id}"
        archiveCache[key]?.let { if (System.currentTimeMillis() - it.first < 1_200_000) return it.second }
        val list = try {
            // build 194: archief van de tweeling, maar met de gids-sleutel van deze zender
            XtreamClient(s, Store.secret(s.passwordKey) ?: "").archiveEPG(c.replayView.copy(epgID = c.epgID ?: c.id))
        } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
        if (archiveCache.size > 300) archiveCache.clear()
        archiveCache[key] = System.currentTimeMillis() to list
        return list
    }

    /** Build 129: MAC-portalverbindingen van andere servers (terugkijken), zodat niet elke keer opnieuw ingelogd wordt. */
    private val otherStalkers = java.util.concurrent.ConcurrentHashMap<String, StalkerClient>()

    /** Is dit een terugkijk-adres van een MAC-portal dat nog een echte link moet worden? */
    fun isArchive(u: String?): Boolean = u != null && u.startsWith(AllServersLogic.ARCHIVE_SCHEME)

    /**
     * Build 129: terugkijk-adres van een MAC-portal ([AllServersLogic.archiveURL]) → echte afspeel-link, via de
     * portalverbinding van die server (de actieve, of een eigen verbinding voor een andere server). null = lukt niet.
     */
    suspend fun resolveArchive(u: String): String? {
        val a = AllServersLogic.parseArchive(u) ?: return null
        val s = Store.playlists.firstOrNull { it.id.equals(a.sid, ignoreCase = true) } ?: return null
        if (s.kind != PlaylistKind.STALKER) return null
        val active = !foreign(s.id)
        val ch = if (active) rawLibrary.channels.firstOrNull { it.id == a.channelID }
            else AllServers.item("${s.id}|${a.channelID}") as? Channel
        val st = if (active) (stalker ?: StalkerClient(s).also { stalker = it })
            else otherStalkers.getOrPut(s.id.lowercase()) { StalkerClient(s) }
        return try {
            withTimeoutOrNull(30_000) { st.archiveLink(a.channelID, ch?.stalkerCmd, a.start) }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }

    /**
     * Terugkijken / timeshift vanaf een willekeurig moment (Xtream). De terugkijk-correctie van de zender
     * (catchupOffsets, build 51) verschuift het begintijdstip (en het eind) met dat aantal minuten.
     */
    fun catchupURL(channelID: String, start: Long, end: Long): String? {
        val s = source ?: return null
        if (s.kind != PlaylistKind.XTREAM && s.kind != PlaylistKind.STALKER && s.kind != PlaylistKind.M3U) return null
        val raw = rawLibrary.channels.firstOrNull { it.id == channelID } ?: return null
        // build 194: geen eigen catch-up = de tweeling (zelfde zender met catch-up op deze server), anders niets
        val ch = if (raw.catchupDays > 0) raw else twinsOf(rawLibrary.channels)[raw.id] ?: return null
        val shift = (Store.playlists.firstOrNull { it.id == s.id }?.catchupOffsets?.get(channelID) ?: 0) * 60_000L
        // build 129: MAC-portal (ook de actieve server): de link komt pas bij het afspelen ([resolveArchive])
        if (s.kind == PlaylistKind.STALKER) return AllServersLogic.archiveURL(s.id, ch.id, start + shift, end + shift)
        if (s.kind == PlaylistKind.M3U) return m3uCatchupURL(ch, start + shift, end + shift)   // build 178
        return XtreamClient(s, Store.secret(s.passwordKey) ?: "").catchupURL(ch, start + shift, end + shift)
    }

    suspend fun shortEPG(c: Channel): List<EPGProgram> {
        val st = stalker
        if (st != null && library.epg[c.epgID ?: c.id] == null) {
            val p = st.shortEPG(c.id)
            if (p.isNotEmpty()) {
                // build 141: gids op schijf (DiskEpg) niet kopiëren (dat leest alles in), maar aanvullen
                fun add(m: Map<String, List<EPGProgram>>) = (m as? DiskEpg)?.with(c.id, p) ?: (m + (c.id to p))
                rawLibrary = rawLibrary.copy(epg = add(rawLibrary.epg))
                library = library.copy(epg = add(library.epg))
            }
            return p
        }
        return library.upcoming(c, count = 10)
    }

    // MARK: zoeken

    suspend fun search(q: String): SearchResults = search(q, allPlaylists = true)

    suspend fun search(q: String, allPlaylists: Boolean): SearchResults {
        val t = Net.fold(q.trim())
        if (t.length < 2) return SearchResults()
        val lib = library
        // build 149: Stremio-add-ons met een zoekcatalogus ook laten zoeken (samengevoegd en ontdubbeld met de lokale treffers)
        val stremio = Store.playlists.filter { it.kind == PlaylistKind.STREMIO }
        val currentID = source?.id
        val others = Store.playlists.filter { it.id != currentID }
        // andere afspeellijsten: zelfde verbergen/kinderslot/leeftijdsgrens als het actieve profiel
        val contexts = others.associate { it.id to context(it) }
        return withContext(Dispatchers.Default) {
            // build 149: op woorden (volgorde maakt niet uit), genormaliseerd (accenten, leestekens, voorvoegsels), met
            // ranking (exact, begint met, woordbegin, bevat, één typefout) en vooraf berekende zoeksleutels per lijst
            val remote = stremio.map { p -> async { runCatching { withTimeoutOrNull(8_000) { StremioClient(addonsFor(p)).search(q) } }.getOrNull()?.let { p to it } } }
            val S = Build149Logic.Search
            val r = SearchResults(
                channels = S.find(lib.channels, q, 60) { it.name },
                movies = S.find(lib.movies, q, 200) { it.title },
                series = S.find(lib.series, q, 200) { it.title },
            )
            val pending = remote.mapNotNull { it.await() }
            if (!allPlaylists && pending.isEmpty()) return@withContext r
            // build 129: treffers van de andere servers van het profiel in dezelfde rijen (zelfde filters als het
            // actieve profiel), ontdubbeld zoals in build 128; openen/afspelen via die server, zonder te wisselen
            val ch = ArrayList<List<Channel>>().apply { add(r.channels) }
            val mv = ArrayList<List<Movie>>().apply { add(r.movies) }
            val se = ArrayList<List<Series>>().apply { add(r.series) }
            if (allPlaylists) for (p in others) {
                val ctx = contexts[p.id] ?: continue
                val olib = AllServers.otherLibrary(p, ctx, allowNetwork = false) ?: continue
                val c = S.find(olib.channels, q, 40) { it.name }.map { it.copy(sourceID = p.id) }
                // build 141: alleen de gids van deze zenders uit de cache van die server (niet de hele gids)
                if (c.isNotEmpty()) {
                    val g = epgOf(p, c.mapTo(HashSet()) { CatalogFiles.epgKey(it) })
                    c.forEach { AllServers.remember(it, g[CatalogFiles.epgKey(it)]) }
                }
                ch += c
                mv += S.find(olib.movies, q, 120) { it.title }.map { it.copy(sourceID = p.id) }
                se += S.find(olib.series, q, 120) { it.title }.map { it.copy(sourceID = p.id) }
            }
            for ((p, lib2) in pending) {
                val own = p.id == currentID
                mv += lib2.movies.map { if (own) it else it.copy(sourceID = p.id) }
                se += lib2.series.map { if (own) it else it.copy(sourceID = p.id) }
            }
            val X = AllServersLogic
            SearchResults(
                channels = X.mergeAcross(ch, { X.crossKey("channel", it.name, null) }, 120),
                movies = X.mergeAcross(mv, { X.crossKey("movie", it.title, it.year) }, 300),
                series = X.mergeAcross(se, { X.crossKey("series", it.title, it.year) }, 300),
            )
        }
    }

    /** Dezelfde titel in andere groepen of andere afspeellijsten ("Andere bronnen"). */
    suspend fun otherSources(title: String, year: Int?, isShow: Boolean, excludingID: String): List<OtherHit> {
        val norm = Net.normTitle(title)
        if (norm.isEmpty()) return emptyList()
        val current = source
        val currentLib = library
        val all = Store.playlists
        val contexts = all.filter { it.id != current?.id }.associate { it.id to context(it) }
        return withContext(Dispatchers.Default) {
            val hits = ArrayList<OtherHit>()
            for (p in all) {
                val isCurrent = p.id == current?.id
                // build 129: andere servers uit het geheugen of de cache (gefilterd voor dit profiel); item met sourceID,
                // zodat de keuze daar opent en speelt zonder van server te wisselen
                val lib = if (isCurrent) currentLib else contexts[p.id]?.let { AllServers.otherLibrary(p, it, allowNetwork = false) } ?: continue
                if (isShow) {
                    for (s in lib.series) if (s.id != excludingID && Net.normTitle(s.title) == norm && (year == null || s.year == null || s.year == year))
                        hits.add(OtherHit(p, s.id, s.title, s.group, s.poster, item = if (isCurrent) s else s.copy(sourceID = p.id)))
                } else {
                    for (m in lib.movies) if (m.id != excludingID && Net.normTitle(m.title) == norm && (year == null || m.year == null || m.year == year))
                        hits.add(OtherHit(p, m.id, m.title, m.group, m.poster, item = if (isCurrent) m else m.copy(sourceID = p.id)))
                }
            }
            hits
        }
    }

    // MARK: toevoegen / testen

    /** Test de verbinding en bewaart geheimen (zie de uitleg bovenaan). null = gelukt, anders foutmelding. */
    suspend fun test(p: PlaylistSource, password: String): String? = try {
        validate(p, password); null
    } catch (e: CancellationException) { throw e } catch (e: Exception) { Net.message(e) }

    /** Als [test], maar geeft de (eventueel aangevulde) bron terug en gooit bij een fout. */
    suspend fun validate(p0: PlaylistSource, password: String): PlaylistSource {
        var p = p0
        when (p.kind) {
            PlaylistKind.M3U -> {
                val u = Net.urlValue(p.m3uURL) ?: throw Net.badURL()
                val r = Http.request(u, headers = mapOf("Range" to "bytes=0-2047"), raw = true)
                if (!r.ok) throw Net.statusError(r.code, "")
                val head = (r.bytes ?: ByteArray(0)).let { it.copyOf(minOf(it.size, 2048)) }.toString(Charsets.UTF_8)
                if (!head.contains("#EXT")) throw ServiceException(L("This doesn't look like a valid M3U playlist."))
                Store.setSecret(p.passwordKey, null)
            }
            PlaylistKind.XTREAM -> {
                XtreamClient(p, password).authenticate()
                Store.setSecret(p.passwordKey, password)
            }
            PlaylistKind.STALKER -> StalkerClient(p).connect()
            PlaylistKind.JELLYFIN, PlaylistKind.EMBY -> {
                val s = JellyfinClient(p, null).login(p.username, password)
                saveSession(p, s)
                Store.setSecret(p.passwordKey, null)     // wachtwoord niet bewaren, token wel
            }
            PlaylistKind.PLEX -> {
                if (password.isNotEmpty()) {
                    Store.setSecret(p.passwordKey, password)
                    val s = PlexClient.discoverServer(password)
                    saveSession(p, s)
                    p = p.copy(serverURL = s.serverURI)
                } else if (Store.secret(p.passwordKey) == null) {
                    throw JellyfinClient.notLoggedIn
                }
            }
            PlaylistKind.WEBDAV -> {
                // build 179: wachtwoord alleen in de privé-opslag (en versleuteld via de bronnen-sync)
                if (!Net.urlValue(p.serverURL).orEmpty().startsWith("http", ignoreCase = true)) throw Net.badURL()
                val pw = password.ifEmpty { Store.secret(p.passwordKey) ?: "" }
                WebDAVClient(p, pw).test()
                Store.setSecret(p.passwordKey, pw)
            }
            PlaylistKind.STREMIO -> {
                if (p.username.isNotEmpty() && password.isNotEmpty()) {
                    Store.setSecret(p.passwordKey, StremioClient.login(p.username, password))
                }
            }
        }
        clearCache(p)
        return p
    }

    /** Geen server meer open (server elders verwijderd of ander account ingelogd): bibliotheek leegmaken. */
    fun unload() {
        filterJob?.cancel(); filterJob = null
        filterGeneration++
        // build 144/145: bijwerken hoort bij de vorige server
        autoGen++
        source = null
        Store.activePlaylistID = null
        stalker = null
        stremioAddons = emptyList()
        episodeCache = emptyMap()
        error = null
        isFiltering = false
        retireEpg(rawLibrary.epg)
        rawLibrary = Library()
        library = Library()
    }

    /** Afspeellijst helemaal verwijderen (profiel, wachtwoord, sessie en cache). */
    fun forget(p: PlaylistSource) {
        Store.delete(p)
        clearCache(p)
        Store.setSecret(sessionKey(p), null)
    }

    // MARK: cache

    /**
     * Build 141: de cache staat in een eigen map (`files/servercache/`), zodat de Google-back-up hem kan overslaan
     * (backup_rules.xml / data_extraction_rules.xml): er staan stream-adressen met inloggegevens in. Een cache van vóór
     * build 141 (`files/cache-<id>.json`) wordt één keer verplaatst.
     */
    private fun cacheFile(p: PlaylistSource): File {
        val dir = File(AppState.appContext.filesDir, "servercache")
        val f = File(dir, "cache-${p.id}.json")
        if (!f.exists()) {
            val legacy = File(AppState.appContext.filesDir, "cache-${p.id}.json")
            if (legacy.exists()) runCatching { dir.mkdirs(); if (!legacy.renameTo(f)) legacy.delete() }
        }
        return f
    }

    /** Build 141: de cache in het nieuwe formaat (catalogus, gids en index apart, zie [CatalogFiles]). */
    private fun cachePaths(p: PlaylistSource): CatalogFiles.Paths {
        val dir = File(AppState.appContext.filesDir, "servercache")
        return CatalogFiles.Paths(File(dir, "lib-${p.id}.json"), File(dir, "epg-${p.id}.bin"), File(dir, "epg-${p.id}.idx"))
    }

    /** Het bestand waarvan de tijd de versie van de cache is: de nieuwe catalogus, anders het oude bestand. */
    private fun stampFile(p: PlaylistSource): File = cachePaths(p).core.takeIf { it.exists() } ?: cacheFile(p)

    /**
     * Gecachete inhoud mét gids (blokkeert: aanroepen buiten de main thread), voor de server die geopend wordt.
     * Build 141: nieuw formaat (catalogus + gids apart); een cache van vóór build 141 wordt gelezen en meteen in het nieuwe
     * formaat herschreven (het oude bestand gaat dan weg).
     */
    fun readCache(p: PlaylistSource): Library? {
        val paths = cachePaths(p)
        if (paths.exists) {
            val core = CatalogFiles.readCore(paths.core, Http.json) ?: return null
            // build 141: de gids blijft op schijf en wordt per zender gelezen (DiskEpg), niet alles in het geheugen
            return core.copy(epg = CatalogFiles.openEpg(paths, Http.json) ?: emptyMap())
        }
        val legacy = CatalogFiles.readLegacy(cacheFile(p), Http.json) ?: return null
        val ok = runCatching { synchronized(cacheWriteLock) { CatalogFiles.write(legacy, paths, Http.json); cacheFile(p).delete() } }.isSuccess
        if (!ok) return legacy
        return legacy.copy(epg = CatalogFiles.openEpg(paths, Http.json) ?: legacy.epg)
    }

    /**
     * Build 141: alleen de titels en zenders van server [p] (zonder gids), voor een server die niet open is: Nieuw op
     * Bingr, Mijn lijst/Verder kijken over alle servers, zoeken, slimme collecties. Tot build 140 kwam de hele gids mee
     * (honderdduizenden programma's per server), soms voor meerdere servers tegelijk: daardoor liep het geheugen op een tv
     * vol na het kiezen van een profiel. Een oud bestand wordt zonder gids gelezen (het `epg`-veld wordt overgeslagen).
     */
    fun readCatalog(p: PlaylistSource): Library? {
        val paths = cachePaths(p)
        return if (paths.exists) CatalogFiles.readCore(paths.core, Http.json) else CatalogFiles.readCore(cacheFile(p), Http.json)
    }

    /**
     * Build 141: de gids van alleen deze zenders ([keys] = epgID of zender-id) van server [p] uit de cache. Een cache in het
     * oude formaat heeft geen index: dan leeg (bij het volgende verversen komt het nieuwe formaat).
     */
    fun epgOf(p: PlaylistSource, keys: Set<String>): Map<String, List<EPGProgram>> {
        if (keys.isEmpty()) return emptyMap()
        if (p.id.equals(source?.id, ignoreCase = true) && !rawLibrary.isEmpty) {
            val e = rawLibrary.epg
            return keys.mapNotNull { k -> e[k]?.let { k to it } }.toMap()
        }
        return runCatching { CatalogFiles.readEpg(cachePaths(p), Http.json, keys) }.getOrDefault(emptyMap())
    }

    /** Leeftijd van de cache in ms (null = geen cache). */
    /** Build 129: tijd van de cache van [p] (0 = geen cache), om een gefilterde kopie te kunnen hergebruiken. */
    fun cacheStamp(p: PlaylistSource): Long = stampFile(p).let { if (it.exists()) it.lastModified() else 0L }

    fun cacheAge(p: PlaylistSource): Long? {
        val f = stampFile(p)
        if (!f.exists()) return null
        return System.currentTimeMillis() - f.lastModified()
    }

    private val cacheWriteLock = Any()

    /** Build 141: alleen in de belastingmodus (StressMode): nepcatalogus wegschrijven in het nieuwe of het oude formaat. */
    fun writeCacheForStress(p: PlaylistSource, lib: Library) {
        if (!StressMode.isActive) return
        synchronized(cacheWriteLock) { CatalogFiles.write(lib, cachePaths(p), Http.json) }
    }
    fun writeLegacyCacheForStress(p: PlaylistSource, lib: Library) {
        if (!StressMode.isActive) return
        synchronized(cacheWriteLock) { cachePaths(p).delete(); CatalogFiles.writeLegacy(lib, cacheFile(p), Http.json) }
    }

    /** Build 141: zoals [writeCache], maar wachtend tot het bestand er staat. */
    private suspend fun writeCacheNow(p: PlaylistSource, lib: Library, notify: Boolean = true) {
        val ok = withContext(Dispatchers.IO) {
            runCatching { synchronized(cacheWriteLock) { CatalogFiles.write(lib, cachePaths(p), Http.json); cacheFile(p).delete() } }.isSuccess
        }
        if (ok) rememberSkipped(p, lib)
        if (ok && notify) withContext(Dispatchers.Main) { catalogVersion++ }
    }

    private fun writeCache(p: PlaylistSource, lib: Library, notify: Boolean = true) {
        AppScope.launch(Dispatchers.IO) {
            // build 141: nieuw formaat (catalogus, gids en index apart); één schrijfactie tegelijk per app
            runCatching {
                synchronized(cacheWriteLock) {
                    CatalogFiles.write(lib, cachePaths(p), Http.json)
                    cacheFile(p).delete()
                }
                rememberSkipped(p, lib)
                // build 130: pas als het bestand er staat (nieuwe tijd), zodat de achtergrond de nieuwe cache leest
                if (notify) withContext(Dispatchers.Main) { catalogVersion++ }
            }
        }
    }

    /** Build 141: de vorige gids op schijf over een minuut dichtdoen (wie hem nu nog leest, kan dat afmaken). */
    private fun retireEpg(old: Map<String, List<EPGProgram>>) {
        val d = old as? DiskEpg ?: return
        AppScope.launch { kotlinx.coroutines.delay(60_000); if (!d.sharesSource(rawLibrary.epg) && !d.sharesSource(library.epg)) d.close() }
    }

    fun clearCache(p: PlaylistSource) { runCatching { cacheFile(p).delete(); cachePaths(p).delete() } }

    // MARK: posters voor de intro

    private const val splashKey = "splash.posters"

    /** Poster-URL's van films/series voor de splash (bewaard zodra een afspeellijst geladen is), geschud. */
    fun splashPosters(): List<String> = runCatching {
        Http.json.decodeFromString(ListSerializer(String.serializer()), Prefs.string(splashKey) ?: "[]")
    }.getOrDefault(emptyList()).shuffled()

    private fun saveSplashPosters(lib: Library) {
        val urls = (lib.movies.take(300).mapNotNull { it.poster } + lib.series.take(200).mapNotNull { it.poster }).shuffled()
        val list = urls.take(40)
        if (list.isEmpty()) return
        Prefs.put(splashKey, Http.json.encodeToString(list))
    }
}

data class SearchResults(
    val channels: List<Channel> = emptyList(),
    val movies: List<Movie> = emptyList(),
    val series: List<Series> = emptyList(),
    val other: List<OtherHit> = emptyList(),
) {
    val isEmpty: Boolean get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty() && other.isEmpty()
}

/** Treffer in een andere afspeellijst. */
data class OtherHit(
    val playlist: PlaylistSource, val itemID: String, val title: String, val subtitle: String, val artwork: String?,
    /** zender (Live TV): in de zoekresultaten altijd na films en series */
    val isLive: Boolean = false,
    /** build 129: de film/serie zelf (met sourceID als hij op een andere server staat), om zonder wisselen te openen */
    val item: Any? = null,
) {
    val id: String get() = "${playlist.id}-$itemID"
}

data class StremioStream(val title: String, val addon: String, val url: String)

/**
 * Build 149: geheugengrens tijdens het inlezen van een grote server (vooral tv's: Android TV heeft vaak 192–256 MB heap).
 * Nadert het geheugen de grens, dan netjes stoppen met een melding in plaats van wegvallen (OOM).
 */
object MemoryGuard {
    private val rt = Runtime.getRuntime()
    fun used(): Long = rt.totalMemory() - rt.freeMemory()
    val check: (Int) -> Unit = Build149Logic.Stream.memoryCheck(0.85, { used() }, { rt.maxMemory() }, { System.gc() })
    fun tooLarge(): ServiceException = ServiceException(L("This server is very large. Hide categories you don't use and it will load faster."))
}


/** Build 178: diagnose: per server hoeveel zenders terugkijken hebben en tot hoeveel dagen (geen namen of adressen) */
object CatchupLog178 {
    private val prefs get() = runCatching {
        AppState.appContext.getSharedPreferences("bingr.catchup178", android.content.Context.MODE_PRIVATE)
    }.getOrNull()

    fun note(server: String, channels: List<Channel>) {
        val (n, d) = Build178Logic.summary(channels.map { it.catchupDays })
        val v = "$n,$d"
        val p = prefs ?: return
        if (p.getString(server.lowercase(), null) != v) p.edit().putString(server.lowercase(), v).apply()
        // build 194: zenders zonder eigen catch-up die via een tweeling terugkijken krijgen
        val t = Build194Logic.Twin.link(channels.map { Build194Logic.Twin.Item(it.id, it.epgID, it.name, it.catchupDays) }).size.toString()
        if (p.getString(server.lowercase() + ".twin", null) != t) p.edit().putString(server.lowercase() + ".twin", t).apply()
    }

    /** Build 194: aantal zenders met terugkijken via een tweeling (null = onbekend) */
    fun twins(server: String): Int? = prefs?.getString(server.lowercase() + ".twin", null)?.toIntOrNull()

    fun get(server: String): Pair<Int, Int>? {
        val v = prefs?.getString(server.lowercase(), null)?.split(',') ?: return null
        if (v.size != 2) return null
        return (v[0].toIntOrNull() ?: return null) to (v[1].toIntOrNull() ?: return null)
    }
}
