package com.nicedevelopments.vomblatt.recognize

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.res.AssetManager
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Gegenstelle für das Python-Modul onnxruntime (src/main/python-android): führt die homr-Modelle
 * mit der ONNX Runtime für Android aus. Pfade mit dem Präfix „asset:“ werden direkt aus der APK
 * eingeblendet (dort unkomprimiert abgelegt) und nicht kopiert.
 */
object OrtBridge {
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    @Volatile lateinit var assets: AssetManager
    private val sessions = HashMap<String, OrtSession>()

    /** Vom Erkenner gesetzt: true, sobald der Nutzer abgebrochen hat – dann bricht jeder weitere Lauf sofort ab. */
    @Volatile var cancelled: () -> Boolean = { false }
    /** XNNPACK (ARM-optimierte Rechenkerne) für die Sitzungen. Abschaltbar zum Vergleich (Testfassung: Datei files/no_xnn). */
    @Volatile var xnnpack: Boolean = true
    /**
     * Einstellungen je Modell zum Messen (Testfassung: Datei files/ort_conf), z. B. „xnn=segnet,decoder;threads=4;intra=4“:
     * xnn = Modelle mit XNNPACK (Teil des Dateinamens; Standard: alle außer dem Encoder – der ist mit XNNPACK langsamer),
     * nnapi = Modelle mit NNAPI, threads = XNNPACK-Threads, intra = ONNX-Runtime-Threads.
     */
    @Volatile var conf: Map<String, String> = emptyMap()
    /** Mehrseitiger Auftrag: Sitzungen bleiben zwischen den Seiten offen (Laden spart Zeit); geschlossen wird am Ende. */
    @Volatile var keepOpen: Boolean = false
    private val runMs = HashMap<String, Long>()
    private val runCount = HashMap<String, Int>()
    private val sessionNames = HashMap<OrtSession, String>()

    class Cancelled : RuntimeException("Erkennung abgebrochen")

    /** Ergebnis eines Laufs; hält die Ausgabetensoren am Leben, bis Python es schließt. */
    class RunResult(private val result: OrtSession.Result, private val tensors: List<OnnxTensor>) : AutoCloseable {
        fun size(): Int = tensors.size
        fun get(i: Int): OnnxTensor = tensors[i]
        override fun close() = result.close()
    }

    @JvmStatic
    @Synchronized
    fun open(path: String): OrtSession = sessions.getOrPut(path) {
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // höchstens 4 Threads (die schnellen Kerne; Sparkerne bremsen) und wartende Threads schlafen statt zu kreisen –
            // weniger Wärme, das Handy drosselt bei langen PDFs später (ORT-Empfehlung für XNNPACK)
            val threads = conf["threads"]?.toIntOrNull() ?: Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            if (conf["spin"] != "1") runCatching {
                addConfigEntry("session.intra_op.allow_spinning", "0"); addConfigEntry("session.inter_op.allow_spinning", "0")
            }
            fun listed(key: String, default: Boolean) = conf[key]?.split(",")?.any { it.isNotBlank() && path.contains(it.trim()) } ?: default
            val useXnn = xnnpack && listed("xnn", !path.contains("encoder"))
            // NNAPI (KI-Beschleuniger des Geräts) nur zum Messen; nicht unterstützte Teile rechnet die CPU
            if (listed("nnapi", false)) runCatching { addNnapi() }.onFailure { android.util.Log.w("VomBlatt", "NNAPI", it) }
            if (useXnn && runCatching { addXnnpack(mapOf("intra_op_num_threads" to threads.toString())) }.isSuccess) {
                // XNNPACK hat einen eigenen Thread-Pool; ONNX Runtime selbst dann einfädig (Empfehlung von ORT)
                setIntraOpNumThreads(conf["intra"]?.toIntOrNull() ?: 1)
            } else setIntraOpNumThreads(conf["intra"]?.toIntOrNull() ?: threads.coerceAtMost(4)) // große Kerne; 6 inkl. Sparkerne war langsamer
        }
        if (path.startsWith("asset:")) {
            val fd = assets.openFd(path.removePrefix("asset:"))
            val buf = FileInputStream(fd.fileDescriptor).channel
                .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            env.createSession(buf, opts)
        } else env.createSession(path, opts)
    }.also { sessionNames[it] = path.substringAfterLast('/').substringBefore('_') }

    /** Messung: Laufzeit je Modell seit dem letzten Aufruf (für das Protokoll der Testfassung). */
    @JvmStatic
    @Synchronized
    fun timings(): String {
        val s = runMs.keys.sorted().joinToString(", ") { "$it %.1f s/%d".format(runMs[it]!! / 1000f, runCount[it] ?: 0) }
        runMs.clear(); runCount.clear()
        return s
    }

    /** Sitzungen schließen, um nach der Erkennung Speicher freizugeben. */
    @JvmStatic
    @Synchronized
    fun closeAll() {
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear(); sessionNames.clear()
    }

    private fun type(dtype: String): OnnxJavaType = when (dtype) {
        "float32" -> OnnxJavaType.FLOAT
        "float16" -> OnnxJavaType.FLOAT16
        "float64" -> OnnxJavaType.DOUBLE
        "int64" -> OnnxJavaType.INT64
        "int32" -> OnnxJavaType.INT32
        "int8" -> OnnxJavaType.INT8
        "uint8" -> OnnxJavaType.UINT8
        "bool" -> OnnxJavaType.BOOL
        else -> throw IllegalArgumentException("Datentyp $dtype")
    }

    @JvmStatic
    fun tensor(data: ByteArray, shape: LongArray, dtype: String): OnnxTensor {
        val buf = ByteBuffer.allocateDirect(data.size).order(ByteOrder.nativeOrder())
        buf.put(data); buf.rewind()
        return OnnxTensor.createTensor(env, buf, shape, type(dtype))
    }

    @JvmStatic
    fun run(s: OrtSession, names: Array<String>, values: Array<OnnxTensor>, outputs: Array<String>?): RunResult {
        if (cancelled()) throw Cancelled()
        val feed = names.indices.associate { names[it] to values[it] }
        val t0 = android.os.SystemClock.elapsedRealtime()
        val res = if (outputs == null) s.run(feed) else s.run(feed, outputs.toSet())
        val n = sessionNames[s] ?: "?"
        synchronized(this) { runMs[n] = (runMs[n] ?: 0L) + android.os.SystemClock.elapsedRealtime() - t0; runCount[n] = (runCount[n] ?: 0) + 1 }
        val order = outputs?.toList() ?: s.outputNames.toList()
        val tensors = order.map { n -> res.get(n).get() as OnnxTensor }
        return RunResult(res, tensors)
    }

    /** Ergebnis von [decode]: je Schritt 6 Token (Rhythmus, Tonhöhe, Vorzeichen, Lage, Artikulation, Bogen) und die
     *  „attention“-Ausgabe (Koordinaten) als zusammenhängende Zahlenfolge. */
    class DecodeResult(@JvmField val tokens: IntArray, @JvmField val attention: FloatArray, @JvmField val attentionShape: LongArray)

    private fun argmaxLast(t: OnnxTensor): Int {
        val fb = t.floatBuffer
        val shape = (t.info as TensorInfo).shape
        val v = shape.last().toInt()
        val off = fb.remaining() - v
        var best = 0; var bestV = fb.get(off)
        for (i in 1 until v) { val x = fb.get(off + i); if (x > bestV) { bestV = x; best = i } }
        return best
    }

    private fun longTensor(v: Long, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(longArrayOf(v)), shape)

    /**
     * homrs Decoder-Schleife (decoder_inference.ScoreDecoder.generate) vollständig hier statt in Python: dieselben
     * Eingaben, gierige Auswahl (erstes Maximum wie numpy.argmax), Abbruch beim EOS-Rhythmus, Cache von Schritt zu
     * Schritt. Python bekommt nur die gewählten Token – spart je Schritt sieben Umwandlungen zwischen Python und Java.
     */
    @JvmStatic
    fun decode(
        s: OrtSession, context: OnnxTensor, contextReduced: OnnxTensor, startToken: Long, nonoteToken: Long,
        cacheCount: Int, heads: Int, headDim: Int, maxSeqLen: Int, eos: Int, stepSession: OrtSession?,
    ): DecodeResult {
        // stepSession (tools/make_decoder_step.py --bias): ab Schritt 1 feste Cross-Caches aus Schritt 0 statt sie je
        // Schritt um Kontext-Token 0 zu verlängern; dessen Vervielfachung als +log(s+1) („cross_bias“) – gleichwertig
        val crossIdx = (0 until cacheCount).filter { it % 4 == 2 || it % 4 == 3 }.toSet()
        val stepWanted = stepSession?.let { ss -> ss.outputNames.toSet() }
        var first: OrtSession.Result? = null
        var fixedCross: Map<Int, OnnxTensor> = emptyMap()
        val outNames = listOf("out_rhythms", "out_pitchs", "out_lifts", "out_positions", "out_articulations", "out_slurs", "attention")
        val cacheOut = (0 until cacheCount).map { "cache_out$it" }
        val wanted = (outNames + cacheOut).toSet()
        var cache: List<OnnxTensor> = (0 until cacheCount).map {
            OnnxTensor.createTensor(env, java.nio.FloatBuffer.allocate(0), longArrayOf(1, heads.toLong(), 0, headDim.toLong()))
        }
        val initial = cache
        var prev: OrtSession.Result? = null
        val tokens = ArrayList<Int>(); val att = ArrayList<Float>()
        var attShape = LongArray(0)
        var last = longArrayOf(startToken, nonoteToken, nonoteToken, nonoteToken, nonoteToken) // Rhythmus, Tonhöhe, Vorzeichen, Artikulation, Bogen
        val one = longArrayOf(1, 1)
        try {
            for (step in 0 until maxSeqLen) {
                if (cancelled()) throw Cancelled()
                val own = listOf(
                    longTensor(last[0], one), longTensor(last[1], one), longTensor(last[2], one),
                    longTensor(last[3], one), longTensor(last[4], one), longTensor(step.toLong(), longArrayOf(1)),
                )
                val feed = HashMap<String, OnnxTensor>()
                feed["rhythms"] = own[0]; feed["pitchs"] = own[1]; feed["lifts"] = own[2]
                feed["articulations"] = own[3]; feed["slurs"] = own[4]; feed["cache_len"] = own[5]
                val useStep = stepSession != null && step > 0
                var bias: OnnxTensor? = null
                if (useStep) {
                    cache.forEachIndexed { i, t -> feed["cache_in$i"] = fixedCross[i] ?: t }
                    val len = fixedCross[2]!!.info.let { (it as TensorInfo).shape[2] }.toInt()
                    val b = FloatArray(len); b[0] = kotlin.math.ln(step + 1.0).toFloat()
                    bias = OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(b), longArrayOf(1, 1, 1, len.toLong()))
                    feed["cross_bias"] = bias
                } else {
                    feed["context"] = if (step == 0) context else contextReduced
                    cache.forEachIndexed { i, t -> feed["cache_in$i"] = t }
                }
                val t0 = android.os.SystemClock.elapsedRealtime()
                val res = if (useStep) stepSession!!.run(feed, stepWanted!!) else s.run(feed, wanted)
                bias?.close()
                val n = sessionNames[s] ?: "?"
                synchronized(this) { runMs[n] = (runMs[n] ?: 0L) + android.os.SystemClock.elapsedRealtime() - t0; runCount[n] = (runCount[n] ?: 0) + 1 }
                own.forEach { it.close() }
                // Cache des vorletzten Laufs wird nicht mehr gebraucht (Schritt 0 bleibt offen: feste Cross-Caches)
                if (prev != null && prev !== first) prev!!.close()
                prev = res
                if (step == 0 && stepSession != null) {
                    first = res
                    fixedCross = crossIdx.associateWith { res.get("cache_out$it").get() as OnnxTensor }
                }
                val outs = outNames.map { res.get(it).get() as OnnxTensor }
                val r = argmaxLast(outs[0]); val p = argmaxLast(outs[1]); val l = argmaxLast(outs[2])
                val pos = argmaxLast(outs[3]); val a = argmaxLast(outs[4]); val sl = argmaxLast(outs[5])
                cache = cacheOut.mapIndexed { i, nm ->
                    if (useStep && i in crossIdx) fixedCross[i]!! else res.get(nm).get() as OnnxTensor
                }
                if (r == eos) break
                tokens.add(r); tokens.add(p); tokens.add(l); tokens.add(pos); tokens.add(a); tokens.add(sl)
                val at = outs[6]
                attShape = (at.info as TensorInfo).shape
                val fb = at.floatBuffer
                while (fb.hasRemaining()) att.add(fb.get())
                last = longArrayOf(r.toLong(), p.toLong(), l.toLong(), a.toLong(), sl.toLong())
            }
        } finally {
            if (prev !== first) prev?.close()
            first?.close()
            initial.forEach { runCatching { it.close() } }
        }
        return DecodeResult(tokens.toIntArray(), att.toFloatArray(), attShape)
    }

    @JvmStatic
    fun bytes(t: OnnxTensor): ByteArray {
        val b = t.byteBuffer
        val out = ByteArray(b.remaining())
        b.get(out)
        return out
    }

    @JvmStatic
    fun shape(t: OnnxTensor): LongArray = (t.info as TensorInfo).shape

    @JvmStatic
    fun dtype(t: OnnxTensor): String = when ((t.info as TensorInfo).type) {
        OnnxJavaType.FLOAT16 -> "float16"
        OnnxJavaType.DOUBLE -> "float64"
        OnnxJavaType.INT64 -> "int64"
        OnnxJavaType.INT32 -> "int32"
        OnnxJavaType.INT8 -> "int8"
        OnnxJavaType.UINT8 -> "uint8"
        OnnxJavaType.BOOL -> "bool"
        else -> "float32"
    }

    @JvmStatic
    fun close(t: OnnxTensor) = t.close()
}
