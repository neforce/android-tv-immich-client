package com.immichclient.tv

import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Alle foto's van een kaartcluster (lang OK op de kaart) als raster: nieuwste eerst (de volgorde van /map/markers),
 * met bedienbare scrollbalk. OK = viewer vanaf die foto, ⏯ (of de knop Diashow) = diashow. Van de foto's zijn alleen
 * de id's bekend; datum e.d. haalt [KaartBron] pas op als een foto (of het balklabel) erom vraagt.
 */
class KaartFotosStaat(val ids: List<String>, scope: CoroutineScope) {
    val bron = KaartBron(ids, scope)
    val grid = LazyGridState()
    /** index die focus moet krijgen zodra hij in beeld is (-1 = geen) */
    var focusDoel by mutableIntStateOf(0)
    var laatsteFocus = 0
}

private const val KOLOMMEN = 6

@Composable
fun KaartFotosScherm(scherm: Scherm.KaartFotos, nav: Nav) {
    val staat = scherm.staat
    val scope = rememberCoroutineScope()
    val n = staat.ids.size
    fun bekijk(i: Int, dia: Boolean) = nav.bekijk(staat.bron, i, scherm.titel, dia, null) { staat.focusDoel = staat.laatsteFocus }
    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key.nativeKeyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> { bekijk(staat.laatsteFocus, true); true }
                else -> false
            }
        },
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(KOLOMMEN),
            state = staat.grid,
            contentPadding = PaddingValues(start = 24.dp, end = 40.dp, top = 20.dp, bottom = 80.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "kop") {
                SchermKop(scherm.titel, pluralStringResource(R.plurals.fotos, n, Taal.getal(n))) {
                    Keuze(
                        selected = false, onClick = { bekijk(0, true) },
                        leadingIcon = { Icon(Icons.Filled.PlayArrow, null, Modifier.width(18.dp)) },
                        tekst = stringResource(R.string.diashow),
                    )
                }
            }
            items(n, key = { staat.ids[it] }) { i ->
                IdVak(
                    staat.ids[i], focusDoel = staat.focusDoel == i, onFocusDoelKlaar = { staat.focusDoel = -1 },
                    onFocus = { staat.laatsteFocus = i },
                ) { bekijk(i, false) }
            }
        }
        // scrollbalk: datum van de eerste foto in beeld (zodra bekend) + "N / M"; bedienbaar in stappen van ~5 %
        fun eersteNu() = (staat.grid.firstVisibleItemIndex - 1).coerceIn(0, (n - 1).coerceAtLeast(0))
        fun datum(i: Int) = staat.bron.op(i)?.let { maandTitel(it.datum.toLocalDate().toString()) }
        val stappen = remember(n) {
            val ankers = (0 until 20).map { it * n / 20 / KOLOMMEN * KOLOMMEN }.distinct()
            BalkStappen(
                aantal = ankers.size,
                fractie = { i -> ankers[i].toFloat() / (n - 1).coerceAtLeast(1) },
                label = { i -> datum(ankers[i]) },
                sub = { i -> "${Taal.getal(ankers[i] + 1)} / ${Taal.getal(n)}" },
                groot = grootPer(5, ankers.size),
                huidige = { ankers.indexOfLast { it <= eersteNu() }.coerceAtLeast(0) },
                ga = { i -> scope.launch { staat.grid.scrollToItem(ankers[i] + 1); staat.focusDoel = ankers[i] } },
                terug = { staat.focusDoel = staat.laatsteFocus },
            )
        }
        if (n > 0) ScrolBalk(
            fractie = { fractieVan(staat.grid, 1) },
            beweging = { staat.grid.firstVisibleItemIndex * 7919 + staat.grid.firstVisibleItemScrollOffset / 200 },
            scrolbaar = { staat.grid.canScrollForward || staat.grid.canScrollBackward },
            label = { datum(eersteNu()) },
            sub = { "${Taal.getal(eersteNu() + 1)} / ${Taal.getal(n)}" },
            modifier = Modifier.align(Alignment.CenterEnd),
            stappen = stappen,
        )
    }
    // terug op dit scherm (of de eerste keer): focus op de laatst gekozen foto
    LaunchedEffect(Unit) { delay(30); if (staat.focusDoel < 0) staat.focusDoel = staat.laatsteFocus }
}

/** Vierkante thumbnail van alleen een id (focus: iets groter, witte rand, gloed in de accentkleur). */
@Composable
private fun IdVak(id: String, focusDoel: Boolean, onFocusDoelKlaar: () -> Unit, onFocus: () -> Unit, onKlik: () -> Unit) {
    val fr = remember { FocusRequester() }
    if (focusDoel) LaunchedEffect(id) {
        repeat(5) { if (runCatching { fr.requestFocus() }.isSuccess) { onFocusDoelKlaar(); return@LaunchedEffect }; delay(16) }
        onFocusDoelKlaar()
    }
    var focus by remember { mutableStateOf(false) }
    val schaal by animateFloatAsState(if (focus) 1.06f else 1f, tween(140), label = "schaal")
    val vorm = RoundedCornerShape(6.dp)
    Box(
        Modifier.aspectRatio(1f)
            .graphicsLayer {
                scaleX = schaal; scaleY = schaal; shape = vorm; clip = true
                shadowElevation = if (focus) 16.dp.toPx() else 0f
                spotShadowColor = Kleur.primair; ambientShadowColor = Kleur.primair
            }
            .background(Kleur.vlak)
            .focusRequester(fr)
            .onFocusChanged { focus = it.isFocused; if (it.isFocused) onFocus() }
            .clickable(interactionSource = null, indication = null, onClick = onKlik),
    ) {
        AsyncImage(beeld(Immich.thumb(id)), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (focus) Box(Modifier.matchParentSize().border(3.dp, Color.White, vorm))
    }
}
