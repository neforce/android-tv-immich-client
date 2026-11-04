package com.immichclient.tv

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Rechtencheck van de API-sleutel: welke van [Koppel.RECHTEN] heeft hij (`/api-keys/me`, een sleutel mag zijn eigen
 * rechten altijd lezen; "all" = alles)? Lukt dat niet (oudere server), dan per recht het bijbehorende endpoint proberen
 * (403 = ontbreekt). Bij een nieuw ontbrekend recht na het starten één keer het overzicht ([RechtenScherm]) tonen;
 * daarna alleen via Instellingen → Account → Rechten of vanuit een scherm dat het recht mist (Mappen).
 */
object Rechten {
    /** Waar elk recht voor nodig is; [kern] = zonder dit werkt de app niet. */
    class Recht(val naam: String, @StringRes val wat: Int, val kern: Boolean = false)

    val ALLE = listOf(
        Recht("asset.read", R.string.recht_asset_read, kern = true),
        Recht("asset.view", R.string.recht_asset_view, kern = true),
        Recht("user.read", R.string.recht_user_read),
        Recht("album.read", R.string.recht_album_read),
        Recht("memory.read", R.string.recht_memory_read),
        Recht("person.read", R.string.recht_person_read),
        Recht("map.read", R.string.recht_map_read),
        Recht("folder.read", R.string.recht_folder_read),
    )

    /** null = nog niet gecontroleerd (of niet te bepalen); anders de ontbrekende rechten */
    var ontbreekt by mutableStateOf<List<String>?>(null)
        private set
    /** rechten waarvan het niet te bepalen was (geen /api-keys/me en geen eenduidig antwoord) */
    var onbekend by mutableStateOf<Set<String>>(emptySet())
        private set
    var sleutelNaam by mutableStateOf<String?>(null)
        private set
    var bezig by mutableStateOf(false)
        private set
    /** overzicht in beeld (MainActivity toont het) */
    var open by mutableStateOf(false)

    suspend fun controleer() {
        if (Immich.sleutel.isBlank()) return
        bezig = true
        try {
            val eigen = Immich.sleutelRechten()
            if (eigen != null) {
                sleutelNaam = eigen.first
                val heeft = eigen.second
                ontbreekt = if ("all" in heeft) emptyList() else ALLE.map { it.naam }.filter { it !in heeft }
                onbekend = emptySet()
            } else {
                sleutelNaam = null
                val uitkomst = ALLE.associate { it.naam to Immich.probeerRecht(it.naam) }
                ontbreekt = uitkomst.filterValues { it == false }.keys.toList()
                onbekend = uitkomst.filterValues { it == null }.keys
            }
            Log.i("Fotos", "rechten: ontbreekt=${ontbreekt} onbekend=$onbekend")
        } finally { bezig = false }
    }

    fun mist(recht: String) = ontbreekt?.contains(recht) == true

    /** Na het starten: overzicht openen als er een recht ontbreekt dat nog niet eerder gemeld is (per account). */
    fun meldNieuw(c: Context) {
        val mist = ontbreekt ?: return
        val p = c.getSharedPreferences("instellingen", Context.MODE_PRIVATE)
        val sleutel = "rechten_gemeld" + (Immich.kijker?.let { "_$it" } ?: "")
        val nu = mist.sorted().joinToString(",")
        val eerder = p.getString(sleutel, "").orEmpty()
        if (nu != eerder) p.edit().putString(sleutel, nu).apply()
        if (mist.isNotEmpty() && nu != eerder) open = true
    }
}

/** Het overzicht ("wizard"): elk recht met ✓ / ✗ en waarvoor het is, plus Opnieuw koppelen / Opnieuw controleren. */
@Composable
fun RechtenScherm(opnieuwKoppelen: () -> Unit, sluit: () -> Unit) {
    val scope = rememberCoroutineScope()
    val eerste = remember { FocusRequester() }
    BackHandler { sluit() }
    LaunchedEffect(Unit) { delay(30); runCatching { eerste.requestFocus() } }
    val mist = Rechten.ontbreekt
    val kern = mist.orEmpty().any { m -> Rechten.ALLE.any { it.naam == m && it.kern } }
    Box(
        Modifier.fillMaxSize().background(Color(0xF20A0A0A)).focusVangen()
            .onPreviewKeyEvent { e -> e.type == KeyEventType.KeyDown && e.key.nativeKeyCode == KeyEvent.KEYCODE_MENU }
            .padding(horizontal = 56.dp, vertical = 36.dp),
    ) {
        Column(Modifier.widthIn(max = 900.dp)) {
            Text(stringResource(R.string.rechten_titel), style = MaterialTheme.typography.displaySmall)
            val sub = when {
                Rechten.bezig || mist == null -> stringResource(R.string.rechten_bezig)
                mist.isEmpty() && Rechten.onbekend.isEmpty() -> stringResource(R.string.rechten_ok)
                mist.isEmpty() -> stringResource(R.string.rechten_deels_onbekend)
                kern -> stringResource(R.string.rechten_kern_mist)
                else -> stringResource(R.string.rechten_mist)
            }
            Text(listOfNotNull(Rechten.sleutelNaam?.let { "“$it”" }, sub).joinToString("  ·  "), style = MaterialTheme.typography.bodyLarge, color = Kleur.zacht)
            Spacer(Modifier.height(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (r in Rechten.ALLE) {
                    val ok = when { mist == null || r.naam in Rechten.onbekend -> null; r.naam in mist -> false; else -> true }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            when (ok) { true -> Icons.Filled.CheckCircle; false -> Icons.Filled.Error; null -> Icons.Filled.HelpOutline },
                            null, Modifier.size(24.dp),
                            tint = when (ok) { true -> Color(0xFF86EFAC); false -> Color(0xFFFCA5A5); null -> Kleur.zacht },
                        )
                        Text(r.naam, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 14.dp).width(150.dp))
                        Text(stringResource(r.wat), style = MaterialTheme.typography.bodyLarge, color = if (ok == false) Color.White else Kleur.zacht)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            if (!mist.isNullOrEmpty()) {
                Text(stringResource(R.string.rechten_oplossen), style = MaterialTheme.typography.bodyMedium, color = Kleur.zacht)
                Spacer(Modifier.height(14.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (!mist.isNullOrEmpty()) {
                    Knop(stringResource(R.string.opnieuw_koppelen), stringResource(R.string.rechten_koppel_sub), Modifier.width(260.dp).focusRequester(eerste)) { opnieuwKoppelen() }
                }
                Knop(stringResource(R.string.rechten_opnieuw), null, Modifier.width(220.dp).then(if (mist.isNullOrEmpty()) Modifier.focusRequester(eerste) else Modifier)) {
                    scope.launch { Rechten.controleer() }
                }
                Knop(stringResource(R.string.rechten_sluiten), null, Modifier.width(160.dp)) { sluit() }
            }
        }
    }
}
