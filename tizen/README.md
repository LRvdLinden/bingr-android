# Bingr voor Samsung TV (Tizen)

Dezelfde Bingr als op Android TV en Apple TV, als Tizen-webapp voor Samsung-smart-tv's (2018 en nieuwer, Tizen 4.0+).
Je logt in met je Bingr-account (QR-code met je telefoon), en daarna staan je profielen, servers, Mijn lijst,
voortgang, collecties en instellingen er gewoon: alles komt uit je account en synchroniseert met je andere apparaten.

- App-id: `BingrTVapp.Bingr`, package-id: `BingrTVapp` (blijven altijd gelijk)
- Versie op de tv: `1.0.<build>` (zelfde buildnummer als de Android-app, uit `gradle.properties`)
- Platform in de apparatenlijst: `tizen`, naam `Samsung TV (<model>)`
- Download: de `.wgt` staat bij elke [release](https://github.com/LRvdLinden/bingr-android/releases/latest), naast de APK

## Wat erin zit

- **Inloggen** met QR-code / code op bingr.online/tv (of e-mail met het schermtoetsenbord), sessie vernieuwen, uitloggen, limiet van 15 apparaten met het scherm *Te veel apparaten*
- **Wie kijkt er?** met avatars (emoji, getekende figuurtjes en profielfoto's uit je account), kinderprofielen, pincode per profiel en accentkleur per profiel
- **Home** in Netflix-stijl: billboard, Verder kijken, Mijn lijst, Top 10, Nu live, Onlangs toegevoegd, je eigen **collecties** (per profiel, slim en handmatig) en categorierijen, in de volgorde van je startscherm-indeling
- **Live TV** met categorieën, favorieten, recente zenders, nu/straks uit de tv-gids, een **voorbeeld** van de zender waar je op staat en een **gids-raster** (tot 7 dagen terug)
- **Films** en **Series** met categorieën, detailpagina met seizoenen en afleveringen, voortgang in procenten en automatisch de huidige aflevering
- **Zoeken** met schermtoetsenbord
- **Speler** met Samsung AVPlay (HLS, TS, MP4), terugval op HTML5-video, hervatten, audio- en ondertitelsporen, zappen met CH+/CH− of omhoog/omlaag (met vertraging) en cijfertoetsen, **intro overslaan**, **volgende aflevering** met aftellen, reservebron bij een live-fout, foutscherm met *Probeer opnieuw* / *Andere bron*
- **Servers beheren op de tv** (Instellingen → Jouw servers, alleen het hoofdprofiel): toevoegen, wijzigen en verwijderen van M3U (+ EPG-URL), Xtream Codes (met test van de inlog en *Toch opslaan*), Stalker/MAC-portal, Plex (koppelcode op plex.tv/link), Jellyfin, Emby en Stremio (add-ons en/of Stremio-account). Hoofdserver kiezen. Opslaan gaat met `put_sources`, precies zoals de webconsole: altijd de hele lijst, tombstones bij verwijderen, onbekende velden, het `apple`/`android`-blok en `catchupOffsets` blijven staan, een leeg wachtwoord betekent ongewijzigd. Tekst invoeren met een schermtoetsenbord dat met de pijltjes werkt (lay-outs voor tekst, URL en MAC-adres).
- **Plex, Jellyfin, Emby en Stremio**: bladeren en afspelen (films, series met seizoenen en afleveringen, Stremio met *Kies een bron*), met dezelfde id's als de andere apps, zodat voortgang, Mijn lijst en collecties gewoon meegaan
- **Terugkijken** (catch-up) bij Xtream en Stalker vanuit de gids of het programmavenster (*Kijken vanaf het begin*), met de **terugkijk-correctie per zender** (−120…+120 min, gedeeld via de server)
- **Trakt**: scrobbelen en *bekeken*-vinkjes met het gedeelde token van je account; is er nog geen koppeling, dan koppel je Trakt op de tv met een code/QR-code (trakt.tv/activate). Alleen voor het hoofdprofiel.
- **OpenSubtitles**: zoeken en tonen in de speler (Ondertitels → OpenSubtitles…), met de gedeelde inlog en API-sleutel; standaardtaal in Instellingen; inloggen kan ook op de tv
- **Herinneringen** vanuit de gids (*Herinner mij*), gedeeld met je andere apparaten; als het programma begint verschijnt er een melding in beeld (groen: live kijken). Lijst in Instellingen → Herinneringen.
- **Multiview**: 2 tot 4 zenders tegelijk (Live TV → Multiview). Een Samsung-tv heeft maar één AVPlay-speler: de tegel met geluid gebruikt AVPlay (alle formaten), de andere tegels spelen gedempt via HTML5-video (HLS/MP4, hoeveel tegelijk hangt af van de tv). OK geeft een tegel het geluid, geel haalt hem weg, Afspelen opent hem volledig. Telt als één stream.
- **Intro en aftiteling markeren** in de speler (rode knop of de knop *Intro en aftiteling*): intro begin/eind en begin aftiteling zetten, ±5 s bijstellen, gedeeld als `settings.shared.introMarkers` voor alle afleveringen van de serie
- **Gezipte tv-gidsen** (`.xml.gz`) met het meegeleverde pako (inflate)
- **Leeftijdsclassificaties uit TMDB** (Kijkwijzer eerst, dan VS, VK, …), gecachet in IndexedDB en op de achtergrond bijgewerkt (kindergroepen eerst, in kleine batches, alleen als de afstandsbediening stil is): het kinderfilter werkt daardoor hetzelfde als op Android
- **Tegelijk kijken** (max. 6, met de lijst van wie er kijkt), kijkgeschiedenis, apparaten beheren, wie kijkt er nu (met profielavatars)
- **Kinderslot** en leeftijdsfilter met dezelfde regels als Android (kinderprofiel: alleen kinderzenders en -categorieën; volwassenengroepen nooit; vergrendelde groepen en aanpassingen uit het Android-blok van de server)
- **Instellingen**: taal (11 talen, gedeeld met je account), accentkleur, actieve server, servers, bibliotheek verversen, laatste zender hervatten, zapvertraging, live-voorbeeld, Trakt, OpenSubtitles, herinneringen, kinderslot
- **Knoppen**: de afspeelknop (*Afspelen*, *Hervatten* / *Verder kijken*, *Volgende aflevering*, *Live kijken*, *Kijken vanaf het begin*) is gevuld in de accentkleur van het profiel (standaard rood #E50914) met witte tekst; met focus wordt hij groter met een witte rand en gloed. *Andere bron* en *Kies een bron* hebben een icoon met gestapelde lagen.
- **Taal van elke titel** (zoals UHF): uit de naam (`Animal (IT)`, `NL | `, `|NL|`, `[NL]`, `NL - `, `NL SUBS`, `MULTI`, `DUAL`; `(IN)` = Hindi), anders uit de categorie (`NL Films`, `VOD | DE`), anders de originele taal uit TMDB. Kleine strook onder op de poster (`NL`, `IT`, `EN · NL ondertitels`, `Multi`), de tag verdwijnt uit de getoonde titel, en *Taal: Italiaans* op de detailpagina. **Andere taal** (wereldbol) toont dezelfde titel in andere talen (zelfde TMDB-id of titel + jaar).
- **Audio en ondertitels**: de gewenste audiotaal (`settings.shared.audioLanguage`, standaard de taal van de app, `stream` = zoals de stream) wordt via AVPlay gekozen vóór het afspelen begint. Heeft een stream maar één audiotaal, dan zegt het audiopaneel dat en kun je op hetzelfde moment verder in een andere taalversie. **Automatische ondertitels** (`autoSubtitles`: uit / als het nodig is / altijd, plus `subtitleLanguage`): is de audio niet in jouw taal, dan zet Bingr een ingebouwd spoor of OpenSubtitles op de achtergrond aan, met een melding in beeld.
- **Sneller starten**: na *Afspelen* gaat de stream meteen naar AVPlay (`prepareAsync`, startbuffer ~2 s); tegelijk-kijken (`stream_start`), Trakt, ondertitels en de taalkeuze lopen ernaast. De tijden staan in het log (`start url / prepared / playing`).
- **Soepel op oude tv's**: lijsten en collecties één keer per wijziging berekend en onthouden, zwaar werk in stukjes zodat de toetsen nooit blijven hangen, ingedrukt houden afgeremd, virtuele lijsten en rasters (alleen wat in beeld is bestaat, scrollen met `transform`), kleine posters pas laden als ze in beeld komen, nu/straks via een index met binair zoeken, het Live TV-voorbeeld pas na ~1,2 s stilstand met één hergebruikte speler

Beperkingen die blijven:

- Multiview: alleen de tegel met geluid speelt `.ts`-streams; de andere tegels hebben HLS of MP4 nodig (bij Xtream: zet *Live TV als HLS* aan). Kan een tegel niet spelen, dan zie je nu/straks en kun je hem met OK het geluid geven.
- MAC-portals werken alleen als het portal het MAC-adres ook als parameter accepteert (een tv-app mag geen eigen cookie meesturen); terugkijken bij Stalker hangt af van het portal (archief per programma of `utc`-parameter).
- Downloaden, casten en het wijzigen van profielen, collecties of de startscherm-indeling doe je in de andere apps of op bingr.online; op de tv zie je het resultaat.
- Stremio speelt alleen bronnen met een gewone URL (geen torrents).
- Inloggen bij Jellyfin/Emby gebeurt met gebruikersnaam en wachtwoord; Bingr bewaart alleen de sessie, niet het wachtwoord.

## Installeren op je Samsung-tv

Een app buiten de Samsung-winkel om installeer je met **ontwikkelaarsmodus** en een **eigen Samsung-certificaat** dat aan jouw tv gekoppeld is (de DUID van de tv). Dat certificaat maak je één keer; daarna bouwt GitHub elke versie er automatisch mee.

### 1. Ontwikkelaarsmodus aanzetten op de tv

1. Zorg dat de tv en je computer op hetzelfde netwerk zitten. Zoek het IP-adres van je computer op (macOS: *Systeeminstellingen → Netwerk*, of `ipconfig getifaddr en0` in Terminal).
2. Open op de tv **Apps** (in het startmenu).
3. Toets op de afstandsbediening **1 2 3 4 5** (bij een afstandsbediening zonder cijfers: via de knop met het nummerblok op het scherm).
4. Zet **Developer mode** op **On**, vul bij **Host PC IP** het IP-adres van je computer in en kies **OK**.
5. Zet de tv helemaal uit en weer aan (houd de aan/uit-knop ingedrukt tot hij herstart).
6. Zoek het IP-adres van de tv op: *Instellingen → Algemeen → Netwerk → Netwerkstatus → IP-instellingen*.

### 2. Tizen Studio en het Samsung-certificaat

1. Installeer [Tizen Studio](https://developer.tizen.org/development/tizen-studio/download) (de versie met IDE).
2. Open **Tools → Package Manager → Extension SDK** en installeer **Samsung Certificate Extension** en **TV Extensions** (de nieuwste).
3. Verbind de tv: **Tools → Device Manager → Remote Device Manager → +**, vul het IP-adres van de tv in (poort 26101) en zet de schakelaar aan. De tv verschijnt in de lijst.
4. Open **Tools → Certificate Manager → +** en kies **Samsung → TV**.
   - **Certificate profile name**: `bingr`
   - **Author certificate**: *Create a new author certificate*, naam `Bingr`, en een **wachtwoord** dat je goed bewaart.
   - Log in met je **Samsung-account**.
   - **Distributor certificate**: *Create a new distributor certificate*, privilege **Public**. De **DUID** van de verbonden tv staat er automatisch in (andere tv's kun je hier toevoegen). Kies een wachtwoord (mag hetzelfde zijn).
5. De bestanden staan daarna in je thuismap, bijvoorbeeld `~/SamsungCertificate/bingr/author.p12` en `~/SamsungCertificate/bingr/distributor.p12`. **Maak hier een back-up van**, net als van de Android-keystore: met een ander auteurscertificaat installeert een update niet meer over de oude versie heen.

### 3. De vier GitHub-secrets toevoegen

In de repo op GitHub: **Settings → Secrets and variables → Actions → New repository secret**. Maak deze vier aan:

| Secret | Inhoud |
|---|---|
| `TIZEN_AUTHOR_P12_BASE64` | `author.p12` als base64 |
| `TIZEN_AUTHOR_PASSWORD` | wachtwoord van het auteurscertificaat |
| `TIZEN_DISTRIBUTOR_P12_BASE64` | `distributor.p12` als base64 |
| `TIZEN_DISTRIBUTOR_PASSWORD` | wachtwoord van het distributeurscertificaat |

Base64 maken op macOS (kopieert meteen naar het klembord; plak het daarna in het veld van het secret):

```bash
base64 -i ~/SamsungCertificate/bingr/author.p12 | pbcopy
base64 -i ~/SamsungCertificate/bingr/distributor.p12 | pbcopy
```

Of in één keer met de GitHub-CLI:

```bash
base64 -i ~/SamsungCertificate/bingr/author.p12 | gh secret set TIZEN_AUTHOR_P12_BASE64 -R LRvdLinden/bingr-android
base64 -i ~/SamsungCertificate/bingr/distributor.p12 | gh secret set TIZEN_DISTRIBUTOR_P12_BASE64 -R LRvdLinden/bingr-android
gh secret set TIZEN_AUTHOR_PASSWORD -R LRvdLinden/bingr-android        # vraagt om het wachtwoord
gh secret set TIZEN_DISTRIBUTOR_PASSWORD -R LRvdLinden/bingr-android
```

Zet de `.p12`-bestanden en wachtwoorden **nooit** in de repo: hij is openbaar.

Zonder deze secrets bouwt GitHub toch een `.wgt`, maar dan met een tijdelijk testcertificaat (`…-testsigned.wgt`). Die installeert niet op een Samsung-tv. Na het toevoegen van de secrets heet het bestand gewoon `Bingr-1.0.0-build<B>.wgt`. Start eventueel een nieuwe build via **Actions → Build → Run workflow**.

### 4. De app installeren

Download `Bingr-1.0.0-build<B>.wgt` van de [nieuwste release](https://github.com/LRvdLinden/bingr-android/releases/latest).

**Met Tizen Studio**: open **Device Manager**, klik met de rechtermuisknop op de tv, kies **Install app** en selecteer de `.wgt`.

**Met de opdrachtregel** (de tools staan in `~/tizen-studio/tools` en `~/tizen-studio/tools/ide/bin`):

```bash
~/tizen-studio/tools/sdb connect 192.168.1.20          # IP-adres van de tv
~/tizen-studio/tools/sdb devices                       # toont de naam, bv. QE55Q80BAT of 192.168.1.20:26101
~/tizen-studio/tools/ide/bin/tizen install -n Bingr-1.0.0-build55.wgt -t QE55Q80BAT
~/tizen-studio/tools/ide/bin/tizen run -p BingrTVapp.Bingr -t QE55Q80BAT   # starten (of via Apps op de tv)
```

Bingr staat daarna bij **Apps** op de tv.

## Updates en je gegevens

- Een nieuwe versie installeer je op dezelfde manier **over de oude heen**. Inlog, apparaat-id, laatst gekozen profiel en de caches (localStorage en IndexedDB van de app) blijven bewaard; profielen, servers, Mijn lijst, voortgang, collecties en instellingen komen sowieso uit je account.
- Dat werkt alleen als de nieuwe versie met **hetzelfde auteurscertificaat** ondertekend is, precies zoals bij de Android-keystore. Met een ander certificaat weigert de tv de update; dan moet je de oude app eerst verwijderen en opnieuw inloggen (de tv telt dan als nieuw apparaat).
- De app-id `BingrTVapp.Bingr` en package-id `BingrTVapp` veranderen nooit, anders installeert de tv een tweede, lege app.
- Nieuwe tv, of is het distributeurscertificaat verlopen? Maak in de Certificate Manager een nieuw **distributeurscertificaat** met het **bestaande** auteurscertificaat, voeg de DUID van de tv toe en werk `TIZEN_DISTRIBUTOR_P12_BASE64` en `TIZEN_DISTRIBUTOR_PASSWORD` bij.
- De ontwikkelaarsmodus en het Host PC-IP mogen na de installatie aan blijven; zet je ze uit, dan blijft de app gewoon werken.

## Bediening

| Toets | Wat |
|---|---|
| Pijltjes, OK | navigeren en kiezen |
| Terug | terug; op het menu van Home: nogmaals drukken sluit Bingr |
| Afspelen/Pauze, Stop, ⏩ ⏪ | speler (⏩/⏪ springen 30 s; links/rechts 10 s) |
| CH+ / CH−, omhoog/omlaag | zappen tijdens live TV |
| Cijfers | zendernummer kiezen; pincode invoeren |
| Geel | zender als favoriet in Live TV; tegel weghalen in multiview |
| Blauw / Info in Live TV | tv-gids |
| Rood | intro en aftiteling markeren (tijdens een aflevering) |
| Groen | live kijken bij een herinnering in beeld |
| Info | balk van de speler tonen of verbergen |

## Voor ontwikkelaars

- Gewone HTML/CSS/JavaScript zonder build-stap en zonder frameworks, geschreven voor de oude Chromium van tv's uit 2018 (M56): geen `?.`, `??`, object-spread, `Promise.finally` of CSS grid.
- `js/core.js` (opslag, netwerk, hash, titels, vertalingen, planning in stukjes), `account.js` (Supabase-account), `sync.js` (profielen, servers, state, collecties, intro-markeringen), `providers.js` (M3U, Xtream, Stalker, XMLTV), `mediaservers.js` (Plex, Jellyfin/Emby, Stremio), `library.js` (laden, cachen, filters en kinderslot), `agerating.js` (TMDB-leeftijden), `connections.js` (Trakt, OpenSubtitles), `reminders.js`, `player.js` (AVPlay, voorbeeld), `multiview.js`, `ui.js` (focus, toetsen, virtuele lijsten, schermtoetsenbord), `screens.js`, `extras.js` (servers, Trakt, herinneringen) en `app.js`. Externe bibliotheken in `js/lib/`: `qrcode.js` en `pako_inflate.min.js` (MIT/Zlib).
- Id's en sync-sleutels zijn gelijk aan Android en iOS (bijv. `x123`, `xv456`, `xs789`, `ep:<serie>|s-e`, `profile.<id>`, collecties `version: 2`), zodat alles tussen de apps synchroniseert.
- Lokale opslag: `localStorage` met het voorvoegsel `bingr.` en een schemaversie (`bingr.schema`, migraties in `B.migrate`), grote caches in IndexedDB (`bingr`).
- Teksten komen uit `app/src/main/assets/Translations.json`; `python3 tizen/tools/make_strings.py` maakt `js/strings.js`. Teksten die alleen op de tv bestaan staan in `tools/strings-extra.json`.
- Lokaal testen in Chromium met een neppe backend: `python3 tizen/test/screenshots.py /tmp/bingr-shots` (Playwright).
- Pakket maken: `tizen/tools/package.sh <map>` (ondertekent als `TIZEN_CLI` en `TIZEN_PROFILE` gezet zijn, anders een niet-ondertekende zip-wgt).
