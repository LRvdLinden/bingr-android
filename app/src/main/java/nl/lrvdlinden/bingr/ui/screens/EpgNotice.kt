package nl.lrvdlinden.bingr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Build 146: de tv-gids volgt "Servers laden bij openen" (Nu bijwerken, Altijd, of handmatig met Servers vernieuwen) en
 * ververst niet meer vanzelf op tijd. Loopt de gids leeg (geen programma's meer voor nu of de komende 3 uur), dan een
 * kleine melding die niets blokkeert: "De tv-gids is verlopen. Werk je servers bij." met de knop Bijwerken.
 * Niet bij een kinderprofiel (dat kan de servers niet bijwerken).
 */
object EpgCheck {
    /** Steekproef: de eerste zenders met een gids (lezen kan van schijf komen, dus buiten de main thread). */
    suspend fun expired(): Boolean = withContext(Dispatchers.IO) {
        val lib = Playlists.library
        if (lib.channels.isEmpty() || lib.epg.isEmpty()) return@withContext false
        val programs = ArrayList<Pair<Long, Long>>()
        var withGuide = 0
        var scanned = 0
        for (c in lib.channels) {
            if (withGuide >= 30 || scanned++ >= 2000) break
            val list = runCatching { Playlists.epgFor(c) }.getOrDefault(emptyList())
            if (list.isEmpty()) continue
            withGuide++
            list.forEach { programs += it.start to it.end }
        }
        Build146Logic.Epg.expired(System.currentTimeMillis(), programs)
    }
}

@Composable
fun EpgExpiredNotice(modifier: Modifier = Modifier) {
    var expired by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    val lib = Playlists.library
    val kids = Store.activeProfile?.kids == true
    LaunchedEffect(lib, kids) {
        if (kids) { expired = false; return@LaunchedEffect }
        // opnieuw kijken bij een andere bibliotheek (na bijwerken) en elk half uur zolang de app open staat
        while (true) {
            expired = runCatching { EpgCheck.expired() }.getOrDefault(false)
            if (!expired) dismissed = false
            delay(30 * 60_000L)
        }
    }
    if (!expired || dismissed || kids || Playlists.refreshingAll || Playlists.updating) return
    Row(
        modifier.widthIn(max = 640.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.88f))
            .border(0.5.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.EventBusy, null, tint = Theme.red, modifier = Modifier.size(tvs(18)))
        Spacer(Modifier.width(8.dp))
        Text(L("The TV guide has expired. Update your servers."), color = Color.White, fontSize = tsp(13),
            modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(10.dp))
        NetflixButton(L("Update"), kind = NetflixKind.ACCENT) {
            dismissed = true
            AppScope.launch { runCatching { Playlists.refreshAll(spinner = true) } }
        }
        Spacer(Modifier.width(4.dp))
        S2IconButton(Icons.Default.Close, L("Close"), tint = Theme.secondary, size = tvs(16)) { dismissed = true }
    }
}
