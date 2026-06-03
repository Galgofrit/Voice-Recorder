package org.fossify.voicerecorder.transcription

import android.content.Context
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Owns the bundled whisper model and a single cached [WhisperContext].
 * The model ships in assets and is copied to filesDir once so whisper can mmap it.
 */
object TranscriptionEngine {
    const val LANGUAGE = "en"
    private const val MODEL_ASSET = "models/ggml-tiny.en.bin"

    private val mutex = Mutex()
    private var context: WhisperContext? = null

    suspend fun getContext(appContext: Context): WhisperContext = mutex.withLock {
        context ?: run {
            val modelFile = ensureModel(appContext)
            WhisperContext.createContextFromFile(modelFile.absolutePath).also { context = it }
        }
    }

    private fun ensureModel(appContext: Context): File {
        val outDir = File(appContext.filesDir, "models").apply { mkdirs() }
        val outFile = File(outDir, "ggml-tiny.en.bin")

        val expectedSize = appContext.assets.openFd(MODEL_ASSET).use { it.length }
        if (outFile.exists() && outFile.length() == expectedSize) {
            return outFile
        }

        appContext.assets.open(MODEL_ASSET).use { input ->
            outFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return outFile
    }
}
