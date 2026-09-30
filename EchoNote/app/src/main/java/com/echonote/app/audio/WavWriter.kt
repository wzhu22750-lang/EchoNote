package com.echonote.app.audio

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.io.IOException

/**
 * Streaming writer for 16-bit PCM WAV files.
 *
 * The RIFF header cannot be written correctly until the total length is known, so
 * the header is emitted as a placeholder and patched in [close]. Between those two
 * points [flush] pushes data to disk, which means a process kill loses at most the
 * buffered tail — and [WavRepair] can then rebuild the header from the file length
 * (see the "App 被杀 / 断电" case in TECHNICAL_FEASIBILITY.md).
 */
class WavWriter(
    private val file: File,
    private val sampleRate: Int = AudioSpec.SAMPLE_RATE,
    private val channels: Int = AudioSpec.CHANNELS,
    bufferBytes: Int = 64 * 1024,
) : Closeable {

    private val stream = BufferedOutputStream(FileOutputStream(file), bufferBytes)
    private var framesWritten = 0L
    private var closed = false

    init {
        require(channels in 1..8) { "unsupported channel count: $channels" }
        stream.write(buildHeader(sampleRate, channels, dataBytes = 0L))
    }

    /** Number of PCM frames committed so far. */
    val frames: Long get() = framesWritten

    /** Duration of the material written so far, in milliseconds. */
    val durationMs: Long get() = framesWritten * 1000L / sampleRate

    /** Appends normalised mono/stereo floats, converting to PCM16. */
    fun append(samples: FloatArray, count: Int = samples.size) {
        check(!closed) { "WavWriter already closed" }
        if (count <= 0) return
        stream.write(PcmAudioUtils.floatToPcm16(samples, count))
        framesWritten += count / channels
    }

    /** Appends already-encoded little-endian PCM16 bytes. */
    fun appendPcm16(bytes: ByteArray, count: Int = bytes.size) {
        check(!closed) { "WavWriter already closed" }
        if (count <= 0) return
        stream.write(bytes, 0, count)
        framesWritten += count / (AudioSpec.BYTES_PER_SAMPLE * channels)
    }

    /**
     * Pushes buffered audio to disk. Called periodically by the recorder so a
     * crash cannot cost more than the flush interval.
     */
    fun flush() {
        if (!closed) stream.flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        stream.flush()
        stream.close()
        // Patch the two length fields now that they are known.
        RandomAccessFile(file, "rw").use { raf ->
            val dataBytes = framesWritten * channels * AudioSpec.BYTES_PER_SAMPLE
            raf.seek(4) // RIFF chunk size = 36 + data size
            raf.write(le32(36 + dataBytes))
            raf.seek(40) // data chunk size
            raf.write(le32(dataBytes))
        }
    }

    companion object {
        const val HEADER_SIZE = 44

        /** Canonical 44-byte WAV header for 16-bit PCM. */
        fun buildHeader(sampleRate: Int, channels: Int, dataBytes: Long): ByteArray {
            val byteRate = sampleRate * channels * AudioSpec.BYTES_PER_SAMPLE
            val blockAlign = channels * AudioSpec.BYTES_PER_SAMPLE
            val header = ByteArray(HEADER_SIZE)
            var p = 0
            fun ascii(s: String) { for (c in s) header[p++] = c.code.toByte() }
            fun u32(v: Long) { le32(v).copyInto(header, p); p += 4 }
            fun u16(v: Int) { header[p++] = (v and 0xFF).toByte(); header[p++] = ((v shr 8) and 0xFF).toByte() }

            ascii("RIFF")
            u32(36 + dataBytes)
            ascii("WAVE")
            ascii("fmt ")
            u32(16)              // PCM fmt chunk size
            u16(1)               // WAVE_FORMAT_PCM
            u16(channels)
            u32(sampleRate.toLong())
            u32(byteRate.toLong())
            u16(blockAlign)
            u16(AudioSpec.BITS_PER_SAMPLE)
            ascii("data")
            u32(dataBytes)
            return header
        }

        private fun le32(value: Long): ByteArray = byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte(),
        )
    }
}

/**
 * Repairs WAV files whose header was never patched because the recording process
 * died. Recovers the "app killed / battery died mid-recording" case.
 */
object WavRepair {

    /**
     * @return true when the file was actually modified.
     */
    fun repairInPlace(file: File): Boolean {
        if (!file.isFile || file.length() <= WavWriter.HEADER_SIZE) return false
        return try {
            RandomAccessFile(file, "rw").use { raf ->
                val header = ByteArray(WavWriter.HEADER_SIZE)
                raf.readFully(header)
                if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF") return false
                if (String(header, 8, 4, Charsets.US_ASCII) != "WAVE") return false

                val declared = readLe32(header, 40)
                val actual = file.length() - WavWriter.HEADER_SIZE
                if (declared == actual) return false

                val dataBytes = minOf(actual, 0xFFFF_FFFFL)
                raf.seek(4)
                raf.write(le32(36 + dataBytes))
                raf.seek(40)
                raf.write(le32(dataBytes))
                true
            }
        } catch (_: IOException) {
            false
        }
    }

    /** True when the header's data length disagrees with the file length. */
    fun needsRepair(file: File): Boolean {
        if (!file.isFile || file.length() <= WavWriter.HEADER_SIZE) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(WavWriter.HEADER_SIZE)
                raf.readFully(header)
                if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF") return false
                readLe32(header, 40) != file.length() - WavWriter.HEADER_SIZE
            }
        } catch (_: IOException) {
            false
        }
    }

    private fun readLe32(b: ByteArray, offset: Int): Long =
        (b[offset].toLong() and 0xFF) or
            ((b[offset + 1].toLong() and 0xFF) shl 8) or
            ((b[offset + 2].toLong() and 0xFF) shl 16) or
            ((b[offset + 3].toLong() and 0xFF) shl 24)

    private fun le32(value: Long): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )
}
