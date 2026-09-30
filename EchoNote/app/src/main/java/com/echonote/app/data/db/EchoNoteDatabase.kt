package com.echonote.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        RecordingEntity::class,
        SpeakerEntity::class,
        TranscriptSegmentEntity::class,
    ],
    version = 1,
    // Schema export stays off for the MVP; migrations will be added alongside
    // the first released schema (see CHANGELOG.md "未完成事项").
    exportSchema = false,
)
abstract class EchoNoteDatabase : RoomDatabase() {

    abstract fun recordingDao(): RecordingDao
    abstract fun speakerDao(): SpeakerDao
    abstract fun segmentDao(): TranscriptSegmentDao

    companion object {
        const val NAME = "echonote.db"

        fun build(context: Context): EchoNoteDatabase =
            Room.databaseBuilder(context, EchoNoteDatabase::class.java, NAME)
                // Foreign keys are already declared; enabling enforcement makes
                // CASCADE delete actually remove speakers and segments.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
