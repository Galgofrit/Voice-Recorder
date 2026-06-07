package org.fossify.voicerecorder.fragments

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import org.fossify.commons.extensions.areSystemAnimationsEnabled
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isQPlus
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.activities.PlaybackActivity
import org.fossify.voicerecorder.activities.SimpleActivity
import org.fossify.voicerecorder.adapters.RecordingsAdapter
import org.fossify.voicerecorder.databases.TranscriptDatabase
import org.fossify.voicerecorder.databinding.FragmentPlayerBinding
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.extensions.getRecording
import org.fossify.voicerecorder.interfaces.RefreshRecordingsListener
import org.fossify.voicerecorder.models.Events
import org.fossify.voicerecorder.models.Recording
import org.fossify.voicerecorder.models.TRANSCRIPT_DONE
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class PlayerFragment(
    context: Context,
    attributeSet: AttributeSet
) : MyViewPagerFragment(context, attributeSet), RefreshRecordingsListener {

    private var itemsIgnoringSearch = ArrayList<Recording>()
    private var lastSearchQuery = ""
    private var bus: EventBus? = null
    private var prevSavePath = ""
    private var prevRecycleBinState = context.config.useRecycleBin
    private var transcriptsByName = HashMap<String, String>()
    private lateinit var binding: FragmentPlayerBinding

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
            loadTranscriptsForSearch()
        }

        storePrevState()
    }

    override fun onDestroy() {
        bus?.unregister(this)
        getRecordingsAdapter()?.stopPlayback()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        bus = EventBus.getDefault()
        bus!!.register(this)
        setupColors()
        loadRecordings()
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
        }

        val adapter = getRecordingsAdapter()
        if (adapter == null) {
            RecordingsAdapter(context as SimpleActivity, recordings, this, binding.recordingsList) {
                openRecording(it as Recording)
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

    private fun openRecording(recording: Recording) {
        getRecordingsAdapter()?.stopPlayback()
        Intent(context, PlaybackActivity::class.java).apply {
            putExtra(PlaybackActivity.EXTRA_PATH, recording.path)
            putExtra(PlaybackActivity.EXTRA_TITLE, recording.title)
            putExtra(PlaybackActivity.EXTRA_DURATION, recording.duration)
            context.startActivity(this)
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

    private fun getRecordingsAdapter() = binding.recordingsList.adapter as? RecordingsAdapter

    private fun storePrevState() {
        prevSavePath = context!!.config.saveRecordingsFolder
        prevRecycleBinState = context.config.useRecycleBin
    }

    private fun setupColors() {
        val properPrimaryColor = context.getProperPrimaryColor()
        binding.recordingsFastscroller.updateColors(properPrimaryColor)
        context.updateTextColors(binding.playerHolder)
        binding.loadingIndicator.setIndicatorColor(properPrimaryColor)
    }

    fun finishActMode() = getRecordingsAdapter()?.finishActMode()

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun recordingCompleted(@Suppress("UNUSED_PARAMETER") event: Events.RecordingCompleted) {
        refreshRecordings()
    }

    // Instant feedback: as soon as a recording is saved, read just that one file and prepend it,
    // so the user sees it immediately instead of waiting for the full folder re-enumeration
    // (which still runs via recordingCompleted to reconcile).
    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun recordingSaved(event: Events.RecordingSaved) {
        val uri = event.uri ?: return
        ensureBackgroundThread {
            val recording = context.getRecording(uri) ?: return@ensureBackgroundThread
            (context as? SimpleActivity)?.runOnUiThread { prependRecording(recording) }
        }
    }

    private fun prependRecording(recording: Recording) {
        if (itemsIgnoringSearch.any { it.path == recording.path }) {
            return
        }
        itemsIgnoringSearch = ArrayList<Recording>(itemsIgnoringSearch.size + 1).apply {
            add(recording)
            addAll(itemsIgnoringSearch)
        }
        binding.recordingsPlaceholder.beVisibleIf(false)
        if (lastSearchQuery.isEmpty()) {
            setupAdapter(itemsIgnoringSearch)
        } else {
            onSearchTextChanged(lastSearchQuery)
        }
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun recordingMovedToRecycleBin(@Suppress("UNUSED_PARAMETER") event: Events.RecordingTrashUpdated) {
        refreshRecordings()
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun transcriptionUpdated(@Suppress("UNUSED_PARAMETER") event: Events.TranscriptionUpdated) {
        loadTranscriptsForSearch()
    }
}
