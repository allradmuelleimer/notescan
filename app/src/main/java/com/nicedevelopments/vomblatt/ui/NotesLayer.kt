package com.nicedevelopments.vomblatt.ui

import android.graphics.Bitmap
import com.nicedevelopments.vomblatt.engrave.Layout
import com.nicedevelopments.vomblatt.model.Score
import com.nicedevelopments.vomblatt.model.SystemGeom
import kotlin.math.abs
import kotlin.math.roundToInt

/** Eine eingepasste Notiz: das unveränderte Bild der Zeichnung und wohin es im Notenbild kommt. */
class NotesPiece(val bitmap: Bitmap, val matrix: android.graphics.Matrix, val meshW: Int = 0, val meshH: Int = 0, val verts: FloatArray? = null, val clip: android.graphics.RectF? = null)

/**
 * Eine Zeichnung als Ganzes (Kreis, Pfeil, Wort): alle Striche, die nah beieinander liegen. [bitmap] sind genau ihre
 * Pixel aus der Notiz-Ebene, [l]…[b] ihr Rahmen im Foto, [system] die Fotozeile, [first]…[last] die Takte darunter.
 */
class NoteMark(val bitmap: Bitmap, val l: Float, val t: Float, val r: Float, val b: Float, val system: Int, val first: Int, val last: Int, val scale: Float)

private class Anchor(val tick: Int, val x: Float, val y: Float)

/** Lage eines Takts im Foto: Fotozeile, waagrechter Bereich (Taktstrich bis Taktstrich), Notenspalten. */
private class PhotoMeasure(val index: Int, val system: Int, val x0: Float, val x1: Float, val anchors: List<Anchor>)

private fun photoMeasures(systems: List<SystemGeom>, score: Score): List<PhotoMeasure?> {
    val perMeasure = score.measures.map { m ->
        m.chords.filter { c -> c.notes.any { it.imgX != null && it.imgY != null } }.groupBy { it.start }.map { (start, cs) ->
            val ns = cs.flatMap { it.notes }.filter { it.imgX != null && it.imgY != null }
            Anchor(start, ns.map { it.imgX!! }.average().toFloat(), ns.map { it.imgY!! }.average().toFloat())
        }.sortedBy { it.x }
    }
    val sys = perMeasure.map { a ->
        if (a.isEmpty()) null else {
            val y = a.map { it.y }.average().toFloat()
            systems.indices.minByOrNull { i -> val g = systems[i]; if (y in g.top0 - g.ls * 3..g.bot1 + g.ls * 3) 0f else minOf(abs(y - g.top0), abs(y - g.bot1)) }
        }
    }
    fun startOf(k: Int): Float {
        val g = systems[sys[k]!!]
        val first = perMeasure[k].first().x
        val samePrev = k > 0 && sys[k - 1] == sys[k] && perMeasure[k - 1].isNotEmpty()
        // Taktstrich etwa 1,3 Linienabstände vor der ersten Note; am Zeilenanfang samt Schlüssel und Rand
        return if (samePrev) maxOf(first - 1.3f * g.ls, (perMeasure[k - 1].last().x + first) / 2)
        else minOf(g.left - 2 * g.ls, first - 6 * g.ls)
    }
    return score.measures.indices.map { k ->
        val si = sys[k] ?: return@map null
        val g = systems[si]
        val nextSame = k + 1 < score.measures.size && sys[k + 1] == si && perMeasure[k + 1].isNotEmpty()
        val x0 = startOf(k)
        val x1 = if (nextSame) startOf(k + 1) else maxOf(g.right + 2 * g.ls, perMeasure[k].last().x + 2 * g.ls)
        PhotoMeasure(k, si, x0, maxOf(x1, x0 + g.ls), perMeasure[k])
    }
}

/**
 * Die Notiz-Ebene in ganze Notizen zerlegen: benachbarte Striche (Abstand bis ~0,6 Linienabstände, so breit
 * wie eine Notenlinie samt Rand) gehören zusammen. Jede Notiz bekommt ihre Fotozeile (nach dem Tintenschwerpunkt)
 * und die Takte, über denen sie liegt.
 */
fun noteMarks(notes: Bitmap, scale: Float, systems: List<SystemGeom>, score: Score): List<NoteMark> {
    if (systems.isEmpty()) return emptyList()
    val pm = photoMeasures(systems, score)
    val w = notes.width; val h = notes.height
    val px = IntArray(w * h)
    notes.getPixels(px, 0, w, 0, 0, w, h)
    val ls = systems.map { it.ls }.sorted()[systems.size / 2] * scale
    // Raster: Zellen mit Tinte, um eine Zelle erweitert und zusammenhängend gezählt
    val c = maxOf(2, (ls * 0.4f).roundToInt())
    val gw = (w + c - 1) / c; val gh = (h + c - 1) / c
    val occ = BooleanArray(gw * gh)
    for (y in 0 until h) {
        val row = y * w; val grow = (y / c) * gw
        for (x in 0 until w) if ((px[row + x] ushr 24) > 40) occ[grow + x / c] = true
    }
    // um zwei Zellen erweitern: Lücken bis ~1,5 Linienabstände (Kreuzung mit Notenlinien, blasse Stellen) schließen
    val dil = BooleanArray(gw * gh)
    for (gy in 0 until gh) for (gx in 0 until gw) if (occ[gy * gw + gx]) {
        for (dy in -2..2) for (dx in -2..2) {
            val yy = gy + dy; val xx = gx + dx
            if (yy in 0 until gh && xx in 0 until gw) dil[yy * gw + xx] = true
        }
    }
    val lab = IntArray(gw * gh) { -1 }
    var n = 0
    val stack = IntArray(gw * gh)
    for (start in 0 until gw * gh) {
        if (!dil[start] || lab[start] >= 0) continue
        var sp = 0; stack[sp++] = start; lab[start] = n
        while (sp > 0) {
            val cell = stack[--sp]; val cy = cell / gw; val cx = cell % gw
            for (dy in -1..1) for (dx in -1..1) {
                val yy = cy + dy; val xx = cx + dx
                if (yy !in 0 until gh || xx !in 0 until gw) continue
                val k = yy * gw + xx
                if (dil[k] && lab[k] < 0) { lab[k] = n; stack[sp++] = k }
            }
        }
        n++
    }
    if (n == 0) return emptyList()
    val minX = IntArray(n) { Int.MAX_VALUE }; val minY = IntArray(n) { Int.MAX_VALUE }
    val maxX = IntArray(n) { -1 }; val maxY = IntArray(n) { -1 }
    val count = IntArray(n); val sumY = LongArray(n)
    for (y in 0 until h) {
        val row = y * w; val grow = (y / c) * gw
        for (x in 0 until w) {
            if ((px[row + x] ushr 24) <= 40) continue
            val g = lab[grow + x / c]
            if (g < 0) continue
            if (x < minX[g]) minX[g] = x; if (x > maxX[g]) maxX[g] = x
            if (y < minY[g]) minY[g] = y; if (y > maxY[g]) maxY[g] = y
            count[g]++; sumY[g] += y
        }
    }
    val out = ArrayList<NoteMark>()
    for (g in 0 until n) {
        if (count[g] < 20) continue
        val bx0 = minX[g]; val by0 = minY[g]; val bw = maxX[g] - bx0 + 1; val bh = maxY[g] - by0 + 1
        // Krümel (kleiner als ~ein Linienabstand) sind Reste von Druck oder Schmutz, keine Notiz
        if (maxOf(bw, bh) < 0.9f * ls) continue
        // nur die Pixel dieser Notiz (andere Notizen im selben Rechteck nicht doppelt zeichnen)
        val part = IntArray(bw * bh)
        for (y in 0 until bh) {
            val sy = by0 + y; val grow = (sy / c) * gw
            for (x in 0 until bw) {
                val sx = bx0 + x
                if (lab[grow + sx / c] == g) part[y * bw + x] = px[sy * w + sx]
            }
        }
        val l = bx0 / scale; val t = by0 / scale; val r = (bx0 + bw) / scale; val b = (by0 + bh) / scale
        val cyPhoto = sumY[g].toFloat() / count[g] / scale
        val si = systems.indices.minByOrNull { i -> val s = systems[i]; if (cyPhoto in s.top0..s.bot1) 0f else minOf(abs(cyPhoto - s.top0), abs(cyPhoto - s.bot1)) }!!
        val inSys = pm.filterNotNull().filter { it.system == si }
        if (inSys.isEmpty()) continue
        val over = inSys.filter { it.x0 < r && it.x1 > l }
        val span = over.ifEmpty { listOf(inSys.minByOrNull { minOf(abs(it.x0 - (l + r) / 2), abs(it.x1 - (l + r) / 2)) }!!) }
        out.add(NoteMark(Bitmap.createBitmap(part, bw, bh, Bitmap.Config.ARGB_8888), l, t, r, b, si, span.minOf { it.index }, span.maxOf { it.index }, scale))
    }
    return out
}

/** Taktgrenzen, an denen der Notensatz nicht umbrechen darf (k: zwischen Takt k und k+1 liegt eine Notiz). */
fun keepTogether(marks: List<NoteMark>): Set<Int> = marks.flatMap { (it.first until it.last).toList() }.toSet()

/**
 * Notizen ins Notenbild setzen – jede als unverändertes Bild, nichts geschnitten, nichts verzerrt: gleiche Größe im
 * Verhältnis zu den Notenlinien (Foto-Linienabstand → Notenbild-Linienabstand), gerade gerückt wie die Notenzeile,
 * und mit ihrer Mitte an dieselbe musikalische Stelle (waagrecht über Notenspalten und Taktstriche, senkrecht über
 * die Notenlinien).
 */
fun notesPieces(marks: List<NoteMark>, systems: List<SystemGeom>, score: Score, layout: Layout): List<NotesPiece> {
    if (systems.isEmpty() || marks.isEmpty()) return emptyList()
    val sp = layout.sp
    val pm = photoMeasures(systems, score)
    val box = layout.measures.associateBy { it.index }
    val pieces = mutableListOf<NotesPiece>()
    val frame = layout.photoFrame
    if (frame != null) {
        // Original-Layout: jeder Punkt der Zeichnung über dieselbe Umrechnung wie Notenlinien, Takte und Noten –
        // gerade gezogen, gleichmäßig auf den Linienabstand gebracht, sonst unverändert
        for (mk in marks) for (map in frame.parts) {
            if (map.system != mk.system) continue
            // Teilstück der Fotozeile: Zeichnungen, die über den Schnitt ragen, erscheinen in beiden Zeilen, jede Kopie
            // bis zum Rand ihrer Zeile (kleine Überstände bis 1,5 Linienabstände bleiben in einer Zeile ganz)
            val tol = 1.5f * map.g.ls
            if (!(mk.r > map.px0 + (if (map.first) -1e9f else tol) && mk.l < map.px1 - (if (map.last) -1e9f else tol))) continue
            val mw = ((mk.r - mk.l) / map.g.ls).toInt().coerceIn(2, 40)
            val mh = ((mk.b - mk.t) / map.g.ls).toInt().coerceIn(2, 40)
            val verts = FloatArray((mw + 1) * (mh + 1) * 2)
            var n = 0
            for (j in 0..mh) for (i in 0..mw) {
                val px = mk.l + (mk.r - mk.l) * i / mw
                val py = mk.t + (mk.b - mk.t) * j / mh
                verts[n++] = map.x(px); verts[n++] = map.y(px, py)
            }
            val clip = if (map.first && map.last) null else android.graphics.RectF(
                if (map.first) -1e6f else map.x(map.px0 - tol), -1e6f,
                if (map.last) 1e6f else map.x(map.px1 + tol), 1e6f,
            )
            pieces.add(NotesPiece(mk.bitmap, android.graphics.Matrix(), mw, mh, verts, clip))
        }
        return pieces
    }
    for (mk in marks) {
        val g = systems[mk.system]
        val es = box[mk.first]?.let { layout.systems.getOrNull(it.system) } ?: continue
        val esIndex = box[mk.first]!!.system
        // Stützpunkte Foto-x → Notenbild-x: Taktanfänge und Notenspalten dieser Zeile
        val support = pm.filterNotNull().filter { it.system == mk.system && box[it.index]?.system == esIndex }.sortedBy { it.index }
        val xs = mutableListOf<Pair<Float, Float>>()
        fun add(px: Float, ex: Float) { if (xs.isEmpty() || (px > xs.last().first + 0.5f && ex > xs.last().second + 0.5f)) xs.add(px to ex) }
        for (pmk in support) {
            val mb = box[pmk.index] ?: continue
            val slots = layout.slotX[pmk.index] ?: emptyList()
            add(pmk.x0, mb.rect.left + sp * 0.2f)
            for (a in pmk.anchors) slots.firstOrNull { it.first == a.tick }?.second?.let { add(a.x, it + sp * 0.6f) }
        }
        support.lastOrNull()?.let { last -> box[last.index]?.let { if (xs.isNotEmpty()) add(last.x1, maxOf(it.rect.right - sp * 0.2f, xs.last().second + 1f)) } }
        if (xs.size < 2) continue
        val k = sp / g.ls
        fun fx(x: Float): Float {
            if (x <= xs.first().first) return xs.first().second - (xs.first().first - x) * k
            for (i in 0 until xs.size - 1) {
                val (a, ea) = xs[i]; val (b, eb) = xs[i + 1]
                if (x <= b) return ea + (eb - ea) * (x - a) / (b - a)
            }
            return xs.last().second + (x - xs.last().first) * k
        }
        val single = g.top1 - g.bot0 < g.ls
        val py = if (single) floatArrayOf(g.top0, g.bot0) else floatArrayOf(g.top0, g.bot0, g.top1, g.bot1)
        val ey = if (single) floatArrayOf(es.staffTops[0], es.staffTops[0] + 4 * sp)
        else floatArrayOf(es.staffTops[0], es.staffTops[0] + 4 * sp, es.staffTops[1], es.staffTops[1] + 4 * sp)
        fun fy(x: Float, y: Float): Float {
            val v = y - g.shiftAt(x)
            if (v <= py.first()) return ey.first() - (py.first() - v) * k
            for (i in 0 until py.size - 1) if (v <= py[i + 1]) {
                val span = (py[i + 1] - py[i]).coerceAtLeast(1f)
                return ey[i] + (ey[i + 1] - ey[i]) * (v - py[i]) / span
            }
            return ey.last() + (v - py.last()) * k
        }
        // Mitte der Zeichnung und Neigung der Notenzeile an dieser Stelle
        val cx = (mk.l + mk.r) / 2; val cy = (mk.t + mk.b) / 2
        val slope = (g.shiftAt(cx + g.ls) - g.shiftAt(cx - g.ls)) / (2 * g.ls)
        val deg = Math.toDegrees(kotlin.math.atan(slope.toDouble())).toFloat()
        val m = android.graphics.Matrix()
        m.setScale(1f / mk.scale, 1f / mk.scale)           // Bildpixel → Foto
        m.postTranslate(mk.l - cx, mk.t - cy)               // Mitte in den Ursprung
        m.postRotate(-deg)                                  // gerade rücken
        m.postScale(k, k)                                   // Foto-Linienabstand → Notenbild-Linienabstand
        // an die Stelle: am nächsten einzelnen Notenkopf festmachen (waagrecht UND senkrecht) und den Abstand dazu
        // exakt wie auf dem Papier halten
        var best: Triple<Float, Float, com.nicedevelopments.vomblatt.engrave.HeadHit>? = null
        var bestD = Float.MAX_VALUE
        for (mi in mk.first..mk.last) for (c in score.measures.getOrNull(mi)?.chords ?: emptyList()) c.notes.forEachIndexed { ni, n ->
            val nx = n.imgX ?: return@forEachIndexed; val ny = n.imgY ?: return@forEachIndexed
            val d = (nx - cx) * (nx - cx) + (ny - cy) * (ny - cy)
            if (d < bestD) layout.heads.firstOrNull { it.chordId == c.id && it.noteIndex == ni }?.let { hh -> bestD = d; best = Triple(nx, ny, hh) }
        }
        val b0 = best
        if (b0 != null) m.postTranslate(b0.third.x + (cx - b0.first) * k, b0.third.y + (cy - b0.second) * k)
        else m.postTranslate(fx(cx), fy(cx, cy))
        pieces.add(NotesPiece(mk.bitmap, m))
    }
    return pieces
}
