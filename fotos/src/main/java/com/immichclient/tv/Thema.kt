package com.immichclient.tv

import androidx.compose.foundation.Image
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.FilterChip
import androidx.tv.material3.FilterChipDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.Size
import kotlinx.coroutines.delay

/** Kleuren van Immich (donker thema). */
object Kleur {
    val achtergrond = Color(0xFF0A0A0A)
    val vlak = Color(0xFF1B1B1D)
    val vlakHoog = Color(0xFF262629)
    // accentkleur = instelling (Compose-state): wie hem leest, hercomponeert bij een andere keuze
    val primair get() = Instellingen.accent.primair
    val primairDonker get() = Instellingen.accent.donker
    /** donkere tekst op de accentkleur */
    val opPrimair get() = Instellingen.accent.opPrimair
    val tekst = Color(0xFFE5E7EB)
    val zacht = Color(0xFFA1A1AA)
}

@OptIn(ExperimentalTextApi::class)
private fun overpass(w: Int) =
    Font(R.font.overpass, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

val Overpass = FontFamily(overpass(400), overpass(500), overpass(600), overpass(700))

private val basis = TextStyle(fontFamily = Overpass, color = Kleur.tekst)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FotosThema(inhoud: @Composable () -> Unit) {
    // één scrollgedrag voor alle lijsten en rasters (tijdlijn, albums, verkennen, zoeken, rijen): zie focusOp30
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides focusOp30) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Kleur.primair, onPrimary = Kleur.opPrimair,
            background = Kleur.achtergrond, surface = Kleur.vlak, onSurface = Kleur.tekst,
            surfaceVariant = Kleur.vlakHoog, onSurfaceVariant = Kleur.zacht, border = Kleur.primair,
        ),
        typography = Typography(
            displaySmall = basis.copy(fontSize = 34.sp, fontWeight = FontWeight(700)),
            headlineSmall = basis.copy(fontSize = 24.sp, fontWeight = FontWeight(600)),
            titleLarge = basis.copy(fontSize = 20.sp, fontWeight = FontWeight(600)),
            titleMedium = basis.copy(fontSize = 16.sp, fontWeight = FontWeight(600)),
            titleSmall = basis.copy(fontSize = 14.sp, fontWeight = FontWeight(600)),
            bodyLarge = basis.copy(fontSize = 16.sp),
            bodyMedium = basis.copy(fontSize = 14.sp),
            bodySmall = basis.copy(fontSize = 12.sp, color = Kleur.zacht),
            labelLarge = basis.copy(fontSize = 14.sp, fontWeight = FontWeight(600)),
            labelMedium = basis.copy(fontSize = 12.sp, fontWeight = FontWeight(600)),
            labelSmall = basis.copy(fontSize = 11.sp, fontWeight = FontWeight(500)),
        ),
        content = inhoud,
    )
    }
}

/** Beeldverzoek met vaste cachesleutel (de URL zonder sleutel); thumbnails zonder schaling, die zijn al klein. */
@Composable
fun beeld(url: String): ImageRequest {
    val ctx = LocalContext.current
    return remember(url) { ImageRequest.Builder(ctx).data(url).size(Size.ORIGINAL).crossfade(90).build() }
}

/**
 * Preview (1440 px) voor de viewer, altijd met dezelfde vaste maat zodat vooraf laden en tonen één cache-item delen;
 * nooit groter dan het scherm gedecodeerd.
 */
fun previewVerzoek(ctx: android.content.Context, id: String): ImageRequest =
    ImageRequest.Builder(ctx).data(Immich.preview(id)).size(1920, 1080).precision(Precision.INEXACT).build()

@Composable
fun preview(id: String): ImageRequest {
    val ctx = LocalContext.current
    return remember(id) { previewVerzoek(ctx, id) }
}

/** Preview voor een kaart (herinneringen): gedecodeerd op de helft (720 px, 1,5 MB) in plaats van 6 MB per kaart. */
@Composable
fun kaartBeeld(id: String): ImageRequest {
    val ctx = LocalContext.current
    return remember(id) { ImageRequest.Builder(ctx).data(Immich.preview(id)).size(400, 500).precision(Precision.INEXACT).crossfade(120).build() }
}

/** Eén foto in een raster: thumbhash meteen, de echte thumbnail eroverheen zodra hij er is. */
@Composable
fun FotoVak(
    asset: Asset,
    breedte: Dp,
    hoogte: Dp,
    onKlik: () -> Unit,
    modifier: Modifier = Modifier,
    focusDoel: Boolean = false,
    onFocusDoelKlaar: () -> Unit = {},
    onFocus: () -> Unit = {},
    vorm: Shape = RoundedCornerShape(5.dp),
) {
    val fr = remember { FocusRequester() }
    if (focusDoel) LaunchedEffect(asset.id) {
        repeat(5) { if (runCatching { fr.requestFocus() }.isSuccess) { onFocusDoelKlaar(); return@LaunchedEffect }; delay(16) }
        onFocusDoelKlaar()
    }
    // Bewust geen tv-Surface: daarvan staan er tientallen tegelijk in beeld en bij snel scrollen telt elke ms.
    var focus by remember { mutableStateOf(false) }
    val schaal by animateFloatAsState(if (focus) 1.06f else 1f, tween(140), label = "schaal")
    Box(
        modifier.size(breedte, hoogte)
            .graphicsLayer {
                scaleX = schaal; scaleY = schaal; shape = vorm; clip = true
                // zachte blauwe gloed rond de gekozen foto (schaduw van de laag: één RenderNode-eigenschap, geen extra tekenwerk)
                shadowElevation = if (focus) 16.dp.toPx() else 0f
                spotShadowColor = Kleur.primair; ambientShadowColor = Kleur.primair
            }
            .background(Kleur.vlak)
            .focusRequester(fr)
            .onFocusChanged { focus = it.isFocused; if (it.isFocused) onFocus() }
            .clickable(interactionSource = null, indication = null, onClick = onKlik),
    ) {
        val hash = remember(asset.thumbhash) { asset.thumbhash?.let { ThumbHash.bitmap(it) } }
        if (hash != null) Image(hash, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        AsyncImage(beeld(Immich.thumb(asset.id)), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (asset.video) VideoLabel(asset, Modifier.align(Alignment.TopEnd))
        if (asset.favoriet) Icon(
            Icons.Filled.Favorite, null, Modifier.align(Alignment.BottomStart).padding(6.dp).size(16.dp), tint = Color.White,
        )
        if (focus) Box(Modifier.matchParentSize().border(3.dp, Color.White, vorm))
    }
}

@Composable
private fun VideoLabel(a: Asset, modifier: Modifier) {
    Row(
        modifier.padding(6.dp).background(Color(0x99000000), RoundedCornerShape(10.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        duurKort(a.duur)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Color.White) }
        Icon(Icons.Filled.PlayArrow, null, Modifier.size(14.dp), tint = Color.White)
    }
}

/** Chip (sorteren, filters): leesbaar in elke toestand, ook gekozen + gefocust. */
@Composable
fun Keuze(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val donker = Kleur.opPrimair
    FilterChip(
        selected = selected, onClick = onClick, modifier = modifier, leadingIcon = leadingIcon,
        colors = FilterChipDefaults.colors(
            containerColor = Color.Transparent, contentColor = Kleur.tekst,
            focusedContainerColor = Kleur.primair, focusedContentColor = donker,
            selectedContainerColor = Kleur.primair.copy(alpha = 0.22f), selectedContentColor = Kleur.primair,
            focusedSelectedContainerColor = Kleur.primair, focusedSelectedContentColor = donker,
            pressedContainerColor = Kleur.primair, pressedContentColor = donker,
            pressedSelectedContainerColor = Kleur.primair, pressedSelectedContentColor = donker,
        ),
        content = { content() },
    )
}

/** Ronde gezichtsfoto van een persoon. */
@Composable
fun Gezicht(p: Persoon, grootte: Dp) {
    Box(Modifier.size(grootte).clip(CircleShape).background(Kleur.vlakHoog)) {
        AsyncImage(beeld(Immich.gezicht(p.id)), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

/** Voor lagen over een scherm (toetsenbord, datumkiezer): focus blijft binnen de laag. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.focusVangen(): Modifier = this
    .focusProperties { exit = { androidx.compose.ui.focus.FocusRequester.Cancel } }
    .focusGroup()

/** Donker verloop onder tekst op een foto. */
val onderVerloop = Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color.Transparent, 1f to Color(0xD9000000))
