package com.nicedevelopments.vomblatt.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.nicedevelopments.vomblatt.model.Performance
import com.nicedevelopments.vomblatt.model.TPQ
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Eigener Sampler mit Sequenzer im Audio-Thread: Töne starten blockgenau (1,5 ms),
 * Anschlagstufen werden überblendet, losgelassene Tasten klingen gedämpft aus, dazu leiser Raumhall.
 */
class PianoEngine(context: Context) {

    val bank = SampleBank(context.applicationContext)

    private val sr = 44100
    private val chunk = 64
    private val maxVoices = 64

    private val track: AudioTrack
    private val latencyFrames: Int

    // --- Zustand, nur im Audio-Thread verändert ---
    private val voices = Array(maxVoices) { Voice() }
    private var perf: Performance? = null
    private var tick = 0.0
    private var nextIdx = 0
    private var nextPedal = 0
    private var pedalDown = false
    private var nextBeat = 0.0
    private var beatCount = 0
    private val reverb = Reverb()
    private var silentChunks = 0

    // --- Steuerung von außen ---
    private val commands = ConcurrentLinkedQueue<() -> Unit>()
    private val lock = ReentrantLock()
    private val wake = lock.newCondition()

    @Volatile var bpm: Double = 100.0
    @Volatile var playRight = true
    @Volatile var playLeft = true
    @Volatile var metronome = false
    @Volatile var loop: IntRange? = null
    @Volatile private var playingFlag = false

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing

    /** Aktuelle Hörposition in Ticks (um die Ausgabelatenz zurückgerechnet). */
    @Volatile var positionTick: Double = 0.0
        private set

    private val clickHi = click(2300.0)
    private val clickLo = click(1600.0)

    init {
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sr)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, chunk * 8 * 8))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        latencyFrames = track.bufferSizeInFrames
        Thread(::run, "piano-audio").apply { priority = Thread.MAX_PRIORITY; isDaemon = true; start() }
    }

    // ------------------------------------------------------------------ API

    fun setPerformance(p: Performance) = post {
        stopAll(fast = true)
        perf = p
        seekInternal(0.0)
        playingFlag = false; _playing.value = false
    }

    fun play() = post {
        val p = perf ?: return@post
        if (tick >= p.totalTicks - 1) seekInternal(loop?.first?.toDouble() ?: 0.0)
        playingFlag = true; _playing.value = true
    }

    fun pause() = post {
        // an der zuletzt gehörten Stelle stehen bleiben (nicht an der schon erzeugten, noch nicht hörbaren) – sonst
        // springt der Balken beim Anhalten vor, und beim Weiterspielen fehlt ein Stück
        val heard = positionTick
        playingFlag = false
        stopAll(fast = false)
        if (heard in 0.0..tick) seekInternal(heard)
        _playing.value = false // erst jetzt: die Anzeige liest danach schon die gehörte Stelle
    }

    fun seek(t: Int) = post { stopAll(fast = true); seekInternal(t.toDouble()) }

    /** Einzelnen Ton sofort anspielen, z. B. beim Antippen einer Note. */
    fun preview(midi: Int, velocity: Int = 80, seconds: Double = 0.9) = post {
        startNote(midi, velocity, endTick = Double.NaN, staff = -1, releaseAfterFrames = (seconds * sr).toInt())
    }

    private fun post(cmd: () -> Unit) {
        commands.add(cmd)
        lock.withLock { wake.signal() }
    }

    // ------------------------------------------------------------------ Audio-Thread

    private fun run() {
        val out = FloatArray(chunk * 2)
        var started = false
        while (true) {
            while (true) { val c = commands.poll() ?: break; c() }

            val idle = !playingFlag && voices.none { it.active } && silentChunks > sr / chunk * 3
            if (idle) {
                // nach dem Anhalten: alten Zeitstempel verwerfen – sonst rechnet der Neustart kurz mit falscher Hörposition
                if (started) { track.pause(); track.flush(); started = false; framesWritten = 0L; histN = 0; tsFrame = -1L; tsCount = 0 }
                lock.withLock { if (commands.isEmpty()) wake.await() }
                silentChunks = 0
                continue
            }
            if (!started) { track.play(); started = true }

            val chunkTick = tick
            val chunkTpf = ticksPerFrame()
            sequence()
            render(out)
            // merken, welcher Takt-Tick zu welchem ausgegebenen Sample gehört
            val slot = histN % HIST
            histFrame[slot] = framesWritten; histTick[slot] = chunkTick; histTpf[slot] = chunkTpf
            histPlaying[slot] = playingFlag
            histN++
            track.write(out, 0, out.size, AudioTrack.WRITE_BLOCKING)
            framesWritten += chunk
            updatePosition()
        }
    }

    // Zuordnung ausgegebener Samples → Ticks (Ringpuffer), für die genaue Hörposition
    private val HIST = 2048
    private val histFrame = LongArray(HIST)
    private val histTick = DoubleArray(HIST)
    private val histTpf = DoubleArray(HIST)
    private val histPlaying = BooleanArray(HIST)
    private var histN = 0
    private var framesWritten = 0L
    private val ts = android.media.AudioTimestamp()
    private var tsFrame = -1L
    private var tsNano = 0L
    private var tsCount = 0

    /**
     * Hörposition: das Sample, das gerade wirklich aus dem Lautsprecher kommt (Zeitstempel des Audiosystems,
     * schließt Mixer- und Treiberverzögerung ein), zurück in Ticks übersetzt.
     */
    private fun updatePosition() {
        if (tsCount++ % 16 == 0 && track.getTimestamp(ts)) { tsFrame = ts.framePosition; tsNano = ts.nanoTime }
        val presented = if (tsFrame >= 0) tsFrame + ((System.nanoTime() - tsNano) * sr / 1e9).toLong()
        else framesWritten - latencyFrames
        if (!playingFlag) { positionTick = tick; return }
        if (com.nicedevelopments.vomblatt.BuildConfig.DEBUG && tsCount % 700 == 1) android.util.Log.d("VomBlatt",
            "Hörposition: erzeugt %.1f, hörbar %.1f Ticks, Verzug %.0f ms (Zeitstempel %s)".format(
                tick, positionTick, (tick - positionTick) / ticksPerFrame() / sr * 1000, if (tsFrame >= 0) "ja" else "nein"))
        // passenden Eintrag suchen (rückwärts, die jüngsten zuerst)
        val n = minOf(histN, HIST)
        for (k in 1..n) {
            val i = (histN - k) % HIST
            if (histFrame[i] <= presented) {
                if (!histPlaying[i]) { positionTick = histTick[i]; return }
                positionTick = (histTick[i] + (presented - histFrame[i]) * histTpf[i]).coerceAtMost(tick)
                return
            }
        }
        positionTick = histTick[(histN - n) % HIST].coerceAtLeast(0.0)
    }

    private fun ticksPerFrame() = bpm * TPQ / 60.0 / sr

    private fun sequence() {
        val p = perf ?: return
        val tpf = ticksPerFrame()
        if (playingFlag) {
            val end = tick + chunk * tpf
            val notes = p.notes
            while (nextPedal < p.pedals.size && p.pedals[nextPedal].tick < end) {
                when (p.pedals[nextPedal++].type) {
                    com.nicedevelopments.vomblatt.model.PedalType.DOWN -> pedalDown = true
                    com.nicedevelopments.vomblatt.model.PedalType.CHANGE -> { releaseSustained(); pedalDown = true }
                    com.nicedevelopments.vomblatt.model.PedalType.UP -> { pedalDown = false; releaseSustained() }
                }
            }
            while (nextIdx < notes.size && notes[nextIdx].tick < end) {
                val n = notes[nextIdx++]
                if ((n.staff == 0 && !playRight) || (n.staff == 1 && !playLeft)) continue
                startNote(n.midi, n.velocity, (n.tick + n.length).toDouble(), n.staff, 0)
            }
            if (metronome) {
                while (nextBeat < end) {
                    val pm = p.playedAt(nextBeat.toInt())
                    val beatTicks = pm?.beatTicks ?: TPQ
                    val first = pm != null && nextBeat.toInt() == pm.tick
                    startClick(if (first) clickHi else clickLo, if (first) 0.55f else 0.38f)
                    nextBeat = nextBeatAfter(p, nextBeat + 0.5)
                }
            } else nextBeat = nextBeatAfter(p, end)
            for (v in voices) if (v.active && !v.releasing && !v.endTick.isNaN() && v.endTick < end) {
                // Taste losgelassen: mit getretenem Pedal klingt der Ton weiter, bis das Pedal hochgeht
                if (pedalDown && v.staff >= 0) { v.sustained = true; v.endTick = Double.NaN }
                else v.release(damperTau(v.midi))
            }
            tick = end
            val lp = loop
            if (lp != null && tick >= lp.last) {
                stopAll(fast = false)
                seekInternal(lp.first.toDouble())
            } else if (tick >= p.totalTicks) {
                playingFlag = false; _playing.value = false
                tick = p.totalTicks.toDouble()
            }
        }
    }

    private fun seekInternal(t: Double) {
        val p = perf ?: return
        tick = t.coerceIn(0.0, p.totalTicks.toDouble())
        var lo = 0; var hi = p.notes.size
        while (lo < hi) { val mid = (lo + hi) / 2; if (p.notes[mid].tick < tick) lo = mid + 1 else hi = mid }
        nextIdx = lo
        var pl = 0
        while (pl < p.pedals.size && p.pedals[pl].tick < tick) pl++
        nextPedal = pl
        pedalDown = pl > 0 && p.pedals[pl - 1].type != com.nicedevelopments.vomblatt.model.PedalType.UP
        nextBeat = nextBeatAfter(p, tick)
        positionTick = tick
    }

    /** Nächster Schlag ab t (inklusive), passend zur Taktart des jeweiligen Takts. */
    private fun nextBeatAfter(p: Performance, t: Double): Double {
        val pm = p.playedAt(t.toInt()) ?: return t
        // In notierter Zeit rechnen, damit Schläge bei Fermaten mit warten
        val rel = pm.toNotated(kotlin.math.ceil(t - pm.tick).toInt())
        val k = (rel + pm.beatTicks - 1) / pm.beatTicks
        var notated = k * pm.beatTicks
        // In gedehnten Abschnitten können mehrere gespielte Ticks auf denselben notierten fallen
        while (notated < pm.notatedLength && pm.tick + pm.toPlayed(notated) < t) notated += pm.beatTicks
        return if (notated >= pm.notatedLength) (pm.tick + pm.length).toDouble() else (pm.tick + pm.toPlayed(notated)).toDouble()
    }

    private fun releaseSustained() {
        for (v in voices) if (v.active && v.sustained && !v.releasing) v.release(damperTau(v.midi))
    }

    private fun stopAll(fast: Boolean) {
        for (v in voices) if (v.active) v.release(if (fast) 0.03 else 0.12)
    }

    private fun damperTau(midi: Int): Double = when {
        midi >= 89 -> 0.9      // oberste Saiten haben keinen Dämpfer
        midi >= 72 -> 0.11
        else -> 0.16
    }

    private fun startNote(midi: Int, velocity: Int, endTick: Double, staff: Int, releaseAfterFrames: Int) {
        val root = bank.rootFor(midi)
        // Anschlagstufen überblenden: leise ↔ mittel ↔ kräftig
        val v = velocity.coerceIn(1, 127)
        val (la, lb, mix) = when {
            v <= 45 -> Triple(SampleBank.Layer.P, SampleBank.Layer.P, 0f)
            v <= 80 -> Triple(SampleBank.Layer.P, SampleBank.Layer.M, (v - 45) / 35f)
            v <= 112 -> Triple(SampleBank.Layer.M, SampleBank.Layer.F, (v - 80) / 32f)
            else -> Triple(SampleBank.Layer.F, SampleBank.Layer.F, 0f)
        }
        // Nie im Audio-Thread dekodieren: fehlt ein Sample noch, wird es nachgeladen und der Ton ausgelassen.
        val a = bank.get(root, la) ?: run { bank.requestAsync(root, la); return }
        val b = if (lb == la) null else (bank.get(root, lb) ?: run { bank.requestAsync(root, lb); null })
        // Gleicher Ton erneut angeschlagen: alte Stimme schnell abdämpfen
        for (o in voices) if (o.active && o.midi == midi && !o.releasing) o.release(0.05)
        val voice = voices.firstOrNull { !it.active } ?: voices.minByOrNull { it.env * (if (it.releasing) 0.1f else 1f) }!!
        val fine = (v / 127.0).pow(0.6) * 0.55 + 0.45
        voice.start(
            a, b, if (b == null) 0f else mix,
            step = 2.0.pow((midi - root) / 12.0),
            gain = (fine * 0.42).toFloat(),
            midi = midi, endTick = endTick, staff = staff, autoRelease = releaseAfterFrames,
            releaseTau = damperTau(midi),
        )
    }

    private fun startClick(s: SampleBank.Sample, gain: Float) {
        val voice = voices.firstOrNull { !it.active } ?: return
        voice.start(s, null, 0f, 1.0, gain, -1, Double.NaN, -1, 0, 0.05)
    }

    private fun render(out: FloatArray) {
        java.util.Arrays.fill(out, 0f)
        for (v in voices) if (v.active) v.mix(out, chunk)
        reverb.process(out, chunk)
        var peak = 0f
        for (i in out.indices) {
            val x = out[i]
            // Weiche Begrenzung statt hartem Clipping
            val y = if (abs(x) < 0.7f) x else (if (x > 0) 1f else -1f) * (0.7f + 0.3f * kotlin.math.tanh((abs(x) - 0.7f) / 0.3f))
            out[i] = y
            if (abs(y) > peak) peak = abs(y)
        }
        silentChunks = if (peak < 1e-4f) silentChunks + 1 else 0
    }

    private fun click(freq: Double): SampleBank.Sample {
        val n = (sr * 0.035).toInt()
        val d = ShortArray(n * 2)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val s = (sin(2 * PI * freq * t) * exp(-t * 140) * 0.9 * Short.MAX_VALUE).toInt().toShort()
            d[2 * i] = s; d[2 * i + 1] = s
        }
        return SampleBank.Sample(d, n, 0)
    }

    // ------------------------------------------------------------------ Stimme

    private inner class Voice {
        var active = false
        var a: SampleBank.Sample? = null
        var b: SampleBank.Sample? = null
        var mixB = 0f
        var pos = 0.0
        var step = 1.0
        var gain = 0f
        var env = 1f
        var releasing = false
        var releaseMul = 1f
        var releaseTau = 0.15
        var midi = 0
        var staff = 0
        var endTick = Double.NaN
        var sustained = false
        var autoRelease = 0
        var age = 0

        fun start(
            a: SampleBank.Sample, b: SampleBank.Sample?, mix: Float, step: Double, gain: Float,
            midi: Int, endTick: Double, staff: Int, autoRelease: Int, releaseTau: Double,
        ) {
            this.a = a; this.b = b; this.mixB = mix; this.step = step; this.gain = gain
            this.midi = midi; this.endTick = endTick; this.staff = staff; this.autoRelease = autoRelease
            this.releaseTau = releaseTau
            pos = 0.0; env = 1f; releasing = false; releaseMul = 1f; age = 0; active = true; sustained = false
        }

        fun release(tau: Double) {
            if (!active) return
            releasing = true
            val m = exp(-1.0 / (tau * sr)).toFloat()
            releaseMul = if (releaseMul == 1f) m else minOf(releaseMul, m)
        }

        fun mix(out: FloatArray, frames: Int) {
            val sa = a ?: run { active = false; return }
            val sb = b
            val da = sa.data
            val db = sb?.data
            val wa = (1f - mixB) * gain
            val wb = mixB * gain
            val lastA = sa.frames - 1
            val lastB = (sb?.frames ?: Int.MAX_VALUE) - 1
            for (f in 0 until frames) {
                val i = pos.toInt()
                if (i >= lastA || (db != null && i >= lastB)) { active = false; return }
                val fr = (pos - i).toFloat()
                val j = i * 2
                var l = (da[j] + (da[j + 2] - da[j]) * fr) * wa
                var r = (da[j + 1] + (da[j + 3] - da[j + 1]) * fr) * wa
                if (db != null && wb > 0f) {
                    l += (db[j] + (db[j + 2] - db[j]) * fr) * wb
                    r += (db[j + 1] + (db[j + 3] - db[j + 1]) * fr) * wb
                }
                val e = env * (1f / 32768f)
                out[2 * f] += l * e
                out[2 * f + 1] += r * e
                pos += step
                if (releasing) {
                    env *= releaseMul
                    if (env < 1e-4f) { active = false; return }
                }
            }
            age += frames
            if (autoRelease > 0 && age >= autoRelease && !releasing) release(releaseTau)
        }
    }

    // ------------------------------------------------------------------ Hall (Freeverb, sparsam eingesetzt)

    private class Reverb {
        private val combL = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617).map { Comb(it) }
        private val combR = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617).map { Comb(it + 23) }
        private val apL = intArrayOf(556, 441, 341, 225).map { AllPass(it) }
        private val apR = intArrayOf(556, 441, 341, 225).map { AllPass(it + 23) }
        private val wet = 0.22f
        private val dry = 1f

        fun process(buf: FloatArray, frames: Int) {
            for (f in 0 until frames) {
                val l = buf[2 * f]; val r = buf[2 * f + 1]
                val input = (l + r) * 0.015f
                var ol = 0f; var or = 0f
                for (c in combL) ol += c.process(input)
                for (c in combR) or += c.process(input)
                for (a in apL) ol = a.process(ol)
                for (a in apR) or = a.process(or)
                buf[2 * f] = l * dry + ol * wet
                buf[2 * f + 1] = r * dry + or * wet
            }
        }

        private class Comb(size: Int) {
            private val b = FloatArray(size); private var i = 0; private var store = 0f
            private val feedback = 0.84f; private val damp = 0.25f
            fun process(x: Float): Float {
                val o = b[i]
                store = o * (1 - damp) + store * damp
                b[i] = x + store * feedback
                if (++i >= b.size) i = 0
                return o
            }
        }

        private class AllPass(size: Int) {
            private val b = FloatArray(size); private var i = 0
            fun process(x: Float): Float {
                val bo = b[i]
                val o = -x + bo
                b[i] = x + bo * 0.5f
                if (++i >= b.size) i = 0
                return o
            }
        }
    }
}
