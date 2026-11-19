package com.nicedevelopments.vomblatt.ui

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Bildanalyse nur für die Anzeige während der Erkennung („Scanner-Look“). Alles, was dort leuchtet oder
 * durchläuft, stammt aus dem Foto: Kanten, Seitenrand, Helligkeiten je Zeile.
 * Koordinaten sind auf 0…1 normiert (Breite bzw. Höhe des Fotos).
 */
class ScanFx(
    /** Kantenpunkte x0,y0,x1,y1 … nach y sortiert. */
    val edges: FloatArray,
    /** Seitenrand als Viereck: oben links, oben rechts, unten rechts, unten links (je x,y). */
    val page: FloatArray?,
    /** Grauwerte je Bildzeile (heruntergerechnet), für die Zahlenkolonnen. */
    val rows: Array<ByteArray>,
    /** Kantenanzahl je Bildzeile. */
    val edgeCount: IntArray,
) {
    val edgeTotal: Int get() = edges.size / 2

    companion object {
        fun analyze(src: Bitmap): ScanFx {
            val w = 300
            val h = (src.height * w.toFloat() / src.width).toInt().coerceAtLeast(10)
            val small = Bitmap.createScaledBitmap(src, w, h, true)
            val px = IntArray(w * h)
            small.getPixels(px, 0, w, 0, 0, w, h)
            if (small !== src) small.recycle()
            val g = FloatArray(w * h) { i -> val p = px[i]; ((p shr 16 and 255) * 0.299f + (p shr 8 and 255) * 0.587f + (p and 255) * 0.114f) }

            // Kanten: Sobel-Betrag, die stärksten ~9 %
            val mag = FloatArray(w * h)
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val i = y * w + x
                val gx = g[i - w + 1] + 2 * g[i + 1] + g[i + w + 1] - g[i - w - 1] - 2 * g[i - 1] - g[i + w - 1]
                val gy = g[i + w - 1] + 2 * g[i + w] + g[i + w + 1] - g[i - w - 1] - 2 * g[i - w] - g[i - w + 1]
                mag[i] = sqrt(gx * gx + gy * gy)
            }
            val sorted = mag.copyOf().also { it.sort() }
            val thr = sorted[(sorted.size * 0.91f).toInt()].coerceAtLeast(40f)
            val pts = ArrayList<Float>()
            val count = IntArray(h)
            for (y in 1 until h - 1) for (x in 1 until w - 1) if (mag[y * w + x] >= thr) {
                pts.add(x / w.toFloat()); pts.add(y / h.toFloat()); count[y]++
            }

            // Seitenrand: helle Fläche (Papier) gegen dunkleren Hintergrund – Schwelle nach Otsu,
            // Ecken = äußerste helle Punkte in den vier Diagonalrichtungen
            val hist = IntArray(256)
            for (v in g) hist[v.toInt().coerceIn(0, 255)]++
            val total = g.size
            var sum = 0.0; for (i in 0..255) sum += i * hist[i].toDouble()
            var sumB = 0.0; var wB = 0; var best = 0.0; var otsu = 128
            for (i in 0..255) {
                wB += hist[i]; if (wB == 0) continue
                val wF = total - wB; if (wF == 0) break
                sumB += i * hist[i].toDouble()
                val mB = sumB / wB; val mF = (sum - sumB) / wF
                val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
                if (between > best) { best = between; otsu = i }
            }
            var tl = Float.MAX_VALUE; var tr = -Float.MAX_VALUE; var br = -Float.MAX_VALUE; var bl = -Float.MAX_VALUE
            val q = FloatArray(8)
            var bright = 0
            for (y in 0 until h step 2) for (x in 0 until w step 2) {
                if (g[y * w + x] <= otsu) continue
                bright++
                val nx = x / w.toFloat(); val ny = y / h.toFloat()
                if (nx + ny < tl) { tl = nx + ny; q[0] = nx; q[1] = ny }
                if (nx - ny > tr) { tr = nx - ny; q[2] = nx; q[3] = ny }
                if (nx + ny > br) { br = nx + ny; q[4] = nx; q[5] = ny }
                if (ny - nx > bl) { bl = ny - nx; q[6] = nx; q[7] = ny }
            }
            // Nur zeigen, wenn es wirklich nach einer Seite aussieht (nicht das ganze Bild, nicht zu klein)
            val area = abs((q[2] - q[6]) * (q[5] - q[1]))
            val page = if (bright > 50 && area in 0.2f..0.97f) q else null

            val rows = Array(h) { y -> ByteArray(16) { k -> g[y * w + (k * (w - 1) / 15)].toInt().toByte() } }
            return ScanFx(pts.toFloatArray(), page, rows, count)
        }
    }
}
