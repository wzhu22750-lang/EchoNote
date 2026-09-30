package com.echonote.app.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.echonote.app.data.db.EchoNoteDatabase
import com.echonote.app.data.db.RecordingEntity
import com.echonote.app.data.db.RecordingStatus
import com.echonote.app.data.db.RecordingType
import com.echonote.app.data.db.SegmentSearchRow
import com.echonote.app.data.db.SpeakerEntity
import com.echonote.app.data.db.TranscriptSegmentEntity
import com.echonote.app.data.db.TranscriptionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric tests for the Room DAOs ([EchoNoteDatabase], in-memory).
 *
 * Covers the three behaviours the whole app leans on:
 *  1. `search()`'s deliberate `LIKE`-based matching (FTS cannot segment Chinese);
 *  2. foreign-key semantics: CASCADE on recording delete, SET_NULL on speaker delete;
 *  3. `SpeakerDao.mergeSpeakers` — segments re-pointed and the source speaker
 *     removed as one unit.
 *
 * All timestamps are fixed constants; nothing depends on wall-clock time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingDaoTest {

    private lateinit var db: EchoNoteDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, EchoNoteDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ------------------------------------------------------------- fixtures

    private val tA = 1_700_000_000_000L
    private val tB = tA + 86_400_000L

    private data class Seed(
        val recA: Long,
        val recB: Long,
        val me: Long,
        val other: Long,
        val meB: Long,
        val seg1: Long, // recA / 我   / "今天讨论项目进度"
        val seg2: Long, // recA / 对方 / "Hello World 好的"
        val seg3: Long, // recB / 我   / "项目进度复盘完成"
        val seg4: Long, // recB / null / "未标注杂项"
    )

    private fun recording(title: String, createdAt: Long) = RecordingEntity(
        title = title,
        createdAt = createdAt,
        startedAt = createdAt,
        duration = 60_000L,
        recordingType = RecordingType.MIC,
        audioPath = "/data/user/0/com.echonote.app/files/rec.wav",
        status = RecordingStatus.COMPLETED,
        transcriptionStatus = TranscriptionStatus.COMPLETED,
        sampleRate = 16_000,
        channels = 1,
        fileSize = 1_920_000L,
    )

    private fun seed(): Seed = runBlocking {
        val recA = db.recordingDao().insert(recording("周一会议", tA))
        val recB = db.recordingDao().insert(recording("项目复盘", tB))
        val me = db.speakerDao().insert(SpeakerEntity(recordingId = recA, name = "我", colorIndex = 0, isMe = true))
        val other = db.speakerDao().insert(SpeakerEntity(recordingId = recA, name = "对方", colorIndex = 1))
        val meB = db.speakerDao().insert(SpeakerEntity(recordingId = recB, name = "我", colorIndex = 0, isMe = true))
        val seg1 = db.segmentDao().insert(
            TranscriptSegmentEntity(recordingId = recA, speakerId = me, startMs = 0, endMs = 20_000, text = "今天讨论项目进度", orderIndex = 0),
        )
        val seg2 = db.segmentDao().insert(
            TranscriptSegmentEntity(recordingId = recA, speakerId = other, startMs = 30_000, endMs = 50_000, text = "Hello World 好的", orderIndex = 1),
        )
        val seg3 = db.segmentDao().insert(
            TranscriptSegmentEntity(recordingId = recB, speakerId = meB, startMs = 5_000, endMs = 15_000, text = "项目进度复盘完成", orderIndex = 0),
        )
        val seg4 = db.segmentDao().insert(
            TranscriptSegmentEntity(recordingId = recB, speakerId = null, startMs = 20_000, endMs = 25_000, text = "未标注杂项", orderIndex = 1),
        )
        Seed(recA, recB, me, other, meB, seg1, seg2, seg3, seg4)
    }

    private fun search(
        textQuery: String = "",
        titleQuery: String = "",
        speakerQuery: String = "",
        speakerId: Long = -1L,
        fromMs: Long = -1L,
        toMs: Long = -1L,
        limit: Int = 200,
    ): List<SegmentSearchRow> = runBlocking {
        db.segmentDao().search(textQuery, titleQuery, speakerQuery, speakerId, fromMs, toMs, limit).first()
    }

    // ---------------------------------------------------------------- search

    @Test
    fun `searchOnAnEmptyDatabaseReturnsNothing`() {
        assertTrue(search().isEmpty())
        assertTrue(search(textQuery = "项目").isEmpty())
    }

    @Test
    fun `searchMatchesChineseSubstringsInSegmentText`() {
        val s = seed()

        // Newest recording first, then startMs ascending.
        assertEquals(listOf(s.seg3, s.seg1), search(textQuery = "项目").map { it.segmentId })
        assertEquals(listOf(s.seg3, s.seg1), search(textQuery = "进度").map { it.segmentId })
        assertTrue(search(textQuery = "不存在的词").isEmpty())

        // Projection carries the joined recording / speaker fields.
        val hit = search(textQuery = "复盘").single()
        assertEquals(s.recB, hit.recordingId)
        assertEquals("项目复盘", hit.recordingTitle)
        assertEquals(tB, hit.recordingCreatedAt)
        assertEquals("我", hit.speakerName)
        assertEquals(5_000L, hit.startMs)
        assertEquals(15_000L, hit.endMs)
        assertEquals(60_000L, hit.recordingDuration)
    }

    @Test
    fun `searchMatchesAsciiCaseInsensitively`() {
        val s = seed()
        assertEquals("SQLite LIKE is case-insensitive for ASCII", listOf(s.seg2), search(textQuery = "hello world").map { it.segmentId })
        assertEquals(listOf(s.seg2), search(textQuery = "HELLO").map { it.segmentId })
        assertTrue("CJK matching must not regress to case rules", search(textQuery = "HELLO WORLD").size == 1)
    }

    @Test
    fun `searchFiltersByRecordingTitle`() {
        val s = seed()
        assertEquals(listOf(s.seg3, s.seg4), search(titleQuery = "复盘").map { it.segmentId })
        assertEquals(listOf(s.seg1, s.seg2), search(titleQuery = "会议").map { it.segmentId })
        assertTrue(search(titleQuery = "不存在的标题").isEmpty())
    }

    @Test
    fun `searchFiltersBySpeakerName`() {
        val s = seed()
        assertEquals(listOf(s.seg2), search(speakerQuery = "对方").map { it.segmentId })
        // Both "我" speakers match; the null-speaker segment must not appear.
        assertEquals(listOf(s.seg3, s.seg1), search(speakerQuery = "我").map { it.segmentId })
    }

    @Test
    fun `searchFiltersBySpeakerId`() {
        val s = seed()
        // seg2 belongs to 「对方」 (per seed), seg1+seg3 to their own recordings' 我-row.
        // speakerId matches the speakers row id, so recB's 我 is a different row.
        assertEquals(listOf(s.seg2), search(speakerId = s.other).map { it.segmentId })
        assertEquals(listOf(s.seg1), search(speakerId = s.me).map { it.segmentId })

        // Sentinel -1 disables the filter: all four rows, newest recording first.
        assertEquals(
            listOf(s.seg3, s.seg4, s.seg1, s.seg2),
            search().map { it.segmentId },
        )
    }

    @Test
    fun `searchFiltersByCreationTimeRange`() {
        val s = seed()
        assertEquals(listOf(s.seg3, s.seg4), search(fromMs = tB).map { it.segmentId })
        assertEquals(listOf(s.seg1, s.seg2), search(toMs = tA).map { it.segmentId })
        assertEquals(4, search(fromMs = tA, toMs = tB).size)
    }

    @Test
    fun `searchOrdersByNewestRecordingThenStartMsAndHonorsLimit`() {
        val s = seed()
        assertEquals(
            listOf(s.seg3, s.seg4, s.seg1),
            search(limit = 3).map { it.segmentId },
        )
        assertTrue(search(limit = 0).isEmpty())
    }

    // ------------------------------------------------- foreign keys: CASCADE

    @Test
    fun `deletingARecordingCascadesToSpeakersAndSegments`() = runBlocking {
        val s = seed()

        db.recordingDao().deleteById(s.recA)

        assertTrue(db.speakerDao().getForRecording(s.recA).isEmpty())
        assertTrue(db.segmentDao().getForRecording(s.recA).isEmpty())

        // The sibling recording is untouched.
        assertEquals(1, db.speakerDao().getForRecording(s.recB).size)
        assertEquals(2, db.segmentDao().getForRecording(s.recB).size)
        assertEquals(listOf(s.seg3, s.seg4), search(textQuery = "").map { it.segmentId })
    }

    // ------------------------------------------------ foreign keys: SET_NULL

    @Test
    fun `deletingASpeakerNullsOutItsSegments`() = runBlocking {
        val s = seed()

        val entity = db.speakerDao().getById(s.other)
        assertNotNull(entity)
        db.speakerDao().delete(entity!!)

        val seg2 = db.segmentDao().getById(s.seg2)
        assertNull("segments of a deleted speaker become unattributed", seg2?.speakerId)
        assertEquals("other segments keep their speaker", s.me, db.segmentDao().getById(s.seg1)?.speakerId)

        val hit = search(textQuery = "Hello").single()
        assertNull(hit.speakerId)
        assertNull(hit.speakerName)
    }

    // ------------------------------------------------------- mergeSpeakers

    @Test
    fun `mergeSpeakersRepointsSegmentsAndRemovesTheSource`() = runBlocking {
        val s = seed()

        db.speakerDao().mergeSpeakers(sourceId = s.other, targetId = s.me)

        // Every segment of the source now belongs to the target...
        assertEquals(s.me, db.segmentDao().getById(s.seg2)?.speakerId)
        assertEquals(s.me, db.segmentDao().getById(s.seg1)?.speakerId)

        // ...the source row is gone, the target survives...
        assertNull(db.speakerDao().getById(s.other))
        assertNotNull(db.speakerDao().getById(s.me))
        assertEquals(1, db.speakerDao().getForRecording(s.recA).size)

        // ...and search sees the merged attribution as one unit.
        assertEquals(
            listOf(s.seg1, s.seg2),
            search(speakerId = s.me).map { it.segmentId },
        )
    }

    // ------------------------------------------- foreign keys: enforcement

    @Test
    fun `insertingAChildWithAMissingParentViolatesTheForeignKey`() = runBlocking {
        try {
            db.speakerDao().insert(SpeakerEntity(recordingId = 9_999L, name = "孤儿"))
            fail("speaker without a recording must violate the foreign key")
        } catch (expected: SQLiteConstraintException) {
        }
        try {
            db.segmentDao().insert(
                TranscriptSegmentEntity(recordingId = 9_999L, startMs = 0, endMs = 1_000, text = "孤儿"),
            )
            fail("segment without a recording must violate the foreign key")
        } catch (expected: SQLiteConstraintException) {
        }
    }
}
