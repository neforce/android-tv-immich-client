package com.immichclient.tv

import androidx.annotation.StringRes
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow

/*
 * Clusters van de wereldkaart, hiërarchisch: werelddeel → regio (UN-subregio) → land → plek (afstandsclusters).
 *
 * Alles wordt één keer per markerset op een achtergrondthread opgebouwd ([KaartIndex]): per marker de landcode (uit
 * de landnaam van Immich), de boom met per groep de leden, het omvattende vak en een plek voor de marker (het drukste
 * stukje van de groep, niet het gemiddelde: dat lag bij Europa of Azië midden in zee), en per heel zoomniveau (lui,
 * gecachet) de afstandsclusters binnen elk land. Wat er bij een zoomniveau te zien is ([KaartIndex.toon]) is daarna
 * alleen nog een wandeling door de boom: een groep blijft één marker zolang hij op het scherm klein is (spreiding
 * onder [KaartIndex.drempelPx]) en het zoomniveau onder zijn maximum ligt; anders zijn kinderen. Zo zie je op de
 * wereld de werelddelen, ingezoomd de regio's en landen en daaronder de plaatsen, en kost elke stap hooguit
 * milliseconden in plaats van een volledige herclustering van tienduizenden foto's.
 */

enum class Werelddeel(@StringRes val label: Int) {
    EUROPA(R.string.deel_europa), AZIE(R.string.deel_azie), AFRIKA(R.string.deel_afrika), NOORD_AMERIKA(R.string.deel_noord_amerika),
    ZUID_AMERIKA(R.string.deel_zuid_amerika), OCEANIE(R.string.deel_oceanie), ANTARCTICA(R.string.deel_antarctica), OVERIG(R.string.deel_overig),
}

/** UN M49-subregio's met hun landen (ISO 3166). */
enum class Regio(val deel: Werelddeel, @StringRes val label: Int, landen: String) {
    NOORD_EUROPA(Werelddeel.EUROPA, R.string.regio_noord_europa, "AX DK EE FO FI GG IS IE IM JE LV LT NO SJ SE GB"),
    WEST_EUROPA(Werelddeel.EUROPA, R.string.regio_west_europa, "AT BE FR DE LI LU MC NL CH"),
    ZUID_EUROPA(Werelddeel.EUROPA, R.string.regio_zuid_europa, "AL AD BA HR GI GR VA IT MT ME MK PT SM RS SI ES XK"),
    OOST_EUROPA(Werelddeel.EUROPA, R.string.regio_oost_europa, "BY BG CZ HU PL MD RO RU SK UA"),
    NOORD_AFRIKA(Werelddeel.AFRIKA, R.string.regio_noord_afrika, "DZ EG LY MA SD TN EH"),
    WEST_AFRIKA(Werelddeel.AFRIKA, R.string.regio_west_afrika, "BJ BF CV CI GM GH GN GW LR ML MR NE NG SH SN SL TG"),
    CENTRAAL_AFRIKA(Werelddeel.AFRIKA, R.string.regio_centraal_afrika, "AO CM CF TD CG CD GQ GA ST"),
    OOST_AFRIKA(Werelddeel.AFRIKA, R.string.regio_oost_afrika, "BI KM DJ ER ET KE MG MW MU YT MZ RE RW SC SO SS TZ UG ZM ZW IO TF"),
    ZUIDELIJK_AFRIKA(Werelddeel.AFRIKA, R.string.regio_zuidelijk_afrika, "BW SZ LS NA ZA"),
    NOORDELIJK_AMERIKA(Werelddeel.NOORD_AMERIKA, R.string.regio_noordelijk_amerika, "BM CA GL PM US"),
    MIDDEN_AMERIKA(Werelddeel.NOORD_AMERIKA, R.string.regio_midden_amerika, "BZ CR SV GT HN MX NI PA"),
    CARIBISCH(Werelddeel.NOORD_AMERIKA, R.string.regio_caribisch, "AI AG AW BS BB BQ VG KY CU CW DM DO GD GP HT JM MQ MS PR BL KN LC MF VC SX TT TC VI"),
    ZUID_AMERIKA(Werelddeel.ZUID_AMERIKA, R.string.deel_zuid_amerika, "AR BO BV BR CL CO EC FK GF GY PY PE GS SR UY VE"),
    CENTRAAL_AZIE(Werelddeel.AZIE, R.string.regio_centraal_azie, "KZ KG TJ TM UZ"),
    OOST_AZIE(Werelddeel.AZIE, R.string.regio_oost_azie, "CN HK MO KP JP MN KR TW"),
    ZUIDOOST_AZIE(Werelddeel.AZIE, R.string.regio_zuidoost_azie, "BN KH ID LA MY MM PH SG TH TL VN"),
    ZUID_AZIE(Werelddeel.AZIE, R.string.regio_zuid_azie, "AF BD BT IN IR MV NP PK LK"),
    WEST_AZIE(Werelddeel.AZIE, R.string.regio_west_azie, "AM AZ BH CY GE IQ IL JO KW LB OM QA SA PS SY TR AE YE"),
    AUSTRALIE(Werelddeel.OCEANIE, R.string.regio_australie, "AU CX CC HM NZ NF"),
    MELANESIE(Werelddeel.OCEANIE, R.string.regio_melanesie, "FJ NC PG SB VU"),
    MICRONESIE(Werelddeel.OCEANIE, R.string.regio_micronesie, "GU KI MH FM NR MP PW UM"),
    POLYNESIE(Werelddeel.OCEANIE, R.string.regio_polynesie, "AS CK PF NU PN WS TK TO TV WF"),
    ANTARCTICA(Werelddeel.ANTARCTICA, R.string.deel_antarctica, "AQ"),
    ;

    val codes: List<String> = landen.split(' ')

    companion object {
        private val vanIso: Map<String, Regio> by lazy { HashMap<String, Regio>().also { m -> for (r in entries) for (c in r.codes) m[c] = r } }
        fun van(iso: String): Regio? = vanIso[iso]
    }
}

enum class KaartNiveau { WERELDDEEL, REGIO, LAND, PLEK }

/**
 * KaartGroep markers op één plek. [sleutel] is stabiel (zelfde groep = zelfde sleutel, ook na herberekenen), [leden] =
 * marker-indexen oplopend (= nieuwste eerst). [naam] alleen bij werelddeel/regio/land; [land] = index in
 * [KaartIndex.landCodes] (-1 bij een werelddeel/regio).
 */
class Cluster(
    val sleutel: String, val wx: Double, val wy: Double, val leden: IntArray,
    val niveau: KaartNiveau = KaartNiveau.PLEK, val naam: String? = null, val land: Int = -1,
    /** samengevoegd omdat de markers elkaar op dit niveau overlapten (Italië + Kroatië): valt bij inzoomen altijd uiteen */
    val samen: Boolean = false,
) {
    val aantal get() = leden.size
    /** representant voor de thumbnail: de middelste (de nieuwste is nogal eens een donkere/toevallige foto) */
    val hoofd get() = leden[leden.size / 2]
}

/** Werelddeel, regio of land: de marker ([cluster]), het vak om alle leden heen en de deelgroepen. */
class KaartGroep(val cluster: Cluster, val x0: Double, val y0: Double, val x1: Double, val y1: Double, val kinderen: List<KaartGroep>, val zMax: Int)

/** Tot welk zoomniveau (exclusief) een groep op dat niveau één marker mag blijven, ook als hij klein is. */
private const val Z_DEEL = 3
private const val Z_REGIO = 5
private const val Z_LAND = 7

/** Markergrootte en de straal waarbinnen plekken samengaan (dp): op wereld/landniveau klein, dieper steeds grover. */
const val STRAAL_DP = 66f
const val STRAAL_DIEP_DP = 130f
/** Clusterstraal (dp) per heel zoomniveau: 66 dp tot niveau 9, lineair naar 130 dp op niveau 14 (× de instelling). */
fun straalDp(z: Int) = (STRAAL_DP + (STRAAL_DIEP_DP - STRAAL_DP) * ((z - 9) / 5f).coerceIn(0f, 1f)) * Instellingen.cluster.straal

private class Cel(val land: Int, val kx: Long, val ky: Long) {
    var n = 0; var sx = 0.0; var sy = 0.0; var leden = IntArray(8); var weg = false
    fun voeg(i: Int, x: Double, y: Double) { if (n == leden.size) leden = leden.copyOf(n * 2); leden[n++] = i; sx += x; sy += y }
    fun slok(b: Cel) {
        if (n + b.n > leden.size) leden = leden.copyOf(max(n + b.n, 2 * leden.size))
        System.arraycopy(b.leden, 0, leden, n, b.n); n += b.n; sx += b.sx; sy += b.sy
    }
}

/** Rastervak + land in één Long: land (14 bits) | kx (25 bits) | ky (25 bits). */
private fun vakSleutel(land: Int, kx: Long, ky: Long) = ((land + 1).toLong() shl 50) or ((kx and 0x1FFFFFF) shl 25) or (ky and 0x1FFFFFF)

/**
 * Alles wat de kaart over de markers weet, één keer opgebouwd (op een achtergrondthread) per markerset.
 * [pr] = pixelverhouding (px per dp), [drempelPx] = spreiding in px waaronder een groep één marker blijft.
 */
class KaartIndex(
    val markers: Markers, private val wx: DoubleArray, private val wy: DoubleArray, private val pr: Float, private val drempelPx: Double,
    /** markers die dichter dan dit (px) bij elkaar staan, worden één marker (anders ligt de ene onder de andere) */
    private val overlapPx: Double = 0.85 * 56 * pr,
) {
    private val n = wx.size
    /** landcodes (ISO) die voorkomen; "??" = foto's waarvan het land onbekend is */
    val landCodes: List<String>
    private val landVan: IntArray
    val wortels: List<KaartGroep>
    private val groepen = HashMap<String, KaartGroep>()
    private val ouder = HashMap<String, KaartGroep>()
    private val landNamen: Array<String>

    init {
        val codes = ArrayList<String>()
        val nummer = HashMap<String, Int>()
        landVan = IntArray(n) { i -> markers.iso[i]?.let { c -> nummer.getOrPut(c) { codes.add(c); codes.size - 1 } } ?: -1 }
        vulOnbekend(codes)
        landCodes = codes
        landNamen = Array(codes.size) { i -> if (codes[i] == ONBEKEND) Taal.s(R.string.deel_overig) else Landnaam.vanIso(codes[i]) }
        wortels = bouw()
    }

    /** Foto's zonder (herkend) land: het land van de dichtstbijzijnde foto mét land (raster van ~1°), anders "onbekend". */
    private fun vulOnbekend(codes: ArrayList<String>) {
        val los = (0 until n).filter { landVan[it] < 0 }
        if (los.isEmpty()) return
        val cel = 1.0 / 360
        val raster = HashMap<Long, Int>()
        for (i in 0 until n) if (landVan[i] >= 0) raster.putIfAbsent(vakSleutel(0, floor(wx[i] / cel).toLong(), floor(wy[i] / cel).toLong()), i)
        var onbekend = -1
        for (i in los) {
            val kx = floor(wx[i] / cel).toLong(); val ky = floor(wy[i] / cel).toLong()
            var best = -1; var bestD = Double.MAX_VALUE
            for (r in 0..3) {
                for (dx in -r..r) for (dy in -r..r) {
                    if (max(kotlin.math.abs(dx), kotlin.math.abs(dy)) != r) continue
                    val j = raster[vakSleutel(0, kx + dx, ky + dy)] ?: continue
                    val d = hypot(wx[j] - wx[i], wy[j] - wy[i])
                    if (d < bestD) { bestD = d; best = j }
                }
                if (best >= 0) break
            }
            landVan[i] = if (best >= 0) landVan[best] else {
                if (onbekend < 0) { codes.add(ONBEKEND); onbekend = codes.size - 1 }
                onbekend
            }
        }
    }

    private fun bouw(): List<KaartGroep> {
        // leden per land (oplopend, want i loopt op)
        val tel = IntArray(landCodes.size)
        for (l in landVan) tel[l]++
        val perLand = Array(landCodes.size) { IntArray(tel[it]) }
        val vul = IntArray(landCodes.size)
        for (i in 0 until n) { val l = landVan[i]; perLand[l][vul[l]++] = i }
        val landen = landCodes.indices.map { l -> maak("l:${landCodes[l]}", landNamen[l], KaartNiveau.LAND, perLand[l], emptyList(), Z_LAND, l) }

        val wortels = ArrayList<KaartGroep>()
        for (deel in Werelddeel.entries) {
            val inDeel = landen.filter { g -> deelVan(landCodes[g.cluster.land]) == deel }
            if (inDeel.isEmpty()) continue
            // regio's met foto's; een regio met één land is gewoon dat land (anders twee markers na elkaar op dezelfde plek)
            val regios = inDeel.groupBy { Regio.van(landCodes[it.cluster.land]) }.map { (r, ls) ->
                if (r == null || ls.size == 1) ls else listOf(maak("r:${r.name}", Taal.s(r.label), KaartNiveau.REGIO, samen(ls), ls, Z_REGIO, -1))
            }.flatten()
            // werelddeel met maar één regio (Zuid-Amerika, alleen VS + Canada, …): dan meteen de landen eronder
            val kinderen = if (regios.size == 1 && regios[0].cluster.niveau == KaartNiveau.REGIO) regios[0].kinderen else regios
            wortels += maak("w:${deel.name}", Taal.s(deel.label), KaartNiveau.WERELDDEEL, samen(inDeel), kinderen, Z_DEEL, -1)
        }
        return wortels
    }

    private fun deelVan(iso: String) = Regio.van(iso)?.deel ?: Werelddeel.OVERIG

    private fun samen(groepen: List<KaartGroep>): IntArray {
        val uit = IntArray(groepen.sumOf { it.cluster.aantal })
        var p = 0
        for (g in groepen) { System.arraycopy(g.cluster.leden, 0, uit, p, g.cluster.aantal); p += g.cluster.aantal }
        uit.sort()
        return uit
    }

    /** KaartGroep met vak en plek: het zwaartepunt van het drukste vakje van ~40 km (daar zitten de meeste foto's). */
    private fun maak(sleutel: String, naam: String, niveau: KaartNiveau, leden: IntArray, kinderen: List<KaartGroep>, zMax: Int, land: Int): KaartGroep {
        var x0 = 1.0; var x1 = 0.0; var y0 = 1.0; var y1 = 0.0
        val cel = 1.0 / 1024
        val vakken = HashMap<Long, DoubleArray>()
        var best: DoubleArray? = null
        for (i in leden) {
            val x = wx[i]; val y = wy[i]
            if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
            val v = vakken.getOrPut(vakSleutel(0, floor(x / cel).toLong(), floor(y / cel).toLong())) { DoubleArray(3) }
            v[0]++; v[1] += x; v[2] += y
            if (best == null || v[0] > best[0]) best = v
        }
        val b = best!!
        val g = KaartGroep(Cluster(sleutel, b[1] / b[0], b[2] / b[0], leden, niveau, naam, land), x0, y0, x1, y1, kinderen, zMax)
        groepen[sleutel] = g
        for (k in kinderen) ouder[k.cluster.sleutel] = g
        return g
    }

    // ---- Afstandsclusters per heel zoomniveau, binnen een land ----

    private class Niveau(val perLand: Array<Array<Cluster>>)
    private val niveaus = HashMap<Int, Niveau>()

    /** Afstandsclusters op heel zoomniveau [z] (lui berekend, daarna gecachet; ook per clusterinstelling). */
    private fun niveau(z: Int): Niveau {
        val sleutel = z * 16 + Instellingen.cluster.ordinal
        synchronized(niveaus) { niveaus[sleutel]?.let { return it } }
        val nieuw = bereken(z)
        synchronized(niveaus) { niveaus[sleutel] = nieuw }
        return nieuw
    }

    /** Is niveau [z] al uitgerekend (dan is [toon] meteen klaar en is een laadindicator niet nodig)? */
    fun klaar(z: Int) = synchronized(niveaus) { niveaus.containsKey(z * 16 + Instellingen.cluster.ordinal) }

    /**
     * Rastervakken van [straalDp] px op het hele zoomniveau [z], per land; daarna gaan buurvakken (zelfde land) waarvan de
     * zwaartepunten dichter dan die straal bij elkaar liggen samen (grootste eerst). Zo verspringt de indeling niet
     * tijdens het zoomen binnen één niveau, en loopt een plek nooit over een landsgrens.
     */
    private fun bereken(z: Int): Niveau {
        val schaalZ = 512.0 * (1L shl z) * pr
        val straal = straalDp(z) * pr
        val cel = straal / schaalZ
        val vakken = HashMap<Long, Cel>()
        for (i in 0 until n) {
            val x = wx[i]; val y = wy[i]; val l = landVan[i]
            val kx = floor(x / cel).toLong(); val ky = floor(y / cel).toLong()
            vakken.getOrPut(vakSleutel(l, kx, ky)) { Cel(l, kx, ky) }.voeg(i, x, y)
        }
        val lijst = vakken.values.sortedByDescending { it.n }
        for (a in lijst) {
            if (a.weg) continue
            for (dx in -1..1) for (dy in -1..1) {
                if (dx == 0 && dy == 0) continue
                val b = vakken[vakSleutel(a.land, a.kx + dx, a.ky + dy)] ?: continue
                if (b.weg || b.n > a.n) continue
                val d = hypot((a.sx / a.n - b.sx / b.n) * schaalZ, (a.sy / a.n - b.sy / b.n) * schaalZ)
                if (d < straal) { a.slok(b); b.weg = true }
            }
        }
        val perLand = Array(landCodes.size) { ArrayList<Cluster>() }
        for (v in lijst) if (!v.weg) perLand[v.land] += Cluster("p$z:${v.land}:${v.kx}:${v.ky}", v.sx / v.n, v.sy / v.n, v.leden.copyOf(v.n).also { it.sort() }, land = v.land)
        return Niveau(Array(perLand.size) { perLand[it].toTypedArray() })
    }

    // ---- Wat er te zien is ----

    private fun schaal(z: Int) = 512.0 * 2.0.pow(z) * pr

    /** Blijft [g] op niveau [z] één marker? Klein genoeg op het scherm, en nog niet voorbij zijn maximale niveau. */
    private fun eenheid(g: KaartGroep, z: Int) = z < g.zMax && max(g.x1 - g.x0, g.y1 - g.y0) * schaal(z) <= drempelPx * Instellingen.cluster.straal

    /**
     * Clusters op heel zoomniveau [z] in het vak [b] (x0, y0, x1, y1 in wereldeenheden). Meer dan [max]: de grootste
     * (een begrensd aantal markers = begrensd werk voor Compose en een begrensd aantal thumbnails tegelijk).
     */
    fun toon(z: Int, b: DoubleArray, max: Int): List<Cluster> {
        val uit = ArrayList<Cluster>()
        for (g in wortels) verzamel(g, z, b, uit)
        val zonderOverlap = voegOverlapSamen(uit, z)
        return if (zonderOverlap.size <= max) zonderOverlap else zonderOverlap.sortedByDescending { it.aantal }.take(max)
    }

    /**
     * Markers die elkaar (bijna) bedekken één marker maken: de grootste blijft staan en neemt de rest op. Naam = de
     * kleinste gemeenschappelijke groep (Italië + Kroatië + Slovenië → Zuid-Europa); zijn het allemaal plekken, dan
     * de plaatsnaam. Zo'n samengevoegde marker valt bij inzoomen altijd weer uiteen.
     */
    private fun voegOverlapSamen(lijst: List<Cluster>, z: Int): List<Cluster> {
        if (lijst.size < 2) return lijst
        val s = schaal(z)
        val grens = overlapPx / s
        val op = lijst.sortedByDescending { it.aantal }
        val uit = ArrayList<Cluster>()
        val bij = ArrayList<ArrayList<Cluster>>()
        for (c in op) {
            val i = uit.indexOfFirst { hypot(it.wx - c.wx, it.wy - c.wy) < grens }
            if (i < 0) { uit += c; bij += arrayListOf(c) } else bij[i] += c
        }
        if (bij.all { it.size == 1 }) return lijst
        return bij.map { groep ->
            if (groep.size == 1) return@map groep[0]
            val hoofd = groep[0]
            val leden = IntArray(groep.sumOf { it.aantal }).also { a -> var p = 0; for (c in groep) { System.arraycopy(c.leden, 0, a, p, c.aantal); p += c.aantal }; a.sort() }
            val allePlek = groep.all { it.niveau == KaartNiveau.PLEK }
            val gemeen = if (allePlek && groep.all { it.land == hoofd.land }) null else gemeenschappelijk(groep)
            Cluster(
                "m:" + groep.joinToString("+") { it.sleutel }, hoofd.wx, hoofd.wy, leden,
                niveau = gemeen?.cluster?.niveau ?: KaartNiveau.PLEK, naam = gemeen?.cluster?.naam,
                land = if (gemeen == null) hoofd.land else -1, samen = true,
            )
        }
    }

    /** Kleinste groep (land, regio, werelddeel) waar alle clusters in vallen; null als er geen is. */
    private fun gemeenschappelijk(lijst: List<Cluster>): KaartGroep? {
        fun keten(c: Cluster): List<KaartGroep> {
            var g: KaartGroep? = groepen[c.sleutel] ?: if (c.land >= 0) groepen["l:${landCodes[c.land]}"] else null
            val uit = ArrayList<KaartGroep>()
            while (g != null) { uit += g; g = ouder[g.cluster.sleutel] }
            return uit
        }
        val eerste = keten(lijst[0])
        val rest = lijst.drop(1).map { keten(it).toSet() }
        return eerste.firstOrNull { g -> rest.all { g in it } }
    }

    /** Wat er van groep [g] op niveau [z] te zien is (alleen binnen vak [b], of alles als [b] null is). */
    private fun verzamel(g: KaartGroep, z: Int, b: DoubleArray?, uit: ArrayList<Cluster>) {
        fun binnen(c: Cluster) = b == null || (c.wx >= b[0] && c.wx <= b[2] && c.wy >= b[1] && c.wy <= b[3])
        if (b != null && (g.x1 < b[0] || g.x0 > b[2] || g.y1 < b[1] || g.y0 > b[3])) return
        if (eenheid(g, z)) { if (binnen(g.cluster)) uit += g.cluster; return }
        if (g.kinderen.isNotEmpty()) { for (k in g.kinderen) verzamel(k, z, b, uit); return }
        for (c in niveau(z).perLand[g.cluster.land]) if (binnen(c)) uit += c
    }

    /** Valt [c] op heel zoomniveau [z] nog uiteen in meer markers? */
    fun splitst(c: Cluster, z: Int): Boolean {
        if (c.samen) return true
        if (c.niveau != KaartNiveau.PLEK) {
            val g = groepen[c.sleutel] ?: return false
            return ArrayList<Cluster>().also { verzamel(g, z, null, it) }.size > 1
        }
        if (c.land < 0) return false
        var x0 = 1.0; var x1 = 0.0; var y0 = 1.0; var y1 = 0.0
        for (i in c.leden) { val x = wx[i]; val y = wy[i]; if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y }
        val marge = 3 * straalDp(z) * pr / schaal(z)
        var gevonden = 0
        for (k in niveau(z).perLand[c.land]) {
            if (k.wx < x0 - marge || k.wx > x1 + marge || k.wy < y0 - marge || k.wy > y1 + marge) continue
            if (overlapt(k.leden, c.leden) && ++gevonden > 1) return true
        }
        return false
    }

    /** Hebben twee oplopend gesorteerde rijen een gemeenschappelijk element? */
    private fun overlapt(a: IntArray, b: IntArray): Boolean {
        var i = 0; var j = 0
        while (i < a.size && j < b.size) { val x = a[i]; val y = b[j]; if (x == y) return true; if (x < y) i++ else j++ }
        return false
    }

    private val plaatsen = HashMap<String, String>()

    /** Naam onder een marker: werelddeel/regio/land, of bij een plek de meest voorkomende plaatsnaam + het land. */
    fun naam(c: Cluster): String {
        c.naam?.let { return it }
        synchronized(plaatsen) { plaatsen[c.sleutel]?.let { return it } }
        val tel = HashMap<String, Int>()
        val stap = max(1, c.aantal / 2000)
        var i = 0
        while (i < c.aantal) { markers.stad[c.leden[i]]?.let { tel[it] = (tel[it] ?: 0) + 1 }; i += stap }
        val stad = tel.maxByOrNull { it.value }?.key
        val land = if (c.land >= 0) landNamen[c.land] else markers.land[c.hoofd]
        val uit = listOfNotNull(stad, land?.takeIf { landCodes.getOrNull(c.land) != ONBEKEND }).joinToString(", ")
        synchronized(plaatsen) { plaatsen[c.sleutel] = uit }
        return uit
    }

    /** Leden-bbox (wereldeenheden) van een cluster, voor het inzoomen. */
    fun vak(c: Cluster): DoubleArray {
        groepen[c.sleutel]?.let { return doubleArrayOf(it.x0, it.y0, it.x1, it.y1) }
        var x0 = 1.0; var x1 = 0.0; var y0 = 1.0; var y1 = 0.0
        for (i in c.leden) { val x = wx[i]; val y = wy[i]; if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y }
        return doubleArrayOf(x0, y0, x1, y1)
    }

    /** Alle niveaus tot en met [tot] alvast uitrekenen (op de achtergrond, na het opbouwen). */
    fun warmOp(tot: Int) { for (z in 0..tot) niveau(z) }

    companion object {
        const val ONBEKEND = "??"
    }
}
