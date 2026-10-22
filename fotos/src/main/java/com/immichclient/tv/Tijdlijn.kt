package com.immichclient.tv

import android.util.Log
import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Tijdlijn per maand: maandenlijst meteen, inhoud van een maand pas als hij (bijna) in beeld komt. */
class TijdlijnStaat(val filter: Filter, nieuwsteEerst: Boolean, private val scope: CoroutineScope) : BeeldBron {
    var nieuwsteEerst by mutableStateOf(nieuwsteEerst)
        private set
    var maanden by mutableStateOf<List<Maand>>(emptyList())
        private set
    var fout by mutableStateOf<String?>(null)
    var bezig by mutableStateOf(true)
    val geladen = mutableStateMapOf<String, List<Asset>>()
    private val onderweg = HashSet<String>()
    private var begin = IntArray(0)
    private var totaal = 0
    val raster = RasterStaat()
    private val regelCache = HashMap<String, List<Regel>>()
    /** per maand de kant-en-klare rijen (kop + foto's), zodat een nieuw geladen maand niet alles opnieuw maakt */
    private val rijCache = HashMap<String, List<Rij>>()

    /** met welke partner-instelling de lijst geladen is (instelling veranderd → opnieuw laden) */
    private var metPartners = Instellingen.partners

    init { start() }

    private fun start() {
        bezig = true
        metPartners = Instellingen.partners
        scope.launch {
            runCatching { Immich.maanden(filter, nieuwsteEerst) }
                .onSuccess { maanden = it; telOp(); fout = null }
                .onFailure { fout = it.message; Log.w("Fotos", "maanden", it) }
            bezig = false
        }
    }

    fun draaiVolgorde() = zetVolgorde(!nieuwsteEerst)

    fun zetVolgorde(nieuwste: Boolean) {
        nieuwsteEerst = nieuwste
        herlaad()
    }

    /** Alles opnieuw ophalen (volgorde of partner-instelling veranderd). */
    fun herlaad() {
        geladen.clear(); regelCache.clear(); rijCache.clear(); onderweg.clear(); maanden = emptyList()
        raster.laatsteFocus = null
        scope.launch { raster.lijst.scrollToItem(0) }
        start()
    }

    /** Instellingen toepassen op een al bestaande tijdlijn (hoofdtijdlijn/favorieten): alleen als er iets verschilt. */
    fun pasInstellingenToe() {
        if (filter.albumId == null && filter.persoonId == null && nieuwsteEerst != Instellingen.nieuwsteEerst) { zetVolgorde(Instellingen.nieuwsteEerst); return }
        if (filter.albumId == null && filter.persoonId == null && !filter.favorieten && metPartners != Instellingen.partners) herlaad()
    }

    private fun telOp() {
        begin = IntArray(maanden.size)
        var s = 0
        maanden.forEachIndexed { i, m -> begin[i] = s; s += m.aantal }
        totaal = s
    }

    fun laad(m: String) {
        if (geladen.containsKey(m) || !onderweg.add(m)) return
        scope.launch {
            runCatching { Immich.maand(filter, nieuwsteEerst, m) }
                .onSuccess { a ->
                    // thumbhashes alvast op de achtergrond, dan hoeft het scrollen dat niet te doen
                    withContext(Dispatchers.Default) { a.take(600).forEach { x -> x.thumbhash?.let(ThumbHash::bitmap) } }
                    val i = maanden.indexOfFirst { it.sleutel == m }
                    if (i >= 0 && maanden[i].aantal != a.size) {
                        maanden = maanden.toMutableList().also { l -> l[i] = Maand(m, a.size) }; telOp()
                    }
                    geladen[m] = a
                }
                .onFailure { Log.w("Fotos", "maand $m", it) }
            onderweg.remove(m)
        }
    }

    fun regels(m: Maand, breedte: Float, hoogte: Float): List<Regel>? {
        val a = geladen[m.sleutel] ?: return null
        return regelCache.getOrPut("${m.sleutel}|$breedte|$hoogte") {
            Opmaak.regels(m.sleutel, Opmaak.perDag(a, metJaar = false), breedte, hoogte, TUSSEN, GROEP_TUSSEN)
        }
    }

    /** Rijen voor alle maanden; [hoogte] = rijhoogte (instelling klein/normaal/groot). */
    fun rijen(breedte: Float, hoogte: Float): Pair<List<Rij>, IntArray> {
        val uit = ArrayList<Rij>()
        val maandVan = ArrayList<Int>()
        maanden.forEachIndexed { i, m ->
            val geladenAl = geladen.containsKey(m.sleutel)
            val r = rijCache.getOrPut("${m.sleutel}|$breedte|$hoogte|$geladenAl|${m.aantal}") {
                val kop = RijKop("k-${m.sleutel}", maandTitel(m.sleutel), Taal.meervoud(R.plurals.items, m.aantal, m.aantal))
                val regels = regels(m, breedte, hoogte)
                if (regels == null) listOf(kop, RijWacht("w-${m.sleutel}", m.sleutel, Opmaak.schatHoogte(m.aantal, breedte, hoogte, KOP_HOOGTE)))
                else listOf(kop) + regels.map { RijFotos(it) }
            }
            uit.addAll(r)
            repeat(r.size) { maandVan.add(i) }
        }
        return uit to maandVan.toIntArray()
    }

    override val aantal get() = totaal
    override fun op(i: Int): Asset? {
        if (i < 0 || i >= totaal) return null
        var lo = 0; var hi = begin.size - 1
        while (lo < hi) { val mid = (lo + hi + 1) / 2; if (begin[mid] <= i) lo = mid else hi = mid - 1 }
        val m = maanden[lo]
        val l = geladen[m.sleutel] ?: run { laad(m.sleutel); return null }
        // buurmaanden alvast, dan loopt de viewer zonder haperen door
        maanden.getOrNull(lo + 1)?.let { if (i - begin[lo] > m.aantal - 6) laad(it.sleutel) }
        return l.getOrNull(i - begin[lo])
    }

    fun indexVan(a: Asset): Int {
        maanden.forEachIndexed { i, m -> geladen[m.sleutel]?.indexOfFirst { it.id == a.id }?.takeIf { it >= 0 }?.let { return begin[i] + it } }
        return -1
    }

    fun beginVan(maandIndex: Int) = begin.getOrElse(maandIndex) { 0 }
}

@Composable
fun TijdlijnScherm(scherm: Scherm.Tijdlijn, nav: Nav) {
    val staat = scherm.staat
    val scope = rememberCoroutineScope()
    // hoofdtijdlijn heet zoals de app (instelling Naam en icoon), ook als hij al bestond toen die veranderde
    val titel = if (scherm.metHerinneringen) stringResource(Instellingen.uiterlijk.label) else scherm.titel
    var kiezer by remember { mutableStateOf(false) }
    var sprongLabel by remember { mutableStateOf<String?>(null) }
    val eerste = remember { FocusRequester() }
    val datumKnop = remember { FocusRequester() }
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(kiezer) { if (wasOpen && !kiezer && staat.raster.focusDoel == null) { delay(16); runCatching { datumKnop.requestFocus() } }; wasOpen = kiezer }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val breedte = breedteVoor(maxWidth.value)
        // rijhoogte = instelling; alleen hier gelezen, zodat een wijziging één keer alle rijen opnieuw opmaakt
        val rijHoogte = Instellingen.rijMaat.hoogte
        val (rijen, maandVan) = remember(staat.maanden, staat.geladen.size, breedte, rijHoogte) { staat.rijen(breedte, rijHoogte) }
        val boven = 1 // kop-item (titel + knoppen + herinneringen)

        // laad wat (bijna) in beeld is
        LaunchedEffect(rijen) {
            snapshotFlow { staat.raster.lijst.layoutInfo.visibleItemsInfo.map { it.index } }
                .distinctUntilChanged()
                .collectLatest { zicht ->
                    if (zicht.isEmpty()) return@collectLatest
                    val van = (zicht.first() - boven).coerceAtLeast(0)
                    val tot = (zicht.last() - boven).coerceAtMost(maandVan.size - 1)
                    if (tot < 0) { staat.maanden.firstOrNull()?.let { staat.laad(it.sleutel) }; return@collectLatest }
                    val mVan = maandVan.getOrElse(van) { 0 }
                    val mTot = maandVan.getOrElse(tot) { 0 }
                    for (mi in (mVan - 1)..(mTot + 2)) staat.maanden.getOrNull(mi)?.let { staat.laad(it.sleutel) }
                }
        }
        // eerste keer: focus op de eerste foto; terug uit een andere sectie: op de laatst gekozen foto (anders gaat de lade open)
        LaunchedEffect(staat.maanden.isNotEmpty(), staat.geladen.isNotEmpty()) {
            // even wachten: anders pakt Android bij het openen van het venster alsnog het eerste item van de lade
            delay(400)
            if (staat.raster.focusDoel == null) {
                val laatste = staat.raster.laatsteFocus
                if (laatste != null) { staat.raster.focusDoel = laatste.id; return@LaunchedEffect }
                val m = staat.maanden.firstOrNull() ?: return@LaunchedEffect
                staat.geladen[m.sleutel]?.firstOrNull()?.let { staat.raster.focusDoel = it.id }
            }
        }

        // altijd de actuele rijen (spring wordt ook bewaard door de scrollbalk; een oude lijst gaf de verkeerde rij)
        val rijenNu = rememberUpdatedState(rijen)
        fun spring(maandIndex: Int) {
            val m = staat.maanden.getOrNull(maandIndex) ?: return
            sprongLabel = maandTitel(m.sleutel)
            staat.laad(m.sleutel)
            scope.launch {
                val i = rijenNu.value.indexOfFirst { it.sleutel == "k-${m.sleutel}" }
                Log.i("Fotos", "spring naar ${m.sleutel}: rij $i")
                if (i >= 0) staat.raster.lijst.scrollToItem(i + boven)
                // wacht tot de maand er is en geef de eerste foto focus
                repeat(100) {
                    staat.geladen[m.sleutel]?.firstOrNull()?.let { staat.raster.focusDoel = it.id; return@launch }
                    delay(30)
                }
            }
        }
        val huidigeMaand by remember(maandVan) {
            derivedStateOf { maandVan.getOrElse((staat.raster.lijst.firstVisibleItemIndex - boven + 1).coerceAtLeast(0)) { 0 } }
        }

        Box(
            Modifier.fillMaxSize().onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key.nativeKeyCode) {
                    // ⏩ / ⏪ op de afstandsbediening: maand verder / terug
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { spring(huidigeMaand + 1); true }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> { spring((huidigeMaand - 1).coerceAtLeast(0)); true }
                    // ▶⏸: diashow vanaf de gekozen foto
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> {
                        val a = staat.raster.laatsteFocus ?: staat.geladen[staat.maanden.firstOrNull()?.sleutel ?: ""]?.firstOrNull()
                        a?.let { x -> staat.indexVan(x).takeIf { it >= 0 }?.let { nav.bekijk(staat, it, titel, true, staat.raster) } }
                        true
                    }
                    else -> false
                }
            },
        ) {
            when {
                staat.fout != null && staat.maanden.isEmpty() -> Melding(stringResource(R.string.immich_onbereikbaar), staat.fout, Icons.Filled.CloudOff)
                !staat.bezig && staat.maanden.isEmpty() -> Melding(
                    stringResource(if (scherm.staat.filter.favorieten) R.string.nog_geen_favorieten else R.string.nog_geen_fotos),
                    if (scherm.staat.filter.favorieten) stringResource(R.string.favorieten_tip) else null,
                    if (scherm.staat.filter.favorieten) Icons.Filled.FavoriteBorder else Icons.Filled.PhotoLibrary,
                )
            }
            FotoRaster(
                rijen = rijen,
                staat = staat.raster,
                onKlik = { a -> staat.indexVan(a).takeIf { it >= 0 }?.let { nav.bekijk(staat, it, titel, false, staat.raster) } },
                onWacht = staat::laad,
                bovenAantal = boven,
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "boven", contentType = "boven") {
                    Column {
                        // begroeting (instelling) boven de hoofdtijdlijn: automatisch ("Goedemiddag, <voornaam>") of eigen tekst
                        val groet = if (scherm.metHerinneringen) groetTekst() else null
                        SchermKop(groet ?: titel, if (groet != null) titel else scherm.sub) {
                            Keuze(
                                selected = false, onClick = { staat.draaiVolgorde() }, modifier = Modifier.focusRequester(eerste),
                                leadingIcon = { Icon(Icons.Filled.SwapVert, null, Modifier.width(18.dp)) },
                            tekst = stringResource(if (staat.nieuwsteEerst) R.string.nieuwste_eerst else R.string.oudste_eerst))
                            Keuze(
                                selected = false, onClick = { kiezer = true }, modifier = Modifier.focusRequester(datumKnop),
                                leadingIcon = { Icon(Icons.Filled.CalendarMonth, null, Modifier.width(18.dp)) },
                            tekst = stringResource(R.string.ga_naar_datum))
                        }
                        if (scherm.metHerinneringen && Instellingen.herinneringen) HerinneringenRij(nav)
                    }
                }
            }
            TijdlijnBalk(staat, rijen, maandVan, boven, huidigeMaand, Modifier.align(Alignment.CenterEnd), ga = ::spring) {
                // niets gekozen op de balk: focus terug op de foto waar hij was (of de eerste van de maand in beeld)
                val terug = staat.raster.laatsteFocus?.id ?: staat.maanden.getOrNull(huidigeMaand)?.let { staat.geladen[it.sleutel]?.firstOrNull()?.id }
                staat.raster.focusDoel = terug
            }
            AnimatedVisibility(sprongLabel != null, Modifier.align(Alignment.Center), enter = fadeIn(), exit = fadeOut()) {
                Text(
                    sprongLabel.orEmpty(), style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.background(Color(0xCC000000), RoundedCornerShape(16.dp)).padding(horizontal = 28.dp, vertical = 14.dp),
                )
            }
            LaunchedEffect(sprongLabel) { if (sprongLabel != null) { delay(900); sprongLabel = null } }
        }
        if (kiezer) DatumKiezer(staat, sluit = { kiezer = false }, kies = { kiezer = false; spring(it) })
    }
}

/** Begroeting volgens de instelling; de naam komt uit /users/me (eerste woord), het dagdeel uit de klok. */
@Composable
fun groetTekst(): String? {
    val g = Instellingen.groet
    if (g == Groet.UIT) return null
    if (g == Groet.EIGEN) return Instellingen.groetTekst.trim().ifEmpty { null }
    LaunchedEffect(Immich.stand) { Immich.laadIk() }
    val naam = Immich.ik?.naam?.trim()?.substringBefore(' ')?.ifEmpty { null }
    val uur = java.time.LocalTime.now().hour
    val dagdeel = stringResource(when (uur) { in 6..11 -> R.string.groet_morgen; in 12..17 -> R.string.groet_middag; in 18..23 -> R.string.groet_avond; else -> R.string.groet_nacht })
    return if (naam != null) stringResource(R.string.groet_met_naam, dagdeel, naam) else dagdeel
}

/**
 * Scrollbalk van de tijdlijn: plek op basis van de maandaantallen (maanden worden lui geladen, dus de rijen zeggen niets
 * over het geheel): eerste zichtbare rij → maand → aandeel binnen die maand → fotonummer in het totaal.
 */
@Composable
private fun TijdlijnBalk(staat: TijdlijnStaat, rijen: List<Rij>, maandVan: IntArray, boven: Int, maandIndex: Int, modifier: Modifier, ga: (Int) -> Unit, terug: () -> Unit) {
    val lijst = staat.raster.lijst
    val maanden = staat.maanden
    if (staat.aantal == 0 || maanden.isEmpty()) return
    // eerste rij-index per maand
    val maandStart = remember(maandVan) {
        val uit = IntArray(maanden.size) { maandVan.size }
        for (r in maandVan.indices.reversed()) uit[maandVan[r]] = r
        for (m in uit.indices.reversed()) if (uit[m] == maandVan.size && m + 1 < uit.size) uit[m] = uit[m + 1]
        uit
    }
    fun fotoNummer(): Int {
        val zicht = lijst.layoutInfo.visibleItemsInfo
        val eerste = zicht.firstOrNull() ?: return 0
        if (!lijst.canScrollForward) return staat.aantal - 1
        val deel = if (eerste.size > 0) (-eerste.offset.toFloat() / eerste.size).coerceIn(0f, 1f) else 0f
        val r = (eerste.index - boven + deel).coerceAtLeast(0f)
        val m = maandVan.getOrElse(r.toInt()) { 0 }.coerceIn(0, maanden.lastIndex)
        val start = maandStart[m]
        val eind = if (m + 1 < maandStart.size) maandStart[m + 1] else maandVan.size
        val binnen = if (eind > start) ((r - start) / (eind - start)).coerceIn(0f, 1f) else 0f
        return (staat.beginVan(m) + binnen * maanden[m].aantal).toInt().coerceIn(0, staat.aantal - 1)
    }
    // jaartallen langs de baan (nieuwste eerst of oudste eerst: de volgorde van de maanden)
    val jaren = remember(maanden) {
        val uit = ArrayList<Pair<Float, String>>()
        var vorig = ""
        maanden.forEachIndexed { i, m ->
            val j = m.sleutel.take(4)
            if (j != vorig) { vorig = j; if (j.toIntOrNull()?.let { it > 1970 } == true) uit.add(staat.beginVan(i).toFloat() / staat.aantal to j) }
        }
        uit
    }
    // bediening met de afstandsbediening: een stap = een maand, ingedrukt houden = per jaar
    val huidig = rememberUpdatedState(maandIndex)
    val gaNu = rememberUpdatedState(ga); val terugNu = rememberUpdatedState(terug)
    val stappen = remember(maanden) {
        fun jaar(i: Int) = maanden[i].sleutel.take(4)
        BalkStappen(
            aantal = maanden.size,
            fractie = { i -> staat.beginVan(i).toFloat() / (staat.aantal - 1).coerceAtLeast(1) },
            label = { i -> maandTitel(maanden[i].sleutel) },
            sub = { i -> Taal.meervoud(R.plurals.items, maanden[i].aantal, maanden[i].aantal) },
            groot = { i, r ->
                // naar de eerste maand (in lijstvolgorde) van het vorige/volgende jaar
                var j = i
                if (r > 0) { while (j < maanden.lastIndex && jaar(j) == jaar(i)) j++ }
                else { while (j > 0 && jaar(j) == jaar(i)) j--; while (j > 0 && jaar(j - 1) == jaar(j)) j-- }
                j
            },
            huidige = { huidig.value },
            ga = { gaNu.value(it) },
            terug = { terugNu.value() },
            perMaand = true,
        )
    }
    ScrolBalk(
        fractie = { fotoNummer().toFloat() / (staat.aantal - 1).coerceAtLeast(1) },
        beweging = { lijst.firstVisibleItemIndex * 7919 + lijst.firstVisibleItemScrollOffset / 200 },
        scrolbaar = { lijst.canScrollForward || lijst.canScrollBackward },
        label = { maanden.getOrNull(maandIndex)?.let { maandTitel(it.sleutel) } },
        sub = { "${Taal.getal(fotoNummer() + 1)} / ${Taal.getal(staat.aantal)}" },
        markeringen = jaren,
        modifier = modifier,
        stappen = stappen,
    )
}

/** Jaar kiezen, daarna maand. */
@Composable
private fun DatumKiezer(staat: TijdlijnStaat, sluit: () -> Unit, kies: (Int) -> Unit) {
    var jaar by remember { mutableStateOf<Int?>(null) }
    val perJaar = remember(staat.maanden) {
        staat.maanden.withIndex().groupBy { it.value.sleutel.take(4).toIntOrNull() ?: 0 }
    }
    androidx.activity.compose.BackHandler { if (jaar != null) jaar = null else sluit() }
    val fr = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(Color(0xF00A0A0A)).focusVangen().padding(48.dp)) {
        Column {
            Text(jaar?.toString() ?: stringResource(R.string.ga_naar_jaar), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(20.dp))
            val j = jaar
            if (j == null) {
                LazyVerticalGrid(GridCells.Fixed(8), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(perJaar.keys.toList(), key = { it }) { y ->
                        val n = perJaar[y].orEmpty().sumOf { it.value.aantal }
                        Knop(if (y <= 1970) stringResource(R.string.geen_datum) else "$y", Taal.getal(n), if (y == perJaar.keys.first()) Modifier.focusRequester(fr) else Modifier) {
                            if (perJaar[y].orEmpty().size == 1) kies(perJaar[y]!!.first().index) else jaar = y
                        }
                    }
                }
            } else {
                LazyVerticalGrid(GridCells.Fixed(6), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(perJaar[j].orEmpty(), key = { it.value.sleutel }) { (i, m) ->
                        Knop(maandTitel(m.sleutel).substringBefore(' '), Taal.getal(m.aantal), if (i == perJaar[j]!!.first().index) Modifier.focusRequester(fr) else Modifier) { kies(i) }
                    }
                }
            }
        }
    }
    LaunchedEffect(jaar) { delay(50); runCatching { fr.requestFocus() } }
}

@Composable
fun Knop(titel: String, sub: String?, modifier: Modifier = Modifier, onKlik: () -> Unit) {
    // tekstkleur zelf zetten: de kleur uit de typografie wint anders van de contentkleur (lichte tekst op lichtblauw)
    var focus by remember { mutableStateOf(false) }
    Surface(
        onClick = onKlik, modifier = modifier.height(64.dp).onFocusChanged { focus = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Kleur.vlak, focusedContainerColor = Kleur.primair, focusedContentColor = Kleur.opPrimair),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.Center) {
            Text(titel, style = MaterialTheme.typography.titleMedium, color = if (focus) Kleur.opPrimair else Kleur.tekst, maxLines = 1)
            sub?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = if (focus) Kleur.opPrimair.copy(alpha = 0.8f) else Kleur.zacht, maxLines = 1) }
        }
    }
}

@Composable
fun SchermKop(titel: String, sub: String?, knoppen: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(titel, style = MaterialTheme.typography.displaySmall, maxLines = 1)
            sub?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Kleur.zacht, maxLines = 1) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { knoppen() }
    }
}

/** Lege staat / foutmelding, gecentreerd met een groot zacht icoon. */
@Composable
fun Melding(titel: String, tekst: String?, icoon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.PhotoLibrary) {
    Column(Modifier.fillMaxSize().padding(64.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.padding(bottom = 18.dp).background(Kleur.vlak, androidx.compose.foundation.shape.CircleShape).padding(22.dp)) {
            Icon(icoon, null, Modifier.width(40.dp).height(40.dp), tint = Kleur.zacht)
        }
        Text(titel, style = MaterialTheme.typography.headlineSmall)
        tekst?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Kleur.zacht, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 6.dp)) }
    }
}

/** "Op deze dag": kaarten bovenaan de tijdlijn, zoals in Immich. */
@Composable
fun HerinneringenRij(nav: Nav) {
    val lijst = Herinneringen.vandaag
    LaunchedEffect(Unit) { Herinneringen.laadVandaag() }
    if (lijst.isEmpty()) return
    // iets naar links + binnenmarge: anders valt de vergrote focusrand van de eerste kaart buiten de rij
    LazyRow(
        Modifier.fillMaxWidth().offset(x = (-10).dp).padding(top = 2.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(lijst, key = { it.id }) { h -> HerinneringKaart(h, nav) }
    }
}

@Composable
fun HerinneringKaart(h: Herinnering, nav: Nav, breedte: Float = 168f) {
    val omslag = h.assets[h.assets.size / 2]
    Card(
        onClick = { nav.bekijk(LijstBron(h.assets), 0, jarenGeleden(h.jarenGeleden), true, null) },
        modifier = Modifier.width(breedte.dp).height((breedte * 1.25f).dp),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
        scale = CardDefaults.scale(focusedScale = 1.05f),
    ) {
        Box(Modifier.fillMaxSize().background(Kleur.vlakHoog)) {
            AsyncImage(kaartBeeld(omslag.id), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(onderVerloop))
            Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                Text("${h.jaar}", style = MaterialTheme.typography.displaySmall, color = Color.White)
                Text(jarenGeleden(h.jarenGeleden), style = MaterialTheme.typography.labelLarge, color = Color.White)
                Text(pluralStringResource(R.plurals.fotos, h.assets.size, Taal.getal(h.assets.size)), style = MaterialTheme.typography.labelSmall, color = Color(0xCCFFFFFF))
            }
        }
    }
}

/** Herinneringen van vandaag (en van de afgelopen dagen voor het Herinneringen-scherm), één keer per dag opgehaald. */
object Herinneringen {
    var vandaag by mutableStateOf<List<Herinnering>>(emptyList())
        private set
    private var opgehaald: LocalDate? = null

    suspend fun laadVandaag() {
        val d = LocalDate.now()
        if (opgehaald == d) return
        runCatching { Immich.herinneringen(d) }.onSuccess { vandaag = it; opgehaald = d }.onFailure { Log.w("Fotos", "herinneringen", it) }
    }
}
