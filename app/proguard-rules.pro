# Build 141 (M4): R8 voor release (kleiner en lastiger terug te lezen). Keep-regels zodat niets breekt; de bibliotheken
# (Media3, OkHttp, Coil, WorkManager, Firebase, Compose, kotlinx-serialization) leveren hun eigen regels mee.
# Let op: R8 verbergt geen ingebouwde sleutels (strings blijven leesbaar); die horen van de server te komen.

# Stacktraces in CI/Play Console leesbaar houden
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*
-renamesourcefileattribute SourceFile

# kotlinx-serialization: gegenereerde serializers en companions van onze eigen @Serializable-klassen
-keep,includedescriptorclasses class nl.lrvdlinden.bingr.**$$serializer { *; }
-keepclassmembers class nl.lrvdlinden.bingr.** {
    *** Companion;
}
-keepclasseswithmembers class nl.lrvdlinden.bingr.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers @kotlinx.serialization.Serializable class nl.lrvdlinden.bingr.** {
    <fields>;
    <init>(...);
}
-dontnote kotlinx.serialization.**

# Enums (opgeslagen bij naam in instellingen en JSON)
-keepclassmembers enum nl.lrvdlinden.bingr.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}

# Chromecast: de Cast-SDK maakt de OptionsProvider aan via de naam in het manifest (meta-data, geen component)
-keep class nl.lrvdlinden.bingr.CastOptionsProvider { <init>(); *; }

# WorkManager maakt de worker aan via zijn naam
-keep class nl.lrvdlinden.bingr.DownloadWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }

# WebView-brug van de trailer (HeroTrailer): methoden met @JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# libVLC: native code roept Java-methoden en -velden aan via JNI (op naam)
-keep class org.videolan.** { *; }
-dontwarn org.videolan.**

# Google Play-services / Cast en Firebase: waarschuwingen over optionele klassen negeren
-dontwarn com.google.android.gms.**
-dontwarn com.google.firebase.**

# OkHttp / Okio optionele platformen
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Build 141: Casting.receiverAddress leest het IP-adres van de Chromecast via reflectie
-keepclassmembers class com.google.android.gms.cast.CastDevice {
    public java.net.InetAddress getInetAddress();
    public java.net.Inet4Address getIpAddress();
}
