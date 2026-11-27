package nl.lrvdlinden.bingr

/**
 * Build 189: pure logica zonder Android, getest in tools/test/Build189LogicTest.kt.
 *
 * - [PinBoxes]: wat elk van de vier pincodevakjes toont: leeg, het net ingetypte cijfer (kort zichtbaar) of een punt.
 * - [Tracks]: audio- en ondertitelsporen met dezelfde naam krijgen een volgnummer, zodat je ze kunt onderscheiden.
 */
object Build189Logic {

    object PinBoxes {
        const val LENGTH = 4
        /** Zo lang (ms) blijft het laatste cijfer zichtbaar voordat het een punt wordt. */
        const val REVEAL_MS = 700L
        const val DOT = "•"

        /** Tekst van vakje [index] (0–3) bij de ingevoerde [pin]; [revealIndex] = vakje dat nu nog zichtbaar is (-1 = geen). */
        fun cell(pin: String, index: Int, revealIndex: Int): String = when {
            index < 0 || index >= LENGTH || index >= pin.length -> ""
            index == revealIndex -> pin[index].toString()
            else -> DOT
        }

        /** Welk vakje staat aan de beurt (de rand licht op): het eerstvolgende lege, of geen als de code vol is. */
        fun activeIndex(pin: String): Int = if (pin.length >= LENGTH) -1 else pin.length
    }

    object Tracks {
        /**
         * Komt dezelfde naam meer dan één keer voor, dan krijgen alle gelijke namen " (1)", " (2)", … in de volgorde van
         * de lijst. Unieke namen blijven zoals ze zijn. De volgorde en het aantal blijven gelijk.
         */
        fun numberDuplicates(labels: List<String>): List<String> {
            val total = labels.groupingBy { it }.eachCount()
            val seen = HashMap<String, Int>()
            return labels.map { l ->
                if ((total[l] ?: 0) < 2) l
                else { val n = (seen[l] ?: 0) + 1; seen[l] = n; "$l ($n)" }
            }
        }
    }
}
