package com.echonote.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.echonote.app.EchoNoteApplication
import com.echonote.app.data.db.CaptureSource
import com.echonote.app.data.storage.RecordingStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable

/**
 * What a single `AudioRecord` source actually did once started. Returned to the
 * Feasibility Lab so the app can *report* real capture behaviour instead of
 * assuming it.
 */
data class CaptureProbe(
    val requested: CaptureSource,
    /** The source the OS actually granted (may differ on some OEM builds). */
    val actualSource: Int,
    val started: Boolean,
    val errorMessage: String?,
    val sampleRate: Int,
    val channelCount: Int,
    val echoCancelerApplied: Boolean,
    val noiseSuppressorApplied: Boolean,
    /** RMS of the first ~0.5 s of audio, in dBFS. */
    val initialDbFs: Float?,
    /** True when the stream returned audio but every sample was digital silence —
     *  the signature of a blocked/zeroed capture path. */
    val appearedSilent: Boolean,
    /** Recommendation for this source on this device. */
    val verdict: Verdict,
) {
    enum class Verdict {
        /** Produces usable audio. */
        WORKS,
        /** Starts but yields only zeros — the source is blocked on this device. */
        BLOCKED,
        /** `AudioRecord` threw; the source is unavailable. */
        UNAVAILABLE,
    }

    /** One-line human summary, used verbatim in the feasibility report. */
    fun summary(): String {
        val rms = initialDbFs?.let { String.format(java.util.Locale.US, "%.1f dBFS", it) } ?: "n/a"
        return when (verdict) {
            Verdict.WORKS -> "✅ 可用 — $rms"
            Verdict.BLOCKED -> "⛔ 只有数字静音 — 录到全 0（来源被系统或应用禁止）"
            Verdict.UNAVAILABLE -> "❌ 无法启动 — ${errorMessage ?: "未知"}"
        }
    }
}

/**
 * Microphone capture engine.
 *
 * Starts an [AudioRecord], feeds it to a [WavWriter], and exposes a live waveform.
 *
 * **The choice of source is the crux of this entire project.** `VOICE_CALL`,
 * `VOICE_UPLINK` and `VOICE_DOWNLINK` are gated behind `CAPTURE_AUDIO_OUTPUT`
 * (AOSP `signature|privileged|role`) and a normal app cannot be granted them, so
 * they are deliberately *not* offered as a recording option — only as a probe in
 * the Feasibility Lab, which demonstrates their unavailability rather than
 * promising them. See TECHNICAL_FEASIBILITY.md.
 */
class MicCaptureEngine(
    private val storage: RecordingStorage,
    private val aecEnabled: Boolean,
    private val nsEnabled: Boolean,
) : Closeable {

    private val _waveform = MutableStateFlow(ByteArray(0))
    /** Normalised peak buckets for the live waveform, one byte per column. */
    val waveform: StateFlow<ByteArray> = _waveform.asStateFlow()

    private val _levelDb = MutableStateFlow(Float.NEGATIVE_INFINITY)
    /** Current input level in dBFS, for the recording screen's meter. */
    val levelDb: StateFlow<Float> = _levelDb.asStateFlow()

    private var record: AudioRecord? = null
    private var writer: WavWriter? = null
    private var thread: Thread? = null
    private var recording = false
    private var paused = false
    private var bytesWritten = 0L
    private var startedAtMs = 0L
    private var actualSource: CaptureSource = CaptureSource.MIC

    var lastProbe: CaptureProbe? = null
        private set

    val isRecording: Boolean get() = recording
    val isPaused: Boolean get() = paused

    /** Duration of audio actually captured (excludes pauses). */
    val durationMs: Long get() = bytesWritten * 1000L / (AudioSpec.SAMPLE_RATE * AudioSpec.BYTES_PER_SAMPLE)

    val bytesCaptured: Long get() = bytesWritten

    /**
     * Validates a source without writing any audio. Returns the probe and does not
     * leave an [AudioRecord] open — safe to call from the Feasibility Lab for every
     * source in sequence.
     */
    fun probe(source: CaptureSource, durationMs: Long = 600): CaptureProbe {
        return try {
            val audioFormat = AudioFormat.Builder()
                .setSampleRate(AudioSpec.SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val minBuffer = AudioRecord.getMinBufferSize(
                AudioSpec.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBuffer, AudioSpec.READ_CHUNK_FRAMES * 2 * 2)
            val ar = AudioRecord(toMediaAudioSource(source), AudioSpec.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)

            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                ar.release()
                return CaptureProbe(
                    requested = source, actualSource = source.mediaAudioSource,
                    started = false, errorMessage = "STATE_UNINITIALIZED",
                    sampleRate = AudioSpec.SAMPLE_RATE, channelCount = 1,
                    echoCancelerApplied = false, noiseSuppressorApplied = false,
                    initialDbFs = null, appearedSilent = false,
                    verdict = CaptureProbe.Verdict.UNAVAILABLE,
                )
            }

            applyEffects(ar, aecEnabled, nsEnabled, effectFlags(source))
            ar.startRecording()

            val probeBytes = (AudioSpec.SAMPLE_RATE.toLong() * 2 * durationMs / 1000).toInt().coerceAtLeast(1024)
            val buf = ByteArray(probeBytes)
            var readTotal = 0
            val deadline = System.currentTimeMillis() + durationMs + 400
            while (readTotal < probeBytes && System.currentTimeMillis() < deadline) {
                val r = ar.read(buf, 0, buf.size)
                if (r > 0) readTotal += r else if (r < 0) break
            }
            ar.stop()
            ar.release()

            val samples = PcmAudioUtils.pcm16ToFloat(buf, readTotal)
            val silent = PcmAudioUtils.isSilent(samples, samples.size, floor = 1e-5f)
            val rms = PcmAudioUtils.rms(samples, samples.size)
            val verdict = when {
                readTotal <= 0 -> CaptureProbe.Verdict.UNAVAILABLE
                silent -> CaptureProbe.Verdict.BLOCKED
                else -> CaptureProbe.Verdict.WORKS
            }
            CaptureProbe(
                requested = source,
                actualSource = source.mediaAudioSource,
                started = true,
                errorMessage = if (readTotal <= 0) "read() returned $readTotal" else null,
                sampleRate = AudioSpec.SAMPLE_RATE,
                channelCount = 1,
                echoCancelerApplied = AcousticEchoCanceler.isAvailable() && aecEnabled,
                noiseSuppressorApplied = NoiseSuppressor.isAvailable() && nsEnabled,
                initialDbFs = if (readTotal > 0) PcmAudioUtils.dbFs(rms) else null,
                appearedSilent = silent,
                verdict = verdict,
            ).also { lastProbe = it }
        } catch (t: Throwable) {
            Log.w(EchoNoteApplication.TAG, "probe($source) failed", t)
            CaptureProbe(
                requested = source, actualSource = source.mediaAudioSource,
                started = false, errorMessage = t.javaClass.simpleName + ": " + t.message,
                sampleRate = AudioSpec.SAMPLE_RATE, channelCount = 1,
                echoCancelerApplied = false, noiseSuppressorApplied = false,
                initialDbFs = null, appearedSilent = false,
                verdict = CaptureProbe.Verdict.UNAVAILABLE,
            ).also { lastProbe = it }
        }
    }

    /**
     * Begins capturing into [wavFile].
     *
     * @return the probe describing what the OS actually granted.
     * @throws SecurityException if RECORD_AUDIO has not been granted.
     */
    @SuppressLint("MissingPermission")
    @Throws(SecurityException::class)
    fun start(wavFile: java.io.File, source: CaptureSource = CaptureSource.VOICE_RECOGNITION): CaptureProbe {
        check(!recording) { "already recording" }

        val minBuffer = AudioRecord.getMinBufferSize(
            AudioSpec.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuffer, AudioSpec.READ_CHUNK_FRAMES * 2 * 2)
        val ar = AudioRecord(
            toMediaAudioSource(source), AudioSpec.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
        )
        require(ar.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord failed to initialise for source=${source.name}"
        }
        applyEffects(ar, aecEnabled, nsEnabled, effectFlags(source))

        val w = WavWriter(wavFile, AudioSpec.SAMPLE_RATE, AudioSpec.CHANNELS)
        bytesWritten = 0
        startedAtMs = System.currentTimeMillis()
        actualSource = source
        record = ar
        writer = w
        recording = true
        paused = false

        ar.startRecording()
        thread = Thread({
            val buf = ShortArray(AudioSpec.READ_CHUNK_FRAMES)
            val floatBuf = FloatArray(AudioSpec.READ_CHUNK_FRAMES)
            var sinceFlush = 0
            var sinceWave = 0
            val waveBytes = ByteArray(WAVEFORM_COLUMNS)
            var waveIndex = 0
            var windowPeak = 0f

            while (recording) {
                if (paused) {
                    Thread.sleep(20)
                    continue
                }
                val read = record?.read(buf, 0, buf.size) ?: 0
                if (read > 0) {
                    for (i in 0 until read) floatBuf[i] = buf[i] / 32768f
                    w.appendPcm16(encode(buf, read))
                    bytesWritten += read

                    var peak = 0f
                    for (i in 0 until read) {
                        val a = kotlin.math.abs(floatBuf[i])
                        if (a > peak) peak = a
                    }
                    if (peak > windowPeak) windowPeak = peak
                    sinceFlush += read
                    sinceWave += read

                    // ~100 ms per waveform column.
                    if (sinceWave >= AudioSpec.SAMPLE_RATE / WAVEFORM_COLUMNS) {
                        waveBytes[waveIndex] = (windowPeak * 127f).toInt().coerceIn(0, 127).toByte()
                        waveIndex = (waveIndex + 1) % WAVEFORM_COLUMNS
                        sinceWave = 0
                        windowPeak = 0f
                        _waveform.value = rotate(waveBytes, waveIndex)
                        _levelDb.value = PcmAudioUtils.dbFs(peak)
                    }
                    // Flush to disk every ~2 s so a crash costs at most 2 s.
                    if (sinceFlush >= AudioSpec.SAMPLE_RATE * 2) {
                        w.flush()
                        sinceFlush = 0
                    }
                } else if (read < 0) {
                    Log.e(EchoNoteApplication.TAG, "AudioRecord.read error=$read — stopping")
                    break
                }
            }
            w.flush()
            w.close()
            runCatching { record?.stop() }
        }, "echonote-mic").also { it.start() }

        return CaptureProbe(
            requested = source,
            actualSource = source.mediaAudioSource,
            started = true,
            errorMessage = null,
            sampleRate = AudioSpec.SAMPLE_RATE,
            channelCount = 1,
            echoCancelerApplied = aecEnabled && AcousticEchoCanceler.isAvailable(),
            noiseSuppressorApplied = nsEnabled && NoiseSuppressor.isAvailable(),
            initialDbFs = null,
            appearedSilent = false,
            verdict = CaptureProbe.Verdict.WORKS,
        ).also { lastProbe = it }
    }

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    /** Stops capturing, finalises the WAV header, and returns the duration. */
    fun stop(): Long {
        if (!recording) return durationMs
        recording = false
        paused = false
        thread?.join(2000)
        thread = null
        record?.release()
        record = null
        writer = null
        _waveform.value = ByteArray(0)
        _levelDb.value = Float.NEGATIVE_INFINITY
        return durationMs
    }

    override fun close() { stop() }

    // ------------------------------------------------------------- helpers

    private fun rotate(src: ByteArray, start: Int): ByteArray {
        val out = ByteArray(src.size)
        for (i in src.indices) out[i] = src[(start + i) % src.size]
        return out
    }

    private fun encode(shorts: ShortArray, count: Int): ByteArray {
        val out = ByteArray(count * 2)
        for (i in 0 until count) {
            out[i * 2] = (shorts[i].toInt() and 0xFF).toByte()
            out[i * 2 + 1] = (shorts[i].toInt() shr 8).toByte()
        }
        return out
    }

    /**
     * Which effects this source should get.
     *
     * Applying AEC/NS to `VOICE_COMMUNICATION` is the interesting case: the OS has
     * already run its own echo canceller on that path, so a second instance is
     * usually redundant but harmless; applying them to `MIC` on the *other* hand
     * genuinely improves the "speaker + microphone" fallback where the phone's own
     * speaker plays into the mic.
     */
    private fun effectFlags(source: CaptureSource): Int =
        when (source) {
            CaptureSource.VOICE_COMMUNICATION, CaptureSource.VOICE_RECOGNITION -> EFFECT_ON
            else -> EFFECT_ON
        }

    private fun applyEffects(
        ar: AudioRecord,
        aec: Boolean,
        ns: Boolean,
        unused: Int,
    ) {
        if (aec && AcousticEchoCanceler.isAvailable()) {
            runCatching {
                AcousticEchoCanceler.create(ar.audioSessionId)?.enabled = true
            }.onFailure { Log.w(EchoNoteApplication.TAG, "AEC unavailable", it) }
        }
        if (ns && NoiseSuppressor.isAvailable()) {
            runCatching {
                NoiseSuppressor.create(ar.audioSessionId)?.enabled = true
            }.onFailure { Log.w(EchoNoteApplication.TAG, "NS unavailable", it) }
        }
    }

    private fun toMediaAudioSource(source: CaptureSource): Int = when (source) {
        CaptureSource.MIC -> MediaRecorder.AudioSource.MIC
        CaptureSource.VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        CaptureSource.VOICE_COMMUNICATION -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        CaptureSource.CAMCORDER -> MediaRecorder.AudioSource.CAMCORDER
        CaptureSource.DEFAULT -> MediaRecorder.AudioSource.DEFAULT
        // UNPROCESSED has no MediaRecorder.AudioSource equivalent (it is an
        // AudioRecord-only source, API 24+); fall back to MIC for the probe.
        CaptureSource.UNPROCESSED -> MediaRecorder.AudioSource.MIC
        else -> MediaRecorder.AudioSource.MIC
    }

    private val CaptureSource.mediaAudioSource: Int get() = toMediaAudioSource(this)

    /**
     * The sources the Feasibility Lab tests, in order.
     *
     * **Excluded on purpose:** `VOICE_CALL`, `VOICE_UPLINK`, `VOICE_DOWNLINK`,
     * `VOICE_COMMUNICATION_RX/TX`, `REMOTE_SUBMIX`. Each requires
     * `CAPTURE_AUDIO_OUTPUT`, which AOSP marks `signature|privileged|role`. Listing
     * them as if a normal app could use them would be dishonest, so the lab tests
     * the sources a normal app *is* allowed to request, and the report explains why
     * the call-specific ones are absent.
     */
    companion object {
        const val WAVEFORM_COLUMNS = 64
        private const val EFFECT_ON = 1

        val probeSources: List<CaptureSource> = listOf(
            CaptureSource.MIC,
            CaptureSource.VOICE_RECOGNITION,
            CaptureSource.VOICE_COMMUNICATION,
            CaptureSource.CAMCORDER,
            CaptureSource.DEFAULT,
        )
    }
}
