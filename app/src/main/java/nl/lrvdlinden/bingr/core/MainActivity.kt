package nl.lrvdlinden.bingr

import android.app.PictureInPictureParams
import android.content.res.Configuration
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        current = this
        setContent { BingrTheme { BingrRoot() } }
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
    }

    override fun onDestroy() { if (current === this) current = null; super.onDestroy() }

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
        if (!AppState.isTV && AppState.playing != null && PlayerController.current?.isPlaying == true) enterPiP()
    }

    fun enterPiP(): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        return runCatching {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
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

    companion object { var current: MainActivity? = null }
}
