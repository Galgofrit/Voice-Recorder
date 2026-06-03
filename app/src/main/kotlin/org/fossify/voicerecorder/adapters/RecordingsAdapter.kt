package org.fossify.voicerecorder.adapters

import android.annotation.SuppressLint
import android.net.Uri
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import com.qtalk.recyclerviewfastscroller.RecyclerViewFastScroller
import org.fossify.commons.adapters.MyRecyclerViewAdapter
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.formatDate
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.getFormattedDuration
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.openPathIntent
import org.fossify.commons.extensions.setupViewBackground
import org.fossify.commons.extensions.sharePathsIntent
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.views.MyRecyclerView
import org.fossify.voicerecorder.BuildConfig
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.activities.SimpleActivity
import org.fossify.voicerecorder.databinding.ItemRecordingBinding
import org.fossify.voicerecorder.dialogs.DeleteConfirmationDialog
import org.fossify.voicerecorder.dialogs.RenameRecordingDialog
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.extensions.deleteRecordings
import org.fossify.voicerecorder.extensions.trashRecordings
import org.fossify.voicerecorder.interfaces.RefreshRecordingsListener
import org.fossify.voicerecorder.models.Events
import org.fossify.voicerecorder.models.Recording
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

    init {
        setupDragListener(true)
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

    override fun getIsItemSelectable(position: Int) = true

    override fun getItemSelectionKey(position: Int): Int? {
        return recordings.getOrNull(position)?.id
    }

    override fun getItemKeyPosition(key: Int): Int {
        return recordings.indexOfFirst { it.id == key }
    }

    override fun onActionModeCreated() {}

    override fun onActionModeDestroyed() {}

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return createViewHolder(
            view = ItemRecordingBinding.inflate(layoutInflater, parent, false).root
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val recording = recordings[position]
        holder.bindView(
            any = recording,
            allowSingleClick = true,
            allowLongClick = true
        ) { itemView, _ ->
            setupView(itemView, recording)
        }

        bindViewHolder(holder)
    }

    override fun getItemCount() = recordings.size

    private fun getItemWithKey(key: Int): Recording? = recordings.firstOrNull { it.id == key }

    @SuppressLint("NotifyDataSetChanged")
    fun updateItems(newItems: ArrayList<Recording>) {
        // Also rebind when only the search highlight changed (the result set can stay
        // identical across keystrokes, e.g. "fo" -> "fox" both matching one recording).
        if (newItems.hashCode() != recordings.hashCode() || textToHighlight != lastHighlight) {
            recordings = newItems
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

        val positions = getSelectedItemPositions()

        activity.deleteRecordings(recordingsToRemove) { success ->
            if (success) {
                doDeleteAnimation(recordingsToRemove, positions)
            }
        }
    }

    private fun trashRecordings() {
        if (selectedKeys.isEmpty()) {
            return
        }

        val recordingsToRemove = recordings
            .filter { selectedKeys.contains(it.id) } as ArrayList<Recording>

        val positions = getSelectedItemPositions()

        activity.trashRecordings(recordingsToRemove) { success ->
            if (success) {
                doDeleteAnimation(recordingsToRemove, positions)
                EventBus.getDefault().post(Events.RecordingTrashUpdated())
            }
        }
    }

    private fun doDeleteAnimation(
        recordingsToRemove: ArrayList<Recording>,
        positions: ArrayList<Int>
    ) {
        recordings.removeAll(recordingsToRemove.toSet())
        activity.runOnUiThread {
            if (recordings.isEmpty()) {
                refreshListener.refreshRecordings()
                finishActMode()
            } else {
                positions.sortDescending()
                removeSelectedItems(positions)
            }
        }
    }

    fun updateCurrentRecording(newId: Int) {
        val oldId = currRecordingId
        currRecordingId = newId
        notifyItemChanged(recordings.indexOfFirst { it.id == oldId })
        notifyItemChanged(recordings.indexOfFirst { it.id == newId })
    }

    private fun getSelectedItems(): ArrayList<Recording> {
        return recordings.filter { selectedKeys.contains(it.id) } as ArrayList<Recording>
    }

    private fun setupView(view: View, recording: Recording) {
        ItemRecordingBinding.bind(view).apply {
            root.setupViewBackground(activity)
            recordingFrame.isSelected = selectedKeys.contains(recording.id)

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
            recordingTitle.text = highlightMatches(recording.title, textToHighlight, markerColor)
            recordingDate.text = recording.timestamp.formatDate(root.context)
            recordingDuration.text = recording.duration.getFormattedDuration()
            recordingSize.text = recording.size.formatSize()

            val snippet = transcriptSnippet(recording.title, textToHighlight, markerColor)
            recordingTranscriptSnippet.beVisibleIf(snippet != null)
            if (snippet != null) {
                recordingTranscriptSnippet.text = snippet
            }
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

    override fun onChange(position: Int) = recordings.getOrNull(position)?.title ?: ""

    companion object {
        private const val SNIPPET_CHARS_BEFORE = 30
        private const val SNIPPET_CHARS_AFTER = 60
        private const val MARKER_ALPHA = 0.4f
    }
}
