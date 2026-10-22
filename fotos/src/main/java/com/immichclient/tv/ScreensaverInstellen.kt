package com.immichclient.tv

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.RadioButtonUnchecked
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.concurrent.thread

/**
 * De screensaver van het systeem op deze app zetten. Android TV heeft daar meestal geen instelscherm voor (op Google TV
 * en sommige boxen is de keuze verborgen); de instelling is `Settings.Secure.screensaver_components`, die een app alleen
 * mag schrijven met WRITE_SECURE_SETTINGS. Die permissie is eenmalig via adb te geven (`pm grant`), daarna zet de app
 * hem zelf aan en uit (en zet bij "uit" de vorige screensaver terug). Zonder permissie: uitleg + de adb-commando's, of
 * [ZelfAdb] (de box verbindt met zijn eigen netwerk-adb en doet het zelf).
 */
object Screensaver {
    private const val COMPONENTEN = "screensaver_components"
    private const val AAN = "screensaver_enabled"

    fun component(c: Context): ComponentName = ComponentName(c, FotoDream::class.java)
    fun componentTekst(c: Context): String = component(c).flattenToShortString()

    fun huidig(c: Context): String? = runCatching { Settings.Secure.getString(c.contentResolver, COMPONENTEN) }.getOrNull()

    /** Staat deze app in de lijst van screensavers? */
    fun gekozen(c: Context): Boolean {
        val eigen = component(c)
        return huidig(c)?.split(',')?.any { ComponentName.unflattenFromString(it.trim()) == eigen } == true
    }

    /** Staat deze app nu ingesteld als screensaver (en staat de screensaver aan)? */
    fun actief(c: Context): Boolean = gekozen(c) && runCatching { Settings.Secure.getInt(c.contentResolver, AAN, 1) }.getOrDefault(1) != 0

    fun magSchrijven(c: Context) = c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    fun ontwikkelaarsopties(c: Context) = runCatching { Settings.Global.getInt(c.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) }.getOrDefault(0) == 1
    /** USB-foutopsporing (op sommige boxen staat netwerkfoutopsporing daar los van, zie [poortOpen]) */
    fun usbFoutopsporing(c: Context) = runCatching { Settings.Global.getInt(c.contentResolver, Settings.Global.ADB_ENABLED, 0) }.getOrDefault(0) == 1

    /**
     * Luistert adb op het netwerk (127.0.0.1:5555)? Alleen een TCP-verbinding openen en sluiten: zonder adb-handshake
     * vraagt de tv niets. Blokkeert hooguit 400 ms (niet op de hoofdthread aanroepen).
     */
    fun poortOpen(): Boolean = runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", ADB_POORT), 400) }; true }.getOrDefault(false)
    const val ADB_POORT = 5555

    /** Na hoeveel ms zonder bediening de screensaver start (Android TV: de systeeminstelling "screen off timeout"). */
    fun startNaMs(c: Context): Int? = runCatching { Settings.System.getInt(c.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) }.getOrNull()?.takeIf { it > 0 }

    /** Leesbare naam van de huidige screensaver (label van de dienst als die zichtbaar is, anders de componentnaam). */
    fun huidigeNaam(c: Context): String? {
        val tekst = huidig(c)?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val cn = ComponentName.unflattenFromString(tekst) ?: return tekst
        return runCatching { c.packageManager.getServiceInfo(cn, 0).loadLabel(c.packageManager).toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: tekst
    }

    /** De huidige screensaver onthouden (om bij "uit" terug te zetten), tenzij dat deze app al is. */
    fun onthoudVorige(c: Context) {
        val nu = huidig(c)
        if (!nu.isNullOrBlank() && !gekozen(c)) Instellingen.dreamVorige = nu
    }

    /** Aan: deze app als screensaver (de vorige wordt onthouden); uit: de vorige (of de standaard) terug. */
    fun zet(c: Context, aan: Boolean): Boolean = runCatching {
        val cr = c.contentResolver
        if (aan) {
            onthoudVorige(c)
            Settings.Secure.putString(cr, COMPONENTEN, componentTekst(c))
            Settings.Secure.putInt(cr, AAN, 1)
        } else {
            val terug = Instellingen.dreamVorige.takeIf { it.isNotBlank() && ComponentName.unflattenFromString(it) != component(c) } ?: standaard(c)
            Settings.Secure.putString(cr, COMPONENTEN, terug)
        }
        Log.i("Fotos", "screensaver ${if (aan) "aan" else "uit"}: nu ${huidig(c)}")
        true
    }.onFailure { Log.w("Fotos", "screensaver zetten", it) }.getOrDefault(false)

    /** De screensaver van het systeem zelf (bijv. Backdrop op Android TV). */
    private fun standaard(c: Context): String? =
        runCatching { Settings.Secure.getString(c.contentResolver, "screensaver_default_component") }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching { Resources.getSystem().let { r -> r.getIdentifier("config_dreamsDefaultComponent", "string", "android").takeIf { it != 0 }?.let { r.getString(it) } } }.getOrNull()

    fun kanOpenen(c: Context, actie: String) = runCatching { Intent(actie).resolveActivity(c.packageManager) != null }.getOrDefault(false)
    fun open(c: Context, actie: String): Boolean = runCatching { c.startActivity(Intent(actie).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)

    const val ACTIE_DREAM = "android.settings.DREAM_SETTINGS"
    const val ACTIE_OVER = Settings.ACTION_DEVICE_INFO_SETTINGS
    const val ACTIE_ONTWIKKELAAR = Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS

    /** De adb-commando's voor de handleiding op het scherm. */
    fun commandos(c: Context): List<String> = listOf(
        "adb connect ${Koppel.eigenIp() ?: "<ip>"}",
        "adb shell pm grant ${c.packageName} android.permission.WRITE_SECURE_SETTINGS",
        "adb shell settings put secure screensaver_components ${componentTekst(c)}",
        "adb shell settings put secure screensaver_enabled 1",
    )
}

/**
 * De box bedient zijn eigen adb (netwerkfoutopsporing, 127.0.0.1:5555): geeft de app WRITE_SECURE_SETTINGS en zet de
 * screensaver. Werkt alleen als netwerkfoutopsporing (of "ADB via netwerk") aanstaat in de ontwikkelaarsopties; de tv
 * vraagt de eerste keer om toestemming (sleutel in filesDir). Daarna is adb niet meer nodig.
 */
object ZelfAdb {
    var stand by mutableStateOf<Int?>(null)
        private set
    var detail by mutableStateOf<String?>(null)
        private set
    @Volatile private var bezig = false

    fun instellen(c: Context) {
        if (bezig) return
        bezig = true
        val app = c.applicationContext
        stand = R.string.adb_bezig; detail = null
        thread(name = "ZelfAdb") {
            try {
                // poort dicht = netwerkfoutopsporing uit: meteen zeggen (anders pas na de time-out)
                if (!Screensaver.poortOpen()) { stand = R.string.adb_geen_verbinding; return@thread }
                Screensaver.onthoudVorige(app)
                val priv = File(app.filesDir, "adbkey"); val pub = File(app.filesDir, "adbkey.pub")
                if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
                // wachten op de toestemmingsvraag op de tv kan even duren: ruime leestime-out
                Dadb.create("127.0.0.1", Screensaver.ADB_POORT, AdbKeyPair.read(priv, pub), 4000, 120_000).use { d ->
                    val fouten = StringBuilder()
                    fun sh(cmd: String) {
                        val r = d.shell(cmd)
                        if (r.exitCode != 0) fouten.append("$cmd → ${r.allOutput.trim()}\n")
                    }
                    if (!Screensaver.magSchrijven(app)) sh("pm grant ${app.packageName} android.permission.WRITE_SECURE_SETTINGS")
                    // met het recht zet de app het zelf (en kan hij het later ook weer uitzetten); lukt dat niet, dan via adb
                    if (!Screensaver.zet(app, true)) {
                        sh("settings put secure screensaver_components ${Screensaver.componentTekst(app)}")
                        sh("settings put secure screensaver_enabled 1")
                    }
                    Log.i("Fotos", "adb: klaar, recht=${Screensaver.magSchrijven(app)} actief=${Screensaver.actief(app)} ${fouten.toString().trim()}")
                    stand = if (Screensaver.actief(app)) R.string.adb_klaar else R.string.adb_mislukt
                    detail = fouten.toString().trim().ifEmpty { null }
                }
            } catch (e: java.net.ConnectException) {
                Log.w("Fotos", "adb: geen verbinding", e); stand = R.string.adb_geen_verbinding; detail = null
            } catch (e: java.net.SocketTimeoutException) {
                Log.w("Fotos", "adb: time-out", e); stand = R.string.adb_geen_toestemming; detail = null
            } catch (e: Exception) {
                Log.w("Fotos", "adb mislukt", e); stand = R.string.adb_mislukt; detail = e.message?.take(200)
            } finally { bezig = false }
        }
    }
}

/** Instellingen → Screensaver → Instellen: toestand, knoppen en de stap-voor-stap-uitleg. Terug sluit. */
@Composable
fun ScreensaverHulp(sluit: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler { sluit() }
    var ronde by remember { mutableIntStateOf(0) }
    // elke 2 s opnieuw kijken: de gebruiker kan intussen via adb of de systeeminstellingen iets veranderen
    LaunchedEffect(Unit) { while (true) { delay(2000); ronde++ } }
    val actief = remember(ronde, ZelfAdb.stand) { Screensaver.actief(ctx) }
    val mag = remember(ronde, ZelfAdb.stand) { Screensaver.magSchrijven(ctx) }
    val ontw = remember(ronde) { Screensaver.ontwikkelaarsopties(ctx) }
    val usb = remember(ronde) { Screensaver.usbFoutopsporing(ctx) }
    val netwerk by produceState(false, ronde) { value = withContext(Dispatchers.IO) { Screensaver.poortOpen() } }
    val nuNaam = remember(ronde, ZelfAdb.stand) { Screensaver.huidigeNaam(ctx) }
    val startNa = remember(ronde) { Screensaver.startNaMs(ctx) }
    val kanDream = remember { Screensaver.kanOpenen(ctx, Screensaver.ACTIE_DREAM) }
    val kanOver = remember { Screensaver.kanOpenen(ctx, Screensaver.ACTIE_OVER) }
    val kanOntw = remember { Screensaver.kanOpenen(ctx, Screensaver.ACTIE_ONTWIKKELAAR) }
    val eerste = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(40); runCatching { eerste.requestFocus() } }

    // kop + toestand staan vast bovenaan; daaronder scrollen de knoppen en de uitleg (anders schuift de toestand
    // uit beeld zodra de focus op een knop komt)
    Column(Modifier.fillMaxSize().background(Color(0xF60A0A0A)).focusVangen().padding(start = 64.dp, end = 64.dp, top = 30.dp)) {
        Text(stringResource(R.string.dream_instellen), style = MaterialTheme.typography.headlineMedium, color = Kleur.tekst)
        Text(stringResource(R.string.dream_instellen_sub), style = MaterialTheme.typography.bodyMedium, color = Kleur.zacht, modifier = Modifier.padding(bottom = 10.dp))
        Column(Modifier.fillMaxWidth().background(Kleur.vlak, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Vinkje(actief, stringResource(if (actief) R.string.dream_status_aan else R.string.dream_status_uit))
                    Vinkje(mag, stringResource(if (mag) R.string.dream_recht_ja else R.string.dream_recht_nee))
                }
                Column(Modifier.weight(0.7f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Vinkje(ontw, stringResource(if (ontw) R.string.dream_ontw_ja else R.string.dream_ontw_nee))
                    Vinkje(usb, stringResource(if (usb) R.string.dream_usb_ja else R.string.dream_usb_nee))
                    Vinkje(netwerk, stringResource(if (netwerk) R.string.dream_net_ja else R.string.dream_net_nee))
                }
            }
            val tijd = startNa?.let { ms -> if (ms >= 60_000) stringResource(R.string.minuten, ms / 60_000) else stringResource(R.string.seconden, ms / 1000) }
            val regels = listOfNotNull(
                nuNaam?.takeIf { !actief }?.let { stringResource(R.string.dream_nu, it) },
                tijd?.takeIf { startNa!! < 24 * 3_600_000 }?.let { stringResource(R.string.dream_start_na, it) },
            )
            if (regels.isNotEmpty()) Text(regels.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = Kleur.zacht, modifier = Modifier.padding(start = 32.dp), maxLines = 1)
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // directe knoppen
            if (mag) item {
                Knop(stringResource(if (actief) R.string.dream_zet_uit else R.string.dream_zet_aan), stringResource(R.string.dream_zet_uitleg), Modifier.fillMaxWidth().focusRequester(eerste)) {
                    Screensaver.zet(ctx, !actief); ronde++
                }
            }
            if (kanDream) item { Knop(stringResource(R.string.dream_open_systeem), stringResource(R.string.dream_open_systeem_uitleg), Modifier.fillMaxWidth().then(if (!mag) Modifier.focusRequester(eerste) else Modifier)) { Screensaver.open(ctx, Screensaver.ACTIE_DREAM) } }
            // automatisch via de eigen adb
            item {
                Text(stringResource(R.string.dream_auto_kop), style = MaterialTheme.typography.titleLarge, color = Kleur.primair, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
                Text(stringResource(R.string.dream_auto_uitleg), style = MaterialTheme.typography.bodyMedium, color = Kleur.zacht)
            }
            item {
                val s = ZelfAdb.stand
                Knop(stringResource(R.string.dream_auto_knop), s?.let { stringResource(it) + (ZelfAdb.detail?.let { d -> "  ·  $d" } ?: "") } ?: stringResource(R.string.dream_auto_knop_uitleg),
                    Modifier.fillMaxWidth().then(if (!mag && !kanDream) Modifier.focusRequester(eerste) else Modifier)) { ZelfAdb.instellen(ctx) }
            }
            // handmatig
            item {
                Text(stringResource(R.string.dream_hand_kop), style = MaterialTheme.typography.titleLarge, color = Kleur.primair, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
                Stap("1", stringResource(R.string.dream_stap1))
                Stap("2", stringResource(R.string.dream_stap2))
                Stap("3", stringResource(R.string.dream_stap3))
            }
            item {
                Column(Modifier.fillMaxWidth().background(Kleur.vlak, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 10.dp)) {
                    for (cmd in Screensaver.commandos(ctx)) Text(cmd, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp), color = Kleur.tekst)
                }
                Text(stringResource(R.string.dream_stap4), style = MaterialTheme.typography.bodyMedium, color = Kleur.zacht, modifier = Modifier.padding(top = 8.dp))
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                    if (kanOver) Knop(stringResource(R.string.dream_open_over), stringResource(R.string.dream_open_over_uitleg), Modifier.weight(1f)) { Screensaver.open(ctx, Screensaver.ACTIE_OVER) }
                    if (kanOntw) Knop(stringResource(R.string.dream_open_ontw), stringResource(R.string.dream_open_ontw_uitleg), Modifier.weight(1f)) { Screensaver.open(ctx, Screensaver.ACTIE_ONTWIKKELAAR) }
                }
            }
            item { Text(stringResource(R.string.terug_sluit), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp)) }
        }
    }
}

@Composable
private fun Vinkje(ok: Boolean, tekst: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null, Modifier.size(20.dp), tint = if (ok) Color(0xFF86EFAC) else Kleur.zacht)
        Text(tekst, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun Stap(n: String, tekst: String) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(n, style = MaterialTheme.typography.labelLarge, color = Kleur.primair, modifier = Modifier.width(22.dp))
        Text(tekst, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** Regel in een keuzelijst: naam, ondertitel en (optioneel) een plaatje (albumomslag, gezicht = rond). */
class KiesItem(val id: String, val naam: String, val sub: String?, val beeld: String?, val rond: Boolean = false)

/**
 * Keuzelijst (album of persoon voor de screensaver), bruikbaar met honderden items: bovenaan een zoekknop (filtert
 * tijdens het typen, accenten tellen niet), dan de huidige keuze, dan de rest (albums: laatst bijgewerkt eerst; personen:
 * wie het vaakst voorkomt eerst). De focus start op de huidige keuze. Terug sluit zonder keuze.
 */
@Composable
fun KiesLijst(titel: String, items: List<KiesItem>?, gekozen: String, sluit: () -> Unit, kies: (String, String) -> Unit) {
    var zoek by remember { mutableStateOf("") }
    var zoekOpen by remember { mutableStateOf(false) }
    BackHandler(enabled = !zoekOpen) { sluit() }
    val fr = remember { FocusRequester() }
    val zoekKnop = remember { FocusRequester() }
    val lijst = androidx.compose.foundation.lazy.rememberLazyListState()
    val zichtbaar = remember(items, zoek, gekozen) {
        val l = items.orEmpty()
        val z = zonderAccenten(zoek.trim())
        if (z.isNotEmpty()) l.filter { zonderAccenten(it.naam).contains(z, ignoreCase = true) }
        else l.filter { it.id == gekozen } + l.filter { it.id != gekozen }
    }
    // focus op de huidige keuze (staat bovenaan) of de eerste regel; na zoeken op de eerste treffer
    LaunchedEffect(items, zoek) {
        if (zichtbaar.isEmpty()) { if (items != null) { delay(40); runCatching { zoekKnop.requestFocus() } }; return@LaunchedEffect }
        lijst.scrollToItem(0)
        delay(40)
        repeat(5) { if (runCatching { fr.requestFocus() }.isSuccess) return@LaunchedEffect; delay(16) }
    }
    var wasOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color(0xF60A0A0A)).focusVangen().padding(horizontal = 64.dp, vertical = 36.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(titel, style = MaterialTheme.typography.displaySmall)
                    if (items != null) Text(Taal.getal(zichtbaar.size) + if (zoek.isNotBlank()) "  ·  “$zoek”" else "", style = MaterialTheme.typography.bodyMedium, color = Kleur.zacht)
                }
                if (!items.isNullOrEmpty()) Keuze(
                    selected = zoek.isNotBlank(), onClick = { zoekOpen = true }, modifier = Modifier.focusRequester(zoekKnop),
                    leadingIcon = { Icon(androidx.compose.material.icons.Icons.Filled.Search, null, Modifier.width(18.dp)) },
                    tekst = zoek.ifBlank { stringResource(R.string.zoeken) },
                )
            }
            Spacer(Modifier.height(16.dp))
            when {
                items == null -> Laadpil(true)
                zichtbaar.isEmpty() -> Text(stringResource(R.string.niets_gevonden), style = MaterialTheme.typography.bodyLarge, color = Kleur.zacht)
                else -> LazyColumn(state = lijst, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp)) {
                    items(zichtbaar, key = { it.id }) { it2 ->
                        KiesRegel(it2, it2.id == gekozen, if (it2 === zichtbaar.first()) Modifier.focusRequester(fr) else Modifier) { kies(it2.id, it2.naam) }
                    }
                }
            }
        }
    }
    LaunchedEffect(zoekOpen) { if (wasOpen && !zoekOpen && zichtbaar.isEmpty()) { delay(16); runCatching { zoekKnop.requestFocus() } }; wasOpen = zoekOpen }
    if (zoekOpen) TekstInvoer(titel, zoek, live = { zoek = it }, actie = stringResource(R.string.actie_klaar), sluit = { zoekOpen = false }) { zoek = it; zoekOpen = false }
}

@Composable
private fun KiesRegel(item: KiesItem, gekozen: Boolean, modifier: Modifier, onKlik: () -> Unit) {
    var focus by remember { mutableStateOf(false) }
    androidx.tv.material3.Surface(
        onClick = onKlik, modifier = modifier.fillMaxWidth().height(64.dp).onFocusChanged { focus = it.isFocused },
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(containerColor = Kleur.vlak, focusedContainerColor = Kleur.primair, focusedContentColor = Kleur.opPrimair),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val vorm = if (item.rond) androidx.compose.foundation.shape.CircleShape else RoundedCornerShape(8.dp)
            Box(Modifier.size(46.dp).clip(vorm).background(Kleur.vlakHoog)) {
                if (item.beeld != null) coil3.compose.AsyncImage(beeld(item.beeld), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(item.naam, style = MaterialTheme.typography.titleMedium, color = if (focus) Kleur.opPrimair else Kleur.tekst, maxLines = 1)
                item.sub?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = if (focus) Kleur.opPrimair.copy(alpha = 0.8f) else Kleur.zacht, maxLines = 1) }
            }
            if (gekozen) Icon(Icons.Filled.CheckCircle, null, Modifier.padding(end = 8.dp), tint = if (focus) Kleur.opPrimair else Kleur.primair)
        }
    }
}

/** Albums voor de keuzelijst: omslag, naam + aantal/periode; laatst bijgewerkt eerst. */
@Composable
fun albumKeuzes(): List<KiesItem>? {
    val l by produceState<List<KiesItem>?>(null) {
        value = runCatching { Immich.albums().filter { it.aantal > 0 }.sortedByDescending { it.bijgewerkt }.map { KiesItem(it.id, it.naam, albumSub(it), it.omslag?.let(Immich::thumb)) } }.getOrDefault(emptyList())
    }
    return l
}

/** Personen: gezicht + naam; in de volgorde van Immich (favorieten, dan wie het vaakst voorkomt). */
@Composable
fun persoonKeuzes(): List<KiesItem>? {
    val l by produceState<List<KiesItem>?>(null) {
        value = runCatching { Immich.personen().map { KiesItem(it.id, it.naam, null, Immich.gezicht(it.id), rond = true) } }.getOrDefault(emptyList())
    }
    return l
}
