package com.echonote.app.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.echonote.app.EchoNoteApplication
import com.echonote.app.audio.AudioFileDecoder
import com.echonote.app.data.db.CaptureSource
import com.echonote.app.data.db.SpeakerEntity
import com.echonote.app.data.db.TranscriptSegmentEntity
import com.echonote.app.data.db.RecordingStatus
import com.echonote.app.data.db.TranscriptionStatus
import com.echonote.app.data.settings.EchoNoteSettings
import com.echonote.app.data.storage.RecordingStorage
import com.echonote.app.di.AppContainer
import com.echonote.app.domain.TranscriptionProgress
import com.echonote.app.export.TranscriptExporter
import com.echonote.app.transcription.TranscriptAssembler
import com.echonote.app.transcription.TranscriptionPipeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * Orchestrates: open models → run [TranscriptionPipeline] → persist speakers and
 * segments → mark status.
 *
 * Runs one recording at a time (the models are tens-to-hundreds of MB in RAM and
 * sharing them across concurrent jobs would just thrash). Progress is exposed as
 * a [StateFlow] so both the notification and the UI can observe the same values.
 */
class TranscriptionManager(private val container: AppContainer) {

    private val _progress = MutableStateFlow<Map<Long, TranscriptionProgress>>(emptyMap())
    /** recordingId → live progress. */
    val progress: StateFlow<Map<Long, TranscriptionProgress>> = _progress.asStateFlow()

    private val _runningIds = MutableStateFlow<Set<Long>>(emptySet())
    val runningIds: StateFlow<Set<Long>> = _runningIds.asStateFlow()

    private val lock = kotlinx.coroutines.sync.Mutex()
    private val decoder = AudioFileDecoder(cacheDir = container.context.cacheDir)

    /**
     * Transcribes one recording end-to-end. Safe to call repeatedly: an existing
     * transcript for the same recording is replaced atomically.
     */
    suspend fun transcribe(recordingId: Long): Result<Unit> {
        if (!lock.tryLock()) return Result.failure(IOException("另一项转写任务正在进行"))
        try {
            return runCatching { doTranscribe(recordingId) }
                .onFailure { e ->
                    if (e !is CancellationException) {
                        Log.e(EchoNoteApplication.TAG, "transcribe($recordingId) failed", e)
                        container.database.recordingDao().updateTranscriptionStatus(
                            recordingId, TranscriptionStatus.FAILED, e.message
                        )
                    }
                }
                .onSuccess {
                    container.database.recordingDao().updateTranscriptionStatus(
                        recordingId, TranscriptionStatus.COMPLETED
                    )
                }
        } finally {
            lock.unlock()
        }
    }

    private suspend fun doTranscribe(recordingId: Long) {
        val dao = container.database.recordingDao()
        val recording = dao.getById(recordingId)
            ?: throw IOException("录音 #$recordingId 不存在")

        val file = File(recording.audioPath)
        if (!file.isFile) throw IOException("音频文件不存在：${recording.audioPath}")

        val settings = container.settings.settings.first()
        _runningIds.value = _runningIds.value + recordingId
        dao.updateTranscriptionStatus(recordingId, TranscriptionStatus.RUNNING)
        publish(recordingId, TranscriptionProgress.Stage.DECODING, null, "准备模型")

        try {
            // Optional: use the user's enrolled voiceprint to anchor "我".
            val selfEmb = container.selfVoiceprint.get()

            val options = TranscriptionPipeline.Options.from(settings, selfEmb)
            val pipeline = TranscriptionPipeline(decoder, container.modelManager)

            val output = pipeline.run(
                audioFile = file,
                options = options,
                onProgress = { progress ->
                    publish(progress.recordingId, progress.stage, progress.fraction, progress.detail)
                },
                recordingId = recordingId,
            )

            // 1) Replace speakers. Cluster 0 is the first speaker; if the user
            //    enrolled their own voice it has already been promoted to index 0.
            val speakerDao = container.database.speakerDao()
            val segmentDao = container.database.segmentDao()
            speakerDao.deleteForRecording(recordingId)
            segmentDao.deleteForRecording(recordingId)

            val clusterCount = output.clusters.size.coerceAtLeast(1)
            val speakerIds = LongArray(clusterCount)
            for (i in 0 until clusterCount) {
                val cluster = output.clusters.getOrNull(i)
                val name = defaultSpeakerName(i, clusterCount, selfEmb != null)
                speakerIds[i] = speakerDao.insert(
                    SpeakerEntity(
                        recordingId = recordingId,
                        name = name,
                        embedding = cluster?.centroid?.let { EmbeddingUtils.serialize(it) },
                        colorIndex = i % SPEAKER_COLOR_COUNT,
                        // First cluster is "我" only when we have evidence (an
                        // enrolled voiceprint) OR when diarization found exactly
                        // one speaker. Otherwise leave it unset so the user
                        // assigns it — guessing would be dishonest.
                        isMe = selfEmb != null && i == 0,
                    )
                )
            }

            // 2) Map assembler's cluster index → speaker row id.
            val entities = output.segments.mapIndexed { order, seg ->
                val speakerRowId = seg.speakerIndex
                    .takeIf { it in 0 until speakerIds.size }
                    ?.let { speakerIds[it] }
                TranscriptSegmentEntity(
                    recordingId = recordingId,
                    speakerId = speakerRowId,
                    startMs = seg.startMs.coerceAtLeast(0),
                    endMs = seg.endMs.coerceAtLeast(seg.startMs + 1),
                    text = seg.text,
                    confidence = seg.confidence.coerceIn(0f, 1f),
                    isEdited = false,
                    orderIndex = order,
                )
            }
            segmentDao.insertAll(entities)

            // 3) Update the recording's denormalised summary.
            val preview = output.segments.joinToString(" ") { it.text }.take(PREVIEW_LENGTH)
            val speakersUsed = entities.mapNotNull { it.speakerId }.toSet().size
            dao.updateTranscriptSummary(
                id = recordingId,
                speakers = speakersUsed,
                segments = entities.size,
                preview = preview,
            )

            Log.i(
                EchoNoteApplication.TAG,
                "transcribe($recordingId): ${entities.size} segments, " +
                    "$speakersUsed speakers, ${output.diagnostics.producedCharacters} chars, " +
                    "RTF=${"%.2f".format(output.diagnostics.realTimeFactor)}",
            )
        } finally {
            _runningIds.value = _runningIds.value - recordingId
            _progress.value = _progress.value - recordingId
        }
    }

    /**
     * Fire-and-forget entry point for the recording service: after a recording
     * finishes, transcription is kicked off without blocking the service. Failures
     * are recorded on the row, never thrown at the caller.
     */
    fun enqueue(recordingId: Long) {
        // A tiny dedicated scope: this outlives any UI/scope that called it.
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            transcribe(recordingId)
        }
    }

    private fun defaultSpeakerName(index: Int, total: Int, hasSelfAnchor: Boolean): String = when {
        // With an enrolled voiceprint, index 0 is provably the owner.
        hasSelfAnchor && index == 0 -> "我"
        total <= 1 -> "说话人"
        index == 0 && total == 2 -> "说话人 A"
        index == 1 && total == 2 -> "说话人 B"
        else -> "说话人 ${index + 1}"
    }

    private fun publish(id: Long, stage: TranscriptionProgress.Stage, fraction: Float?, detail: String) {
        _progress.value = _progress.value + (id to TranscriptionProgress(id, stage, fraction, detail))
    }

    /**
     * Imports an audio file from a SAF `content://` URI into private storage and
     * registers it as an [RecordingStatus.IMPORTED] recording, ready to transcribe.
     * This is the always-available fallback that keeps the AI pipeline useful even
     * when live call capture is impossible.
     */
    suspend fun importAudio(uri: Uri, title: String? = null): Long = withContext(Dispatchers.IO) {
        val name = queryDisplayName(uri) ?: "导入的音频"
        val target = container.storage.newImportFile(name)
        container.context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { out -> input.copyTo(out) }
        } ?: throw IOException("无法打开所选文件")

        if (!target.isFile || target.length() == 0L) {
            target.delete()
            throw IOException("文件为空或读取失败")
        }

        // Probe duration/format so the record is useful before transcription.
        val duration = runCatching { probeDuration(target) }.getOrDefault(0L)

        val id = container.database.recordingDao().insert(
            com.echonote.app.data.db.RecordingEntity(
                title = title?.takeIf { it.isNotBlank() } ?: name.removeSuffix("/"),
                createdAt = System.currentTimeMillis(),
                startedAt = System.currentTimeMillis(),
                duration = duration,
                recordingType = com.echonote.app.data.db.RecordingType.IMPORTED,
                audioPath = target.absolutePath,
                status = RecordingStatus.IMPORTED,
                transcriptionStatus = TranscriptionStatus.NOT_STARTED,
                sampleRate = 16_000,
                channels = 1,
                fileSize = target.length(),
                audioSource = name,
                captureSource = CaptureSource.IMPORTED,
                imported = true,
            )
        )
        Log.i(EchoNoteApplication.TAG, "imported $name (${target.length()} bytes, ${duration}ms) -> recording #$id")
        id
    }

    private fun probeDuration(file: File): Long {
        // Cheap WAV-only probe; compressed formats fall back to 0 and get their
        // duration filled in after transcription.
        return runCatching {
            com.echonote.app.audio.WavFileReader.readHeader(file).durationMs
        }.getOrDefault(0L)
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        container.context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    }.getOrNull()

    companion object {
        const val SPEAKER_COLOR_COUNT = 6
        const val PREVIEW_LENGTH = 160
    }
}
