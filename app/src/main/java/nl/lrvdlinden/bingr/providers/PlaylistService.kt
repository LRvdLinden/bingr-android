package nl.lrvdlinden.bingr

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
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
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
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
    var library by mutableStateOf(Library()); private set
    var rawLibrary by mutableStateOf(Library()); private set
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
    var episodeCache by mutableStateOf(mapOf<String, List<Episode>>()); private set
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
            // build 128: ook de verborgen categorieën van het profiel
            snapshotFlow { Triple(Store.customizations, Store.parental, Store.hiddenCategories) }.drop(1).collect { applyCustomizations() }
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
            snapshotFlow { Store.activeProfile?.let { "${it.effectiveAgeLimit ?: 0}-${it.strictAgeFilter}" } ?: "" }
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
                setRaw(cached)
                if (!RefreshPolicy.current.isDue(cacheAge(p))) return
                AppScope.launch { refresh(p, showSpinner = false) }
                return
            }
        }
        setRaw(Library())
        refresh(p, showSpinner = true)
    }

    /** true = gelukt. */
    suspend fun refresh(s: PlaylistSource, showSpinner: Boolean): Boolean {
        if (showSpinner) isLoading = true
        val background = !showSpinner && source?.id == s.id
        if (background) updating = true
        try {
            val lib = fetch(s)
            writeCache(s, lib)
            if (source?.id == s.id) {
                setRaw(lib)
                saveSplashPosters(lib)
            }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (source?.id == s.id && rawLibrary.isEmpty) error = Net.message(e)
            return false
        } finally {
            isLoading = false; loadingText = ""
            if (background) updating = false
        }
    }

    /** Inhoud van server [s] ophalen van het netwerk (zonder cache en zonder de stand van de actieve server te wijzigen). */
    private suspend fun fetch(s: PlaylistSource, quiet: Boolean = false): Library {
        fun status(t: String) { if (!quiet) loadingText = t }
        val password = Store.secret(s.passwordKey) ?: ""
        var lib: Library
        when (s.kind) {
            PlaylistKind.M3U -> {
                status(L("Downloading playlist…"))
                val u = Net.urlValue(s.m3uURL) ?: throw Net.badURL()
                val text = Net.text(u)
                status(L("Sorting channels and movies…"))
                lib = withContext(Dispatchers.Default) { M3UParser.library(text, s.id) }
                val epg = Net.urlValue(s.epgURL) ?: M3UParser.epgURL(text)
                if (epg != null) { status(L("Loading TV guide…")); lib = lib.copy(epg = XMLTVParser.load(epg)) }
            }
            PlaylistKind.XTREAM -> {
                status(L("Signing in to server…"))
                val x = XtreamClient(s, password)
                x.authenticate()
                status(L("Fetching live TV, movies and series…"))
                lib = x.library()
                val epg = Net.urlValue(s.epgURL) ?: x.xmltvURL
                if (epg != null) { status(L("Loading TV guide…")); lib = lib.copy(epg = XMLTVParser.load(epg)) }
            }
            PlaylistKind.STALKER -> {
                status(L("Connecting to portal…"))
                val st = (if (source?.id == s.id) stalker else null) ?: StalkerClient(s)
                st.connect()
                status(L("Fetching channels and videos…"))
                lib = st.library()
                Net.urlValue(s.epgURL)?.let { lib = lib.copy(epg = XMLTVParser.load(it)) }
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
            PlaylistKind.STREMIO -> {
                status(L("Loading Stremio add-ons…"))
                val addons = loadStremioAddons(s)
                if (source?.id == s.id) stremioAddons = addons
                status(L("Fetching catalogs…"))
                lib = StremioClient(addons).library()
            }
        }
        return lib
    }

    /**
     * Build 128: ruwe catalogus van server [p] (ook een niet-actieve): de actieve uit het geheugen, anders de cache op dit
     * apparaat, anders (alleen met [allowNetwork]) stil van het netwerk met een time-out (de cache wordt dan bijgewerkt).
     */
    suspend fun catalogOf(p: PlaylistSource, allowNetwork: Boolean, background: Boolean = false): Library? {
        if (p.id.equals(source?.id, ignoreCase = true) && !rawLibrary.isEmpty) return rawLibrary
        withContext(Dispatchers.IO) { runCatching { readCache(p) }.getOrNull() }?.let { return it }
        if (!allowNetwork) return null
        // build 130: op de achtergrond ([background]) hooguit eens per 6 uur per server (ook over herstarts heen); altijd
        // met een time-out. Aangeklikt door de gebruiker = meteen proberen.
        if (background && !AllServersLogic.mayFetch(lastFetch(p), System.currentTimeMillis())) return null
        markFetch(p)
        return try {
            withTimeoutOrNull(AllServersLogic.FETCH_TIMEOUT) { fetch(p, quiet = true) }?.also { writeCache(p, it, notify = false) }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }

    /** Build 130: laatste poging om server [p] (niet de actieve) op de achtergrond van het netwerk te halen. */
    private fun fetchKey(p: PlaylistSource) = "allservers.fetch." + p.id.lowercase(java.util.Locale.ROOT)
    private fun lastFetch(p: PlaylistSource): Long? = Prefs.string(fetchKey(p))?.toLongOrNull()
    private fun markFetch(p: PlaylistSource) = Prefs.put(fetchKey(p), System.currentTimeMillis().toString())

    /** Build 130: een andere server (niet de actieve) nu verversen, stil en met een time-out. true = gelukt. */
    private suspend fun refreshOther(p: PlaylistSource): Boolean = try {
        markFetch(p)
        val lib = withTimeoutOrNull(AllServersLogic.FETCH_TIMEOUT) { fetch(p, quiet = true) }
        if (lib != null) writeCache(p, lib)
        lib != null
    } catch (e: CancellationException) { throw e } catch (_: Exception) { false }

    /** Build 128: de server van een item ([Movie.sourceID] e.d.); null = de actieve. */
    fun sourceFor(id: String?): PlaylistSource? =
        if (id == null) source else Store.playlists.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: source

    private val otherAddons = java.util.concurrent.ConcurrentHashMap<String, List<StremioClient.Addon>>()
    private suspend fun addonsFor(src: PlaylistSource): List<StremioClient.Addon> {
        if (src.id == source?.id) { if (stremioAddons.isEmpty()) stremioAddons = loadStremioAddons(src); return stremioAddons }
        otherAddons[src.id]?.let { return it }
        return loadStremioAddons(src).also { otherAddons[src.id] = it }
    }

    /** Bij terugkeren in de app: verversen als het volgens de instelling tijd is. */
    suspend fun refreshIfDue() {
        val s = source ?: return
        val policy = RefreshPolicy.current
        if (isLoading || policy == RefreshPolicy.MANUAL || !policy.isDue(cacheAge(s))) return
        refresh(s, showSpinner = false)
    }

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
        )
    }

    /**
     * Past aanpassingen, kinderslot en de leeftijdsgrens van het actieve profiel toe op [rawLibrary] → [library].
     * Grote lijsten worden op Dispatchers.Default gefilterd; bij een profiel met leeftijdsgrens is [library]
     * intussen leeg en [isFiltering] true.
     */
    fun applyCustomizations(quiet: Boolean = false) {
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
        // kleine lijsten: meteen; grote lijsten: op de achtergrond
        if (raw.channels.size + raw.movies.size + raw.series.size < 3000) {
            library = apply(raw, ctx)
            isFiltering = false
            return
        }
        // kinderprofiel/leeftijdsgrens: niets ongefilterds laten zien terwijl er gefilterd wordt
        if (ctx.ageLimit != null) library = Library()
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
        val epg = if (old.epg === new.epg || old.epg == new.epg) old.epg else new.epg
        if (ch === old.channels && mv === old.movies && se === old.series && epg === old.epg) return old
        return Library(ch, mv, se, epg)
    }

    /** Oude aanroep: filteren met de huidige kinderslot-instellingen (op de main thread). */
    fun apply(raw: Library, c: Customization, playlist: String, ageLimit: Int? = null, strict: Boolean = false): Library {
        val pc = Store.parental
        return apply(raw, FilterContext(c, playlist, ageLimit, strict, pc.enabled, pc.lockedGroups, pc.autoLockAdult,
            ParentalControl.isUnlocked, Store.hiddenCategoryNames(playlist)))
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
        fun visible(id: String, g: String): Boolean {
            if (id in c.hidden) return false
            groupOK[g]?.let { return it }
            val m = c.merged[g] ?: g
            // build 128: categorieën aan/uit per profiel (hiddenCategories), niet meer "group:" uit de aanpassingen van de server
            val ok = g !in ctx.hiddenCategories && m !in ctx.hiddenCategories &&
                !isLocked(g) && !isLocked(m)
            groupOK[g] = ok
            return ok
        }
        // leeftijd: TMDB (Kijkwijzer eerst, AgeRatings) en anders OMDb (Metadata); beide caches zijn thread-veilig
        fun ageOK(md: TitleMetadata?, info: AgeRating?, g: String): Boolean =
            ageLimit == null || allowed(md?.rated, g, md?.genre, info)

        var channels = raw.channels.filter { visible(it.id, it.group) }
            .map { ch -> ch.copy(group = group(ch.group), name = c.renamed[ch.id] ?: ch.name) }
        // kinderprofiel / leeftijdsgrens: geen volwassenen-zenders (streng: alleen kinderzenders)
        if (ageLimit != null && !ctx.unlocked) channels = channels.filter { ParentalControl.channelAllowedFor(it.name, it.group, ageLimit, ctx.strict) }
        val movies = raw.movies.filter {
            visible(it.id, it.group) && (ageLimit == null || ageOK(Metadata.cached(it), AgeRatings.cached(it), it.group))
        }.map { m -> m.copy(group = group(m.group), title = c.renamed[m.id] ?: m.title) }
        val series = raw.series.filter {
            visible(it.id, it.group) && (ageLimit == null || ageOK(Metadata.cached(it), AgeRatings.cached(it), it.group))
        }.map { se -> se.copy(group = group(se.group), title = c.renamed[se.id] ?: se.title) }

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
        val ch = (AllServers.item("${s.id}|$channelID") as? Channel) ?: return null
        if (ch.catchupDays <= 0) return null
        val shift = (s.catchupOffsets[channelID] ?: 0) * 60_000L
        // build 129: MAC-portal: de link komt pas bij het afspelen ([resolveArchive]), met een eigen portalverbinding
        if (s.kind == PlaylistKind.STALKER) return AllServersLogic.archiveURL(s.id, channelID, start + shift, end + shift)
        if (s.kind != PlaylistKind.XTREAM) return null
        return XtreamClient(s, Store.secret(s.passwordKey) ?: "").catchupURL(ch, start + shift, end + shift)
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
        if (s.kind != PlaylistKind.XTREAM && s.kind != PlaylistKind.STALKER) return null
        val ch = rawLibrary.channels.firstOrNull { it.id == channelID } ?: return null
        if (ch.catchupDays <= 0) return null
        val shift = (Store.playlists.firstOrNull { it.id == s.id }?.catchupOffsets?.get(channelID) ?: 0) * 60_000L
        // build 129: MAC-portal (ook de actieve server): de link komt pas bij het afspelen ([resolveArchive])
        if (s.kind == PlaylistKind.STALKER) return AllServersLogic.archiveURL(s.id, channelID, start + shift, end + shift)
        return XtreamClient(s, Store.secret(s.passwordKey) ?: "").catchupURL(ch, start + shift, end + shift)
    }

    suspend fun shortEPG(c: Channel): List<EPGProgram> {
        val st = stalker
        if (st != null && library.epg[c.epgID ?: c.id] == null) {
            val p = st.shortEPG(c.id)
            if (p.isNotEmpty()) {
                rawLibrary = rawLibrary.copy(epg = rawLibrary.epg + (c.id to p))
                library = library.copy(epg = library.epg + (c.id to p))
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
        val currentID = source?.id
        val others = Store.playlists.filter { it.id != currentID }
        // andere afspeellijsten: zelfde verbergen/kinderslot/leeftijdsgrens als het actieve profiel
        val contexts = others.associate { it.id to context(it) }
        return withContext(Dispatchers.Default) {
            fun m(s: String) = Net.fold(s).contains(t)
            // films én series én zenders, uit álle categorieën
            val r = SearchResults(
                channels = lib.channels.asSequence().filter { m(it.name) }.take(60).toList(),
                movies = lib.movies.asSequence().filter { m(it.title) }.take(200).toList(),
                series = lib.series.asSequence().filter { m(it.title) }.take(200).toList(),
            )
            if (!allPlaylists) return@withContext r
            // build 129: treffers van de andere servers van het profiel in dezelfde rijen (zelfde filters als het
            // actieve profiel), ontdubbeld zoals in build 128; openen/afspelen via die server, zonder te wisselen
            val ch = ArrayList<List<Channel>>().apply { add(r.channels) }
            val mv = ArrayList<List<Movie>>().apply { add(r.movies) }
            val se = ArrayList<List<Series>>().apply { add(r.series) }
            for (p in others) {
                val ctx = contexts[p.id] ?: continue
                val olib = AllServers.otherLibrary(p, ctx, allowNetwork = false) ?: continue
                val c = olib.channels.asSequence().filter { m(it.name) }.take(40).map { it.copy(sourceID = p.id) }.toList()
                c.forEach { AllServers.remember(it, olib.epg[it.epgID ?: it.id]) }
                ch += c
                mv += olib.movies.asSequence().filter { m(it.title) }.take(120).map { it.copy(sourceID = p.id) }.toList()
                se += olib.series.asSequence().filter { m(it.title) }.take(120).map { it.copy(sourceID = p.id) }.toList()
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
        source = null
        Store.activePlaylistID = null
        stalker = null
        stremioAddons = emptyList()
        episodeCache = emptyMap()
        error = null
        isFiltering = false
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

    private fun cacheFile(p: PlaylistSource) = File(AppState.appContext.filesDir, "cache-${p.id}.json")

    /**
     * Gecachete inhoud (blokkeert: aanroepen buiten de main thread). build 97: direct uit het bestand lezen
     * (decodeFromStream) in plaats van eerst het hele bestand als één String in het geheugen.
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    fun readCache(p: PlaylistSource): Library? {
        val f = cacheFile(p)
        if (!f.exists()) return null
        return runCatching {
            f.inputStream().buffered(64 * 1024).use { Http.json.decodeFromStream(Library.serializer(), it) }
        }.getOrNull()
    }

    /** Leeftijd van de cache in ms (null = geen cache). */
    /** Build 129: tijd van de cache van [p] (0 = geen cache), om een gefilterde kopie te kunnen hergebruiken. */
    fun cacheStamp(p: PlaylistSource): Long = cacheFile(p).let { if (it.exists()) it.lastModified() else 0L }

    fun cacheAge(p: PlaylistSource): Long? {
        val f = cacheFile(p)
        if (!f.exists()) return null
        return System.currentTimeMillis() - f.lastModified()
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun writeCache(p: PlaylistSource, lib: Library, notify: Boolean = true) {
        val f = cacheFile(p)
        AppScope.launch(Dispatchers.IO) {
            // build 97: direct naar het bestand (encodeToStream) en een eigen tijdelijk bestand per keer, zodat twee
            // gelijktijdige schrijfacties elkaar niet in de weg zitten
            val tmp = File(f.parentFile, f.name + "." + System.nanoTime() + ".tmp")
            runCatching {
                tmp.outputStream().buffered(64 * 1024).use { Http.json.encodeToStream(Library.serializer(), lib, it) }
                if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) tmp.delete() }
                // build 130: pas als het bestand er staat (nieuwe tijd), zodat de achtergrond de nieuwe cache leest
                if (notify) withContext(Dispatchers.Main) { catalogVersion++ }
            }.onFailure { tmp.delete() }
        }
    }

    fun clearCache(p: PlaylistSource) { runCatching { cacheFile(p).delete() } }

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

/** Hoe vaak afspeellijsten (incl. gids) automatisch ververst worden. Prefs "playlist.refresh". */
enum class RefreshPolicy(val raw: String) {
    MANUAL("manual"), LAUNCH("launch"), SIX_HOURS("sixHours"), DAILY("daily"), WEEKLY("weekly");

    val title: String
        get() = when (this) {
            MANUAL -> L("Manually")
            LAUNCH -> L("Every time the app opens")
            SIX_HOURS -> L("Every 6 hours")
            DAILY -> L("Daily")
            WEEKLY -> L("Weekly")
        }

    /** [ageMs] = leeftijd van de cache in ms (null = geen cache). */
    fun isDue(ageMs: Long?): Boolean {
        val age = ageMs ?: return true
        return when (this) {
            MANUAL -> false
            LAUNCH -> age > 60_000
            SIX_HOURS -> age > 6 * 3_600_000L
            DAILY -> age > 24 * 3_600_000L
            WEEKLY -> age > 7 * 24 * 3_600_000L
        }
    }

    companion object {
        var current: RefreshPolicy
            get() = entries.firstOrNull { it.raw == Prefs.string("playlist.refresh") } ?: DAILY
            set(v) = Prefs.put("playlist.refresh", v.raw)
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
