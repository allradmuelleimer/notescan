package com.nicedevelopments.vomblatt.engrave

import android.graphics.RectF
import com.nicedevelopments.vomblatt.model.Beam
import com.nicedevelopments.vomblatt.model.Chord
import com.nicedevelopments.vomblatt.model.Clef
import com.nicedevelopments.vomblatt.model.Measure
import com.nicedevelopments.vomblatt.model.NoteType
import com.nicedevelopments.vomblatt.model.Orn
import com.nicedevelopments.vomblatt.model.Score
import com.nicedevelopments.vomblatt.model.TPQ
import com.nicedevelopments.vomblatt.model.keyAlter
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** SMuFL-Codepunkte (Bravura). */
object G {
    const val BRACE = ""
    const val G_CLEF = ""
    const val F_CLEF = ""
    const val HEAD_WHOLE = ""
    const val HEAD_HALF = ""
    const val HEAD_BLACK = ""
    const val REST_WHOLE = ""
    const val REST_HALF = ""
    const val REST_QUARTER = ""
    const val REST_8 = ""
    const val REST_16 = ""
    const val REST_32 = ""
    const val FLAG_8_UP = ""
    const val FLAG_8_DOWN = ""
    const val FLAG_16_UP = ""
    const val FLAG_16_DOWN = ""
    const val FLAG_32_UP = ""
    const val FLAG_32_DOWN = ""
    const val FLAT = ""
    const val NATURAL = ""
    const val SHARP = ""
    const val DOUBLE_SHARP = ""
    const val DOUBLE_FLAT = ""
    const val DOT = ""
    const val ACCENT_ABOVE = ""
    const val ACCENT_BELOW = ""
    const val STACCATO_ABOVE = ""
    const val STACCATO_BELOW = ""
    const val REPEAT_DOTS = ""
    const val TENUTO_ABOVE = "\uE4A4"
    const val TENUTO_BELOW = "\uE4A5"
    const val STACCATISSIMO_ABOVE = "\uE4A8"
    const val STACCATISSIMO_BELOW = "\uE4A9"
    const val MARCATO_ABOVE = "\uE4AC"
    const val MARCATO_BELOW = "\uE4AD"
    const val FERMATA_ABOVE = "\uE4C0"
    const val FERMATA_BELOW = "\uE4C1"
    const val BREATH = "\uE4CE"
    const val TRILL = "\uE566"
    const val TURN = "\uE567"
    const val TURN_INV = "\uE568"
    const val PRALL = "\uE56C"
    const val MORDENT = "\uE56D"
    const val OTTAVA_ALTA = "\uE511"
    const val OTTAVA_BASSA = "\uE51C"
    fun timeDigit(d: Int) = (0xE080 + d).toChar().toString()
    fun tupletDigit(d: Int) = (0xE880 + d).toChar().toString()
    fun fingerDigit(d: Int) = (0xED10 + d).toChar().toString()
    /** Dynamikbuchstaben p m f r s z n als Bravura-Zeichen. */
    fun dynamic(text: String) = text.map { ch -> (0xE520 + "pmfrszn".indexOf(ch)).toChar() }.joinToString("")
}

/** MUTED = graue Beschriftung (Taktzahlen), EXPR = kursive Vortragsangabe, PLAIN = Akkordsymbol, BOLD = Tempo/Formteil. */
enum class TextStyle { MUTED, EXPR, PLAIN, BOLD, BOLD_EXPR }

sealed class Op {
    abstract val chordId: Long
    data class Glyph(val text: String, val x: Float, val y: Float, val scale: Float = 1f, override val chordId: Long = -1, val scaleY: Float = 1f) : Op()
    data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val width: Float, override val chordId: Long = -1) : Op()
    data class Poly(val pts: FloatArray, override val chordId: Long = -1) : Op()
    data class Curve(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val up: Boolean, val thickness: Float, override val chordId: Long = -1) : Op()
    /** Text in der Oberflächenschrift; [style] bestimmt Farbe, Schnitt und Lage. */
    data class Label(val text: String, val x: Float, val y: Float, val size: Float, override val chordId: Long = -1, val style: TextStyle = TextStyle.MUTED) : Op()
}

data class HeadHit(val chordId: Long, val noteIndex: Int, val measure: Int, val rect: RectF, val x: Float, val y: Float)
/**
 * Notensatz im Original-Layout (Zeilen, Taktstriche, Notenspalten wie auf dem Foto, gerade gezogen).
 * Je Zeile: Takte [measures], Systemanfang [x0], gewünschte Oberkante des oberen Systems [prefTop0] (Seitenabstände
 * wie auf dem Foto), Abstand obere Linie oben → obere Linie unten [gap01], Taktgrenzen [barX] (Größe = Taktzahl + 1)
 * und je Takt die Kopfmitte je Notenanfang (Tick → x).
 */
class FixedSystem(
    val measures: IntRange, val x0: Float, val prefTop0: Float, val gap01: Float,
    val barX: FloatArray, val slotCenter: Map<Int, Map<Int, Float>>,
)

class FixedLayout(val systems: List<FixedSystem>)

data class MeasureBox(val index: Int, val system: Int, val rect: RectF, val contentX: Float, val contentRight: Float)
data class SystemBox(val top: Float, val bottom: Float, val staffTops: FloatArray, val x0: Float, val x1: Float)

class Layout(
    val width: Float,
    val height: Float,
    val ops: List<Op>,
    val heads: List<HeadHit>,
    val measures: List<MeasureBox>,
    val systems: List<SystemBox>,
    /** x-Position je Takt und Tick – für den mitlaufenden Cursor. */
    val slotX: Map<Int, List<Pair<Int, Float>>>,
    val sp: Float,
) {
    /** Gesetzt, wenn im Original-Layout des Fotos gesetzt wurde: Umrechnung Foto → Notenbild. */
    var photoFrame: PhotoFrame? = null
}

/**
 * Setzt eine Partitur für Klavier (zwei Systeme) auf eine feste Breite.
 * [measureGlyph] liefert die Breite eines Glyphs bei Schriftgröße 4 sp (= 1 em).
 */
/** Platz vor einer Note für einen Schlüsselwechsel mitten im Takt (kleiner Schlüssel, Maßstab 0,62), in Linienabständen. */
const val CLEF_CHANGE_SP = 1.8f

/** Zeilenkopf in Linienabständen: Klammer → Schlüssel, Schlüssel, Abstand nach der Tonart, Taktart. */
const val HEAD_LEAD_SP = 1.0f
const val HEAD_CLEF_SP = 3.1f
const val HEAD_KEY_PAD_SP = 0.5f
const val HEAD_TIME_SP = 2.3f
/** Taktanfang (Taktstrich bzw. Ende des Zeilenkopfs) → linke Kante des ersten Notenkopfs. */
const val NOTE_LEAD_SP = 1.1f

/** Breite des Zeilenkopfs (ohne Klammer davor) in Linienabständen. */
fun headSp(fifths: Int, time: Boolean): Float =
    HEAD_LEAD_SP + HEAD_CLEF_SP + abs(fifths) * 1.05f + HEAD_KEY_PAD_SP + if (time) HEAD_TIME_SP else 0f

/** Platz für Wechsel am Taktanfang in Linienabständen – gemeinsam für freien Satz und Original-Layout. */
fun changeSp(clef: Boolean, key: Boolean, time: Boolean, fifths: Int): Float =
    (if (clef) 2.4f else 0f) + (if (key) abs(fifths).coerceAtLeast(1) * 1.05f + 0.6f else 0f) + (if (time) 2.4f else 0f)

/** Wechsel am Anfang von Takt [m] gegenüber [prev] in Linienabständen. */
fun changeSp(prev: Measure?, m: Measure): Float = if (prev == null) 0f else changeSp(
    prev.clefs[0] != m.clefs[0] || prev.clefs[1] != m.clefs[1], prev.fifths != m.fifths,
    prev.beats != m.beats || prev.beatType != m.beatType, m.fifths,
)

class Engraver(
    private val sp: Float,
    private val measureGlyph: (String) -> Float,
    /** Breite eines Texts in der Oberflächenschrift (Größe in Pixeln). */
    private val measureText: (String, Float, TextStyle) -> Float = { t, size, _ -> t.length * size * 0.55f },
) {

    private companion object {
        /** Zeichen, die über dem oberen System Platz brauchen. */
        val ABOVE_STAFF = setOf(Orn.TRILL, Orn.PRALL, Orn.MORDENT, Orn.TURN, Orn.TURN_INV, Orn.FERMATA)
    }

    private val headW by lazy { measureGlyph(G.HEAD_BLACK) }
    /** Breite eines schwarzen Notenkopfs. */
    val headWidth: Float get() = headW
    private val wholeW by lazy { measureGlyph(G.HEAD_WHOLE) }

    private fun half(p: Int) = p * sp / 2f

    private fun bottomLine(clef: Clef) = if (clef == Clef.TREBLE) 30 else 18

    private fun pos(diatonic: Int, clef: Clef) = diatonic - bottomLine(clef)

    /** Notierte Stammtonnummer: unter 8va eine Oktave tiefer geschrieben als sie klingt. */
    private fun written(c: Chord, diatonic: Int) = diatonic - 7 * c.ottava

    /** Halsrichtung und Ausdehnung je Akkord – für Bögen und Oktavklammern. */
    private class ChordInfo(val measure: Int, val staff: Int, val x: Float, val top: Float, val bottom: Float, val up: Boolean, val stemEnd: Float, val hasStem: Boolean) {
        /** Äußerster Punkt oben bzw. unten (Hals eingeschlossen). */
        val outerTop: Float get() = if (hasStem && up) min(top, stemEnd) else top
        val outerBottom: Float get() = if (hasStem && !up) max(bottom, stemEnd) else bottom
    }
    private val info = HashMap<Long, ChordInfo>()
    /** Original-Layout: gedruckte Fingersätze an ihrer Fotostelle relativ zum Kopf (sonst gestapelt). */
    private var photoFingers = false

    private fun xAt(slotMap: Map<Int, List<Pair<Int, Float>>>, m: Int, t: Int): Float? {
        val slots = slotMap[m] ?: return null
        var prev = slots.first()
        for (s in slots) {
            if (s.first >= t) {
                if (s.first == prev.first) return s.second
                val f = (t - prev.first).toFloat() / (s.first - prev.first)
                return prev.second + (s.second - prev.second) * f
            }
            prev = s
        }
        return slots.last().second
    }

    private fun yOf(staffTop: Float, p: Int) = staffTop + (8 - p) * sp / 2f

    /**
     * Zeitproportionaler Notensatz: Abstand wächst linear mit der Dauer (Viertel = 5,6 Linienabstände), damit der
     * Zeitbalken gleichmäßig läuft und trotzdem auf jeder klingenden Note sitzt. Sehr kurze Werte bekommen eine
     * Mindestbreite, damit die Köpfe nicht zusammenstoßen.
     */
    private fun spacing(dur: Int): Float = max(sp * 1.7f, sp * 5.6f * dur / TPQ)

    private class Slot(val tick: Int, var w: Float, var accW: Float = 0f, var x: Float = 0f)

    /** Platz für die Wechsel am Taktanfang (Schlüssel, Tonart, Taktart) in Linienabständen. */
    private fun changeWidth(pl: MeasurePlan): Float = sp * changeSp(
        pl.showClef.any { it }, pl.showKey, pl.showTime, pl.m.fifths,
    )

    private class MeasurePlan(
        val index: Int, val m: Measure, val slots: List<Slot>,
        val pre: Float, val post: Float, val showClef: BooleanArray, val showKey: Boolean, val showTime: Boolean,
    ) {
        val natural: Float get() = pre + post + slots.sumOf { (it.w + it.accW).toDouble() }.toFloat()
    }

    /** [keep]: Taktgrenzen k, an denen nicht umbrochen werden darf (zwischen Takt k und k+1 liegt eine Foto-Notiz). */
    fun layout(score: Score, width: Float, marginX: Float, topPad: Float, keep: Set<Int> = emptySet(), fixed: FixedLayout? = null): Layout {
        val ops = mutableListOf<Op>()
        val heads = mutableListOf<HeadHit>()
        val mboxes = mutableListOf<MeasureBox>()
        val sboxes = mutableListOf<SystemBox>()
        val slotMap = HashMap<Int, List<Pair<Int, Float>>>()
        if (score.measures.isEmpty()) return Layout(width, topPad * 2, ops, heads, mboxes, sboxes, slotMap, sp)
        info.clear()
        photoFingers = fixed != null

        // --- 1. Takte vorplanen
        val plans = score.measures.mapIndexed { i, m ->
            val prev = score.measures.getOrNull(i - 1)
            val onsets = m.chords.filter { !it.wholeMeasureRest }.map { it.start }.toSortedSet().toMutableList()
            if (onsets.isEmpty()) onsets.add(0)
            val slots = onsets.mapIndexed { k, t ->
                val next = onsets.getOrNull(k + 1) ?: m.length
                val shortest = m.chords.filter { it.start == t }.minOfOrNull { it.duration } ?: (next - t)
                val d = max(min(next - t, shortest.coerceAtLeast(next - t)), 3)
                Slot(t, spacing(if (m.chords.any { it.wholeMeasureRest } && onsets.size == 1) m.length else d))
            }
            // Platz für Vorzeichen und Punkte
            val acc = accidentals(m)
            for (s in slots) {
                if (m.chords.any { c -> c.start == s.tick && c.notes.indices.any { acc[c.id to it] != null } }) s.accW = sp * 1.2f
                val graces = m.chords.filter { it.start == s.tick }.maxOfOrNull { it.graces.size } ?: 0
                if (graces > 0) s.accW += sp * (0.6f + 1.5f * graces)
                if (m.chords.any { it.start == s.tick && Orn.ARPEGGIO in it.orn }) s.accW += sp * 1.1f
                if (m.clefChanges.any { it.tick == s.tick }) s.accW += sp * CLEF_CHANGE_SP
            }
            val clefChange = BooleanArray(2) { st -> prev != null && prev.clefs[st] != m.clefs[st] }
            val keyChange = prev != null && prev.fifths != m.fifths
            val timeChange = prev == null || prev.beats != m.beats || prev.beatType != m.beatType
            var pre = sp * NOTE_LEAD_SP
            if (clefChange.any { it }) pre += sp * 2.4f
            if (keyChange) pre += sp * (abs(m.fifths).coerceAtLeast(1) * 1.05f + 0.6f)
            if (timeChange && prev != null) pre += sp * 2.4f
            if (m.repeatStart) pre += sp * 1.4f
            MeasurePlan(i, m, slots, pre, sp * 0.5f + if (m.repeatEnd) sp * 1.4f else 0f, clefChange, keyChange, timeChange && prev != null)
        }

        // --- 2. Auf Systeme verteilen
        fun headerWidth(m: Measure, first: Boolean): Float {
            return sp * (0.4f + headSp(m.fifths, first))
        }
        val avail = width - 2 * marginX
        val systems = mutableListOf<List<MeasurePlan>>()
        var cur = mutableListOf<MeasurePlan>()
        var curW = 0f
        for (p in plans) {
            if (cur.isNotEmpty() && curW + p.natural > avail) {
                if ((p.index - 1) !in keep) {
                    systems.add(cur); cur = mutableListOf(); curW = headerWidth(p.m, p.showTime)
                } else {
                    // Eine Notiz überspannt diese Taktgrenze: früher umbrechen, wo es erlaubt ist – sonst die Zeile enger setzen
                    val j = (cur.size - 1 downTo 1).firstOrNull { (cur[it].index - 1) !in keep }
                    if (j != null) {
                        systems.add(cur.subList(0, j).toList())
                        cur = cur.subList(j, cur.size).toMutableList()
                        curW = headerWidth(cur.first().m, cur.first().showTime) + cur.sumOf { it.natural.toDouble() }.toFloat()
                    }
                }
            } else if (cur.isEmpty()) curW = headerWidth(p.m, systems.isEmpty() || p.showTime)
            cur.add(p); curW += p.natural
        }
        if (cur.isNotEmpty()) systems.add(cur)
        if (fixed != null) {
            // Original-Layout: Zeilen wie auf dem Foto
            systems.clear()
            for (fs in fixed.systems) systems.add(plans.filter { it.index in fs.measures })
        }

        // --- 3. Systeme setzen
        var y = topPad
        val accRecalc = HashMap<Int, Map<Pair<Long, Int>, String>>()
        systems.forEachIndexed { si, sys ->
            val firstM = sys.first().m
            // Taktart im Zeilenkopf: in der ersten Zeile und wenn sie sich am Zeilenanfang ändert
            val headTime = si == 0 || sys.first().showTime
            val header = headerWidth(firstM, headTime)
            val natural = sys.sumOf { it.natural.toDouble() }.toFloat()
            val stretchable = sys.sumOf { pl -> pl.slots.sumOf { it.w.toDouble() } }.toFloat()
            val isLast = si == systems.size - 1
            val target = avail - header
            var factor = if (stretchable > 0) (target - (natural - stretchable)) / stretchable else 1f
            if (isLast && factor > 1.5f) factor = 1f.coerceAtLeast(min(factor, 1.25f))

            // Vertikale Ausdehnung (Hilfslinien) bestimmen
            var upTop = 8; var upBottom = 0; var loTop = 8; var loBottom = 0
            for (pl in sys) for (c in pl.m.chords) for (n in c.notes) {
                val p = pos(written(c, n.pitch.diatonic), pl.m.clefAt(c.staff, c.start))
                if (c.staff == 0) { upTop = max(upTop, p); upBottom = min(upBottom, p) }
                else { loTop = max(loTop, p); loBottom = min(loBottom, p) }
            }
            val hasVolta = sys.any { it.m.volta != 0 }
            val alta = sys.any { pl -> pl.m.chords.any { it.staff == 0 && it.ottava > 0 } }
            val bassa = sys.any { pl -> pl.m.chords.any { it.staff == 1 && it.ottava < 0 } }
            val hasOrn = sys.any { pl -> pl.m.chords.any { c -> c.staff == 0 && c.orn.any { it in ABOVE_STAFF } } }
            val firstIdx = sys.first().index; val lastIdx = sys.last().index
            val hasChords = score.chordSymbols.any { it.measure in firstIdx..lastIdx }
            val hasWordsAbove = score.words.any { it.measure in firstIdx..lastIdx && it.kind.above }
            val hasWordsBelow = score.words.any { it.measure in firstIdx..lastIdx && (it.kind == com.nicedevelopments.vomblatt.model.WordKind.UNA_CORDA || it.kind == com.nicedevelopments.vomblatt.model.WordKind.TRE_CORDE) }
            val above = max(sp * 3.2f, half(upTop - 8) + sp * 4.2f) + (if (hasVolta) sp * 3.0f else 0f) +
                (if (hasChords) sp * 2.3f else 0f) + (if (hasWordsAbove) sp * 2.1f else 0f) +
                sp * 1.05f * (sys.flatMap { pl -> pl.m.chords.filter { it.staff == 0 } }.maxOfOrNull { c -> c.notes.count { it.finger != null } } ?: 0) +
                (if (alta) sp * 1.6f else 0f) + (if (hasOrn) sp * 1.2f else 0f)
            val gap = max(sp * 6.5f, half(-upBottom) + half(loTop - 8) + sp * 4.5f)
            val ornLow = sys.any { pl -> pl.m.chords.any { c -> c.staff == 1 && c.orn.any { it in ABOVE_STAFF } } }
            val below = max(sp * 3.2f, half(-loBottom) + sp * 4.2f) + (if (bassa) sp * 1.6f else 0f) + (if (ornLow) sp * 1.4f else 0f) +
                sp * 1.05f * (sys.flatMap { pl -> pl.m.chords.filter { it.staff == 1 } }.maxOfOrNull { c -> c.notes.count { it.finger != null } } ?: 0) +
                (if (hasWordsBelow) sp * 2.2f else 0f)
            val fs = fixed?.systems?.getOrNull(si)
            val top0 = if (fs != null) maxOf(fs.prefTop0, y + above) else y + above
            val top1 = if (fs != null) top0 + fs.gap01.coerceAtLeast(sp * 7f) else top0 + sp * 4 + gap
            val staffTop = floatArrayOf(top0, top1)
            val bottom = top1 + sp * 4 + below
            val x0 = fs?.x0 ?: marginX
            val systemOpsStart = ops.size

            // Systemklammer
            ops.add(Op.Line(x0, top0, x0, top1 + 4 * sp, sp * 0.16f))
            val braceH = top1 + 4 * sp - top0
            // gleichmäßig vergrößert wie im Druck – nur in der Höhe gestreckt wirkt sie platt
            val braceK = braceH / (4 * sp)
            ops.add(Op.Glyph(G.BRACE, x0 - sp * 0.3f - measureGlyph(G.BRACE) * braceK, top1 + 4 * sp, braceK))
            ops.add(Op.Label("${sys.first().index + 1}", x0, top0 - sp * 1.6f, sp * 1.25f))

            var x = x0 + sp * HEAD_LEAD_SP
            for (st in 0..1) clefGlyph(ops, firstM.clefs[st], x, staffTop[st], 1f)
            x += sp * HEAD_CLEF_SP
            for (st in 0..1) keySig(ops, firstM.fifths, firstM.clefs[st], x, staffTop[st])
            x += abs(firstM.fifths) * sp * 1.05f + sp * HEAD_KEY_PAD_SP
            if (headTime) {
                for (st in 0..1) timeSig(ops, firstM, x, staffTop[st])
                x += sp * HEAD_TIME_SP
            }
            // Platzverteilung auf die Takte
            sys.forEachIndexed { mi, pl ->
                val mStart = if (fs != null) (if (mi == 0) maxOf(x, fs.barX[0]) else fs.barX[mi]) else x
                val m = pl.m
                var cx = mStart
                // Taktanfang: Wiederholung, Schlüssel-, Tonart-, Taktartwechsel
                if (m.repeatStart) {
                    ops.add(Op.Line(cx + sp * 0.25f, top0, cx + sp * 0.25f, top1 + 4 * sp, sp * 0.5f))
                    ops.add(Op.Line(cx + sp * 0.85f, top0, cx + sp * 0.85f, top1 + 4 * sp, sp * 0.16f))
                    for (st in 0..1) ops.add(Op.Glyph(G.REPEAT_DOTS, cx + sp * 1.15f, staffTop[st] + 4 * sp))
                    cx += sp * 1.4f
                }
                if (pl.showClef.any { it } && mi > 0) {
                    for (st in 0..1) if (pl.showClef[st]) clefGlyph(ops, m.clefs[st], cx + sp * 0.3f, staffTop[st], 0.78f)
                    cx += sp * 2.4f
                } else if (pl.showClef.any { it }) {
                    // Wechsel fällt auf Systemanfang: Kopf zeigt bereits den neuen Schlüssel
                }
                if (pl.showKey && mi > 0) {
                    for (st in 0..1) keySig(ops, m.fifths, m.clefs[st], cx + sp * 0.3f, staffTop[st])
                    cx += abs(m.fifths).coerceAtLeast(1) * sp * 1.05f + sp * 0.6f
                }
                if (pl.showTime && mi > 0) {
                    for (st in 0..1) timeSig(ops, m, cx + sp * 0.3f, staffTop[st])
                    cx += sp * 2.4f
                }
                // Wechsel am Zeilenanfang stehen schon im Kopf – ihr Platz im Takt entfällt
                val headChanges = if (mi == 0) changeWidth(pl) else 0f
                val contentX = mStart + pl.pre - headChanges
                var sx = max(cx, contentX - sp * 0.0f).coerceAtLeast(mStart + sp * NOTE_LEAD_SP)
                val mEnd: Float
                if (fs != null) {
                    // Original-Layout: Köpfe an ihre Stelle auf dem Foto; Notenanfänge ohne Fotoposition (Pausen)
                    // nach der Zeit zwischen ihre Nachbarn
                    val end = fs.barX.getOrElse(mi + 1) { fs.barX.last() }
                    val known = fs.slotCenter[pl.index] ?: emptyMap()
                    val lo = sx; val hi = (end - pl.post - headW).coerceAtLeast(lo)
                    val pts = ArrayList<Pair<Int, Float>>()
                    pts.add(0 to lo - headW / 2)
                    for (sl in pl.slots) known[sl.tick]?.let { pts.add(sl.tick to it - headW / 2) }
                    pts.add(m.length to hi + headW)
                    // Notenanfänge ohne Fotoposition (Pausen, nicht erkannte Köpfe): im selben Zeitabstand wie die übrigen
                    val kn = pl.slots.mapNotNull { sl -> known[sl.tick]?.let { sl.tick to it - headW / 2 } }
                    fun at(t: Int): Float {
                        if (kn.size >= 2 && kn.last().first > kn.first().first) {
                            val r = (kn.last().second - kn.first().second) / (kn.last().first - kn.first().first)
                            return kn.first().second + r * (t - kn.first().first)
                        }
                        var a = pts.first(); var b = pts.last()
                        for (q in pts) { if (q.first <= t) a = q; if (q.first >= t) { b = q; break } }
                        if (a.first == b.first) return a.second
                        return a.second + (b.second - a.second) * (t - a.first) / (b.first - a.first)
                    }
                    var prev = Float.NEGATIVE_INFINITY
                    for (sl in pl.slots) {
                        val px = (known[sl.tick]?.minus(headW / 2) ?: at(sl.tick)).coerceIn(lo, hi)
                        // Vorzeichen, Arpeggio oder Schlüsselwechsel davor brauchen Platz – nur diese Note rückt nach rechts
                        sl.x = maxOf(px, prev + headW * 1.05f + sl.accW)
                        prev = sl.x
                    }
                    sx = maxOf(prev + headW, lo)
                    mEnd = maxOf(end, sx + pl.post)
                } else {
                    for (s in pl.slots) {
                        sx += s.accW
                        s.x = sx
                        sx += s.w * factor
                    }
                    mEnd = sx + pl.post
                }
                slotMap[pl.index] = pl.slots.map { it.tick to it.x } + (m.length to (mEnd - pl.post))
                mboxes.add(MeasureBox(pl.index, si, RectF(mStart, top0 - sp * 2, mEnd, top1 + 4 * sp + sp * 2), sx, mEnd))

                // Inhalte
                val acc = accRecalc.getOrPut(pl.index) { accidentals(m) }
                engraveMeasure(ops, heads, pl, staffTop, acc, if (fs != null) 1f else factor)

                // Taktstrich
                if (m.repeatEnd) {
                    for (st in 0..1) ops.add(Op.Glyph(G.REPEAT_DOTS, mEnd - sp * 1.55f, staffTop[st] + 4 * sp))
                    ops.add(Op.Line(mEnd - sp * 0.85f, top0, mEnd - sp * 0.85f, top1 + 4 * sp, sp * 0.16f))
                    ops.add(Op.Line(mEnd - sp * 0.25f, top0, mEnd - sp * 0.25f, top1 + 4 * sp, sp * 0.5f))
                } else if (m.finalBar || pl.index == score.measures.size - 1) {
                    ops.add(Op.Line(mEnd - sp * 0.85f, top0, mEnd - sp * 0.85f, top1 + 4 * sp, sp * 0.16f))
                    ops.add(Op.Line(mEnd - sp * 0.25f, top0, mEnd - sp * 0.25f, top1 + 4 * sp, sp * 0.5f))
                } else {
                    ops.add(Op.Line(mEnd, top0, mEnd, top1 + 4 * sp, sp * 0.16f))
                }
                x = mEnd
            }
            // Notenlinien bis zum tatsächlichen Systemende (zuerst zeichnen, damit sie unter den Noten liegen)
            val lines = mutableListOf<Op>()
            for (st in 0..1) for (l in 0..4) {
                val ly = staffTop[st] + l * sp
                lines.add(Op.Line(x0, ly, x, ly, sp * 0.11f))
            }
            ops.addAll(systemOpsStart, lines)
            sboxes.add(SystemBox(y, bottom, staffTop, x0, x))
            y = bottom
        }

        ties(ops, score, heads, sboxes, mboxes)
        slurs(ops, score, sboxes, mboxes)
        pedals(ops, score, sboxes, mboxes, slotMap)
        dynamics(ops, score, sboxes, mboxes, slotMap)
        hairpins(ops, score, sboxes, mboxes, slotMap)
        texts(ops, score, sboxes, mboxes, slotMap)
        voltas(ops, score, sboxes, mboxes)
        ottavas(ops, score, sboxes, mboxes)
        return Layout(width, y + topPad, ops, heads, mboxes, sboxes, slotMap, sp)
    }

    // ---------------------------------------------------------------- Kopfteile

    private fun clefGlyph(ops: MutableList<Op>, clef: Clef, x: Float, staffTop: Float, scale: Float) {
        if (clef == Clef.TREBLE) ops.add(Op.Glyph(G.G_CLEF, x, yOf(staffTop, 2), scale))
        else ops.add(Op.Glyph(G.F_CLEF, x, yOf(staffTop, 6), scale))
    }

    private fun keySig(ops: MutableList<Op>, fifths: Int, clef: Clef, x: Float, staffTop: Float) {
        // Positionen im Violinschlüssel (Stammtonnummern), Bass zwei Oktaven tiefer
        val sharps = intArrayOf(38, 35, 39, 36, 33, 37, 34) // F5 C5 G5 D5 A4 E5 H4
        val flats = intArrayOf(34, 37, 33, 36, 32, 35, 31)  // B4 Es5 As4 Des5 Ges4 Ces5 Fes4
        val shift = if (clef == Clef.BASS) -14 else 0
        val list = if (fifths > 0) sharps.take(fifths) else flats.take(-fifths)
        list.forEachIndexed { i, d ->
            val p = d + shift - bottomLine(clef)
            ops.add(Op.Glyph(if (fifths > 0) G.SHARP else G.FLAT, x + i * sp * 1.05f, yOf(staffTop, p)))
        }
    }

    private fun timeSig(ops: MutableList<Op>, m: Measure, x: Float, staffTop: Float) {
        fun num(n: Int, yy: Float) {
            val s = n.toString().map { G.timeDigit(it - '0') }.joinToString("")
            ops.add(Op.Glyph(s, x, yy))
        }
        num(m.beats, yOf(staffTop, 6))
        num(m.beatType, yOf(staffTop, 2))
    }

    // ---------------------------------------------------------------- Vorzeichen

    /** Welche Noten ein sichtbares Vorzeichen brauchen: (chordId, noteIndex) -> Glyph. */
    fun accidentals(m: Measure): Map<Pair<Long, Int>, String> {
        val res = HashMap<Pair<Long, Int>, String>()
        for (staff in 0..1) {
            val shown = HashMap<Int, Int>()
            for (c in m.chords.filter { it.staff == staff }.sortedBy { it.start }) {
                c.notes.forEachIndexed { i, n ->
                    val d = n.pitch.diatonic
                    val current = shown[d] ?: keyAlter(m.fifths, n.pitch.step)
                    if (n.tieStop) { shown[d] = n.pitch.alter; return@forEachIndexed }
                    if (n.pitch.alter != current) {
                        res[c.id to i] = when (n.pitch.alter) {
                            -2 -> G.DOUBLE_FLAT; -1 -> G.FLAT; 1 -> G.SHARP; 2 -> G.DOUBLE_SHARP; else -> G.NATURAL
                        }
                        shown[d] = n.pitch.alter
                    }
                }
            }
        }
        return res
    }

    /**
     * Platzbedarf je Notenanfang: wie weit Zeichen links bzw. rechts über die linke Kante des Notenkopfs hinausragen
     * (Vorzeichen, versetzte Köpfe bei Sekunden, Fähnchen, Punkte, Hilfslinien, Pausen) – nach denselben Regeln für
     * Halsrichtung und Balken wie beim Zeichnen. Für die Platzverteilung im Original-Layout.
     * Je Notenanfang: [0] links (Kopfform, Hilfslinien), [1] rechts, [2] links zusätzlich (Vorzeichen, Arpeggio,
     * Vorschläge, Schlüsselwechsel – nur dieser Abstand wird größer, nicht alle gleich langen).
     */
    fun slotExtents(m: Measure): Map<Int, FloatArray> {
        val res = HashMap<Int, FloatArray>()
        val acc = accidentals(m)
        fun at(t: Int) = res.getOrPut(t) { floatArrayOf(0f, headW, 0f) }
        for (staff in 0..1) {
            val chords = m.chords.filter { it.staff == staff && !it.wholeMeasureRest }
            val multiVoice = chords.map { it.voice }.distinct().size > 1
            val placed = mutableListOf<Placed>()
            for (c in chords) {
                val e = at(c.start)
                if (c.isRest) {
                    val g = when (c.type) {
                        NoteType.WHOLE -> G.REST_WHOLE; NoteType.HALF -> G.REST_HALF; NoteType.QUARTER -> G.REST_QUARTER
                        NoteType.EIGHTH -> G.REST_8; NoteType.SIXTEENTH -> G.REST_16; else -> G.REST_32
                    }
                    e[1] = max(e[1], measureGlyph(g) + if (c.dots > 0) sp * (0.4f + 0.5f * c.dots) else 0f)
                    continue
                }
                val ps = c.notes.map { pos(written(c, it.pitch.diatonic), m.clefAt(staff, c.start)) }
                val up = if (multiVoice) c.voice % 2 == 1 else (ps.max() - 4) <= (4 - ps.min())
                placed.add(Placed(c, 0f, ps, emptyList(), up))
            }
            val groups = beamGroups(placed, m)
            for (g in groups) if (!multiVoice) {
                val up = (g.maxOf { it.ps.max() } - 4) <= (4 - g.minOf { it.ps.min() })
                g.forEach { it.up = up }
            }
            for (p in placed) {
                val c = p.chord
                val w = if (c.type == NoteType.WHOLE) wholeW else headW
                val sorted = p.ps.sorted()
                val second = sorted.zipWithNext().any { (a, b) -> b - a == 1 }
                var l = 0f
                var r = w
                if (second) { if (p.up) r += w - sp * 0.08f else l += w - sp * 0.08f }
                if (sorted.first() < -1 || sorted.last() > 9) { l += sp * 0.4f; r += sp * 0.4f }
                var x = 0f
                val accs = c.notes.indices.mapNotNull { acc[c.id to it] }
                if (accs.isNotEmpty()) x += sp * 0.25f + accs.maxOf { measureGlyph(it) } + (if (accs.size > 1) sp * 1.0f else 0f)
                if (c.type != NoteType.WHOLE && c.type.beams > 0 && groups.none { p in it }) {
                    val f = if (c.type == NoteType.EIGHTH) G.FLAG_8_UP else G.FLAG_16_UP
                    r = max(r, (if (p.up) headW else sp * 0.1f) + measureGlyph(f))
                }
                if (c.dots > 0) r = max(r, (if (second && p.up) 2 * w else w) + sp * (0.35f + 0.5f * c.dots))
                if (Orn.ARPEGGIO in c.orn) x += sp * 1.1f
                if (c.graces.isNotEmpty()) x += sp * (0.6f + 1.5f * c.graces.size)
                val e = at(c.start)
                e[0] = max(e[0], l); e[1] = max(e[1], r); e[2] = max(e[2], x)
            }
        }
        for (ch in m.clefChanges) {
            val t = res.keys.filter { it >= ch.tick }.minOrNull() ?: continue
            res[t]!![2] += sp * (CLEF_CHANGE_SP + 0.1f)
        }
        return res
    }

    // ---------------------------------------------------------------- Takt-Inhalt

    private class Placed(val chord: Chord, val x: Float, val ps: List<Int>, val ys: List<Float>, var up: Boolean, var stemX: Float = 0f, var stemEnd: Float = 0f)

    private fun engraveMeasure(
        ops: MutableList<Op>, heads: MutableList<HeadHit>, pl: MeasurePlan,
        staffTop: FloatArray, acc: Map<Pair<Long, Int>, String>, factor: Float,
    ) {
        val m = pl.m
        val slotX = pl.slots.associate { it.tick to it.x }
        // Schlüsselwechsel mitten im Takt: kleiner Schlüssel vor dem Notenanfang (und vor dessen Vorzeichen)
        for (ch in m.clefChanges) {
            val slot = pl.slots.firstOrNull { it.tick >= ch.tick } ?: continue
            val hasAcc = m.chords.any { c -> c.start == slot.tick && c.staff == ch.staff && c.notes.indices.any { acc[c.id to it] != null } }
            clefGlyph(ops, ch.clef, slot.x - sp * (CLEF_CHANGE_SP + 0.1f + if (hasAcc) 1.1f else 0f), staffTop[ch.staff], 0.62f)
        }
        for (staff in 0..1) {
            val top = staffTop[staff]
            val chords = m.chords.filter { it.staff == staff }
            val multiVoice = chords.map { it.voice }.distinct().size > 1
            val placed = mutableListOf<Placed>()
            for (c in chords) {
                val x = if (c.wholeMeasureRest) {
                    val first = pl.slots.first().x; val last = pl.slots.last().x + pl.slots.last().w * factor
                    (first + last) / 2f - sp
                } else slotX[c.start] ?: pl.slots.first().x
                if (c.isRest) {
                    rest(ops, c, x, top, multiVoice)
                    heads.add(HeadHit(c.id, -1, pl.index, RectF(x - sp * 0.4f, yOf(top, 8), x + sp * 1.6f, yOf(top, 0)), x, yOf(top, 4)))
                    continue
                }
                val ps = c.notes.map { pos(written(c, it.pitch.diatonic), m.clefAt(staff, c.start)) }
                val up = if (multiVoice) c.voice % 2 == 1 else (ps.max() - 4) <= (4 - ps.min())
                placed.add(Placed(c, x, ps, ps.map { yOf(top, it) }, up))
            }

            // Balkengruppen und gemeinsame Halsrichtung
            val groups = beamGroups(placed, m)
            for (g in groups) if (!multiVoice) {
                val hi = g.maxOf { it.ps.max() }; val lo = g.minOf { it.ps.min() }
                val up = (hi - 4) <= (4 - lo)
                g.forEach { it.up = up }
            }

            for (p in placed) noteHeads(ops, heads, p, top, acc, pl.index)
            for (p in placed) if (p.chord.type != NoteType.WHOLE) stem(p, top)
            for (g in groups) beam(ops, g)
            for (p in placed) {
                if (p.chord.type == NoteType.WHOLE) continue
                ops.add(Op.Line(p.stemX, if (p.up) p.ys.max() - sp * 0.15f else p.ys.min() + sp * 0.15f, p.stemX, p.stemEnd, sp * 0.12f, p.chord.id))
                if (groups.none { g -> p in g } && p.chord.type.beams > 0) {
                    val f = when (p.chord.type) {
                        NoteType.EIGHTH -> if (p.up) G.FLAG_8_UP else G.FLAG_8_DOWN
                        NoteType.SIXTEENTH -> if (p.up) G.FLAG_16_UP else G.FLAG_16_DOWN
                        else -> if (p.up) G.FLAG_32_UP else G.FLAG_32_DOWN
                    }
                    ops.add(Op.Glyph(f, p.stemX - sp * 0.06f, p.stemEnd, chordId = p.chord.id))
                }
                articulations(ops, p, top, staff, acc)
            }
            for (p in placed) if (p.chord.type == NoteType.WHOLE) articulations(ops, p, top, staff, acc)
            for (p in placed) {
                info[p.chord.id] = ChordInfo(pl.index, staff, p.x, p.ys.min(), p.ys.max(), p.up, p.stemEnd, p.chord.type != NoteType.WHOLE)
                if (p.chord.graces.isNotEmpty()) graces(ops, p, m.clefAt(staff, p.chord.start), top, m, acc)
            }
            tuplets(ops, chords, placed, groups, slotX, top, m)
            for (p in placed) fingerings(ops, p, top, staff)
        }
    }

    /**
     * Fingersätze wie im Heft: rechte Hand über, linke Hand unter dem System, bei Akkorden gestapelt
     * (oben = höchster Ton).
     */
    private fun fingerings(ops: MutableList<Op>, p: Placed, top: Float, staff: Int) {
        val c = p.chord
        val withF = c.notes.withIndex().filter { it.value.finger != null }
        if (withF.isEmpty()) return
        val scale = 0.85f
        val step = sp * 1.05f
        val cx = p.x + headW / 2
        val hasStem = c.type != NoteType.WHOLE
        if (photoFingers && withF.all { it.value.fingerDx != null && it.value.fingerDy != null }) {
            // wie auf dem Blatt: Ziffermitte im selben Abstand zum Kopf (in Linienabständen) – so steht eine eingekreiste
            // Ziffer auch in der App im Bleistiftkreis
            for ((i, n) in withF) {
                val g = G.fingerDigit(n.finger!!)
                val gx = cx + n.fingerDx!! * sp - measureGlyph(g) * scale / 2
                val gy = p.ys[i] + n.fingerDy!! * sp + sp * 0.45f * scale
                ops.add(Op.Glyph(g, gx, gy, scale, c.id))
            }
            return
        }
        if (staff == 0) {
            val outer = if (hasStem && p.up) min(p.stemEnd, p.ys.min()) else p.ys.min()
            var y = min(top - sp * 0.6f, outer - sp * 0.7f)
            // von unten nach oben: tiefster Ton zuerst
            for ((_, n) in withF.sortedBy { it.value.pitch.diatonic }) {
                val g = G.fingerDigit(n.finger!!)
                ops.add(Op.Glyph(g, cx - measureGlyph(g) * scale / 2, y, scale, c.id))
                y -= step
            }
        } else {
            val outer = if (hasStem && !p.up) max(p.stemEnd, p.ys.max()) else p.ys.max()
            var y = max(top + sp * 4 + sp * 1.5f, outer + sp * 1.6f)
            // von oben nach unten: höchster Ton zuerst
            for ((_, n) in withF.sortedByDescending { it.value.pitch.diatonic }) {
                val g = G.fingerDigit(n.finger!!)
                ops.add(Op.Glyph(g, cx - measureGlyph(g) * scale / 2, y, scale, c.id))
                y += step
            }
        }
    }

    /** Vorschläge: kleine Achtel mit Schrägstrich links vom Akkord. */
    private fun graces(ops: MutableList<Op>, p: Placed, clef: Clef, top: Float, m: Measure, acc: Map<Pair<Long, Int>, String>) {
        val c = p.chord
        val scale = 0.62f
        val w = headW * scale
        val hasAcc = c.notes.indices.any { acc[c.id to it] != null }
        val arp = if (Orn.ARPEGGIO in c.orn) sp * 1.1f else 0f
        c.graces.forEachIndexed { k, g ->
            val gx = p.x - arp - (if (hasAcc) sp * 1.3f else sp * 0.2f) - (c.graces.size - k) * sp * 1.5f
            val pp = pos(written(c, g.diatonic), clef)
            val gy = yOf(top, pp)
            if (g.alter != keyAlter(m.fifths, g.step)) {
                val a = when (g.alter) { -2 -> G.DOUBLE_FLAT; -1 -> G.FLAT; 1 -> G.SHARP; 2 -> G.DOUBLE_SHARP; else -> G.NATURAL }
                ops.add(Op.Glyph(a, gx - measureGlyph(a) * scale - sp * 0.15f, gy, scale, c.id))
            }
            ops.add(Op.Glyph(G.HEAD_BLACK, gx, gy, scale, c.id))
            if (pp < -1) for (l in -2 downTo pp step 2) ops.add(Op.Line(gx - sp * 0.25f, yOf(top, l), gx + w + sp * 0.25f, yOf(top, l), sp * 0.1f))
            if (pp > 9) for (l in 10..pp step 2) ops.add(Op.Line(gx - sp * 0.25f, yOf(top, l), gx + w + sp * 0.25f, yOf(top, l), sp * 0.1f))
            val sx = gx + w - sp * 0.05f
            val end = gy - sp * 2.4f
            ops.add(Op.Line(sx, gy - sp * 0.1f, sx, end, sp * 0.1f, c.id))
            ops.add(Op.Glyph(G.FLAG_8_UP, sx - sp * 0.04f, end, scale, c.id))
            // Schrägstrich (Acciaccatura)
            ops.add(Op.Line(sx - sp * 0.55f, end + sp * 1.5f, sx + sp * 0.75f, end + sp * 0.55f, sp * 0.1f, c.id))
        }
    }

    /**
     * Triolen usw.: Ziffer über bzw. unter der Gruppe, eckige Klammer nur ohne gemeinsamen Balken.
     * Eine Gruppe endet, sobald ihre Dauer einen ganzen Schlag (oder ein Vielfaches) füllt.
     */
    private fun tuplets(
        ops: MutableList<Op>, chords: List<Chord>, placed: List<Placed>, beams: List<List<Placed>>,
        slotX: Map<Int, Float>, top: Float, m: Measure,
    ) {
        val beat = TPQ * 4 / m.beatType
        for ((_, voice) in chords.filter { !it.wholeMeasureRest }.groupBy { it.voice }) {
            var group = mutableListOf<Chord>()
            var sum = 0
            fun flush() {
                if (group.size < 2) { group = mutableListOf(); sum = 0; return }
                val ps = group.mapNotNull { c -> placed.firstOrNull { it.chord.id == c.id } }
                val x1 = slotX[group.first().start] ?: return
                val x2 = (slotX[group.last().start] ?: return) + headW
                val up = ps.count { it.up } * 2 >= ps.size
                val beamed = ps.size == group.size && beams.any { b -> ps.all { it in b } }
                val y = if (up) min(top - sp * 0.6f, (ps.minOfOrNull { if (it.chord.type != NoteType.WHOLE) min(it.stemEnd, it.ys.min()) else it.ys.min() } ?: top) - sp * 0.9f)
                else max(top + sp * 4.6f, (ps.maxOfOrNull { if (it.chord.type != NoteType.WHOLE) max(it.stemEnd, it.ys.max()) else it.ys.max() } ?: top) + sp * 2.2f)
                val digit = group.first().tuplet.toString().map { G.tupletDigit(it - '0') }.joinToString("")
                val dw = measureGlyph(digit) * 0.8f
                val cx = (x1 + x2) / 2
                ops.add(Op.Glyph(digit, cx - dw / 2, y + sp * 0.55f, 0.8f, group.first().id))
                if (!beamed) {
                    val ly = y - sp * 0.1f
                    val hook = if (up) sp * 0.6f else -sp * 0.6f
                    val lw = sp * 0.1f
                    ops.add(Op.Line(x1, ly + hook, x1, ly, lw)); ops.add(Op.Line(x1, ly, cx - dw / 2 - sp * 0.3f, ly, lw))
                    ops.add(Op.Line(cx + dw / 2 + sp * 0.3f, ly, x2, ly, lw)); ops.add(Op.Line(x2, ly, x2, ly + hook, lw))
                }
                group = mutableListOf(); sum = 0
            }
            for (c in voice.sortedBy { it.start }) {
                if (c.tuplet == 0) { flush(); continue }
                if (group.isNotEmpty() && group.last().tuplet != c.tuplet) flush()
                group.add(c); sum += c.duration
                if (sum % beat == 0 && sum > 0) flush()
            }
            flush()
        }
    }

    private fun rest(ops: MutableList<Op>, c: Chord, x: Float, top: Float, multi: Boolean) {
        val shift = if (!multi) 0 else if (c.voice % 2 == 1) 2 else -2
        val (g, p) = when {
            c.wholeMeasureRest || c.type == NoteType.WHOLE -> G.REST_WHOLE to 6
            c.type == NoteType.HALF -> G.REST_HALF to 4
            c.type == NoteType.QUARTER -> G.REST_QUARTER to 4
            c.type == NoteType.EIGHTH -> G.REST_8 to 4
            c.type == NoteType.SIXTEENTH -> G.REST_16 to 4
            else -> G.REST_32 to 4
        }
        ops.add(Op.Glyph(g, x, yOf(top, p + shift), chordId = c.id))
        repeat(c.dots) { k -> ops.add(Op.Glyph(G.DOT, x + measureGlyph(g) + sp * (0.4f + k * 0.5f), yOf(top, 5 + shift), chordId = c.id)) }
    }

    private fun noteHeads(ops: MutableList<Op>, heads: MutableList<HeadHit>, p: Placed, top: Float, acc: Map<Pair<Long, Int>, String>, measure: Int) {
        val c = p.chord
        val glyph = when (c.type) { NoteType.WHOLE -> G.HEAD_WHOLE; NoteType.HALF -> G.HEAD_HALF; else -> G.HEAD_BLACK }
        val w = if (c.type == NoteType.WHOLE) wholeW else headW
        // Sekunden im Akkord: Köpfe wechselseitig versetzen
        val order = p.ps.indices.sortedBy { p.ps[it] }
        val offset = FloatArray(p.ps.size)
        if (p.up) {
            var prev = Int.MIN_VALUE; var prevShifted = false
            for (i in order) {
                val shifted = p.ps[i] - prev == 1 && !prevShifted
                if (shifted) offset[i] = w - sp * 0.08f
                prevShifted = shifted; prev = p.ps[i]
            }
        } else {
            var prev = Int.MAX_VALUE; var prevShifted = false
            for (i in order.reversed()) {
                val shifted = prev - p.ps[i] == 1 && !prevShifted
                if (shifted) offset[i] = -(w - sp * 0.08f)
                prevShifted = shifted; prev = p.ps[i]
            }
        }
        // Vorzeichen links, bei mehreren gestaffelt
        var accCol = 0
        for (i in order.reversed()) {
            val a = acc[c.id to i] ?: continue
            val ax = p.x - sp * 0.25f - measureGlyph(a) - (accCol % 2) * sp * 1.0f + min(0f, offset[i])
            ops.add(Op.Glyph(a, ax, p.ys[i], chordId = c.id))
            accCol++
        }
        for (i in p.ps.indices) {
            val hx = p.x + offset[i]
            ops.add(Op.Glyph(glyph, hx, p.ys[i], chordId = c.id))
            heads.add(HeadHit(c.id, i, measure, RectF(hx - sp * 0.3f, p.ys[i] - sp * 0.8f, hx + w + sp * 0.3f, p.ys[i] + sp * 0.8f), hx + w / 2, p.ys[i]))
            // Hilfslinien
            val pp = p.ps[i]
            if (pp < -1) for (l in -2 downTo pp step 2) ops.add(Op.Line(hx - sp * 0.4f, yOf(top, l), hx + w + sp * 0.4f, yOf(top, l), sp * 0.13f))
            if (pp > 9) for (l in 10..pp step 2) ops.add(Op.Line(hx - sp * 0.4f, yOf(top, l), hx + w + sp * 0.4f, yOf(top, l), sp * 0.13f))
            // Punkte
            if (c.dots > 0) {
                val dy = if (pp % 2 == 0) yOf(top, pp + 1) else p.ys[i]
                val dx = p.x + w + max(0f, offset.max()) + sp * 0.35f
                repeat(c.dots) { k -> ops.add(Op.Glyph(G.DOT, dx + k * sp * 0.5f, dy, chordId = c.id)) }
            }
        }
    }

    private fun stem(p: Placed, top: Float) {
        val mid = yOf(top, 4)
        if (p.up) {
            p.stemX = p.x + headW - sp * 0.06f
            val tip = p.ys.min() - sp * 3.5f
            p.stemEnd = if (p.ys.min() > mid + sp * 2) min(tip, mid) else tip
        } else {
            p.stemX = p.x + sp * 0.06f
            val tip = p.ys.max() + sp * 3.5f
            p.stemEnd = if (p.ys.max() < mid - sp * 2) max(tip, mid) else tip
        }
    }

    private fun beamGroups(placed: List<Placed>, m: Measure): List<List<Placed>> {
        val groups = mutableListOf<List<Placed>>()
        val byVoice = placed.groupBy { it.chord.voice }
        for ((_, list) in byVoice) {
            val sorted = list.sortedBy { it.chord.start }
            val hasFlags = sorted.any { it.chord.beam != Beam.NONE }
            if (hasFlags) {
                var cur = mutableListOf<Placed>()
                for (p in sorted) {
                    when (p.chord.beam) {
                        Beam.BEGIN -> { if (cur.size >= 2) groups.add(cur); cur = mutableListOf(p) }
                        Beam.CONTINUE -> if (cur.isNotEmpty()) cur.add(p)
                        Beam.END -> { if (cur.isNotEmpty()) { cur.add(p); if (cur.size >= 2) groups.add(cur) }; cur = mutableListOf() }
                        Beam.NONE -> { if (cur.size >= 2) groups.add(cur); cur = mutableListOf() }
                    }
                }
                if (cur.size >= 2) groups.add(cur)
            } else {
                // Automatisch: innerhalb eines Schlags (bei 6/8 usw. drei Achtel) zusammenfassen
                val beat = if (m.beatType == 8 && m.beats % 3 == 0) TPQ * 3 / 2 else if (m.beatType == 4 && m.beats == 4) TPQ * 2 else TPQ * 4 / m.beatType
                var cur = mutableListOf<Placed>()
                var curBeat = -1
                for (p in sorted) {
                    val b = p.chord.start / beat
                    val ok = p.chord.type.beams > 0
                    if (!ok || b != curBeat) { if (cur.size >= 2) groups.add(cur); cur = mutableListOf() }
                    if (ok) { cur.add(p); curBeat = b }
                }
                if (cur.size >= 2) groups.add(cur)
            }
        }
        return groups
    }

    private fun beam(ops: MutableList<Op>, g: List<Placed>) {
        val up = g.first().up
        val first = g.first(); val last = g.last()
        val dx = (last.stemX - first.stemX).coerceAtLeast(1f)
        val e1 = if (up) first.ys.min() - sp * 3.5f else first.ys.max() + sp * 3.5f
        val e2 = if (up) last.ys.min() - sp * 3.5f else last.ys.max() + sp * 3.5f
        var slope = ((e2 - e1) / dx).coerceIn(-0.18f, 0.18f)
        if (abs(e2 - e1) < sp * 0.4f) slope = 0f
        var base = e1
        // Mindestlänge der Hälse sicherstellen
        val minLen = sp * 2.8f + (g.maxOf { it.chord.type.beams } - 1) * sp * 0.75f
        for (p in g) {
            val yb = base + slope * (p.stemX - first.stemX)
            if (up) { val need = p.ys.min() - minLen; if (yb > need) base -= yb - need }
            else { val need = p.ys.max() + minLen; if (yb < need) base += need - yb }
        }
        for (p in g) p.stemEnd = base + slope * (p.stemX - first.stemX)
        val th = sp * 0.5f
        val dir = if (up) 1f else -1f
        fun bar(xa: Float, xb: Float, level: Int, id: Long) {
            val off = level * sp * 0.75f * dir
            val ya = base + slope * (xa - first.stemX) + off
            val yb = base + slope * (xb - first.stemX) + off
            ops.add(Op.Poly(floatArrayOf(xa, ya, xb, yb, xb, yb + th * dir, xa, ya + th * dir), id))
        }
        bar(first.stemX - sp * 0.06f, last.stemX + sp * 0.06f, 0, first.chord.id)
        val maxLevel = g.maxOf { it.chord.type.beams }
        for (level in 1 until maxLevel) {
            var i = 0
            while (i < g.size) {
                if (g[i].chord.type.beams > level) {
                    var j = i
                    while (j + 1 < g.size && g[j + 1].chord.type.beams > level) j++
                    if (j > i) bar(g[i].stemX, g[j].stemX, level, g[i].chord.id)
                    else {
                        val left = i > 0
                        val xa = if (left) g[i].stemX - sp * 1.1f else g[i].stemX
                        val xb = if (left) g[i].stemX else g[i].stemX + sp * 1.1f
                        bar(xa, xb, level, g[i].chord.id)
                    }
                    i = j + 1
                } else i++
            }
        }
    }

    private fun articulations(ops: MutableList<Op>, p: Placed, top: Float, staff: Int, acc: Map<Pair<Long, Int>, String>) {
        val c = p.chord
        val below = p.up || c.type == NoteType.WHOLE && p.ps.average() >= 4
        val cx = p.x + headW / 2
        // Am Notenkopf (gegenüber dem Hals), von innen nach außen gestapelt
        var y = if (below) p.ys.max() + sp * 0.85f else p.ys.min() - sp * 0.85f
        fun put(aboveG: String, belowG: String, h: Float) {
            val g = if (below) belowG else aboveG
            ops.add(Op.Glyph(g, cx - measureGlyph(g) / 2, y, chordId = c.id))
            y += if (below) h + sp * 0.3f else -(h + sp * 0.3f)
        }
        if (Orn.STACCATISSIMO in c.orn) put(G.STACCATISSIMO_ABOVE, G.STACCATISSIMO_BELOW, sp * 1.16f)
        else if (c.staccato) put(G.STACCATO_ABOVE, G.STACCATO_BELOW, sp * 0.34f)
        if (Orn.TENUTO in c.orn) put(G.TENUTO_ABOVE, G.TENUTO_BELOW, sp * 0.2f)
        if (Orn.MARCATO in c.orn) put(G.MARCATO_ABOVE, G.MARCATO_BELOW, sp * 1.02f)
        else if (c.accent) put(G.ACCENT_ABOVE, G.ACCENT_BELOW, sp * 0.98f)

        // Verzierungen über der Note bzw. über dem System
        val stemTop = if (p.up && c.type != NoteType.WHOLE) min(p.stemEnd, p.ys.min()) else p.ys.min()
        var oy = min(top - sp * 0.9f, stemTop - sp * 1.0f)
        if (!below) oy = min(oy, y)
        val orns = listOf(
            Orn.TRILL to (G.TRILL to 1.6f), Orn.PRALL to (G.PRALL to 1.0f), Orn.MORDENT to (G.MORDENT to 1.3f),
            Orn.TURN to (G.TURN to 0.9f), Orn.TURN_INV to (G.TURN_INV to 0.9f),
        )
        // Linke Hand: Verzierungen unter das System, damit sie der Dynamik zwischen den Systemen nicht im Weg sind
        val stemBottom = if (!p.up && c.type != NoteType.WHOLE) max(p.stemEnd, p.ys.max()) else p.ys.max()
        var uy = max(top + sp * 4.9f, stemBottom + sp * 1.0f).let { if (below) max(it, y) else it }
        for ((o, gh) in orns) if (o in c.orn) {
            val (g, h) = gh
            if (staff == 0) {
                ops.add(Op.Glyph(g, cx - measureGlyph(g) / 2, oy, chordId = c.id))
                oy -= sp * (h + 0.35f)
            } else {
                ops.add(Op.Glyph(g, cx - measureGlyph(g) / 2, uy + sp * h, chordId = c.id))
                uy += sp * (h + 0.35f)
            }
        }
        if (Orn.FERMATA in c.orn) {
            if (staff == 0) {
                ops.add(Op.Glyph(G.FERMATA_ABOVE, cx - measureGlyph(G.FERMATA_ABOVE) / 2, oy, chordId = c.id))
            } else {
                val fy = uy
                ops.add(Op.Glyph(G.FERMATA_BELOW, cx - measureGlyph(G.FERMATA_BELOW) / 2, fy, chordId = c.id))
            }
        }
        if (Orn.BREATH in c.orn) ops.add(Op.Glyph(G.BREATH, p.x + headW + sp * 0.9f, top - sp * 0.3f, chordId = c.id))

        // Arpeggio: Wellenlinie links vom Akkord
        if (Orn.ARPEGGIO in c.orn && c.notes.size > 1) {
            val hasAcc = c.notes.indices.any { acc[c.id to it] != null }
            val ax = p.x - sp * 0.8f - if (hasAcc) sp * 1.3f else 0f
            var yy = p.ys.max() + sp * 0.6f
            val stop = p.ys.min() - sp * 0.6f
            var side = 1f
            while (yy > stop) {
                val ny = max(stop, yy - sp * 0.5f)
                ops.add(Op.Line(ax - side * sp * 0.22f, yy, ax + side * sp * 0.22f, ny, sp * 0.13f, c.id))
                side = -side; yy = ny
            }
        }
        // Tremolo: drei Schrägbalken am Hals (bei Ganzen über dem Kopf)
        if (Orn.TREMOLO in c.orn) {
            val (tx, ty) = if (c.type == NoteType.WHOLE) (p.x + wholeW / 2) to (p.ys.min() - sp * 2.0f)
            else p.stemX to (if (p.up) (p.ys.min() + p.stemEnd) / 2 - sp * 0.2f else (p.ys.max() + p.stemEnd) / 2 + sp * 0.2f)
            for (k in -1..1) {
                val yc = ty + k * sp * 0.65f
                ops.add(Op.Poly(floatArrayOf(
                    tx - sp * 0.6f, yc + sp * 0.25f, tx + sp * 0.6f, yc - sp * 0.25f,
                    tx + sp * 0.6f, yc + sp * 0.07f, tx - sp * 0.6f, yc + sp * 0.57f,
                ), c.id))
            }
        }
    }

    // ---------------------------------------------------------------- Bindebögen

    private fun slurs(ops: MutableList<Op>, score: Score, systems: List<SystemBox>, mboxes: List<MeasureBox>) {
        val systemOf = mboxes.associate { it.index to it.system }
        for (sl in score.slurs) {
            val a = info[sl.from] ?: continue
            val b = info[sl.to] ?: continue
            val sa = systemOf[a.measure] ?: continue
            val sb = systemOf[b.measure] ?: continue
            if (sb < sa) continue
            // Unter den Köpfen nur, wenn beide Hälse nach oben zeigen
            val above = !(a.up && b.up)
            fun yOf(ci: ChordInfo) = if (above) (if (ci.up && ci.hasStem) ci.outerTop - sp * 0.5f else ci.top - sp * 0.9f)
                else (if (!ci.up && ci.hasStem) ci.outerBottom + sp * 0.5f else ci.bottom + sp * 0.9f)
            val xa = a.x + headW * 0.6f
            val xb = b.x + headW * 0.4f
            fun arc(x1: Float, y1: Float, x2: Float, y2: Float, between: List<ChordInfo>) {
                // Bogen über bzw. unter dazwischenliegende Noten heben
                val dx = x2 - x1
                val h = min(sp * 1.3f, dx * 0.16f + sp * 0.35f)
                var shift = 0f
                for (ci in between) {
                    val f = ((ci.x - x1) / dx).coerceIn(0f, 1f)
                    val line = y1 + (y2 - y1) * f
                    if (above) { val need = ci.outerTop - sp * 0.6f; val peak = line - h; if (peak > need) shift = min(shift, need - peak) }
                    else { val need = ci.outerBottom + sp * 0.6f; val peak = line + h; if (peak < need) shift = max(shift, need - peak) }
                }
                shift = shift.coerceIn(-sp * 3f, sp * 3f)
                ops.add(Op.Curve(x1, y1 + shift, x2, y2 + shift, above, sp * 0.16f, sl.from))
            }
            val inner = info.values.filter { it.staff == a.staff }
            if (sa == sb) {
                arc(xa, yOf(a), xb, yOf(b), inner.filter { systemOf[it.measure] == sa && it.x > xa && it.x < b.x })
            } else {
                val s1 = systems[sa]; val s2 = systems[sb]
                arc(xa, yOf(a), s1.x1 - sp * 0.3f, yOf(a), inner.filter { systemOf[it.measure] == sa && it.x > xa })
                val startX = mboxes.firstOrNull { it.system == sb }?.rect?.left?.let { min(it + sp * 0.8f, xb - sp * 2f) } ?: (xb - sp * 3f)
                arc(min(startX, xb - sp * 2f), yOf(b), xb, yOf(b), inner.filter { systemOf[it.measure] == sb && it.x < b.x })
            }
        }
    }

    // ---------------------------------------------------------------- Dynamik

    /** Lautstärkezeichen zwischen den Systemen, Zusätze („dolce“, „più“) kursiv daneben. */
    private fun dynamics(ops: MutableList<Op>, score: Score, systems: List<SystemBox>, mboxes: List<MeasureBox>, slotMap: Map<Int, List<Pair<Int, Float>>>) {
        val systemOf = mboxes.associate { it.index to it.system }
        val seen = HashSet<Triple<Int, Int, String>>()
        val nextX = HashMap<Pair<Int, Int>, Float>()
        for (d in score.dynamics.sortedWith(compareBy({ it.measure }, { it.tick }, { !it.isLetters }))) {
            if (!seen.add(Triple(d.measure, d.tick, d.text))) continue
            val si = systemOf[d.measure] ?: continue
            val sys = systems[si]
            var x = xAt(slotMap, d.measure, d.tick) ?: continue
            val key = d.measure to d.tick
            nextX[key]?.let { x = max(x, it) }
            val mid = (sys.staffTops[0] + sp * 4 + sys.staffTops[1]) / 2
            if (d.isLetters) {
                val g = G.dynamic(d.text)
                ops.add(Op.Glyph(g, x - sp * 0.2f, mid + sp * 0.6f))
                nextX[key] = x - sp * 0.2f + measureGlyph(g) + sp * 0.5f
            } else {
                val size = sp * 1.5f
                ops.add(Op.Label(d.text, x, mid + sp * 0.5f, size, style = TextStyle.EXPR))
                nextX[key] = x + measureText(d.text, size, TextStyle.EXPR) + sp * 0.5f
            }
        }
    }

    // ---------------------------------------------------------------- Gabeln

    /** Gabeln zwischen den Systemen; über einen Zeilenumbruch hinweg in Stücken mit passender Öffnung. */
    private fun hairpins(ops: MutableList<Op>, score: Score, systems: List<SystemBox>, mboxes: List<MeasureBox>, slotMap: Map<Int, List<Pair<Int, Float>>>) {
        val systemOf = mboxes.associate { it.index to it.system }
        val open = sp * 0.6f
        val w = sp * 0.12f
        for (h in score.hairpins) {
            val s0 = systemOf[h.measure] ?: continue
            val s1 = systemOf[h.endMeasure.coerceAtMost(score.measures.size - 1)] ?: continue
            var xa = xAt(slotMap, h.measure, h.tick) ?: continue
            var xb = xAt(slotMap, h.endMeasure, h.endTick) ?: continue
            // Platz für ein Dynamikzeichen am Anfang bzw. Ende lassen
            if (score.dynamics.any { it.measure == h.measure && it.tick == h.tick }) xa += sp * 2.6f
            if (score.dynamics.any { it.measure == h.endMeasure && it.tick == h.endTick }) xb -= sp * 0.6f else xb += headW
            val total = systems.subList(s0, s1 + 1).foldIndexed(0f) { k, acc, sys ->
                val a = if (k == 0) xa else sys.x0 + sp * 6f
                val b = if (s0 + k == s1) xb else sys.x1 - sp * 0.5f
                acc + max(0f, b - a)
            }.coerceAtLeast(1f)
            var done = 0f
            for (si in s0..s1) {
                val sys = systems[si]
                val a = if (si == s0) xa else (mboxes.firstOrNull { it.system == si }?.rect?.left ?: (sys.x0 + sp * 6f)) + sp * 0.8f
                val b = if (si == s1) xb else sys.x1 - sp * 0.5f
                if (b - a < sp) continue
                val mid = (sys.staffTops[0] + sp * 4 + sys.staffTops[1]) / 2
                fun gap(f: Float) = open * (if (h.cresc) f else 1f - f)
                val ga = gap(done / total); val gb = gap((done + b - a) / total)
                ops.add(Op.Line(a, mid - ga, b, mid - gb, w))
                ops.add(Op.Line(a, mid + ga, b, mid + gb, w))
                done += b - a
            }
        }
    }

    // ---------------------------------------------------------------- Wörter und Akkordsymbole

    /** Oberkante der obersten Textzeile über jedem System – die Volta-Klammer kommt darüber. */
    private val textTop = HashMap<Int, Float>()

    /**
     * Akkordsymbole und Anweisungen über dem System (Tempo, rit., Fine, D.C., Formteile),
     * cresc./dim./sim. zwischen den Systemen, una corda/tre corde unter dem Bass-System.
     */
    private fun texts(ops: MutableList<Op>, score: Score, systems: List<SystemBox>, mboxes: List<MeasureBox>, slotMap: Map<Int, List<Pair<Int, Float>>>) {
        textTop.clear()
        val systemOf = mboxes.associate { it.index to it.system }
        val chordSize = sp * 1.75f
        val wordSize = sp * 1.55f
        systems.forEachIndexed { si, sys ->
            val ms = mboxes.filter { it.system == si }
            if (ms.isEmpty()) return@forEachIndexed
            val range = ms.first().index..ms.last().index
            val st0 = sys.staffTops[0]
            // Höchster Punkt der rechten Hand (mit Verzierungen) in dieser Zeile
            val highest = range.flatMap { mi -> score.measures[mi].chords.filter { it.staff == 0 }.mapNotNull { c ->
                info[c.id]?.let { ci ->
                    val fingers = c.notes.count { it.finger != null }
                    // Fingersätze stehen über dem System – Text kommt darüber
                    val fingerTop = if (fingers > 0) min(st0 - sp * 0.6f, ci.outerTop - sp * 0.7f) - fingers * sp * 1.05f else Float.MAX_VALUE
                    min(ci.outerTop - (if (c.orn.any { it in ABOVE_STAFF }) sp * 2.2f else 0f), fingerTop)
                }
            } }.minOrNull() ?: st0
            var base = min(st0 - sp * 1.2f, highest - sp * 1.0f)
            val chords = score.chordSymbols.filter { it.measure in range }
            if (chords.isNotEmpty()) {
                for (c in chords) {
                    val x = xAt(slotMap, c.measure, c.tick) ?: continue
                    chordSymbol(ops, c.text, x - sp * 0.2f, base, chordSize)
                }
                base -= chordSize * 1.25f
            }
            val above = score.words.filter { it.measure in range && it.kind.above }
            if (above.isNotEmpty()) {
                for (w in above) {
                    val style = when (w.kind) {
                        com.nicedevelopments.vomblatt.model.WordKind.TEMPO, com.nicedevelopments.vomblatt.model.WordKind.REHEARSAL -> TextStyle.BOLD
                        com.nicedevelopments.vomblatt.model.WordKind.FINE, com.nicedevelopments.vomblatt.model.WordKind.CODA -> TextStyle.BOLD_EXPR
                        else -> TextStyle.EXPR
                    }
                    if (w.kind == com.nicedevelopments.vomblatt.model.WordKind.REHEARSAL) {
                        // Formteil-Buchstabe im Kästchen am Zeilen- bzw. Taktanfang
                        val x = if (w.measure == range.first) sys.x0 - sp * 0.4f else (ms.first { it.index == w.measure }.rect.left)
                        val tw = measureText(w.text, wordSize, TextStyle.BOLD)
                        val pad = sp * 0.35f
                        ops.add(Op.Label(w.text, x + pad, base, wordSize, style = TextStyle.BOLD))
                        val t = base - wordSize * 0.78f - pad; val b = base + pad; val r = x + tw + 2 * pad
                        val lw = sp * 0.12f
                        ops.add(Op.Line(x, t, r, t, lw)); ops.add(Op.Line(r, t, r, b, lw)); ops.add(Op.Line(r, b, x, b, lw)); ops.add(Op.Line(x, b, x, t, lw))
                        continue
                    }
                    var x = xAt(slotMap, w.measure, w.tick) ?: continue
                    val tw = measureText(w.text, wordSize, style)
                    // Fine, D.C. usw. stehen am Taktende rechtsbündig
                    if (w.kind in setOf(com.nicedevelopments.vomblatt.model.WordKind.FINE, com.nicedevelopments.vomblatt.model.WordKind.DC,
                            com.nicedevelopments.vomblatt.model.WordKind.DC_AL_CODA, com.nicedevelopments.vomblatt.model.WordKind.DS,
                            com.nicedevelopments.vomblatt.model.WordKind.DS_AL_CODA, com.nicedevelopments.vomblatt.model.WordKind.TO_CODA)) {
                        x = (ms.firstOrNull { it.index == w.measure }?.rect?.right ?: x) - tw - sp * 0.3f
                    }
                    ops.add(Op.Label(w.text, x - sp * 0.2f, base, wordSize, style = style))
                }
                base -= wordSize * 1.25f
            }
            textTop[si] = base + sp * 0.4f

            // zwischen den Systemen (neben der Dynamik) und unter dem Bass-System
            val mid = (st0 + sp * 4 + sys.staffTops[1]) / 2
            for (w in score.words.filter { it.measure in range && !it.kind.above }) {
                var x = xAt(slotMap, w.measure, w.tick) ?: continue
                val belowBass = w.kind == com.nicedevelopments.vomblatt.model.WordKind.UNA_CORDA || w.kind == com.nicedevelopments.vomblatt.model.WordKind.TRE_CORDE
                if (!belowBass) score.dynamics.filter { it.measure == w.measure && it.tick == w.tick && it.isLetters }.forEach {
                    x += measureGlyph(G.dynamic(it.text)) + sp * 0.4f
                }
                val y = if (belowBass) max(sys.staffTops[1] + sp * 6.4f, sys.bottom - sp * 3.0f) else mid + sp * 0.5f
                ops.add(Op.Label(w.text, x - sp * 0.2f, y, wordSize, style = TextStyle.EXPR))
            }
        }
    }

    /** Akkordsymbol: Buchstaben in der Oberflächenschrift, ♭/♯ als Bravura-Zeichen. */
    private fun chordSymbol(ops: MutableList<Op>, text: String, x0: Float, y: Float, size: Float) {
        var x = x0
        var i = 0
        val buf = StringBuilder()
        fun flush() {
            if (buf.isEmpty()) return
            ops.add(Op.Label(buf.toString(), x, y, size, style = TextStyle.PLAIN))
            x += measureText(buf.toString(), size, TextStyle.PLAIN)
            buf.clear()
        }
        while (i < text.length) {
            val ch = text[i]
            val afterRoot = i > 0 && (text[i - 1] in 'A'..'H')
            if (afterRoot && (ch == 'b' || ch == '#')) {
                flush()
                val g = if (ch == 'b') G.FLAT else G.SHARP
                val scale = 0.62f
                ops.add(Op.Glyph(g, x + sp * 0.05f, y - size * 0.42f, scale))
                x += measureGlyph(g) * scale + sp * 0.12f
            } else buf.append(ch)
            i++
        }
        flush()
    }

    // ---------------------------------------------------------------- Volta-Klammern

    private fun voltas(ops: MutableList<Op>, score: Score, systems: List<SystemBox>, mboxes: List<MeasureBox>) {
        systems.forEachIndexed { si, sys ->
            val ms = mboxes.filter { it.system == si }
            var i = 0
            while (i < ms.size) {
                val v = score.measures[ms[i].index].volta
                if (v == 0) { i++; continue }
                var j = i
                while (j + 1 < ms.size && score.measures[ms[j + 1].index].volta == v && !score.measures[ms[j].index].voltaClosed) j++
                // Knapp über den höchsten Noten der Klammer, mindestens 2,6 Linienabstände über dem System
                val highest = (i..j).flatMap { k -> score.measures[ms[k].index].chords.filter { it.staff == 0 }.mapNotNull { c -> info[c.id]?.let { ci -> ci.outerTop - (if (c.orn.any { it in ABOVE_STAFF }) sp * 2.2f else 0f) } } }.minOrNull() ?: sys.staffTops[0]
                val y = max(sys.top + sp * 0.4f, min(min(sys.staffTops[0] - sp * 2.6f, highest - sp * 3.4f), (textTop[si] ?: Float.MAX_VALUE) - sp * 2.2f))
                val x1 = ms[i].rect.left + sp * 0.2f
                val x2 = ms[j].rect.right - sp * 0.4f
                val w = sp * 0.13f
                val startsHere = i == 0 && ms[i].index > 0 && score.measures[ms[i].index - 1].volta == v
                if (!startsHere) {
                    ops.add(Op.Line(x1, y + sp * 1.8f, x1, y, w))
                    ops.add(Op.Label("$v.", x1 + sp * 0.5f, y + sp * 1.6f, sp * 1.4f))
                }
                ops.add(Op.Line(x1, y, x2, y, w))
                if (score.measures[ms[j].index].voltaClosed) ops.add(Op.Line(x2, y, x2, y + sp * 1.8f, w))
                i = j + 1
            }
        }
    }

    // ---------------------------------------------------------------- Oktavierung

    /** „8va“ über (bzw. „8vb“ unter) dem System mit gestrichelter Linie bis zur letzten betroffenen Note. */
    private fun ottavas(ops: MutableList<Op>, score: Score, systems: List<SystemBox>, mboxes: List<MeasureBox>) {
        val systemOf = mboxes.associate { it.index to it.system }
        for (staff in 0..1) {
            val runs = mutableListOf<MutableList<Pair<Chord, ChordInfo>>>()
            var cur: MutableList<Pair<Chord, ChordInfo>>? = null
            var curSys = -1
            var curOtt = 0
            score.measures.forEachIndexed { mi, m ->
                for (c in m.chords.filter { it.staff == staff && !it.isRest }.sortedBy { it.start }) {
                    val ci = info[c.id] ?: continue
                    val si = systemOf[mi] ?: continue
                    if (c.ottava == 0) { cur = null; continue }
                    if (cur == null || si != curSys || c.ottava != curOtt) { cur = mutableListOf<Pair<Chord, ChordInfo>>().also { runs.add(it) } }
                    cur!!.add(c to ci); curSys = si; curOtt = c.ottava
                }
            }
            for (run in runs) {
                val ott = run.first().first.ottava
                val sys = systems[systemOf[run.first().second.measure] ?: continue]
                val st = sys.staffTops[staff]
                val g = if (ott > 0) G.OTTAVA_ALTA else G.OTTAVA_BASSA
                val gs = 0.75f
                val x1 = run.first().second.x - sp * 0.3f
                val x2 = run.last().second.x + headW + sp * 0.2f
                val y = if (ott > 0) min(st - sp * 1.6f, run.minOf { it.second.outerTop } - sp * 1.2f)
                    else max(st + sp * 6.2f, run.maxOf { it.second.outerBottom } + sp * 2.2f)
                ops.add(Op.Glyph(g, x1, y, gs))
                val lineY = y - sp * 0.8f
                var dx = x1 + measureGlyph(g) * gs + sp * 0.4f
                while (dx < x2) {
                    ops.add(Op.Line(dx, lineY, min(dx + sp * 0.6f, x2), lineY, sp * 0.1f))
                    dx += sp * 1.1f
                }
                val hook = if (ott > 0) sp * 1.0f else -sp * 1.0f
                ops.add(Op.Line(x2, lineY, x2, lineY + hook, sp * 0.1f))
            }
        }
    }

    // ---------------------------------------------------------------- Pedal

    /**
     * Haltepedal wie im Heft: Anfangsstrich ┌, Linie unter dem Bass-System, Zacke ^ beim Wechsel,
     * Endstrich ┘ beim Lösen. Getretenes Pedal läuft über den Zeilenumbruch weiter.
     */
    private fun pedals(
        ops: MutableList<Op>, score: Score, systems: List<SystemBox>, mboxes: List<MeasureBox>,
        slotMap: Map<Int, List<Pair<Int, Float>>>,
    ) {
        if (score.pedals.isEmpty()) return
        val w = sp * 0.13f
        val rise = sp * 1.1f
        val half = sp * 0.5f
        fun xOf(m: Int, t: Int): Float? {
            val slots = slotMap[m] ?: return null
            var prev = slots.first()
            for (s in slots) {
                if (s.first >= t) {
                    if (s.first == prev.first) return s.second + headW / 2
                    val f = (t - prev.first).toFloat() / (s.first - prev.first)
                    return prev.second + (s.second - prev.second) * f + headW / 2
                }
                prev = s
            }
            return slots.last().second
        }
        val sorted = score.pedals.sortedWith(compareBy({ it.measure }, { it.tick }))
        systems.forEachIndexed { si, sys ->
            val ms = mboxes.filter { it.system == si }
            if (ms.isEmpty()) return@forEachIndexed
            val first = ms.first().index
            val last = ms.last().index
            val y = sys.bottom - sp * 1.4f
            val upAtStart = sorted.any { it.measure == first && it.tick == 0 && it.type == com.nicedevelopments.vomblatt.model.PedalType.UP }
            var down = !upAtStart && score.pedalDownBefore(first, 0)
            var startX = ms.first().rect.left + sp * 0.3f
            for (p in sorted.filter { it.measure in first..last }) {
                val x = xOf(p.measure, p.tick) ?: continue
                when (p.type) {
                    com.nicedevelopments.vomblatt.model.PedalType.UP -> if (down) {
                        // gleich nach einer Zacke an derselben Stelle: kein zusätzlicher Endstrich
                        if (!(p.measure == first && p.tick == 0) && x > startX + w) {
                            ops.add(Op.Line(startX, y, x, y, w))
                            ops.add(Op.Line(x, y + w / 2, x, y - rise, w))
                        }
                        down = false
                    }
                    else -> if (down) {
                        // Zacke: kurz hoch und wieder runter
                        ops.add(Op.Line(startX, y, x - half, y, w))
                        ops.add(Op.Line(x - half, y, x, y - rise, w))
                        ops.add(Op.Line(x, y - rise, x + half, y, w))
                        startX = x + half
                    } else {
                        ops.add(Op.Line(x, y - rise, x, y + w / 2, w))
                        startX = x
                        down = true
                    }
                }
            }
            if (down) {
                ops.add(Op.Line(startX, y, sys.x1, y, w))
                val upNext = sorted.any { it.measure == last + 1 && it.tick == 0 && it.type == com.nicedevelopments.vomblatt.model.PedalType.UP }
                if (upNext) ops.add(Op.Line(sys.x1 - w, y + w / 2, sys.x1 - w, y - rise, w))
            }
        }
    }

    // ---------------------------------------------------------------- Haltebögen

    private fun ties(ops: MutableList<Op>, score: Score, heads: List<HeadHit>, systems: List<SystemBox>, mboxes: List<MeasureBox>) {
        val headByChord = heads.filter { it.noteIndex >= 0 }.groupBy { it.chordId }
        val systemOfMeasure = mboxes.associate { it.index to it.system }
        score.measures.forEachIndexed { mi, m ->
            for (c in m.chords) c.notes.forEachIndexed { ni, n ->
                if (!n.tieStart) return@forEachIndexed
                val from = headByChord[c.id]?.firstOrNull { it.noteIndex == ni } ?: return@forEachIndexed
                // Ziel: nächster Akkord im selben System (Hand) mit gleicher Tonhöhe
                val candidates = (m.chords.filter { it.staff == c.staff && it.start >= c.end } +
                    (score.measures.getOrNull(mi + 1)?.chords?.filter { it.staff == c.staff && it.start == 0 } ?: emptyList()))
                val target = candidates.sortedBy { if (it in m.chords) it.start else 100000 + it.start }
                    .firstOrNull { t -> t.notes.any { it.pitch.midi == n.pitch.midi && it.tieStop } }
                val stemUp = c.notes.size == 1 && from.y > (systems.getOrNull(systemOfMeasure[mi] ?: 0)?.let { s -> s.staffTops[c.staff] + 2 * sp } ?: 0f)
                val up = !stemUp
                val x1 = from.rect.right - sp * 0.2f
                val yOff = if (up) -sp * 0.55f else sp * 0.55f
                if (target == null) {
                    ops.add(Op.Curve(x1, from.y + yOff, x1 + sp * 2.2f, from.y + yOff, up, sp * 0.18f, c.id)); return@forEachIndexed
                }
                val ti = target.notes.indexOfFirst { it.pitch.midi == n.pitch.midi }
                val to = headByChord[target.id]?.firstOrNull { it.noteIndex == ti } ?: return@forEachIndexed
                val sysFrom = systemOfMeasure[mi]
                val sysTo = systemOfMeasure[if (target in m.chords) mi else mi + 1]
                if (sysFrom == sysTo) {
                    ops.add(Op.Curve(x1, from.y + yOff, to.rect.left + sp * 0.2f, to.y + yOff, up, sp * 0.18f, c.id))
                } else {
                    val s = systems[sysFrom ?: 0]
                    ops.add(Op.Curve(x1, from.y + yOff, s.x1 - sp * 0.3f, from.y + yOff, up, sp * 0.18f, c.id))
                    ops.add(Op.Curve(to.rect.left - sp * 2.0f, to.y + yOff, to.rect.left + sp * 0.2f, to.y + yOff, up, sp * 0.18f, target.id))
                }
            }
        }
    }
}
