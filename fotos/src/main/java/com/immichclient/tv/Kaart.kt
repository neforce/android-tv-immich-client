package com.immichclient.tv

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.core.VectorConverter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Landkaart: de contour van het land met een stip waar de foto is gemaakt (zoals een fotolijst-screensaver).
 * Landvormen uit assets/landen.bin (Natural Earth 50m, vereenvoudigd; gemaakt met landen_bin.py).
 */
class Land(val naam: String, val minLon: Float, val minLat: Float, val maxLon: Float, val maxLat: Float, val ringen: List<FloatArray>)

object Landen {
    @Volatile private var lijst: List<Land>? = null
    private val slot = Mutex()

    /** Eén keer lezen (≈250 kB, 60k punten), buiten de hoofdthread. */
    suspend fun lijst(ctx: Context): List<Land> {
        lijst?.let { return it }
        return slot.withLock {
            lijst ?: withContext(Dispatchers.IO) { lees(ctx.assets.open("landen.bin").use { it.readBytes() }) }.also { lijst = it }
        }
    }

    private fun lees(b: ByteArray): List<Land> {
        val bb = ByteBuffer.wrap(b) // big-endian, zie landen_bin.py
        return List(bb.short.toInt()) {
            val naam = ByteArray(bb.short.toInt()).also { bb.get(it) }.toString(Charsets.UTF_8)
            val b0 = bb.short / 100f; val b1 = bb.short / 100f; val b2 = bb.short / 100f; val b3 = bb.short / 100f
            val ringen = List(bb.short.toInt()) { FloatArray(2 * bb.short.toInt()) { bb.short / 100f } }
            Land(naam, b0, b1, b2, b3, ringen)
        }
    }

    private fun inRing(x: Float, y: Float, r: FloatArray): Boolean {
        var binnen = false
        var j = r.size - 2
        var i = 0
        while (i < r.size) {
            val xi = r[i]; val yi = r[i + 1]; val xj = r[j]; val yj = r[j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) binnen = !binnen
            j = i; i += 2
        }
        return binnen
    }

    /** Het land waar het punt in ligt; net naast de (vereenvoudigde) kust: het dichtstbijzijnde land binnen ~1°. */
    fun zoek(landen: List<Land>, lon: Float, lat: Float): Land? {
        var best: Land? = null
        var bestAfstand = 1e9f
        for (l in landen) {
            if (lon < l.minLon - 1 || lon > l.maxLon + 1 || lat < l.minLat - 1 || lat > l.maxLat + 1) continue
            for (r in l.ringen) if (inRing(lon, lat, r)) return l
            for (r in l.ringen) {
                var i = 0
                while (i < r.size) {
                    val d = abs(r[i] - lon) + abs(r[i + 1] - lat)
                    if (d < bestAfstand) { bestAfstand = d; best = l }
                    i += 2
                }
            }
        }
        return if (bestAfstand < 1f) best else null
    }
}

/**
 * Uitgerekende kaart in px (oorsprong = linkerbovenhoek van de kaart): ringen als paden, maat en de stip.
 * [ringIdx] = welke ringen van het land getekend zijn; met de projectie (k, minX, minY, schaal) kan de stip van een
 * andere foto in hetzelfde land in dezelfde tekening gezet worden (contour blijft staan, alleen de stip verhuist).
 */
class KaartVorm(
    val land: String, val ringIdx: List<Int>, val paden: List<Path>, val breedte: Float, val hoogte: Float, val stip: Offset,
    private val k: Float, private val minX: Float, private val minY: Float, private val schaal: Float,
) {
    fun stipVoor(lon: Float, lat: Float) = Offset((lon * k - minX) * schaal, (-lat - minY) * schaal)
}

/**
 * Eenvoudige projectie: x = lon·cos(lat), y = -lat. Alleen delen van het land in de buurt van de foto
 * (geen Alaska bij New York). Het land vult [hoog]; een breed land mag tot [maxOverloop]×[breed] worden
 * (loopt dan rechts buiten beeld), de stip blijft minstens [stipMarge] binnen het vlak.
 */
fun kaartVorm(landen: List<Land>, lon: Float, lat: Float, breed: Float, hoog: Float, stipMarge: Float, maxOverloop: Float): KaartVorm? {
    val land = Landen.zoek(landen, lon, lat) ?: return null
    val k = cos(lat * Math.PI / 180).toFloat()
    val ringIdx = land.ringen.indices.filter { ri ->
        val r = land.ringen[ri]
        var x0 = 1e9f; var x1 = -1e9f; var y0 = 1e9f; var y1 = -1e9f
        var i = 0
        while (i < r.size) { x0 = min(x0, r[i]); x1 = max(x1, r[i]); y0 = min(y0, r[i + 1]); y1 = max(y1, r[i + 1]); i += 2 }
        (lon in x0..x1 && lat in y0..y1) || (abs((x0 + x1) / 2 - lon) * k < 8 && abs((y0 + y1) / 2 - lat) < 8)
    }
    if (ringIdx.isEmpty()) return null
    val ringen = ringIdx.map { land.ringen[it] }
    var minX = lon * k; var maxX = minX; var minY = -lat; var maxY = minY
    for (r in ringen) {
        var i = 0
        while (i < r.size) {
            val x = r[i] * k; val y = -r[i + 1]
            if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
            i += 2
        }
    }
    val w = (maxX - minX).takeIf { it > 0 } ?: 1f
    val h = (maxY - minY).takeIf { it > 0 } ?: 1f
    val stipX = lon * k - minX
    val schaal = minOf(hoog / h, maxOverloop * breed / w, if (stipX > 0) (breed - stipMarge) / stipX else 1e9f)
    val paden = ringen.map { r ->
        Path().apply {
            var i = 0
            while (i < r.size) {
                val x = (r[i] * k - minX) * schaal; val y = (-r[i + 1] - minY) * schaal
                if (i == 0) moveTo(x, y) else lineTo(x, y)
                i += 2
            }
            close()
        }
    }
    return KaartVorm(land.naam, ringIdx, paden, w * schaal, h * schaal, Offset(stipX * schaal, (-lat - minY) * schaal), k, minX, minY, schaal)
}

private val LAND_VUL = Color(0x42FFFFFF)
private val LAND_LIJN = Color(0xF2FFFFFF)
private val SCHADUW = Color(0x8C000000)
private val GLOED = Color(0x47FFFFFF)

/** Contour (deel [lijn] 0..1 getekend), vulling ([vul] 0..1) en stip ([plof] 0..1), alles t.o.v. de kaart-oorsprong. */
private fun DrawScope.tekenLand(vorm: KaartVorm, metingen: List<PathMeasure>?, stuk: Path, lijn: Float, vul: Float, plof: Float, dikte: Float, stip: Offset = vorm.stip) {
    val streek = Stroke(dikte, join = StrokeJoin.Round)
    val schaduw = Stroke(dikte * 2.5f, join = StrokeJoin.Round)
    vorm.paden.forEachIndexed { i, p ->
        if (vul > 0f) drawPath(p, LAND_VUL.copy(alpha = LAND_VUL.alpha * vul))
        val pad = when {
            lijn >= 1f || metingen == null -> p
            lijn <= 0f -> null
            else -> { stuk.rewind(); metingen[i].getSegment(0f, lijn * metingen[i].length, stuk, true); stuk }
        }
        if (pad != null) {
            translate(0f, dikte * 0.6f) { drawPath(pad, SCHADUW, style = schaduw) }
            drawPath(pad, LAND_LIJN, style = streek)
        }
    }
    if (plof > 0f) {
        drawCircle(SCHADUW, 7.dp.toPx() * plof, stip + Offset(0f, dikte * 0.6f))
        drawCircle(GLOED, 14.dp.toPx() * plof, stip)
        drawCircle(Color.White, 5.5.dp.toPx() * plof, stip)
    }
}

// Vlak voor de kaart in de viewer: rechter 1/3 van de breedte, land tot 80% van de hoogte, 6% boven de onderrand.
private const val BREED = 1f / 3
private const val HOOG = 0.8f
private const val ONDER = 0.06f
private const val MAX_OVERLOOP = 2.2f

/** Animatie: contour tekent zich (0,2–1,8 s), vult (1,6–2,4 s), stip ploft (1,5–2,0 s), label schuift in (1,8–2,5 s). */
private fun deel(t: Float, van: Float, duur: Float) = ((t - van) / duur).coerceIn(0f, 1f)
private val TEKEN = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

/** Kaart pas tonen als de overgang klaar is en je even op een foto blijft (niet storend tijdens snel bladeren). */
private const val KAART_WACHT_MS = 500L
private const val UIT_MS = 450

/**
 * Kaart in de viewer: één laag die blijft bestaan terwijl je bladert. [doel] is de foto die de kaart moet tonen (null =
 * geen kaart: video, infopaneel, geen GPS). Ligt de nieuwe foto in hetzelfde land (zelfde ringen, stip past in de
 * tekening), dan blijft de contour staan en glijdt alleen de stip naar de nieuwe plek (label wisselt met een fade);
 * anders ontrolt de oude kaart eerst (uitgang) en tekent de nieuwe zich opnieuw.
 */
@Composable
fun KaartLaag(doel: Asset?, breedtePx: Int, hoogtePx: Int, maat: Float = 1f, modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val dicht = LocalDensity.current
    val scope = rememberCoroutineScope()
    var vorm by remember { mutableStateOf<KaartVorm?>(null) }
    var label by remember { mutableStateOf<Pair<String, String?>?>(null) }
    /** doel van de stip (voor de kant van het label), de stip zelf animeert ernaartoe */
    var stipDoel by remember { mutableStateOf(Offset.Zero) }
    val stip = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val klok = remember { Animatable(0f) }
    val uit = remember { Animatable(0f) }
    var pulseert by remember { mutableStateOf(false) }
    val stipMarge = with(dicht) { 40.dp.toPx() }
    val rechtsMarge = with(dicht) { 64.dp.toPx() }
    // [maat] = instelling klein/groot: schaalt het vlak (breedte én hoogte) waarin het land past
    val breed = breedtePx * BREED * maat
    val hoog = hoogtePx * HOOG * maat

    // plek van de kaart op het scherm: past het land, dan rechts uitgelijnd; anders begint het links in het vlak en loopt het rechts buiten beeld
    fun plek(v: KaartVorm): Offset {
        val rechts = if (v.breedte <= breed - rechtsMarge) rechtsMarge else breed - v.breedte
        return Offset(breedtePx - rechts - v.breedte, hoogtePx * (1 - ONDER) - v.hoogte)
    }
    suspend fun bereken(a: Asset): KaartVorm? {
        val landen = Landen.lijst(ctx)
        return withContext(Dispatchers.Default) {
            with(dicht) { kaartVorm(landen, a.lon!!.toFloat(), a.lat!!.toFloat(), breed, hoog, stipMarge, MAX_OVERLOOP) }
        }
    }
    // uitgang: contour ontrolt, stip krimpt, label schuift omhoog weg
    suspend fun weg() {
        uit.animateTo(1f, tween(UIT_MS, easing = EaseIn))
        vorm = null; label = null; pulseert = false
        uit.snapTo(0f)
    }

    val doelNu = rememberUpdatedState(doel)
    LaunchedEffect(breedtePx, hoogtePx, maat) {
        // sequentieel: een uitgang loopt altijd af, daarna pas de volgende wens (snapshotFlow geeft alleen de laatste door)
        snapshotFlow { doelNu.value }.collect { a ->
            val oud = vorm
            if (a == null || a.lat == null || a.lon == null) { if (oud != null) weg(); return@collect }
            val nieuw = runCatching { bereken(a) }.onFailure { android.util.Log.w("Fotos", "kaart", it) }.getOrNull()
            if (oud != null && nieuw != null && oud.land == nieuw.land && oud.ringIdx == nieuw.ringIdx) {
                val s = oud.stipVoor(a.lon.toFloat(), a.lat.toFloat())
                val p = plek(oud)
                // 1 px speling: een stip precies op de marge (de projectie zet hem daar vaak) mag niet op afronding afvallen
                if (s.x in -1f..oud.breedte + 1f && s.y in -1f..oud.hoogte + 1f && p.x + s.x <= breedtePx - stipMarge + 1f) {
                    label = maandJaar(a.datum) to a.stad
                    stipDoel = s
                    scope.launch { stip.animateTo(s, tween(700, easing = FastOutSlowInEasing)) }
                    return@collect
                }
            }
            val t0 = System.currentTimeMillis()
            if (oud != null) weg()
            kotlinx.coroutines.delay((KAART_WACHT_MS - (System.currentTimeMillis() - t0)).coerceAtLeast(0))
            if (nieuw == null || doelNu.value?.id != a.id) return@collect
            klok.snapTo(0f); uit.snapTo(0f); stip.snapTo(nieuw.stip); stipDoel = nieuw.stip; pulseert = false
            label = maandJaar(a.datum) to a.stad
            vorm = nieuw
        }
    }
    val v = vorm ?: return
    val oorsprong = plek(v)
    val links = oorsprong.x
    val boven = oorsprong.y
    val metingen = remember(v) { v.paden.map { PathMeasure().apply { setPath(it, false) } } }
    val stuk = remember { Path() }
    LaunchedEffect(v) { klok.animateTo(2600f, tween(2600, easing = LinearEasing)) }
    LaunchedEffect(v) { kotlinx.coroutines.delay(2000); pulseert = true }

    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val t = klok.value
            val u = uit.value
            translate(links, boven) {
                tekenLand(
                    v, metingen, stuk,
                    lijn = min(TEKEN.transform(deel(t, 200f, 1600f)), 1f - u),
                    vul = EaseOut.transform(deel(t, 1600f, 800f)) * (1f - u),
                    plof = EaseOutBack.transform(deel(t, 1500f, 500f)) * (1f - min(1f, u * 1.3f)),
                    dikte = 2.dp.toPx(),
                    stip = stip.value,
                )
            }
        }
        // pulserende ring om de stip: alleen laag-eigenschappen veranderen, de rest wordt niet opnieuw getekend
        if (pulseert) {
            val puls = rememberInfiniteTransition(label = "puls")
            val p by puls.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = EaseOut), RepeatMode.Restart), label = "p")
            Canvas(
                Modifier.offset { IntOffset((links + stip.value.x).roundToInt() - 14.dp.roundToPx(), (boven + stip.value.y).roundToInt() - 14.dp.roundToPx()) }
                    .size(28.dp)
                    .graphicsLayer { val s = 1 + 1.2f * p; scaleX = s; scaleY = s; alpha = (1 - p / 0.7f).coerceIn(0f, 1f) * (1 - uit.value) },
            ) { drawCircle(GLOED, 14.dp.toPx()) }
        }
        // label bij de stip: rechts ervan, of links als het anders buiten beeld valt; volgt de stip, tekst wisselt met een fade
        val l = label ?: return@Box
        val tm = rememberTextMeasurer()
        val stijlMaand = TextStyle(fontFamily = Overpass, fontSize = 14.sp, color = Color(0xD9FFFFFF), shadow = Shadow(Color(0xD9000000), Offset(0f, 1f), 8f))
        val stijlPlaats = TextStyle(fontFamily = Overpass, fontSize = 20.sp, color = Color.White, shadow = Shadow(Color(0xD9000000), Offset(0f, 1f), 8f))
        val tekstMaat = remember(l) {
            val a = tm.measure(l.first, stijlMaand).size
            val b = l.second?.let { tm.measure(it, stijlPlaats).size }
            IntOffset(max(a.width, b?.width ?: 0), a.height + (b?.height ?: 0))
        }
        val afstand = with(dicht) { 22.dp.toPx() }
        val naarLinks = links + stipDoel.x + afstand + tekstMaat.x > breedtePx - with(dicht) { 24.dp.toPx() }
        val uitlijning = if (naarLinks) TextAlign.End else TextAlign.Start
        Box(
            Modifier.offset {
                val sx = links + stip.value.x
                val x = if (naarLinks) sx - afstand - tekstMaat.x else sx + afstand
                IntOffset(x.roundToInt(), (boven + stip.value.y - tekstMaat.y / 2f).roundToInt())
            }.graphicsLayer {
                val f = EaseOut.transform(deel(klok.value, 1800f, 700f))
                val u = uit.value
                alpha = f * (1 - u); translationX = (1 - f) * 10.dp.toPx() * if (naarLinks) 1 else -1; translationY = -u * 0.3f * size.height
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
        ) {
            AnimatedContent(
                targetState = l, label = "label",
                transitionSpec = { fadeIn(tween(350, delayMillis = 150)) togetherWith fadeOut(tween(200)) },
                contentAlignment = if (naarLinks) Alignment.TopEnd else Alignment.TopStart,
            ) { (maand, plaats) ->
                Column(horizontalAlignment = if (naarLinks) Alignment.End else Alignment.Start) {
                    Text(maand, style = stijlMaand, maxLines = 1, textAlign = uitlijning)
                    if (plaats != null) Text(plaats, style = stijlPlaats, maxLines = 1, textAlign = uitlijning)
                }
            }
        }
    }
}

/** Kleine, stilstaande kaart (infopaneel): het hele land past in het vak. */
@Composable
fun KaartKlein(lat: Double, lon: Double, modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val dicht = LocalDensity.current
        val w = with(dicht) { maxWidth.toPx() }
        val h = with(dicht) { maxHeight.toPx() }
        val vorm by produceState<KaartVorm?>(null, lat, lon, w, h) {
            value = runCatching {
                val landen = Landen.lijst(ctx)
                withContext(Dispatchers.Default) { with(dicht) { kaartVorm(landen, lon.toFloat(), lat.toFloat(), w - 24.dp.toPx(), h - 24.dp.toPx(), 12.dp.toPx(), 1f) } }
            }.getOrNull()
        }
        val v = vorm ?: return@BoxWithConstraints
        val stuk = remember { Path() }
        Canvas(Modifier.fillMaxSize()) {
            translate((size.width - v.breedte) / 2, (size.height - v.hoogte) / 2) { tekenLand(v, null, stuk, 1f, 1f, 1f, 1.5.dp.toPx()) }
        }
    }
}
