package com.nicedevelopments.vomblatt.recognize

import android.graphics.BitmapFactory
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import kotlin.math.abs

/**
 * Liest den Titel eines Notenblatts: die größte Schrift oberhalb der ersten Notenzeile.
 * Tempoangaben, Datumsnotizen, Seitenzahlen und Übungsnummern werden übergangen.
 * Läuft offline mit dem in die App gebündelten ML-Kit-Modell.
 */
object TitleReader {

    /** Was vom Blattkopf gelesen wurde. */
    class Head(val title: String?, val tempo: Int?, val lines: List<com.nicedevelopments.vomblatt.model.OcrText> = emptyList())

    /** Alle Textzeilen eines Bilds mit ihren Wörtern (für Akkordsymbole und Spielanweisungen). */
    fun lines(bmp: android.graphics.Bitmap, scale: Float = 1f): List<com.nicedevelopments.vomblatt.model.OcrText> {
        val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            return toOcr(Tasks.await(client.process(InputImage.fromBitmap(bmp, 0))).textBlocks.flatMap { it.lines }, scale)
        } finally { client.close() }
    }

    /**
     * Wie [lines], dazu jeden Streifen ([bands], in Fotokoordinaten nach [scale]) vergrößert noch einmal lesen:
     * einzelne Buchstaben (Akkordsymbole „C“, „G“) übersieht die Texterkennung auf der ganzen Seite oft.
     */
    fun linesWithBands(bmp: android.graphics.Bitmap, scale: Float, bands: List<android.graphics.RectF>): List<com.nicedevelopments.vomblatt.model.OcrText> {
        val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val all = toOcr(Tasks.await(client.process(InputImage.fromBitmap(bmp, 0))).textBlocks.flatMap { it.lines }, scale).toMutableList()
            for (b in bands) {
                val l = (b.left / scale).toInt().coerceIn(0, bmp.width - 1); val r = (b.right / scale).toInt().coerceIn(l + 1, bmp.width)
                val t = (b.top / scale).toInt().coerceIn(0, bmp.height - 1); val bt = (b.bottom / scale).toInt().coerceIn(t + 1, bmp.height)
                if (r - l < 20 || bt - t < 8) continue
                // stark vergrößern: kleine Ziffern (Fingersätze) liest die Texterkennung erst ab ~20 px Höhe
                val zoom = (160f / (bt - t)).coerceIn(2f, 3.5f).let { z -> minOf(z, 4000f / (r - l)) }
                val crop = android.graphics.Bitmap.createBitmap(bmp, l, t, r - l, bt - t)
                val big = android.graphics.Bitmap.createScaledBitmap(crop, ((r - l) * zoom).toInt(), ((bt - t) * zoom).toInt(), true)
                val found = Tasks.await(client.process(InputImage.fromBitmap(big, 0))).textBlocks.flatMap { it.lines }
                for (o in toOcr(found, 1f / zoom)) {
                    val moved = o.shift(l.toFloat(), t.toFloat(), scale)
                    // schon auf der ganzen Seite gefunden? dann nicht doppelt
                    if (all.none { a -> overlap(a, moved) > 0.4f }) all.add(moved)
                }
            }
            return all
        } finally { client.close() }
    }

    /** Nur zur Fehlersuche: hier wird die zusammengesetzte Ziffernreihe abgelegt. */
    var debugDir: java.io.File? = null

    /**
     * Ein Fingersatz-Kandidat aus der Bildanalyse (vomblatt_digits.py), Rahmen im Bild [bmp] von [readDigits]:
     * [digit] sicher gelesene Ziffer oder 0, [best] ähnlichste Ziffer 1–5, [score] Ähnlichkeit, [margin] Vorsprung
     * vor dem besten Nicht-Kandidaten (Buchstabe, Strich, andere Ziffer).
     */
    class DigitCandidate(val box: FloatArray, val digit: Int, val best: Int, val score: Float, val margin: Float, val sizeOk: Boolean = true)

    /**
     * Gedruckte Fingersätze endgültig lesen. Zwei unabhängige Leser: der Vorlagen-Abgleich der Bildanalyse und
     * die Texterkennung (jede Ziffer als Wort „0Z0“ zwischen gezeichneten Nullen, zwei Größen).
     * Eine Ziffer zählt nur, wenn
     *  – beide dasselbe lesen, oder
     *  – die Texterkennung nichts liest und der Vorlagen-Abgleich sehr eindeutig ist („1“ strenger: Striche ähneln ihr).
     * Widersprechen sie sich, bleibt die Stelle „?“ (kein Fingersatz, aber auch keine Zeichnung, keine Verzierung).
     */
    fun readDigits(bmp: android.graphics.Bitmap, cands: List<DigitCandidate>): List<com.nicedevelopments.vomblatt.model.OcrText> {
        if (cands.isEmpty()) return emptyList()
        val boxes = cands.map { it.box }
        val a = readDigitsPass(bmp, boxes, 48)
        val b = readDigitsPass(bmp, boxes, 66)
        val out = ArrayList<com.nicedevelopments.vomblatt.model.OcrText>()
        var agree = 0; var alone = 0; var conflict = 0
        for ((k, c) in cands.withIndex()) {
            val ra = a[k]; val rb = b[k]
            // Texterkennung: null = nichts gelesen, -1 = die beiden Größen widersprechen sich
            val mk: Int? = when {
                ra == null -> rb
                rb == null -> ra
                ra == rb -> ra
                else -> -1
            }
            val strongAlone = c.digit > 0 && c.margin >= (if (c.digit == 1) 0.1f else 0.04f)
            val digit = when {
                // eine „1“ ähnelt jedem Strich: dort muss auch der Vorlagen-Abgleich sie vorn sehen
                mk != null && mk > 0 && mk == c.best && c.sizeOk && c.score >= 0.8f && c.margin > (if (mk == 1) 0f else -0.08f) -> { agree++; mk }
                mk == null && strongAlone -> { alone++; c.digit }
                else -> { if (mk != null) conflict++; 0 }
            }
            val bx = c.box
            if (digit > 0) out.add(com.nicedevelopments.vomblatt.model.OcrText(digit.toString(), bx[0], bx[1], bx[2], bx[3], checked = true))
            else if (c.score >= 0.75f || mk != null) out.add(com.nicedevelopments.vomblatt.model.OcrText("?", bx[0], bx[1], bx[2], bx[3], checked = true))
        }
        android.util.Log.d("VomBlatt", "Fingersätze: ${cands.size} Kandidaten, $agree übereinstimmend, $alone eindeutig, $conflict Widersprüche")
        return out
    }

    /** Texterkennung der Kandidaten: Index → gelesene Ziffer 1–5 (nur, was genau als „0Z0“ gelesen wurde). */
    private fun readDigitsPass(bmp: android.graphics.Bitmap, boxes: List<FloatArray>, h: Int): Map<Int, Int> {
        // Jeder Ausschnitt wird zu einem eigenen „Wort“ 0[Ziffer]0: mit Ziffern-Kontext liest die Texterkennung
        // eine Zahl statt eines einzelnen, unsicheren Zeichens. Gilt nur, wenn genau 0, 1–5, 0 herauskommt.
        val gap = 70; val inner = 5; val rowH = h + 44; val perRow = 12
        val rows = (boxes.size + perRow - 1) / perRow
        val zeroPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
            color = android.graphics.Color.BLACK
        }
        run { // Größe der Kontext-Null an die Ziffernhöhe angleichen
            zeroPaint.textSize = 100f
            val r = android.graphics.Rect(); zeroPaint.getTextBounds("0", 0, 1, r)
            zeroPaint.textSize = 100f * (h * 0.72f) / r.height()
        }
        val zr = android.graphics.Rect(); zeroPaint.getTextBounds("0", 0, 1, zr)
        val zw = zr.width()
        class Slot(val x0: Int, val x1: Int, val row: Int)
        val slots = ArrayList<Slot>()
        var maxW = 0
        val crops = boxes.mapIndexed { i, bx ->
            val padY = (bx[3] - bx[1]) * 0.12f; val padX = (bx[2] - bx[0]) * 0.15f + padY
            val l = (bx[0] - padX).toInt().coerceIn(0, bmp.width - 1); val t = (bx[1] - padY).toInt().coerceIn(0, bmp.height - 1)
            val r = (bx[2] + padX).toInt().coerceIn(l + 1, bmp.width); val bt = (bx[3] + padY).toInt().coerceIn(t + 1, bmp.height)
            val w = ((r - l) * h.toFloat() / (bt - t)).toInt().coerceIn(8, 120)
            Triple(i, android.graphics.Rect(l, t, r, bt), w)
        }
        var x = gap; var row = 0
        for ((i, _, w) in crops) {
            if (i > 0 && i % perRow == 0) { maxW = maxOf(maxW, x); x = gap; row++ }
            val wordW = zw + inner + w + inner + zw
            slots.add(Slot(x, x + wordW, row)); x += wordW + gap
        }
        maxW = maxOf(maxW, x)
        val strip = android.graphics.Bitmap.createBitmap(maxW, rows * rowH + 24, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(strip)
        cv.drawColor(android.graphics.Color.WHITE)
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        for ((k, c) in crops.withIndex()) {
            val sl = slots[k]
            val top = 24 + sl.row * rowH
            // Kontrast je Ausschnitt strecken: Papier weiß, Ziffer schwarz
            val rc = c.second
            val px = IntArray(rc.width() * rc.height())
            bmp.getPixels(px, 0, rc.width(), rc.left, rc.top, rc.width(), rc.height())
            val lum = px.map { ((it shr 16 and 255) * 299 + (it shr 8 and 255) * 587 + (it and 255) * 114) / 1000 }.sorted()
            val lo = lum[(lum.size * 0.03f).toInt()].toFloat(); val hi = lum[(lum.size * 0.7f).toInt()].toFloat()
            val k2 = 255f / (hi - lo).coerceAtLeast(20f)
            paint.colorFilter = android.graphics.ColorMatrixColorFilter(floatArrayOf(
                0.299f * k2, 0.587f * k2, 0.114f * k2, 0f, -lo * k2,
                0.299f * k2, 0.587f * k2, 0.114f * k2, 0f, -lo * k2,
                0.299f * k2, 0.587f * k2, 0.114f * k2, 0f, -lo * k2,
                0f, 0f, 0f, 1f, 0f,
            ))
            val base = top + h * 0.86f
            cv.drawText("0", sl.x0.toFloat() - zr.left, base, zeroPaint)
            val cx0 = sl.x0 + zw + inner
            cv.drawBitmap(bmp, rc, android.graphics.Rect(cx0, top, cx0 + c.third, top + h), paint)
            cv.drawText("0", (cx0 + c.third + inner).toFloat() - zr.left, base, zeroPaint)
        }
        val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        if (debugDir != null) runCatching { java.io.File(debugDir, "digits_strip_$h.png").outputStream().use { strip.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        try {
            val res = Tasks.await(client.process(InputImage.fromBitmap(strip, 0)))
            val out = HashMap<Int, Int>()
            val pattern = Regex("""^0([1-5])0$""")
            for (e in res.textBlocks.flatMap { it.lines }.flatMap { it.elements }) {
                val eb = e.boundingBox ?: continue
                val digit = pattern.matchEntire(e.text.replace('O', '0').replace('o', '0'))?.groupValues?.get(1) ?: continue
                val row = ((eb.exactCenterY() - 24) / rowH).toInt()
                val k = slots.indices.firstOrNull { slots[it].row == row && eb.exactCenterX() in slots[it].x0.toFloat()..slots[it].x1.toFloat() } ?: continue
                if (k !in out) out[k] = digit.toInt()
            }
            return out
        } finally { client.close(); strip.recycle() }
    }

    private fun com.nicedevelopments.vomblatt.model.OcrText.shift(dx: Float, dy: Float, scale: Float): com.nicedevelopments.vomblatt.model.OcrText =
        copy(l = (l + dx) * scale, t = (t + dy) * scale, r = (r + dx) * scale, b = (b + dy) * scale, words = words.map { it.shift(dx, dy, scale) })

    private fun overlap(a: com.nicedevelopments.vomblatt.model.OcrText, b: com.nicedevelopments.vomblatt.model.OcrText): Float {
        val w = minOf(a.r, b.r) - maxOf(a.l, b.l); val h = minOf(a.b, b.b) - maxOf(a.t, b.t)
        if (w <= 0 || h <= 0) return 0f
        return w * h / minOf((a.r - a.l) * (a.b - a.t), (b.r - b.l) * (b.b - b.t)).coerceAtLeast(1f)
    }

    private fun toOcr(lines: List<Text.Line>, scale: Float) = lines.mapNotNull { l ->
        val b = l.boundingBox ?: return@mapNotNull null
        com.nicedevelopments.vomblatt.model.OcrText(
            l.text, b.left * scale, b.top * scale, b.right * scale, b.bottom * scale,
            l.elements.mapNotNull { e ->
                val eb = e.boundingBox ?: return@mapNotNull null
                com.nicedevelopments.vomblatt.model.OcrText(e.text, eb.left * scale, eb.top * scale, eb.right * scale, eb.bottom * scale)
            },
        )
    }

    /** [firstStaffTop]: Oberkante der ersten Notenzeile in Fotopixeln (aus der Erkennung). */
    /** [ls]: Linienabstand der ersten Notenzeile (Text knapp darüber ist eher Akkord/Notiz als Titel). */
    fun read(photo: File, firstStaffTop: Float?, ls: Float? = null, pageX: ClosedFloatingPointRange<Float>? = null): Head? = runCatching {
        val bmp = BitmapFactory.decodeFile(photo.absolutePath) ?: return null
        val limit = firstStaffTop ?: (bmp.height * 0.3f)
        val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val normal = Tasks.await(client.process(InputImage.fromBitmap(bmp, 0))).textBlocks.flatMap { it.lines }
            // Titel stehen oft hell auf dunklem Balken – den Kopfbereich zusätzlich invertiert lesen.
            val head = limit.toInt().coerceIn(1, bmp.height)
            val inverted = android.graphics.Bitmap.createBitmap(bmp.width, head, android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(inverted).drawBitmap(bmp, 0f, 0f, android.graphics.Paint().apply {
                colorFilter = android.graphics.ColorMatrixColorFilter(floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f,
                ))
            })
            val inv = Tasks.await(client.process(InputImage.fromBitmap(inverted, 0))).textBlocks.flatMap { it.lines }
            val title = pick(normal, inv, limit, bmp.width, ls ?: (bmp.height / 300f), pageX)
            // Tempo steht knapp über der ersten Zeile („♩ = 120“, „M.M. ♩ = 112–160“)
            // Auf der ganzen Seite ist kleine Schrift für ML Kit oft zu klein (Bohemian Rhapsody: „♩ = 72“ fehlte) –
            // den Kopfbereich deshalb zusätzlich für sich lesen
            val headBmp = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, (limit + bmp.height * 0.02f).toInt().coerceIn(1, bmp.height))
            val headLines = runCatching { Tasks.await(client.process(InputImage.fromBitmap(headBmp, 0))).textBlocks.flatMap { it.lines } }
                .getOrDefault(emptyList())
            headBmp.recycle()
            val above = normal.filter { (it.boundingBox?.bottom ?: Int.MAX_VALUE) < limit + bmp.height * 0.02f } + headLines + inv
            android.util.Log.d("VomBlatt", "Kopftext: " + above.joinToString(" | ") { it.text })
            val tempo = above.firstNotNullOfOrNull { l -> tempoOf(l.text)?.let { l to it } }?.let { (l, n) ->
                // Notenwert vor dem „=“ im Bild ansehen (Halbe, punktiert …) – die App rechnet in Vierteln
                val beat = if (l in inv) null else runCatching { noteBeat(bmp, l) }.getOrNull()
                android.util.Log.d("VomBlatt", "Tempo: '${l.text}' → $n je ${beat ?: "?"} Viertel")
                if (beat != null && beat != 1f) Math.round(n * beat).coerceIn(20, 400) else n
            }
            inverted.recycle()
            Head(title, tempo, toOcr(normal, 1f))
        } finally { client.close(); bmp.recycle() }
    }.onFailure { android.util.Log.w("VomBlatt", "Titel", it) }.getOrNull()

    /** Notenwert des Symbols vor dem „=“ einer Tempozeile in Vierteln (null = nicht erkennbar). */
    private fun noteBeat(bmp: android.graphics.Bitmap, l: Text.Line): Float? {
        val b = l.boundingBox ?: return null
        val text = l.text
        val eq = text.indexOf('=').takeIf { it >= 0 } ?: return null
        val bh = b.height().toFloat().coerceAtLeast(4f)
        val xEq = b.left + b.width() * eq / text.length.coerceAtLeast(1).toFloat()
        val x0 = (xEq - 2.2f * bh).toInt().coerceIn(0, bmp.width - 1)
        val x1 = (xEq + 0.1f * bh).toInt().coerceIn(x0 + 1, bmp.width)
        val y0 = (b.top - 0.6f * bh).toInt().coerceIn(0, bmp.height - 1)
        val y1 = (b.bottom + 0.4f * bh).toInt().coerceIn(y0 + 1, bmp.height)
        val w = x1 - x0; val h = y1 - y0
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, x0, y0, w, h)
        val gray = IntArray(w * h) { i -> val c = px[i]; (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000 }
        return com.nicedevelopments.vomblatt.model.TempoNote.beatOf(gray, w, h, 0.8f * bh)
    }

    private fun pick(normal: List<Text.Line>, inv: List<Text.Line>, limit: Float, width: Int, ls: Float, pageX: ClosedFloatingPointRange<Float>?): String? {
        val all = normal + inv
        val lines = all.filter { l ->
            val b = l.boundingBox ?: return@filter false
            // nur über der Seite mit den Noten (nicht der Titel einer Nachbarseite)
            b.bottom < limit && plausible(l.text) && (pageX == null || b.exactCenterX() in pageX)
        }
        for (l in all) android.util.Log.d(
            "VomBlatt", "Titel-Kandidat '${l.text}' h=${l.boundingBox?.height()} y=${l.boundingBox?.top} " +
                (if (l in lines) "ok" else "verworfen")
        )
        if (lines.isEmpty()) return null
        // Größe zählt, mittig stehende Zeilen werden bevorzugt (Titel sind meist zentriert)
        fun base(l: Text.Line): Float {
            val b = l.boundingBox!!
            val mid = pageX?.let { (it.start + it.endInclusive) / 2 } ?: (width / 2f)
            val halfW = pageX?.let { (it.endInclusive - it.start) / 2 } ?: (width / 2f)
            val offCenter = (abs(b.exactCenterX() - mid) / halfW.coerceAtLeast(1f)).coerceAtMost(1.5f)
            return b.height() * (1.15f - 0.5f * offCenter)
        }
        fun score(l: Text.Line): Float {
            val bb = l.boundingBox!!
            // Knapp über der ersten Zeile stehen Akkorde, Tempo, Bleistiftnotizen – kein Titel
            val near = if (bb.bottom > limit - 7 * ls) 0.35f else 1f
            // Titel stehen oft hell auf dunklem Balken
            val banner = if (l in inv && l !in normal) 1.3f else 1f
            // Handschrift erkennt die Texterkennung meist unsicher
            val sure = runCatching { l.confidence }.getOrDefault(1f).let { if (it in 0f..0.55f) 0.5f else 1f }
            return base(l) * near * banner * sure
        }
        val biggest = lines.maxBy { score(it) }
        val h = biggest.boundingBox!!.height()
        // Weitere Teile in derselben Zeile und Größe (z. B. zweisprachige Titel) anhängen
        val row = lines.filter { l ->
            val b = l.boundingBox!!
            abs(b.centerY() - biggest.boundingBox!!.centerY()) < h * 0.6f && b.height() > h * 0.75f
        }.sortedBy { it.boundingBox!!.left }
        // Normaler und invertierter Durchgang finden dieselbe Zeile oft beide – nur einmal nehmen.
        val unique = mutableListOf<Text.Line>()
        for (l in row) {
            val b = l.boundingBox!!
            val dup = unique.any { u ->
                val ub = u.boundingBox!!
                u.text.trim().equals(l.text.trim(), ignoreCase = true) ||
                    android.graphics.Rect(ub).let { it.intersect(b) && it.width() * it.height() > 0.5f * minOf(ub.width() * ub.height(), b.width() * b.height()) }
            }
            if (!dup) unique.add(l)
        }
        val title = unique.joinToString("  ") { l ->
            // knapp über den Noten: Akkordsymbole sind kein Teil des Titels („Jyetey F“)
            val near = l.boundingBox!!.bottom > limit - 7 * ls
            if (!near) l.text.trim()
            else l.text.trim().split(Regex("""\s+""")).filter { com.nicedevelopments.vomblatt.model.PhotoMarks.chordOf(it) == null }.joinToString(" ")
        }
            .replace(Regex("""^\s*\d{1,3}\s*[.)]\s*"""), "") // „9. …“ – Übungsnummer
            .trim()
        return title.takeIf { it.count(Char::isLetter) >= 3 }
    }

    private val TEMPO = Regex("""=\s*(\d{1,3})(?:\s*[-–—]\s*(\d{2,3}))?""")

    /** Metronomzahl aus einer Tempoangabe; bei einer Spanne die untere (Übetempo). */
    fun tempoOf(text: String): Int? {
        val m = TEMPO.find(text) ?: return null
        var a = m.groupValues[1].toInt()
        val b = m.groupValues[2].toIntOrNull()
        // Texterkennung verliert gern die führende 1: „=12-160“ war „112–160“
        if (a < 30 && b != null && "1$a".toInt() in 30..b) a = "1$a".toInt()
        return when {
            a in 30..260 -> a
            b != null && b in 30..260 -> b
            else -> null
        }
    }

    private val DATE = Regex("""\d{1,2}\s*\.\s*\d{1,2}\s*\.\s*\d{0,4}""")

    private fun plausible(s: String): Boolean {
        val t = s.trim()
        if (t.length < 3) return false
        val letters = t.count(Char::isLetter)
        val digits = t.count(Char::isDigit)
        if (letters < 3) return false
        if (digits > letters) return false                   // Seitenzahlen, Datum, „100“
        if ('=' in t || t.contains("M.M", ignoreCase = true)) return false // Tempoangaben
        // Beschriftungen von Klaviaturen und Tonleitern: „G|A H c d e f“, „c'd'e'“
        if ('|' in t || t.count { it == '\'' || it == '’' } >= 2) return false
        if (t.split(' ').any { it.length > 18 }) return false
        if (DATE.containsMatchIn(t) && letters < 6) return false
        return true
    }
}
