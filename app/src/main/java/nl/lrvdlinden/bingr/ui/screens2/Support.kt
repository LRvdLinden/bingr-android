package nl.lrvdlinden.bingr

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * Support (build 75): https://support.bingr.online. Telefoon/tablet: in de browser openen.
 * Android TV / Google TV / Fire TV hebben vaak geen browser: dan een QR-code met het adres (zie [SupportDialogHost]).
 */
object Support {
    const val URL = "https://support.bingr.online"

    /** QR-venster zichtbaar (één venster voor de hele app, zodat het menu dat het opent gewoon dicht kan). */
    var showQR by mutableStateOf(false)
    /** Build 146: adres in het QR-venster (support, privacy, voorwaarden). */
    var qrURL by mutableStateOf(URL)
        private set

    fun open(ctx: Context) = openUrl(ctx, URL)

    /** Build 146: een pagina van Bingr openen: telefoon/tablet in de browser, tv (of zonder browser) als QR-code. */
    fun openUrl(ctx: Context, url: String) {
        qrURL = url
        if (AppState.isTV) { showQR = true; return }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // geen browser op dit apparaat → QR-code
        if (runCatching { ctx.startActivity(intent) }.isFailure) showQR = true
    }
}

/** QR-code + adres van de supportpagina; staat één keer in [MainScreen]. */
@Composable
fun SupportDialogHost() {
    if (!Support.showQR) return
    val close = { Support.showQR = false }
    Dialog(onDismissRequest = close) {
        val fr = remember { FocusRequester() }
        Column(
            Modifier.widthIn(min = 280.dp, max = 460.dp).clip(RoundedCornerShape(14.dp)).background(Theme.elevated)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(tvs(14))
        ) {
            val url = Support.qrURL
            val isSupport = url == Support.URL
            Text(if (isSupport) L("Support") else "Bingr", color = Color.White, fontSize = tsp(20), fontWeight = FontWeight.Bold)
            Text(if (isSupport) L("Questions or problems? Scan the QR code with your phone's camera to open the Bingr support page.")
                else L("Scan the QR code with your phone's camera to open this page."),
                color = Theme.secondary, fontSize = tsp(13), textAlign = TextAlign.Center)
            QrCodeImage(url, tvs(180))
            Text(url.removePrefix("https://"), color = Color.White, fontSize = tsp(16), fontWeight = FontWeight.SemiBold)
            NetflixButton(L("Close"), Icons.Default.Close, kind = NetflixKind.PRIMARY,
                modifier = Modifier.fillMaxWidth().focusRequester(fr)) { close() }
        }
        LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    }
}
