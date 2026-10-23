package nl.lrvdlinden.bingr

// Build 141: kleine vervangers voor de Android-kant van core/Models.kt, alleen voor de belastingtest
// (tools/test/ProfileOpenStressTest.kt draait zonder Android op de JVM). Niet in de app.
fun L(s: String, vararg a: Any?): String = s
object TrackPreferences { const val AUTO_ALWAYS = "always" }
