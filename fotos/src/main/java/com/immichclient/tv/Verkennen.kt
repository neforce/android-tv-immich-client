package com.immichclient.tv

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class VerkenStaat(scope: CoroutineScope) {
    var personen by mutableStateOf<List<Persoon>>(emptyList())
    var plaatsen by mutableStateOf<List<Plaats>>(emptyList())
    var fout by mutableStateOf<String?>(null)
    val grid = LazyGridState()
    var laatsteFocus: String? = null

    init {
        scope.launch { runCatching { Immich.personen() }.onSuccess { personen = it }.onFailure { fout = it.message; Log.w("Fotos", "personen", it) } }
        scope.launch {
            runCatching { Immich.plaatsen() }
                .onSuccess { l ->
                    // het land met de meeste plaatsen (hoofdland) eerst, daarna op land en plaats
                    val hoofdland = l.groupingBy { it.land }.eachCount().maxByOrNull { it.value }?.key
                    val volgorde = java.text.Collator.getInstance(Taal.locale)
                    plaatsen = l.sortedWith(compareBy<Plaats> { it.land != hoofdland }.thenBy(volgorde) { it.land }.thenBy(volgorde) { it.stad })
                }
                .onFailure { Log.w("Fotos", "plaatsen", it) }
        }
    }
}

/** Personen (gezichten) en plaatsen, zoals "Verkennen" in Immich. */
@Composable
fun VerkenScherm(staat: VerkenStaat, nav: Nav) {
    val eerste = remember { FocusRequester() }
    val terug = remember { FocusRequester() }
    val persoon = stringResource(R.string.persoon)
    LazyVerticalGrid(
        columns = GridCells.Fixed(30),
        state = staat.grid,
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 80.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "kop") { SchermKop(stringResource(R.string.verkennen), null) }
        if (staat.fout != null && staat.personen.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Melding(stringResource(R.string.niet_geladen), staat.fout) }
        if (staat.personen.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "kp") {
            Text(stringResource(R.string.personen), style = MaterialTheme.typography.titleLarge)
        }
        items(staat.personen, key = { "p" + it.id }, span = { GridItemSpan(3) }) { p ->
            val mod = when {
                ("p" + p.id) == staat.laatsteFocus -> Modifier.focusRequester(terug)
                p == staat.personen.first() -> Modifier.focusRequester(eerste)
                else -> Modifier
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Card(
                    onClick = { nav.open(Scherm.Tijdlijn(p.naam, persoon, Filter(persoonId = p.id), true, false, nav.scope)) },
                    modifier = mod.fillMaxWidth().aspectRatio(1f).onFocusChanged { if (it.isFocused) staat.laatsteFocus = "p" + p.id },
                    shape = CardDefaults.shape(CircleShape),
                    scale = CardDefaults.scale(focusedScale = 1.08f),
                ) { AsyncImage(beeld(Immich.gezicht(p.id)), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                Text(p.naam, style = MaterialTheme.typography.labelMedium, maxLines = 2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (staat.plaatsen.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "kpl") {
            Text(stringResource(R.string.plaatsen), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 18.dp))
        }
        items(staat.plaatsen, key = { "s" + it.stad + it.land }, span = { GridItemSpan(5) }) { pl ->
            Card(
                onClick = {
                    nav.open(Scherm.Lijst(pl.stad, pl.land, true, nav.scope) { pagina -> Immich.zoekPlaats(pl.stad, pagina) })
                },
                modifier = (if (("s" + pl.stad + pl.land) == staat.laatsteFocus) Modifier.focusRequester(terug) else Modifier)
                    .fillMaxWidth().aspectRatio(1.3f).onFocusChanged { if (it.isFocused) staat.laatsteFocus = "s" + pl.stad + pl.land },
                shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
                scale = CardDefaults.scale(focusedScale = 1.05f),
            ) {
                Box(Modifier.fillMaxSize()) {
                    AsyncImage(beeld(Immich.thumb(pl.omslag)), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(onderVerloop))
                    Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                        Text(pl.stad, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1)
                        Text(pl.land, style = MaterialTheme.typography.labelSmall, color = Color(0xCCFFFFFF), maxLines = 1)
                    }
                }
            }
        }
    }
    LaunchedEffect(staat.personen.isNotEmpty()) {
        delay(30)
        if (staat.laatsteFocus == null || runCatching { terug.requestFocus() }.isFailure) runCatching { eerste.requestFocus() }
    }
}
