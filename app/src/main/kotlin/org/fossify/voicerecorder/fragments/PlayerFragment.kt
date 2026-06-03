package org.fossify.voicerecorder.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.Context.RECEIVER_NOT_EXPORTED
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.util.AttributeSet
import android.view.View
import android.widget.SeekBar
import androidx.core.net.toUri
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.areSystemAnimationsEnabled
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.copyToClipboard
import org.fossify.commons.extensions.getColoredDrawableWithColor
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getFormattedDuration
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.value
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isQPlus
import org.fossify.commons.helpers.isTiramisuPlus
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.activities.SimpleActivity
import org.fossify.voicerecorder.adapters.RecordingsAdapter
import org.fossify.voicerecorder.databases.TranscriptDatabase
import org.fossify.voicerecorder.databinding.FragmentPlayerBinding
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.interfaces.RefreshRecordingsListener
import org.fossify.voicerecorder.models.Events
import org.fossify.voicerecorder.models.Recording
import org.fossify.voicerecorder.models.TRANSCRIPT_DONE
import org.fossify.voicerecorder.models.TRANSCRIPT_FAILED
import org.fossify.voicerecorder.models.TRANSCRIPT_PROCESSING
import org.fossify.voicerecorder.models.Transcript
import org.fossify.voicerecorder.models.Word
import org.fossify.voicerecorder.transcription.WhisperLanguages
import org.fossify.voicerecorder.receivers.BecomingNoisyReceiver
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.Stack
import java.util.Timer
import java.util.TimerTask

class PlayerFragment(
    context: Context,
    attributeSet: AttributeSet
) : MyViewPagerFragment(context, attributeSet), RefreshRecordingsListener {

    companion object {
        private const val FAST_FORWARD_SKIP_MS = 10000
        private const val WORD_HIGHLIGHT_INTERVAL_MS = 80L
        private const val MS_PER_SECOND = 1000
        private const val MARKER_ALPHA = 0.4f
    }

    private var player: MediaPlayer? = null
    private var progressTimer = Timer()
    private var wordTimer = Timer()
    private var words: List<Word> = emptyList()
    private var wordRanges: List<IntRange> = emptyList()
    private var transcriptSpannable: Spannable? = null
    private var wordHighlightSpan: BackgroundColorSpan? = null
    private var highlightedWordIndex = -1
    private var playedRecordingIDs = Stack<Int>()
    private var itemsIgnoringSearch = ArrayList<Recording>()
    private var lastSearchQuery = ""
    private var bus: EventBus? = null
    private var prevSavePath = ""
    private var prevRecycleBinState = context.config.useRecycleBin
    private var playOnPreparation = true
    private var currentRecordingName = ""
    private var transcriptsByName = HashMap<String, String>()
    private lateinit var binding: FragmentPlayerBinding

    private var becomingNoisyReceiver: BecomingNoisyReceiver? = null
    private var isReceiverRegistered = false

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = FragmentPlayerBinding.bind(this)
    }

    override fun onResume() {
        setupColors()
        if (prevSavePath.isNotEmpty() && context!!.config.saveRecordingsFolder != prevSavePath || context.config.useRecycleBin != prevRecycleBinState) {
            loadRecordings()
        } else {
            getRecordingsAdapter()?.updateTextColor(context.getProperTextColor())
        }

        storePrevState()
    }

    override fun onDestroy() {
        unregisterNoisyAudioReceiver()
        player?.stop()
        player?.release()
        player = null

        bus?.unregister(this)
        progressTimer.cancel()
        stopWordTimer()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        bus = EventBus.getDefault()
        bus!!.register(this)
        setupColors()
        loadRecordings()
        initMediaPlayer()
        setupViews()
        storePrevState()
    }

    override fun onLoadingStart() {
        if (itemsIgnoringSearch.isEmpty()) {
            binding.loadingIndicator.show()
        } else {
            binding.loadingIndicator.hide()
        }
    }

    override fun onLoadingEnd(recordings: ArrayList<Recording>) {
        binding.loadingIndicator.hide()
        binding.recordingsPlaceholder.beVisibleIf(recordings.isEmpty())
        itemsIgnoringSearch = recordings
        setupAdapter(itemsIgnoringSearch)
        loadTranscriptsForSearch()
    }

    // Cache of completed transcript text keyed by recording name, so search can
    // match transcript content without hitting the database on every keystroke.
    private fun loadTranscriptsForSearch() {
        ensureBackgroundThread {
            val map = TranscriptDatabase.getInstance(context)
                .transcriptDao()
                .getByStatus(TRANSCRIPT_DONE)
                .associate { it.recordingName to it.text }
            (context as SimpleActivity).runOnUiThread {
                transcriptsByName = HashMap(map)
                if (lastSearchQuery.isNotEmpty()) {
                    onSearchTextChanged(lastSearchQuery)
                }
            }
        }
    }

    private fun setupViews() {
        binding.playPauseBtn.setOnClickListener {
            if (playedRecordingIDs.empty() || binding.playerProgressbar.max == 0) {
                binding.nextBtn.callOnClick()
            } else {
                togglePlayPause()
            }
        }

        binding.playerProgressCurrent.setOnClickListener {
            skip(false)
        }

        binding.playerProgressMax.setOnClickListener {
            skip(true)
        }

        binding.previousBtn.setOnClickListener {
            if (playedRecordingIDs.isEmpty()) {
                return@setOnClickListener
            }

            val adapter = getRecordingsAdapter() ?: return@setOnClickListener
            var wantedRecordingID = playedRecordingIDs.pop()
            if (wantedRecordingID == adapter.currRecordingId && !playedRecordingIDs.isEmpty()) {
                wantedRecordingID = playedRecordingIDs.pop()
            }

            val prevRecordingIndex = adapter.recordings.indexOfFirst { it.id == wantedRecordingID }
            val prevRecording = adapter.recordings
                .getOrNull(prevRecordingIndex) ?: return@setOnClickListener
            playRecording(prevRecording, true)
        }

        binding.playerTitle.setOnLongClickListener {
            if (binding.playerTitle.value.isNotEmpty()) {
                context.copyToClipboard(binding.playerTitle.value)
            }
            true
        }

        binding.transcriptView.setOnLongClickListener {
            if (binding.transcriptView.value.isNotEmpty()) {
                context.copyToClipboard(binding.transcriptView.value)
            }
            true
        }

        binding.nextBtn.setOnClickListener {
            val adapter = getRecordingsAdapter()
            if (adapter == null || adapter.recordings.isEmpty()) {
                return@setOnClickListener
            }

            val oldRecordingIndex =
                adapter.recordings.indexOfFirst { it.id == adapter.currRecordingId }
            val newRecordingIndex = (oldRecordingIndex + 1) % adapter.recordings.size
            val newRecording =
                adapter.recordings.getOrNull(newRecordingIndex) ?: return@setOnClickListener
            playRecording(newRecording, true)
            playedRecordingIDs.push(newRecording.id)
        }
    }

    override fun refreshRecordings() = loadRecordings()

    private fun setupAdapter(recordings: ArrayList<Recording>) {
        binding.recordingsFastscroller.beVisibleIf(recordings.isNotEmpty())
        if (recordings.isEmpty()) {
            val stringId = if (lastSearchQuery.isEmpty()) {
                if (isQPlus()) {
                    R.string.no_recordings_found
                } else {
                    R.string.no_recordings_in_folder_found
                }
            } else {
                org.fossify.commons.R.string.no_items_found
            }

            binding.recordingsPlaceholder.text = context.getString(stringId)
            resetProgress(null)
            player?.stop()
        }

        val adapter = getRecordingsAdapter()
        if (adapter == null) {
            RecordingsAdapter(context as SimpleActivity, recordings, this, binding.recordingsList) {
                playRecording(it as Recording, true)
                if (playedRecordingIDs.isEmpty() || playedRecordingIDs.peek() != it.id) {
                    playedRecordingIDs.push(it.id)
                }
            }.apply {
                textToHighlight = lastSearchQuery
                transcripts = transcriptsByName
                binding.recordingsList.adapter = this
            }

            if (context.areSystemAnimationsEnabled) {
                binding.recordingsList.scheduleLayoutAnimation()
            }
        } else {
            adapter.textToHighlight = lastSearchQuery
            adapter.transcripts = transcriptsByName
            adapter.updateItems(recordings)
        }
    }

    private fun initMediaPlayer() {
        player = MediaPlayer().apply {
            setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )

            setOnCompletionListener {
                progressTimer.cancel()
                stopWordTimer()
                unregisterNoisyAudioReceiver()
                binding.playerProgressbar.progress = binding.playerProgressbar.max
                binding.playerProgressCurrent.text = binding.playerProgressMax.text
                binding.playPauseBtn.setImageDrawable(getToggleButtonIcon(false))
            }

            setOnPreparedListener {
                if (playOnPreparation) {
                    resumePlayback()
                }

                playOnPreparation = true
            }
        }
    }

    override fun playRecording(recording: Recording, playOnPrepared: Boolean) {
        resetProgress(recording)
        currentRecordingName = recording.title
        loadTranscript(recording.title)
        (binding.recordingsList.adapter as RecordingsAdapter).updateCurrentRecording(recording.id)
        playOnPreparation = playOnPrepared

        player!!.apply {
            reset()

            try {
                setDataSource(context, recording.path.toUri())
            } catch (e: Exception) {
                context?.showErrorToast(e)
                return
            }

            try {
                prepareAsync()
            } catch (e: Exception) {
                context.showErrorToast(e)
                return
            }
        }

        binding.playPauseBtn.setImageDrawable(getToggleButtonIcon(playOnPreparation))
        binding.playerProgressbar.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && !playedRecordingIDs.isEmpty()) {
                    player?.seekTo(progress * 1000)
                    binding.playerProgressCurrent.text = progress.getFormattedDuration()
                    resumePlayback()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    @SuppressLint("DiscouragedApi")
    private fun setupProgressTimer() {
        progressTimer.cancel()
        progressTimer = Timer()
        progressTimer.scheduleAtFixedRate(getProgressUpdateTask(), 1000, 1000)
    }

    private fun getProgressUpdateTask() = object : TimerTask() {
        override fun run() {
            Handler(Looper.getMainLooper()).post {
                if (player != null) {
                    val progress = Math.round(player!!.currentPosition / 1000.toDouble()).toInt()
                    updateCurrentProgress(progress)
                    binding.playerProgressbar.progress = progress
                }
            }
        }
    }

    private fun updateCurrentProgress(seconds: Int) {
        binding.playerProgressCurrent.text = seconds.getFormattedDuration()
    }

    private fun resetProgress(recording: Recording?) {
        updateCurrentProgress(0)
        binding.playerProgressbar.progress = 0
        binding.playerProgressbar.max = recording?.duration ?: 0
        binding.playerTitle.text = recording?.title ?: ""
        binding.playerProgressMax.text = (recording?.duration ?: 0).getFormattedDuration()
        if (recording == null) {
            currentRecordingName = ""
            binding.transcriptPanel.beGone()
        }
    }

    fun onSearchTextChanged(text: String) {
        lastSearchQuery = text
        val filtered = itemsIgnoringSearch
            .filter {
                it.title.contains(text, true) ||
                    transcriptsByName[it.title]?.contains(text, true) == true
            }
            .toMutableList() as ArrayList<Recording>
        setupAdapter(filtered)
    }

    private fun togglePlayPause() {
        if (getIsPlaying()) {
            pausePlayback()
        } else {
            resumePlayback()
        }
    }

    private fun pausePlayback() {
        unregisterNoisyAudioReceiver()
        player?.pause()
        binding.playPauseBtn.setImageDrawable(getToggleButtonIcon(false))
        progressTimer.cancel()
        stopWordTimer()
    }

    private fun resumePlayback() {
        registerNoisyAudioReceiver()
        player?.start()
        binding.playPauseBtn.setImageDrawable(getToggleButtonIcon(true))
        setupProgressTimer()
        startWordTimer()
    }

    private fun getToggleButtonIcon(isPlaying: Boolean): Drawable {
        val drawable = if (isPlaying) {
            org.fossify.commons.R.drawable.ic_pause_vector
        } else {
            org.fossify.commons.R.drawable.ic_play_vector
        }

        return resources.getColoredDrawableWithColor(
            drawableId = drawable,
            color = context.getProperPrimaryColor().getContrastColor()
        )
    }

    private fun skip(forward: Boolean) {
        if (playedRecordingIDs.empty()) {
            return
        }

        val curr = player?.currentPosition ?: return
        var newProgress = if (forward) curr + FAST_FORWARD_SKIP_MS else curr - FAST_FORWARD_SKIP_MS
        if (newProgress > player!!.duration) {
            newProgress = player!!.duration
        }

        player!!.seekTo(newProgress)
        resumePlayback()
    }

    private fun getIsPlaying() = player?.isPlaying == true

    private fun getRecordingsAdapter() = binding.recordingsList.adapter as? RecordingsAdapter

    private fun storePrevState() {
        prevSavePath = context!!.config.saveRecordingsFolder
        prevRecycleBinState = context.config.useRecycleBin
    }

    private fun setupColors() {
        val properPrimaryColor = context.getProperPrimaryColor()
        binding.recordingsFastscroller.updateColors(properPrimaryColor)
        context.updateTextColors(binding.playerHolder)

        val textColor = context.getProperTextColor()
        arrayListOf(binding.previousBtn, binding.nextBtn).forEach {
            it.applyColorFilter(textColor)
        }

        binding.playPauseBtn.background.applyColorFilter(properPrimaryColor)
        binding.playPauseBtn.setImageDrawable(getToggleButtonIcon(getIsPlaying()))

        binding.loadingIndicator.setIndicatorColor(properPrimaryColor)
    }

    fun finishActMode() = getRecordingsAdapter()?.finishActMode()

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun recordingCompleted(@Suppress("UNUSED_PARAMETER") event: Events.RecordingCompleted) {
        refreshRecordings()
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun recordingMovedToRecycleBin(@Suppress("UNUSED_PARAMETER") event: Events.RecordingTrashUpdated) {
        refreshRecordings()
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun transcriptionUpdated(event: Events.TranscriptionUpdated) {
        if (event.recordingName == currentRecordingName) {
            loadTranscript(currentRecordingName)
        }
        loadTranscriptsForSearch()
    }

    private fun loadTranscript(recordingName: String) {
        ensureBackgroundThread {
            val transcript = TranscriptDatabase.getInstance(context)
                .transcriptDao()
                .get(recordingName)
            (context as SimpleActivity).runOnUiThread {
                if (recordingName == currentRecordingName) {
                    updateTranscriptUi(transcript)
                }
            }
        }
    }

    private fun updateTranscriptUi(transcript: Transcript?) {
        stopWordTimer()
        clearWordState()
        if (transcript == null) {
            binding.transcriptPanel.beGone()
            return
        }

        binding.transcriptPanel.beVisible()
        val showLanguage = transcript.status == TRANSCRIPT_DONE && transcript.language.isNotBlank()
        binding.transcriptLanguage.beVisibleIf(showLanguage)
        if (showLanguage) {
            binding.transcriptLanguage.text = WhisperLanguages.nameOf(transcript.language)
        }

        val wordList = if (transcript.status == TRANSCRIPT_DONE) {
            Word.fromJson(transcript.wordsJson)
        } else {
            emptyList()
        }

        if (wordList.isNotEmpty()) {
            showReadAlong(wordList)
            if (getIsPlaying()) {
                startWordTimer()
            }
            return
        }

        // No word timeline (processing/failed/old transcript): plain, non-interactive text.
        binding.transcriptView.movementMethod = null
        binding.transcriptView.text = when (transcript.status) {
            TRANSCRIPT_PROCESSING -> context.getString(R.string.transcribing)
            TRANSCRIPT_FAILED -> context.getString(R.string.transcription_failed)
            TRANSCRIPT_DONE -> transcript.text.ifBlank {
                context.getString(R.string.no_transcript_yet)
            }

            else -> context.getString(R.string.no_transcript_yet)
        }
    }

    private fun clearWordState() {
        words = emptyList()
        wordRanges = emptyList()
        transcriptSpannable = null
        wordHighlightSpan = null
        highlightedWordIndex = -1
    }

    // Renders the transcript with each word tappable (seek) and tracked for highlighting.
    private fun showReadAlong(wordList: List<Word>) {
        val builder = SpannableStringBuilder()
        val ranges = ArrayList<IntRange>(wordList.size)
        wordList.forEachIndexed { index, word ->
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
            val markerColor = context.getProperPrimaryColor().adjustAlpha(MARKER_ALPHA)
            val span = BackgroundColorSpan(markerColor)
            spannable.setSpan(span, range.first, range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            wordHighlightSpan = span
            autoScrollToWord(range.first)
        } else {
            wordHighlightSpan = null
        }
        binding.transcriptView.text = spannable
    }

    // Index of the latest word that has started by [positionMs], or -1 before the first.
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

    private fun registerNoisyAudioReceiver() {
        if (isReceiverRegistered) return
        if (becomingNoisyReceiver == null) {
            becomingNoisyReceiver = BecomingNoisyReceiver(onBecomingNoisy = ::pausePlayback)
        }

        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (isTiramisuPlus()) {
            context.registerReceiver(becomingNoisyReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(becomingNoisyReceiver, filter)
        }

        isReceiverRegistered = true
    }

    private fun unregisterNoisyAudioReceiver() {
        if (!isReceiverRegistered || becomingNoisyReceiver == null) return
        try {
            isReceiverRegistered = false
            context.unregisterReceiver(becomingNoisyReceiver)
        } catch (ignored: IllegalArgumentException) {
        }
    }
}
