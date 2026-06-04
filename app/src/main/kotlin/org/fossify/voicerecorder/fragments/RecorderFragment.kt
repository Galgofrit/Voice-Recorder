package org.fossify.voicerecorder.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.ColorUtils
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.compose.extensions.getActivity
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.PermissionRequiredDialog
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getColoredDrawableWithColor
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getFormattedDuration
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.openNotificationSettings
import org.fossify.commons.extensions.setDebouncedClickListener
import org.fossify.commons.extensions.toast
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.databinding.FragmentRecorderBinding
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.extensions.ensureStoragePermission
import org.fossify.voicerecorder.extensions.setKeepScreenAwake
import org.fossify.voicerecorder.helpers.CANCEL_RECORDING
import org.fossify.voicerecorder.helpers.GET_RECORDER_INFO
import org.fossify.voicerecorder.helpers.RECORDING_PAUSED
import org.fossify.voicerecorder.helpers.RECORDING_RUNNING
import org.fossify.voicerecorder.helpers.RECORDING_STOPPED
import org.fossify.voicerecorder.helpers.TOGGLE_PAUSE
import org.fossify.voicerecorder.models.Events
import org.fossify.voicerecorder.services.RecorderService
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class RecorderFragment(
    context: Context,
    attributeSet: AttributeSet
) : MyViewPagerFragment(context, attributeSet) {

    companion object {
        // Matches PlaybackActivity so the recorder card has the same tint as the player.
        private const val CARD_TINT_RATIO = 0.05f
        private const val TRANSFORM_DURATION_MS = 300L
        private const val RECORD_FADE_SCALE = 0.2f
    }

    private var status = RECORDING_STOPPED
    private var previousStatus = RECORDING_STOPPED
    private var bus: EventBus? = null
    private lateinit var binding: FragmentRecorderBinding

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = FragmentRecorderBinding.bind(this)
    }

    override fun onResume() {
        setupColors()
        if (!RecorderService.isRunning) {
            status = RECORDING_STOPPED
        }

        refreshView()
    }

    override fun onDestroy() {
        bus?.unregister(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setupColors()
        binding.recorderVisualizer.recreate()
        bus = EventBus.getDefault()
        bus!!.register(this)

        updateRecordingDuration(0)
        binding.toggleRecordingButton.setDebouncedClickListener {
            val activity = context as? BaseSimpleActivity
            activity?.ensureStoragePermission {
                if (it) {
                    activity.handleNotificationPermission { granted ->
                        if (granted) {
                            startRecordingFromUi()
                        } else {
                            PermissionRequiredDialog(
                                activity = context as BaseSimpleActivity,
                                textId = org.fossify.commons.R.string.allow_notifications_voice_recorder,
                                positiveActionCallback = {
                                    (context as BaseSimpleActivity).openNotificationSettings()
                                }
                            )
                        }
                    }
                } else {
                    activity.toast(org.fossify.commons.R.string.no_storage_permissions)
                }
            }
        }

        binding.pauseButton.setDebouncedClickListener { togglePause() }
        binding.stopButton.setDebouncedClickListener { saveRecording() }

        Intent(context, RecorderService::class.java).apply {
            action = GET_RECORDER_INFO
            try {
                context.startService(this)
            } catch (ignored: Exception) {
            }
        }
    }

    private fun setupColors() {
        val properTextColor = context.getProperTextColor()
        val properPrimaryColor = context.getProperPrimaryColor()
        binding.toggleRecordingButton.apply {
            setImageDrawable(getToggleButtonIcon())
            background.applyColorFilter(properPrimaryColor)
        }

        binding.recorderVisualizer.chunkColor = properPrimaryColor
        binding.recordingDuration.setTextColor(properTextColor)

        val cardColor = ColorUtils.blendARGB(
            context.getProperBackgroundColor(), properTextColor, CARD_TINT_RATIO
        )
        binding.recorderCard.setCardBackgroundColor(cardColor)
    }

    private fun updateRecordingDuration(duration: Int) {
        binding.recordingDuration.text = duration.getFormattedDuration()
    }

    private fun getToggleButtonIcon(): Drawable = resources.getColoredDrawableWithColor(
        drawableId = R.drawable.ic_record_circle,
        color = context.getProperPrimaryColor().getContrastColor()
    )

    private fun startRecordingFromUi() {
        Intent(context, RecorderService::class.java).apply {
            context.startService(this)
        }
        status = RECORDING_RUNNING
        refreshView()
    }

    private fun togglePause() {
        Intent(context, RecorderService::class.java).apply {
            action = TOGGLE_PAUSE
            context.startService(this)
        }
        status = if (status == RECORDING_RUNNING) RECORDING_PAUSED else RECORDING_RUNNING
        refreshView()
    }

    private fun showCancelRecordingDialog() {
        val activity = context as? BaseSimpleActivity ?: return
        ConfirmationDialog(
            activity = activity,
            message = activity.getString(R.string.discard_recording_confirmation),
            dialogTitle = activity.getString(R.string.discard_recording)
        ) {
            cancelRecording()
        }
    }

    private fun cancelRecording() {
        leaveRecording()
        Intent(context, RecorderService::class.java).apply {
            action = CANCEL_RECORDING
            context.startService(this)
        }
    }

    // Discards the in-progress recording (wired to the trash icon in the title bar).
    fun discardRecording() = showCancelRecordingDialog()

    // Called by MainActivity's record FAB to start recording in one tap (reuses the
    // permission-gated start flow on the record button).
    fun beginRecording() {
        if (status == RECORDING_STOPPED) {
            binding.toggleRecordingButton.performClick()
        }
    }

    fun saveRecording() {
        leaveRecording()
        Intent(context, RecorderService::class.java).apply {
            context.stopService(this)
        }
    }

    // Marks the recording as stopped without repainting the idle record icon, which would
    // otherwise flash on the toggle button as we leave for the list.
    private fun leaveRecording() {
        status = RECORDING_STOPPED
    }

    @SuppressLint("DiscouragedApi")
    private fun refreshView() {
        val recording = status != RECORDING_STOPPED
        val justStarted = previousStatus == RECORDING_STOPPED && status == RECORDING_RUNNING
        previousStatus = status

        binding.toggleRecordingButton.setImageDrawable(getToggleButtonIcon())
        updatePauseButton()

        if (justStarted) {
            animateRecordTransform()
        } else {
            binding.toggleRecordingButton.beVisibleIf(!recording)
            binding.pauseButton.beVisibleIf(recording)
            binding.stopButton.beVisibleIf(recording)
        }

        when (status) {
            RECORDING_RUNNING -> {
                if (context.config.keepScreenOn) {
                    context.getActivity().setKeepScreenAwake(true)
                }
            }

            RECORDING_STOPPED -> {
                binding.recorderVisualizer.recreate()
                binding.recordingDuration.text = null
            }
        }
    }

    private fun updatePauseButton() {
        if (status == RECORDING_PAUSED) {
            binding.pauseButton.setText(R.string.resume)
            binding.pauseButton.setIconResource(org.fossify.commons.R.drawable.ic_play_vector)
        } else {
            binding.pauseButton.setText(R.string.pause)
            binding.pauseButton.setIconResource(org.fossify.commons.R.drawable.ic_pause_vector)
        }
    }

    // Smoothly transforms the single record button into the Pause + Stop buttons: the
    // record button shrinks away to nothing while the two buttons simultaneously grow in
    // from zero at their resting positions. Started synchronously (no posted frame) so the
    // record button is never shown sitting still before the transition.
    private fun animateRecordTransform() {
        val record = binding.toggleRecordingButton
        val pause = binding.pauseButton
        val stop = binding.stopButton

        listOf(pause, stop).forEach { button ->
            button.beVisibleIf(true)
            button.alpha = 0f
            button.scaleX = 0f
            button.scaleY = 0f
            button.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(TRANSFORM_DURATION_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        record.animate()
            .alpha(0f)
            .scaleX(RECORD_FADE_SCALE)
            .scaleY(RECORD_FADE_SCALE)
            .setDuration(TRANSFORM_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                record.beVisibleIf(false)
                record.alpha = 1f
                record.scaleX = 1f
                record.scaleY = 1f
            }
            .start()
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun gotDurationEvent(event: Events.RecordingDuration) {
        updateRecordingDuration(event.duration)
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun gotStatusEvent(event: Events.RecordingStatus) {
        status = event.status
        refreshView()
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun gotAmplitudeEvent(event: Events.RecordingAmplitude) {
        val amplitude = event.amplitude
        if (status == RECORDING_RUNNING) {
            binding.recorderVisualizer.update(amplitude)
        }
    }
}
