package com.immichclient.tv

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

/*
 * Instellingen (sectie onderaan in de lade): één lijst met groepen. Elke regel is een tv-Surface: ▲▼ kiest een regel,
 * ◀ ▶ past de waarde aan (stap terug/verder), OK neemt de volgende waarde (met omloop) of voert een actie uit.
 * Alles leest/schrijft rechtstreeks het Instellingen-object; de rest van de app volgt via Compose-state.
 */

@Composable
fun InstellingenScherm(opnieuwKoppelen: () -> Unit, ontkoppeld: () -> Unit, toetsen: () -> Unit) {
    val ctx = LocalContext.current
    val ik by produceState(Immich.ik, Immich.stand) { value = Immich.laadIk() }
    val immichVersie by produceState<String?>(null, Immich.stand) { value = Immich.serverVersie() }
    val versie = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }
    var bevestig by remember { mutableStateOf(false) }
    var groetInvoer by remember { mutableStateOf(false) }
    // cachegrootte: schijf + geheugen, opnieuw gemeten na legen
    var cacheRonde by remember { mutableIntStateOf(0) }
    val cacheMaat = stringResource(R.string.cache_maat)
    val cache by produceState<String?>(null, cacheRonde) {
        value = withContext(Dispatchers.IO) {
            val l = SingletonImageLoader.get(ctx)
            val schijf = l.diskCache?.size ?: 0L; val geheugen = l.memoryCache?.size ?: 0L
            cacheMaat.format(Taal.locale, Taal.getal((schijf / 1048576.0).roundToLong()), Taal.getal((geheugen / 1048576.0).roundToLong()))
        }
    }
    val eerste = remember { FocusRequester() }
    val taalRegel = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(30)
        // na een taalwissel (recreate) staat de lijst nog op dezelfde plek: focus terug op de regel Taal, anders de eerste regel
        if (Taal.focusTaal) { Taal.focusTaal = false; repeat(5) { if (runCatching { taalRegel.requestFocus() }.isSuccess) return@LaunchedEffect; delay(16) } }
        runCatching { eerste.requestFocus() }
    }
    LaunchedEffect(bevestig) { if (bevestig) { delay(6000); bevestig = false } }
    val uitAan = listOf(stringResource(R.string.uit), stringResource(R.string.aan))

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { SchermKop(stringResource(R.string.instellingen), stringResource(R.string.instellingen_sub)) }

        groep(R.string.groep_diashow)
        item {
            Keuzes(stringResource(R.string.tijd_per_foto), stringResource(R.string.tijd_per_foto_uitleg), Instellingen.DIA_TIJDEN.map { stringResource(R.string.seconden, it) },
                Instellingen.DIA_TIJDEN.indexOf(Instellingen.diaSeconden).coerceAtLeast(0), Modifier.focusRequester(eerste)) { Instellingen.diaSeconden = Instellingen.DIA_TIJDEN[it] }
        }
        schakel(R.string.ken_burns, R.string.ken_burns_uitleg, uitAan, Instellingen.kenBurns) { Instellingen.kenBurns = it }
        item { Keuzes(stringResource(R.string.volgorde), stringResource(R.string.dia_volgorde_uitleg), listOf(stringResource(R.string.op_datum), stringResource(R.string.willekeurig)), if (Instellingen.diaWillekeurig) 1 else 0) { Instellingen.diaWillekeurig = it == 1 } }
        item { Keuzes(stringResource(R.string.videos), stringResource(R.string.dia_videos_uitleg), listOf(stringResource(R.string.afspelen), stringResource(R.string.overslaan)), if (Instellingen.diaVideos) 0 else 1) { Instellingen.diaVideos = it == 0 } }
        item { Keuzes(stringResource(R.string.overgang), stringResource(R.string.overgang_uitleg), listOf(stringResource(R.string.vervagen), stringResource(R.string.schuiven)), if (Instellingen.diaSchuiven) 1 else 0) { Instellingen.diaSchuiven = it == 1 } }

        groep(R.string.groep_bekijken)
        item { Keuzes(stringResource(R.string.landkaart), stringResource(R.string.landkaart_uitleg), KaartMaat.entries.map { stringResource(it.label) }, Instellingen.viewerKaart.ordinal) { Instellingen.viewerKaart = KaartMaat.entries[it] } }
        item {
            val altijd = stringResource(R.string.altijd_tonen)
            val labels = Instellingen.BALK_TIJDEN.map { if (it == 0) altijd else stringResource(R.string.seconden_s, (it / 1000.0).let { s -> if (s % 1 == 0.0) "%.0f".format(Taal.locale, s) else "%.1f".format(Taal.locale, s) }) }
            Keuzes(stringResource(R.string.datum_plaats), stringResource(R.string.datum_plaats_uitleg), labels, Instellingen.BALK_TIJDEN.indexOf(Instellingen.balkMs).coerceAtLeast(0)) { Instellingen.balkMs = Instellingen.BALK_TIJDEN[it] }
        }
        item { Keuzes(stringResource(R.string.teller), stringResource(R.string.teller_uitleg, Taal.getal(12345)), uitAan, if (Instellingen.teller) 1 else 0) { Instellingen.teller = it == 1 } }

        groep(R.string.groep_tijdlijn)
        item { Keuzes(stringResource(R.string.grootte), stringResource(R.string.grootte_uitleg), RijMaat.entries.map { stringResource(it.label) }, Instellingen.rijMaat.ordinal) { Instellingen.rijMaat = RijMaat.entries[it] } }
        item { Keuzes(stringResource(R.string.volgorde), stringResource(R.string.tijdlijn_volgorde_uitleg), listOf(stringResource(R.string.nieuwste_eerst), stringResource(R.string.oudste_eerst)), if (Instellingen.nieuwsteEerst) 0 else 1) { Instellingen.nieuwsteEerst = it == 0 } }
        schakel(R.string.partner, R.string.partner_uitleg, uitAan, Instellingen.partners) { Instellingen.partners = it }
        schakel(R.string.herinneringen_boven, R.string.herinneringen_boven_uitleg, uitAan, Instellingen.herinneringen) { Instellingen.herinneringen = it }
        item {
            val keuzes = listOf(Sectie.FOTOS, Sectie.HERINNERINGEN, Sectie.KAART, Sectie.ALBUMS, Sectie.FAVORIETEN)
            Keuzes(stringResource(R.string.startscherm), stringResource(R.string.startscherm_uitleg), keuzes.map { stringResource(if (it == Sectie.FOTOS) Instellingen.uiterlijk.label else it.label) },
                keuzes.indexOf(Instellingen.start).coerceAtLeast(0)) { Instellingen.start = keuzes[it] }
        }
        item {
            val voorbeeld = stringResource(R.string.groet_met_naam, stringResource(R.string.groet_middag), ik?.naam?.substringBefore(' ') ?: "…")
            Keuzes(stringResource(R.string.begroeting), stringResource(R.string.begroeting_uitleg, voorbeeld), Groet.entries.map { stringResource(it.label) }, Instellingen.groet.ordinal) { Instellingen.groet = Groet.entries[it] }
        }
        if (Instellingen.groet == Groet.EIGEN) item {
            Actie(stringResource(R.string.eigen_tekst), Instellingen.groetTekst.ifBlank { stringResource(R.string.nog_niets) }) { groetInvoer = true }
        }

        groep(R.string.groep_kaart)
        item { Keuzes(stringResource(R.string.startweergave), stringResource(R.string.startweergave_uitleg), KaartStart.entries.map { stringResource(it.label) }, Instellingen.kaartStart.ordinal) { Instellingen.kaartStart = KaartStart.entries[it] } }
        item { Keuzes(stringResource(R.string.clusterniveau), stringResource(R.string.cluster_uitleg), ClusterNiveau.entries.map { stringResource(it.label) }, Instellingen.cluster.ordinal) { Instellingen.cluster = ClusterNiveau.entries[it] } }
        item { Keuzes(stringResource(R.string.kaartstijl), stringResource(R.string.kaartstijl_uitleg), KaartStijl.entries.map { stringResource(it.label) }, Instellingen.kaartStijl.ordinal) { Instellingen.kaartStijl = KaartStijl.entries[it] } }

        groep(R.string.groep_uiterlijk)
        item {
            // taal: meteen toepassen = activity opnieuw opbouwen (alle teksten, datums en landnamen volgen)
            Keuzes(stringResource(R.string.taal), stringResource(R.string.taal_uitleg), Taal.TALEN.map { Taal.naam(it) }, Taal.TALEN.indexOf(Taal.code).coerceAtLeast(0), Modifier.focusRequester(taalRegel)) {
                val nieuw = Taal.TALEN[it]
                if (nieuw != Taal.code) { Taal.kies(ctx, nieuw); Taal.herstartSectie = Sectie.INSTELLINGEN; Taal.focusTaal = true; (ctx as? android.app.Activity)?.recreate() }
            }
        }
        item {
            Keuzes(stringResource(R.string.accentkleur), stringResource(R.string.accent_uitleg), Accent.entries.map { stringResource(it.label) }, Instellingen.accent.ordinal, kleur = Instellingen.accent.primair) { Instellingen.accent = Accent.entries[it] }
        }
        item {
            val u = Instellingen.uiterlijk
            Keuzes(stringResource(R.string.naam_icoon), stringResource(R.string.naam_icoon_uitleg, stringResource(u.uitleg)), Uiterlijk.entries.map { stringResource(it.label) }, u.ordinal) {
                val nieuw = Uiterlijk.entries[it]; Instellingen.uiterlijk = nieuw; nieuw.pasToe(ctx)
            }
        }

        groep(R.string.groep_opslag)
        item { Actie(stringResource(R.string.cache_legen), cache ?: "…") {
            val l = SingletonImageLoader.get(ctx)
            l.memoryCache?.clear(); ThumbHash.leeg()
            Thread { runCatching { l.diskCache?.clear() }; cacheRonde++ }.start()
        } }

        groep(R.string.groep_account)
        item { Info(stringResource(R.string.immich_server), Immich.server.ifBlank { "–" }) }
        item { Info(stringResource(R.string.ingelogd_als), ik?.let { "${it.naam}  ·  ${it.email}" } ?: "…") }
        item { Actie(stringResource(R.string.opnieuw_koppelen), stringResource(R.string.opnieuw_koppelen_uitleg)) { opnieuwKoppelen() } }
        item {
            Actie(stringResource(if (bevestig) R.string.zeker_weten else R.string.ontkoppelen), stringResource(if (bevestig) R.string.zeker_weten_uitleg else R.string.ontkoppelen_uitleg)) {
                if (!bevestig) bevestig = true else { Immich.ontkoppel(ctx, Immich.kijker); ontkoppeld() }
            }
        }

        groep(R.string.groep_over)
        item { Actie(stringResource(R.string.toetsen), stringResource(R.string.toetsen_uitleg)) { toetsen() } }
        item { Info(stringResource(R.string.app_regel, stringResource(Instellingen.uiterlijk.label)), stringResource(R.string.versie, versie)) }
        item { Info(stringResource(R.string.immich_server), immichVersie?.let { stringResource(R.string.versie, it) } ?: "…") }
        item { Info(stringResource(R.string.kaart), stringResource(R.string.kaart_bron, SAT_BRON)) }
    }
    if (groetInvoer) TekstInvoer(stringResource(R.string.begroeting), Instellingen.groetTekst, actie = stringResource(R.string.actie_klaar), sluit = { groetInvoer = false }) {
        Instellingen.groetTekst = it; groetInvoer = false
    }
}

private fun LazyListScope.groep(@StringRes naam: Int) = item {
    Text(stringResource(naam), style = MaterialTheme.typography.titleLarge, color = Kleur.primair, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp, start = 4.dp))
}

private fun LazyListScope.schakel(@StringRes titel: Int, @StringRes uitleg: Int, uitAan: List<String>, waarde: Boolean, zet: (Boolean) -> Unit) = item {
    Keuzes(stringResource(titel), stringResource(uitleg), uitAan, if (waarde) 1 else 0) { zet(it == 1) }
}


/** Regel met een keuze: ◀ ▶ = vorige/volgende waarde (zonder omloop), OK = volgende (met omloop). */
@Composable
private fun Keuzes(titel: String, uitleg: String, opties: List<String>, index: Int, modifier: Modifier = Modifier, kleur: Color? = null, kies: (Int) -> Unit) {
    Regel(
        titel, uitleg, modifier.onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key.nativeKeyCode) {
                // altijd verwerkt (ook aan de randen), anders schiet de focus bij de laagste waarde ineens naar de lade
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (index > 0) kies(index - 1); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (index < opties.lastIndex) kies(index + 1); true }
                else -> false
            }
        },
        onKlik = { kies((index + 1) % opties.size) },
    ) { focus ->
        val dim = if (focus) Kleur.opPrimair.copy(alpha = 0.45f) else Kleur.zacht.copy(alpha = 0.5f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", style = MaterialTheme.typography.titleLarge, color = if (index > 0) (if (focus) Kleur.opPrimair else Kleur.tekst) else dim)
            if (kleur != null) Box(Modifier.padding(start = 14.dp).size(16.dp).background(kleur, CircleShape))
            // 240 dp: de langste waarde ("Country with the most photos", "Land mit den meisten Fotos") is ~220 dp
            Text(opties[index], style = MaterialTheme.typography.titleMedium, color = if (focus) Kleur.opPrimair else Kleur.tekst, modifier = Modifier.padding(horizontal = 14.dp).width(240.dp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text("›", style = MaterialTheme.typography.titleLarge, color = if (index < opties.lastIndex) (if (focus) Kleur.opPrimair else Kleur.tekst) else dim)
        }
    }
}

@Composable
private fun Actie(titel: String, uitleg: String, onKlik: () -> Unit) = Regel(titel, uitleg, onKlik = onKlik) { focus ->
    Text("OK", style = MaterialTheme.typography.labelLarge, color = if (focus) Kleur.opPrimair else Kleur.zacht, modifier = Modifier.padding(end = 10.dp))
}

@Composable
private fun Info(titel: String, waarde: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(titel, style = MaterialTheme.typography.bodyMedium, color = Kleur.zacht, modifier = Modifier.width(220.dp))
        Text(waarde, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Regel(titel: String, uitleg: String, modifier: Modifier = Modifier, onKlik: () -> Unit, rechts: @Composable (focus: Boolean) -> Unit) {
    var focus by remember { mutableStateOf(false) }
    Surface(
        onClick = onKlik, modifier = modifier.fillMaxWidth().onFocusChanged { focus = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(containerColor = Kleur.vlak, contentColor = Kleur.tekst, focusedContainerColor = Kleur.primair, focusedContentColor = Kleur.opPrimair),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                // tekststijlen hebben een eigen kleur (Thema.kt), die wint van de content-kleur van de Surface: dus expliciet
                Text(titel, style = MaterialTheme.typography.titleMedium, color = if (focus) Kleur.opPrimair else Kleur.tekst, maxLines = 1)
                Text(uitleg, style = MaterialTheme.typography.bodySmall, color = if (focus) Kleur.opPrimair.copy(alpha = 0.8f) else Kleur.zacht, maxLines = 2)
            }
            rechts(focus)
        }
    }
}

/** Overzicht van alle sneltoetsen per scherm (INFO-toets, of via Instellingen → Toetsen). Elke toets sluit het. */
@Composable
fun ToetsenOverzicht(sluit: () -> Unit) {
    val fr = remember { FocusRequester() }
    BackHandler { sluit() }
    LaunchedEffect(Unit) { delay(30); runCatching { fr.requestFocus() } }
    Box(
        Modifier.fillMaxSize().background(Color(0xF20A0A0A)).focusVangen().focusRequester(fr).focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key.nativeKeyCode) { KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> false; else -> { sluit(); true } }
            }
            .padding(horizontal = 56.dp, vertical = 36.dp),
    ) {
        Column {
            Text(stringResource(R.string.toetsen), style = MaterialTheme.typography.displaySmall)
            Text(stringResource(R.string.toetsen_sluit), style = MaterialTheme.typography.bodyMedium, color = Kleur.zacht)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ToetsGroep(R.string.tg_overal, listOf(
                        R.string.t_terug_lang to R.string.t_terug_lang_wat,
                        R.string.t_info to R.string.t_info_wat,
                        R.string.t_links_rand to R.string.t_links_rand_wat,
                        R.string.t_terug to R.string.t_terug_wat,
                    ))
                    ToetsGroep(R.string.tg_tijdlijn, listOf(
                        R.string.t_pijlen to R.string.t_pijlen_wat,
                        R.string.t_ok to R.string.t_ok_open,
                        R.string.t_play to R.string.t_play_wat,
                        R.string.t_spoel to R.string.t_spoel_maand,
                        R.string.t_terug to R.string.t_terug_boven,
                    ))
                    ToetsGroep(R.string.tg_instellingen, listOf(
                        R.string.t_links_rechts to R.string.t_waarde,
                        R.string.t_ok to R.string.t_ok_waarde,
                    ))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ToetsGroep(R.string.tg_viewer, listOf(
                        R.string.t_links_rechts to R.string.t_vorige_volgende,
                        R.string.t_ok_play to R.string.t_diashow_wat,
                        R.string.t_info_toets to R.string.t_info_paneel,
                        R.string.t_ok_info to R.string.t_ok_info_wat,
                        R.string.t_spoel to R.string.t_spoel_viewer,
                        R.string.t_terug to R.string.t_sluiten,
                    ))
                    ToetsGroep(R.string.tg_kaart, listOf(
                        R.string.t_pijlen to R.string.t_kaart_pijlen,
                        R.string.t_ok to R.string.t_kaart_ok,
                        R.string.t_spoel to R.string.t_kaart_zoom,
                        R.string.t_terug to R.string.t_kaart_terug,
                        R.string.t_kaart_links to R.string.t_links_rand_wat,
                    ))
                }
            }
        }
    }
}

@Composable
private fun ToetsGroep(@StringRes naam: Int, regels: List<Pair<Int, Int>>) {
    Column {
        Text(stringResource(naam), style = MaterialTheme.typography.titleMedium, color = Kleur.primair, modifier = Modifier.padding(bottom = 4.dp))
        for ((toets, wat) in regels) Row(Modifier.padding(vertical = 1.dp)) {
            Text(stringResource(toets), style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(150.dp).padding(end = 8.dp), maxLines = 2)
            Text(stringResource(wat), style = MaterialTheme.typography.bodyMedium, color = Kleur.zacht, maxLines = 2, modifier = Modifier.weight(1f))
        }
    }
}
