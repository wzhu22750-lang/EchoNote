package com.echonote.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {

    @Query("SELECT * FROM recordings ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE id = :id")
    fun observeById(id: Long): Flow<RecordingEntity?>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getById(id: Long): RecordingEntity?

    @Query(
        """
        SELECT * FROM recordings
        WHERE (:query = '' OR title LIKE '%' || :query || '%'
               OR transcriptPreview LIKE '%' || :query || '%'
               OR notes LIKE '%' || :query || '%')
          AND (:fromMs = -1 OR createdAt >= :fromMs)
          AND (:toMs = -1 OR createdAt <= :toMs)
          AND (:typeFilter = '' OR recordingType = :typeFilter)
        ORDER BY createdAt DESC
        LIMIT :limit
        """
    )
    fun observeFiltered(
        query: String,
        fromMs: Long,
        toMs: Long,
        typeFilter: String,
        limit: Int,
    ): Flow<List<RecordingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recording: RecordingEntity): Long

    @Update
    suspend fun update(recording: RecordingEntity)

    @Query("UPDATE recordings SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String)

    @Query("UPDATE recordings SET notes = :notes WHERE id = :id")
    suspend fun updateNotes(id: Long, notes: String)

    @Query("UPDATE recordings SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: RecordingStatus)

    @Query(
        """
        UPDATE recordings SET duration = :duration, fileSize = :fileSize,
               sampleRate = :sampleRate, channels = :channels,
               audioPath = :audioPath, status = :status, errorMessage = :error
        WHERE id = :id
        """
    )
    suspend fun finalize(
        id: Long,
        duration: Long,
        fileSize: Long,
        sampleRate: Int,
        channels: Int,
        audioPath: String,
        status: RecordingStatus,
        error: String?,
    )

    @Query(
        """
        UPDATE recordings SET transcriptionStatus = :status, errorMessage = :error
        WHERE id = :id
        """
    )
    suspend fun updateTranscriptionStatus(
        id: Long,
        status: TranscriptionStatus,
        error: String? = null,
    )

    @Query(
        """
        UPDATE recordings SET speakerCount = :speakers, segmentCount = :segments,
               transcriptPreview = :preview
        WHERE id = :id
        """
    )
    suspend fun updateTranscriptSummary(
        id: Long,
        speakers: Int,
        segments: Int,
        preview: String,
    )

    @Delete
    suspend fun delete(recording: RecordingEntity)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM recordings WHERE createdAt >= :start AND createdAt < :end")
    fun observeCountBetween(start: Long, end: Long): Flow<Int>

    @Query(
        """
        SELECT COALESCE(SUM(duration), 0) FROM recordings
        WHERE createdAt >= :start AND createdAt < :end
        """
    )
    fun observeDurationBetween(start: Long, end: Long): Flow<Long>

    @Query(
        """
        SELECT COALESCE(SUM(fileSize), 0) AS totalBytes,
               COUNT(*) AS totalRecordings,
               COALESCE(SUM(duration), 0) AS totalDurationMs
        FROM recordings
        """
    )
    fun observeStorageStats(): Flow<StorageStats>

    /** Recordings whose audio is on disk but which have not been transcribed. */
    @Query(
        """
        SELECT * FROM recordings
        WHERE audioPath != '' AND transcriptionStatus IN ('NOT_STARTED', 'FAILED', 'QUEUED')
        ORDER BY createdAt ASC
        """
    )
    suspend fun pendingTranscriptions(): List<RecordingEntity>
}

@Dao
interface SpeakerDao {

    @Query("SELECT * FROM speakers WHERE recordingId = :recordingId ORDER BY colorIndex ASC, id ASC")
    fun observeForRecording(recordingId: Long): Flow<List<SpeakerEntity>>

    @Query("SELECT * FROM speakers WHERE recordingId = :recordingId ORDER BY colorIndex ASC, id ASC")
    suspend fun getForRecording(recordingId: Long): List<SpeakerEntity>

    @Query("SELECT * FROM speakers WHERE id = :id")
    suspend fun getById(id: Long): SpeakerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(speaker: SpeakerEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(speakers: List<SpeakerEntity>): List<Long>

    @Update
    suspend fun update(speaker: SpeakerEntity)

    @Query("UPDATE speakers SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE speakers SET isMe = :isMe WHERE id = :id")
    suspend fun setMe(id: Long, isMe: Boolean)

    @Query("UPDATE speakers SET colorIndex = :colorIndex WHERE id = :id")
    suspend fun setColor(id: Long, colorIndex: Int)

    @Delete
    suspend fun delete(speaker: SpeakerEntity)

    @Query("DELETE FROM speakers WHERE recordingId = :recordingId")
    suspend fun deleteForRecording(recordingId: Long)

    /**
     * Merge [sourceId] into [targetId]: every segment attributed to the source is
     * re-pointed at the target, then the source row is deleted. Runs in one
     * transaction so the transcript never observes a half-merged state.
     */
    @Transaction
    suspend fun mergeSpeakers(sourceId: Long, targetId: Long) {
        reassignSegments(fromSpeakerId = sourceId, toSpeakerId = targetId)
        deleteById(sourceId)
    }

    @Query("UPDATE transcript_segments SET speakerId = :toSpeakerId WHERE speakerId = :fromSpeakerId")
    suspend fun reassignSegments(fromSpeakerId: Long, toSpeakerId: Long)

    @Query("DELETE FROM speakers WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface TranscriptSegmentDao {

    @Query("SELECT * FROM transcript_segments WHERE recordingId = :recordingId ORDER BY startMs ASC, orderIndex ASC")
    fun observeForRecording(recordingId: Long): Flow<List<TranscriptSegmentEntity>>

    @Query("SELECT * FROM transcript_segments WHERE recordingId = :recordingId ORDER BY startMs ASC, orderIndex ASC")
    suspend fun getForRecording(recordingId: Long): List<TranscriptSegmentEntity>

    @Query("SELECT * FROM transcript_segments WHERE id = :id")
    suspend fun getById(id: Long): TranscriptSegmentEntity?

    @Query("SELECT COUNT(*) FROM transcript_segments WHERE recordingId = :recordingId")
    suspend fun countForRecording(recordingId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(segment: TranscriptSegmentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(segments: List<TranscriptSegmentEntity>)

    @Update
    suspend fun update(segment: TranscriptSegmentEntity)

    @Delete
    suspend fun delete(segment: TranscriptSegmentEntity)

    @Query("DELETE FROM transcript_segments WHERE recordingId = :recordingId")
    suspend fun deleteForRecording(recordingId: Long)

    @Query("UPDATE transcript_segments SET text = :text, isEdited = 1 WHERE id = :id")
    suspend fun editText(id: Long, text: String)

    @Query("UPDATE transcript_segments SET speakerId = :speakerId, isEdited = 1 WHERE id = :id")
    suspend fun editSpeaker(id: Long, speakerId: Long?)

    @Query(
        """
        UPDATE transcript_segments SET startMs = :startMs, endMs = :endMs, isEdited = 1
        WHERE id = :id
        """
    )
    suspend fun editTimestamps(id: Long, startMs: Long, endMs: Long)

    /**
     * Full-text-ish search across transcript segments.
     *
     * Deliberately uses `LIKE` instead of SQLite FTS: FTS' default tokenizer
     * cannot segment Chinese, so FTS would silently fail to match the majority of
     * this app's content. Sentinels of -1 / '' mean "no filter" and keep the
     * generated statement free of nullable binding pitfalls.
     */
    @Query(
        """
        SELECT s.id AS segmentId,
               s.recordingId AS recordingId,
               r.title AS recordingTitle,
               r.createdAt AS recordingCreatedAt,
               s.speakerId AS speakerId,
               sp.name AS speakerName,
               s.startMs AS startMs,
               s.endMs AS endMs,
               s.text AS text,
               r.duration AS recordingDuration
        FROM transcript_segments s
        INNER JOIN recordings r ON r.id = s.recordingId
        LEFT JOIN speakers sp ON sp.id = s.speakerId
        WHERE (:textQuery = '' OR s.text LIKE '%' || :textQuery || '%')
          AND (:titleQuery = '' OR r.title LIKE '%' || :titleQuery || '%')
          AND (:speakerQuery = '' OR sp.name LIKE '%' || :speakerQuery || '%')
          AND (:speakerId = -1 OR s.speakerId = :speakerId)
          AND (:fromMs = -1 OR r.createdAt >= :fromMs)
          AND (:toMs = -1 OR r.createdAt <= :toMs)
        ORDER BY r.createdAt DESC, s.startMs ASC
        LIMIT :limit
        """
    )
    fun search(
        textQuery: String,
        titleQuery: String,
        speakerQuery: String,
        speakerId: Long,
        fromMs: Long,
        toMs: Long,
        limit: Int,
    ): Flow<List<SegmentSearchRow>>

    @Query(
        """
        SELECT s.id AS segmentId,
               s.recordingId AS recordingId,
               r.title AS recordingTitle,
               r.createdAt AS recordingCreatedAt,
               s.speakerId AS speakerId,
               sp.name AS speakerName,
               s.startMs AS startMs,
               s.endMs AS endMs,
               s.text AS text,
               r.duration AS recordingDuration
        FROM transcript_segments s
        INNER JOIN recordings r ON r.id = s.recordingId
        LEFT JOIN speakers sp ON sp.id = s.speakerId
        WHERE s.recordingId = :recordingId
        ORDER BY s.startMs ASC
        LIMIT :limit
        """
    )
    suspend fun searchInRecording(recordingId: Long, limit: Int): List<SegmentSearchRow>
}
