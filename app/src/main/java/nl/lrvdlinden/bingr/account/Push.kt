package nl.lrvdlinden.bingr

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import kotlin.coroutines.resume

/**
 * Pushmeldingen via FCM (contract: docs/PUSH.md in iptv-ios-appletv). Alleen telefoon/tablet (platform `android`);
 * Android TV / Google TV / Fire TV registreren niet. Zonder google-services.json in de build ([BuildConfig.PUSH_CONFIGURED])
 * start Firebase niet en doet dit object niets.
 *
 * - Registreren bij de Edge Function `push` (`register`) na inloggen, met toestemming en met de schakelaar aan; opnieuw
 *   bij een nieuwe FCM-token, app-taal of build. Afmelden (`unregister`) bij uitloggen, schakelaar uit of als de
 *   toestemming is ingetrokken.
 * - Zolang push actief is ([active]) plant de app geen lokale herinneringen (AlarmManager): de server stuurt ze.
 *   Bestaande wekkers worden bij de overstap opgeruimd en bij het terugvallen weer gepland ([refreshAlarms]).
 * - Eén meldingskanaal per soort: releases, berichten (beheer) en herinneringen ([Reminders.CHANNEL_ID]).
 */
object Push {
    const val CH_RELEASES = "releases"
    const val CH_MESSAGES = "messages"

    const val EXTRA_KIND = "nl.lrvdlinden.bingr.push.kind"
    const val EXTRA_URL = "nl.lrvdlinden.bingr.push.url"
    const val EXTRA_DEEPLINK = "nl.lrvdlinden.bingr.push.deeplink"
    const val EXTRA_MSG_ID = "nl.lrvdlinden.bingr.push.msg_id"

    /** Schakelaar "Pushmeldingen" (per apparaat, gaat niet mee met het account). */
    private const val K_ENABLED = "push.enabled"
    /** SHA-256 van token|taal|build|account|apparaat van de laatste geslaagde `register` (null = niet geregistreerd). */
    private const val K_SIG = "push.sig"
    /** Account waarvoor dit apparaat geregistreerd is. */
    private const val K_USER = "push.user"
    /** true = de lokale wekkers van herinneringen zijn geannuleerd omdat de server ze stuurt. */
    private const val K_ALARMS_OFF = "push.alarmsOff"
    /** Laatst getoonde msg_id's (dedup). */
    private const val K_SEEN = "push.seen"
    /** Uitleg + toestemmingsvraag voor meldingen is al getoond. */
    private const val K_ASKED = "push.asked"
    private const val MAX_SEEN = 100

    /** Teller zodat Compose (instellingen) opnieuw tekent na een wijziging in Prefs of de toestemming. */
    private var tick by mutableIntStateOf(0)
    private val lock = Mutex()
    private val seenLock = Any()

    /** Uitleg vóór de systeemvraag om meldingen (Android 13+, na inloggen, één keer). */
    var showPrimer by mutableStateOf(false)
        private set

    private val ctx: Context get() = AppState.appContext

    /** Deze build heeft een Firebase-configuratie en Firebase is gestart. */
    val configured: Boolean
        get() = BuildConfig.PUSH_CONFIGURED &&
            runCatching { FirebaseApp.getApps(ctx).isNotEmpty() }.getOrDefault(false)

    /** Push kan op dit apparaat: geconfigureerd en geen tv. */
    val supported: Boolean get() = configured && !AppState.isTV

    var enabled: Boolean
        get() { tick; return Prefs.bool(K_ENABLED, true) }
        set(v) {
            Prefs.put(K_ENABLED, v); tick++
            if (v && !hasPermission) MainActivity.current?.requestNotificationPermission()
            sync()
        }

    /** Meldingen mogen (Android 13+: POST_NOTIFICATIONS; ook niet door de gebruiker uitgezet in Android). */
    val permitted: Boolean
        get() { tick; return runCatching { NotificationManagerCompat.from(ctx).areNotificationsEnabled() }.getOrDefault(false) }

    private val hasPermission: Boolean
        get() = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Bij `push` geregistreerd voor het huidige account. */
    val registered: Boolean
        get() {
            tick
            val u = Prefs.string(K_USER) ?: return false
            return Prefs.string(K_SIG) != null && u == Account.userId
        }

    /** Push is echt in gebruik: de server stuurt releases, berichten en herinneringen naar dit apparaat. */
    val active: Boolean
        get() = supported && Account.configured && Account.isSignedIn && enabled && registered && permitted

    // MARK: registreren

    /** Registratie bijwerken (op de achtergrond, stil): na inloggen, bij de voorgrond, taal, schakelaar, toestemming, token. */
    fun sync() {
        if (!supported) return
        AppScope.launch {
            runCatching { lock.withLock { syncLocked() } }
            tick++
            refreshAlarms()
        }
    }

    /** FCM heeft een nieuwe token gemaakt (FirebaseMessagingService.onNewToken; achtergrond-thread). */
    fun tokenChanged() {
        AppScope.launch { sync() }
    }

    private suspend fun syncLocked() {
        ensureChannels(ctx)
        val want = enabled && Account.configured && Account.isSignedIn && permitted
        if (!want) {
            if (Prefs.string(K_SIG) == null) return
            // afmelden; mislukt het (offline), dan bij de volgende sync opnieuw (de server stuurt intussen nog)
            if (!Account.isSignedIn || Account.pushCall(unregisterBody())) clearLocal()
            return
        }
        val token = fetchToken() ?: return
        val user = Account.userId ?: return
        val sig = sha256(listOf(token, Localizer.language, BuildConfig.VERSION_CODE.toString(), user, Account.deviceId).joinToString("|"))
        if (Prefs.string(K_SIG) == sig && Prefs.string(K_USER) == user) return
        val body = buildJsonObject {
            put("action", "register")
            put("device_id", Account.deviceId)
            put("platform", "android")
            put("channel", "fcm")
            put("token", token)
            put("lang", Localizer.language)
            put("app_version", BuildConfig.VERSION_NAME)
            put("app_build", BuildConfig.VERSION_CODE)
        }
        if (Account.pushCall(body)) {
            Prefs.put(K_SIG, sig)
            Prefs.put(K_USER, user)
        }
    }

    private fun unregisterBody(): JsonObject = buildJsonObject {
        put("action", "unregister")
        put("device_id", Account.deviceId)
    }

    /** Bij uitloggen, vóór het wissen van de sessie (Account.signOut): afmelden bij `push`. */
    suspend fun unregisterNow() {
        if (Prefs.string(K_SIG) == null) return
        lock.withLock { Account.pushCall(unregisterBody()) }
        clearLocal()
    }

    /** Sessie gewist (uitgelogd, verwijderd, geblokkeerd): lokaal niet meer geregistreerd; lokale herinneringen terug. */
    fun signedOut() {
        clearLocal()
        runCatching { refreshAlarms() }
    }

    private fun clearLocal() {
        Prefs.remove(K_SIG)
        Prefs.remove(K_USER)
        tick++
    }

    private suspend fun fetchToken(): String? = suspendCancellableCoroutine<String?> { c ->
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { t ->
                val v = if (t.isSuccessful) t.result?.takeIf { it.isNotBlank() } else null
                if (c.isActive) c.resume(v)
            }
        } catch (e: Exception) {
            if (c.isActive) c.resume(null)
        }
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    // MARK: herinneringen

    /**
     * Lokale wekkers van herinneringen gelijktrekken met [active]: actief → alle wekkers weg (de server stuurt de
     * herinnering, anders dubbel); niet (meer) actief → de toekomstige herinneringen weer lokaal plannen.
     */
    fun refreshAlarms() {
        val a = active
        val off = Prefs.bool(K_ALARMS_OFF)
        if (a && !off) {
            Reminders.cancelAll()
            Prefs.put(K_ALARMS_OFF, true)
        } else if (!a && off) {
            Prefs.put(K_ALARMS_OFF, false)
            Reminders.rescheduleAll(ctx)
        }
    }

    // MARK: toestemming (Android 13+)

    /** Hoofdscherm in beeld (na inloggen en profielkeuze): zo nodig eerst uitleggen waarom, dan registreren. */
    fun mainShown() {
        if (Build.VERSION.SDK_INT >= 33 && !AppState.isTV && !Prefs.bool(K_ASKED) && !hasPermission &&
            (Account.isSignedIn || !Account.configured)) {
            showPrimer = true
        }
        sync()
    }

    /** Antwoord op de uitleg: "Doorgaan" → systeemvraag, "Niet nu" → niet meer vragen (kan via Instellingen). */
    fun primerAnswered(allow: Boolean) {
        Prefs.put(K_ASKED, true)
        showPrimer = false
        if (allow) MainActivity.current?.requestNotificationPermission()
    }

    /** Systeemvraag beantwoord (MainActivity). */
    fun permissionResult() {
        tick++
        sync()
    }

    /** Android-instellingen voor de meldingen van Bingr (als de toestemming geweigerd is). */
    fun openSystemSettings(c: Context) {
        val i = if (Build.VERSION.SDK_INT >= 26) {
            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, c.packageName)
        } else {
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.packageName))
        }
        runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    // MARK: meldingen tonen

    /** Kanalen voor releases en berichten (herinneringen: [Reminders.createChannel]); namen in de app-taal. */
    fun ensureChannels(c: Context) {
        if (Build.VERSION.SDK_INT < 26 || AppState.isTV) return
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching {
            nm.createNotificationChannels(listOf(
                NotificationChannel(CH_RELEASES, L("New versions"), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CH_MESSAGES, L("Messages from Bingr"), NotificationManager.IMPORTANCE_HIGH),
            ))
        }
        Reminders.ensureChannel(c)
    }

    /** true = nieuw (en nu als gezien bewaard); false = deze msg_id is al getoond. */
    private fun markSeen(id: String): Boolean {
        synchronized(seenLock) {
            val list = Prefs.string(K_SEEN)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()
            if (id in list) return false
            Prefs.put(K_SEEN, (list + id).takeLast(MAX_SEEN).joinToString("\n"))
            return true
        }
    }

    /**
     * Pushbericht binnen (ook als de app op de voorgrond staat): systeemmelding in het kanaal van de soort, met
     * dedup op `data.msg_id`. Tikken opent [MainActivity] met de soort, `url` en `deeplink` ([PushNav.handle]).
     */
    @SuppressLint("MissingPermission")
    fun show(c: Context, m: RemoteMessage) {
        if (AppState.isTV || !Account.isSignedIn || !enabled) return
        val d = m.data
        val title = m.notification?.title ?: d["title"] ?: ""
        val body = m.notification?.body ?: d["body"] ?: ""
        if (title.isBlank() && body.isBlank()) return
        val msgId = d["msg_id"]?.takeIf { it.isNotBlank() } ?: m.messageId
        if (msgId != null && !markSeen(msgId)) return
        val kind = d["kind"] ?: "admin"
        val channel = when (kind) {
            "release" -> CH_RELEASES
            "reminder" -> Reminders.CHANNEL_ID
            else -> CH_MESSAGES
        }
        ensureChannels(c)
        val notifId = (msgId ?: (title + body)).hashCode()
        val open = Intent(c, MainActivity::class.java).apply {
            action = "nl.lrvdlinden.bingr.PUSH.$notifId"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_KIND, kind)
            d["url"]?.takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_URL, it) }
            d["deeplink"]?.takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_DEEPLINK, it) }
            msgId?.let { putExtra(EXTRA_MSG_ID, it) }
        }
        val pi = PendingIntent.getActivity(c, notifId, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_stat_bingr)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(if (kind == "release") NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
        if (kind == "reminder") b.setCategory(NotificationCompat.CATEGORY_REMINDER)
        val nm = NotificationManagerCompat.from(c)
        if (nm.areNotificationsEnabled()) runCatching { nm.notify(notifId, b.build()) }
    }
}

/** Ontvangt FCM-berichten en nieuwe tokens (alleen als de build Firebase heeft; zie [Push]). */
class BingrMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        Push.tokenChanged()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        Push.show(applicationContext, message)
    }
}

/**
 * Tik op een pushmelding of een bingr://-link (docs/PUSH.md):
 * - `release` → `bingr://changelog?p=<platform>&b=<build>`: de app heeft geen eigen changelogscherm, dus de changelog
 *   van precies die build op bingr.online in de browser.
 * - `reminder` → `bingr://live?channel=<id>&profile=<profiel-id>`: zender openen zoals de herinneringsbanner, met
 *   dezelfde profielcontrole ([Reminders.canOpenFor]); pas in het hoofdscherm (na inloggen en profielkeuze).
 * - `admin` → `data.url` (https) in de browser, anders alleen de app.
 * - build 120: `bingr://title?type=movie|tv&id=<tmdb-id>&q=<titel>&y=<jaar>` en de app link
 *   `https://bingr.online/titel/?…` (e-mail van Bingr) → [pendingTitle]: detailpagina of zoekscherm ([TitleLink]).
 */
object PushNav {
    data class Live(val channel: String, val profile: String?)

    /** Zender uit een herinnering die geopend moet worden zodra het hoofdscherm er is. */
    var pending by mutableStateOf<Live?>(null)
        private set

    /**
     * Build 120: film of serie uit een link (e-mail van Bingr: https://bingr.online/titel/… of bingr://title?…).
     * [MainScreen] zoekt hem in de bibliotheek zodra die geladen is (dus ook na inloggen en "Wie kijkt er?").
     */
    var pendingTitle by mutableStateOf<TitleLink?>(null)

    fun handle(intent: Intent?) {
        val i = intent ?: return
        val data = i.data
        // app link (android:autoVerify): https://bingr.online/titel/?type=&id=&q=&y=
        if (data != null && i.action == Intent.ACTION_VIEW && (data.scheme.equals("https", ignoreCase = true) || data.scheme.equals("http", ignoreCase = true))) {
            i.data = null
            TitleLink.parse(data)?.let { openTitle(it) }
            return
        }
        if (data != null && data.scheme.equals("bingr", ignoreCase = true)) {
            i.data = null
            openDeepLink(data)
            return
        }
        // eigen melding (Push.show) of een melding met notification-blok die Android zelf toonde (data in de extras)
        val fromSystem = i.hasExtra("google.message_id") || i.hasExtra("msg_id")
        val kind = i.getStringExtra(Push.EXTRA_KIND) ?: (if (fromSystem) i.getStringExtra("kind") else null) ?: return
        val url = i.getStringExtra(Push.EXTRA_URL) ?: (if (fromSystem) i.getStringExtra("url") else null)
        val link = i.getStringExtra(Push.EXTRA_DEEPLINK) ?: (if (fromSystem) i.getStringExtra("deeplink") else null)
        listOf(Push.EXTRA_KIND, Push.EXTRA_URL, Push.EXTRA_DEEPLINK, Push.EXTRA_MSG_ID, "kind", "url", "deeplink", "msg_id", "google.message_id")
            .forEach { i.removeExtra(it) }
        val deep = link?.let { runCatching { Uri.parse(it) }.getOrNull() }?.takeIf { it.scheme.equals("bingr", ignoreCase = true) }
        when (kind) {
            "release", "reminder" -> when {
                deep != null -> openDeepLink(deep)
                kind == "release" -> openUrl(url ?: "https://bingr.online/changelog/?p=android")
            }
            else -> url?.let { openUrl(it) }
        }
    }

    private fun openTitle(link: TitleLink) {
        // de detailpagina of het zoekscherm komt boven het hoofdscherm: speler en multiview dicht
        AppState.playing = null
        AppState.showMultiview = false
        pendingTitle = link
    }

    private fun openDeepLink(u: Uri) {
        when (u.host?.lowercase()) {
            "title", "titel" -> TitleLink.parse(u)?.let { openTitle(it) }
            "changelog" -> {
                val p = u.getQueryParameter("p")?.lowercase()?.filter { it in 'a'..'z' }?.takeIf { it.isNotEmpty() } ?: "android"
                val b = u.getQueryParameter("b")?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() }
                openUrl("https://bingr.online/changelog/?p=$p" + (b?.let { "#build-$it" } ?: ""))
            }
            "live" -> {
                val ch = u.getQueryParameter("channel")?.takeIf { it.isNotBlank() } ?: return
                pending = Live(ch, u.getQueryParameter("profile")?.takeIf { it.isNotBlank() })
                consume()
            }
        }
    }

    /** Alleen https/http, in de browser. */
    private fun openUrl(url: String) {
        val u = runCatching { Uri.parse(url) }.getOrNull() ?: return
        val scheme = u.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return
        val c: Context = MainActivity.current ?: AppState.appContext
        runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Wachtende zender openen als het hoofdscherm er is (BingrRoot roept dit aan bij het hoofdscherm). */
    fun consume() {
        val t = pending ?: return
        if (AppState.stage != AppState.Stage.MAIN) return
        pending = null
        // profielcontrole zoals de herinneringsbanner: geen zender van een ander profiel openen op een kinderprofiel
        if (!Reminders.canOpenFor(t.profile)) return
        val lib = Playlists.library
        val c = lib.channels.firstOrNull { it.id == t.channel }
        if (c != null) {
            AppState.play(Playables.channel(c, lib.nowPlaying(c)), zap = lib.channels)
            return
        }
        // zender in een andere server (of de bibliotheek laadt nog): openen zodra hij er is
        val r = Store.reminders.firstOrNull { it.channelID == t.channel }
        val p = r?.let { rr -> Store.playlists.firstOrNull { it.id == rr.playlistID } }
        if (p != null && Playlists.source?.id != p.id) AppState.open(p, thenShow = t.channel)
        else AppState.pendingItemID = t.channel
    }
}

/** Uitleg vóór de systeemvraag om meldingen (Android 13+), na inloggen in het hoofdscherm; één keer. */
@Composable
fun NotificationPrimerDialog() {
    Dialog(onDismissRequest = { Push.primerAnswered(false) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(24.dp).widthIn(min = 280.dp, max = 460.dp).clip(RoundedCornerShape(14.dp))
                .background(Theme.elevated).padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            Icon(Icons.Default.Notifications, null, tint = Theme.red, modifier = Modifier.padding(bottom = 10.dp))
            Text(L("Allow notifications?"), color = Color.White, fontSize = tsp(20), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(L("Bingr can let you know when a new version is available, when a program you set a reminder for is about to start, and when there's a message from Bingr. You can change this any time in Settings."),
                color = Color.White.copy(alpha = 0.85f), fontSize = tsp(15))
            Spacer(Modifier.height(18.dp))
            val fr = remember { FocusRequester() }
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NetflixButton(L("Not now"), kind = NetflixKind.SECONDARY) { Push.primerAnswered(false) }
                NetflixButton(L("Continue"), kind = NetflixKind.ACCENT, modifier = Modifier.focusRequester(fr)) { Push.primerAnswered(true) }
            }
            LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
        }
    }
}
