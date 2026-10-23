package nl.lrvdlinden.bingr

import android.app.Application
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.content.pm.PackageManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

class BingrApplication : Application(), ImageLoaderFactory {
    /**
     * Eén Coil-ImageLoader (build 56): posters en zenderlogo's uit de schijfcache, ook als de IPTV-server "no-cache"
     * stuurt (anders werd elk logo bij het terugscrollen opnieuw gedownload), via de gedeelde OkHttp-client, zonder
     * crossfade-animatie per kaart. De grootte volgt de kaart (AsyncImage gebruikt de afmetingen van de layout).
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { Http.client.newBuilder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", Http.USER_AGENT).build())
        }.build() }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.20).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("image_cache")).maxSizeBytes(200L * 1024 * 1024).build() }
        .respectCacheHeaders(false)
        .crossfade(false)
        .build()

    override fun onCreate() {
        super.onCreate()
        AppState.appContext = applicationContext
        Prefs.init(this)
        // build 145: eenmalige omzetting: de vernieuwtermijn ("Afspeellijsten bijwerken": handmatig, elke start, 6 uur,
        // dagelijks, wekelijks) vervalt; "Servers laden bij openen" wordt "Vragen" (per apparaat, niet gesynct)
        Build145Logic.LoadOnOpen.migrate(Prefs.string(Build145Logic.LoadOnOpen.PREF))?.let { Prefs.put(Build145Logic.LoadOnOpen.PREF, it.raw) }
        if (Prefs.has("playlist.refresh")) Prefs.remove("playlist.refresh")
        Secrets.init(this)
        // build 141: belastingmodus (alleen de rooktest-build, BuildConfig.STRESS): geen account, main-thread-wachter
        StressMode.initApp()
        Store.init(this)
        Collections.ensureLoaded()
        Localizer.init(this)
        Account.init(this)
        val ui = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        AppState.isTV = ui.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            !packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        // build 52: accentkleur, app-icoon en downloads (na isTV: op TV geen icoonkeuze en geen downloads)
        // build 54: accentkleur per profiel; zonder account meteen de oude kleur naar de profielen (met account na de sync)
        if (!(Account.isSignedIn && Account.configured)) Accent.migrate(null)
        IntroMarkers.reload()
        AppIcon.init()
        Downloads.init(this)
        Reminders.createChannel(this)
        // pushmeldingen (alleen telefoon/tablet en alleen met Firebase in de build): kanalen voor releases en berichten
        if (Push.supported) Push.ensureChannels(this)
        ExternalDisplay.init(this)
    }
}
