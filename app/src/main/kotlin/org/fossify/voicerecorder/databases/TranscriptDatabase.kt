package org.fossify.voicerecorder.databases

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.fossify.voicerecorder.interfaces.TranscriptDao
import org.fossify.voicerecorder.models.Transcript

@Database(entities = [Transcript::class], version = 2, exportSchema = false)
abstract class TranscriptDatabase : RoomDatabase() {
    abstract fun transcriptDao(): TranscriptDao

    companion object {
        @Volatile
        private var instance: TranscriptDatabase? = null

        // Adds the word-timeline column; existing transcripts keep their text.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transcripts ADD COLUMN wordsJson TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getInstance(context: Context): TranscriptDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TranscriptDatabase::class.java,
                    "transcripts.db"
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
        }
    }
}
