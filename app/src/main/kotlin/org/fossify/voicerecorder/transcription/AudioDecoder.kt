package org.fossify.voicerecorder.transcription

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes a recorded audio file (m4a/mp3/ogg) at [uri] into mono 16 kHz float PCM
 * in the [-1, 1] range, which is what whisper.cpp expects.
 */
object AudioDecoder {
    const val WHISPER_SAMPLE_RATE = 16000
    private const val TIMEOUT_US = 10000L
    private const val PCM16_FULL_SCALE = 32768f

    fun decodeToWhisperInput(context: Context, uri: Uri): FloatArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = selectAudioTrack(extractor)
            require(trackIndex >= 0) { "No audio track found in $uri" }
            extractor.selectTrack(trackIndex)

            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val pcm = decodePcm16(extractor, format, mime)
            val mono = downmixToMono(pcm, channelCount)
            return resampleToFloat(mono, sampleRate, WHISPER_SAMPLE_RATE)
        } finally {
            extractor.release()
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

    private fun decodePcm16(
        extractor: MediaExtractor,
        format: MediaFormat,
        mime: String,
    ): ShortArray {
        val codec = MediaCodec.createDecoderByType(mime)
        val output = ArrayList<Short>()
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false

            while (!sawOutputEos) {
                if (!sawInputEos) {
                    sawInputEos = feedInput(codec, extractor)
                }
                sawOutputEos = drainOutput(codec, bufferInfo, output)
            }
        } finally {
            codec.stop()
            codec.release()
        }

        return output.toShortArray()
    }

    // Returns true once the end of the input stream has been queued.
    private fun feedInput(codec: MediaCodec, extractor: MediaExtractor): Boolean {
        val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
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
        return false
    }

    // Returns true once the end of the decoded output stream has been reached.
    private fun drainOutput(
        codec: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo,
        output: ArrayList<Short>,
    ): Boolean {
        val outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
        if (outIndex < 0) {
            return false
        }

        appendShorts(codec.getOutputBuffer(outIndex)!!, bufferInfo, output)
        codec.releaseOutputBuffer(outIndex, false)
        return bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
    }

    private fun appendShorts(
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        out: ArrayList<Short>,
    ) {
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val shorts = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        while (shorts.hasRemaining()) {
            out.add(shorts.get())
        }
    }

    private fun downmixToMono(pcm: ShortArray, channelCount: Int): ShortArray {
        if (channelCount <= 1) {
            return pcm
        }

        val monoLength = pcm.size / channelCount
        val mono = ShortArray(monoLength)
        for (i in 0 until monoLength) {
            var sum = 0
            for (ch in 0 until channelCount) {
                sum += pcm[i * channelCount + ch]
            }
            mono[i] = (sum / channelCount).toShort()
        }
        return mono
    }

    private fun resampleToFloat(mono: ShortArray, srcRate: Int, dstRate: Int): FloatArray {
        if (mono.isEmpty()) {
            return FloatArray(0)
        }

        if (srcRate == dstRate) {
            return FloatArray(mono.size) { mono[it] / PCM16_FULL_SCALE }
        }

        val ratio = dstRate.toDouble() / srcRate.toDouble()
        val outLength = (mono.size * ratio).toInt()
        val out = FloatArray(outLength)
        for (i in 0 until outLength) {
            val srcPos = i / ratio
            val idx = srcPos.toInt()
            val frac = (srcPos - idx).toFloat()
            val s0 = mono[idx]
            val s1 = if (idx + 1 < mono.size) mono[idx + 1] else s0
            val sample = s0 + (s1 - s0) * frac
            out[i] = sample / PCM16_FULL_SCALE
        }
        return out
    }
}
