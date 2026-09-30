package com.echonote.app.audio

import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Robust, chunk-oriented WAV reader.
 *
 * Written by hand rather than delegated to [android.media.MediaExtractor] for two
 * reasons: WAV is trivially parseable, and recorders in the wild emit three
 * variants that a naive reader gets wrong — `WAVE_FORMAT_EXTENSIBLE` (fmt tag
 * 0xFFFE), `fmt ` chunks of size 18/40, and `data` chunks that are not the last
 * chunk in the file. This reader handles all three, plus truncated files.
 *
 * Nothing here touches the Android framework, so the parsing logic is covered by
 * JVM unit tests.
 */
object WavFileReader {

    data class WavHeader(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val formatTag: Int,
        val dataOffset: Long,
        val dataBytes: Long,
        val frameCount: Long,
    ) {
        val bytesPerFrame: Int get() = channels * (bitsPerSample / 8)
        val durationMs: Long get() = if (sampleRate > 0) frameCount * 1000L / sampleRate else 0L
    }

    class WavFormatException(message: String) : IOException(message)

    /** Parses just the header. Cheap, and safe for multi-gigabyte files. */
    fun readHeader(file: File): WavHeader {
        FileInputStream(file).use { input ->
            val riff = ByteArray(12)
            readFully(input, riff)
            if (ascii(riff, 0, 4) != "RIFF") throw WavFormatException("not a RIFF file")
            if (ascii(riff, 8, 4) != "WAVE") throw WavFormatException("not a WAVE file")
            return parseChunks(input, file.length())
        }
    }

    private fun parseChunks(input: FileInputStream, fileLength: Long): WavHeader {
        var sampleRate = 0
        var channels = 0
        var bits = 0
        var formatTag = 1
        var dataOffset = -1L
        var dataBytes = -1L

        val chunkHeader = ByteArray(8)
        while (true) {
            val read = input.read(chunkHeader)
            if (read < 8) break
            val id = ascii(chunkHeader, 0, 4)
            val size = le32(chunkHeader, 4)
            val payloadStart = input.channel.position()

            when (id) {
                "fmt " -> {
                    val fmt = ByteArray(minOf(size, 64L).toInt())
                    readFully(input, fmt)
                    formatTag = le16(fmt, 0)
                    channels = le16(fmt, 2)
                    sampleRate = le32(fmt, 4).toInt()
                    bits = le16(fmt, 14)
                    if (formatTag == 0xFFFE && fmt.size >= 26) {
                        // WAVE_FORMAT_EXTENSIBLE stores the real tag in the GUID's
                        // first two bytes.
                        formatTag = le16(fmt, 24)
                    }
                }

                "data" -> {
                    dataOffset = payloadStart
                    // A size of 0 (or the 0xFFFFFFFF streaming sentinel) means
                    // "until end of file".
                    dataBytes = if (size <= 0 || size == 0xFFFF_FFFFL) {
                        fileLength - payloadStart
                    } else {
                        // Clamp: recorders that died mid-write leave a data chunk
                        // that claims more bytes than the file actually holds.
                        minOf(size, fileLength - payloadStart)
                    }
                }

                else -> Unit // LIST, fact, cue, bext … skip
            }

            // Chunks are word-aligned: an odd payload is followed by a pad byte.
            val advance = size + (size and 1L)
            val next = payloadStart + advance
            if (id == "data") break
            if (next >= fileLength) break
            input.channel.position(next)
        }

        if (sampleRate <= 0 || channels <= 0 || bits <= 0) {
            throw WavFormatException("missing or invalid fmt chunk")
        }
        if (dataOffset < 0) throw WavFormatException("missing data chunk")

        val bytesPerFrame = channels * (bits / 8)
        val frameCount = if (bytesPerFrame > 0) dataBytes / bytesPerFrame else 0L
        return WavHeader(
            sampleRate = sampleRate,
            channels = channels,
            bitsPerSample = bits,
            formatTag = formatTag,
            dataOffset = dataOffset,
            dataBytes = dataBytes,
            frameCount = frameCount,
        )
    }

    /**
     * Streams the file as mono floats at the file's own sample rate, invoking
     * [onChunk] for each block of at most [chunkFrames] frames.
     *
     * Streaming keeps peak memory bounded: a one-hour 48 kHz stereo import is
     * ~690 MB on disk and would never fit in a phone heap as one FloatArray, but
     * costs only a few hundred kilobytes here.
     */
    fun streamMono(
        file: File,
        header: WavHeader,
        chunkFrames: Int = 65_536,
        onChunk: (FloatArray, Int) -> Unit,
    ) {
        val bytesPerFrame = header.bytesPerFrame
        if (bytesPerFrame <= 0) throw WavFormatException("invalid frame size")
        val bytesPerChunk = chunkFrames * bytesPerFrame

        FileInputStream(file).use { input ->
            input.channel.position(header.dataOffset)
            val raw = ByteArray(bytesPerChunk)
            var remaining = header.dataBytes

            while (remaining > 0) {
                val want = minOf(remaining, raw.size.toLong()).toInt()
                val got = readAtMost(input, raw, want)
                if (got <= 0) break
                remaining -= got

                val frames = got / bytesPerFrame
                if (frames <= 0) break
                onChunk(decodeFrames(raw, got, header, frames), frames)
            }
        }
    }

    /** One-shot convenience, used by tests and by short files (< ~5 minutes). */
    fun read(file: File): Pair<FloatArray, Int> {
        val header = readHeader(file)
        if (header.frameCount > MAX_IN_MEMORY_FRAMES) {
            throw WavFormatException(
                "file too long for one-shot read (${header.frameCount} frames); use streamMono"
            )
        }
        val out = FloatArray(header.frameCount.toInt())
        var offset = 0
        streamMono(file, header) { chunk, count ->
            if (offset + count <= out.size) {
                chunk.copyInto(out, offset, 0, count)
                offset += count
            }
        }
        return (if (offset == out.size) out else out.copyOf(offset)) to header.sampleRate
    }

    /** ~230 MB of float samples — the practical ceiling for a single allocation. */
    private const val MAX_IN_MEMORY_FRAMES = 57_600_000L

    /** Decodes [frames] interleaved frames out of [raw] and averages to mono. */
    private fun decodeFrames(
        raw: ByteArray,
        length: Int,
        header: WavHeader,
        frames: Int,
    ): FloatArray {
        val channels = header.channels
        val out = FloatArray(frames)
        val bpf = header.bytesPerFrame
        val isFloat = header.formatTag == 3
        val bytesPerSample = header.bitsPerSample / 8

        for (frame in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                val idx = frame * bpf + c * bytesPerSample
                if (idx + bytesPerSample > length) break
                sum += readSample(raw, idx, header.bitsPerSample, isFloat)
            }
            out[frame] = sum / channels
        }
        return out
    }

    private fun readSample(b: ByteArray, i: Int, bits: Int, isFloat: Boolean): Float = when {
        isFloat && bits == 32 -> Float.fromBits(
            (b[i].toInt() and 0xFF) or
                ((b[i + 1].toInt() and 0xFF) shl 8) or
                ((b[i + 2].toInt() and 0xFF) shl 16) or
                ((b[i + 3].toInt() and 0xFF) shl 24)
        )

        bits == 8 -> ((b[i].toInt() and 0xFF) - 128) / 128f

        bits == 16 -> (((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xFF)).toShort()) / 32768f

        bits == 24 -> {
            val v = (b[i].toInt() and 0xFF) or
                ((b[i + 1].toInt() and 0xFF) shl 8) or
                (b[i + 2].toInt() shl 16)
            v / 8_388_608f
        }

        bits == 32 -> {
            val v = (b[i].toInt() and 0xFF).toLong() or
                ((b[i + 1].toLong() and 0xFF) shl 8) or
                ((b[i + 2].toLong() and 0xFF) shl 16) or
                ((b[i + 3].toLong() and 0xFF) shl 24)
            v.toInt() / 2_147_483_648f
        }

        else -> 0f
    }

    // ---- small helpers ----------------------------------------------------

    private fun readFully(input: FileInputStream, buf: ByteArray) {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) throw WavFormatException("unexpected end of file")
            read += n
        }
    }

    private fun readAtMost(input: FileInputStream, buf: ByteArray, want: Int): Int {
        var read = 0
        while (read < want) {
            val n = input.read(buf, read, want - read)
            if (n < 0) break
            read += n
        }
        return read
    }

    private fun ascii(b: ByteArray, offset: Int, length: Int): String =
        String(b, offset, length, Charsets.US_ASCII)

    private fun le16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or
            ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or
            ((b[o + 3].toLong() and 0xFF) shl 24)
}
