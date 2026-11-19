package com.nicedevelopments.vomblatt.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoMarksTest {
    @Test fun akkordsymbole_erkennen() {
        assertEquals("Bb", PhotoMarks.chordOf("B♭"))
        assertEquals("F/G", PhotoMarks.chordOf("F/G"))
        assertEquals("Em/B", PhotoMarks.chordOf("Em/B"))
        assertEquals("Dm7", PhotoMarks.chordOf("Dm7"))
        assertEquals("Csus4", PhotoMarks.chordOf("Csus4"))
        assertNull(PhotoMarks.chordOf("Synkope"))
        assertNull(PhotoMarks.chordOf("38"))
        assertNull(PhotoMarks.chordOf("Florian"))
    }

    /** Ein System (Linien bei y 100–140 und 200–240, ls = 10), zwei Takte mit je zwei Vierteln … */
    private fun score(): Score {
        fun c(id: Long, start: Int, x: Float, staff: Int = 0) = Chord(
            id, staff, 1, start, NoteType.HALF, 0,
            listOf(Note(Pitch(0, if (staff == 0) 5 else 3, 0), imgX = x, imgY = if (staff == 0) 120f else 220f)),
        )
        val m1 = Measure(4, 4, 0, listOf(Clef.TREBLE, Clef.BASS), listOf(c(1, 0, 100f), c(2, 48, 200f), c(3, 0, 100f, 1)))
        val m2 = Measure(4, 4, 0, listOf(Clef.TREBLE, Clef.BASS), listOf(c(4, 0, 300f), c(5, 48, 400f), c(6, 0, 300f, 1)))
        return Score("T", 100, listOf(m1, m2))
    }

    private val sys = listOf(SystemGeom(100f, 140f, 200f, 240f, 20f, 500f, 10f))
    private fun t(s: String, l: Float, top: Float) = OcrText(s, l, top, l + 10f * s.length, top + 12f, listOf(OcrText(s, l, top, l + 10f * s.length, top + 12f)))

    @Test fun fingersaetze_werden_den_noten_zugeordnet() {
        // Akkord C-E-G (rechte Hand) bei x=100, darüber gestapelt „5 3 1“; Bass-Ton bei x=300 mit „2“ darunter
        val chord = Chord(1, 0, 1, 0, NoteType.HALF, 0, listOf(
            Note(Pitch(0, 4, 0), imgX = 100f, imgY = 130f), Note(Pitch(2, 4, 0), imgX = 100f, imgY = 125f), Note(Pitch(4, 4, 0), imgX = 100f, imgY = 120f),
        ))
        val bass = Chord(2, 1, 1, 0, NoteType.HALF, 0, listOf(Note(Pitch(0, 3, 0), imgX = 300f, imgY = 225f)))
        val s = Score("T", 100, listOf(Measure(4, 4, 0, listOf(Clef.TREBLE, Clef.BASS), listOf(chord, bass))))
        fun d(t: String, x: Float, y: Float) = OcrText(t, x - 4, y - 5, x + 4, y + 5)
        val out = PhotoMarks.fingerings(s, listOf(d("5", 100f, 70f), d("3", 100f, 82f), d("1", 100f, 94f), d("2", 300f, 260f), d("12", 30f, 80f)), sys)
        val fs = out.measures[0].chords.first { it.id == 1L }.notes.map { it.finger }
        assertEquals(listOf(1, 3, 5), fs) // C=1, E=3, G=5
        assertEquals(2, out.measures[0].chords.first { it.id == 2L }.notes.single().finger)
        assertEquals(out, ScoreJson.read(ScoreJson.write(out)))
    }

    @Test fun text_wird_den_takten_zugeordnet() {
        val texts = listOf(
            t("C", 96f, 75f), t("G7", 298f, 75f),            // Akkorde über dem System
            t("9B", 30f, 80f),                                // Formteil links vom ersten Ton, mit Taktzahl gelesen
            t("sim.", 198f, 165f),                            // zwischen den Systemen
            t("una corda", 296f, 255f),                       // unter dem Bass-System
            t("4", 100f, 88f),                                // Fingersatz
            t("♩ = 120", 60f, 60f),                           // Tempoangabe – liest der Kopf
            OcrText("Andante", 96f, 62f, 170f, 74f),          // Tempowort
            t("Fine", 430f, 80f),
        )
        val pins = listOf(PhotoHairpin(95f, 260f, 170f, true, 0))
        val s = PhotoMarks.place(score(), texts, pins, sys)
        assertEquals(listOf(ChordSymbol(0, 0, "C"), ChordSymbol(1, 0, "G7")), s.chordSymbols)
        assertEquals(
            listOf(0 to WordKind.REHEARSAL, 0 to WordKind.TEMPO, 0 to WordKind.SIM, 1 to WordKind.UNA_CORDA, 1 to WordKind.FINE),
            s.words.map { it.measure to it.kind }.sortedBy { it.first },
        )
        assertEquals(1, s.words.first { it.kind == WordKind.UNA_CORDA }.staff)
        assertEquals(80, s.tempo) // Andante, weil keine Metronomzahl vom Blatt
        assertEquals(listOf(Hairpin(0, 0, 0, 96, true)), s.hairpins)
        assertEquals(100, PhotoMarks.place(score(), texts, pins, sys, hasSheetTempo = true).tempo)
        // F-Dur: „B“ ohne ♭ gelesen ist der B♭-Akkord; „Bb“ bleibt, in C-Dur bleibt „B“
        val f = score().let { sc -> sc.copy(measures = sc.measures.map { it.copy(fifths = -1) }) }
        assertEquals(listOf("Bb", "Bbm"), PhotoMarks.place(f, listOf(t("B", 96f, 75f), t("Bbm", 298f, 75f)), emptyList(), sys).chordSymbols.map { it.text })
        assertEquals("H", PhotoMarks.place(f, listOf(t("H", 96f, 75f)), emptyList(), sys).chordSymbols.single().text)
        assertEquals("B", PhotoMarks.place(score(), listOf(t("B", 96f, 75f)), emptyList(), sys).chordSymbols.single().text)
    }
}
