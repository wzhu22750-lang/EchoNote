package com.echonote.app.repository

import com.echonote.app.data.db.RecordingEntity
import com.echonote.app.data.db.RecordingStatus
import com.echonote.app.data.db.SpeakerEntity
import com.echonote.app.data.db.TranscriptSegmentEntity
import com.echonote.app.data.db.TranscriptionStatus
import com.echonote.app.domain.Recording
import com.echonote.app.domain.SearchHit
import com.echonote.app.domain.Speaker
import com.echonote.app.domain.TranscriptDetail
import com.echonote.app.domain.TranscriptSegment
import com.echonote.app.domain.toDomain
import com.echonote.app.di.AppContainer
import com.echonote.app.data.storage.RecordingStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take

/**
 * Read/write access to the recording + transcript data, plus the deletions that
 * must keep disk and DB consistent.
 *
 * Everything returns domain types, never Room entities, so the UI and the export
 * code never learn about persistence.
 */
class RecordingRepository(private val container: AppContainer) {

    private val dao get() = container.database.recordingDao()
    private val speakersDao get() = container.database.speakerDao()
    private val segmentsDao get() = container.database.segmentDao()

    fun observeAll(): Flow<List<Recording>> = dao.observeAll().map { it.map { r -> r.toDomain() } }

    fun observeRecent(limit: Int): Flow<List<Recording>> =
        dao.observeRecent(limit).map { it.map { r -> r.toDomain() } }

    fun observeById(id: Long): Flow<Recording?> = dao.observeById(id).map { it?.toDomain() }

    suspend fun getById(id: Long): Recording? = dao.getById(id)?.toDomain()

    fun observeFiltered(
        query: String,
        fromMs: Long = -1,
        toMs: Long = -1,
        typeFilter: String = "",
        limit: Int = 500,
    ): Flow<List<Recording>> =
        dao.observeFiltered(query, fromMs, toMs, typeFilter, limit)
            .map { it.map { r -> r.toDomain() } }

    /** Today's recording count and duration, for the home screen. */
    fun observeTodayStats(): Flow<Pair<Int, Long>> {
        val (start, end) = todayRange()
        return combine(
            dao.observeCountBetween(start, end),
            dao.observeDurationBetween(start, end),
        ) { count, duration -> count to duration }
    }

    fun observeStorageStats() = dao.observeStorageStats()

    /** Total bytes consumed by audio files on disk (not just the DB's estimate). */
    fun onDiskBytes(): Long = container.storage.totalBytes()

    fun freeBytes(): Long = container.storage.freeBytes()

    suspend fun pendingTranscriptions(): List<Recording> =
        dao.pendingTranscriptions().map { it.toDomain() }

    fun observeDetail(id: Long): Flow<TranscriptDetail> =
        combine(
            dao.observeById(id),
            speakersDao.observeForRecording(id),
            segmentsDao.observeForRecording(id),
        ) { recording, speakers, segments ->
            TranscriptDetail(
                recording = recording?.toDomain() ?: throw IllegalStateException("recording $id gone"),
                speakers = speakers.map { it.toDomain() },
                segments = segments.map { it.toDomain() },
            )
        }

    // ------------------------------------------------------------------ writes

    suspend fun rename(id: Long, title: String) = dao.updateTitle(id, title)

    suspend fun updateNotes(id: Long, notes: String) = dao.updateNotes(id, notes)

    suspend fun renameSpeaker(speakerId: Long, name: String) = speakersDao.rename(speakerId, name)

    suspend fun markSpeakerAsMe(speakerId: Long, recordingId: Long) {
        // Exactly one "我" per recording.
        speakersDao.getForRecording(recordingId).forEach { speakersDao.setMe(it.id, it.id == speakerId) }
        speakersDao.rename(speakerId, "我")
    }

    suspend fun clearSelfMark(recordingId: Long) {
        speakersDao.getForRecording(recordingId).forEach { speakersDao.setMe(it.id, false) }
    }

    suspend fun editSegmentText(segmentId: Long, text: String) = segmentsDao.editText(segmentId, text)

    suspend fun editSegmentSpeaker(segmentId: Long, speakerId: Long?) =
        segmentsDao.editSpeaker(segmentId, speakerId)

    suspend fun editSegmentTimestamps(segmentId: Long, startMs: Long, endMs: Long) =
        segmentsDao.editTimestamps(segmentId, startMs, endMs)

    /** Merge speaker [sourceId] into [targetId] (both must share one recording). */
    suspend fun mergeSpeakers(sourceId: Long, targetId: Long) =
        speakersDao.mergeSpeakers(sourceId, targetId)

    /** Reassign a whole speaker's segments onto [target] then delete [source]. */
    suspend fun combineSpeakers(sourceId: Long, targetId: Long) =
        speakersDao.mergeSpeakers(sourceId, targetId)

    /**
     * Deletes a recording: rows first (cascades to speakers/segments), then the
     * audio file. Returns the bytes reclaimed.
     */
    suspend fun delete(id: Long): Long {
        val recording = dao.getById(id) ?: return 0L
        val bytes = container.storage.sizeOf(recording.audioPath)
        dao.delete(recording)
        container.storage.delete(recording.audioPath)
        return bytes
    }

    suspend fun deleteAll() {
        // take(1).first() resolves the Flow once rather than collecting it forever.
        dao.observeAll().take(1).first().forEach { dao.delete(it) }
    }

    suspend fun retagStatus(id: Long, status: RecordingStatus) = dao.updateStatus(id, status)

    // ------------------------------------------------------------- search

    fun search(
        textQuery: String = "",
        titleQuery: String = "",
        speakerQuery: String = "",
        speakerId: Long = -1,
        fromMs: Long = -1,
        toMs: Long = -1,
        limit: Int = 200,
    ): Flow<List<SearchHit>> = segmentsDao.search(
        textQuery = textQuery,
        titleQuery = titleQuery,
        speakerQuery = speakerQuery,
        speakerId = speakerId,
        fromMs = fromMs,
        toMs = toMs,
        limit = limit,
    ).map { rows -> rows.map { it.toDomain() } }

    suspend fun speakersOf(recordingId: Long): List<Speaker> =
        speakersDao.getForRecording(recordingId).map { it.toDomain() }

    suspend fun segmentsOf(recordingId: Long): List<TranscriptSegment> =
        segmentsDao.getForRecording(recordingId).map { it.toDomain() }

    private fun todayRange(): Pair<Long, Long> {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
        return start to cal.timeInMillis
    }
}
