package com.nicedevelopments.vomblatt.model

/** Korrekturen an einzelnen Noten. Jede Funktion liefert eine neue Partitur. */
object ScoreEdit {

    private fun Score.measureOf(id: Long): Int = measures.indexOfFirst { m -> m.chords.any { it.id == id } }

    /** Balken des Takts zurücksetzen – nach einer Änderung setzt der Notensatz sie neu. */
    private fun Measure.rebeam(): Measure = copy(chords = chords.map { it.copy(beam = Beam.NONE) })

    private fun Score.updateMeasure(index: Int, f: (Measure) -> Measure): Score =
        copy(measures = measures.mapIndexed { i, m -> if (i == index) f(m) else m })

    /** Ton um Stammtonschritte verschieben; das Vorzeichen folgt der Tonart. */
    fun step(score: Score, chordId: Long, noteIndex: Int, steps: Int): Score {
        val mi = score.measureOf(chordId); if (mi < 0) return score
        val fifths = score.measures[mi].fifths
        return score.replaceChord(chordId) { c ->
            c.copy(notes = c.notes.mapIndexed { i, n ->
                if (i != noteIndex) n else {
                    val p = n.pitch.shiftDiatonic(steps)
                    n.copy(pitch = p.copy(alter = keyAlter(fifths, p.step)))
                }
            }.sortedBy { it.pitch.diatonic })
        }
    }

    fun setAlter(score: Score, chordId: Long, noteIndex: Int, alter: Int): Score =
        score.replaceChord(chordId) { c ->
            c.copy(notes = c.notes.mapIndexed { i, n -> if (i == noteIndex) n.copy(pitch = n.pitch.copy(alter = alter)) else n })
        }

    /**
     * Notenwert ändern. Ging der Takt dieser Stimme auf, bleibt er aufgehend (Fable-Prüfung 19.11.: sonst roter Punkt):
     * Kürzen lässt eine Pause zurück, die folgenden Noten bleiben stehen; Verlängern schiebt die folgenden nach hinten
     * und nimmt dafür Pausen am Taktende weg. Ging er nicht auf (Erkennungsfehler im Rhythmus), rücken die folgenden
     * Ereignisse wie bisher nach.
     */
    fun setDuration(score: Score, chordId: Long, type: NoteType, dots: Int): Score {
        val mi = score.measureOf(chordId); if (mi < 0) return score
        var nextId = score.nextId()
        return score.updateMeasure(mi) { m ->
            val c = m.chords.first { it.id == chordId }
            val newDur = dotted(type.ticks, dots)
            val delta = newDur - c.duration
            val voice = m.chords.filter { it.staff == c.staff && it.voice == c.voice }
            val fitted = !c.wholeMeasureRest && voice.maxOf { it.end } == m.length
            val changed = c.copy(type = type, dots = dots, duration = newDur, wholeMeasureRest = false)
            if (fitted && delta < 0) {
                var at = c.start + newDur
                val rests = pieces(-delta).map { (ty, d) -> Chord(nextId++, c.staff, c.voice, at, ty, d, emptyList()).also { at += dotted(ty.ticks, d) } }
                return@updateMeasure m.copy(chords = m.chords.map { if (it.id == chordId) changed else it } + rests).rebeam()
            }
            var chords = m.chords.map {
                when {
                    it.id == chordId -> changed
                    it.staff == c.staff && it.voice == c.voice && it.start > c.start -> it.copy(start = it.start + delta)
                    else -> it
                }
            }
            if (fitted && delta > 0) {
                // überstehende Pausen am Taktende kürzen bzw. weglassen; Noten bleiben (dann zeigt der Punkt das Zuviel)
                chords = chords.flatMap { ch ->
                    if (ch.staff != c.staff || ch.voice != c.voice || !ch.isRest || ch.end <= m.length) listOf(ch)
                    else if (ch.start >= m.length) emptyList()
                    else {
                        var at = ch.start
                        pieces(m.length - ch.start).map { (ty, d) -> Chord(nextId++, ch.staff, ch.voice, at, ty, d, emptyList()).also { at += dotted(ty.ticks, d) } }
                    }
                }
            }
            m.copy(chords = chords.filter { it.start < m.length }).rebeam()
        }
    }

    /**
     * Was im Takt je System nicht aufgeht: (System, Ticks) – positiv = fehlt, negativ = zu viel. Maßgeblich ist die
     * längste bzw. kürzeste Stimme des Systems; ganztaktige Pausen gehen immer auf.
     */
    fun balance(m: Measure): List<Pair<Int, Int>> = (0..1).mapNotNull { staff ->
        val voices = m.chords.filter { it.staff == staff }.groupBy { it.voice }.values
            .filterNot { v -> v.size == 1 && v[0].wholeMeasureRest }
        if (voices.isEmpty()) return@mapNotNull null
        val diffs = voices.map { v -> m.length - v.maxOf { it.end } }
        val d = diffs.firstOrNull { it < 0 } ?: diffs.firstOrNull { it > 0 } ?: return@mapNotNull null
        staff to d
    }

    /** Einzelnen Ton entfernen; war es der letzte, wird der Akkord zur Pause. */
    fun deleteNote(score: Score, chordId: Long, noteIndex: Int): Score {
        val mi = score.measureOf(chordId); if (mi < 0) return score
        return score.updateMeasure(mi) { m ->
            m.copy(chords = m.chords.map { c ->
                if (c.id != chordId) c
                else if (noteIndex < 0 || c.notes.size <= 1) c.copy(notes = emptyList(), accent = false, staccato = false)
                else c.copy(notes = c.notes.filterIndexed { i, _ -> i != noteIndex })
            }).rebeam()
        }
    }

    /** Ton zum Akkord hinzufügen (eine Terz über dem obersten) oder aus einer Pause eine Note machen. */
    fun addNote(score: Score, chordId: Long): Score {
        val mi = score.measureOf(chordId); if (mi < 0) return score
        val m = score.measures[mi]
        return score.replaceChord(chordId) { c ->
            val base = c.notes.maxByOrNull { it.pitch.diatonic }?.pitch?.shiftDiatonic(2)
                ?: if (m.clefAt(c.staff, c.start) == Clef.TREBLE) Pitch(6, 4, 0) else Pitch(1, 3, 0)
            val p = base.copy(alter = keyAlter(m.fifths, base.step))
            c.copy(notes = (c.notes + Note(p)).sortedBy { it.pitch.diatonic })
        }
    }

    /** Pedal an Taktstelle setzen oder – wenn dort schon dieselbe Art steht – entfernen. */
    fun togglePedal(score: Score, measure: Int, tick: Int, press: Boolean): Score {
        val here = score.pedals.firstOrNull { it.measure == measure && it.tick == tick }
        val type = if (!press) PedalType.UP else if (score.pedalDownBefore(measure, tick)) PedalType.CHANGE else PedalType.DOWN
        val rest = score.pedals.filterNot { it.measure == measure && it.tick == tick }
        val result = if (here != null && (here.type == PedalType.UP) == !press) rest else rest + PedalMark(measure, tick, type)
        return score.copy(pedals = result.sortedWith(compareBy({ it.measure }, { it.tick })))
    }

    /**
     * Ältere gespeicherte Fassungen kannten Dynamik, Bögen, Verzierungen usw. noch nicht. Diese Zeichen
     * aus dem (neu gelesenen) Erkennungsergebnis übernehmen; Korrekturen des Nutzers bleiben erhalten.
     * Die Akkord-IDs stimmen überein, weil beide Fassungen aus derselben MusicXML-Datei stammen.
     */
    fun upgradeFrom(saved: Score, source: Score): Score {
        val byId = source.measures.flatMap { it.chords }.associateBy { it.id }
        val ids = saved.measures.flatMap { m -> m.chords.map { it.id } }.toSet()
        return saved.copy(
            measures = saved.measures.mapIndexed { mi, m ->
                val sm = source.measures.getOrNull(mi)
                m.copy(
                    volta = sm?.volta ?: 0, voltaClosed = sm?.voltaClosed ?: false,
                    clefChanges = sm?.clefChanges ?: emptyList(),
                    chords = m.chords.map { c ->
                        val o = byId[c.id] ?: return@map c
                        if (c.isRest != o.isRest) c
                        else c.copy(orn = o.orn, tuplet = o.tuplet, graces = o.graces, ottava = o.ottava)
                    },
                )
            },
            dynamics = source.dynamics.filter { it.measure < saved.measures.size },
            slurs = source.slurs.filter { it.from in ids && it.to in ids },
        )
    }

    /** Oktave höher (+1) oder tiefer (−1); das Vorzeichen bleibt. */
    fun octave(score: Score, chordId: Long, noteIndex: Int, dir: Int): Score =
        score.replaceChord(chordId) { c ->
            c.copy(notes = c.notes.mapIndexed { i, n -> if (i == noteIndex) n.copy(pitch = n.pitch.shiftDiatonic(7 * dir)) else n }
                .sortedBy { it.pitch.diatonic })
        }

    /** Index des Tons [midi] im Akkord – nach einer Änderung folgt die Auswahl dem Ton (die Töne sind nach Höhe sortiert). */
    /** Erster Akkord (nach Takt und Zeit), der in [after] anders ist als in [before] oder neu dazukam. */
    fun changedChord(before: Score, after: Score): Long? {
        val old = before.measures.flatMap { it.chords }.associateBy { it.id }
        for (m in after.measures) for (c in m.chords.sortedBy { it.start }) if (old[c.id] != c) return c.id
        return null
    }

    fun indexOf(score: Score, chordId: Long, midi: Int): Int =
        score.chordById(chordId)?.second?.notes?.indexOfFirst { it.pitch.midi == midi } ?: -1

    /**
     * Zeitlich verschieben: mit dem vorigen (−1) bzw. nächsten (+1) Ereignis derselben Stimme im Takt tauschen.
     * Beide behalten ihre Dauer; der Takt bleibt gleich lang. Am Taktrand passiert nichts.
     */
    fun swapWithNeighbor(score: Score, chordId: Long, dir: Int): Score {
        val mi = score.measureOf(chordId); if (mi < 0) return score
        val m = score.measures[mi]
        val c = m.chords.first { it.id == chordId }
        val voice = m.chords.filter { it.staff == c.staff && it.voice == c.voice }.sortedBy { it.start }
        val k = voice.indexOfFirst { it.id == chordId }
        val o = voice.getOrNull(k + dir) ?: return score
        val (a, b) = if (dir > 0) c to o else o to c // a steht vorn, b dahinter
        val start = a.start
        return score.updateMeasure(mi) { mm ->
            mm.copy(chords = mm.chords.map {
                when (it.id) {
                    b.id -> it.copy(start = start)
                    a.id -> it.copy(start = start + b.duration)
                    else -> it
                }
            }).rebeam()
        }
    }

    /** Dauer in darstellbare Notenwerte zerlegen (größte zuerst, auch punktiert). */
    internal fun pieces(ticks: Int): List<Pair<NoteType, Int>> {
        val cands = NoteType.entries.flatMap { t -> listOf(t to 1, t to 0) }.sortedByDescending { (t, d) -> dotted(t.ticks, d) }
        val out = ArrayList<Pair<NoteType, Int>>()
        var left = ticks
        while (left > 0) {
            val p = cands.firstOrNull { (t, d) -> dotted(t.ticks, d) <= left } ?: break
            out.add(p); left -= dotted(p.first.ticks, p.second)
        }
        return out
    }

    /** Darstellbar als ein einziger Notenwert? */
    private fun single(ticks: Int): Pair<NoteType, Int>? = pieces(ticks).singleOrNull()

    private fun Score.nextId(): Long = (measures.flatMap { m -> m.chords.map { it.id } }.maxOrNull() ?: 0L) + 1

    /**
     * Neue Note mit der notierten Stammtonnummer [diatonic] in Takt [measure], System [staff] bei [tick] einsetzen –
     * dort, wo man im Notenbild gedrückt hält. Steht dort schon ein Akkord, kommt der Ton dazu; steht dort eine Pause,
     * wird (ein Teil von) ihr zur Note mit [want] Ticks; mitten in einem Ereignis wird es geteilt. Das Vorzeichen folgt
     * der Tonart. Liefert die neue Partitur und die Auswahl (Akkord, Ton) – oder die alte Partitur und null.
     */
    fun insertAt(score: Score, measure: Int, staff: Int, tick: Int, diatonic: Int, want: Int = TPQ): Pair<Score, Pair<Long, Int>?> {
        val m = score.measures.getOrNull(measure) ?: return score to null
        var t = tick.coerceIn(0, m.length - 1)
        val pitch = Pitch(Math.floorMod(diatonic, 7), Math.floorDiv(diatonic, 7), 0).let { it.copy(alter = keyAlter(m.fifths, it.step)) }
        val inStaff = m.chords.filter { it.staff == staff }
        fun withNote(c: Chord, p: Pitch): Chord {
            val sounding = Pitch(p.step, p.octave + c.ottava, p.alter) // Tonhöhen sind klingend gespeichert
            if (c.notes.any { it.pitch.midi == sounding.midi }) return c
            return c.copy(notes = (c.notes + Note(sounding)).sortedBy { it.pitch.diatonic }, wholeMeasureRest = false)
        }
        fun result(s: Score, id: Long, p: Pitch): Pair<Score, Pair<Long, Int>?> {
            val c = s.chordById(id)?.second ?: return s to null
            val sounding = Pitch(p.step, p.octave + c.ottava, p.alter)
            return s to (id to c.notes.indexOfFirst { it.pitch.midi == sounding.midi })
        }
        // 1. Akkord, der genau hier beginnt: Ton dazu (bzw. Pause wird Note)
        inStaff.firstOrNull { it.start == t && !it.isRest }?.let { c ->
            return result(score.replaceChord(c.id) { withNote(it, pitch) }, c.id, pitch)
        }
        // 2. Ereignis, das hier klingt oder pausiert (eine ganztaktige Pause zählt über den ganzen Takt)
        val cover = inStaff.firstOrNull { (it.start <= t && t < it.end) || it.wholeMeasureRest }
        var nextId = score.nextId()
        if (cover == null) {
            // leere Stimme: Pausen bis zur Stelle, dann die Note
            val voice = if (staff == 0) 1 else 5
            val add = ArrayList<Chord>()
            var at = 0
            for ((ty, d) in pieces(t)) { add.add(Chord(nextId++, staff, voice, at, ty, d, emptyList())); at += dotted(ty.ticks, d) }
            val (ty, d) = pieces(minOf(want, m.length - t)).first()
            val id = nextId++
            add.add(Chord(id, staff, voice, t, ty, d, listOf(Note(pitch))))
            return result(score.updateMeasure(measure) { it.copy(chords = it.chords + add).rebeam() }, id, pitch)
        }
        val cs = if (cover.wholeMeasureRest) 0 else cover.start
        val ce = if (cover.wholeMeasureRest) m.length else cover.end
        // Triolen nicht zerteilen: der Ton kommt an ihren Anfang
        if (cover.tuplet != 0) return result(score.replaceChord(cover.id) { withNote(it, pitch) }, cover.id, pitch)
        // ergibt der Teil davor keinen einzelnen Notenwert, auf die nächste darstellbare Stelle davor rücken
        t = if (t > cs && single(t - cs) == null) cs + pieces(t - cs).first().let { (ty, d) -> dotted(ty.ticks, d) } else t
        if (t == cs && !cover.isRest) return result(score.replaceChord(cover.id) { withNote(it, pitch) }, cover.id, pitch)
        val newChords = ArrayList<Chord>()
        var selId: Long
        if (cover.isRest) {
            // Pause: davor Pause, dann die Note (höchstens [want]), danach wieder Pause
            var at = cs
            for ((ty, d) in pieces(t - cs)) { newChords.add(Chord(nextId++, staff, cover.voice, at, ty, d, emptyList())); at += dotted(ty.ticks, d) }
            val (ty, d) = pieces(minOf(want, ce - t)).first()
            selId = if (t == cs) cover.id else nextId++
            newChords.add(Chord(selId, staff, cover.voice, t, ty, d, listOf(Note(pitch))))
            at = t + dotted(ty.ticks, d)
            for ((ty2, d2) in pieces(ce - at)) { newChords.add(Chord(nextId++, staff, cover.voice, at, ty2, d2, emptyList())); at += dotted(ty2.ticks, d2) }
        } else {
            // klingende Note: sie endet hier; die neue Note bekommt den gewünschten Wert (höchstens bis zum Ende der
            // alten), der Rest wird Pause
            val (ty1, d1) = single(t - cs)!!
            newChords.add(cover.copy(type = ty1, dots = d1, duration = dotted(ty1.ticks, d1), notes = cover.notes.map { it.copy(tieStart = false) }))
            var at = t
            val (tn, dn) = pieces(minOf(want, ce - t)).first()
            selId = nextId++
            newChords.add(Chord(selId, staff, cover.voice, at, tn, dn, listOf(Note(pitch))))
            at += dotted(tn.ticks, dn)
            for ((ty2, d2) in pieces(ce - at)) { newChords.add(Chord(nextId++, staff, cover.voice, at, ty2, d2, emptyList())); at += dotted(ty2.ticks, d2) }
        }
        val s = score.updateMeasure(measure) { mm ->
            mm.copy(chords = (mm.chords.filter { it.id != cover.id } + newChords).sortedWith(compareBy({ it.staff }, { it.start }))).rebeam()
        }
        return result(s, selId, pitch)
    }

    /**
     * Ton in das andere System verschieben (homr verwechselt gern die Hände): er verlässt seinen Akkord (war es der letzte,
     * bleibt eine Pause) und kommt zur selben Zeit mit demselben Notenwert ins andere System, gleiche Tonhöhe.
     */
    fun moveToStaff(score: Score, chordId: Long, noteIndex: Int): Pair<Score, Pair<Long, Int>?> {
        val (mi, c) = score.chordById(chordId) ?: return score to null
        val n = c.notes.getOrNull(noteIndex) ?: return score to null
        val written = n.pitch.diatonic - 7 * c.ottava
        val s1 = deleteNote(score, chordId, noteIndex)
        val (s2, sel) = insertAt(s1, mi, 1 - c.staff, c.start, written, c.duration)
        if (sel == null) return score to null
        // Vorzeichen des Tons beibehalten (nicht aus der Tonart neu ableiten)
        return setAlter(s2, sel.first, sel.second, n.pitch.alter) to sel
    }

    fun toggleAccent(score: Score, chordId: Long): Score = score.replaceChord(chordId) { it.copy(accent = !it.accent) }

    fun toggleTie(score: Score, chordId: Long, noteIndex: Int): Score {
        // Haltebogen beginnt hier; der nächste gleiche Ton derselben Hand bekommt das Ende.
        val (mi, c) = score.chordById(chordId) ?: return score
        val n = c.notes.getOrNull(noteIndex) ?: return score
        val starting = !n.tieStart
        var s = score.replaceChord(chordId) { ch ->
            ch.copy(notes = ch.notes.mapIndexed { i, x -> if (i == noteIndex) x.copy(tieStart = starting) else x })
        }
        val m = s.measures[mi]
        val target = (m.chords.filter { it.staff == c.staff && it.start >= c.end }.sortedBy { it.start } +
            (s.measures.getOrNull(mi + 1)?.chords?.filter { it.staff == c.staff && it.start == 0 } ?: emptyList()))
            .firstOrNull { t -> t.notes.any { it.pitch.midi == n.pitch.midi } }
        if (target != null) s = s.replaceChord(target.id) { t ->
            t.copy(notes = t.notes.map { if (it.pitch.midi == n.pitch.midi) it.copy(tieStop = starting) else it })
        }
        return s
    }
}
