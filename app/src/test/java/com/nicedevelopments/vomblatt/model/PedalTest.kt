package com.nicedevelopments.vomblatt.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PedalTest {
    private fun xml(body: String) = """<?xml version="1.0"?><score-partwise><part-list><score-part id="P1"/></part-list>
        <part id="P1">$body</part></score-partwise>""".byteInputStream()

    private val twoBars = """
        <measure number="1"><attributes><divisions>1</divisions><time><beats>4</beats><beat-type>4</beat-type></time></attributes>
          <direction><direction-type><pedal type="start"/></direction-type></direction>
          <note><pitch><step>C</step><octave>4</octave></pitch><duration>2</duration><type>half</type></note>
          <direction><direction-type><pedal type="change"/></direction-type></direction>
          <note><pitch><step>E</step><octave>4</octave></pitch><duration>2</duration><type>half</type></note>
        </measure>
        <measure number="2">
          <note><pitch><step>G</step><octave>4</octave></pitch><duration>4</duration><type>whole</type></note>
          <direction><direction-type><pedal type="stop"/></direction-type></direction>
        </measure>"""

    @Test fun musicxml_pedal_wird_gelesen() {
        val s = MusicXml.parse(xml(twoBars))
        assertEquals(
            listOf(PedalMark(0, 0, PedalType.DOWN), PedalMark(0, 48, PedalType.CHANGE), PedalMark(1, 96, PedalType.UP)),
            s.pedals,
        )
        assertTrue(s.pedalDownBefore(1, 0))
        assertFalse(s.pedalDownBefore(0, 0))
    }

    @Test fun pedal_wird_bei_wiederholung_mitgespielt_und_gespeichert() {
        val s = MusicXml.parse(xml(twoBars)).let { it.copy(measures = it.measures.mapIndexed { i, m -> if (i == 1) m.copy(repeatEnd = true) else m }) }
        val p = Performance.build(s)
        assertEquals(6, p.pedals.size) // zweimal durchgespielt
        assertEquals(listOf(0, 48, 192, 192, 240, 384), p.pedals.map { it.tick })
        assertEquals(s, ScoreJson.read(ScoreJson.write(s)))
    }

    @Test fun pedal_knoepfe_setzen_wechsel_und_entfernen() {
        val s = MusicXml.parse(xml(twoBars)).copy(pedals = emptyList())
        val a = ScoreEdit.togglePedal(s, 0, 0, press = true)
        assertEquals(PedalType.DOWN, a.pedals.single().type)
        val b = ScoreEdit.togglePedal(a, 0, 48, press = true)
        assertEquals(PedalType.CHANGE, b.pedals.last().type) // schon getreten -> Wechsel
        val c = ScoreEdit.togglePedal(b, 1, 0, press = false)
        assertEquals(PedalType.UP, c.pedals.last().type)
        val d = ScoreEdit.togglePedal(c, 1, 0, press = false)
        assertEquals(2, d.pedals.size) // nochmal tippen entfernt
    }
}
