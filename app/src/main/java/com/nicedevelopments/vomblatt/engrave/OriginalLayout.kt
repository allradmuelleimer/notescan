package com.nicedevelopments.vomblatt.engrave

import com.nicedevelopments.vomblatt.model.Score
import com.nicedevelopments.vomblatt.model.SystemGeom
import com.nicedevelopments.vomblatt.model.TPQ
import kotlin.math.abs

/**
 * Umrechnung Foto → Notenbild für ein Stück einer Notenzeile im Original-Layout: gerade gezogen (Wölbung der Seite
 * über die oberste Notenlinie herausgerechnet) und gleichmäßig auf die Linienabstände des Notenbilds gebracht.
 * Jeder Punkt des Stücks hat damit genau eine Stelle im Notenbild. [px0]…[px1]: waagrechter Bereich im Foto.
 */
class PhotoMap(val g: SystemGeom, val system: Int, val s: Float, val originPx: Float, var ox: Float, var oy: Float, val px0: Float, val px1: Float, val first: Boolean, val last: Boolean, val line: Int = 0) {
    /** Oberkante des unteren Systems im Notenbild (nach dem Setzen). */
    var oy1: Float = Float.NaN
    /** Waagrechter Maßstab außerhalb der Stützpunkte. */
    var sx: Float = s
    /** Stützpunkte Foto-x → Notenbild-x (Taktstriche, Notenspalten), streng steigend; dazwischen linear. */
    var knotsP: FloatArray = FloatArray(0)
    var knotsA: FloatArray = FloatArray(0)
    fun x(px: Float): Float {
        val kp = knotsP; val ka = knotsA
        if (kp.size < 2) return ox + (px - originPx) * sx
        if (px <= kp[0]) return ka[0] - (kp[0] - px) * s
        if (px >= kp[kp.size - 1]) return ka[ka.size - 1] + (px - kp[kp.size - 1]) * s
        var i = 1
        while (kp[i] < px) i++
        return ka[i - 1] + (ka[i] - ka[i - 1]) * (px - kp[i - 1]) / (kp[i] - kp[i - 1])
    }
    /** Oberes System ab [oy], unteres ab [oy1] (Abstand der Systeme wie im Notenbild), dazwischen linear. */
    fun y(px: Float, py: Float): Float {
        val r = py - g.shiftAt(px)
        val lower = if (oy1.isNaN()) oy + (g.top1 - g.top0) * s else oy1
        val upperEnd = oy + (g.bot0 - g.top0) * s
        return when {
            r <= g.bot0 || g.top1 <= g.bot0 -> oy + (r - g.top0) * s
            r >= g.top1 -> lower + (r - g.top1) * s
            else -> upperEnd + (lower - upperEnd) * (r - g.bot0) / (g.top1 - g.bot0)
        }
    }
}

/**
 * Original-Layout eines Stücks: Vorgaben für den Engraver und die Umrechnungen – je Fotozeilen-Abschnitt einer
 * Notenbild-Zeile eine (eine Zeile kann Takte aus zwei Fotozeilen enthalten, [PhotoMap.line]).
 */
class PhotoFrame(val fixed: FixedLayout, val parts: List<PhotoMap>, val sp: Float) {
    /** Nach dem Setzen: Oberkanten aus dem fertigen Notenbild übernehmen. */
    fun bind(layout: Layout) {
        for (m in parts) layout.systems.getOrNull(m.line)?.let { m.oy = it.staffTops[0]; m.oy1 = it.staffTops[1] }
    }
}

object OriginalLayout {
    /**
     * Baut das Original-Layout. Standard: immer zwei Takte je Zeile (die letzte Zeile bei ungerader Zahl drei), auch
     * über Fotozeilen hinweg. [dynamic]: jede Fotozeile wird für sich in Stücke zu 2 oder 3 Takten geteilt.
     * Null, wenn ein Takt keine Fotoposition hat oder die Zeilen nicht in Reihenfolge vorkommen.
     */
    /** Abstand zweier Zeichen nebeneinander (Linienabstände). */
    private const val PAD = 0.4f
    /** Grundbreite je Viertel, solange die Zeichen nicht mehr Platz brauchen (Linienabstände). */
    private const val Q0 = 2.6f

    /**
     * Platzverteilung eines Takts (alles in Linienabständen): Notenanfänge [onsets]; vor dem ersten Kopf [lead];
     * je Abstand (der letzte reicht bis zum Taktstrich) Dauer in Vierteln, Mindestplatz der beiden Zeichen und
     * Mindestplatz aller gleich langen Abstände im Takt (gleicher Rhythmus = gleiche Abstände).
     */
    private class Spacing(val onsets: IntArray, val lead: Float, val dur: FloatArray, val min: FloatArray, val cls: FloatArray) {
        fun gap(i: Int, q: Float) = maxOf(q * dur[i], min[i], cls[i])
        fun width(q: Float): Float { var w = lead; for (i in dur.indices) w += gap(i, q); return w }
        /** Linke Kanten der Notenköpfe ab Taktanfang, damit der Takt genau [w] breit ist. Lange Werte geben zuerst
         *  Platz ab; reicht auch der Mindestplatz nicht, werden alle Abstände gleichmäßig enger. */
        fun place(w: Float): FloatArray {
            val g = FloatArray(dur.size)
            val w0 = width(0f)
            if (w0 >= w) {
                val f = ((w - lead) / (w0 - lead)).coerceAtLeast(0.2f)
                for (i in g.indices) g[i] = gap(i, 0f) * f
            } else {
                var lo = 0f; var hi = 1f
                while (width(hi) < w && hi < 1e4f) hi *= 2
                repeat(40) { val mid = (lo + hi) / 2; if (width(mid) < w) lo = mid else hi = mid }
                for (i in g.indices) g[i] = gap(i, lo)
            }
            val e = FloatArray(onsets.size)
            var x = lead
            for (i in onsets.indices) { e[i] = x; x += g[i] }
            return e
        }
    }

    private fun spacing(score: Score, mi: Int, unit: Engraver): Spacing {
        val m = score.measures[mi]
        val len = m.length.coerceAtLeast(1)
        val on = m.chords.filter { !it.wholeMeasureRest }.map { it.start }.distinct().sorted().ifEmpty { listOf(0) }
        val ext = unit.slotExtents(m)
        val hw = unit.headWidth
        fun e(t: Int) = ext[t] ?: floatArrayOf(0f, hw, 0f)
        val n = on.size
        val dur = FloatArray(n) { i -> ((on.getOrNull(i + 1) ?: len) - on[i]).toFloat() / TPQ }
        val base = FloatArray(n); val min = FloatArray(n)
        val endPad = 0.7f + if (m.repeatEnd || m.finalBar || mi == score.measures.size - 1) 1.6f else 0f
        for (i in 0 until n) {
            if (i < n - 1) {
                base[i] = e(on[i])[1] + e(on[i + 1])[0] + PAD
                min[i] = base[i] + e(on[i + 1])[2]
            } else { base[i] = e(on[i])[1] + endPad; min[i] = base[i] }
        }
        val cls = FloatArray(n) { i -> if (i == n - 1) 0f else (0 until n - 1).filter { dur[it] == dur[i] }.maxOf { base[it] } }
        val lead = NOTE_LEAD_SP + e(on[0])[0] + e(on[0])[2]
        return Spacing(on.toIntArray(), lead, dur, min, cls)
    }

    fun build(score: Score, systems: List<SystemGeom>, width: Float, topPad: Float, spTarget: Float, dynamic: Boolean = false, unit: Engraver, marginOf: (Float) -> Float): PhotoFrame? {
        if (systems.isEmpty() || score.measures.isEmpty()) return null
        class Anchor(val tick: Int, val x: Float, val y: Float)
        val anchors0 = score.measures.map { m ->
            m.chords.filter { c -> c.notes.any { it.imgX != null && it.imgY != null } }.groupBy { it.start }.map { (t, cs) ->
                val ns = cs.flatMap { it.notes }.filter { it.imgX != null && it.imgY != null }
                Anchor(t, ns.map { it.imgX!! }.average().toFloat(), ns.map { it.imgY!! }.average().toFloat())
            }.sortedBy { it.tick }
        }
        // Takte ohne Noten (Ganztaktpausen, Mehrtaktpausen – homr gibt ihnen keine Fotoposition): Lage aus dem
        // vorigen Takt und den erkannten Taktstrichen danach (Bohemian T. 134–137); ohne Nachbarn freier Satz
        val filled = anchors0.toMutableList()
        for (mi in filled.indices) {
            if (filled[mi].isNotEmpty()) continue
            val prev = (mi - 1 downTo 0).firstOrNull { anchors0[it].isNotEmpty() }
            val next = (mi + 1 until anchors0.size).firstOrNull { anchors0[it].isNotEmpty() }
            val ref = prev ?: next ?: break
            val y = anchors0[ref].map { it.y }.average().toFloat()
            val g = systems.minByOrNull { gg -> if (y in gg.top0 - gg.ls * 3..gg.bot1 + gg.ls * 3) 0f else minOf(abs(y - gg.top0), abs(y - gg.bot1)) } ?: break
            val x = if (prev != null) {
                val from = anchors0[prev].maxOf { it.x } + 0.7f * g.ls
                val bars = g.bars.sorted().filter { it > from && it < g.right - 0.5f * g.ls }
                val k = mi - prev // 1 = erster leerer Takt nach dem vorigen
                val left = bars.getOrNull(k - 1); val right = bars.getOrNull(k) ?: g.right
                if (left != null) (left + right) / 2 else from + (g.right - from) * k / (k + 1f)
            } else anchors0[next!!].minOf { it.x } - 3f * g.ls * (next - mi)
            filled[mi] = listOf(Anchor(0, x, y))
        }
        if (filled.any { it.isEmpty() }) {
            android.util.Log.d("VomBlatt", "Original-Layout: Takt ohne Fotoposition – freier Satz")
            return null
        }
        val anchors = filled
        val sysOf = IntArray(score.measures.size) { mi ->
            val y = anchors[mi].map { it.y }.average().toFloat()
            systems.indices.minByOrNull { i -> val g = systems[i]; if (y in g.top0 - g.ls * 3..g.bot1 + g.ls * 3) 0f else minOf(abs(y - g.top0), abs(y - g.bot1)) }!!
        }
        for (i in 1 until sysOf.size) if (sysOf[i] < sysOf[i - 1]) {
            android.util.Log.d("VomBlatt", "Original-Layout: Zeilen nicht in Reihenfolge – freier Satz")
            return null
        }
        val used = sysOf.distinct()
        val maxFifths = score.measures.maxOf { abs(it.fifths) }
        // Kopf einer Folgezeile (Klammer, Schlüssel, Vorzeichen) in Linienabständen
        val rowHeadSp = headSp(maxFifths, false) + 0.6f

        // Taktgrenzen im Foto je Zeile: erkannte Taktstriche zwischen letzter Note des einen und erster des nächsten Takts
        class Row(val gi: Int, val ms: List<Int>, val start: Float, val bars: FloatArray, val end: Float)
        val rows = used.map { gi ->
            val g = systems[gi]
            val ms = sysOf.indices.filter { sysOf[it] == gi }
            val bars = g.bars.sorted()
            val inner = FloatArray(ms.size - 1) { k ->
                val last = anchors[ms[k]].maxOf { it.x }; val first = anchors[ms[k + 1]].minOf { it.x }
                val cand = bars.filter { it > last + 0.7f * g.ls && it < first - 0.5f * g.ls }
                cand.minByOrNull { abs(it - (first - 1.3f * g.ls)) } ?: maxOf((last + first) / 2, first - 1.3f * g.ls)
            }
            Row(gi, ms, g.left, inner, g.right)
        }

        // Aufteilung in Notenbild-Zeilen; jede Zeile besteht aus Abschnitten je Fotozeile (Takte ms[k0 until k1])
        var margin = marginOf(spTarget)
        val avail = width - 2 * margin
        class Seg(val row: Row, val k0: Int, val k1: Int)
        fun startPx(r: Row, k0: Int) = if (k0 == 0) r.start else r.bars[k0 - 1]
        fun endPx(r: Row, k1: Int) = if (k1 == r.ms.size) r.end else r.bars[k1 - 1]
        fun firstNote(r: Row) = anchors[r.ms.first()].minOf { it.x } - 1.5f * systems[r.gi].ls
        fun spFor(line: List<Seg>): Float {
            // avail = Breite der Abschnitte im Foto in Linienabständen + Kopf (Schlüssel/Vorzeichen), wenn die Zeile
            // nicht mit einem Fotozeilenanfang beginnt (dort steht der Kopf schon im Foto)
            var w = 0f
            line.forEachIndexed { i, sg ->
                val g = systems[sg.row.gi]
                val st = if (sg.k0 == 0 && i > 0) firstNote(sg.row) else startPx(sg.row, sg.k0)
                w += (endPx(sg.row, sg.k1) - st) / g.ls
            }
            return avail / (w + if (line.first().k0 > 0) rowHeadSp else 0f)
        }
        val lines = ArrayList<List<Seg>>()
        if (!dynamic) {
            // Gideon: immer zwei Takte je Zeile, über Fotozeilen hinweg; bei ungerader Zahl hat die letzte Zeile drei
            val all = rows.flatMap { r -> r.ms.indices.map { r to it } }
            val groups = all.chunked(2).toMutableList()
            if (groups.size > 1 && groups.last().size == 1) {
                val tail = groups.removeAt(groups.size - 1)
                groups[groups.size - 1] = groups[groups.size - 1] + tail
            }
            for (gr in groups) {
                val segs = ArrayList<Seg>()
                for ((r, k) in gr) {
                    val lastSeg = segs.lastOrNull()
                    if (lastSeg != null && lastSeg.row === r && lastSeg.k1 == k) segs[segs.size - 1] = Seg(r, lastSeg.k0, k + 1)
                    else segs.add(Seg(r, k, k + 1))
                }
                lines.add(segs)
            }
        } else for (r in rows) {
            // je Fotozeile so wenige Stücke wie möglich, solange der Linienabstand nicht unter 90 % des Ziels fällt;
            // nie nur ein Takt in einer Zeile (außer die Fotozeile hat nur einen), 3 bleibt 3, 5 → 2 + 3
            val n = r.ms.size
            var cnt = 1
            fun split(c: Int): List<Pair<Int, Int>> {
                val s0 = r.start; val s1 = r.end
                val cuts = (1 until c).map { j ->
                    val target = s0 + (s1 - s0) * j / c
                    (1 until n).minByOrNull { abs(r.bars[it - 1] - target) }!!
                }.distinct().sorted()
                return (listOf(0) + cuts + listOf(n)).zipWithNext()
            }
            while (cnt < n && split(cnt).minOf { (a, b) -> spFor(listOf(Seg(r, a, b))) } < 0.9f * spTarget) cnt++
            cnt = cnt.coerceAtMost(maxOf(1, n / 2))
            val base = n / cnt; val extra = n % cnt
            var at = 0
            for (k in 0 until cnt) {
                val size = base + if (k >= cnt - extra) 1 else 0
                lines.add(listOf(Seg(r, at, at + size))); at += size
            }
        }
        // Notengröße nach dem Inhalt: so groß, dass in jeder üblichen Zeile (bis zwei Takte) der dichteste Takt bei
        // gleichmäßigem Zeitabstand noch passt (Mindestabstand der Notenanfänge, Platz für Vorzeichen und Wechsel).
        // Dreiergruppen bekommen dafür schmalere Takte. Höchstens 1,5 × die Größe des freien Satzes.
        fun count(line: List<Seg>) = line.sumOf { it.k1 - it.k0 }
        val usual = lines.filter { count(it) <= 2 }.ifEmpty { lines }
        val headerSp = headSp(maxFifths, true)
        val spaces = score.measures.indices.map { spacing(score, it, unit) }
        fun chgSp(mi: Int, inLine: Int) =
            if (inLine > 0) changeSp(score.measures.getOrNull(mi - 1), score.measures[mi]).let { if (it > 0f) it + 1f else 0f } else 0f
        // Platzbedarf eines Takts: Grundbreite je Viertel, mindestens so viel, wie die Zeichen brauchen
        fun needSp(mi: Int, inLine: Int): Float = chgSp(mi, inLine) + spaces[mi].width(Q0)
        val k = marginOf(1f) - marginOf(0f)
        fun spContent(line: List<Seg>): Float {
            val ms = line.flatMap { sg -> sg.row.ms.subList(sg.k0, sg.k1) }
            val need = ms.mapIndexed { i, mi -> needSp(mi, i) }.max()
            return (width - 2 * marginOf(0f)) / (2 * k + headerSp + ms.size * need)
        }
        // Ausreißer (Bohemian T. 50/51: Sechzehnteltriolen, 27 Notenanfänge): Zeilen, die viel mehr Platz brauchen als die
        // üblichen, bekommen je einen Takt (die „absolut nötige“ Ausnahme); passt ein Takt auch allein nicht, rücken seine
        // Noten dort enger (Notensatz), statt das ganze Stück zu verkleinern
        val med = usual.map { spContent(it) }.sorted().let { it[it.size / 2] }
        run {
            val split = ArrayList<List<Seg>>()
            for (line in lines) {
                if (count(line) >= 2 && spContent(line) < 0.7f * med) {
                    for (sg in line) for (q in sg.k0 until sg.k1) split.add(listOf(Seg(sg.row, q, q + 1)))
                } else split.add(line)
            }
            lines.clear(); lines.addAll(split)
        }
        val fitting = lines.filter { count(it) <= 2 && spContent(it) >= 0.7f * med }.ifEmpty { usual }
        val sp = minOf(spTarget * 1.5f, fitting.minOf { spContent(it) })
        margin = marginOf(sp)

        // Takte gleich breit, Anfang/Mitte/Ende in allen Zeilen auf derselben Linie; innerhalb eines Takts nach Dauer
        // verteilt (gleicher Rhythmus = gleiche Abstände), mindestens so weit, wie die Zeichen brauchen. Der Zeitbalken
        // läuft zwischen den Notenspalten. Die Umrechnung für Zeichnungen ist an Taktstrichen und Notenspalten
        // festgemacht (dazwischen linear).
        val maps = ArrayList<PhotoMap>()
        val rightEdge = width - margin
        val contentStart = margin + sp * headerSp
        val hwPx = unit.headWidth * sp
        val fixedSystems = lines.mapIndexed { li, line ->
            val ms = line.flatMap { sg -> sg.row.ms.subList(sg.k0, sg.k1) }
            val wM = (rightEdge - contentStart) / ms.size
            val barX = FloatArray(ms.size + 1) { contentStart + it * wM }
            // Der erste Takt beginnt direkt nach dem Kopf dieser Zeile (ohne Taktart bzw. mit weniger Vorzeichen ist
            // er kürzer); der gewonnene Platz gehört den Noten des ersten Takts – die Taktstriche bleiben in allen
            // Zeilen übereinander
            val m0 = ms.first()
            val headTime = m0 == 0 || score.measures[m0 - 1].let { it.beats != score.measures[m0].beats || it.beatType != score.measures[m0].beatType }
            barX[0] = minOf(barX[0], margin + sp * headSp(score.measures[m0].fifths, headTime))
            val slots = HashMap<Int, Map<Int, Float>>()
            var k = 0 // Takt in der Zeile
            line.forEachIndexed { si, sg ->
                val g = systems[sg.row.gi]
                val s = sp / g.ls
                val px0 = startPx(sg.row, sg.k0); val px1 = endPx(sg.row, sg.k1)
                // offen = Zeichnungen dürfen über diese Seite hinausragen (Anfang/Ende von Foto- und Notenbild-Zeile)
                val openL = si == 0 && sg.k0 == 0; val openR = si == line.size - 1 && sg.k1 == sg.row.ms.size
                val map = PhotoMap(g, sg.row.gi, s, px0, barX[k], 0f, px0, px1, openL, openR, li)
                val kp = ArrayList<Float>(); val ka = ArrayList<Float>()
                fun knot(ppx: Float, ax: Float) {
                    if (kp.isEmpty() || (ppx > kp.last() + 0.5f && ax > ka.last() + 0.5f)) { kp.add(ppx); ka.add(ax) }
                }
                for (q in sg.k0 until sg.k1) {
                    val mi = sg.row.ms[q]
                    val bStart = barX[k]
                    knot(if (q == 0) firstNote(sg.row) else sg.row.bars[q - 1], bStart)
                    // Schlüssel-/Tonart-/Taktartwechsel mitten in der Zeile: Platz vor der ersten Note (am Zeilenanfang
                    // stehen sie im Kopf)
                    val chg = chgSp(mi, k)
                    // Notenanfänge nach Dauer verteilt; wo Zeichen mehr Platz brauchen (Sekunden-Akkorde, Vorzeichen,
                    // Fähnchen), geben die langen Werte im selben Takt Platz ab – nie Köpfe übereinander
                    val sc = spaces[mi]
                    val edges = sc.place((barX[k + 1] - bStart) / sp - chg)
                    val centers = HashMap<Int, Float>()
                    sc.onsets.forEachIndexed { i, t -> centers[t] = bStart + (chg + edges[i]) * sp + hwPx / 2 }
                    for (a in anchors[mi]) knot(a.x, centers[a.tick] ?: continue)
                    slots[mi] = centers
                    k++
                }
                knot(px1, barX[k])
                map.knotsP = kp.toFloatArray(); map.knotsA = ka.toFloatArray()
                maps.add(map)
            }
            val g0 = systems[line.first().row.gi]
            FixedSystem(ms.first()..ms.last(), margin, topPad, (g0.top1 - g0.top0) * sp / g0.ls, barX, slots)
        }
        android.util.Log.d("VomBlatt", "Original-Layout: ${rows.size} Fotozeilen → ${lines.size} Zeilen (${if (dynamic) "2–3" else "je 2"}), sp %.1f (Ziel %.1f)".format(sp, spTarget))
        return PhotoFrame(FixedLayout(fixedSystems), maps, sp)
    }
}
