package nl.lrvdlinden.bingr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay

/**
 * Build 145: de vraag "Servers bijwerken?" na de splash en de profielkeuze, met een bewaarde lijst en de instelling
 * "Vragen" (zie [Build145Logic.LoadOnOpen]). "Nu bijwerken": alle servers één voor één op de achtergrond (Home blijft
 * bruikbaar); "Later" (ook Terug): niets ophalen. Op de tv staat de focus eerst op "Later". Niet tijdens het afspelen:
 * dan wacht de vraag tot de speler dicht is.
 */
@Composable
fun UpdateAskHost() {
    if (!Playlists.askUpdate || AppState.playing != null || AppState.stage != AppState.Stage.MAIN) return
    Dialog(onDismissRequest = { Playlists.answerUpdate(false) }) {
        val later = remember { FocusRequester() }
        Column(
            Modifier.widthIn(min = 280.dp, max = 520.dp).clip(RoundedCornerShape(14.dp)).background(Theme.elevated)
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            Text(L("Update servers?"), color = Color.White, fontSize = tsp(18), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(L("Your saved list is ready. Do you want to update your servers now?"), color = Theme.secondary, fontSize = tsp(14))
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NetflixButton(L("Update now"), kind = NetflixKind.PRIMARY) { Playlists.answerUpdate(true) }
                NetflixButton(L("Later"), kind = NetflixKind.SECONDARY, modifier = Modifier.focusRequester(later)) {
                    Playlists.answerUpdate(false)
                }
            }
        }
        // d-pad: standaard op "Later" (zelfde als de Apple TV en Samsung TV); op een telefoon geen zichtbare focus nodig
        LaunchedEffect(Unit) {
            if (!AppState.isTV) return@LaunchedEffect
            repeat(8) {
                delay(60)
                if (runCatching { later.requestFocus() }.isSuccess) return@LaunchedEffect
            }
        }
    }
}
