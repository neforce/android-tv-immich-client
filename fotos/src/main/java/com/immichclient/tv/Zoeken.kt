package com.immichclient.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Pagina's met resultaten (zoeken, plaats): laadt de volgende pagina als je bij het einde komt. */
class Resultaten(private val scope: CoroutineScope) {
    var assets by mutableStateOf<List<Asset>>(emptyList())
    var bezig by mutableStateOf(false)
    var fout by mutableStateOf<String?>(null)
    var gezocht by mutableStateOf(false)
    private var volgende: Int? = null
    private var laad: (suspend (Int) -> Pagina)? = null
    private var job: Job? = null
    val raster = RasterStaat()

    fun start(l: suspend (Int) -> Pagina) {
        job?.cancel()
        laad = l; assets = emptyList(); volgende = 1; fout = null; gezocht = true
        scope.launch { raster.lijst.scrollToItem(0) }
        meer()
    }

    fun meer() {
        val p = volgende ?: return
        val l = laad ?: return
        if (bezig) return
        bezig = true
        job = scope.launch {
            runCatching { l(p) }
                .onSuccess { r -> assets = assets + r.assets.filter { a -> assets.none { it.id == a.id } }; volgende = r.volgende }
                .onFailure { fout = it.message; volgende = null; Log.w("Fotos", "zoeken", it) }
            bezig = false
        }
    }
}

enum class ZoekModus(@StringRes val label: Int) { SLIM(R.string.modus_slim), TEKST(R.string.modus_tekst), NAAM(R.string.modus_naam) }
enum class ZoekType(@StringRes val label: Int, val video: Boolean?) { ALLES(R.string.type_alles, null), FOTO(R.string.type_fotos, false), VIDEO(R.string.type_videos, true) }
enum class ZoekVolgorde(@StringRes val label: Int) { RELEVANTIE(R.string.volg_relevantie), NIEUW(R.string.volg_nieuw), OUD(R.string.volg_oud) }

class ZoekStaat(scope: CoroutineScope) {
    var tekst by mutableStateOf("")
    var modus by mutableStateOf(ZoekModus.SLIM)
    var type by mutableStateOf(ZoekType.ALLES)
    var volgorde by mutableStateOf(ZoekVolgorde.RELEVANTIE)
    val res = Resultaten(scope)
    /** na een nieuwe zoekopdracht het eerste resultaat focus geven */
    var focusOpResultaat = false
    var personen by mutableStateOf<List<Persoon>>(emptyList())

    init {
        scope.launch { runCatching { Immich.personen() }.onSuccess { personen = it } }
        scope.launch { Immich.warmZoeken() }
    }

    fun zoek(c: Context) {
        val t = tekst.trim()
        if (t.isEmpty()) return
        Recent.voegToe(c, t)
        focusOpResultaat = true
        val m = modus
        val ty = type
        res.start { p ->
            when (m) {
                ZoekModus.SLIM -> Immich.zoekSlim(t, p, ty.video)
                ZoekModus.TEKST -> Immich.zoekTekst(t, "ocr", p)
                ZoekModus.NAAM -> Immich.zoekTekst(t, "originalFileName", p)
            }.let { r -> if (ty.video == null || m == ZoekModus.SLIM) r else r.copy(assets = r.assets.filter { it.video == ty.video }) }
        }
    }
}

object Recent {
    private const val MAX = 10
    fun lijst(c: Context): List<String> =
        c.getSharedPreferences("zoeken", Context.MODE_PRIVATE).getString("recent", "").orEmpty().split('\n').filter { it.isNotBlank() }
    fun voegToe(c: Context, t: String) {
        val l = (listOf(t) + lijst(c).filter { !it.equals(t, true) }).take(MAX)
        c.getSharedPreferences("zoeken", Context.MODE_PRIVATE).edit().putString("recent", l.joinToString("\n")).apply()
    }
}

@Composable
fun ZoekScherm(staat: ZoekStaat, nav: Nav) {
    val ctx = LocalContext.current
    var invoer by remember { mutableStateOf(false) }
    val veld = remember { FocusRequester() }
    val spraak = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val t = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == Activity.RESULT_OK && !t.isNullOrBlank()) { staat.tekst = t; staat.zoek(ctx) }
    }
    val spraakPrompt = stringResource(R.string.spraak_prompt)
    val kanSpraak = remember {
        ctx.packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val breedte = breedteVoor(maxWidth.value)
        val gesorteerd = remember(staat.res.assets, staat.volgorde) {
            when (staat.volgorde) {
                ZoekVolgorde.RELEVANTIE -> staat.res.assets
                ZoekVolgorde.NIEUW -> staat.res.assets.sortedByDescending { it.datum }
                ZoekVolgorde.OUD -> staat.res.assets.sortedBy { it.datum }
            }
        }
        val rijHoogte = Instellingen.rijMaat.hoogte
        val rijen = remember(gesorteerd, breedte, rijHoogte) { rijenVoor(gesorteerd, breedte, perDag = staat.volgorde != ZoekVolgorde.RELEVANTIE, hoogte = rijHoogte) }
        val bron = remember(gesorteerd) { LijstBron(gesorteerd) }
        LaunchedEffect(rijen) {
            if (staat.focusOpResultaat && gesorteerd.isNotEmpty()) { staat.focusOpResultaat = false; staat.res.raster.focusDoel = gesorteerd.first().id }
        }
        // volgende pagina als het einde in zicht komt
        LaunchedEffect(rijen) {
            snapshotFlow { staat.res.raster.lijst.layoutInfo.let { (it.visibleItemsInfo.lastOrNull()?.index ?: 0) >= it.totalItemsCount - 3 } }
                .distinctUntilChanged().collect { if (it && rijen.isNotEmpty()) staat.res.meer() }
        }
        FotoRaster(
            rijen = rijen, staat = staat.res.raster, bovenAantal = 1,
            onKlik = { a -> nav.bekijk(bron, gesorteerd.indexOf(a), ctx.getString(R.string.zoeken_titel, staat.tekst), false, staat.res.raster) },
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "boven") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SchermKop(stringResource(R.string.zoeken), null)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(
                            onClick = { invoer = true },
                            modifier = Modifier.weight(1f).height(56.dp).focusRequester(veld),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(28.dp)),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                            colors = ClickableSurfaceDefaults.colors(containerColor = Kleur.vlakHoog, focusedContainerColor = Color(0xFF34343A)),
                            border = ClickableSurfaceDefaults.border(
                                focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.dp, Kleur.primair), shape = RoundedCornerShape(28.dp)),
                            ),
                        ) {
                            Row(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Search, null, tint = Kleur.zacht)
                                Spacer(Modifier.width(14.dp))
                                Text(
                                    staat.tekst.ifEmpty { stringResource(R.string.zoek_hint) },
                                    style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                                    color = if (staat.tekst.isEmpty()) Kleur.zacht else Kleur.tekst,
                                )
                            }
                        }
                        if (kanSpraak) Surface(
                            onClick = {
                                runCatching {
                                    spraak.launch(
                                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Taal.locale.toLanguageTag())
                                            .putExtra(RecognizerIntent.EXTRA_PROMPT, spraakPrompt),
                                    )
                                }
                            },
                            modifier = Modifier.size(56.dp),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(28.dp)),
                            colors = ClickableSurfaceDefaults.colors(containerColor = Kleur.vlakHoog, focusedContainerColor = Kleur.primair, focusedContentColor = Kleur.opPrimair),
                        ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Mic, stringResource(R.string.spraak)) } }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (m in ZoekModus.entries) Keuze(selected = staat.modus == m, onClick = { staat.modus = m; staat.zoek(ctx) }) { Text(stringResource(m.label)) }
                        Spacer(Modifier.width(18.dp))
                        for (t in ZoekType.entries) Keuze(selected = staat.type == t, onClick = { staat.type = t; staat.zoek(ctx) }) { Text(stringResource(t.label)) }
                    }
                    if (staat.res.assets.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (v in ZoekVolgorde.entries) Keuze(selected = staat.volgorde == v, onClick = { staat.volgorde = v }) { Text(stringResource(v.label)) }
                    }
                    when {
                        staat.res.bezig && staat.res.assets.isEmpty() -> Text(stringResource(R.string.zoeken_bezig), style = MaterialTheme.typography.bodyLarge, color = Kleur.zacht)
                        staat.res.fout != null && staat.res.assets.isEmpty() -> Text(stringResource(R.string.zoeken_mislukt, staat.res.fout.orEmpty()), color = Kleur.zacht)
                        staat.res.gezocht && staat.res.assets.isEmpty() -> Text(stringResource(R.string.niets_gevonden), style = MaterialTheme.typography.bodyLarge, color = Kleur.zacht)
                        staat.res.gezocht -> Text(pluralStringResource(R.plurals.resultaten, gesorteerd.size, Taal.getal(gesorteerd.size) + if (staat.res.bezig) "+" else ""), style = MaterialTheme.typography.bodySmall)
                        else -> Suggesties(staat, nav) { staat.tekst = it; staat.zoek(ctx) }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(30); if (!staat.res.gezocht) runCatching { veld.requestFocus() } }
    // laag dicht: focus terug op het zoekveld (anders springt Android naar de lade); resultaten nemen hem daarna over
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(invoer) { if (wasOpen && !invoer) { delay(16); runCatching { veld.requestFocus() } }; wasOpen = invoer }
    if (invoer) TekstInvoer(stringResource(R.string.zoeken), staat.tekst, sluit = { invoer = false }) { staat.tekst = it; invoer = false; staat.zoek(ctx) }
}

@Composable
private fun Suggesties(staat: ZoekStaat, nav: Nav, zoek: (String) -> Unit) {
    val ctx = LocalContext.current
    val recent = remember { Recent.lijst(ctx) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 10.dp)) {
        if (recent.isNotEmpty()) {
            Text(stringResource(R.string.recent_gezocht), style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(recent) { t ->
                    Keuze(selected = false, onClick = { zoek(t) }, leadingIcon = { Icon(Icons.Filled.History, null, Modifier.width(18.dp)) }) { Text(t) }
                }
            }
        }
        Text(stringResource(R.string.probeer), style = MaterialTheme.typography.titleMedium)
        val suggesties = stringArrayResource(R.array.suggesties).toList()
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(suggesties) { t ->
                Keuze(selected = false, onClick = { zoek(t) }) { Text(t) }
            }
        }
        if (staat.personen.isNotEmpty()) {
            Text(stringResource(R.string.personen), style = MaterialTheme.typography.titleMedium)
            val persoon = stringResource(R.string.persoon)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(staat.personen.take(30), key = { it.id }) { p ->
                    Keuze(
                        selected = false,
                        onClick = { nav.open(Scherm.Tijdlijn(p.naam, persoon, Filter(persoonId = p.id), true, false, nav.scope)) },
                        leadingIcon = { Gezicht(p, 26.dp) },
                    ) { Text(p.naam) }
                }
            }
        }
    }
}

/**
 * Eigen schermtoetsenbord: het standaard-invoerpaneel van Google TV is de telefoon-brug ("gebruik het toetsenbord op je
 * mobiele apparaat"), niet iets wat je met de afstandsbediening bedient. Alleen a-z en cijfers (slim zoeken en de
 * albumfilter zijn ongevoelig voor accenten); een fysiek toetsenbord typt gewoon mee, met accenten.
 * [live] krijgt elke wijziging (albums filteren tijdens het typen).
 */
@Composable
fun TekstInvoer(titel: String, begin: String, live: ((String) -> Unit)? = null, actie: String = stringResource(R.string.actie_zoeken), sluit: () -> Unit, klaar: (String) -> Unit) {
    var tekst by remember { mutableStateOf(begin) }
    val eerste = remember { FocusRequester() }
    fun zet(t: String) { tekst = t; live?.invoke(t) }
    BackHandler { sluit() }
    Box(
        Modifier.fillMaxSize().background(Color(0xF00A0A0A)).focusVangen().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val n = e.nativeKeyEvent
            when {
                n.keyCode == android.view.KeyEvent.KEYCODE_DEL -> { zet(tekst.dropLast(1)); true }
                n.keyCode == android.view.KeyEvent.KEYCODE_SEARCH -> { klaar(tekst); true }
                n.unicodeChar > 0 && n.keyCode !in setOf(android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER) -> {
                    zet(tekst + n.unicodeChar.toChar()); true
                }
                else -> false
            }
        },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.padding(top = 56.dp).width(700.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(titel, style = MaterialTheme.typography.headlineSmall)
            Row(
                Modifier.fillMaxWidth().height(56.dp).background(Kleur.vlakHoog, RoundedCornerShape(28.dp))
                    .border(2.dp, Kleur.primair, RoundedCornerShape(28.dp)).padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, null, tint = Kleur.zacht)
                Spacer(Modifier.width(14.dp))
                Text(tekst, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                Box(Modifier.width(2.dp).height(26.dp).background(Kleur.primair))
            }
            Spacer(Modifier.height(6.dp))
            "abcdefghijklmnopqrstuvwxyz1234567890".chunked(10).forEachIndexed { ri, rij ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rij.forEachIndexed { ci, c ->
                        Toets(c.toString(), Modifier.weight(1f).then(if (ri == 0 && ci == 0) Modifier.focusRequester(eerste) else Modifier)) { zet(tekst + c) }
                    }
                    // laatste rij aanvullen tot 10, zodat alles netjes onder elkaar staat
                    repeat(10 - rij.length) { Spacer(Modifier.weight(1f)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Toets(stringResource(R.string.toets_spatie), Modifier.weight(3f)) { zet("$tekst ") }
                Toets("⌫", Modifier.weight(1f)) { zet(tekst.dropLast(1)) }
                Toets(stringResource(R.string.toets_wissen), Modifier.weight(2f)) { zet("") }
                Toets(actie, Modifier.weight(4f), hoofd = true) { klaar(tekst) }
            }
        }
    }
    LaunchedEffect(Unit) { delay(40); runCatching { eerste.requestFocus() } }
}

@Composable
private fun Toets(label: String, modifier: Modifier, hoofd: Boolean = false, onKlik: () -> Unit) {
    Surface(
        onClick = onKlik, modifier = modifier.height(52.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (hoofd) Kleur.primair.copy(alpha = 0.25f) else Kleur.vlak, contentColor = Kleur.tekst,
            focusedContainerColor = Kleur.primair, focusedContentColor = Kleur.opPrimair,
        ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(label, style = MaterialTheme.typography.titleMedium) }
    }
}

/** Platte lijst met pagina's (bijv. alle foto's uit één plaats), per dag met jaartal. */
@Composable
fun LijstScherm(scherm: Scherm.Lijst, nav: Nav) {
    val res = scherm.res
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val breedte = breedteVoor(maxWidth.value)
        val rijHoogte = Instellingen.rijMaat.hoogte
        val rijen = remember(res.assets, breedte, rijHoogte) { rijenVoor(res.assets, breedte, perDag = scherm.perDag, hoogte = rijHoogte) }
        val bron = remember(res.assets) { LijstBron(res.assets) }
        LaunchedEffect(rijen) {
            snapshotFlow { res.raster.lijst.layoutInfo.let { (it.visibleItemsInfo.lastOrNull()?.index ?: 0) >= it.totalItemsCount - 3 } }
                .distinctUntilChanged().collect { if (it && rijen.isNotEmpty()) res.meer() }
        }
        LaunchedEffect(rijen.isNotEmpty()) {
            if (rijen.isNotEmpty() && res.raster.laatsteFocus == null) res.raster.focusDoel = res.assets.firstOrNull()?.id
        }
        FotoRaster(
            rijen = rijen, staat = res.raster, bovenAantal = 1,
            onKlik = { a -> nav.bekijk(bron, res.assets.indexOf(a), scherm.titel, false, res.raster) },
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "boven") {
                SchermKop(scherm.titel, listOfNotNull(scherm.sub, if (res.assets.isNotEmpty()) pluralStringResource(R.plurals.items, res.assets.size, res.assets.size) + (if (res.bezig) "+" else "") else null).joinToString("  ·  "))
                if (res.fout != null && res.assets.isEmpty()) Text(stringResource(R.string.laden_mislukt, res.fout.orEmpty()), color = Kleur.zacht)
            }
        }
    }
}

/** Herinneringen van de afgelopen week: per dag de jaren, zoals "Op deze dag" in Immich. */
@Composable
fun HerinneringenScherm(staat: HerinneringStaat, nav: Nav) {
    val eerste = remember { FocusRequester() }
    androidx.compose.foundation.lazy.LazyColumn(
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { SchermKop(stringResource(R.string.sectie_herinneringen), stringResource(R.string.herinneringen_sub)) }
        if (staat.klaar && staat.dagen.all { it.second.isEmpty() }) item { Melding(stringResource(R.string.geen_herinneringen), null) }
        staat.dagen.forEachIndexed { di, (dag, lijst) ->
            if (lijst.isEmpty()) return@forEachIndexed
            item(key = dag.toString()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(dagTitel(dag, false), style = MaterialTheme.typography.titleLarge)
                    LazyRow(
                        Modifier.offset(x = (-10).dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                    ) {
                        items(lijst, key = { it.id }) { h ->
                            Box(if (di == staat.dagen.indexOfFirst { it.second.isNotEmpty() } && h == lijst.first()) Modifier.focusRequester(eerste) else Modifier) {
                                HerinneringKaart(h, nav, breedte = 190f)
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(staat.klaar) { delay(50); runCatching { eerste.requestFocus() } }
}

class HerinneringStaat(scope: CoroutineScope) {
    var dagen by mutableStateOf<List<Pair<java.time.LocalDate, List<Herinnering>>>>(emptyList())
    var klaar by mutableStateOf(false)

    init {
        scope.launch {
            val vandaag = java.time.LocalDate.now()
            val l = (0..6).map { vandaag.minusDays(it.toLong()) }
            dagen = l.map { it to emptyList() }
            l.forEachIndexed { i, d ->
                runCatching { Immich.herinneringen(d) }.onSuccess { h -> dagen = dagen.toMutableList().also { it[i] = d to h } }
            }
            klaar = true
        }
    }
}
