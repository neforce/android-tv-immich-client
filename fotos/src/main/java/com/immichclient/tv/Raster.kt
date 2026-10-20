package com.immichclient.tv

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/** Een regel in een fotoraster (LazyColumn). */
sealed interface Rij { val sleutel: String }
data class RijKop(override val sleutel: String, val titel: String, val sub: String?) : Rij
data class RijFotos(val regel: Regel) : Rij { override val sleutel get() = regel.sleutel }
/** Maand die nog geladen wordt; focusbaar, zodat ingedrukt houden van "omlaag" nooit vastloopt. */
data class RijWacht(override val sleutel: String, val maand: String, val hoogte: Float) : Rij

const val RIJ_HOOGTE = 138f
const val TUSSEN = 6f
const val GROEP_TUSSEN = 26f
const val KOP_HOOGTE = 30f

class RasterStaat {
    val lijst = LazyListState()
    /** Asset die focus moet krijgen zodra hij in beeld is (terug uit de viewer / terug naar dit scherm). */
    var focusDoel by mutableStateOf<String?>(null)
    var laatsteFocus: Asset? = null
    /** Viewer gesloten op deze foto: daarheen scrollen en focus geven. */
    var terugNaar by mutableStateOf<String?>(null)

    suspend fun gaNaar(id: String, rijen: List<Rij>, voorKop: Int = 0) {
        val i = rijen.indexOfFirst { r -> r is RijFotos && r.regel.stukken.any { s -> s.vakken.any { it.asset.id == id } } }
        if (i < 0) return
        val zicht = lijst.layoutInfo.visibleItemsInfo
        val al = zicht.any { it.index == i + voorKop && it.offset >= 0 && it.offset + it.size <= lijst.layoutInfo.viewportEndOffset }
        if (!al) lijst.scrollToItem(i + voorKop, -(lijst.layoutInfo.viewportSize.height * 0.3f).toInt())
        focusDoel = id
    }
}

/** Bron voor de viewer: kan meer zijn dan wat er geladen is (tijdlijn laadt per maand bij). */
interface BeeldBron {
    val aantal: Int
    fun op(i: Int): Asset?
}

class LijstBron(val lijst: List<Asset>) : BeeldBron {
    override val aantal get() = lijst.size
    override fun op(i: Int) = lijst.getOrNull(i)
}

/**
 * Gekozen foto's blijven op ~30 % van de hoogte staan (zoals Android TV-rijen), met een zachte scroll;
 * standaard schuift Compose alleen net genoeg, dan zit je steeds tegen de rand. Geldt voor alle lijsten en rasters
 * (via het thema).
 *
 * De spec is een spring, geen tween: Compose past de spec bij "bring into view" elk frame opnieuw toe op de
 * resterende afstand (UpdatableAnimationState, playTime = één frame). Een tween(260, FastOutSlowIn) kwam daardoor
 * per frame maar ~1 % dichterbij (de easing begint vlak): een kruipende scroll die als een bewuste vertraging voelde.
 * Een spring integreert wél correct per frame (met snelheid) en volgt ingedrukt houden soepel zonder achterstand.
 */
@OptIn(ExperimentalFoundationApi::class)
val focusOp30 = object : BringIntoViewSpec {
    @Deprecated("") override val scrollAnimationSpec: AnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 700f)
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val doel = containerSize * 0.3f
        return if (size > containerSize * 0.7f) offset else offset - doel
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FotoRaster(
    rijen: List<Rij>,
    staat: RasterStaat,
    onKlik: (Asset) -> Unit,
    modifier: Modifier = Modifier,
    bovenAantal: Int = 1,
    onWacht: (String) -> Unit = {},
    boven: LazyListScope.() -> Unit = {},
) {
    androidx.compose.runtime.LaunchedEffect(staat.terugNaar, rijen) {
        val id = staat.terugNaar ?: return@LaunchedEffect
        staat.gaNaar(id, rijen, bovenAantal)
        staat.terugNaar = null
    }
    LazyColumn(
        state = staat.lijst,
        modifier = modifier,
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(TUSSEN.dp),
    ) {
        boven()
        items(rijen, key = { it.sleutel }, contentType = { it::class }) { r ->
            when (r) {
                is RijKop -> MaandKop(r)
                is RijFotos -> FotoRegel(r.regel, staat, onKlik)
                is RijWacht -> WachtVak(r, onWacht)
            }
        }
    }
}

@Composable
private fun MaandKop(r: RijKop) {
    Column(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(r.titel, style = MaterialTheme.typography.headlineSmall)
            r.sub?.let { Text("   $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 3.dp)) }
        }
        // subtiele accentlijn: kort stukje in de hoofdkleur, daarna een haarlijn
        Row(Modifier.padding(top = 5.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(34.dp).height(3.dp).background(Kleur.primair, RoundedCornerShape(2.dp)))
            Box(Modifier.padding(start = 8.dp).weight(1f).height(1.dp).background(Kleur.tekst.copy(alpha = 0.08f)))
        }
    }
}

@Composable
private fun FotoRegel(regel: Regel, staat: RasterStaat, onKlik: (Asset) -> Unit) {
    var focus by remember { mutableStateOf(false) }
    // vergrote focusfoto boven de volgende rij tekenen
    Row(
        Modifier.zIndex(if (focus) 1f else 0f).onFocusChanged { focus = it.hasFocus },
        horizontalArrangement = Arrangement.spacedBy(GROEP_TUSSEN.dp),
    ) {
        for (stuk in regel.stukken) {
            Column {
                if (regel.metKop) Text(
                    stuk.kop.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1,
                    modifier = Modifier.height(KOP_HOOGTE.dp).padding(top = 6.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(TUSSEN.dp)) {
                    for (v in stuk.vakken) {
                        FotoVak(
                            v.asset, v.breedte.dp, regel.hoogte.dp,
                            onKlik = { onKlik(v.asset) },
                            focusDoel = staat.focusDoel == v.asset.id,
                            onFocusDoelKlaar = { staat.focusDoel = null },
                            onFocus = { staat.laatsteFocus = v.asset },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WachtVak(r: RijWacht, onWacht: (String) -> Unit) {
    androidx.compose.runtime.LaunchedEffect(r.maand) { onWacht(r.maand) }
    // zacht ademende plaatshouder (alleen laag-alpha: geen hertekenen)
    val adem = rememberInfiniteTransition(label = "adem")
    val a by adem.animateFloat(0.45f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a")
    Surface(
        onClick = {},
        modifier = Modifier.fillMaxWidth().height(r.hoogte.dp).graphicsLayer { alpha = a }.onFocusChanged { if (it.isFocused) onWacht(r.maand) },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(5.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(containerColor = Kleur.vlak.copy(alpha = 0.5f), focusedContainerColor = Kleur.vlakHoog),
    ) { Box(Modifier) {} }
}

fun breedteVoor(totaal: Float) = totaal - 48f

/** Platte lijst (zoekresultaten, herinnering, plaats) als rijen: per dag, of als één blok op relevantie. */
fun rijenVoor(assets: List<Asset>, breedte: Float, perDag: Boolean, metJaar: Boolean = true, hoogte: Float = RIJ_HOOGTE): List<Rij> {
    val groepen = if (perDag) Opmaak.perDag(assets, metJaar) else listOf(Groep(null, assets))
    return Opmaak.regels("l", groepen, breedte, hoogte, TUSSEN, GROEP_TUSSEN).map { RijFotos(it) }
}
