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
            val chunk = ShortArray(rate / 10)
            while (running) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n > 0) append(chunk, n)
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
