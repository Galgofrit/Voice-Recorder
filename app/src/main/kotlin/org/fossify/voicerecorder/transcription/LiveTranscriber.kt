package org.fossify.voicerecorder.transcription

import android.content.Context
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.util.Log
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.fossify.commons.extensions.getFormattedDuration
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.voicerecorder.databases.TranscriptDatabase
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.helpers.MAX_TRANSCRIPTION_CHUNK_SECONDS
import org.fossify.voicerecorder.helpers.MIN_TRANSCRIPTION_CHUNK_SECONDS
import org.fossify.voicerecorder.models.Events
import org.fossify.voicerecorder.models.TRANSCRIPT_DONE
import org.fossify.voicerecorder.models.Transcript
import org.fossify.voicerecorder.models.Word
import org.fossify.voicerecorder.models.needsTimestamp
import org.greenrobot.eventbus.EventBus

/**
 * Transcribes a recording live, while it's being recorded. PCM is fed in via [feed] and
 * accumulated into a rolling 16kHz buffer. A background consumer runs VAD over the buffer and
 * closes a window at a silence gap between words, so a boundary never bisects a word — the
 * completed speech is transcribed and the in-progress tail is carried forward. Only when there
 * is no gap at all for [capSeconds] of continuous sound do we force a cut, carrying a short
 * overlap and text-deduping the join. The growing transcript is broadcast
 * ([Events.LiveTranscription]); on [finish] the tail is flushed and saved to the transcript DB.
 *
 * Transcription failures never propagate to the recorder — the recording is untouchable.
 */
@Suppress("TooManyFunctions")
class LiveTranscriber(private val context: Context, private val recordingName: String) {
    companion object {
        private const val TAG = "LiveTranscriber"
        private const val TARGET_RATE = 16000
        private const val PCM16_FULL_SCALE = 32768f
        private const val MS_PER_SECOND = 1000L

        // Tentative trailing marker shown while the speaker is paused mid-thought; dropped once
        // the next clip continues the sentence, kept only if speech never resumes.
        private const val ELLIPSIS = "…"

        // 60% opacity (0..255) for timestamp markers — matches the player's dimmed look.
        private const val TIMESTAMP_ALPHA = 153

        // Extra seconds beyond the (slider) target before a continuous, gapless run of speech is
        // force-cut mid-word with an overlap stitch.
        private const val CAP_EXTRA_SECONDS = 7

        // Trailing silence (samples @16kHz) after the last speech segment that marks the speaker
        // as having paused, so all buffered speech can be emitted. 0.3s.
        private const val COMPLETE_SILENCE_SAMPLES = 4800

        // Overlap carried past a forced (mid-word) cut so the next window can re-capture the cut
        // word; the join is text-deduped. 1.5s.
        private const val FORCED_OVERLAP_SAMPLES = 24000

        // Don't re-run VAD until at least this much new audio has arrived (samples @16kHz). 1s.
        private const val VAD_THROTTLE_SAMPLES = 16000

        // Max words checked when deduping a forced-cut overlap join.
        private const val MAX_STITCH_WORDS = 12

        // Repetition-loop guard: a window with at least this many words whose distinct-word
        // ratio is below the threshold is treated as a hallucinated decode loop and dropped.
        private const val MIN_WORDS_FOR_LOOP_CHECK = 6
        private const val MIN_WORD_DIVERSITY = 0.35f

        // Smallest immediately-repeated block (in words) collapsed as a whisper fill-repeat;
        // below this we leave repeats alone so genuine short ones ("very very") survive.
        private const val MIN_LOOP_PHRASE = 3
    }

    // Where to cut the rolling buffer: emit [emitStart, emitEnd), then drop everything up to
    // [consumeUntil) (the rest is carried forward). [forced] marks a mid-word cap cut whose
    // carried overlap must be text-deduped against the committed words on the next emit.
    private class Cut(
        val emitStart: Int,
        val emitEnd: Int,
        val consumeUntil: Int,
        val forced: Boolean,
    )

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val chunks = Channel<ShortArray>(Channel.UNLIMITED)
    private var consumerJob: Job? = null
    private val language = context.config.transcriptionLanguage

    // Target seconds of audio gathered before we look for a gap to cut at (≈ emit cadence).
    private val targetSeconds = context.config.transcriptionChunkSeconds
        .coerceIn(MIN_TRANSCRIPTION_CHUNK_SECONDS, MAX_TRANSCRIPTION_CHUNK_SECONDS)
    private val capSeconds = targetSeconds + CAP_EXTRA_SECONDS

    // Rolling buffer of not-yet-emitted audio (16kHz mono) and its absolute start time. Only
    // touched by the single consumer coroutine, so it needs no locking.
    private var buffer = FloatArray(0)
    private var bufferStartMs = 0L
    private var lastVadSamples = 0
    private var pendingStitch = false

    private var sourceRate = TARGET_RATE
    private val words = ArrayList<Word>()
    private val ellipsisDots = Regex("\\.{2,}")

    fun start() {
        consumerJob = scope.launch {
            for (chunk in chunks) {
                appendChunk(chunk)
                drainBuffer(flush = false)
            }
            drainBuffer(flush = true)
        }
    }

    fun feed(samples: ShortArray, count: Int, sampleRate: Int) {
        sourceRate = sampleRate
        chunks.trySend(samples.copyOf(count))
    }

    // Stops accepting audio, flushes whatever is left, saves the transcript, then cleans up.
    // [onComplete] runs after the result is persisted.
    fun finish(onComplete: () -> Unit) {
        scope.launch {
            try {
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

    private fun appendChunk(chunk: ShortArray) {
        val resampled = resampleToFloat(chunk, sourceRate)
        if (resampled.isEmpty()) {
            return
        }
        val grown = FloatArray(buffer.size + resampled.size)
        buffer.copyInto(grown, 0)
        resampled.copyInto(grown, buffer.size)
        buffer = grown
    }

    // Emits as many windows as the buffer currently allows. [flush] forces out the remainder
    // regardless of length (used at the end of the recording).
    private suspend fun drainBuffer(flush: Boolean) {
        while (buffer.isNotEmpty()) {
            if (!flush) {
                if (samplesToMs(buffer.size) < targetSeconds * MS_PER_SECOND) {
                    return
                }
                if (buffer.size - lastVadSamples < VAD_THROTTLE_SAMPLES) {
                    return
                }
            }
            lastVadSamples = buffer.size

            val segments = TranscriptionEngine.getVadContext(context).segments(buffer)
            val cut = decideCut(segments, buffer.size, flush) ?: return

            val stitch = pendingStitch
            if (cut.emitStart < cut.emitEnd) {
                emit(
                    clip = buffer.copyOfRange(cut.emitStart, cut.emitEnd),
                    offsetMs = bufferStartMs + samplesToMs(cut.emitStart),
                    stitch = stitch,
                )
            }
            bufferStartMs += samplesToMs(cut.consumeUntil)
            buffer = buffer.copyOfRange(cut.consumeUntil, buffer.size)
            lastVadSamples = buffer.size
            pendingStitch = cut.forced
        }
    }

    // Decides where to cut, given VAD speech segments (flat sample-index pairs) over a buffer of
    // [length] samples. Returns null to keep accumulating.
    @Suppress("ReturnCount")
    private fun decideCut(segments: IntArray, length: Int, flush: Boolean): Cut? {
        val segCount = segments.size / 2
        if (segCount == 0) {
            // No speech: drop the silence but keep ~1s tail in case a word is just beginning.
            return Cut(0, 0, (length - TARGET_RATE).coerceAtLeast(0), forced = false)
        }

        val firstStart = segments[0]
        val lastEnd = segments[2 * (segCount - 1) + 1]

        if (flush) {
            return Cut(firstStart, lastEnd, length, forced = false)
        }

        // Speaker paused after the last segment -> everything buffered is complete speech.
        if (length - lastEnd >= COMPLETE_SILENCE_SAMPLES) {
            return Cut(firstStart, lastEnd, length, forced = false)
        }

        // Buffer ends mid-speech. If there's an earlier completed segment, emit up to it and
        // carry the in-progress last segment (cut falls in the gap between them — safe).
        if (segCount >= 2) {
            val lastCompletedEnd = segments[2 * (segCount - 2) + 1]
            val inProgressStart = segments[2 * (segCount - 1)]
            return Cut(firstStart, lastCompletedEnd, inProgressStart, forced = false)
        }

        // One continuous in-progress segment. Wait for a gap unless we've hit the cap, in which
        // case force a mid-word cut and carry an overlap for stitching.
        if (length >= capSeconds * TARGET_RATE) {
            val overlap = FORCED_OVERLAP_SAMPLES.coerceAtMost(length / 2)
            return Cut(firstStart, length, length - overlap, forced = true)
        }
        return null
    }

    private suspend fun emit(clip: FloatArray, offsetMs: Long, stitch: Boolean) {
        try {
            val whisper = TranscriptionEngine.getContext(context)
            val started = System.currentTimeMillis()
            val result = whisper.transcribeWithWords(clip, language = language)
            val rawWords = result.words.map {
                Word(it.text, it.startMs + offsetMs, it.endMs + offsetMs)
            }
            Log.i(
                TAG,
                "Emit (${clip.size / TARGET_RATE}s @ ${offsetMs}ms, stitch=$stitch) in " +
                    "${System.currentTimeMillis() - started}ms raw=\"" +
                    "${rawWords.joinToString("") { it.text }.trim()}\""
            )
            // Check the raw output for a decode loop *before* collapsing — collapse would
            // shrink "X X X…" down to one "X" and hide the degeneracy. A looped window is a
            // hallucination (short phrase repeated many times), so drop it entirely.
            if (isRepetitionLoop(rawWords)) {
                Log.i(TAG, "Emit @${offsetMs}ms dropped, repetition loop")
                return
            }
            // The clip may trail off into a "…" (the speaker pausing to think). Strip ellipses
            // from the committed words, but remember whether this clip ended on one so we can
            // keep a single tentative "…" at the live end — it's dropped automatically when the
            // next clip continues the sentence, and only survives if speech never resumes.
            val endedThinking = rawWords.isNotEmpty() && rawWords.last().text.trim().let {
                it.endsWith("…") || it.endsWith("..")
            }
            val clean = rawWords
                .map { Word(stripEllipsis(it.text), it.startMs, it.endMs) }
                .filter { it.text.isNotBlank() }
            val windowWords = collapseTrailingRepeat(clean)

            // A continuation arrived, so drop the previous tentative "…" before stitching.
            if (words.lastOrNull()?.text?.trim() == ELLIPSIS) {
                words.removeAt(words.size - 1)
            }
            // Only a forced (mid-word) cut overlaps already-committed audio, so dedup the join
            // there; normal cuts fall in silence and append cleanly.
            val toAdd = if (stitch) {
                windowWords.drop(leadingDuplicateCount(words, windowWords))
            } else {
                windowWords
            }
            words.addAll(toAdd)
            if (endedThinking && words.isNotEmpty()) {
                val lastMs = words.last().endMs
                words.add(Word(" $ELLIPSIS", lastMs, lastMs))
            }
            EventBus.getDefault().post(Events.LiveTranscription(displayText()))
            saveTranscript()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.e(TAG, "Live emit transcription failed", e)
        }
    }

    // Largest run k (0..MAX_STITCH_WORDS) where the last k committed words equal the first k
    // incoming words by normalized text — the duplicated overlap to drop from a forced join.
    private fun leadingDuplicateCount(committed: List<Word>, incoming: List<Word>): Int {
        var k = minOf(committed.size, incoming.size, MAX_STITCH_WORDS)
        while (k > 0) {
            val matched = (0 until k).all {
                val c = normalizeWord(committed[committed.size - k + it].text)
                c.isNotEmpty() && c == normalizeWord(incoming[it].text)
            }
            if (matched) {
                return k
            }
            k--
        }
        return 0
    }

    private fun normalizeWord(text: String) =
        text.trim().lowercase().trim { !it.isLetterOrDigit() }

    // Removes ellipses ("…" or a run of 2+ dots) while keeping single periods (real sentence
    // ends). whisper sprinkles these onto clips it sees as unfinished, which is most of them
    // once VAD cuts at thinking-pauses.
    private fun stripEllipsis(text: String) =
        text.replace("…", "").replace(ellipsisDots, "")

    // Trims an immediately-repeated trailing block of words ("…X X" -> "…X"), which is how
    // whisper pads a clip that ends in silence by replaying its last phrase. Only blocks of at
    // least MIN_LOOP_PHRASE words are collapsed, so genuine short repeats ("very very") survive.
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

    // A whisper greedy-decode loop shows up as many words but very few distinct ones (e.g.
    // "ok ok ok…"). Real speech is far more varied, so a low distinct-word ratio flags a loop.
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

    private fun samplesToMs(samples: Int) = samples.toLong() * MS_PER_SECOND / TARGET_RATE

    private fun currentText() = words.joinToString("") { it.text }.trim()

    // Same words as [currentText] but with timestamp markers inserted at long pauses (and at the
    // start), styled small + dimmed to match the player. The stored transcript stays clean.
    private fun displayText(): CharSequence {
        val builder = SpannableStringBuilder()
        val timestampColor = ColorUtils.setAlphaComponent(context.getProperTextColor(), TIMESTAMP_ALPHA)
        val timestampSize =
            context.resources.getDimensionPixelSize(org.fossify.commons.R.dimen.smaller_text_size)
        var prevEndMs = 0L
        words.forEachIndexed { index, word ->
            if (needsTimestamp(index, prevEndMs, word.startMs)) {
                if (builder.isNotEmpty()) {
                    builder.append("\n")
                }
                val tsStart = builder.length
                builder.append((word.startMs / MS_PER_SECOND).toInt().getFormattedDuration())
                builder.setSpan(
                    AbsoluteSizeSpan(timestampSize), tsStart, builder.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                builder.setSpan(
                    ForegroundColorSpan(timestampColor), tsStart, builder.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                builder.append("\n")
            }
            prevEndMs = word.endMs
            builder.append(word.text)
        }
        return builder
    }

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
