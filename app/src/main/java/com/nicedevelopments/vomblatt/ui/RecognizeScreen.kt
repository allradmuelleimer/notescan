package com.nicedevelopments.vomblatt.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.abs
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.nicedevelopments.vomblatt.data.Library
import com.nicedevelopments.vomblatt.recognize.Overlay
import com.nicedevelopments.vomblatt.recognize.Progress
import com.nicedevelopments.vomblatt.ui.theme.LocalInk
import com.nicedevelopments.vomblatt.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.nicedevelopments.vomblatt.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/** Nur auf diesem Bildschirm: Scanner-Hellblau mit Leuchten, auf leicht bläulich abgedunkeltem Foto. */
private val SCAN_CYAN = androidx.compose.ui.graphics.Color(0xFF3FD4FF)
private val SCAN_GLOW = androidx.compose.ui.graphics.Color(0xFFB8F2FF)
private val SCAN_TINT = androidx.compose.ui.graphics.Color(0xFF0A2238)

@Composable
fun RecognizeScreen(photo: File, progress: Progress?, overlay: Overlay?, error: String?, onBack: () -> Unit, onRetry: () -> Unit, pageLabel: String? = null) {
    val ink = LocalInk.current
    // Während der Erkennung (gut eine Minute) soll sich das Display nicht abschalten
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    // Die Erkennung schreibt das Foto zu Beginn aufrecht neu – danach noch einmal laden, sonst sieht man
    // womöglich eine halb geschriebene Datei (grauer Rest)
    val prepared = progress != null && progress.phase != Progress.Phase.PREPARE
    val raw by produceState<android.graphics.Bitmap?>(null, photo, prepared) {
        val fresh = withContext(Dispatchers.IO) { runCatching { Library.decodeUpright(photo, 1400) }.getOrNull() }
        if (fresh != null) value = fresh
    }
    val bmp = remember(raw) { raw?.asImageBitmap() }
    // Kanten, Seitenrand, Zeilenwerte für den Scanner-Look (aus dem Foto, nicht ausgedacht)
    val fx by produceState<ScanFx?>(null, raw) {
        val r = raw ?: return@produceState
        value = withContext(Dispatchers.Default) { runCatching { ScanFx.analyze(r) }.getOrNull() }
    }
    // Bildtakt für Blinken und durchlaufende Zahlen
    val now by produceState(0L) { while (true) androidx.compose.runtime.withFrameMillis { value = it } }
    val fxStart = remember(fx) { mutableLongStateOf(0L) }
    LaunchedEffect(fx) { if (fx != null) androidx.compose.runtime.withFrameMillis { fxStart.longValue = it } }
    val mono = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.MONOSPACE }
    }
    // Größe der Datei, auf die sich die Erkennungskoordinaten beziehen (vorbereitetes, aufrechtes Foto)
    val sourceWidth by produceState(0, overlay) {
        value = if (overlay == null) 0 else withContext(Dispatchers.IO) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(photo.absolutePath, o)
            o.outWidth
        }
    }
    val scan = rememberInfiniteTransition(label = "scan")
    val t by scan.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Reverse), label = "t")
    val pulse by scan.animateFloat(0.55f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
    // Funde bauen sich Zeile für Zeile auf
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(overlay) { if (overlay != null) { reveal.snapTo(0f); reveal.animateTo(1f, tween(1800, easing = LinearEasing)) } }
    val reading = if (progress?.phase == Progress.Phase.READ) progress.done - 1 else if (progress?.phase == Progress.Phase.WRITE) Int.MAX_VALUE else -1

    Column(Modifier.fillMaxSize().background(ink.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back), onBack)
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp).clip(RoundedCornerShape(18.dp)).background(ink.surface),
            contentAlignment = Alignment.Center,
        ) {
            val b = bmp
            b?.let { Image(it, stringResource(R.string.photographed_sheet), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            if (error == null && b != null) Canvas(Modifier.fillMaxSize()) {
                val scale = minOf(size.width / b.width, size.height / b.height)
                val dx = (size.width - b.width * scale) / 2
                val dy = (size.height - b.height * scale) / 2
                val ov = overlay
                val W = b.width * scale; val H = b.height * scale
                fun np(x: Float, y: Float) = Offset(dx + x * W, dy + y * H)
                val f = fx
                val frame = now
                // Leicht bläulich abgedunkelt, damit das Leuchten trägt – kein Schwarz
                drawRect(SCAN_TINT.copy(alpha = if (ink.dark) 0.38f else 0.24f), Offset(dx, dy), Size(W, H))

                // Seitenrand einmal leuchtend abfahren, danach Eckwinkel wie im Sucher
                f?.page?.let { q ->
                    val corners = (0 until 4).map { np(q[2 * it], q[2 * it + 1]) }
                    val segs = (0 until 4).map { corners[it] to corners[(it + 1) % 4] }
                    val lens = segs.map { (a, c) -> (c - a).getDistance() }
                    val perim = lens.sum().coerceAtLeast(1f)
                    val prog = ((frame - fxStart.longValue) / 1700f).coerceIn(0f, 1f)
                    var left = perim * prog
                    var head: Offset? = null
                    for ((i, sg) in segs.withIndex()) {
                        if (left <= 0f) break
                        val part = (left / lens[i]).coerceAtMost(1f)
                        val end = sg.first + (sg.second - sg.first) * part
                        drawLine(SCAN_CYAN.copy(alpha = 0.22f), sg.first, end, 7.dp.toPx())
                        drawLine(SCAN_CYAN.copy(alpha = if (prog < 1f) 0.95f else 0.45f), sg.first, end, 1.6.dp.toPx())
                        head = end
                        left -= lens[i]
                    }
                    if (prog < 1f) head?.let {
                        drawCircle(SCAN_CYAN.copy(alpha = 0.25f), 12.dp.toPx(), it)
                        drawCircle(androidx.compose.ui.graphics.Color.White, 3.dp.toPx(), it)
                    } else {
                        val arm = 22.dp.toPx()
                        val glowA = 0.6f + 0.4f * pulse
                        corners.forEachIndexed { i, c ->
                            val toNext = corners[(i + 1) % 4] - c; val toPrev = corners[(i + 3) % 4] - c
                            for (d in listOf(toNext, toPrev)) {
                                val e = c + d / d.getDistance().coerceAtLeast(1f) * arm
                                drawLine(SCAN_CYAN.copy(alpha = 0.3f * glowA), c, e, 7.dp.toPx())
                                drawLine(SCAN_CYAN.copy(alpha = glowA), c, e, 2.4.dp.toPx())
                            }
                        }
                    }
                }

                if (ov == null || sourceWidth == 0) {
                    // Scanlinie: Kanten in ihrer Nähe leuchten auf und klingen nach
                    val yN = t
                    val y = dy + H * yN
                    drawRect(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(SCAN_CYAN.copy(alpha = 0f), SCAN_CYAN.copy(alpha = 0.18f), SCAN_CYAN.copy(alpha = 0f)),
                            startY = y - 70.dp.toPx(), endY = y + 70.dp.toPx(),
                        ),
                        Offset(dx, maxOf(dy, y - 70.dp.toPx())),
                        Size(W, (minOf(dy + H, y + 70.dp.toPx()) - maxOf(dy, y - 70.dp.toPx())).coerceAtLeast(0f)),
                    )
                    f?.let { fe ->
                        val band = 0.07f
                        val buckets = Array(4) { ArrayList<Offset>() }
                        val e = fe.edges
                        var i = 0
                        // Anfang per Binärsuche (nach y sortiert)
                        var lo = 0; var hi = e.size / 2
                        while (lo < hi) { val mid = (lo + hi) / 2; if (e[2 * mid + 1] < yN - band) lo = mid + 1 else hi = mid }
                        i = lo
                        while (i < e.size / 2 && e[2 * i + 1] <= yN + band) {
                            val d = abs(e[2 * i + 1] - yN) / band
                            buckets[(d * 4).toInt().coerceIn(0, 3)].add(np(e[2 * i], e[2 * i + 1]))
                            i++
                        }
                        buckets.forEachIndexed { k, pts ->
                            if (pts.isEmpty()) return@forEachIndexed
                            val a = 1f - k / 4f
                            drawPoints(pts, androidx.compose.ui.graphics.PointMode.Points, SCAN_CYAN.copy(alpha = 0.22f * a), 6.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
                            drawPoints(pts, androidx.compose.ui.graphics.PointMode.Points, SCAN_GLOW.copy(alpha = a), 1.8.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
                        }
                    }
                    drawLine(SCAN_CYAN.copy(alpha = 0.35f), Offset(dx, y), Offset(dx + W, y), 6.dp.toPx())
                    drawLine(SCAN_GLOW, Offset(dx, y), Offset(dx + W, y), 1.6.dp.toPx())
                }
                // Zahlenkolonnen am Bildrand: Grauwerte der Zeilen um die Scanlinie, laufen durch
                f?.let { fe ->
                    val nc = drawContext.canvas.nativeCanvas
                    mono.textSize = 9.dp.toPx()
                    val lineH = 11.dp.toPx()
                    val rowsN = (H / lineH).toInt().coerceAtMost(60)
                    val scroll = (frame / 70L).toInt()
                    val center = ((if (ov == null) t else ((reading.coerceAtLeast(0) + 0.5f) / ov.staffBoxes.size.coerceAtLeast(1))) * fe.rows.size).toInt()
                    for (side in 0..1) {
                        for (r in 0 until rowsN) {
                            val idx = Math.floorMod(center + r - rowsN / 2 + scroll * (if (side == 0) 1 else -1), fe.rows.size)
                            val bytes = fe.rows[idx]
                            val txt = (0 until 3).joinToString("") { k -> "%02X".format(bytes[(k * 5 + side * 2 + r) % 16].toInt() and 255) }
                            val yy = dy + r * lineH + lineH
                            val dist = abs(r - rowsN / 2f) / (rowsN / 2f)
                            val flick = if ((idx * 7 + scroll) % 13 == 0) 1f else 0.55f
                            mono.color = android.graphics.Color.argb(((1f - dist) * 200 * flick).toInt().coerceIn(0, 255), 120, 230, 255)
                            val x = if (side == 0) dx + 4.dp.toPx() else dx + W - mono.measureText(txt) - 4.dp.toPx()
                            nc.drawText(txt, x, yy, mono)
                        }
                    }
                    // Telemetrie-Zeile unten im Bild
                    val stripH = 18.dp.toPx()
                    drawRect(SCAN_TINT.copy(alpha = 0.62f), Offset(dx, dy + H - stripH), Size(W, stripH))
                    val rowIdx = (t * (fe.rows.size - 1)).toInt()
                    val info = if (ov == null) {
                        "SCAN Y %.3f   EDGES %04d / %05d   %s".format(java.util.Locale.ROOT, t, fe.edgeCount[rowIdx], fe.edgeTotal, if (fe.page != null) "PAGE LOCKED" else "PAGE …")
                    } else {
                        val cur = (reading + 1).coerceIn(0, ov.staffBoxes.size)
                        "STAFF %d/%d   NOTEHEADS %04d   LINES %02d   %s".format(java.util.Locale.ROOT, cur, ov.staffBoxes.size, ov.noteCount,
                            ov.staffLines.sumOf { it.size }, "▮".repeat(1 + (frame / 250L % 4).toInt()))
                    }
                    mono.textSize = 9.5f.dp.toPx()
                    mono.color = android.graphics.Color.argb(235, 150, 238, 255)
                    nc.drawText(info, dx + 8.dp.toPx(), dy + H - 5.dp.toPx(), mono)
                }
                if (ov == null || sourceWidth == 0) return@Canvas
                val k = b.width.toFloat() / sourceWidth * scale
                fun pt(x: Float, y: Float) = Offset(dx + x * k, dy + y * k)
                val n = ov.staffLines.size
                ov.staffLines.forEachIndexed { i, lines ->
                    val shown = ((reveal.value * n) - i).coerceIn(0f, 1f)
                    if (shown <= 0f) return@forEachIndexed
                    val box = ov.staffBoxes[i]
                    val done = i < reading
                    val current = i == reading
                    if (current) {
                        val tl = pt(box.left, box.top); val br = pt(box.right, box.bottom)
                        val pad = 6.dp.toPx()
                        drawRoundRect(SCAN_CYAN.copy(alpha = 0.14f * pulse), Offset(tl.x - pad, tl.y - pad), Size(br.x - tl.x + 2 * pad, br.y - tl.y + 2 * pad), CornerRadius(8.dp.toPx()))
                        drawRoundRect(SCAN_GLOW.copy(alpha = pulse), Offset(tl.x - pad, tl.y - pad), Size(br.x - tl.x + 2 * pad, br.y - tl.y + 2 * pad), CornerRadius(8.dp.toPx()), style = Stroke(2.dp.toPx()))
                        // Lesekopf fährt die Zeile entlang
                        val sx = tl.x + (br.x - tl.x) * ((now % 1400L) / 1400f)
                        drawLine(SCAN_CYAN.copy(alpha = 0.35f), Offset(sx, tl.y - pad), Offset(sx, br.y + pad), 8.dp.toPx())
                        drawLine(SCAN_GLOW, Offset(sx, tl.y - pad), Offset(sx, br.y + pad), 1.6.dp.toPx())
                    }
                    val col = if (done) SCAN_GLOW.copy(alpha = 0.30f * shown) else SCAN_CYAN.copy(alpha = 0.9f * shown)
                    for (line in lines) {
                        val path = Path()
                        var j = 0
                        val visible = (line.size / 2 * shown).toInt().coerceAtLeast(1)
                        while (j < visible * 2 && j + 1 < line.size) {
                            val p = pt(line[j], line[j + 1])
                            if (j == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                            j += 2
                        }
                        drawPath(path, col, style = Stroke(1.6.dp.toPx()))
                    }
                }
                // Notenköpfe
                val total = ov.noteCount
                for (i in 0 until total) {
                    val a = ((reveal.value * total * 1.1f) - i).coerceIn(0f, 1f)
                    if (a <= 0f) continue
                    val c = pt(ov.notes[2 * i], ov.notes[2 * i + 1])
                    val inBox = ov.staffBoxes.indexOfFirst { it.contains(ov.notes[2 * i], ov.notes[2 * i + 1]) }
                    val done = inBox in 0 until reading
                    // Messpunkt wie beim Abgleich: Kästchen, in der aktuellen Zeile blinkend und mit Kennung
                    val live = inBox == reading
                    val blink = if (live) 0.55f + 0.45f * kotlin.math.sin(now / 110.0 + i * 0.9).toFloat() else 1f
                    val r = 4.dp.toPx()
                    val colN = if (done) SCAN_GLOW.copy(alpha = 0.35f * a) else SCAN_GLOW.copy(alpha = a * blink)
                    drawRect(SCAN_CYAN.copy(alpha = 0.25f * a * blink), Offset(c.x - r, c.y - r), Size(2 * r, 2 * r))
                    drawRect(colN, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = Stroke(1.3.dp.toPx()))
                    if (live && i % 3 == 0) {
                        mono.textSize = 8.dp.toPx()
                        mono.color = android.graphics.Color.argb((220 * blink).toInt().coerceIn(0, 255), 160, 240, 255)
                        drawContext.canvas.nativeCanvas.drawText("N-%04d".format(i), c.x + r + 2.dp.toPx(), c.y - r, mono)
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(24.dp)) {
            if (error != null) {
                Text(stringResource(R.string.rec_failed), style = Type.title, color = ink.ink)
                Spacer(Modifier.height(8.dp))
                Text(error, style = Type.body, color = ink.ink2)
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PrimaryButton(stringResource(R.string.try_again), null, onRetry, Modifier.weight(1f))
                    SecondaryButton(stringResource(R.string.cancel), null, onBack)
                }
            } else {
                Text(pageLabel?.let { stringResource(R.string.reading_notes_page, it) } ?: stringResource(R.string.reading_notes), style = Type.title, color = ink.ink)
                if (overlay != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        pluralStringResource(R.plurals.heads_found, overlay.staffBoxes.size, overlay.noteCount, overlay.staffBoxes.size),
                        style = Type.label, color = ink.accent,
                    )
                }
                Spacer(Modifier.height(14.dp))
                for (ph in Progress.Phase.entries) {
                    val cur = progress?.phase
                    val state = when {
                        cur == null -> 0
                        ph.ordinal < cur.ordinal -> 2
                        ph == cur -> 1
                        else -> 0
                    }
                    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(10.dp).clip(CircleShape).background(
                                when (state) { 2 -> ink.ink; 1 -> ink.accent; else -> ink.line }
                            )
                        )
                        Spacer(Modifier.width(12.dp))
                        val extra = if (state == 1 && progress != null && progress.total > 0) "  " + stringResource(R.string.progress_of, progress.done, progress.total) else ""
                        Text(stringResource(ph.label) + extra, style = Type.body, color = if (state == 0) ink.ink3 else ink.ink)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(if (pageLabel != null) stringResource(R.string.rec_hint_pages) else stringResource(R.string.rec_hint), style = Type.label, color = ink.ink2)
            }
        }
    }
}
