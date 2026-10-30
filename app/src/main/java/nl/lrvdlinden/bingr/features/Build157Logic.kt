package nl.lrvdlinden.bingr

/**
 * Build 157: pure logica (zonder Android, getest in tools/test/Build157LogicTest.kt) voor de nieuwe verdeling Gratis /
 * Lifetime (docs/ACCOUNT.md in iptv-ios-appletv, "Build 157: Gratis en Lifetime aangepast").
 *
 * - [Flags]: `features.continue_watching`, `features.kids_profiles` en `features.opensubtitles` lezen, precies zoals de
 *   bestaande vlaggen (`stats`, `trakt`, `themes`): staat de vlag er, dan die waarde; ontbreekt hij (oudere server),
 *   dan volgt hij het abonnement (Gratis = dicht, Lifetime = open). Onbekend abonnement = alles open.
 * - [Plans]: de standaardlimieten voor de vergelijking (`plans` in `entitlements`); ontbreekt `plans`, dan Gratis 1/2/1 en
 *   Lifetime 3/15/onbeperkt.
 * - [Continue], [Kids], [Subs]: wat de app toont of mag bij een dichte vlag.
 */
object Build157Logic {
    const val CONTINUE_WATCHING = "continue_watching"
    const val KIDS_PROFILES = "kids_profiles"
    const val OPENSUBTITLES = "opensubtitles"

    object Flags {
        /** [value] = `features.<key>` van de server (null = ontbreekt); [plan] = `plan` ("free" / "lifetime"). */
        fun feature(plan: String?, value: Boolean?): Boolean {
            if (value != null) return value
            val p = plan?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return true
            return p != "free"
        }

        /** Andere schrijfwijzen in `feature_locked` (403) naar de sleutel van build 157; null = geen build-157-vlag. */
        fun key(k: String?): String? = when (k?.trim()?.lowercase()?.replace('-', '_')) {
            CONTINUE_WATCHING, "continue", "resume_row", "continuewatching" -> CONTINUE_WATCHING
            KIDS_PROFILES, "kids", "kids_profile", "kidsprofiles" -> KIDS_PROFILES
            OPENSUBTITLES, "open_subtitles", "subtitles_search" -> OPENSUBTITLES
            else -> null
        }
    }

    object Plans {
        data class Limits(val streams: Int, val devices: Int, /** null = onbeperkt */ val servers: Int?)

        val FREE = Limits(streams = 1, devices = 2, servers = 1)
        val LIFETIME = Limits(streams = 3, devices = 15, servers = null)

        /**
         * Eén abonnement uit `plans.<naam>`: [present] = staat het object er; [hasServers] = staat `servers` erin
         * (null-waarde = onbeperkt). Ontbreekt het object of een getal, dan de standaard.
         */
        fun limits(fallback: Limits, present: Boolean, streams: Int?, devices: Int?, hasServers: Boolean, servers: Int?): Limits {
            if (!present) return fallback
            return Limits(
                streams = streams?.takeIf { it > 0 } ?: fallback.streams,
                devices = devices?.takeIf { it > 0 } ?: fallback.devices,
                servers = if (hasServers) servers?.takeIf { it > 0 } else fallback.servers,
            )
        }
    }

    /** Rij Verder kijken (Home, Mijn Bingr). */
    object Continue {
        enum class Row { HIDDEN, LOCKED, SHOWN }

        /** Gratis: een slotje in plaats van de titels; de voortgang zelf wordt wel bewaard en gesynct. */
        fun row(hasItems: Boolean, allowed: Boolean): Row = when {
            !hasItems -> Row.HIDDEN
            !allowed -> Row.LOCKED
            else -> Row.SHOWN
        }

        /** "Hervatten vanaf …" in de speler en op de detailpagina blijft altijd werken. */
        fun canResume(@Suppress("UNUSED_PARAMETER") allowed: Boolean): Boolean = true

        /** Voortgang bewaren en syncen gebeurt altijd (zodat hij er na het upgraden meteen is). */
        fun saveProgress(@Suppress("UNUSED_PARAMETER") allowed: Boolean): Boolean = true
    }

    /** Kinderprofielen. */
    object Kids {
        /** Een bestaand kinderprofiel blijft altijd gefilterd, ook bij Gratis (nooit ineens zonder beperkingen). */
        fun filtered(storedKids: Boolean, @Suppress("UNUSED_PARAMETER") allowed: Boolean): Boolean = storedKids

        /** Mag de schakelaar Kinderprofiel / de leeftijd van dit profiel veranderd worden? Gratis: nee (alleen lezen). */
        fun editable(allowed: Boolean): Boolean = allowed

        /** Tik op de schakelaar: true = het slotje tonen in plaats van omzetten. */
        fun showsLock(allowed: Boolean): Boolean = !allowed

        /**
         * Wat er bij Opslaan in `isKids` komt: Gratis houdt de opgeslagen waarde (een nieuw profiel wordt geen kinderprofiel,
         * een bestaand kinderprofiel blijft er een); Lifetime de keuze.
         */
        fun savedKids(allowed: Boolean, wanted: Boolean, stored: Boolean): Boolean = if (allowed) wanted else stored

        /** Leeftijdsgrens bij Opslaan: Gratis houdt die van een kinderprofiel ongewijzigd. */
        fun savedAgeLimit(allowed: Boolean, storedKids: Boolean, wanted: Int?, stored: Int?): Int? =
            if (!allowed && storedKids) stored else wanted

        /** Een kinderprofiel verwijderen mag altijd (achter de ouderpoort). */
        fun canDelete(@Suppress("UNUSED_PARAMETER") allowed: Boolean): Boolean = true
    }

    /** OpenSubtitles (zoeken en downloaden); ondertitels uit de stream blijven gratis. */
    object Subs {
        /** OpenSubtitles gebruiken (handmatig en automatisch): alleen met de vlag én een koppeling. */
        fun usable(allowed: Boolean, signedIn: Boolean): Boolean = allowed && signedIn

        /** Een bestaande koppeling wordt bij Gratis nooit gewist (`connections.opensubtitles` blijft bewaard). */
        fun keepConnection(@Suppress("UNUSED_PARAMETER") allowed: Boolean): Boolean = true
    }
}
