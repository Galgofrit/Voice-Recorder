package org.fossify.voicerecorder.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.ParcelFileDescriptor
import org.fossify.commons.extensions.showErrorToast
import org.fossify.voicerecorder.extensions.config
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Records M4A by capturing raw PCM with [AudioRecord] and encoding AAC via [MediaCodec] +
 * [MediaMuxer]. Unlike [MediaRecorderWrapper] this gives us the PCM stream ([onPcmData]),
 * which is what live transcription needs.
 */
@Suppress("TooManyFunctions")
class AacRecorder(private val context: Context) : Recorder {
    companion object {
        private const val TIMEOUT_US = 10000L
        private const val MICROS_PER_SECOND = 1_000_000L
    }

    override var onPcmData: ((samples: ShortArray, count: Int, sampleRate: Int) -> Unit)? = null

    private val sampleRate = context.config.samplingRate
    private val isPaused = AtomicBoolean(false)
    private val isStopped = AtomicBoolean(false)
    private val amplitude = AtomicInteger(0)

    private var outputPath: String? = null
    private var fileDescriptor: ParcelFileDescriptor? = null

    private val minBufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )

    @SuppressLint("MissingPermission")
    private val audioRecord = AudioRecord(
        context.config.microphoneMode,
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        minBufferSize * 2
    )

    private lateinit var encoder: MediaCodec
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var totalFramesRead = 0L
    private var recordingThread: Thread? = null

    override fun setOutputFile(path: String) {
        outputPath = path
    }

    override fun setOutputFile(parcelFileDescriptor: ParcelFileDescriptor) {
        fileDescriptor = ParcelFileDescriptor.dup(parcelFileDescriptor.fileDescriptor)
    }

    override fun prepare() {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, context.config.bitrate)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBufferSize * 2)
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        @Suppress("DEPRECATION")
        muxer = if (fileDescriptor != null) {
            MediaMuxer(fileDescriptor!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } else {
            MediaMuxer(outputPath!!, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }
    }

    override fun start() {
        encoder.start()
        try {
            audioRecord.startRecording()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            context.showErrorToast(e)
            return
        }

        recordingThread = Thread { recordLoop() }.also { it.start() }
    }

    private fun recordLoop() {
        val buffer = ShortArray(minBufferSize)
        val bufferInfo = MediaCodec.BufferInfo()
        while (!isStopped.get()) {
            if (isPaused.get()) {
                continue
            }

            val count = audioRecord.read(buffer, 0, minBufferSize)
            if (count > 0) {
                onPcmData?.invoke(buffer, count, sampleRate)
                updateAmplitude(buffer, count)
                feedEncoder(buffer, count)
                drainEncoder(bufferInfo)
            }
        }

        finishEncoding(bufferInfo)
    }

    private fun feedEncoder(buffer: ShortArray, count: Int) {
        val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
        if (inIndex < 0) {
            return
        }

        val inputBuffer = encoder.getInputBuffer(inIndex)!!
        inputBuffer.clear()
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            inputBuffer.putShort(buffer[i])
        }
        val presentationTimeUs = totalFramesRead * MICROS_PER_SECOND / sampleRate
        encoder.queueInputBuffer(inIndex, 0, count * 2, presentationTimeUs, 0)
        totalFramesRead += count
    }

    // Drains whatever output is currently ready, without blocking (used during recording).
    private fun drainEncoder(bufferInfo: MediaCodec.BufferInfo) {
        while (true) {
            val outIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> startMuxer()
                outIndex < 0 -> return
                else -> {
                    writeEncodedSample(outIndex, bufferInfo)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        return
                    }
                }
            }
        }
    }

    // Signals end-of-stream and drains until the encoder emits its EOS, retrying the EOS
    // queue until an input buffer frees up so finalization can never get stuck.
    private fun finishEncoding(bufferInfo: MediaCodec.BufferInfo) {
        var eosQueued = false
        while (true) {
            if (!eosQueued) {
                val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val presentationTimeUs = totalFramesRead * MICROS_PER_SECOND / sampleRate
                    encoder.queueInputBuffer(
                        inIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                    eosQueued = true
                }
            }

            val outIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> startMuxer()
                outIndex >= 0 -> {
                    writeEncodedSample(outIndex, bufferInfo)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        return
                    }
                }
            }
        }
    }

    private fun startMuxer() {
        if (!muxerStarted) {
            trackIndex = muxer!!.addTrack(encoder.outputFormat)
            muxer!!.start()
            muxerStarted = true
        }
    }

    private fun writeEncodedSample(outIndex: Int, bufferInfo: MediaCodec.BufferInfo) {
        val encodedData = encoder.getOutputBuffer(outIndex)!!
        val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        if (!isConfig && bufferInfo.size > 0 && muxerStarted) {
            encodedData.position(bufferInfo.offset)
            encodedData.limit(bufferInfo.offset + bufferInfo.size)
            muxer!!.writeSampleData(trackIndex, encodedData, bufferInfo)
        }
        encoder.releaseOutputBuffer(outIndex, false)
    }

    override fun stop() {
        isPaused.set(false)
        isStopped.set(true)
        recordingThread?.join()
        try {
            audioRecord.stop()
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            // Already stopped; nothing to do.
        }
        if (muxerStarted) {
            muxer?.stop()
        }
    }

    override fun pause() {
        isPaused.set(true)
    }

    override fun resume() {
        isPaused.set(false)
    }

    override fun release() {
        try {
            encoder.release()
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            // Already released; nothing to do.
        }
        muxer?.release()
        audioRecord.release()
        fileDescriptor?.close()
    }

    override fun getMaxAmplitude(): Int = amplitude.get()

    private fun updateAmplitude(data: ShortArray, count: Int) {
        var sum = 0L
        var i = 0
        while (i < count) {
            sum += abs(data[i].toInt())
            i += 2
        }
        val samples = (count / 2).coerceAtLeast(1)
        amplitude.set((sum / samples).toInt())
    }
}
