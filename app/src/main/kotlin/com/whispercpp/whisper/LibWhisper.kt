package com.whispercpp.whisper

import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors

private const val LOG_TAG = "LibWhisper"

// whisper timestamps are in centiseconds; multiply to get milliseconds.
private const val MS_PER_CENTISECOND = 10L

private val SPACE_BYTE = ' '.code.toByte()

data class WhisperWord(val text: String, val startMs: Long, val endMs: Long)

data class TranscriptionResult(val text: String, val words: List<WhisperWord>)

class WhisperContext private constructor(private var ptr: Long) {
    // Meet Whisper C++ constraint: Don't access from more than one thread at a time.
    private val scope: CoroutineScope = CoroutineScope(
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    )

    suspend fun detectLanguage(
        data: FloatArray,
        candidates: Array<String> = emptyArray()
    ): String = withContext(scope.coroutineContext) {
        require(ptr != 0L)
        WhisperLib.detectLanguage(ptr, WhisperCpuConfig.preferredThreadCount, data, candidates)
    }

    suspend fun transcribeData(
        data: FloatArray,
        language: String = "auto",
        printTimestamp: Boolean = true
    ): String = withContext(scope.coroutineContext) {
        require(ptr != 0L)
        val numThreads = WhisperCpuConfig.preferredThreadCount
        Log.d(LOG_TAG, "Selecting $numThreads threads")
        WhisperLib.fullTranscribe(ptr, numThreads, data, language)
        val textCount = WhisperLib.getTextSegmentCount(ptr)
        return@withContext buildString {
            for (i in 0 until textCount) {
                if (printTimestamp) {
                    val textTimestamp = "[${toTimestamp(WhisperLib.getTextSegmentT0(ptr, i))} --> ${toTimestamp(WhisperLib.getTextSegmentT1(ptr, i))}]"
                    val textSegment = WhisperLib.getTextSegment(ptr, i)
                    append("$textTimestamp: $textSegment\n")
                } else {
                    append(WhisperLib.getTextSegment(ptr, i))
                }
            }
        }
    }

    // Transcribes and also returns a word-level timeline (for read-along playback).
    suspend fun transcribeWithWords(
        data: FloatArray,
        language: String = "auto"
    ): TranscriptionResult = withContext(scope.coroutineContext) {
        require(ptr != 0L)
        WhisperLib.fullTranscribe(ptr, WhisperCpuConfig.preferredThreadCount, data, language)
        val words = mutableListOf<WhisperWord>()
        val segmentCount = WhisperLib.getTextSegmentCount(ptr)
        for (segment in 0 until segmentCount) {
            words += wordsInSegment(segment)
        }
        val text = words.joinToString("") { it.text }.trim()
        return@withContext TranscriptionResult(text, words)
    }

    private fun wordsInSegment(segment: Int): List<WhisperWord> {
        data class Tok(val bytes: ByteArray, val startMs: Long, val endMs: Long)

        val tokens = (0 until WhisperLib.getTokenCount(ptr, segment))
            .map {
                Tok(
                    bytes = WhisperLib.getTokenBytes(ptr, segment, it),
                    startMs = WhisperLib.getTokenT0(ptr, segment, it) * MS_PER_CENTISECOND,
                    endMs = WhisperLib.getTokenT1(ptr, segment, it) * MS_PER_CENTISECOND
                )
            }
            // Drop empty + whisper special tokens (ASCII, e.g. "[_BEG_]", "[_TT_123]").
            .filter { it.bytes.isNotEmpty() && !isSpecialToken(it.bytes) }

        if (tokens.isEmpty()) {
            return emptyList()
        }

        // Spaced languages (English, …): a token starting with a space begins a new word.
        // Spaceless languages (Japanese, Chinese, …): emit a unit per complete UTF-8 char.
        val hasSpaces = tokens.drop(1).any { it.bytes.firstOrNull() == SPACE_BYTE }
        val words = mutableListOf<WhisperWord>()
        var acc = mutableListOf<Byte>()
        var startMs = 0L
        var endMs = 0L

        fun flush() {
            if (acc.isNotEmpty()) {
                words += WhisperWord(String(acc.toByteArray(), Charsets.UTF_8), startMs, endMs)
                acc = mutableListOf()
            }
        }

        tokens.forEachIndexed { index, token ->
            val boundary = if (hasSpaces) {
                index > 0 && token.bytes.firstOrNull() == SPACE_BYTE
            } else {
                acc.isNotEmpty() && endsOnCompleteUtf8(acc)
            }
            if (boundary) {
                flush()
            }
            if (acc.isEmpty()) {
                startMs = token.startMs
            }
            acc.addAll(token.bytes.asList())
            endMs = token.endMs
        }
        flush()
        return words
    }

    private fun isSpecialToken(bytes: ByteArray): Boolean =
        bytes.first() == '['.code.toByte() && bytes.last() == ']'.code.toByte()

    // True if the bytes don't end mid-way through a multi-byte UTF-8 character.
    private fun endsOnCompleteUtf8(bytes: List<Byte>): Boolean {
        var i = bytes.size - 1
        var continuations = 0
        while (i >= 0 && (bytes[i].toInt() and 0xC0) == 0x80) {
            continuations++
            i--
        }
        if (i < 0) {
            return false
        }
        val lead = bytes[i].toInt() and 0xFF
        val expected = when {
            lead < 0x80 -> 1
            lead in 0xC0..0xDF -> 2
            lead in 0xE0..0xEF -> 3
            else -> 4
        }
        return continuations + 1 >= expected
    }

    suspend fun benchMemory(nthreads: Int): String = withContext(scope.coroutineContext) {
        return@withContext WhisperLib.benchMemcpy(nthreads)
    }

    suspend fun benchGgmlMulMat(nthreads: Int): String = withContext(scope.coroutineContext) {
        return@withContext WhisperLib.benchGgmlMulMat(nthreads)
    }

    suspend fun release() = withContext(scope.coroutineContext) {
        if (ptr != 0L) {
            WhisperLib.freeContext(ptr)
            ptr = 0
        }
    }

    protected fun finalize() {
        runBlocking {
            release()
        }
    }

    companion object {
        fun createContextFromFile(filePath: String): WhisperContext {
            val ptr = WhisperLib.initContext(filePath)
            if (ptr == 0L) {
                throw java.lang.RuntimeException("Couldn't create context with path $filePath")
            }
            return WhisperContext(ptr)
        }

        fun createContextFromInputStream(stream: InputStream): WhisperContext {
            val ptr = WhisperLib.initContextFromInputStream(stream)

            if (ptr == 0L) {
                throw java.lang.RuntimeException("Couldn't create context from input stream")
            }
            return WhisperContext(ptr)
        }

        fun createContextFromAsset(assetManager: AssetManager, assetPath: String): WhisperContext {
            val ptr = WhisperLib.initContextFromAsset(assetManager, assetPath)

            if (ptr == 0L) {
                throw java.lang.RuntimeException("Couldn't create context from asset $assetPath")
            }
            return WhisperContext(ptr)
        }

        fun getSystemInfo(): String {
            return WhisperLib.getSystemInfo()
        }
    }
}

private class WhisperLib {
    companion object {
        init {
            Log.d(LOG_TAG, "Primary ABI: ${Build.SUPPORTED_ABIS[0]}")
            var loadVfpv4 = false
            var loadV8fp16 = false
            if (isArmEabiV7a()) {
                // armeabi-v7a needs runtime detection support
                val cpuInfo = cpuInfo()
                cpuInfo?.let {
                    Log.d(LOG_TAG, "CPU info: $cpuInfo")
                    if (cpuInfo.contains("vfpv4")) {
                        Log.d(LOG_TAG, "CPU supports vfpv4")
                        loadVfpv4 = true
                    }
                }
            } else if (isArmEabiV8a()) {
                // ARMv8.2a needs runtime detection support
                val cpuInfo = cpuInfo()
                cpuInfo?.let {
                    Log.d(LOG_TAG, "CPU info: $cpuInfo")
                    if (cpuInfo.contains("fphp")) {
                        Log.d(LOG_TAG, "CPU supports fp16 arithmetic")
                        loadV8fp16 = true
                    }
                }
            }

            if (loadVfpv4) {
                Log.d(LOG_TAG, "Loading libwhisper_vfpv4.so")
                System.loadLibrary("whisper_vfpv4")
            } else if (loadV8fp16) {
                Log.d(LOG_TAG, "Loading libwhisper_v8fp16_va.so")
                System.loadLibrary("whisper_v8fp16_va")
            } else {
                Log.d(LOG_TAG, "Loading libwhisper.so")
                System.loadLibrary("whisper")
            }
        }

        // JNI methods
        external fun initContextFromInputStream(inputStream: InputStream): Long
        external fun initContextFromAsset(assetManager: AssetManager, assetPath: String): Long
        external fun initContext(modelPath: String): Long
        external fun freeContext(contextPtr: Long)
        external fun fullTranscribe(
            contextPtr: Long,
            numThreads: Int,
            audioData: FloatArray,
            language: String
        )

        external fun detectLanguage(
            contextPtr: Long,
            numThreads: Int,
            audioData: FloatArray,
            candidates: Array<String>
        ): String
        external fun getTextSegmentCount(contextPtr: Long): Int
        external fun getTextSegment(contextPtr: Long, index: Int): String
        external fun getTextSegmentT0(contextPtr: Long, index: Int): Long
        external fun getTextSegmentT1(contextPtr: Long, index: Int): Long
        external fun getTokenCount(contextPtr: Long, segment: Int): Int
        external fun getTokenBytes(contextPtr: Long, segment: Int, token: Int): ByteArray
        external fun getTokenT0(contextPtr: Long, segment: Int, token: Int): Long
        external fun getTokenT1(contextPtr: Long, segment: Int, token: Int): Long
        external fun getSystemInfo(): String
        external fun benchMemcpy(nthread: Int): String
        external fun benchGgmlMulMat(nthread: Int): String
    }
}

//  500 -> 00:05.000
// 6000 -> 01:00.000
private fun toTimestamp(t: Long, comma: Boolean = false): String {
    var msec = t * 10
    val hr = msec / (1000 * 60 * 60)
    msec -= hr * (1000 * 60 * 60)
    val min = msec / (1000 * 60)
    msec -= min * (1000 * 60)
    val sec = msec / 1000
    msec -= sec * 1000

    val delimiter = if (comma) "," else "."
    return String.format("%02d:%02d:%02d%s%03d", hr, min, sec, delimiter, msec)
}

private fun isArmEabiV7a(): Boolean {
    return Build.SUPPORTED_ABIS[0].equals("armeabi-v7a")
}

private fun isArmEabiV8a(): Boolean {
    return Build.SUPPORTED_ABIS[0].equals("arm64-v8a")
}

private fun cpuInfo(): String? {
    return try {
        File("/proc/cpuinfo").inputStream().bufferedReader().use {
            it.readText()
        }
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Couldn't read /proc/cpuinfo", e)
        null
    }
}