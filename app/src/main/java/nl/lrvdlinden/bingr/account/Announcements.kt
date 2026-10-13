package nl.lrvdlinden.bingr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/**
 * Mededelingen van de beheerder (build 62): device-auth `announcements` {lang} → {items:[{id, title, body, created_at}]}.
 * Bij het starten (na de profielkeuze) en bij terugkeer naar de voorgrond (hooguit één keer per uur) ophalen;
 * elke nieuwe mededeling één keer tonen. Gelezen ids staan lokaal (max. 200). Fouten worden stil genegeerd.
 */
object Announcements {
    private const val SEEN_KEY = "announcements.seen"
    private const val MAX_SEEN = 200
    private const val INTERVAL_MS = 60 * 60_000L

    /** Nog te tonen mededelingen (oudste eerst); de eerste staat in beeld. */
    val queue = mutableStateListOf<Account.Announcement>()

    private var lastCheck = 0L
    private var busy = false

    private fun seen(): List<String> =
        Prefs.string(SEEN_KEY)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()

    private fun markSeen(id: String) {
        val list = seen().filter { it != id } + id
        Prefs.put(SEEN_KEY, list.takeLast(MAX_SEEN).joinToString("\n"))
    }

    /** Ophalen als we in het hoofdscherm zitten; de eerste keer per start altijd, daarna max. één keer per uur. */
    fun check() {
        if (busy || !Account.isSignedIn || !Account.configured) return
        if (AppState.stage != AppState.Stage.MAIN) return
        val now = System.currentTimeMillis()
        if (lastCheck != 0L && now - lastCheck < INTERVAL_MS) return
        lastCheck = now
        busy = true
        AppScope.launch {
            try {
                val items = runCatching { Account.announcements(Localizer.language) }.getOrDefault(emptyList())
                val done = seen().toSet()
                val shown = queue.map { it.id }.toSet()
                val fresh = items.filter { it.id !in done && it.id !in shown }
                    .sortedBy { it.createdAt ?: 0L }
                queue.addAll(fresh)
            } finally {
                busy = false
            }
        }
    }

    /** OK (of terug): als gelezen bewaren en de volgende tonen. */
    fun dismiss(a: Account.Announcement) {
        markSeen(a.id)
        queue.remove(a)
    }
}

/** Mededeling in Bingr-stijl: titel, tekst en OK (op tv krijgt OK de focus; terug sluit ook). */
@Composable
fun AnnouncementDialog(a: Account.Announcement) {
    Dialog(onDismissRequest = { Announcements.dismiss(a) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(24.dp).widthIn(min = 280.dp, max = tvs(520)).clip(RoundedCornerShape(14.dp))
                .background(Theme.elevated).padding(horizontal = tvs(24), vertical = tvs(20))
        ) {
            if (a.title.isNotEmpty()) {
                Text(a.title, color = Color.White, fontSize = tsp(20), fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(tvs(10)))
            }
            if (a.body.isNotEmpty()) {
                Column(Modifier.heightIn(max = tvs(360)).verticalScroll(rememberScrollState())) {
                    Text(a.body, color = Color.White.copy(alpha = 0.85f), fontSize = tsp(15))
                }
            }
            Spacer(Modifier.height(tvs(18)))
            val fr = remember(a.id) { FocusRequester() }
            NetflixButton(L("OK"), kind = NetflixKind.ACCENT,
                modifier = Modifier.align(Alignment.End).widthIn(min = tvs(110)).focusRequester(fr)) { Announcements.dismiss(a) }
            LaunchedEffect(a.id) { runCatching { fr.requestFocus() } }
        }
    }
}
