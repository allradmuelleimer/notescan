package com.nicedevelopments.vomblatt.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.nicedevelopments.vomblatt.model.OcrText
import com.nicedevelopments.vomblatt.recognize.Recognition
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Mehrere Seiten zu einem Stück: die Fotos untereinander zu einem langen Blatt (alle auf die Breite der ersten Seite
 * gebracht), alle Koordinaten (Notenpositionen, Notenzeilen, Pedal, Gabeln, Text, Notizen) entsprechend versetzt,
 * die Takte der Folgeseiten hinten angehängt. Danach verhält sich das Stück wie eine einzige, lange Seite.
 */
object PageMerge {
    private class Place(val s: Float, val dy: Float)

    /** [pages]: Foto (aufrecht, wie von der Erkennung neu geschrieben) und Erkennungsergebnis je Seite. */
    fun merge(pages: List<Pair<File, Recognition>>, dir: File): Pair<File, Recognition> {
        require(pages.isNotEmpty())
        if (pages.size == 1) return pages.first()
        // Maße und Lage jeder Seite im langen Blatt
        val dims = pages.map { (f, _) ->
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, o); o.outWidth to o.outHeight
        }
        val w = dims.first().first
        val places = ArrayList<Place>()
        var y = 0f
        for ((pw, ph) in dims) { val s = w.toFloat() / pw; places.add(Place(s, y)); y += ph * s }
        val totalH = kotlin.math.ceil(y).toInt()

        // langes Foto
        val photo = File(dir, "pages_${System.currentTimeMillis()}.jpg")
        run {
            val big = Bitmap.createBitmap(w, totalH, Bitmap.Config.RGB_565)
            val c = Canvas(big)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            pages.forEachIndexed { i, (f, _) ->
                val b = BitmapFactory.decodeFile(f.absolutePath) ?: return@forEachIndexed
                val p = places[i]
                c.drawBitmap(b, null, RectF(0f, p.dy, w.toFloat(), p.dy + b.height * p.s), paint)
                b.recycle()
            }
            photo.outputStream().use { big.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            big.recycle()
        }

        // Notenzeilen, Pedal, Gabeln: je Seite versetzen, Systemnummern fortzählen
        var sysOffset = 0
        val staffs = JSONArray(); val notes = JSONArray(); val pedals = JSONArray(); val pins = JSONArray()
        pages.forEachIndexed { i, (_, r) ->
            val p = places[i]
            val ov = r.overlayJson?.let { runCatching { JSONObject(it) }.getOrNull() }
            var count = 0
            if (ov != null) {
                val st = ov.getJSONArray("staffs")
                count = st.length()
                for (k in 0 until st.length()) {
                    val s = st.getJSONObject(k)
                    val lines = s.getJSONArray("lines")
                    val nl = JSONArray()
                    for (li in 0 until lines.length()) nl.put(pointsXY(lines.getJSONArray(li), p))
                    val b = s.getJSONArray("box")
                    val o = JSONObject().put("lines", nl).put("box", JSONArray().apply {
                        put(b.getDouble(0) * p.s); put(b.getDouble(1) * p.s + p.dy); put(b.getDouble(2) * p.s); put(b.getDouble(3) * p.s + p.dy)
                    })
                    s.optJSONArray("bars")?.let { bars -> o.put("bars", JSONArray().apply { for (q in 0 until bars.length()) put(bars.getDouble(q) * p.s) }) }
                    staffs.put(o)
                }
                val n = ov.getJSONArray("notes")
                val moved = pointsXY(n, p)
                for (q in 0 until moved.length()) notes.put(moved.get(q))
            }
            r.pedalsJson?.let { runCatching { JSONArray(it) }.getOrNull() }?.let { a ->
                for (q in 0 until a.length()) {
                    val e = a.getJSONObject(q)
                    pedals.put(JSONObject(e.toString()).put("x", e.getDouble("x") * p.s).put("y", e.getDouble("y") * p.s + p.dy)
                        .put("system", e.optInt("system", -1).let { if (it >= 0) it + sysOffset else it }))
                }
            }
            r.hairpinsJson?.let { runCatching { JSONArray(it) }.getOrNull() }?.let { a ->
                for (q in 0 until a.length()) {
                    val e = a.getJSONObject(q)
                    pins.put(JSONObject(e.toString()).put("x0", e.getDouble("x0") * p.s).put("x1", e.getDouble("x1") * p.s)
                        .put("y", e.getDouble("y") * p.s + p.dy).put("system", e.optInt("system", -1).let { if (it >= 0) it + sysOffset else it }))
                }
            }
            sysOffset += count
        }
        val overlay = JSONObject().put("staffs", staffs).put("notes", notes).toString()

        // Text
        val text = pages.flatMapIndexed { i, (_, r) -> r.text.map { moveText(it, places[i]) } }

        // MusicXML: Takte der Folgeseiten anhängen, Fotopositionen (imgpos) versetzen
        val xml = mergeXml(pages.mapIndexed { i, (_, r) -> r.musicXml to places[i] })

        // Notizen und aufgehelltes Foto: im Maßstab der ersten Seite untereinander
        val ns = pages.first().second.notesScale
        var notesFile: File? = null
        if (pages.all { it.second.notesPng?.exists() == true }) runCatching {
            val nw = (w * ns).toInt(); val nh = (totalH * ns).toInt()
            val out = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888)
            val cleanOut = Bitmap.createBitmap(nw, nh, Bitmap.Config.RGB_565).also { it.eraseColor(android.graphics.Color.WHITE) }
            val c = Canvas(out); val cc = Canvas(cleanOut)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            pages.forEachIndexed { i, (_, r) ->
                val p = places[i]
                val dst = RectF(0f, p.dy * ns, nw.toFloat(), 0f)
                BitmapFactory.decodeFile(r.notesPng!!.absolutePath)?.let { b ->
                    dst.bottom = dst.top + b.height * (ns * p.s / r.notesScale)
                    c.drawBitmap(b, null, dst, paint); b.recycle()
                }
                File(r.notesPng!!.absolutePath.removeSuffix(".png") + ".clean.jpg").takeIf { it.exists() }?.let { cf ->
                    BitmapFactory.decodeFile(cf.absolutePath)?.let { b ->
                        cc.drawBitmap(b, null, RectF(0f, p.dy * ns, nw.toFloat(), p.dy * ns + b.height * (ns * p.s / r.notesScale)), paint); b.recycle()
                    }
                }
            }
            val nf = File(dir, photo.name + ".notes.png")
            nf.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(nf.absolutePath.removeSuffix(".png") + ".clean.jpg").outputStream().use { cleanOut.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            out.recycle(); cleanOut.recycle()
            notesFile = nf
        }.onFailure { android.util.Log.w("VomBlatt", "Seiten: Notizen", it) }

        val first = pages.first().second
        val merged = Recognition(
            xml, first.title, first.tempo, overlay, pedals.toString(), pins.toString(), text, notesFile, ns,
        )
        android.util.Log.d("VomBlatt", "Seiten zusammengeführt: ${pages.size} Seiten, Foto ${w}×$totalH, $sysOffset Notenzeilen")
        return photo to merged
    }

    private fun pointsXY(a: JSONArray, p: Place): JSONArray {
        val out = JSONArray()
        var k = 0
        while (k + 1 < a.length()) { out.put(a.getDouble(k) * p.s); out.put(a.getDouble(k + 1) * p.s + p.dy); k += 2 }
        return out
    }

    private fun moveText(t: OcrText, p: Place): OcrText =
        t.copy(l = t.l * p.s, t = t.t * p.s + p.dy, r = t.r * p.s, b = t.b * p.s + p.dy, words = t.words.map { moveText(it, p) })

    private val IMGPOS = Regex("""imgpos:\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)""")

    private fun mergeXml(parts: List<Pair<String, Place>>): String {
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false; isValidating = false }
        val b = f.newDocumentBuilder()
        b.setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) }
        val docs = parts.map { (x, _) -> b.parse(org.xml.sax.InputSource(java.io.StringReader(x))) }
        val main = docs.first()
        val mainPart = main.getElementsByTagName("part").item(0) as Element
        var number = mainPart.getElementsByTagName("measure").length
        docs.forEachIndexed { i, d ->
            val p = parts[i].second
            val part = if (i == 0) mainPart else d.getElementsByTagName("part").item(0) as? Element ?: return@forEachIndexed
            if (i > 0 || p.s != 1f || p.dy != 0f) moveComments(part, p)
            if (i == 0) return@forEachIndexed
            val ms = (0 until part.childNodes.length).map { part.childNodes.item(it) }.filter { it is Element && it.tagName == "measure" }
            for (m in ms) {
                val imported = main.importNode(m, true) as Element
                imported.setAttribute("number", (++number).toString())
                mainPart.appendChild(imported)
            }
        }
        val tf = TransformerFactory.newInstance().newTransformer().apply { setOutputProperty(OutputKeys.INDENT, "no") }
        val sw = StringWriter()
        tf.transform(DOMSource(main), StreamResult(sw))
        return sw.toString()
    }

    private fun moveComments(n: Node, p: Place) {
        val kids = n.childNodes
        for (k in 0 until kids.length) {
            val c = kids.item(k)
            if (c.nodeType == Node.COMMENT_NODE) {
                val v = c.nodeValue
                val m = IMGPOS.find(v)
                if (m != null) {
                    val x = m.groupValues[1].toFloat() * p.s; val y = m.groupValues[2].toFloat() * p.s + p.dy
                    c.nodeValue = v.replaceRange(m.range, "imgpos: %.1f, %.1f".format(java.util.Locale.ROOT, x, y))
                }
            } else if (c.hasChildNodes()) moveComments(c, p)
        }
    }
}
