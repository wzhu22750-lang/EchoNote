package com.echonote.app.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Band-limited sample-rate conversion.
 *
 * Imported phone recordings are routinely 44.1 kHz or 48 kHz, while every model we
 * run (Silero VAD, SenseVoice, the speaker-embedding extractors) expects 16 kHz
 * mono. Naive decimation would alias speech energy above 8 kHz back into the band
 * the models care about and measurably degrade recognition, so this is a
 * windowed-sinc resampler with an anti-aliasing cutoff rather than linear
 * interpolation.
 *
 * Pure Kotlin, therefore directly unit-testable on the JVM.
 */
object Resampler {

    /** Taps either side of the interpolation point. 16 is a good quality/speed
     *  trade-off for voice; measured cost is ~30 MACs per output sample. */
    const val DEFAULT_TAPS = 16

    /**
     * Resamples [input] from [inputRate] to [outputRate].
     *
     * @return the resampled signal; returns [input] itself when the rates match.
     */
    fun resample(
        input: FloatArray,
        inputRate: Int,
        outputRate: Int,
        taps: Int = DEFAULT_TAPS,
    ): FloatArray {
        require(inputRate > 0 && outputRate > 0) { "sample rates must be positive" }
        if (inputRate == outputRate || input.isEmpty()) return input

        val step = inputRate.toDouble() / outputRate
        val outputLength = maxOf(1, floor(input.size / step).toInt())
        val out = FloatArray(outputLength)

        // Anti-aliasing: when decimating, band-limit to the new Nyquist.
        val cutoff = if (outputRate < inputRate) outputRate.toDouble() / inputRate else 1.0
        val halfWidth = taps

        // Precompute the window for the normalised offsets we actually use.
        val kernel = DoubleArray(2 * halfWidth + 1)
        for (k in -halfWidth..halfWidth) {
            val x = k.toDouble()
            kernel[k + halfWidth] = sinc(cutoff * x) * cutoff * blackman(x / halfWidth)
        }

        for (i in 0 until outputLength) {
            val center = i * step
            val first = floor(center).toInt() - halfWidth + 1
            var acc = 0.0
            var norm = 0.0
            for (k in 0 until kernel.size) {
                val idx = first + k
                if (idx < 0 || idx >= input.size) continue
                val w = kernel[k]
                acc += input[idx] * w
                norm += w
            }
            out[i] = if (abs(norm) > 1e-9) (acc / norm).toFloat() else 0f
        }
        return out
    }

    /** Normalised sinc: sin(pi x) / (pi x), with the removable singularity handled. */
    private fun sinc(x: Double): Double {
        if (abs(x) < 1e-9) return 1.0
        val px = PI * x
        return sin(px) / px
    }

    /** Blackman window over [-1, 1]. */
    private fun blackman(t: Double): Double {
        if (abs(t) >= 1.0) return 0.0
        val x = PI * (t + 1.0) // map [-1,1] -> [0, 2pi]
        return 0.42 - 0.5 * cos(x) + 0.08 * cos(2 * x)
    }

    /**
     * Convenience wrapper that also folds multi-channel audio down to mono and
     * lands on the pipeline's working rate.
     */
    fun toWorkingFormat(
        interleaved: FloatArray,
        sourceRate: Int,
        sourceChannels: Int,
        workingRate: Int = AudioSpec.SAMPLE_RATE,
    ): FloatArray {
        val mono = PcmAudioUtils.downmixToMono(interleaved, sourceChannels)
        return resample(mono, sourceRate, workingRate)
    }
}

/** Single source of truth for the format everything downstream assumes. */
object AudioSpec {
    /** 16 kHz mono 16-bit is what every bundled model was trained on. */
    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16
    const val BYTES_PER_SAMPLE = 2
    const val WAV_HEADER_BYTES = 44L

    /** Frames per AudioRecord read (~100 ms at 16 kHz). */
    const val READ_CHUNK_FRAMES = 1_600

    fun bytesForMs(ms: Long): Long = ms * SAMPLE_RATE / 1000L * BYTES_PER_SAMPLE

    fun msForBytes(bytes: Long): Long = bytes / BYTES_PER_SAMPLE * 1000L / SAMPLE_RATE
}
