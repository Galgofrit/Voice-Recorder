package org.fossify.voicerecorder.transcription

import android.content.Context
import com.whispercpp.whisper.WhisperContext
import com.whispercpp.whisper.WhisperVadContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Owns the bundled whisper model and a single cached [WhisperContext].
 * The model ships in assets and is copied to filesDir once so whisper can mmap it.
 */
object TranscriptionEngine {
    // Multilingual quantized "base" model — chosen for speed so transcription can keep up
    // live during recording. Less accurate than "small" for low-resource languages (notably
    // Hebrew), but solid for English/Japanese and several times faster.
    private const val MODEL_NAME = "ggml-base-q5_1.bin"
    private const val MODEL_ASSET = "models/$MODEL_NAME"

    // Silero VAD model — gates non-speech audio so whisper never transcribes noise/silence.
    private const val VAD_MODEL_NAME = "ggml-silero-v5.1.2.bin"
    private const val VAD_MODEL_ASSET = "models/$VAD_MODEL_NAME"

    private val mutex = Mutex()
    private var context: WhisperContext? = null
    private var vadContext: WhisperVadContext? = null

    suspend fun getContext(appContext: Context): WhisperContext = mutex.withLock {
        context ?: run {
            val modelFile = ensureModel(appContext, MODEL_NAME, MODEL_ASSET)
            WhisperContext.createContextFromFile(modelFile.absolutePath).also { context = it }
        }
    }

    suspend fun getVadContext(appContext: Context): WhisperVadContext = mutex.withLock {
        vadContext ?: run {
            val modelFile = ensureModel(appContext, VAD_MODEL_NAME, VAD_MODEL_ASSET)
            WhisperVadContext.createContextFromFile(modelFile.absolutePath).also { vadContext = it }
        }
    }

    private fun ensureModel(appContext: Context, name: String, asset: String): File {
        val outDir = File(appContext.filesDir, "models").apply { mkdirs() }
        val outFile = File(outDir, name)

        val expectedSize = appContext.assets.openFd(asset).use { it.length }
        if (outFile.exists() && outFile.length() == expectedSize) {
            return outFile
        }

        appContext.assets.open(asset).use { input ->
            outFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return outFile
    }
}
