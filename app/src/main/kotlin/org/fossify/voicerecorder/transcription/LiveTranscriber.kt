package org.fossify.voicerecorder.transcription

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.fossify.voicerecorder.databases.TranscriptDatabase
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.helpers.MAX_TRANSCRIPTION_CHUNK_SECONDS
import org.fossify.voicerecorder.helpers.MIN_TRANSCRIPTION_CHUNK_SECONDS
import org.fossify.voicerecorder.models.Events
import org.fossify.voicerecorder.models.TRANSCRIPT_DONE
import org.fossify.voicerecorder.models.Transcript
import org.fossify.voicerecorder.models.Word
import org.greenrobot.eventbus.EventBus
import kotlin.math.abs

/**
 * Transcribes a recording live, while it's being recorded. PCM is fed in via [feed]; once
 * ~[chunkSeconds] of new audio has accumulated, a window of that audio plus the previous
 * ~[OVERLAP_SECONDS] is handed to whisper on a background worker, and the growing transcript
 * is broadcast ([Events.LiveTranscription]). On [finish] the tail is transcribed and the
 * result saved to the transcript DB, so playback needs no extra work.
 *
 * The overlap gives whisper context across chunk boundaries so words spoken across a cut
 * aren't chopped; each window re-transcribes the carried-over region and replaces the
 * previously committed words there (keyed by timestamp), so the overlap is never duplicated.
 *
 * Transcription failures never propagate to the recorder — the recording is untouchable.
 */
@Suppress("TooManyFunctions")
class LiveTranscriber(private val context: Context, private val recordingName: String) {
    companion object {
        private const val TAG = "LiveTranscriber"

        // How much of the previous window is carried over for cross-boundary context.
        private const val OVERLAP_SECONDS = 3
        private const val TARGET_RATE = 16000
        private const val PCM16_FULL_SCALE = 32768f
        private const val MS_PER_SECOND = 1000L

        // Seam dedup: how many words at the join to check, and how close in time two identical
        // words must be to count as the same spoken instance (vs a genuine repeated word).
        private const val MAX_SEAM_WORDS = 12
        private const val SEAM_TOLERANCE_MS = 1000L
    }

    // A window of audio handed to whisper, tagged with the absolute time (ms into the
    // recording) at which the window starts, so word timestamps can be mapped back.
    private class Segment(val audio: ShortArray, val startMs: Long)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val chunks = Channel<Segment>(Channel.UNLIMITED)
    private var consumerJob: Job? = null
    private val language = context.config.transcriptionLanguage

    // Seconds of new audio per window — user-configurable; latency to first text ≈ this.
    private val chunkSeconds = context.config.transcriptionChunkSeconds
        .coerceIn(MIN_TRANSCRIPTION_CHUNK_SECONDS, MAX_TRANSCRIPTION_CHUNK_SECONDS)

    // PCM accumulated (at the source rate) until it makes up a chunk, plus the tail of the
    // previous chunk carried over as overlap. Guarded because [feed] runs on the recording
    // thread while [finish] drains from a coroutine.
    private val pending = ArrayList<ShortArray>()
    private var pendingCount = 0
    private var carry = ShortArray(0)
    private var committedMs = 0L
    private var sourceRate = TARGET_RATE

    private val words = ArrayList<Word>()

    fun start() {
        consumerJob = scope.launch {
            for (segment in chunks) {
                transcribeSegment(segment)
            }
        }
    }

    fun feed(samples: ShortArray, count: Int, sampleRate: Int) {
        sourceRate = sampleRate
        val chunkSamples = sampleRate * chunkSeconds
        val segment = synchronized(pending) {
            pending.add(samples.copyOf(count))
            pendingCount += count
            if (pendingCount >= chunkSamples) takeSegment() else null
        }
        if (segment != null) {
            chunks.trySend(segment)
        }
    }

    // Stops accepting audio, transcribes whatever is left, saves the transcript, then cleans
    // up. [onComplete] runs after the result is persisted.
    fun finish(onComplete: () -> Unit) {
        scope.launch {
            try {
                val tail = synchronized(pending) { takeSegment() }
                if (tail != null) {
                    runCatching { chunks.send(tail) }
                }
                runCatching { chunks.close() }
                consumerJob?.join()
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Log.e(TAG, "Live finish failed; saving what we have", e)
            } finally {
                saveTranscript()
                onComplete()
            }
        }
    }

    fun cancel() {
        chunks.close()
        scope.coroutineContext[Job]?.cancel()
    }

    // Builds the next window: the carried-over overlap followed by the new pending audio.
    // Advances [committedMs] by the new audio and keeps its tail as the next overlap.
    // Caller must hold the [pending] lock.
    private fun takeSegment(): Segment? {
        if (pendingCount == 0) {
            return null
        }
        val fresh = ShortArray(pendingCount)
        var offset = 0
        for (part in pending) {
            part.copyInto(fresh, offset)
            offset += part.size
        }
        pending.clear()
        pendingCount = 0

        val window = ShortArray(carry.size + fresh.size)
        carry.copyInto(window, 0)
        fresh.copyInto(window, carry.size)
        val windowStartMs = committedMs - carry.size * MS_PER_SECOND / sourceRate

        committedMs += fresh.size * MS_PER_SECOND / sourceRate
        val overlapSamples = (OVERLAP_SECONDS * sourceRate).coerceAtMost(fresh.size)
        carry = fresh.copyOfRange(fresh.size - overlapSamples, fresh.size)

        return Segment(window, windowStartMs)
    }

    private suspend fun transcribeSegment(segment: Segment) {
        try {
            val audio = resampleToFloat(segment.audio, sourceRate)
            if (audio.isEmpty()) {
                return
            }
            val whisper = TranscriptionEngine.getContext(context)
            val started = System.currentTimeMillis()
            val result = whisper.transcribeWithWords(audio, language = language)
            Log.i(
                TAG,
                "Window (${audio.size / TARGET_RATE}s @ ${segment.startMs}ms) transcribed in " +
                    "${System.currentTimeMillis() - started}ms"
            )
            // Re-transcribing the overlap supersedes whatever we'd committed for that region,
            // so drop the old words there and re-append the whole window. Words before the
            // window start (earlier, already-final audio) are left untouched.
            words.removeAll { it.startMs >= segment.startMs }
            val windowWords = result.words.map {
                Word(it.text, it.startMs + segment.startMs, it.endMs + segment.startMs)
            }
            words.addAll(windowWords.drop(seamOverlap(words, windowWords)))
            EventBus.getDefault().post(Events.LiveTranscription(currentText()))
            // Persist progress as we go, so stopping never waits on a backlog and a crash
            // can't lose what's been transcribed.
            saveTranscript()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.e(TAG, "Live window transcription failed", e)
        }
    }

    // Number of leading [incoming] words that duplicate the trailing [committed] words at the
    // seam (same word picked up by both the previous window's tail and this window's overlap).
    // Returns the largest run length k (0..MAX_SEAM_WORDS) such that the last k committed words
    // match the first k incoming words by text and lie at about the same point in time.
    private fun seamOverlap(committed: List<Word>, incoming: List<Word>): Int {
        var k = minOf(committed.size, incoming.size, MAX_SEAM_WORDS)
        while (k > 0) {
            val matched = (0 until k).all {
                isSeamDuplicate(committed[committed.size - k + it], incoming[it])
            }
            if (matched) {
                return k
            }
            k--
        }
        return 0
    }

    private fun isSeamDuplicate(a: Word, b: Word): Boolean {
        // Primary signal is time: if the two words occupy nearly the same slot, they're the
        // same spoken instance — robust even when the windows transcribe it differently. Text
        // equality is a fallback for when the timing estimates drift apart.
        val overlapMs = minOf(a.endMs, b.endMs) - maxOf(a.startMs, b.startMs)
        val shorterMs = minOf(a.endMs - a.startMs, b.endMs - b.startMs).coerceAtLeast(1)
        if (overlapMs * 2 > shorterMs) {
            return true
        }
        val na = normalizeWord(a.text)
        return na.isNotEmpty() &&
            na == normalizeWord(b.text) &&
            abs(b.startMs - a.startMs) <= SEAM_TOLERANCE_MS
    }

    private fun normalizeWord(text: String) =
        text.trim().lowercase().trim { !it.isLetterOrDigit() }

    private fun currentText() = words.joinToString("") { it.text }.trim()

    private fun saveTranscript() {
        try {
            val dao = TranscriptDatabase.getInstance(context).transcriptDao()
            dao.upsert(
                Transcript(
                    recordingName = recordingName,
                    text = currentText(),
                    status = TRANSCRIPT_DONE,
                    language = language,
                    createdAt = System.currentTimeMillis(),
                    wordsJson = Word.toJson(words),
                )
            )
            EventBus.getDefault().post(Events.TranscriptionUpdated(recordingName))
            Log.i(TAG, "Saved live transcript for '$recordingName' (${words.size} words)")
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.e(TAG, "Saving live transcript failed", e)
        }
    }

    private fun resampleToFloat(mono: ShortArray, srcRate: Int): FloatArray {
        if (mono.isEmpty()) {
            return FloatArray(0)
        }
        if (srcRate == TARGET_RATE) {
            return FloatArray(mono.size) { mono[it] / PCM16_FULL_SCALE }
        }

        val ratio = TARGET_RATE.toDouble() / srcRate
        val outLength = (mono.size * ratio).toInt()
        return FloatArray(outLength) { i ->
            val srcPos = i / ratio
            val idx = srcPos.toInt()
            val s0 = mono[idx]
            val s1 = if (idx + 1 < mono.size) mono[idx + 1] else s0
            val frac = (srcPos - idx).toFloat()
            (s0 + (s1 - s0) * frac) / PCM16_FULL_SCALE
        }
    }
}
