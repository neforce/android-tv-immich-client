package com.immichclient.tv

import android.app.Application
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ModalNavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.NavigationDrawerItemDefaults
import androidx.tv.material3.Text
import androidx.tv.material3.rememberDrawerState
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath

class App : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        Taal.start(this)
        Immich.kiesAccount(this, null)
    }

    /**
     * Eén ImageLoader voor alles: OkHttp mét sleutel, 400 MB op schijf (thumbnails ~15 kB, previews ~300 kB; een
     * tv-box heeft vaak maar een paar GB vrij, dus niet groter). Geheugencache bewust klein en vast: een box met 2 GB
     * ruimt bij geheugennood ook de app vooraan op (gezien tijdens een video). Bitmaps zijn hardware-bitmaps
     * (GPU-geheugen, telt mee in "Graphics" van meminfo); met 35 % van de grote heap stond er 220 MB aan previews in de cache.
     */
    override fun newImageLoader(context: PlatformContext) = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { Immich.http })) }
        .memoryCache { MemoryCache.Builder().maxSizeBytes(CACHE_MB * 1024L * 1024).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("beelden").toOkioPath()).maxSizeBytes(400L * 1024 * 1024).build() }
        .build()

    // Geheugennood of naar de achtergrond: cache leeg (thumbnails komen in ms terug van schijf).
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            android.util.Log.i("Fotos", "onTrimMemory $level: geheugencache leeg")
            SingletonImageLoader.get(this).memoryCache?.clear()
            openKaart?.onLowMemory() // MapLibre: tegelcache in het geheugen leeg
            if (level >= TRIM_MEMORY_RUNNING_CRITICAL) ThumbHash.leeg()
        }
    }

    companion object { const val CACHE_MB = 56 }
}

/** Een scherm met zijn eigen staat; blijft bewaard zolang het op de stapel staat (terug = zelfde plek, zelfde focus). */
sealed class Scherm {
    class Tijdlijn(
        val titel: String, val sub: String?, filter: Filter, nieuwsteEerst: Boolean, val metHerinneringen: Boolean, scope: CoroutineScope,
    ) : Scherm() { val staat = TijdlijnStaat(filter, nieuwsteEerst, scope) }
    class Albums(scope: CoroutineScope) : Scherm() { val staat = AlbumsStaat(scope) }
    class Verkennen(scope: CoroutineScope) : Scherm() { val staat = VerkenStaat(scope) }
    class Zoeken(scope: CoroutineScope) : Scherm() { val staat = ZoekStaat(scope) }
    class Herinneringen(scope: CoroutineScope) : Scherm() { val staat = HerinneringStaat(scope) }
    class Kaart(scope: CoroutineScope) : Scherm() { val staat = KaartStaat(scope) }
    data object Instellingen : Scherm()
    class Lijst(val titel: String, val sub: String?, val perDag: Boolean, scope: CoroutineScope, laad: suspend (Int) -> Pagina) : Scherm() {
        val res = Resultaten(scope).also { it.start(laad) }
    }
}

class Nav(
    val scope: CoroutineScope,
    val open: (Scherm) -> Unit,
    private val toon: (bron: BeeldBron, index: Int, titel: String?, diashow: Boolean, raster: RasterStaat?, naTerug: (() -> Unit)?) -> Unit,
) {
    /** Viewer openen; [raster] krijgt de focus terug, of [naTerug] wordt aangeroepen bij het sluiten (kaart). */
    fun bekijk(bron: BeeldBron, index: Int, titel: String?, diashow: Boolean, raster: RasterStaat?, naTerug: (() -> Unit)? = null) =
        toon(bron, index, titel, diashow, raster, naTerug)
}

/** Signalen van toetsen die de activity afvangt (Terug lang ingedrukt). */
object Toetsen { var langeTerug by androidx.compose.runtime.mutableIntStateOf(0) }

enum class Sectie(@StringRes val label: Int, val icoon: ImageVector) {
    FOTOS(R.string.sectie_fotos, Icons.Filled.Photo),
    HERINNERINGEN(R.string.sectie_herinneringen, Icons.Filled.AutoAwesome),
    ZOEKEN(R.string.sectie_zoeken, Icons.Filled.Search),
    VERKENNEN(R.string.sectie_verkennen, Icons.Filled.Explore),
    KAART(R.string.sectie_kaart, Icons.Filled.Map),
    ALBUMS(R.string.sectie_albums, Icons.Filled.PhotoLibrary),
    FAVORIETEN(R.string.sectie_favorieten, Icons.Filled.Favorite),
    INSTELLINGEN(R.string.sectie_instellingen, Icons.Filled.Settings),
}

class MainActivity : ComponentActivity() {
    /** OK/Enter pas doorlaten na een eigen KeyDown: een losse key-up/repeat van een launcher-tegel mag hier niets "klikken" */
    private var okGezien = false
    private var start = 0L

    /** De gekozen taal, los van de systeemtaal (zie Taal). */
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(Taal.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        start = android.os.SystemClock.uptimeMillis()
        // een eigen launcher kan het profiel meegeven ("kijker"); per profiel kan dan een eigen Immich-account gelden
        Immich.kiesAccount(this, intent.getStringExtra("kijker"))
        // Test-extra's alleen bij een verse start vanaf de shell (`am start`, referrer com.android.shell). Bij een herstart
        // van de taak (proces opgeruimd door geheugennood, daarna de tegel in de launcher) geeft Android het óórspronkelijke
        // intent van de taak opnieuw mee (savedInstanceState != null) en zou `--ei test_index` blijven plakken: de app
        // opende dan "uit het niets" in de viewer.
        val vanShell = referrer?.host == "com.android.shell"
        val test = savedInstanceState == null && vanShell
        Log.i("Fotos", "start: referrer=$referrer hersteld=${savedInstanceState != null} test=$test extras=${intent.extras?.keySet()?.joinToString()}")
        // test: `am start … --ei test_index 91` opent de viewer meteen op die foto in de tijdlijn
        val testIndex = if (test) intent.getIntExtra("test_index", -1) else -1
        // test: `--es sectie KAART` opent meteen die sectie; anders het ingestelde startscherm
        val startSectie = intent.getStringExtra("sectie")?.takeIf { test }?.let { s -> Sectie.entries.firstOrNull { it.name == s } }
            ?: Taal.herstartSectie?.also { Taal.herstartSectie = null } // na een taalwissel terug in Instellingen
            ?: Instellingen.start
        // test: `--ez instel_test true` toont het instelscherm zonder de bestaande koppeling te wissen
        val instelTest = test && intent.getBooleanExtra("instel_test", false)
        // test: `--ez taal_test true` toont het taalkeuzescherm van de eerste start (de koppeling blijft staan)
        val taalTest = test && intent.getBooleanExtra("taal_test", false)
        if (test) {
            kaartTexture = intent.getBooleanExtra("kaart_texture", false); kaartLod = intent.getIntExtra("kaart_lod", kaartLod)
            kaartFps = intent.getIntExtra("kaart_fps", 0); kaartAlles = intent.getBooleanExtra("kaart_alles", false)
            kaartRes = intent.getIntExtra("kaart_res", kaartRes); kaartVlucht = intent.getIntExtra("kaart_vlucht", kaartVlucht)
        }
        setContent { FotosThema { FotosApp(finish = ::finish, herstart = ::recreate, testIndex = testIndex, startSectie = startSectie, instelTest = instelTest, taalTest = taalTest) } }
    }

    /** Terug lang ingedrukt = menu (lade); de key-up erna mag dan geen gewone Terug meer zijn */
    private var terugLang = false

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        // Terug lang ingedrukt: overal het menu. KEYCODE_MENU onderscheppen sommige boxen (NVIDIA Shield) op systeemniveau,
        // daarom deze toets; MENU blijft ernaast voor afstandsbedieningen die hem wél doorgeven (zie FotosApp).
        if (e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount > 0) {
                if (!terugLang) { terugLang = true; Toetsen.langeTerug++ }
                return true
            }
            if (e.action == KeyEvent.ACTION_UP && terugLang) { terugLang = false; return true }
        }
        if (e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER) {
            // eerste halve seconde na het starten helemaal geen OK (ook geen verse: dubbele druk op de tegel)
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && android.os.SystemClock.uptimeMillis() - start > 500) okGezien = true
            if (!okGezien) { Log.i("Fotos", "OK genegeerd (actie ${e.action}, herhaling ${e.repeatCount})"); return true }
        }
        return super.dispatchKeyEvent(e)
    }

    // singleTask: draait hij al en komt er een ander profiel binnen met een eigen account, dan opnieuw opbouwen
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        okGezien = false
        val oud = Immich.sleutel
        Immich.kiesAccount(this, intent.getStringExtra("kijker"))
        if (Immich.sleutel != oud) recreate()
    }
}

@Composable
private fun FotosApp(finish: () -> Unit, herstart: () -> Unit, testIndex: Int = -1, startSectie: Sectie = Sectie.FOTOS, instelTest: Boolean = false, taalTest: Boolean = false) {
    // Eerste start: eerst de taal kiezen (voorgeselecteerd op de systeemtaal); daarna opnieuw opbouwen in die taal
    var taalKiezen by remember { mutableStateOf(taalTest || !Taal.gekozen) }
    if (taalKiezen) { TaalScherm(finish = finish) { taalKiezen = false; herstart() }; return }
    // Geen account (eerste start, of na Ontkoppelen) of "Opnieuw koppelen": instelscherm met QR-code. Na een geslaagde
    // koppeling de activity opnieuw opbouwen: alle schermen hangen aan het oude adres/account.
    val stand = Immich.stand
    var instellen by remember { mutableStateOf(instelTest) }
    val gekoppeld = Immich.sleutel.isNotBlank() && stand >= 0
    if (!gekoppeld || instellen) {
        // via Instellingen → Opnieuw koppelen is het nooit een test (sleutelnaam zonder "(test)")
        InstelScherm(test = instelTest && !instellen, terug = if (gekoppeld) ({ instellen = false }) else null, klaar = { instellen = false; herstart() }, finish = finish)
        return
    }
    val scope = rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(Unit) { Immich.warmZoeken() }
    val wortels = remember { HashMap<Sectie, Scherm>() }
    var sectie by remember { mutableStateOf(startSectie) }
    val stapel = remember { mutableStateListOf<Scherm>() }
    var kijker by remember { mutableStateOf<Triple<KijkerStaat, RasterStaat?, (() -> Unit)?>?>(null) }
    /** overzicht van de sneltoetsen (INFO-toets of via Instellingen) */
    var toetsen by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val naamApp = stringResource(Instellingen.uiterlijk.label)
    // instellingen die een bestaande tijdlijn raken (volgorde, partner-foto's): toepassen zonder herstart
    val volgorde = Instellingen.nieuwsteEerst; val partners = Instellingen.partners
    androidx.compose.runtime.LaunchedEffect(volgorde, partners) {
        for (s in listOf(Sectie.FOTOS, Sectie.FAVORIETEN)) (wortels[s] as? Scherm.Tijdlijn)?.staat?.pasInstellingenToe()
    }

    val nav = remember {
        Nav(
            scope = scope,
            open = { stapel.add(it) },
            toon = { bron, i, titel, dia, raster, naTerug -> if (i >= 0) kijker = Triple(KijkerStaat(bron, i, titel, dia), raster, naTerug) },
        )
    }
    fun wortel(s: Sectie): Scherm = wortels.getOrPut(s) {
        when (s) {
            Sectie.FOTOS -> Scherm.Tijdlijn(naamApp, null, Filter(), Instellingen.nieuwsteEerst, true, scope)
            Sectie.HERINNERINGEN -> Scherm.Herinneringen(scope)
            Sectie.ZOEKEN -> Scherm.Zoeken(scope)
            Sectie.VERKENNEN -> Scherm.Verkennen(scope)
            Sectie.KAART -> Scherm.Kaart(scope)
            Sectie.ALBUMS -> Scherm.Albums(scope)
            Sectie.FAVORIETEN -> Scherm.Tijdlijn(ctx.getString(R.string.sectie_favorieten), null, Filter(favorieten = true), Instellingen.nieuwsteEerst, false, scope)
            Sectie.INSTELLINGEN -> Scherm.Instellingen
        }
    }
    val scherm = stapel.lastOrNull() ?: wortel(sectie)
    if (testIndex >= 0) androidx.compose.runtime.LaunchedEffect(Unit) {
        val s = (wortel(Sectie.FOTOS) as Scherm.Tijdlijn).staat
        while (s.aantal == 0) kotlinx.coroutines.delay(100)
        kotlinx.coroutines.delay(600)
        nav.bekijk(s, testIndex, naamApp, false, s.raster)
    }

    // Terug: toetsenoverzicht dicht → sub-scherm sluiten → naar het startscherm → in Foto's naar boven → app uit (terug naar de launcher)
    BackHandler(enabled = kijker == null) {
        val t = (scherm as? Scherm.Tijdlijn)?.staat?.raster?.lijst
        when {
            toetsen -> toetsen = false
            stapel.isNotEmpty() -> stapel.removeAt(stapel.lastIndex)
            sectie != startSectie -> sectie = startSectie
            t != null && t.firstVisibleItemIndex > 0 -> scope.launch {
                t.scrollToItem(0)
                (scherm as Scherm.Tijdlijn).staat.let { s -> s.maanden.firstOrNull()?.let { m -> s.geladen[m.sleutel]?.firstOrNull()?.let { s.raster.focusDoel = it.id } } }
            }
            else -> finish()
        }
    }

    val ladeFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    // menu openen = focus op het lade-item van de huidige sectie (de lade van tv-material opent op focus)
    fun openLade() { toetsen = false; runCatching { ladeFocus.requestFocus() }.onFailure { Log.w("Fotos", "lade", it) } }
    val langeTerug = Toetsen.langeTerug
    androidx.compose.runtime.LaunchedEffect(langeTerug) { if (langeTerug > 0 && kijker == null) openLade() }
    Box(
        Modifier.fillMaxSize().background(Kleur.achtergrond).onPreviewKeyEvent { e ->
            if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown || kijker != null) return@onPreviewKeyEvent false
            when (e.key.nativeKeyCode) {
                // MENU (≡ op sommige afstandsbedieningen): overal de lade openen, met de focus op de huidige sectie
                KeyEvent.KEYCODE_MENU -> { openLade(); true }
                // INFO: overzicht van de sneltoetsen (in de viewer is INFO het infopaneel)
                KeyEvent.KEYCODE_INFO -> { toetsen = !toetsen; true }
                else -> false
            }
        },
    ) {
        val lade = rememberDrawerState(DrawerValue.Closed)
        ModalNavigationDrawer(
            drawerState = lade,
            modifier = Modifier.graphicsLayer { alpha = if (kijker != null) 0f else 1f },
            drawerContent = { waarde ->
                Column(
                    Modifier.fillMaxHeight().background(if (waarde == DrawerValue.Open) Kleur.vlak else Kleur.achtergrond)
                        .padding(12.dp).selectableGroup(),
                ) {
                    Spacer(Modifier.height(24.dp))
                    for (s in Sectie.entries) {
                        if (s == Sectie.INSTELLINGEN) Spacer(Modifier.weight(1f))
                        NavigationDrawerItem(
                            selected = sectie == s && stapel.isEmpty(),
                            modifier = if (sectie == s) Modifier.focusRequester(ladeFocus) else Modifier,
                            onClick = {
                                stapel.clear(); sectie = s
                                lade.setValue(DrawerValue.Closed)
                            },
                            leadingContent = { Icon(s.icoon, null) },
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = Kleur.primair.copy(alpha = 0.18f), selectedContentColor = Kleur.primair,
                                focusedContainerColor = Kleur.primair, focusedContentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) { Text(if (s == Sectie.FOTOS) naamApp else stringResource(s.label)) }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            },
        ) {
            Box(Modifier.fillMaxSize().padding(start = 80.dp)) {
                key(scherm) {
                    when (scherm) {
                        is Scherm.Tijdlijn -> TijdlijnScherm(scherm, nav)
                        is Scherm.Albums -> AlbumsScherm(scherm.staat, nav)
                        is Scherm.Verkennen -> VerkenScherm(scherm.staat, nav)
                        is Scherm.Zoeken -> ZoekScherm(scherm.staat, nav)
                        is Scherm.Herinneringen -> HerinneringenScherm(scherm.staat, nav)
                        is Scherm.Kaart -> KaartScherm(scherm.staat, nav)
                        is Scherm.Lijst -> LijstScherm(scherm, nav)
                        is Scherm.Instellingen -> InstellingenScherm(opnieuwKoppelen = { instellen = true }, ontkoppeld = { sectie = Sectie.FOTOS }, toetsen = { toetsen = true })
                    }
                }
            }
        }
        if (toetsen && kijker == null) ToetsenOverzicht { toetsen = false }
        kijker?.let { (k, raster, naTerug) ->
            Kijker(
                k,
                // "Toon op kaart" (OK in het infopaneel): viewer dicht, naar de kaartsectie, inzoomen op die plek
                opKaart = { a ->
                    kijker = null; stapel.clear(); sectie = Sectie.KAART
                    (wortel(Sectie.KAART) as Scherm.Kaart).staat.toon(a)
                },
            ) { laatste ->
                kijker = null
                if (raster != null && laatste != null) raster.terugNaar = laatste.id
                naTerug?.invoke()
            }
        }
    }
}
