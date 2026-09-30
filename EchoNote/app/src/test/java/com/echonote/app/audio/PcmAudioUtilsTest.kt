package com.echonote.app.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Pure JVM tests for [PcmAudioUtils] (level maths, downmix, PCM16 conversion,
 * normalisation, envelope) — the shared audio vocabulary used by the recorder,
 * the waveform view and the importer.
 */
class PcmAudioUtilsTest {

    // ------------------------------------------------------------------ rms

    @Test
    fun `rmsMatchesAnalyticValues`() {
        val constant = FloatArray(1_000) { 0.5f }
        assertEquals(0.5f, PcmAudioUtils.rms(constant), 1e-6f)

        // 10 full sine cycles: RMS of a unit-amplitude sine is 1/sqrt(2).
        val sine = FloatArray(1_000) { i -> sin(2.0 * PI * 10 * i / 1_000).toFloat() }
        assertEquals(0.70710678f, PcmAudioUtils.rms(sine), 0.001f)

        assertEquals(0f, PcmAudioUtils.rms(FloatArray(0)), 0f)
        assertEquals("count parameter must bound the window", 0.5f, PcmAudioUtils.rms(floatArrayOf(0.5f, 0.5f, 0f, 0f), count = 2), 1e-6f)
    }

    // ----------------------------------------------------------------- peak

    @Test
    fun `peakReturnsLargestMagnitude`() {
        assertEquals(0.9f, PcmAudioUtils.peak(floatArrayOf(-0.9f, 0.3f)), 0f)
        assertEquals(0f, PcmAudioUtils.peak(FloatArray(0)), 0f)
        assertEquals("count parameter must bound the window", 0.9f, PcmAudioUtils.peak(floatArrayOf(0.9f, -0.95f), count = 1), 0f)
    }

    // ----------------------------------------------------------------- dbFs

    @Test
    fun `dbFsIsTwentyLogTenWithASilenceFloor`() {
        assertEquals(-20f, PcmAudioUtils.dbFs(0.1f), 1e-3f)
        assertEquals(0f, PcmAudioUtils.dbFs(1f), 1e-3f)
        assertEquals(PcmAudioUtils.SILENCE_DB, PcmAudioUtils.dbFs(0f), 0f)
        assertEquals("near-digital silence maps to the floor", PcmAudioUtils.SILENCE_DB, PcmAudioUtils.dbFs(1e-8f), 0f)
    }

    // -------------------------------------------------------------- clipping

    @Test
    fun `clippingRatioCountsSamplesAtOrAboveThreshold`() {
        val samples = floatArrayOf(1.0f, 0.5f, -0.999f, 0.2f)
        assertEquals(0.5f, PcmAudioUtils.clippingRatio(samples), 0f)
        assertEquals(0.75f, PcmAudioUtils.clippingRatio(samples, threshold = 0.5f), 0f)
        assertEquals(0f, PcmAudioUtils.clippingRatio(FloatArray(0)), 0f)
        assertEquals("quiet material never clips", 0f, PcmAudioUtils.clippingRatio(FloatArray(100) { 0.4f }), 0f)
    }

    // -------------------------------------------------------------- silence

    @Test
    fun `isSilentUsesTheConfiguredFloor`() {
        assertTrue(PcmAudioUtils.isSilent(FloatArray(1_024)))
        assertTrue(PcmAudioUtils.isSilent(FloatArray(0)))
        assertTrue("1e-6 is below the default 1e-5 floor", PcmAudioUtils.isSilent(FloatArray(64) { 1e-6f }))
        assertFalse(PcmAudioUtils.isSilent(FloatArray(64) { 2e-5f }))

        assertTrue(PcmAudioUtils.isSilent(floatArrayOf(-0.4f), floor = 0.5f))
        assertFalse(PcmAudioUtils.isSilent(floatArrayOf(0.6f), floor = 0.5f))
    }

    // -------------------------------------------------------------- downmix

    @Test
    fun `downmixToMonoAveragesChannels`() {
        val stereo = floatArrayOf(0.5f, -0.5f, 0.25f, 0.75f)
        assertArrayEquals(floatArrayOf(0f, 0.5f), PcmAudioUtils.downmixToMono(stereo, channels = 2), 1e-9f)

        val quad = floatArrayOf(1f, 0f, 0f, 0f, 0.5f, 0.5f, 0.25f, 0.25f)
        assertArrayEquals(floatArrayOf(0.25f, 0.375f), PcmAudioUtils.downmixToMono(quad, channels = 4), 1e-9f)

        val mono = floatArrayOf(0.1f, -0.2f)
        assertSame("mono input must be returned as-is", mono, PcmAudioUtils.downmixToMono(mono, channels = 1))

        // A trailing partial frame is dropped, not averaged.
        assertArrayEquals(floatArrayOf(1f), PcmAudioUtils.downmixToMono(floatArrayOf(1f, 1f, 1f), channels = 2), 0f)
    }

    // --------------------------------------------------------- PCM16 codecs

    @Test
    fun `pcm16ToFloatDecodesLittleEndianSigned`() {
        val values = intArrayOf(0, 16_383, Short.MAX_VALUE.toInt(), Short.MIN_VALUE.toInt())
        val bytes = ByteArray(values.size * 2)
        values.forEachIndexed { i, v ->
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        assertArrayEquals(
            floatArrayOf(0f, 16_383f / 32_768f, 32_767f / 32_768f, -1f),
            PcmAudioUtils.pcm16ToFloat(bytes),
            1e-9f,
        )
        // Odd trailing byte is ignored.
        assertEquals(1, PcmAudioUtils.pcm16ToFloat(byteArrayOf(0x00, 0x40, 0x7F)).size)
    }

    @Test
    fun `floatToPcm16TruncatesAndClampsLittleEndian`() {
        assertArrayEquals(
            byteArrayOf(
                0xFF.toByte(), 0x3F, // 0.5   -> 16383
                0x01, 0xC0.toByte(), // -0.5  -> -16383
                0xFF.toByte(), 0x7F, // 1.5   -> clamped 32767
                0x00, 0x80.toByte(), // -1.5  -> clamped -32768
            ),
            PcmAudioUtils.floatToPcm16(floatArrayOf(0.5f, -0.5f, 1.5f, -1.5f)),
        )
        assertEquals(0, PcmAudioUtils.floatToPcm16(FloatArray(0)).size)
    }

    @Test
    fun `toPcm16ClampsToSignedShortRange`() {
        assertEquals(0, PcmAudioUtils.toPcm16(0f).toInt())
        assertEquals(16_383, PcmAudioUtils.toPcm16(0.5f).toInt())
        assertEquals(-16_383, PcmAudioUtils.toPcm16(-0.5f).toInt())
        assertEquals(32_767, PcmAudioUtils.toPcm16(1f).toInt())
        assertEquals(-32_767, PcmAudioUtils.toPcm16(-1f).toInt())
        assertEquals(32_767, PcmAudioUtils.toPcm16(1.5f).toInt())
        assertEquals(-32_768, PcmAudioUtils.toPcm16(-1.5f).toInt())
    }

    @Test
    fun `pcm16RoundTripStaysWithinOneLsb`() {
        val original = FloatArray(101) { i -> (i - 50) / 50f } // -1f .. 1f step 1/50
        val decoded = PcmAudioUtils.pcm16ToFloat(PcmAudioUtils.floatToPcm16(original))
        assertEquals(original.size, decoded.size)
        for (i in original.indices) {
            assertTrue("sample $i drifted by ${decoded[i] - original[i]}", abs(decoded[i] - original[i]) < 1e-4f)
        }
    }

    // ----------------------------------------------------------- normalise

    @Test
    fun `normalizeTargetsThePeak`() {
        val normalised = PcmAudioUtils.normalize(floatArrayOf(0.1f, -0.2f))
        assertEquals(0.89f, PcmAudioUtils.peak(normalised), 1e-6f)
        assertEquals(0.445f, normalised[0], 1e-6f)
        assertTrue(normalised[0] > 0f && normalised[1] < 0f)
    }

    @Test
    fun `normalizeLeavesSilenceAloneAndCapsTheGain`() {
        val silence = FloatArray(32)
        assertSame("digital silence must be returned untouched", silence, PcmAudioUtils.normalize(silence))

        // 0.89 / 0.01 = 89x would blast noise up; the gain cap is 8x.
        val capped = PcmAudioUtils.normalize(floatArrayOf(0.01f, 0f))
        assertEquals(0.08f, capped[0], 1e-6f)
        assertEquals(0f, capped[1], 0f)
    }

    // ------------------------------------------------------------ envelope

    @Test
    fun `envelopeBucketsTrackLocalPeaks`() {
        val samples = FloatArray(100) { i -> if (i < 25) 0.1f else 0.5f }
        val env = PcmAudioUtils.envelope(samples, buckets = 4)
        assertArrayEquals(floatArrayOf(0.1f, 0.5f, 0.5f, 0.5f), env, 1e-9f)
    }

    @Test
    fun `envelopeHandlesDegenerateInputs`() {
        assertEquals(0, PcmAudioUtils.envelope(FloatArray(0), buckets = 8).size)
        assertEquals(0, PcmAudioUtils.envelope(floatArrayOf(0.5f), buckets = 0).size)

        // More buckets than samples: the stride floor is 1, unused buckets are 0.
        val env = PcmAudioUtils.envelope(floatArrayOf(0.2f, -0.4f, 0.1f), buckets = 5)
        assertArrayEquals(floatArrayOf(0.2f, 0.4f, 0.1f, 0f, 0f), env, 1e-9f)
    }
}
