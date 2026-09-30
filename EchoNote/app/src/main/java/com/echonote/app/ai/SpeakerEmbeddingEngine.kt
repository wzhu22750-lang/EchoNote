package com.echonote.app.ai

import com.echonote.app.audio.AudioSpec
import com.k2fsa.sherpa.onnx.OfflinePunctuation
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.Closeable

/**
 * Speaker voiceprint extractor.
 *
 * Produces one embedding per VAD speech span. The embeddings are what
 * [SpeakerClusterer] groups; nothing about volume or channel is used.
 */
class SpeakerEmbeddingEngine(
    modelPath: String,
    numThreads: Int = 1,
) : Closeable {

    private val extractor: SpeakerEmbeddingExtractor

    /** Embedding dimensionality reported by the loaded model (192 or 256 typical). */
    val dimension: Int

    /** Segments that were too short for a usable voiceprint, for diagnostics. */
    var rejectedShortSegments: Int = 0
        private set

    init {
        val config = SpeakerEmbeddingExtractorConfig(
            model = modelPath,
            numThreads = numThreads,
            debug = false,
            provider = "cpu",
        )
        extractor = SpeakerEmbeddingExtractor(config = config)
        dimension = extractor.dim()
    }

    /**
     * @return a unit-norm embedding, or null when the segment is too short for the
     *         model to produce a stable voiceprint (typically < 0.6 s of speech).
     */
    fun embed(samples: FloatArray, sampleRate: Int = AudioSpec.SAMPLE_RATE): FloatArray? {
        if (samples.size < minimumSamples(sampleRate)) {
            rejectedShortSegments++
            return null
        }
        val stream = extractor.createStream()
        return try {
            stream.acceptWaveform(samples, sampleRate)
            stream.inputFinished()
            if (!extractor.isReady(stream)) {
                rejectedShortSegments++
                null
            } else {
                EmbeddingUtils.l2Normalize(extractor.compute(stream))
            }
        } catch (_: RuntimeException) {
            // A malformed/short segment must not abort the whole transcription.
            rejectedShortSegments++
            null
        } finally {
            runCatching { stream.release() }
        }
    }

    /** Enrollment helper: the mean voiceprint of several samples of one person. */
    fun embedAndAverage(chunks: List<FloatArray>, sampleRate: Int = AudioSpec.SAMPLE_RATE): FloatArray? =
        EmbeddingUtils.mean(chunks.mapNotNull { embed(it, sampleRate) })

    private fun minimumSamples(sampleRate: Int): Int = sampleRate * MIN_SPEECH_MS / 1000

    override fun close() {
        runCatching { extractor.release() }
    }

    private companion object {
        /** Below this, speaker models are notoriously unstable. */
        const val MIN_SPEECH_MS = 400
    }
}

/**
 * Chinese/English punctuation restoration (CT-Transformer).
 *
 * SenseVoice and Whisper both emit unpunctuated or sparsely punctuated Mandarin, so
 * the transcript is much easier to read with this pass applied per segment.
 */
class PunctuationEngine(modelPath: String, numThreads: Int = 1) : Closeable {

    private val punctuation: OfflinePunctuation

    init {
        val config = OfflinePunctuationConfig(
            model = OfflinePunctuationModelConfig(
                ctTransformer = modelPath,
                numThreads = numThreads,
                debug = false,
                provider = "cpu",
            ),
        )
        punctuation = OfflinePunctuation(config = config)
    }

    /** Returns [text] unchanged if the model fails, so it is always safe to call. */
    fun addPunctuation(text: String): String {
        if (text.isBlank()) return text
        return runCatching { punctuation.addPunctuation(text) }.getOrDefault(text)
    }

    override fun close() {
        runCatching { punctuation.release() }
    }
}
