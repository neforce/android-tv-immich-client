package com.immichclient.tv

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Taal van de app, los van de systeemtaal. De keuze staat in de prefs ("instellingen"/"taal", per apparaat) en wordt
 * toegepast via een Context met eigen Configuration ([wrap], in MainActivity.attachBaseContext); na een wissel
 * `recreate()`. Voor tekst buiten Compose (koppelpagina, foutmeldingen op de serverthread, rijkoppen) is er [ctx]:
 * de applicatiecontext in de gekozen taal. Datum-/tijdpatronen en getalnotatie volgen de gekozen [locale].
 *
 * Een taal toevoegen: `values-xx/strings.xml` (alle strings + plurals + de datumpatronen), code in [TALEN] en de naam in
 * [naam] (in de eigen taal); verder niets.
 */
object Taal {
    /** Ondersteunde talen (ISO 639-1), in de volgorde van het keuzescherm. */
    val TALEN = listOf("en", "nl", "de", "fr", "es")
    private const val PREFS = "instellingen"
    private const val SLEUTEL = "taal"

    /** Naam van de taal in die taal zelf (voor het keuzescherm en de instelling). */
    fun naam(code: String) = when (code) {
        "nl" -> "Nederlands"; "de" -> "Deutsch"; "fr" -> "Français"; "es" -> "Español"; else -> "English"
    }

    @Volatile var code: String = "en"
        private set
    /** Is er ooit een taal gekozen (anders eerst het keuzescherm)? */
    var gekozen by mutableStateOf(false)
        private set
    /** Sectie waarin de activity na een taalwissel (recreate) opnieuw opent, één keer (Instellingen). */
    @Volatile var herstartSectie: Sectie? = null
    /** Na die herstart de focus op de regel Taal zetten (de lijst staat nog op dezelfde plek, zie InstellingenScherm). */
    @Volatile var focusTaal = false
    val locale: Locale get() = Locale.forLanguageTag(code)

    /** Applicatiecontext in de gekozen taal, voor strings buiten Compose. */
    lateinit var ctx: Context
        private set
    private lateinit var basis: Context

    /** Taal die de systeeminstelling voorstelt (als die ondersteund wordt), anders Engels. */
    fun systeemTaal(c: Context): String {
        val l = c.resources.configuration.locales.let { if (it.isEmpty) Locale.getDefault() else it[0] }
        return l.language.takeIf { it in TALEN } ?: "en"
    }

    /** Eén keer bij het starten van het proces (App.onCreate). */
    fun start(app: Context) {
        basis = app.applicationContext
        val p = basis.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val opgeslagen = p.getString(SLEUTEL, null)?.takeIf { it in TALEN }
        gekozen = opgeslagen != null
        code = opgeslagen ?: systeemTaal(basis)
        pasToe()
    }

    /** Keuze bewaren en meteen toepassen; de activity moet daarna `recreate()` doen. */
    fun kies(c: Context, nieuw: String) {
        if (nieuw !in TALEN) return
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(SLEUTEL, nieuw).apply()
        code = nieuw
        gekozen = true
        pasToe()
    }

    private fun pasToe() {
        Locale.setDefault(locale)
        ctx = wrap(basis)
        Formaat.ververs()
    }

    /** Context met de gekozen taal (voor Activity.attachBaseContext). */
    fun wrap(base: Context): Context = base.createConfigurationContext(configuratie(base, locale))

    private fun configuratie(base: Context, l: Locale) = Configuration(base.resources.configuration).apply { setLocale(l); setLayoutDirection(l) }

    /** Resources in een andere ondersteunde taal (koppelpagina in de taal van de telefoon). */
    fun resources(taal: String): Resources =
        if (taal == code) ctx.resources else basis.createConfigurationContext(configuratie(basis, Locale.forLanguageTag(taal))).resources

    /** Eerste ondersteunde taal uit een Accept-Language-header ("nl-NL,nl;q=0.9,en;q=0.8"), anders de app-taal. */
    fun uitAcceptLanguage(header: String?): String {
        if (header.isNullOrBlank()) return code
        for (deel in header.split(',')) {
            val tag = deel.substringBefore(';').trim().lowercase(Locale.ROOT)
            val taal = tag.substringBefore('-')
            if (taal in TALEN) return taal
        }
        return code
    }

    fun s(@StringRes id: Int, vararg args: Any): String = ctx.getString(id, *args)
    fun meervoud(@PluralsRes id: Int, n: Int, vararg args: Any): String = ctx.resources.getQuantityString(id, n, *args)
    /** Getal met duizendtalscheiding volgens de taal ("12.345" / "12,345" / "12 345"). */
    fun getal(n: Int): String = Formaat.getal.format(n.toLong())
    fun getal(n: Long): String = Formaat.getal.format(n)
}

/** Datum-/tijdnotatie in de gekozen taal; de patronen staan in de strings (per taal), zodat een vertaling ze kan aanpassen. */
object Formaat {
    lateinit var maandJaar: DateTimeFormatter; private set
    lateinit var maandKort: DateTimeFormatter; private set
    lateinit var dagKort: DateTimeFormatter; private set
    lateinit var dagJaar: DateTimeFormatter; private set
    lateinit var dagLang: DateTimeFormatter; private set
    lateinit var tijd: DateTimeFormatter; private set
    lateinit var getal: NumberFormat; private set

    fun ververs() {
        val l = Taal.locale
        fun f(id: Int) = DateTimeFormatter.ofPattern(Taal.s(id), l)
        maandJaar = f(R.string.patroon_maand_jaar); maandKort = f(R.string.patroon_maand_kort)
        dagKort = f(R.string.patroon_dag_kort); dagJaar = f(R.string.patroon_dag_jaar); dagLang = f(R.string.patroon_dag_lang)
        tijd = f(R.string.patroon_tijd)
        getal = NumberFormat.getIntegerInstance(l)
    }
}

private fun String.hoofdletter() = replaceFirstChar { if (it.isLowerCase()) it.titlecase(Taal.locale) else it.toString() }

fun maandTitel(sleutel: String): String =
    runCatching { LocalDate.parse(sleutel.take(10)).format(Formaat.maandJaar).hoofdletter() }.getOrDefault(sleutel)

fun dagTitel(d: LocalDate, metJaar: Boolean): String {
    val vandaag = LocalDate.now()
    return when (d) {
        vandaag -> Taal.s(R.string.vandaag)
        vandaag.minusDays(1) -> Taal.s(R.string.gisteren)
        else -> d.format(if (metJaar || d.year != vandaag.year) Formaat.dagJaar else Formaat.dagKort).hoofdletter()
    }
}

fun maandJaar(t: LocalDateTime): String = t.format(Formaat.maandJaar)

fun datumLang(t: LocalDateTime): String = t.format(Formaat.dagLang).hoofdletter() + " ·  " + t.format(Formaat.tijd)

fun jarenGeleden(n: Int) = Taal.meervoud(R.plurals.jaren_geleden, n, n)

/**
 * Landnamen: Immich geeft ze in het Engels. Via de ISO-code (Locale.getISOCountries + Engelse naam) naar de naam in de
 * taal van de app; onbekende namen blijven zoals ze zijn.
 */
object Landnaam {
    private val isoVanEngels: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        for (iso in Locale.getISOCountries()) {
            val n = Locale("", iso).getDisplayCountry(Locale.ENGLISH).lowercase(Locale.ROOT)
            m[n] = iso
            // ICU schrijft "Bosnia & Herzegovina", "St. Lucia"; de geocoder van Immich "Bosnia and Herzegovina", "Saint Lucia"
            m[n.replace(" & ", " and ")] = iso
            if (n.startsWith("st. ")) m["saint " + n.removePrefix("st. ")] = iso
        }
        // namen die Immich (geocoder) anders schrijft dan de ICU-weergave
        m["the netherlands"] = "NL"; m["netherlands"] = "NL"; m["united states of america"] = "US"; m["united states"] = "US"
        m["czech republic"] = "CZ"; m["czechia"] = "CZ"; m["russia"] = "RU"; m["south korea"] = "KR"; m["north korea"] = "KP"
        m["vietnam"] = "VN"; m["macedonia"] = "MK"; m["north macedonia"] = "MK"; m["ivory coast"] = "CI"; m["côte d'ivoire"] = "CI"
        m["swaziland"] = "SZ"; m["eswatini"] = "SZ"; m["turkey"] = "TR"; m["türkiye"] = "TR"; m["uk"] = "GB"; m["great britain"] = "GB"
        m["cape verde"] = "CV"; m["myanmar (burma)"] = "MM"; m["burma"] = "MM"; m["the bahamas"] = "BS"; m["the gambia"] = "GM"
        m["bolivia"] = "BO"; m["tanzania"] = "TZ"; m["laos"] = "LA"; m["syria"] = "SY"; m["iran"] = "IR"; m["moldova"] = "MD"
        m["brunei"] = "BN"; m["micronesia"] = "FM"; m["palestine"] = "PS"; m["vatican city"] = "VA"; m["hong kong"] = "HK"; m["macau"] = "MO"
        m
    }
    private val cache = HashMap<String, String>()
    private var cacheTaal = ""

    /** Engelse naam (zoals Immich hem geeft) → ISO 3166-code, of null als hij onbekend is. */
    fun iso(engels: String): String? = isoVanEngels[engels.trim().lowercase(Locale.ROOT)]

    /** Naam van een land (ISO-code) in de app-taal. */
    fun vanIso(iso: String): String = Locale("", iso).getDisplayCountry(Taal.locale).ifBlank { iso }

    /** Engelse naam → naam in de app-taal (gecachet per taal). */
    @Synchronized fun vertaal(engels: String): String {
        if (cacheTaal != Taal.code) { cache.clear(); cacheTaal = Taal.code }
        return cache.getOrPut(engels) {
            val iso = isoVanEngels[engels.trim().lowercase(Locale.ROOT)] ?: return@getOrPut engels
            Locale("", iso).getDisplayCountry(Taal.locale).ifBlank { engels }
        }
    }
}
