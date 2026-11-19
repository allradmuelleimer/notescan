package com.nicedevelopments.vomblatt.model

import kotlin.random.Random

/** Ein zu spielender Ton in absoluter Zeit (Ticks ab Stückbeginn, Wiederholungen ausgeschrieben). */
data class PlayNote(
    val tick: Int,
    val length: Int,
    val midi: Int,
    val velocity: Int,
    val staff: Int,
    val chordId: Long,
)

/**
 * Abschnitt der Aufführung: welcher notierte Takt zu welcher absoluten Zeit erklingt.
 * Die Zeitkarte ([nPts] notiert → [pPts] gespielt, stückweise linear) bildet Fermaten, rit. und accel. ab.
 */
class PlayedMeasure(
    val measureIndex: Int,
    val tick: Int,
    /** Gespielte Länge in Ticks. */
    val length: Int,
    val beatTicks: Int = TPQ,
    private val nPts: IntArray = intArrayOf(0, length),
    private val pPts: IntArray = intArrayOf(0, length),
) {
    val notatedLength: Int get() = nPts.last()

    private fun map(v: Int, from: IntArray, to: IntArray): Int {
        if (v <= from[0]) return to[0] + (v - from[0])
        for (i in 0 until from.size - 1) {
            if (v <= from[i + 1]) {
                val span = from[i + 1] - from[i]
                if (span == 0) return to[i + 1]
                return to[i] + ((v - from[i]).toLong() * (to[i + 1] - to[i]) / span).toInt()
            }
        }
        return to.last() + (v - from.last())
    }

    /** Notierte Position im Takt → gespielte Position. */
    fun toPlayed(n: Int): Int = map(n, nPts, pPts)

    /** Gespielte Position im Takt → notierte Position (für Cursor und Anzeige). */
    fun toNotated(p: Int): Int = map(p, pPts, nPts)
}

/** Pedal in absoluter Zeit der Aufführung. */
data class PlayPedal(val tick: Int, val type: PedalType)

class Performance(val notes: List<PlayNote>, val timeline: List<PlayedMeasure>, val pedals: List<PlayPedal> = emptyList()) {
    val totalTicks: Int = timeline.lastOrNull()?.let { it.tick + it.length } ?: 0

    /** Notierter Takt und Position darin zu einem Zeitpunkt der Aufführung. */
    fun locate(tick: Int): Pair<Int, Int>? {
        val pm = playedAt(tick) ?: return null
        return pm.measureIndex to pm.toNotated((tick - pm.tick).coerceIn(0, pm.length)).coerceIn(0, pm.notatedLength)
    }

    fun playedAt(tick: Int): PlayedMeasure? {
        if (timeline.isEmpty()) return null
        var lo = 0; var hi = timeline.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (timeline[mid].tick <= tick) lo = mid else hi = mid - 1
        }
        return timeline[lo]
    }

    /** Erster Zeitpunkt, an dem ein notierter Takt erklingt. */
    fun tickOfMeasure(index: Int): Int = timeline.firstOrNull { it.measureIndex == index }?.tick ?: 0

    /** Abspielzeit für Takt [index] an der notierten Stelle [notated]; bei Wiederholungen der Durchgang, der [near] am nächsten liegt. */
    fun tickAt(index: Int, notated: Int, near: Int = 0): Int {
        val pm = timeline.filter { it.measureIndex == index }.minByOrNull { kotlin.math.abs(it.tick - near) } ?: return 0
        return pm.tick + pm.toPlayed(notated.coerceIn(0, pm.notatedLength))
    }

    /** Ende des ersten Erklingens eines notierten Takts. */
    fun endOfMeasure(index: Int): Int = timeline.firstOrNull { it.measureIndex == index }?.let { it.tick + it.length } ?: 0

    companion object {
        private const val RIT_FACTOR = 0.72f
        private const val ACCEL_FACTOR = 1.25f

        /**
         * Spielreihenfolge der Takte:
         * - Wiederholungen: Rücksprung zum letzten Wiederholungsbeginn (oder Stückanfang), einmal.
         * - Volta-Klammern: 1. Schluss nur im ersten Durchgang, 2. Schluss nur im zweiten.
         * - D.C. / D.S.: nach dem Takt mit der Anweisung zurück an den Anfang bzw. zum Segno, dann ohne
         *   Wiederholungen bis „Fine“ – oder bis „To Coda“ und weiter bei „Coda“.
         */
        fun order(score: Score): List<Int> {
            val n = score.measures.size
            fun kindsAt(i: Int) = score.words.filter { it.measure == i }.map { it.kind }.toSet()
            val jumpAt = (0 until n).firstOrNull { i -> kindsAt(i).any { it in setOf(WordKind.DC, WordKind.DC_AL_CODA, WordKind.DS, WordKind.DS_AL_CODA) } }
            val jumpKind = jumpAt?.let { i -> kindsAt(i).first { it in setOf(WordKind.DC, WordKind.DC_AL_CODA, WordKind.DS, WordKind.DS_AL_CODA) } }
            val segno = score.words.firstOrNull { it.kind == WordKind.SEGNO }?.measure
            val jumpTarget = when (jumpKind) {
                WordKind.DC, WordKind.DC_AL_CODA -> 0
                WordKind.DS, WordKind.DS_AL_CODA -> segno
                else -> null
            }
            val fine = score.words.firstOrNull { it.kind == WordKind.FINE }?.measure
            val toCoda = score.words.firstOrNull { it.kind == WordKind.TO_CODA }?.measure
            val coda = score.words.lastOrNull { it.kind == WordKind.CODA && (toCoda == null || it.measure > toCoda) }?.measure

            val out = mutableListOf<Int>()
            var i = 0
            var sectionStart = 0
            val repeated = mutableSetOf<Int>()
            val useVolta = score.measures.any { it.repeatEnd }
            var pass = 1
            var passUntil = -1
            var jumped = false
            while (i < n) {
                val m = score.measures[i]
                if (!jumped && m.volta == 0 && i > passUntil) pass = 1
                if (jumped && m.volta != 0) {
                    // nach D.C./D.S.: nur der letzte Schluss einer Klammergruppe
                    if ((i + 1 until minOf(n, i + 8)).any { score.measures[it].volta > m.volta }) { i++; continue }
                } else if (useVolta && m.volta != 0 && m.volta != pass) {
                    if (m.repeatEnd && i in repeated) sectionStart = i + 1
                    i++; continue
                }
                if (m.repeatStart && !jumped) sectionStart = i
                out.add(i)
                if (out.size > 4000) break
                if (jumped) {
                    // Nach dem Sprung: bis Fine bzw. zur Coda, Wiederholungen nicht noch einmal
                    if (fine != null && i == fine && jumpKind in setOf(WordKind.DC, WordKind.DS)) break
                    if (toCoda != null && coda != null && i == toCoda && jumpKind in setOf(WordKind.DC_AL_CODA, WordKind.DS_AL_CODA)) {
                        i = coda; continue
                    }
                    i++; continue
                }
                if (m.repeatEnd && i !in repeated) {
                    repeated.add(i)
                    pass += 1; passUntil = i
                    i = sectionStart
                    continue
                }
                if (m.repeatEnd) sectionStart = i + 1
                if (jumpAt != null && i == jumpAt && jumpTarget != null) {
                    jumped = true
                    i = jumpTarget
                    continue
                }
                i++
            }
            return out
        }

        /** Fermaten eines Takts: (von, bis, Verlängerung) – jede dehnt ihren Akkord etwa auf das Doppelte. */
        private fun holdsOf(m: Measure): List<Triple<Int, Int, Int>> {
            val raw = m.chords.filter { Orn.FERMATA in it.orn && it.duration > 0 }
                .map { Triple(it.start, it.end, it.duration.coerceIn(TPQ / 2, TPQ * 2)) }
                .sortedBy { it.first }
            val merged = mutableListOf<Triple<Int, Int, Int>>()
            for (h in raw) {
                val last = merged.lastOrNull()
                if (last != null && h.first < last.second) merged[merged.size - 1] = Triple(last.first, maxOf(last.second, h.second), maxOf(last.third, h.third))
                else merged.add(h)
            }
            return merged
        }

        /** Tempo relativ zum Grundtempo (1 = wie angegeben) an jeder notierten Stelle. */
        internal class TempoMap(score: Score) {
            private data class Seg(val s: Int, val e: Int, val f0: Float, val f1: Float)
            private val segs = mutableListOf<Seg>()
            private var tail = 1f

            init {
                val evs = score.words.filter { it.kind in setOf(WordKind.RIT, WordKind.ACCEL, WordKind.A_TEMPO, WordKind.TEMPO) }
                    .map { score.abs(it.measure, it.tick) to it }.sortedBy { it.first }
                val total = score.measureStarts.last()
                val baseBpm = evs.firstOrNull { it.second.kind == WordKind.TEMPO && it.first == 0 }?.second?.let { WordKind.tempoOf(it.text) } ?: score.tempo
                var f = 1f
                var stable = 1f
                var pos = 0
                for ((k, ev) in evs.withIndex()) {
                    val (p, w) = ev
                    if (p > pos) segs.add(Seg(pos, p, f, f))
                    pos = maxOf(pos, p)
                    when (w.kind) {
                        WordKind.TEMPO -> { f = (WordKind.tempoOf(w.text) ?: baseBpm).toFloat() / baseBpm; stable = f }
                        WordKind.A_TEMPO -> f = stable
                        else -> {
                            // rit./accel.: über höchstens zwei Takte gleitend, danach gehalten
                            val next = evs.drop(k + 1).firstOrNull()?.first ?: total
                            val len = minOf(next - p, score.measures.getOrNull(w.measure)?.length?.times(2) ?: (TPQ * 8)).coerceAtLeast(1)
                            val target = f * if (w.kind == WordKind.RIT) RIT_FACTOR else ACCEL_FACTOR
                            segs.add(Seg(p, p + len, f, target))
                            pos = p + len
                            f = target
                        }
                    }
                }
                tail = f
                if (pos < total) segs.add(Seg(pos, total, f, f))
            }

            fun at(abs: Int): Float {
                for (s in segs) if (abs >= s.s && abs < s.e) {
                    return s.f0 + (s.f1 - s.f0) * (abs - s.s).toFloat() / (s.e - s.s)
                }
                return tail
            }
        }

        /** Zeitkarte eines Takts aus Tempo (je Schlag) und Fermaten. */
        private fun timeMap(score: Score, mi: Int, tempo: TempoMap): Pair<IntArray, IntArray> {
            val m = score.measures[mi]
            val beat = TPQ * 4 / m.beatType
            val holds = holdsOf(m)
            val cuts = sortedSetOf(0, m.length)
            var b = beat; while (b < m.length) { cuts.add(b); b += beat }
            for ((s, e, _) in holds) { cuts.add(s.coerceIn(0, m.length)); cuts.add(e.coerceIn(0, m.length)) }
            val n = cuts.toIntArray()
            val p = IntArray(n.size)
            var acc = 0.0
            for (i in 0 until n.size - 1) {
                val a = n[i]; val e = n[i + 1]
                val f = tempo.at(score.abs(mi, a)).coerceIn(0.3f, 3f)
                var len = (e - a).toDouble()
                for ((hs, he, extra) in holds) if (a >= hs && e <= he && he > hs) len += extra.toDouble() * (e - a) / (he - hs)
                acc += len / f
                p[i + 1] = Math.round(acc).toInt()
            }
            return n to p
        }

        /** Lautstärke (Anschlagstärke) an jeder notierten Stelle: Dynamikzeichen plus Gabeln/cresc./dim. */
        internal class LevelMap(score: Score) {
            private data class Ev(val pos: Int, val level: Int?, val rampEnd: Int = 0, val cresc: Boolean = true)
            private val evs: List<Ev>
            private val deltas = HashMap<Int, Int>()

            init {
                val list = mutableListOf<Ev>()
                for (d in score.dynamics) d.level?.let { list.add(Ev(score.abs(d.measure, d.tick), it)) }
                val levelsPos = list.map { it.pos to it.level!! }.sortedBy { it.first }
                for (h in score.hairpins) {
                    val s = score.abs(h.measure, h.tick); val e = score.abs(h.endMeasure, h.endTick)
                    if (e > s) list.add(Ev(s, null, e, h.cresc))
                }
                for (w in score.words) if (w.kind == WordKind.CRESC || w.kind == WordKind.DIM) {
                    val s = score.abs(w.measure, w.tick)
                    // bis zum nächsten Dynamikzeichen (höchstens vier Takte), sonst zwei Takte
                    val bar = score.measures.getOrNull(w.measure)?.length ?: (TPQ * 4)
                    val next = levelsPos.firstOrNull { it.first > s }?.first?.takeIf { it - s <= 4 * bar }
                    list.add(Ev(s, null, next ?: (s + 2 * bar), w.kind == WordKind.CRESC))
                }
                evs = list.sortedBy { it.pos }
                // Ziel jeder Gabel: das nächste Dynamikzeichen kurz nach ihrem Ende, sonst ±14
                var lvl = DynamicMark.LEVELS.getValue("mf")
                for ((i, ev) in evs.withIndex()) {
                    if (ev.level != null) { lvl = ev.level; continue }
                    val bar = TPQ * 4
                    val target = levelsPos.firstOrNull { it.first >= ev.pos && it.first <= ev.rampEnd + bar }?.second
                    val delta = if (target != null && (target > lvl) == ev.cresc && target != lvl) target - lvl else if (ev.cresc) 14 else -14
                    deltas[i] = delta
                    lvl = (lvl + delta).coerceIn(20, 118)
                }
            }

            fun at(abs: Int): Int {
                var lvl = DynamicMark.LEVELS.getValue("mf").toFloat()
                for ((i, ev) in evs.withIndex()) {
                    if (ev.pos > abs) break
                    if (ev.level != null) { lvl = ev.level.toFloat(); continue }
                    val d = deltas[i] ?: 0
                    val prog = if (abs >= ev.rampEnd) 1f else (abs - ev.pos).toFloat() / (ev.rampEnd - ev.pos)
                    lvl += d * prog
                }
                return lvl.toInt().coerceIn(20, 118)
            }
        }

        /**
         * „sim.“ (simile): Betonungen des Vortakts gelten in den folgenden Takten derselben Hand weiter –
         * solange der Rhythmus gleich bleibt und keine eigenen Zeichen stehen. Nur für die Wiedergabe.
         */
        fun applySimile(score: Score): Score {
            var s = score
            for (w in score.words.filter { it.kind == WordKind.SIM }) {
                fun arts(c: Chord) = c.accent || c.staccato || c.orn.any { it in setOf(Orn.TENUTO, Orn.STACCATISSIMO, Orn.MARCATO) }
                val staffChords = { mi: Int -> s.measures.getOrNull(mi)?.chords?.filter { it.staff == w.staff && !it.isRest } ?: emptyList() }
                val tm = if (staffChords(w.measure).any(::arts)) w.measure else w.measure - 1
                val template = staffChords(tm)
                if (template.none(::arts)) continue
                val onsets = template.map { it.start }.toSet()
                var k = tm + 1
                while (k < s.measures.size && k - tm <= 16) {
                    val cs = staffChords(k)
                    if (cs.isEmpty() || cs.any(::arts) || cs.map { it.start }.toSet() != onsets) break
                    val byStart = template.associateBy { it.start }
                    s = s.copy(measures = s.measures.mapIndexed { i, m ->
                        if (i != k) m else m.copy(chords = m.chords.map { c ->
                            val t = byStart[c.start]
                            if (c.staff != w.staff || c.isRest || t == null) c
                            else c.copy(accent = t.accent, staccato = t.staccato, orn = c.orn + t.orn.filter { it in setOf(Orn.TENUTO, Orn.STACCATISSIMO, Orn.MARCATO) })
                        })
                    })
                    k++
                }
            }
            return s
        }

        private fun before(m1: Int, t1: Int, m2: Int, t2: Int) = m1 < m2 || (m1 == m2 && t1 <= t2)

        fun build(original: Score, seed: Int = 7): Performance {
            val score = applySimile(original)
            val rnd = Random(seed)
            val notes = mutableListOf<PlayNote>()
            val timeline = mutableListOf<PlayedMeasure>()
            // Offene Haltebögen: (System, MIDI) -> Index in notes
            val openTies = HashMap<Pair<Int, Int>, Int>()
            val tempo = TempoMap(score)
            val levels = LevelMap(score)
            val punches = score.dynamics.filter { it.punch > 0 }
            // una corda: Bereiche (notiert, absolut), in denen leiser gespielt wird
            val corda = mutableListOf<IntRange>()
            run {
                var startUc: Int? = null
                for (w in score.words.sortedBy { score.abs(it.measure, it.tick) }) {
                    val p = score.abs(w.measure, w.tick)
                    if (w.kind == WordKind.UNA_CORDA && startUc == null) startUc = p
                    if (w.kind == WordKind.TRE_CORDE && startUc != null) { corda.add(startUc!! until p); startUc = null }
                }
                startUc?.let { corda.add(it until Int.MAX_VALUE) }
            }

            // Bindebögen: Akkorde innerhalb eines Bogens legato, letzter Ton etwas abgesetzt
            val legato = HashSet<Long>()
            val slurEnd = HashSet<Long>()
            for (sl in score.slurs) {
                val (mf, cf) = score.chordById(sl.from) ?: continue
                val (mt, ct) = score.chordById(sl.to) ?: continue
                if (!before(mf, cf.start, mt, ct.start)) continue
                for (mi in mf..mt) for (c in score.measures[mi].chords) {
                    if (c.staff != cf.staff) continue
                    if (before(mf, cf.start, mi, c.start) && before(mi, c.start, mt, ct.start)) {
                        if (c.id == ct.id || (mi == mt && c.start == ct.start)) slurEnd.add(c.id) else legato.add(c.id)
                    }
                }
            }
            legato.removeAll(slurEnd)

            var t = 0
            for (mi in order(score)) {
                val m = score.measures[mi]
                val beatTicks = TPQ * 4 / m.beatType
                val (nPts, pPts) = timeMap(score, mi, tempo)
                val pm = PlayedMeasure(mi, t, pPts.last(), beatTicks, nPts, pPts)
                timeline.add(pm)
                fun at(n: Int) = t + pm.toPlayed(n)
                fun add(start: Int, end: Int, midi: Int, vel: Int, c: Chord): Int {
                    val s = at(start).coerceAtLeast(0)
                    notes.add(PlayNote(s, (at(end) - s).coerceAtLeast(2), midi, vel.coerceIn(16, 127), c.staff, c.id))
                    return notes.size - 1
                }
                for (c in m.chords.sortedBy { it.start }) {
                    if (c.isRest) continue
                    val absPos = score.abs(mi, c.start)
                    val onBeatOne = c.start == 0
                    val onBeat = c.start % beatTicks == 0
                    var base = levels.at(absPos)
                    if (onBeatOne) base += 8 else if (onBeat) base += 3 else base -= 4
                    if (c.accent) base += 20
                    if (Orn.MARCATO in c.orn) base += 6
                    base += punches.filter { it.measure == mi && it.tick == c.start }
                        .let { ps -> ps.filter { it.staff == c.staff }.ifEmpty { ps } }.maxOfOrNull { it.punch } ?: 0
                    if (corda.any { absPos in it }) base -= 12
                    // Begleitung (linke Hand) etwas zurücknehmen, Melodie tragen lassen.
                    if (c.staff == 1) base -= 7
                    val factor = when {
                        Orn.STACCATISSIMO in c.orn -> 0.3
                        c.staccato -> 0.45
                        Orn.BREATH in c.orn -> 0.75
                        Orn.MARCATO in c.orn -> 0.85
                        c.id in slurEnd -> 0.88
                        Orn.TENUTO in c.orn -> 1.0
                        c.id in legato -> 1.0
                        else -> 0.96
                    }
                    val overlap = if (c.id in legato && !c.staccato) 2 else 0
                    val soundEnd = c.start + (c.duration * factor).toInt().coerceAtLeast(2) + overlap

                    // Vorschläge: ganz kurz vor dem Schlag
                    c.graces.forEachIndexed { k, g ->
                        val gs = c.start - (c.graces.size - k) * 2
                        add(gs, gs + 2, g.midi, base - 10, c)
                    }

                    val top = c.notes.maxByOrNull { it.pitch.midi }
                    val sorted = c.notes.sortedBy { it.pitch.midi }
                    val roll = if (Orn.ARPEGGIO in c.orn) minOf(3, (c.duration / 2) / maxOf(1, sorted.size)) else 0
                    for (n in c.notes) {
                        val vel = base + rnd.nextInt(-3, 4)
                        val key = c.staff to n.pitch.midi
                        val tied = openTies[key]
                        if (n.tieStop && tied != null) {
                            val prev = notes[tied]
                            notes[tied] = prev.copy(length = at(soundEnd) - prev.tick)
                            if (!n.tieStart) openTies.remove(key)
                            continue
                        }
                        val start = c.start + roll * sorted.indexOf(n)
                        val upper = n.pitch.shiftDiatonic(1).let { it.copy(alter = keyAlter(m.fifths, it.step)) }.midi
                        val lower = n.pitch.shiftDiatonic(-1).let { it.copy(alter = keyAlter(m.fifths, it.step)) }.midi
                        val main = n.pitch.midi
                        val orn = if (n === top) c.orn else emptySet()
                        val step = if (c.duration >= 12) 3 else 2
                        val figure: List<Int>? = when {
                            Orn.TRILL in orn && c.duration >= 2 * step -> {
                                val count = (soundEnd - start) / step
                                List(count.coerceAtLeast(2)) { if (it % 2 == 0) main else upper }
                            }
                            Orn.MORDENT in orn -> listOf(main, lower, main)
                            Orn.PRALL in orn -> listOf(main, upper, main)
                            Orn.TURN in orn -> listOf(upper, main, lower, main)
                            Orn.TURN_INV in orn -> listOf(lower, main, upper, main)
                            Orn.TREMOLO in c.orn && c.duration >= 2 * step -> List((soundEnd - start) / step) { main }
                            else -> null
                        }
                        if (figure != null) {
                            // Verzierung ausspielen; der letzte Ton hält bis zum Ende
                            val turnStep = if (Orn.TURN in orn || Orn.TURN_INV in orn) minOf(step, (soundEnd - start) / 4).coerceAtLeast(1) else step
                            var s = start
                            figure.forEachIndexed { k, midi ->
                                val last = k == figure.size - 1
                                val e = if (last) soundEnd else minOf(s + turnStep, soundEnd)
                                val idx = add(s, e, midi, if (k == 0) vel else vel - 8, c)
                                if (last && n.tieStart) openTies[key] = idx
                                s = e
                            }
                            continue
                        }
                        val idx = add(start, soundEnd, main, vel, c)
                        if (n.tieStart) openTies[key] = idx
                    }
                }
                t += pm.length
            }
            notes.sortBy { it.tick }
            val pedals = timeline.flatMap { pm ->
                score.pedals.filter { it.measure == pm.measureIndex }.map { PlayPedal(pm.tick + pm.toPlayed(it.tick), it.type) }
            }.sortedBy { it.tick }
            return Performance(notes, timeline, pedals)
        }
    }
}
