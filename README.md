# Bingr voor Android

Native Android-versie van **Bingr**, de IPTV-speler voor je eigen afspeellijsten en mediaservers. Werkt op telefoon, tablet en Android TV / Google TV / Fire TV. De iOS-, iPadOS-, tvOS- en macOS-versie staat in [iptv-ios-appletv](https://github.com/LRvdLinden/iptv-ios-appletv).

## Functies

- **Bronnen:** Xtream Codes, M3U/M3U8 (met XMLTV-gids), Stalker-portals, Plex, Jellyfin en Emby
- **Profielen:** "Wie kijkt er?", subprofielen met eigen lijsten en voortgang, kinderprofiel met strikt filter, pincode per profiel
- **Home:** hero, Verder kijken (met voortgangsbalk, hervatten of opnieuw kijken), Mijn lijst, Top 10 en categorieën
- **Live TV:** zenders, gids (EPG), zoeken in alle zenders, favorieten, catch-up, herinneringen
- **Details:** seizoenen en afleveringen, TMDB- en Trakt-scores, gezien-vinkjes, trailer en delen
- **Speler:** Media3/ExoPlayer en VLC, volgende aflevering, audio- en ondertitelsporen, OpenSubtitles, instelbare ondertitelgrootte, Chromecast, multiview, bediening met de afstandsbediening op Android TV
- **Trakt:** scrobbelen, gezien en watchlist synchroniseren (hoofdprofiel)
- **Talen:** Nederlands, Engels en meer (gedeeld met de iOS-app)

## Bouwen

1. Open deze map in **Android Studio** (*File → Open*). Gradle 8.10.2, AGP 8.7.3, Kotlin 2.1, JDK 17.
2. Zet je sleutels in `local.properties` (staat niet in git):
   ```
   TRAKT_CLIENT_ID=...
   # TRAKT_CLIENT_SECRET=...   (optioneel: Trakt-koppeling werkt met PKCE zonder secret)
   TMDB_API_KEY=...
   TMDB_ACCESS_TOKEN=...
   OMDB_API_KEY=...
   OPENSUBTITLES_API_KEY=...
   ```
3. *Build → Build App Bundle(s) / APK(s) → Build APK(s)*, of druk op ▶ voor een toestel of Android TV-emulator.

Een eigen release-sleutel stel je in met `BINGR_KEYSTORE`, `BINGR_KEYSTORE_PASSWORD`, `BINGR_KEY_ALIAS` en `BINGR_KEY_PASSWORD` in `local.properties`; zonder sleutel wordt met de debug-sleutel getekend.

**Automatisch bouwen:** de workflow `Build` bouwt bij elke push een APK (te downloaden onder *Actions → run → Artifacts*). Zet dezelfde sleutels als repository-secrets bij *Settings → Secrets and variables → Actions*.

Versie en buildnummer staan in `gradle.properties` (`BINGR_VERSION`, `BINGR_BUILD`) en lopen gelijk met de iOS-app. De vertalingen (`app/src/main/assets/Translations.json`) zijn een kopie van `Shared/Resources/Translations.json` uit de iOS-repo.

`CONTRACT.md` beschrijft de opzet van de code.

## Opmerking

Bingr is een speler: je gebruikt je eigen, legale afspeellijsten en mediaservers.
