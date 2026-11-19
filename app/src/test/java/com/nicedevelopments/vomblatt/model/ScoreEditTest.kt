package com.nicedevelopments.vomblatt.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Korrekturen: Oktave, zeitlich tauschen, an Stelle und Tonhöhe einsetzen, ins andere System verschieben. */
class ScoreEditTest {
    private fun p(step: Int, octave: Int, alter: Int = 0) = Pitch(step, octave, alter)
    private fun note(id: Long, staff: Int, start: Int, type: NoteType, vararg ps: Pitch, dots: Int = 0) =
        Chord(id, staff, if (staff == 0) 1 else 5, start, type, dots, ps.map { Note(it) })
    private fun rest(id: Long, staff: Int, start: Int, type: NoteType) = Chord(id, staff, if (staff == 0) 1 else 5, start, type, 0, emptyList())
    private fun score(vararg chords: Chord) =
        Score("T", 100, listOf(Measure(4, 4, 0, listOf(Clef.TREBLE, Clef.BASS), chords.toList())))
    private fun Score.voice(staff: Int) = measures[0].chords.filter { it.staff == staff }.sortedBy { it.start }
    private fun Score.ok() = assertFalse("Takt geht nicht auf", measures[0].suspicious())

    /** Notierte Stammtonnummer (wie das Notenbild sie aus der Höhe ermittelt). */
    private fun d(step: Int, octave: Int) = octave * 7 + step

    private val bass = rest(100, 1, 0, NoteType.WHOLE).copy(wholeMeasureRest = true, duration = 96)

    @Test fun oktave_hoch_behaelt_vorzeichen() {
        val s = score(note(1, 0, 0, NoteType.WHOLE, p(3, 4, 1)), bass)
        val r = ScoreEdit.octave(s, 1, 0, +1)
        assertEquals(p(3, 5, 1), r.chordById(1)!!.second.notes[0].pitch)
    }

    @Test fun spaeter_tauscht_mit_der_naechsten_note() {
        val s = score(
            note(1, 0, 0, NoteType.QUARTER, p(0, 4)), note(2, 0, 24, NoteType.QUARTER, p(1, 4)),
            note(3, 0, 48, NoteType.QUARTER, p(2, 4)), note(4, 0, 72, NoteType.QUARTER, p(3, 4)), bass,
        )
        val r = ScoreEdit.swapWithNeighbor(s, 2, +1)
        assertEquals(listOf(1L, 3L, 2L, 4L), r.voice(0).map { it.id })
        assertEquals(listOf(0, 24, 48, 72), r.voice(0).map { it.start })
        r.ok()
    }

    @Test fun frueher_mit_unterschiedlichen_dauern() {
        val s = score(note(1, 0, 0, NoteType.HALF, p(0, 4)), note(2, 0, 48, NoteType.QUARTER, p(1, 4)), note(3, 0, 72, NoteType.QUARTER, p(2, 4)), bass)
        val r = ScoreEdit.swapWithNeighbor(s, 2, -1)
        assertEquals(listOf(2L to 0, 1L to 24, 3L to 72), r.voice(0).map { it.id to it.start })
        r.ok()
        // am Taktanfang passiert nichts
        assertEquals(r, ScoreEdit.swapWithNeighbor(r, 2, -1))
    }

    @Test fun einsetzen_in_eine_pause_teilt_sie() {
        val s = score(note(1, 0, 0, NoteType.HALF, p(0, 4)), rest(2, 0, 48, NoteType.HALF), bass)
        val (r, sel) = ScoreEdit.insertAt(s, 0, 0, 72, d(4, 4), want = 24)
        assertNotNull(sel)
        val v = r.voice(0)
        assertEquals(listOf(0, 48, 72), v.map { it.start })
        assertEquals(listOf(false, true, false), v.map { it.isRest })
        assertEquals(p(4, 4), r.chordById(sel!!.first)!!.second.notes[sel.second].pitch)
        r.ok()
    }

    @Test fun einsetzen_am_akkordanfang_fuegt_ton_hinzu() {
        val s = score(note(1, 0, 0, NoteType.WHOLE, p(0, 4)), bass)
        val (r, sel) = ScoreEdit.insertAt(s, 0, 0, 0, d(2, 4))
        assertEquals(1L, sel!!.first)
        assertEquals(listOf(p(0, 4), p(2, 4)), r.chordById(1)!!.second.notes.map { it.pitch })
        r.ok()
    }

    @Test fun einsetzen_mitten_in_einer_note_teilt_sie() {
        val s = score(note(1, 0, 0, NoteType.WHOLE, p(0, 4)), bass)
        val (r, sel) = ScoreEdit.insertAt(s, 0, 0, 48, d(2, 4), want = 24)
        val v = r.voice(0)
        // C wird Halbe, die neue Note Viertel (gewünschter Wert), der Rest Pause
        assertEquals(listOf(NoteType.HALF to 0, NoteType.QUARTER to 48, NoteType.QUARTER to 72), v.map { it.type to it.start })
        assertEquals(listOf(false, false, true), v.map { it.isRest })
        assertEquals(p(2, 4), r.chordById(sel!!.first)!!.second.notes.single().pitch)
        r.ok()
    }

    @Test fun einsetzen_in_ganztaktige_pause_links() {
        val s = score(note(1, 0, 0, NoteType.WHOLE, p(0, 4)), bass)
        val (r, sel) = ScoreEdit.insertAt(s, 0, 1, 24, d(0, 3), want = 24)
        val v = r.voice(1)
        assertEquals(listOf(true, false, true), v.map { it.isRest })
        assertEquals(listOf(0, 24, 48), v.map { it.start })
        assertEquals(p(0, 3), r.chordById(sel!!.first)!!.second.notes.single().pitch)
        r.ok()
    }

    @Test fun einsetzen_folgt_der_tonart() {
        val s = score(note(1, 0, 0, NoteType.WHOLE, p(0, 4)), bass)
            .let { it.copy(measures = it.measures.map { m -> m.copy(fifths = -1) }) } // F-Dur: H wird B
        val (r, sel) = ScoreEdit.insertAt(s, 0, 0, 0, d(6, 4))
        assertEquals(-1, r.chordById(sel!!.first)!!.second.notes[sel.second].pitch.alter)
    }

    @Test fun andere_hand() {
        val s = score(note(1, 0, 0, NoteType.HALF, p(0, 4), p(2, 4)), rest(2, 0, 48, NoteType.HALF), bass)
        val (r, sel) = ScoreEdit.moveToStaff(s, 1, 1) // E4 nach unten
        assertEquals(listOf(p(0, 4)), r.chordById(1)!!.second.notes.map { it.pitch })
        val moved = r.chordById(sel!!.first)!!.second
        assertEquals(1, moved.staff)
        assertEquals(0, moved.start)
        assertEquals(NoteType.HALF, moved.type)
        assertEquals(p(2, 4), moved.notes[sel.second].pitch)
        r.ok()
    }

    @Test fun kuerzen_im_aufgehenden_takt_laesst_pause() {
        val s = score(note(1, 0, 0, NoteType.HALF, p(0, 4)), note(2, 0, 48, NoteType.HALF, p(2, 4)), bass)
        val r = ScoreEdit.setDuration(s, 1, NoteType.QUARTER, 0)
        assertEquals(listOf(0 to false, 24 to true, 48 to false), r.voice(0).map { it.start to it.isRest })
        r.ok()
    }

    @Test fun verlaengern_nimmt_pause_am_ende_weg() {
        val s = score(note(1, 0, 0, NoteType.QUARTER, p(0, 4)), note(2, 0, 24, NoteType.QUARTER, p(2, 4)), rest(3, 0, 48, NoteType.HALF), bass)
        val r = ScoreEdit.setDuration(s, 1, NoteType.HALF, 0)
        assertEquals(listOf(0 to false, 48 to false, 72 to true), r.voice(0).map { it.start to it.isRest })
        r.ok()
    }

    @Test fun rhythmusfehler_ruecken_weiter_nach() {
        // Takt geht nicht auf (Erkennung: Halbe statt Viertel) – nach dem Korrigieren rücken die Folgenden vor
        val s = score(note(1, 0, 0, NoteType.HALF, p(0, 4)), note(2, 0, 48, NoteType.QUARTER, p(2, 4)),
            note(3, 0, 72, NoteType.QUARTER, p(4, 4)), note(4, 0, 96, NoteType.QUARTER, p(5, 4)), bass)
        val r = ScoreEdit.setDuration(s, 1, NoteType.QUARTER, 0)
        assertEquals(listOf(0, 24, 48, 72), r.voice(0).map { it.start })
        r.ok()
    }

    @Test fun taktbilanz() {
        val s = score(note(1, 0, 0, NoteType.HALF, p(0, 4)), note(2, 0, 48, NoteType.QUARTER, p(2, 4)), bass)
        assertEquals(listOf(0 to 24), ScoreEdit.balance(s.measures[0]))
    }

    @Test fun zerlegen_in_notenwerte() {
        assertEquals(listOf(NoteType.HALF to 1), ScoreEdit.pieces(72))
        assertEquals(listOf(NoteType.HALF to 0, NoteType.SIXTEENTH to 0), ScoreEdit.pieces(54))
    }
}
