package com.echonote.app.ai

import com.echonote.app.audio.AudioSpec
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.Closeable

/** Which family of acoustic model the recognizer is backed by. */
enum class AsrFamily {
    /** Primary: FunAudioLLM SenseVoice-Small. Strong Mandarin/Cantonese, very fast,
     *  and it also predicts emotion and audio events. */
    SENSE_VOICE,

    /** Fallback: OpenAI Whisper. Weaker on Mandarin than SenseVoice but broader
     *  language coverage and a well-understood failure profile. */
    WHISPER,
}

/**
 * Which files a recognizer build needs on disk.
 *
 * Kept separate from the sherpa-onnx config objects so that the model catalog and
 * the download manager can reason about file sets without touching native code.
 */
data class AsrModelFiles(
    val family: AsrFamily,
    /** Single-file models (SenseVoice, Paraformer). */
    val modelPath: String = "",
    /** Whisper splits into an encoder and a decoder. */
    val encoderPath: String = "",
    val decoderPath: String = "",
    val tokensPath: String = "",
) {
    fun allPresent(): Boolean = listOfNotNull(
        modelPath.ifBlank { null },
        encoderPath.ifBlank { null },
        decoderPath.ifBlank { null },
        tokensPath.ifBlank { null },
    ).all { java.io.File(it).isFile }
}

data class AsrRequest(
    val language: String = "auto",
    val useInverseTextNormalization: Boolean = true,
    val enableTokenTimestamps: Boolean = true,
)

data class AsrText(
    val text: String,
    val tokens: List<String> = emptyList(),
    /** Per-token start times in seconds, relative to the segment passed in. */
    val timestamps: List<Float> = emptyList(),
    val language: String = "",
    val emotion: String = "",
    val event: String = "",
)

/**
 * Offline (non-streaming) recogniser.
 *
 * Deliberately instantiated once and reused: building an `OfflineRecognizer` loads
 * a ~200 MB ONNX graph, so the pipeline constructs one per run rather than one per
 * speech segment.
 */
class AsrEngine(
    private val files: AsrModelFiles,
    private val request: AsrRequest = AsrRequest(),
    numThreads: Int = 3,
) : Closeable {

    private val recognizer: OfflineRecognizer

    /** Wall-clock milliseconds spent inside [transcribe], for the diagnostics report. */
    var decodeMillis: Long = 0
        private set

    var decodedSamples: Long = 0
        private set

    init {
        val featureConfig = FeatureConfig(
            sampleRate = AudioSpec.SAMPLE_RATE,
            featureDim = 80,
        )

        val modelConfig = when (files.family) {
            AsrFamily.SENSE_VOICE -> OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = files.modelPath,
                    language = request.language,
                    useInverseTextNormalization = request.useInverseTextNormalization,
                ),
                tokens = files.tokensPath,
                numThreads = numThreads,
                debug = false,
                provider = "cpu",
                modelType = "sense_voice",
            )

            AsrFamily.WHISPER -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = files.encoderPath,
                    decoder = files.decoderPath,
                    language = request.language,
                    task = "transcribe",
                    tailPaddings = 1_000,
                    enableTokenTimestamps = request.enableTokenTimestamps,
                    enableSegmentTimestamps = false,
                ),
                tokens = files.tokensPath,
                numThreads = numThreads,
                debug = false,
                provider = "cpu",
                modelType = "whisper",
            )
        }

        val config = OfflineRecognizerConfig(
            featConfig = featureConfig,
            modelConfig = modelConfig,
            decodingMethod = "greedy_search",
            maxActivePaths = 4,
        )

        // `assetManager = null` selects sherpa-onnx's file-based native constructor.
        recognizer = OfflineRecognizer(config = config)
    }

    /**
     * Recognises one speech segment.
     *
     * @param samples mono float samples in [-1, 1] at [sampleRate].
     */
    fun transcribe(samples: FloatArray, sampleRate: Int = AudioSpec.SAMPLE_RATE): AsrText {
        if (samples.isEmpty()) return AsrText(text = "")
        val startedAt = System.currentTimeMillis()
        decodedSamples += samples.size

        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            AsrText(
                text = result.text.trim(),
                tokens = result.tokens?.toList() ?: emptyList(),
                timestamps = result.timestamps?.toList() ?: emptyList(),
                language = result.lang.orEmpty(),
                emotion = result.emotion.orEmpty(),
                event = result.event.orEmpty(),
            )
        } finally {
            decodeMillis += System.currentTimeMillis() - startedAt
            runCatching { stream.release() }
        }
    }

    override fun close() {
        runCatching { recognizer.release() }
    }
}
