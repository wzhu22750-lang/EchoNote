package com.echonote.app.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pure JVM tests for the hand-written RIFF parser [WavFileReader].
 *
 * Every WAV fixture is assembled byte by byte in memory (via [riff]) so the
 * tests can express exactly the malformed inputs real-world recorders produce:
 * odd-sized chunks, `data` that is not the last chunk, truncated tails,
 * zero/0xFFFFFFFF streaming sizes and `WAVE_FORMAT_EXTENSIBLE`.
 */
class WavFileReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ------------------------------------------------- byte-level fixtures

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    private fun le32(v: Long) = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte(),
    )

    private fun chunk(id: String, payload: ByteArray): ByteArray {
        val pad = if (payload.size % 2 == 1) byteArrayOf(0) else ByteArray(0)
        return id.toByteArray(Charsets.US_ASCII) + le32(payload.size.toLong()) + payload + pad
    }

    /** RIFF container with correct top-level size: "RIFF" + size + "WAVE" + chunks. */
    private fun riff(vararg chunks: ByteArray): ByteArray {
        val body = "WAVE".toByteArray(Charsets.US_ASCII) + chunks.reduce { acc, b -> acc + b }
        return "RIFF".toByteArray(Charsets.US_ASCII) + le32(body.size.toLong()) + body
    }

    private fun fmtPcm(sampleRate: Int, channels: Int, bits: Int): ByteArray {
        val byteRate = sampleRate * channels * bits / 8
        val blockAlign = channels * bits / 8
        return le16(1) + le16(channels) +
            le32(sampleRate.toLong()) + le32(byteRate.toLong()) +
            le16(blockAlign) + le16(bits)
    }

    /** fmt size 40 with tag 0xFFFE; the real format tag rides in the GUID's first two bytes. */
    private fun fmtExtensible(sampleRate: Int, channels: Int, bits: Int, subFormatTag: Int): ByteArray {
        val byteRate = sampleRate * channels * bits / 8
        val blockAlign = channels * bits / 8
        val base = le16(0xFFFE) + le16(channels) +
            le32(sampleRate.toLong()) + le32(byteRate.toLong()) +
            le16(blockAlign) + le16(bits)
        val guid = le16(subFormatTag) + ByteArray(14)
        return base + le16(22) + le16(bits) + le32(0x3) + guid // cbSize, validBits, channelMask, GUID
    }

    private fun pcm16Bytes(values: IntArray): ByteArray {
        val out = ByteArray(values.size * 2)
        values.forEachIndexed { i, v ->
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun float32Bytes(values: FloatArray): ByteArray {
        val out = ByteArray(values.size * 4)
        values.forEachIndexed { i, v ->
            val bits = v.toRawBits()
            out[i * 4] = (bits and 0xFF).toByte()
            out[i * 4 + 1] = ((bits shr 8) and 0xFF).toByte()
            out[i * 4 + 2] = ((bits shr 16) and 0xFF).toByte()
            out[i * 4 + 3] = ((bits shr 24) and 0xFF).toByte()
        }
        return out
    }

    private fun writeWav(name: String, bytes: ByteArray): File =
        File(tmp.root, name).apply { writeBytes(bytes) }

    private fun expectWavFormatException(body: () -> Unit) {
        try {
            body()
            fail("expected WavFileReader.WavFormatException")
        } catch (expected: WavFileReader.WavFormatException) {
            // expected
        }
    }

    // ------------------------------------------------------------- headers

    @Test
    fun `readHeaderParsesCanonicalPcmFields`() {
        val file = writeWav(
            "canon.wav",
            riff(chunk("fmt ", fmtPcm(16_000, 1, 16)), chunk("data", pcm16Bytes(IntArray(320) { 0 }))),
        )
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
    fun `readReturnsSamplesWithTheFileSampleRate`() {
        val file = writeWav(
            "rate.wav",
            riff(chunk("fmt ", fmtPcm(48_000, 1, 16)), chunk("data", pcm16Bytes(intArrayOf(16_384)))),
        )
        val (samples, rate) = WavFileReader.read(file)
        assertEquals(48_000, rate)
        assertEquals(1, samples.size)
        assertEquals(0.5f, samples[0], 1e-9f)
    }

    @Test
    fun `durationMsIsDerivedFromFrameCountAndRate`() {
        val file = writeWav(
            "one-second.wav",
            riff(chunk("fmt ", fmtPcm(16_000, 1, 16)), chunk("data", pcm16Bytes(IntArray(16_000) { 0 }))),
        )
        assertEquals(1_000L, WavFileReader.readHeader(file).durationMs)
    }

    // ------------------------------------------------------------- decoders

    @Test
    fun `decodesPcm16Exactly`() {
        val file = writeWav(
            "pcm16.wav",
            riff(
                chunk("fmt ", fmtPcm(16_000, 1, 16)),
                chunk("data", pcm16Bytes(intArrayOf(0, 16_383, -16_384, Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()))),
            ),
        )
        val (samples, _) = WavFileReader.read(file)
        assertArrayEquals(
            floatArrayOf(0f, 16_383f / 32_768f, -0.5f, -1f, 32_767f / 32_768f),
            samples,
            1e-9f,
        )
    }

    @Test
    fun `decodesUnsignedEightBitPcmAround128`() {
        val payload = byteArrayOf(0x80.toByte(), 0x00, 0xFF.toByte(), 0x7F)
        val file = writeWav(
            "pcm8.wav",
            riff(chunk("fmt ", fmtPcm(8_000, 1, 8)), chunk("data", payload)),
        )
        val (samples, _) = WavFileReader.read(file)
        // (byte - 128) / 128
        assertArrayEquals(floatArrayOf(0f, -1f, 127f / 128f, -1f / 128f), samples, 1e-9f)
    }

    @Test
    fun `decodesTwentyFourBitPcm`() {
        // 24-bit little-endian: 0x400000 = 0.5, 0xC00000 (sign-extended) = -0.5.
        val payload = byteArrayOf(
            0x00, 0x00, 0x40, // +0.5
            0x00, 0x00, 0xC0.toByte(), // -0.5
            0x00, 0x00, 0x20, // +0.25
            0x00, 0x00, 0x80.toByte(), // -1.0
        )
        val file = writeWav(
            "pcm24.wav",
            riff(chunk("fmt ", fmtPcm(16_000, 1, 24)), chunk("data", payload)),
        )
        val h = WavFileReader.readHeader(file)
        assertEquals(24, h.bitsPerSample)
        assertEquals(3, h.bytesPerFrame)
        val (samples, _) = WavFileReader.read(file)
        assertArrayEquals(floatArrayOf(0.5f, -0.5f, 0.25f, -1f), samples, 1e-9f)
    }

    @Test
    fun `decodesFloat32WhenFormatTagIsThree`() {
        val values = floatArrayOf(0.5f, -0.25f, 1f, -1f)
        val file = writeWav(
            "f32.wav",
            riff(chunk("fmt ", fmtPcm(16_000, 1, 32).let { le16(3) + it.drop(2).toByteArray() }), chunk("data", float32Bytes(values))),
        )
        val h = WavFileReader.readHeader(file)
        assertEquals(3, h.formatTag)
        assertEquals(32, h.bitsPerSample)
        val (samples, _) = WavFileReader.read(file)
        assertArrayEquals("IEEE float samples must round-trip bit-exactly", values, samples, 1e-9f)
    }

    @Test
    fun `downmixesStereoToMonoByAveraging`() {
        val file = writeWav(
            "stereo.wav",
            riff(chunk("fmt ", fmtPcm(16_000, 2, 16)), chunk("data", pcm16Bytes(intArrayOf(16_384, -16_384, 8_192, 24_576)))),
        )
        val h = WavFileReader.readHeader(file)
        assertEquals(2, h.channels)
        assertEquals(4, h.bytesPerFrame)
        assertEquals(2L, h.frameCount)
        val (samples, _) = WavFileReader.read(file)
        assertArrayEquals(floatArrayOf(0f, 0.5f), samples, 1e-9f)
    }

    // ------------------------------------------------------ chunk traversal

    @Test
    fun `oddSizedChunksAreSkippedIncludingTheirPadByte`() {
        val listBefore = chunk("LIST", "abc".toByteArray(Charsets.US_ASCII)) // odd: 3 bytes + pad
        val note = chunk("ICMT", "hello".toByteArray(Charsets.US_ASCII)) // odd: 5 bytes + pad
        val file = writeWav(
            "odd.wav",
            riff(listBefore, chunk("fmt ", fmtPcm(16_000, 1, 16)), note, chunk("data", pcm16Bytes(intArrayOf(16_384, -16_384)))),
        )
        val h = WavFileReader.readHeader(file)
        assertEquals(16_000, h.sampleRate)
        assertEquals(2L, h.frameCount)
        val (samples, _) = WavFileReader.read(file)
        assertArrayEquals(floatArrayOf(0.5f, -0.5f), samples, 1e-9f)
    }

    @Test
    fun `dataChunkThatIsNotLastIsStillRead`() {
        val trailing = chunk("cue ", byteArrayOf(0, 0, 0, 0))
        val file = writeWav(
            "data-first.wav",
            riff(chunk("fmt ", fmtPcm(16_000, 1, 16)), chunk("data", pcm16Bytes(intArrayOf(0, 16_384))), trailing),
        )
        val (samples, _) = WavFileReader.read(file)
        assertArrayEquals(floatArrayOf(0f, 0.5f), samples, 1e-9f)
    }

    @Test
    fun `oversizedDataChunkIsClampedToTheActualFileSize`() {
        // Data chunk claims 20 bytes (10 frames) but the file was cut off at 14.
        val full = riff(chunk("fmt ", fmtPcm(16_000, 1, 16)), chunk("data", pcm16Bytes(IntArray(10) { i -> i * 1000 })))
        val truncated = full.copyOfRange(0, 44 + 14)
        val file = writeWav("cut.wav", truncated)

        val h = WavFileReader.readHeader(file)
        assertEquals(14L, h.dataBytes)
        assertEquals(7L, h.frameCount)

        val (samples, _) = WavFileReader.read(file)
        assertEquals(7, samples.size)
        assertEquals(0f, samples[0], 1e-9f)
        assertEquals(6_000f / 32_768f, samples[6], 1e-9f)
    }

    @Test
    fun `zeroAndStreamingSentinelDataSizesMeanUntilEndOfFile`() {
        val data = pcm16Bytes(intArrayOf(0, 16_384, -16_384, 8_192))

        val zeroSize = writeWav(
            "zero.wav",
            riff(chunk("fmt ", fmtPcm(16_000, 1, 16)), "data".toByteArray(Charsets.US_ASCII) + le32(0L) + data),
        )
        val sentinel = writeWav(
            "sentinel.wav",
            riff(chunk("fmt ", fmtPcm(16_000, 1, 16)), "data".toByteArray(Charsets.US_ASCII) + le32(0xFFFF_FFFFL) + data),
        )

        for (file in listOf(zeroSize, sentinel)) {
            val h = WavFileReader.readHeader(file)
            assertEquals("file ${file.name}", data.size.toLong(), h.dataBytes)
            assertEquals("file ${file.name}", 4L, h.frameCount)
            val (samples, _) = WavFileReader.read(file)
            assertEquals(4, samples.size)
        }
    }

    // -------------------------------------------------------- EXTENSIBLE

    @Test
    fun `extensiblePcmResolvesTheRealFormatTagFromTheGuid`() {
        val file = writeWav(
            "ext-pcm.wav",
            riff(chunk("fmt ", fmtExtensible(16_000, 1, 16, subFormatTag = 1)), chunk("data", pcm16Bytes(intArrayOf(16_384)))),
        )
        val h = WavFileReader.readHeader(file)
        assertEquals("0xFFFE must resolve to the GUID's 0x0001", 1, h.formatTag)
        val (samples, _) = WavFileReader.read(file)
        assertEquals(0.5f, samples[0], 1e-9f)
    }

    @Test
    fun `extensibleFloatDecodesIeeeFloatSamples`() {
        val values = floatArrayOf(0.25f, -0.75f)
        val file = writeWav(
            "ext-f32.wav",
            riff(chunk("fmt ", fmtExtensible(16_000, 1, 32, subFormatTag = 3)), chunk("data", float32Bytes(values))),
        )
        val h = WavFileReader.readHeader(file)
        assertEquals(3, h.formatTag)
        val (samples, _) = WavFileReader.read(file)
        assertArrayEquals(values, samples, 1e-9f)
    }

    // ---------------------------------------------------------- corruption

    @Test
    fun `corruptFilesThrowWavFormatException`() {
        expectWavFormatException {
            WavFileReader.readHeader(writeWav("not-riff.wav", "JUNKJUNKJUNK".toByteArray(Charsets.US_ASCII)))
        }
        expectWavFormatException {
            val bad = "RIFF".toByteArray(Charsets.US_ASCII) + le32(4L) + "AVI ".toByteArray(Charsets.US_ASCII)
            WavFileReader.readHeader(writeWav("not-wave.wav", bad))
        }
        expectWavFormatException {
            // RIFF/WAVE magic present but cut off before a fmt chunk can be parsed.
            val partial = riff(chunk("fmt ", fmtPcm(16_000, 1, 16))).copyOfRange(0, 12 + 5)
            WavFileReader.readHeader(writeWav("no-fmt.wav", partial))
        }
        expectWavFormatException {
            // fmt exists but there is no data chunk before EOF.
            WavFileReader.readHeader(writeWav("no-data.wav", riff(chunk("fmt ", fmtPcm(16_000, 1, 16)))))
        }
    }

    // ---------------------------------------------------------- streaming

    @Test
    fun `streamMonoDeliversBoundedChunks`() {
        val values = IntArray(100) { i -> (i - 50) * 300 }
        val file = writeWav(
            "stream.wav",
            riff(chunk("fmt ", fmtPcm(16_000, 1, 16)), chunk("data", pcm16Bytes(values))),
        )
        val header = WavFileReader.readHeader(file)

        val chunkSizes = ArrayList<Int>()
        val collected = ArrayList<Float>()
        WavFileReader.streamMono(file, header, chunkFrames = 30) { chunk, frames ->
            chunkSizes.add(frames)
            for (i in 0 until frames) collected.add(chunk[i])
        }

        assertEquals(listOf(30, 30, 30, 10), chunkSizes)
        val (oneShot, _) = WavFileReader.read(file)
        assertArrayEquals(oneShot.toList().toFloatArray(), collected.toFloatArray(), 1e-9f)
    }
}
