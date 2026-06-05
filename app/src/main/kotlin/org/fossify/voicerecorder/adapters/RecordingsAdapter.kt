package org.fossify.voicerecorder.adapters

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.core.graphics.ColorUtils
import androidx.core.net.toUri
import com.qtalk.recyclerviewfastscroller.RecyclerViewFastScroller
import org.fossify.commons.adapters.MyRecyclerViewAdapter
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.formatDate
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.getFormattedDuration
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.openPathIntent
import org.fossify.commons.extensions.setupViewBackground
import org.fossify.commons.extensions.sharePathsIntent
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.views.MyRecyclerView
import org.fossify.voicerecorder.BuildConfig
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.activities.SimpleActivity
import org.fossify.voicerecorder.databinding.ItemRecordingBinding
import org.fossify.voicerecorder.databinding.ItemRecordingSectionBinding
import org.fossify.voicerecorder.dialogs.DeleteConfirmationDialog
import org.fossify.voicerecorder.dialogs.RenameRecordingDialog
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.extensions.deleteRecordings
import org.fossify.voicerecorder.extensions.getRecordingDisplayTitle
import org.fossify.voicerecorder.extensions.recordingMonthLabel
import org.fossify.voicerecorder.extensions.trashRecordings
import org.fossify.voicerecorder.interfaces.RefreshRecordingsListener
import org.fossify.voicerecorder.models.Events
import org.fossify.voicerecorder.models.Recording
import org.fossify.voicerecorder.models.RecordingEntry
import org.fossify.voicerecorder.models.RecordingListItem
import org.fossify.voicerecorder.models.RecordingSection
import org.fossify.voicerecorder.transcription.TranscriptionWorker
import org.greenrobot.eventbus.EventBus
import java.io.File

class RecordingsAdapter(
    activity: SimpleActivity,
    var recordings: ArrayList<Recording>,
    private val refreshListener: RefreshRecordingsListener,
    recyclerView: MyRecyclerView,
    itemClick: (Any) -> Unit
) : MyRecyclerViewAdapter(activity, recyclerView, itemClick),
    RecyclerViewFastScroller.OnPopupTextUpdate {

    var currRecordingId = 0

    // Active search query and a map of recording name -> full transcript text,
    // used to highlight matches and show a transcript snippet under each result.
    var textToHighlight = ""
    var transcripts: Map<String, String> = emptyMap()
    private var lastHighlight = ""

    // Inline mini-player: a single recording plays at a time, its row's seekbar tracking
    // progress. [playingId] is the recording currently loaded into [player].
    private var player: MediaPlayer? = null
    private var playingId = -1
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            updatePlayingProgress()
            progressHandler.postDelayed(this, PLAYBACK_PROGRESS_INTERVAL_MS)
        }
    }

    // The recordings grouped into month sections + entries; this backs the adapter.
    private var listItems = buildListItems(recordings)

    init {
        setupDragListener(true)
    }

    // Groups the (already newest-first) recordings under month headers.
    private fun buildListItems(items: List<Recording>): ArrayList<RecordingListItem> {
        val result = ArrayList<RecordingListItem>()
        var lastMonth: String? = null
        items.forEach { recording ->
            val month = recordingMonthLabel(recording.timestamp)
            if (month != lastMonth) {
                result.add(RecordingSection(month))
                lastMonth = month
            }
            result.add(RecordingEntry(recording))
        }
        return result
    }

    override fun getActionMenuId() = R.menu.cab_recordings

    override fun prepareActionMode(menu: Menu) {
        menu.apply {
            findItem(R.id.cab_rename).isVisible = isOneItemSelected()
            findItem(R.id.cab_open_with).isVisible = isOneItemSelected()
        }
    }

    override fun actionItemPressed(id: Int) {
        if (selectedKeys.isEmpty()) {
            return
        }

        when (id) {
            R.id.cab_rename -> renameRecording()
            R.id.cab_share -> shareRecordings()
            R.id.cab_delete -> askConfirmDelete()
            R.id.cab_select_all -> selectAll()
            R.id.cab_open_with -> openRecordingWith()
            R.id.cab_transcribe -> transcribeRecordings()
        }
    }

    private fun transcribeRecordings() {
        getSelectedItems().forEach { recording ->
            val path = recording.path
            val uri = if (path.startsWith("content://") || path.startsWith("file://")) {
                Uri.parse(path)
            } else {
                Uri.fromFile(File(path))
            }
            TranscriptionWorker.enqueue(activity, uri, recording.title)
        }
        finishActMode()
    }

    override fun getSelectableItemCount() = recordings.size

    override fun getIsItemSelectable(position: Int) = listItems.getOrNull(position) is RecordingEntry

    override fun getItemSelectionKey(position: Int): Int? {
        return (listItems.getOrNull(position) as? RecordingEntry)?.recording?.id
    }

    override fun getItemKeyPosition(key: Int): Int {
        return listItems.indexOfFirst { it is RecordingEntry && it.recording.id == key }
    }

    override fun getItemViewType(position: Int): Int {
        return if (listItems[position] is RecordingSection) VIEW_TYPE_SECTION else VIEW_TYPE_ITEM
    }

    override fun onActionModeCreated() {}

    override fun onActionModeDestroyed() {}

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = if (viewType == VIEW_TYPE_SECTION) {
            ItemRecordingSectionBinding.inflate(layoutInflater, parent, false).root
        } else {
            ItemRecordingBinding.inflate(layoutInflater, parent, false).root
        }
        return createViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        when (val item = listItems[position]) {
            is RecordingSection -> holder.bindView(
                any = item,
                allowSingleClick = false,
                allowLongClick = false
            ) { itemView, _ ->
                setupSection(itemView, item)
            }

            is RecordingEntry -> holder.bindView(
                any = item.recording,
                allowSingleClick = true,
                allowLongClick = true
            ) { itemView, _ ->
                setupView(itemView, item.recording)
            }
        }

        bindViewHolder(holder)
    }

    override fun getItemCount() = listItems.size

    private fun getItemWithKey(key: Int): Recording? = recordings.firstOrNull { it.id == key }

    @SuppressLint("NotifyDataSetChanged")
    fun updateItems(newItems: ArrayList<Recording>) {
        // Also rebind when only the search highlight changed (the result set can stay
        // identical across keystrokes, e.g. "fo" -> "fox" both matching one recording).
        if (newItems.hashCode() != recordings.hashCode() || textToHighlight != lastHighlight) {
            if (newItems.none { it.id == playingId }) {
                stopPlayback()
            }
            recordings = newItems
            listItems = buildListItems(newItems)
            lastHighlight = textToHighlight
            notifyDataSetChanged()
            finishActMode()
        }
    }

    private fun renameRecording() {
        val recording = getItemWithKey(selectedKeys.first()) ?: return
        RenameRecordingDialog(activity, recording) {
            finishActMode()
            refreshListener.refreshRecordings()
        }
    }

    private fun openRecordingWith() {
        val recording = getItemWithKey(selectedKeys.first()) ?: return
        val path = recording.path
        activity.openPathIntent(
            path = path,
            forceChooser = true,
            applicationId = BuildConfig.APPLICATION_ID,
            forceMimeType = "audio/*"
        )
    }

    private fun shareRecordings() {
        val selectedItems = getSelectedItems()
        val paths = selectedItems.map { it.path }
        activity.sharePathsIntent(paths, BuildConfig.APPLICATION_ID)
    }

    private fun askConfirmDelete() {
        val itemsCnt = selectedKeys.size
        val firstItem = getSelectedItems().firstOrNull() ?: return
        val items = if (itemsCnt == 1) {
            "\"${firstItem.title}\""
        } else {
            resources.getQuantityString(R.plurals.delete_recordings, itemsCnt, itemsCnt)
        }

        val baseString = if (activity.config.useRecycleBin) {
            org.fossify.commons.R.string.move_to_recycle_bin_confirmation
        } else {
            R.string.delete_recordings_confirmation
        }
        val question = String.format(resources.getString(baseString), items)

        DeleteConfirmationDialog(
            activity = activity,
            message = question,
            showSkipRecycleBinOption = activity.config.useRecycleBin
        ) { skipRecycleBin ->
            ensureBackgroundThread {
                val toRecycleBin = !skipRecycleBin && activity.config.useRecycleBin
                if (toRecycleBin) {
                    trashRecordings()
                } else {
                    deleteRecordings()
                }
            }
        }
    }

    private fun deleteRecordings() {
        if (selectedKeys.isEmpty()) {
            return
        }

        val recordingsToRemove = recordings
            .filter { selectedKeys.contains(it.id) } as ArrayList<Recording>

        activity.deleteRecordings(recordingsToRemove) { success ->
            if (success) {
                removeFromList(recordingsToRemove)
            }
        }
    }

    private fun trashRecordings() {
        if (selectedKeys.isEmpty()) {
            return
        }

        val recordingsToRemove = recordings
            .filter { selectedKeys.contains(it.id) } as ArrayList<Recording>

        activity.trashRecordings(recordingsToRemove) { success ->
            if (success) {
                removeFromList(recordingsToRemove)
                EventBus.getDefault().post(Events.RecordingTrashUpdated())
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun removeFromList(recordingsToRemove: ArrayList<Recording>) {
        if (recordingsToRemove.any { it.id == playingId }) {
            stopPlayback()
        }
        recordings.removeAll(recordingsToRemove.toSet())
        listItems = buildListItems(recordings)
        activity.runOnUiThread {
            if (recordings.isEmpty()) {
                refreshListener.refreshRecordings()
            } else {
                notifyDataSetChanged()
            }
            finishActMode()
        }
    }

    fun updateCurrentRecording(newId: Int) {
        val oldId = currRecordingId
        currRecordingId = newId
        getItemKeyPosition(oldId).takeIf { it >= 0 }?.let { notifyItemChanged(it) }
        getItemKeyPosition(newId).takeIf { it >= 0 }?.let { notifyItemChanged(it) }
    }

    private fun getSelectedItems(): ArrayList<Recording> {
        return recordings.filter { selectedKeys.contains(it.id) } as ArrayList<Recording>
    }

    private fun togglePlayback(recording: Recording) {
        when {
            playingId != recording.id -> startPlayback(recording)
            player?.isPlaying == true -> pausePlayback()
            else -> resumePlayback()
        }
    }

    private fun startPlayback(recording: Recording) {
        val previousId = playingId
        releasePlayer()
        playingId = recording.id

        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setOnPreparedListener {
                it.start()
                progressHandler.post(progressRunnable)
                refreshRow(recording.id)
            }
            setOnCompletionListener { stopPlayback() }
            try {
                setDataSource(activity, recording.path.toUri())
                prepareAsync()
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                activity.showErrorToast(e)
                playingId = -1
            }
        }

        if (previousId != -1) {
            refreshRow(previousId)
        }
        refreshRow(recording.id)
    }

    private fun pausePlayback() {
        player?.pause()
        progressHandler.removeCallbacks(progressRunnable)
        refreshRow(playingId)
    }

    private fun resumePlayback() {
        player?.start()
        progressHandler.post(progressRunnable)
        refreshRow(playingId)
    }

    // Stops and unloads playback, resetting the previously-playing row to its idle state.
    fun stopPlayback() {
        val stoppedId = playingId
        releasePlayer()
        playingId = -1
        if (stoppedId != -1) {
            refreshRow(stoppedId)
        }
    }

    private fun releasePlayer() {
        progressHandler.removeCallbacks(progressRunnable)
        player?.release()
        player = null
    }

    private fun updatePlayingProgress() {
        val currentPlayer = player ?: return
        val position = getItemKeyPosition(playingId).takeIf { it >= 0 } ?: return
        val holder = recyclerView.findViewHolderForAdapterPosition(position) ?: return
        val elapsed = currentPlayer.currentPosition / MS_PER_SECOND
        val total = currentPlayer.duration / MS_PER_SECOND
        ItemRecordingBinding.bind(holder.itemView).apply {
            recordingSeekbar.progress = elapsed
            recordingDuration.text = formatRemaining(total - elapsed)
        }
    }

    // Remaining time shown as a countdown, e.g. "-01:23".
    private fun formatRemaining(seconds: Int) = "-${seconds.coerceAtLeast(0).getFormattedDuration()}"

    private fun refreshRow(id: Int) {
        getItemKeyPosition(id).takeIf { it >= 0 }?.let { notifyItemChanged(it) }
    }

    private fun setupPlayback(binding: ItemRecordingBinding, recording: Recording) {
        val isCurrent = recording.id == playingId
        val isPlaying = isCurrent && player?.isPlaying == true
        val playIcon = if (isPlaying) {
            org.fossify.commons.R.drawable.ic_pause_vector
        } else {
            org.fossify.commons.R.drawable.ic_play_vector
        }

        binding.playPauseButton.setImageResource(playIcon)
        binding.playPauseButton.applyColorFilter(activity.config.recordingAccentColor)
        binding.playPauseButton.setOnClickListener { togglePlayback(recording) }

        // While this recording is loaded, the indicator counts down the remaining time;
        // otherwise it shows the total length.
        binding.recordingDuration.text = if (isCurrent) {
            val elapsed = (player?.currentPosition ?: 0) / MS_PER_SECOND
            formatRemaining(recording.duration - elapsed)
        } else {
            recording.duration.getFormattedDuration()
        }

        binding.recordingSeekbar.max = recording.duration
        binding.recordingSeekbar.progress = if (isCurrent) {
            (player?.currentPosition ?: 0) / MS_PER_SECOND
        } else {
            0
        }

        binding.recordingSeekbar.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && recording.id == playingId) {
                    player?.seekTo(progress * MS_PER_SECOND)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }

    private fun setupSection(view: View, section: RecordingSection) {
        ItemRecordingSectionBinding.bind(view).apply {
            sectionTitle.text = section.title
            sectionTitle.setTextColor(activity.getProperPrimaryColor())
        }
    }

    // A subtle fill, a touch brighter than the screen on dark themes / dimmer on light
    // themes, matching Google Recorder's borderless (no-stroke) cards.
    private fun cardColor() =
        ColorUtils.blendARGB(activity.getProperBackgroundColor(), textColor, CARD_TINT_RATIO)

    private fun setupView(view: View, recording: Recording) {
        ItemRecordingBinding.bind(view).apply {
            itemHolder.setupViewBackground(activity)
            recordingFrame.setCardBackgroundColor(cardColor())
            itemHolder.isSelected = selectedKeys.contains(recording.id)

            val primaryColor = activity.getProperPrimaryColor()
            arrayListOf(
                recordingTitle,
                recordingDate,
                recordingDuration,
                recordingSize,
                recordingTranscriptSnippet
            ).forEach {
                it.setTextColor(textColor)
            }

            if (recording.id == currRecordingId) {
                recordingTitle.setTextColor(primaryColor)
            }

            val markerColor = primaryColor.adjustAlpha(MARKER_ALPHA)
            val displayTitle = activity.getRecordingDisplayTitle(recording)
            recordingTitle.text = highlightMatches(displayTitle, textToHighlight, markerColor)
            recordingDate.text = recording.timestamp.formatDate(root.context)
            recordingDuration.text = recording.duration.getFormattedDuration()
            recordingSize.text = recording.size.formatSize()

            val snippet = transcriptSnippet(recording.title, textToHighlight, markerColor)
            recordingTranscriptSnippet.beVisibleIf(snippet != null)
            if (snippet != null) {
                recordingTranscriptSnippet.text = snippet
            }

            setupPlayback(this, recording)
        }
    }

    // Builds a snippet of the transcript around the matched query (marker-highlighted),
    // or null when there's no transcript match to show.
    private fun transcriptSnippet(name: String, query: String, markerColor: Int): CharSequence? {
        if (query.isEmpty()) {
            return null
        }

        val text = transcripts[name] ?: return null
        val matchIndex = text.indexOf(query, ignoreCase = true)
        if (matchIndex < 0) {
            return null
        }

        val start = (matchIndex - SNIPPET_CHARS_BEFORE).coerceAtLeast(0)
        val end = (matchIndex + query.length + SNIPPET_CHARS_AFTER).coerceAtMost(text.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < text.length) "…" else ""
        return highlightMatches(prefix + text.substring(start, end) + suffix, query, markerColor)
    }

    // Marker-style highlight: a translucent background behind every occurrence of
    // [query], leaving the text color itself unchanged.
    private fun highlightMatches(text: String, query: String, markerColor: Int): SpannableString {
        val spannable = SpannableString(text)
        if (query.isEmpty()) {
            return spannable
        }

        var index = text.indexOf(query, ignoreCase = true)
        while (index >= 0) {
            spannable.setSpan(
                BackgroundColorSpan(markerColor),
                index,
                index + query.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            index = text.indexOf(query, startIndex = index + query.length, ignoreCase = true)
        }
        return spannable
    }

    override fun onChange(position: Int): CharSequence {
        return when (val item = listItems.getOrNull(position)) {
            is RecordingEntry -> recordingMonthLabel(item.recording.timestamp)
            is RecordingSection -> item.title
            else -> ""
        }
    }

    companion object {
        private const val SNIPPET_CHARS_BEFORE = 30
        private const val SNIPPET_CHARS_AFTER = 60
        private const val MARKER_ALPHA = 0.4f
        private const val CARD_TINT_RATIO = 0.08f
        private const val VIEW_TYPE_SECTION = 0
        private const val VIEW_TYPE_ITEM = 1
        private const val PLAYBACK_PROGRESS_INTERVAL_MS = 200L
        private const val MS_PER_SECOND = 1000
    }
}
