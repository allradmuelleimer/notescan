package com.nicedevelopments.vomblatt.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** Schlüsselwechsel mitten im Takt (Bohemian Rhapsody: Bass-System wechselt für zwei Akkorde in den Violinschlüssel). */
class ClefChangeTest {
    private fun note(step: String, oct: Int, staff: Int) =
        """<note><pitch><step>$step</step><octave>$oct</octave></pitch><duration>4</duration><type>half</type><staff>$staff</staff></note>"""

    private val xml = """<?xml version="1.0"?><score-partwise><part-list><score-part id="P1"/></part-list><part id="P1">
        <measure number="1"><attributes><divisions>2</divisions><staves>2</staves>
            <time><beats>4</beats><beat-type>4</beat-type></time>
            <clef number="1"><sign>G</sign><line>2</line></clef><clef number="2"><sign>F</sign><line>4</line></clef></attributes>
          ${note("C", 5, 1)}${note("D", 5, 1)}
          <backup><duration>8</duration></backup>
          ${note("C", 3, 2)}
          <attributes><clef number="2"><sign>G</sign><line>2</line></clef></attributes>
          ${note("A", 4, 2)}
        </measure>
        <measure number="2">${note("E", 5, 1)}${note("F", 5, 1)}<backup><duration>8</duration></backup>${note("B", 4, 2)}${note("C", 5, 2)}</measure>
        </part></score-partwise>"""

    @Test fun wechselGiltAbDemNotenanfangImTakt() {
        val s = MusicXml.parse(xml.byteInputStream())
        val m = s.measures[0]
        assertEquals(listOf(Clef.TREBLE, Clef.BASS), m.clefs)
        assertEquals(listOf(ClefChange(TPQ * 2, 1, Clef.TREBLE)), m.clefChanges)
        assertEquals(Clef.BASS, m.clefAt(1, 0))
        assertEquals(Clef.TREBLE, m.clefAt(1, TPQ * 2))
        assertEquals(Clef.TREBLE, m.clefAt(0, TPQ * 2))
        // Folgetakt beginnt im neuen Schlüssel, ohne eigenen Wechsel
        assertEquals(listOf(Clef.TREBLE, Clef.TREBLE), s.measures[1].clefs)
        assertEquals(emptyList<ClefChange>(), s.measures[1].clefChanges)
    }

    @Test fun speichernUndLesen() {
        val s = MusicXml.parse(xml.byteInputStream())
        assertEquals(s, ScoreJson.read(ScoreJson.write(s)))
    }

    @Test fun aeltereStueckeBekommenDenWechselNachgetragen() {
        val fresh = MusicXml.parse(xml.byteInputStream())
        val old = fresh.copy(measures = fresh.measures.map { it.copy(clefChanges = emptyList()) })
        assertEquals(fresh.measures[0].clefChanges, ScoreEdit.upgradeFrom(old, fresh).measures[0].clefChanges)
    }
}
