package com.immichclient.tv

import android.util.Log
import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Mappen: bladeren door de foto's zoals ze op de schijf staan (uploadmap of externe bibliotheek), via de
 * mappenweergave van Immich (`/view/folder/unique-paths` + `/view/folder?path=`, recht folder.read). Elke map is een
 * eigen scherm op de stapel, dus Terug = een map omhoog.
 */
class MapKnoop(var naam: String, val pad: String, var ouder: MapKnoop?) {
    val kinderen = ArrayList<MapKnoop>()
    /** Immich kent deze map als map met eigen foto's/video's */
    var heeftFotos = false

    /** pad vanaf het startpunt van de boom, voor de ondertitel */
    fun relatief(start: MapKnoop): String {
        val delen = ArrayList<String>()
        var k: MapKnoop? = this
        while (k != null && k !== start) { delen += k.naam; k = k.ouder }
        return delen.asReversed().joinToString(" / ")
    }

    companion object {
        /** Boom uit de lijst met paden; geeft het eerste punt waar iets te kiezen valt (gemeenschappelijk begin overgeslagen). */
        fun bouw(paden: List<String>): MapKnoop {
            val wortel = MapKnoop("", "", null)
            val absoluut = paden.any { it.startsWith("/") }
            for (p in paden) {
                var k = wortel
                var pad = if (absoluut) "" else null
                for (deel in p.split('/').filter { it.isNotEmpty() }) {
                    pad = if (pad == null) deel else "$pad/$deel"
                    k = k.kinderen.firstOrNull { it.naam == deel } ?: MapKnoop(deel, pad, k).also { k.kinderen += it }
                }
                if (k !== wortel) k.heeftFotos = true
            }
            val volgorde = java.text.Collator.getInstance(Taal.locale).apply { strength = java.text.Collator.SECONDARY }
            fun sorteer(k: MapKnoop) { k.kinderen.sortWith { a, b -> volgorde.compare(a.naam, b.naam) }; k.kinderen.forEach(::sorteer) }
            sorteer(wortel)
            // "/data/upload/<id>/…" of "/mnt/media/fotos/…": de mappen zonder keuze overslaan
            var start = wortel
            while (start.kinderen.size == 1 && !start.heeftFotos) start = start.kinderen[0]
            // Ketens zonder keuze samenvoegen ("2024 / Camera" in plaats van een map met maar één submap). Bij meer
            // bibliotheken (bijv. /mnt/fotos en /usr/src/app/upload/…) staat in het begin dan per bibliotheek één regel.
            fun voegSamen(k: MapKnoop) {
                for (i in k.kinderen.indices) {
                    var c = k.kinderen[i]
                    while (c.kinderen.size == 1 && !c.heeftFotos) {
                        val g = c.kinderen[0]
                        g.naam = c.naam + " / " + g.naam; g.ouder = k
                        c = g
                    }
                    k.kinderen[i] = c
                    voegSamen(c)
                }
            }
            voegSamen(start)
            // de standaard-uploadmap van Immich (in de container) heet voortaan gewoon "upload / …"
            for (c in start.kinderen) for (pre in listOf("usr / src / app / ", "data / ")) if (c.naam.startsWith(pre + "upload")) c.naam = c.naam.removePrefix(pre)
            return start
        }
    }
}

/** De mappenboom, één keer per sessie geladen en gedeeld door alle mapschermen. */
class MappenBoom(scope: CoroutineScope) {
    var start by mutableStateOf<MapKnoop?>(null)
    var fout by mutableStateOf<String?>(null)
    /** mappen Z–A (bijv. "26-10-17 …" bovenaan bij mappen met een datum ervoor); geldt voor alle mapschermen */
    var omgekeerd by mutableStateOf(false)

    init {
        scope.launch {
            runCatching { MapKnoop.bouw(Immich.mappen()) }
                .onSuccess { start = it }
                .onFailure { fout = it.message ?: it.toString(); Log.w("Fotos", "mappen", it) }
        }
    }
}

class MapStaat(val boom: MappenBoom, val knoop: MapKnoop?, scope: CoroutineScope) {
    var fotos by mutableStateOf<List<Asset>?>(null)
    var fout by mutableStateOf<String?>(null)
    val grid = LazyGridState()
    /** index (mappen eerst, dan foto's) die focus moet krijgen zodra hij in beeld is; -1 = geen */
    var focusDoel by mutableIntStateOf(0)
    var laatsteFocus = 0
    private var geladen: MapKnoop? = null

    init { scope.launch { laad() } }

    /** wortelscherm: de boom komt later binnen; dan pas weten we welke map dit is */
    val huidig: MapKnoop? get() = knoop ?: boom.start

    suspend fun laad() {
        val k = huidig ?: return
        if (geladen === k) return
        geladen = k
        if (!k.heeftFotos) { fotos = emptyList(); return }
        runCatching { Immich.mapInhoud(k.pad) }.onSuccess { fotos = it }.onFailure { fout = it.message; fotos = emptyList(); Log.w("Fotos", "map ${k.pad}", it) }
    }
}

private const val KOLOMMEN = 6

@Composable
fun MappenScherm(scherm: Scherm.Mappen, nav: Nav) {
    val staat = scherm.staat
    val boom = staat.boom
    val k = staat.huidig
    LaunchedEffect(k) { staat.laad() }

    if (k == null) {
        val f = boom.fout
        if (f == null) Melding(stringResource(R.string.laden), null, Icons.Filled.Folder)
        else if (f.contains("folder.read")) Box(Modifier.fillMaxSize()) {
            Melding(stringResource(R.string.mappen_geen_recht), stringResource(R.string.mappen_geen_recht_uitleg), Icons.Filled.Folder)
            // knop onder de melding (die staat gecentreerd)
            val fr = remember { FocusRequester() }
            Knop(stringResource(R.string.rechten_titel), stringResource(R.string.rechten_bekijk), Modifier.align(Alignment.Center).offset(y = 140.dp).width(320.dp).focusRequester(fr)) { Rechten.open = true }
            LaunchedEffect(Unit) { delay(50); runCatching { fr.requestFocus() } }
        }
        else Melding(stringResource(R.string.mappen_niet_geladen), f, Icons.Filled.Folder)
        return
    }
    val mappen = remember(k, boom.omgekeerd) { if (boom.omgekeerd) k.kinderen.asReversed().toList() else k.kinderen.toList() }
    val fotos = staat.fotos.orEmpty()
    val titel = if (k === boom.start) stringResource(R.string.sectie_mappen) else k.naam
    fun bekijk(i: Int, dia: Boolean) {
        if (fotos.isEmpty()) return
        nav.bekijk(LijstBron(fotos), i.coerceIn(0, fotos.lastIndex), titel, dia, null) { staat.focusDoel = staat.laatsteFocus }
    }

    Box(Modifier.fillMaxSize()) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(KOLOMMEN),
        state = staat.grid,
        contentPadding = PaddingValues(start = 24.dp, end = 40.dp, top = 20.dp, bottom = 80.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key.nativeKeyCode) {
                // ⏯ = diashow van deze map, vanaf de gekozen foto (of de eerste)
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> { bekijk(staat.laatsteFocus - mappen.size, true); fotos.isNotEmpty() }
                else -> false
            }
        },
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "kop") {
            val delen = listOfNotNull(
                mappen.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.mappen_aantal, it, it) },
                fotos.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.items, it, it) },
            )
            val pad = boom.start?.let { k.relatief(it) }.orEmpty()
            SchermKop(titel, listOfNotNull(pad.takeIf { it.isNotEmpty() && it != titel }, delen.joinToString("  ·  ").ifEmpty { null }).joinToString("   —   ").ifEmpty { null }) {
                if (mappen.size > 1) Keuze(
                    selected = false, onClick = { boom.omgekeerd = !boom.omgekeerd; staat.focusDoel = 0 },
                    tekst = if (boom.omgekeerd) "Z–A" else "A–Z",
                )
                if (fotos.isNotEmpty()) Keuze(
                    selected = false, onClick = { bekijk(0, true) },
                    leadingIcon = { Icon(Icons.Filled.PlayArrow, null, Modifier.width(18.dp)) },
                    tekst = stringResource(R.string.diashow),
                )
            }
        }
        items(mappen.size, key = { "m:" + mappen[it].pad }) { i ->
            val m = mappen[i]
            MapVak(m, focusDoel = staat.focusDoel == i, onFocusDoelKlaar = { staat.focusDoel = -1 }, onFocus = { staat.laatsteFocus = i }) {
                nav.open(Scherm.Mappen(staat.boom, m, nav.scope))
            }
        }
        items(fotos.size, key = { "f:" + fotos[it].id }) { i ->
            val idx = mappen.size + i
            IdVak(
                fotos[i].id, focusDoel = staat.focusDoel == idx, onFocusDoelKlaar = { staat.focusDoel = -1 },
                onFocus = { staat.laatsteFocus = idx },
            ) { bekijk(i, false) }
        }
        if (staat.fotos == null && k.heeftFotos) item(span = { GridItemSpan(maxLineSpan) }) {
            Text(stringResource(R.string.laden), style = MaterialTheme.typography.bodyLarge, color = Kleur.zacht, modifier = Modifier.padding(top = 24.dp))
        }
        if (staat.fotos != null && mappen.isEmpty() && fotos.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Melding(stringResource(if (staat.fout != null) R.string.mappen_niet_geladen else R.string.map_leeg), staat.fout, Icons.Filled.Folder)
        }
    }
    val n = mappen.size + fotos.size
    if (n > 0) ScrolBalk(
        fractie = { fractieVan(staat.grid, 1) },
        beweging = { staat.grid.firstVisibleItemIndex * 7919 + staat.grid.firstVisibleItemScrollOffset / 200 },
        scrolbaar = { staat.grid.canScrollForward || staat.grid.canScrollBackward },
        label = { mappen.getOrNull((staat.grid.firstVisibleItemIndex - 1).coerceAtLeast(0))?.naam },
        sub = { "${Taal.getal((staat.grid.firstVisibleItemIndex - 1).coerceIn(0, n - 1) + 1)} / ${Taal.getal(n)}" },
        modifier = Modifier.align(Alignment.CenterEnd),
    )
    }
    // terug op dit scherm (of de eerste keer): focus op het laatst gekozen vak
    LaunchedEffect(Unit) { delay(30); if (staat.focusDoel < 0) staat.focusDoel = staat.laatsteFocus }
}

/** Map: groot mapicoon met de naam eronder (focus: iets groter, witte rand, gloed in de accentkleur). */
@Composable
private fun MapVak(m: MapKnoop, focusDoel: Boolean, onFocusDoelKlaar: () -> Unit, onFocus: () -> Unit, onKlik: () -> Unit) {
    val fr = remember { FocusRequester() }
    if (focusDoel) LaunchedEffect(m.pad) {
        repeat(5) { if (runCatching { fr.requestFocus() }.isSuccess) { onFocusDoelKlaar(); return@LaunchedEffect }; delay(16) }
        onFocusDoelKlaar()
    }
    var focus by remember { mutableStateOf(false) }
    val schaal by animateFloatAsState(if (focus) 1.06f else 1f, tween(140), label = "schaal")
    val vorm = RoundedCornerShape(10.dp)
    Column(
        Modifier.aspectRatio(1f)
            .graphicsLayer {
                scaleX = schaal; scaleY = schaal; shape = vorm; clip = true
                shadowElevation = if (focus) 16.dp.toPx() else 0f
                spotShadowColor = Kleur.primair; ambientShadowColor = Kleur.primair
            }
            .background(if (focus) Kleur.primair else Kleur.vlak)
            .then(if (focus) Modifier.border(3.dp, Color.White, vorm) else Modifier)
            .focusRequester(fr)
            .onFocusChanged { focus = it.isFocused; if (it.isFocused) onFocus() }
            .clickable(interactionSource = null, indication = null, onClick = onKlik)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Folder, null, Modifier.size(40.dp), tint = if (focus) Kleur.opPrimair else Kleur.primair)
        Text(
            m.naam, style = MaterialTheme.typography.titleSmall, color = if (focus) Kleur.opPrimair else Kleur.tekst,
            maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
}
