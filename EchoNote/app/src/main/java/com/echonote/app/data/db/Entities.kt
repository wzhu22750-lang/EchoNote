package com.echonote.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * How a recording's audio was obtained. This is the single most important field
 * in the whole schema, because it records *which audio path was actually used*
 * rather than what the user hoped was captured. See TECHNICAL_FEASIBILITY.md.
 */
enum class RecordingType {
    /** Microphone only. Captures the room, therefore the local speaker and,
     *  when the phone is on speaker, the remote party as well. */
    MIC,

    /** AudioPlaybackCapture only (Android 10+, MediaProjection). Captures audio
     *  that other apps render through the media/unknown usage paths. */
    PLAYBACK,

    /** Microphone + AudioPlaybackCapture summed into one mono track. */
    MIC_WITH_PLAYBACK,

    /** A pre-existing audio file the user imported. Always available, so it is
     *  the permanent fallback for the whole AI pipeline. */
    IMPORTED,
}

enum class RecordingStatus {
    RECORDING,
    PAUSED,
    COMPLETED,
    FAILED,
    IMPORTED,
}

enum class TranscriptionStatus {
    NOT_STARTED,
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
}

/** Which `AudioRecord` source the capture engine negotiated. Recorded verbatim
 *  so the Feasibility Lab can prove what was really used on a given handset. */
enum class CaptureSource {
    MIC,
    VOICE_RECOGNITION,
    VOICE_COMMUNICATION,
    UNPROCESSED,
    CAMCORDER,
    DEFAULT,
    PLAYBACK_CAPTURE,
    MIXED,
    IMPORTED,
}

@Entity(
    tableName = "recordings",
    indices = [
        Index("createdAt"),
        Index("status"),
        Index("transcriptionStatus"),
    ],
)
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** Wall clock when the row was created. */
    val createdAt: Long,
    /** Wall clock when audio capture actually began. */
    val startedAt: Long,
    /** Duration of recordable audio content in milliseconds. */
    val duration: Long,
    val recordingType: RecordingType,
    /** Absolute path in app-private storage (PCM WAV). Empty until finalised. */
    val audioPath: String,
    val status: RecordingStatus,
    val transcriptionStatus: TranscriptionStatus,
    val sampleRate: Int,
    val channels: Int,
    val fileSize: Long,
    val notes: String = "",
    /** Human readable description of the negotiated capture path. */
    val audioSource: String = "",
    /** Machine readable capture source, for the feasibility report. */
    val captureSource: CaptureSource = CaptureSource.MIC,
    val language: String = "auto",
    val errorMessage: String? = null,
    /** Denormalised caches so the history list never needs a join. */
    val speakerCount: Int = 0,
    val segmentCount: Int = 0,
    val transcriptPreview: String = "",
    /** True when the user imported the file rather than recording it live. */
    val imported: Boolean = false,
)

@Entity(
    tableName = "speakers",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("recordingId")],
)
data class SpeakerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: Long,
    /** User-facing label: 我 / 对方 / 说话人 3 … */
    val name: String,
    /**
     * Speaker embedding, serialised as a compact comma-separated list of floats.
     * Stored as TEXT rather than BLOB so that Room's generated `equals`/`hashCode`
     * stay value-based (ByteArray would compare by identity) and so the value can
     * be inspected with plain sqlite3 during debugging.
     */
    val embedding: String? = null,
    /** Index used to pick a stable UI colour. */
    val colorIndex: Int = 0,
    /** True for the local device owner (maps to "我"). */
    val isMe: Boolean = false,
    /** Non-null when the user manually merged this speaker into another one. */
    val mergedIntoId: Long? = null,
)

@Entity(
    tableName = "transcript_segments",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SpeakerEntity::class,
            parentColumns = ["id"],
            childColumns = ["speakerId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("recordingId"), Index("speakerId"), Index("startMs")],
)
data class TranscriptSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: Long,
    /** Null means the diarizer could not attribute this segment (显示为「未知」). */
    val speakerId: Long? = null,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val confidence: Float = 0f,
    /** True when a human edited the text, speaker or timestamps. */
    val isEdited: Boolean = false,
    /** Stable ordering key so re-clustering never shuffles the transcript. */
    val orderIndex: Int = 0,
)

/** Projection used by the search screen: one row per matching transcript segment. */
data class SegmentSearchRow(
    val segmentId: Long,
    val recordingId: Long,
    val recordingTitle: String,
    val recordingCreatedAt: Long,
    val speakerId: Long?,
    val speakerName: String?,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val recordingDuration: Long,
)

/** Aggregate storage / activity counters for the home screen. */
data class StorageStats(
    val totalBytes: Long,
    val totalRecordings: Int,
    val totalDurationMs: Long,
)
