package org.fossify.voicerecorder.transcription

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.fossify.voicerecorder.databases.TranscriptDatabase
import org.fossify.voicerecorder.models.TRANSCRIPT_DONE
import org.fossify.voicerecorder.models.TRANSCRIPT_FAILED
import org.fossify.voicerecorder.models.TRANSCRIPT_PROCESSING
import org.fossify.voicerecorder.models.Events
import org.fossify.voicerecorder.models.Transcript
import org.greenrobot.eventbus.EventBus

class TranscriptionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val uriString = inputData.getString(KEY_URI) ?: return Result.failure()
        val name = inputData.getString(KEY_NAME) ?: return Result.failure()
        val dao = TranscriptDatabase.getInstance(applicationContext).transcriptDao()

        fun publish(text: String, status: String) {
            dao.upsert(
                Transcript(
                    recordingName = name,
                    text = text,
                    status = status,
                    language = TranscriptionEngine.LANGUAGE,
                    createdAt = System.currentTimeMillis(),
                )
            )
            EventBus.getDefault().post(Events.TranscriptionUpdated(name))
        }

        return try {
            publish(text = "", status = TRANSCRIPT_PROCESSING)
            val audio = AudioDecoder.decodeToWhisperInput(applicationContext, Uri.parse(uriString))
            check(audio.isNotEmpty()) { "Decoded audio is empty" }

            val whisper = TranscriptionEngine.getContext(applicationContext)
            val text = whisper.transcribeData(audio, printTimestamp = false).trim()

            publish(text = text, status = TRANSCRIPT_DONE)
            Result.success()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.e(TAG, "Transcription failed for $name", e)
            publish(text = e.message ?: "Transcription failed", status = TRANSCRIPT_FAILED)
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "TranscriptionWorker"
        const val KEY_URI = "uri"
        const val KEY_NAME = "name"

        fun enqueue(context: Context, uri: Uri, recordingName: String) {
            val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
                .setInputData(workDataOf(KEY_URI to uri.toString(), KEY_NAME to recordingName))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "transcribe_$recordingName",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
