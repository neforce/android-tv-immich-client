package com.immichclient.tv

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.service.dreams.DreamService
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.PhotoLibrary
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.SuccessResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.roundToInt
import kotlin.random.Random

/*
 * Screensaver (DreamService) met dezelfde sfeer als de viewer: grote foto (crossfade, Ken Burns), klok + datum
 * linksboven, de landkaart met stip rechtsonder (Kaart.kt), een timer-ringetje en datum/plaats linksonder.
 * Bron, interval en de onderdelen zijn instelbaar (Instellingen → Screensaver). ◀ ▶ = vorige/volgende foto, elke
 * andere toets (behalve volume) sluit de screensaver, zoals een gewone screensaver.
 *
 * Zuinig met geheugen (de screensaver draait naast andere apps): previews op schermmaat via dezelfde Coil-cache als de
 * app, en nooit meer dan drie beelden tegelijk (de huidige, de vorige die uitvloeit, de volgende vooraf geladen).
 */

/** Wat de screensaver toont; één per keer dat hij start. */
class DreamStaat {
    var huidig by mutableStateOf<Asset?>(null)
    /** telt bij elke nieuwe foto op (het ringetje begint dan opnieuw) */
    var ronde by mutableIntStateOf(0)
    /** melding in het midden; alleen zolang er nog geen foto staat */
    var melding by mutableStateOf<Int?>(null)
    /** ophalen mislukt terwijl er al een foto staat: die blijft staan, met een klein wolkje bij het ringetje */
    var storing by mutableStateOf(false)
    /** wens van de afstandsbediening: -1 vorige, +1 volgende (alleen de laatste telt) */
    val wens = Channel<Int>(Channel.CONFLATED)
    val geschiedenis = ArrayList<Asset>()
    var plek = -1
    /** alleen om te testen (VoorbeeldActivity, `--es test_fout koppeling|sleutel|net|leeg`): bootst die fout na */
    var testFout: String? = null
    private var laatsteToets = 0L

    /** Toets ◀/▶: max. 1× per 600 ms, anders stapelen grote previews zich op. */
    fun stuur(richting: Int) {
        val nu = SystemClock.elapsedRealtime()
        if (nu - laatsteToets < 600) return
        laatsteToets = nu
        wens.trySend(richting)
    }
}

/**
 * Levert foto's volgens de instelling: alles (willekeurig, via /search/random in porties), een album / favorieten /
 * een persoon (maandlijst, gewogen willekeurige maand, dan een willekeurige foto; hooguit 3 maanden in het geheugen) of
 * de herinneringen van vandaag. Video's worden overgeslagen; onlangs getoonde foto's komen niet meteen terug. Is de
 * gekozen bron leeg (geen album gekozen, geen favorieten, geen herinneringen vandaag), dan komen er willekeurige foto's.
 */
class DreamVoorraad {
    private val wachtrij = ArrayDeque<Asset>()
    private val recent = ArrayDeque<String>()
    private var maanden: List<Maand>? = null
    private var filter: Filter? = null
    private val maandCache = object : LinkedHashMap<String, List<Asset>>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Asset>>?) = size > 3
    }
    private var herinneringen: List<Asset>? = null
    private var herinneringDag: LocalDate? = null
    private var herinneringIndex = 0
    /** foto's die echt uit de gekozen bron kwamen (niet uit de terugval): alleen die krijgen het bronlabel */
    private val bronIds = LinkedHashSet<String>()
    /** alvast opgehaalde volgende (en vooraf geladen), zie [kijkVooruit] */
    private var vooruit: Asset? = null
    /** volgende() en kijkVooruit() nooit tegelijk (beide halen anders dezelfde portie op) */
    private val slot = kotlinx.coroutines.sync.Mutex()

    fun uitBron(id: String) = id in bronIds

    suspend fun volgende(): Asset? = slot.withLock {
        vooruit?.let { vooruit = null; return@withLock it }
        haal()
    }

    /** De volgende alvast bepalen en zijn preview laden, terwijl de huidige te zien is. */
    suspend fun kijkVooruit(ctx: Context) = slot.withLock {
        if (vooruit != null) return@withLock
        val a = runCatching { haal() }.onFailure { Log.w("Fotos", "screensaver vooruit", it) }.getOrNull() ?: return@withLock
        vooruit = a
        SingletonImageLoader.get(ctx).enqueue(previewVerzoek(ctx, a.id))
    }

    private suspend fun haal(): Asset? = when (Instellingen.dreamBron) {
        DreamBron.ALLES -> uitPortie()
        DreamBron.HERINNERINGEN -> markeer(uitHerinneringen()) ?: uitPortie()
        DreamBron.ALBUM -> Instellingen.dreamAlbum.takeIf { it.isNotBlank() }?.let { markeer(uitMaanden(Filter(albumId = it))) } ?: uitPortie()
        DreamBron.FAVORIETEN -> markeer(uitMaanden(Filter(favorieten = true))) ?: uitPortie()
        DreamBron.PERSOON -> Instellingen.dreamPersoon.takeIf { it.isNotBlank() }?.let { markeer(uitMaanden(Filter(persoonId = it))) } ?: uitPortie()
    }

    private fun markeer(a: Asset?): Asset? {
        if (a != null) { bronIds.add(a.id); if (bronIds.size > 120) bronIds.remove(bronIds.first()) }
        return a
    }

    private suspend fun uitPortie(): Asset? {
        if (wachtrij.size < 3) wachtrij.addAll(Immich.willekeurig(40).filter { !it.video && it.id !in recent && wachtrij.none { w -> w.id == it.id } })
        return wachtrij.removeFirstOrNull()?.let { onthoud(it) }
    }

    private suspend fun uitMaanden(f: Filter): Asset? {
        if (filter != f || maanden == null) { maanden = Immich.maanden(f, true).filter { it.aantal > 0 }; filter = f; maandCache.clear(); recent.clear() }
        val ms = maanden ?: return null
        val totaal = ms.sumOf { it.aantal }
        if (totaal == 0) return null
        var laatste: Asset? = null
        repeat(10) {
            var r = Random.nextInt(totaal)
            val m = ms.first { r -= it.aantal; r < 0 }
            val lijst = maandCache.getOrPut(m.sleutel) { Immich.maand(f, true, m.sleutel) }
            val a = lijst.filter { !it.video }.randomOrNull() ?: return@repeat
            laatste = a
            if (a.id !in recent) return onthoud(a, totaal)
        }
        return laatste?.let { onthoud(it, totaal) }
    }

    private suspend fun uitHerinneringen(): Asset? {
        val vandaag = LocalDate.now()
        if (herinneringen == null || herinneringDag != vandaag) {
            herinneringen = Immich.herinneringen(vandaag).flatMap { it.assets }.filter { !it.video }.shuffled()
            herinneringDag = vandaag; herinneringIndex = 0
        }
        val l = herinneringen ?: return null
        if (l.isEmpty()) return null
        return l[herinneringIndex++ % l.size]
    }

    private fun onthoud(a: Asset, totaal: Int = Int.MAX_VALUE): Asset {
        recent.addLast(a.id)
        val max = (totaal / 2).coerceIn(2, 80)
        while (recent.size > max) recent.removeFirst()
        return a
    }
}

/** Fout bij het ophalen → melding: sleutel geweigerd, bron bestaat niet meer (album weg) of geen verbinding. */
private fun meldingVoor(e: Throwable): Int {
    val m = e.message.orEmpty()
    return when {
        m.startsWith("Immich 401") || m.startsWith("Immich 403") -> R.string.dream_sleutel_fout
        m.startsWith("Immich 4") -> R.string.dream_geen_fotos
        else -> R.string.dream_geen_verbinding
    }
}

/** Label van de bron voor de regel linksonder (albumnaam, "3 jaar geleden", …); null bij "alle foto's" of de terugval. */
@Composable
private fun bronNaam(a: Asset, voorraad: DreamVoorraad): String? {
    if (Instellingen.dreamBron == DreamBron.ALLES || !voorraad.uitBron(a.id)) return null
    return when (Instellingen.dreamBron) {
        DreamBron.ALLES -> null
        DreamBron.ALBUM -> Instellingen.dreamAlbumNaam.ifBlank { null }
        DreamBron.PERSOON -> Instellingen.dreamPersoonNaam.ifBlank { null }
        DreamBron.FAVORIETEN -> stringResource(R.string.sectie_favorieten)
        DreamBron.HERINNERINGEN -> (LocalDate.now().year - a.datum.year).takeIf { it > 0 }?.let { pluralStringResource(R.plurals.jaren_geleden, it, it) }
    }
}

private val SCHADUW = Shadow(Color(0xD9000000), Offset(0f, 2f), 10f)

/** Zachte verschuiving van klok/tekst (een paar dp, elke 4 minuten): niets staat urenlang op exact dezelfde pixels. */
private val DRIFT = listOf(Offset(0f, 0f), Offset(10f, 6f), Offset(-6f, 12f), Offset(12f, -4f), Offset(-10f, -8f), Offset(4f, 10f))

/** Geheugencache na elke wissel terugbrengen tot ±3 previews (huidige, vorige, volgende); de rest staat op schijf. */
private const val GEHEUGEN = 24L * 1024 * 1024

@Composable
fun DreamScherm(staat: DreamStaat) {
    val ctx = LocalContext.current
    val voorraad = remember { DreamVoorraad() }
    val seconden = Instellingen.dreamSeconden

    // de lus: foto kiezen → preview laden → tonen → wachten op de tijd of een toets
    LaunchedEffect(Unit) {
        if (Immich.sleutel.isBlank() || Immich.server.isBlank() || staat.testFout == "koppeling") { staat.melding = R.string.dream_niet_gekoppeld; return@LaunchedEffect }
        val loader = SingletonImageLoader.get(ctx)
        suspend fun nieuw(): Asset? = when (staat.testFout) {
            "leeg" -> null
            "net" -> throw java.net.ConnectException("test")
            "sleutel" -> throw java.io.IOException("Immich 401: test")
            else -> voorraad.volgende()
        }
        /** mislukt: zonder foto een melding in het midden, met foto alleen het wolkje (de foto blijft staan) */
        fun mislukt(m: Int) { if (staat.huidig == null) staat.melding = m else staat.storing = true }
        var richting = 1
        while (isActive) {
            val g = staat.geschiedenis
            var wacht = 20_000L
            val a: Asset? = when {
                richting < 0 && staat.plek > 0 -> { staat.plek--; g[staat.plek] }
                richting < 0 -> staat.huidig // al bij de oudste: blijven staan
                richting > 0 && staat.plek < g.lastIndex -> { staat.plek++; g[staat.plek] }
                else -> runCatching { nieuw() }.fold(
                    onSuccess = { n -> n?.also { g.add(it); if (g.size > 30) g.removeAt(0); staat.plek = g.lastIndex } },
                    onFailure = { e ->
                        Log.w("Fotos", "screensaver: ${e.message}")
                        val m = meldingVoor(e)
                        if (m != R.string.dream_geen_verbinding) wacht = 60_000L
                        mislukt(m); null
                    },
                )
            }
            if (a == null) {
                if (staat.huidig == null && staat.melding == null) staat.melding = R.string.dream_geen_fotos
                richting = withTimeoutOrNull(wacht) { staat.wens.receive() } ?: 1
                continue
            }
            if (a.id != staat.huidig?.id) {
                // eerst de preview binnenhalen (max. 8 s), dan pas wisselen: geen half geladen of lege foto
                val ok = withTimeoutOrNull(8_000) { runCatching { loader.execute(previewVerzoek(ctx, a.id)) is SuccessResult }.getOrDefault(false) } == true
                if (!ok) {
                    Log.w("Fotos", "screensaver: preview ${a.id} niet geladen")
                    mislukt(R.string.dream_geen_verbinding)
                    richting = withTimeoutOrNull(wacht) { staat.wens.receive() } ?: 1
                    continue
                }
                staat.melding = null
                staat.storing = false
                staat.huidig = a
                // na de overgang: cache klein houden en de volgende alvast klaarzetten
                launch { delay(1800); loader.memoryCache?.trimToSize(GEHEUGEN); voorraad.kijkVooruit(ctx) }
            }
            staat.ronde++
            richting = withTimeoutOrNull(Instellingen.dreamSeconden * 1000L) { staat.wens.receive() } ?: 1
        }
    }

    // inbranden voorkomen: alles wat stilstaat schuift af en toe een paar dp
    val driftDoel by produceState(DRIFT[0]) { var i = 0; while (true) { delay(4 * 60_000L); i++; value = DRIFT[i % DRIFT.size] } }
    val drift by animateOffsetAsState(driftDoel, tween(4000), label = "drift")
    val verschuif = Modifier.offset { IntOffset((drift.x * density).roundToInt(), (drift.y * density).roundToInt()) }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val dicht = LocalDensity.current
        val breedtePx = with(dicht) { maxWidth.roundToPx() }
        val hoogtePx = with(dicht) { maxHeight.roundToPx() }
        val huidig = staat.huidig
        val kenBurns = Instellingen.dreamKenBurns

        AnimatedContent(
            targetState = huidig, contentKey = { it?.id },
            // scale 1→1: houdt de oude foto 1,4 s vast; de nieuwe (bovenop) vloeit eroverheen in DreamBeeld (ModulateAlpha,
            // geen extra laag). De oude blijft volledig zichtbaar tot hij weg is: geen donkere dip halverwege.
            transitionSpec = { scaleIn(tween(1400), initialScale = 1f) togetherWith scaleOut(tween(1400), targetScale = 1f) },
            label = "dream",
        ) { a ->
            val zicht by transition.animateFloat(transitionSpec = { tween(1100) }, label = "zicht") { if (it == EnterExitState.PreEnter) 0f else 1f }
            if (a != null) DreamBeeld(a, actief = a.id == huidig?.id, kenBurns = kenBurns, duurMs = seconden * 1000L + 2500, zicht = { zicht })
        }

        if (Instellingen.dreamKaart) KaartLaag(doel = huidig?.takeIf { it.lat != null && it.lon != null }, breedtePx = breedtePx, hoogtePx = hoogtePx)

        if (Instellingen.dreamKlok) Klok(Modifier.align(Alignment.TopStart).then(verschuif).padding(start = 48.dp, top = 36.dp))

        // linksonder: ringetje (tijd tot de volgende foto) + datum en plaats/bron
        if (huidig != null) Row(Modifier.align(Alignment.BottomStart).then(verschuif).padding(start = 48.dp, bottom = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            val ring = remember { Animatable(0f) }
            LaunchedEffect(staat.ronde) { ring.snapTo(0f); if (staat.ronde > 0) ring.animateTo(1f, tween(seconden * 1000, easing = LinearEasing)) }
            VoortgangRing({ ring.value }, Modifier.size(22.dp))
            if (staat.storing) Icon(Icons.Filled.CloudOff, null, Modifier.padding(start = 10.dp).size(20.dp), tint = Color(0xB3FFFFFF))
            if (Instellingen.dreamInfo) Column(Modifier.padding(start = 16.dp)) {
                Text(huidig.datum.format(Formaat.dagLang).replaceFirstChar { it.titlecase(Taal.locale) }, style = TextStyle(fontFamily = Overpass, fontSize = 18.sp, color = Color.White, shadow = SCHADUW), maxLines = 1)
                // met kaart staat de plaats bij de stip; anders hier
                val plaats = listOfNotNull(huidig.stad, huidig.land).joinToString(", ").takeIf { it.isNotEmpty() && !(Instellingen.dreamKaart && huidig.lat != null) }
                val regel = listOfNotNull(plaats, bronNaam(huidig, voorraad)).joinToString("  ·  ")
                if (regel.isNotEmpty()) Text(regel, style = TextStyle(fontFamily = Overpass, fontSize = 15.sp, color = Color(0xD9FFFFFF), shadow = SCHADUW), maxLines = 1)
            }
        }

        if (huidig == null) staat.melding?.let { m ->
            Column(Modifier.align(Alignment.Center).then(verschuif).padding(horizontal = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if (m == R.string.dream_geen_verbinding) Icons.Filled.CloudOff else Icons.Filled.PhotoLibrary, null, Modifier.size(44.dp), tint = Kleur.zacht)
                Text(stringResource(m), style = MaterialTheme.typography.bodyLarge, color = Kleur.zacht, modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}

/** Klok + datum in de taal van de app; de tekst verandert alleen als de minuut wisselt. */
@Composable
private fun Klok(modifier: Modifier) {
    val nu by produceState(LocalDateTime.now()) { while (true) { value = LocalDateTime.now().withSecond(0).withNano(0); delay(1000) } }
    Column(modifier) {
        Text(nu.format(Formaat.tijd), style = TextStyle(fontFamily = Overpass, fontSize = 64.sp, fontWeight = FontWeight(600), color = Color.White, shadow = SCHADUW), maxLines = 1)
        Text(nu.format(Formaat.dagLang).replaceFirstChar { it.titlecase(Taal.locale) }, style = TextStyle(fontFamily = Overpass, fontSize = 22.sp, color = Color(0xE6FFFFFF), shadow = SCHADUW), maxLines = 1)
    }
}

/**
 * Eén foto: liggende foto's rond 3:2 vullen het scherm (bijgesneden), de rest past erin met de thumbhash als wazige
 * achtergrond. Ken Burns: langzaam inzoomen met een lichte zwenking, alleen op de actieve foto (de vertrekkende houdt
 * zijn zoom, anders springt hij terug tijdens de crossfade).
 */
@Composable
private fun DreamBeeld(a: Asset, actief: Boolean, kenBurns: Boolean, duurMs: Long, zicht: () -> Float) {
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = zicht(); compositingStrategy = CompositingStrategy.ModulateAlpha }, contentAlignment = Alignment.Center) {
        val vullen = a.ratio in 1.45f..2.0f
        val zoom = remember { Animatable(1f) }
        val richting = remember(a.id) { Random.nextInt(-1, 2) }
        LaunchedEffect(a.id, kenBurns) { if (kenBurns && actief) zoom.animateTo(1.08f, tween(duurMs.toInt(), easing = LinearEasing)) }
        val mod = Modifier.fillMaxSize().graphicsLayer {
            val z = zoom.value
            scaleX = z; scaleY = z
            // zwenking: de rand blijft binnen beeld ((z-1)/2 van de breedte speling aan elke kant)
            translationX = (z - 1f) * size.width * 0.5f * richting
        }
        val hash = remember(a.thumbhash) { a.thumbhash?.let { ThumbHash.bitmap(it) } }
        if (hash != null && !vullen) {
            Image(hash, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.7f)
            Box(Modifier.fillMaxSize().background(Color(0x8C000000)))
            Image(hash, null, Modifier.aspectRatio(a.ratio.coerceIn(0.3f, 4f), matchHeightConstraintsFirst = true), contentScale = ContentScale.FillBounds)
        }
        AsyncImage(preview(a.id), null, mod, contentScale = if (vullen) ContentScale.Crop else ContentScale.Fit)
    }
}

/**
 * De screensaver zelf. Compose in een DreamService: de ComposeView heeft een LifecycleOwner, SavedStateRegistryOwner
 * en ViewModelStoreOwner nodig in de view-boom; die speelt de service zelf (levensloop: aangekoppeld = CREATED,
 * dromen = RESUMED). Toetsen gaan via dispatchKeyEvent (interactief), niet via Compose.
 */
class FotoDream : DreamService(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val register = LifecycleRegistry(this)
    private val bewaarder = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = register
    override val savedStateRegistry: SavedStateRegistry get() = bewaarder.savedStateRegistry
    override val viewModelStore = ViewModelStore()
    private val staat = DreamStaat()

    override fun onCreate() {
        super.onCreate()
        // Zelfde proces als de app (moet, zie het manifest). Staat de app nog in het geheugen (bv. met de wereldkaart),
        // dan eerst alles wat weg kan vrijgeven: het systeem rekent het hele proces als voorgrond zolang de screensaver
        // draait, en ruimde bij het wekken anders van alles op (zwart scherm, 21-10). De kaart zelf gaat weg zodra de
        // activity stopt (KaartScherm).
        (application as? App)?.ontlast("screensaver")
        bewaarder.performAttach()
        bewaarder.performRestore(null)
        register.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true
        isFullscreen = true
        isScreenBright = true
        val view = ComposeView(Taal.wrap(this))
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        view.setContent { FotosThema { DreamScherm(staat) } }
        setContentView(view)
        isAttached = true
        Log.i("Fotos", "screensaver: gestart (${Instellingen.dreamBron}, ${Instellingen.dreamSeconden} s)")
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        register.handleLifecycleEvent(Lifecycle.Event.ON_START)
        register.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) staat.stuur(if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                return true
            }
            // volume gewoon laten werken, zonder de screensaver te sluiten
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> return super.dispatchKeyEvent(event)
        }
        // Andere toets: wakker worden bij het LOSLATEN (de key-up mag niet bij de app eronder terechtkomen: in een
        // launcher is dat een klik op de tegel die focus heeft). Blijft de toets ingedrukt, of reageert het systeem
        // niet op wakeUp(), dan na 1,5 s alsnog: de screensaver mag nooit blijven hangen.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) wekUiterlijk(1500)
        if (event.action == KeyEvent.ACTION_UP) wek()
        return true
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var gewekt = false

    private fun wek() {
        if (gewekt) return
        gewekt = true
        Log.i("Fotos", "screensaver: toets, wakker worden")
        runCatching { wakeUp() }.onFailure { Log.w("Fotos", "screensaver wakeUp", it); finish() }
        // vangnet: na 2 s nog niet weg → hard stoppen
        handler.postDelayed({ if (isAttached) { Log.w("Fotos", "screensaver: wakeUp hielp niet, finish()"); runCatching { finish() } } }, 2000)
    }

    private fun wekUiterlijk(ms: Long) = handler.postDelayed({ if (!gewekt) wek() }, ms)

    private var isAttached = false

    override fun onDreamingStopped() {
        register.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        register.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        isAttached = false
        handler.removeCallbacksAndMessages(null)
        if (register.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            register.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            register.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        register.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
        super.onDetachedFromWindow()
        Log.i("Fotos", "screensaver: gestopt")
    }
}

/** Instellingen → Screensaver → Voorbeeld: dezelfde weergave als gewone activity (ook om te testen zonder de systeeminstelling). */
class VoorbeeldActivity : ComponentActivity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(Taal.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Immich.kiesAccount(this, null)
        val staat = DreamStaat().apply { testFout = intent.getStringExtra("test_fout") }
        setContent {
            FotosThema {
                Box(
                    Modifier.fillMaxSize().onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key.nativeKeyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> { staat.stuur(-1); true }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> { staat.stuur(1); true }
                            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> false
                            else -> { finish(); true }
                        }
                    },
                ) { DreamScherm(staat) }
            }
        }
    }
}
