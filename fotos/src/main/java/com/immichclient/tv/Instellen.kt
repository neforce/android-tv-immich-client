package com.immichclient.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Storage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/**
 * Eerste start (of "Opnieuw koppelen"): QR-code naar de telefoonpagina van Koppel.kt, links de uitleg en de voortgang.
 * [terug] is null als er nog geen account is (Terug sluit dan de app); [klaar] wordt aangeroepen na een geslaagde koppeling.
 */
@Composable
fun InstelScherm(test: Boolean, terug: (() -> Unit)?, klaar: () -> Unit, finish: () -> Unit) {
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        Koppel.start(ctx, test)
        onDispose { Koppel.stop() }
    }
    BackHandler { terug?.invoke() ?: finish() }
    val stand = Koppel.stand
    val url = Koppel.url
    if (stand is Koppel.Stand.Klaar) LaunchedEffect(stand) { delay(2800); klaar() }

    // Het scherm is 960×540 dp: links uitleg + voortgang, rechts de QR (320 dp); alles moet zonder scrollen passen.
    Row(Modifier.fillMaxSize().background(Kleur.achtergrond).padding(horizontal = 64.dp, vertical = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 40.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.background(Kleur.vlak, CircleShape).padding(12.dp)) { Icon(Icons.Filled.PhotoLibrary, null, Modifier.size(26.dp), tint = Kleur.primair) }
                Column(Modifier.padding(start = 16.dp)) {
                    Text(stringResource(R.string.koppel_titel), style = MaterialTheme.typography.displaySmall.copy(fontSize = 28.sp), maxLines = 1)
                    Text(stringResource(if (test) R.string.koppel_test else R.string.koppel_eenmalig), style = MaterialTheme.typography.bodyMedium, color = Kleur.zacht, maxLines = 2)
                    Text(stringResource(R.string.onofficieel_kort), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
            Spacer(Modifier.height(26.dp))
            Stap(1, stringResource(R.string.stap1), stringResource(R.string.stap1_sub))
            Stap(2, stringResource(R.string.stap2), stringResource(R.string.stap2_sub))
            Stap(3, stringResource(R.string.stap3), stringResource(R.string.stap3_sub))
            Spacer(Modifier.height(10.dp))
            // voortgang / resultaat
            AnimatedContent(stand::class, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) }, label = "stand") { _ ->
                when (val s = Koppel.stand) {
                    is Koppel.Stand.Wacht -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Puls(); Text(stringResource(R.string.wacht_telefoon), style = MaterialTheme.typography.bodyLarge, color = Kleur.zacht, modifier = Modifier.padding(start = 12.dp))
                    }
                    is Koppel.Stand.Bezig -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Ring(); Text(s.stap.toString(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp), maxLines = 2)
                    }
                    is Koppel.Stand.Klaar -> Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Filled.CheckCircle, null, Modifier.size(28.dp), tint = Color(0xFF86EFAC))
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(stringResource(R.string.klaar_titel), style = MaterialTheme.typography.titleLarge, color = Color(0xFF86EFAC))
                            Text(stringResource(R.string.gekoppeld_als, s.naam, s.server), style = MaterialTheme.typography.bodyMedium, color = Kleur.zacht)
                            s.waarschuwing?.let { Text(it.toString(), style = MaterialTheme.typography.bodyMedium, color = Color(0xFFFCD34D), modifier = Modifier.padding(top = 6.dp)) }
                        }
                    }
                    is Koppel.Stand.Fout -> Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Filled.ErrorOutline, null, Modifier.size(28.dp), tint = Color(0xFFFCA5A5))
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(stringResource(R.string.koppel_mislukt), style = MaterialTheme.typography.titleLarge, color = Color(0xFFFCA5A5))
                            Text(s.melding.toString(), style = MaterialTheme.typography.bodyMedium, color = Kleur.tekst)
                            if (url != null) Text(stringResource(R.string.probeer_opnieuw), style = MaterialTheme.typography.bodyMedium, color = Kleur.zacht, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
        // QR rechts
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(320.dp).background(Color.White, RoundedCornerShape(20.dp)).padding(20.dp), contentAlignment = Alignment.Center) {
                when {
                    stand is Koppel.Stand.Klaar -> Icon(Icons.Filled.CheckCircle, null, Modifier.size(140.dp), tint = Color(0xFF16A34A))
                    url != null -> {
                        val qr = remember(url) { Koppel.qr(url).asImageBitmap() }
                        Image(qr, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
                    }
                    else -> Text(stringResource(R.string.geen_netwerk), style = MaterialTheme.typography.titleLarge, color = Color(0xFF52525B))
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.typ_adres), style = MaterialTheme.typography.bodySmall)
            Text(url ?: "–", style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 17.sp, letterSpacing = 0.5.sp), color = Kleur.primair, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun Stap(n: Int, titel: String, sub: String) {
    Row(Modifier.padding(bottom = 14.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(30.dp).background(Kleur.primair, CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", style = MaterialTheme.typography.labelLarge, color = Kleur.opPrimair)
        }
        Column(Modifier.padding(start = 16.dp)) {
            Text(titel, style = MaterialTheme.typography.titleMedium)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = Kleur.zacht)
        }
    }
}

/** Zacht pulserend stipje: er gebeurt nog niets. */
@Composable
private fun Puls() {
    val t = rememberInfiniteTransition(label = "puls")
    val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(12.dp).background(Kleur.primair.copy(alpha = a), CircleShape)) }
}

/** Draaiend ringetje tijdens het koppelen. */
@Composable
private fun Ring() {
    val t = rememberInfiniteTransition(label = "ring")
    val hoek by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "hoek")
    Canvas(Modifier.size(28.dp).padding(3.dp)) {
        val dikte = 3.dp.toPx()
        drawCircle(Color(0x33FFFFFF), style = Stroke(dikte))
        drawArc(Kleur.primair, hoek, 100f, false, style = Stroke(dikte, cap = StrokeCap.Round))
    }
}
