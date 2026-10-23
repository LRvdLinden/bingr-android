package nl.lrvdlinden.bingr

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * App-brede scope voor achtergrondwerk dat schermen overleeft. Draait op de main thread (Compose-state en de
 * sync-objecten worden alleen daar gewijzigd); build 97: zwaar werk (JSON, samenvoegen, bestanden) gaat met
 * withContext naar Dispatchers.Default/IO, zie Account.post, StateSync, ProfileSync en Store.writeIn.
 */
val AppScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/** Globale navigatiestatus (tegenhanger van AppState op iOS). */
object AppState {
    enum class Stage { SPLASH, AUTH, PICKER, MAIN }

    lateinit var appContext: Context
    /** true op Android TV / Google TV / Fire TV (bediening met afstandsbediening) */
    var isTV by mutableStateOf(false)

    var stage by mutableStateOf(Stage.SPLASH)
    /** Wat er nu speelt (niet-null = spelerscherm open) */
    var playing by mutableStateOf<PlayableItem?>(null)
    var liveZapList by mutableStateOf(listOf<Channel>())
    var showMultiview by mutableStateOf(false)
    var multiviewStart by mutableStateOf(listOf<Channel>())
    /** Na wisselen van afspeellijst (globaal zoeken / herinnering): dit item openen */
    var pendingItemID by mutableStateOf<String?>(null)
    /** Na het kiezen van een profiel: laatst bekeken zender starten zodra de zenders er zijn (build 52) */
    var pendingLastChannel by mutableStateOf(false)
    /** Beeld-in-beeld actief (Android PiP): spelerscherm toont dan alleen video */
    var inPiP by mutableStateOf(false)

    var lastPlaylistID: String?
        get() = Prefs.string("lastPlaylistID")
        set(v) = Prefs.put("lastPlaylistID", v)

    /** Build zonder Supabase-sleutels: "Continue without account" gekozen (alleen deze sessie). */
    var skippedAccount by mutableStateOf(false)

    /** Inloggen is verplicht: geen sessie → inlogscherm, anders "Wie kijkt er?" (en op de achtergrond vernieuwen). */
    val needsAccount: Boolean get() = !Account.isSignedIn && !(skippedAccount && !Account.configured)

    fun splashFinished() {
        if (needsAccount) { stage = Stage.AUTH; return }
        stage = Stage.PICKER
        Account.refreshOnLaunch()
    }

    /**
     * Na inloggen (of "Continue without account") → apparaat registreren (limiet van de server; bij de limiet
     * eerst het scherm "Too many devices") → profielen en servers van het account ophalen → "Wie kijkt er?".
     */
    fun signedIn() {
        if (!Account.isSignedIn) { stage = Stage.PICKER; return }
        AppScope.launch {
            // ander account dan het vorige op dit apparaat: eerst de lokale gegevens van dat account wissen
            SourceSync.checkAccountSwitch()
            // limiet: het scherm "Too many devices" roept na een geslaagde registratie opnieuw signedIn() aan
            if (Account.ensureRegistered() == Account.RegisterResult.LIMIT) return@launch
            if (!Account.isSignedIn) return@launch
            // app-sleutels van de server ophalen (op de achtergrond)
            Account.launchConfigFetch()
            // abonnement (build 88): Gratis of Lifetime
            Entitlements.launchFetch(force = true)
            // even wachten op de profielen en servers van het account (max. 8 seconden; fouten zijn stil)
            withTimeoutOrNull(8_000) {
                ProfileSync.syncNow()
                // servers en alle overige gegevens (Trakt, OpenSubtitles, instellingen, per profiel; build 44) tegelijk
                coroutineScope {
                    launch { SourceSync.syncNow() }
                    launch { StateSync.syncNow() }
                }
            }
            if (stage == Stage.AUTH && Account.isSignedIn) stage = Stage.PICKER
        }
    }

    /** Uitgelogd, account verwijderd of sessie ongeldig → inlogscherm (speler dicht). */
    fun signedOut() {
        playing = null
        showMultiview = false
        // build 97: upgradevenster en "Too many devices" horen bij het vorige account: dicht
        Upgrade.close()
        Account.deviceLimit = null
        if (!Account.configured && skippedAccount) return
        stage = Stage.AUTH
    }

    /**
     * Profiel gekozen ("Wie kijkt er?") → profiel actief maken en de server van het hoofdaccount openen.
     * Niet-hoofdprofielen: kinderslot weer dicht; Trakt alleen voor het hoofdaccount.
     * (Pincode van het profiel moet al gecontroleerd zijn, zie ParentalControl.verify(pin, profile).)
     */
    fun open(profile: ViewerProfile) {
        // build 140: weer in een kinderprofiel → bij het verlaten opnieuw de rekensom
        if (profile.kids) KidsGate.kidsOpened()
        // build 141: het werk aan de andere servers wacht weer tot het nieuwe profiel (actieve server, Home) klaar is
        AllServers.profileOpened()
        StressMode.profileOpened(profile)
        Store.selectProfile(profile)
        pendingLastChannel = PlayerSettings.resumeLastChannel
        val main = Store.isMain(profile)
        if (!main) ParentalControl.lock()   // ontgrendeling van het hoofdaccount niet meenemen
        Trakt.applyProfile(main)
        val src = Store.mainSource ?: run { stage = Stage.PICKER; return }
        if (Playlists.source?.id == src.id && !Playlists.rawLibrary.isEmpty) {
            pendingItemID = null
            stage = Stage.MAIN
            Playlists.applyCustomizations()
            return
        }
        open(src)
    }

    /**
     * Deze server openen. Vanuit het hoofdprofiel wordt hij de hoofdserver van alle profielen; build 130: een ander
     * profiel mag ook elke server van het account kiezen, maar verandert de hoofdserver van het account niet.
     */
    fun open(p: PlaylistSource, thenShow: String? = null) {
        lastPlaylistID = p.id
        if (Store.mainSourceID != p.id && Store.canManage) Store.setMainSource(p.id)
        pendingItemID = thenShow
        stage = Stage.MAIN
        AppScope.launch { Playlists.select(p) }
    }

    /** Het actieve profiel is op een ander apparaat verwijderd: speler dicht en terug naar "Wie kijkt er?". */
    fun profileRemoved() {
        playing = null
        showMultiview = false
        if (stage == Stage.MAIN) stage = Stage.PICKER
    }

    /** Terug naar "Wie kijkt er?" (profiel wisselen). */
    fun switchPlaylist() { stage = Stage.PICKER }

    fun play(item: PlayableItem, zap: List<Channel> = emptyList()) {
        StartTiming.begin(item.title)
        liveZapList = zap
        // leeftijdscontrole die nog loopt (build 59): hoort bij deze titel, de speler wacht er bij het eerste beeld op
        playingAgeCheck = pendingAgeCheck
        pendingAgeCheck = null
        playing = item
    }

    /**
     * Kinderprofiel (build 59): de detailpagina zet hier de leeftijdscontrole (TMDB) neer als die nog niet in de
     * cache staat; [play] geeft hem door aan de speler, die hem tegelijk met het voorbereiden uitvoert.
     */
    var pendingAgeCheck: (suspend () -> Boolean)? = null
    /** Leeftijdscontrole voor wat er nu gaat spelen (eenmalig opgehaald door het spelerscherm). */
    var playingAgeCheck: (suspend () -> Boolean)? = null

    fun openMultiview(start: List<Channel>, candidates: List<Channel>) {
        multiviewStart = start
        liveZapList = candidates
        showMultiview = true
    }
}
