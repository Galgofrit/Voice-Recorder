package org.fossify.voicerecorder.recorder

import android.os.ParcelFileDescriptor

interface Recorder {
    // Invoked with each captured PCM-16 buffer (mono) as it's recorded, so callers can tee
    // the raw audio (e.g. to live transcription). Null for backends with no PCM access.
    var onPcmData: ((samples: ShortArray, count: Int, sampleRate: Int) -> Unit)?

    fun setOutputFile(path: String)
    fun setOutputFile(parcelFileDescriptor: ParcelFileDescriptor)
    fun prepare()
    fun start()
    fun stop()
    fun pause()
    fun resume()
    fun release()
    fun getMaxAmplitude(): Int
}
