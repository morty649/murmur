package com.morty649.murmur

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/** Records 16 kHz mono audio into memory. Nothing is written to disk. */
class Recorder(private val onLimitReached: () -> Unit) {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    /** Loudness of the latest ~33 ms, 0..1 (drives the bubble's live bars). */
    @Volatile var level = 0f
        private set

    private var buffer = FloatArray(Transcriber.SAMPLE_RATE * 30)
    private var size = 0
    private val lock = Any()

    @SuppressLint("MissingPermission") // checked by the caller before starting
    fun start(): Boolean {
        val rate = Transcriber.SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, rate / 2),
            )
        } catch (e: Exception) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        try {
            rec.startRecording()
        } catch (e: Exception) {
            rec.release()
            return false
        }
        record = rec
        running = true
        thread = Thread({
            val chunk = ShortArray(rate / 30)
            while (running) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n > 0) {
                    append(chunk, n)
                    level = loudness(chunk, n)
                }
                if (size >= MAX_SECONDS * rate) {
                    running = false
                    onLimitReached()
                }
            }
        }, "murmur-recorder").also { it.start() }
        return true
    }

    private fun append(chunk: ShortArray, n: Int) = synchronized(lock) {
        if (size + n > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + n))
        for (i in 0 until n) buffer[size + i] = chunk[i] / 32768f
        size += n
    }

    /** Same normalisation as Muesli's indicator: clamp((dBFS + 68) / 38, 0, 1). */
    private fun loudness(chunk: ShortArray, n: Int): Float {
        var sum = 0.0
        for (i in 0 until n) {
            val v = chunk[i] / 32768.0
            sum += v * v
        }
        val db = 10 * kotlin.math.log10(sum / n + 1e-12)
        return ((db + 68) / 38).toFloat().coerceIn(0f, 1f)
    }

    fun stop(): FloatArray {
        running = false
        thread?.join(1000)
        thread = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
        return synchronized(lock) { buffer.copyOf(size) }
    }

    companion object {
        const val MAX_SECONDS = 300
    }
}
