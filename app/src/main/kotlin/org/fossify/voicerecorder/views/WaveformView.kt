package org.fossify.voicerecorder.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * A fixed-scale waveform that scrolls horizontally under a fixed center needle:
 * the bar at the current playback position sits at the horizontal center, the
 * waveform pans left as playback advances, and dragging pans it for fine seeking.
 * Bars occupy a centered vertical band (the rest of the height stays clear).
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private companion object {
        const val BAR_SLOT_DP = 5f
        const val BAR_WIDTH_RATIO = 0.55f
        const val NEEDLE_WIDTH_DP = 2.5f
        const val WAVE_BAND_FRACTION = 0.5f
        const val MIN_BAR_FRACTION = 0.06f
    }

    private val density = resources.displayMetrics.density
    private val barSlotPx = BAR_SLOT_DP * density
    private val barWidthPx = max(1f, barSlotPx * BAR_WIDTH_RATIO)
    private val needleWidthPx = NEEDLE_WIDTH_DP * density

    private var amplitudes = FloatArray(0)
    private var progress = 0f
    private var isUserScrubbing = false
    private var lastTouchX = 0f

    private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val unplayedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barRect = RectF()

    var onSeek: ((fraction: Float) -> Unit)? = null

    // Reports the currently-visible bar range so the host can lazily decode just that
    // window of the waveform instead of the whole file up front.
    var onVisibleRangeChanged: ((firstBar: Int, lastBar: Int) -> Unit)? = null

    // Supplies the live playback fraction; queried once per displayed frame so the
    // scroll is smooth and vsync-aligned (matches high-refresh-rate screens).
    var positionProvider: (() -> Float)? = null
    private var ticking = false
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!ticking) {
                return
            }
            if (!isUserScrubbing) {
                positionProvider?.let { progress = it().coerceIn(0f, 1f) }
            }
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun startTicking() {
        if (!ticking) {
            ticking = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    fun stopTicking() {
        ticking = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopTicking()
    }

    fun setColors(playedColor: Int, unplayedColor: Int) {
        playedPaint.color = playedColor
        unplayedPaint.color = unplayedColor
        needlePaint.color = playedColor
        invalidate()
    }

    fun setAmplitudes(values: FloatArray) {
        amplitudes = values
        invalidate()
    }

    // Position updates from playback are ignored while the user is dragging.
    fun setProgress(fraction: Float) {
        if (isUserScrubbing) {
            return
        }
        progress = fraction.coerceIn(0f, 1f)
        invalidate()
    }

    private fun contentWidth() = amplitudes.size * barSlotPx

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val count = amplitudes.size
        if (count == 0 || width == 0 || height == 0) {
            return
        }

        val centerX = width / 2f
        val centerY = height / 2f
        val band = height * WAVE_BAND_FRACTION
        val minBar = band * MIN_BAR_FRACTION
        val scrollX = progress * contentWidth()

        val firstVisible = floor((scrollX - centerX) / barSlotPx).toInt().coerceAtLeast(0)
        val lastVisible = ceil((scrollX + (width - centerX)) / barSlotPx).toInt()
            .coerceAtMost(count - 1)

        onVisibleRangeChanged?.invoke(firstVisible, lastVisible)

        for (i in firstVisible..lastVisible) {
            val contentX = i * barSlotPx
            val screenX = centerX + (contentX - scrollX)
            val barHeight = max(minBar, amplitudes[i] * band)
            barRect.set(
                screenX - barWidthPx / 2f,
                centerY - barHeight / 2f,
                screenX + barWidthPx / 2f,
                centerY + barHeight / 2f
            )
            val paint = if (contentX <= scrollX) playedPaint else unplayedPaint
            canvas.drawRoundRect(barRect, barWidthPx / 2f, barWidthPx / 2f, paint)
        }

        // Fixed center needle, spanning the same central band as the bars.
        barRect.set(
            centerX - needleWidthPx / 2f,
            centerY - band / 2f,
            centerX + needleWidthPx / 2f,
            centerY + band / 2f
        )
        canvas.drawRoundRect(barRect, needleWidthPx / 2f, needleWidthPx / 2f, needlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                isUserScrubbing = true
                lastTouchX = event.x
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val width = contentWidth()
                if (width > 0f) {
                    // Drag left -> advance; drag right -> rewind.
                    progress = (progress - (event.x - lastTouchX) / width).coerceIn(0f, 1f)
                    lastTouchX = event.x
                    invalidate()
                    onSeek?.invoke(progress)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isUserScrubbing = false
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
