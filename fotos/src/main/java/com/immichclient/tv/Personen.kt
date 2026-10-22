package com.immichclient.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

/**
 * Alle personen (vanuit Zoeken → "Alle personen"): raster met gezichten, standaard op hoe vaak iemand voorkomt (de
 * volgorde van Immich: favorieten eerst, dan het aantal foto's), of op naam; zoeken filtert tijdens het typen.
 */
class PersonenStaat(val alle: List<Persoon>) {
    var zoek by mutableStateOf("")
    var opNaam by mutableStateOf(false)
    val grid = LazyGridState()
    var laatsteFocus: String? = null

    fun zichtbaar(): List<Persoon> {
        val z = zonderAccenten(zoek.trim())
        val l = if (z.isEmpty()) alle else alle.filter { p -> zonderAccenten(p.naam).split(' ').any { it.startsWith(z, ignoreCase = true) } || zonderAccenten(p.naam).contains(z, ignoreCase = true) }
        return if (opNaam) l.sortedWith(compareBy(java.text.Collator.getInstance(Taal.locale)) { it.naam }) else l
    }
}

fun zonderAccenten(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")

@Composable
fun PersonenScherm(staat: PersonenStaat, nav: Nav) {
    val lijst = remember(staat.alle, staat.zoek, staat.opNaam) { staat.zichtbaar() }
    val persoon = stringResource(R.string.persoon)
    var zoekOpen by remember { mutableStateOf(false) }
    val zoekKnop = remember { FocusRequester() }
    val terug = remember { FocusRequester() }
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(zoekOpen) { if (wasOpen && !zoekOpen) { delay(16); runCatching { zoekKnop.requestFocus() } }; wasOpen = zoekOpen }
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(8),
            state = staat.grid,
            contentPadding = PaddingValues(start = 24.dp, end = 40.dp, top = 20.dp, bottom = 80.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "kop") {
                Column {
                    SchermKop(stringResource(R.string.personen), Taal.getal(lijst.size) + if (staat.zoek.isNotBlank()) "  ·  “${staat.zoek}”" else "")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Keuze(
                            selected = staat.zoek.isNotBlank(), onClick = { zoekOpen = true }, modifier = Modifier.focusRequester(zoekKnop),
                            leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.width(18.dp)) },
                            tekst = staat.zoek.ifBlank { stringResource(R.string.zoek_persoon) },
                        )
                        Keuze(selected = !staat.opNaam, onClick = { staat.opNaam = false }, tekst = stringResource(R.string.volg_vaakst))
                        Keuze(selected = staat.opNaam, onClick = { staat.opNaam = true }, tekst = stringResource(R.string.volg_naam))
                    }
                }
            }
            items(lijst, key = { it.id }) { p ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Card(
                        onClick = { nav.open(Scherm.Tijdlijn(p.naam, persoon, Filter(persoonId = p.id), true, false, nav.scope)) },
                        modifier = (if (p.id == staat.laatsteFocus) Modifier.focusRequester(terug) else Modifier)
                            .fillMaxWidth().aspectRatio(1f).onFocusChanged { if (it.isFocused) staat.laatsteFocus = p.id },
                        shape = CardDefaults.shape(CircleShape),
                        scale = CardDefaults.scale(focusedScale = 1.08f),
                    ) { AsyncImage(beeld(Immich.gezicht(p.id)), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    Text(p.naam, style = MaterialTheme.typography.labelMedium, maxLines = 2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        ScrolBalk(
            fractie = { fractieVan(staat.grid, 1) },
            beweging = { staat.grid.firstVisibleItemIndex * 7919 + staat.grid.firstVisibleItemScrollOffset / 200 },
            scrolbaar = { staat.grid.canScrollForward || staat.grid.canScrollBackward },
            label = { lijst.getOrNull((staat.grid.firstVisibleItemIndex - 1).coerceAtLeast(0))?.naam?.take(1)?.uppercase()?.takeIf { staat.opNaam } },
            sub = { "${Taal.getal((staat.grid.firstVisibleItemIndex - 1).coerceAtLeast(0) + 1)} / ${Taal.getal(lijst.size)}" },
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
    LaunchedEffect(Unit) {
        delay(30)
        if (staat.laatsteFocus == null || runCatching { terug.requestFocus() }.isFailure) runCatching { zoekKnop.requestFocus() }
    }
    if (zoekOpen) TekstInvoer(stringResource(R.string.zoek_persoon), staat.zoek, live = { staat.zoek = it }, actie = stringResource(R.string.actie_klaar), sluit = { zoekOpen = false }) {
        staat.zoek = it; zoekOpen = false
    }
}
