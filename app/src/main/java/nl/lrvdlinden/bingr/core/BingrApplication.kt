package nl.lrvdlinden.bingr

import android.app.Application
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.content.pm.PackageManager

class BingrApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppState.appContext = applicationContext
        Prefs.init(this)
        Secrets.init(this)
        Store.init(this)
        Collections.ensureLoaded()
        Localizer.init(this)
        Account.init(this)
        val ui = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        AppState.isTV = ui.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            !packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        // build 52: accentkleur, app-icoon en downloads (na isTV: op TV geen icoonkeuze en geen downloads)
        Accent.reload()
        IntroMarkers.reload()
        AppIcon.init()
        Downloads.init(this)
        Reminders.createChannel(this)
        ExternalDisplay.init(this)
    }
}
