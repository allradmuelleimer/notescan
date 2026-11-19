package com.nicedevelopments.vomblatt.ui

import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.res.ResourcesCompat
import com.nicedevelopments.vomblatt.R
import com.nicedevelopments.vomblatt.engrave.Engraver
import com.nicedevelopments.vomblatt.engrave.Layout
import com.nicedevelopments.vomblatt.engrave.Op
import com.nicedevelopments.vomblatt.engrave.TextStyle
import com.nicedevelopments.vomblatt.model.Score
import com.nicedevelopments.vomblatt.ui.theme.LocalInk
import kotlin.math.abs
import kotlin.math.min

class MusicFont(val typeface: Typeface)

@Composable
fun rememberMusicFont(): MusicFont {
    val ctx = LocalContext.current
    return remember { MusicFont(Typeface.createFromAsset(ctx.assets, "fonts/Bravura.otf")) }
}

/** Setzt die Partitur für eine Breite (Pixel). Teuer – nur bei Änderungen neu rechnen. */
fun engrave(
    score: Score, widthPx: Float, density: Float, font: MusicFont, uiTypeface: Typeface?, keep: Set<Int> = emptySet(),
    /** Notenzeilen des Fotos: dann im Original-Layout setzen (Zeilen, Takte, Notenspalten wie auf dem Blatt). */
    photo: List<com.nicedevelopments.vomblatt.model.SystemGeom>? = null,
    /** Einstellung „Takte je Zeile“: false = immer 2, true = je Fotozeile 2 oder 3. */
    dynamicLines: Boolean = false,
): Layout {
    val topPad = 6f * density
    val spFree = (widthPx / density).let { w -> if (w > 600) 8f else 6.2f } * density
    val frame = photo?.let { g ->
        // Maße der Zeichen in Linienabständen (für die Platzverteilung)
        val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font.typeface; textSize = 4f }
        val unit = Engraver(1f, { unitPaint.measureText(it) })
        runCatching {
            com.nicedevelopments.vomblatt.engrave.OriginalLayout.build(score, g, widthPx, topPad, spFree, dynamicLines, unit) { sp0 -> 12f * density + sp0 * 1.2f }
        }.onFailure { android.util.Log.w("VomBlatt", "Original-Layout", it) }.getOrNull()
    }
    val sp = frame?.sp ?: spFree
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font.typeface; textSize = sp * 4 }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = uiTypeface }
    val bold = uiTypeface?.let { Typeface.create(it, Typeface.BOLD) } ?: Typeface.DEFAULT_BOLD
    val engraver = Engraver(sp, { paint.measureText(it) }) { t, size, style ->
        text.textSize = size
        text.typeface = if (style == TextStyle.BOLD || style == TextStyle.BOLD_EXPR) bold else uiTypeface
        text.measureText(t)
    }
    val layout = engraver.layout(score, widthPx, marginX = 12f * density + sp * 1.2f, topPad = topPad, keep = keep, fixed = frame?.fixed)
    if (frame != null) { frame.bind(layout); layout.photoFrame = frame }
    if (com.nicedevelopments.vomblatt.BuildConfig.DEBUG) {
        // Prüfung Note für Note: steht der Zeitbalken beim Notenanfang genau über der Note?
        var worst = 0f; var where = ""
        for ((mi, slots) in layout.slotX) for ((t, x) in slots.dropLast(1)) {
            val cx = cursorX(layout, mi, t.toFloat()) ?: continue
            if (abs(cx - x) > worst) { worst = abs(cx - x); where = "Takt ${mi + 1} Tick $t – Spalten " + slots.joinToString { (tt, xx) -> "$tt:%.0f".format(xx) } }
        }
        android.util.Log.d("VomBlatt", "Zeitbalken-Prüfung: größte Abweichung %.1f px (%.2f Linienabstände) bei %s".format(worst, worst / sp, where))
        // Wie weit mussten Noten vom gleichmäßigen Zeitabstand abweichen (Vorzeichen, Schlüssel)?
        var shift = 0f; var shiftWhere = ""; var shifted = 0
        for ((mi, slots) in layout.slotX) {
            val real = slots.dropLast(1)
            if (real.size < 3 || real.last().first <= real.first().first) continue
            val rate = (real.last().second - real.first().second) / (real.last().first - real.first().first)
            for ((t, x) in real) {
                val d = abs(x - (real.first().second + rate * (t - real.first().first)))
                if (d > sp * 0.3f) shifted++
                if (d > shift) { shift = d; shiftWhere = "Takt ${mi + 1} Tick $t" }
            }
        }
        android.util.Log.d("VomBlatt", "Notenabstand: %d Noten weichen > 0,3 Linienabstände vom Zeitabstand ab, höchstens %.2f bei %s".format(shifted, shift / sp, shiftWhere))
    }
    return layout
}

data class Cursor(val measure: Int, val x: Float)

/** Wo im Notenbild gedrückt gehalten wurde: auf einer Note oder an einer leeren Stelle eines Systems. */
sealed interface Hold {
    data class Head(val chordId: Long, val noteIndex: Int) : Hold
    /** [pos]: Halbzwischenräume über der untersten Notenlinie (0 = unterste Linie), [tick] im Takt [measure]. */
    data class Spot(val measure: Int, val staff: Int, val tick: Int, val pos: Int) : Hold
}

/** Note unter dem Finger, sonst die Stelle im nächstgelegenen Takt und System (Zeit nach den Notenspalten, Höhe nach den Linien). */
fun holdAt(layout: Layout, x: Float, y: Float): Hold? {
    layout.heads.filter { it.rect.contains(x, y) }
        .minByOrNull { abs(it.y - y) + abs(it.x - x) * 0.5f }
        ?.let { return Hold.Head(it.chordId, it.noteIndex) }
    val mb = layout.measures.firstOrNull { it.rect.contains(x, y) } ?: layout.measures.minByOrNull { m ->
        val dx = maxOf(m.rect.left - x, 0f, x - m.rect.right)
        val dy = maxOf(m.rect.top - y, 0f, y - m.rect.bottom)
        dx * 4 + dy
    } ?: return null
    val sys = layout.systems.getOrNull(mb.system) ?: return null
    val sp = layout.sp
    // oberes System bis zur Mitte zwischen den beiden Systemen
    val staff = if (y < (sys.staffTops[0] + 4 * sp + sys.staffTops[1]) / 2) 0 else 1
    val pos = 8 - kotlin.math.round((y - sys.staffTops[staff]) / (sp / 2)).toInt()
    // auf eine Notenspalte in der Nähe einrasten, sonst auf Sechzehntel
    val raw = tickAtX(layout, mb.index, x)
    val onSlot = layout.slotX[mb.index]?.any { it.first == raw } == true
    val tick = if (onSlot) raw else (raw + 3) / 6 * 6
    return Hold.Spot(mb.index, staff, tick, pos)
}

@Composable
fun ScoreCanvas(
    layout: Layout,
    font: MusicFont,
    active: Set<Long>,
    selected: Long?,
    cursor: Cursor?,
    loop: IntRange?,
    suspicious: Set<Int>,
    /** Gedrückt halten: auf einer Note bzw. an einer leeren Stelle (dort entsteht eine neue Note). */
    onHold: (Hold) -> Unit,
    onTapMeasure: (measure: Int, tick: Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Weiter gedrückt nach oben/unten gezogen: Stammtonschritte seit Beginn (+ = höher). */
    onHoldMove: (Int) -> Unit = {},
    /** Finger losgelassen. */
    onHoldEnd: () -> Unit = {},
    /** Gewählter Ton im gewählten Akkord (wird hinterlegt, damit man ihn im Akkord erkennt). */
    selectedNote: Int = -1,
    /** Eingepasste Bleistift-Notizen aus dem Foto (leer = ausgeblendet). */
    notes: List<NotesPiece> = emptyList(),
) {
    val ink = LocalInk.current
    val density = LocalDensity.current.density
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val currentLayout = rememberUpdatedState(layout)
    val currentTap = rememberUpdatedState(onTapMeasure)
    val currentHold = rememberUpdatedState(onHold)
    val currentMove = rememberUpdatedState(onHoldMove)
    val currentEnd = rememberUpdatedState(onHoldEnd)
    val labelTypeface = remember { ResourcesCompat.getFont(ctx, R.font.instrument_sans) }
    val glyphPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font.typeface } }
    val linePaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.BUTT } }
    val fillPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL } }
    val labelPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = labelTypeface } }
    val labelBold = remember { labelTypeface?.let { Typeface.create(it, Typeface.BOLD) } ?: Typeface.DEFAULT_BOLD }
    val path = remember { Path() }
    val notePaint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    // Bleistift gut lesbar, aber von den schwarzen Noten unterscheidbar: Farbe auf den Bereich helles
    // Anthrazit (dunkelste Stelle ≈ #5A5E64) bis Hellgrau abbilden, Deckkraft etwa verdoppeln
    val darkenFilter = remember {
        android.graphics.ColorMatrixColorFilter(floatArrayOf(
            0.35f, 0f, 0f, 0f, 76f,
            0f, 0.35f, 0f, 0f, 80f,
            0f, 0f, 0.35f, 0f, 86f,
            0f, 0f, 0f, 1.6f, 0f,
        ))
    }
    // Dunkelmodus: dasselbe hell auf dunkel (hellstes Grau statt Weiß)
    val invertFilter = remember {
        android.graphics.ColorMatrixColorFilter(floatArrayOf(
            -0.35f, 0f, 0f, 0f, 200f,
            0f, -0.35f, 0f, 0f, 200f,
            0f, 0f, -0.35f, 0f, 204f,
            0f, 0f, 0f, 1.6f, 0f,
        ))
    }
    val heightDp = with(LocalDensity.current) { layout.height.toDp() }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(heightDp)
            // Schlüssel Unit: Ändert sich das Notenbild während des Ziehens (neue Tonhöhe), darf die Geste nicht neu
            // starten – Layout und Rückmeldungen werden deshalb immer frisch gelesen
            .pointerInput(Unit) {
                awaitEachGesture {
                    val layout = currentLayout.value
                    val onTapMeasure = currentTap.value
                    val onHold = currentHold.value
                    val onHoldMove = { n: Int -> currentMove.value(n) }
                    val onHoldEnd = { currentEnd.value() }
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var up = false
                    var moved = false
                    val waited = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) { up = true; break }
                            if ((ch.position - down.position).getDistance() > viewConfiguration.touchSlop) { moved = true; break }
                        }
                    }
                    if (waited != null) {
                        // Tippen: Abspielmarker an diese Stelle (auf eine Notenspalte in der Nähe einrasten) – im Takt
                        // oder, über/unter den Linien und zwischen den Zeilen, im nächstgelegenen. Wischen: scrollen.
                        if (up && !moved) {
                            val pos = down.position
                            (layout.measures.firstOrNull { it.rect.contains(pos.x, pos.y) } ?: layout.measures.minByOrNull { m ->
                                val dx = maxOf(m.rect.left - pos.x, 0f, pos.x - m.rect.right)
                                val dy = maxOf(m.rect.top - pos.y, 0f, pos.y - m.rect.bottom)
                                dx * 4 + dy
                            })?.let { mb -> onTapMeasure(mb.index, tickAtX(layout, mb.index, pos.x)) }
                        }
                        return@awaitEachGesture
                    }
                    // Gedrückt halten: Note auswählen bzw. an leerer Stelle eine neue setzen; weiter nach oben/unten
                    // ziehen ändert die Tonhöhe stufenweise (eine Stufe je ~0,7 Linienabstände)
                    val target = holdAt(layout, down.position.x, down.position.y) ?: return@awaitEachGesture
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onHold(target)
                    val stepPx = layout.sp * 0.7f
                    var last = 0
                    while (true) {
                        val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        ch.consume()
                        val steps = kotlin.math.round(-(ch.position.y - down.position.y) / stepPx).toInt()
                        if (steps != last) {
                            last = steps
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onHoldMove(steps)
                        }
                    }
                    onHoldEnd()
                }
            }
    ) {
        val c = drawContext.canvas.nativeCanvas
        val sp = layout.sp
        val inkC = ink.ink.toArgb()
        val accentC = ink.accent.toArgb()

        // Bleistift-Notizen vom Foto, leicht zurückgenommen; im Dunkelmodus hell statt dunkel
        if (notes.isNotEmpty()) {
            notePaint.alpha = 255
            notePaint.colorFilter = if (ink.dark) invertFilter else darkenFilter
            // unter allem anderen: die Zeichnung als unverändertes Bild (im Original-Layout nur gerade gezogen)
            for (p in notes) {
                val v = p.verts
                val clip = p.clip
                if (clip != null) { c.save(); c.clipRect(clip) }
                if (v != null) c.drawBitmapMesh(p.bitmap, p.meshW, p.meshH, v, 0, null, 0, notePaint)
                else c.drawBitmap(p.bitmap, p.matrix, notePaint)
                if (clip != null) c.restore()
            }
        }
        // gewählter Ton: kräftige Hinterlegung (im Akkord leuchten sonst alle Töne gleich)
        if (selected != null && selectedNote >= 0) {
            layout.heads.firstOrNull { it.chordId == selected && it.noteIndex == selectedNote }?.let { h ->
                fillPaint.color = ink.accent.copy(alpha = 0.22f).toArgb()
                val r = h.rect
                c.drawRoundRect(r.left - sp * 0.1f, h.y - sp * 0.75f, r.right + sp * 0.1f, h.y + sp * 0.75f, sp * 0.5f, sp * 0.5f, fillPaint)
            }
        }
        // Schleifenbereich
        if (loop != null) {
            fillPaint.color = ink.accentSoft.toArgb()
            for (mb in layout.measures) if (mb.index in loop) {
                val s = layout.systems[mb.system]
                c.drawRect(mb.rect.left, s.staffTops[0] - sp * 1.2f, mb.rect.right, s.staffTops[1] + sp * 5.2f, fillPaint)
            }
        }
        // Cursor
        if (cursor != null) {
            layout.measures.firstOrNull { it.index == cursor.measure }?.let { mb ->
                val s = layout.systems[mb.system]
                // Zeitbalken: eine feine Linie mit leichtem Schein, über das ganze Klaviersystem
                val x = cursor.x + sp * 0.6f
                fillPaint.color = ink.accent.copy(alpha = 0.14f).toArgb()
                c.drawRect(x - sp * 0.45f, s.staffTops[0] - sp * 1.5f, x + sp * 0.45f, s.staffTops[1] + sp * 5.5f, fillPaint)
                fillPaint.color = ink.accent.toArgb()
                c.drawRect(x - sp * 0.09f, s.staffTops[0] - sp * 1.5f, x + sp * 0.09f, s.staffTops[1] + sp * 5.5f, fillPaint)
            }
        }
        // Hinweis auf Takte, deren Notenwerte nicht aufgehen
        if (suspicious.isNotEmpty()) {
            fillPaint.color = ink.warn.toArgb()
            for (mb in layout.measures) if (mb.index in suspicious) {
                val s = layout.systems[mb.system]
                c.drawCircle(mb.rect.right - sp * 0.9f, s.staffTops[0] - sp * 1.6f, sp * 0.42f, fillPaint)
            }
        }

        for (op in layout.ops) {
            val col = when {
                op.chordId >= 0 && op.chordId == selected -> accentC
                op.chordId >= 0 && op.chordId in active -> accentC
                else -> inkC
            }
            when (op) {
                is Op.Line -> {
                    linePaint.color = col; linePaint.strokeWidth = op.width
                    c.drawLine(op.x1, op.y1, op.x2, op.y2, linePaint)
                }
                is Op.Glyph -> {
                    glyphPaint.color = col
                    glyphPaint.textSize = sp * 4 * op.scale
                    if (op.scaleY != 1f) {
                        c.save(); c.scale(1f, op.scaleY, op.x, op.y)
                        c.drawText(op.text, op.x, op.y, glyphPaint)
                        c.restore()
                    } else c.drawText(op.text, op.x, op.y, glyphPaint)
                }
                is Op.Poly -> {
                    fillPaint.color = col
                    path.reset(); path.moveTo(op.pts[0], op.pts[1])
                    var i = 2
                    while (i < op.pts.size) { path.lineTo(op.pts[i], op.pts[i + 1]); i += 2 }
                    path.close(); c.drawPath(path, fillPaint)
                }
                is Op.Curve -> {
                    fillPaint.color = col
                    val dx = op.x2 - op.x1
                    val h = min(sp * 1.3f, dx * 0.16f + sp * 0.35f) * (if (op.up) -1 else 1)
                    val t = op.thickness * (if (op.up) -1 else 1)
                    path.reset()
                    path.moveTo(op.x1, op.y1)
                    path.cubicTo(op.x1 + dx * 0.25f, op.y1 + h, op.x2 - dx * 0.25f, op.y2 + h, op.x2, op.y2)
                    path.cubicTo(op.x2 - dx * 0.25f, op.y2 + h - t, op.x1 + dx * 0.25f, op.y1 + h - t, op.x1, op.y1)
                    path.close(); c.drawPath(path, fillPaint)
                }
                is Op.Label -> {
                    labelPaint.color = if (op.style == TextStyle.MUTED) ink.ink3.toArgb() else inkC; labelPaint.textSize = op.size
                    // Vortragsangaben („dolce“, „rit.“) kursiv, Tempo und Formteile fett wie im Druck
                    labelPaint.textSkewX = if (op.style == TextStyle.EXPR || op.style == TextStyle.BOLD_EXPR) -0.22f else 0f
                    labelPaint.typeface = if (op.style == TextStyle.BOLD || op.style == TextStyle.BOLD_EXPR) labelBold else labelTypeface
                    c.drawText(op.text, op.x, op.y, labelPaint)
                }
            }
        }
    }
}

/** x-Position des Cursors aus Takt und Tick (zwischen den Notenspalten interpoliert). */
/**
 * Zeitbalken für Takt und Tick. Läuft er im letzten Takt einer Zeile über das Zeilenende hinaus, beginnt er schon in
 * der nächsten Zeile zu laufen – so, dass er die erste Note dort genau beim Erklingen erreicht. Am Stückende bleibt
 * er am Schluss stehen.
 */
fun cursorAt(layout: Layout, measure: Int, tick: Float): Cursor? {
    val x = cursorX(layout, measure, tick) ?: return null
    val mb = layout.measures.firstOrNull { it.index == measure } ?: return Cursor(measure, x)
    val sys = layout.systems.getOrNull(mb.system) ?: return Cursor(measure, x)
    val draw = layout.sp * 0.6f // der Balken wird um so viel rechts von x gezeichnet
    if (x + draw <= sys.x1) return Cursor(measure, x)
    val nb = layout.measures.firstOrNull { it.index == measure + 1 }
    val nx = layout.slotX[measure + 1]?.firstOrNull()?.second
    if (nb == null || nx == null || nb.system == mb.system) return Cursor(measure, minOf(x, sys.x1 - draw))
    val length = layout.slotX[measure]?.lastOrNull()?.first?.toFloat() ?: return Cursor(measure, x)
    val xEnd = cursorX(layout, measure, length) ?: return Cursor(measure, x)
    return Cursor(measure + 1, nx - (xEnd - x))
}

/** Umkehrung von [cursorX]: notierte Stelle (Tick) im Takt, über der der Zeitbalken bei [x] steht; nahe Notenspalten rasten ein. */
fun tickAtX(layout: Layout, measure: Int, x: Float): Int {
    val slots = layout.slotX[measure] ?: return 0
    val length = slots.last().first.coerceAtLeast(1)
    slots.dropLast(1).minByOrNull { abs(it.second - x) }?.let { (t, sx) -> if (abs(sx - x) < layout.sp * 1.2f) return t }
    var best = 0; var bestD = Float.MAX_VALUE
    for (t in 0 until length) {
        val cx = cursorX(layout, measure, t.toFloat()) ?: continue
        val d = abs(cx - x)
        if (d < bestD) { bestD = d; best = t }
    }
    return best
}

fun cursorX(layout: Layout, measure: Int, tick: Float): Float? {
    // Innerhalb eines Takts mit gleichbleibender Geschwindigkeit: von der ersten Notenspalte bis zur ersten Note des
    // nächsten Takts derselben Zeile (sonst bis zum Taktende). Kein Abbremsen und Beschleunigen an den Notenspalten.
    val slots = layout.slotX[measure] ?: return null
    if (slots.size < 2) return slots.firstOrNull()?.second
    val sys = layout.measures.firstOrNull { it.index == measure }?.system
    val nextFirst = layout.measures.firstOrNull { it.index == measure + 1 }
        ?.takeIf { it.system == sys }?.let { layout.slotX[measure + 1]?.firstOrNull()?.second }
    val length = slots.last().first.coerceAtLeast(1)
    val x0 = slots.first().second
    val t0 = slots.first().first.coerceAtMost(length - 1)
    // letzter Takt der Zeile: im Takt der Noten weiterlaufen (aus erster und letzter Notenspalte), nicht aufs Taktende
    val real = slots.dropLast(1)
    // Im Original-Layout stehen die Notenanfänge eines Takts im Zeitabstand: Geschwindigkeit aus den Notenspalten
    // selbst (dann steht der Balken exakt über jeder Note); sonst bis zur ersten Note des nächsten Takts
    val ownRate = real.size >= 2 && real.last().first > real.first().first && (layout.photoFrame != null || nextFirst == null)
    val x1 = if (ownRate) x0 + (real.last().second - real.first().second) / (real.last().first - real.first().first) * (length - t0)
    else nextFirst ?: slots.last().second
    // Im Original-Layout exakt über jeder Note: Musste eine Note einem Vorzeichen oder Schlüssel ausweichen, läuft der
    // Balken zwischen den Notenspalten (dort kurz schneller/langsamer), sonst gleichmäßig
    if (ownRate && layout.photoFrame != null && tick >= real.first().first && tick <= real.last().first) {
        var i = 0
        while (i < real.size - 2 && real[i + 1].first <= tick) i++
        val (ta, xa) = real[i]; val (tb, xb) = real[i + 1]
        return xa + (xb - xa) * ((tick - ta) / (tb - ta).coerceAtLeast(1)).coerceIn(0f, 1f)
    }
    val f = ((tick - t0).toFloat() / (length - t0)).coerceIn(0f, 1f)
    return x0 + (x1 - x0) * f
}
