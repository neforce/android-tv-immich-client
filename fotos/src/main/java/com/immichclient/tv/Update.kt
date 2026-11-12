package com.immichclient.tv

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Updates: (1) na het bijwerken één keer "Wat is er nieuw" (de punten staan per taal in `wat_nieuw`), (2) bij het
 * starten kijken of er op GitHub een nieuwere release staat, en (3) die vanuit de app installeren: APK downloaden,
 * sha256 controleren tegen de digest die GitHub bij het bestand geeft, en via de PackageInstaller installeren (Android
 * vraagt dan zelf om bevestiging; de eerste keer moet "onbekende bronnen" voor deze app aan).
 * Er gaat niets naar GitHub behalve een gewone, anonieme API-aanvraag (geen sleutel, geen apparaatgegevens).
 */
object Update {
    private const val REPO = "neforce/android-tv-immich-client"
    private const val PREFS = "update"
    private const val TAG = "Fotos"

    class Release(val versie: String, val notities: String, val apk: String, val sha256: String?, val grootte: Long)

    sealed interface Stand {
        data object Rust : Stand
        data class Downloaden(val deel: Float) : Stand
        data object Installeren : Stand
        /** "onbekende bronnen" staat nog uit voor deze app */
        data object Toestemming : Stand
        data class Fout(val tekst: String) : Stand
    }

    /** nieuwere release op GitHub (null = geen, of nog niet gecontroleerd) */
    var nieuw by mutableStateOf<Release?>(null)
        private set
    var gecontroleerd by mutableStateOf(false)
        private set
    var stand by mutableStateOf<Stand>(Stand.Rust)
    /** venster met de nieuwe versie open */
    var open by mutableStateOf(false)
    /** "Wat is er nieuw" open */
    var nieuwsOpen by mutableStateOf(false)
    /** test: doen alsof deze versie geïnstalleerd is (`--es update_test 2.0`) */
    var testVersie: String? = null

    private val http by lazy { OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build() }

    fun versie(c: Context): String = testVersie ?: runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "0"

    /** "2.10" > "2.9"; ontbrekende delen tellen als 0, tekst achter een getal ("2.8-beta") telt niet mee */
    fun nieuwer(a: String, b: String): Boolean {
        fun delen(s: String) = s.trimStart('v', 'V').split('.', '-').map { d -> d.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        val x = delen(a); val y = delen(b)
        for (i in 0 until maxOf(x.size, y.size)) { val p = x.getOrElse(i) { 0 }; val q = y.getOrElse(i) { 0 }; if (p != q) return p > q }
        return false
    }

    /** Bij het starten: na een update "Wat is er nieuw" tonen (niet na een verse installatie). */
    fun bijStart(c: Context) {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val nu = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: return // nooit de testversie
        val eerder = p.getString("gezien", null)
        if (eerder == nu) return
        p.edit().putString("gezien", nu).apply()
        val info = runCatching { c.packageManager.getPackageInfo(c.packageName, 0) }.getOrNull()
        // eerder == null: of een verse installatie, of een update vanaf een versie zonder deze functie (≤ 2.7.2)
        val bijgewerkt = if (eerder == null) info != null && info.lastUpdateTime > info.firstInstallTime + 1000 else nieuwer(nu, eerder)
        if (bijgewerkt) nieuwsOpen = true
    }

    /** Nieuwste release op GitHub ophalen; een nieuwe versie één keer vanzelf melden (daarna alleen in Instellingen). */
    suspend fun controleer(c: Context, melden: Boolean) {
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url("https://api.github.com/repos/$REPO/releases/latest")
                    .header("Accept", "application/vnd.github+json").header("User-Agent", "android-tv-immich-client").build()
                http.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) error("GitHub ${res.code}")
                    val o = JSONObject(res.body!!.string())
                    val assets = o.getJSONArray("assets")
                    val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk") } ?: return@use null
                    Release(
                        o.getString("tag_name").trimStart('v', 'V'), o.optString("body"), apk.getString("browser_download_url"),
                        apk.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"), apk.optLong("size"),
                    )
                }
            }.onFailure { Log.w(TAG, "update: controle mislukt: ${it.message}") }.getOrNull()
        }
        gecontroleerd = true
        nieuw = r?.takeIf { nieuwer(it.versie, versie(c)) }
        Log.i(TAG, "update: geïnstalleerd ${versie(c)}, nieuwste ${r?.versie}, nieuwer=${nieuw != null}")
        val n = nieuw ?: return
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (melden && (testVersie != null || p.getString("gemeld", null) != n.versie) && !nieuwsOpen) { p.edit().putString("gemeld", n.versie).apply(); open = true }
    }

    /** Downloaden, controleren en de installatie starten. */
    suspend fun installeer(c: Context) {
        val r = nieuw ?: return
        if (Build.VERSION.SDK_INT >= 26 && !c.packageManager.canRequestPackageInstalls()) { stand = Stand.Toestemming; return }
        stand = Stand.Downloaden(0f)
        val bestand = File(c.cacheDir, "update.apk")
        val fout = withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(Request.Builder().url(r.apk).header("User-Agent", "android-tv-immich-client").build()).execute().use { res ->
                    if (!res.isSuccessful) error("download ${res.code}")
                    val body = res.body!!
                    val totaal = body.contentLength().takeIf { it > 0 } ?: r.grootte
                    val md = MessageDigest.getInstance("SHA-256")
                    var gelezen = 0L; var laatst = 0L
                    body.byteStream().use { inp -> bestand.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            out.write(buf, 0, n); md.update(buf, 0, n); gelezen += n
                            if (totaal > 0 && gelezen - laatst > 256 * 1024) { laatst = gelezen; stand = Stand.Downloaden(gelezen.toFloat() / totaal) }
                        }
                    } }
                    val hash = md.digest().joinToString("") { "%02x".format(it) }
                    if (r.sha256 != null && !hash.equals(r.sha256, ignoreCase = true)) error("sha256 klopt niet")
                    Log.i(TAG, "update: ${r.versie} gedownload ($gelezen bytes, sha256 $hash)")
                }
                stand = Stand.Installeren
                val pi = c.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(c.packageName) }
                val id = pi.createSession(params)
                pi.openSession(id).use { s ->
                    bestand.inputStream().use { inp -> s.openWrite("app.apk", 0, bestand.length()).use { out -> inp.copyTo(out); s.fsync(out) } }
                    val terug = Intent(c, UpdateOntvanger::class.java)
                    val vlag = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    s.commit(PendingIntent.getBroadcast(c, id, terug, vlag).intentSender)
                }
            }.exceptionOrNull()
        }
        if (fout != null) { Log.w(TAG, "update", fout); bestand.delete(); stand = Stand.Fout(fout.message ?: fout.javaClass.simpleName) }
    }

    /** Systeeminstelling "onbekende bronnen" voor deze app openen (valt terug op Beveiliging als de tv die niet kent). */
    fun openToestemming(c: Context) {
        val per = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { c.startActivity(per) }.isSuccess) return
        runCatching { c.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Releasetekst (Markdown) kort en leesbaar: (kop?, tekst); citaten, afbeeldingen en links eruit, hooguit [max] regels. */
    fun kort(md: String, max: Int = 10): List<Pair<Boolean, String>> = md.lines().map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("```") && !it.startsWith("![") && !it.startsWith("<") && !it.startsWith(">") }
        .map { r -> r.startsWith("#") to r.trimStart('#', '*', '-', ' ').replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1").replace("*", "").replace("`", "") }
        .filter { it.second.isNotBlank() && !it.second.replace("-", "").contains("sha256", ignoreCase = true) }
        .take(max)
}

/**
 * Telt de open lagen (keuzelijsten, uitleg, popups). Sluit er een, dan heeft even niets focus en zou Android die aan
 * de lade geven (klapt open); MainActivity houdt de lade dan even dicht en zet de focus terug op de inhoud.
 */
object Lagen {
    var open by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    /** Aanroepen bovenaan een laag-composable: telt mee zolang de laag in beeld is. */
    @Composable
    fun Open() {
        androidx.compose.runtime.DisposableEffect(Unit) { open++; onDispose { open-- } }
    }
}

/** Uitslag van de PackageInstaller: om bevestiging vragen, of een fout tonen. */
class UpdateOntvanger : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        Log.i("Fotos", "update: installatiestatus $status ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val vraag = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else i.getParcelableExtra(Intent.EXTRA_INTENT)
                vraag?.let { runCatching { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
            PackageInstaller.STATUS_SUCCESS -> Update.stand = Update.Stand.Rust // de app wordt nu vervangen
            PackageInstaller.STATUS_FAILURE_ABORTED -> Update.stand = Update.Stand.Rust // zelf geannuleerd
            else -> Update.stand = Update.Stand.Fout(i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status")
        }
    }
}

/** Venster "nieuwe versie": releasetekst, Bijwerken / Later; toont ook voortgang, toestemming en fouten. */
@Composable
fun UpdateScherm(sluit: () -> Unit) {
    Lagen.Open()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val r = Update.nieuw ?: run { sluit(); return }
    val eerste = remember { FocusRequester() }
    val bezig = Update.stand is Update.Stand.Downloaden || Update.stand is Update.Stand.Installeren
    BackHandler(enabled = !bezig) { Update.stand = Update.Stand.Rust; sluit() }
    LaunchedEffect(Update.stand::class) { delay(30); runCatching { eerste.requestFocus() } }
    Venster(stringResource(R.string.update_titel, r.versie), stringResource(R.string.update_sub, Update.versie(ctx))) {
        for ((kop, regel) in Update.kort(r.notities)) {
            if (kop) Text(regel, style = MaterialTheme.typography.titleMedium, color = Kleur.primair, modifier = Modifier.padding(top = 6.dp))
            else Text("•  $regel", style = MaterialTheme.typography.bodyLarge, maxLines = 2)
        }
        Spacer(Modifier.height(22.dp))
        when (val s = Update.stand) {
            is Update.Stand.Downloaden -> Text(stringResource(R.string.update_downloaden, (s.deel * 100).toInt()), style = MaterialTheme.typography.titleMedium, color = Kleur.primair)
            Update.Stand.Installeren -> Text(stringResource(R.string.update_installeren), style = MaterialTheme.typography.titleMedium, color = Kleur.primair)
            Update.Stand.Toestemming -> Text(stringResource(R.string.update_toestemming), style = MaterialTheme.typography.bodyLarge, color = Color(0xFFFCD34D))
            is Update.Stand.Fout -> Text(stringResource(R.string.update_fout, s.tekst), style = MaterialTheme.typography.bodyLarge, color = Color(0xFFFCA5A5))
            Update.Stand.Rust -> {}
        }
        Spacer(Modifier.height(14.dp))
        if (!bezig) Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (Update.stand == Update.Stand.Toestemming) {
                Knop(stringResource(R.string.update_toestemming_knop), null, Modifier.width(300.dp).focusRequester(eerste)) { Update.stand = Update.Stand.Rust; Update.openToestemming(ctx) }
            } else {
                Knop(stringResource(R.string.update_nu), null, Modifier.width(240.dp).focusRequester(eerste)) { scope.launch { Update.installeer(ctx) } }
            }
            Knop(stringResource(R.string.update_later), null, Modifier.width(160.dp)) { Update.stand = Update.Stand.Rust; sluit() }
        }
    }
}

/** "Wat is er nieuw" na een update: een paar regels, elke toets sluit. */
@Composable
fun NieuwsScherm(sluit: () -> Unit) {
    Lagen.Open()
    val ctx = LocalContext.current
    val knop = remember { FocusRequester() }
    BackHandler { sluit() }
    LaunchedEffect(Unit) { delay(30); runCatching { knop.requestFocus() } }
    Venster(stringResource(R.string.nieuw_titel, Update.versie(ctx)), null) {
        for (regel in stringArrayResource(R.array.wat_nieuw)) Text("•  $regel", style = MaterialTheme.typography.bodyLarge, maxLines = 3)
        Spacer(Modifier.height(24.dp))
        Knop(stringResource(R.string.actie_klaar), null, Modifier.width(160.dp).focusRequester(knop)) { sluit() }
    }
}

@Composable
private fun Venster(titel: String, sub: String?, inhoud: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0xF20A0A0A)).focusVangen()
            .onPreviewKeyEvent { e -> e.type == KeyEventType.KeyDown && e.key.nativeKeyCode == KeyEvent.KEYCODE_MENU }
            .padding(horizontal = 56.dp, vertical = 36.dp),
    ) {
        Column(Modifier.widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(titel, style = MaterialTheme.typography.displaySmall)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodyLarge, color = Kleur.zacht)
            Spacer(Modifier.height(14.dp))
            inhoud()
        }
    }
}
