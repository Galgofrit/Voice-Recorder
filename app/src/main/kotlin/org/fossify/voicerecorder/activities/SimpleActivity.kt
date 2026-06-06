package org.fossify.voicerecorder.activities

import android.content.Context
import android.content.res.Configuration
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.helpers.FONT_SIZE_EXTRA_LARGE
import org.fossify.commons.helpers.FONT_SIZE_LARGE
import org.fossify.commons.helpers.FONT_SIZE_SMALL
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.helpers.FONT_SCALE_EXTRA_LARGE
import org.fossify.voicerecorder.helpers.FONT_SCALE_LARGE
import org.fossify.voicerecorder.helpers.FONT_SCALE_MEDIUM
import org.fossify.voicerecorder.helpers.FONT_SCALE_SMALL
import org.fossify.voicerecorder.helpers.REPOSITORY_NAME

open class SimpleActivity : BaseSimpleActivity() {
    // Subclasses can enlarge text relative to the rest of the app (Settings bumps this).
    protected open fun extraFontScale() = 1f

    // Apply the chosen font size globally by scaling the configuration's fontScale, so every
    // sp-sized text in the app grows/shrinks together (commons only scales per-view).
    override fun attachBaseContext(newBase: Context) {
        val sizeMultiplier = when (newBase.config.fontSize) {
            FONT_SIZE_SMALL -> FONT_SCALE_SMALL
            FONT_SIZE_LARGE -> FONT_SCALE_LARGE
            FONT_SIZE_EXTRA_LARGE -> FONT_SCALE_EXTRA_LARGE
            else -> FONT_SCALE_MEDIUM
        }
        val multiplier = sizeMultiplier * extraFontScale()
        if (multiplier == 1f) {
            super.attachBaseContext(newBase)
        } else {
            val configuration = Configuration(newBase.resources.configuration)
            configuration.fontScale = newBase.resources.configuration.fontScale * multiplier
            super.attachBaseContext(newBase.createConfigurationContext(configuration))
        }
    }

    override fun getAppIconIDs() = arrayListOf(
        R.mipmap.ic_launcher_red,
        R.mipmap.ic_launcher_pink,
        R.mipmap.ic_launcher_purple,
        R.mipmap.ic_launcher_deep_purple,
        R.mipmap.ic_launcher_indigo,
        R.mipmap.ic_launcher_blue,
        R.mipmap.ic_launcher_light_blue,
        R.mipmap.ic_launcher_cyan,
        R.mipmap.ic_launcher_teal,
        R.mipmap.ic_launcher,
        R.mipmap.ic_launcher_light_green,
        R.mipmap.ic_launcher_lime,
        R.mipmap.ic_launcher_yellow,
        R.mipmap.ic_launcher_amber,
        R.mipmap.ic_launcher_orange,
        R.mipmap.ic_launcher_deep_orange,
        R.mipmap.ic_launcher_brown,
        R.mipmap.ic_launcher_blue_grey,
        R.mipmap.ic_launcher_grey_black
    )

    override fun getAppLauncherName() = getString(R.string.app_launcher_name)

    override fun getRepositoryName() = REPOSITORY_NAME
}
