package org.fossify.voicerecorder.transcription

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.Closeable
import java.nio.ByteOrder
import java.nio.ShortBuffer

/**
 * Keeps a single audio decoder open for a recording so its waveform can be sampled one
 * visible window at a time, cheaply. Opening the file and creating a [MediaCodec] is the
 * expensive part (~0.5s), so doing it once and then just flush+seek+decode per range makes
 * each window load in tens of milliseconds. Returns raw peak amplitudes per bar; the caller
 * normalizes. Not thread-safe — drive it from a single worker thread.
 */
class WaveformRangeDecoder(
    context: Context,
    uri: Uri,
    private val barsPerSecond: Int,
) : Closeable {
    companion object {
        private const val TIMEOUT_US = 1000L
        private const val MICROS_PER_SECOND = 1_000_000L
    }

    class Slice(val startBar: Int, val peaks: FloatArray)

    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val samplesPerBar: Int
    private var released = false

    init {
        extractor.setDataSource(context, uri, null)
        val trackIndex = selectAudioTrack(extractor)
        require(trackIndex >= 0) { "No audio track found in $uri" }
        extractor.selectTrack(trackIndex)

        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        samplesPerBar = (sampleRate.toLong() * channelCount / barsPerSecond).toInt().coerceAtLeast(1)

        codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()
    }

    fun decodeRange(startBar: Int, endBar: Int): Slice {
        check(!released) { "decoder released" }
        codec.flush()
        val startUs = startBar.toLong() * MICROS_PER_SECOND / barsPerSecond
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val actualStartUs = extractor.sampleTime.coerceAtLeast(0)
        val actualStartBar = (actualStartUs * barsPerSecond / MICROS_PER_SECOND).toInt()
        val maxBars = (endBar - actualStartBar).coerceAtLeast(1)

        val builder = RangeBuilder(samplesPerBar, maxBars)
        val bufferInfo = MediaCodec.BufferInfo()
        var sawInputEos = false
        var done = false
        while (!done) {
            if (!sawInputEos) {
                sawInputEos = feedAllInput()
            }
            done = drain(bufferInfo, builder)
        }
        return Slice(actualStartBar, builder.result())
    }

    override fun close() {
        if (released) {
            return
        }
        released = true
        try {
            codec.stop()
        } catch (@Suppress("SwallowedException") e: IllegalStateException) {
            // Already stopped/in an odd state; nothing to do but release.
        }
        codec.release()
        extractor.release()
    }

    private fun feedAllInput(): Boolean {
        while (true) {
            val inIndex = codec.dequeueInputBuffer(0)
            if (inIndex < 0) {
                return false
            }

            val inputBuffer = codec.getInputBuffer(inIndex)!!
            val sampleSize = extractor.readSampleData(inputBuffer, 0)
            if (sampleSize < 0) {
                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                return true
            }

            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    private fun drain(bufferInfo: MediaCodec.BufferInfo, builder: RangeBuilder): Boolean {
        var timeoutUs = TIMEOUT_US
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            if (outIndex < 0) {
                return false
            }

            val buffer = codec.getOutputBuffer(outIndex)!!
            buffer.position(bufferInfo.offset)
            buffer.limit(bufferInfo.offset + bufferInfo.size)
            val full = builder.add(buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer())
            codec.releaseOutputBuffer(outIndex, false)
            if (full || bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                return true
            }
            timeoutUs = 0
        }
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                return i
            }
        }
        return -1
    }

    // Accumulates streamed PCM16 samples into raw peak-per-bar values, up to [maxBars].
    private class RangeBuilder(private val samplesPerBar: Int, private val maxBars: Int) {
        private val bars = ArrayList<Float>(maxBars)
        private var barMax = 0
        private var samplesInBar = 0

        // Returns true once [maxBars] bars have been collected.
        fun add(shorts: ShortBuffer): Boolean {
            while (shorts.hasRemaining()) {
                val abs = kotlin.math.abs(shorts.get().toInt())
                if (abs > barMax) barMax = abs
                if (++samplesInBar >= samplesPerBar) {
                    bars.add(barMax.toFloat())
                    barMax = 0
                    samplesInBar = 0
                    if (bars.size >= maxBars) {
                        return true
                    }
                }
            }
            return false
        }

        fun result(): FloatArray {
            if (samplesInBar > 0 && bars.size < maxBars) {
                bars.add(barMax.toFloat())
            }
            return bars.toFloatArray()
        }
    }
}
