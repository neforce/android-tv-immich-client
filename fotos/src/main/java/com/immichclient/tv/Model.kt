package com.immichclient.tv

import androidx.compose.runtime.Immutable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

@Immutable
data class Asset(
    val id: String,
    /** breedte / hoogte, al gedraaid zoals hij getoond wordt */
    val ratio: Float,
    val video: Boolean,
    val thumbhash: String?,
    val datum: LocalDateTime,
    /** "0:01:23.456000" */
    val duur: String?,
    val stad: String?,
    val land: String?,
    val favoriet: Boolean,
    /** GPS (null zonder locatie); voor de landkaart in de viewer */
    val lat: Double? = null,
    val lon: Double? = null,
)

@Immutable data class Maand(val sleutel: String, val aantal: Int)
@Immutable data class Album(
    val id: String, val naam: String, val omslag: String?, val aantal: Int,
    val van: LocalDate?, val tot: LocalDate?, val bijgewerkt: String, val gedeeld: Boolean, val oudsteEerst: Boolean,
)
@Immutable data class Persoon(val id: String, val naam: String, val favoriet: Boolean)
@Immutable data class Plaats(val stad: String, val land: String, val omslag: String)
@Immutable data class Herinnering(val id: String, val jaar: Int, val dag: LocalDate, val assets: List<Asset>) {
    val jarenGeleden get() = dag.year - jaar
}
data class Pagina(val assets: List<Asset>, val volgende: Int?)
@Immutable data class Gebruiker(val naam: String, val email: String)
/** Alle foto's met GPS voor de wereldkaart, als kolommen (compact: kunnen tienduizenden zijn). Volgorde = nieuwste eerst. */
class Markers(val id: Array<String>, val lat: DoubleArray, val lon: DoubleArray, val stad: Array<String?>, val land: Array<String?>) {
    val aantal get() = id.size
}
data class Details(
    val bestand: String, val camera: String?, val lens: String?, val instellingen: String?, val plaats: String?,
    val mensen: List<String>, val omschrijving: String?, val afmeting: String?,
)

/** Welke foto's de tijdlijn toont: alles, een album, een persoon of de favorieten. */
@Immutable
data class Filter(val albumId: String? = null, val persoonId: String? = null, val favorieten: Boolean = false) {
    fun query(nieuwsteEerst: Boolean) = buildString {
        append("order=").append(if (nieuwsteEerst) "desc" else "asc")
        // in een album geen partner-foto's en geen filter op "tijdlijn" (gearchiveerde albumfoto's horen erbij);
        // partner-foto's alleen in de hoofdtijdlijn, en alleen als die instelling aanstaat
        if (albumId != null) append("&albumId=").append(albumId)
        else append("&visibility=timeline&withPartners=").append(persoonId == null && !favorieten && Instellingen.partners)
        if (persoonId != null) append("&personId=").append(persoonId)
        if (favorieten) append("&isFavorite=true")
        append("&withStacked=true")
    }
}

/** "0:01:23.456000" -> "1:23" */
fun duurKort(d: String?): String? {
    if (d == null) return null
    // Immich 3 geeft in de tijdlijn milliseconden, elders "0:01:23.456000"
    d.toLongOrNull()?.let { ms -> val s = ms / 1000; return if (s <= 0) null else if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }
    val p = d.split(":")
    if (p.size != 3) return null
    val u = p[0].toIntOrNull() ?: 0
    val m = p[1].toIntOrNull() ?: 0
    val s = p[2].substringBefore('.').toIntOrNull() ?: 0
    if (u == 0 && m == 0 && s == 0) return null
    return if (u > 0) "%d:%02d:%02d".format(u, m, s) else "%d:%02d".format(m, s)
}

fun lokaleTijd(s: String, offsetUren: Double): LocalDateTime {
    val t = runCatching { LocalDateTime.parse(s.take(19)) }.getOrElse { LocalDateTime.of(1970, 1, 1, 0, 0) }
    // timeline/bucket geeft fileCreatedAt in UTC + localOffsetHours; nul = al lokaal
    return if (offsetUren == 0.0) t else t.plus((offsetUren * 60).toLong(), ChronoUnit.MINUTES)
}
