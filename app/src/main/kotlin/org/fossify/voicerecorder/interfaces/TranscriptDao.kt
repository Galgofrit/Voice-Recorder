package org.fossify.voicerecorder.interfaces

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import org.fossify.voicerecorder.models.Transcript

@Dao
interface TranscriptDao {
    @Query("SELECT * FROM transcripts WHERE recordingName = :name")
    fun get(name: String): Transcript?

    @Query("SELECT * FROM transcripts WHERE status = :status")
    fun getByStatus(status: String): List<Transcript>

    @Upsert
    fun upsert(transcript: Transcript)

    @Query("DELETE FROM transcripts WHERE recordingName = :name")
    fun delete(name: String)
}
