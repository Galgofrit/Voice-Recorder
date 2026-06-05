package org.fossify.voicerecorder.activities

import android.content.Context.RECEIVER_NOT_EXPORTED
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.SeekBar
import androidx.core.graphics.ColorUtils
import androidx.core.net.toUri
import com.google.android.material.tabs.TabLayout
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beGoneIf
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getFormattedDuration
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.openPathIntent
import org.fossify.commons.extensions.sharePathsIntent
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isTiramisuPlus
import org.fossify.commons.models.RadioItem
import org.fossify.voicerecorder.BuildConfig
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.databases.TranscriptDatabase
import org.fossify.voicerecorder.databinding.ActivityPlaybackBinding
import org.fossify.voicerecorder.dialogs.DeleteConfirmationDialog
import org.fossify.voicerecorder.dialogs.RenameRecordingDialog
import org.fossify.voicerecorder.dialogs.TranscribeLanguageDialog
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.extensions.deleteRecordings
import org.fossify.voicerecorder.extensions.trashRecordings
import org.fossify.voicerecorder.helpers.WaveformCache
import org.fossify.voicerecorder.models.Events
import org.fossify.voicerecorder.models.Recording
import org.fossify.voicerecorder.models.TRANSCRIPT_DONE
import org.fossify.voicerecorder.models.TRANSCRIPT_FAILED
import org.fossify.voicerecorder.models.TRANSCRIPT_PROCESSING
import org.fossify.voicerecorder.models.Transcript
import org.fossify.voicerecorder.models.Word
import org.fossify.voicerecorder.models.needsTimestamp
import org.fossify.voicerecorder.receivers.BecomingNoisyReceiver
import org.fossify.voicerecorder.transcription.TranscriptionWorker
import org.fossify.voicerecorder.transcription.WhisperLanguages
import org.fossify.voicerecorder.transcription.WaveformRangeDecoder
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.Executors

@Suppress("TooManyFunctions")
class PlaybackActivity : SimpleActivity() {
    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_TITLE = "title"
        const val EXTRA_DURATION = "duration"

        private const val SKIP_BACK_MS = 5000
        private const val SKIP_FORWARD_MS = 10000
        private const val MS_PER_SECOND = 1000
        private const val PROGRESS_INTERVAL_MS = 50L
        private const val WORD_HIGHLIGHT_INTERVAL_MS = 80L
        private const val MARKER_ALPHA = 0.4f
        private const val WAVEFORM_BARS_PER_SECOND = 12
        private const val WAVEFORM_UNPLAYED_ALPHA = 0.3f
        private const val CARD_TINT_RATIO = 0.05f

        // 60% opacity (0..255) — matches the transcript language label's dimmed look.
        private const val TIMESTAMP_ALPHA = 153
        private const val WAVEFORM_BARS_PER_CHUNK = 240
    }

    private val binding by lazy { ActivityPlaybackBinding.inflate(layoutInflater) }

    private var player: MediaPlayer? = null
    private var progressTimer = Timer()
    private var wordTimer = Timer()
    private var bus: EventBus? = null

    private var recordingPath = ""
    private var recordingTitle = ""
    private var durationSec = 0

    private var words: List<Word> = emptyList()
    private var wordRanges: List<IntRange> = emptyList()
    private var transcriptSpannable: Spannable? = null
    private var wordHighlightSpan: BackgroundColorSpan? = null
    private var highlightedWordIndex = -1

    private var waveformReady = false
    private var anchorPositionMs = 0
    private var anchorClockMs = 0L

    // Windowed waveform: the full timeline is sized up front from the duration, but bars
    // are decoded lazily, one chunk at a time, only for the range that scrolls into view.
    private var totalBars = 0
    private var rawBars = FloatArray(0)
    private var chunkDecoded = BooleanArray(0)
    private var waveformPeak = 1f
    private val waveformExecutor = Executors.newSingleThreadExecutor()

    private var becomingNoisyReceiver: BecomingNoisyReceiver? = null
    private var isReceiverRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        recordingPath = intent.getStringExtra(EXTRA_PATH).orEmpty()
        recordingTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        durationSec = intent.getIntExtra(EXTRA_DURATION, 0)

        setupEdgeToEdge(padBottomSystem = listOf(binding.playbackControlsWrapper))
        binding.playbackToolbar.title = recordingTitle

        bus = EventBus.getDefault().apply { register(this@PlaybackActivity) }
        setupViews()
        initMediaPlayer()
        loadTranscript()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.playbackAppbar, NavigationIcon.Arrow)
        updateTextColors(binding.root)
        setupColors()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterNoisyAudioReceiver()
        player?.release()
        player = null
        progressTimer.cancel()
        stopWordTimer()
        waveformExecutor.shutdownNow()
        bus?.unregister(this)
    }

    private fun setupViews() {
        binding.playbackToolbar.setOnClickListener { renameRecording() }
        binding.playbackToolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.cab_transcribe -> showTranscribeLanguageDialog()
                R.id.cab_rename -> renameRecording()
                R.id.cab_share -> sharePathsIntent(arrayListOf(recordingPath), BuildConfig.APPLICATION_ID)
                R.id.cab_delete -> askConfirmDelete()
                R.id.cab_open_with -> openPathIntent(
                    recordingPath, true, BuildConfig.APPLICATION_ID, "audio/*"
                )

                R.id.playback_speed -> showPlaybackSpeedDialog()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        colorOverflowMenu()

        binding.playPauseBtn.setOnClickListener { togglePlayPause() }
        binding.replayBtn.setOnClickListener { skip(forward = false) }
        binding.forwardBtn.setOnClickListener { skip(forward = true) }

        binding.playerProgressbar.max = durationSec
        binding.playerProgressMax.text = durationSec.getFormattedDuration()
        binding.playerProgressbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    player?.seekTo(progress * MS_PER_SECOND)
                    reanchorPlayback()
                    binding.playerProgressCurrent.text = progress.getFormattedDuration()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })

        binding.transcribeButton.setOnClickListener { showTranscribeLanguageDialog() }

        setupTabs()
        setupWaveform()
    }

    // setupTopAppBar themes the bar but not the overflow popup's item text, which renders
    // unreadable on light themes. Colour each menu title with the theme's proper text colour.
    private fun colorOverflowMenu() {
        val color = getProperTextColor()
        val menu = binding.playbackToolbar.menu
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            val title = item.title ?: continue
            item.title = SpannableString(title).apply {
                setSpan(ForegroundColorSpan(color), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun setupTabs() {
        updateTabViews(selectedTab = 0)
        binding.playbackTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = updateTabViews(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    private fun updateTabViews(selectedTab: Int) {
        val showWaveform = selectedTab == 0
        binding.waveformView.beVisibleIf(showWaveform)
        binding.waveformLoading.beVisibleIf(showWaveform && !waveformReady)
        binding.transcriptPanel.beVisibleIf(!showWaveform)
    }

    private fun setupWaveform() {
        val primaryColor = getProperPrimaryColor()
        binding.waveformView.setColors(primaryColor, primaryColor.adjustAlpha(WAVEFORM_UNPLAYED_ALPHA))
        binding.waveformView.onSeek = ::onWaveformSeek
        binding.waveformView.positionProvider = {
            val durationMs = player?.duration ?: 0
            if (durationMs > 0) estimatedPositionMs().toFloat() / durationMs else 0f
        }
    }

    // Sizes the waveform timeline from the known duration and shows it immediately. A cached
    // envelope loads instantly; otherwise a single worker decodes it, always doing the chunk
    // at the current position first so seeks resolve right away while the rest fills in.
    private fun initWaveform() {
        if (totalBars > 0) {
            return
        }

        val durationMs = player?.duration ?: 0
        if (durationMs <= 0) {
            return
        }

        totalBars = (durationMs / 1000f * WAVEFORM_BARS_PER_SECOND).toInt().coerceAtLeast(1)
        waveformReady = true
        binding.waveformLoading.beGoneIf(true)
        binding.waveformView.setProgress(currentFraction())

        val cached = WaveformCache.load(this, recordingTitle)
        if (cached != null && cached.isNotEmpty()) {
            rawBars = cached
            binding.waveformView.setAmplitudes(cached)
            return
        }

        rawBars = FloatArray(totalBars)
        chunkDecoded = BooleanArray((totalBars + WAVEFORM_BARS_PER_CHUNK - 1) / WAVEFORM_BARS_PER_CHUNK)
        binding.waveformView.setAmplitudes(FloatArray(totalBars))
        waveformExecutor.execute { fillWaveform() }
    }

    // Decodes the whole waveform on one worker, always picking the undecoded chunk nearest
    // the current position so whatever is on screen fills first. Caches the result when done.
    private fun fillWaveform() {
        val decoder = try {
            WaveformRangeDecoder(this, recordingPath.toUri(), WAVEFORM_BARS_PER_SECOND)
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            null
        } ?: return

        try {
            while (!Thread.currentThread().isInterrupted) {
                val chunk = nextChunkToDecode() ?: break
                val startBar = chunk * WAVEFORM_BARS_PER_CHUNK
                val endBar = ((chunk + 1) * WAVEFORM_BARS_PER_CHUNK).coerceAtMost(totalBars)
                val slice = try {
                    decoder.decodeRange(startBar, endBar)
                } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                    null
                }
                if (slice != null) {
                    runOnUiThread { applyWaveformSlice(slice) }
                }
            }
            runOnUiThread { cacheWaveform() }
        } finally {
            decoder.close()
        }
    }

    // Picks (and marks) the not-yet-decoded chunk closest to the current position; null when
    // all are done. Only the worker touches [chunkDecoded], so no locking is needed.
    private fun nextChunkToDecode(): Int? {
        val chunkCount = chunkDecoded.size
        if (chunkCount == 0) {
            return null
        }

        val durationMs = player?.duration ?: 0
        val positionMs = player?.currentPosition ?: 0
        val centerChunk = if (durationMs > 0) {
            (positionMs.toFloat() / durationMs * chunkCount).toInt().coerceIn(0, chunkCount - 1)
        } else {
            0
        }

        var best = -1
        var bestDistance = Int.MAX_VALUE
        for (chunk in 0 until chunkCount) {
            if (!chunkDecoded[chunk]) {
                val distance = kotlin.math.abs(chunk - centerChunk)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = chunk
                }
            }
        }

        if (best < 0) {
            return null
        }
        chunkDecoded[best] = true
        return best
    }

    private fun cacheWaveform() {
        if (rawBars.isEmpty()) {
            return
        }
        val peak = waveformPeak
        WaveformCache.save(this, recordingTitle, FloatArray(totalBars) { rawBars[it] / peak })
    }

    private fun applyWaveformSlice(slice: WaveformRangeDecoder.Slice) {
        if (rawBars.isEmpty()) {
            return
        }

        for (i in slice.peaks.indices) {
            val bar = slice.startBar + i
            if (bar in rawBars.indices) {
                val peak = slice.peaks[i]
                rawBars[bar] = peak
                if (peak > waveformPeak) {
                    waveformPeak = peak
                }
            }
        }
        binding.waveformView.setAmplitudes(FloatArray(totalBars) { rawBars[it] / waveformPeak })
    }

    // Re-sync the clock anchor used to interpolate position between player updates.
    private fun reanchorPlayback() {
        anchorPositionMs = player?.currentPosition ?: 0
        anchorClockMs = SystemClock.elapsedRealtime()
        binding.waveformView.setProgress(currentFraction())
    }

    private fun currentFraction(): Float {
        val durationMs = player?.duration ?: 0
        return if (durationMs > 0) (player?.currentPosition ?: 0).toFloat() / durationMs else 0f
    }

    // While playing, extrapolate from the anchor by wall-clock * speed for smooth motion.
    private fun estimatedPositionMs(): Int {
        val currentPlayer = player ?: return 0
        if (!currentPlayer.isPlaying) {
            return currentPlayer.currentPosition
        }
        val elapsed = (SystemClock.elapsedRealtime() - anchorClockMs).toDouble() * config.playbackSpeed
        return (anchorPositionMs + elapsed).toInt().coerceIn(0, currentPlayer.duration)
    }

    private fun onWaveformSeek(fraction: Float) {
        val durationMs = player?.duration ?: return
        val targetMs = (fraction * durationMs).toInt()
        player?.seekTo(targetMs)
        reanchorPlayback()
        val seconds = targetMs / MS_PER_SECOND
        binding.playerProgressbar.progress = seconds
        binding.playerProgressCurrent.text = seconds.getFormattedDuration()
    }

    private fun setupColors() {
        val backgroundColor = getProperBackgroundColor()
        val primaryColor = getProperPrimaryColor()
        val textColor = getProperTextColor()
        // A very slight tint toward the text color, just enough to set the card apart
        // from the screen without washing out the faded (unplayed) waveform bars.
        val cardColor = ColorUtils.blendARGB(backgroundColor, textColor, CARD_TINT_RATIO)

        binding.playbackCoordinator.setBackgroundColor(backgroundColor)
        binding.playbackCard.setCardBackgroundColor(cardColor)
        binding.playbackTabs.setBackgroundColor(cardColor)
        binding.playbackTabs.setTabTextColors(textColor, primaryColor)
        binding.playbackTabs.setSelectedTabIndicatorColor(primaryColor)

        binding.playPauseBtn.background.setTint(primaryColor)
        binding.replayBtn.applyColorFilter(textColor)
        binding.forwardBtn.applyColorFilter(textColor)
    }

    private fun initMediaPlayer() {
        player = MediaPlayer().apply {
            setWakeMode(this@PlaybackActivity, PowerManager.PARTIAL_WAKE_LOCK)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )

            setOnCompletionListener {
                progressTimer.cancel()
                stopWordTimer()
                binding.waveformView.stopTicking()
                binding.waveformView.setProgress(1f)
                unregisterNoisyAudioReceiver()
                binding.playerProgressbar.progress = binding.playerProgressbar.max
                binding.playerProgressCurrent.text = binding.playerProgressMax.text
                binding.playPauseBtn.setImageResource(org.fossify.commons.R.drawable.ic_play_vector)
            }

            setOnPreparedListener {
                applyPlaybackSpeed()
                initWaveform()
                resumePlayback()
            }

            try {
                setDataSource(this@PlaybackActivity, recordingPath.toUri())
                prepareAsync()
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                showErrorToast(e)
            }
        }
    }

    private fun togglePlayPause() {
        if (player?.isPlaying == true) {
            pausePlayback()
        } else {
            resumePlayback()
        }
    }

    private fun pausePlayback() {
        unregisterNoisyAudioReceiver()
        player?.pause()
        binding.playPauseBtn.setImageResource(org.fossify.commons.R.drawable.ic_play_vector)
        progressTimer.cancel()
        stopWordTimer()
        binding.waveformView.stopTicking()
    }

    private fun resumePlayback() {
        registerNoisyAudioReceiver()
        player?.start()
        binding.playPauseBtn.setImageResource(org.fossify.commons.R.drawable.ic_pause_vector)
        reanchorPlayback()
        setupProgressTimer()
        startWordTimer()
        binding.waveformView.startTicking()
    }

    private fun skip(forward: Boolean) {
        val current = player?.currentPosition ?: return
        val delta = if (forward) SKIP_FORWARD_MS else -SKIP_BACK_MS
        val target = (current + delta).coerceIn(0, player?.duration ?: 0)
        player?.seekTo(target)
        resumePlayback()
    }

    private fun setupProgressTimer() {
        progressTimer.cancel()
        progressTimer = Timer()
        progressTimer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                Handler(Looper.getMainLooper()).post {
                    if (player == null) {
                        return@post
                    }
                    val seconds = Math.round(estimatedPositionMs() / MS_PER_SECOND.toDouble()).toInt()
                    binding.playerProgressbar.progress = seconds
                    binding.playerProgressCurrent.text = seconds.getFormattedDuration()
                }
            }
        }, PROGRESS_INTERVAL_MS, PROGRESS_INTERVAL_MS)
    }

    private fun applyPlaybackSpeed() {
        val currentPlayer = player ?: return
        val wasPlaying = currentPlayer.isPlaying
        try {
            // Setting params can auto-start a paused player; restore the prior state.
            currentPlayer.playbackParams = currentPlayer.playbackParams.setSpeed(config.playbackSpeed)
            if (!wasPlaying) {
                currentPlayer.pause()
            }
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            // Player not ready for params yet; ignore.
        }
    }

    @Suppress("MagicNumber")
    private fun showPlaybackSpeedDialog() {
        val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val items = ArrayList(speeds.mapIndexed { index, speed ->
            RadioItem(index, "${speed}x", speed)
        })
        val checked = speeds.indexOf(config.playbackSpeed).coerceAtLeast(0)
        RadioGroupDialog(this, items, checked) {
            config.playbackSpeed = it as Float
            applyPlaybackSpeed()
        }
    }

    // ---- transcript read-along ----

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun transcriptionUpdated(event: Events.TranscriptionUpdated) {
        if (event.recordingName == recordingTitle) {
            loadTranscript()
        }
    }

    // Lets the user (re-)transcribe this recording in a chosen language, defaulting to the
    // configured transcription language. Picks per-job without changing the global setting.
    private fun showTranscribeLanguageDialog() {
        TranscribeLanguageDialog(this, config.transcriptionLanguage) { language ->
            TranscriptionWorker.enqueue(this, recordingPath.toUri(), recordingTitle, language)
        }
    }

    private fun loadTranscript() {
        ensureBackgroundThread {
            val transcript = TranscriptDatabase.getInstance(this)
                .transcriptDao()
                .get(recordingTitle)
            runOnUiThread { updateTranscriptUi(transcript) }
        }
    }

    private fun updateTranscriptUi(transcript: Transcript?) {
        stopWordTimer()
        clearWordState()

        // Most recordings are already transcribed (auto/live), so the action is usually a redo.
        binding.playbackToolbar.menu.findItem(R.id.cab_transcribe)?.setTitle(
            if (transcript?.status == TRANSCRIPT_DONE) R.string.re_transcribe else R.string.transcribe
        )
        colorOverflowMenu()

        val showLanguage = transcript?.status == TRANSCRIPT_DONE && transcript.language.isNotBlank()
        binding.transcriptLanguage.beVisibleIf(showLanguage)
        if (showLanguage) {
            binding.transcriptLanguage.text = WhisperLanguages.nameOf(transcript!!.language)
        }

        // Offer transcription when there isn't a usable transcript yet.
        binding.transcribeButton.beVisibleIf(transcript == null || transcript.status == TRANSCRIPT_FAILED)

        val wordList = if (transcript?.status == TRANSCRIPT_DONE) {
            Word.fromJson(transcript.wordsJson)
        } else {
            emptyList()
        }

        if (wordList.isNotEmpty()) {
            showReadAlong(wordList)
            if (player?.isPlaying == true) {
                startWordTimer()
            }
            return
        }

        binding.transcriptView.movementMethod = null
        binding.transcriptView.text = when (transcript?.status) {
            TRANSCRIPT_PROCESSING -> getString(R.string.transcribing)
            TRANSCRIPT_FAILED -> getString(R.string.transcription_failed)
            TRANSCRIPT_DONE -> transcript.text.ifBlank { getString(R.string.no_transcript_yet) }
            else -> getString(R.string.no_transcript_yet)
        }
    }

    private fun clearWordState() {
        words = emptyList()
        wordRanges = emptyList()
        transcriptSpannable = null
        wordHighlightSpan = null
        highlightedWordIndex = -1
    }

    private fun showReadAlong(wordList: List<Word>) {
        val builder = SpannableStringBuilder()
        val ranges = ArrayList<IntRange>(wordList.size)
        val timestampColor = ColorUtils.setAlphaComponent(getProperTextColor(), TIMESTAMP_ALPHA)
        val timestampSize = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.smaller_text_size)
        var prevEndMs = 0L
        wordList.forEachIndexed { index, word ->
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
            val rawStart = builder.length
            builder.append(word.text)
            val visibleStart = rawStart + word.text.takeWhile { it == ' ' }.length
            val visibleEnd = builder.length
            ranges.add(visibleStart until visibleEnd)
            if (visibleEnd > visibleStart) {
                builder.setSpan(
                    object : ClickableSpan() {
                        override fun onClick(widget: View) = seekToWord(index)
                        override fun updateDrawState(ds: TextPaint) {
                            ds.isUnderlineText = false
                        }
                    },
                    visibleStart, visibleEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        words = wordList
        wordRanges = ranges
        transcriptSpannable = builder
        binding.transcriptView.movementMethod = LinkMovementMethod.getInstance()
        binding.transcriptView.text = builder
    }

    private fun seekToWord(index: Int) {
        val word = words.getOrNull(index) ?: return
        player?.seekTo(word.startMs.toInt())
        val seconds = (word.startMs / MS_PER_SECOND).toInt()
        binding.playerProgressbar.progress = seconds
        binding.playerProgressCurrent.text = seconds.getFormattedDuration()
        resumePlayback()
    }

    private fun startWordTimer() {
        if (words.isEmpty()) {
            return
        }
        wordTimer.cancel()
        wordTimer = Timer()
        wordTimer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                Handler(Looper.getMainLooper()).post { updateWordHighlight() }
            }
        }, 0, WORD_HIGHLIGHT_INTERVAL_MS)
    }

    private fun stopWordTimer() {
        wordTimer.cancel()
    }

    private fun updateWordHighlight() {
        val currentPlayer = player ?: return
        val spannable = transcriptSpannable ?: return
        val index = currentWordIndex(currentPlayer.currentPosition.toLong())
        if (index == highlightedWordIndex) {
            return
        }

        wordHighlightSpan?.let { spannable.removeSpan(it) }
        highlightedWordIndex = index
        if (index >= 0) {
            val range = wordRanges[index]
            val span = BackgroundColorSpan(getProperPrimaryColor().adjustAlpha(MARKER_ALPHA))
            spannable.setSpan(span, range.first, range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            wordHighlightSpan = span
            autoScrollToWord(range.first)
        } else {
            wordHighlightSpan = null
        }
        binding.transcriptView.text = spannable
    }

    private fun currentWordIndex(positionMs: Long): Int {
        var index = -1
        for (i in words.indices) {
            if (words[i].startMs <= positionMs) {
                index = i
            } else {
                break
            }
        }
        return index
    }

    private fun autoScrollToWord(charOffset: Int) {
        val layout = binding.transcriptView.layout ?: return
        val line = layout.getLineForOffset(charOffset)
        val targetY = binding.transcriptView.top +
            binding.transcriptView.paddingTop + layout.getLineTop(line)
        val panelHeight = binding.transcriptPanel.height
        binding.transcriptPanel.smoothScrollTo(0, (targetY - panelHeight / 2).coerceAtLeast(0))
    }

    // ---- 3-dot menu actions ----

    private fun currentRecording() = Recording(
        id = recordingPath.hashCode(),
        title = recordingTitle,
        path = recordingPath,
        timestamp = 0L,
        duration = durationSec,
        size = 0,
    )

    private fun renameRecording() {
        RenameRecordingDialog(this, currentRecording()) { finish() }
    }

    private fun askConfirmDelete() {
        val message = String.format(
            getString(
                if (config.useRecycleBin) {
                    org.fossify.commons.R.string.move_to_recycle_bin_confirmation
                } else {
                    R.string.delete_recordings_confirmation
                }
            ),
            "\"$recordingTitle\""
        )

        DeleteConfirmationDialog(
            activity = this,
            message = message,
            showSkipRecycleBinOption = config.useRecycleBin
        ) { skipRecycleBin ->
            ensureBackgroundThread {
                val toRecycleBin = !skipRecycleBin && config.useRecycleBin
                val callback: (Boolean) -> Unit = { success ->
                    if (success) {
                        if (toRecycleBin) {
                            EventBus.getDefault().post(Events.RecordingTrashUpdated())
                        }
                        runOnUiThread { finish() }
                    }
                }
                if (toRecycleBin) {
                    trashRecordings(listOf(currentRecording()), callback)
                } else {
                    deleteRecordings(listOf(currentRecording()), callback)
                }
            }
        }
    }

    private fun registerNoisyAudioReceiver() {
        if (isReceiverRegistered) {
            return
        }
        if (becomingNoisyReceiver == null) {
            becomingNoisyReceiver = BecomingNoisyReceiver(onBecomingNoisy = ::pausePlayback)
        }

        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (isTiramisuPlus()) {
            registerReceiver(becomingNoisyReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(becomingNoisyReceiver, filter)
        }
        isReceiverRegistered = true
    }

    private fun unregisterNoisyAudioReceiver() {
        if (!isReceiverRegistered || becomingNoisyReceiver == null) {
            return
        }
        try {
            isReceiverRegistered = false
            unregisterReceiver(becomingNoisyReceiver)
        } catch (ignored: IllegalArgumentException) {
        }
    }
}
