package nl.lrvdlinden.bingr

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Kinderslot en pincodes (= ParentalControl in ParentalAndReminders.swift).
 *
 * - Pincode per profiel: het hoofdaccount gebruikt de kinderslot-pincode ("parental.pin"), elk ander profiel
 *   een eigen pincode ("profile.<id>.pin"), allemaal in [Store.secret].
 * - [verify] met de pincode van het hoofdaccount ontgrendelt 30 minuten (daarna gaat het slot vanzelf dicht);
 *   [check] controleert alleen (één titel toestaan) zonder te ontgrendelen.
 * - Leeftijdsfilter: [allowed] / [allowedFor] (pure functie, ook op de achtergrond), [isAdultGroup], [isKidsGroup].
 */
object ParentalControl {
    /** Ontgrendeld tot (epoch-ms) */
    var unlockedUntil by mutableStateOf<Long?>(null); private set
    /** Verhoogt als een pincode wijzigt (zodat schermen die [hasPIN] lezen opnieuw tekenen). */
    var pinVersion by mutableStateOf(0); private set
    private var relockJob: Job? = null

    private const val PIN_KEY = "parental.pin"
    private val adultWords = listOf("adult", "xxx", "18+", "+18", "porn", "erotic", "erotiek", "for adults")

    /** Heeft het hoofdaccount (kinderslot) een pincode? */
    val hasPIN: Boolean get() { pinVersion; return Store.secret(PIN_KEY) != null }
    val isUnlocked: Boolean get() = (unlockedUntil ?: 0L) > System.currentTimeMillis()

    /** Pincode van het hoofdaccount/kinderslot zetten (null = wissen). Build 146: bewaard als hash. */
    fun setPIN(pin: String?) { Store.setSecret(PIN_KEY, pin?.let { hashFor(it).json() }); pinVersion++ }

    /** Juiste pincode van het hoofdaccount? Dan 30 min ontgrendelen. (Build 141: niet tijdens de wachttijd.) */
    fun verify(pin: String): Boolean {
        if (lockoutRemaining() > 0) return false
        if (!matches(pin, PIN_KEY)) return false
        unlock()
        return true
    }

    /** Alleen controleren (één titel toestaan), zónder de hele bibliotheek te ontgrendelen. */
    fun check(pin: String): Boolean {
        if (lockoutRemaining() > 0) return false
        return matches(pin, PIN_KEY)
    }

    // MARK: build 146: pincode als hash (docs/ACCOUNT.md, "Build 146" → "Pincode als hash")

    /**
     * Lokaal staat alleen nog de hash ([Build146Logic.Pin.Hash.json], in [Store.secret] zoals vroeger de pincode). Een
     * pincode van vóór build 146 (alleen cijfers) wordt bij het starten ([migrateLocalPins]) of bij de eerste juiste invoer
     * gehasht, zonder de tijd van de pincode (`pinUpdatedAt`) te veranderen.
     * Afleiden kost 100.000 iteraties: [PinPad] rekent het buiten de main thread uit ([prepare]) en de controle daarna
     * gebruikt die uitkomst.
     */
    private class Prepared(val pin: String, val derived: Map<String, ByteArray>, val fresh: Build146Logic.Pin.Hash?)
    @Volatile private var prepared: Prepared? = null

    private fun saltKey(h: Build146Logic.Pin.Hash) = "${h.iter}|${h.salt}"

    /** Alle sleutels waaronder een pincode kan staan (hoofdaccount en elk profiel). */
    private fun pinKeys(): List<String> = (listOf(PIN_KEY) + Store.profiles.map { pinKey(it.id) }).distinct()

    /**
     * Buiten de main thread (PinPad): de ingetypte pincode afleiden tegen alle bewaarde hashes, en met [newPin] ook een
     * nieuwe hash (nieuwe salt) maken voor [setPIN].
     */
    fun prepare(pin: String, newPin: Boolean) {
        if (!Build146Logic.Pin.valid(pin)) { prepared = null; return }
        val out = HashMap<String, ByteArray>()
        for (k in pinKeys()) {
            val h = Build146Logic.Pin.fromStored(Store.secret(k)) ?: continue
            val sk = saltKey(h)
            if (sk in out) continue
            val salt = Build146Logic.B64.decode(h.salt) ?: continue
            out[sk] = runCatching { Build146Logic.Pin.derive(pin, salt, h.iter) }.getOrNull() ?: continue
        }
        val fresh = if (newPin) runCatching { Build146Logic.Pin.make(pin) }.getOrNull() else null
        prepared = Prepared(pin, out, fresh)
    }

    /** Na het invoeren: de voorbereide uitkomst weer vergeten. */
    fun forgetPrepared() { prepared = null }

    /** Nieuwe hash voor [pin]: de voorbereide (nieuwe salt) als die er is, anders nu uitrekenen. Elke keer een eigen salt. */
    private fun hashFor(pin: String): Build146Logic.Pin.Hash {
        val pre = prepared
        val fresh = pre?.takeIf { it.pin == pin }?.fresh
        if (fresh != null && pre != null) { prepared = Prepared(pre.pin, pre.derived, null); return fresh }
        return StartDiagnostics.task("pin_hash") { Build146Logic.Pin.make(pin) }
    }

    /** Klopt [pin] met wat onder [key] bewaard is? Een oude leesbare pincode wordt bij een juiste invoer meteen gehasht. */
    private fun matches(pin: String, key: String): Boolean {
        val stored = Store.secret(key) ?: return false
        val h = Build146Logic.Pin.fromStored(stored)
        if (h != null) {
            val got = prepared?.takeIf { it.pin == pin }?.derived?.get(saltKey(h))
            val want = Build146Logic.B64.decode(h.hash) ?: return false
            if (got != null) return Build146Logic.Pin.valid(pin) && Build146Logic.Pin.constantTimeEquals(got, want)
            return StartDiagnostics.task("pin_check") { Build146Logic.Pin.verify(pin, h) }
        }
        if (!Build146Logic.Pin.isLegacy(stored)) return false
        val ok = Build146Logic.Pin.constantTimeEquals(stored.toByteArray(), pin.toByteArray())
        if (ok) migrateKey(key, stored)
        return ok
    }

    /** Oude leesbare pincode onder [key] op de achtergrond hashen (tijd van de pincode blijft gelijk). */
    private fun migrateKey(key: String, plain: String) {
        AppScope.launch {
            val h = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                runCatching { Build146Logic.Pin.make(plain) }.getOrNull()
            } ?: return@launch
            if (Store.secret(key) != plain) return@launch
            StateSync.applyingChanges { Store.setSecret(key, h.json()) }
            pinVersion++
        }
    }

    /** Bij het starten (build 146): alle oude leesbare pincodes op dit apparaat hashen. */
    fun migrateLocalPins() {
        val legacy = pinKeys().mapNotNull { k -> Store.secret(k)?.takeIf { Build146Logic.Pin.isLegacy(it) }?.let { k to it } }
        legacy.forEach { (k, v) -> migrateKey(k, v) }
    }

    /** Is [plain] de pincode van de bewaarde hash [storedHash]? (Voor de sync: dezelfde salt houden.) */
    fun hashMatches(plain: String, storedHash: String?): Boolean =
        Build146Logic.Pin.verify(plain, Build146Logic.Pin.fromStored(storedHash))

    // MARK: build 141 (M6): limiet op pincodepogingen

    /**
     * Na 5 foute pincodes op rij een oplopende wachttijd (30 s, 1 min, 5 min, 15 min, 1 uur), voor alle pincodes samen.
     * Bewaard in [Store.setLocalSecret] (versleuteld, alleen dit apparaat), zodat een herstart hem niet wist; daarnaast
     * een teller op de monotone klok, zodat de systeemklok verzetten ook niet helpt zolang de app draait.
     * [PinPad] telt de pogingen (via [pinFailed]/[pinSucceeded]); [verify]/[check] weigeren tijdens de wachttijd.
     */
    private const val LOCKOUT_KEY = "pin.lockout"
    /** Verandert bij elke fout/succes (schermen die de wachttijd tonen tekenen opnieuw). */
    var lockoutVersion by mutableStateOf(0); private set
    @Volatile private var monoUntil = 0L

    private fun lockoutState() = Build141Logic.PinLockout.decode(Store.secret(LOCKOUT_KEY))

    /** Resterende wachttijd in ms (0 = je mag een pincode invoeren). */
    fun lockoutRemaining(): Long {
        val wall = Build141Logic.PinLockout.remaining(lockoutState(), System.currentTimeMillis())
        val mono = (monoUntil - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        return maxOf(wall, mono)
    }

    fun pinFailed() {
        val s = Build141Logic.PinLockout.failed(lockoutState(), System.currentTimeMillis())
        Store.setLocalSecret(LOCKOUT_KEY, Build141Logic.PinLockout.encode(s))
        val w = Build141Logic.PinLockout.waitAfter(s.failures)
        monoUntil = if (w > 0) android.os.SystemClock.elapsedRealtime() + w else 0L
        lockoutVersion++
    }

    fun pinSucceeded() {
        if (Store.secret(LOCKOUT_KEY) != null) Store.setLocalSecret(LOCKOUT_KEY, null)
        monoUntil = 0L
        lockoutVersion++
    }

    /** 30 min ontgrendelen; daarna gaat het slot vanzelf weer dicht (en wordt de bibliotheek opnieuw gefilterd). */
    private fun unlock() {
        val until = System.currentTimeMillis() + 30 * 60_000L
        unlockedUntil = until
        relockJob?.cancel()
        relockJob = AppScope.launch {
            delay(30 * 60_000L)
            if (unlockedUntil == until) unlockedUntil = null
        }
    }

    fun lock() { relockJob?.cancel(); relockJob = null; unlockedUntil = null }

    // MARK: pincode per profiel

    /** Sleutel van de pincode van dit profiel (hoofdaccount: "parental.pin"). */
    fun pinKey(profileID: String): String =
        if (Store.mainProfileID == profileID) PIN_KEY else "profile.$profileID.pin"

    fun hasPIN(profile: ViewerProfile): Boolean = hasPIN(profile.id)
    fun hasPIN(profileID: String): Boolean { pinVersion; return Store.secret(pinKey(profileID)) != null }

    fun setPIN(pin: String?, profile: ViewerProfile) = setPIN(pin, profile.id)
    fun setPIN(pin: String?, profileID: String) { Store.setSecret(pinKey(profileID), pin?.let { hashFor(it).json() }); pinVersion++ }

    /** Pincode van dit profiel zoals hij lokaal bewaard is (build 146: de hash als JSON; voor de account-sync). */
    fun storedPIN(profileID: String): String? = Store.secret(pinKey(profileID))

    /** Pincode uit het Bingr-account (build 44; build 146: de hash als JSON); null = geen pincode. */
    fun applySyncedPIN(pin: String?, profileID: String) {
        if (Store.secret(pinKey(profileID)) == pin) return
        StateSync.applyingChanges { Store.setSecret(pinKey(profileID), pin) }
        pinVersion++
    }

    /** Juiste pincode voor dit profiel? (Bij het hoofdaccount ook meteen 30 min ontgrendelen.) */
    fun verify(pin: String, profile: ViewerProfile): Boolean = verify(pin, profile.id)
    fun verify(pin: String, profileID: String): Boolean {
        if (lockoutRemaining() > 0) return false
        if (!matches(pin, pinKey(profileID))) return false
        if (Store.mainProfileID == profileID) unlock()
        return true
    }

    // MARK: groepen op slot

    /** Is deze groep op slot (en dus verborgen tot ontgrendeling)? */
    fun isLocked(playlist: String, group: String): Boolean {
        val s = Store.parental
        if (!s.enabled || isUnlocked) return false
        if ("$playlist|$group" in s.lockedGroups) return true
        if (s.autoLockAdult) return isAdultGroup(group)
        return false
    }

    fun toggleLock(playlist: String, group: String) {
        val k = "$playlist|$group"
        Store.updateParental { s ->
            s.copy(lockedGroups = if (k in s.lockedGroups) s.lockedGroups - k else s.lockedGroups + k)
        }
    }

    // MARK: leeftijdsfilter per profiel

    /** Keuzes voor de leeftijdsgrens (naast "Geen") */
    val ageChoices = listOf(6, 9, 12, 16)

    /** Is deze groepsnaam duidelijk voor volwassenen? */
    fun isAdultGroup(g: String): Boolean {
        val l = g.lowercase()
        return adultWords.any { l.contains(it) }
    }

    // let op: "anim" zou ook "anime" raken (vaak niet voor kinderen); daarom "animat" (animation, animatie, animated)
    private val kidsWords = listOf("kid", "kinder", "child", "junior", "jeugd", "cartoon", "animat", "disney",
        "nick", "pixar", "dreamworks", "family", "familie", "enfant", "jeunesse", "niños", "infantil",
        "bambini", "ragazzi", "çocuk", "cocuk", "dzieci", "bajki", "kika", "zapp", "baby", "boomerang",
        "cbeebies", "pbs kids", "toon", "أطفال", "儿童", "动画")
    /** Woorden die een "kindergroep" toch uitsluiten. */
    private val notKidsWords = listOf("anime", "hentai", "horror", "thriller", "family guy")
    /** Zendernamen die duidelijk voor kinderen zijn (zonder "family": "Family" is vaak een gewone zender). */
    private val kidsChannelWords = listOf("kids", "kinder", "junior", "cartoon", "disney", "nick", "boomerang", "cbeebies",
        "baby", "zapp", "kika", "toon", "jeugd", "jeunesse", "pbs kids", "duck tv", "jimjam", "minimax", "pop kids")

    /** Groep met kinderinhoud? */
    fun isKidsGroup(g: String): Boolean {
        val l = g.lowercase()
        return !isAdultGroup(g) && notKidsWords.none { l.contains(it) } && kidsWords.any { l.contains(it) }
    }

    /**
     * Mag deze zender op dit profiel? Kinderprofiel / t/m 12 ([strict]): alleen kindercategorieën
     * (of een zender die duidelijk een kinderzender is, ook in een gewone landengroep). Anders: geen volwassenengroepen.
     */
    fun channelAllowedFor(name: String, group: String, limit: Int?, strict: Boolean): Boolean {
        if (limit == null) return true
        if (isAdultGroup(group) || isAdultGroup(name)) return false
        if (!strict) return true
        if (isKidsGroup(group)) return true
        val n = name.lowercase()
        return notKidsWords.none { n.contains(it) } && kidsChannelWords.any { n.contains(it) }
    }

    private val ageTable = mapOf(
        "G" to 0, "TV-Y" to 0, "TV-G" to 0, "AL" to 0, "U" to 0, "ALL" to 0, "APPROVED" to 0, "PASSED" to 0,
        "TV-Y7" to 7, "TV-Y7-FV" to 7, "PG" to 9, "TV-PG" to 10, "UC" to 0,
        "PG-13" to 13, "TV-14" to 14, "12A" to 12, "M" to 15, "MA15+" to 15, "MA 15+" to 15,
        "R" to 17, "TV-MA" to 17, "NC-17" to 18, "X" to 18, "R18+" to 18, "R 18+" to 18, "18" to 18,
        "BTL" to 0, "TP" to 0, "L" to 0, "ATP" to 0, "KT" to 0,
    )

    /** Minimumleeftijd uit een classificatie (Kijkwijzer AL/6/9/12/14/16/18, PG-13, TV-MA, FSK 16, …). null = onbekend. */
    fun minimumAge(rated: String?): Int? {
        val r = rated?.uppercase()?.trim() ?: return null
        if (r.isEmpty() || r == "N/A" || r == "NR" || r == "NOT RATED" || r == "UNRATED" || r == "NOT_RATED") return null
        ageTable[r]?.let { return it }
        // getallen als getal vergelijken, nooit als tekst ("9" > "12" als tekst)
        val n = Regex("\\d{1,2}").find(r)?.value?.toIntOrNull() ?: return null
        return if (n <= 21) n else null
    }

    /** Landen in volgorde van voorkeur: Kijkwijzer eerst, dan VS, VK, Duitsland. */
    private val certCountries = listOf("NL", "US", "GB", "DE", "BE", "FR", "IE", "AU", "CA")

    /**
     * Minimumleeftijd uit TMDB-certificeringen (land → code). Per land de hoogste (een film kan meerdere
     * uitgaven hebben); het eerste land uit [certCountries] dat iets bruikbaars heeft wint; anders het
     * strengste van alle andere landen.
     */
    fun ageFromCertifications(certs: List<Pair<String, String>>): Int? {
        val byCountry = HashMap<String, Int>()
        for ((c, code) in certs) {
            val a = minimumAge(code) ?: continue
            val cc = c.uppercase()
            byCountry[cc] = maxOf(byCountry[cc] ?: 0, a)
        }
        for (c in certCountries) byCountry[c]?.let { return it }
        return byCountry.values.maxOrNull()
    }

    /**
     * Mag dit profiel dit zien? Ontgrendeld = alles. Zie [allowedFor].
     */
    fun allowed(rated: String?, group: String, limit: Int?, genre: String? = null, strict: Boolean = false,
                info: AgeRating? = null): Boolean {
        if (isUnlocked) return true
        return allowedFor(rated, group, limit, genre, strict, info)
    }

    /**
     * Dezelfde regel zonder app-status (veilig op de achtergrond, bijv. Dispatchers.Default).
     * - geen grens → alles; volwassenengroep → nooit
     * - bekende leeftijd (TMDB: Kijkwijzer eerst; anders OMDb "Rated") → alleen als die ≤ de grens is
     * - onbekende leeftijd, gewoon profiel → toegestaan
     * - onbekende leeftijd, kinderprofiel / t/m 12 ([strict]): nooit bij horror/thriller/misdaad/oorlog;
     *   wel bij animatie/familie/kids, of als de categorie duidelijk voor kinderen is; anders verborgen
     */
    fun allowedFor(rated: String?, group: String, limit: Int?, genre: String? = null, strict: Boolean = false,
                   info: AgeRating? = null): Boolean {
        if (limit == null) return true
        if (isAdultGroup(group)) return false
        val age = info?.age ?: minimumAge(rated)
        if (age != null) return age <= limit
        if (!strict) return true
        if (AgeRatings.isAdultGenre(info, genre)) return false
        if (AgeRatings.isKidsGenre(info, genre)) return true
        return isKidsGroup(group)
    }
}

/**
 * Herinneringen voor programma's: melding 2 minuten voor de start (AlarmManager + [ReminderReceiver]).
 * Op TV verschijnen meldingen niet altijd: [due] is dan het herinneringsbanner-signaal in de app
 * (start met [startChecking], zoals ReminderCenter op Apple TV; de banner staat in BingrRoot).
 *
 * Per profiel (zie docs/ACCOUNT.md → "Herinneringen per profiel" in iptv-ios-appletv): elk profiel zet, ziet en
 * verwijdert alleen zijn eigen herinneringen ([idFor], [isMine]); [Store.reminders] bevat die van alle profielen en
 * meldingen/banners komen voor allemaal, met de profielnaam erbij ([displayTitle]).
 */
object Reminders {
    const val CHANNEL_ID = "reminders"
    const val EXTRA_ID = "reminderID"
    const val EXTRA_TITLE = "title"
    const val EXTRA_CHANNEL_NAME = "channelName"
    const val EXTRA_ITEM_ID = "itemID"
    const val EXTRA_PLAYLIST_ID = "playlistID"

    /** Herinnering die nu begint (voor een banner in de app); zet terug op null na tonen. */
    var due by mutableStateOf<ProgramReminder?>(null)
    private var checker: Job? = null

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, L("Reminders"), NotificationManager.IMPORTANCE_HIGH)
            nm.createNotificationChannel(ch)
        }
    }

    /**
     * Bij het starten van de app (BingrApplication): meldingskanaal + alarmen opnieuw plannen.
     * Met actieve pushmeldingen ([Push.active]) geen lokale alarmen: de server stuurt de herinnering (docs/PUSH.md).
     */
    fun createChannel(ctx: Context) {
        ensureChannel(ctx)
        if (Push.active) return
        // na een herstart van het toestel zijn alarmen weg: toekomstige herinneringen opnieuw plannen
        runCatching { Store.reminders.filter { it.start > System.currentTimeMillis() }.forEach { schedule(ctx, it) } }
    }

    // MARK: per profiel

    /**
     * ID per profiel: het hoofdprofiel houdt "<zender>-<start in s>" (zoals oudere apps), elk ander profiel
     * "p:<profiel-ID in kleine letters>:<zender>-<start>". Zo kunnen twee profielen hetzelfde programma onthouden.
     */
    fun makeID(channelID: String, start: Long, profileID: String?): String {
        val base = "$channelID-${start / 1000}"
        val main = Store.mainProfileID
        if (profileID == null || (main != null && profileID.equals(main, ignoreCase = true))) return base
        return "p:${profileID.lowercase()}:$base"
    }

    /** Profiel uit de ID ("p:<profiel>:…"); null = geen profiel in de ID. */
    fun profileFromID(id: String): String? {
        if (!id.startsWith("p:")) return null
        val end = id.indexOf(':', 2)
        return if (end > 2) id.substring(2, end) else null
    }

    /** Profiel van deze herinnering (null = hoofdprofiel). Ook als een oudere app `profileID` niet meeschreef. */
    fun owner(r: ProgramReminder): String? = r.profileID ?: profileFromID(r.id)

    /** Het actieve profiel, of het hoofdprofiel als er (nog) niemand gekozen is. */
    private val currentProfile: String? get() = Store.activeProfileID ?: Store.mainProfileID

    /** ID van de herinnering van het actieve profiel voor dit programma. */
    fun idFor(channelID: String, start: Long): String = makeID(channelID, start, currentProfile)

    /** Hoort deze herinnering bij het actieve profiel? (Zonder profiel = hoofdprofiel; ID's zonder hoofdlettergevoeligheid.) */
    fun isMine(r: ProgramReminder): Boolean {
        val active = Store.activeProfileID ?: return true
        val o = owner(r) ?: return Store.isMain(active)
        return o.equals(active, ignoreCase = true)
    }

    /** Titel voor melding/banner: met de profielnaam als het account meer profielen heeft ("Voor Emma: Journaal"). */
    fun displayTitle(r: ProgramReminder): String {
        // build 153: programmatitel zonder taal- en pipe-aanduidingen
        val t = ContentLanguage.displayTitle(r.title)
        if (Store.profiles.size <= 1) return t
        val id = owner(r) ?: Store.mainProfileID ?: return t
        val name = Store.profiles.firstOrNull { it.id.equals(id, ignoreCase = true) }?.name?.trim().orEmpty()
        return if (name.isEmpty()) t else L("For %@: %@", name, t)
    }

    /**
     * Mag de banner deze zender openen? Eigen herinnering altijd (de bibliotheek is al gefilterd); die van een ander
     * profiel niet als het actieve profiel een leeftijdsgrens heeft.
     */
    fun canWatch(r: ProgramReminder): Boolean = isMine(r) || Store.activeProfile?.effectiveAgeLimit == null

    /**
     * Zelfde controle voor een tik op een herinnering (push of lokale melding, `bingr://live?channel=&profile=`):
     * [profileID] = profiel van de herinnering (leeg = hoofdprofiel).
     */
    fun canOpenFor(profileID: String?): Boolean {
        val active = Store.activeProfileID ?: return true
        val owner = profileID?.trim()?.takeIf { it.isNotEmpty() }
        val mine = if (owner == null) Store.isMain(active)
            else owner.equals(active, ignoreCase = true) ||
                (Store.isMain(active) && Store.mainProfileID?.equals(owner, ignoreCase = true) == true)
        return mine || Store.activeProfile?.effectiveAgeLimit == null
    }

    /** Herinnering voor het actieve profiel (elk profiel heeft zijn eigen herinneringen, ook een kinderprofiel). */
    fun add(channel: Channel, program: EPGProgram, playlistID: String) {
        val owner = currentProfile
        val r = ProgramReminder(id = idFor(channel.id, program.start), playlistID = playlistID,
            channelID = channel.id, channelName = channel.name, title = program.title, start = program.start,
            profileID = owner?.takeUnless { Store.isMain(it) }?.lowercase())
        Store.addReminder(r)
        // met actieve pushmeldingen stuurt de server de herinnering (geen lokaal alarm, anders dubbel)
        if (!Push.active) schedule(AppState.appContext, r)
    }

    /** Een profiel is verwijderd (hier of op een ander apparaat): zijn herinneringen en alarmen gaan weg. */
    fun profileDeleted(profileID: String) {
        val gone = Store.reminders.filter { owner(it)?.equals(profileID, ignoreCase = true) == true }
        if (gone.isEmpty()) return
        val ctx = AppState.appContext
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        gone.forEach { r -> runCatching { am.cancel(pending(ctx, r)) } }
        gone.forEach { Store.removeReminder(it.id) }
    }

    fun remove(id: String) {
        Store.removeReminder(id)
        val ctx = AppState.appContext
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(ctx, ProgramReminder(id = id, playlistID = "", channelID = "", channelName = "", title = "", start = 0)))
    }

    private fun pending(ctx: Context, r: ProgramReminder): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).apply {
            action = "nl.lrvdlinden.bingr.REMINDER.${r.id}"
            putExtra(EXTRA_ID, r.id)
            putExtra(EXTRA_TITLE, r.title)
            putExtra(EXTRA_CHANNEL_NAME, r.channelName)
            putExtra(EXTRA_ITEM_ID, r.channelID)
            putExtra(EXTRA_PLAYLIST_ID, r.playlistID)
        }
        return PendingIntent.getBroadcast(ctx, r.id.hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * Herinneringen uit het Bingr-account (build 44): lijst vervangen, alarmen van verdwenen herinneringen
     * annuleren en nieuwe (toekomstige) plannen.
     */
    fun applySynced(list: List<ProgramReminder>) {
        val ctx = AppState.appContext
        val old = Store.reminders
        val now = System.currentTimeMillis()
        val keep = list.filter { it.start > now - 3_600_000 }
        Store.applySyncedReminders(keep)
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        old.filter { o -> keep.none { it.id == o.id } }.forEach { runCatching { am.cancel(pending(ctx, it)) } }
        if (!Push.active) keep.filter { it.start > now && it !in old }.forEach { runCatching { schedule(ctx, it) } }
    }

    /** Na het opstarten van het apparaat zijn alle alarmen weg: de toekomstige herinneringen opnieuw plannen. */
    fun rescheduleAll(ctx: Context) {
        if (Push.active) return
        val now = System.currentTimeMillis()
        Store.reminders.filter { it.start > now }.forEach { runCatching { schedule(ctx, it) } }
    }

    /** Alle alarmen annuleren (ander account op dit apparaat). */
    fun cancelAll() {
        val ctx = AppState.appContext
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        Store.reminders.forEach { runCatching { am.cancel(pending(ctx, it)) } }
    }

    private fun schedule(ctx: Context, r: ProgramReminder) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val fire = maxOf(r.start - 120_000L, System.currentTimeMillis() + 1_000L)
        val pi = pending(ctx, r)
        val exactAllowed = if (Build.VERSION.SDK_INT >= 31) am.canScheduleExactAlarms() else true
        try {
            if (exactAllowed) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fire, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fire, pi)
        } catch (_: SecurityException) {
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fire, pi) }
        }
    }

    /**
     * In-app controle elke 20 s: zet [due] als een herinnering (van welk profiel ook) (bijna) begint. Alleen als de
     * app in beeld is (zoals op Samsung TV): anders gaat de herinnering niet ongezien uit de lijst.
     */
    fun startChecking() {
        checker?.cancel()
        checker = AppScope.launch {
            while (isActive) {
                val visible = MainActivity.current?.lifecycle?.currentState
                    ?.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) == true
                val now = System.currentTimeMillis()
                // met actieve pushmeldingen komt de herinnering als systeemmelding (ook op de voorgrond): geen banner erbij
                if (visible && due == null && !Push.active) {
                    Store.reminders.firstOrNull { it.start <= now + 60_000 && it.start > now - 300_000 }?.let { r ->
                        due = r
                        Store.removeReminder(r.id)
                    }
                }
                delay(20_000)
            }
        }
    }

    /** Toont de melding (aangeroepen door [ReminderReceiver]). */
    @SuppressLint("MissingPermission")
    fun showNotification(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        // overgestapt op pushmeldingen terwijl dit alarm nog stond: de server stuurt hem al
        if (Push.active) return
        // met de profielnaam (ook voor een profiel dat nu niet actief is); de herinnering zelf heeft de nieuwste naam
        val title = Store.reminders.firstOrNull { it.id == id }?.let { displayTitle(it) } ?: intent.getStringExtra(EXTRA_TITLE) ?: ""
        val channelName = ContentLanguage.channelTitle(intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: "")
        ensureChannel(ctx)
        // tik: zender openen met profielcontrole, net als een herinnering via push (bingr://live, PushNav)
        val owner = Store.reminders.firstOrNull { it.id == id }?.let { owner(it) } ?: profileFromID(id)
        val live = android.net.Uri.Builder().scheme("bingr").authority("live")
            .appendQueryParameter("channel", intent.getStringExtra(EXTRA_ITEM_ID) ?: "")
            .apply { if (owner != null) appendQueryParameter("profile", owner) }
            .build()
        val open = Intent(ctx, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = live
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_ITEM_ID, intent.getStringExtra(EXTRA_ITEM_ID))
            putExtra(EXTRA_PLAYLIST_ID, intent.getStringExtra(EXTRA_PLAYLIST_ID))
        }
        val contentIntent = PendingIntent.getActivity(ctx, id.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(L("Starts in 2 minutes on %@", channelName))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        val nm = NotificationManagerCompat.from(ctx)
        if (nm.areNotificationsEnabled()) runCatching { nm.notify(id.hashCode(), n) }
        // ook in de app laten zien (TV / app open)
        Store.reminders.firstOrNull { it.id == id }?.let { due = it }
        Store.removeReminder(id)
    }
}

/** Ontvangt het alarm van een herinnering en toont de melding; tikken opent de app op die zender. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.showNotification(context, intent)
    }
}

/** Na een herstart van het apparaat (of een app-update) de herinneringen opnieuw plannen. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Reminders.rescheduleAll(context.applicationContext)
        }
    }
}
