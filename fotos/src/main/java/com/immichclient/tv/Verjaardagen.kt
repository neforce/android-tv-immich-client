package com.immichclient.tv

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cake
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.Year
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/*
 * Verjaardagen: personen met een geboortedatum in Immich krijgen op hun verjaardag (en nog een paar dagen erna,
 * instelling) een kaart vóór de herinneringen bovenaan de tijdlijn, met de foto's van die persoon die op zijn/haar
 * verjaardag gemaakt zijn (alle jaren). Ophalen via de gefilterde tijdlijn: /timeline/buckets?personId=… geeft de
 * maanden, alleen die van de geboortemaand worden geladen en daarna op de dag gefilterd.
 */

/** Datumrekenwerk rond verjaardagen (los van Android, zie VerjaardagTest). */
object Jarig {
    /** De verjaardag in [jaar]; wie op 29 februari jarig is, viert dat in een gewoon jaar op 28 februari. */
    fun inJaar(geboren: LocalDate, jaar: Int): LocalDate =
        if (geboren.monthValue == 2 && geboren.dayOfMonth == 29 && !Year.isLeap(jaar.toLong())) LocalDate.of(jaar, 2, 28)
        else geboren.withYear(jaar)

    /**
     * De verjaardag die op [vandaag] valt of hooguit [dagenErna] dagen geleden was (ook over de jaarwisseling: jarig op
     * 31-12, kijken op 1-1), anders null. De geboortedag zelf telt niet als verjaardag.
     */
    fun recent(geboren: LocalDate, vandaag: LocalDate, dagenErna: Int): LocalDate? {
        for (j in intArrayOf(vandaag.year, vandaag.year - 1)) {
            if (j <= geboren.year) continue
            val d = inJaar(geboren, j)
            if (ChronoUnit.DAYS.between(d, vandaag) in 0..dagenErna.toLong()) return d
        }
        return null
    }

    /** Leeftijd die op verjaardag [dag] bereikt wordt. */
    fun leeftijd(geboren: LocalDate, dag: LocalDate) = dag.year - geboren.year

    /** Is [datum] een verjaardag (of de geboortedag zelf)? */
    fun opVerjaardag(geboren: LocalDate, datum: LocalDate) = datum == inJaar(geboren, datum.year)

    /** Afstand in dagen tot de dichtstbijzijnde verjaardag; rond de jaarwisseling telt die van het buurjaar ook. */
    fun afstand(geboren: LocalDate, datum: LocalDate): Long =
        (-1..1).minOf { abs(ChronoUnit.DAYS.between(inJaar(geboren, datum.year + it), datum)) }

    /** Maanden (1-12) waarin een datum binnen [marge] dagen van de verjaardag kan vallen (schrikkeljaar en gewoon jaar). */
    fun maanden(geboren: LocalDate, marge: Int): Set<Int> {
        val uit = HashSet<Int>()
        for (j in intArrayOf(2000, 2001)) {
            val d = inJaar(geboren, j)
            var x = d.minusDays(marge.toLong())
            while (!x.isAfter(d.plusDays(marge.toLong()))) { uit += x.monthValue; x = x.plusDays(1) }
        }
        return uit
    }
}

@Immutable
data class Verjaardag(
    val persoonId: String,
    /** voornaam (eerste woord van de naam in Immich) */
    val naam: String,
    val volledigeNaam: String,
    /** de verjaardag zelf (dit jaar, of vorig jaar rond de jaarwisseling) */
    val dag: LocalDate,
    val leeftijd: Int,
    val dagenGeleden: Int,
    /** foto's op de verjaardag (alle jaren), oudste eerst; leeg = kaart met het gezicht */
    val fotos: List<Asset>,
    /** geen foto's op de dag zelf: dit zijn foto's van rond die datum */
    val rond: Boolean,
)

/** Wie er (bijna) jarig is, opgehaald als de datum, de instelling of het account verandert. */
object Verjaardagen {
    var lijst by mutableStateOf<List<Verjaardag>>(emptyList())
        private set
    /** test (`--es verjaardag_test 2026-11-21`): doen alsof het vandaag die dag is */
    @Volatile var testDatum: LocalDate? = null
    fun vandaag(): LocalDate = testDatum ?: LocalDate.now()

    /** marge in dagen voor foto's "rond" de verjaardag als er op de dag zelf niets is, en hoeveel daarvan */
    private const val MARGE = 7
    private const val MAX_ROND = 40
    private val slot = Mutex()
    private var klaar: String? = null
    private val fotoCache = HashMap<String, Pair<List<Asset>, Boolean>>()

    suspend fun laad(): Unit = slot.withLock {
        val dag = vandaag()
        val n = Instellingen.verjaardagDagen.coerceIn(0, 7)
        val sleutel = "${Immich.stand}|$dag|$n"
        if (sleutel == klaar) return@withLock
        val personen = runCatching { Immich.personen() }.getOrElse { Log.w("Fotos", "verjaardagen: ${it.message}"); return@withLock }
        val jarig = personen.mapNotNull { p -> p.geboren?.let { g -> Jarig.recent(g, dag, n)?.let { d -> Triple(p, g, d) } } }
            .sortedByDescending { it.third } // wie het laatst jarig was (vandaag) eerst
        val uit = coroutineScope {
            jarig.map { (p, g, d) ->
                async {
                    val (fotos, rond) = fotos(p.id, g)
                    Verjaardag(
                        persoonId = p.id, naam = p.naam.trim().substringBefore(' '), volledigeNaam = p.naam, dag = d,
                        leeftijd = Jarig.leeftijd(g, d), dagenGeleden = ChronoUnit.DAYS.between(d, dag).toInt(), fotos = fotos, rond = rond,
                    )
                }
            }.awaitAll()
        }
        lijst = uit
        klaar = sleutel
        Log.i("Fotos", "verjaardagen $dag (+$n dagen): ${uit.size} kaart(en) " + uit.joinToString { "${it.fotos.size} foto's${if (it.rond) " (rond)" else ""}" })
        Unit
    }

    /** Foto's van deze persoon op zijn/haar verjaardag (alle jaren); geen enkele → de dichtstbijzijnde rond die datum. */
    private suspend fun fotos(id: String, geboren: LocalDate): Pair<List<Asset>, Boolean> {
        val sleutel = "${Immich.stand}|$id|$geboren"
        synchronized(fotoCache) { fotoCache[sleutel] }?.let { return it }
        val uit = runCatching {
            val filter = Filter(persoonId = id)
            val maanden = Immich.maanden(filter, nieuwsteEerst = false)
            suspend fun laad(m: Set<Int>) = coroutineScope {
                maanden.filter { it.sleutel.substring(5, 7).toIntOrNull()?.let { x -> x in m } == true }
                    .map { async { Immich.maand(filter, false, it.sleutel) } }.awaitAll().flatten()
            }
            val geboorteMaand = laad(setOf(Jarig.inJaar(geboren, 2001).monthValue, geboren.monthValue))
            val opDag = geboorteMaand.filter { Jarig.opVerjaardag(geboren, it.datum.toLocalDate()) }
            if (opDag.isNotEmpty()) opDag.sortedBy { it.datum } to false
            else {
                // rond de verjaardag (ook in de buurmaand als de dag aan het begin/eind van de maand valt)
                val extra = Jarig.maanden(geboren, MARGE) - setOf(geboren.monthValue, Jarig.inJaar(geboren, 2001).monthValue)
                val alle = geboorteMaand + (if (extra.isEmpty()) emptyList() else laad(extra))
                alle.map { it to Jarig.afstand(geboren, it.datum.toLocalDate()) }
                    .filter { it.second <= MARGE }
                    .sortedBy { it.second }.take(MAX_ROND)
                    .map { it.first }.sortedBy { it.datum } to true
            }
        }.onFailure { Log.w("Fotos", "verjaardag foto's: ${it.message}") }.getOrElse { return emptyList<Asset>() to false }
        synchronized(fotoCache) { fotoCache[sleutel] = uit }
        return uit
    }
}

/** Kaart in de rij boven de tijdlijn; OK = diashow met de verjaardagsfoto's (of de tijdlijn van die persoon). */
@Composable
fun VerjaardagKaart(v: Verjaardag, nav: Nav, breedte: Float = 168f) {
    val titel = stringResource(if (v.rond) R.string.verjaardag_rond else R.string.verjaardag_titel, v.naam)
    var focus by remember { mutableStateOf(false) }
    // confetti: de eerste seconden na het verschijnen, daarna alleen zolang de kaart focus heeft
    var net by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(5000); net = false }
    Card(
        onClick = {
            if (v.fotos.isNotEmpty()) nav.bekijk(LijstBron(v.fotos), 0, titel, true, null)
            else nav.open(Scherm.Tijdlijn(v.volledigeNaam, null, Filter(persoonId = v.persoonId), true, false, nav.scope))
        },
        modifier = Modifier.width(breedte.dp).height((breedte * 1.25f).dp).onFocusChanged { focus = it.isFocused },
        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
        scale = CardDefaults.scale(focusedScale = 1.05f),
    ) {
        Box(Modifier.fillMaxSize().background(Kleur.vlakHoog)) {
            val omslag = v.fotos.lastOrNull()
            if (omslag != null) AsyncImage(kaartBeeld(omslag.id), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else AsyncImage(Immich.gezicht(v.persoonId), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(onderVerloop))
            Confetti(focus || net, v.persoonId.hashCode(), Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.TopStart).padding(10.dp).background(Kleur.primair, CircleShape).padding(7.dp)) {
                Icon(Icons.Filled.Cake, null, Modifier.size(20.dp), tint = Kleur.opPrimair)
            }
            Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                Text(v.naam, style = MaterialTheme.typography.displaySmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val wanneer = when (v.dagenGeleden) {
                    0 -> stringResource(R.string.verjaardag_vandaag)
                    1 -> stringResource(R.string.verjaardag_gisteren)
                    else -> pluralStringResource(R.plurals.verjaardag_geleden, v.dagenGeleden, v.dagenGeleden)
                }
                Text(wanneer, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 2)
                val leeftijd = pluralStringResource(if (v.dagenGeleden == 0) R.plurals.verjaardag_wordt else R.plurals.verjaardag_werd, v.leeftijd, v.leeftijd)
                val aantal = if (v.fotos.isEmpty()) null else pluralStringResource(R.plurals.fotos, v.fotos.size, Taal.getal(v.fotos.size))
                Text(listOfNotNull(leeftijd, aantal).joinToString("  ·  "), style = MaterialTheme.typography.labelSmall, color = Color(0xCCFFFFFF), maxLines = 1)
            }
        }
    }
}

private val CONFETTI_KLEUREN = arrayOf(
    Color(0xFFFFC94D), Color(0xFFFF6B8B), Color(0xFF6BD5FF), Color(0xFF8BE38B), Color(0xFFC89BFF), Color(0xFFFFFFFF),
)

/**
 * Vaste set snippers (geen objecten per frame): elk snipper valt in een lus van boven naar onder met eigen vertraging,
 * snelheid, slinger en draaiing. Alleen [t] verandert per frame en wordt alleen in de tekenfase gelezen.
 */
private class ConfettiStaat(seed: Int) {
    val n = 34
    private val r = java.util.Random(seed.toLong())
    val x = FloatArray(n) { r.nextFloat() }
    /** start in seconden (gespreid, zodat het begint als een bui) */
    val vertraging = FloatArray(n) { r.nextFloat() * 1.8f }
    /** kaarthoogtes per seconde: één val duurt 1,7-3 s */
    val snelheid = FloatArray(n) { 0.33f + r.nextFloat() * 0.25f }
    val slinger = FloatArray(n) { 1.5f + r.nextFloat() * 2.5f }
    val fase = FloatArray(n) { r.nextFloat() * 6.28f }
    val draai = FloatArray(n) { (r.nextFloat() - 0.5f) * 600f }
    val kleur = IntArray(n) { r.nextInt(CONFETTI_KLEUREN.size) }
    val maat = FloatArray(n) { 5f + r.nextFloat() * 4f }
    /** zichtbaar van ronde [eerste] t/m [laatste] (bij stoppen: de lopende val afmaken, niet opnieuw beginnen) */
    val eerste = IntArray(n)
    val laatste = IntArray(n) { Int.MAX_VALUE }
    /** tijd in s sinds de start; < 0 = niets te tekenen */
    var t by mutableFloatStateOf(-1f)

    fun ronde(i: Int, tijd: Float): Float = (tijd - vertraging[i]) * snelheid[i]
}

@Composable
private fun Confetti(actief: Boolean, seed: Int, modifier: Modifier) {
    val s = remember(seed) { ConfettiStaat(seed) }
    LaunchedEffect(actief) {
        val nu = s.t
        if (actief) {
            if (nu < 0f) { s.eerste.fill(0); s.laatste.fill(Int.MAX_VALUE) }
            else for (i in 0 until s.n) {
                // weer aan terwijl de laatste snippers nog vielen: wie al klaar was, begint pas bij de volgende ronde bovenaan
                val r = floor(s.ronde(i, nu)).toInt()
                if (r > s.laatste[i]) s.eerste[i] = r + 1
                s.laatste[i] = Int.MAX_VALUE
            }
        } else {
            if (nu < 0f) return@LaunchedEffect
            for (i in 0 until s.n) s.laatste[i] = minOf(s.laatste[i], floor(s.ronde(i, nu)).toInt())
        }
        val eind = if (actief) Float.MAX_VALUE else (0 until s.n).maxOf { i ->
            if (s.laatste[i] < s.eerste[i]) 0f else s.vertraging[i] + (s.laatste[i] + 1) / s.snelheid[i]
        }
        val t0 = nu.coerceAtLeast(0f)
        val begin = withFrameNanos { it }
        while (true) {
            val t = withFrameNanos { t0 + (it - begin) / 1e9f }
            if (t > eind) { s.t = -1f; break }
            s.t = t
        }
    }
    Canvas(modifier) {
        val t = s.t
        if (t < 0f) return@Canvas
        val marge = 12.dp.toPx()
        val baan = size.height + 2 * marge
        val zwaai = 7.dp.toPx()
        for (i in 0 until s.n) {
            val p = s.ronde(i, t)
            if (p < 0f) continue
            val r = floor(p).toInt()
            if (r < s.eerste[i] || r > s.laatste[i]) continue
            val y = -marge + (p - r) * baan
            val x = s.x[i] * size.width + sin(t * s.slinger[i] + s.fase[i]) * zwaai
            val b = s.maat[i].dp.toPx()
            // "omklappen": de hoogte golft, zoals een draaiend papiertje
            val h = b * 0.5f * (0.25f + 0.75f * abs(cos(t * s.slinger[i] * 1.3f + s.fase[i])))
            rotate(t * s.draai[i] + s.fase[i] * 57f, Offset(x, y)) {
                drawRect(CONFETTI_KLEUREN[s.kleur[i]], Offset(x - b / 2, y - h / 2), Size(b, h))
            }
        }
    }
}
