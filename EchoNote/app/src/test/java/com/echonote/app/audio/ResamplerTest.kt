package com.echonote.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Pure JVM tests for [Resampler].
 *
 * The tolerances are derived from an exact numerical simulation of the same
 * windowed-sinc kernel, not from optimistic guesses:
 *  * a 440 Hz sine at 48 kHz survives 48k→16k with amplitude error ~1.6e-5;
 *  * a 10 kHz tone (above the 8 kHz output Nyquist) lands at -23 dB — attenuated,
 *    but by a Blackman-windowed 33-tap kernel, not a brick wall.
 */
class ResamplerTest {

    // ------------------------------------------------------------- fixtures

    private fun sine(rate: Int, freq: Double, amplitude: Double, seconds: Double): FloatArray {
        val n = (rate * seconds).toInt()
        return FloatArray(n) { i -> (amplitude * sin(2.0 * PI * freq * i / rate)).toFloat() }
    }

    private fun peakOf(samples: FloatArray, from: Int, until: Int): Float {
        var p = 0f
        for (i in from until until) {
            val a = abs(samples[i])
            if (a > p) p = a
        }
        return p
    }

    /** Positive-going zero crossings per second — a robust frequency estimate. */
    private fun frequencyHz(samples: FloatArray, from: Int, until: Int, sampleRate: Int): Double {
        var crossings = 0
        for (i in from + 1 until until) {
            if (samples[i - 1] <= 0f && samples[i] > 0f) crossings++
        }
        return crossings * sampleRate.toDouble() / (until - from)
    }

    // ---------------------------------------------------------------- tests

    @Test
    fun `sameRateReturnsTheInputUnchanged`() {
        val signal = floatArrayOf(0.1f, -0.2f, 0.3f)
        assertSame("identity rate must not copy or transform", signal, Resampler.resample(signal, 16_000, 16_000))

        val empty = FloatArray(0)
        assertSame(empty, Resampler.resample(empty, 48_000, 16_000))
    }

    @Test
    fun `invalidSampleRatesAreRejected`() {
        try {
            Resampler.resample(FloatArray(10), 0, 16_000)
            fail("zero input rate must throw")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            StreamingResampler(16_000, -1)
            fail("negative output rate must throw")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `resamplesSinePreservingFrequencyAndAmplitude`() {
        val input = sine(48_000, freq = 440.0, amplitude = 0.8, seconds = 1.0)
        val out = Resampler.resample(input, 48_000, 16_000)

        assertEquals(16_000, out.size) // 1 s at 16 kHz

        // Steady-state region only: the edges carry the filter's warm-up/tail.
        val steadyPeak = peakOf(out, 1_000, 15_000)
        assertEquals("amplitude must be preserved", 0.8f, steadyPeak, 0.016f)

        val measuredHz = frequencyHz(out, 1_000, 15_000, 16_000)
        assertEquals("frequency must be preserved", 440.0, measuredHz, 1.0)
    }

    @Test
    fun `attenuatesTonesAboveTheOutputNyquist`() {
        // 10 kHz is above the 16 kHz output Nyquist (8 kHz): without an
        // anti-aliasing filter it would alias down to 6 kHz at full volume.
        val aliased = Resampler.resample(sine(48_000, freq = 10_000.0, amplitude = 0.8, seconds = 0.5), 48_000, 16_000)
        val leaked = peakOf(aliased, 1_000, aliased.size - 1_000)
        assertTrue("expected >= 14 dB attenuation, got peak $leaked", leaked < 0.8f * 0.2f)

        // Contrast: an in-band tone passes essentially untouched.
        val inBand = Resampler.resample(sine(48_000, freq = 1_000.0, amplitude = 0.8, seconds = 0.5), 48_000, 16_000)
        val kept = peakOf(inBand, 1_000, inBand.size - 1_000)
        assertTrue("in-band tone must survive, got peak $kept", kept > 0.8f * 0.75f)
    }

    @Test
    fun `outputLengthIsFloorOfScaledInputLength`() {
        assertEquals(16_000, Resampler.resample(FloatArray(48_000), 48_000, 16_000).size)
        assertEquals(16_000, Resampler.resample(FloatArray(48_001), 48_000, 16_000).size)
        assertEquals(15_999, Resampler.resample(FloatArray(47_999), 48_000, 16_000).size)
        assertEquals(14_700, Resampler.resample(FloatArray(44_100), 48_000, 16_000).size)
        assertEquals(16_000, Resampler.resample(FloatArray(16_000), 16_000, 16_000).size)
    }

    @Test
    fun `toWorkingFormatDownmixesThenResamples`() {
        // Stereo 48 kHz with an identical 440 Hz tone in both channels.
        val tone = sine(48_000, freq = 440.0, amplitude = 0.8, seconds = 1.0)
        val interleaved = FloatArray(tone.size * 2) { i -> tone[i / 2] }

        val out = Resampler.toWorkingFormat(interleaved, sourceRate = 48_000, sourceChannels = 2)

        assertEquals(16_000, out.size)
        val steadyPeak = peakOf(out, 1_000, 15_000)
        assertEquals("stereo average must keep the tone amplitude", 0.8f, steadyPeak, 0.016f)
        assertEquals(440.0, frequencyHz(out, 1_000, 15_000, 16_000), 1.0)
    }

    @Test
    fun `audioSpecByteMathRoundTrips`() {
        assertEquals(16_000, AudioSpec.SAMPLE_RATE)
        assertEquals(1, AudioSpec.CHANNELS)
        assertEquals(16, AudioSpec.BITS_PER_SAMPLE)
        assertEquals(2, AudioSpec.BYTES_PER_SAMPLE)

        assertEquals(32_000L, AudioSpec.bytesForMs(1_000L))
        assertEquals(1_000L, AudioSpec.msForBytes(32_000L))
        assertEquals("partial frames truncate down", 0L, AudioSpec.msForBytes(31L))
        // 100 ms = 3,200 bytes; every 320 bytes is 10 ms.
        assertEquals(10L, AudioSpec.bytesForMs(100L) / 320L)
    }
}
