package nl.lrvdlinden.bingr

// Build 157: Gratis en Lifetime aangepast (docs/ACCOUNT.md in LRvdLinden/iptv-ios-appletv): de vlaggen
// features.continue_watching / kids_profiles / opensubtitles, de vergelijking (plans, Gratis 2 apparaten) en wat de app
// bij Gratis toont. Draaien zonder Android:
//   kotlinc app/src/main/java/nl/lrvdlinden/bingr/features/Build157Logic.kt tools/test/Build157LogicTest.kt -include-runtime -d /tmp/b157.jar
//   java -jar /tmp/b157.jar

private var failures = 0
private var checks = 0
private fun eq(name: String, got: Any?, want: Any?) {
    checks++
    if (got != want) { failures++; println("FOUT $name kreeg <$got>, verwacht <$want>"); println("::error title=Build157LogicTest::$name") }
}
private fun ok(name: String, v: Boolean) = eq(name, v, true)

fun main() {
    val F = Build157Logic.Flags
    val P = Build157Logic.Plans
    val C = Build157Logic.Continue
    val K = Build157Logic.Kids
    val S = Build157Logic.Subs
    val keys = listOf(Build157Logic.CONTINUE_WATCHING, Build157Logic.KIDS_PROFILES, Build157Logic.OPENSUBTITLES)

    // 1. Gratis: de server geeft de vlaggen false → dicht
    for (k in keys) eq("gratis: $k dicht", F.feature("free", false), false)
    // 2. Lifetime: de server geeft de vlaggen true → open
    for (k in keys) eq("lifetime: $k open", F.feature("lifetime", true), true)
    // 3. oude server zonder de nieuwe vlaggen: volgt het abonnement (Gratis = dicht, Lifetime = open), zoals stats/trakt/themes
    eq("oude server, gratis, geen vlag = Gratis (dicht)", F.feature("free", null), false)
    eq("oude server, Free (hoofdletters), geen vlag = dicht", F.feature(" Free ", null), false)
    eq("oude server, lifetime, geen vlag = open", F.feature("lifetime", null), true)
    // 4. onbekend abonnement (nog nooit opgehaald / netwerkfout zonder cache) = alles open: nooit iets dicht door een fout
    eq("geen abonnement = open", F.feature(null, null), true)
    eq("leeg abonnement = open", F.feature("", null), true)
    // 5. de vlag gaat voor het abonnement (bijv. het beheer zet een functie open bij Gratis)
    eq("gratis met vlag true = open", F.feature("free", true), true)
    eq("lifetime met vlag false = dicht", F.feature("lifetime", false), false)

    // 6. feature_locked-sleutels
    eq("sleutel continue_watching", F.key("continue_watching"), Build157Logic.CONTINUE_WATCHING)
    eq("sleutel continue-watching", F.key("Continue-Watching"), Build157Logic.CONTINUE_WATCHING)
    eq("sleutel kids_profiles", F.key("kids_profiles"), Build157Logic.KIDS_PROFILES)
    eq("sleutel kids", F.key("kids"), Build157Logic.KIDS_PROFILES)
    eq("sleutel opensubtitles", F.key("opensubtitles"), Build157Logic.OPENSUBTITLES)
    eq("sleutel open_subtitles", F.key("open_subtitles"), Build157Logic.OPENSUBTITLES)
    eq("sleutel stats is geen build-157-vlag", F.key("stats"), null)
    eq("sleutel null", F.key(null), null)

    // 7. vergelijking: standaard (zonder plans) Gratis 1/2/1, Lifetime 3/15/onbeperkt
    eq("standaard gratis", P.FREE, Build157Logic.Plans.Limits(1, 2, 1))
    eq("standaard lifetime", P.LIFETIME, Build157Logic.Plans.Limits(3, 15, null))
    eq("geen plans: gratis", P.limits(P.FREE, present = false, streams = null, devices = null, hasServers = false, servers = null), Build157Logic.Plans.Limits(1, 2, 1))
    eq("geen plans: lifetime", P.limits(P.LIFETIME, false, null, null, false, null), Build157Logic.Plans.Limits(3, 15, null))
    // plans van de server (build 157: Gratis devices 2)
    eq("plans gratis 1/2/1", P.limits(P.FREE, true, 1, 2, true, 1), Build157Logic.Plans.Limits(1, 2, 1))
    eq("plans lifetime servers null = onbeperkt", P.limits(P.LIFETIME, true, 3, 15, true, null), Build157Logic.Plans.Limits(3, 15, null))
    // oudere server met plans maar nog 8 apparaten: dan toont de app wat de server zegt
    eq("plans oude server gratis 8 apparaten", P.limits(P.FREE, true, 1, 8, true, 1), Build157Logic.Plans.Limits(1, 8, 1))
    // ontbrekende of ongeldige getallen → standaard
    eq("plans zonder devices", P.limits(P.FREE, true, 1, null, true, 1), Build157Logic.Plans.Limits(1, 2, 1))
    eq("plans zonder servers-sleutel", P.limits(P.FREE, true, 1, 2, false, null), Build157Logic.Plans.Limits(1, 2, 1))
    eq("plans devices 0 ongeldig", P.limits(P.FREE, true, 1, 0, true, 1), Build157Logic.Plans.Limits(1, 2, 1))

    // 8. Verder kijken: Gratis = slotje (alleen als er iets is), Lifetime = de rij; voortgang en hervatten altijd
    eq("verder kijken leeg", C.row(hasItems = false, allowed = false), Build157Logic.Continue.Row.HIDDEN)
    eq("verder kijken leeg lifetime", C.row(false, true), Build157Logic.Continue.Row.HIDDEN)
    eq("verder kijken gratis = slotje", C.row(true, false), Build157Logic.Continue.Row.LOCKED)
    eq("verder kijken lifetime = rij", C.row(true, true), Build157Logic.Continue.Row.SHOWN)
    ok("gratis: hervatten vanaf … blijft werken", C.canResume(false))
    ok("gratis: voortgang wordt bewaard en gesynct", C.saveProgress(false))

    // 9. Kinderprofielen
    ok("gratis: bestaand kinderprofiel blijft gefilterd", K.filtered(storedKids = true, allowed = false))
    ok("lifetime: kinderprofiel gefilterd", K.filtered(true, true))
    eq("gewoon profiel niet gefilterd", K.filtered(false, false), false)
    ok("gratis: schakelaar toont slotje", K.showsLock(false))
    eq("lifetime: geen slotje", K.showsLock(true), false)
    eq("gratis: instellingen alleen lezen", K.editable(false), false)
    ok("lifetime: instellingen te wijzigen", K.editable(true))
    // opslaan
    eq("gratis: nieuw profiel wordt geen kinderprofiel", K.savedKids(allowed = false, wanted = true, stored = false), false)
    eq("gratis: bestaand kinderprofiel blijft kinderprofiel", K.savedKids(false, wanted = false, stored = true), true)
    eq("lifetime: kinderprofiel aan", K.savedKids(true, true, false), true)
    eq("lifetime: kinderprofiel uit", K.savedKids(true, false, true), false)
    eq("gratis: leeftijd kinderprofiel ongewijzigd", K.savedAgeLimit(false, storedKids = true, wanted = null, stored = 12), 12)
    eq("gratis: leeftijd kinderprofiel niet verhoogd", K.savedAgeLimit(false, true, 18, 9), 9)
    eq("gratis: leeftijd gewoon profiel te wijzigen", K.savedAgeLimit(false, false, 16, null), 16)
    eq("lifetime: leeftijd kinderprofiel te wijzigen", K.savedAgeLimit(true, true, 7, 12), 7)
    ok("gratis: kinderprofiel verwijderen mag", K.canDelete(false))

    // 10. OpenSubtitles
    eq("gratis + gekoppeld: niet gebruiken", S.usable(allowed = false, signedIn = true), false)
    eq("lifetime zonder koppeling", S.usable(true, false), false)
    ok("lifetime + gekoppeld", S.usable(true, true))
    ok("gratis: koppeling blijft bewaard", S.keepConnection(false))

    // 11. samen: oude server zonder vlag bij Gratis → alles van build 157 dicht, kinderprofiel blijft gefilterd
    val oldFree = F.feature("free", null)
    eq("oude server gratis: verder kijken slotje", C.row(true, oldFree), Build157Logic.Continue.Row.LOCKED)
    ok("oude server gratis: kinderprofiel gefilterd", K.filtered(true, oldFree))
    eq("oude server gratis: OpenSubtitles niet", S.usable(oldFree, true), false)

    println(if (failures == 0) "Build157LogicTest: $checks controles geslaagd" else "Build157LogicTest: $failures van $checks controles mislukt")
    if (failures > 0) kotlin.system.exitProcess(1)
}
