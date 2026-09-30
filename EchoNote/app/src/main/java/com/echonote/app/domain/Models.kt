package com.echonote.app.domain

import com.echonote.app.data.db.CaptureSource
import com.echonote.app.data.db.RecordingEntity
import com.echonote.app.data.db.RecordingStatus
import com.echonote.app.data.db.RecordingType
import com.echonote.app.data.db.SpeakerEntity
import com.echonote.app.data.db.TranscriptSegmentEntity
import com.echonote.app.data.db.TranscriptionStatus
import com.echonote.app.ai.EmbeddingUtils

/**
 * Domain models.
 *
 * These are plain Kotlin with no Room or Android dependency, so every screen and
 * every unit test works against stable types rather than database rows. The
 * mapping functions live next to them and are the only place that knows about
 * persistence.
 */

data class Recording(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val startedAt: Long,
    val durationMs: Long,
    val type: RecordingType,
    val audioPath: String,
    val status: RecordingStatus,
    val transcriptionStatus: TranscriptionStatus,
    val sampleRate: Int,
    val channels: Int,
    val fileSize: Long,
    val notes: String,
    val audioSource: String,
    val captureSource: CaptureSource,
    val language: String,
    val errorMessage: String?,
    val speakerCount: Int,
    val segmentCount: Int,
    val transcriptPreview: String,
    val imported: Boolean,
) {
    val isTranscribed: Boolean get() = transcriptionStatus == TranscriptionStatus.COMPLETED

    /** True when there is audio on disk that can be played. */
    val hasAudio: Boolean get() = audioPath.isNotBlank()

    /** One-line description of how the audio was actually obtained. */
    val captureLabel: String
        get() = when {
            imported -> "导入文件"
            captureSource == CaptureSource.MIXED -> "麦克风 + 系统播放（混合）"
            captureSource == CaptureSource.PLAYBACK_CAPTURE -> "系统播放捕获"
            audioSource.isNotBlank() -> audioSource
            else -> captureSource.name
        }
}

data class Speaker(
    val id: Long,
    val recordingId: Long,
    val name: String,
    val embedding: FloatArray?,
    val colorIndex: Int,
    val isMe: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Speaker) return false
        return id == other.id &&
            recordingId == other.recordingId &&
            name == other.name &&
            colorIndex == other.colorIndex &&
            isMe == other.isMe
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + recordingId.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + colorIndex
        result = 31 * result + isMe.hashCode()
        return result
    }
}

data class TranscriptSegment(
    val id: Long,
    val recordingId: Long,
    val speakerId: Long?,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val confidence: Float,
    val isEdited: Boolean,
    val orderIndex: Int,
)

/** A full recording plus everything needed to render the detail screen. */
data class TranscriptDetail(
    val recording: Recording,
    val speakers: List<Speaker>,
    val segments: List<TranscriptSegment>,
) {
    fun speakerById(id: Long?): Speaker? = id?.let { sid -> speakers.firstOrNull { it.id == sid } }
}

/** Result of a full-text search hit, ready for the search screen. */
data class SearchHit(
    val segmentId: Long,
    val recordingId: Long,
    val recordingTitle: String,
    val recordingCreatedAt: Long,
    val speakerId: Long?,
    val speakerName: String?,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val recordingDurationMs: Long,
)

/** Progress reporting for the transcription pipeline. */
data class TranscriptionProgress(
    val recordingId: Long,
    val stage: Stage,
    /** 0..1, or null when the stage cannot report a fraction. */
    val fraction: Float? = null,
    val detail: String = "",
) {
    enum class Stage {
        DECODING,
        VAD,
        ASR,
        EMBEDDING,
        CLUSTERING,
        SAVING,
        DONE,
        FAILED,
    }
}

// ---------------------------------------------------------------- mappers

fun RecordingEntity.toDomain(): Recording = Recording(
    id = id,
    title = title,
    createdAt = createdAt,
    startedAt = startedAt,
    durationMs = duration,
    type = recordingType,
    audioPath = audioPath,
    status = status,
    transcriptionStatus = transcriptionStatus,
    sampleRate = sampleRate,
    channels = channels,
    fileSize = fileSize,
    notes = notes,
    audioSource = audioSource,
    captureSource = captureSource,
    language = language,
    errorMessage = errorMessage,
    speakerCount = speakerCount,
    segmentCount = segmentCount,
    transcriptPreview = transcriptPreview,
    imported = imported,
)

fun SpeakerEntity.toDomain(): Speaker = Speaker(
    id = id,
    recordingId = recordingId,
    name = name,
    embedding = EmbeddingUtils.deserialize(embedding),
    colorIndex = colorIndex,
    isMe = isMe,
)

fun TranscriptSegmentEntity.toDomain(): TranscriptSegment = TranscriptSegment(
    id = id,
    recordingId = recordingId,
    speakerId = speakerId,
    startMs = startMs,
    endMs = endMs,
    text = text,
    confidence = confidence,
    isEdited = isEdited,
    orderIndex = orderIndex,
)

fun com.echonote.app.data.db.SegmentSearchRow.toDomain(): SearchHit = SearchHit(
    segmentId = segmentId,
    recordingId = recordingId,
    recordingTitle = recordingTitle,
    recordingCreatedAt = recordingCreatedAt,
    speakerId = speakerId,
    speakerName = speakerName,
    startMs = startMs,
    endMs = endMs,
    text = text,
    recordingDurationMs = recordingDuration,
)
