package org.fossify.voicerecorder.activities

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.provider.MediaStore
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.appLaunched
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.checkAppSideloading
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.isAValidFilename
import org.fossify.commons.extensions.launchMoreAppsFromUsIntent
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.showKeyboard
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.commons.helpers.LICENSE_ANDROID_LAME
import org.fossify.commons.helpers.LICENSE_AUDIO_RECORD_VIEW
import org.fossify.commons.helpers.LICENSE_AUTOFITTEXTVIEW
import org.fossify.commons.helpers.LICENSE_EVENT_BUS
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.PERMISSION_RECORD_AUDIO
import org.fossify.commons.helpers.PERMISSION_WRITE_STORAGE
import org.fossify.commons.helpers.isRPlus
import org.fossify.commons.models.FAQItem
import org.fossify.voicerecorder.BuildConfig
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.databinding.ActivityMainBinding
import org.fossify.voicerecorder.databinding.DialogRenameRecordingBinding
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.extensions.deleteExpiredTrashedRecordings
import org.fossify.voicerecorder.extensions.ensureStoragePermission
import org.fossify.voicerecorder.extensions.renameRecording
import org.fossify.voicerecorder.helpers.STOP_AMPLITUDE_UPDATE
import org.fossify.voicerecorder.models.Events
import org.fossify.voicerecorder.services.RecorderService
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class MainActivity : SimpleActivity() {

    private enum class Screen { LIST, RECORDER, TRASH }

    private var bus: EventBus? = null
    private var currentScreen = Screen.LIST
    private var initialized = false

    // The custom name the user typed for the in-progress recording (if any). It is
    // applied to the file once it's saved.
    private var pendingCustomName: String? = null

    override var isSearchBarEnabled = true

    private lateinit var binding: ActivityMainBinding

    private val playerView get() = binding.playerFragment.root
    private val recorderView get() = binding.recorderFragment.root
    private val trashView get() = binding.trashFragment.root

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        appLaunched(BuildConfig.APPLICATION_ID)
        setupOptionsMenu()
        refreshMenuItems()

        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.mainHolder))

        if (checkAppSideloading()) {
            return
        }

        if (savedInstanceState == null) {
            deleteExpiredTrashedRecordings()
        }

        handlePermission(PERMISSION_RECORD_AUDIO) {
            if (it) {
                tryInitVoiceRecorder()
            } else {
                toast(org.fossify.commons.R.string.no_audio_permissions)
                finish()
            }
        }

        bus = EventBus.getDefault()
        bus!!.register(this)
        if (config.recordAfterLaunch && !RecorderService.isRunning) {
            Intent(this@MainActivity, RecorderService::class.java).apply {
                try {
                    startService(this)
                } catch (ignored: Exception) {
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.mainMenu.updateColors()
        setupTopAppBar(binding.recorderFragment.recorderAppbar, NavigationIcon.None)
        refreshMenuItems()
        setupRecordFab()
        if (initialized) {
            playerView.onResume()
            recorderView.onResume()
            trashView.onResume()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bus?.unregister(this)
        if (initialized) {
            playerView.onDestroy()
            recorderView.onDestroy()
            trashView.onDestroy()
        }

        Intent(this@MainActivity, RecorderService::class.java).apply {
            action = STOP_AMPLITUDE_UPDATE
            try {
                startService(this)
            } catch (ignored: Exception) {
            }
        }
    }

    override fun onBackPressedCompat(): Boolean {
        return when {
            binding.mainMenu.isSearchOpen -> {
                binding.mainMenu.closeSearch()
                true
            }

            RecorderService.isRunning -> {
                showStopRecordingDialog()
                true
            }

            currentScreen != Screen.LIST -> {
                showScreen(Screen.LIST)
                true
            }

            isThirdPartyIntent() -> {
                setResult(Activity.RESULT_CANCELED, null)
                false
            }

            else -> false
        }
    }

    private fun refreshMenuItems() {
        binding.mainMenu.requireToolbar().menu.apply {
            findItem(R.id.recycle_bin).isVisible = config.useRecycleBin
            findItem(R.id.more_apps_from_us).isVisible = !resources.getBoolean(
                org.fossify.commons.R.bool.hide_google_relations
            )
        }
    }

    private fun setupOptionsMenu() {
        binding.mainMenu.requireToolbar().inflateMenu(R.menu.menu)
        binding.mainMenu.toggleHideOnScroll(false)
        binding.mainMenu.setupMenu()

        binding.mainMenu.onSearchTextChangedListener = { text ->
            when (currentScreen) {
                Screen.LIST -> playerView.onSearchTextChanged(text)
                Screen.TRASH -> trashView.onSearchTextChanged(text)
                else -> {}
            }
        }

        binding.mainMenu.requireToolbar().setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.recycle_bin -> showScreen(Screen.TRASH)
                R.id.more_apps_from_us -> launchMoreAppsFromUsIntent()
                R.id.settings -> launchSettings()
                R.id.about -> launchAbout()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun tryInitVoiceRecorder() {
        if (isRPlus()) {
            ensureStoragePermission { granted ->
                if (granted) {
                    setupContent()
                } else {
                    toast(org.fossify.commons.R.string.no_storage_permissions)
                    finish()
                }
            }
        } else {
            handlePermission(PERMISSION_WRITE_STORAGE) {
                if (it) {
                    setupContent()
                } else {
                    toast(org.fossify.commons.R.string.no_storage_permissions)
                    finish()
                }
            }
        }
    }

    private fun setupContent() {
        initialized = true
        setupRecordFab()
        binding.recordFab.setOnClickListener {
            showScreen(Screen.RECORDER)
            recorderView.beginRecording()
        }
        binding.recorderFragment.recorderToolbar.apply {
            setOnClickListener { showRenamePendingDialog() }
            inflateMenu(R.menu.recorder)
            setOnMenuItemClickListener { item ->
                if (item.itemId == R.id.discard_recording) {
                    recorderView.discardRecording()
                    true
                } else {
                    false
                }
            }
        }

        val startScreen = if (isThirdPartyIntent() || config.recordAfterLaunch) {
            Screen.RECORDER
        } else {
            Screen.LIST
        }
        showScreen(startScreen)
    }

    private fun setupRecordFab() {
        binding.recordFab.setImageResource(R.drawable.ic_record_circle)
        val accentColor = config.recordingAccentColor
        binding.recordFab.backgroundTintList = ColorStateList.valueOf(accentColor)
        binding.recordFab.imageTintList = ColorStateList.valueOf(accentColor.getContrastColor())
    }

    private fun showScreen(screen: Screen) {
        currentScreen = screen
        playerView.beVisibleIf(screen == Screen.LIST)
        recorderView.beVisibleIf(screen == Screen.RECORDER)
        trashView.beVisibleIf(screen == Screen.TRASH)
        binding.recordFab.beVisibleIf(screen == Screen.LIST)

        // While recording, hide the global search bar; the recorder fragment shows its
        // own title bar (the search bar is the only AppBarLayout, so the content sits
        // directly below it / fills from the top when it's hidden).
        binding.mainMenu.beVisibleIf(screen != Screen.RECORDER)

        if (screen != Screen.LIST && screen != Screen.TRASH) {
            binding.mainMenu.closeSearch()
        }

        when (screen) {
            Screen.LIST -> playerView.onResume()
            Screen.RECORDER -> recorderView.onResume()
            Screen.TRASH -> trashView.onResume()
        }
    }

    private fun showStopRecordingDialog() {
        ConfirmationDialog(
            activity = this,
            message = getString(R.string.stop_recording_description),
            positive = R.string.stop,
            negative = org.fossify.commons.R.string.cancel,
            dialogTitle = getString(R.string.stop_recording_title),
        ) {
            recorderView.saveRecording()
        }
    }

    private fun launchSettings() {
        hideKeyboard()
        startActivity(Intent(applicationContext, SettingsActivity::class.java))
    }

    private fun launchAbout() {
        val licenses = LICENSE_EVENT_BUS or
                LICENSE_AUDIO_RECORD_VIEW or
                LICENSE_ANDROID_LAME or
                LICENSE_AUTOFITTEXTVIEW

        val faqItems = arrayListOf(
            FAQItem(
                title = R.string.faq_1_title,
                text = R.string.faq_1_text
            ),
            FAQItem(
                title = org.fossify.commons.R.string.faq_9_title_commons,
                text = org.fossify.commons.R.string.faq_9_text_commons
            )
        )

        if (!resources.getBoolean(org.fossify.commons.R.bool.hide_google_relations)) {
            faqItems.add(
                FAQItem(
                    title = org.fossify.commons.R.string.faq_2_title_commons,
                    text = org.fossify.commons.R.string.faq_2_text_commons
                )
            )
            faqItems.add(
                FAQItem(
                    title = org.fossify.commons.R.string.faq_6_title_commons,
                    text = org.fossify.commons.R.string.faq_6_text_commons
                )
            )
        }

        startAboutActivity(
            appNameId = R.string.app_name,
            licenseMask = licenses,
            versionName = BuildConfig.VERSION_NAME,
            faqItems = faqItems,
            showFAQBeforeMail = true
        )
    }

    private fun isThirdPartyIntent() = intent?.action == MediaStore.Audio.Media.RECORD_SOUND_ACTION

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun recordingCompleted(@Suppress("UNUSED_PARAMETER") event: Events.RecordingCompleted) {
        if (currentScreen == Screen.RECORDER && !isThirdPartyIntent()) {
            showScreen(Screen.LIST)
        }
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun recordingSaved(event: Events.RecordingSaved) {
        val customName = pendingCustomName
        val savedBaseName = event.name.substringBeforeLast('.')
        if (!customName.isNullOrEmpty() && customName != savedBaseName) {
            renameRecording(event.name, customName)
        }

        pendingCustomName = null

        if (isThirdPartyIntent()) {
            Intent().apply {
                data = event.uri!!
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                setResult(Activity.RESULT_OK, this)
            }
            finish()
        }
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun gotRecordingFilename(event: Events.RecordingFilename) {
        pendingCustomName = null
        binding.recorderFragment.recorderToolbar.title = event.name
    }

    private fun showRenamePendingDialog() {
        val dialogBinding = DialogRenameRecordingBinding.inflate(layoutInflater).apply {
            renameRecordingTitle.setText(binding.recorderFragment.recorderToolbar.title)
        }

        getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                setupDialogStuff(
                    view = dialogBinding.root,
                    dialog = this,
                    titleId = org.fossify.commons.R.string.rename
                ) { alertDialog ->
                    alertDialog.showKeyboard(dialogBinding.renameRecordingTitle)
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val newTitle = dialogBinding.renameRecordingTitle.value
                        when {
                            newTitle.isEmpty() ->
                                toast(org.fossify.commons.R.string.empty_name)

                            !newTitle.isAValidFilename() ->
                                toast(org.fossify.commons.R.string.invalid_name)

                            else -> {
                                pendingCustomName = newTitle
                                binding.recorderFragment.recorderToolbar.title = newTitle
                                alertDialog.dismiss()
                            }
                        }
                    }
                }
            }
    }
}
