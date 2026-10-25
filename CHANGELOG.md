# Changelog · Android en Samsung TV

Alle versies van Bingr voor Android-telefoon en -tablet, Android TV, Google TV, Fire TV en Samsung TV (Tizen). iPhone, iPad, Apple TV en Mac: [iptv-ios-appletv](https://github.com/LRvdLinden/iptv-ios-appletv/blob/main/CHANGELOG.md) · alles: [bingr.online/changelog](https://bingr.online/changelog/).

Per build, ingedeeld per apparaatsoort: **Nieuw**, **Verbeterd** en **Opgelost**.

## Versie 1.0.1 · Build 150
_25 oktober 2026_

### Alle apps en de website

**Verbeterd**

- Categorieën met dezelfde naam worden één categorie, zonder dubbele titels en met de talen als label; aan- en uitzetten geldt voor alle bronnen tegelijk

**Opgelost**

- Taal-, land- en kwaliteitsaanduidingen zoals "| NL |", "MULTI / LINGO", "[EN]" of "4K" verdwijnen overal uit de namen van categorieën, en namen in hoofdletters krijgen nette hoofdletters
- Ontdek categorieën toont Netflix niet meer twee keer en toont de logo's van streamingdiensten weer

### iOS, iPadOS, tvOS, macOS, Android, Android TV, Samsung TV (Tizen) en LG TV (webOS)

**Verbeterd**

- Bij Over staat nu ook de bronvermelding Streamingdata: JustWatch

### Samsung TV (Tizen) en LG TV (webOS)

**Nieuw**

- Zoeken vindt ook titels in verborgen categorieën en via de zoekcatalogi van Stremio-add-ons

## Versie 1.0.1 · Build 149
_25 oktober 2026_

### Alle apps en de website

**Nieuw**

- Op Home een rij Ontdek categorieën met posters, een gekleurde balk en het logo van de streamingdienst

**Verbeterd**

- Zoeken vindt meer: losse woorden in elke volgorde, zonder accenten en voorvoegsels en met een kleine typefout

**Opgelost**

- Geen haperingen meer bij sync toepassen en bewaren: datums worden niet meer per item opnieuw opgemaakt

### iOS, iPadOS, tvOS, Android, Android TV, Samsung TV (Tizen) en LG TV (webOS)

**Opgelost**

- Een heel grote server stopt netjes met een melding in plaats van de app te laten wegvallen

### iOS, iPadOS, tvOS, macOS, Android, Android TV, Windows en Website

**Verbeterd**

- Zoeken doorzoekt ook de catalogi van Stremio-add-ons

### iOS, iPadOS, tvOS, macOS, Android, Android TV, Samsung TV (Tizen) en LG TV (webOS)

**Opgelost**

- Diagnose: de bereikbaarheid van je eigen server en de fase toepassen worden goed gemeten

### Android, Android TV, Samsung TV (Tizen), Windows, LG TV (webOS) en Website

**Verbeterd**

- Grote servers laden sneller en met veel minder geheugen; verborgen categorieën worden niet meer ingelezen

## Versie 1.0.1 · Build 148
_25 oktober 2026_

### Alle apps en de website

**Verbeterd**

- Een heel grote catalogus wordt in kleine stukjes ingelezen en toegepast, zodat de app blijft reageren en minder geheugen gebruikt
- Diagnose in de taal van de app, met de langste hapering, het bijwerken per server, geleverd naast zichtbaar en haperingen tijdens het afspelen

### iOS, iPadOS, tvOS, macOS, Android, Android TV, Samsung TV (Tizen) en LG TV (webOS)

**Opgelost**

- Live tv hapert niet meer kort na een paar seconden afspelen: het audio- of ondertitelspoor wordt niet meer opnieuw gekozen als dat niet nodig is

### Server

**Verbeterd**

- Een diagnose bij een supportvraag laat nu zien waar de app bij het starten en bijwerken op wacht, hoeveel van je catalogus verborgen is en of het afspelen haperde
- Mails van Bingr: de afbeelding staat niet meer in een donker kader en een afbeelding die niet laadt geeft geen groot leeg vlak meer

## Versie 1.0.1 · Build 147
_24 oktober 2026_

### Alle apps en de website

**Verbeterd**

- De startdiagnose toont bovenaan of de start goed, traag of vastgelopen was, met app-tijd los van wachten op jou en de vorige start
- Een diagnose naar support bevat altijd de startdiagnose van deze en de vorige start

### iOS, iPadOS, tvOS, macOS, Android, Android TV, Samsung TV (Tizen), Windows en Website

**Nieuw**

- Een LG-tv staat in je apparatenlijst en bij Wie kijkt er nu als LG TV

**Verbeterd**

- Bij vastlopen legt de diagnose vast welke taak het was en of de app op de voorgrond stond
- De diagnose toont per server of zenders, films en series geleverd zijn, leeg waren, mislukten of allemaal verborgen zijn, plus de soort verbinding en de opslag als één totaal

**Opgelost**

- Na het starten loopt Bingr niet meer vast bij een grote bibliotheek: zwaar werk zoals sorteren, filteren, Mijn lijst, de tv-gids, opslaan en pincodes gebeurt op de achtergrond of in kleine stukjes

### Website en Server

**Verbeterd**

- Statuspagina: een mislukte build geeft niet meer meteen een grote storing; alleen de echte build telt, en pas na twee mislukte builds op rij (of na 2 uur) is het een storing

### Server

**Nieuw**

- De server kent de LG TV-app (webOS)

**Verbeterd**

- Support: de diagnose uit de app is uitgebreider en adressen en servernamen worden er beter uit gehaald

## Versie 1.0.1 · Build 146
_24 oktober 2026_

### Alle apps en de website

**Nieuw**

- Support in de app (Instellingen → Support): je tickets in Open, In behandeling en Gesloten bekijken, reageren, sluiten en een nieuw ticket maken, met een badge bij een nieuw antwoord
- Diagnose in Instellingen: startdiagnose en app-diagnose met je apparaat, systeem, netwerk, opslag en geheugen, zonder persoonsgegevens met één knop naar support te sturen
- Servers vernieuwen in het profielmenu, boven Wissel profiel

**Verbeterd**

- Instellingen opnieuw ingedeeld in tabs: Algemeen, Afspelen, Taal en ondertitels, Servers, Profielen en kinderslot, Account, Koppelingen, Support, Diagnose en Over
- De tv-gids wordt samen met je servers bijgewerkt; is hij verlopen, dan zie je een melding met Bijwerken
- Windows-apparaten heten Windows, met een eigen icoon, in de apparatenlijst en bij Wie kijkt er nu

### Alle apps, de website en de server

**Verbeterd**

- Pincodes worden alleen nog als beveiligde hash bewaard en gesynchroniseerd en zijn niet meer terug te lezen
- Profielen synchroniseren versleuteld via de Bingr-server

### iOS, iPadOS, macOS, Android, Windows en Website

**Nieuw**

- Pushmelding bij een antwoord van support; tikken opent het ticket

### Server

**Verbeterd**

- Kijkgeschiedenis, statistieken en supportberichten staan versleuteld opgeslagen

**Opgelost**

- Profielfoto's zijn beter afgeschermd per account

## Versie 1.0.1 · Build 145
_24 oktober 2026_

### Alle apps en de website

**Nieuw**

- Na het kiezen van een profiel vraagt Bingr of je je servers nu wilt bijwerken (Nu bijwerken of Later); je bewaarde lijst staat meteen klaar
- Nieuwe instelling Servers laden bij openen: Vragen, Altijd of Nooit, alleen handmatig (per apparaat)

**Verbeterd**

- Geen automatisch verversen op tijd meer: de vernieuwtermijn (elke dag, week enz.) is vervallen; je kiest zelf wanneer, of vernieuwt handmatig
- Bijwerken gaat één server tegelijk op de achtergrond, de open server eerst, en nooit tijdens het afspelen

### tvOS, Android TV en Samsung TV (Tizen)

**Verbeterd**

- Op de tv staat de focus bij de vraag standaard op Later

### Android en Android TV

**Verbeterd**

- Je gegevens komen alleen nog terug via je Bingr-account, niet meer via de Google-back-up

## Versie 1.0.1 · Build 144
_23 oktober 2026_

### Android en Android TV

**Verbeterd**

- Profiel kiezen en het pincodescherm laden niets meer op de achtergrond

### Android, Android TV en Samsung TV (Tizen)

**Verbeterd**

- Openen gaat sneller: met een bewaarde lijst wordt bij het openen niets opgehaald, verversen gebeurt pas later en één server tegelijk
- Verversen gebeurt nooit tijdens het kijken, in de profielkeuze of op het pincodescherm

**Opgelost**

- Verder kijken toont de echte poster van de film of serie, ook bij eerder bekeken titels en titels van je andere apparaten

## Versie 1.0.1 · Build 142
_23 oktober 2026_

### Alle apps en de website

**Verbeterd**

- Nieuw op Bingr staat op de plek van Nieuw toegevoegd, met Film of Serie op elke tegel
- Verder kijken toont de cover van de film of serie

**Opgelost**

- Titels die je na zoeken aan Mijn lijst toevoegt (ook van een andere server of een aanbeveling) staan er meteen in, ook op je andere apparaten

### Alle apps, de website en de server

**Verbeterd**

- Inloggen is beter beschermd tegen raden, met een duidelijke melding bij te veel pogingen
- Een nieuw wachtwoord moet minstens 8 tekens hebben

### iOS, iPadOS, macOS en Android

**Verbeterd**

- Tv koppelen toont eerst welk apparaat het is en vraagt om bevestiging

### iOS, iPadOS, tvOS, macOS, Android en Android TV

**Verbeterd**

- Bij het openen ververst eerst alleen de open server; je andere servers volgen later op de achtergrond
- Casten naar een Chromecast is beter afgeschermd: alleen wat je cast is bereikbaar, met een nieuwe sleutel per sessie

### iOS, iPadOS, tvOS, macOS, Android, Android TV en Samsung TV (Tizen)

**Verbeterd**

- Kinderslot uitzetten, de pincode wijzigen en een vergrendelde groep ontgrendelen vragen nu de huidige pincode
- Na 5 foute pincodes geldt een oplopende wachttijd
- Inloggegevens van je servers en je accountsessie worden versleuteld bewaard (sleutelhanger op Apple)
- Verbindingen met Bingr en de eigen diensten gaan altijd via HTTPS

### Android

**Verbeterd**

- Downloads op Android 14 en nieuwer lopen als door jou gestarte overdracht, zonder vaste melding op de achtergrond

### Android en Android TV

**Verbeterd**

- Een export van je instellingen vraagt eerst je pincode en het tijdelijke bestand wordt na het delen verwijderd

**Opgelost**

- Een profiel kiezen gaat snel en zonder vastlopen, ook met grote servers op een Android TV

### Website en Server

**Verbeterd**

- Een supportticket koppel je aan je account en is alleen voor jou zichtbaar

### Server

**Verbeterd**

- De webproxy werkt alleen nog voor je eigen servers, met limieten per account

## Versie 1.0.1 · Build 140
_23 oktober 2026_

### iOS, iPadOS, tvOS, macOS, Android, Android TV, Samsung TV (Tizen) en Website

**Nieuw**

- Kinderprofielen tonen alleen kinderinhoud, afgestemd op de leeftijdsgrens; een kinderprofiel verlaten kan pas na een rekensom (Vraag een volwassene)
- Categorieën staat in het menu naast Series; Mijn Bingr open je via je profiel

**Verbeterd**

- Verborgen categorieën gelden nu voor je hele account (alle profielen behalve kinderprofielen)
- Categorieën aan- en uitzetten in Instellingen gaat veel sneller
- Voor jou is gebaseerd op wat dit profiel kijkt; zonder kijkgeschiedenis heet de rij Populair
- Nieuw op Bingr toont wat het laatst aan je bibliotheek is toegevoegd, over al je servers
- Uitgelicht op Home toont een vaste selectie die vanzelf om de 8 seconden wisselt

**Opgelost**

- Servers automatisch vernieuwen volgt weer je instelling en werkt al je servers bij, ook als de app lang openstaat
- Wat je aan Mijn lijst toevoegt, blijft er nu altijd in staan, ook op je andere apparaten

### Server

**Opgelost**

- Mails aan support@bingr.online worden weer altijd een ticket, ook als ze al gelezen waren; reacties komen in het bestaande ticket en niets wordt dubbel geïmporteerd

## Versie 1.0.0 · Build 138
_19 oktober 2026_

### Alle apps en de website

**Verbeterd**

- Voorbereiding op de eerste release (versie 1.0.0)

### Android en Android TV

**Verbeterd**

- Klaar voor Google Play: de app richt zich nu op Android 16 en wordt ook als App Bundle gebouwd

### Server

**Opgelost**

- Betere bescherming tegen nepaccounts: aanmeldingen van bots via de website worden herkend en geweigerd
- E-mails van Bingr tonen weer een afzendernaam (Bingr of Bingr Support) in plaats van alleen het adres
