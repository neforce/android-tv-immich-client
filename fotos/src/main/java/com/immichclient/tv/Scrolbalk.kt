package com.immichclient.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.max

/**
 * Scrollbalk rechts: een dunne baan met een duim op de plek in het GEHELE geheel, een label ernaast (maand, of
 * "1.234 / 47.153") en eventueel jaartallen langs de baan. Verschijnt bij scrollen en vervaagt 1,5 s later.
 *
 * Alles wordt in één Canvas getekend en de scrollpositie wordt alleen in de tekenfase gelezen ([fractie], [label],
 * [sub] in de draw-lambda): per scrollframe wordt dus alleen deze laag opnieuw getekend, zonder hercompositie of
 * layout. Een eerdere versie met gewone Text/offset-composables hercomponeerde tijdens de scroll-animatie en kostte
 * 12-21 % janky frames in plaats van 1-5 %; met de Canvas en de afgeremde tekstopmaak is het verschil met "geen balk"
 * binnen de meetruis (test: `--ez geen_balk true`).
 */
@Composable
fun ScrolBalk(
    /** 0..1: plek van de duim; gelezen tijdens het tekenen */
    fractie: () -> Float,
    /** snapshot-waarde die bij elke scrollstap verandert (bepaalt wanneer de balk te zien is) */
    beweging: () -> Any?,
    /** is er überhaupt iets te scrollen? */
    scrolbaar: () -> Boolean,
    label: () -> String?,
    sub: () -> String?,
    /** jaartallen (of andere markeringen) langs de baan: plek 0..1 → tekst */
    markeringen: List<Pair<Float, String>> = emptyList(),
    modifier: Modifier = Modifier,
) {
    if (scrolBalkUit) return
    var zichtbaar by remember { mutableStateOf(false) }
    // na elke scrollstap één extra tekenbeurt (170 ms later): de tekst wordt hooguit elke 150 ms opnieuw opgemaakt en zou
    // anders na de laatste stap een paar nummers achter kunnen blijven
    var tik by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        snapshotFlow { beweging() }.distinctUntilChanged().collectLatest {
            if (!scrolbaar()) { zichtbaar = false; return@collectLatest }
            zichtbaar = true; delay(170); tik++; delay(1330); zichtbaar = false
        }
    }
    // tekstopmaak van het label is het dure deel (1-3 ms per meting met het variabele lettertype): hooguit ~6× per seconde
    val geheugen = remember { LabelGeheugen() }
    // alpha alleen in de laag gelezen (geen hercompositie per animatieframe)
    val alpha = animateFloatAsState(if (zichtbaar) 1f else 0f, tween(if (zichtbaar) 160 else 450), label = "scrolbalk")
    val tm = rememberTextMeasurer(cacheSize = 16)
    val stijlLabel = MaterialTheme.typography.labelLarge.copy(color = Color.White)
    val stijlSub = MaterialTheme.typography.labelSmall.copy(color = Color(0xB3FFFFFF))
    val stijlJaar = MaterialTheme.typography.labelSmall.copy(color = Color(0x99FFFFFF))
    val jaren = remember(markeringen, stijlJaar) { markeringen.map { (f, t) -> f to tm.measure(t, stijlJaar) } }
    val accent = Kleur.primair

    Canvas(modifier.fillMaxHeight().width(300.dp).graphicsLayer { this.alpha = alpha.value; compositingStrategy = CompositingStrategy.ModulateAlpha }) {
        if (alpha.value <= 0f) return@Canvas
        val w = size.width
        val rand = 28.dp.toPx()
        val duimH = 30.dp.toPx()
        val loop = (size.height - 2 * rand - duimH).coerceAtLeast(0f)
        // baan
        drawRoundRect(Color(0x33FFFFFF), Offset(w - 13.dp.toPx(), rand), Size(3.dp.toPx(), size.height - 2 * rand), CornerRadius(2.dp.toPx()))
        // jaartallen: links van de baan, alleen als ze elkaar niet overlappen (minstens 20 dp uit elkaar)
        val min = 20.dp.toPx()
        var laatste = -1e9f
        for ((f, lay) in jaren) {
            val y = f * loop
            if (y - laatste < min) continue
            laatste = y
            drawText(lay, topLeft = Offset(w - 22.dp.toPx() - lay.size.width, rand + y + duimH / 2 - lay.size.height / 2))
        }
        // duim
        val duimY = rand + fractie().coerceIn(0f, 1f) * loop
        drawRoundRect(accent, Offset(w - 15.dp.toPx(), duimY), Size(7.dp.toPx(), duimH), CornerRadius(4.dp.toPx()))
        // label naast de duim (donker vlak, zodat hij ook over foto's leesbaar is)
        @Suppress("UNUSED_EXPRESSION") tik
        val nu = android.os.SystemClock.uptimeMillis()
        if (nu - geheugen.tijd >= 150) {
            geheugen.tijd = nu
            run {
                val lt = label(); if (lt != geheugen.labelTekst) { geheugen.labelTekst = lt; geheugen.label = lt?.let { tm.measure(it, stijlLabel) } }
                val st = sub(); if (st != geheugen.subTekst) { geheugen.subTekst = st; geheugen.sub = st?.let { tm.measure(it, stijlSub) } }
            }
        }
        val l = geheugen.label
        val s = geheugen.sub
        if (l == null && s == null) return@Canvas
        val tekstB = max(l?.size?.width ?: 0, s?.size?.width ?: 0).toFloat()
        val tekstH = ((l?.size?.height ?: 0) + (s?.size?.height ?: 0)).toFloat()
        val pb = 14.dp.toPx(); val ph = 6.dp.toPx()
        val vlakB = tekstB + 2 * pb; val vlakH = tekstH + 2 * ph
        val rechts = w - 24.dp.toPx()
        val boven = duimY + duimH / 2 - vlakH / 2
        drawRoundRect(Color(0xD9000000), Offset(rechts - vlakB, boven), Size(vlakB, vlakH), CornerRadius(12.dp.toPx()))
        var y = boven + ph
        if (l != null) { drawText(l, topLeft = Offset(rechts - pb - l.size.width, y)); y += l.size.height }
        if (s != null) drawText(s, topLeft = Offset(rechts - pb - s.size.width, y))
    }
}

/** test: `--ez geen_balk true` zet de scrollbalk uit (om de soepelheid met en zonder te vergelijken) */
@Volatile var scrolBalkUit = false

private class LabelGeheugen {
    var tijd = 0L
    var labelTekst: String? = null
    var subTekst: String? = null
    var label: androidx.compose.ui.text.TextLayoutResult? = null
    var sub: androidx.compose.ui.text.TextLayoutResult? = null
}

/** Plek 0..1 van een LazyColumn: eerste zichtbare item + stukje erin, t.o.v. wat er te scrollen valt. */
fun fractieVan(lijst: LazyListState, bovenAantal: Int = 0): Float {
    val info = lijst.layoutInfo
    val zicht = info.visibleItemsInfo
    val totaal = info.totalItemsCount - bovenAantal
    if (totaal <= 0 || zicht.isEmpty()) return 0f
    val eerste = zicht.first()
    val deel = if (eerste.size > 0) (-eerste.offset.toFloat() / eerste.size).coerceIn(0f, 1f) else 0f
    val i = (eerste.index - bovenAantal + deel).coerceAtLeast(0f)
    val n = (totaal - zicht.size + 1).coerceAtLeast(1)
    return if (!lijst.canScrollForward) 1f else (i / n).coerceIn(0f, 1f)
}

fun fractieVan(grid: LazyGridState, bovenAantal: Int = 0): Float {
    val info = grid.layoutInfo
    val zicht = info.visibleItemsInfo
    val totaal = info.totalItemsCount - bovenAantal
    if (totaal <= 0 || zicht.isEmpty()) return 0f
    val i = (zicht.first().index - bovenAantal).coerceAtLeast(0)
    val n = (totaal - zicht.size + 1).coerceAtLeast(1)
    return if (!grid.canScrollForward) 1f else (i.toFloat() / n).coerceIn(0f, 1f)
}

/** Per rij het nummer (vanaf 0) van de eerste foto erin, voor "N / M" in platte lijsten. */
fun eersteFotoPerRij(rijen: List<Rij>): IntArray {
    val uit = IntArray(rijen.size)
    var n = 0
    rijen.forEachIndexed { i, r -> uit[i] = n; if (r is RijFotos) n += r.regel.stukken.sumOf { it.vakken.size } }
    return uit
}
