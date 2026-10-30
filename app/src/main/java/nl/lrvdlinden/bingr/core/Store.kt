package nl.lrvdlinden.bingr

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.serializer
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Alle gebruikersgegevens (Android-tegenhanger van CloudStore op iOS).
 * Compose-state: schermen die dit lezen verversen vanzelf.
 * Opslag: JSON-bestanden in de app-map. Tussen apparaten delen kan via [exportJSON]/[importJSON].
 *
 * ## Kijkprofielen ("Wie kijkt er?")
 * Eén hoofdaccount met de server(s) ([playlists], [mainSource]), Trakt en OpenSubtitles; daaronder kijkprofielen
 * ([profiles], [ViewerProfile]). Per profiel apart (bestanden in `store/profile-<id>/`):
 * [favorites], [myList], [progress] (Verder kijken), [localWatched], [recentChannels], [history],
 * plus kijkvoorkeuren, avatar/foto ([photo] met het profiel-id), pincode en leeftijdsgrens.
 * Gedeeld: server(s), [customizations], [parental], [reminders], NAS, Trakt, OpenSubtitles.
 *
 * De bestaande API's ([toggleFavorite], [setProgress], [addHistory], …) werken altijd op het **actieve** profiel
 * ([activeProfile]); wissel met [selectProfile] (of beter `AppState.open(profile)`).
 */
object Store {
    private lateinit var dir: File
    private lateinit var secrets: SharedPreferences

    var playlists by mutableStateOf(listOf<PlaylistSource>()); private set
    /** "<playlistID>|<itemID>" — van het actieve profiel */
    var favorites by mutableStateOf(setOf<String>()); private set
    /** "<playlistID>|<itemID>" — van het actieve profiel */
    var myList by mutableStateOf(setOf<String>()); private set
    /** key: item-id — van het actieve profiel */
    var progress by mutableStateOf(mapOf<String, ProgressEntry>()); private set
    /** item-id's en [episodeKey]'s — van het actieve profiel */
    var localWatched by mutableStateOf(setOf<String>()); private set
    /** "<playlistID>|<channelID>" — van het actieve profiel */
    var recentChannels by mutableStateOf(listOf<String>()); private set
    var customizations by mutableStateOf(mapOf<String, Customization>()); private set
    var parental by mutableStateOf(ParentalSettings()); private set
    var reminders by mutableStateOf(listOf<ProgramReminder>()); private set
    /** Kijkgeschiedenis van het actieve profiel */
    var history by mutableStateOf(listOf<HistoryEntry>()); private set
    /**
     * Build 140: verborgen categorieën voor het hele account ("<server-id kleine letters>|<categorienaam>", alle servers),
     * voor alle profielen die geen kinderprofiel zijn. Een kinderprofiel: leeg (daar bepaalt het kinderfilter alles).
     */
    val hiddenCategories: Set<String> get() = if (activeProfile?.kids == true) emptySet() else accountHidden
    /** Build 140: de account-set zelf (zonder de kinderregel), voor het categorieënscherm en de sync. */
    var accountHidden by mutableStateOf(setOf<String>()); private set
    /** Build 140: per sleutel wanneer hij aan/uit is gezet (section `account`, zie [StateSync]). */
    private var accountMarks: Map<String, Build140Logic.Mark> = emptyMap()
    /** Build 140: snel opzoeken per server (kleine letters id → namen). */
    private var hiddenIndex: Map<String, Set<String>> = emptyMap()
    /** Build 128: de oude per-profiel-set van het actieve profiel (wordt nog gesynct voor oudere apps, niet meer gebruikt). */
    private var profileHiddenCats = setOf<String>()
    /** Startscherm (build 51) van het actieve profiel */
    var activeHomeLayout by mutableStateOf(HomeLayout()); private set
    var nasServers by mutableStateOf(listOf<NASServer>()); private set
    /** verandert als een profielfoto wijzigt (zodat afbeeldingen opnieuw laden) */
    var photoVersion by mutableStateOf(0); private set
    /** De server (bron) die nu open is (gezet door Playlists.select) */
    var activePlaylistID: String? = null

    // MARK: profielen -------------------------------------------------------------

    /** Alle kijkprofielen (volgorde = volgorde in "Wie kijkt er?") */
    var profiles by mutableStateOf(listOf<ViewerProfile>()); private set
    /** De server die alle profielen gebruiken */
    var mainSourceID by mutableStateOf<String?>(null); private set
    /** Gekozen profiel op dít apparaat (Prefs "activeProfileID") */
    var activeProfileID by mutableStateOf<String?>(null); private set
    private var mainProfileIDStored by mutableStateOf<String?>(null)

    /** Het hoofdaccount: alleen dat profiel beheert server, Trakt, OpenSubtitles, kinderslot, categorieën en profielen. */
    val mainProfileID: String?
        get() {
            val m = mainProfileIDStored
            if (m != null && profiles.any { it.id == m }) return m
            return profiles.firstOrNull()?.id
        }
    val mainProfile: ViewerProfile? get() = mainProfileID?.let { id -> profiles.firstOrNull { it.id == id } }
    val activeProfile: ViewerProfile? get() = activeProfileID?.let { id -> profiles.firstOrNull { it.id == id } }
    fun isMain(p: ViewerProfile): Boolean = p.id == mainProfileID
    fun isMain(profileID: String?): Boolean = profileID != null && profileID == mainProfileID
    /** Is het actieve profiel het hoofdaccount? */
    val isMainActive: Boolean get() = activeProfileID != null && activeProfileID == mainProfileID
    /** Mag het actieve profiel beheren (hoofdaccount, of nog geen profiel gekozen/aanwezig)? */
    val canManage: Boolean get() = isMainActive || activeProfileID == null || profiles.isEmpty()
    /** De server van het hoofdaccount (of de eerste) */
    val mainSource: PlaylistSource? get() = playlists.firstOrNull { it.id == mainSourceID } ?: playlists.firstOrNull()

    /** Build 140: opslag van de account-brede categorieën (sleutel → tijd), aan en verwijderd. */
    @Serializable
    private data class AccountCats(val on: Map<String, Long> = emptyMap(), val off: Map<String, Long> = emptyMap())

    @Serializable
    private data class ProfileMeta(val mainSourceID: String? = null, val mainProfileID: String? = null)

    @Serializable
    data class ProfileData(
        val favorites: Set<String> = emptySet(),
        val myList: Set<String> = emptySet(),
        val progress: Map<String, ProgressEntry> = emptyMap(),
        val watched: Set<String> = emptySet(),
        val recent: List<String> = emptyList(),
        val history: List<HistoryEntry> = emptyList(),
        /** build 128: verborgen categorieën ("<server-id>|<naam>") */
        val hiddenCategories: Set<String> = emptySet(),
    )

    /** Per-profiel-bestanden (vroeger in store/, nu in store/profile-<id>/) */
    private val profileFiles = listOf("favorites", "mylist", "progress", "watched", "recent", "history", "hiddencats")

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "store").apply { mkdirs() }
        secrets = ctx.getSharedPreferences("bingr.secrets", Context.MODE_PRIVATE)
        load()
        // build 97: geheimen van vóór build 97 (platte tekst) eenmalig versleutelen, op de achtergrond
        writer.execute { runCatching { migrateSecrets() } }
    }

    // MARK: build 97: bestanden schrijven buiten de main thread

    /** Eén schrijf-thread: opdrachten gaan op volgorde, zodat de laatste versie van een bestand altijd wint. */
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "bingr-store").apply { isDaemon = true } }
    /** Nog niet geschreven waarden (pad → waarde), zodat lezen direct na schrijven de nieuwe versie geeft. */
    private val pending = ConcurrentHashMap<String, Any>()

    /** Bestandswerk van anderen (bijv. de metabestanden van StateSync) in dezelfde volgorde als de Store-bestanden. */
    fun enqueueIO(block: () -> Unit) { writer.execute { runCatching(block) } }

    /** Wachten tot alle schrijfopdrachten klaar zijn (vóór mappen verwijderen of hernoemen; hooguit 5 s). */
    fun flushWrites() { runCatching { writer.submit { }.get(5, TimeUnit.SECONDS) } }

    // MARK: opslaan/laden

    private fun profileDir(id: String?): File =
        if (id == null) dir else File(dir, "profile-$id").apply { mkdirs() }
    /** Map van het actieve profiel (zonder profiel: de oude map, wordt bij het eerste profiel overgenomen) */
    private val pdir: File get() = profileDir(activeProfileID)

    private inline fun <reified T> readIn(d: File, name: String, def: T): T {
        val f = File(d, "$name.json")
        // build 97: wat nog op de schrijf-thread wacht is de nieuwste versie
        pending[f.path]?.let { p -> (p as? T)?.let { return it } }
        return runCatching { Http.json.decodeFromString<T>(f.readText()) }.getOrDefault(def)
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> writeIn(d: File, name: String, value: T) {
        val f = File(d, "$name.json")
        val v: Any = value ?: return
        val ser = serializer<T>() as KSerializer<Any>
        // build 97: JSON maken en schrijven op de schrijf-thread (niet meer op de main thread)
        pending[f.path] = v
        writer.execute {
            // build 60: eerst naar een tijdelijk bestand en dan hernoemen (atomair), zodat een app die midden in het
            // schrijven stopt (update, geheugen vol, afsluiten) nooit een half bestand achterlaat (= profielen of lijsten kwijt)
            runCatching {
                val tmp = File(d, "$name.json.tmp")
                tmp.writeText(Http.json.encodeToString(ser, v))
                if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) tmp.delete() }
            }
            pending.remove(f.path, v)
        }
    }

    private inline fun <reified T> read(name: String, def: T): T = readIn(dir, name, def)
    private inline fun <reified T> write(name: String, value: T) = writeIn(dir, name, value)
    /** Schrijven in de map van het actieve profiel */
    private inline fun <reified T> pwrite(name: String, value: T) = writeIn(pdir, name, value)

    fun load() {
        playlists = read("playlists", emptyList())
        customizations = read("custom", emptyMap())
        parental = read("parental", ParentalSettings())
        reminders = read<List<ProgramReminder>>("reminders", emptyList()).filter { it.start > System.currentTimeMillis() - 3_600_000 }
        nasServers = read("nas", emptyList())
        read("accountcats", AccountCats()).let { a ->
            setAccountMarks(a.on.mapValues { Build140Logic.Mark(it.value, true) } + a.off.mapValues { Build140Logic.Mark(it.value, false) }, save = false)
        }

        profiles = read("profiles", emptyList())
        val meta = read("profilemeta", ProfileMeta())
        mainSourceID = meta.mainSourceID
        mainProfileIDStored = meta.mainProfileID
        activeProfileID = Prefs.string("activeProfileID")
        migrateToProfiles()
        ensureMainProfile()
        if (activeProfileID != null && profiles.none { it.id == activeProfileID }) {
            activeProfileID = mainProfileID
            Prefs.put("activeProfileID", activeProfileID)
        }
        loadProfileData()
    }

    private fun loadProfileData() {
        val d = pdir
        favorites = readIn(d, "favorites", emptySet())
        myList = readIn(d, "mylist", emptySet())
        progress = readIn(d, "progress", emptyMap())
        localWatched = readIn(d, "watched", emptySet())
        recentChannels = readIn(d, "recent", emptyList())
        history = readIn(d, "history", emptyList())
        profileHiddenCats = readIn(d, "hiddencats", emptySet())
        activeHomeLayout = readIn(d, "homelayout", HomeLayout())
    }

    private fun saveMeta() = write("profilemeta", ProfileMeta(mainSourceID, mainProfileIDStored))

    /** Gegevens van vóór de profielen (store/<naam>.json) naar de map van dit profiel verplaatsen (alleen het hoofdaccount). */
    private fun adoptLegacyData(profileID: String) {
        flushWrites()   // build 97: eerst alles wat nog geschreven moet worden
        val target = profileDir(profileID)
        for (name in profileFiles) {
            val src = File(dir, "$name.json")
            if (!src.exists()) continue
            val dst = File(target, "$name.json")
            if (dst.exists()) { src.delete(); continue }
            if (!src.renameTo(dst)) runCatching { src.copyTo(dst, overwrite = true); src.delete() }
        }
    }

    /** Bestaande afspeellijsten (vroeger = profielen) eenmalig omzetten naar kijkprofielen (zoals iOS). */
    private fun migrateToProfiles() {
        if (File(dir, "profiles.json").exists() || profiles.isNotEmpty() || playlists.isEmpty()) return
        val list = playlists.map { p ->
            val prof = ViewerProfile(name = p.name, avatar = p.avatar, ageLimit = p.ageLimit)
            val photo = photoFile(p.id)
            if (photo.exists()) runCatching { photo.copyTo(photoFile(prof.id), overwrite = true) }
            prof
        }
        profiles = list
        write("profiles", list)
        mainProfileIDStored = list.first().id
        ProfileSync.profilesChanged()
        val last = Prefs.string("lastPlaylistID")
        mainSourceID = playlists.firstOrNull { it.id == last }?.id ?: playlists.first().id
        saveMeta()
        adoptLegacyData(list.first().id)
        if (activeProfileID == null) {
            activeProfileID = list.first().id
            Prefs.put("activeProfileID", activeProfileID)
        }
    }

    /** Er is een server maar (nog) geen profiel (bijv. na importeren): hoofdprofiel aanmaken. */
    private fun ensureMainProfile() {
        if (mainSourceID == null || playlists.none { it.id == mainSourceID }) {
            val s = playlists.firstOrNull()
            if (s?.id != mainSourceID) { mainSourceID = s?.id; saveMeta() }
        }
        if (profiles.isNotEmpty() || playlists.isEmpty()) return
        val src = mainSource ?: return
        val main = ViewerProfile(name = src.name, avatar = src.avatar)
        val photo = photoFile(src.id)
        if (photo.exists()) runCatching { photo.copyTo(photoFile(main.id), overwrite = true) }
        profiles = listOf(main)
        write("profiles", profiles)
        mainProfileIDStored = main.id
        saveMeta()
        ProfileSync.profilesChanged()
        adoptLegacyData(main.id)
        if (activeProfileID == null) {
            activeProfileID = main.id
            Prefs.put("activeProfileID", activeProfileID)
        }
    }

    /** Demo-gegevens van de screenshotmodus (ScreenshotMode.kt). */
    data class ScreenshotData(
        val playlists: List<PlaylistSource>,
        val profiles: List<ViewerProfile>,
        val mainProfile: String,
        val active: String,
        val favorites: Set<String>,
        val myList: Set<String>,
        val progress: Map<String, ProgressEntry>,
        val watched: Set<String>,
        val recent: List<String>,
        val hiddenCategories: Set<String>,
        val homeLayout: HomeLayout,
    )

    /** Screenshotmodus (alleen de screenshot-build): demo-gegevens alleen in het geheugen, nooit opgeslagen of gesynchroniseerd. */
    fun applyScreenshotDemo(d: ScreenshotData) {
        if (!Screenshots.isActive) return
        playlists = d.playlists
        profiles = d.profiles
        mainProfileIDStored = d.mainProfile
        mainSourceID = d.playlists.firstOrNull()?.id
        activeProfileID = d.active
        favorites = d.favorites
        myList = d.myList
        progress = d.progress
        localWatched = d.watched
        recentChannels = d.recent
        history = emptyList()
        accountHidden = d.hiddenCategories.map { Build140Logic.CatMarks.norm(it) }.toSet()
        hiddenIndex = Build140Logic.CatMarks.index(accountHidden)
        activeHomeLayout = d.homeLayout
        customizations = emptyMap()
        parental = ParentalSettings()
        reminders = emptyList()
    }

    /** Dit profiel actief maken: eigen lijsten, voortgang, geschiedenis en kijkvoorkeuren laden. */
    fun selectProfile(p: ViewerProfile) {
        activeProfileID = p.id
        Prefs.put("activeProfileID", p.id)
        applyPreferences(p)
        loadProfileData()
    }

    /** Kijkvoorkeuren van dit profiel actief maken (automatische ondertitels, talen). */
    private fun applyPreferences(p: ViewerProfile): Unit = StateSync.withoutSettingsChange {
        // nieuw/ander profiel zonder eigen keuze → standaardwaarden, niet die van een ander profiel
        val fresh = !isMain(p)
        val auto = p.autoSubtitles
        if (auto != null) Prefs.put("autoSubtitles", auto) else if (fresh) Prefs.put("autoSubtitles", false)
        // build 91: zonder eigen keuze (`prefs` null) geldt `settings.shared` (docs/ACCOUNT.md), voor elk profiel.
        // Onbekend (nog nooit gesynchroniseerd): het hoofdprofiel houdt wat er staat, een ander profiel de standaard.
        val sharedSub = StateSync.sharedViewing("subtitleLanguage")
        // build 59: ondertitelstand (off/whenNeeded/always) van dit profiel, anders die van het account, anders de standaard
        val mode = p.subtitleMode
            ?: TrackPreferences.normalizedMode(StateSync.sharedViewing("autoSubtitles"))
                // oud "ondertitels uit" (subtitleLanguage "off") = geen automatische ondertitels (build 60)
                ?: (if (sharedSub == "off") TrackPreferences.AUTO_OFF else null)
        // zonder eigen stand: oud "ondertitels uit" (streamSubtitleLanguage "off") = geen automatische ondertitels (build 60)
        if (mode != null) TrackPreferences.autoSubtitles = mode
        else if (fresh) TrackPreferences.autoSubtitles =
            if (p.streamSubtitleLanguage == "off") TrackPreferences.AUTO_OFF else TrackPreferences.AUTO_WHEN_NEEDED
        val audio = p.audioLanguage ?: StateSync.sharedViewing("audioLanguage")
        if (audio != null) TrackPreferences.audio = audio else if (fresh) TrackPreferences.audio = ""
        val sub = p.streamSubtitleLanguage ?: sharedSub
        if (sub != null) TrackPreferences.subtitle = sub else if (fresh) TrackPreferences.subtitle = ""
        val os = p.subtitleLanguage ?: StateSync.sharedViewing("openSubtitlesLanguage")
        if (os != null) OpenSubtitles.defaultLanguage = os
        else if (fresh) OpenSubtitles.defaultLanguage = Localizer.language.take(2)
        Unit
    }

    /** Kijkvoorkeur van het actieve profiel opslaan, bijv. `Store.updateActivePreferences { it.copy(autoSubtitles = true) }`. */
    fun updateActivePreferences(change: (ViewerProfile) -> ViewerProfile) {
        val p = activeProfile ?: return
        val n = change(p)
        if (n != p) {
            upsertProfile(n)
            // kijkvoorkeuren gaan per profiel naar het account (build 44)
            if (n.autoSubtitles != p.autoSubtitles || n.autoSubtitleMode != p.autoSubtitleMode || n.subtitleLanguage != p.subtitleLanguage ||
                n.audioLanguage != p.audioLanguage || n.streamSubtitleLanguage != p.streamSubtitleLanguage) StateSync.prefsChanged(n.id)
        }
    }

    /** Kijkvoorkeuren uit het account (build 44): profiel bijwerken en, als het actief is, meteen toepassen. */
    fun applySyncedPrefs(id: String, autoSubtitles: String?, subtitleLanguage: String?, audioLanguage: String?, streamSubtitleLanguage: String?) {
        val p = profiles.firstOrNull { it.id == id } ?: return
        // build 59: ondertitelstand als tekst; de oude aan/uit-waarde vervalt daarmee
        val n = p.copy(autoSubtitles = null, autoSubtitleMode = autoSubtitles, subtitleLanguage = subtitleLanguage,
            audioLanguage = audioLanguage, streamSubtitleLanguage = streamSubtitleLanguage)
        if (n == p) return
        profiles = profiles.map { if (it.id == id) n else it }
        write("profiles", profiles)
        if (id == activeProfileID) applyPreferences(n)
    }

    fun upsertProfile(p: ViewerProfile) {
        val old = profiles.firstOrNull { it.id == p.id }
        // updatedAt (build 41): alleen bij naam, avatar, kinderprofiel, leeftijdsgrens of accentkleur (niet bij kijkvoorkeuren)
        if (old == null || old.name != p.name || old.avatar != p.avatar || old.kids != p.kids || old.ageLimit != p.ageLimit ||
            old.accentColor != p.accentColor) {
            ProfileSync.profileEdited(p.id)
        }
        profiles = if (profiles.any { it.id == p.id }) profiles.map { if (it.id == p.id) p else it } else profiles + p
        write("profiles", profiles)
        if (mainProfileIDStored == null) { mainProfileIDStored = profiles.first().id; saveMeta() }
        ProfileSync.profilesChanged()
    }

    /**
     * Profielen uit het Bingr-account overnemen (zie [ProfileSync]): lijst vervangen en hoofdprofiel zetten.
     * [adoptFrom]: het automatische hoofdprofiel van dit apparaat dat niet op de server staat; zijn gegevens
     * (lijsten, voortgang, foto) gaan naar het hoofdprofiel van het account. Pincodes worden niet aangeraakt.
     */
    fun applyAccountProfiles(list: List<ViewerProfile>, mainID: String?, adoptFrom: String? = null) {
        if (list.isEmpty()) return
        val main = mainID?.takeIf { id -> list.any { it.id == id } } ?: list.first().id
        val adopted = adoptFrom != null && list.none { it.id == adoptFrom }
        if (adopted) {
            flushWrites()   // build 97: map pas hernoemen als alles geschreven is
            runCatching {
                val old = File(dir, "profile-$adoptFrom")
                val new = File(dir, "profile-$main")
                if (old.exists() && (!new.exists() || new.listFiles().isNullOrEmpty())) {
                    new.deleteRecursively()
                    old.renameTo(new)
                } else old.deleteRecursively()
            }
            runCatching {
                val op = photoFile(adoptFrom)
                if (op.exists()) { if (!photoFile(main).exists()) op.renameTo(photoFile(main)) else op.delete() }
            }
            setSecret("profile.$adoptFrom.pin", null)
            photoVersion++
        }
        profiles = list
        write("profiles", profiles)
        mainProfileIDStored = main
        saveMeta()
        val act = activeProfileID
        if (act != null && list.none { it.id == act }) {
            activeProfileID = main
            Prefs.put("activeProfileID", main)
            list.firstOrNull { it.id == main }?.let { applyPreferences(it) }
            loadProfileData()
        }
    }

    /**
     * Profielen uit de volledige sync (build 41, zie [ProfileSync]): nieuwe lijst (al gesorteerd op `order`) en
     * profielen die elders verwijderd zijn (tombstone). Die gaan hier weg met foto, pincode en gegevens; was het
     * het actieve profiel, dan terug naar "Wie kijkt er?". Het hoofdprofiel wordt nooit verwijderd.
     */
    fun applySyncedProfiles(list: List<ViewerProfile>, removed: List<String>, newMain: String? = null) {
        val main = newMain?.takeIf { id -> list.any { it.id == id } } ?: mainProfileID
        val gone = removed.filter { it != main }
        val newList = list.filter { it.id !in gone }
        if (newList.isEmpty()) return
        if (gone.isNotEmpty()) flushWrites()   // build 97: geen schrijfopdracht die de map daarna weer aanmaakt
        for (id in gone) {
            Collections.profileDeleted(id, fromSync = true)
            Downloads.profileDeleted(id)
            Reminders.profileDeleted(id)
            runCatching { photoFile(id).delete() }
            setSecret("profile.$id.pin", null)
            runCatching { File(dir, "profile-$id").deleteRecursively() }
        }
        if (gone.isNotEmpty()) photoVersion++
        profiles = newList
        write("profiles", profiles)
        if (mainProfileIDStored == null || newList.none { it.id == mainProfileIDStored } || (main != null && main != mainProfileIDStored)) {
            mainProfileIDStored = main?.takeIf { id -> newList.any { it.id == id } } ?: newList.first().id
            saveMeta()
        }
        val act = activeProfileID
        if (act != null && newList.none { it.id == act }) {
            activeProfileID = null
            Prefs.put("activeProfileID", null)
            loadProfileData()
            AppState.profileRemoved()
        }
    }

    /**
     * Accentkleur van het actieve profiel wijzigen (build 54). Gaat met het profielobject mee naar het account
     * (`updatedAt` = nu) en is meteen overal zichtbaar, want [Accent] leest het actieve profiel.
     */
    fun setActiveAccent(key: String) {
        val p = activeProfile ?: return
        if (p.accentColor == key) return
        upsertProfile(p.copy(accentColor = key))
    }

    /**
     * Eenmalige migratie (build 54): de accentkleur van vóór build 54 (`settings.shared.accentColor` of de lokale
     * instelling) naar alle profielen die er nog geen hebben. true = er is iets gewijzigd.
     */
    fun copyAccentToProfiles(key: String): Boolean {
        val missing = profiles.filter { it.accentColor == null }
        if (missing.isEmpty()) return false
        missing.forEach { ProfileSync.profileEdited(it.id) }
        profiles = profiles.map { if (it.accentColor == null) it.copy(accentColor = key) else it }
        write("profiles", profiles)
        ProfileSync.profilesChanged()
        return true
    }

    /** Profiel met al zijn gegevens verwijderen. Het hoofdaccount kan niet verwijderd worden. */
    fun deleteProfile(p: ViewerProfile) {
        if (isMain(p)) return
        // tombstone (build 41): andere apparaten verwijderen het ook; ook de foto gaat uit Storage
        ProfileSync.profileDeleted(p.id)
        // en de overige gegevens van dit profiel uit het account (build 44): delete_state profile.<id>
        StateSync.profileDeleted(p.id)
        // en zijn collecties (build 54)
        Collections.profileDeleted(p.id)
        // en zijn downloads (build 60): anders blijven ze onzichtbaar opslag innemen
        Downloads.profileDeleted(p.id)
        // en zijn herinneringen (met de alarmen); de verwijdering gaat mee naar het account
        Reminders.profileDeleted(p.id)
        profiles = profiles.filterNot { it.id == p.id }
        write("profiles", profiles)
        ProfileSync.profilesChanged()
        setPhoto(p.id, null, fromSync = true)
        setSecret("profile.${p.id}.pin", null)
        flushWrites()   // build 97
        runCatching { File(dir, "profile-${p.id}").deleteRecursively() }
        if (activeProfileID == p.id) {
            activeProfileID = null
            Prefs.put("activeProfileID", null)
            loadProfileData()
        }
    }

    fun setMainSource(id: String) {
        mainSourceID = id
        saveMeta()
        SourceSync.sourcesChanged()
    }

    /** Hoort een opgeslagen item bij het actieve profiel? Oude items zonder profiel horen bij het hoofdaccount. */
    fun belongsToActive(profileID: String?): Boolean {
        val active = activeProfileID ?: return true
        if (profileID != null) return profileID == active
        return active == mainProfileID
    }

    // MARK: geheimen (wachtwoorden, tokens, eigen API-sleutels)

    /** build 97: ontsleutelde waarden (de Keystore niet bij elke lezing aanspreken). */
    private val secretCache = ConcurrentHashMap<String, String>()
    private val secretLock = Any()

    /** build 97: versleuteld bewaard ([SecureBox], AES-GCM met een Keystore-sleutel); platte tekst van vroeger wordt ook gelezen. */
    fun secret(key: String): String? {
        secretCache[key]?.let { return it }
        val raw = runCatching { secrets.getString(key, null) }.getOrNull() ?: return null
        val v = SecureBox.decrypt(raw) ?: return null
        secretCache[key] = v
        return v
    }

    private fun putSecret(key: String, value: String?) {
        synchronized(secretLock) {
            if (value == null) {
                secretCache.remove(key)
                secrets.edit().remove(key).apply()
            } else {
                secretCache[key] = value
                secrets.edit().putString(key, SecureBox.encrypt(value) ?: value).apply()
            }
        }
    }

    /** build 97: bestaande geheimen in platte tekst versleutelen (schrijf-thread; niet als het intussen gewijzigd is). */
    private fun migrateSecrets() {
        val all = secrets.all
        for ((k, v) in all) {
            if (v !is String || SecureBox.isEncrypted(v)) continue
            val enc = SecureBox.encrypt(v) ?: return   // Keystore werkt niet: alles laten zoals het was
            synchronized(secretLock) {
                if (secrets.getString(k, null) == v) secrets.edit().putString(k, enc).apply()
            }
        }
    }

    /** Build 141: geheim alleen op dit apparaat (niet naar het account), zoals de pincode-limiet. */
    fun setLocalSecret(key: String, value: String?) = putSecret(key, value)

    fun setSecret(key: String, value: String?) {
        val old = secret(key)
        putSecret(key, value)
        // wachtwoord of token van een server gewijzigd → ook naar het account (build 43)
        if (old != value && key.startsWith("playlist.")) SourceSync.secretChanged(key)
        // Trakt, OpenSubtitles en pincodes → ook naar het account (build 44)
        if (old != value) StateSync.secretChanged(key)
    }

    /** Alle geheimen met dit voorvoegsel (bijv. "playlist.<id>."), sleutel → waarde. */
    fun secretsWithPrefix(prefix: String): Map<String, String> = runCatching {
        secrets.all.mapNotNull { (k, v) -> if (k.startsWith(prefix) && v is String) secret(k)?.let { k to it } else null }.toMap()
    }.getOrDefault(emptyMap())

    private fun removeSecrets(match: (String) -> Boolean) {
        runCatching {
            synchronized(secretLock) {
                val keys = secrets.all.keys.filter(match)
                keys.forEach { secretCache.remove(it) }
                secrets.edit().apply { keys.forEach { remove(it) } }.apply()
            }
        }
    }

    // MARK: servers (afspeellijsten)

    /**
     * Server toevoegen/bijwerken. De eerste server wordt meteen de hoofdserver, en als er nog geen profiel is
     * wordt het hoofdprofiel aangemaakt (naam/avatar van de server) en gekozen.
     */
    fun upsert(p: PlaylistSource) {
        val n = p.copy(updatedAt = System.currentTimeMillis())
        SourceSync.sourceEdited(n.id)
        playlists = if (playlists.any { it.id == n.id }) playlists.map { if (it.id == n.id) n else it } else playlists + n
        write("playlists", playlists)
        if (mainSourceID == null || playlists.none { it.id == mainSourceID }) setMainSource(n.id)
        if (profiles.isEmpty()) {
            val main = ViewerProfile(name = n.name, avatar = n.avatar)
            mainProfileIDStored = main.id
            upsertProfile(main)
            saveMeta()
            adoptLegacyData(main.id)
            if (activeProfileID == null) selectProfile(main)
        }
        SourceSync.sourcesChanged()
    }

    fun delete(p: PlaylistSource) {
        // tombstone (build 43): andere apparaten van het account verwijderen deze server ook
        SourceSync.sourceDeleted(p.id)
        removeServerCategories(p.id)   // build 128
        playlists = playlists.filterNot { it.id == p.id }
        write("playlists", playlists)
        if (mainSourceID == p.id) { mainSourceID = playlists.firstOrNull()?.id; saveMeta() }
        setSecret(p.passwordKey, null)
        removeSecrets { it.startsWith("playlist.${p.id}.") }
        photoFile(p.id).delete()
        customizations = customizations - p.id; write("custom", customizations)
        SourceSync.sourcesChanged()
    }

    // MARK: servers uit het Bingr-account (build 43, zie [SourceSync])

    /**
     * Servers uit de account-sync overnemen: nieuwe lijst en servers die elders verwijderd zijn (tombstone; die gaan
     * hier weg met geheimen, foto, aanpassingen en vergrendelde groepen). [mainID] wordt de hoofdserver als die in
     * de lijst staat. Roept geen sync-hooks aan.
     */
    fun applySyncedSources(list: List<PlaylistSource>, removed: List<String>, mainID: String?) {
        for (id in removed) {
            removeSecrets { it.startsWith("playlist.$id.") }
            runCatching { photoFile(id).delete() }
            removeServerCategories(id)   // build 128
        }
        if (removed.isNotEmpty()) {
            customizations = customizations - removed.toSet(); write("custom", customizations)
            val locked = parental.lockedGroups.filterNot { k -> removed.any { k.startsWith("$it|") } }.toSet()
            if (locked != parental.lockedGroups) { parental = parental.copy(lockedGroups = locked); write("parental", parental) }
            photoVersion++
        }
        playlists = list
        write("playlists", playlists)
        val main = mainID?.let { m -> list.firstOrNull { it.id.equals(m, ignoreCase = true) }?.id }
        when {
            main != null && main != mainSourceID -> { mainSourceID = main; saveMeta() }
            mainSourceID == null || list.none { it.id == mainSourceID } -> { mainSourceID = list.firstOrNull()?.id; saveMeta() }
        }
        // servers maar nog geen profiel: hoofdprofiel aanmaken (zoals bij het eerste toevoegen)
        if (profiles.isEmpty() && list.isNotEmpty()) ensureMainProfile()
    }

    /** Instellingen van een server uit het "android"-blok: aanpassingen en vergrendelde groepen. null = niet aanraken. */
    fun applySyncedSourceSettings(id: String, custom: Customization?, lockedGroups: Set<String>?) {
        if (custom != null && customizations[id] != custom) {
            customizations = if (custom == Customization()) customizations - id else customizations + (id to custom)
            write("custom", customizations)
        }
        if (lockedGroups != null) {
            val others = parental.lockedGroups.filterNot { it.startsWith("$id|") }.toSet()
            val locked = others + lockedGroups.map { "$id|$it" }
            if (locked != parental.lockedGroups) { parental = parental.copy(lockedGroups = locked); write("parental", parental) }
        }
    }

    /** Vergrendelde groepen van één server (zonder het voorvoegsel "<id>|"). */
    fun lockedGroups(id: String): Set<String> =
        parental.lockedGroups.filter { it.startsWith("$id|") }.map { it.removePrefix("$id|") }.toSet()

    /**
     * Een ander Bingr-account logt in op dit apparaat: servers, profielen en gegevens van het vorige account wissen
     * (geheimen, pincodes, foto's, per-profielgegevens, aanpassingen, kinderslot en herinneringen).
     */
    fun wipeAccountData() {
        flushWrites()   // build 97
        (playlists.map { it.id } + profiles.map { it.id }).forEach { runCatching { photoFile(it).delete() } }
        removeSecrets { it.startsWith("playlist.") || it.startsWith("profile.") || it == "parental.pin" }
        runCatching { dir.listFiles()?.forEach { f -> if (f.isDirectory && f.name.startsWith("profile-")) f.deleteRecursively() } }
        for (name in profileFiles) runCatching { File(dir, "$name.json").delete() }
        playlists = emptyList(); write("playlists", playlists)
        profiles = emptyList(); write("profiles", profiles)
        customizations = emptyMap(); write("custom", customizations)
        parental = ParentalSettings(); write("parental", parental)
        reminders = emptyList(); write("reminders", reminders)
        setAccountMarks(emptyMap())
        runCatching { Prefs.remove("cats.migrated140") }
        mainSourceID = null; mainProfileIDStored = null; saveMeta()
        activeProfileID = null; Prefs.put("activeProfileID", null)
        activePlaylistID = null
        photoVersion++
        loadProfileData()
    }

    // MARK: foto's (profiel-id of server-id)

    fun photoFile(id: String) = File(dir, "photo-$id.jpg")
    fun photo(id: String): Bitmap? = photoFile(id).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
    /**
     * Foto zetten of wissen. Bij een profiel (niet [fromSync]) gaat de wijziging ook naar het Bingr-account
     * (zie [ProfileSync.photoChanged]); [fromSync] = komt uit de sync, niet terugsturen.
     */
    fun setPhoto(id: String, jpeg: ByteArray?, fromSync: Boolean = false) {
        val existed = photoFile(id).exists()
        if (jpeg == null) photoFile(id).delete() else runCatching { photoFile(id).writeBytes(jpeg) }
        photoVersion++
        if (!fromSync && (jpeg != null || existed)) ProfileSync.photoChanged(id, jpeg != null)
    }

    // MARK: startscherm (per profiel, build 51)

    /** Startscherm van een profiel (leeg = standaard). */
    fun homeLayout(id: String): HomeLayout =
        if (id == activeProfileID) activeHomeLayout else readIn(profileDir(id), "homelayout", HomeLayout())

    fun setHomeLayout(id: String, layout: HomeLayout) {
        writeIn(profileDir(id), "homelayout", layout)
        if (id == activeProfileID) activeHomeLayout = layout
        StateSync.profileDataChanged(id)
    }

    /** Startscherm uit het account; roept geen sync-hooks aan. */
    fun applySyncedHomeLayout(id: String, layout: HomeLayout) {
        if (homeLayout(id) == layout) return
        writeIn(profileDir(id), "homelayout", layout)
        if (id == activeProfileID) activeHomeLayout = layout
    }

    // MARK: terugkijk-correctie per zender (build 51, gedeeld veld catchupOffsets van de bron)

    fun catchupOffset(sourceID: String?, channelID: String): Int =
        playlists.firstOrNull { it.id == sourceID }?.catchupOffsets?.get(channelID) ?: 0

    fun setCatchupOffset(sourceID: String, channelID: String, minutes: Int) {
        val p = playlists.firstOrNull { it.id == sourceID } ?: return
        val m = minutes.coerceIn(-120, 120)
        val offsets = if (m == 0) p.catchupOffsets - channelID else p.catchupOffsets + (channelID to m)
        if (offsets == p.catchupOffsets) return
        upsert(p.copy(catchupOffsets = offsets))
    }

    // MARK: favorieten / mijn lijst (per profiel)

    fun key(playlist: String, item: String) = "$playlist|$item"

    /**
     * Is [stored] de sleutel van dit item, ongeacht hoofdletters in de server-id? iOS schrijft de id met hoofdletters,
     * web/Android/Samsung TV soms met kleine letters (server daar aangemaakt). Het item-id blijft exact.
     */
    fun sameKey(stored: String, playlist: String, item: String): Boolean =
        stored.length == playlist.length + 1 + item.length && stored.endsWith("|$item") &&
            stored.regionMatches(0, playlist, 0, playlist.length, ignoreCase = true)

    /** Alle opgeslagen schrijfwijzen van dit item in [set] (meestal 0 of 1). */
    private fun keysFor(set: Set<String>, playlist: String, item: String): Set<String> =
        set.filterTo(HashSet()) { sameKey(it, playlist, item) }

    private fun inSet(set: Set<String>, playlist: String, item: String): Boolean =
        key(playlist, item) in set || set.any { sameKey(it, playlist, item) }

    fun toggleFavorite(playlist: String, item: String) {
        val found = keysFor(favorites, playlist, item)
        // uit: alle schrijfwijzen weg (anders komt het via de andere schrijfwijze terug); aan: in de eigen schrijfwijze
        favorites = if (found.isNotEmpty()) favorites - found else favorites + key(playlist, item)
        pwrite("favorites", favorites)
        StateSync.profileDataChanged(activeProfileID)
    }
    fun isFavorite(playlist: String, item: String) = inSet(favorites, playlist, item)

    /**
     * Build 141: een TMDB-titel ("tmdb-movie-123", niet in een bibliotheek) hoort bij geen enkele server: hij staat in Mijn
     * lijst onder de server die toen actief was. Een ander apparaat of profiel met een andere actieve server moet hem
     * ook als "in Mijn lijst" zien (en kunnen weghalen), anders kwam hij er dubbel in of leek hij te ontbreken.
     */
    private fun myListKeys(playlist: String, item: String): Set<String> =
        if (item.startsWith(Build141Feedback.MyListKeys.EXTERNAL)) myList.filterTo(HashSet()) { Build141Feedback.MyListKeys.itemOf(it) == item }
        else keysFor(myList, playlist, item)

    fun toggleMyList(playlist: String, item: String) {
        val found = myListKeys(playlist, item)
        myList = if (found.isNotEmpty()) myList - found else myList + key(playlist, item)
        pwrite("mylist", myList)
        StateSync.profileDataChanged(activeProfileID)
    }
    fun inMyList(playlist: String, item: String) =
        if (item.startsWith(Build141Feedback.MyListKeys.EXTERNAL)) myListKeys(playlist, item).isNotEmpty() else inSet(myList, playlist, item)
    fun setMyList(playlist: String, item: String, on: Boolean) {
        if (inMyList(playlist, item) != on) toggleMyList(playlist, item)
    }

    // MARK: voortgang / bekeken (per profiel)

    fun setProgress(itemKey: String, entry: ProgressEntry) {
        val e = if (entry.profileID == null && activeProfileID != null) entry.copy(profileID = activeProfileID) else entry
        var p = progress + (itemKey to e)
        if (p.size > 300) p = p.entries.sortedByDescending { it.value.updated }.take(250).associate { it.key to it.value }
        progress = p
        pwrite("progress", progress)
        // tijdens het afspelen hooguit eens per 30 seconden naar het account
        StateSync.progressChanged(activeProfileID)
    }
    fun clearProgress(itemKey: String) {
        if (itemKey !in progress) return
        progress = progress - itemKey; pwrite("progress", progress)
        // bewust uit Verder kijken: als verwijdering vastleggen (een ontbrekende sleutel telt niet meer als verwijdering)
        StateSync.progressCleared(activeProfileID, itemKey)
    }

    fun setLocalWatched(itemKey: String, watched: Boolean) {
        val n = if (watched) localWatched + itemKey else localWatched - itemKey
        if (n == localWatched) return
        localWatched = n
        pwrite("watched", localWatched)
        StateSync.profileDataChanged(activeProfileID)
    }

    /**
     * Build 154: sleutels (id-sleutels en inhoudssleutels `w:m:`/`w:e:`/`w:s:`) in één keer toevoegen of weghalen.
     * Weghalen gaat via de bestaande account-sync met dezelfde tijd naar `removed.watched`. [notify] = false: de aanroeper
     * meldt de wijziging zelf (migratie met dezelfde `at`).
     */
    fun updateWatched(add: Collection<String>, remove: Collection<String>, notify: Boolean = true): Boolean {
        var n = localWatched
        if (remove.isNotEmpty()) n = n - remove.toSet()
        if (add.isNotEmpty()) n = n + add
        if (n == localWatched) return false
        localWatched = n
        pwrite("watched", localWatched)
        if (notify) StateSync.profileDataChanged(activeProfileID)
        return true
    }

    /**
     * Sleutel voor "aflevering bekeken" per serie (seizoen-aflevering), zodat het vinkje en "X GEZIEN" ook kloppen
     * als de afleveringen pas later geladen worden. Geldt per profiel, ook zonder Trakt.
     */
    fun episodeKey(series: String, season: Int, episode: Int): String = "ep:$series|$season-$episode"

    fun setEpisodeWatched(e: Episode, watched: Boolean) {
        val k = episodeKey(e.seriesID, e.season, e.number)
        localWatched = if (watched) localWatched + e.id + k else localWatched - e.id - k
        pwrite("watched", localWatched)
        StateSync.profileDataChanged(activeProfileID)
    }

    /** Is deze aflevering lokaal (zonder Trakt) als bekeken gemarkeerd? */
    fun isEpisodeWatched(e: Episode): Boolean =
        e.id in localWatched || episodeKey(e.seriesID, e.season, e.number) in localWatched

    /** Aantal lokaal bekeken afleveringen van een serie (actief profiel). */
    fun localWatchedCount(seriesID: String): Int {
        val prefix = "ep:$seriesID|"
        return localWatched.count { it.startsWith(prefix) }
    }

    fun pushRecentChannel(playlist: String, channelID: String) {
        val k = key(playlist, channelID)
        recentChannels = (listOf(k) + recentChannels.filterNot { sameKey(it, playlist, channelID) }).take(20)
        pwrite("recent", recentChannels)
        StateSync.profileDataChanged(activeProfileID)
    }

    /** Build 128: een zender uit Recent halen (alle schrijfwijzen van de server-id). */
    fun removeRecentChannel(playlist: String, channelID: String) {
        val n = recentChannels.filterNot { sameKey(it, playlist, channelID) }
        if (n == recentChannels) return
        recentChannels = n
        pwrite("recent", recentChannels)
        StateSync.profileDataChanged(activeProfileID)
    }

    /** Build 128: kijkgeschiedenis van het actieve profiel over alle servers (dezelfde titel uit iOS én web: één keer). */
    fun historyAll() = history.filter { belongsToActive(it.profileID) }
        .distinctBy { (it.playlistID?.lowercase() ?: "-") + "|" + it.itemID }

    // MARK: aanpassingen (hernoemen / verbergen / volgorde / samenvoegen) — gedeeld

    fun customization(playlist: String) = customizations[playlist] ?: Customization()
    fun updateCustomization(playlist: String, change: (Customization) -> Customization) {
        val old = customization(playlist)
        val n = change(old)
        customizations = customizations + (playlist to n)
        write("custom", customizations)
        if (n != old) { SourceSync.sourceEdited(playlist); SourceSync.sourcesChanged() }
    }

    // MARK: kinderslot — gedeeld (ingesteld door het hoofdaccount)

    fun updateParental(change: (ParentalSettings) -> ParentalSettings) {
        val old = parental.lockedGroups
        val before = parental
        parental = change(parental); write("parental", parental)
        // kinderslot aan/uit en 18+ automatisch vergrendelen horen bij de instellingen van het account (build 44)
        if (before.enabled != parental.enabled || before.autoLockAdult != parental.autoLockAdult) StateSync.settingsChanged()
        // vergrendelde groepen horen bij een server (android-blok, build 43)
        val diff = (old - parental.lockedGroups) + (parental.lockedGroups - old)
        val ids = diff.map { it.substringBefore('|') }.toSet().filter { id -> playlists.any { it.id == id } }
        if (ids.isNotEmpty()) { ids.forEach { SourceSync.sourceEdited(it) }; SourceSync.sourcesChanged() }
    }

    /** Kinderslot-instellingen die niet per server zijn, uit het account (build 44); roept geen sync-hooks aan. */
    fun applySyncedParental(enabled: Boolean, autoLockAdult: Boolean) {
        val n = parental.copy(enabled = enabled, autoLockAdult = autoLockAdult)
        if (n != parental) { parental = n; write("parental", parental) }
    }

    // MARK: herinneringen

    fun addReminder(r: ProgramReminder) {
        reminders = reminders.filterNot { it.id == r.id } + r; write("reminders", reminders)
        StateSync.remindersChanged()
    }
    fun removeReminder(id: String) {
        if (reminders.none { it.id == id }) return
        reminders = reminders.filterNot { it.id == id }; write("reminders", reminders)
        StateSync.remindersChanged()
    }

    /** Herinneringen uit het account (build 44); roept geen sync-hooks aan. */
    fun applySyncedReminders(list: List<ProgramReminder>) {
        if (list == reminders) return
        reminders = list; write("reminders", reminders)
    }
    fun hasReminder(id: String) = reminders.any { it.id == id }

    // MARK: kijkgeschiedenis (per profiel)

    fun addHistory(e: HistoryEntry) {
        val n = if (e.profileID == null && activeProfileID != null) e.copy(profileID = activeProfileID) else e
        // dezelfde titel (ook in een andere schrijfwijze van de server-id) lokaal vervangen; géén verwijdering vastleggen:
        // op iOS valt die schrijfwijze samen met de nieuwe vermelding en zou die dan ook verdwijnen (de lijst toont
        // elke titel toch maar één keer, zie history())
        history = (listOf(n) + history.filterNot { it.itemID == n.itemID && samePlaylist(it.playlistID, n.playlistID) }).take(500)
        pwrite("history", history)
        StateSync.profileDataChanged(activeProfileID)
    }
    fun removeHistory(e: HistoryEntry) {
        val gone = history.filter { it.id.equals(e.id, ignoreCase = true) && it.itemID == e.itemID }
        history = history.filterNot { it in gone }; pwrite("history", history)
        StateSync.historyRemoved(activeProfileID, (gone.map { it.id } + e.id).distinct())
    }
    /** [all] (build 128): de hele geschiedenis van dit profiel, van alle servers. */
    fun clearHistory(playlist: String?, all: Boolean = false) {
        val gone = history.filter { if (all) belongsToActive(it.profileID) else samePlaylist(it.playlistID, playlist) }
        history = history.filterNot { it in gone }; pwrite("history", history)
        StateSync.historyRemoved(activeProfileID, gone.map { it.id })
    }
    /** Kijkgeschiedenis van het actieve profiel op deze server. */
    fun history(playlist: String?) = history.filter { samePlaylist(it.playlistID, playlist) && belongsToActive(it.profileID) }
        .distinctBy { (it.playlistID?.lowercase() ?: "-") + "|" + it.itemID }   // dezelfde titel uit iOS én web: één keer

    /** Zelfde server-id, ongeacht hoofdletters (iOS schrijft hoofdletters, web/Android/Samsung TV soms kleine letters). */
    fun samePlaylist(a: String?, b: String?): Boolean = if (a == null || b == null) a == b else a.equals(b, ignoreCase = true)

    // MARK: NAS

    fun saveNAS(list: List<NASServer>) { nasServers = list; write("nas", list) }

    // MARK: delen tussen apparaten (Android heeft geen iCloud)

    @Serializable
    private data class Backup(
        val playlists: List<PlaylistSource>,
        val passwords: Map<String, String>,
        /** gegevens van het actieve profiel (ook het formaat van vóór de profielen) */
        val favorites: Set<String>,
        val myList: Set<String>,
        val progress: Map<String, ProgressEntry>,
        val watched: Set<String>,
        val custom: Map<String, Customization>,
        val parental: ParentalSettings,
        val history: List<HistoryEntry> = emptyList(),
        val profiles: List<ViewerProfile> = emptyList(),
        val profileData: Map<String, ProfileData> = emptyMap(),
        val mainSourceID: String? = null,
        val mainProfileID: String? = null,
    )

    /** Gegevens van een profiel (actief: uit het geheugen, anders uit de bestanden). */
    fun profileData(id: String): ProfileData = readProfileData(id)

    /** Gegevens van een profiel uit het account (build 44) vervangen; roept geen sync-hooks aan. */
    fun applySyncedProfileData(id: String, data: ProfileData, current: ProfileData? = null) {
        // build 147: [current] = net gelezen (buiten de main thread), dan niet opnieuw alle bestanden lezen
        if (data == (current ?: readProfileData(id))) return
        val d = profileDir(id)
        writeIn(d, "favorites", data.favorites); writeIn(d, "mylist", data.myList)
        writeIn(d, "progress", data.progress); writeIn(d, "watched", data.watched)
        writeIn(d, "recent", data.recent); writeIn(d, "history", data.history)
        writeIn(d, "hiddencats", data.hiddenCategories)
        if (id == activeProfileID) loadProfileData()
    }

    private fun readProfileData(id: String): ProfileData {
        if (id == activeProfileID) return ProfileData(favorites, myList, progress, localWatched, recentChannels, history, profileHiddenCats)
        val d = profileDir(id)
        return ProfileData(
            readIn(d, "favorites", emptySet()), readIn(d, "mylist", emptySet()), readIn(d, "progress", emptyMap()),
            readIn(d, "watched", emptySet()), readIn(d, "recent", emptyList()), readIn(d, "history", emptyList()),
            readIn(d, "hiddencats", emptySet()),
        )
    }

    // MARK: build 140: categorieën per account (docs/ACCOUNT.md → "Build 140")

    private fun setAccountMarks(m: Map<String, Build140Logic.Mark>, save: Boolean = true) {
        accountMarks = m
        val alive = Build140Logic.CatMarks.alive(m)
        if (alive != accountHidden) {
            accountHidden = alive
            hiddenIndex = Build140Logic.CatMarks.index(alive)
        }
        if (save) write("accountcats", AccountCats(
            on = m.filter { it.value.on }.mapValues { it.value.at },
            off = m.filter { !it.value.on }.mapValues { it.value.at },
        ))
    }

    /** Build 140: de account-set met tijden (voor de sync). */
    fun accountCategoryMarks(): Map<String, Build140Logic.Mark> = accountMarks

    /** Build 140: account-set uit de sync; roept geen sync-hooks aan. */
    fun applySyncedAccountCategories(m: Map<String, Build140Logic.Mark>) {
        if (m == accountMarks) return
        setAccountMarks(m)
    }

    /** Is categorie [group] (naam zoals de server hem levert) van server [sid] uit (account-set, niet-kinderprofiel)? */
    fun isCategoryHidden(sid: String, group: String): Boolean {
        if (activeProfile?.kids == true) return false
        return hiddenIndex[sid.lowercase()]?.contains(group) == true
    }

    /** Uit in de account-set (ook voor het scherm als er een kinderprofiel actief is). */
    fun isAccountCategoryHidden(sid: String, group: String): Boolean = hiddenIndex[sid.lowercase()]?.contains(group) == true

    /** Verborgen categorienamen van server [sid] voor het actieve profiel (voor het filteren; kinderprofiel: geen). */
    fun hiddenCategoryNames(sid: String): Set<String> =
        if (activeProfile?.kids == true) emptySet() else hiddenIndex[sid.lowercase()] ?: emptySet()

    /** Categorieën (sleutels "<server-id>|<naam>") aan of uit zetten voor het hele account. */
    fun setCategoriesHidden(keys: Collection<String>, hidden: Boolean) {
        val n = Build140Logic.CatMarks.set(accountMarks, keys, hidden, System.currentTimeMillis())
        if (n == accountMarks) return
        setAccountMarks(n)
        StateSync.accountChanged()
    }

    /** Server verwijderd: zijn categoriekeuzes uit de account-set (als verwijdering, dus gesynct) en uit de oude per-profiel-sets. */
    fun removeServerCategories(sid: String) {
        val n = Build140Logic.CatMarks.removeServer(accountMarks, sid, System.currentTimeMillis())
        if (n != accountMarks) { setAccountMarks(n); StateSync.accountChanged() }
        val prefix = "${sid.lowercase()}|"
        for (p in profiles) {
            val d = readProfileData(p.id)
            val k = d.hiddenCategories.filterNot { it.lowercase().startsWith(prefix) }.toSet()
            if (k == d.hiddenCategories) continue
            if (p.id == activeProfileID) { profileHiddenCats = k; pwrite("hiddencats", k) }
            else writeIn(profileDir(p.id), "hiddencats", k)
            StateSync.profileDataChanged(p.id)
        }
    }

    /**
     * Build 140: eenmalig per apparaat de per-profiel-sets van alle niet-kinderprofielen (unie) naar de account-set, met
     * `at` = 0, zodat een keuze die een ander apparaat al in `account` maakte voorgaat. De per-profiel-data blijft staan.
     */
    fun migrateCategoriesToAccount(allowFlag: Boolean) {
        if (profiles.isEmpty() || Prefs.bool("cats.migrated140", false)) return
        val union = profiles.filter { !it.kids }.flatMap { readProfileData(it.id).hiddenCategories }
        val n = Build140Logic.CatMarks.migrate(accountMarks, union)
        if (n != accountMarks) { setAccountMarks(n); StateSync.accountChanged() }
        // ingelogd: pas vastleggen na de eerste sync (dan zijn de per-profiel-sets van de server er ook); mag vaker lopen
        if (allowFlag) Prefs.put("cats.migrated140", true)
    }

    /**
     * Build 147: zoals [migrateCategoriesToAccount], maar het lezen van de profielbestanden (verborgen categorieën van elk
     * profiel) gebeurt buiten de main thread; alleen het toepassen op de main thread ([applying]: binnen
     * StateSync.applyingChanges, zoals na een sync).
     */
    suspend fun migrateCategoriesToAccountOffMain(allowFlag: Boolean, applying: Boolean = false) {
        val main = kotlinx.coroutines.Dispatchers.Main
        val ids = kotlinx.coroutines.withContext(main) {
            if (profiles.isEmpty() || Prefs.bool("cats.migrated140", false)) null else profiles.filter { !it.kids }.map { it.id }
        } ?: return
        val union = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ids.flatMap { readProfileData(it).hiddenCategories } }
        kotlinx.coroutines.withContext(main) {
            if (profiles.isEmpty() || Prefs.bool("cats.migrated140", false)) return@withContext
            StartDiagnostics.task("categories_migrate") {
                val doMigrate = {
                    val n = Build140Logic.CatMarks.migrate(accountMarks, union)
                    if (n != accountMarks) { setAccountMarks(n); StateSync.accountChanged() }
                    if (allowFlag) Prefs.put("cats.migrated140", true)
                }
                if (applying) StateSync.applyingChanges(doMigrate) else doMigrate()
            }
        }
    }

    /**
     * Migratie (eenmalig per apparaat): de verborgen categorieën van vóór build 128 (per server, "group:<naam>" in de
     * aanpassingen van het android-blok) naar elk profiel, met een oude tijd (epoch), zodat een keuze die een ander
     * apparaat al per profiel maakte voorgaat. Het oude blok blijft staan.
     */
    fun migrateCategoriesToProfiles() {
        if (Prefs.bool("cats.migrated128", false) || profiles.isEmpty()) return
        val keys = playlists.flatMap { p ->
            customization(p.id).hidden.filter { it.startsWith("group:") }.map { key(p.id, it.removePrefix("group:")) }
        }.toSet()
        if (keys.isNotEmpty()) for (p in profiles) {
            StateSync.seedPresent(p.id, "hiddenCategories", keys, 0L)
            val d = readProfileData(p.id)
            val n = d.hiddenCategories + keys
            if (n == d.hiddenCategories) continue
            if (p.id == activeProfileID) { profileHiddenCats = n; pwrite("hiddencats", n) }
            else writeIn(profileDir(p.id), "hiddencats", n)
            StateSync.profileDataChanged(p.id)
        }
        Prefs.put("cats.migrated128", true)
    }

    private fun mergeProfileData(id: String, b: ProfileData) {
        val cur = readProfileData(id)
        val prog = cur.progress.toMutableMap()
        b.progress.forEach { (k, v) -> if ((prog[k]?.updated ?: 0) < v.updated) prog[k] = v }
        val merged = ProfileData(
            hiddenCategories = cur.hiddenCategories + b.hiddenCategories,
            favorites = cur.favorites + b.favorites,
            myList = cur.myList + b.myList,
            progress = prog,
            watched = cur.watched + b.watched,
            recent = (cur.recent + b.recent).distinct().take(20),
            history = (cur.history + b.history).distinctBy { it.id }.sortedByDescending { it.date }.take(200),
        )
        val d = profileDir(id)
        writeIn(d, "favorites", merged.favorites); writeIn(d, "mylist", merged.myList)
        writeIn(d, "progress", merged.progress); writeIn(d, "watched", merged.watched)
        writeIn(d, "recent", merged.recent); writeIn(d, "history", merged.history)
        if (id == activeProfileID) loadProfileData()
    }

    /** Alles (incl. wachtwoorden, profielen en pincodes) als JSON, om naar een ander apparaat te sturen. */
    fun exportJSON(): String {
        val keys = playlists.map { it.passwordKey } + playlists.map { "playlist.${it.id}.session" } +
            listOf("parental.pin") + profiles.map { "profile.${it.id}.pin" }
        return Http.json.encodeToString(
            Backup(playlists, keys.mapNotNull { k -> secret(k)?.let { k to it } }.toMap(),
                favorites, myList, progress, localWatched, customizations, parental, history,
                profiles = profiles,
                profileData = profiles.associate { it.id to readProfileData(it.id) },
                mainSourceID = mainSourceID, mainProfileID = mainProfileID)
        )
    }

    /** Samenvoegen met een export van een ander apparaat. */
    fun importJSON(text: String): Boolean = runCatching {
        val b = Http.json.decodeFromString<Backup>(text)
        b.playlists.forEach { p -> if (playlists.none { it.id == p.id }) upsert(p) }
        b.passwords.forEach { (k, v) -> if (secret(k) == null) setSecret(k, v) }
        customizations = b.custom + customizations; write("custom", customizations)
        if (b.profiles.isEmpty()) {
            // export van vóór de profielen: bij het actieve profiel (of het hoofdaccount) voegen
            val target = activeProfileID ?: mainProfileID
            if (target != null) mergeProfileData(target,
                ProfileData(b.favorites, b.myList, b.progress, b.watched, emptyList(), b.history))
        } else {
            for (p in b.profiles) if (profiles.none { it.id == p.id }) upsertProfile(p)
            b.profileData.forEach { (id, data) -> if (profiles.any { it.id == id }) mergeProfileData(id, data) }
        }
        true
    }.getOrDefault(false)
}
