package nl.lrvdlinden.bingr

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Netflix-kleuren en maten (zelfde als iOS Theme). */
object Theme {
    /** Accentkleur van de app (build 52: instelbaar, zie [Accent]; standaard Netflix-rood) */
    val red: Color get() = Accent.color
    /** Tekst/iconen óp een vlak in de accentkleur: wit, bij goud donker (wit op goud is onleesbaar) */
    val onAccent: Color get() = Accent.onColor
    val bg = Color.Black
    val card = Color(0xFF181818)
    val elevated = Color(0xFF232323)
    val secondary = Color(0xFFB3B3B3)
    val imdb = Color(0xFFF5C518)
    /** Bingr-rood, vast (ook bij een andere accentkleur): o.a. het Lifetime-icoon (build 104) */
    val brandRed = Color(0xFFE50914)
    val green = Color(0xFF46D369)

    /** Op TV alles groter (zoals tvScaled op iOS) */
    val tv: Boolean get() = AppState.isTV
    val posterWidth: Dp get() = if (tv) 150.dp else 112.dp
    val edge: Dp get() = if (tv) 48.dp else 16.dp
    val rowSpacing: Dp get() = if (tv) 18.dp else 8.dp
    val titleSize: TextUnit get() = if (tv) 24.sp else 19.sp
}

/** Schaal voor TV (≈ tvScaled op iOS, maar Android-TV-dp's zijn al groot). */
fun tvs(v: Float): Float = if (AppState.isTV) v * 1.35f else v
fun tvs(v: Int): Dp = (if (AppState.isTV) v * 1.35f else v.toFloat()).dp
fun tsp(v: Int): TextUnit = (if (AppState.isTV) v * 1.35f else v.toFloat()).sp

@Composable
fun BingrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Theme.red, onPrimary = Theme.onAccent, background = Color.Black, surface = Color(0xFF141414),
            onSurface = Color.White, onBackground = Color.White, secondary = Theme.secondary, surfaceVariant = Theme.elevated,
        ),
        content = content
    )
}

/**
 * Klikbaar + focusbaar (afstandsbediening) zonder grijze vlakken: bij focus iets groter met schaduw.
 * Gebruik dit voor posters, kaarten en knoppen overal in de app.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.focusLift(
    scale: Float = 1.08f,
    dimWhenIdle: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    // build 97: TV: na het sluiten van een scherm (detail, categorie) komt de focus terug op dit element (FocusRestore)
    val restore = remember { FocusRequester() }
    val s by animateFloatAsState(if (focused) scale else 1f, spring(dampingRatio = 0.78f, stiffness = 500f), label = "lift")
    val alpha = if (dimWhenIdle && !focused && AppState.isTV) 0.75f else 1f
    this
        .focusRequester(restore)
        .onFocusChanged { focused = it.isFocused; FocusRestore.focusChanged(restore, it.isFocused) }
        .graphicsLayer {
            scaleX = s; scaleY = s; this.alpha = alpha
            shadowElevation = if (focused) 28f else 0f
            shape = RoundedCornerShape(6.dp); clip = false
        }
        .combinedClickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
            onLongClick = onLongClick, onClick = onClick
        )
}

/** Lokaal: is het element waar dit in staat gefocust (voor witte knoppen op TV). */
@Composable
fun rememberFocus(): MutableState<Boolean> = remember { mutableStateOf(false) }

/**
 * PLAY (build 58): de primaire afspeelknop, overal gevuld in de accentkleur van het profiel met wit icoon en
 * witte tekst. Op TV wordt hij bij focus nooit wit, maar groter met een witte rand en gloed ([playFocusRing]).
 */
enum class NetflixKind { PRIMARY, SECONDARY, ACCENT, PLAY }

/** Vaste iconen die op meerdere plekken hetzelfde moeten zijn (build 58). */
object BingrIcons {
    /** "Andere bronnen" / "Andere bron": detailpagina, spelerbalk, bronnenpaneel en foutmelding. */
    val otherSources: androidx.compose.ui.graphics.vector.ImageVector get() = androidx.compose.material.icons.Icons.Rounded.SwapHoriz
}

/**
 * Focusstijl van de afspeelknop op TV (build 58): bij focus een witte rand en een witte gloed; de vulling blijft
 * de accentkleur. Zonder focus (of op telefoon) een onzichtbare rand van dezelfde dikte, zodat niets verspringt.
 */
fun Modifier.playFocusRing(focused: Boolean, shape: androidx.compose.ui.graphics.Shape): Modifier =
    if (focused) this
        .shadow(18.dp, shape, clip = false, ambientColor = Color.White, spotColor = Color.White)
        .border(2.5.dp, Color.White, shape)
    else this.border(2.5.dp, Color.Transparent, shape)

/**
 * Afspelen / Meer info: op TV wordt de geselecteerde knop wit (zoals Netflix), behalve de afspeelknop
 * ([NetflixKind.PLAY]): die blijft in de accentkleur en krijgt bij focus een witte rand en gloed.
 */
@Composable
fun NetflixButton(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    kind: NetflixKind = NetflixKind.PRIMARY,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val play = kind == NetflixKind.PLAY
    val shape = RoundedCornerShape(6.dp)
    val light = !play && (if (AppState.isTV) kind != NetflixKind.ACCENT && focused else kind == NetflixKind.PRIMARY)
    val bg = when {
        kind == NetflixKind.ACCENT || play -> Theme.red
        light -> Color.White
        else -> Color.White.copy(alpha = 0.22f)
    }
    val fg = when {
        kind == NetflixKind.ACCENT || play -> Theme.onAccent
        light -> Color.Black
        else -> Color.White
    }
    Row(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .focusLift(if (play) 1.1f else 1.06f, onClick = onClick)
            .then(if (play) Modifier.playFocusRing(AppState.isTV && focused, shape) else Modifier)
            .clip(shape)
            .background(bg)
            .padding(horizontal = tvs(20), vertical = tvs(10)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) { Icon(icon, null, tint = fg, modifier = Modifier.size(tvs(18))); Spacer(Modifier.width(8.dp)) }
        Text(title, color = fg, fontWeight = FontWeight.Bold, fontSize = tsp(15), maxLines = 1)
    }
}

/** Afbeelding van internet (Coil), met tekst als placeholder. */
@Composable
fun RemoteImage(
    url: String?,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    fit: Boolean = false,
    /** build 97: TalkBack-label (bijv. de titel van een poster); null = decoratief */
    contentDescription: String? = null,
) {
    val label = contentDescription?.takeIf { it.isNotBlank() }
    val described = if (label == null) Modifier
        else Modifier.semantics { this.contentDescription = label; role = androidx.compose.ui.semantics.Role.Image }
    Box(modifier.background(Theme.card).then(described), contentAlignment = Alignment.Center) {
        if (placeholder.isNotEmpty()) {
            Text(placeholder, color = Theme.secondary, fontSize = 11.sp, textAlign = TextAlign.Center,
                maxLines = 3,
                // build 97: met een label leest TalkBack de titel één keer (niet ook nog de tijdelijke tekst)
                modifier = Modifier.padding(6.dp).then(if (label == null) Modifier else Modifier.clearAndSetSemantics { }))
        }
        if (!url.isNullOrBlank()) {
            coil.compose.AsyncImage(
                model = url, contentDescription = null, modifier = Modifier.matchParentSize(),
                contentScale = if (fit) androidx.compose.ui.layout.ContentScale.Fit else androidx.compose.ui.layout.ContentScale.Crop
            )
        }
    }
}

/** Ronde avatar van een server (bron): foto, figuurtje of emoji op kleurverloop. Voor profielen: [ProfileAvatar]. */
@Composable
fun AvatarCircle(playlist: PlaylistSource, size: Dp, ring: Boolean = false, editing: Boolean = false) {
    val v = Store.photoVersion
    val photo = remember(playlist.id, v) { Store.photo(playlist.id) }
    AvatarView(playlist.avatar, photo, size, ring = ring, editing = editing)
}


/** Bingr-logo: witte afgeronde letters met rode streep eronder (zelfde als splash). */
@Composable
fun BingrLogo(size: TextUnit = 26.sp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("BINGR", color = Color.White, fontWeight = FontWeight.Black, fontSize = size, letterSpacing = 1.sp)
        Box(Modifier.padding(top = 2.dp).width((size.value * 2.9f).dp).height((size.value * 0.12f).dp)
            .clip(RoundedCornerShape(50)).background(Theme.red))
    }
}

/** Dunne voortgangsbalk (rood op grijs). */
@Composable
fun ProgressBar(value: Double, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.3f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(value.toFloat().takeIf { !it.isNaN() }?.coerceIn(0f, 1f) ?: 0f).background(Theme.red))
    }
}
