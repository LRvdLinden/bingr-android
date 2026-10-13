# Changelog · Android en Samsung TV

Alle versies van Bingr voor Android-telefoon en -tablet, Android TV, Google TV, Fire TV en Samsung TV (Tizen). iPhone, iPad, Apple TV en Mac: [iptv-ios-appletv](https://github.com/LRvdLinden/iptv-ios-appletv/blob/main/CHANGELOG.md) · alles: [bingr.online/changelog](https://bingr.online/changelog/).

Per build, ingedeeld per apparaatsoort: **Nieuw**, **Verbeterd** en **Opgelost**.

## Versie 1.0.0 · Build 62
_13 oktober 2026_

### Alle apps, de website en de server

**Nieuw**

- Apparaten: de browser verschijnt als eigen apparaattype (bijv. "Chrome op Mac") met een browser-icoon

### Server

**Nieuw**

- Servers zonder CORS werken ook in de browser via een beveiligde doorgifte, alleen voor https en alleen voor je eigen servers

## Versie 1.0.0 · Build 61
_13 oktober 2026_

### iOS, iPadOS, tvOS, macOS, Android en Android TV

**Verbeterd**

- Apparaten: Naam wijzigen en Verwijderen staan nu naast elkaar onder de tekst, zodat naam en gegevens goed leesbaar zijn

## Versie 1.0.0 · Build 60
_13 oktober 2026_

### Alle apps en de server

**Verbeterd**

- Het maximum van 6 schermen en 15 apparaten klopt nu ook als apparaten precies tegelijk starten of inloggen; kijkgeschiedenis ouder dan 8 dagen wordt altijd gewist

**Opgelost**

- Je wordt niet meer onterecht uitgelogd als je sessie net verlopen is of de server even niet bereikbaar is

### Alle apps, de website en de server

**Opgelost**

- Servers die je op een ander apparaat of op de website toevoegt, verdwijnen niet meer als een ander apparaat tegelijk een oudere lijst opslaat

### Alle apps

**Verbeterd**

- Audiotaal 'Zoals in de stream' en de ondertitelinstellingen werken op alle apps hetzelfde

**Opgelost**

- Wat je uit Verder kijken of je geschiedenis haalt, komt niet meer terug op je andere apparaten

### iOS, iPadOS, tvOS, macOS, Android en Android TV

**Opgelost**

- Plex crasht niet meer bij een onbruikbaar serveradres; Chromecast-sessies sluiten netjes af; OpenSubtitles vindt betere treffers

### Android

**Nieuw**

- Downloads casten naar een Chromecast; downloads van een ander profiel staan ook in je eigen Downloads en verwijderde bestanden verdwijnen uit de lijst

**Opgelost**

- Meldingen-toestemming wordt maar één keer gevraagd en herinneringen blijven werken na een herstart van je telefoon

### Android en Android TV

**Opgelost**

- Profielen, lijsten en voortgang raken niet meer kwijt als de app midden in het opslaan stopt; reservebronnen houden rekening met het kinderprofiel; ontbrekende vertalingen aangevuld

### Samsung TV (Tizen)

**Nieuw**

- Apparaten hernoemen, en bij inloggen met e-mail ook een account maken of je wachtwoord herstellen

**Opgelost**

- De speler wordt netjes vrijgegeven als de app naar de achtergrond gaat en speelt daarna verder op dezelfde plek (ook multiview)
- Geen vastlopende focus meer in lege lijsten en de tv-gids; server opslaan zonder internet geeft een duidelijke melding; minder geheugengebruik

## Versie 1.0.0 · Build 59
_12 oktober 2026_

### Alle apps

**Nieuw**

- Taal op covers: een strookje onderaan de poster toont de taal van de versie (bijv. NL, IT, EN · NL ondertitels, Multi); het taallabel verdwijnt uit de titel en de detailpagina toont de taal
- Andere taal: staat dezelfde film of serie in een andere taal in je lijst, dan kies je die op de detailpagina of tijdens het kijken, en kijk je verder op dezelfde plek
- Ondertitels automatisch (uit, als nodig, altijd) met een voorkeurstaal: is de audio in een andere taal, dan gaan ondertitels in jouw taal vanzelf aan, eerst uit de stream en anders van OpenSubtitles

**Verbeterd**

- Audiotaal: je voorkeurstaal (standaard de taal van de app) wordt automatisch gekozen; wisselen werkt betrouwbaarder en bij een stream met één audiotaal zie je dat meteen
- Sneller starten: kleinere buffer, direct verder op de juiste plek en niets wacht meer op de server, de leeftijdscontrole of ondertitels voordat het beeld begint

## Versie 1.0.0 · Build 58
_12 oktober 2026_

### Alle apps

**Verbeterd**

- De knop Afspelen is nu gevuld in de accentkleur van je profiel (standaard rood) met witte tekst en icoon; op tv's blijft de selectie duidelijk zichtbaar
- De knop Andere bronnen heeft overal een eigen icoon: op de detailpagina, in de speler en bij een afspeelfout

### Samsung TV (Tizen)

**Nieuw**

- Servers toevoegen, wijzigen en verwijderen op de tv (M3U, Xtream, Stalker, Plex, Jellyfin, Emby, Stremio) met een schermtoetsenbord en keuze van de hoofdserver
- Plex, Jellyfin, Emby en Stremio: bladeren en afspelen
- Terugkijken bij Xtream en Stalker, met terugkijk-correctie per zender
- Trakt (scrobbelen en bekeken, koppelen met een code), OpenSubtitles in de speler en herinneringen met een melding in beeld
- Multiview: 2 tot 4 zenders tegelijk
- Intro en aftiteling markeren in de speler, gedeeld met je andere apps

**Verbeterd**

- Ingepakte tv-gidsen (.xml.gz) en leeftijden via TMDB (Kijkwijzer eerst): het kinderfilter werkt nu hetzelfde als op de andere apps
- Veel soepeler scrollen en zappen, ook op oudere tv's; het zendervoorbeeld start pas als je stilstaat

## Versie 1.0.0 · Build 57
_12 oktober 2026_

### Alle apps, de website en de server

**Nieuw**

- Bij Nu aan het kijken en in de kijkgeschiedenis staat de profielfoto van elk profiel voor de regel (alleen voor het hoofdaccount, met alle profielen)

### iOS, iPadOS, macOS en Android

**Nieuw**

- Ondertitels gaan mee naar de Chromecast: gedownloade ondertitels (OpenSubtitles) en ondertitelsporen uit HLS-streams, ook als je tijdens het casten wisselt

## Versie 1.0.0 · Build 56
_12 oktober 2026_

### iOS, iPadOS, tvOS, macOS, Android en Android TV

**Verbeterd**

- Soepeler scrollen: collecties en leeftijdsclassificaties worden op de achtergrond bijgewerkt, alleen als er echt iets veranderd is, zonder dat lijsten haperen of verspringen
- Live TV: het zendervoorbeeld start pas als je even op een zender blijft staan en hapert niet meer bij snel scrollen; de tv-gids reageert sneller

### Android

**Verbeterd**

- Chromecast toont titel, poster en aflevering, gaat verder op de juiste plek en neemt de volgende aflevering of zender automatisch mee

**Opgelost**

- Casten naar Chromecast werkt nu ook voor live-zenders, films en series: Bingr kiest automatisch een formaat dat de Chromecast kan afspelen en probeert bij een fout eerst een ander formaat

## Versie 1.0.0 · Build 55
_12 oktober 2026_

### iOS, iPadOS, tvOS, macOS, Android, Android TV en Website

**Verbeterd**

- Samsung TV verschijnt met eigen icoon in je apparatenlijst, bij Wie kijkt er nu en in de kijkgeschiedenis

### Samsung TV (Tizen)

**Nieuw**

- Bingr voor Samsung TV (Tizen, 2018 en nieuwer): inloggen met de QR-code, profielen met pincode en accentkleur, Home, Live TV met tv-gids, films, series, zoeken en instellingen. Alles synchroniseert met je account en blijft na updates bewaard
- Speler met Samsung AVPlay: verder kijken, audio- en ondertitelsporen, zappen, intro overslaan, volgende aflevering, reservebron bij storing en maximaal 6 schermen tegelijk
- Bij elke release staat naast de APK een .wgt-bestand voor Samsung TV

## Versie 1.0.0 · Build 54
_12 oktober 2026_

### Alle apps

**Nieuw**

- Elk profiel heeft een eigen accentkleur; die wisselt mee als je van profiel wisselt en gaat mee naar al je apparaten

### Alle apps en de website

**Nieuw**

- Collecties per profiel: elk profiel, ook een kinderprofiel, maakt zijn eigen collecties. Bestaande collecties staan bij elk profiel, er verdwijnt niets

### iOS, iPadOS, macOS en Android

**Verbeterd**

- Alleen het hoofdprofiel kan het app-icoon wijzigen; andere profielen kiezen alleen hun accentkleur

## Versie 1.0.0 · Build 53
_12 oktober 2026_

### Alle apps

**Nieuw**

- Kijkvoortgang in procenten: hoeveel van de serie en van elk seizoen je hebt gezien
- Trailer per seizoen: de trailerknop speelt de trailer van het gekozen seizoen

**Verbeterd**

- Een serie opent bij de aflevering waar je bent: de afleveringenlijst scrolt er direct naartoe

**Opgelost**

- Kinderprofielen filteren nu echt op leeftijd, overal in de app: met Kijkwijzer-classificaties via TMDB, en titels zonder leeftijd alleen als ze duidelijk voor kinderen zijn
- Live tv en de tv-gids tonen op een kinderprofiel alleen kinderzenders

## Versie 1.0.0 · Build 52
_12 oktober 2026_

### Alle apps

**Nieuw**

- Intro overslaan: markeer de intro en de aftiteling één keer per serie; daarna krijg je bij elke aflevering een knop Intro overslaan en start bij de aftiteling de volgende aflevering na een aftelling
- Uiterlijk: kies een accentkleur voor de hele app (rood, blauw, groen, paars, oranje of roze)
- Reservebron bij live tv: valt een zender weg, dan schakelt Bingr automatisch over naar dezelfde zender op een andere server; zelf kiezen kan via Andere bron in de speler
- Deinterlacing voor oude SD- en sportzenders: Uit, Automatisch of Aan
- Start bij laatste zender: na het kiezen van je profiel meteen verder met de zender die je het laatst keek (optioneel)

**Verbeterd**

- Slim foutherstel: eerst stil opnieuw proberen, daarna Opnieuw, Andere bron of Andere speler, en je kijkt verder op dezelfde plek

### iOS, iPadOS en Android

**Nieuw**

- Downloads: films en afleveringen downloaden en offline kijken, met een eigen Downloads-overzicht. Het downloaden gaat door op de achtergrond en je ziet de voortgang op het toegangsscherm

### iOS, iPadOS, macOS en Android

**Nieuw**

- Kies een ander app-icoon: donker, licht, blauw of goud (op de Mac het Dock-icoon zolang Bingr open is)

## Versie 1.0.0 · Build 51
_12 oktober 2026_

### Alle apps en de server

**Nieuw**

- Tegelijk kijken: met één account kijk je op maximaal 6 schermen tegelijk; zijn het er meer, dan zie je wie er kijkt
- Apparaten: je account werkt nu op maximaal 15 apparaten, met per apparaat sinds wanneer het is ingelogd
- Wie kijkt er nu (hoofdaccount): Mijn Bingr → Account toont welk apparaat en profiel wat kijkt, en hoe lang al
- Kijkgeschiedenis van 7 dagen (hoofdaccount): per dag wat er op welk apparaat en profiel gekeken is

### Alle apps

**Nieuw**

- Collecties: maak je eigen rijen met naam en afbeelding, handmatig (Aan collectie toevoegen…) of slim met zoekwoorden
- Startscherm per profiel: zet rijen aan of uit en in je eigen volgorde (Mijn Bingr → Startscherm)
- Zappen: veeg in de live-speler omhoog of omlaag naar de volgende of vorige zender, met zenderbalk en instelbare zapvertraging
- Zendervoorbeeld: in Live TV en de tv-gids speelt de geselecteerde zender na een seconde zonder geluid mee
- Terugkijk-correctie per zender: verschuif het begin van terugkijken met −120 tot +120 minuten
- Evenementzenders (PPV, events) in een eigen groep Evenementen, op begintijd met LIVE-label
- Zoeken: resultaten tonen de kleur van hun bron als je meerdere bronnen hebt

**Verbeterd**

- Direct laden: je bewaarde lijst staat meteen in beeld en wordt op de achtergrond bijgewerkt

### Alle apps, de website en de server

**Nieuw**

- Na het wijzigen van je wachtwoord word je op al je apparaten automatisch uitgelogd

### iOS, iPadOS en Android

**Nieuw**

- Extern scherm: op een aangesloten monitor of tv speelt de video schermvullend, de bediening blijft op je telefoon of tablet

### tvOS en Android TV

**Nieuw**

- Beeldfrequentie aanpassen: de tv schakelt naar de beeldfrequentie van de stream (op Apple TV ook HDR/Dolby Vision)

## Versie 1.0.0 · Build 50
_12 oktober 2026_

### Alle apps

**Nieuw**

- Nieuw in Mijn Bingr: Bron controleren — zie precies hoeveel zenders, films en series je server aanbiedt en hoeveel Bingr inleest

**Verbeterd**

- Seriepagina opent standaard op het nieuwste seizoen (of op het seizoen waar je gebleven bent)

## Versie 1.0.0 · Build 49
_12 oktober 2026_

### Alle apps

**Nieuw**

- Tik op een acteur of actrice voor een eigen pagina met foto, bio en alle films en series waarin die speelt — wat in je afspeellijst staat kijk je direct
- Zoeken op naam van een acteur of actrice toont ook personen

**Verbeterd**

- Copyright onderaan Mijn Bingr: © 2026 Bingr

## Versie 1.0.0 · Build 48
_12 oktober 2026_

### Alle apps

**Verbeterd**

- Lukt het ophalen van afleveringen even niet, dan probeert Bingr het de volgende keer opnieuw

**Opgelost**

- Series van Xtream-servers tonen weer alle seizoenen en afleveringen, ook als de server ze in een andere vorm stuurt

## Versie 1.0.0 · Build 47
_12 oktober 2026_

### Alle apps

**Nieuw**

- Onderaan Mijn Bingr: app-icoon, versie- en buildnummer en copyright

## Versie 1.0.0 · Build 45
_12 oktober 2026_

### Alle apps en de server

**Nieuw**

- Gekoppelde apparaten een eigen naam geven of wijzigen, in de app en op bingr.online/account

### tvOS en Android TV

**Verbeterd**

- Geen deelknop meer op de detailpagina op Apple TV, Android TV, Google TV en Fire TV

### Android TV

**Nieuw**

- Google TV wordt als eigen apparaattype herkend

## Versie 1.0.0 · Build 44
_12 oktober 2026_

### Alle apps en de server

**Nieuw**

- Alles gaat mee met je account: Trakt- en OpenSubtitles-login, instellingen, Mijn lijst, Verder kijken, bekeken, kijkgeschiedenis, pincodes en herinneringen — na opnieuw installeren of op een nieuw apparaat staat alles weer klaar

## Versie 1.0.0 · Build 43
_12 oktober 2026_

### Alle apps en de server

**Nieuw**

- Servers en afspeellijsten worden versleuteld bij je Bingr-account bewaard: na updaten, opnieuw installeren of op een nieuw apparaat staat alles na inloggen weer klaar

## Versie 1.0.0 · Build 42
_12 oktober 2026_

### Alle apps

**Opgelost**

- Trakt koppelen werkt weer: na 'Apparaat goedgekeurd' op trakt.tv logt Bingr nu meteen in, ook als de app even op de achtergrond stond

## Versie 1.0.0 · Build 41
_12 oktober 2026_

### Alle apps en de server

**Nieuw**

- Profielen toevoegen, wijzigen en verwijderen synchroniseert met al je apparaten

**Verbeterd**

- Trakt en OpenSubtitles: gewoon inloggen, de sleutels komen veilig van de Bingr-server

### Android en Android TV

**Opgelost**

- Android: vaste ondertekensleutel, zodat je updates over de vorige versie heen installeert

## Versie 1.0.0 · Build 40
_12 oktober 2026_

### Alle apps en de server

**Nieuw**

- Eigen profielfoto's worden via je Bingr-account gedeeld met al je apparaten, ook Android

## Versie 1.0.0 · Build 39
_12 oktober 2026_

### Alle apps en de server

**Nieuw**

- Maximaal 10 apparaten per account
- Apparaten beheren: gekoppeld op, laatst gebruikt en verwijderen (in de app en op bingr.online/account)

### Alle apps

**Nieuw**

- Een verwijderd apparaat logt automatisch uit

## Versie 1.0.0 · Build 38
_12 oktober 2026_

### Alle apps en de server

**Nieuw**

- Bingr-account: inloggen of account maken na de splash, in Netflix-stijl
- Bestaande profielen worden aan je account gekoppeld en gedeeld met al je apparaten

### Alle apps, de website en de server

**Nieuw**

- E-mails in Bingr-stijl (NL/EN), wachtwoord herstellen via bingr.online/reset

### tvOS, Android TV en Website

**Nieuw**

- Apple TV, Android TV en Fire TV: inloggen met een QR-code via bingr.online/tv

## Versie 1.0.0 · Build 37
_11 oktober 2026_

### Android en Android TV

**Nieuw**

- Eerste Android-versie: telefoon, tablet, Android TV en Fire TV
