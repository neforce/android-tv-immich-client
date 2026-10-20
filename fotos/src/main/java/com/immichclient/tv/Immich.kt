package com.immichclient.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Immich (alleen lezen). Serveradres en API-sleutel staan in de prefs: gezet via het instelscherm (Koppel.kt, met de
 * telefoon) of via adb met `am broadcast -a com.immichclient.tv.SET_KEY --es sleutel … [--es server …]` (zie SleutelOntvanger).
 * Een eigen launcher kan een profiel meegeven (extra "kijker", bij het zetten én bij het starten van de app): dan geldt
 * per profiel een eigen account.
 */
object Immich {
    private const val PREFS = "immich"

    @Volatile var sleutel = ""
        private set
    /** Basisadres zonder /api, bijv. http://192.168.1.10:2283 of https://photos.example.com */
    @Volatile var server = ""
        private set
    /** Profiel waarvan het account nu gebruikt wordt (null = het algemene account). */
    @Volatile var kijker: String? = null
        private set
    val api get() = "$server/api"
    /** Verandert bij koppelen/ontkoppelen zodat Compose het merkt (sleutel/server zelf zijn gewone velden). */
    var stand by mutableIntStateOf(0)
        private set

    val http: OkHttpClient = OkHttpClient.Builder()
        // de sleutel alleen naar de eigen server (niet naar bijv. tiles.immich.cloud)
        .addInterceptor { chain ->
            val req = chain.request()
            chain.proceed(if (req.url.toString().startsWith(server)) req.newBuilder().header("x-api-key", sleutel).build() else req)
        }
        .dispatcher(Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 24 })
        .connectionPool(ConnectionPool(24, 5, TimeUnit.MINUTES))
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // slim zoeken kan na een tijdje stilte even duren (ML-model laadt)
        .build()

    /** Kies het account voor dit profiel (valt terug op het algemene account). */
    fun kiesAccount(c: Context, kijker: String?) {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = kijker?.takeIf { it.isNotBlank() && p.contains("sleutel_$it") }
        this.kijker = k
        sleutel = p.getString(if (k != null) "sleutel_$k" else "sleutel", "") ?: ""
        server = p.getString(if (k != null) "server_$k" else "server", "") ?: ""
        ik = null
        Instellingen.laad(c, k)
        stand++
    }

    fun zetSleutel(c: Context, nieuw: String, kijker: String?, serverAdres: String? = null) {
        val na = if (kijker.isNullOrBlank()) "" else "_$kijker"
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putString("sleutel$na", nieuw.trim())
            serverAdres?.let { putString("server$na", Koppel.normaliseer(it)) }
        }.apply()
    }

    /** Sleutel (en adres) van dit account wissen; de sleutel zelf blijft in Immich staan tot je hem daar verwijdert. */
    fun ontkoppel(c: Context, kijker: String?) {
        val na = if (kijker.isNullOrBlank()) "" else "_$kijker"
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("sleutel$na").remove("server$na").apply()
        kiesAccount(c, kijker)
    }

    fun thumb(id: String) = "$api/assets/$id/thumbnail?size=thumbnail"
    fun preview(id: String) = "$api/assets/$id/thumbnail?size=preview"
    fun video(id: String) = "$api/assets/$id/video/playback"
    fun gezicht(id: String) = "$api/people/$id/thumbnail"

    private suspend fun haal(req: Request): String = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("Immich ${r.code}: ${req.url.encodedPath} ${body.take(200)}")
            body
        }
    }

    private suspend fun get(pad: String) = haal(Request.Builder().url(api + pad).build())
    private suspend fun post(pad: String, json: JSONObject) =
        haal(Request.Builder().url(api + pad).post(json.toString().toRequestBody("application/json".toMediaType())).build())

    /** Ingelogde gebruiker (naam, e-mail) voor Instellingen en de begroeting; één keer opgehaald per account. */
    var ik by mutableStateOf<Gebruiker?>(null)
        private set
    suspend fun laadIk(): Gebruiker? = ik ?: runCatching { JSONObject(get("/users/me")).let { Gebruiker(it.optString("name"), it.optString("email")) } }
        .onFailure { Log.w("Fotos", "users/me", it) }.getOrNull()?.also { ik = it }

    /** Versie van de Immich-server ("3.3.3"), voor "Over". */
    suspend fun serverVersie(): String? = runCatching {
        JSONObject(get("/server/version")).let { "${it.optInt("major")}.${it.optInt("minor")}.${it.optInt("patch")}" }
    }.getOrNull()

    // ---- Tijdlijn (ook voor albums, personen en favorieten: zelfde endpoint met een filter) ----

    suspend fun maanden(filter: Filter, nieuwsteEerst: Boolean): List<Maand> {
        val a = JSONArray(get("/timeline/buckets?" + filter.query(nieuwsteEerst)))
        return List(a.length()) { i -> a.getJSONObject(i).let { Maand(it.getString("timeBucket"), it.getInt("count")) } }
    }

    suspend fun maand(filter: Filter, nieuwsteEerst: Boolean, maand: String): List<Asset> = withContext(Dispatchers.Default) {
        val o = JSONObject(get("/timeline/bucket?" + filter.query(nieuwsteEerst) + "&withCoordinates=true&timeBucket=$maand"))
        val ids = o.getJSONArray("id")
        val ratio = o.optJSONArray("ratio")
        val isImage = o.getJSONArray("isImage")
        val hash = o.optJSONArray("thumbhash")
        val datum = o.getJSONArray("fileCreatedAt")
        val offset = o.optJSONArray("localOffsetHours")
        val duur = o.optJSONArray("duration")
        val stad = o.optJSONArray("city")
        val land = o.optJSONArray("country")
        val fav = o.optJSONArray("isFavorite")
        val lat = o.optJSONArray("latitude")
        val lon = o.optJSONArray("longitude")
        fun coord(a: JSONArray?, i: Int) = a?.optDouble(i)?.takeIf { !it.isNaN() }
        List(ids.length()) { i ->
            Asset(
                id = ids.getString(i),
                ratio = ratio?.optDouble(i, 1.0)?.toFloat()?.takeIf { it > 0 && !it.isNaN() } ?: 1f,
                video = !isImage.getBoolean(i),
                thumbhash = hash?.optString(i)?.takeIf { it.isNotEmpty() && it != "null" },
                datum = lokaleTijd(datum.getString(i), offset?.optDouble(i, 0.0) ?: 0.0),
                duur = duur?.optString(i)?.takeIf { it.isNotEmpty() && it != "null" },
                stad = stad?.optString(i)?.takeIf { it.isNotEmpty() && it != "null" },
                land = land?.optString(i)?.takeIf { it.isNotEmpty() && it != "null" }?.let(Landnaam::vertaal),
                favoriet = fav?.optBoolean(i) ?: false,
                lat = coord(lat, i),
                lon = coord(lon, i),
            )
        }
    }

    // ---- Zoeken ----

    /** Slim zoeken (CLIP): "strand", "verjaardag met taart", "hond in de sneeuw". Volgorde = relevantie; taal = de app-taal. */
    suspend fun zoekSlim(tekst: String, pagina: Int, alleenVideo: Boolean?): Pagina {
        val body = JSONObject().put("query", tekst).put("language", Taal.code).put("size", 120).put("page", pagina)
        if (alleenVideo != null) body.put("type", if (alleenVideo) "VIDEO" else "IMAGE")
        return pagina(JSONObject(post("/search/smart", body)))
    }

    /** Het ML-model van Immich gaat na een paar minuten stilte uit het geheugen; dan duurt de eerste zoekopdracht ~9 s. */
    suspend fun warmZoeken() {
        runCatching { post("/search/smart", JSONObject().put("query", "photo").put("language", Taal.code).put("size", 1)) }
    }

    suspend fun zoekPlaats(stad: String, pagina: Int): Pagina =
        pagina(JSONObject(post("/search/metadata", JSONObject().put("city", stad).put("size", 250).put("page", pagina).put("order", "desc"))))

    /** Bestandsnaam / omschrijving / OCR (tekst op de foto). */
    suspend fun zoekTekst(tekst: String, veld: String, pagina: Int): Pagina =
        pagina(JSONObject(post("/search/metadata", JSONObject().put(veld, tekst).put("size", 250).put("page", pagina).put("order", "desc"))))

    private suspend fun pagina(o: JSONObject): Pagina = withContext(Dispatchers.Default) {
        val a = o.getJSONObject("assets")
        val items = a.getJSONArray("items")
        Pagina(List(items.length()) { asset(items.getJSONObject(it)) }, a.optString("nextPage").toIntOrNull())
    }

    suspend fun plaatsen(): List<Plaats> = withContext(Dispatchers.Default) {
        val a = JSONArray(get("/search/cities"))
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            val exif = o.optJSONObject("exifInfo")
            Plaats(exif?.optString("city").orEmpty(), Landnaam.vertaal(exif?.optString("country").orEmpty()), o.getString("id"))
        }.filter { it.stad.isNotBlank() }
    }

    suspend fun personen(): List<Persoon> = withContext(Dispatchers.Default) {
        val a = JSONObject(get("/people?size=1000&withHidden=false")).getJSONArray("people")
        List(a.length()) { i -> a.getJSONObject(i).let { Persoon(it.getString("id"), it.optString("name"), it.optBoolean("isFavorite")) } }
            .filter { it.naam.isNotBlank() }
    }

    // ---- Albums ----

    suspend fun albums(): List<Album> = withContext(Dispatchers.Default) {
        val a = JSONArray(get("/albums"))
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            Album(
                id = o.getString("id"),
                naam = o.optString("albumName"),
                omslag = o.optString("albumThumbnailAssetId").takeIf { it.isNotEmpty() && it != "null" },
                aantal = o.optInt("assetCount"),
                van = o.optString("startDate").take(10).let { runCatching { LocalDate.parse(it) }.getOrNull() },
                tot = o.optString("endDate").take(10).let { runCatching { LocalDate.parse(it) }.getOrNull() },
                bijgewerkt = o.optString("lastModifiedAssetTimestamp").ifEmpty { o.optString("updatedAt") },
                gedeeld = o.optBoolean("shared"),
                oudsteEerst = o.optString("order") == "asc",
            )
        }
    }

    // ---- Herinneringen ("vandaag X jaar geleden") ----

    suspend fun herinneringen(dag: LocalDate): List<Herinnering> = withContext(Dispatchers.Default) {
        val a = JSONArray(get("/memories?for=$dag"))
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            val assets = o.getJSONArray("assets")
            Herinnering(
                id = o.getString("id"),
                jaar = o.optJSONObject("data")?.optInt("year") ?: 0,
                dag = dag,
                assets = List(assets.length()) { asset(assets.getJSONObject(it)) }.sortedBy { it.datum },
            )
        }.filter { it.assets.isNotEmpty() }.sortedByDescending { it.jaar }
    }

    /** Eén asset (voor de viewer vanaf de kaart: daar zijn alleen id's bekend). */
    suspend fun asset(id: String): Asset = withContext(Dispatchers.Default) { asset(JSONObject(get("/assets/$id"))) }

    // ---- Kaart ----

    /** Alle foto's met GPS (id, plek, plaats): één call (kunnen tienduizenden zijn), nieuwste eerst. */
    suspend fun markers(): Markers = withContext(Dispatchers.Default) {
        val a = JSONArray(get("/map/markers"))
        val n = a.length()
        val ids = Array(n) { "" }; val lat = DoubleArray(n); val lon = DoubleArray(n); val stad = arrayOfNulls<String>(n); val land = arrayOfNulls<String>(n)
        for (i in 0 until n) {
            val o = a.getJSONObject(i)
            ids[i] = o.getString("id"); lat[i] = o.getDouble("lat"); lon[i] = o.getDouble("lon")
            stad[i] = o.optString("city").takeIf { it.isNotEmpty() && it != "null" }
            land[i] = o.optString("country").takeIf { it.isNotEmpty() && it != "null" }?.let(Landnaam::vertaal)
        }
        Markers(ids, lat, lon, stad, land)
    }

    /** Kaartstijl van de Immich-server (licht of donker; satelliet = donkere stijl met Sentinel-2 eronder, zie Wereldkaart.kt). */
    suspend fun kaartStijl(licht: Boolean): String {
        val veld = if (licht) "mapLightStyleUrl" else "mapDarkStyleUrl"
        return runCatching { JSONObject(get("/server/config")).optString(veld).takeIf { it.startsWith("http") } }.getOrNull()
            ?: "https://tiles.immich.cloud/v1/style/${if (licht) "light" else "dark"}.json"
    }

    /** Details voor het infopaneel in de viewer. */
    suspend fun details(id: String): Details = withContext(Dispatchers.Default) {
        val o = JSONObject(get("/assets/$id"))
        val e = o.optJSONObject("exifInfo")
        fun s(k: String) = e?.optString(k)?.takeIf { it.isNotEmpty() && it != "null" }
        val mensen = o.optJSONArray("people")
        Details(
            bestand = o.optString("originalFileName"),
            camera = listOfNotNull(s("make"), s("model")).joinToString(" ").ifEmpty { null },
            lens = s("lensModel"),
            instellingen = listOfNotNull(
                e?.optDouble("focalLength")?.takeIf { !it.isNaN() && it > 0 }?.let { "${it.toInt()} mm" },
                e?.optDouble("fNumber")?.takeIf { !it.isNaN() && it > 0 }?.let { "ƒ/" + ("%.1f".format(it)).removeSuffix(",0").removeSuffix(".0") },
                s("exposureTime")?.let { "$it s" },
                e?.optInt("iso")?.takeIf { it > 0 }?.let { "ISO $it" },
            ).joinToString("  ·  ").ifEmpty { null },
            plaats = listOfNotNull(s("city"), s("state"), s("country")?.let(Landnaam::vertaal)).distinct().joinToString(", ").ifEmpty { null },
            mensen = if (mensen == null) emptyList() else
                List(mensen.length()) { mensen.getJSONObject(it).optString("name") }.filter { it.isNotBlank() },
            omschrijving = s("description"),
            afmeting = listOfNotNull(o.optInt("width").takeIf { it > 0 }, o.optInt("height").takeIf { it > 0 })
                .takeIf { it.size == 2 }?.let { (w, h) -> "$w × $h" },
        )
    }

    private fun asset(o: JSONObject): Asset {
        var w = o.optInt("width")
        var h = o.optInt("height")
        val e = o.optJSONObject("exifInfo")
        if (w <= 0 || h <= 0) {
            w = e?.optInt("exifImageWidth") ?: 0
            h = e?.optInt("exifImageHeight") ?: 0
            if (e?.optString("orientation") in setOf("5", "6", "7", "8")) w = h.also { h = w }
        }
        return Asset(
            id = o.getString("id"),
            ratio = if (w > 0 && h > 0) w.toFloat() / h else 1f,
            video = o.optString("type") == "VIDEO",
            thumbhash = o.optString("thumbhash").takeIf { it.isNotEmpty() && it != "null" },
            // localDateTime is de lokale tijd, ook al staat er een Z achter
            datum = lokaleTijd(o.optString("localDateTime").ifEmpty { o.optString("fileCreatedAt") }, 0.0),
            duur = o.optString("duration").takeIf { it.isNotEmpty() && it != "null" },
            stad = e?.optString("city")?.takeIf { it.isNotEmpty() && it != "null" },
            land = e?.optString("country")?.takeIf { it.isNotEmpty() && it != "null" }?.let(Landnaam::vertaal),
            favoriet = o.optBoolean("isFavorite"),
            lat = e?.optDouble("latitude")?.takeIf { !it.isNaN() },
            lon = e?.optDouble("longitude")?.takeIf { !it.isNaN() },
        )
    }
}

/**
 * Zet de sleutel via adb (alleen de shell mag dit, zie de DUMP-permissie in het manifest):
 * `am broadcast -a com.immichclient.tv.SET_KEY -n com.immichclient.tv/.SleutelOntvanger --es sleutel … --es server http://…:2283 [--es taal nl]`.
 * Zonder `server` blijft het opgeslagen adres staan; `taal` (en/nl/de/fr/es) slaat het taalkeuzescherm van de eerste start over.
 */
class SleutelOntvanger : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val s = i.getStringExtra("sleutel") ?: return
        i.getStringExtra("taal")?.let { Taal.kies(c, it) }
        Immich.zetSleutel(c, s, i.getStringExtra("kijker"), i.getStringExtra("server"))
        Immich.kiesAccount(c, null)
        Log.i("Fotos", "sleutel gezet" + (i.getStringExtra("kijker")?.let { " voor $it" } ?: "") + " voor ${Immich.server}")
    }
}
