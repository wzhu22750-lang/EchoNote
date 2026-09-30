package com.echonote.app.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Pure JVM tests for [WavRepair] — the "app killed / battery died mid-recording"
 * recovery path. The crash is simulated by writing a file whose header still
 * carries the zero-length placeholder emitted in the [WavWriter] constructor
 * (i.e. `close()` never patched offsets 4 and 40), and by truncating otherwise
 * healthy files.
 */
class WavRepairTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ------------------------------------------------------------- fixtures

    private fun sine(frames: Int, amplitude: Float = 0.5f): FloatArray =
        FloatArray(frames) { i -> amplitude * sin(2.0 * PI * i / 50).toFloat() }

    private fun newFile(name: String): File = File(tmp.root, name)

    /** A file as it looks when the process died between init and close. */
    private fun crashedFile(name: String, frames: Int): File {
        val file = newFile(name)
        val pcm = PcmAudioUtils.floatToPcm16(sine(frames))
        file.writeBytes(WavWriter.buildHeader(16_000, 1, dataBytes = 0L) + pcm)
        return file
    }

    private fun healthyFile(name: String, frames: Int): File {
        val file = newFile(name)
        WavWriter(file).use { it.append(sine(frames)) }
        return file
    }

    // ---------------------------------------------------------------- tests

    @Test
    fun `needsRepairDetectsHeaderThatWasNeverPatched`() {
        val file = crashedFile("crash.wav", frames = 320)
        assertTrue(WavRepair.needsRepair(file))
    }

    @Test
    fun `repairInPlaceRestoresAReadableFile`() {
        val file = crashedFile("crash.wav", frames = 320)
        val original = sine(320)

        assertTrue(WavRepair.repairInPlace(file))
        // Idempotent: a second pass finds nothing to do.
        assertFalse(WavRepair.needsRepair(file))
        assertFalse(WavRepair.repairInPlace(file))

        val h = WavFileReader.readHeader(file)
        assertEquals(320L, h.frameCount)
        assertEquals(640L, h.dataBytes)
        assertEquals(16_000, h.sampleRate)

        val (decoded, _) = WavFileReader.read(file)
        assertEquals(original.size, decoded.size)
        for (i in original.indices) {
            assertTrue("sample $i drifted", abs(decoded[i] - original[i]) <= 1.0f / 16_000f)
        }
    }

    @Test
    fun `healthyFileIsLeftUntouched`() {
        val file = healthyFile("healthy.wav", frames = 160)
        val before = file.readBytes()

        assertFalse(WavRepair.needsRepair(file))
        assertFalse(WavRepair.repairInPlace(file))
        assertArrayEquals("repair must not rewrite a consistent file", before, file.readBytes())
    }

    @Test
    fun `repairRecoversATruncatedTail`() {
        val file = healthyFile("truncated.wav", frames = 10)
        // Simulate a partial final flush: header still claims 20 data bytes,
        // but only 13 made it to disk.
        RandomAccessFile(file, "rw").use { it.setLength(WavWriter.HEADER_SIZE + 13L) }

        assertTrue(WavRepair.needsRepair(file))
        assertTrue(WavRepair.repairInPlace(file))

        val h = WavFileReader.readHeader(file)
        assertEquals(13L, h.dataBytes)
        assertEquals(6L, h.frameCount) // 13 / 2 whole frames

        val (decoded, _) = WavFileReader.read(file)
        assertEquals(6, decoded.size)
        assertFalse(WavRepair.needsRepair(file))
    }

    @Test
    fun `nonWavTinyAndMissingFilesAreRejected`() {
        val tiny = newFile("tiny.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertFalse(WavRepair.needsRepair(tiny))
        assertFalse(WavRepair.repairInPlace(tiny))

        // RIFF magic but no WAVE form: repair must refuse, not "fix" it.
        val junk = newFile("junk.bin").apply {
            writeBytes("RIFF".toByteArray(Charsets.US_ASCII) + ByteArray(60))
        }
        assertFalse(WavRepair.repairInPlace(junk))
        assertEquals(64L, junk.length()) // untouched

        val missing = File(tmp.root, "does-not-exist.wav")
        assertFalse(WavRepair.needsRepair(missing))
        assertFalse(WavRepair.repairInPlace(missing))
    }
}
