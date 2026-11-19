package com.nicedevelopments.vomblatt.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stufe 1: Zeichen, die homr schon liefert – lesen, speichern, spielen. */
class NotationTest {
    private fun xml(body: String) = """<?xml version="1.0"?><score-partwise><part-list><score-part id="P1"/></part-list>
        <part id="P1">$body</part></score-partwise>""".byteInputStream()

    private fun note(step: String, oct: Int, dur: Int, type: String, extra: String = "", notations: String = "") =
        """<note>$extra<pitch><step>$step</step><octave>$oct</octave></pitch><duration>$dur</duration><type>$type</type><staff>1</staff>
           ${if (notations.isNotEmpty()) "<notations>$notations</notations>" else ""}</note>"""

    // Takt 1: p, Bogen über drei Töne, Fermate am Schluss. Takt 2: f, Triole, Triller, Vorschlag.
    private val piece = """
        <measure number="1"><attributes><divisions>6</divisions><time><beats>4</beats><beat-type>4</beat-type></time></attributes>
          <direction><direction-type><dynamics><p/></dynamics></direction-type><staff>1</staff></direction>
          ${note("C", 4, 6, "quarter", notations = """<slur type="start" number="1"/><articulations><tenuto/></articulations>""")}
          ${note("D", 4, 6, "quarter")}
          ${note("E", 4, 6, "quarter", notations = """<slur type="stop" number="1"/>""")}
          ${note("F", 4, 6, "quarter", notations = """<fermata/><articulations><strong-accent/></articulations>""")}
        </measure>
        <measure number="2">
          <direction><direction-type><dynamics><f/></dynamics></direction-type><staff>1</staff></direction>
          ${note("G", 4, 2, "eighth", "", """<tuplet type="start"/>""").replace("</type>", "</type><time-modification><actual-notes>3</actual-notes><normal-notes>2</normal-notes></time-modification>")}
          ${note("A", 4, 2, "eighth").replace("</type>", "</type><time-modification><actual-notes>3</actual-notes><normal-notes>2</normal-notes></time-modification>")}
          ${note("B", 4, 2, "eighth").replace("</type>", "</type><time-modification><actual-notes>3</actual-notes><normal-notes>2</normal-notes></time-modification>")}
          <note><grace slash="yes"/><pitch><step>D</step><octave>5</octave></pitch><type>eighth</type><staff>1</staff></note>
          ${note("C", 5, 6, "quarter")}
          ${note("E", 5, 12, "half", notations = """<ornaments><trill-mark/></ornaments>""")}
        </measure>"""

    @Test fun zeichen_werden_gelesen() {
        val s = MusicXml.parse(xml(piece))
        val m1 = s.measures[0].chords
        assertEquals(setOf(Orn.TENUTO), m1[0].orn)
        assertEquals(setOf(Orn.FERMATA, Orn.MARCATO), m1[3].orn)
        assertEquals(listOf(Slur(m1[0].id, m1[2].id)), s.slurs)
        assertEquals(listOf("p", "f"), s.dynamics.map { it.text })
        assertEquals(DynamicMark(1, 0, 0, "f"), s.dynamics[1])
        val m2 = s.measures[1].chords
        assertEquals(listOf(3, 3, 3, 0, 0), m2.map { it.tuplet })
        assertEquals(8, m2[0].duration)
        assertEquals(listOf(Pitch(1, 5, 0)), m2[3].graces)
        assertTrue(Orn.TRILL in m2[4].orn)
        assertEquals(s, ScoreJson.read(ScoreJson.write(s)))
    }

    @Test fun dynamik_fermate_triller_und_vorschlag_klingen() {
        val s = MusicXml.parse(xml(piece))
        val p = Performance.build(s)
        // Fermate auf der letzten Viertel von Takt 1 dehnt den Takt
        val pm = p.timeline[0]
        assertEquals(96 + 24, pm.length)
        assertEquals(96, pm.toNotated(pm.length))
        assertEquals(72, pm.toNotated(72))
        assertEquals(1, p.timeline[1].measureIndex)
        assertEquals(120, p.timeline[1].tick)
        // p deutlich leiser als f
        val c4 = p.notes.first { it.midi == 60 }
        val c5 = p.notes.first { it.midi == 72 }
        assertTrue("p=${c4.velocity} f=${c5.velocity}", c5.velocity - c4.velocity > 20)
        // Vorschlag D5 kurz vor C5
        val d5 = p.notes.first { it.midi == 74 }
        assertTrue(d5.tick < c5.tick && d5.length <= 3)
        // Triller auf E5: viele Wechsel E5/F5
        val trill = p.notes.filter { it.tick >= c5.tick + 24 && (it.midi == 76 || it.midi == 77) }
        assertTrue("Triller: ${trill.size}", trill.size >= 8)
        // Unter dem Bogen legato: C4 klingt bis D4
        val d4 = p.notes.first { it.midi == 62 }
        assertTrue(c4.tick + c4.length >= d4.tick)
    }

    @Test fun alte_fassung_bekommt_neue_zeichen() {
        val fresh = MusicXml.parse(xml(piece))
        // So hätte die alte App gespeichert – ohne neue Zeichen, aber mit einer Korrektur
        val old = fresh.copy(
            dynamics = emptyList(), slurs = emptyList(),
            measures = fresh.measures.map { m -> m.copy(chords = m.chords.map { it.copy(orn = emptySet(), tuplet = 0, graces = emptyList()) }) },
        ).let { ScoreEdit.step(it, it.measures[0].chords[1].id, 0, 1) }
        val up = ScoreEdit.upgradeFrom(old, fresh)
        assertEquals(fresh.slurs, up.slurs)
        assertEquals(fresh.dynamics, up.dynamics)
        assertEquals(fresh.measures[1].chords.map { it.tuplet }, up.measures[1].chords.map { it.tuplet })
        assertEquals(Pitch(2, 4, 0), up.measures[0].chords[1].notes[0].pitch) // Korrektur D→E bleibt
    }

    @Test fun volta_erster_und_zweiter_schluss() {
        val bar = { n: Int -> """<measure number="$n">${note("C", 4, 4, "whole")}</measure>""" }
        val body = bar(1) +
            """<measure number="2"><barline location="left"><ending number="1" type="start"/></barline>${note("D", 4, 4, "whole")}
               <barline location="right"><ending number="1" type="stop"/><repeat direction="backward"/></barline></measure>""" +
            """<measure number="3"><barline location="left"><ending number="2" type="start"/></barline>${note("E", 4, 4, "whole")}
               <barline location="right"><ending number="2" type="discontinue"/></barline></measure>""" + bar(4)
        val s = MusicXml.parse(xml(body.replaceFirst("<measure number=\"1\">", "<measure number=\"1\"><attributes><divisions>1</divisions></attributes>")))
        assertEquals(listOf(0, 1, 2, 0), s.measures.map { it.volta })
        assertEquals(listOf(0, 1, 0, 2, 3), Performance.order(s))
    }

    @Test fun oktavierung_bleibt_klingend() {
        val body = """<measure number="1"><attributes><divisions>1</divisions></attributes>
            <direction><direction-type><octave-shift type="down" size="8" number="1"/></direction-type><staff>1</staff></direction>
            ${note("C", 6, 2, "half")}
            <direction><direction-type><octave-shift type="stop" size="8" number="1"/></direction-type><staff>1</staff></direction>
            ${note("C", 5, 2, "half")}</measure>"""
        val s = MusicXml.parse(xml(body))
        assertEquals(listOf(1, 0), s.measures[0].chords.map { it.ottava })
        assertEquals(84, Performance.build(s).notes.first().midi)
    }
}
