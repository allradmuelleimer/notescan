package com.nicedevelopments.vomblatt.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Stufe 2: Wörter, Akkordsymbole, Gabeln, Sprünge – lesen, speichern, spielen. Prüfblatt: tools/make_s2_test.py */
class Stufe2Test {
    private val s = File("src/test/resources/stufe2.musicxml").inputStream().use { MusicXml.parse(it) }

    @Test fun woerter_werden_erkannt() {
        assertEquals(WordKind.DC, WordKind.of("D.C. al Fine"))
        assertEquals(WordKind.DS_AL_CODA, WordKind.of("D.S. al Coda"))
        assertEquals(WordKind.RIT, WordKind.of("poco rit."))
        assertEquals(WordKind.RIT, WordKind.of("rall."))
        assertEquals(WordKind.A_TEMPO, WordKind.of("a tempo"))
        assertEquals(WordKind.CRESC, WordKind.of("poco a poco cresc."))
        assertEquals(WordKind.DIM, WordKind.of("dim."))
        assertEquals(WordKind.SIM, WordKind.of("sim."))
        assertEquals(WordKind.UNA_CORDA, WordKind.of("una corda"))
        assertEquals(WordKind.TEMPO, WordKind.of("Allegro moderato"))
        assertEquals(118, WordKind.tempoOf("Allegro moderato"))
        assertEquals(WordKind.FINE, WordKind.of("Fine"))
        assertNull(WordKind.of("Florian Tekale"))
        assertNull(WordKind.of("Am"))
    }

    @Test fun pruefblatt_wird_gelesen() {
        assertEquals(12, s.measures.size)
        assertEquals(listOf("C", "G", "Am", "F", "C", "Em/B", "Dm7", "G7", "F/G", "Bb", "Am", "C"), s.chordSymbols.map { it.text })
        assertEquals(listOf(Hairpin(0, 0, 1, 96, true), Hairpin(2, 0, 3, 96, false)), s.hairpins)
        val kinds = s.words.map { it.measure to it.kind }
        assertTrue(kinds.containsAll(listOf(
            0 to WordKind.REHEARSAL, 0 to WordKind.TEMPO, 4 to WordKind.CRESC, 5 to WordKind.SIM, 6 to WordKind.RIT,
            8 to WordKind.A_TEMPO, 9 to WordKind.UNA_CORDA, 10 to WordKind.TRE_CORDE, 7 to WordKind.FINE, 11 to WordKind.DC,
        )))
        assertEquals(80, s.tempo) // Andante, keine Metronomzahl
        assertEquals(s, ScoreJson.read(ScoreJson.write(s)))
    }

    @Test fun dc_al_fine_springt_an_den_anfang() {
        assertEquals((0..11).toList() + (0..7).toList(), Performance.order(s))
    }

    @Test fun dc_al_coda() {
        val w = listOf(
            WordMark(1, 0, 0, "To Coda", WordKind.TO_CODA), WordMark(2, 96, 0, "D.C. al Coda", WordKind.DC_AL_CODA),
            WordMark(3, 0, 0, "Coda", WordKind.CODA),
        )
        val sc = s.copy(measures = s.measures.take(4), words = w)
        assertEquals(listOf(0, 1, 2, 0, 1, 3), Performance.order(sc))
    }

    @Test fun gabeln_und_rit_wirken() {
        val p = Performance.build(s)
        // Gabel cresc. Takt 1–2: Takt 2 lauter als Takt 1 (gleiche Schlagposition, rechte Hand)
        fun vel(m: Int) = p.notes.first { n -> p.timeline.first { it.measureIndex == m }.let { n.tick == it.tick } && n.staff == 0 }.velocity
        assertTrue("cresc ${vel(0)} -> ${vel(1)}", vel(1) > vel(0) + 5)
        assertTrue("dim ${vel(2)} -> ${vel(3)}", vel(3) < vel(2) - 5)
        // rit. in Takt 7: Takt 8 klingt deutlich länger als Takt 6, „a tempo“ in Takt 9 wieder normal
        val len = { m: Int -> p.timeline.first { it.measureIndex == m }.length }
        assertEquals(96, len(5))
        assertTrue("rit ${len(7)}", len(7) > 110 && len(6) > 96)
        assertEquals(96, len(8))
        // Zeitkarte umkehrbar
        val pm = p.timeline.first { it.measureIndex == 6 }
        for (n in listOf(0, 24, 50, 96)) assertTrue(kotlin.math.abs(pm.toNotated(pm.toPlayed(n)) - n) <= 1)
        // una corda (Takt 10) leiser als tre corde (Takt 11)
        assertTrue(vel(9) < vel(10))
    }

    @Test fun simile_setzt_betonungen_fort() {
        val m0 = s.measures[0]
        val accented = s.copy(
            measures = s.measures.mapIndexed { i, m -> if (i == 4) m.copy(chords = m.chords.map { if (it.staff == 0 && it.start == 0) it.copy(accent = true) else it }) else m },
        )
        val sim = Performance.applySimile(accented)
        assertTrue(sim.measures[5].chords.first { it.staff == 0 && it.start == 0 }.accent)
        assertTrue(!sim.measures[5].chords.first { it.staff == 0 && it.start == 24 }.accent)
        assertEquals(m0, sim.measures[0])
    }
}
