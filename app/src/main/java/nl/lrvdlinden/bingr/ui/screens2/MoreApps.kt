package nl.lrvdlinden.bingr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp

/** Build 161: portfoliosite van de ontwikkelaar. */
const val DEV_APPS_URL = "https://lrvdlinden.app"

/** Donkerrood van het verloop (Bingr-rood → donkerrood, zoals de profielverlopen in Models.kt). */
private val DevAppsDarkRed = Color(0xFF7A0008)

/** "View" bestaat al als "Bekijken"; deze kaart gebruikt een eigen sleutel ("Bekijk"). Engels heeft geen tabel. */
private fun devAppsViewLabel(): String = L("View (more apps)").let { if (it == "View (more apps)") "View" else it }

/**
 * Build 161: sectie "Ontdekken" met één kaart "Meer apps van de ontwikkelaar", onderaan Instellingen › Algemeen.
 * Alleen telefoon en tablet: de aanroeper toont hem niet op Android TV / Fire TV ([AppState.isTV]).
 * De hele kaart opent https://lrvdlinden.app in de standaardbrowser; voor TalkBack is het een link.
 */
@Composable
fun S2MoreAppsSection() {
    val ctx = LocalContext.current
    val title = L("More apps from the developer")
    val subtitle = L("Apps for iPhone, iPad, Apple TV and Homey")
    val view = devAppsViewLabel()
    val open = { Support.openUrl(ctx, DEV_APPS_URL) }
    var focused by remember { mutableStateOf(false) }
    val a11y = buildAnnotatedString {
        withLink(LinkAnnotation.Url(DEV_APPS_URL)) { append("$title, $subtitle, lrvdlinden.app") }
    }
    S2Section(L("Discover")) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .onFocusChanged { focused = it.isFocused }
                .focusLift(1.02f, onClick = open)
                .clip(RoundedCornerShape(12.dp))
                .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(12.dp)) else Modifier)
                .clearAndSetSemantics {
                    text = a11y
                    onClick(label = view) { open(); true }
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
                    .background(Brush.linearGradient(listOf(Theme.brandRed, DevAppsDarkRed))),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.GridView, null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = tsp(15), fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = Theme.secondary, fontSize = tsp(12), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(view, color = Theme.brandRed, fontSize = tsp(15), fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text("lrvdlinden.app", color = Theme.secondary, fontSize = tsp(11), maxLines = 1)
            }
        }
    }
}
