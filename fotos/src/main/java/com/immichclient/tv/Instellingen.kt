package com.immichclient.tv

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import kotlin.reflect.KProperty

/**
 * Alle instellingen van de app als één stateobject: elke instelling is Compose-state (wie hem leest, hercomponeert bij een
 * wijziging) en wordt meteen in de prefs bewaard. Opgeslagen per account, net als server/sleutel: `sleutel` of
 * `sleutel_<KIJKER>` (zie [laad], aangeroepen uit Immich.kiesAccount). Alleen het uiterlijk (naam/icoon van de
 * app) geldt voor het hele apparaat: dat zit in het manifest, niet in een account.
 *
 * Standaardwaarden = het gedrag van versie 1.1, zodat een update bij bestaande gebruikers niets verandert.
 */
object Instellingen {
    private const val PREFS = "instellingen"
    private var prefs: SharedPreferences? = null
    private var na = ""
    private val alle = ArrayList<Instelling<*>>()

    class Instelling<T>(
        private val sleutel: String, val standaard: T, private val perApparaat: Boolean,
        private val lees: (SharedPreferences, String, T) -> T, private val schrijf: (SharedPreferences.Editor, String, T) -> Unit,
    ) {
        private val staat = mutableStateOf(standaard)
        private val key get() = if (perApparaat) sleutel else sleutel + na
        var waarde: T
            get() = staat.value
            set(v) { staat.value = v; prefs?.edit()?.also { schrijf(it, key, v) }?.apply() }
        fun laad(p: SharedPreferences) { staat.value = runCatching { lees(p, key, standaard) }.getOrDefault(standaard) }
        operator fun getValue(t: Any?, p: KProperty<*>): T = waarde
        operator fun setValue(t: Any?, p: KProperty<*>, v: T) { waarde = v }
    }

    private fun bool(k: String, d: Boolean, perApparaat: Boolean = false) = Instelling(k, d, perApparaat, { p, s, x -> p.getBoolean(s, x) }, { e, s, v -> e.putBoolean(s, v) }).also { alle += it }
    private fun getal(k: String, d: Int) = Instelling(k, d, false, { p, s, x -> p.getInt(s, x) }, { e, s, v -> e.putInt(s, v) }).also { alle += it }
    private fun tekst(k: String, d: String, perApparaat: Boolean = false) =
        Instelling(k, d, perApparaat, { p, s, x -> p.getString(s, x) ?: x }, { e, s, v -> e.putString(s, v) }).also { alle += it }
    private inline fun <reified E : Enum<E>> keuze(k: String, d: E, perApparaat: Boolean = false) = Instelling(
        k, d, perApparaat,
        { p, s, x -> p.getString(s, null)?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: x },
        { e, s, v -> e.putString(s, v.name) },
    ).also { alle += it }

    // ---- Diashow ----
    /** Langzaam inzoomen tijdens de diashow. */
    var kenBurns by bool("ken_burns", true)
    var diaSeconden by getal("dia_s", 6)
    val DIA_TIJDEN = listOf(3, 5, 6, 8, 10, 15, 20)
    var diaWillekeurig by bool("dia_willekeurig", false)
    /** Video's in de diashow afspelen (anders overslaan). */
    var diaVideos by bool("dia_videos", true)
    var diaSchuiven by bool("dia_schuiven", false)
    /** wat er tijdens een lopende diashow over de foto heen staat (ringetje, badge, datum/plaats, landkaart) */
    var diaBeeld by keuze("dia_beeld", DiaBeeld.ALLES)

    // ---- Viewer ----
    var viewerKaart by keuze("viewer_kaart", KaartMaat.GROOT)
    /** Balk met datum/plaats verbergen na … ms (0 = altijd tonen). */
    var balkMs by getal("balk_ms", 3500)
    val BALK_TIJDEN = listOf(2000, 3500, 6000, 10000, 0)
    var teller by bool("teller", true)
    /** album(s) van de foto in de balk en het infopaneel (handig vanuit een persoon of de tijdlijn) */
    var albumNaam by bool("album_naam", true)
    /** wazige thumbhash-achtergrond rond foto's/video's die het scherm niet vullen (viewer + screensaver); uit = zwart */
    var wazig by bool("wazige_achtergrond", true)

    // ---- Tijdlijn ----
    var rijMaat by keuze("rij", RijMaat.NORMAAL)
    var nieuwsteEerst by bool("nieuwste_eerst", true)
    var partners by bool("partners", true)
    var herinneringen by bool("herinneringen", true)
    var start by keuze("start", Sectie.FOTOS)
    var groet by keuze("groet", Groet.UIT)
    var groetTekst by tekst("groet_tekst", "")

    // ---- Kaart ----
    var kaartStart by keuze("kaart_start", KaartStart.WERELD)
    var cluster by keuze("cluster", ClusterNiveau.NORMAAL)
    var kaartStijl by keuze("kaart_stijl", KaartStijl.DONKER)
    /** laatst bekeken plek: "lat,lon,zoom" (voor KaartStart.LAATST) */
    var kaartLaatst by tekst("kaart_laatst", "")

    // ---- Instellingenscherm ----
    /** alle instellingen tonen (anders alleen de basis); per apparaat */
    var alleInstellingen by bool("alle_instellingen", false, perApparaat = true)
    /** bij het starten kijken of er een nieuwe versie op GitHub staat; per apparaat */
    var updateCheck by bool("update_check", true, perApparaat = true)

    // ---- Uiterlijk ----
    var accent by keuze("accent", Accent.BLAUW)
    var uiterlijk by keuze("uiterlijk", Uiterlijk.FOTOS, perApparaat = true)

    // ---- Screensaver (DreamService, zie Dream.kt) ----
    var dreamBron by keuze("dream_bron", DreamBron.ALLES)
    /** gekozen album-id's, gescheiden door komma's (t/m 2.7.2 altijd één) */
    var dreamAlbum by tekst("dream_album", "")
    /** namen bij [dreamAlbum], gescheiden door regeleinden (zelfde volgorde) */
    var dreamAlbumNaam by tekst("dream_album_naam", "")
    /** bij een bronalbum/favorieten: zit de foto ook in een ander album, dan dié naam tonen i.p.v. de bron */
    var dreamAnderAlbum by bool("dream_ander_album", true)
    /** gekozen personen (komma-lijst; t/m 2.7.2 altijd één) en hun namen (regeleinden), zoals de albums */
    var dreamPersoon by tekst("dream_persoon", "")
    var dreamPersoonNaam by tekst("dream_persoon_naam", "")
    /** alleen foto's vanaf dit jaar (0 = alle jaren) */
    var dreamVanaf by getal("dream_vanaf", 0)
    var dreamVolgorde by keuze("dream_volgorde", DreamVolgorde.WILLEKEURIG)
    /** bij een vaste volgorde: waar de screensaver was ("bron-sleutel<TAB>datum"), zodat hij daar verdergaat */
    var dreamStand by tekst("dream_stand", "")
    var dreamSeconden by getal("dream_s", 15)
    val DREAM_TIJDEN = listOf(5, 8, 10, 15, 20, 30, 45, 60, 120)
    var dreamKlok by bool("dream_klok", true)
    /** landkaart rechtsonder: uit / klein / groot (t/m 2.7.2 een schakelaar "dream_kaart", zie [laad]) */
    var dreamKaart by keuze("dream_kaart_maat", KaartMaat.GROOT)
    var dreamKenBurns by bool("dream_ken_burns", true)
    /** datum/plaats (en albumnaam) linksonder */
    var dreamInfo by bool("dream_info", true)
    /** voortgangsringetje linksonder */
    var dreamRing by bool("dream_ring", true)
    /** screensaver die er stond vóór deze app werd ingesteld (om bij "uit" terug te zetten); per apparaat */
    var dreamVorige by tekst("dream_vorige", "", perApparaat = true)

    /** Prefs lezen voor dit account; [kijker] = profiel met een eigen account, anders het algemene account. */
    fun laad(c: Context, kijker: String?) {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        na = if (kijker.isNullOrBlank()) "" else "_$kijker"
        for (i in alle) i.laad(p)
        // t/m 1.2 heette de startweergave "land met de meeste foto's" anders; dat is de enige bewaarde waarde die niet
        // (meer) in KaartStart staat, en die zou anders stil terugvallen op de standaard (hele wereld)
        p.getString("kaart_start$na", null)?.let { oud -> if (KaartStart.entries.none { it.name == oud }) kaartStart = KaartStart.HOOFDLAND }
        // t/m 2.7.2 was de screensaver-kaart alleen aan/uit
        if (!p.contains("dream_kaart_maat$na") && !p.getBoolean("dream_kaart$na", true)) dreamKaart = KaartMaat.UIT
        // het manifest is de waarheid voor naam/icoon (bijv. na een herinstallatie met oude prefs)
        Uiterlijk.entries.firstOrNull { it.aan(c) }?.let { if (it != uiterlijk) uiterlijk = it }
    }

    val dreamAlbums get() = lijst(dreamAlbum)
    val dreamAlbumNamen get() = namen(dreamAlbumNaam)
    val dreamPersonen get() = lijst(dreamPersoon)
    val dreamPersoonNamen get() = namen(dreamPersoonNaam)
    /** keuzes voor "vanaf": 0 = alle jaren, dan 2000 … dit jaar */
    val DREAM_JAREN get() = listOf(0) + (2000..java.time.LocalDate.now().year)
    private fun lijst(s: String) = s.split(',').filter { it.isNotBlank() }
    private fun namen(s: String) = s.split('\n').filter { it.isNotBlank() }
    /** (id, naam)-paren van een keuze, namen aangevuld als ze ontbreken */
    fun paren(ids: List<String>, namen: List<String>) = ids.mapIndexed { i, id -> id to namen.getOrElse(i) { "" } }
    private fun wissel(ids: List<String>, nm: List<String>, id: String, naam: String): Pair<String, String> {
        val p = paren(ids, nm).toMutableList()
        if (!p.removeAll { it.first == id }) p += id to naam
        return p.joinToString(",") { it.first } to p.joinToString("\n") { it.second }
    }
    /** Album aan/uit zetten in de screensaver-keuze. */
    fun wisselDreamAlbum(id: String, naam: String) { wissel(dreamAlbums, dreamAlbumNamen, id, naam).let { dreamAlbum = it.first; dreamAlbumNaam = it.second } }
    /** Persoon aan/uit zetten in de screensaver-keuze. */
    fun wisselDreamPersoon(id: String, naam: String) { wissel(dreamPersonen, dreamPersoonNamen, id, naam).let { dreamPersoon = it.first; dreamPersoonNaam = it.second } }

    /** Diashow-tijd in ms. */
    val diaMs get() = diaSeconden * 1000L
}

// Labels zijn string-resources (vertaald); de UI haalt ze op met stringResource(x.label).
enum class KaartMaat(@StringRes val label: Int, val schaal: Float) { UIT(R.string.uit, 0f), KLEIN(R.string.klein, 0.62f), GROOT(R.string.groot, 1f) }
enum class RijMaat(@StringRes val label: Int, val hoogte: Float) { KLEIN(R.string.klein, 104f), NORMAAL(R.string.normaal, 138f), GROOT(R.string.groot, 186f) }
enum class Groet(@StringRes val label: Int) { UIT(R.string.uit), AUTO(R.string.automatisch), EIGEN(R.string.eigen_tekst) }
enum class KaartStart(@StringRes val label: Int) { WERELD(R.string.hele_wereld), EUROPA(R.string.europa), HOOFDLAND(R.string.land_meeste), LAATST(R.string.laatst_bekeken) }
/** Schaal op de clusterstraal en het diepste zoomniveau (zie Wereldkaart.kt: 14 ≈ 400 m per cluster op 52° breedte). */
enum class ClusterNiveau(@StringRes val label: Int, val straal: Float, val maxZoom: Double) { FIJN(R.string.fijn, 0.7f, 16.0), NORMAAL(R.string.normaal_cluster, 1f, 14.0), GROF(R.string.grof, 1.5f, 12.0) }
enum class KaartStijl(@StringRes val label: Int) { DONKER(R.string.donker), LICHT(R.string.licht), SATELLIET(R.string.satelliet) }
/** Wat er tijdens een lopende diashow in beeld staat; gepauzeerd (of na een toets) komt de info gewoon terug. */
enum class DiaBeeld(@StringRes val label: Int) { ALLES(R.string.dia_beeld_alles), ZONDER_RING(R.string.dia_beeld_zonder_ring), ALLEEN_FOTO(R.string.dia_beeld_alleen_foto) }
/** Waar de screensaver zijn foto's vandaan haalt. */
enum class DreamBron(@StringRes val label: Int) {
    ALLES(R.string.dream_alle_fotos), ALBUM(R.string.dream_albums), FAVORIETEN(R.string.sectie_favorieten),
    HERINNERINGEN(R.string.dream_herinneringen), PERSOON(R.string.dream_personen),
    /** de gekozen albums én de gekozen personen samen */
    COMBI(R.string.dream_combi),
}
/** Volgorde van de screensaver-foto's; bij een vaste volgorde gaat hij verder waar hij was ([Instellingen.dreamStand]). */
enum class DreamVolgorde(@StringRes val label: Int) { WILLEKEURIG(R.string.willekeurig), OUDSTE(R.string.oudste_eerst), NIEUWSTE(R.string.nieuwste_eerst) }

/** Accentkleur door de hele app (Kleur.primair e.d. lezen deze). */
enum class Accent(@StringRes val label: Int, val primair: Color, val donker: Color, val opPrimair: Color) {
    BLAUW(R.string.kleur_blauw, Color(0xFFACCBFA), Color(0xFF4250AF), Color(0xFF0B1A3A)),
    GROEN(R.string.kleur_groen, Color(0xFFA6E3B9), Color(0xFF2F7D4F), Color(0xFF07261A)),
    ORANJE(R.string.kleur_oranje, Color(0xFFFFC89A), Color(0xFFC2520C), Color(0xFF3A1A05)),
    ROZE(R.string.kleur_roze, Color(0xFFF9B8D4), Color(0xFFBE185D), Color(0xFF3B0A1F)),
    PAARS(R.string.kleur_paars, Color(0xFFD4BCFA), Color(0xFF6D3FC9), Color(0xFF22103F)),
    GRIJS(R.string.kleur_grijs, Color(0xFFD9D9DE), Color(0xFF5B5B66), Color(0xFF1A1A1E)),
}

/**
 * Naam + icoon + banner van de app in de launcher: per variant een activity-alias in het manifest; precies één staat
 * aan. Android kan een label/icoon niet tijdens het draaien veranderen, aan/uit zetten van een alias wel (de launcher
 * werkt dan binnen een paar seconden bij; een launcher die op package zoekt, vindt de aangezette alias). Het label en
 * de banner van een alias volgen de systeemtaal van de launcher (manifest-resources), de naam in de app de app-taal.
 */
enum class Uiterlijk(@StringRes val label: Int, val alias: String, @StringRes val uitleg: Int) {
    FOTOS(R.string.alias_fotos, "Fotos", R.string.uiterlijk_fotos),
    ALBUM(R.string.alias_album, "Fotoalbum", R.string.uiterlijk_album),
    HERINNERINGEN(R.string.alias_herinneringen, "Herinneringen", R.string.uiterlijk_herinneringen),
    FAMILIE(R.string.alias_familie, "Familie", R.string.uiterlijk_familie);

    private fun component(c: Context) = ComponentName(c, "${c.packageName}.$alias")
    fun aan(c: Context) = c.packageManager.getComponentEnabledSetting(component(c)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
        (c.packageManager.getComponentEnabledSetting(component(c)) == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && this == FOTOS)

    /** Deze variant aanzetten (eerst aan, dan de rest uit: er is altijd minstens één startpunt). */
    fun pasToe(c: Context) {
        val pm = c.packageManager
        runCatching {
            pm.setComponentEnabledSetting(component(c), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            for (u in entries) if (u != this) pm.setComponentEnabledSetting(u.component(c), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            Log.i("Fotos", "uiterlijk: $name ($alias)")
        }.onFailure { Log.w("Fotos", "uiterlijk", it) }
    }
}
