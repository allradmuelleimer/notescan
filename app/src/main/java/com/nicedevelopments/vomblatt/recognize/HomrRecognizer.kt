package com.nicedevelopments.vomblatt.recognize

import android.content.Context
import android.graphics.Bitmap
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.nicedevelopments.vomblatt.data.Library
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Notenerkennung mit homr (AGPL-3.0) direkt auf dem Gerät: Python-Teil über Chaquopy,
 * die neuronalen Netze über die ONNX Runtime für Android (siehe [OrtBridge]).
 */
class HomrRecognizer private constructor(private val context: Context) : Recognizer {
    override val available = true

    /** Wird aus Python aufgerufen (vomblatt_omr.recognize). */
    class ProgressSink(private val onProgress: (Progress) -> Unit, private val onOverlay: (Overlay) -> Unit, private val isCancelled: () -> Boolean = { false }) {
        /** Wird aus Python bei jedem Fortschritt gefragt: hat der Nutzer abgebrochen? */
        @Suppress("unused")
        fun cancelled(): Boolean = isCancelled()

        var json: String? = null
        var pedals: String? = null
        var hairpins: String? = null

        @Suppress("unused")
        fun hairpins(json: String) { hairpins = json }

        @Suppress("unused")
        fun pedals(json: String) { pedals = json }

        @Suppress("unused")
        fun overlay(json: String) {
            this.json = json
            runCatching { Overlay.parse(json) }.onSuccess(onOverlay)
                .onFailure { android.util.Log.w("VomBlatt", "Overlay", it) }
        }

        @Suppress("unused")
        fun update(phase: String, done: Int, total: Int) {
            val p = runCatching { Progress.Phase.valueOf(phase) }.getOrNull() ?: return
            onProgress(Progress(p, done, total))
        }
    }

    /** Nach einem mehrseitigen Auftrag: Sitzungen schließen – unter derselben Sperre wie die Erkennung. */
    override suspend fun release() {
        busy.withLock { OrtBridge.keepOpen = false; OrtBridge.closeAll() }
    }

    override suspend fun recognize(photo: File, onProgress: (Progress) -> Unit, onOverlay: (Overlay) -> Unit, inheritedTime: String?): Recognition =
        // Nie zwei Erkennungen gleichzeitig: eine abgebrochene läuft in Python noch bis zur nächsten Prüfstelle weiter
        // und teilt sich die Modell-Sitzungen mit der neuen (sonst: Absturz in der ONNX Runtime beim Schließen)
        busy.withLock { recognizeLocked(photo, onProgress, onOverlay, inheritedTime) }

    private suspend fun recognizeLocked(photo: File, onProgress: (Progress) -> Unit, onOverlay: (Overlay) -> Unit, inheritedTime: String?): Recognition = withContext(Dispatchers.Default) {
        val job = coroutineContext[kotlinx.coroutines.Job]
        val isCancelled = { job?.isCancelled == true }
        OrtBridge.cancelled = isCancelled
        OrtBridge.xnnpack = !File(context.filesDir, "no_xnn").exists()
        OrtBridge.conf = File(context.filesDir, "ort_conf").takeIf { it.exists() }?.readText()?.trim()?.split(";")
            ?.mapNotNull { kv -> kv.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }?.toMap() ?: emptyMap()
        onProgress(Progress(Progress.Phase.PREPARE))
        // Aufrecht drehen und auf vernünftige Größe bringen; die Datei wird ersetzt,
        // damit Notenpositionen und gespeichertes Foto dasselbe Koordinatensystem haben.
        // Original in voller Auflösung behalten: winzige Fingersatz-Ziffern liest man dort besser
        val original = File(photo.parentFile, photo.name + ".orig")
        runCatching { photo.copyTo(original, overwrite = true) }
        val bmp = Library.decodeUpright(photo, 2600)
        val workWidth = bmp.width
        photo.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bmp.recycle()

        if (!Python.isStarted()) Python.start(AndroidPlatform(context))
        // Absturzbericht für Abstürze in nativen Bibliotheken (OpenCV): Python-Aufrufstelle in eine Datei schreiben
        if (!faultInstalled) runCatching {
            Python.getInstance().getModule("vomblatt_omr").callAttr("install_faulthandler", File(context.filesDir, "python_fault.txt").absolutePath)
            faultInstalled = true
        }
        OrtBridge.assets = context.assets
        val py = Python.getInstance()
        var found: Overlay? = null
        try {
            val module = py.getModule("vomblatt_omr")
            val sink = ProgressSink(onProgress, { o -> found = o; onOverlay(o) }, isCancelled)
            val tStart = android.os.SystemClock.elapsedRealtime()
            val times = StringBuilder()
            var tLast = tStart
            fun lap(name: String) { val n = android.os.SystemClock.elapsedRealtime(); times.append("$name %.1f s, ".format((n - tLast) / 1000f)); tLast = n }
            val xml = module.callAttr("recognize", photo.absolutePath, "asset:models", sink, inheritedTime).toString()
            lap("Noten (homr)")
            ensureActive()
            val head = TitleReader.read(
                photo, found?.staffBoxes?.minOfOrNull { it.top }, found?.systems()?.minByOrNull { it.top0 }?.ls,
                found?.staffBoxes?.takeIf { it.isNotEmpty() }?.let { bx ->
                    val l = bx.minOf { it.left }; val r = bx.maxOf { it.right }; val m = (r - l) * 0.08f
                    (l - m)..(r + m)
                },
            )
            lap("Titel")
            // Text um die Systeme gründlich lesen (auch vergrößert) – für Akkordsymbole und um Druck von Notizen zu trennen
            val ov = found
            var lines = head?.lines ?: emptyList()
            var notes: File? = null
            var notesScale = 1f
            if (ov != null) {
                val systems = ov.systems()
                runCatching {
                    android.graphics.BitmapFactory.decodeFile(photo.absolutePath)?.let { b ->
                        lines = TitleReader.linesWithBands(b, 1f, Overlay.bands(systems)); b.recycle()
                    }
                }.onFailure { android.util.Log.w("VomBlatt", "Text", it) }
                lap("Text")
                val printed = com.nicedevelopments.vomblatt.model.PhotoMarks.printBoxes(lines, systems)
                val boxes = org.json.JSONArray(printed.map { org.json.JSONArray(it.toList()) })
                val out = File(photo.parentFile, photo.name + ".notes.png")
                // PDF-Seiten (gerendert, „pdf_…“) enthalten keine Bleistift-Notizen: nur Fingersatz-Kandidaten suchen
                val withNotes = !photo.name.startsWith("pdf_")
                val sc = runCatching { module.callAttr("extract_notes", photo.absolutePath, boxes.toString(), out.absolutePath, if (original.exists()) original.absolutePath else "", withNotes).toFloat() }
                    .onFailure { android.util.Log.w("VomBlatt", "Notizen", it) }.getOrDefault(-1f)
                if (sc > 0 && out.exists()) { notes = out; notesScale = sc }
                lap("Notizen")
                // Kleine gedruckte Ziffern (Fingersätze), die die Texterkennung übersehen hat, gezielt nachlesen
                val digitsFile = File(out.absolutePath.removeSuffix(".png") + ".digits.json")
                if (digitsFile.exists()) runCatching {
                    val arr = org.json.JSONArray(digitsFile.readText())
                    // [x0, y0, x1, y1, ziffer, wert, abstand, an_linie, beste_ziffer, größe_passt]; nur ziffernähnliche Stücke lesen
                    val raw = (0 until arr.length()).map { i -> arr.getJSONArray(i).let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } } }
                        .filter { it.size >= 9 && (it[4] > 0f || it[5] >= 0.75f) }
                    // aus dem Original lesen (größer), Ergebnis zurück auf das Arbeitsfoto umrechnen
                    val big = runCatching { if (original.exists()) Library.decodeUpright(original, 4200) else null }.getOrNull()
                        ?: android.graphics.BitmapFactory.decodeFile(photo.absolutePath)
                    big?.let { b ->
                        val f = b.width.toFloat() / workWidth
                        if (com.nicedevelopments.vomblatt.BuildConfig.DEBUG) TitleReader.debugDir = context.cacheDir
                        val read = TitleReader.readDigits(b, raw.map { v ->
                            TitleReader.DigitCandidate(FloatArray(4) { v[it] * f }, v[4].toInt(), v[8].toInt(), v[5], v[6], v.size < 10 || v[9] > 0f)
                        })
                        lines = lines + read.map { it.copy(l = it.l / f, t = it.t / f, r = it.r / f, b = it.b / f) }
                        android.util.Log.d("VomBlatt", "Fingersätze aus Bild ${b.width} px (Faktor %.2f)".format(f))
                        b.recycle()
                    }
                    digitsFile.delete()
                }.onFailure { android.util.Log.w("VomBlatt", "Fingersätze", it) }
            }
            lap("Fingersätze")
            android.util.Log.d("VomBlatt", "Zeit je Schritt: $times gesamt %.1f s".format((android.os.SystemClock.elapsedRealtime() - tStart) / 1000f))
            android.util.Log.d("VomBlatt", "Modelle: ${OrtBridge.timings()} (Einstellung ${OrtBridge.conf.ifEmpty { mapOf("xnn" to "alle") }})")
            Recognition(xml, head?.title, head?.tempo, sink.json, sink.pedals, sink.hairpins, lines, notes, notesScale)
        } catch (e: PyException) {
            // abgebrochen: keine Fehlermeldung, die Erkennung endet einfach
            if (isCancelled()) throw kotlinx.coroutines.CancellationException("abgebrochen")
            android.util.Log.e("VomBlatt", "homr", e)
            throw RecognitionException(explain(e.message ?: ""), e)
        } finally {
            File(photo.parentFile, photo.name + ".orig").delete()
            if (!OrtBridge.keepOpen) OrtBridge.closeAll()
            System.gc() // Bitmaps und Tensor-Puffer der Seite freigeben, bevor die nächste beginnt
            OrtBridge.cancelled = { false }
        }
    }

    private fun explain(msg: String): String = when {
        "No noteheads or clefs" in msg -> context.getString(com.nicedevelopments.vomblatt.R.string.err_no_notes)
        "staff" in msg.lowercase() -> context.getString(com.nicedevelopments.vomblatt.R.string.err_staff)
        "MemoryError" in msg || "OutOfMemory" in msg -> context.getString(com.nicedevelopments.vomblatt.R.string.err_memory)
        // technische Einzelheiten stehen im Protokoll, nicht in der Meldung
        else -> context.getString(com.nicedevelopments.vomblatt.R.string.err_generic)
    }

    companion object {
        private val busy = kotlinx.coroutines.sync.Mutex()
        @Volatile private var faultInstalled = false

        /**
         * Chaquopys OpenCV/numpy-Bausteine sind nur für 4-KB-Speicherseiten gebaut. Auf Geräten mit
         * 16-KB-Seiten (neuere Emulator-Abbilder, künftige Handys) lassen sie sich nicht laden.
         */
        val pageSizeSupported: Boolean
            get() = runCatching { android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) <= 4096L }.getOrDefault(true)

        fun createOrNull(context: Context): Recognizer? =
            if (pageSizeSupported) HomrRecognizer(context.applicationContext) else null
    }
}
