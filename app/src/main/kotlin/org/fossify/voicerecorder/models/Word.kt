package org.fossify.voicerecorder.models

import org.json.JSONArray
import org.json.JSONObject

// A silence longer than this between consecutive words inserts a timestamp marker into the
// rendered transcript (and the first words always get one). Display-only — stored text stays clean.
const val TRANSCRIPT_TIMESTAMP_GAP_MS = 5000L

// True if a timestamp should precede the word at [index]: it's the first word, or the silence
// from [prevEndMs] to [startMs] exceeds the threshold.
fun needsTimestamp(index: Int, prevEndMs: Long, startMs: Long): Boolean =
    index == 0 || startMs - prevEndMs > TRANSCRIPT_TIMESTAMP_GAP_MS

// A single transcribed word with its position in the recording, for read-along playback.
data class Word(val text: String, val startMs: Long, val endMs: Long) {
    companion object {
        fun toJson(words: List<Word>): String {
            val array = JSONArray()
            words.forEach { word ->
                array.put(
                    JSONObject()
                        .put("w", word.text)
                        .put("s", word.startMs)
                        .put("e", word.endMs)
                )
            }
            return array.toString()
        }

        fun fromJson(json: String): List<Word> {
            if (json.isEmpty()) {
                return emptyList()
            }

            return try {
                val array = JSONArray(json)
                (0 until array.length()).map { index ->
                    val item = array.getJSONObject(index)
                    Word(item.getString("w"), item.getLong("s"), item.getLong("e"))
                }
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                emptyList()
            }
        }
    }
}
