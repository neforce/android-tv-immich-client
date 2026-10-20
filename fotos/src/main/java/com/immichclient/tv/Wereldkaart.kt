package com.immichclient.tv

import android.graphics.Point
import android.util.Log
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Wereldkaart zoals in Google Foto's / Immich: alle foto's met GPS geclusterd op een kaart, inzoomen splitst de
 * clusters tot losse foto's. Ondergrond = MapLibre met de donkere vector-tegelstijl van Immich (tiles.immich.cloud,
 * uit /server/config). De clusters doen we zelf (raster per heel zoomniveau + samenvoegen van buren, op een
 * achtergrondthread) en tekenen we als Compose-lagen boven de kaart: zo kunnen ze een thumbnail + aantal tonen,
 * focus krijgen en per frame meebewegen met de camera (positie = wereldcoördinaat × schaal, geen JNI per frame).
 *
 * Bediening: ▲▼◀▶ = focus naar het volgende cluster in die richting (geen cluster meer: kaart schuift op; links
 * niets meer te vinden: de lade); OK = inzoomen op het gekozen cluster, op het diepste niveau (of als het toch
 * niet meer uiteenvalt) de viewer; ⏩⏪ = zoomniveau op/af; Terug = in één vlucht naar de hele wereld, nog eens Terug
 * = sectie verlaten.
 *
 * Soepelheid: de kaart rendert in een SurfaceView (eigen laag, geen TextureView-kopie per frame door
 * HWUI), camera's gaan met easeCamera (rechte interpolatie; animateCamera = flyTo zoomt eerst uit en laadt dan
 * tegels op alle tussenniveaus), en tijdens een beweging blijft de clusterindeling van het oude zoomniveau staan
 * (de markers glijden mee); pas als de camera stilstaat wordt er voor het nieuwe niveau geclusterd. Zo is er geen
 * marker-churn (nieuwe composables + thumbnails) midden in een animatie.
 */

/** Camera van de kaart: middelpunt in webmercator-wereldeenheden (0..1) en schaal in px per wereldeenheid. */
@Immutable
data class Camera(val cx: Double, val cy: Double, val zoom: Double, val schaal: Double)

fun wereldX(lon: Double) = (lon + 180) / 360
fun wereldY(lat: Double): Double {
    val s = sin(lat.coerceIn(-85.05, 85.05) * PI / 180)
    return 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
}
fun lonVan(x: Double) = x * 360 - 180
fun latVan(y: Double) = (2 * atan(exp((0.5 - y) * 2 * PI)) - PI / 2) * 180 / PI

/** Groep markers op één plek: sleutel = zoomniveau + rastervak (stabiel binnen een zoomniveau), leden = marker-indexen (nieuwste eerst). */
class Cluster(val sleutel: String, val wx: Double, val wy: Double, val leden: IntArray) {
    val aantal get() = leden.size
    /** representant voor de thumbnail: de middelste (de nieuwste is nogal eens een donkere/toevallige foto) */
    val hoofd get() = leden[leden.size / 2]
}

/**
 * Diepste zoomniveau (standaard, ClusterNiveau.NORMAAL): op 14 is 1 px ≈ 1,5 m (op 52° breedte), dus een cluster van
 * [STRAAL_DIEP_DP] ≈ 400 m. Een vakantiepark (zwembad + huisje) blijft zo één cluster dat direct de viewer opent;
 * dieper inzoomen/splitsen heeft geen zin. De instelling Clusterniveau schaalt de straal (×0,7 / ×1,5) en zet het
 * diepste niveau op 16 / 12.
 */
private val maxZoom get() = Instellingen.cluster.maxZoom
/** Markergrootte en de straal waarbinnen clusters samengaan (dp): op wereld/landniveau klein, dieper steeds grover. */
private const val MARKER_DP = 56f
private const val STRAAL_DP = 66f
private const val STRAAL_DIEP_DP = 130f
/** Clusterstraal (dp) per heel zoomniveau: 66 dp tot niveau 9, lineair naar 130 dp op niveau 14 (× de instelling). */
private fun straalDp(z: Int) = (STRAAL_DP + (STRAAL_DIEP_DP - STRAAL_DP) * ((z - 9) / 5f).coerceIn(0f, 1f)) * Instellingen.cluster.straal

/** Satelliet: Sentinel-2 cloudless 2023 van EOX (CC BY-NC-SA 4.0, vrij voor persoonlijk gebruik met bronvermelding, geen sleutel). */
private const val SAT_TEGELS = "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2023_3857/default/g/{z}/{y}/{x}.jpg"
const val SAT_BRON = "EOxCloudless cloudless.eox.at by EOX IT Services GmbH (Contains modified Copernicus Sentinel data 2023)"

/** De open MapView (als de sectie in beeld is): bij geheugennood zijn tegelcache laten legen (App.onTrimMemory). */
@Volatile var openKaart: MapView? = null
/**
 * Test-extra's (`--ez kaart_texture true`, `--ei kaart_lod 0`, `--ei kaart_fps 30`, `--ez kaart_alles true`): TextureView
 * i.p.v. SurfaceView, tegel-LOD-verschuiving, maximale kaart-fps, alle stijllagen laten staan.
 * Meting (NVIDIA Shield, zoomreeks): LOD-verschuiving −1 (tegels van één niveau lager, 4× minder tegels) bracht de
 * kaart van ~30 naar ~55 fps en de gemiste frames in een schermopname van 19 % naar 6 %.
 */
@Volatile var kaartTexture = false
@Volatile var kaartLod = -1
@Volatile var kaartFps = 0
@Volatile var kaartAlles = false
/** hoogte van de kaart-View in px, als laag opgeschaald naar het scherm (0 = natief); 720 gaf geen meetbare winst (GPU is niet de bottleneck) */
@Volatile var kaartRes = 0
/** extra LOD-verschuiving tijdens de Terug-vlucht naar de wereld */
@Volatile var kaartVlucht = -2

/**
 * Stijllagen van tiles.immich.cloud (Protomaps dark, 70 lagen) die op een fotokaart op de tv niets toevoegen maar wel
 * tegels zwaarder maken (gebouwen, POI's, dienstwegen, kleine landgebruiksvlakken, labels die pas op z15+ komen).
 * Elke laag kost per frame evaluatie op de hoofdthread én tekenwerk op de GPU, die de markers (Compose) deelt.
 */
private val WEG_LAGEN = listOf(
    "buildings", "pois", "address_label", "roads_oneway", "roads_labels_minor", "water_waterway_label", "water_stream",
    "roads_minor_service", "roads_minor_service_casing", "roads_taxiway", "roads_runway", "landuse_runway", "landuse_aerodrome",
    "landuse_hospital", "landuse_school", "landuse_zoo", "landuse_industrial", "landuse_pedestrian", "landuse_pier", "roads_pier",
    "earth_label_islands",
)

class KaartStaat(private val scope: CoroutineScope) {
    var markers by mutableStateOf<Markers?>(null)
        private set
    var fout by mutableStateOf<String?>(null)
    var stijlUrl by mutableStateOf<String?>(null)
    /** voor welke instelling [stijlUrl] is opgehaald */
    var stijlSoort by mutableStateOf<KaartStijl?>(null)
    /** startweergave (Europa / hoofdland) al toegepast (één keer per sessie) */
    var gestart = false
    /** wereldcoördinaten van de markers (één keer uitgerekend) */
    var wx = DoubleArray(0); var wy = DoubleArray(0)
    /** camera van dit moment, elke frame bijgewerkt tijdens een beweging */
    val camera = mutableStateOf(Camera(0.5, 0.5, 1.0, 1024.0))
    var clusters by mutableStateOf<List<Cluster>>(emptyList())
    var focus by mutableStateOf<String?>(null)
    /** camera bij het verlaten van de sectie, hersteld bij terugkomst */
    var bewaard: Camera? = null
    /** "Toon op kaart" vanuit de viewer: hierheen vliegen zodra de kaart er is */
    var doel by mutableStateOf<Pair<Double, Double>?>(null)
    /** teller: de kaart moet de toetsen weer krijgen (viewer gesloten) */
    var vraagFocus by mutableIntStateOf(0)

    init {
        scope.launch {
            runCatching { Immich.markers() }
                .onSuccess { m -> wx = DoubleArray(m.aantal) { wereldX(m.lon[it]) }; wy = DoubleArray(m.aantal) { wereldY(m.lat[it]) }; markers = m }
                .onFailure { fout = it.message; Log.w("Fotos", "markers", it) }
        }
    }

    /** Stijl-url ophalen voor de ingestelde kaartstijl (satelliet = donkere stijl met Sentinel-2 eronder). */
    suspend fun laadStijl(s: KaartStijl) {
        if (stijlSoort == s && stijlUrl != null) return
        stijlUrl = Immich.kaartStijl(licht = s == KaartStijl.LICHT)
        stijlSoort = s
    }

    /** Land met de meeste foto's → bbox (wereldeenheden) van die foto's zonder uitschieters (3e–97e percentiel). */
    fun hoofdlandBbox(): DoubleArray? {
        val m = markers ?: return null
        val land = m.land.filterNotNull().groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: return null
        val idx = (0 until m.aantal).filter { m.land[it] == land }
        if (idx.size < 2) return null
        val xs = idx.map { wx[it] }.sorted(); val ys = idx.map { wy[it] }.sorted()
        val a = (idx.size * 0.03).toInt(); val b = (idx.size * 0.97).toInt().coerceAtMost(idx.size - 1)
        return doubleArrayOf(xs[a], ys[a], xs[b], ys[b])
    }

    fun toon(a: Asset) { if (a.lat != null && a.lon != null) { doel = a.lat to a.lon; vraagFocus++ } }
}

/** Viewer-bron vanaf de kaart: alleen id's bekend, de asset wordt opgehaald zodra hij (bijna) in beeld komt. */
class KaartBron(private val ids: List<String>, private val scope: CoroutineScope) : BeeldBron {
    private val cache = mutableStateMapOf<String, Asset>()
    private val onderweg = HashSet<String>()
    override val aantal get() = ids.size
    override fun op(i: Int): Asset? {
        val id = ids.getOrNull(i) ?: return null
        cache[id]?.let { return it }
        if (onderweg.add(id)) scope.launch {
            runCatching { Immich.asset(id) }.onSuccess { cache[id] = it }.onFailure { onderweg.remove(id); Log.w("Fotos", "asset $id", it) }
        }
        return null
    }
}

// ---- Clusteren ----

private class Cel(val kx: Long, val ky: Long) {
    var n = 0; var sx = 0.0; var sy = 0.0; var leden = IntArray(8); var weg = false
    fun voeg(i: Int, x: Double, y: Double) { if (n == leden.size) leden = leden.copyOf(n * 2); leden[n++] = i; sx += x; sy += y }
    fun slok(b: Cel) {
        if (n + b.n > leden.size) leden = leden.copyOf(max(n + b.n, 2 * leden.size))
        System.arraycopy(b.leden, 0, leden, n, b.n); n += b.n; sx += b.sx; sy += b.sy
    }
}

private fun vakSleutel(kx: Long, ky: Long) = (kx shl 32) or (ky and 0xffffffffL)

/**
 * Clusters voor wat in beeld is (plus een rand): rastervakken van [straalDp] px op het hele zoomniveau [z], daarna gaan
 * buurvakken waarvan de zwaartepunten dichter dan die straal bij elkaar liggen samen (grootste eerst). Alles op het hele
 * zoomniveau gerekend, zodat de indeling niet verspringt tijdens het zoomen binnen één niveau. Geeft ook de bbox (met
 * rand) terug: zolang het beeld daarbinnen blijft, hoeft er niet opnieuw geclusterd te worden.
 * [leden] = alleen deze markers (hele wereld) i.p.v. alles in beeld: om te kijken of een cluster op niveau [z] uiteenvalt.
 */
private fun clusteren(s: KaartStaat, cam: Camera, w: Int, h: Int, pr: Float, z: Int, leden: IntArray? = null): Pair<List<Cluster>, DoubleArray> {
    val schaalZ = 512.0 * (1L shl z) * pr
    val straal = straalDp(z) * pr
    val cel = straal / schaalZ
    val rand = 2 * straal / cam.schaal
    val x0 = cam.cx - w / 2.0 / cam.schaal - rand; val x1 = cam.cx + w / 2.0 / cam.schaal + rand
    val y0 = cam.cy - h / 2.0 / cam.schaal - rand; val y1 = cam.cy + h / 2.0 / cam.schaal + rand
    val vakken = HashMap<Long, Cel>()
    val wx = s.wx; val wy = s.wy
    fun voeg(i: Int) {
        val x = wx[i]; val y = wy[i]
        val kx = floor(x / cel).toLong(); val ky = floor(y / cel).toLong()
        vakken.getOrPut(vakSleutel(kx, ky)) { Cel(kx, ky) }.voeg(i, x, y)
    }
    if (leden != null) for (i in leden) voeg(i)
    else for (i in wx.indices) {
        val x = wx[i]; if (x < x0 || x > x1) continue
        val y = wy[i]; if (y < y0 || y > y1) continue
        voeg(i)
    }
    val lijst = vakken.values.sortedByDescending { it.n }
    for (a in lijst) {
        if (a.weg) continue
        for (dx in -1..1) for (dy in -1..1) {
            if (dx == 0 && dy == 0) continue
            val b = vakken[vakSleutel(a.kx + dx, a.ky + dy)] ?: continue
            if (b.weg || b.n > a.n) continue
            val d = hypot((a.sx / a.n - b.sx / b.n) * schaalZ, (a.sy / a.n - b.sy / b.n) * schaalZ)
            if (d < straal) { a.slok(b); b.weg = true }
        }
    }
    val uit = lijst.filter { !it.weg }.map { v -> Cluster("$z:${v.kx}:${v.ky}", v.sx / v.n, v.sy / v.n, v.leden.copyOf(v.n).also { it.sort() }) }
    return uit to doubleArrayOf(x0, y0, x1, y1)
}

/** Alles wat de kaart aanstuurt (camera, clusters, focus, toetsen); leeft zolang de sectie in beeld is. */
private class KaartMotor(
    val staat: KaartStaat, val scope: CoroutineScope, val w: Int, val h: Int, val pr: Float, val wereldZoom: Double,
    val open: (Cluster) -> Unit,
) {
    var kaart: MapLibreMap? = null
    private var bezig = false
    private var opnieuw = false
    /** kwam er tijdens een lopende ronde een "stil"-verzoek (camera staat), dan daarna de focus kiezen */
    private var opnieuwStil = false
    private var vorigeBbox: DoubleArray? = null
    private var vorigeZ = -1
    /** marker die focus moet krijgen zodra de camera stilstaat (na in-/uitzoomen: het cluster met die foto) */
    private var focusWens = -1
    /** na opschuiven: volgende cluster in die richting kiezen, gezien vanaf de oude focusplek (meegeschoven) */
    private var naSchuif: Triple<Int, Int, Offset>? = null
    private var laatsteFocusPlek = Offset(w / 2f, h / 2f)
    /** zoom waar de lopende animatie heen gaat: een volgende ⏩/⏪ telt daarop door (retarget, niet wachten) */
    private var zoomDoel: Double? = null

    // meting: kaartframes per beweging (MapLibre meldt per gerenderd frame de rendertijd)
    private var meetStart = 0L
    private var meetFrames = 0
    private var meetRenderMs = 0.0
    private var meetMaxMs = 0.0

    fun schermPlek(c: Cluster, cam: Camera = staat.camera.value) =
        Offset(((c.wx - cam.cx) * cam.schaal + w / 2).toFloat(), ((c.wy - cam.cy) * cam.schaal + h / 2).toFloat())

    fun focusCluster() = staat.clusters.firstOrNull { it.sleutel == staat.focus }

    fun frameKlaar(renderS: Double) {
        if (meetStart == 0L) return
        meetFrames++; val ms = renderS * 1000; meetRenderMs += ms; if (ms > meetMaxMs) meetMaxMs = ms
    }

    fun leesCamera(map: MapLibreMap, stil: Boolean) {
        val p = map.cameraPosition
        val t = p.target ?: return
        staat.camera.value = Camera(wereldX(t.longitude), wereldY(t.latitude), p.zoom, 512.0 * 2.0.pow(p.zoom) * pr)
        if (stil) {
            zoomDoel = null
            if (map.tileLodZoomShift != kaartLod.toDouble()) map.tileLodZoomShift = kaartLod.toDouble()
            if (meetStart != 0L && meetFrames > 0) {
                val duur = System.currentTimeMillis() - meetStart
                Log.i("Fotos", "kaart beweging: $duur ms, $meetFrames kaartframes (%.0f fps), render gem %.1f ms max %.1f ms, zoom %.2f".format(
                    meetFrames * 1000.0 / duur, meetRenderMs / meetFrames, meetMaxMs, p.zoom))
            }
            meetStart = 0L
        } else if (meetStart == 0L) { meetStart = System.currentTimeMillis(); meetFrames = 0; meetRenderMs = 0.0; meetMaxMs = 0.0 }
        hercluster(stil)
    }

    /**
     * Opnieuw clusteren als dat nodig is. Tijdens een beweging (niet [stil]) blijft het zoomniveau van de vorige indeling
     * staan (geen marker-churn midden in een animatie); alleen als het beeld buiten de vorige bbox komt, wordt er voor
     * het nieuwe beeld geclusterd, op dat oude niveau. Staat de camera stil, dan geldt het echte niveau.
     */
    fun hercluster(stil: Boolean, altijd: Boolean = false) {
        if (staat.markers == null) return
        val cam = staat.camera.value
        val echtZ = floor(cam.zoom + 1e-6).toInt().coerceAtLeast(0)
        val z = if (stil || vorigeZ < 0) echtZ else vorigeZ
        val hw = w / 2.0 / cam.schaal; val hh = h / 2.0 / cam.schaal
        val b = vorigeBbox
        val nodig = altijd || z != vorigeZ || b == null || cam.cx - hw < b[0] || cam.cy - hh < b[1] || cam.cx + hw > b[2] || cam.cy + hh > b[3]
        if (!nodig) { if (stil) kiesFocus(); return }
        if (bezig) { opnieuw = true; opnieuwStil = opnieuwStil || stil; return }
        bezig = true
        scope.launch {
            val (lijst, bbox) = withContext(Dispatchers.Default) { clusteren(staat, cam, w, h, pr, z) }
            vorigeBbox = bbox; vorigeZ = z
            staat.clusters = lijst
            bezig = false
            if (opnieuw) { val s2 = stil || opnieuwStil; opnieuw = false; opnieuwStil = false; hercluster(s2) } else if (stil) kiesFocus()
        }
    }

    /** Focus bepalen als de camera stilstaat: gewenste foto → het cluster ermee; anders de bestaande; anders de dichtstbijzijnde. */
    private fun kiesFocus() {
        val l = staat.clusters
        if (l.isEmpty()) { staat.focus = null; return }
        val wens = focusWens
        if (wens >= 0) {
            focusWens = -1
            l.firstOrNull { it.leden.binarySearch(wens) >= 0 }?.let { zetFocus(it); return }
        }
        naSchuif?.let { (dx, dy, van) ->
            naSchuif = null
            inRichting(l, van, dx, dy)?.let { zetFocus(it); return }
        }
        if (l.any { it.sleutel == staat.focus }) return
        zetFocus(l.minByOrNull { (schermPlek(it) - laatsteFocusPlek).getDistance() }!!)
    }

    private fun zetFocus(c: Cluster) { staat.focus = c.sleutel; laatsteFocusPlek = schermPlek(c) }

    /** Beste cluster in richting (dx, dy) vanaf [van]: eerst wat er dicht langs de as ligt (zoals de Android-focuszoeker). */
    private fun inRichting(l: List<Cluster>, van: Offset, dx: Int, dy: Int): Cluster? {
        var best: Cluster? = null; var bestScore = Float.MAX_VALUE
        for (c in l) {
            val p = schermPlek(c)
            if (p.x < 0 || p.y < 0 || p.x > w || p.y > h) continue
            val langs = (p.x - van.x) * dx + (p.y - van.y) * dy
            if (langs < 6f) continue
            val dwars = abs((p.x - van.x) * dy) + abs((p.y - van.y) * dx)
            val score = langs + 2.5f * dwars
            if (score < bestScore) { bestScore = score; best = c }
        }
        return best
    }

    /** Pijltje: volgende cluster in die richting, of de kaart een stuk opschuiven. Geeft false als de toets niet voor de kaart is (lade). */
    fun richting(dx: Int, dy: Int): Boolean {
        val map = kaart ?: return false
        val f = focusCluster()
        val van = f?.let { schermPlek(it) } ?: laatsteFocusPlek
        inRichting(staat.clusters, van, dx, dy)?.let { zetFocus(it); return true }
        val cam = staat.camera.value
        // geen cluster meer in die richting: de kaart opschuiven. Alleen op wereldniveau (alles al in beeld, schuiven
        // heeft geen zin) gaat ◀ naar de lade, zoals overal in de app aan de linkerrand; verder opent MENU de lade.
        if (dx < 0 && cam.zoom <= wereldZoom + 0.2) return false
        val stap = 0.45
        val nx = cam.cx + dx * stap * w / cam.schaal
        val ny = (cam.cy + dy * stap * h / cam.schaal).coerceIn(0.02, 0.98)
        naSchuif = Triple(dx, dy, Offset(van.x - (dx * stap * w).toFloat(), van.y - (dy * stap * h).toFloat()))
        map.easeCamera(CameraUpdateFactory.newLatLng(LatLng(latVan(ny), lonVan(nx))), 380)
        return true
    }

    /** Valt [c] op heel zoomniveau [z] nog uiteen in meer clusters? */
    private fun splitst(c: Cluster, z: Int): Boolean =
        clusteren(staat, staat.camera.value, w, h, pr, z, c.leden).first.size > 1

    /** OK: inzoomen op het cluster tot het uiteenvalt; valt het (ook op het diepste niveau) niet meer uiteen of is het één foto: de viewer. */
    fun kies() {
        val map = kaart ?: return
        val c = focusCluster() ?: return
        if (c.aantal == 1) { open(c); return }
        val cam = staat.camera.value
        var x0 = 1.0; var x1 = 0.0; var y0 = 1.0; var y1 = 0.0
        for (i in c.leden) { val x = staat.wx[i]; val y = staat.wy[i]; if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y }
        val spanX = (x1 - x0) * cam.schaal; val spanY = (y1 - y0) * cam.schaal
        val past = cam.zoom + log2(min(0.55 * w / max(spanX, 1e-6), 0.55 * h / max(spanY, 1e-6)))
        val doelZoom = min(past, maxZoom)
        // geen zoomwinst meer, of het cluster blijft op het doelniveau toch één geheel (vakantiepark): de viewer
        if (doelZoom - cam.zoom < 0.5 || !splitst(c, floor(doelZoom + 1e-6).toInt())) { open(c); return }
        focusWens = c.hoofd
        zoomDoel = doelZoom
        map.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(latVan((y0 + y1) / 2), lonVan((x0 + x1) / 2)), doelZoom), 650)
    }

    /** ⏩ / ⏪: een niveau in- of uitzoomen rond het gekozen cluster (dat blijft op zijn plek); tijdens een animatie telt het door op het doel. */
    fun zoom(d: Double) {
        val map = kaart ?: return
        val cam = staat.camera.value
        val basis = zoomDoel ?: cam.zoom
        val nieuw = (basis + d).coerceIn(wereldZoom, maxZoom)
        if (abs(nieuw - cam.zoom) < 0.01) return
        val f = focusCluster()
        focusWens = f?.hoofd ?: -1
        val p = f?.let { schermPlek(it) } ?: Offset(w / 2f, h / 2f)
        zoomDoel = nieuw
        map.easeCamera(CameraUpdateFactory.zoomBy(nieuw - cam.zoom, Point(p.x.roundToInt(), p.y.roundToInt())), 400)
    }

    /** Camera die [bbox] (wereldeenheden) in beeld brengt, met rand. */
    fun pasInBeeld(b: DoubleArray, rand: Double = 0.78): CameraPosition {
        val dx = (b[2] - b[0]).coerceAtLeast(1e-6); val dy = (b[3] - b[1]).coerceAtLeast(1e-6)
        val z = log2(min(rand * w / (dx * 512 * pr), rand * h / (dy * 512 * pr))).coerceIn(wereldZoom, maxZoom - 1)
        return CameraPosition.Builder().target(LatLng(latVan((b[1] + b[3]) / 2), lonVan((b[0] + b[2]) / 2))).zoom(z).build()
    }

    /** Terug: in één vlucht naar de hele wereld. Geeft false als we daar al zijn (dan verlaat Terug de sectie). */
    fun terug(): Boolean {
        val map = kaart ?: return false
        val cam = staat.camera.value
        if (cam.zoom <= wereldZoom + 0.2) return false
        focusWens = focusCluster()?.hoofd ?: -1
        zoomDoel = wereldZoom
        // tijdens de vlucht door 12 zoomniveaus extra grove tegels (anders per niveau nieuwe tegels laden → 15 fps); bij stilstand terug
        if (kaartVlucht != 0) map.tileLodZoomShift = (kaartLod + kaartVlucht).toDouble()
        map.easeCamera(wereld(wereldZoom), 800)
        return true
    }

    fun vliegNaar(lat: Double, lon: Double) {
        val map = kaart ?: return
        focusWens = -1
        laatsteFocusPlek = Offset(w / 2f, h / 2f)
        zoomDoel = maxZoom
        map.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), maxZoom), 1200)
    }
}

private const val WERELD_LAT = 20.0
private const val WERELD_LON = 10.0
private fun wereld(zoom: Double) = CameraUpdateFactory.newLatLngZoom(LatLng(WERELD_LAT, WERELD_LON), zoom)
/** Europa (instelling "startweergave"): van Ierland tot de Zwarte Zee, van Sicilië tot Noord-Noorwegen. */
private val EUROPA = doubleArrayOf(wereldX(-12.0), wereldY(66.0), wereldX(34.0), wereldY(35.0))

/** "lat,lon,zoom" ↔ camera (laatst bekeken plek). */
private fun laatstePlek(): CameraPosition? {
    val p = Instellingen.kaartLaatst.split(',').mapNotNull { it.toDoubleOrNull() }
    if (p.size != 3) return null
    return CameraPosition.Builder().target(LatLng(p[0], p[1])).zoom(p[2]).build()
}

@Composable
fun KaartScherm(staat: KaartStaat, nav: Nav) {
    val dicht = LocalDensity.current
    val scope = rememberCoroutineScope()
    val fr = remember { FocusRequester() }
    var hintTot by remember { mutableLongStateOf(System.currentTimeMillis() + 6000) }
    var hint by remember { mutableStateOf(true) }
    LaunchedEffect(hintTot) { hint = true; delay((hintTot - System.currentTimeMillis()).coerceAtLeast(0)); hint = false }
    var stijlFout by remember { mutableStateOf(false) }
    var stijlKlaar by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF101418))) {
        val w = constraints.maxWidth; val h = constraints.maxHeight
        val pr = dicht.density
        // hele wereld over de breedte (polen vallen af); MapLibre: wereld = 512 × 2^zoom logische px
        val wereldZoom = log2(w / (512.0 * pr))
        val motor = remember(w, h) {
            KaartMotor(staat, scope, w, h, pr, wereldZoom) { c ->
                val m = staat.markers ?: return@KaartMotor
                val ids = c.leden.map { m.id[it] }
                val titel = listOfNotNull(m.stad[c.hoofd], m.land[c.hoofd]).joinToString(", ").ifEmpty { Taal.s(R.string.kaart) }
                nav.bekijk(KaartBron(ids, nav.scope), 0, titel, false, null) { staat.vraagFocus++ }
            }
        }
        val ctx = LocalContext.current
        val schaal = if (kaartRes in 1 until h) kaartRes.toFloat() / h else 1f
        val mapView = remember(w, h) {
            MapLibre.getInstance(ctx)
            val start = staat.bewaard
            // startweergave: terug in de sectie = waar je was; anders de instelling (wereld / Europa / laatste plek;
            // het hoofdland (land met de meeste foto's) pas zodra de markers er zijn, zie hieronder)
            val camera = when {
                start != null -> CameraPosition.Builder().target(LatLng(latVan(start.cy), lonVan(start.cx))).zoom(start.zoom).build()
                Instellingen.kaartStart == KaartStart.EUROPA -> motor.pasInBeeld(EUROPA)
                Instellingen.kaartStart == KaartStart.LAATST -> laatstePlek() ?: CameraPosition.Builder().target(LatLng(WERELD_LAT, WERELD_LON)).zoom(wereldZoom).build()
                else -> CameraPosition.Builder().target(LatLng(WERELD_LAT, WERELD_LON)).zoom(wereldZoom).build()
            }
            // SurfaceView (textureMode false): eigen laag onder het venster, door SurfaceFlinger samengesteld; de Compose-lagen
            // erboven (markers, lade, viewer met zwarte achtergrond) tekenen gewoon in het venster. Een TextureView kostte
            // op een Tegra-box per kaartframe een extra kopie door HWUI.
            // Optioneel (kaartRes) een kleinere kaart-View die als laag opgeschaald wordt (SurfaceView volgt de transformatie):
            // minder vulwerk voor de GPU. pixelRatio zó dat de logische maat gelijk blijft aan die van het scherm (dp): tekst
            // even groot, en onze Camera-wiskunde (512·2^zoom·density px per wereld) klopt. Standaard natief.
            val opties = MapLibreMapOptions.createFromAttributes(ctx).camera(camera)
                .textureMode(kaartTexture).pixelRatio(pr * schaal)
                .compassEnabled(false).logoEnabled(false).attributionEnabled(false)
            MapView(ctx, opties).apply { isFocusable = false; isFocusableInTouchMode = false }
        }
        val levensloop = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(mapView) {
            mapView.onCreate(null)
            val kijker = LifecycleEventObserver { _, e ->
                when (e) {
                    Lifecycle.Event.ON_START -> mapView.onStart()
                    Lifecycle.Event.ON_RESUME -> mapView.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                    Lifecycle.Event.ON_STOP -> mapView.onStop()
                    else -> {}
                }
            }
            if (levensloop.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
            if (levensloop.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
            levensloop.addObserver(kijker)
            openKaart = mapView
            mapView.addOnDidFailLoadingMapListener { Log.w("Fotos", "kaartstijl: $it"); stijlFout = true }
            mapView.addOnDidFinishRenderingFrameListener(MapView.OnDidFinishRenderingFrameListener { _, _, renderS -> motor.frameKlaar(renderS) })
            mapView.getMapAsync { map ->
                map.uiSettings.apply { setAllGesturesEnabled(false); isCompassEnabled = false; isLogoEnabled = false; isAttributionEnabled = false }
                map.setMinZoomPreference(wereldZoom)
                map.setMaxZoomPreference(maxZoom)
                if (kaartLod != 0) map.tileLodZoomShift = kaartLod.toDouble()
                if (kaartFps > 0) mapView.setMaximumFps(kaartFps)
                Log.i("Fotos", "kaart: lod $kaartLod, vlucht $kaartVlucht, res $kaartRes, texture $kaartTexture, fps ${kaartFps.takeIf { it > 0 } ?: "vrij"}, alle lagen $kaartAlles, pixelRatio ${mapView.pixelRatio}")
                map.addOnCameraMoveListener { motor.leesCamera(map, stil = false) }
                map.addOnCameraIdleListener { motor.leesCamera(map, stil = true) }
                motor.kaart = map
                motor.leesCamera(map, stil = true)
                // controle van de projectie (één keer): eigen formule naast die van MapLibre
                val p = map.projection.toScreenLocation(LatLng(52.0, 5.0))
                val cam = staat.camera.value
                Log.i("Fotos", "kaart projectie: maplibre=(${p.x},${p.y}) eigen=(${(wereldX(5.0) - cam.cx) * cam.schaal + w / 2}, ${(wereldY(52.0) - cam.cy) * cam.schaal + h / 2}) pr=$pr/${mapView.pixelRatio}")
            }
            onDispose {
                staat.bewaard = staat.camera.value
                if (Instellingen.kaartStart == KaartStart.LAATST) staat.camera.value.let { c ->
                    Instellingen.kaartLaatst = "%.5f,%.5f,%.2f".format(java.util.Locale.ROOT, latVan(c.cy), lonVan(c.cx), c.zoom)
                }
                levensloop.removeObserver(kijker)
                if (openKaart === mapView) openKaart = null
                motor.kaart = null
                mapView.onPause(); mapView.onStop(); mapView.onDestroy()
            }
        }
        // stijl (instelling) ophalen en zetten zodra kaart én url er zijn
        val stijlKeuze = Instellingen.kaartStijl
        LaunchedEffect(stijlKeuze) { staat.laadStijl(stijlKeuze) }
        LaunchedEffect(staat.stijlUrl, staat.stijlSoort) {
            val url = staat.stijlUrl ?: return@LaunchedEffect
            val soort = staat.stijlSoort ?: return@LaunchedEffect
            while (motor.kaart == null) delay(50)
            motor.kaart?.setStyle(Style.Builder().fromUri(url)) { stijl ->
                stijlKlaar = true
                if (!kaartAlles) {
                    val n = WEG_LAGEN.count { stijl.removeLayer(it) }
                    Log.i("Fotos", "kaartstijl ${soort.name}: $n lagen weg, ${stijl.layers.size} over")
                }
                // satelliet = hybride: alle vlakken (land, water, landgebruik) weg en Sentinel-2 onderop; wegen, grenzen en
                // namen van de Immich-stijl blijven erbovenop staan
                if (soort == KaartStijl.SATELLIET) {
                    for (l in stijl.layers.toList()) if (l is org.maplibre.android.style.layers.FillLayer || l is org.maplibre.android.style.layers.BackgroundLayer) stijl.removeLayer(l)
                    val tegels = org.maplibre.android.style.sources.TileSet("2.2.0", SAT_TEGELS).apply { minZoom = 0f; maxZoom = 15f; attribution = SAT_BRON }
                    stijl.addSource(org.maplibre.android.style.sources.RasterSource("sat", tegels, 256))
                    stijl.addLayerAt(org.maplibre.android.style.layers.RasterLayer("sat", "sat"), 0)
                }
            }
        }
        // markers binnen: clusteren
        LaunchedEffect(staat.markers) { if (staat.markers != null) motor.hercluster(stil = true, altijd = true) }
        // clusterniveau (instelling) veranderd terwijl de kaart open is: diepste zoom en indeling bijwerken
        val clusterKeuze = Instellingen.cluster
        LaunchedEffect(clusterKeuze) {
            while (motor.kaart == null) delay(50)
            motor.kaart?.setMaxZoomPreference(clusterKeuze.maxZoom)
            if (staat.markers != null) motor.hercluster(stil = true, altijd = true)
        }
        // startweergave "land met de meeste foto's": zodra de markers er zijn (één keer, niet bij terugkomst in de sectie)
        LaunchedEffect(staat.markers) {
            if (staat.markers == null || staat.gestart || staat.bewaard != null || Instellingen.kaartStart != KaartStart.HOOFDLAND) return@LaunchedEffect
            staat.gestart = true
            val b = staat.hoofdlandBbox() ?: return@LaunchedEffect
            while (motor.kaart == null) delay(50)
            motor.kaart?.easeCamera(CameraUpdateFactory.newCameraPosition(motor.pasInBeeld(b)), 900)
        }
        // "Toon op kaart"
        LaunchedEffect(staat.doel) {
            val (lat, lon) = staat.doel ?: return@LaunchedEffect
            while (motor.kaart == null) delay(50)
            staat.doel = null
            motor.vliegNaar(lat, lon)
        }
        LaunchedEffect(staat.vraagFocus) { delay(60); runCatching { fr.requestFocus() } }

        // derivedStateOf: de camera verandert elk frame, alleen de uitkomst (wel/niet terug) mag hercomponeren
        val kanTerug by remember { androidx.compose.runtime.derivedStateOf { staat.camera.value.zoom > wereldZoom + 0.2 } }
        BackHandler(enabled = kanTerug) { hintTot = System.currentTimeMillis() + 3000; motor.terug() }

        Box(
            Modifier.fillMaxSize().focusRequester(fr).focusable().onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val klaar = when (e.key.nativeKeyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> motor.richting(-1, 0)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> motor.richting(1, 0)
                    KeyEvent.KEYCODE_DPAD_UP -> motor.richting(0, -1)
                    KeyEvent.KEYCODE_DPAD_DOWN -> motor.richting(0, 1)
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { motor.kies(); true }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { motor.zoom(1.0); true }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> { motor.zoom(-1.0); true }
                    else -> false
                }
                if (klaar) hintTot = System.currentTimeMillis() + 3000
                klaar
            },
        ) {
            AndroidView(
                factory = { mapView },
                modifier = Modifier.graphicsLayer { scaleX = 1 / schaal; scaleY = 1 / schaal; transformOrigin = TransformOrigin(0f, 0f) }
                    .layout { m, _ -> val p = m.measure(Constraints.fixed((w * schaal).roundToInt(), (h * schaal).roundToInt())); layout(w, h) { p.place(0, 0) } },
            )
            // geen ondergrond (offline / tiles.immich.cloud onbereikbaar): landcontouren uit landen.bin
            if (stijlFout && !stijlKlaar) LandenLaag(staat.camera, w, h)

            val m = staat.markers
            if (m != null) for (c in staat.clusters) key(c.sleutel) {
                Marker(c, m, focus = c.sleutel == staat.focus, cam = staat.camera, w = w, h = h)
            }
            // naam en aantal onder het gekozen cluster
            motor.focusCluster()?.let { c ->
                key(c.sleutel) { FocusLabel(c, m!!, staat.camera, w, h) }
            }

            // kop linksboven (op een lichte of satellietkaart met een donker vlak erachter, anders onleesbaar)
            val donkereKaart = stijlKeuze == KaartStijl.DONKER
            Column(
                Modifier.padding(start = 24.dp, top = 20.dp)
                    .then(if (donkereKaart) Modifier else Modifier.background(Color(0xB3000000), RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 8.dp)),
            ) {
                Text(stringResource(R.string.kaart), style = MaterialTheme.typography.displaySmall)
                val n = m?.aantal
                Text(
                    when { staat.fout != null -> stringResource(R.string.kaart_niet_geladen, staat.fout.orEmpty()); n == null -> stringResource(R.string.laden); else -> pluralStringResource(R.plurals.fotos_met_locatie, n, Taal.getal(n)) },
                    style = MaterialTheme.typography.bodyMedium, color = Kleur.zacht,
                )
            }
            if (stijlFout && !stijlKlaar) Text(
                stringResource(R.string.kaart_geen_achtergrond), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.TopEnd).padding(24.dp),
            )
            // bedieningshint onderin (verdwijnt vanzelf) en bronvermelding
            val hintAlpha by animateFloatAsState(if (hint) 1f else 0f, tween(400), label = "hint")
            Text(
                stringResource(R.string.kaart_hint),
                style = MaterialTheme.typography.labelLarge, color = Color(0xDDFFFFFF),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp).graphicsLayer { alpha = hintAlpha }
                    .background(Color(0xB3000000), RoundedCornerShape(16.dp)).padding(horizontal = 18.dp, vertical = 8.dp),
            )
            Text(
                if (stijlKeuze == KaartStijl.SATELLIET) "$SAT_BRON · © OpenStreetMap · Protomaps" else "© OpenStreetMap · Protomaps",
                style = MaterialTheme.typography.labelSmall, color = Color(0xCCFFFFFF), maxLines = 1,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 3.dp)
                    .background(Color(0x80000000), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

/** Plaatst de inhoud met zijn anker ([ankerX], [ankerY] in 0..1) op een wereldpunt; alleen de plaatsing volgt de camera (geen hermeting). */
private fun Modifier.opPunt(cam: State<Camera>, w: Int, h: Int, wx: Double, wy: Double, ankerX: Float = 0.5f, ankerY: Float = 0.5f) = layout { meetbaar, c ->
    val p = meetbaar.measure(c.copy(minWidth = 0, minHeight = 0))
    layout(0, 0) {
        val k = cam.value
        p.place(((wx - k.cx) * k.schaal + w / 2 - p.width * ankerX).roundToInt(), ((wy - k.cy) * k.schaal + h / 2 - p.height * ankerY).roundToInt())
    }
}

@Composable
private fun Marker(c: Cluster, m: Markers, focus: Boolean, cam: State<Camera>, w: Int, h: Int) {
    // verschijnt met een kleine plof; de gekozen groeit
    val komt = remember { Animatable(0f) }
    LaunchedEffect(Unit) { komt.animateTo(1f, tween(260, easing = EaseOutBack)) }
    val fs by animateFloatAsState(if (focus) 1.3f else 1f, tween(160), label = "focus")
    val vorm = RoundedCornerShape(12.dp)
    Box(
        Modifier.opPunt(cam, w, h, c.wx, c.wy).zIndex(if (focus) 1f else 0f).size(MARKER_DP.dp)
            .graphicsLayer { val s = komt.value * fs; scaleX = s; scaleY = s; alpha = komt.value.coerceIn(0f, 1f) }
            // donkere rand i.p.v. een laag-schaduw: HWUI tesselleert schaduwen per node per frame, met tientallen
            // meebewegende markers tikt dat aan; alleen de gekozen marker houdt een (blauwe) schaduw
            .drawBehind { drawRoundRect(Color(0x80000000), Offset(-2f, 0f), Size(size.width + 4f, size.height + 3f), CornerRadius(13.dp.toPx())) },
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                shape = vorm; clip = true; shadowElevation = if (focus) 14.dp.toPx() else 0f
                spotShadowColor = Kleur.primair; ambientShadowColor = Kleur.primair
            }.background(Kleur.vlakHoog),
        ) {
            AsyncImage(beeld(Immich.thumb(m.id[c.hoofd])), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().border(if (focus) 3.dp else 2.dp, if (focus) Kleur.primair else Color.White, vorm))
        }
        if (c.aantal > 1) Text(
            Taal.getal(c.aantal), style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-8).dp)
                .background(if (focus) Kleur.primairDonker else Color(0xE6000000), RoundedCornerShape(10.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun FocusLabel(c: Cluster, m: Markers, cam: State<Camera>, w: Int, h: Int) {
    val plaats = listOfNotNull(m.stad[c.hoofd], m.land[c.hoofd]).joinToString(", ")
    Column(
        Modifier.opPunt(cam, w, h, c.wx, c.wy, ankerY = 0f).zIndex(2f).padding(top = (MARKER_DP * 0.65f + 12).dp)
            .background(Color(0xD9000000), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (plaats.isNotEmpty()) Text(plaats, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1, textAlign = TextAlign.Center)
        Text(pluralStringResource(R.plurals.fotos, c.aantal, Taal.getal(c.aantal)), style = MaterialTheme.typography.labelSmall, color = Color(0xCCFFFFFF))
    }
}

/** Landcontouren (landen.bin) in webmercator als noodondergrond zonder tegels. */
@Composable
private fun LandenLaag(cam: State<Camera>, w: Int, h: Int) {
    val ctx = LocalContext.current
    var pad by remember { mutableStateOf<Path?>(null) }
    LaunchedEffect(Unit) {
        val landen = Landen.lijst(ctx)
        pad = withContext(Dispatchers.Default) {
            Path().apply {
                for (l in landen) for (r in l.ringen) {
                    var i = 0
                    while (i < r.size) {
                        val x = wereldX(r[i].toDouble()).toFloat(); val y = wereldY(r[i + 1].toDouble()).toFloat()
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                        i += 2
                    }
                    close()
                }
            }
        }
    }
    val p = pad ?: return
    Canvas(Modifier.fillMaxSize()) {
        val k = cam.value
        withTransform({
            translate((w / 2 - k.cx * k.schaal).toFloat(), (h / 2 - k.cy * k.schaal).toFloat())
            scale(k.schaal.toFloat(), k.schaal.toFloat(), Offset.Zero)
        }) {
            drawPath(p, Color(0x33FFFFFF))
            drawPath(p, Color(0x99FFFFFF), style = Stroke((1.5.dp.toPx() / k.schaal).toFloat()))
        }
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x33000000)))))
}
