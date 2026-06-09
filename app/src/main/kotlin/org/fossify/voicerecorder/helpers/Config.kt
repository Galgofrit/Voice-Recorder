package org.fossify.voicerecorder.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaRecorder
import androidx.core.content.edit
import org.fossify.commons.helpers.BaseConfig
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.extensions.getDefaultRecordingsFolder
import org.fossify.voicerecorder.transcription.WhisperLanguages
import java.util.Locale

class Config(context: Context) : BaseConfig(context) {
    companion object {
        fun newInstance(context: Context) = Config(context)
    }

    var saveRecordingsFolder: String
        get() = prefs.getString(SAVE_RECORDINGS, context.getDefaultRecordingsFolder())!!
        set(saveRecordingsFolder) = prefs.edit().putString(SAVE_RECORDINGS, saveRecordingsFolder)
            .apply()

    var extension: Int
        get() = prefs.getInt(EXTENSION, EXTENSION_M4A)
        set(extension) = prefs.edit().putInt(EXTENSION, extension).apply()

    var microphoneMode: Int
        get() = prefs.getInt(MICROPHONE_MODE, MediaRecorder.AudioSource.DEFAULT)
        set(audioSource) = prefs.edit { putInt(MICROPHONE_MODE, audioSource) }

    fun getMicrophoneModeText(mode: Int) = context.getString(
        when (mode) {
            MediaRecorder.AudioSource.CAMCORDER -> R.string.microphone_mode_camcorder
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> R.string.microphone_mode_voice_communication
            MediaRecorder.AudioSource.VOICE_PERFORMANCE -> R.string.microphone_mode_voice_performance
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> R.string.microphone_mode_voice_recognition
            MediaRecorder.AudioSource.UNPROCESSED -> R.string.microphone_mode_unprocessed
            else -> org.fossify.commons.R.string.system_default
        }
    )

    var bitrate: Int
        get() = prefs.getInt(BITRATE, DEFAULT_BITRATE)
        set(bitrate) = prefs.edit().putInt(BITRATE, bitrate).apply()

    var samplingRate: Int
        get() = prefs.getInt(SAMPLING_RATE, DEFAULT_SAMPLING_RATE)
        set(samplingRate) = prefs.edit().putInt(SAMPLING_RATE, samplingRate).apply()

    var recordAfterLaunch: Boolean
        get() = prefs.getBoolean(RECORD_AFTER_LAUNCH, false)
        set(recordAfterLaunch) = prefs.edit().putBoolean(RECORD_AFTER_LAUNCH, recordAfterLaunch)
            .apply()

    fun getExtensionText() = context.getString(
        when (extension) {
            EXTENSION_M4A -> R.string.m4a
            EXTENSION_OGG -> R.string.ogg_opus
            else -> R.string.mp3_experimental
        }
    )

    fun getExtension() = context.getString(
        when (extension) {
            EXTENSION_M4A -> R.string.m4a
            EXTENSION_OGG -> R.string.ogg
            else -> R.string.mp3
        }
    )

    @SuppressLint("InlinedApi")
    fun getOutputFormat() = when (extension) {
        EXTENSION_OGG -> MediaRecorder.OutputFormat.OGG
        else -> MediaRecorder.OutputFormat.MPEG_4
    }

    @SuppressLint("InlinedApi")
    fun getAudioEncoder() = when (extension) {
        EXTENSION_OGG -> MediaRecorder.AudioEncoder.OPUS
        else -> MediaRecorder.AudioEncoder.AAC
    }

    var useRecycleBin: Boolean
        get() = prefs.getBoolean(USE_RECYCLE_BIN, true)
        set(useRecycleBin) = prefs.edit().putBoolean(USE_RECYCLE_BIN, useRecycleBin).apply()

    var lastRecycleBinCheck: Long
        get() = prefs.getLong(LAST_RECYCLE_BIN_CHECK, 0L)
        set(lastRecycleBinCheck) = prefs.edit().putLong(LAST_RECYCLE_BIN_CHECK, lastRecycleBinCheck)
            .apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEEP_SCREEN_ON, true)
        set(keepScreenOn) = prefs.edit().putBoolean(KEEP_SCREEN_ON, keepScreenOn).apply()

    var wasMicModeWarningShown: Boolean
        get() = prefs.getBoolean(WAS_MIC_MODE_WARNING_SHOWN, false)
        set(wasMicModeWarningShown) = prefs.edit {
            putBoolean(WAS_MIC_MODE_WARNING_SHOWN, wasMicModeWarningShown)
        }

    var filenamePattern: String
        get() = prefs.getString(FILENAME_PATTERN, DEFAULT_FILENAME_PATTERN)!!
        set(filenamePattern) = prefs.edit { putString(FILENAME_PATTERN, filenamePattern) }

    var autoTranscribe: Boolean
        get() = prefs.getBoolean(AUTO_TRANSCRIBE, true)
        set(autoTranscribe) = prefs.edit { putBoolean(AUTO_TRANSCRIBE, autoTranscribe) }

    // The single language recordings are transcribed in (a whisper language code).
    var transcriptionLanguage: String
        get() = prefs.getString(TRANSCRIPTION_LANGUAGE, null) ?: defaultTranscriptionLanguage()
        set(value) = prefs.edit { putString(TRANSCRIPTION_LANGUAGE, value) }

    // Seconds of new audio per live-transcription window (see DEFAULT/MIN/MAX constants).
    var transcriptionChunkSeconds: Int
        get() = prefs.getInt(TRANSCRIPTION_CHUNK_SECONDS, DEFAULT_TRANSCRIPTION_CHUNK_SECONDS)
        set(value) = prefs.edit { putInt(TRANSCRIPTION_CHUNK_SECONDS, value) }

    private fun defaultTranscriptionLanguage(): String {
        val deviceLanguage = Locale.getDefault().language
        val supported = WhisperLanguages.ALL.map { it.first }.toSet()
        return if (deviceLanguage in supported) deviceLanguage else "en"
    }

    var playbackSpeed: Float
        get() = prefs.getFloat(PLAYBACK_SPEED, 1f)
        set(playbackSpeed) = prefs.edit { putFloat(PLAYBACK_SPEED, playbackSpeed) }

    // Secondary accent for record/play controls — a classic recording red by default.
    var recordingAccentColor: Int
        get() = prefs.getInt(
            RECORDING_ACCENT_COLOR,
            context.resources.getColor(R.color.default_recording_accent, context.theme)
        )
        set(value) = prefs.edit { putInt(RECORDING_ACCENT_COLOR, value) }

    // Explicit card/surface color. The transparent default (0) means "auto" — derive it from
    // the background (so it still adapts on Material You / other themes); see cardSurfaceColor().
    var recordingCardColor: Int
        get() = prefs.getInt(RECORDING_CARD_COLOR, 0)
        set(value) = prefs.edit { putInt(RECORDING_CARD_COLOR, value) }

    // Favorited recordings, keyed by filename (same key transcripts use), so the mark survives
    // restarts and follows the file on rename (see renameFavoriteRecording).
    var favoriteRecordings: Set<String>
        get() = prefs.getStringSet(FAVORITE_RECORDINGS, HashSet())!!
        set(value) = prefs.edit { putStringSet(FAVORITE_RECORDINGS, value) }

    fun isFavoriteRecording(title: String) = favoriteRecordings.contains(title)

    fun setFavoriteRecording(title: String, favorite: Boolean) {
        favoriteRecordings = if (favorite) favoriteRecordings + title else favoriteRecordings - title
    }

    fun renameFavoriteRecording(oldTitle: String, newTitle: String) {
        if (isFavoriteRecording(oldTitle)) {
            favoriteRecordings = favoriteRecordings - oldTitle + newTitle
        }
    }
}
