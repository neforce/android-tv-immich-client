package com.immichclient.tv

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/**
 * Eerste start: taal kiezen met grote tegels (naam in de eigen taal), voorgeselecteerd op de systeemtaal als die
 * ondersteund wordt, anders Engels. De titel en uitleg volgen de tegel die focus heeft, zodat iedereen ziet wat er
 * gevraagd wordt. OK = kiezen → [klaar] (de activity wordt opnieuw opgebouwd in die taal).
 */
@Composable
fun TaalScherm(finish: () -> Unit, klaar: () -> Unit) {
    val ctx = LocalContext.current
    val standaard = remember { Taal.systeemTaal(ctx) }
    var focusTaal by remember { mutableStateOf(standaard) }
    val eerste = remember { FocusRequester() }
    BackHandler { finish() }
    LaunchedEffect(Unit) { delay(60); runCatching { eerste.requestFocus() } }
    // teksten in de taal van de gefocuste tegel (de app zelf staat nog in de voorlopige taal)
    val res = remember(focusTaal) { Taal.resources(focusTaal) }

    Column(Modifier.fillMaxSize().background(Kleur.achtergrond).padding(horizontal = 64.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.background(Kleur.vlak, CircleShape).padding(14.dp)) { Icon(Icons.Filled.Language, null, Modifier.size(30.dp), tint = Kleur.primair) }
        Spacer(Modifier.height(18.dp))
        Text(res.getString(R.string.taal_titel), style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center, maxLines = 1)
        Text(res.getString(R.string.taal_sub), style = MaterialTheme.typography.bodyLarge, color = Kleur.zacht, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(36.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            for (code in Taal.TALEN) {
                val gekozen = code == standaard
                Surface(
                    onClick = { Taal.kies(ctx, code); klaar() },
                    modifier = Modifier.width(150.dp).height(110.dp).then(if (gekozen) Modifier.focusRequester(eerste) else Modifier)
                        .onFocusChanged { if (it.isFocused) focusTaal = code },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (gekozen) Kleur.primair.copy(alpha = 0.22f) else Kleur.vlak, contentColor = Kleur.tekst,
                        focusedContainerColor = Kleur.primair, focusedContentColor = Kleur.opPrimair,
                    ),
                    border = ClickableSurfaceDefaults.border(border = if (gekozen) Border(androidx.compose.foundation.BorderStroke(2.dp, Kleur.primair), shape = RoundedCornerShape(16.dp)) else Border.None),
                ) {
                    // tekststijlen hebben een eigen kleur (Thema.kt) die wint van de content-kleur van de Surface: dus expliciet
                    val kleur = if (focusTaal == code) Kleur.opPrimair else Kleur.tekst
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(code.uppercase(), style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, letterSpacing = 2.sp), color = kleur.copy(alpha = 0.7f))
                        Text(Taal.naam(code), style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp), maxLines = 1, color = kleur)
                    }
                }
            }
        }
    }
}
