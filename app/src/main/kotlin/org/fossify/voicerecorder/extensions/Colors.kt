package org.fossify.voicerecorder.extensions

import android.content.Context
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.voicerecorder.helpers.CARD_TINT_RATIO_AUTO
import org.fossify.voicerecorder.helpers.RECORD_DOT_LIGHTNESS_FACTOR

// The surface color for recording cards. If the user set an explicit color it's used as-is;
// otherwise (transparent sentinel) it's derived from the background so it still adapts to the
// active theme — matching the previous auto behavior.
fun Context.cardSurfaceColor(): Int {
    val configured = config.recordingCardColor
    return if (Color.alpha(configured) == 0) {
        ColorUtils.blendARGB(getProperBackgroundColor(), getProperTextColor(), CARD_TINT_RATIO_AUTO)
    } else {
        configured
    }
}

// The record button's inner dot: the accent color darkened by lowering its HSL lightness only,
// so it keeps the hue/saturation (a vivid dark red for the coral accent, à la Google Recorder).
@Suppress("MagicNumber") // HSL array is always size 3, lightness at index 2
fun Int.darkenForRecordDot(): Int {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(this, hsl)
    hsl[2] *= RECORD_DOT_LIGHTNESS_FACTOR
    return ColorUtils.HSLToColor(hsl)
}
