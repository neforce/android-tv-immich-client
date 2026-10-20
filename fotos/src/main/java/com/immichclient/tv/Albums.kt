package com.immichclient.tv

import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class AlbumVolgorde(@StringRes val label: Int) { RECENT(R.string.volg_recent), NIEUW(R.string.volg_nieuwste_fotos), NAAM(R.string.volg_naam), AANTAL(R.string.volg_aantal) }

class AlbumsStaat(scope: CoroutineScope) {
    var albums by mutableStateOf<List<Album>>(emptyList())
    var fout by mutableStateOf<String?>(null)
    var volgorde by mutableStateOf(AlbumVolgorde.RECENT)
    var alleenGedeeld by mutableStateOf(false)
    var zoek by mutableStateOf("")
    val grid = LazyGridState()
    var laatsteFocus: String? = null

    init {
        scope.launch {
            runCatching { Immich.albums() }.onSuccess { albums = it }.onFailure { fout = it.message; Log.w("Fotos", "albums", it) }
        }
    }

    fun zichtbaar(): List<Album> {
        val l = albums.filter { (!alleenGedeeld || it.gedeeld) && (zoek.isBlank() || zonderAccent(it.naam).contains(zonderAccent(zoek.trim()), ignoreCase = true)) && it.aantal > 0 }
        return when (volgorde) {
            AlbumVolgorde.RECENT -> l.sortedByDescending { it.bijgewerkt }
            AlbumVolgorde.NIEUW -> l.sortedByDescending { it.tot }
            AlbumVolgorde.NAAM -> l.sortedWith(compareBy(java.text.Collator.getInstance(Taal.locale)) { it.naam })
            AlbumVolgorde.AANTAL -> l.sortedByDescending { it.aantal }
        }
    }
}

/** "Malaisie" vinden met "malaisie" (accenten negeren) */
private fun zonderAccent(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")

@Composable
fun AlbumsScherm(staat: AlbumsStaat, nav: Nav) {
    val lijst = remember(staat.albums, staat.volgorde, staat.alleenGedeeld, staat.zoek) { staat.zichtbaar() }
    val eerste = remember { FocusRequester() }
    val terug = remember { FocusRequester() }
    var zoekOpen by remember { mutableStateOf(false) }
    val zoekKnop = remember { FocusRequester() }
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(zoekOpen) { if (wasOpen && !zoekOpen) { delay(16); runCatching { zoekKnop.requestFocus() } }; wasOpen = zoekOpen }

    Box(Modifier.fillMaxSize()) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        state = staat.grid,
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 80.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "kop") {
            Column {
                SchermKop(stringResource(R.string.albums), pluralStringResource(R.plurals.albums_aantal, lijst.size, lijst.size) + if (staat.zoek.isNotBlank()) " " + stringResource(R.string.albums_met, staat.zoek) else "")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (v in AlbumVolgorde.entries) {
                        Keuze(
                            selected = staat.volgorde == v, onClick = { staat.volgorde = v },
                            modifier = if (v == AlbumVolgorde.RECENT) Modifier.focusRequester(eerste) else Modifier,
                        tekst = stringResource(v.label))
                    }
                    Keuze(selected = staat.alleenGedeeld, onClick = { staat.alleenGedeeld = !staat.alleenGedeeld }, tekst = stringResource(R.string.gedeeld))
                    Keuze(
                        selected = staat.zoek.isNotBlank(), onClick = { zoekOpen = true }, modifier = Modifier.focusRequester(zoekKnop),
                        leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.width(18.dp)) },
                    tekst = staat.zoek.ifBlank { stringResource(R.string.zoek_album) })
                }
            }
        }
        if (staat.fout != null && staat.albums.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Melding(stringResource(R.string.albums_niet_geladen), staat.fout) }
        items(lijst, key = { it.id }) { a ->
            AlbumKaart(
                a, if (a.id == staat.laatsteFocus) Modifier.focusRequester(terug) else Modifier,
                onFocus = { staat.laatsteFocus = a.id },
            ) {
                nav.open(Scherm.Tijdlijn(a.naam, albumSub(a), Filter(albumId = a.id), !a.oudsteEerst, false, nav.scope))
            }
        }
    }
    ScrolBalk(
        fractie = { fractieVan(staat.grid, 1) },
        beweging = { staat.grid.firstVisibleItemIndex * 7919 + staat.grid.firstVisibleItemScrollOffset / 200 },
        scrolbaar = { staat.grid.canScrollForward || staat.grid.canScrollBackward },
        label = { null },
        sub = { "${Taal.getal((staat.grid.firstVisibleItemIndex - 1).coerceAtLeast(0) + 1)} / ${Taal.getal(lijst.size)}" },
        modifier = Modifier.align(Alignment.CenterEnd),
    )
    }
    LaunchedEffect(Unit) {
        delay(30)
        if (staat.laatsteFocus == null || runCatching { terug.requestFocus() }.isFailure) runCatching { eerste.requestFocus() }
    }
    if (zoekOpen) TekstInvoer(stringResource(R.string.zoek_album), staat.zoek, live = { staat.zoek = it }, actie = stringResource(R.string.actie_klaar), sluit = { zoekOpen = false }) {
        staat.zoek = it; zoekOpen = false
    }
}

fun albumSub(a: Album): String {
    val periode = when {
        a.van == null -> null
        a.tot == null || a.van.withDayOfMonth(1) == a.tot.withDayOfMonth(1) -> a.van.format(Formaat.maandKort)
        else -> a.van.format(Formaat.maandKort) + " – " + a.tot.format(Formaat.maandKort)
    }
    return listOfNotNull(Taal.meervoud(R.plurals.items, a.aantal, a.aantal), periode, if (a.gedeeld) Taal.s(R.string.gedeeld_klein) else null).joinToString("  ·  ")
}

@Composable
private fun AlbumKaart(a: Album, modifier: Modifier, onFocus: () -> Unit, onKlik: () -> Unit) {
    Column {
        Card(
            onClick = onKlik,
            modifier = modifier.fillMaxWidth().aspectRatio(1f).onFocusChanged { if (it.isFocused) onFocus() },
            shape = CardDefaults.shape(RoundedCornerShape(14.dp)),
            scale = CardDefaults.scale(focusedScale = 1.05f),
        ) {
            Box(Modifier.fillMaxSize().background(Kleur.vlakHoog)) {
                if (a.omslag != null) AsyncImage(beeld(Immich.thumb(a.omslag)), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Icon(Icons.Filled.PhotoLibrary, null, Modifier.align(Alignment.Center), tint = Kleur.zacht)
            }
        }
        Text(a.naam, style = MaterialTheme.typography.titleSmall, maxLines = 2, modifier = Modifier.padding(top = 8.dp))
        Text(albumSub(a), style = MaterialTheme.typography.bodySmall, maxLines = 1, color = Color(0xFFA1A1AA))
    }
}
