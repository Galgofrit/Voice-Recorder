package org.fossify.voicerecorder.helpers

import android.content.Context
import java.io.File

/**
 * Caches computed waveform envelopes on disk (one small file per recording) so a
 * recording's waveform is only ever decoded once; afterwards it loads instantly. Bars are
 * stored as one byte each (0..255), which is plenty of precision for the display.
 */
object WaveformCache {
    private const val DIR = "waveforms"
    private const val BYTE_SCALE = 255f
    private const val BYTE_MASK = 0xFF

    private fun cacheFile(context: Context, recordingName: String): File {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        return File(dir, "${recordingName.hashCode()}_$recordingName.bin")
    }

    fun load(context: Context, recordingName: String): FloatArray? {
        val file = cacheFile(context, recordingName)
        if (!file.exists()) {
            return null
        }

        return try {
            val bytes = file.readBytes()
            FloatArray(bytes.size) { (bytes[it].toInt() and BYTE_MASK) / BYTE_SCALE }
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            null
        }
    }

    // Keeps a recording's cached waveform attached to it across renames.
    fun rename(context: Context, oldName: String, newName: String) {
        val old = cacheFile(context, oldName)
        if (old.exists()) {
            old.renameTo(cacheFile(context, newName))
        }
    }

    fun save(context: Context, recordingName: String, bars: FloatArray) {
        if (bars.isEmpty()) {
            return
        }

        try {
            val bytes = ByteArray(bars.size) {
                (bars[it].coerceIn(0f, 1f) * BYTE_SCALE).toInt().toByte()
            }
            cacheFile(context, recordingName).writeBytes(bytes)
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            // A missing cache just means we recompute next time; ignore write failures.
        }
    }
}
