package com.morty649.murmur

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File
import kotlin.math.sqrt

/** Holds one loaded sherpa-onnx recognizer and runs fully on-device transcription. */
object Transcriber {
    const val SAMPLE_RATE = 16000

    private var recognizer: OfflineRecognizer? = null
    private var loadedId: String? = null

    @Synchronized
    fun load(ctx: Context, model: AsrModel) {
        if (recognizer != null && loadedId == model.id) return
        release()
        val manager = ModelManager(ctx)
        check(manager.isReady(model)) { "${model.title} isn't downloaded yet" }
        val d = manager.dir(model)
        fun p(name: String) = File(d, name).absolutePath
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = p("encoder.int8.onnx"),
                    decoder = p("decoder.int8.onnx"),
                    joiner = p("joiner.int8.onnx"),
                ),
                tokens = p("tokens.txt"),
                modelType = "nemo_transducer",
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
            ),
            decodingMethod = "greedy_search",
        )
        recognizer = OfflineRecognizer(config = config)
        loadedId = model.id
    }

    @Synchronized
    fun release() {
        recognizer?.release()
        recognizer = null
        loadedId = null
    }

    @Synchronized
    fun transcribe(samples: FloatArray): String {
        val r = checkNotNull(recognizer) { "Model not loaded" }
        return splitAtPauses(samples).joinToString(" ") { chunk ->
            val stream = r.createStream()
            try {
                stream.acceptWaveform(chunk, SAMPLE_RATE)
                r.decode(stream)
                r.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
        }.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Long recordings are cut into <=25 s pieces at the quietest moment near each boundary,
     * which keeps memory flat on phones and avoids cutting through words.
     */
    private fun splitAtPauses(x: FloatArray): List<FloatArray> {
        val maxLen = 25 * SAMPLE_RATE
        if (x.size <= maxLen) return listOf(x)
        val frame = SAMPLE_RATE / 10 // 100 ms
        val search = 4 * SAMPLE_RATE
        val out = mutableListOf<FloatArray>()
        var start = 0
        while (x.size - start > maxLen) {
            val end = start + maxLen
            var bestCut = end
            var bestEnergy = Double.MAX_VALUE
            var f = end - search
            while (f + frame <= end) {
                var e = 0.0
                for (i in f until f + frame) e += x[i] * x[i]
                val rms = sqrt(e / frame)
                if (rms < bestEnergy) {
                    bestEnergy = rms
                    bestCut = f + frame / 2
                }
                f += frame / 2
            }
            out += x.copyOfRange(start, bestCut)
            start = bestCut
        }
        out += x.copyOfRange(start, x.size)
        return out
    }
}
