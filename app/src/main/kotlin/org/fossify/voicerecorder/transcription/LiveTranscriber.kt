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

        // Padding kept around the VAD speech span when trimming a window (samples @ 16kHz),
        // so we never clip the onset/tail of a word. 0.2s.
        private const val VAD_PAD_SAMPLES = 3200

        // Seam dedup: how many words at the join to check, and how close in time two identical
        // words must be to count as the same spoken instance (vs a genuine repeated word).
        private const val MAX_SEAM_WORDS = 12
        private const val SEAM_TOLERANCE_MS = 1000L

        // Repetition-loop guard: a window with at least this many words whose distinct-word
        // ratio is below the threshold is treated as a hallucinated decode loop and dropped.
        private const val MIN_WORDS_FOR_LOOP_CHECK = 6
        private const val MIN_WORD_DIVERSITY = 0.35f

        // Smallest immediately-repeated block (in words) collapsed as a whisper fill-repeat;
        // below this we leave repeats alone so genuine short ones ("very very") survive.
        private const val MIN_LOOP_PHRASE = 3
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
            // Gate on voice activity: if VAD finds no speech in the window, never hand it to
            // whisper — that's how noise/silence/clicks are stopped from becoming hallucinated
            // text. Anything already captured in the overlap stays put.
            val bounds = TranscriptionEngine.getVadContext(context).speechBounds(audio)
            if (bounds[0] < 0) {
                Log.i(TAG, "Window @ ${segment.startMs}ms skipped, no speech (VAD)")
                return
            }
            // Trim to the speech span (with a little padding) so whisper never sees the silent
            // head/tail it would otherwise "fill" by replaying a phrase. Word timestamps from
            // the trimmed clip are shifted back by the trim offset.
            val trimStart = (bounds[0] - VAD_PAD_SAMPLES).coerceAtLeast(0)
            val trimEnd = (bounds[1] + VAD_PAD_SAMPLES).coerceAtMost(audio.size)
            val speech = audio.copyOfRange(trimStart, trimEnd)
            val offsetMs = segment.startMs + trimStart * MS_PER_SECOND / TARGET_RATE

            val whisper = TranscriptionEngine.getContext(context)
            val started = System.currentTimeMillis()
            val result = whisper.transcribeWithWords(speech, language = language)
            Log.i(
                TAG,
                "Window (${speech.size / TARGET_RATE}s @ ${segment.startMs}ms) transcribed in " +
                    "${System.currentTimeMillis() - started}ms"
            )
            val rawWords = result.words.map {
                Word(it.text, it.startMs + offsetMs, it.endMs + offsetMs)
            }
            // whisper sometimes repeats a phrase to "fill" a window that ends in silence
            // ("…before Monday. …before Monday."); collapse an immediately-repeated trailing
            // block so the doubling never reaches the transcript.
            val windowWords = collapseTrailingRepeat(rawWords)
            // Drop a window whose text is degenerate repetition (a greedy decode loop) — it's a
            // hallucination, not speech, and the overlapping next window will re-cover the audio.
            if (isRepetitionLoop(windowWords)) {
                Log.i(TAG, "Window @ ${segment.startMs}ms dropped, repetition loop")
                return
            }
            // Re-transcribing the overlap supersedes whatever we'd committed for that region,
            // so drop the old words from where this window's speech actually begins and
            // re-append. Words before that (earlier, already-final audio) are left untouched.
            words.removeAll { it.startMs >= offsetMs }
            val seamDropped = seamOverlap(words, windowWords)
            Log.i(
                TAG,
                "Window @${segment.startMs}ms seamDrop=$seamDropped " +
                    "raw=\"${rawWords.joinToString("") { it.text }.trim()}\""
            )
            words.addAll(windowWords.drop(seamDropped))
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
        // Same spoken word only if the text matches and it lands at about the same point in
        // time. We deliberately do NOT treat mere time-overlap as a duplicate: across windows
        // with different trim offsets, two *different* adjacent words ("and" / "that") can
        // overlap in time, and matching them would wrongly delete a correct word at the seam.
        val na = normalizeWord(a.text)
        return na.isNotEmpty() &&
            na == normalizeWord(b.text) &&
            abs(b.startMs - a.startMs) <= SEAM_TOLERANCE_MS
    }

    private fun normalizeWord(text: String) =
        text.trim().lowercase().trim { !it.isLetterOrDigit() }

    // Trims an immediately-repeated trailing block of words ("…X X" -> "…X"), which is how
    // whisper pads a window that ends in silence by replaying its last phrase. Only blocks of
    // at least MIN_LOOP_PHRASE words are collapsed, so genuine short repeats ("very very") are
    // left alone. Loops until stable, so "X X X" collapses fully.
    private fun collapseTrailingRepeat(words: List<Word>): List<Word> {
        val result = words.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            val n = result.size
            for (p in n / 2 downTo MIN_LOOP_PHRASE) {
                val isRepeat = (0 until p).all {
                    normalizeWord(result[n - p + it].text) == normalizeWord(result[n - 2 * p + it].text)
                }
                if (isRepeat) {
                    repeat(p) { result.removeAt(result.size - 1) }
                    changed = true
                    break
                }
            }
        }
        return result
    }

    // A whisper greedy-decode loop shows up as a window with many words but very few distinct
    // ones (e.g. "ok ok ok ok…" or "the cat the cat the cat"). Real speech is far more varied,
    // so a low distinct-word ratio over enough words flags a hallucinated repetition.
    private fun isRepetitionLoop(windowWords: List<Word>): Boolean {
        if (windowWords.size < MIN_WORDS_FOR_LOOP_CHECK) {
            return false
        }
        val normalized = windowWords.map { normalizeWord(it.text) }.filter { it.isNotEmpty() }
        if (normalized.size < MIN_WORDS_FOR_LOOP_CHECK) {
            return false
        }
        val diversity = normalized.distinct().size.toFloat() / normalized.size
        return diversity < MIN_WORD_DIVERSITY
    }

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
