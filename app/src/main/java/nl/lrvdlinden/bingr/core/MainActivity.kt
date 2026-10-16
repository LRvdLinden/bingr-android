package nl.lrvdlinden.bingr

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        current = this
        // build 97: knoppen in het beeld-in-beeld-venster (afspelen/pauzeren)
        androidx.core.content.ContextCompat.registerReceiver(
            this, pipReceiver, IntentFilter(ACTION_PIP), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        setContent { BingrTheme { BingrRoot() } }
        // herinneringen (van alle profielen) als banner in de app, ook op TV waar meldingen niet altijd verschijnen
        Reminders.startChecking()
        // meldingen (herinneringen, downloads): één keer vragen, alleen op telefoon/tablet (op TV niet)
        if (Build.VERSION.SDK_INT >= 33 && !AppState.isTV && !Prefs.bool("notifications.asked") &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Prefs.put("notifications.asked", true)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        // bestand van een download buiten de app verwijderd → niet meer als "Gedownload" tonen
        Downloads.verifyFiles()
        // build 75: terug in de app → Trakt-vinkjes bijwerken (wat je intussen elders keek), zoals iOS bij "active".
        // Zonder force: eerst /sync/last_activities, dus goedkoop als er niets veranderd is.
        if (AppState.stage == AppState.Stage.MAIN && Trakt.isLoggedIn) AppScope.launch { runCatching { Trakt.refresh() } }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(pipReceiver) }
        if (current === this) current = null
        super.onDestroy()
    }

    // Invoer bijhouden (build 56): zware achtergrondklussen en het zendervoorbeeld wachten tot er niet meer gescrold wordt
    @android.annotation.SuppressLint("RestrictedApi")   // lint ziet de gewone Activity-methode ten onrechte als androidx-intern
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        UserActivity.touch(repeat = event.repeatCount > 0)
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        UserActivity.touch()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        UserActivity.touch()
        return super.dispatchGenericMotionEvent(ev)
    }

    /** Naar het beginscherm terwijl er iets speelt → beeld-in-beeld (telefoon/tablet). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // build 97: vanaf Android 12 gaat dat vanzelf (setAutoEnterEnabled), zonder schokkende overgang
        if (Build.VERSION.SDK_INT >= 31 && autoEnter) return
        if (!AppState.isTV && AppState.playing != null && PlayerController.current?.isPlaying == true) enterPiP()
    }

    /** build 97: staat automatisch beeld-in-beeld nu aan (Android 12+)? */
    private var autoEnter = false

    /** build 97: afspelen/pauzeren vanuit het beeld-in-beeld-venster. */
    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val i = intent ?: return
            if (i.action != ACTION_PIP) return
            val p = PlayerController.current ?: return
            val play = i.getBooleanExtra(EXTRA_PLAY, false)
            if (play) p.resume() else p.pause()
            updatePiP(play)
        }
    }

    private fun pipAction(play: Boolean): RemoteAction? {
        if (Build.VERSION.SDK_INT < 26) return null
        val intent = Intent(ACTION_PIP).setPackage(packageName).putExtra(EXTRA_PLAY, play)
        val pi = PendingIntent.getBroadcast(this, if (play) 1 else 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val label = if (play) L("Play") else L("Pause")
        val icon = Icon.createWithResource(this, if (play) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause)
        return RemoteAction(icon, label, label, pi)
    }

    /** Instellingen van het beeld-in-beeld-venster voor de huidige afspeelstand. */
    private fun pipParams(playing: Boolean): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < 26) return null
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        pipAction(play = !playing)?.let { b.setActions(listOf(it)) }
        if (Build.VERSION.SDK_INT >= 31) {
            // alleen op telefoon/tablet en alleen terwijl er echt iets speelt
            val auto = playing && !AppState.isTV && AppState.playing != null
            b.setAutoEnterEnabled(auto)
            // video: geen naadloos meeschalen (dat is voor niet-video-inhoud), anders vervormt het beeld bij het wisselen
            b.setSeamlessResizeEnabled(false)
            autoEnter = auto
        }
        return b.build()
    }

    /** build 97: knoppen en automatisch beeld-in-beeld bijwerken als het afspelen start, pauzeert of stopt. */
    fun updatePiP(playing: Boolean) {
        if (Build.VERSION.SDK_INT < 26) return
        val params = pipParams(playing) ?: return
        runCatching { setPictureInPictureParams(params) }
    }

    fun enterPiP(): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        return runCatching {
            val params = pipParams(PlayerController.current?.isPlaying == true) ?: return false
            enterPictureInPictureMode(params)
        }.getOrDefault(false)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        AppState.inPiP = isInPictureInPictureMode
        // venster dichtgeveegd → stoppen
        if (!isInPictureInPictureMode && lifecycle.currentState == androidx.lifecycle.Lifecycle.State.CREATED) {
            PlayerController.current?.stop(); AppState.playing = null
        }
    }

    /** Scherm aan laten tijdens afspelen */
    fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        var current: MainActivity? = null
        private const val ACTION_PIP = "nl.lrvdlinden.bingr.PIP_PLAY_PAUSE"
        private const val EXTRA_PLAY = "play"
    }
}
