package com.nicedevelopments.vomblatt.recognize

import android.content.Context
import java.io.File

/** Fortschritt der Erkennung, in Schritten, die homr tatsächlich durchläuft. */
data class Progress(val phase: Phase, val done: Int = 0, val total: Int = 0) {
    enum class Phase(@androidx.annotation.StringRes val label: Int) {
        PREPARE(com.nicedevelopments.vomblatt.R.string.phase_prepare),
        SEGMENT(com.nicedevelopments.vomblatt.R.string.phase_segment),
        STAFFS(com.nicedevelopments.vomblatt.R.string.phase_staffs),
        READ(com.nicedevelopments.vomblatt.R.string.phase_read),
        WRITE(com.nicedevelopments.vomblatt.R.string.phase_write),
    }
}

/** Was die Erkennung auf dem Foto gefunden hat (Fotokoordinaten), für die Live-Ansicht. */
class Overlay(val staffLines: List<List<FloatArray>>, val staffBoxes: List<android.graphics.RectF>, val notes: FloatArray, val bars: List<FloatArray> = emptyList()) {
    val noteCount: Int get() = notes.size / 2

    /** Lage der Klaviersysteme aus den erkannten Notenlinien (je System 5 oder 10 Linien). */
    fun systems(): List<com.nicedevelopments.vomblatt.model.SystemGeom> =
        staffLines.mapIndexedNotNull { i, lines ->
            if (lines.size < 5) return@mapIndexedNotNull null
            val ys = lines.map { l -> (1 until l.size step 2).map { l[it] }.average().toFloat() }
            val ls = (1 until minOf(5, ys.size)).map { ys[it] - ys[it - 1] }.sorted().let { it[it.size / 2] }
            val box = staffBoxes.getOrNull(i)
            com.nicedevelopments.vomblatt.model.SystemGeom(
                ys[0], ys[4], if (ys.size >= 10) ys[5] else ys[4], ys.last(),
                box?.left ?: 0f, box?.right ?: Float.MAX_VALUE, ls, lines[0], bars.getOrNull(i) ?: FloatArray(0),
            )
        }

    companion object {
        /** Streifen, in denen Akkordsymbole und Anweisungen stehen: über, zwischen und unter den Systemen. */
        fun bands(systems: List<com.nicedevelopments.vomblatt.model.SystemGeom>): List<android.graphics.RectF> =
            systems.flatMap { g ->
                val lift = g.topLine.filterIndexed { i, _ -> i % 2 == 1 }.minOrNull()?.let { it - g.top0 } ?: 0f
                val drop = g.topLine.filterIndexed { i, _ -> i % 2 == 1 }.maxOrNull()?.let { it - g.top0 } ?: 0f
                listOf(
                    android.graphics.RectF(g.left - 6 * g.ls, g.top0 + lift - 7 * g.ls, g.right + 2 * g.ls, g.top0 + drop + g.ls),
                    // nur die Akkordzeile (ohne Fingersätze darunter, die einzelne Buchstaben verdecken)
                    android.graphics.RectF(g.left - 6 * g.ls, g.top0 + lift - 6.5f * g.ls, g.right + 2 * g.ls, g.top0 + drop - 3.2f * g.ls),
                    android.graphics.RectF(g.left - 2 * g.ls, g.bot0 + lift, g.right + 2 * g.ls, g.top1 + drop),
                    // Fingersatz-Zeilen direkt über und unter dem System (kleine, gestapelte Ziffern)
                    android.graphics.RectF(g.left - 2 * g.ls, g.top0 + lift - 4.5f * g.ls, g.right + 2 * g.ls, g.top0 + drop + 0.5f * g.ls),
                    android.graphics.RectF(g.left - 2 * g.ls, g.bot1 + lift - 0.5f * g.ls, g.right + 2 * g.ls, g.bot1 + drop + 4f * g.ls),
                    android.graphics.RectF(g.left - 2 * g.ls, g.bot1 + lift, g.right + 2 * g.ls, g.bot1 + drop + 5 * g.ls),
                )
            }

        fun parse(json: String): Overlay {
            val o = org.json.JSONObject(json)
            val st = o.getJSONArray("staffs")
            val lines = mutableListOf<List<FloatArray>>()
            val boxes = mutableListOf<android.graphics.RectF>()
            val bars = mutableListOf<FloatArray>()
            for (i in 0 until st.length()) {
                val s = st.getJSONObject(i)
                val ba = s.optJSONArray("bars")
                bars.add(if (ba == null) FloatArray(0) else FloatArray(ba.length()) { ba.getDouble(it).toFloat() })
                val ls = s.getJSONArray("lines")
                lines.add((0 until ls.length()).map { k ->
                    val a = ls.getJSONArray(k); FloatArray(a.length()) { a.getDouble(it).toFloat() }
                })
                val b = s.getJSONArray("box")
                boxes.add(android.graphics.RectF(b.getDouble(0).toFloat(), b.getDouble(1).toFloat(), b.getDouble(2).toFloat(), b.getDouble(3).toFloat()))
            }
            val n = o.getJSONArray("notes")
            return Overlay(lines, boxes, FloatArray(n.length()) { n.getDouble(it).toFloat() }, bars)
        }
    }
}

/** Ergebnis: MusicXML, falls lesbar Titel und Tempo vom Blatt, dazu die gefundenen Zeilen/Noten im Foto. */
class Recognition(
    val musicXml: String, val title: String?, val tempo: Int?, val overlayJson: String?,
    /** Gefundene Pedalstellen in Fotokoordinaten (JSON aus vomblatt_omr._detect_pedals). */
    val pedalsJson: String? = null,
    /** Gefundene Gabeln in Fotokoordinaten (JSON aus vomblatt_omr._detect_hairpins). */
    val hairpinsJson: String? = null,
    /** Gelesener Text des Blatts (Akkordsymbole, Spielanweisungen). */
    val text: List<com.nicedevelopments.vomblatt.model.OcrText> = emptyList(),
    /** Freigestellte Notizen (PNG mit Transparenz) und ihr Maßstab gegenüber dem Foto. */
    val notesPng: java.io.File? = null,
    val notesScale: Float = 1f,
)

class RecognitionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Liest ein Notenblatt-Foto und liefert MusicXML. Läuft vollständig auf dem Gerät. */
interface Recognizer {
    val available: Boolean
    /** Nach einem Auftrag (auch abgebrochen): Modelle sicher freigeben. */
    suspend fun release() {}

    /** [inheritedTime]: Taktart am Ende der vorigen Seite („12/8“) – gilt, wenn die Seite ohne eigene Taktart beginnt. */
    suspend fun recognize(photo: File, onProgress: (Progress) -> Unit, onOverlay: (Overlay) -> Unit = {}, inheritedTime: String? = null): Recognition

    companion object {
        fun create(context: Context): Recognizer = HomrRecognizer.createOrNull(context) ?: Unavailable(context.applicationContext)
    }

    class Unavailable(private val context: Context) : Recognizer {
        override val available = false
        override suspend fun recognize(photo: File, onProgress: (Progress) -> Unit, onOverlay: (Overlay) -> Unit, inheritedTime: String?): Recognition =
            throw RecognitionException(context.getString(com.nicedevelopments.vomblatt.R.string.err_16k))
    }
}
