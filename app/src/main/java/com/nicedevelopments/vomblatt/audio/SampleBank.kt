package com.nicedevelopments.vomblatt.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Salamander Grand Piano V3 (Alexander Holm, CC-BY 3.0): im Kleinterz-Abstand gesampelt,
 * drei Anschlagstufen. Dekodiert wird erst bei Bedarf und dann im Speicher gehalten.
 */
class SampleBank(private val context: Context) {

    class Sample(val data: ShortArray, val frames: Int, val rootMidi: Int)

    enum class Layer(val tag: String) { P("p"), M("m"), F("f") }

    private val cache = ConcurrentHashMap<String, Sample>()

    /** Sample-Grundton, der einem MIDI-Ton am nächsten liegt (A0 = 21, dann alle 3 Halbtöne). */
    fun rootFor(midi: Int): Int {
        val m = midi.coerceIn(21, 108)
        val k = Math.round((m - 21) / 3f)
        return (21 + k * 3).coerceAtMost(108)
    }

    fun get(root: Int, layer: Layer): Sample? = cache["${root}_${layer.tag}"]

    /** Lädt alle Samples, die für die gegebenen Töne gebraucht werden (blockierend, im Hintergrund aufrufen). */
    fun preload(midis: Collection<Int>) {
        val jobs = midis.map { rootFor(it) }.toSet().flatMap { r -> Layer.entries.map { l -> r to l } }
            .filter { (r, l) -> cache["${r}_${l.tag}"] == null }
            .map { (r, l) -> pool.submit<Sample?> { load(r, l) } }
        jobs.forEach { runCatching { it.get() } }
    }

    /** Im Hintergrund nachladen, ohne zu warten (z. B. aus dem Audio-Thread). */
    fun requestAsync(root: Int, layer: Layer) {
        val key = "${root}_${layer.tag}"
        if (cache[key] == null && pending.add(key)) pool.execute { load(root, layer); pending.remove(key) }
    }

    private val pending = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val pool = java.util.concurrent.Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
    ) { r -> Thread(r, "sample-decode").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }

    fun load(root: Int, layer: Layer): Sample? {
        val key = "${root}_${layer.tag}"
        cache[key]?.let { return it }
        val t0 = System.nanoTime()
        val s = runCatching { decode("piano/$key.ogg", root) }
            .onFailure { android.util.Log.e("VomBlatt", "Sample $key", it) }.getOrNull() ?: return null
        android.util.Log.d("VomBlatt", "Sample $key ${s.frames} Frames in ${(System.nanoTime() - t0) / 1_000_000} ms")
        cache[key] = s
        return s
    }

    private fun decode(asset: String, root: Int): Sample {
        val afd = context.assets.openFd(asset)
        val ex = MediaExtractor()
        ex.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        afd.close()
        ex.selectTrack(0)
        val fmt = ex.getTrackFormat(0)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        var out = ShortArray(44100 * 2 * 8)
        var n = 0
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val ii = codec.dequeueInputBuffer(2000)
                if (ii >= 0) {
                    val buf = codec.getInputBuffer(ii)!!
                    val size = ex.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(ii, 0, size, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }
            val oi = codec.dequeueOutputBuffer(info, if (inputDone) 2000 else 0)
            if (oi >= 0) {
                val buf = codec.getOutputBuffer(oi)!!
                buf.position(info.offset); buf.limit(info.offset + info.size)
                val sb = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                val count = sb.remaining()
                if (channels == 1) {
                    while (n + count * 2 > out.size) out = out.copyOf(out.size * 2)
                    for (i in 0 until count) { val v = sb.get(); out[n++] = v; out[n++] = v }
                } else {
                    while (n + count > out.size) out = out.copyOf(out.size * 2)
                    sb.get(out, n, count); n += count
                }
                codec.releaseOutputBuffer(oi, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }
        }
        codec.stop(); codec.release(); ex.release()
        return Sample(out.copyOf(n), n / 2, root)
    }
}
