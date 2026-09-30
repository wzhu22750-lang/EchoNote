package com.echonote.app.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Level maths shared by the recorder, the waveform view and the Feasibility Lab.
 *
 * Everything here is pure Kotlin on purpose: it is the part of the audio path
 * that can be verified by JVM unit tests with no device attached.
 */
object PcmAudioUtils {

    /** Root-mean-square of `samples[0 until count]`, in the normalised [-1, 1] domain. */
    fun rms(samples: FloatArray, count: Int = samples.size): Float {
        if (count <= 0) return 0f
        var sum = 0.0
        for (i in 0 until count) {
            val s = samples[i]
            sum += s.toDouble() * s
        }
        return sqrt(sum / count).toFloat()
    }

    /** Largest magnitude sample. */
    fun peak(samples: FloatArray, count: Int = samples.size): Float {
        var p = 0f
        for (i in 0 until count) {
            val a = abs(samples[i])
            if (a > p) p = a
        }
        return p
    }

    /** RMS expressed in dBFS. Returns [SILENCE_DB] for digital silence. */
    fun dbFs(rms: Float): Float =
        if (rms <= 1e-7f) SILENCE_DB else (20f * kotlin.math.log10(rms.toDouble())).toFloat()

    /**
     * Fraction of samples at or beyond full scale. A high value means the capture
     * path is clipping, which is the classic failure mode when a phone is on
     * speaker and the microphone is also picking up its own output.
     */
    fun clippingRatio(samples: FloatArray, count: Int = samples.size, threshold: Float = 0.999f): Float {
        if (count <= 0) return 0f
        var clipped = 0
        for (i in 0 until count) {
            if (abs(samples[i]) >= threshold) clipped++
        }
        return clipped.toFloat() / count
    }

    /** True when the buffer is essentially digital silence (all zeros / denormal). */
    fun isSilent(samples: FloatArray, count: Int = samples.size, floor: Float = 1e-5f): Boolean {
        for (i in 0 until count) {
            if (abs(samples[i]) > floor) return false
        }
        return true
    }

    /**
     * Downsamples interleaved multi-channel audio to a single mono channel by
     * averaging. Used for imported files that are stereo or 5.1.
     */
    fun downmixToMono(interleaved: FloatArray, channels: Int): FloatArray {
        if (channels <= 1) return interleaved
        val frames = interleaved.size / channels
        val out = FloatArray(frames)
        for (frame in 0 until frames) {
            var sum = 0f
            val base = frame * channels
            for (c in 0 until channels) sum += interleaved[base + c]
            out[frame] = sum / channels
        }
        return out
    }

    /** PCM16 little-endian -> normalised floats. */
    fun pcm16ToFloat(bytes: ByteArray, length: Int = bytes.size): FloatArray {
        val samples = length / 2
        val out = FloatArray(samples)
        var i = 0
        var o = 0
        while (i + 1 < length) {
            val lo = bytes[i].toInt() and 0xFF
            val hi = bytes[i + 1].toInt()
            val v = ((hi shl 8) or lo).toShort()
            out[o++] = v / 32768f
            i += 2
        }
        return out
    }

    /** Normalised floats -> PCM16 little-endian. */
    fun floatToPcm16(samples: FloatArray, count: Int = samples.size): ByteArray {
        val out = ByteArray(count * 2)
        for (i in 0 until count) {
            out[i * 2] = toPcm16(samples[i]).toInt().and(0xFF).toByte()
            out[i * 2 + 1] = (toPcm16(samples[i]).toInt() shr 8).and(0xFF).toByte()
        }
        return out
    }

    /** Rounds and clamps to the signed 16-bit range. */
    fun toPcm16(sample: Float): Short {
        val scaled = (sample * 32767f).toInt()
        return when {
            scaled > Short.MAX_VALUE -> Short.MAX_VALUE
            scaled < Short.MIN_VALUE -> Short.MIN_VALUE
            else -> scaled.toShort()
        }
    }

    /**
     * Peak-normalises a segment to [target] peak. Used to compensate for the very
     * different levels between the local microphone and playback-capture audio
     * when the two are summed.
     */
    fun normalize(samples: FloatArray, target: Float = 0.89f): FloatArray {
        val p = peak(samples)
        if (p <= 1e-6f) return samples
        val gain = target / p
        if (gain > MAX_NORMALISE_GAIN) {
            // Do not amplify near-silence into noise.
            return FloatArray(samples.size) { samples[it] * MAX_NORMALISE_GAIN }
        }
        return FloatArray(samples.size) { (samples[it] * gain).coerceIn(-1f, 1f) }
    }

    /** Stride-decimated peak envelope, for drawing a waveform without per-pixel work. */
    fun envelope(samples: FloatArray, buckets: Int): FloatArray {
        if (buckets <= 0 || samples.isEmpty()) return FloatArray(0)
        val out = FloatArray(buckets)
        val per = max(1, samples.size / buckets)
        for (b in 0 until buckets) {
            val start = b * per
            val end = minOf(samples.size, start + per)
            var p = 0f
            for (i in start until end) {
                val a = abs(samples[i])
                if (a > p) p = a
            }
            out[b] = p
        }
        return out
    }

    const val SILENCE_DB = -100f
    private const val MAX_NORMALISE_GAIN = 8f
}
