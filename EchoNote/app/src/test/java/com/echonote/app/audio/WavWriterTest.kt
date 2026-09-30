package com.echonote.app.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Pure JVM tests for [WavWriter]: write → close → read back with
 * [WavFileReader], plus direct byte-level checks of the patched header.
 *
 * All audio fixtures are synthesised in memory — no external resources.
 */
class WavWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ------------------------------------------------------------- fixtures

    private fun sine(frames: Int, period: Float = 50f, amplitude: Float = 0.5f): FloatArray =
        FloatArray(frames) { i -> amplitude * sin(2.0 * PI * i / period).toFloat() }

    private fun le32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)

    private fun le16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun ascii(b: ByteArray, off: Int, len: Int) = String(b, off, len, Charsets.US_ASCII)

    private fun newFile(name: String): File = File(tmp.root, name)

    /** One LSB of PCM16 in the normalised float domain. */
    private val lsb = 1.0f / 32768f

    // -------------------------------------------------------------- header

    @Test
    fun `writerProducesCanonical44ByteHeader`() {
        val file = newFile("canon.wav")
        WavWriter(file, sampleRate = 16_000, channels = 1).use { writer ->
            writer.append(sine(320))
        }

        val header = WavWriter.buildHeader(16_000, 1, 640L)
        assertArrayEquals("closed file header must equal buildHeader(sr, ch, dataBytes)", header, file.readBytes().copyOfRange(0, 44))

        val h = WavFileReader.readHeader(file)
        assertEquals(16_000, h.sampleRate)
        assertEquals(1, h.channels)
        assertEquals(16, h.bitsPerSample)
        assertEquals(1, h.formatTag)
        assertEquals(44L, h.dataOffset)
        assertEquals(640L, h.dataBytes)
        assertEquals(320L, h.frameCount)
        assertEquals(2, h.bytesPerFrame)
        assertEquals(20L, h.durationMs)
    }

    @Test
    fun `buildHeaderEmitsCanonicalLittleEndianFields`() {
        val header = WavWriter.buildHeader(44_100, 2, 8_820L)
        assertEquals(44, header.size)
        assertEquals("RIFF", ascii(header, 0, 4))
        assertEquals(36L + 8_820L, le32(header, 4)) // RIFF size
        assertEquals("WAVE", ascii(header, 8, 4))
        assertEquals("fmt ", ascii(header, 12, 4))
        assertEquals(16L, le32(header, 16))         // fmt chunk size
        assertEquals(1, le16(header, 20))           // WAVE_FORMAT_PCM
        assertEquals(2, le16(header, 22))           // channels
        assertEquals(44_100L, le32(header, 24))     // sample rate
        assertEquals(44_100L * 2 * 2, le32(header, 28)) // byte rate
        assertEquals(4, le16(header, 32))           // block align
        assertEquals(16, le16(header, 34))          // bits per sample
        assertEquals("data", ascii(header, 36, 4))
        assertEquals(8_820L, le32(header, 40))      // data size
    }

    // ----------------------------------------------------------- roundtrip

    @Test
    fun `roundTripPreservesSamplesWithinOneLsb`() {
        val file = newFile("roundtrip.wav")
        val original = sine(1_000, period = 37f, amplitude = 0.8f)
        WavWriter(file).use { it.append(original) }

        val (decoded, sampleRate) = WavFileReader.read(file)
        assertEquals(16_000, sampleRate)
        assertEquals(original.size, decoded.size)
        for (i in original.indices) {
            assertTrue("sample $i drifted: ${decoded[i]} vs ${original[i]}", abs(decoded[i] - original[i]) <= 1.0f / 16_000f)
        }
    }

    @Test
    fun `appendPcm16RoundTripsExactValues`() {
        val file = newFile("pcm16.wav")
        val values = intArrayOf(0, 16_383, -16_384, Short.MAX_VALUE.toInt(), Short.MIN_VALUE.toInt())
        val bytes = ByteArray(values.size * 2)
        values.forEachIndexed { i, v ->
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        WavWriter(file).use { it.appendPcm16(bytes) }

        val (decoded, _) = WavFileReader.read(file)
        assertEquals(values.size, decoded.size)
        val expected = floatArrayOf(
            0f,
            16_383f / 32_768f,
            -16_384f / 32_768f,
            32_767f / 32_768f,
            -1f,
        )
        assertArrayEquals(expected, decoded, 1e-9f)
    }

    // -------------------------------------------------------- frame counting

    @Test
    fun `framesAndDurationMsTrackAppends`() {
        val file = newFile("counter.wav")
        val writer = WavWriter(file)
        assertEquals(0L, writer.frames)
        assertEquals(0L, writer.durationMs)

        writer.append(sine(16_000))
        assertEquals(16_000L, writer.frames)
        assertEquals(1_000L, writer.durationMs)

        writer.append(sine(8_000))
        assertEquals(24_000L, writer.frames)
        assertEquals(1_500L, writer.durationMs)

        writer.appendPcm16(ByteArray(640)) // 320 frames
        assertEquals(24_320L, writer.frames)

        writer.close()
        assertEquals(24_320L * 2L, WavFileReader.readHeader(file).dataBytes)
    }

    @Test
    fun `stereoWriterCountsFramesAndReaderDownmixes`() {
        val file = newFile("stereo.wav")
        WavWriter(file, sampleRate = 44_100, channels = 2).use { writer ->
            writer.append(floatArrayOf(0.5f, -0.5f, 0.25f, 0.75f)) // 2 frames
        }

        val h = WavFileReader.readHeader(file)
        assertEquals(44_100, h.sampleRate)
        assertEquals(2, h.channels)
        assertEquals(2L, h.frameCount)
        assertEquals(8L, h.dataBytes)

        val (decoded, _) = WavFileReader.read(file)
        assertArrayEquals(floatArrayOf(0f, 0.5f), decoded, lsb)
    }

    // ------------------------------------------------------------ edge cases

    @Test
    fun `appendAfterCloseThrows`() {
        val file = newFile("closed.wav")
        val writer = WavWriter(file)
        writer.close()

        try {
            writer.append(sine(10))
            fail("append after close should throw")
        } catch (expected: IllegalStateException) {
        }
        try {
            writer.appendPcm16(ByteArray(4))
            fail("appendPcm16 after close should throw")
        } catch (expected: IllegalStateException) {
        }
    }

    @Test
    fun `emptyAppendIsIgnoredAndZeroFrameFileIsReadable`() {
        val file = newFile("empty.wav")
        val writer = WavWriter(file)
        writer.flush() // harmless mid-stream
        writer.append(FloatArray(0))
        assertEquals(0L, writer.frames)
        writer.close()

        val h = WavFileReader.readHeader(file)
        assertEquals(0L, h.frameCount)
        assertEquals(0L, h.dataBytes)
        val (decoded, _) = WavFileReader.read(file)
        assertEquals(0, decoded.size)
    }

    @Test
    fun `closeIsIdempotent`() {
        val file = newFile("twice.wav")
        val writer = WavWriter(file)
        writer.append(sine(160))
        writer.close()
        val afterFirstClose = file.readBytes()
        writer.close() // must be a no-op
        assertArrayEquals(afterFirstClose, file.readBytes())
        assertEquals(160L, WavFileReader.readHeader(file).frameCount)
    }
}
