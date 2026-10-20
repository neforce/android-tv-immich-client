package com.immichclient.tv

import android.util.Log
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

class KijkerStaat(val bron: BeeldBron, start: Int, val titel: String?, diashow: Boolean) {
    var index by mutableIntStateOf(start)
    var richting = 1
    var diashow by mutableStateOf(diashow)
    var info by mutableStateOf(false)
    /** laatste toetsdruk; de balk onderin verdwijnt na een paar seconden */
    var actief by mutableLongStateOf(System.currentTimeMillis())
    var speeltVideo by mutableStateOf(false)
    var videoFout by mutableStateOf(false)
    var speler: ExoPlayer? = null
    /** volgende foto van de diashow, alvast gekozen (en vooraf geladen) zodra de huidige in beeld staat */
    var diaVolgende = -1

    /** Volgende foto voor de diashow: op volgorde of willekeurig, video's zo nodig overgeslagen; -1 = klaar. */
    fun diaDoel(): Int {
        val n = bron.aantal
        if (Instellingen.diaWillekeurig) {
            if (n <= 1) return -1
            repeat(20) {
                val i = kotlin.random.Random.nextInt(n)
                if (i != index && (Instellingen.diaVideos || bron.op(i)?.video != true)) return i
            }
            return -1
        }
        var i = index + 1
        while (i < n && !Instellingen.diaVideos && bron.op(i)?.video == true) i++
        return if (i < n) i else -1
    }

    /** Diashow een stap verder (of stoppen aan het eind). */
    fun diaVerder() {
        val d = diaVolgende.takeIf { it >= 0 && it != index && it < bron.aantal } ?: diaDoel()
        diaVolgende = -1
        if (d < 0) { diashow = false; return }
        richting = if (Instellingen.diaSchuiven) 1 else 0
        index = d
    }
}

@Composable
fun Kijker(staat: KijkerStaat, opKaart: ((Asset) -> Unit)? = null, sluit: (Asset?) -> Unit) {
    val ctx = LocalContext.current
    val fr = remember { FocusRequester() }
    val huidig = staat.bron.op(staat.index)
    BackHandler { if (staat.info) staat.info = false else sluit(staat.bron.op(staat.index)) }
    LaunchedEffect(Unit) { fr.requestFocus() }

    fun ga(d: Int) {
        val n = (staat.index + d)
        if (n < 0 || n >= staat.bron.aantal) return
        // video meteen stoppen (buffers vrij) in plaats van pas na de overgang: minder piek naast de nieuwe preview
        if (huidig?.video == true) { staat.speler?.stop(); staat.speeltVideo = false }
        staat.richting = d
        staat.index = n
        staat.actief = System.currentTimeMillis()
    }

    // Buren vooraf laden, zuinig: eerst de volgende, dan de vorige, en pas als je blijft nog eentje verder.
    // Niet tijdens een video (de speler heeft het geheugen nodig); previews ≈ 6 MB per stuk als hardware-bitmap.
    LaunchedEffect(staat.index, staat.speeltVideo, staat.diashow) {
        if (staat.speeltVideo) return@LaunchedEffect
        // willekeurige diashow: de buren zijn dan niet de volgende; die laadt de diashow-lus zelf vooraf
        if (staat.diashow && Instellingen.diaWillekeurig) return@LaunchedEffect
        val loader = SingletonImageLoader.get(ctx)
        fun laad(d: Int) { staat.bron.op(staat.index + d)?.takeIf { !it.video }?.let { loader.enqueue(previewVerzoek(ctx, it.id)) } }
        laad(1)
        delay(300); laad(-1)
        delay(700); laad(2)
    }
    // diashow: volgende alvast kiezen en vooraf laden (ook bij willekeurig of overgeslagen video's), dan verder
    LaunchedEffect(staat.diashow, staat.index, staat.speeltVideo) {
        if (!staat.diashow || staat.speeltVideo) return@LaunchedEffect
        val t0 = System.currentTimeMillis()
        val doel = staat.diaDoel()
        staat.diaVolgende = doel
        if (doel >= 0) {
            val loader = SingletonImageLoader.get(ctx)
            // de maand van een willekeurige foto is misschien nog niet geladen (op() start dat): even later nog eens
            for (poging in 0 until 3) {
                val a = staat.bron.op(doel)
                if (a != null) { if (!a.video) loader.enqueue(previewVerzoek(ctx, a.id)); break }
                delay(500)
            }
        }
        delay((Instellingen.diaMs - (System.currentTimeMillis() - t0)).coerceAtLeast(300))
        staat.diaVerder()
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(fr).focusable().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val k = e.key.nativeKeyCode
            when (k) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { staat.diashow = false; ga(-1); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { staat.diashow = false; ga(1); true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO -> {
                    staat.info = !staat.info; staat.actief = System.currentTimeMillis(); true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    val sp = staat.speler
                    // infopaneel open bij een foto met plek: OK springt naar de wereldkaart
                    if (staat.info && opKaart != null && huidig != null && huidig.lat != null) opKaart(huidig)
                    else if (huidig?.video == true && sp != null && !staat.diashow) sp.playWhenReady = !sp.playWhenReady
                    else staat.diashow = !staat.diashow
                    staat.actief = System.currentTimeMillis(); true
                }
                // ⏩ / ⏪: in een video 10 s, anders 10 foto's
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { staat.speler?.takeIf { huidig?.video == true }?.let { it.seekTo(it.currentPosition + 10_000) } ?: ga(10); true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { staat.speler?.takeIf { huidig?.video == true }?.let { it.seekTo((it.currentPosition - 10_000).coerceAtLeast(0)) } ?: ga(-10); true }
                else -> false
            }
        },
    ) {
        val dicht = LocalDensity.current
        val breedtePx = with(dicht) { maxWidth.roundToPx() }
        val hoogtePx = with(dicht) { maxHeight.roundToPx() }

        // Bewust geen fadeIn/fadeOut hier: die zetten een schermvullende offscreen-laag (8 MB) per pagina op en HWUI houdt
        // die lagen in zijn cache. De crossfade zit in Beeld (ModulateAlpha = alpha per tekenopdracht, zonder laag);
        // de slide/zoom bepaalt alleen hoe lang de oude pagina nog bestaat.
        AnimatedContent(
            targetState = staat.index,
            transitionSpec = {
                when (staat.richting) {
                    // diashow: de nieuwe foto vloeit erin en 'landt' met een kleine zoom
                    0 -> scaleIn(tween(1400), initialScale = 1.03f) togetherWith scaleOut(tween(900), targetScale = 1f)
                    else -> slideInHorizontally(tween(260)) { it / 6 * staat.richting.coerceIn(-1, 1) } togetherWith
                        slideOutHorizontally(tween(260)) { -it / 6 * staat.richting.coerceIn(-1, 1) }
                }
            },
            label = "foto",
        ) { i ->
            val a = staat.bron.op(i)
            val zicht by transition.animateFloat(transitionSpec = { tween(if (staat.richting == 0) 900 else 200) }, label = "zicht") {
                if (it == EnterExitState.Visible) 1f else 0f
            }
            if (a != null) Beeld(a, staat, actief = i == staat.index, zicht = { zicht })
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.laden), style = MaterialTheme.typography.bodyLarge, color = Kleur.zacht) }
        }

        // Landkaart (zoals in een fotolijst-screensaver): alleen bij foto's met GPS, niet bij video of info. De laag zelf wacht even
        // bij een nieuw land en laat bij hetzelfde land alleen de stip glijden. Instelling: uit / klein / groot.
        val kaartMaat = Instellingen.viewerKaart.schaal
        KaartLaag(
            doel = huidig?.takeIf { kaartMaat > 0f && !it.video && it.lat != null && it.lon != null && !staat.info },
            breedtePx = breedtePx, hoogtePx = hoogtePx, maat = kaartMaat,
        )

        // balk onderin: datum en plaats, verdwijnt vanzelf (instelbaar, 0 = altijd)
        var balk by remember { mutableStateOf(true) }
        LaunchedEffect(staat.actief, staat.index) { balk = true; val ms = Instellingen.balkMs; if (ms > 0) { delay(ms.toLong()); balk = false } }
        AnimatedVisibility(
            (balk || staat.info) && huidig != null && !staat.speeltVideo, Modifier.align(Alignment.BottomStart),
            enter = fadeIn(), exit = fadeOut(),
        ) {
            if (huidig != null) OnderBalk(huidig, staat, metKaart = huidig.lat != null && !huidig.video && kaartMaat > 0f)
        }
        AnimatedVisibility(
            staat.info && huidig != null, Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut(),
        ) {
            if (huidig != null) InfoPaneel(huidig, opKaart != null && huidig.lat != null)
        }
        // Diashow rechtsboven: een badge (play-icoon + "Diashow") bij het starten, en kort "Diashow gepauzeerd" bij het
        // stoppen (OK, of handmatig bladeren); niet bij elke automatische wissel. Hij vervaagt na een paar seconden.
        // Het voortgangsringetje houdt zijn vaste plek: de badge vervaagt alleen (alpha) en houdt zijn ruimte.
        var badge by remember { mutableStateOf<Boolean?>(null) }
        var badgeRonde by remember { mutableIntStateOf(0) }
        var eersteKeer by remember { mutableStateOf(true) }
        LaunchedEffect(staat.diashow) {
            // viewer geopend zonder diashow: geen "gepauzeerd"
            if (eersteKeer) { eersteKeer = false; if (!staat.diashow) return@LaunchedEffect }
            badge = staat.diashow; badgeRonde++
        }
        LaunchedEffect(badgeRonde) { if (badge != null) { delay(if (badge == true) 5000 else 3000); badge = null } }
        Row(Modifier.align(Alignment.TopEnd).padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            val b = badge
            val badgeAlpha by animateFloatAsState(if (b != null) 1f else 0f, tween(450), label = "badge")
            var laatste by remember { mutableStateOf(true) } // laatste inhoud, zodat hij met die tekst uitfadet
            if (b != null) laatste = b
            Row(
                Modifier.padding(end = 10.dp).graphicsLayer { alpha = badgeAlpha }
                    .background(Color(0xA6000000), RoundedCornerShape(14.dp)).padding(start = 9.dp, end = 13.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (laatste) Icons.Filled.PlayArrow else Icons.Filled.Pause, null, Modifier.size(17.dp), tint = Color.White)
                Spacer(Modifier.width(5.dp))
                Text(stringResource(if (laatste) R.string.diashow else R.string.diashow_pauze), style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1)
            }
            AnimatedVisibility(staat.diashow, enter = fadeIn(), exit = fadeOut()) { DiaRing(staat) }
        }
    }
}

/** Ringetje dat in de diashow-tijd volloopt en bij elke foto opnieuw begint; staat stil zolang een video speelt. */
@Composable
private fun DiaRing(staat: KijkerStaat) {
    val deel = remember { Animatable(0f) }
    LaunchedEffect(staat.index, staat.speeltVideo) {
        deel.snapTo(0f)
        if (!staat.speeltVideo) deel.animateTo(1f, tween(Instellingen.diaMs.toInt(), easing = LinearEasing))
    }
    Canvas(Modifier.size(22.dp)) {
        val dikte = 2.2.dp.toPx()
        drawCircle(Color(0x38FFFFFF), style = Stroke(dikte))
        drawArc(Color(0xD9FFFFFF), -90f, 360f * deel.value, false, style = Stroke(dikte, cap = StrokeCap.Round))
    }
}

@Composable
private fun Beeld(a: Asset, staat: KijkerStaat, actief: Boolean, zicht: () -> Float) {
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = zicht(); compositingStrategy = CompositingStrategy.ModulateAlpha }, contentAlignment = Alignment.Center) {
        // diashow: heel langzaam inzoomen (Ken Burns, instelbaar), anders stil
        // Alleen op de diashow-schakelaar: de vertrekkende pagina houdt zijn zoom (anders springt hij terug tijdens de
        // crossfade), en na het stoppen glijdt de foto terug naar 1,0 in plaats van te springen.
        val zoom = remember { Animatable(1f) }
        LaunchedEffect(staat.diashow) {
            if (staat.diashow && Instellingen.kenBurns) { if (actief) zoom.animateTo(1.06f, tween((Instellingen.diaMs + 1500).toInt(), easing = LinearEasing)) }
            else if (zoom.value != 1f) zoom.animateTo(1f, tween(500, easing = FastOutSlowInEasing))
        }
        val mod = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom.value; scaleY = zoom.value }
        // 1) thumbhash 2) thumbnail (al in de cache van het raster) 3) preview: er staat dus altijd meteen iets
        val hash = remember(a.thumbhash) { a.thumbhash?.let { ThumbHash.bitmap(it) } }
        if (hash != null) {
            // achtergrond i.p.v. zwarte balken: de thumbhash (32 px) schermvullend opgerekt = gratis 'blur', verdonkerd
            Image(hash, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.7f)
            Box(Modifier.fillMaxSize().background(Color(0x8C000000)))
            val vorm = Modifier.aspectRatio(a.ratio.coerceIn(0.3f, 4f), matchHeightConstraintsFirst = true)
            Image(hash, null, vorm, contentScale = ContentScale.FillBounds)
        }
        AsyncImage(beeld(Immich.thumb(a.id)), null, mod, contentScale = ContentScale.Fit)
        if (!a.video) AsyncImage(preview(a.id), null, mod, contentScale = ContentScale.Fit)
        if (a.video && actief) Video(a, staat)
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun Video(a: Asset, staat: KijkerStaat) {
    val ctx = LocalContext.current
    val speler = remember {
        // Kleine buffers: standaard reserveert ExoPlayer tot ~140 MB (50 s) aan video; op een box met 2 GB is dat
        // genoeg om de app vooraan te laten opruimen. Een server in het eigen netwerk heeft aan 4-15 s genoeg.
        val buffers = DefaultLoadControl.Builder()
            .setBufferDurationsMs(4_000, 15_000, 1_000, 2_000)
            .setTargetBufferBytes(16 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(0, false)
            .build()
        ExoPlayer.Builder(ctx).setLoadControl(buffers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(Immich.http))).build()
    }
    DisposableEffect(a.id) {
        staat.videoFout = false
        speler.setMediaItem(MediaItem.fromUri(Immich.video(a.id)))
        speler.prepare()
        speler.playWhenReady = true
        val luister = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { staat.speeltVideo = isPlaying }
            override fun onPlaybackStateChanged(s: Int) {
                // diashow: na de video door naar de volgende
                if (s == Player.STATE_ENDED) { staat.speeltVideo = false; if (staat.diashow) staat.diaVerder() }
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.w("Fotos", "video ${a.id}", error)
                staat.speeltVideo = false; staat.videoFout = true // diashow loopt dan na de diashow-tijd gewoon door
            }
        }
        speler.addListener(luister)
        staat.speler = speler
        // vrijgeven zodra deze pagina weg is; bij video→video heeft de nieuwe pagina staat.speler dan al overgenomen
        onDispose {
            speler.removeListener(luister); speler.stop(); speler.release()
            if (staat.speler === speler) { staat.speler = null; staat.speeltVideo = false }
        }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { PlayerView(it).apply { player = speler; useController = false } }, modifier = Modifier.fillMaxSize())
        if (staat.videoFout) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.VideocamOff, null, Modifier.size(44.dp), tint = Kleur.zacht)
            Text(stringResource(R.string.video_fout), style = MaterialTheme.typography.bodyLarge, color = Kleur.zacht, modifier = Modifier.padding(top = 8.dp))
        }
        // dun voortgangsbalkje onderin
        val deel by produceState(0f, speler) {
            while (true) { val d = speler.duration; value = if (d > 0) speler.currentPosition.toFloat() / d else 0f; delay(250) }
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x33FFFFFF))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(deel).background(Kleur.primair))
        }
    }
}

@Composable
private fun OnderBalk(a: Asset, staat: KijkerStaat, metKaart: Boolean) {
    Box(Modifier.fillMaxWidth().height(170.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))) {
        Column(Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = 28.dp)) {
            // teller bij de titel, links: rechtsonder staat de landkaart (die liep over de teller heen)
            Row(verticalAlignment = Alignment.Bottom) {
                staat.titel?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = Kleur.primair, modifier = Modifier.padding(end = 12.dp)) }
                if (Instellingen.teller) Text("${staat.index + 1} / ${Taal.getal(staat.bron.aantal)}", style = MaterialTheme.typography.labelMedium, color = Color(0xAAFFFFFF), modifier = Modifier.padding(bottom = 1.dp))
            }
            Text(datumLang(a.datum), style = MaterialTheme.typography.titleLarge, color = Color.White)
            val plaats = listOfNotNull(a.stad, a.land).joinToString(", ")
            // met kaart (foto met GPS) staat de plaats zo bij de stip
            if (plaats.isNotEmpty() && !metKaart) Text(plaats, style = MaterialTheme.typography.bodyLarge, color = Color(0xDDFFFFFF))
        }
    }
}

@Composable
private fun InfoPaneel(a: Asset, metKaartSprong: Boolean) {
    val details by produceState<Details?>(null, a.id) { value = runCatching { Immich.details(a.id) }.getOrNull() }
    Column(Modifier.fillMaxHeight().width(380.dp).background(Color(0xF0121214)).padding(28.dp)) {
        // De hintregel staat vast onderaan; de inhoud erboven mag langer zijn dan het scherm (veel personen, lange
        // omschrijving) en scrolt dan weg, in plaats van de hint uit beeld te duwen. Lange lijsten worden afgekapt.
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(stringResource(R.string.info), style = MaterialTheme.typography.headlineSmall)
            InfoRegel(Icons.Filled.Schedule, datumLang(a.datum), null)
            val d = details
            if (d != null) {
                d.omschrijving?.let { Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                if (d.mensen.isNotEmpty()) InfoRegel(Icons.Filled.People, d.mensen.joinToString(", "), null, maxRegels = 3)
                d.plaats?.let { InfoRegel(Icons.Filled.Place, it, null) }
                if (a.lat != null && a.lon != null) {
                    KaartKlein(a.lat, a.lon, Modifier.fillMaxWidth().height(150.dp).background(Kleur.vlak, RoundedCornerShape(12.dp)))
                }
                InfoRegel(Icons.Filled.Image, d.bestand, d.afmeting)
                d.camera?.let { InfoRegel(Icons.Filled.CameraAlt, it, listOfNotNull(d.lens, d.instellingen).joinToString("\n").ifEmpty { null }) }
            } else {
                Text(stringResource(R.string.laden), style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Info, null, Modifier.size(16.dp), tint = Kleur.zacht)
            Text("  " + stringResource(if (metKaartSprong) R.string.hint_info_kaart else R.string.hint_info), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun InfoRegel(icoon: ImageVector, titel: String, sub: String?, maxRegels: Int = Int.MAX_VALUE) {
    Row {
        Icon(icoon, null, Modifier.size(22.dp).padding(top = 2.dp), tint = Kleur.zacht)
        Column(Modifier.padding(start = 14.dp)) {
            Text(titel, style = MaterialTheme.typography.bodyLarge, maxLines = maxRegels, overflow = TextOverflow.Ellipsis)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
