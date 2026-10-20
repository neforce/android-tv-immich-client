package com.immichclient.tv

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Vertaalbare melding: string-resource + argumenten, in te vullen in de taal van de tv of van de telefoon. */
class Tekst(@StringRes val id: Int, vararg val args: Any) {
    fun bij(res: Resources): String = res.getString(id, *args)
    /** In de taal van de app (voor het scherm op de tv). */
    override fun toString() = bij(Taal.ctx.resources)
}

/**
 * Koppelen met een Immich-server via de telefoon. Op de tv staat een QR-code met http://<ip-van-de-box>:<poort>/<code>;
 * zolang het instelscherm open is, draait hier een piepklein HTTP-servertje (eigen ServerSocket) dat één mobiele pagina
 * serveert, in de taal van de telefoon (Accept-Language) als die ondersteund wordt, anders in de taal van de app.
 * De telefoon stuurt serveradres + e-mail + wachtwoord (of een bestaande sleutel) terug; het koppelen zelf (ping, versie,
 * login, API-sleutel aanmaken, logout) doet de app. Het wachtwoord wordt nergens bewaard of gelogd. Zonder de code uit
 * de QR geeft het servertje 403; het stopt zodra het koppelen gelukt is of het scherm sluit.
 */
object Koppel {
    private const val TAG = "Fotos"
    private val POORTEN = 8791..8799
    /** Precies de rechten die de app nodig heeft (alleen lezen); zie x-immich-permission in de OpenAPI-spec. */
    val RECHTEN = listOf("asset.read", "asset.view", "album.read", "memory.read", "person.read", "map.read", "user.read")

    sealed class Stand {
        data object Wacht : Stand()
        class Bezig(val stap: Tekst) : Stand()
        class Klaar(val naam: String, val server: String, val waarschuwing: Tekst?) : Stand()
        class Fout(val melding: Tekst) : Stand()
    }

    var stand by mutableStateOf<Stand>(Stand.Wacht)
        private set
    /** Adres voor de QR-code, null zolang er geen netwerk is of de poort niet lukt. */
    var url by mutableStateOf<String?>(null)
        private set

    private val main = Handler(Looper.getMainLooper())
    private var server: ServerSocket? = null
    private var code = ""
    private var test = false
    private var app: Context? = null

    /** Losse client zonder sleutel-interceptor: korte timeouts, want elke stap wordt aan de telefoon gemeld. */
    private val kaal = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    // ---- servertje ----

    fun start(c: Context, test: Boolean) {
        stop()
        app = c.applicationContext
        this.test = test
        stand = Stand.Wacht
        url = null
        code = nieuweCode()
        val ip = eigenIp()
        val ss = POORTEN.firstNotNullOfOrNull { p -> runCatching { ServerSocket(p) }.getOrNull() }
        if (ip == null || ss == null) {
            Log.w(TAG, "koppelen: geen netwerk ($ip) of geen vrije poort")
            url = null
            stand = Stand.Fout(Tekst(if (ip == null) R.string.fout_geen_netwerk else R.string.fout_geen_poort))
            return
        }
        server = ss
        url = "http://$ip:${ss.localPort}/$code"
        Log.i(TAG, "koppelen: wacht op $url")
        thread(name = "Koppel", isDaemon = true) {
            while (true) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break // gesloten door stop()
                runCatching { behandel(s) }.onFailure { e -> Log.w(TAG, "koppelen: verzoek", e) }
                runCatching { s.close() }
            }
            Log.i(TAG, "koppelen: servertje gestopt")
        }
    }

    /** Sluit het servertje; [url] blijft staan (het scherm toont na "Klaar" geen QR meer, maar wel het adres). */
    fun stop() {
        server?.let { runCatching { it.close() } }
        server = null
    }

    private fun behandel(s: Socket) {
        s.soTimeout = 10_000
        // alleen het eigen netwerk (de code beschermt al; dit is een extra slot)
        val adres = s.inetAddress
        if (!(adres.isSiteLocalAddress || adres.isLinkLocalAddress || adres.isLoopbackAddress)) { antwoord(s.getOutputStream(), 403, "text/plain", Taal.s(R.string.geen_toegang)); return }
        val invoer = s.getInputStream().buffered()
        val uit = s.getOutputStream()
        val eerste = leesRegel(invoer) ?: return
        val delen = eerste.split(' ')
        if (delen.size < 2) return
        val methode = delen[0]
        val pad = delen[1].substringBefore('?').trimEnd('/')
        var lengte = 0
        var acceptLanguage: String? = null
        while (true) {
            val regel = leesRegel(invoer) ?: break
            if (regel.isEmpty()) break
            if (regel.startsWith("Content-Length:", ignoreCase = true)) lengte = regel.substringAfter(':').trim().toIntOrNull() ?: 0
            if (regel.startsWith("Accept-Language:", ignoreCase = true)) acceptLanguage = regel.substringAfter(':').trim()
        }
        val body = ByteArray(lengte.coerceAtMost(64 * 1024))
        var gelezen = 0
        while (gelezen < body.size) { val n = invoer.read(body, gelezen, body.size - gelezen); if (n < 0) break; gelezen += n }
        // taal van de telefoon (als die ondersteund wordt), anders die van de app
        val taal = Taal.uitAcceptLanguage(acceptLanguage)
        val res = Taal.resources(taal)

        when {
            methode == "GET" && pad == "/$code" -> antwoord(uit, 200, "text/html; charset=utf-8", pagina(taal, res))
            methode == "POST" && pad == "/$code/koppel" -> {
                val json = runCatching { JSONObject(String(body, 0, gelezen, Charsets.UTF_8)) }.getOrElse { JSONObject() }
                val r = koppel(json, res)
                antwoord(uit, 200, "application/json; charset=utf-8", r.toString())
                if (r.optBoolean("ok")) stop() // klaar: niets meer aannemen
            }
            else -> antwoord(uit, 403, "text/html; charset=utf-8", "<!doctype html><meta charset=utf-8><body style=\"background:#0a0a0a;color:#a1a1aa;font-family:sans-serif;padding:40px 20px;text-align:center\">${res.getString(R.string.geen_toegang_html)}</body>")
        }
    }

    private fun antwoord(uit: OutputStream, code: Int, type: String, inhoud: String) {
        val bytes = inhoud.toByteArray(Charsets.UTF_8)
        val tekst = when (code) { 200 -> "OK"; 403 -> "Forbidden"; else -> "Error" }
        uit.write("HTTP/1.1 $code $tekst\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        uit.write(bytes)
        uit.flush()
    }

    private fun leesRegel(invoer: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = invoer.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun nieuweCode(): String {
        val tekens = "abcdefghjkmnpqrstuvwxyz23456789" // geen l/1/o/0: leesbaar als je hem overtypt
        val r = SecureRandom()
        return String(CharArray(6) { tekens[r.nextInt(tekens.length)] })
    }

    /** IPv4-adres van de actieve interface (wifi of ethernet), geen loopback. */
    fun eigenIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { n -> when { n.name.startsWith("eth") -> 0; n.name.startsWith("wlan") -> 1; else -> 2 } }
            .firstNotNullOfOrNull { n -> n.inetAddresses.toList().firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress }
    }.getOrNull()

    // ---- het koppelen zelf ----

    private class KoppelFout(val tekst: Tekst) : Exception(tekst.toString())

    /** "192.168.1.10:2283/" → "http://192.168.1.10:2283"; "https://photos.example.com/api" → "https://photos.example.com" */
    fun normaliseer(adres: String): String {
        var a = adres.trim().trimEnd('/')
        if (!a.startsWith("http://") && !a.startsWith("https://")) a = "http://$a"
        if (a.endsWith("/api")) a = a.removeSuffix("/api")
        return a.trimEnd('/')
    }

    private fun zetStand(s: Stand) = main.post { stand = s }

    /** Verwerkt het formulier van de telefoon; geeft {ok, titel, melding} terug in de taal [res] van de telefoon. Draait op de serverthread. */
    private fun koppel(json: JSONObject, res: Resources): JSONObject {
        val c = app ?: return JSONObject().put("ok", false).put("titel", res.getString(R.string.mislukt)).put("melding", res.getString(R.string.fout_app_inactief))
        val serverIn = json.optString("server").trim()
        val sleutelIn = json.optString("sleutel").trim()
        val email = json.optString("email").trim()
        val ww = json.optString("wachtwoord")
        if (serverIn.isEmpty()) return fout(Tekst(R.string.fout_vul_adres), res)
        val basis = normaliseer(serverIn)
        return try {
            val waarschuwing = controleerServer(basis)
            val (sleutel, naam) = if (sleutelIn.isNotEmpty()) {
                zetStand(Stand.Bezig(Tekst(R.string.bezig_sleutel_rechten)))
                sleutelIn to controleerRechten(basis, sleutelIn, res)
            } else {
                if (email.isEmpty() || ww.isEmpty()) throw KoppelFout(Tekst(R.string.fout_vul_email))
                maakSleutel(basis, email, ww)
            }
            Immich.zetSleutel(c, sleutel, null, basis)
            main.post { Immich.kiesAccount(c, null) }
            Log.i(TAG, "koppelen: gelukt met $basis" + if (test) " (test)" else "")
            zetStand(Stand.Klaar(naam, basis, waarschuwing))
            JSONObject().put("ok", true).put("titel", res.getString(R.string.klaar_titel))
                .put("melding", res.getString(R.string.gekoppeld_melding, naam, basis) + (waarschuwing?.let { "\n\n${it.bij(res)}" } ?: ""))
        } catch (e: KoppelFout) {
            Log.i(TAG, "koppelen: ${e.message}")
            fout(e.tekst, res)
        } catch (e: Exception) {
            Log.w(TAG, "koppelen", e)
            fout(Tekst(R.string.er_ging_iets_mis, "${e.javaClass.simpleName} ${e.message.orEmpty()}".trim()), res)
        }
    }

    private fun fout(melding: Tekst, res: Resources): JSONObject {
        zetStand(Stand.Fout(melding))
        return JSONObject().put("ok", false).put("titel", res.getString(R.string.koppel_mislukt)).put("melding", melding.bij(res))
    }

    private fun verzoek(url: String, bearer: String? = null, sleutel: String? = null, body: JSONObject? = null): Request =
        Request.Builder().url(url).apply {
            header("Accept", "application/json")
            bearer?.let { header("Authorization", "Bearer $it") }
            sleutel?.let { header("x-api-key", it) }
            if (body != null) post(body.toString().toRequestBody("application/json".toMediaType()))
        }.build()

    private class Antwoord(val code: Int, val body: String)

    private fun roep(req: Request): Antwoord = try {
        kaal.newCall(req).execute().use { Antwoord(it.code, it.body?.string().orEmpty()) }
    } catch (e: javax.net.ssl.SSLException) {
        throw KoppelFout(Tekst(R.string.fout_ssl, req.url.host))
    } catch (e: java.net.UnknownHostException) {
        throw KoppelFout(Tekst(R.string.fout_host, req.url.host))
    } catch (e: java.io.IOException) {
        throw KoppelFout(Tekst(R.string.fout_verbinding, req.url.host, req.url.port))
    }

    /** Ping + versie. Geeft een waarschuwing terug (of null) als de server ouder is dan waarmee de app getest is. */
    private fun controleerServer(basis: String): Tekst? {
        zetStand(Stand.Bezig(Tekst(R.string.bezig_server, basis)))
        val ping = roep(verzoek("$basis/api/server/ping"))
        val pong = runCatching { JSONObject(ping.body).optString("res") }.getOrNull()
        if (ping.code != 200 || pong != "pong") throw KoppelFout(
            if (ping.code == 200) Tekst(R.string.fout_geen_pong, basis) else Tekst(R.string.fout_geen_immich, basis, ping.code)
        )
        val v = runCatching { JSONObject(roep(verzoek("$basis/api/server/version")).body) }.getOrNull()
        val major = v?.optInt("major", -1) ?: -1
        val versie = v?.let { "${it.optInt("major")}.${it.optInt("minor")}.${it.optInt("patch")}" } ?: "?"
        Log.i(TAG, "koppelen: Immich $versie op $basis")
        // De app gebruikt de kolom-tijdlijn (/timeline/bucket) en /memories zoals in Immich 2 en 3; getest met 3.3.
        if (major in 0..1) throw KoppelFout(Tekst(R.string.fout_te_oud, versie))
        return if (major == 2) Tekst(R.string.waarschuwing_versie, versie) else null
    }

    /** Inloggen, sleutel met precies de leesrechten aanmaken, uitloggen. Geeft (sleutel, naam). */
    private fun maakSleutel(basis: String, email: String, ww: String): Pair<String, String> {
        zetStand(Stand.Bezig(Tekst(R.string.bezig_inloggen, email)))
        val login = roep(verzoek("$basis/api/auth/login", body = JSONObject().put("email", email).put("password", ww)))
        if (login.code == 401) throw KoppelFout(Tekst(R.string.fout_login))
        if (login.code == 400) throw KoppelFout(Tekst(R.string.fout_login_adres))
        if (login.code !in 200..299) throw KoppelFout(Tekst(R.string.fout_login_http, login.code))
        val lo = JSONObject(login.body)
        val token = lo.optString("accessToken")
        if (token.isEmpty()) throw KoppelFout(Tekst(R.string.fout_geen_token))
        val naam = lo.optString("name").ifBlank { email }
        try {
            zetStand(Stand.Bezig(Tekst(R.string.bezig_sleutel_aanmaken)))
            // naam van de sleutel in Immich, in de taal van de app
            val sleutelNaam = Taal.s(R.string.sleutel_naam, apparaatNaam()) + if (test) " (test)" else ""
            val body = JSONObject().put("name", sleutelNaam).put("permissions", JSONArray(RECHTEN))
            val aanmaak = roep(verzoek("$basis/api/api-keys", bearer = token, body = body))
            if (aanmaak.code == 403) throw KoppelFout(Tekst(R.string.fout_geen_sleutels))
            if (aanmaak.code !in 200..299) throw KoppelFout(Tekst(R.string.fout_sleutel_http, aanmaak.code, aanmaak.body.take(120)))
            val sleutel = JSONObject(aanmaak.body).optString("secret")
            if (sleutel.isEmpty()) throw KoppelFout(Tekst(R.string.fout_geen_sleutel_terug))
            Log.i(TAG, "koppelen: sleutel '$sleutelNaam' aangemaakt")
            // meteen controleren of de sleutel werkt
            zetStand(Stand.Bezig(Tekst(R.string.bezig_sleutel_controleren)))
            val ik = roep(verzoek("$basis/api/users/me", sleutel = sleutel))
            if (ik.code != 200) throw KoppelFout(Tekst(R.string.fout_sleutel_werkt_niet, ik.code))
            return sleutel to naam
        } finally {
            // sessie van de login altijd weer afsluiten; de app werkt verder alleen met de sleutel
            runCatching { roep(verzoek("$basis/api/auth/logout", bearer = token, body = JSONObject())) }
        }
    }

    /** Bestaande sleutel: elk recht testen door het bijbehorende endpoint aan te roepen. Geeft de gebruikersnaam. */
    private fun controleerRechten(basis: String, sleutel: String, res: Resources): String {
        val mist = mutableListOf<String>()
        var naam = ""
        fun probeer(recht: String, pad: String): JSONObject? {
            val a = roep(verzoek("$basis/api$pad", sleutel = sleutel))
            when (a.code) {
                401 -> throw KoppelFout(Tekst(R.string.fout_sleutel_geweigerd, basis))
                403 -> { mist += recht; return null }
                in 200..299 -> return if (a.body.startsWith("{")) JSONObject(a.body) else JSONObject().put("lijst", a.body)
                else -> throw KoppelFout(Tekst(R.string.fout_onverwacht, a.code, pad))
            }
        }
        probeer("user.read", "/users/me")?.let { naam = it.optString("name") }
        val buckets = probeer("asset.read", "/timeline/buckets?visibility=timeline&order=desc")
        probeer("album.read", "/albums")
        probeer("memory.read", "/memories?for=${LocalDate.now()}")
        probeer("person.read", "/people?size=1")
        probeer("map.read", "/map/markers?fileCreatedAfter=2999-01-01T00:00:00.000Z") // leeg antwoord, maar wel de rechtencheck
        // asset.view: een echte thumbnail opvragen; zonder foto's op de server niet te testen
        val eersteMaand = runCatching { JSONArray(buckets?.optString("lijst").orEmpty()).getJSONObject(0).getString("timeBucket") }.getOrNull()
        if (eersteMaand != null) {
            val bucket = probeer("asset.read", "/timeline/bucket?visibility=timeline&order=desc&timeBucket=$eersteMaand")
            val id = bucket?.optJSONArray("id")?.optString(0)
            if (!id.isNullOrEmpty()) probeer("asset.view", "/assets/$id/thumbnail?size=thumbnail")
        }
        if (mist.isNotEmpty()) throw KoppelFout(Tekst(R.string.fout_rechten, mist.distinct().joinToString(", "), RECHTEN.joinToString(", ")))
        return naam.ifBlank { res.getString(R.string.onbekende_gebruiker) }
    }

    private fun apparaatNaam(): String {
        val c = app
        val naam = c?.let { runCatching { Settings.Global.getString(it.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull() }
        return naam?.takeIf { it.isNotBlank() } ?: Build.MODEL
    }

    // ---- QR ----

    /** QR-code als bitmap (zwart op wit), een module per pixel; de weergave schaalt zonder vervagen. */
    fun qr(tekst: String): Bitmap {
        val m = QRCodeWriter().encode(tekst, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1))
        val b = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
        val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        b.setPixels(px, 0, m.width, 0, 0, m.width, m.height)
        return b
    }

    // ---- de telefoonpagina ----

    private fun html(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** De pagina in taal [taal]: alle teksten uit de string-resources; de JavaScript-teksten gaan als JSON mee. */
    private fun pagina(taal: String, res: Resources): String {
        fun t(id: Int) = html(res.getString(id))
        val js = JSONObject()
            .put("bezigTitel", res.getString(R.string.html_bezig_titel)).put("bezig", res.getString(R.string.html_bezig))
            .put("geenVerbindingTitel", res.getString(R.string.html_geen_verbinding_titel)).put("geenVerbinding", res.getString(R.string.html_geen_verbinding))
        return PAGINA
            .replace("%TAAL%", taal)
            .replace("%TITEL%", t(R.string.html_titel)).replace("%KOP%", t(R.string.html_kop)).replace("%SUB%", t(R.string.html_sub))
            .replace("%ADRES%", t(R.string.html_adres)).replace("%ADRES_VB%", t(R.string.html_adres_voorbeeld)).replace("%ADRES_VB_KORT%", t(R.string.html_adres_voorbeeld_kort))
            .replace("%ADRES_HINT%", t(R.string.html_adres_hint)).replace("%EMAIL%", t(R.string.html_email)).replace("%WW%", t(R.string.html_wachtwoord))
            .replace("%WW_HINT%", t(R.string.html_wachtwoord_hint)).replace("%KOPPELEN%", t(R.string.html_koppelen))
            .replace("%SLEUTEL_KOP%", t(R.string.html_sleutel_kop)).replace("%SLEUTEL%", t(R.string.html_sleutel))
            .replace("%SLEUTEL_HINT_VOOR%", t(R.string.html_sleutel_hint_voor)).replace("%SLEUTEL_PAD%", t(R.string.html_sleutel_pad)).replace("%SLEUTEL_HINT_NA%", t(R.string.html_sleutel_hint_na))
            .replace("%KOPPELEN_SLEUTEL%", t(R.string.html_koppelen_sleutel)).replace("%VOET%", t(R.string.html_voet))
            .replace("%RECHTEN%", RECHTEN.joinToString("") { "<li>$it</li>" })
            .replace("%JS%", js.toString())
    }

    // Geen $ in de JavaScript/CSS hieronder (Kotlin-string).
    private val PAGINA = """<!doctype html>
<html lang="%TAAL%"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="robots" content="noindex"><title>%TITEL%</title>
<style>
:root{color-scheme:dark}
*{box-sizing:border-box}
body{margin:0;background:#0a0a0a;color:#e5e7eb;font:16px/1.45 -apple-system,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;padding:24px 16px calc(32px + env(safe-area-inset-bottom));max-width:520px;margin:0 auto}
.kop{display:flex;align-items:center;gap:14px;margin:6px 0 22px}
.kop svg{width:44px;height:44px;flex:none}
h1{font-size:22px;margin:0;font-weight:600;line-height:1.2}
.kop p{margin:3px 0 0;color:#a1a1aa;font-size:14px}
.kaart{background:#1b1b1d;border-radius:16px;padding:20px 18px;margin-bottom:14px}
label{display:block;font-size:13px;color:#a1a1aa;margin:16px 0 6px}
form > label:first-child{margin-top:0}
input{width:100%;font:inherit;color:#fff;background:#262629;border:1px solid #3a3a3f;border-radius:10px;padding:12px 14px;outline:none;-webkit-appearance:none}
input:focus{border-color:#adcbfa;box-shadow:0 0 0 3px rgba(173,203,250,.2)}
.hint{font-size:12.5px;color:#71717a;margin:6px 0 0;line-height:1.4}
button{width:100%;margin-top:20px;font:inherit;font-weight:600;color:#0b1a3a;background:#adcbfa;border:0;border-radius:10px;padding:14px;cursor:pointer}
button:disabled{opacity:.55}
details summary{cursor:pointer;color:#adcbfa;font-size:15px;list-style:none;padding:2px 0}
summary::-webkit-details-marker{display:none}
summary::before{content:"▸  "}
details[open] summary::before{content:"▾  "}
details form{margin-top:14px}
.rechten{font-size:13px;margin:8px 0 0;padding:0;list-style:none;display:grid;grid-template-columns:1fr 1fr;gap:4px 10px;color:#d4d4d8}
.rechten li::before{content:"✓ ";color:#adcbfa}
code{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:13px}
#uit{display:none;border-radius:16px;padding:18px;margin:0 0 14px;font-size:15px;white-space:pre-line}
#uit.goed{display:block;background:#143420;color:#bbf7d0}
#uit.fout{display:block;background:#3b1414;color:#fecaca}
#uit.bezig{display:block;background:#1b1b1d;color:#e5e7eb}
#uit b{display:block;font-size:17px;margin-bottom:4px}
.spin{display:inline-block;width:16px;height:16px;border:2px solid #adcbfa;border-top-color:transparent;border-radius:50%;animation:d .8s linear infinite;vertical-align:-3px;margin-right:8px}
@keyframes d{to{transform:rotate(360deg)}}
.voet{color:#52525b;font-size:12px;text-align:center;margin-top:22px;line-height:1.4}
</style></head><body>
<div class="kop">
<svg viewBox="0 0 48 48" fill="none"><rect x="4" y="8" width="40" height="32" rx="7" fill="#1b1b1d" stroke="#adcbfa" stroke-width="2.5"/><circle cx="17" cy="19" r="4" fill="#adcbfa"/><path d="M8 36l10-11 7 8 6-6 9 9" stroke="#adcbfa" stroke-width="2.5" stroke-linejoin="round" stroke-linecap="round"/></svg>
<div><h1>%KOP%</h1><p>%SUB%</p></div>
</div>
<div id="uit"></div>
<form id="f1" class="kaart" autocomplete="on">
<label for="server">%ADRES%</label>
<input id="server" name="server" inputmode="url" autocapitalize="none" autocorrect="off" spellcheck="false" placeholder="%ADRES_VB%" required>
<p class="hint">%ADRES_HINT%</p>
<label for="email">%EMAIL%</label>
<input id="email" name="email" type="email" autocomplete="username" autocapitalize="none" required>
<label for="ww">%WW%</label>
<input id="ww" name="ww" type="password" autocomplete="current-password" required>
<p class="hint">%WW_HINT%</p>
<button type="submit">%KOPPELEN%</button>
</form>
<div class="kaart"><details><summary>%SLEUTEL_KOP%</summary>
<form id="f2">
<label for="server2">%ADRES%</label>
<input id="server2" inputmode="url" autocapitalize="none" autocorrect="off" spellcheck="false" placeholder="%ADRES_VB_KORT%" required>
<label for="sl">%SLEUTEL%</label>
<input id="sl" autocapitalize="none" autocorrect="off" spellcheck="false" required>
<p class="hint">%SLEUTEL_HINT_VOOR% <b>%SLEUTEL_PAD%</b> %SLEUTEL_HINT_NA%</p>
<ul class="rechten">%RECHTEN%</ul>
<button type="submit">%KOPPELEN_SLEUTEL%</button>
</form></details></div>
<p class="voet">%VOET%</p>
<script>
var T=%JS%;
var uit=document.getElementById('uit');
function toon(k,titel,tekst){uit.className=k;uit.innerHTML='';var b=document.createElement('b');if(k==='bezig'){var s=document.createElement('span');s.className='spin';b.appendChild(s);}b.appendChild(document.createTextNode(titel));uit.appendChild(b);uit.appendChild(document.createTextNode(tekst||''));if(k!=='bezig')uit.scrollIntoView({behavior:'smooth',block:'start'});}
function stuur(body,knoppen){
  for(var i=0;i<knoppen.length;i++)knoppen[i].disabled=true;
  toon('bezig',T.bezigTitel,T.bezig);
  fetch(location.pathname.replace(/\/+${'$'}/,'')+'/koppel',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)})
  .then(function(r){return r.json();})
  .then(function(j){toon(j.ok?'goed':'fout',j.titel,j.melding);if(j.ok){document.getElementById('f1').style.display='none';document.getElementById('f2').style.display='none';}})
  .catch(function(){toon('fout',T.geenVerbindingTitel,T.geenVerbinding);})
  .then(function(){for(var i=0;i<knoppen.length;i++)knoppen[i].disabled=false;});
}
var kn=document.querySelectorAll('button');
document.getElementById('f1').addEventListener('submit',function(e){e.preventDefault();stuur({server:document.getElementById('server').value,email:document.getElementById('email').value,wachtwoord:document.getElementById('ww').value},kn);});
document.getElementById('f2').addEventListener('submit',function(e){e.preventDefault();stuur({server:document.getElementById('server2').value,sleutel:document.getElementById('sl').value},kn);});
</script>
</body></html>
"""
}
