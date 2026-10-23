package nl.lrvdlinden.bingr

/**
 * Build 145: pure logica (zonder Android, getest in tools/test/Build145LogicTest.kt en, voor Samsung TV, in
 * tizen/test/build145_test.js met dezelfde gevallen; de webspeler heeft dezelfde regels in web/js/xs.js).
 *
 * [LoadOnOpen]: de gebruiker kiest zelf wanneer de servers worden bijgewerkt. Het automatisch verversen op tijd (elke dag,
 * week, "elke keer dat de app opent") en het verversen na ±45 s (build 144) zijn weg. Na de splash en de profielkeuze
 * (dus na de juiste pincode) en alleen met een bewaarde lijst:
 * - "Vragen" (standaard): de vraag "Servers bijwerken?" ("Nu bijwerken" / "Later"), één keer per keer openen en niet bij
 *   een kinderprofiel (daar geldt "Later");
 * - "Altijd": meteen bijwerken, zonder vraag (ook bij een kinderprofiel);
 * - "Nooit, alleen handmatig": niets; handmatig met Vernieuwen.
 * Bijwerken = alle servers van het account, één voor één, de open server eerst, op de achtergrond. Zonder bewaarde lijst
 * laadt de open server meteen (zoals altijd). De instelling is per apparaat (niet gesynct).
 */
object Build145Logic {

    object LoadOnOpen {
        /** Sleutel op het apparaat (SharedPreferences; Tizen en web: localStorage `loadOnOpen`). Nooit in de account-sync. */
        const val PREF = "playlist.loadOnOpen"

        enum class Mode(val raw: String) { ASK("ask"), ALWAYS("always"), NEVER("never") }

        /** Wat er na het openen van een profiel gebeurt. */
        enum class Action { ASK, UPDATE_ALL, NOTHING }

        fun parse(raw: String?): Mode? = Mode.entries.firstOrNull { it.raw == raw?.trim() }

        /** De stand op dit apparaat; niets of iets onbekends = Vragen. */
        fun current(stored: String?): Mode = parse(stored) ?: Mode.ASK

        /**
         * Eenmalige omzetting: zonder geldige nieuwe stand wordt het "Vragen", wat de oude vernieuwtermijn ook was
         * (Handmatig, elke start, 6 uur, dagelijks, wekelijks). null = niets te doen.
         */
        fun migrate(stored: String?): Mode? = if (parse(stored) == null) Mode.ASK else null

        /**
         * Wat te doen na het kiezen van een profiel. [hasSavedList] = de open server staat er uit de bewaarde lijst (zonder
         * bewaarde lijst laadt hij zelf, meteen); [kids] = kinderprofiel; [askedThisLaunch] = de vraag kwam al.
         */
        fun decide(mode: Mode, hasSavedList: Boolean, kids: Boolean, askedThisLaunch: Boolean): Action = when {
            mode == Mode.ALWAYS -> Action.UPDATE_ALL
            !hasSavedList -> Action.NOTHING
            mode == Mode.NEVER -> Action.NOTHING
            kids || askedThisLaunch -> Action.NOTHING
            else -> Action.ASK
        }

        /**
         * Volgorde van het bijwerken: de open server eerst, daarna de andere in de volgorde van het account. [skip] = servers
         * die bij deze keer openen al bijgewerkt zijn (kleine letters; bij "Altijd" na een profielwissel niet opnieuw).
         */
        fun order(ids: List<String>, active: String?, skip: Set<String> = emptySet()): List<String> {
            val out = ArrayList<String>()
            val seen = HashSet<String>()
            fun add(id: String) {
                val k = id.lowercase()
                if (k in skip || !seen.add(k)) return
                out += id
            }
            if (active != null) ids.firstOrNull { it.equals(active, ignoreCase = true) }?.let { add(it) }
            ids.forEach { add(it) }
            return out
        }

        /** Mag de volgende server nu (niet tijdens het afspelen, alleen op het hoofdscherm)? Anders wachten. */
        fun mayRun(onMain: Boolean, playing: Boolean): Boolean = onMain && !playing

        /** Hoort dit bijwerken nog bij de open server en het open profiel ([started] = stand bij het begin)? */
        fun stillCurrent(started: Int, now: Int): Boolean = started == now
    }
}
