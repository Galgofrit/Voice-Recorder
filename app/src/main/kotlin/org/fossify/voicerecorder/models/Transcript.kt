package org.fossify.voicerecorder.models

import androidx.room.Entity
import androidx.room.PrimaryKey

const val TRANSCRIPT_PROCESSING = "processing"
const val TRANSCRIPT_DONE = "done"
const val TRANSCRIPT_FAILED = "failed"

// Keyed by the recording's filename (Recording.title) because Recording.id
// (a file hashCode) is not stable across reloads/renames.
@Entity(tableName = "transcripts")
data class Transcript(
    @PrimaryKey val recordingName: String,
    val text: String,
    val status: String,
    val language: String,
    val createdAt: Long,
    // JSON-encoded word timeline (see Word) for read-along playback; "" when absent.
    val wordsJson: String = "",
)
