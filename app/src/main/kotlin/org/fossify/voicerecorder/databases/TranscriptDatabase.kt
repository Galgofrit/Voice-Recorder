package org.fossify.voicerecorder.databases

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import org.fossify.voicerecorder.interfaces.TranscriptDao
import org.fossify.voicerecorder.models.Transcript

@Database(entities = [Transcript::class], version = 1, exportSchema = false)
abstract class TranscriptDatabase : RoomDatabase() {
    abstract fun transcriptDao(): TranscriptDao

    companion object {
        @Volatile
        private var instance: TranscriptDatabase? = null

        fun getInstance(context: Context): TranscriptDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TranscriptDatabase::class.java,
                    "transcripts.db"
                ).build().also { instance = it }
            }
        }
    }
}
