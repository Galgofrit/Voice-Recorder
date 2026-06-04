package org.fossify.voicerecorder.models

// A flat list of these backs the recordings adapter: month headers interleaved with
// the recordings that fall under them.
sealed class RecordingListItem

data class RecordingSection(val title: String) : RecordingListItem()

data class RecordingEntry(val recording: Recording) : RecordingListItem()
