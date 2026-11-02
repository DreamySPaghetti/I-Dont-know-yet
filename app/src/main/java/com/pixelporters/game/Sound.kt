package com.pixelporters.game

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.SoundPool
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

private const val RATE = 22050

/** One synthesized note: frequency, length, waveform (0 sine, 1 triangle, 2 square), volume, pitch slide factor, start delay. */
private class Note(val f: Double, val dur: Double, val type: Int = 0, val vol: Double = .2, val slide: Double = 1.0, val delay: Double = 0.0)

private fun wave(type: Int, ph: Double): Double {
    val p = ph - Math.floor(ph)
    return when (type) {
        1 -> 4 * abs(p - .5) - 1
        2 -> if (p < .5) .6 else -.6
        else -> sin(2 * PI * p)
    }
}

private fun synth(notes: List<Note>): ShortArray {
    val len = notes.maxOf { it.delay + it.dur + .03 }
    val out = DoubleArray((len * RATE).toInt())
    for (n in notes) {
        val start = (n.delay * RATE).toInt(); val count = (n.dur * RATE).toInt()
        var ph = 0.0
        for (i in 0 until count) {
            val t = i.toDouble() / RATE
            val f = if (n.slide != 1.0) n.f * Math.pow(n.slide, t / n.dur) else n.f
            ph += f / RATE
            val env = if (t < .008) t / .008 else exp(ln(.0001) * (t - .008) / (n.dur - .008).coerceAtLeast(.001))
            val k = start + i
            if (k < out.size) out[k] += wave(n.type, ph) * n.vol * env
        }
    }
    return ShortArray(out.size) { (out[it].coerceIn(-1.0, 1.0) * 32000).toInt().toShort() }
}

private fun writeWav(file: File, pcm: ShortArray) {
    val data = pcm.size * 2
    val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
    b.put("RIFF".toByteArray()); b.putInt(36 + data); b.put("WAVE".toByteArray())
    b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(RATE); b.putInt(RATE * 2); b.putShort(2); b.putShort(16)
    b.put("data".toByteArray()); b.putInt(data)
    for (s in pcm) b.putShort(s)
    FileOutputStream(file).use { it.write(b.array()) }
}

class Sound(ctx: Context) {
    var soundOn = true
    var musicOn = true
        set(v) { field = v; if (v) startMusic() else stopMusic() }

    private val pool = SoundPool.Builder().setMaxStreams(10)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
    private val ids = HashMap<String, Int>()
    private var lastPop = 0L
    private var lastTick = 0L
    @Volatile private var musicRunning = false
    private var musicThread: Thread? = null

    init {
        val defs = mapOf(
            "click" to listOf(Note(660.0, .07, 1, .18, 1.3)),
            "land" to listOf(Note(520.0, .09, 0, .14, .8)),
            "shake" to listOf(Note(180.0, .16, 2, .06, .8)),
            "pop" to listOf(Note(1100.0, .06, 0, .09, .5)),
            "tick" to listOf(Note(1200.0, .03, 1, .05)),
            "done" to listOf(Note(784.0, .12, 0, .14), Note(1046.0, .16, 0, .12, 1.0, .08)),
            "jam" to listOf(Note(300.0, .3, 1, .22, .6), Note(220.0, .4, 1, .18, .6, .2)),
            "win" to listOf(523.0, 659.0, 784.0, 1046.0, 1318.0).mapIndexed { i, f -> Note(f, .3, 1, .16, 1.0, i * .09) }
        )
        for ((name, notes) in defs) {
            val f = File(ctx.cacheDir, "snd_$name.wav")
            writeWav(f, synth(notes))
            ids[name] = pool.load(f.absolutePath, 1)
        }
    }

    private fun play(name: String, rate: Float = 1f) {
        if (!soundOn) return
        ids[name]?.let { pool.play(it, 1f, 1f, 1, 0, rate) }
    }

    fun click() = play("click")
    fun land() = play("land")
    fun shake() = play("shake")
    fun done() = play("done")
    fun jam() = play("jam")
    fun win() = play("win")
    fun pop() { val n = System.currentTimeMillis(); if (n - lastPop < 45) return; lastPop = n; play("pop", (.8 + Math.random() * .5).toFloat()) }
    fun tick() { val n = System.currentTimeMillis(); if (n - lastTick < 70) return; lastTick = n; play("tick") }

    /** Calm generative music: slow sine-pad chords with occasional pentatonic plucks. */
    fun startMusic() {
        if (!musicOn || musicRunning) return
        musicRunning = true
        musicThread = Thread {
            val buf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(maxOf(buf, RATE)).setTransferMode(AudioTrack.MODE_STREAM).build()
            } catch (e: Exception) { musicRunning = false; return@Thread }
            track.play()
            val chords = listOf(doubleArrayOf(261.6, 329.6, 392.0, 493.9), doubleArrayOf(220.0, 261.6, 329.6, 392.0), doubleArrayOf(174.6, 220.0, 261.6, 329.6), doubleArrayOf(196.0, 246.9, 293.7, 392.0))
            val penta = doubleArrayOf(523.3, 587.3, 659.3, 784.0, 880.0, 1046.5)
            class Voice(val f: Double, val start: Long, val pad: Boolean, var ph: Double = 0.0)
            val voices = ArrayList<Voice>()
            val step = RATE / 2
            val chunk = ShortArray(step)
            var pos = 0L
            var n = 0
            while (musicRunning) {
                if (n % 8 == 0) for (f in chords[(n / 8) % chords.size]) voices.add(Voice(f / 2, pos, true))
                if (Math.random() < .45) voices.add(Voice(penta[(Math.random() * penta.size).toInt()], pos, false))
                for (i in 0 until step) {
                    val s = pos + i
                    var sum = 0.0
                    for (v in voices) {
                        val t = (s - v.start).toDouble() / RATE
                        v.ph += v.f / RATE
                        val env = if (v.pad) { if (t < 1.2) t / 1.2 * .25 else (.25 * (1 - (t - 1.2) / 2.8)).coerceAtLeast(0.0) }
                        else { if (t < .02) t / .02 * .18 else .18 * exp(ln(.0001) * (t - .02) / 1.18) }
                        sum += (if (v.pad) sin(2 * PI * v.ph) else wave(1, v.ph)) * env
                    }
                    chunk[i] = (sum * .22 * 32000).toInt().coerceIn(-32000, 32000).toShort()
                }
                pos += step; n++
                voices.removeAll { (pos - it.start).toDouble() / RATE > (if (it.pad) 4.0 else 1.3) }
                track.write(chunk, 0, chunk.size)
            }
            try { track.stop(); track.release() } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
    }

    fun stopMusic() { musicRunning = false; musicThread = null }
}
