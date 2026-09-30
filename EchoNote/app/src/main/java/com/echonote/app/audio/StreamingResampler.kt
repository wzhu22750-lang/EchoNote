package com.echonote.app.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Incremental sample-rate converter for files that are decoded chunk by chunk.
 *
 * A one-shot [Resampler.resample] call would need the whole decoded signal in
 * memory, which is not viable for a one-hour 48 kHz stereo import. This class
 * keeps only the `2 * taps + 1` samples of context that the next output sample
 * needs, so memory stays flat regardless of file length.
 *
 * Feed it audio with [process]; call [flush] once at end-of-stream. Output is
 * never delayed by more than one tap window, so callers can pipeline straight
 * into VAD.
 */
class StreamingResampler(
    private val inputRate: Int,
    private val outputRate: Int,
    private val taps: Int = Resampler.DEFAULT_TAPS,
) {
    init {
        require(inputRate > 0 && outputRate > 0) { "sample rates must be positive" }
    }

    private val passthrough = inputRate == outputRate
    private val step = inputRate.toDouble() / outputRate
    private val cutoff = if (outputRate < inputRate) outputRate.toDouble() / inputRate else 1.0

    private val kernel = DoubleArray(2 * taps + 1) { k ->
        val x = (k - taps).toDouble()
        sinc(cutoff * x) * cutoff * blackman(x / taps)
    }

    private var buffer = FloatArray(INITIAL_CAPACITY)
    private var size = 0
    /** Global index of `buffer[0]` within the whole input stream. */
    private var globalStart = 0L
    private var nextOutput = 0L
    private var finished = false

    val totalOutputSamples: Long get() = nextOutput

    /** Converts [count] samples and returns everything that is now decidable. */
    fun process(input: FloatArray, count: Int = input.size): FloatArray {
        check(!finished) { "flush() already called" }
        if (passthrough) return if (count == input.size) input else input.copyOf(count)
        append(input, count)
        return drain()
    }

    /** Signals end-of-stream and returns the remaining output samples. */
    fun flush(): FloatArray {
        if (finished) return FloatArray(0)
        finished = true
        return if (passthrough) FloatArray(0) else drain()
    }

    private fun append(input: FloatArray, count: Int) {
        if (count <= 0) return
        compact()
        if (size + count > buffer.size) {
            var capacity = maxOf(buffer.size * 2, size + count)
            if (capacity < 0) capacity = size + count
            buffer = buffer.copyOf(capacity)
        }
        input.copyInto(buffer, size, 0, count)
        size += count
    }

    /** Drops the prefix that no future output sample can reference. */
    private fun compact() {
        val keepFrom = floor(nextOutput * step).toInt() - taps + 1
        val drop = (keepFrom - globalStart).toInt()
        if (drop <= 0) return
        val effective = minOf(drop, size)
        if (effective >= size) {
            globalStart += size
            size = 0
            return
        }
        System.arraycopy(buffer, effective, buffer, 0, size - effective)
        size -= effective
        globalStart += effective
    }

    private fun drain(): FloatArray {
        if (size <= 0 && !finished) return FloatArray(0)

        var capacity = 1024
        var out = FloatArray(capacity)
        var outCount = 0

        while (true) {
            val center = nextOutput * step
            val first = floor(center).toInt() - taps + 1
            val last = first + kernel.size - 1
            val available = last < globalStart + size

            // Mid-stream, wait until every tap is in hand. At end-of-stream, accept
            // a partial window and renormalise (that is what handles the tail).
            if (!available && !finished) break
            if (finished && first >= globalStart + size) break

            var acc = 0.0
            var norm = 0.0
            for (k in 0 until kernel.size) {
                val globalIndex = first + k
                val local = (globalIndex - globalStart).toInt()
                if (local < 0 || local >= size) continue
                val w = kernel[k]
                acc += buffer[local] * w
                norm += w
            }

            if (outCount == capacity) {
                capacity *= 2
                out = out.copyOf(capacity)
            }
            out[outCount++] = if (abs(norm) > 1e-9) (acc / norm).toFloat() else 0f
            nextOutput++
        }

        compact()
        return if (outCount == out.size) out else out.copyOf(outCount)
    }

    private companion object {
        const val INITIAL_CAPACITY = 1 shl 16

        fun sinc(x: Double): Double {
            if (abs(x) < 1e-9) return 1.0
            val px = PI * x
            return sin(px) / px
        }

        fun blackman(t: Double): Double {
            if (abs(t) >= 1.0) return 0.0
            val x = PI * (t + 1.0)
            return 0.42 - 0.5 * cos(x) + 0.08 * cos(2 * x)
        }
    }
}
