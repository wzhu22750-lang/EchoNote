package com.echonote.app.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.echonote.app.EchoNoteApplication
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pull-based decoded audio source. Every implementation yields **mono** floats at
 * its own native sample rate; resampling to the pipeline rate happens downstream
 * so that each stage has one job.
 */
interface AudioChunkSource : Closeable {
    val sourceSampleRate: Int
    val sourceChannels: Int
    /** Best-effort total frame count, for progress reporting. -1 when unknown. */
    val totalFrames: Long

    /** @return the next mono chunk, or null at end of stream. */
    fun readChunk(): FloatArray?
}

/**
 * Opens any audio file the platform can decode and exposes it as 16-bit-or-float
 * PCM chunks.
 *
 * WAV goes through [WavFileReader] rather than `MediaExtractor` because the
 * hand-written parser is stricter about the real-world WAV variants a phone
 * recorder produces, and because it avoids a codec round-trip for the app's own
 * recordings (the common case). MP3 / M4A / AAC / OGG / FLAC / Opus go through
 * `MediaExtractor` + `MediaCodec`, which is what makes the "import existing audio"
 * fallback work for files exported from WeChat or any other recorder.
 */
class AudioFileDecoder(private val cacheDir: File) {

    /**
     * @throws IOException when the file is not decodable.
     */
    fun open(file: File): AudioChunkSource {
        if (!file.isFile) throw IOException("not a file: ${file.absolutePath}")
        if (isWave(file)) {
            return runCatching { WavChunkSource(file) }
                .getOrElse { error ->
                    Log.w(EchoNoteApplication.TAG, "WAV parse failed, falling back to codec", error)
                    CodecChunkSource(file)
                }
        }
        return CodecChunkSource(file)
    }

    private fun isWave(file: File): Boolean {
        if (file.extension.equals("wav", ignoreCase = true) ||
            file.extension.equals("wave", ignoreCase = true)
        ) {
            return true
        }
        // Sniff the magic bytes: some exporters give .m4a-style names to RIFF data.
        return runCatching {
            FileInputStream(file).use { input ->
                val magic = ByteArray(4)
                input.read(magic) == 4 && String(magic, Charsets.US_ASCII) == "RIFF"
            }
        }.getOrDefault(false)
    }
}

/** Streams a RIFF/WAVE file, folding multi-channel data to mono. */
internal class WavChunkSource(private val file: File) : AudioChunkSource {

    private val header = WavFileReader.readHeader(file)
    private val input = FileInputStream(file)
    private val raw = ByteArray(CHUNK_FRAMES * header.bytesPerFrame)
    private var remaining = header.dataBytes

    override val sourceSampleRate: Int = header.sampleRate
    override val sourceChannels: Int = header.channels
    override val totalFrames: Long = header.frameCount

    init {
        input.channel.position(header.dataOffset)
    }

    override fun readChunk(): FloatArray? {
        if (remaining <= 0) return null
        val want = minOf(remaining, raw.size.toLong()).toInt()
        val got = readAtMost(want)
        if (got <= 0) return null
        remaining -= got

        val frames = got / header.bytesPerFrame
        if (frames <= 0) return null

        val channels = header.channels
        val bytesPerSample = header.bitsPerSample / 8
        val isFloat = header.formatTag == 3
        val out = FloatArray(frames)
        for (frame in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                val idx = frame * header.bytesPerFrame + c * bytesPerSample
                if (idx + bytesPerSample > got) break
                sum += decodeSample(raw, idx, header.bitsPerSample, isFloat)
            }
            out[frame] = sum / channels
        }
        return out
    }

    private fun readAtMost(want: Int): Int {
        var read = 0
        while (read < want) {
            val n = input.read(raw, read, want - read)
            if (n < 0) break
            read += n
        }
        return read
    }

    private fun decodeSample(b: ByteArray, i: Int, bits: Int, isFloat: Boolean): Float = when {
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

    override fun close() {
        runCatching { input.close() }
    }

    private companion object {
        const val CHUNK_FRAMES = 32_768
    }
}

/**
 * Decodes a compressed audio file with `MediaExtractor` + `MediaCodec`.
 *
 * Handles both 16-bit integer and float PCM output, and tolerates a mid-stream
 * format change (which some AAC encoders emit for the first frame).
 */
internal class CodecChunkSource(private val file: File) : AudioChunkSource {

    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    private var trackIndex = -1

    private var sampleRate = 16_000
    private var channels = 1
    private var pcmIsFloat = false

    private var inputDone = false
    private var outputDone = false
    private var idlePolls = 0
    private var pending = FloatArray(0)
    private var pendingOffset = 0

    private val bufferInfo = MediaCodec.BufferInfo()

    override val sourceSampleRate: Int get() = sampleRate
    override val sourceChannels: Int get() = channels
    override val totalFrames: Long
        get() = runCatching {
            val durationUs = extractor.getTrackFormat(trackIndex).getLong(MediaFormat.KEY_DURATION)
            durationUs / 1_000_000.0 * sampleRate
        }.getOrDefault(-1.0).toLong()

    init {
        extractor.setDataSource(file.absolutePath)
        trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index)
                .getString(MediaFormat.KEY_MIME)
                ?.startsWith("audio/") == true
        } ?: throw IOException("no audio track in ${file.name}")

        val format = extractor.getTrackFormat(trackIndex)
        extractor.selectTrack(trackIndex)

        sampleRate = format.getIntOr(MediaFormat.KEY_SAMPLE_RATE, 16_000)
        channels = format.getIntOr(MediaFormat.KEY_CHANNEL_COUNT, 1)
        pcmIsFloat = format.getIntOr(MediaFormat.KEY_PCM_ENCODING, 0) ==
            android.media.AudioFormat.ENCODING_PCM_FLOAT

        val mime = format.getString(MediaFormat.KEY_MIME)
            ?: throw IOException("audio track has no MIME type")
        codec = MediaCodec.createDecoderByType(mime).apply {
            configure(format, null, null, 0)
            start()
        }
    }

    override fun readChunk(): FloatArray? {
        val decoder = codec ?: return null
        while (true) {
            // Drain whatever is left in the current decoded buffer first.
            if (pendingOffset < pending.size) {
                val end = minOf(pending.size, pendingOffset + CHUNK_FRAMES)
                val out = pending.copyOfRange(pendingOffset, end)
                pendingOffset = end
                return out
            }

            if (outputDone) return null

            if (!inputDone) {
                val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val buffer = decoder.getInputBuffer(inIndex)
                        ?: throw IOException("decoder returned a null input buffer")
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(
                            inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            when (val outIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // Some encoders never set BUFFER_FLAG_END_OF_STREAM on the output
                    // side. If input is finished and the decoder stays idle for a
                    // while, treat the stream as complete rather than spinning.
                    if (inputDone) {
                        idlePolls++
                        if (idlePolls > MAX_IDLE_POLLS) {
                            outputDone = true
                            return null
                        }
                    }
                }

                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = decoder.outputFormat
                    sampleRate = format.getIntOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                    channels = format.getIntOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                    pcmIsFloat = format.getIntOr(MediaFormat.KEY_PCM_ENCODING, 0) ==
                        android.media.AudioFormat.ENCODING_PCM_FLOAT
                }

                else -> {
                    if (outIndex >= 0) {
                        idlePolls = 0
                        val buffer = decoder.getOutputBuffer(outIndex)
                        val flags = bufferInfo.flags
                        if (buffer != null && bufferInfo.size > 0) {
                            pending = toMono(buffer, bufferInfo)
                            pendingOffset = 0
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        }
    }

    /** Converts one decoder output buffer into mono floats. */
    private fun toMono(buffer: ByteBuffer, info: MediaCodec.BufferInfo): FloatArray {
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val ch = channels.coerceAtLeast(1)

        if (pcmIsFloat) {
            val floats = buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
            val frames = floats.remaining() / ch
            val out = FloatArray(frames)
            for (frame in 0 until frames) {
                var sum = 0f
                for (c in 0 until ch) if (floats.hasRemaining()) sum += floats.get()
                out[frame] = sum / ch
            }
            return out
        }

        val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
        val frames = shorts.remaining() / ch
        val out = FloatArray(frames)
        for (frame in 0 until frames) {
            var sum = 0f
            for (c in 0 until ch) if (shorts.hasRemaining()) sum += shorts.get() / 32768f
            out[frame] = sum / ch
        }
        return out
    }

    override fun close() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { extractor.release() }
        codec = null
    }

    private fun MediaFormat.getIntOr(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val CHUNK_FRAMES = 32_768
        /** ~2 s of 10 ms polls with no output and no input left => we are done. */
        const val MAX_IDLE_POLLS = 200
    }
}
