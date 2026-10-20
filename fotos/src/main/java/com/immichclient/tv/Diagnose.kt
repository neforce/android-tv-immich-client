package com.immichclient.tv

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * Diagnose: waarom sloot de app? Alles blijft op het apparaat (filesDir/diagnose.json, de laatste [MAX] regels):
 *  - een eigen UncaughtExceptionHandler schrijft de stacktrace weg en geeft dan door aan de standaardhandler (de app
 *    sluit dus gewoon, maar bij de volgende start staat er wat er gebeurde);
 *  - bij het starten leest [start] de systeemlijst van proces-eindes (ApplicationExitInfo, Android 11+): crash, ANR,
 *    geheugennood, signaal, door de gebruiker gestopt, …;
 *  - gevangen fouten in coroutines ([noteer], via de CoroutineExceptionHandler van de app) staan er ook, als "app liep door".
 * Te zien onder Instellingen → Over → Diagnose.
 */
object Diagnose {
    private const val BESTAND = "diagnose.json"
    private const val MAX = 12
    private const val TAG = "Fotos"

    class Regel(val tijd: Long, val soort: Soort, val omschrijving: String?, val detail: String?, val voorgrond: Boolean)

    enum class Soort(@StringRes val label: Int) {
        CRASH(R.string.diag_crash), CRASH_NATIVE(R.string.diag_crash_native), ANR(R.string.diag_anr), GEHEUGEN(R.string.diag_geheugen),
        SIGNAAL(R.string.diag_signaal), GEBRUIKER(R.string.diag_gebruiker), SYSTEEM(R.string.diag_systeem), GEVANGEN(R.string.diag_gevangen),
        ANDERS(R.string.diag_anders),
    }

    private lateinit var app: Context
    @Volatile private var geinstalleerd = false

    fun start(c: Context) {
        if (geinstalleerd) return
        geinstalleerd = true
        app = c.applicationContext
        val vorige = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { voegToe(Regel(System.currentTimeMillis(), Soort.CRASH, "${t.name}: ${e::class.java.simpleName}", stack(e), true)) }
            vorige?.uncaughtException(t, e)
        }
        Thread({ runCatching { leesEindes() }.onFailure { Log.w(TAG, "diagnose", it) } }, "diagnose").start()
    }

    /** Gevangen fout (de app liep door), bijv. uit een coroutine. */
    fun noteer(waar: String, e: Throwable) {
        if (!geinstalleerd) return
        Thread { runCatching { voegToe(Regel(System.currentTimeMillis(), Soort.GEVANGEN, "$waar: ${e::class.java.simpleName}: ${e.message?.take(160)}", stack(e), true)) } }.start()
    }

    private fun stack(e: Throwable): String {
        val sw = java.io.StringWriter()
        e.printStackTrace(java.io.PrintWriter(sw))
        // alleen het begin: de eerste regels zeggen waar het misging; dieper is alleen framework
        return sw.toString().lineSequence().filter { it.isNotBlank() }.take(14).joinToString("\n") { it.trim().take(200) }
    }

    /** Proces-eindes van het systeem, alleen die na de laatst bekende (anders staat alles dubbel). */
    private fun leesEindes() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val prefs = app.getSharedPreferences("diagnose", Context.MODE_PRIVATE)
        val laatst = prefs.getLong("laatst", 0L)
        var nieuwLaatst = laatst
        val lijst = am.getHistoricalProcessExitReasons(app.packageName, 0, 10).filter { it.timestamp > laatst }.sortedBy { it.timestamp }
        for (e in lijst) {
            val soort = when (e.reason) {
                ApplicationExitInfo.REASON_CRASH -> Soort.CRASH
                ApplicationExitInfo.REASON_CRASH_NATIVE -> Soort.CRASH_NATIVE
                ApplicationExitInfo.REASON_ANR -> Soort.ANR
                ApplicationExitInfo.REASON_LOW_MEMORY -> Soort.GEHEUGEN
                ApplicationExitInfo.REASON_SIGNALED -> Soort.SIGNAAL
                ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED -> Soort.GEBRUIKER
                ApplicationExitInfo.REASON_PERMISSION_CHANGE, ApplicationExitInfo.REASON_DEPENDENCY_DIED,
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> Soort.SYSTEEM
                else -> Soort.ANDERS
            }
            val voorgrond = e.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            val om = listOfNotNull(e.description?.takeIf { it.isNotBlank() }, "pss ${e.pss / 1024} MB", if (voorgrond) null else "importance ${e.importance}").joinToString(" · ")
            // de stacktrace van een crash staat al in de eigen regel van de handler (zelfde moment): dan alleen aanvullen
            if (soort == Soort.CRASH && lees().any { it.soort == Soort.CRASH && kotlin.math.abs(it.tijd - e.timestamp) < 15_000 }) { nieuwLaatst = maxOf(nieuwLaatst, e.timestamp); continue }
            voegToe(Regel(e.timestamp, soort, om, null, voorgrond))
            nieuwLaatst = maxOf(nieuwLaatst, e.timestamp)
        }
        if (nieuwLaatst != laatst) prefs.edit().putLong("laatst", nieuwLaatst).apply()
    }

    private val slot = Any()

    @Synchronized private fun voegToe(r: Regel) = synchronized(slot) {
        val l = (lees() + r).sortedBy { it.tijd }.takeLast(MAX)
        val a = JSONArray()
        for (x in l) a.put(JSONObject().put("t", x.tijd).put("s", x.soort.name).put("o", x.omschrijving ?: JSONObject.NULL).put("d", x.detail ?: JSONObject.NULL).put("v", x.voorgrond))
        File(app.filesDir, BESTAND).writeText(a.toString())
    }

    fun lees(): List<Regel> = runCatching {
        val f = File(app.filesDir, BESTAND)
        if (!f.exists()) return emptyList()
        val a = JSONArray(f.readText())
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            Regel(o.getLong("t"), runCatching { Soort.valueOf(o.getString("s")) }.getOrDefault(Soort.ANDERS), o.optString("o").takeIf { it.isNotEmpty() && it != "null" }, o.optString("d").takeIf { it.isNotEmpty() && it != "null" }, o.optBoolean("v"))
        }
    }.getOrDefault(emptyList())

    fun wis() { runCatching { File(app.filesDir, BESTAND).delete() } }
}

/** Instellingen → Over → Diagnose: de laatste afsluitingen met tijd, reden en het begin van de stacktrace. */
@Composable
fun DiagnoseLaag(sluit: () -> Unit) {
    val ctx = LocalContext.current
    val fr = remember { FocusRequester() }
    BackHandler { sluit() }
    LaunchedEffect(Unit) { delay(30); runCatching { fr.requestFocus() } }
    val regels by produceState<List<Diagnose.Regel>?>(null) { value = withContext(Dispatchers.IO) { Diagnose.lees().sortedByDescending { it.tijd } } }
    val scroll = rememberScrollState()
    val versie = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }
    val geheugen = remember {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        "${mi.totalMem / 1048576} MB"
    }
    Box(Modifier.fillMaxSize().background(Color(0xF20A0A0A)).focusVangen().focusRequester(fr).focusable().toetsScroll(scroll).padding(horizontal = 56.dp, vertical = 36.dp)) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Text(stringResource(R.string.diagnose), style = MaterialTheme.typography.displaySmall)
            Text(stringResource(R.string.diagnose_sub), style = MaterialTheme.typography.bodyMedium, color = Kleur.zacht)
            Text(
                "${stringResource(R.string.app_regel, stringResource(Instellingen.uiterlijk.label))} $versie  ·  Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})  ·  ${Build.MANUFACTURER} ${Build.MODEL}  ·  RAM $geheugen",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(18.dp))
            val l = regels
            when {
                l == null -> Text(stringResource(R.string.laden), style = MaterialTheme.typography.bodyLarge, color = Kleur.zacht)
                l.isEmpty() -> Text(stringResource(R.string.diagnose_leeg), style = MaterialTheme.typography.bodyLarge, color = Kleur.zacht)
                else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (r in l) {
                        Column(Modifier.fillMaxWidth().background(Kleur.vlak, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 10.dp)) {
                            val t = Instant.ofEpochMilli(r.tijd).atZone(ZoneId.systemDefault()).toLocalDateTime()
                            Row {
                                Text(datumLang(t), style = MaterialTheme.typography.labelLarge, color = Kleur.primair)
                                Text("   " + stringResource(r.soort.label) + if (r.voorgrond && r.soort != Diagnose.Soort.GEVANGEN) "  ·  " + stringResource(R.string.diag_voorgrond) else "", style = MaterialTheme.typography.labelLarge)
                            }
                            r.omschrijving?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp)) }
                            r.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp), modifier = Modifier.padding(top = 6.dp)) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.diagnose_voet), style = MaterialTheme.typography.bodySmall)
        }
    }
}
