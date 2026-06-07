package org.fossify.voicerecorder.extensions

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.graphics.createBitmap
import androidx.documentfile.provider.DocumentFile
import org.fossify.commons.extensions.createFirstParentTreeUri
import org.fossify.commons.extensions.createSAFDirectorySdk30
import org.fossify.commons.extensions.getDocumentSdk30
import org.fossify.commons.extensions.getDoesFilePathExistSdk30
import org.fossify.commons.extensions.getDuration
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getMimeType
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.getSAFDocumentId
import org.fossify.commons.extensions.internalStoragePath
import org.fossify.commons.extensions.isAudioFast
import org.fossify.commons.helpers.isQPlus
import org.fossify.commons.helpers.isRPlus
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.helpers.Config
import org.fossify.voicerecorder.helpers.DEFAULT_RECORDINGS_FOLDER
import org.fossify.voicerecorder.helpers.DURATION_CACHE_PREFS
import org.fossify.voicerecorder.helpers.IS_RECORDING
import org.fossify.voicerecorder.helpers.MyWidgetRecordDisplayProvider
import org.fossify.voicerecorder.helpers.TOGGLE_WIDGET_UI
import org.fossify.voicerecorder.models.Recording
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

val Context.config: Config get() = Config.newInstance(applicationContext)

val Context.trashFolder
    get() = "${config.saveRecordingsFolder}/.trash"

fun Context.drawableToBitmap(drawable: Drawable): Bitmap {
    val size = (60 * resources.displayMetrics.density).toInt()
    val mutableBitmap = createBitmap(size, size)
    val canvas = Canvas(mutableBitmap)
    drawable.setBounds(0, 0, size, size)
    drawable.draw(canvas)
    return mutableBitmap
}

fun Context.updateWidgets(isRecording: Boolean) {
    val widgetIDs = AppWidgetManager.getInstance(applicationContext)
        ?.getAppWidgetIds(
            ComponentName(
                applicationContext,
                MyWidgetRecordDisplayProvider::class.java
            )
        ) ?: return

    if (widgetIDs.isNotEmpty()) {
        Intent(applicationContext, MyWidgetRecordDisplayProvider::class.java).apply {
            action = TOGGLE_WIDGET_UI
            putExtra(IS_RECORDING, isRecording)
            sendBroadcast(this)
        }
    }
}

fun Context.getOrCreateTrashFolder(): String {
    val folder = File(trashFolder)
    if (!folder.exists()) {
        folder.mkdir()
    }
    return trashFolder
}

fun Context.getDefaultRecordingsFolder(): String {
    val defaultPath = getDefaultRecordingsRelativePath()
    return "$internalStoragePath/$defaultPath"
}

fun Context.getDefaultRecordingsRelativePath(): String {
    return if (isQPlus()) {
        "${Environment.DIRECTORY_MUSIC}/$DEFAULT_RECORDINGS_FOLDER"
    } else {
        getString(R.string.app_name)
    }
}

fun Context.hasRecordings(): Boolean {
    val recordingsFolder = config.saveRecordingsFolder
    return if (isRPlus()) {
        getDocumentSdk30(recordingsFolder)
            ?.listFiles()
            ?.any { it.isAudioRecording() }
            ?: false
    } else {
        File(recordingsFolder)
            .listFiles()
            ?.any { it.isAudioFast() }
            ?: false
    }
}

fun Context.getAllRecordings(trashed: Boolean = false): ArrayList<Recording> {
    return if (isRPlus()) {
        val recordings = arrayListOf<Recording>()
        recordings.addAll(getRecordings(trashed))
        if (trashed) {
            // Return recordings trashed using MediaStore, this won't be needed in the future
            @Suppress("DEPRECATION")
            recordings.addAll(getMediaStoreTrashedRecordings())
        }

        recordings
    } else {
        getLegacyRecordings(trashed)
    }
}

// Reads a single recording by its uri — used to show a just-saved recording instantly,
// without re-listing (and re-reading metadata for) the whole folder.
fun Context.getRecording(uri: Uri): Recording? {
    return try {
        DocumentFile.fromSingleUri(this, uri)
            ?.takeIf { it.exists() && it.name != null }
            ?.let { readRecordingFromFile(it) }
    } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
        null
    }
}

private fun Context.getRecordings(trashed: Boolean = false): ArrayList<Recording> {
    val recordings = ArrayList<Recording>()
    val folder = if (trashed) trashFolder else config.saveRecordingsFolder
    val files = getDocumentSdk30(folder)?.listFiles() ?: return recordings
    files.forEach { file ->
        if (file.isAudioRecording()) {
            recordings.add(
                readRecordingFromFile(file)
            )
        }
    }

    return recordings
}

@Deprecated(
    message = "Use getRecordings instead. This method is only here for backward compatibility.",
    replaceWith = ReplaceWith("getRecordings(trashed = true)")
)
private fun Context.getMediaStoreTrashedRecordings(): ArrayList<Recording> {
    val recordings = ArrayList<Recording>()
    val folder = config.saveRecordingsFolder
    val documentFiles = getDocumentSdk30(folder)?.listFiles() ?: return recordings
    documentFiles.forEach { file ->
        if (file.isTrashedMediaStoreRecording()) {
            val recording = readRecordingFromFile(file)
            recordings.add(
                recording.copy(
                    title = "^\\.trashed-\\d+-".toRegex().replace(file.name!!, "")
                )
            )
        }
    }

    return recordings
}

private fun Context.getLegacyRecordings(trashed: Boolean = false): ArrayList<Recording> {
    val recordings = ArrayList<Recording>()
    val folder = if (trashed) {
        trashFolder
    } else {
        config.saveRecordingsFolder
    }
    val files = File(folder).listFiles() ?: return recordings

    files.filter { it.isAudioFast() }.forEach {
        val id = it.hashCode()
        val title = it.name
        val path = it.absolutePath
        val timestamp = it.lastModified()
        val size = it.length().toInt()
        val duration = cachedDuration(path, "$size|$timestamp") {
            (getDuration(it.absolutePath) ?: 0).toLong()
        }.toInt()
        recordings.add(
            Recording(
                id = id,
                title = title,
                path = path,
                timestamp = timestamp,
                duration = duration,
                size = size
            )
        )
    }
    return recordings
}

private fun Context.readRecordingFromFile(file: DocumentFile): Recording {
    val id = file.hashCode()
    val title = file.name!!
    val path = file.uri.toString()
    val timestamp = file.lastModified()
    val size = file.length().toInt()
    val duration = cachedDuration(path, "$size|$timestamp") { getDurationFromUri(file.uri) }
    return Recording(
        id = id,
        title = title,
        path = path,
        timestamp = timestamp,
        duration = duration.toInt(),
        size = size
    )
}

private fun Context.getDurationFromUri(uri: Uri): Long {
    return try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(this, uri)
        val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!
        (time.toLong() / 1000.toDouble()).roundToLong()
    } catch (e: Exception) {
        0L
    }
}

// Reading a recording's duration means opening and parsing the media file, which is by far the
// slowest part of listing recordings. Cache it persistently, keyed by the file plus a signature
// (size + last-modified) so a file that actually changes is re-read but unchanged ones are not.
private fun Context.cachedDuration(key: String, signature: String, compute: () -> Long): Long {
    val prefs = getSharedPreferences(DURATION_CACHE_PREFS, Context.MODE_PRIVATE)
    val cacheKey = "$key|$signature"
    val cached = prefs.getLong(cacheKey, -1L)
    if (cached >= 0L) {
        return cached
    }
    val computed = compute()
    prefs.edit().putLong(cacheKey, computed).apply()
    return computed
}

// Based on common's `Context.createSAFFileSdk30` extension
fun Context.createDocumentFile(path: String): Uri? {
    return try {
        val treeUri = createFirstParentTreeUri(path)
        val parentPath = path.getParentPath()
        if (!getDoesFilePathExistSdk30(parentPath)) {
            createSAFDirectorySdk30(parentPath)
        }

        val documentId = getSAFDocumentId(parentPath)
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        DocumentsContract.createDocument(
            contentResolver,
            parentUri,
            path.getMimeType(),
            path.getFilenameFromPath()
        )
    } catch (@Suppress("SwallowedException") e: IllegalStateException) {
        null
    }
}

// move to commons in the future
// True when [nameWithoutExtension] looks like an auto-generated name produced by the
// configured filename pattern (i.e. a bare timestamp the user never renamed).
fun Context.isAutoGeneratedRecordingName(nameWithoutExtension: String): Boolean {
    val pattern = config.filenamePattern
    val regex = buildString {
        append('^')
        var i = 0
        while (i < pattern.length) {
            if (pattern[i] == '%' && i + 1 < pattern.length) {
                when (pattern[i + 1]) {
                    'Y' -> append("\\d{4}")
                    'M', 'D', 'h', 'm', 's' -> append("\\d{2}")
                    else -> append(Regex.escape(pattern.substring(i, i + 2)))
                }
                i += 2
            } else {
                append(Regex.escape(pattern[i].toString()))
                i++
            }
        }
        append('$')
    }
    return regex.toRegex().matches(nameWithoutExtension)
}

// The title shown in the recordings list: a human-friendly date ("Jun 1 at 14:50") for
// auto-named recordings, otherwise the user's filename without its extension.
fun Context.getRecordingDisplayTitle(recording: Recording): String {
    val nameWithoutExtension = recording.title.substringBeforeLast('.')
    if (!isAutoGeneratedRecordingName(nameWithoutExtension)) {
        return nameWithoutExtension
    }

    val date = Date(recording.timestamp)
    val datePart = SimpleDateFormat("MMM d", Locale.getDefault()).format(date)
    val timePart = android.text.format.DateFormat.getTimeFormat(this).format(date)
    return getString(R.string.recording_title_date_time, datePart, timePart)
}

// Month header label for grouping ("June", or "June 2025" when not the current year).
fun recordingMonthLabel(timestamp: Long): String {
    val calendar = Calendar.getInstance().apply { timeInMillis = timestamp }
    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    val pattern = if (calendar.get(Calendar.YEAR) == currentYear) "LLLL" else "LLLL yyyy"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(calendar.time)
}

fun Context.getFormattedFilename(): String {
    val pattern = config.filenamePattern
    val calendar = Calendar.getInstance()

    val year = calendar.get(Calendar.YEAR).toString()
    val month = String.format(Locale.ROOT, "%02d", calendar.get(Calendar.MONTH) + 1)
    val day = String.format(Locale.ROOT, "%02d", calendar.get(Calendar.DAY_OF_MONTH))
    val hour = String.format(Locale.ROOT, "%02d", calendar.get(Calendar.HOUR_OF_DAY))
    val minute = String.format(Locale.ROOT, "%02d", calendar.get(Calendar.MINUTE))
    val second = String.format(Locale.ROOT, "%02d", calendar.get(Calendar.SECOND))

    return pattern
        .replace("%Y", year, false)
        .replace("%M", month, false)
        .replace("%D", day, false)
        .replace("%h", hour, false)
        .replace("%m", minute, false)
        .replace("%s", second, false)
}
