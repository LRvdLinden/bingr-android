# Bingr voor Samsung TV (Tizen)

Dezelfde Bingr als op Android TV en Apple TV, als app voor **Samsung-smart-tv's van 2018 en nieuwer** (Tizen 4.0+), met dezelfde Netflix-look en bediening met de gewone afstandsbediening.
Je logt in met je Bingr-account (QR-code met je telefoon of een code op [bingr.online/koppelen](https://bingr.online/koppelen/)), en daarna staan je profielen, servers, Mijn lijst,
voortgang, collecties en instellingen er gewoon: alles komt uit je account en synchroniseert met je andere apparaten.

Downloaden: [bingr.online](https://bingr.online/#download). Installeren en bijwerken (ontwikkelaarsmodus, Tizen Studio en een Samsung-certificaat): zie de [hulppagina](https://support.bingr.online/hulp/samsung/).

## Wat erin zit

- **Inloggen** met QR-code / code op bingr.online/koppelen (of e-mail met het schermtoetsenbord), sessie vernieuwen, uitloggen, limiet van 15 apparaten met het scherm *Te veel apparaten*
- **Wie kijkt er?** met avatars (emoji, getekende figuurtjes en profielfoto's uit je account), kinderprofielen, pincode per profiel en accentkleur per profiel
- **Home** in Netflix-stijl: billboard, Verder kijken, Mijn lijst, Top 10, Nu live, Onlangs toegevoegd, je eigen **collecties** (per profiel, slim en handmatig) en categorierijen, in de volgorde van je startscherm-indeling
- **Live TV** met categorieën, favorieten, recente zenders, nu/straks uit de tv-gids, een **voorbeeld** van de zender waar je op staat en een **gids-raster** (tot 7 dagen terug)
- **Films** en **Series** met categorieën, detailpagina met seizoenen en afleveringen, voortgang in procenten en automatisch de huidige aflevering
- **Categorieën** (Netflix-stijl): tegels per genre (TMDB) en per categorie van je server, met een collage van posters; per categorie Alles/Films/Series en sorteren op Populair, Nieuwste, A–Z of Beoordeling; te openen via het profielmenu (avatar) en de rij **Categorieën** op Home (vijf rijen onder Nu live, aan/uit in de startscherm-indeling)
- **Zoeken** met schermtoetsenbord, ook naar personen (TMDB), programma's in de tv-gids, categorieën/genres en titels in je andere afspeellijsten (wisselt van bron bij openen)
- **Contextmenu** (OK ingedrukt houden of de Tools-toets): Mijn lijst, aan een collectie toevoegen (of een nieuwe maken), bekeken/niet bekeken, uit Verder kijken halen; bij zenders favoriet, tv-gids, terugkijk-correctie en multiview
- **Zoals op de Apple TV**: rij *Voor jou* (Trakt) en Top 10 uit TMDB/Trakt trending (kinderprofiel: populaire kidstitels), *Bekijk allemaal* met sorteren (ook op Films en Series), billboard met IMDb-score, genre en speelduur, detailpagina met trailer, beoordelingen (IMDb/TMDB/Trakt), voortgang per serie en seizoen, heel seizoen afvinken, *Andere bronnen* en wisselende achtergronden; Live TV met *Evenementen* (PPV, op begintijd, LIVE) en je collecties als groep
- **Speler** met Samsung AVPlay (HLS, TS, MP4), terugval op HTML5-video, hervatten, audio- en ondertitelsporen, zappen met CH+/CH− of omhoog/omlaag (met vertraging) en cijfertoetsen, **intro overslaan**, **volgende aflevering** met aftellen, reservebron bij een live-fout, foutscherm met *Probeer opnieuw* / *Andere bron*
- **Servers beheren op de tv** (Instellingen → Jouw servers, alleen het hoofdprofiel): toevoegen, wijzigen en verwijderen van M3U (+ EPG-URL), Xtream Codes (met test van de inlog en *Toch opslaan*), Stalker/MAC-portal, Plex (koppelcode op plex.tv/link), Jellyfin, Emby en Stremio (add-ons en/of Stremio-account). Hoofdserver kiezen. Wijzigingen gaan meteen naar je account en verschijnen op je andere apparaten. Tekst invoeren met een schermtoetsenbord dat met de pijltjes werkt (lay-outs voor tekst, URL en MAC-adres).
- **Plex, Jellyfin, Emby en Stremio**: bladeren en afspelen (films, series met seizoenen en afleveringen, Stremio met *Kies een bron*), met dezelfde id's als de andere apps, zodat voortgang, Mijn lijst en collecties gewoon meegaan
- **Terugkijken** (catch-up) bij Xtream en Stalker vanuit de gids of het programmavenster (*Kijken vanaf het begin*), met de **terugkijk-correctie per zender** (−120…+120 min, gedeeld via de server)
- **Trakt**: scrobbelen en *bekeken*-vinkjes met het gedeelde token van je account; is er nog geen koppeling, dan koppel je Trakt op de tv met een code/QR-code (trakt.tv/activate). Alleen voor het hoofdprofiel.
- **OpenSubtitles**: zoeken en tonen in de speler (Ondertitels → OpenSubtitles…), met de gedeelde inlog en API-sleutel; standaardtaal in Instellingen; inloggen kan ook op de tv
- **Herinneringen** vanuit de gids (*Herinner mij*), gedeeld met je andere apparaten; als het programma begint verschijnt er een melding in beeld (groen: live kijken). Lijst in Instellingen → Herinneringen.
- **Multiview**: 2 tot 4 zenders tegelijk (Live TV → Multiview). Een Samsung-tv heeft maar één AVPlay-speler: de tegel met geluid gebruikt AVPlay (alle formaten), de andere tegels spelen gedempt via HTML5-video (HLS/MP4, hoeveel tegelijk hangt af van de tv). OK geeft een tegel het geluid, geel haalt hem weg, Afspelen opent hem volledig. Telt als één stream.
- **Intro en aftiteling markeren** in de speler (rode knop of de knop *Intro en aftiteling*): intro begin/eind en begin aftiteling zetten, ±5 s bijstellen, gedeeld met je andere apparaten voor alle afleveringen van de serie
- **Eén lijst over alle servers** (build 128): Verder kijken, Mijn lijst, favorieten, recente zenders en collecties tonen alles van al je servers; een titel of zender van een andere server speelt via die server (ook terugkijken in de gids), zonder te wisselen. Is een server verwijderd, dan zoekt Bingr de titel of zender op je andere servers en verhuist hij mee; vindt hij hem nergens, dan blijft hij staan met bij aanklikken Verwijderen / Zoeken / Annuleren
- **Categorieën aan/uit voor het hele account** (build 140, Instellingen → Profiel), voor de categorieën van al je servers; geldt voor alle profielen behalve kinderprofielen
- **Kinderprofielen** (build 140): alleen inhoud uit kindercategorieën (overal: Home, Live TV, zoeken, lijsten); profiel verlaten kan pas na een rekensom (*Vraag een volwassene*)
- **Gezipte tv-gidsen** (`.xml.gz`) worden gewoon gelezen
- **Leeftijdsclassificaties uit TMDB** (Kijkwijzer eerst, dan VS, VK, …), op de achtergrond bijgewerkt: het kinderfilter werkt daardoor hetzelfde als op Android
- **Tegelijk kijken** (max. 6, met de lijst van wie er kijkt), kijkgeschiedenis, apparaten beheren, wie kijkt er nu (met profielavatars)
- **Kinderslot** en leeftijdsfilter met dezelfde regels als Android (kinderprofiel: alleen kinderzenders en -categorieën; volwassenengroepen nooit; vergrendelde groepen en aanpassingen uit het Android-blok van de server)
- **Instellingen**: taal (11 talen, gedeeld met je account), accentkleur, actieve server, servers, bibliotheek verversen, laatste zender hervatten, zapvertraging, live-voorbeeld, Trakt, OpenSubtitles, herinneringen, kinderslot
- **Knoppen**: de afspeelknop (*Afspelen*, *Hervatten* / *Verder kijken*, *Volgende aflevering*, *Live kijken*, *Kijken vanaf het begin*) is gevuld in de accentkleur van het profiel (standaard rood #E50914) met witte tekst; met focus wordt hij groter met een witte rand en gloed. *Andere bron* en *Kies een bron* hebben een icoon met gestapelde lagen.
- **Taal van elke titel** (zoals UHF): uit de naam (`Animal (IT)`, `NL | `, `|NL|`, `[NL]`, `NL - `, `NL SUBS`, `MULTI`, `DUAL`; `(IN)` = Hindi), anders uit de categorie (`NL Films`, `VOD | DE`), anders de originele taal uit TMDB. Kleine strook onder op de poster (`NL`, `IT`, `EN · NL ondertitels`, `Multi`), de tag verdwijnt uit de getoonde titel, en *Taal: Italiaans* op de detailpagina. **Andere taal** (wereldbol) toont dezelfde titel in andere talen (zelfde TMDB-id of titel + jaar).
- **Audio en ondertitels**: je gewenste audiotaal (standaard de taal van de app) wordt gekozen vóór het afspelen begint. Heeft een stream maar één audiotaal, dan zegt het audiopaneel dat en kun je op hetzelfde moment verder in een andere taalversie. **Automatische ondertitels** (uit / als het nodig is / altijd): is de audio niet in jouw taal, dan zet Bingr een ingebouwd spoor of OpenSubtitles op de achtergrond aan, met een melding in beeld. Kijkvoorkeuren per profiel gaan voor, net als op Android.
- **Sneller starten**: na *Afspelen* begint de stream meteen (startbuffer ~2 s); tegelijk-kijken, Trakt, ondertitels en de taalkeuze lopen ernaast.
- **Soepel op oude tv's**: ook met grote afspeellijsten blijven de toetsen vlot reageren; posters laden pas als ze in beeld komen en het Live TV-voorbeeld start pas als je even stilstaat op een zender.

Beperkingen die blijven:

- Multiview: alleen de tegel met geluid speelt `.ts`-streams; de andere tegels hebben HLS of MP4 nodig (bij Xtream: zet *Live TV als HLS* aan). Kan een tegel niet spelen, dan zie je nu/straks en kun je hem met OK het geluid geven.
- MAC-portals werken alleen als het portal het MAC-adres ook als parameter accepteert (een tv-app mag geen eigen cookie meesturen); terugkijken bij Stalker hangt af van het portal (archief per programma of `utc`-parameter).
- Downloaden, casten en het wijzigen van profielen, slimme collecties of de startscherm-indeling doe je in de andere apps of op bingr.online; op de tv zie je het resultaat. Handmatige collecties maken en vullen kan wel op de tv (contextmenu).
- Stremio speelt alleen bronnen met een gewone URL (geen torrents).
- Inloggen bij Jellyfin/Emby gebeurt met gebruikersnaam en wachtwoord; Bingr bewaart alleen de sessie, niet het wachtwoord.
- Gevoelige gegevens (build 141): de sessie van je Bingr-account, je servers met wachtwoorden, pincodes, Trakt- en OpenSubtitles-tokens en app-sleutels staan niet meer in localStorage maar in IndexedDB, versleuteld (AES-GCM, Web Crypto) met een sleutel die per installatie wordt gemaakt en niet uit te lezen is. Tizen heeft voor web-apps geen keystore: die sleutel staat in dezelfde app-opslag. Dat beschermt tegen uitlezen van localStorage of een gekopieerd opslagbestand, niet tegen iemand met volledige toegang tot het bestandssysteem van de tv. Kent een tv Web Crypto niet, dan staan de waarden onversleuteld in IndexedDB; werkt IndexedDB niet, dan blijft alles in localStorage zoals vroeger (`js/build141.js`).
- Pincode: na 5 foute pogingen op rij een oplopende wachttijd (30 s tot 1 uur), ook na een herstart.

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
