package org.fossify.voicerecorder.models

import android.net.Uri

class Events {
    class RecordingDuration internal constructor(val duration: Int)
    class RecordingStatus internal constructor(val status: Int)
    class RecordingAmplitude internal constructor(val amplitude: Int)
    class RecordingCompleted internal constructor()
    class RecordingTrashUpdated internal constructor()
    class RecordingSaved internal constructor(val uri: Uri?, val name: String = "")
    class RecordingFilename internal constructor(val name: String)
    class TranscriptionUpdated internal constructor(val recordingName: String)
    class LiveTranscription internal constructor(val text: String)
}
