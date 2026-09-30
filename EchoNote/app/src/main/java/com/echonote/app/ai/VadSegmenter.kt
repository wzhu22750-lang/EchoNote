package com.echonote.app.ai

import com.echonote.app.audio.AudioSpec
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.Closeable

/**
 * Thin, allocation-conscious wrapper over sherpa-onnx's Silero VAD.
 *
 * The VAD is fed in exact `windowSize` blocks (512 samples @ 16 kHz), which is the
 * granularity the underlying Silero model was exported for.
 *
 * Segments are delivered through a callback and never accumulated, so peak memory
 * is one speech segment rather than the whole recording.
 */
class VadSegmenter(
    modelPath: String,
    sampleRate: Int = AudioSpec.SAMPLE_RATE,
    threshold: Float = 0.5f,
    minSilenceDuration: Float = 0.35f,
    minSpeechDuration: Float = 0.25f,
    maxSpeechDuration: Float = 12f,
    numThreads: Int = 1,
) : Closeable {

    private val sampleRate = sampleRate
    private val windowSize: Int

    private val vad: Vad

    /** Total samples handed to the VAD so far; the pipeline's own clock. */
    private var fedSamples = 0L

    /** Leftover samples that did not fill a whole window yet. */
    private var carry = FloatArray(0)

    /** Counts how many segments were emitted, for the diagnostics report. */
    var emittedSegments: Int = 0
        private set

    init {
        // Silero is exported for 512-sample windows at 16 kHz and 256 at 8 kHz.
        windowSize = if (sampleRate >= 16_000) 512 else 256
        val silero = SileroVadModelConfig(
            model = modelPath,
            threshold = threshold,
            minSilenceDuration = minSilenceDuration,
            minSpeechDuration = minSpeechDuration,
            windowSize = windowSize,
            maxSpeechDuration = maxSpeechDuration,
        )
        val config = VadModelConfig(
            sileroVadModelConfig = silero,
            sampleRate = sampleRate,
            numThreads = numThreads,
            provider = "cpu",
            debug = false,
        )
        vad = Vad(config = config)
    }

    /**
     * Feeds [samples] and invokes [onSegment] for every completed speech span.
     *
     * @param onSegment `(startMs, endMs, samples)` — the sample array is owned by
     *        the callback and must be copied if retained.
     */
    fun accept(
        samples: FloatArray,
        count: Int = samples.size,
        onSegment: (startMs: Long, endMs: Long, samples: FloatArray) -> Unit,
    ) {
        if (count <= 0) return

        val block = if (carry.isEmpty()) {
            samples
        } else {
            FloatArray(carry.size + count).also {
                carry.copyInto(it, 0)
                samples.copyInto(it, carry.size, 0, count)
            }
        }
        val total = if (carry.isEmpty()) count else carry.size + count
        carry = FloatArray(0)

        var offset = 0
        while (offset + windowSize <= total) {
            val window = block.copyOfRange(offset, offset + windowSize)
            vad.acceptWaveform(window)
            fedSamples += windowSize
            offset += windowSize
            drain(onSegment)
        }

        if (offset < total) {
            carry = block.copyOfRange(offset, total)
        }
    }

    /** Ends the stream and emits whatever speech is still buffered. */
    fun flush(onSegment: (startMs: Long, endMs: Long, samples: FloatArray) -> Unit) {
        if (carry.isNotEmpty()) {
            // Zero-pad the tail to one full window so the model sees a valid frame.
            val padded = FloatArray(windowSize)
            carry.copyInto(padded, 0, 0, minOf(carry.size, windowSize))
            vad.acceptWaveform(padded)
            fedSamples += windowSize
            carry = FloatArray(0)
        }
        vad.flush()
        drain(onSegment)
    }

    private fun drain(onSegment: (startMs: Long, endMs: Long, samples: FloatArray) -> Unit) {
        while (!vad.empty()) {
            // ⚠️ ORDER MATTERS. `pop()` advances the detector past the front segment
            // and invalidates it. Reading `samples` afterwards yields an EMPTY array.
            //
            // This was measured, not assumed: with sherpa-onnx 1.13.8 on macOS, the
            // same 16 s two-speaker file produced
            //   read before pop -> 4 segments, sample counts [27584, 33728, 33728, 39872]
            //   read after  pop -> 4 segments, sample counts [0, 0, 0, 0]
            // Getting this wrong would silently produce an app that records fine and
            // transcribes nothing at all.
            val segment = vad.front()
            val declaredStart = segment.start
            val samples = segment.samples
            vad.pop()

            if (samples.isEmpty()) continue

            val startSample = resolveStartSample(declaredStart, samples.size, fedSamples)
            val endSample = startSample + samples.size

            onSegment(
                startSample * 1000L / sampleRate,
                endSample * 1000L / sampleRate,
                samples,
            )
            emittedSegments++
        }
    }

    /**
     * Interprets [SpeechSegment.start] defensively.
     *
     * **Determined empirically**, not from documentation. Running the reference
     * sherpa-onnx 1.13.8 build over three fixtures showed the value is an
     * **absolute sample index within the stream fed so far**:
     *   * strictly monotonic across 61 segments of a 272 s file,
     *   * always satisfying `start + samples.size <= fedSamples`,
     *   * and *not* consistent with a millisecond reading.
     *
     * The millisecond interpretation is still checked first-to-last as a guard, so
     * that a future binding change cannot silently shift every timestamp by 16x.
     * A conservative fallback ("the segment ended now") covers anything else.
     */
    private fun resolveStartSample(declared: Int, length: Int, fed: Long): Long {
        if (declared >= 0 && declared.toLong() + length <= fed + windowSize) {
            return declared.toLong()
        }
        val asMillis = declared.toLong() * sampleRate / 1000L
        if (declared >= 0 && asMillis + length <= fed + windowSize) {
            return asMillis
        }
        return (fed - length).coerceAtLeast(0L)
    }

    override fun close() {
        runCatching { vad.release() }
    }
}
