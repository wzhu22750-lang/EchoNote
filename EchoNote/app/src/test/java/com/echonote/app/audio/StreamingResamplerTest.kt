package com.echonote.app.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/**
 * Pure JVM tests for [StreamingResampler]: incremental (chunked) processing must
 * agree with a one-shot [Resampler.resample] call, since both run the identical
 * kernel over the identical output positions.
 *
 * The comparison signal is `java.util.Random(42L)` — a fixed seed keeps the test
 * deterministic while still exercising broadband content.
 */
class StreamingResamplerTest {

    // ------------------------------------------------------------- fixtures

    private fun pseudoRandomSignal(size: Int): FloatArray {
        val random = Random(42L)
        return FloatArray(size) { random.nextFloat() * 2f - 1f }
    }

    private fun streamAll(signal: FloatArray, chunkSize: Int): FloatArray {
        val resampler = StreamingResampler(inputRate = 48_000, outputRate = 16_000)
        val out = ArrayList<Float>(signal.size / 3 + 16)
        var i = 0
        while (i < signal.size) {
            val end = minOf(i + chunkSize, signal.size)
            for (v in resampler.process(signal.copyOfRange(i, end))) out.add(v)
            i = end
        }
        for (v in resampler.flush()) out.add(v)
        return out.toFloatArray()
    }

    private fun assertAgreesWithOneShot(signal: FloatArray, chunkSize: Int) {
        val oneShot = Resampler.resample(signal, 48_000, 16_000)
        val streamed = streamAll(signal, chunkSize)

        // End-of-stream may emit a few extra tail samples (partial final window);
        // one-shot stops at floor(N/step).
        assertTrue("streaming produced ${streamed.size}, one-shot ${oneShot.size}", streamed.size >= oneShot.size)
        assertTrue(streamed.size <= oneShot.size + Resampler.DEFAULT_TAPS)

        var maxDiff = 0f
        for (i in 0 until oneShot.size) {
            val d = abs(oneShot[i] - streamed[i])
            if (d > maxDiff) maxDiff = d
        }
        assertTrue("chunked output diverged by $maxDiff", maxDiff < 1e-4f)
    }

    // ---------------------------------------------------------------- tests

    @Test
    fun `chunkedProcessingMatchesOneShot`() {
        assertAgreesWithOneShot(pseudoRandomSignal(30_000), chunkSize = 1_000)
    }

    @Test
    fun `tinyChunksMatchOneShot`() {
        assertAgreesWithOneShot(pseudoRandomSignal(30_000), chunkSize = 7)
    }

    @Test
    fun `chunkLargerThanTheSignalMatchesOneShot`() {
        assertAgreesWithOneShot(pseudoRandomSignal(9_000), chunkSize = 100_000)
    }

    @Test
    fun `flushFinalizesTheStreamExactlyOnce`() {
        val resampler = StreamingResampler(48_000, 16_000)
        val signal = pseudoRandomSignal(1_000)

        val midStream = resampler.process(signal)
        assertTrue("mid-stream output must not be empty", midStream.isNotEmpty())

        val tail = resampler.flush()
        assertTrue("flush must emit the buffered tail", tail.isNotEmpty())
        assertEquals(midStream.size.toLong() + tail.size.toLong(), resampler.totalOutputSamples)

        // The stream is finished: flushing again is a no-op and processing throws.
        assertTrue(resampler.flush().isEmpty())
        try {
            resampler.process(FloatArray(10))
            fail("process after flush must throw")
        } catch (expected: IllegalStateException) {
        }
    }

    @Test
    fun `passthroughWhenRatesMatch`() {
        val passthrough = StreamingResampler(16_000, 16_000)
        val input = floatArrayOf(0.1f, -0.2f, 0.3f)
        assertSame("same-rate processing must forward the array", input, passthrough.process(input))

        val partial = passthrough.process(floatArrayOf(1f, 2f, 3f, 4f), count = 2)
        assertArrayEquals(floatArrayOf(1f, 2f), partial, 0f)

        assertTrue(passthrough.flush().isEmpty())
    }

    @Test
    fun `totalOutputSamplesTracksEmittedSamples`() {
        val resampler = StreamingResampler(48_000, 16_000)
        val signal = pseudoRandomSignal(3_000)

        var emitted = 0
        var i = 0
        while (i < signal.size) {
            val end = minOf(i + 700, signal.size)
            emitted += resampler.process(signal.copyOfRange(i, end)).size
            i = end
        }
        assertEquals(emitted.toLong(), resampler.totalOutputSamples)

        emitted += resampler.flush().size
        assertEquals(emitted.toLong(), resampler.totalOutputSamples)
    }
}
