package com.echonote.app.transcription

import android.util.Log
import com.echonote.app.EchoNoteApplication
import com.echonote.app.ai.AsrEngine
import com.echonote.app.ai.ModelResolver
import com.echonote.app.ai.ModelManager
import com.echonote.app.ai.SpeakerClusterer
import com.echonote.app.ai.SpeakerEmbeddingEngine
import com.echonote.app.ai.VadSegmenter
import com.echonote.app.audio.AudioFileDecoder
import com.echonote.app.audio.AudioSpec
import com.echonote.app.audio.PcmAudioUtils
import com.echonote.app.audio.StreamingResampler
import com.echonote.app.data.settings.EchoNoteSettings
import com.echonote.app.domain.TranscriptionProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * The offline audio → transcript pipeline.
 *
 * ```
 * file ─► decode ─► downmix+resample(16k) ─► VAD ─┬─► speaker embedding ─┐
 *                                                 └─► ASR text ──────────┤
 *                                                                        ▼
 *                                                       clustering ─► assembly
 * ```
 *
 * **Streaming by construction.** Audio is never held as one array: chunks flow
 * through the resampler into the VAD, and each speech span is embedded and
 * recognised the moment it closes, then discarded. A one-hour 48 kHz stereo import
 * therefore costs a few hundred kilobytes of audio buffers plus one embedding and
 * one short transcript per turn — not the ~700 MB the raw file would occupy.
 *
 * Everything runs on-device. No stage touches the network.
 */
class TranscriptionPipeline(
    private val decoder: AudioFileDecoder,
    private val modelManager: ModelManager,
) {

    data class Options(
        val enableDiarization: Boolean = true,
        val forcedSpeakerCount: Int = 2,
        val clusteringThreshold: Float = 0.5f,
        val language: String = "auto",
        val useInverseTextNormalization: Boolean = true,
        val asrModelId: String = "asr_sense_voice_small",
        val asrNumThreads: Int = 3,
        val vadThreshold: Float = 0.5f,
        val minSilenceSeconds: Float = 0.35f,
        val maxSpeechSeconds: Float = 12f,
        /** Enrolled owner voiceprint; when present it decides which cluster is "我". */
        val selfEmbedding: FloatArray? = null,
    ) {
        companion object {
            fun from(settings: EchoNoteSettings, selfEmbedding: FloatArray? = null) = Options(
                enableDiarization = settings.enableDiarization,
                forcedSpeakerCount = settings.forcedSpeakerCount,
                clusteringThreshold = settings.clusteringThreshold,
                language = settings.language,
                useInverseTextNormalization = settings.useInverseTextNormalization,
                asrModelId = settings.asrModel.id,
                selfEmbedding = selfEmbedding,
            )
        }
    }

    /** One cluster discovered by the diarizer. */
    data class Cluster(
        val index: Int,
        val centroid: FloatArray?,
        val segmentCount: Int,
        val speechMs: Long,
        /** Mean cosine distance of members to the centroid; lower = tighter. */
        val meanDistance: Float,
    )

    /** Everything measured during a run. Rendered verbatim in the feasibility report. */
    data class Diagnostics(
        val audioDurationMs: Long,
        val speechDurationMs: Long,
        val silenceDurationMs: Long,
        val sourceSampleRate: Int,
        val sourceChannels: Int,
        val vadSpanCount: Int,
        val embeddedSpanCount: Int,
        val rejectedShortSpans: Int,
        val embeddingDimension: Int,
        val asrSpanCount: Int,
        val totalWallMs: Long,
        val asrWallMs: Long,
        val realTimeFactor: Float,
        val clusterCount: Int,
        val silhouette: Float,
        val peakHeapBytes: Long,
        val producedCharacters: Int,
    )

    data class Output(
        val rawAsrSpans: List<TranscriptAssembler.AsrSpan>,
        val speakerSpans: List<TranscriptAssembler.SpeakerSpan>,
        val segments: List<TranscriptAssembler.MergedSegment>,
        val clusters: List<Cluster>,
        val diagnostics: Diagnostics,
    )

    class ModelMissingException(message: String) : IOException(message)

    suspend fun run(
        audioFile: File,
        options: Options,
        onProgress: (TranscriptionProgress) -> Unit = {},
        recordingId: Long = 0L,
    ): Output = withContext(Dispatchers.Default) {
        val startedAt = System.currentTimeMillis()
        val peakHeapStart = usedHeap()

        val vadPath = ModelResolver.vadPath(modelManager)
            ?: throw ModelMissingException("VAD 模型未安装，请先在「模型管理」中下载 Silero VAD。")

        val asrFiles = ModelResolver.asrFiles(modelManager, options.asrModelId)
            ?: throw ModelMissingException("语音识别模型未安装，请先在「模型管理」中下载 ASR 模型。")

        val speakerPath = if (options.enableDiarization) {
            ModelResolver.speakerPath(modelManager)
                ?: throw ModelMissingException("声纹模型未安装，请先在「模型管理」中下载说话人模型，或关闭说话人分离。")
        } else {
            null
        }

        report(onProgress, recordingId, TranscriptionProgress.Stage.DECODING, null, "打开音频文件")

        val source = decoder.open(audioFile)
        val resampler = StreamingResampler(source.sourceSampleRate, AudioSpec.SAMPLE_RATE)
        val vad = VadSegmenter(
            modelPath = vadPath,
            sampleRate = AudioSpec.SAMPLE_RATE,
            threshold = options.vadThreshold,
            minSilenceDuration = options.minSilenceSeconds,
            maxSpeechDuration = options.maxSpeechSeconds,
        )
        val embedder = speakerPath?.let { SpeakerEmbeddingEngine(it) }
        val asr = AsrEngine(
            files = asrFiles,
            request = com.echonote.app.ai.AsrRequest(
                language = options.language,
                useInverseTextNormalization = options.useInverseTextNormalization,
            ),
            numThreads = options.asrNumThreads,
        )

        val asrSpans = ArrayList<TranscriptAssembler.AsrSpan>(256)
        val embeddings = ArrayList<FloatArray?>(256)
        val speechSpans = ArrayList<LongRange>(256)
        val vadRawSpans = ArrayList<LongRange>(256)
        var speechSamples = 0L
        var decodedSamples = 0L
        var asrWallMs = 0L

        // Every completed VAD span is embedded and recognised exactly once, then
        // released. Defined once so the main loop, the resampler tail and the VAD
        // flush cannot drift apart.
        fun handleSegment(startMs: Long, endMs: Long, samples: FloatArray) {
            speechSamples += samples.size
            vadRawSpans += startMs..endMs
            speechSpans += startMs..endMs

            embeddings += embedder?.embed(samples)

            val before = asr.decodeMillis
            val recognized = asr.transcribe(samples)
            asrWallMs += asr.decodeMillis - before

            asrSpans += TranscriptAssembler.AsrSpan(
                startMs = startMs,
                endMs = endMs,
                text = recognized.text,
                tokens = recognized.tokens,
                tokenTimestamps = recognized.timestamps,
                emotion = recognized.emotion,
                event = recognized.event,
            )
        }

        try {
            // ---- stage 1: stream → resample → VAD, embedding and recognising as we go
            val totalFramesGuess = source.totalFrames
            while (true) {
                coroutineContext.ensureActive()
                val chunk = source.readChunk() ?: break
                decodedSamples += chunk.size

                val resampled = resampler.process(chunk)
                if (resampled.isNotEmpty()) {
                    vad.accept(resampled, onSegment = ::handleSegment)
                }
                reportFractionProgress(
                    onProgress, recordingId, decodedSamples, totalFramesGuess, source.sourceSampleRate
                )
            }

            // ---- drain the resampler, then the VAD
            val tail = resampler.flush()
            if (tail.isNotEmpty()) {
                vad.accept(tail, onSegment = ::handleSegment)
            }
            vad.flush(onSegment = ::handleSegment)

            // ---- stage 2: cluster voiceprints
            val (speakerSpans, clusters, silhouette) = if (embedder != null) {
                report(onProgress, recordingId, TranscriptionProgress.Stage.CLUSTERING, null, "聚类说话人")
                clusterSpeakers(
                    embeddings = embeddings,
                    speechSpans = speechSpans,
                    forcedK = options.forcedSpeakerCount,
                    threshold = options.clusteringThreshold,
                    selfEmbedding = options.selfEmbedding,
                )
            } else {
                Triple(emptyList(), emptyList(), 0f)
            }

            // ---- stage 3: align and merge
            report(onProgress, recordingId, TranscriptionProgress.Stage.SAVING, null, "生成逐字稿")
            val merged = TranscriptAssembler.assemble(asrSpans, speakerSpans)

            val audioDurationMs = decodedSamples * 1000L / source.sourceSampleRate
            val diagnostics = Diagnostics(
                audioDurationMs = audioDurationMs,
                speechDurationMs = speechSamples * 1000L / AudioSpec.SAMPLE_RATE,
                silenceDurationMs = (audioDurationMs - speechSamples * 1000L / AudioSpec.SAMPLE_RATE)
                    .coerceAtLeast(0L),
                sourceSampleRate = source.sourceSampleRate,
                sourceChannels = source.sourceChannels,
                vadSpanCount = vadRawSpans.size,
                embeddedSpanCount = embeddings.count { it != null },
                rejectedShortSpans = embedder?.rejectedShortSegments ?: 0,
                embeddingDimension = embedder?.dimension ?: 0,
                asrSpanCount = asrSpans.size,
                totalWallMs = System.currentTimeMillis() - startedAt,
                asrWallMs = asrWallMs,
                realTimeFactor = if (audioDurationMs > 0) {
                    (System.currentTimeMillis() - startedAt).toFloat() / audioDurationMs
                } else 0f,
                clusterCount = clusters.size,
                silhouette = silhouette,
                peakHeapBytes = (usedHeap() - peakHeapStart).coerceAtLeast(0L),
                producedCharacters = merged.sumOf { it.text.length },
            )

            Log.i(
                EchoNoteApplication.TAG,
                "pipeline: ${diagnostics.vadSpanCount} VAD spans, ${diagnostics.clusterCount} speakers, " +
                    "RTF ${"%.2f".format(diagnostics.realTimeFactor)}, ${diagnostics.totalWallMs} ms",
            )

            Output(
                rawAsrSpans = asrSpans,
                speakerSpans = speakerSpans,
                segments = merged,
                clusters = clusters,
                diagnostics = diagnostics,
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } finally {
            runCatching { source.close() }
            runCatching { vad.close() }
            runCatching { embedder?.close() }
            runCatching { asr.close() }
        }
    }

    /**
     * Clusters the per-span voiceprints and converts the result into timed speaker
     * spans.
     *
     * Spans whose embedding was rejected (too short) are attributed to the
     * temporally nearest labelled neighbour when it is within [INHERIT_WINDOW_MS],
     * and left as [TranscriptAssembler.UNKNOWN_SPEAKER] otherwise. That keeps short
     * back-channels ("嗯", "对") attached to the right person without inventing a
     * speaker from nothing.
     */
    private fun clusterSpeakers(
        embeddings: List<FloatArray?>,
        speechSpans: List<LongRange>,
        forcedK: Int,
        threshold: Float,
        selfEmbedding: FloatArray?,
    ): Triple<List<TranscriptAssembler.SpeakerSpan>, List<Cluster>, Float> {
        val present = embeddings.withIndex().filter { it.value != null }
        if (present.isEmpty()) return Triple(emptyList(), emptyList(), 0f)

        val vectors = present.map { it.value!! }
        val result = SpeakerClusterer.cluster(
            embeddings = vectors,
            forcedK = forcedK,
            threshold = threshold,
            selfEmbedding = selfEmbedding,
        )

        // label per original span index; -1 = unattributed
        val labelBySpan = IntArray(embeddings.size) { TranscriptAssembler.UNKNOWN_SPEAKER }
        present.forEachIndexed { i, indexed -> labelBySpan[indexed.index] = result.labels[i] }

        // Inherit the neighbour's speaker for very short unattributed spans.
        for (i in labelBySpan.indices) {
            if (labelBySpan[i] != TranscriptAssembler.UNKNOWN_SPEAKER) continue
            val previous = (i - 1 downTo 0).firstOrNull { labelBySpan[it] != TranscriptAssembler.UNKNOWN_SPEAKER }
            val next = (i + 1 until labelBySpan.size).firstOrNull {
                labelBySpan[it] != TranscriptAssembler.UNKNOWN_SPEAKER
            }
            val previousGap = previous?.let { speechSpans[i].first - speechSpans[it].last } ?: Long.MAX_VALUE
            val nextGap = next?.let { speechSpans[it].first - speechSpans[i].last } ?: Long.MAX_VALUE
            labelBySpan[i] = when {
                previousGap <= INHERIT_WINDOW_MS && previousGap <= nextGap -> labelBySpan[previous!!]
                nextGap <= INHERIT_WINDOW_MS -> labelBySpan[next!!]
                else -> TranscriptAssembler.UNKNOWN_SPEAKER
            }
        }

        val speakerSpans = ArrayList<TranscriptAssembler.SpeakerSpan>(speechSpans.size)
        for (i in speechSpans.indices) {
            val label = labelBySpan[i]
            val span = speechSpans[i]
            val confidence = if (label == TranscriptAssembler.UNKNOWN_SPEAKER) {
                0f
            } else {
                val vectorIndex = present.indexOfFirst { it.index == i }
                if (vectorIndex >= 0) (1f - result.distances[vectorIndex]).coerceIn(0f, 1f) else 0.5f
            }
            speakerSpans += TranscriptAssembler.SpeakerSpan(
                startMs = span.first,
                endMs = span.last,
                speakerIndex = label,
                confidence = confidence,
            )
        }

        // Summarise the clusters for persistence and for the diagnostics screen.
        val clusters = result.centroids.mapIndexed { index, centroid ->
            val members = present.indices.filter { result.labels[it] == index }
            val speechMs = members.sumOf { memberIndex ->
                val span = speechSpans[present[memberIndex].index]
                (span.last - span.first).toLong()
            }
            val meanDistance = if (members.isEmpty()) {
                0f
            } else {
                members.map { result.distances[it] }.average().toFloat()
            }
            Cluster(
                index = index,
                centroid = centroid,
                segmentCount = members.size,
                speechMs = speechMs,
                meanDistance = meanDistance,
            )
        }
        return Triple(speakerSpans, clusters, result.silhouette)
    }

    private fun reportFractionProgress(
        onProgress: (TranscriptionProgress) -> Unit,
        recordingId: Long,
        decodedSamples: Long,
        totalFramesGuess: Long,
        sourceRate: Int,
    ) {
        val fraction = if (totalFramesGuess > 0) {
            (decodedSamples.toFloat() / totalFramesGuess).coerceIn(0f, 1f)
        } else {
            null
        }
        val seconds = decodedSamples / sourceRate
        report(
            onProgress, recordingId, TranscriptionProgress.Stage.VAD, fraction,
            "已处理 ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
        )
    }

    private fun report(
        onProgress: (TranscriptionProgress) -> Unit,
        recordingId: Long,
        stage: TranscriptionProgress.Stage,
        fraction: Float?,
        detail: String,
    ) {
        onProgress(TranscriptionProgress(recordingId, stage, fraction, detail))
    }

    private fun usedHeap(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    /** Amplitude statistics for a decoded file, used by the import preview. */
    suspend fun inspect(file: File): Inspection = withContext(Dispatchers.IO) {
        val source = decoder.open(file)
        try {
            var frames = 0L
            var peak = 0f
            var rmsAccumulator = 0.0
            var chunks = 0
            var silentChunks = 0
            while (true) {
                val chunk = source.readChunk() ?: break
                frames += chunk.size
                chunks++
                val rms = PcmAudioUtils.rms(chunk)
                if (PcmAudioUtils.isSilent(chunk)) silentChunks++ else rmsAccumulator += rms
                val chunkPeak = PcmAudioUtils.peak(chunk)
                if (chunkPeak > peak) peak = chunkPeak
            }
            val loud = (chunks - silentChunks).coerceAtLeast(1)
            Inspection(
                durationMs = frames * 1000L / source.sourceSampleRate,
                sourceSampleRate = source.sourceSampleRate,
                sourceChannels = source.sourceChannels,
                peak = peak,
                meanRms = (rmsAccumulator / loud).toFloat(),
                silentFraction = if (chunks == 0) 1f else silentChunks.toFloat() / chunks,
            )
        } finally {
            runCatching { source.close() }
        }
    }

    data class Inspection(
        val durationMs: Long,
        val sourceSampleRate: Int,
        val sourceChannels: Int,
        val peak: Float,
        val meanRms: Float,
        val silentFraction: Float,
    ) {
        /** A file that is almost entirely digital silence cannot be transcribed. */
        val isEffectivelySilent: Boolean get() = silentFraction > 0.95f
    }

    private companion object {
        /** How far a short unattributed span may sit from a labelled one. */
        const val INHERIT_WINDOW_MS = 600L
    }
}
