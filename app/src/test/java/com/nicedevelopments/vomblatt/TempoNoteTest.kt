package com.nicedevelopments.vomblatt

import com.nicedevelopments.vomblatt.model.TempoNote
import org.junit.Assert.assertEquals
import org.junit.Test
import javax.imageio.ImageIO

/** Notenwert vor dem „=“ der Tempoangabe – Ausschnitte aus echten Fotos (Harmony: Halbe, sonst Viertel). */
class TempoNoteTest {
    private fun beat(name: String, textH: Float): Float? {
        val img = ImageIO.read(javaClass.getResourceAsStream("/tempo/$name.png")!!)
        val w = img.width; val h = img.height
        val g = IntArray(w * h) { i -> img.raster.getSample(i % w, i / w, 0) }
        return TempoNote.beatOf(g, w, h, textH)
    }

    @Test fun halbeImAllaBreve() = assertEquals(2f, beat("half_harmony", 17f))
    @Test fun viertelBohemian() = assertEquals(1f, beat("quarter_bohem", 17f))
    @Test fun viertelNebenMM() = assertEquals(1f, beat("quarter_uebung", 27f))
    @Test fun viertelComingHome() = assertEquals(1f, beat("quarter_coming", 15f))
    @Test fun viertelHeyRoger() = assertEquals(1f, beat("quarter_roger", 30f))

    /** Gezeichnete Note: Kopf (Ellipse, gefüllt oder hohl), Hals, wahlweise Punkt. */
    private fun drawn(hollow: Boolean, dot: Boolean): Float? {
        val w = 40; val h = 40
        val g = IntArray(w * h) { 235 }
        for (y in 0 until h) for (x in 0 until w) {
            val e = ((x - 12) / 6.0) * ((x - 12) / 6.0) + ((y - 32) / 4.0) * ((y - 32) / 4.0)
            if (e <= 1.0 && !(hollow && e < 0.35)) g[y * w + x] = 30
            if (x in 17..18 && y in 6..31) g[y * w + x] = 40
            if (dot && (x - 23) * (x - 23) + (y - 32) * (y - 32) <= 3) g[y * w + x] = 30
        }
        return TempoNote.beatOf(g, w, h, 18f)
    }

    @Test fun punktierteViertel() = assertEquals(1.5f, drawn(hollow = false, dot = true))
    @Test fun punktierteHalbe() = assertEquals(3f, drawn(hollow = true, dot = true))
    @Test fun gezeichneteHalbe() = assertEquals(2f, drawn(hollow = true, dot = false))
}
