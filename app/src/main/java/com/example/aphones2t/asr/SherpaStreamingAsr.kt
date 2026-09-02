package com.example.aphones2t.asr

import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * Streaming ASR engine built on sherpa-onnx [OnlineRecognizer].
 *
 * Supports the two streaming model families shipped by k2-fsa:
 *  - Paraformer   (encoder.int8.onnx + decoder.int8.onnx + tokens.txt)
 *  - Transducer   (encoder + decoder + joiner + tokens.txt, e.g. zipformer)
 *
 * File layout is auto-detected from the model directory so the same code path
 * works for the bundled bilingual Paraformer and for any custom model URL the
 * user pastes in the model manager.
 */
class SherpaStreamingAsr {

    companion object {
        private const val TAG = "SherpaStreamingAsr"
        const val SAMPLE_RATE = 16000
        private const val PARAFORMER_TAIL_MS = 800 // right-context padding for last word

        /**
         * Build a recognizer config by scanning [modelDir] for known file names.
         * Returns null when the directory does not look like a valid streaming model.
         */
        private fun buildConfig(modelDir: File): OnlineRecognizerConfig? {
            val files = modelDir.listFiles() ?: return null
            val has = { name: String -> files.any { it.name.equals(name, true) } }
            val firstMatch = { vararg names: String ->
                files.firstOrNull { f -> names.any { n -> f.name.equals(n, true) } }?.absolutePath
            }

            val tokens = firstMatch("tokens.txt") ?: return null

            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 2.4f, 0.0f),
                    rule2 = EndpointRule(true, 1.4f, 0.0f),
                    rule3 = EndpointRule(false, 0.0f, 20.0f)
                ),
                enableEndpoint = true,
                modelConfig = OnlineModelConfig(
                    tokens = tokens,
                    numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                    provider = "cpu"
                )
            )

            // Paraformer
            val enc = firstMatch("encoder.int8.onnx", "encoder.onnx")
            val dec = firstMatch("decoder.int8.onnx", "decoder.onnx")
            if (enc != null && dec != null) {
                config.modelConfig.paraformer = OnlineParaformerModelConfig(encoder = enc, decoder = dec)
                config.modelConfig.modelType = "paraformer"
                return config
            }

            // Transducer (zipformer / lstm / etc.)
            val joiner = firstMatch("joiner.int8.onnx", "joiner.onnx")
            if (enc != null && dec != null && joiner != null) {
                config.modelConfig.transducer =
                    OnlineTransducerModelConfig(encoder = enc, decoder = dec, joiner = joiner)
                config.modelConfig.modelType = "zipformer2"
                return config
            }

            return null
        }

        /**
         * Final verification step: try to actually construct the native recognizer.
         * If the model files are wrong/corrupt this throws, so we surface a clean FAIL
         * instead of failing later at record time.
         */
        fun isModelValid(modelDir: File): Boolean {
            return try {
                val cfg = buildConfig(modelDir) ?: return false
                val r = OnlineRecognizer(assetManager = null, config = cfg)
                r.release()
                true
            } catch (e: Exception) {
                Log.w(TAG, "model load test failed for $modelDir: ${e.message}")
                false
            }
        }
    }

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var modelType: String = ""
    private var isReady = false

    /** Accumulated finalized text (segments ended at an endpoint). */
    private var finalized = StringBuilder()

    /** Whether the currently loaded model is a Paraformer (needs tail padding). */
    private val isParaformer: Boolean get() = modelType == "paraformer"

    fun init(modelDir: File): Boolean {
        release()
        return try {
            val cfg = buildConfig(modelDir) ?: return false
            recognizer = OnlineRecognizer(assetManager = null, config = cfg)
            modelType = cfg.modelConfig.modelType
            stream = recognizer!!.createStream()
            finalized.setLength(0)
            isReady = true
            Log.i(TAG, "ASR ready (modelType=$modelType) from ${modelDir.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "ASR init failed: ${e.message}")
            release()
            false
        }
    }

    fun isInitialized(): Boolean = isReady

    /**
     * Feed one chunk of 16 kHz mono PCM (float, -1..1). Returns the current
     * display text = finalized segments + current partial.
     */
    fun accept(samples: FloatArray): String {
        val rec = recognizer ?: return finalized.toString()
        val s = stream ?: return finalized.toString()

        s.acceptWaveform(samples, SAMPLE_RATE)
        while (rec.isReady(s)) rec.decode(s)

        val endpoint = rec.isEndpoint(s)
        var text = rec.getResult(s).text

        if (endpoint && isParaformer) {
            val tail = FloatArray((PARAFORMER_TAIL_MS * SAMPLE_RATE / 1000))
            s.acceptWaveform(tail, SAMPLE_RATE)
            while (rec.isReady(s)) rec.decode(s)
            text = rec.getResult(s).text
        }

        if (endpoint) {
            rec.reset(s)
            if (text.isNotBlank()) {
                if (finalized.isNotEmpty()) finalized.append('\n')
                finalized.append(text)
            }
            return finalized.toString()
        }

        return if (finalized.isNotEmpty()) {
            if (text.isBlank()) finalized.toString() else "${finalized}\n$text"
        } else {
            text
        }
    }

    /** Final flush: add tail padding once more so the last word is recognized. */
    fun finalText(): String {
        val rec = recognizer ?: return finalized.toString()
        val s = stream ?: return finalized.toString()
        if (isParaformer) {
            val tail = FloatArray((PARAFORMER_TAIL_MS * SAMPLE_RATE / 1000))
            s.acceptWaveform(tail, SAMPLE_RATE)
            while (rec.isReady(s)) rec.decode(s)
            val text = rec.getResult(s).text
            if (text.isNotBlank()) {
                if (finalized.isNotEmpty()) finalized.append('\n')
                finalized.append(text)
            }
        }
        return finalized.toString()
    }

    fun release() {
        try { stream?.release() } catch (_: Exception) {}
        try { recognizer?.release() } catch (_: Exception) {}
        stream = null
        recognizer = null
        isReady = false
        finalized.setLength(0)
    }
}
