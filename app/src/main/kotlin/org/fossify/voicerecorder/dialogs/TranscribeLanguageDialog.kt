package org.fossify.voicerecorder.dialogs

import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.databinding.DialogTranscribeLanguageBinding
import org.fossify.voicerecorder.transcription.WhisperLanguages

// Lets the user pick a language and confirm with OK/Cancel before (re-)transcribing. The
// language list is a collapsed dropdown that opens on tap, preselected to [currentLanguage].
class TranscribeLanguageDialog(
    val activity: BaseSimpleActivity,
    val currentLanguage: String,
    val callback: (language: String) -> Unit,
) {
    private val languages = WhisperLanguages.SORTED_BY_NAME

    init {
        val binding = DialogTranscribeLanguageBinding.inflate(activity.layoutInflater)
        val names = languages.map { it.second }
        val textColor = activity.getProperTextColor()

        // The Material dropdown doesn't follow the app's runtime theme, so colour the items
        // ourselves; the no-op filter keeps the full list visible (it's a fixed pick list).
        val adapter = object : ArrayAdapter<String>(
            activity, android.R.layout.simple_list_item_1, names
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(textColor)
                }

            override fun getFilter(): Filter = object : Filter() {
                override fun performFiltering(constraint: CharSequence?) =
                    FilterResults().apply { values = names; count = names.size }

                override fun publishResults(constraint: CharSequence?, results: FilterResults?) =
                    notifyDataSetChanged()
            }
        }

        binding.transcribeLanguageDropdown.apply {
            setAdapter(adapter)
            setTextColor(textColor)
            setText(WhisperLanguages.nameOf(currentLanguage), false)
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.transcribe) { alertDialog ->
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val name = binding.transcribeLanguageDropdown.text.toString()
                        val language = languages.firstOrNull { it.second == name }?.first
                            ?: currentLanguage
                        callback(language)
                        alertDialog.dismiss()
                    }
                }
            }
    }
}
