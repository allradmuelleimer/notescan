package com.nicedevelopments.vomblatt.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.nicedevelopments.vomblatt.model.MusicXml
import com.nicedevelopments.vomblatt.model.Orn
import com.nicedevelopments.vomblatt.model.Score
import com.nicedevelopments.vomblatt.model.ScoreJson
import com.nicedevelopments.vomblatt.model.PedalMark
import com.nicedevelopments.vomblatt.model.PedalType
import com.nicedevelopments.vomblatt.recognize.Overlay
import org.json.JSONObject
import java.io.File
import java.io.InputStream

data class PieceInfo(
    val id: String,
    val title: String,
    val created: Long,
    val measures: Int,
    val key: String,
    val time: String,
    /** Größe des Originalfotos, auf die sich die Notenpositionen beziehen. */
    val sourceW: Int,
    val sourceH: Int,
    val dir: File,
    /** Maßstab der Notiz-Ebene gegenüber dem Originalfoto. */
    val notesScale: Float = 1f,
) {
    /** Freigestellte Bleistift-Notizen (PNG mit Transparenz), falls vorhanden. */
    val notes: File get() = File(dir, "notes.png")
    /** Weißabgeglichenes Foto (Papier weiß, Wesentliches dunkel), falls vorhanden. */
    val clean: File get() = File(dir, "clean.jpg")
    val photo: File get() = File(dir, "photo.jpg")
    val thumb: File get() = File(dir, "thumb.jpg")
    /** Erkannte Notenzeilen und Notenköpfe im Foto (aus der Erkennung), für Zeitbalken und Markierung. */
    val layout: File get() = File(dir, "layout.json")
    val hasPhoto: Boolean get() = photo.exists()
}

/** Ablage im App-Speicher: je Stück ein Ordner mit Foto, Original-MusicXML und bearbeiteter Fassung. */
class Library(context: Context) {
    private val root = File(context.filesDir, "pieces").apply { mkdirs() }

    fun list(): List<PieceInfo> = root.listFiles()?.mapNotNull { readInfo(it) }?.sortedByDescending { it.created } ?: emptyList()

    fun get(id: String): PieceInfo? = readInfo(File(root, id))

    private fun readInfo(dir: File): PieceInfo? {
        val f = File(dir, "meta.json")
        if (!f.exists()) return null
        return runCatching {
            val o = JSONObject(f.readText())
            PieceInfo(
                id = dir.name, title = o.getString("title"), created = o.getLong("created"),
                measures = o.optInt("measures"), key = o.optString("key"), time = o.optString("time"),
                sourceW = o.optInt("sourceW"), sourceH = o.optInt("sourceH"), dir = dir,
                notesScale = o.optDouble("notesScale", 1.0).toFloat(),
            )
        }.getOrNull()
    }

    fun loadScore(info: PieceInfo): Score = checkOrnamentsLater(info, readTextLater(info, loadStored(info)))

    /** Stücke aus älteren Fassungen: einmalig Zeichen aus Bleistift verwerfen ([dropPencilOrnaments]). */
    private fun checkOrnamentsLater(info: PieceInfo, score: Score): Score {
        val metaFile = File(info.dir, "meta.json")
        val meta = runCatching { JSONObject(metaFile.readText()) }.getOrNull() ?: return score
        if (meta.optInt("ornCheck") >= 1) return score
        val notes = File(info.dir, "notes.png")
        val result = if (notes.exists() && info.layout.exists()) runCatching {
            val systems = Overlay.parse(info.layout.readText()).systems()
            com.nicedevelopments.vomblatt.model.PhotoMarks.rebaseFingers(dropPencilOrnaments(score, notes, info.notesScale, systems), systems)
        }.onFailure { android.util.Log.w("VomBlatt", "Zeichen prüfen", it) }.getOrNull() ?: score else score
        meta.put("ornCheck", 1)
        metaFile.writeText(meta.toString())
        if (result !== score) File(info.dir, "score.json").writeText(ScoreJson.write(result))
        return result
    }

    private fun loadStored(info: PieceInfo): Score {
        val edited = File(info.dir, "score.json")
        val source = File(info.dir, "source.musicxml")
        if (edited.exists()) {
            val text = edited.readText()
            val saved = ScoreJson.read(text)
            if (ScoreJson.version(text) >= ScoreJson.VERSION || !source.exists()) return saved
            // Gespeichert mit älterer App: neue Zeichen aus dem Erkennungsergebnis nachtragen
            val upgraded = runCatching {
                val fresh = source.inputStream().use { MusicXml.parse(it, info.title) }
                com.nicedevelopments.vomblatt.model.ScoreEdit.upgradeFrom(saved, fresh)
            }.getOrElse { saved }
            edited.writeText(ScoreJson.write(upgraded))
            return upgraded
        }
        return source.inputStream().use { MusicXml.parse(it, info.title) }
    }

    fun saveScore(info: PieceInfo, score: Score): PieceInfo {
        val edited = File(info.dir, "score.json")
        // Stand vor der ersten Korrektur festhalten (ältere Stücke): „Korrekturen verwerfen“ kehrt dorthin zurück
        val recognized = File(info.dir, RECOGNIZED)
        if (!recognized.exists() && edited.exists()) edited.copyTo(recognized)
        edited.writeText(ScoreJson.write(score))
        return writeMeta(info.dir, score, info.created, info.sourceW, info.sourceH)
    }

    fun rename(info: PieceInfo, title: String): PieceInfo {
        val s = loadScore(info).copy(title = title)
        return saveScore(info, s)
    }

    /** Zurück zum Erkennungsergebnis, alle Korrekturen verwerfen. */
    fun resetEdits(info: PieceInfo) {
        // Erkennungsergebnis samt Nachbearbeitung (Fingersätze, Akkordsymbole, Pedal, Tempo …) – nicht nur homrs MusicXML
        val recognized = File(info.dir, RECOGNIZED)
        if (recognized.exists()) { recognized.copyTo(File(info.dir, "score.json"), overwrite = true); return }
        File(info.dir, "score.json").delete()
        // Akkordsymbole und Anweisungen beim nächsten Öffnen erneut aus dem Foto lesen
        val metaFile = File(info.dir, "meta.json")
        runCatching { JSONObject(metaFile.readText()).apply { remove("textRead"); remove("textReadV"); remove("ornCheck") }.let { metaFile.writeText(it.toString()) } }
    }

    fun delete(info: PieceInfo) { info.dir.deleteRecursively() }

    /**
     * Legt ein neues Stück an. [photo] ist das Originalfoto (optional), [musicXml] das Erkennungsergebnis
     * oder eine importierte Datei.
     */
    fun create(
        musicXml: String, photo: File?, fallbackTitle: String, tempo: Int? = null,
        overlayJson: String? = null, pedalsJson: String? = null,
        hairpinsJson: String? = null, text: List<com.nicedevelopments.vomblatt.model.OcrText> = emptyList(),
        notesPng: File? = null, notesScale: Float = 1f,
    ): PieceInfo {
        val id = "p" + System.currentTimeMillis().toString(36)
        val dir = File(root, id).apply { mkdirs() }
        File(dir, "source.musicxml").writeText(musicXml)
        var w = 0; var h = 0
        var photoBmp: Bitmap? = null
        if (photo != null && photo.exists()) {
            val bmp = decodePhoto(photo, 2400)
            photoBmp = bmp
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(photo.absolutePath, o)
            val rotated = exifRotation(photo) % 180 != 0
            w = if (rotated) o.outHeight else o.outWidth
            h = if (rotated) o.outWidth else o.outHeight
            File(dir, "photo.jpg").outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            val t = Bitmap.createScaledBitmap(bmp, 240, (240f * bmp.height / bmp.width).toInt(), true)
            File(dir, "thumb.jpg").outputStream().use { t.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        }
        var score = musicXml.byteInputStream().use { MusicXml.parse(it, fallbackTitle) }
        var changed = false
        if (tempo != null) { score = score.copy(tempo = tempo); changed = true }
        if (overlayJson != null && w > 0) {
            File(dir, "layout.json").writeText(overlayJson)
            runCatching { Overlay.parse(overlayJson) }.getOrNull()?.let { ov ->
                score = snapToHeads(score, ov.notes, w); changed = true
                if (pedalsJson != null) {
                    val marks = pedalsToMarks(score, pedalsJson, ov.staffBoxes, w)
                    if (marks.isNotEmpty()) score = score.copy(pedals = marks)
                    // homr liest die gedruckten Pedalzacken (^) unter dem Bass gern als Marcato der Bassnote
                    score = dropPedalMarcato(score, marks)
                }
                val systems = ov.systems()
                // Streifen um die Systeme vergrößert nachlesen – findet einzelne Akkordbuchstaben zuverlässiger
                val allText = if (text.isNotEmpty()) text else photoBmp?.let { bmp ->
                    runCatching { com.nicedevelopments.vomblatt.recognize.TitleReader.linesWithBands(bmp, w.toFloat() / bmp.width, Overlay.bands(systems)) }
                        .onFailure { android.util.Log.w("VomBlatt", "Text", it) }.getOrNull()
                } ?: text
                score = com.nicedevelopments.vomblatt.model.PhotoMarks.place(
                    score, allText, parseHairpins(hairpinsJson), systems, hasSheetTempo = tempo != null,
                )
            }
        }
        if (notesPng != null && notesPng.exists() && w > 0) {
            // Bögen, die homr aus Bleistiftkreisen gelesen hat (dort liegt Notiz-Tinte), sind kein Druck
            if (overlayJson != null) runCatching {
                val ov = Overlay.parse(overlayJson)
                val cleaned = dropPencilOrnaments(dropPencilSlurs(score, notesPng, notesScale, ov.systems()), notesPng, notesScale, ov.systems())
                if (cleaned !== score) { score = cleaned; changed = true }
            }.onFailure { android.util.Log.w("VomBlatt", "Bögen prüfen", it) }
            notesPng.copyTo(File(dir, "notes.png"), overwrite = true)
            notesPng.delete()
            val clean = File(notesPng.absolutePath.removeSuffix(".png") + ".clean.jpg")
            if (clean.exists()) { clean.copyTo(File(dir, "clean.jpg"), overwrite = true); clean.delete() }
        }
        val info = writeMeta(dir, score, System.currentTimeMillis(), w, h, textRead = overlayJson != null && w > 0,
            notesScale = if (File(dir, "notes.png").exists()) notesScale else null)
        // Ausgangsstand für „Korrekturen verwerfen“
        File(dir, RECOGNIZED).writeText(ScoreJson.write(score))
        return if (changed) saveScore(info, score) else info
    }

    /** Marcato/Keil an Bassakkorden, die genau auf einer gedruckten Pedalstelle stehen, ist eine falsch gelesene Pedalzacke. */
    private fun dropPedalMarcato(score: Score, marks: List<PedalMark>): Score {
        if (marks.isEmpty()) return score
        val at = marks.map { it.measure to it.tick }.toSet()
        var hit = false
        val ms = score.measures.mapIndexed { mi, m ->
            m.copy(chords = m.chords.map { c ->
                val wrong = setOf(com.nicedevelopments.vomblatt.model.Orn.MARCATO, com.nicedevelopments.vomblatt.model.Orn.STACCATISSIMO)
                // (Staccatissimo liest der Parser als staccato, strong-accent als accent + Marcato)
                if (c.staff == 1 && (c.orn.any { it in wrong } || c.staccato || c.accent) && (mi to c.start) in at) {
                    hit = true; c.copy(orn = c.orn - wrong, staccato = false, accent = false)
                } else c
            })
        }
        return if (hit) score.copy(measures = ms) else score
    }

    /**
     * Bögen, unter/über denen im Foto Bleistift liegt (Notiz-Ebene), stammen von Bleistiftkreisen: entfernen.
     * Prüfbereich: waagrecht zwischen den beiden Noten, senkrecht bis 3 Linienabstände über/unter ihnen.
     */
    private fun dropPencilSlurs(score: Score, notesPng: File, scale: Float, systems: List<com.nicedevelopments.vomblatt.model.SystemGeom>): Score {
        if (score.slurs.isEmpty() || systems.isEmpty()) return score
        val bmp = BitmapFactory.decodeFile(notesPng.absolutePath) ?: return score
        try {
            val ls = systems.map { it.ls }.sorted()[systems.size / 2]
            val byId = score.measures.flatMap { it.chords }.associateBy { it.id }
            fun pos(id: Long): Pair<Float, Float>? {
                val ns = byId[id]?.notes?.filter { it.imgX != null && it.imgY != null } ?: return null
                if (ns.isEmpty()) return null
                return ns.map { it.imgX!! }.average().toFloat() to ns.map { it.imgY!! }.average().toFloat()
            }
            val keep = score.slurs.filter { sl ->
                val a = pos(sl.from) ?: return@filter true
                val b = pos(sl.to) ?: return@filter true
                // (bis 1,5 Linienabstände über die Notenköpfe hinaus: ein Kreis um die Zielnote liegt oft daneben)
                val x0 = ((minOf(a.first, b.first) - 1.5f * ls) * scale).toInt().coerceIn(0, bmp.width - 1)
                val x1 = ((maxOf(a.first, b.first) + 1.5f * ls) * scale).toInt().coerceIn(x0 + 1, bmp.width)
                val y0 = ((minOf(a.second, b.second) - 3 * ls) * scale).toInt().coerceIn(0, bmp.height - 1)
                val y1 = ((maxOf(a.second, b.second) + 3 * ls) * scale).toInt().coerceIn(y0 + 1, bmp.height)
                val px = IntArray((x1 - x0) * (y1 - y0))
                bmp.getPixels(px, 0, x1 - x0, x0, y0, x1 - x0, y1 - y0)
                // Notiz-Tinte am Bogen (mehr als ein kurzes Strichstück): dann stammt er vom Bleistift – homr liest die
                // Seite eines Kreises gern als Bogen zwischen zwei Nachbarnoten
                var ink = 0
                for (v in px) if ((v ushr 24) > 40) ink++
                ink < 2f * ls * scale
            }
            return if (keep.size == score.slurs.size) score else score.copy(slurs = keep)
        } finally { bmp.recycle() }
    }

    /**
     * Zeichen über/unter der Note (Fermate, Triller, Mordent, Doppelschlag), die homr aus Bleistift gelesen hat: liegt
     * genau dort, wo das Zeichen stünde (über dem oberen bzw. unter dem unteren System an der Note), Notiz-Tinte, ist es
     * kein Druck – gedruckte Fermaten kommen nie in die Notiz-Ebene. Gideon 18.11.: ein Bleistiftkreis um eine
     * Fingersatz-3 wurde zur Fermate und hielt die Note beim Abspielen sinnlos an (Harmony T. 4).
     */
    private fun dropPencilOrnaments(score: Score, notesPng: File, scale: Float, systems: List<com.nicedevelopments.vomblatt.model.SystemGeom>): Score {
        val marks = setOf(Orn.FERMATA, Orn.TRILL, Orn.PRALL, Orn.MORDENT, Orn.TURN, Orn.TURN_INV)
        if (systems.isEmpty() || score.measures.none { m -> m.chords.any { c -> c.orn.any { it in marks } } }) return score
        val bmp = BitmapFactory.decodeFile(notesPng.absolutePath) ?: return score
        try {
            var dropped = 0
            val out = score.copy(measures = score.measures.map { m ->
                m.copy(chords = m.chords.map { c ->
                    if (c.orn.none { it in marks }) return@map c
                    val ns = c.notes.filter { it.imgX != null && it.imgY != null }
                    if (ns.isEmpty()) return@map c
                    val cx = ns.map { it.imgX!! }.average().toFloat()
                    val cy = ns.map { it.imgY!! }.average().toFloat()
                    val g = systems.minByOrNull { s -> if (cy in s.top0 - 6 * s.ls..s.bot1 + 6 * s.ls) 0f else minOf(kotlin.math.abs(cy - s.top0), kotlin.math.abs(cy - s.bot1)) } ?: return@map c
                    val sh = g.shiftAt(cx)
                    val (ya, yb) = if (c.staff == 0) (g.top0 + sh - 4.5f * g.ls) to (g.top0 + sh + 0.3f * g.ls)
                        else (g.bot1 + sh - 0.3f * g.ls) to (g.bot1 + sh + 4.5f * g.ls)
                    val x0 = ((cx - 1.5f * g.ls) * scale).toInt().coerceIn(0, bmp.width - 1)
                    val x1 = ((cx + 1.5f * g.ls) * scale).toInt().coerceIn(x0 + 1, bmp.width)
                    val y0 = (ya * scale).toInt().coerceIn(0, bmp.height - 1)
                    val y1 = (yb * scale).toInt().coerceIn(y0 + 1, bmp.height)
                    val px = IntArray((x1 - x0) * (y1 - y0))
                    bmp.getPixels(px, 0, x1 - x0, x0, y0, x1 - x0, y1 - y0)
                    var ink = 0
                    for (v in px) if ((v ushr 24) > 40) ink++
                    if (ink < 2f * g.ls * scale) c else { dropped++; c.copy(orn = c.orn - marks) }
                })
            })
            if (dropped > 0) android.util.Log.d("VomBlatt", "Zeichen aus Bleistift verworfen: $dropped")
            return if (dropped == 0) score else out
        } finally { bmp.recycle() }
    }

    /**
     * Pedalstellen aus dem Foto Takten und Schlägen zuordnen: jede Stelle rückt auf den Notenanfang
     * im selben Klaviersystem, der ihr waagrecht am nächsten liegt (Zacken stehen unter der neuen Note).
     */
    private fun pedalsToMarks(score: Score, json: String, boxes: List<android.graphics.RectF>, width: Int): List<PedalMark> {
        val arr = runCatching { org.json.JSONArray(json) }.getOrNull() ?: return emptyList()
        val margin = width * 0.02f
        // Anker: (Takt, Tick, x, y) aller Notenanfänge mit Fotoposition
        val anchors = score.measures.flatMapIndexed { mi, m ->
            m.chords.filter { c -> c.notes.any { it.imgX != null } }.groupBy { it.start }.map { (start, cs) ->
                val ns = cs.flatMap { it.notes }.filter { it.imgX != null && it.imgY != null }
                listOf(mi.toFloat(), start.toFloat(), ns.minOf { it.imgX!! }, ns.map { it.imgY!! }.average().toFloat())
            }
        }
        val marks = mutableListOf<PedalMark>()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val x = e.getDouble("x").toFloat()
            val sys = e.optInt("system", -1)
            val box = boxes.getOrNull(sys)
            val inSystem = anchors.filter { a -> box == null || (a[3] >= box.top - margin && a[3] <= box.bottom + margin) }
            val best = inSystem.minByOrNull { kotlin.math.abs(it[2] - x) } ?: continue
            val type = when (e.getString("type")) { "down" -> PedalType.DOWN; "change" -> PedalType.CHANGE; else -> PedalType.UP }
            var mi = best[0].toInt(); var tick = best[1].toInt()
            // „Lösen“ rechts vom letzten Ton eines Takts: am Taktende (= Anfang des nächsten Takts)
            if (type == PedalType.UP && x > best[2] + width * 0.01f && inSystem.none { it[0].toInt() == mi && it[2] > best[2] }) {
                if (mi + 1 < score.measures.size) { mi += 1; tick = 0 } else tick = score.measures[mi].length // Schluss des Stücks
            }
            marks.add(PedalMark(mi, tick, type))
        }
        // Doppelte Stellen zusammenfassen, sortieren, und Wechsel ohne vorheriges Treten zu „Treten“ machen
        // an derselben Stelle zählt nur eine Pedalstelle: Treten/Wechseln geht vor Lösen (sonst Zacke + Endstrich übereinander)
        val sorted = marks.distinctBy { Triple(it.measure, it.tick, it.type) }
            .filter { m -> m.type != PedalType.UP || marks.none { o -> o.measure == m.measure && o.tick == m.tick && o.type != PedalType.UP } }
            .sortedWith(compareBy({ it.measure }, { it.tick }))
        var down = false
        return sorted.map { m ->
            val fixed = if (m.type == PedalType.CHANGE && !down) m.copy(type = PedalType.DOWN) else m
            down = fixed.type != PedalType.UP
            fixed
        }
    }

    /**
     * homr schätzt Notenpositionen nur grob (aus der Aufmerksamkeit des Modells). Die Segmentierung
     * kennt die Notenköpfe genau – jede Note wird auf den nächstgelegenen passenden Kopf gezogen.
     */
    private fun snapToHeads(score: Score, heads: FloatArray, width: Int): Score {
        if (heads.isEmpty()) return score
        val maxDx = width * 0.05f
        val maxDy = width * 0.012f
        fun snap(x: Float, y: Float): Pair<Float, Float> {
            var best = -1; var bestD = Float.MAX_VALUE
            var i = 0
            while (i < heads.size) {
                val dx = kotlin.math.abs(heads[i] - x); val dy = kotlin.math.abs(heads[i + 1] - y)
                if (dx <= maxDx && dy <= maxDy) {
                    val d = dx + 3 * dy
                    if (d < bestD) { bestD = d; best = i }
                }
                i += 2
            }
            return if (best >= 0) heads[best] to heads[best + 1] else x to y
        }
        return score.copy(measures = score.measures.map { m ->
            m.copy(chords = m.chords.map { c ->
                c.copy(notes = c.notes.map { n ->
                    if (n.imgX == null || n.imgY == null) n
                    else snap(n.imgX, n.imgY).let { (x, y) -> n.copy(imgX = x, imgY = y) }
                })
            })
        })
    }

    fun importStream(input: InputStream, name: String): PieceInfo =
        create(input.bufferedReader().readText(), null, name.substringBeforeLast('.'))

    private fun parseHairpins(json: String?): List<com.nicedevelopments.vomblatt.model.PhotoHairpin> {
        val arr = runCatching { org.json.JSONArray(json ?: return emptyList()) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            com.nicedevelopments.vomblatt.model.PhotoHairpin(
                o.getDouble("x0").toFloat(), o.getDouble("x1").toFloat(), o.getDouble("y").toFloat(),
                o.getString("type") == "cresc", o.optInt("system", -1),
            )
        }
    }

    /**
     * Stücke aus älteren App-Fassungen: Akkordsymbole und Spielanweisungen einmalig aus dem gespeicherten
     * Foto nachlesen (Gabeln brauchen die volle Erkennung und kommen nur bei neuen Fotos).
     */
    private fun readTextLater(info: PieceInfo, score: Score): Score {
        val metaFile = File(info.dir, "meta.json")
        val meta = runCatching { JSONObject(metaFile.readText()) }.getOrNull() ?: return score
        // Stand 2: mit Fingersätzen
        if (meta.optInt("textReadV") >= 2 || !info.photo.exists() || !info.layout.exists() || info.sourceW <= 0) return score
        val result = runCatching {
            val ov = Overlay.parse(info.layout.readText())
            val bmp = BitmapFactory.decodeFile(info.photo.absolutePath) ?: return score
            val lines = com.nicedevelopments.vomblatt.recognize.TitleReader.linesWithBands(bmp, info.sourceW.toFloat() / bmp.width, Overlay.bands(ov.systems()))
            com.nicedevelopments.vomblatt.model.PhotoMarks.place(score, lines, emptyList(), ov.systems(), hasSheetTempo = true)
        }.onFailure { android.util.Log.w("VomBlatt", "Text nachlesen", it) }.getOrNull() ?: score
        meta.put("textRead", true).put("textReadV", 2)
        metaFile.writeText(meta.toString())
        if (result != score) File(info.dir, "score.json").writeText(ScoreJson.write(result))
        return result
    }

    private fun writeMeta(dir: File, score: Score, created: Long, w: Int, h: Int, textRead: Boolean? = null, notesScale: Float? = null): PieceInfo {
        val first = score.measures.firstOrNull()
        val o = JSONObject()
            .put("title", score.title)
            .put("created", created)
            .put("measures", score.measures.size)
            .put("key", score.keyName)
            .put("time", first?.let { "${it.beats}/${it.beatType}" } ?: "")
            .put("sourceW", w).put("sourceH", h)
        // Merker „Text gelesen“ bei späteren Speicherungen erhalten
        val old = runCatching { JSONObject(File(dir, "meta.json").readText()) }.getOrNull()
        if (textRead ?: old?.optBoolean("textRead") == true) o.put("textRead", true)
        if (textRead == true) o.put("textReadV", 2) else old?.optInt("textReadV")?.takeIf { it > 0 }?.let { o.put("textReadV", it) }
        (notesScale?.toDouble() ?: old?.optDouble("notesScale")?.takeIf { !it.isNaN() })?.let { o.put("notesScale", it) }
        // neue Stücke sind schon geprüft (beim Anlegen), ältere behalten ihren Stand
        if (notesScale != null || old?.optInt("ornCheck") == 1) o.put("ornCheck", 1)
        File(dir, "meta.json").writeText(o.toString())
        return readInfo(dir)!!
    }

    companion object {
        /** Erkanntes und nachbearbeitetes Stück vor jeder Korrektur. */
        private const val RECOGNIZED = "recognized.json"
        fun exifRotation(f: File): Int = runCatching {
            when (ExifInterface(f.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }.getOrDefault(0)

        /** Foto aufrecht und auf höchstens [maxSide] Pixel verkleinert laden. */
        /**
         * Wie [decodeUpright], aber für lange Blätter aus mehreren Seiten: die Grenze gilt dann je Seite (bei einem
         * Seitenverhältnis über 1,45 wird sie entsprechend größer), damit die Breite nicht schrumpft.
         */
        fun decodePhoto(f: File, maxSide: Int): Bitmap {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, o)
            val lo = minOf(o.outWidth, o.outHeight).coerceAtLeast(1); val hi = maxOf(o.outWidth, o.outHeight)
            val pages = (hi.toFloat() / lo / 1.45f).coerceAtLeast(1f)
            return decodeUpright(f, (maxSide * pages).toInt())
        }

        fun decodeUpright(f: File, maxSide: Int): Bitmap {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, o)
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            val rot = exifRotation(f)
            var out = bmp
            val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
            val m = Matrix()
            if (scale < 1f) m.postScale(scale, scale)
            if (rot != 0) m.postRotate(rot.toFloat())
            if (scale < 1f || rot != 0) out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            return out
        }
    }
}
