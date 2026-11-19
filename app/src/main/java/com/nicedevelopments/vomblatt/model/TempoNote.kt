package com.nicedevelopments.vomblatt.model

/**
 * Notenwert der Tempoangabe („𝅗𝅥 = 54“, „♩. = 60“): Die Texterkennung liest das Notensymbol vor dem „=“ nicht
 * verlässlich (die Viertel als „J“, die Halbe ebenso) – deshalb wird es im Bild selbst angesehen: Notenkopf hohl →
 * Halbe, gefüllt → Viertel, Punkt daneben → punktiert. Die App rechnet das Tempo in Vierteln (Gideon 18.11.: Harmony
 * „𝅗𝅥 = 54“ im Alla breve wurde als ♩ = 54 übernommen, halb so schnell wie notiert).
 */
object TempoNote {
    /**
     * Wert des Notensymbols in Vierteln (1 = Viertel, 2 = Halbe, 1,5 = punktierte Viertel, 3 = punktierte Halbe);
     * null, wenn kein Notensymbol zu finden ist. [gray]: Grauwerte zeilenweise (0 = schwarz), Ausschnitt links vom
     * „=“ (darf Teile der Zahl oder von „M.M.“ enthalten). [textH]: Höhe der Ziffern in Pixeln (ungefähr).
     */
    fun beatOf(gray: IntArray, w: Int, h: Int, textH: Float): Float? {
        if (w < 4 || h < 4 || gray.size < w * h) return null
        val lo = gray.min(); val hi = gray.max()
        if (hi - lo < 40) return null
        val t = otsu(gray, lo, hi)
        // Hals ist im Foto oft blasser als der Kopf: zum Finden des Symbols eine hellere Schwelle (Kopf und Hals hängen
        // zusammen), für das Loch im Kopf die strenge
        val paper = gray.copyOf().also { it.sort() }[(gray.size * 0.9).toInt().coerceAtMost(gray.size - 1)]
        val tFind = (t + paper) / 2
        val dark = BooleanArray(w * h) { gray[it] < tFind }
        // Stücke (8er-Nachbarschaft)
        val lab = IntArray(w * h)
        val boxes = ArrayList<IntArray>() // l, t, r, b, Fläche
        var n = 0
        val stack = IntArray(w * h)
        for (i in 0 until w * h) {
            if (!dark[i] || lab[i] != 0) continue
            n++
            var sp = 0; stack[sp++] = i; lab[i] = n
            val bb = intArrayOf(w, h, -1, -1, 0)
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % w; val y = p / w
                bb[0] = minOf(bb[0], x); bb[1] = minOf(bb[1], y); bb[2] = maxOf(bb[2], x); bb[3] = maxOf(bb[3], y); bb[4]++
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx; val yy = y + dy
                    if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                    val q = yy * w + xx
                    if (dark[q] && lab[q] == 0) { lab[q] = n; stack[sp++] = q }
                }
            }
            boxes.add(bb)
        }
        fun rowWidth(k: Int, y: Int): Int {
            val b = boxes[k - 1]
            var c = 0
            for (x in b[0]..b[2]) if (lab[y * w + x] == k) c++
            return c
        }
        // Notensymbol: hoch, oben ein dünner Hals, unten ein breiter Kopf (Buchstaben und Ziffern sind oben breit)
        var glyph = -1
        for (k in 1..n) {
            val b = boxes[k - 1]
            val bh = b[3] - b[1] + 1
            if (bh < 0.75f * textH || bh > 3.5f * textH || b[2] - b[0] + 1 > 1.4f * textH) continue
            val topRows = (b[1]..(b[1] + (bh * 0.4f).toInt())).map { rowWidth(k, it) }
            val botRows = ((b[3] - (bh * 0.35f).toInt())..b[3]).map { rowWidth(k, it) }
            val stem = topRows.average()
            val head = botRows.max()
            if (stem <= 0.3f * textH && head >= 2.0f * stem && head >= 0.35f * textH) {
                if (glyph < 0 || b[2] > boxes[glyph - 1][2]) glyph = k
            }
        }
        if (glyph < 0) return null
        val g = boxes[glyph - 1]
        val gh = g[3] - g[1] + 1
        // Kopf: unterer Teil des Symbols; hohl = helle Pixel darin, die vom Rand des Kopfbereichs nicht erreichbar sind
        val hy0 = g[3] - (gh * 0.45f).toInt()
        val hx0 = maxOf(0, g[0] - 1); val hx1 = minOf(w - 1, g[2] + 1)
        val hy1 = minOf(h - 1, g[3] + 1)
        val sw = hx1 - hx0 + 1; val sh = hy1 - hy0 + 1
        val seen = BooleanArray(sw * sh)
        var qp = 0
        fun light(x: Int, y: Int) = gray[(hy0 + y) * w + hx0 + x] >= t
        for (x in 0 until sw) for (y in intArrayOf(0, sh - 1)) if (light(x, y) && !seen[y * sw + x]) { seen[y * sw + x] = true; stack[qp++] = y * sw + x }
        for (y in 0 until sh) for (x in intArrayOf(0, sw - 1)) if (light(x, y) && !seen[y * sw + x]) { seen[y * sw + x] = true; stack[qp++] = y * sw + x }
        while (qp > 0) {
            val p = stack[--qp]
            val x = p % sw; val y = p / sw
            for ((dx, dy) in arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val xx = x + dx; val yy = y + dy
                if (xx < 0 || yy < 0 || xx >= sw || yy >= sh) continue
                val q = yy * sw + xx
                if (!seen[q] && light(xx, yy)) { seen[q] = true; stack[qp++] = q }
            }
        }
        var hole = 0
        for (y in 0 until sh) for (x in 0 until sw) if (light(x, y) && !seen[y * sw + x]) hole++
        val hollow = hole >= maxOf(2, (0.03f * sw * sh).toInt())
        // Punkt: kleines Stück rechts neben dem Kopf, auf Kopfhöhe
        val dotted = (1..n).any { k ->
            if (k == glyph) return@any false
            val b = boxes[k - 1]
            val bw = b[2] - b[0] + 1; val bh = b[3] - b[1] + 1
            val cy = (b[1] + b[3]) / 2f
            // rundlich, nicht winzig (Krümel), so dunkel wie Druck
            var darkest = 255
            for (y in b[1]..b[3]) for (x in b[0]..b[2]) if (lab[y * w + x] == k) darkest = minOf(darkest, gray[y * w + x])
            maxOf(bw, bh) <= 0.3f * textH && minOf(bw, bh) >= 0.12f * textH && bw <= 2 * bh && bh <= 2 * bw &&
                darkest < t && b[0] > g[2] && b[0] - g[2] <= 0.6f * textH &&
                cy >= hy0 - 0.1f * textH && cy <= g[3] + 0.1f * textH
        }
        return (if (hollow) 2f else 1f) * (if (dotted) 1.5f else 1f)
    }

    private fun otsu(gray: IntArray, lo: Int, hi: Int): Int {
        val hist = IntArray(256)
        for (v in gray) hist[v.coerceIn(0, 255)]++
        val total = gray.size.toDouble()
        var sum = 0.0
        for (i in 0..255) sum += i * hist[i].toDouble()
        var sumB = 0.0; var wB = 0.0; var best = 0.0; var t = (lo + hi) / 2
        for (i in 0..255) {
            wB += hist[i]; if (wB == 0.0) continue
            val wF = total - wB; if (wF == 0.0) break
            sumB += i * hist[i].toDouble()
            val mB = sumB / wB; val mF = (sum - sumB) / wF
            val v = wB * wF * (mB - mF) * (mB - mF)
            if (v > best) { best = v; t = i }
        }
        return t + 1
    }
}
