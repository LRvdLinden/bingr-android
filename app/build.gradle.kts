import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Versie/build: gelijk aan de iOS-app, bij te werken in gradle.properties
val buildNumber = (findProperty("BINGR_BUILD") as String?)?.toIntOrNull() ?: 1
val marketing = (findProperty("BINGR_VERSION") as String?) ?: "1.0.0"

// App-sleutels: uit GitHub Secrets (CI) of local.properties
val localProps = Properties().apply {
    val f = File(rootDir, "local.properties"); if (f.exists()) f.inputStream().use { load(it) }
}
fun key(name: String): String = (System.getenv(name) ?: localProps.getProperty(name) ?: "").trim().replace("\"", "")

android {
    namespace = "nl.lrvdlinden.bingr"
    compileSdk = 35

    defaultConfig {
        applicationId = "nl.lrvdlinden.bingr"
        minSdk = 24
        targetSdk = 35
        versionCode = buildNumber
        versionName = marketing
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        buildConfigField("String", "TRAKT_CLIENT_ID", "\"${key("TRAKT_CLIENT_ID")}\"")
        buildConfigField("String", "TRAKT_CLIENT_SECRET", "\"${key("TRAKT_CLIENT_SECRET")}\"")
        buildConfigField("String", "OPENSUBTITLES_API_KEY", "\"${key("OPENSUBTITLES_API_KEY")}\"")
        buildConfigField("String", "OMDB_API_KEY", "\"${key("OMDB_API_KEY")}\"")
        buildConfigField("String", "TMDB_API_KEY", "\"${key("TMDB_API_KEY").ifEmpty { key("TMDB_ACCESS_TOKEN") }}\"")
        // Bingr-account (Supabase): alleen ingebouwd, niet in de app aan te passen
        buildConfigField("String", "SUPABASE_URL", "\"${key("SUPABASE_URL").ifEmpty { "https://vjewsncmsxmhmhnoxwqx.supabase.co" }}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${key("SUPABASE_ANON_KEY").ifEmpty { "sb_publishable_lpIup6Omq5kW2k3oICGZ4g_HPGac5A3" }}\"")
    }

    // Eigen release-sleutel: BINGR_KEYSTORE (pad), BINGR_KEYSTORE_PASSWORD, BINGR_KEY_ALIAS, BINGR_KEY_PASSWORD
    // in local.properties of als omgevingsvariabelen. Zonder sleutel wordt met de debug-sleutel getekend.
    val keystorePath = key("BINGR_KEYSTORE")
    val hasKeystore = keystorePath.isNotEmpty() && file(keystorePath).exists()
    signingConfigs {
        if (hasKeystore) create("bingr") {
            storeFile = file(keystorePath)
            storePassword = key("BINGR_KEYSTORE_PASSWORD")
            keyAlias = key("BINGR_KEY_ALIAS").ifEmpty { "bingr" }
            keyPassword = key("BINGR_KEY_PASSWORD").ifEmpty { key("BINGR_KEYSTORE_PASSWORD") }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasKeystore) signingConfigs.getByName("bingr") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Vertalingen: app/src/main/assets/Translations.json (kopie van Shared/Resources/Translations.json uit de iOS-repo)

    // Lint (build 60): volledige controle na de release-build, alleen ter info in het CI-log (blokkeert nooit)
    lint {
        abortOnError = false
        textReport = true
        textOutput = file("build/reports/lint-results-release.txt")
        explainIssues = false
    }

    packaging {
        resources { excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES") }
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Spelers: Media3 (ExoPlayer) voor HLS/DASH/MP4/TS, VLC voor de rest
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("org.videolan.android:libvlc-all:3.5.1")

    // Chromecast
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")

    // Profielfoto kiezen
    implementation("androidx.activity:activity-ktx:1.9.3")

    // Downloads op de achtergrond (build 52): hervat na netwerkverlies en herstart
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}

// Lint na assembleRelease (zie lint { } hierboven): rapport in het CI-log, fouten blokkeren de APK niet
val printLintReport = tasks.register("printLintReport") {
    val report = layout.buildDirectory.file("reports/lint-results-release.txt")
    mustRunAfter(tasks.matching { it.name == "copyReleaseLintReports" })
    doLast { report.get().asFile.takeIf { it.exists() }?.let { println("=== Lint ===\n" + it.readText()) } }
}
tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy("lintRelease") }
tasks.matching { it.name == "lintRelease" }.configureEach { finalizedBy(printLintReport) }
