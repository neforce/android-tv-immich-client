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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.focusable
import android.view.KeyEvent
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
 *
 * Bedienbaar ([stappen], 2.5): ▶ aan de rechterrand zet de focus op de balk (dan breder, met accentrand en een hint);
 * ▲▼ verschuift de keuze per stap (tijdlijn: per maand), ingedrukt houden versnelt naar grote stappen (per jaar); het
 * label toont live waar je heen gaat. OK of ◀ springt erheen en zet de focus op de eerste foto daar; ◀ zonder iets
 * te kiezen en Terug zetten de focus terug waar hij was.
 */
class BalkStappen(
    val aantal: Int,
    /** plek 0..1 van stap i op de baan */
    val fractie: (Int) -> Float,
    val label: (Int) -> String?,
    val sub: (Int) -> String?,
    /** grote sprong (ingedrukt houden) vanaf stap i in richting r (−1/+1), bijv. naar het vorige/volgende jaar */
    val groot: (Int, Int) -> Int,
    /** stap die nu (bovenaan) in beeld is */
    val huidige: () -> Int,
    /** naar stap i springen en de focus op de eerste foto daar zetten */
    val ga: (Int) -> Unit,
    /** niets gekozen: focus terug waar hij was */
    val terug: () -> Unit,
    /** stappen zijn maanden (hint "▲▼ maand · vasthouden: jaar"), anders een algemene hint */
    val perMaand: Boolean = false,
)

/** Grote sprong: elke [n] stappen (lijsten zonder jaren). */
fun grootPer(n: Int, aantal: Int): (Int, Int) -> Int = { i, r -> (i + r * n).coerceIn(0, (aantal - 1).coerceAtLeast(0)) }
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
    /** bediening met de afstandsbediening (focus op de balk); null = alleen aanwijzer */
    stappen: BalkStappen? = null,
) {
    if (scrolBalkUit) return
    var zichtbaar by remember { mutableStateOf(false) }
    // bediening: heeft de balk focus, en welke stap is gekozen (alleen in de tekenfase gelezen)
    var focus by remember { mutableStateOf(false) }
    val keuze = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var start by remember { mutableIntStateOf(0) }
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
    val alpha = animateFloatAsState(if (zichtbaar || focus) 1f else 0f, tween(if (zichtbaar || focus) 160 else 450), label = "scrolbalk")
    val tm = rememberTextMeasurer(cacheSize = 16)
    val stijlLabel = MaterialTheme.typography.labelLarge.copy(color = Color.White)
    val stijlSub = MaterialTheme.typography.labelSmall.copy(color = Color(0xB3FFFFFF))
    val stijlJaar = MaterialTheme.typography.labelSmall.copy(color = Color(0x99FFFFFF))
    val jaren = remember(markeringen, stijlJaar) { markeringen.map { (f, t) -> f to tm.measure(t, stijlJaar) } }
    val accent = Kleur.primair
    val hintTekst = androidx.compose.ui.res.stringResource(if (stappen?.perMaand == true) R.string.balk_hint else R.string.balk_hint_algemeen)
    val hint = remember(hintTekst, stijlSub) { tm.measure(hintTekst, stijlSub) }

    androidx.compose.foundation.layout.Box(modifier.fillMaxHeight().width(300.dp)) {
    Canvas(Modifier.matchParentSize().graphicsLayer { this.alpha = alpha.value; compositingStrategy = CompositingStrategy.ModulateAlpha }) {
        if (alpha.value <= 0f) return@Canvas
        val w = size.width
        val rand = 28.dp.toPx()
        val bediend = focus && stappen != null
        val duimH = (if (bediend) 40 else 30).dp.toPx()
        val loop = (size.height - 2 * rand - duimH).coerceAtLeast(0f)
        // baan (met focus breder en lichter)
        val baanB = (if (bediend) 5 else 3).dp.toPx()
        drawRoundRect(if (bediend) Color(0x66FFFFFF) else Color(0x33FFFFFF), Offset(w - 11.5.dp.toPx() - baanB / 2, rand), Size(baanB, size.height - 2 * rand), CornerRadius(baanB / 2))
        // jaartallen: links van de baan, alleen als ze elkaar niet overlappen (minstens 20 dp uit elkaar)
        val min = 20.dp.toPx()
        var laatste = -1e9f
        for ((f, lay) in jaren) {
            val y = f * loop
            if (y - laatste < min) continue
            laatste = y
            drawText(lay, topLeft = Offset(w - 22.dp.toPx() - lay.size.width, rand + y + duimH / 2 - lay.size.height / 2))
        }
        // duim (bediend: op de gekozen stap, groter, met een gloed)
        val st = stappen
        val k = keuze.intValue
        val f = if (bediend && st != null && st.aantal > 0) st.fractie(k.coerceIn(0, st.aantal - 1)) else fractie()
        val duimY = rand + f.coerceIn(0f, 1f) * loop
        val duimB = (if (bediend) 11 else 7).dp.toPx()
        if (bediend) drawRoundRect(accent.copy(alpha = 0.35f), Offset(w - 11.5.dp.toPx() - duimB / 2 - 4.dp.toPx(), duimY - 4.dp.toPx()), Size(duimB + 8.dp.toPx(), duimH + 8.dp.toPx()), CornerRadius(9.dp.toPx()))
        drawRoundRect(accent, Offset(w - 11.5.dp.toPx() - duimB / 2, duimY), Size(duimB, duimH), CornerRadius(duimB / 2))
        // label naast de duim (donker vlak, zodat hij ook over foto's leesbaar is)
        @Suppress("UNUSED_EXPRESSION") tik
        val nu = android.os.SystemClock.uptimeMillis()
        if (bediend || nu - geheugen.tijd >= 150) {
            geheugen.tijd = nu
            run {
                val lt = if (bediend && st != null) st.label(k) else label()
                if (lt != geheugen.labelTekst) { geheugen.labelTekst = lt; geheugen.label = lt?.let { tm.measure(it, stijlLabel) } }
                val sbt = if (bediend && st != null) st.sub(k) else sub()
                if (sbt != geheugen.subTekst) { geheugen.subTekst = sbt; geheugen.sub = sbt?.let { tm.measure(it, stijlSub) } }
            }
        }
        val l = geheugen.label
        val s = geheugen.sub
        val hl = if (bediend) hint else null
        if (l == null && s == null) return@Canvas
        val tekstB = maxOf(l?.size?.width ?: 0, s?.size?.width ?: 0, hl?.size?.width ?: 0).toFloat()
        val tekstH = ((l?.size?.height ?: 0) + (s?.size?.height ?: 0) + (hl?.size?.height?.plus(4.dp.roundToPx()) ?: 0)).toFloat()
        val pb = 14.dp.toPx(); val ph = 6.dp.toPx()
        val vlakB = tekstB + 2 * pb; val vlakH = tekstH + 2 * ph
        val rechts = w - 26.dp.toPx()
        val boven = (duimY + duimH / 2 - vlakH / 2).coerceIn(0f, (size.height - vlakH).coerceAtLeast(0f))
        drawRoundRect(Color(0xE6000000), Offset(rechts - vlakB, boven), Size(vlakB, vlakH), CornerRadius(12.dp.toPx()))
        if (bediend) drawRoundRect(accent, Offset(rechts - vlakB, boven), Size(vlakB, vlakH), CornerRadius(12.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        var y = boven + ph
        if (l != null) { drawText(l, topLeft = Offset(rechts - pb - l.size.width, y)); y += l.size.height }
        if (s != null) { drawText(s, topLeft = Offset(rechts - pb - s.size.width, y)); y += s.size.height }
        if (hl != null) drawText(hl, topLeft = Offset(rechts - pb - hl.size.width, y + 4.dp.toPx()), color = accent)
    }
    // de balk zelf als focusdoel (smal, helemaal rechts): ▶ aan de rechterrand van het raster komt hier uit
    if (stappen != null && stappen.aantal > 0) androidx.compose.foundation.layout.Box(
        Modifier.align(androidx.compose.ui.Alignment.CenterEnd).fillMaxHeight().width(28.dp)
            .focusProperties { canFocus = scrolbaar() }
            .onFocusChanged {
                if (it.isFocused && !focus) { keuze.intValue = stappen.huidige().coerceIn(0, stappen.aantal - 1); start = keuze.intValue; android.util.Log.i("Fotos", "balk: focus (stap $start van ${stappen.aantal})") }
                focus = it.isFocused
            }
            .onPreviewKeyEvent { e ->
                val n = e.nativeKeyEvent
                val pijl = n.keyCode == KeyEvent.KEYCODE_DPAD_UP || n.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                val ok = n.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || n.keyCode == KeyEvent.KEYCODE_ENTER
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent pijl || ok || n.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || n.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                when {
                    pijl -> {
                        val r = if (n.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                        val h = n.repeatCount
                        // los = één stap; ingedrukt eerst ~10 stappen per seconde, na ~1 s grote sprongen (jaar) ~6 per seconde
                        val nieuw = when {
                            h >= 14 -> if (h % 3 == 0) stappen.groot(keuze.intValue, r) else keuze.intValue
                            h > 0 && h % 2 == 1 -> keuze.intValue
                            else -> keuze.intValue + r
                        }
                        keuze.intValue = nieuw.coerceIn(0, stappen.aantal - 1)
                        true
                    }
                    ok -> { android.util.Log.i("Fotos", "balk: OK → stap ${keuze.intValue}"); stappen.ga(keuze.intValue); true }
                    n.keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> { if (keuze.intValue != start) stappen.ga(keuze.intValue) else stappen.terug(); true }
                    n.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> true
                    n.keyCode == KeyEvent.KEYCODE_BACK -> { stappen.terug(); true }
                    else -> false
                }
            }
            .focusable(),
    )
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
