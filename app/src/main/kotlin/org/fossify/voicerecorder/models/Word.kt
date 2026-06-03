package org.fossify.voicerecorder.models

import org.json.JSONArray
import org.json.JSONObject

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
