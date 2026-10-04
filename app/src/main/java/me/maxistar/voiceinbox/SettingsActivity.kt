package me.maxistar.voiceinbox

import me.maxistar.voiceinbox.core.*

import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

internal object VoiceInboxPublicLinks {
    const val WEBSITE = "https://voiceinbox.simpleditor.org/"
    const val DOCUMENTATION = "https://voiceinbox.simpleditor.org/docs/"
    const val VOICE_KEYBOARD_DOCUMENTATION = "https://voiceinbox.simpleditor.org/docs/voice-keyboard/"
    const val LEGAL = "https://voiceinbox.simpleditor.org/legal/"
}

class SettingsActivity : AppCompatActivity() {
    private lateinit var settingsStore: ScheduledTranscriptionSettingsStore
    private lateinit var startupPolicyStore: StartupProcessingPolicyStore
    private lateinit var selectionStore: DocumentSelectionStore
    private lateinit var documentAccess: DocumentAccess
    private lateinit var folderScanner: AudioFolderScanner
    private lateinit var folderDetail: TextView
    private lateinit var outputDetail: TextView
    private lateinit var modelDetail: TextView
    private lateinit var scheduledSwitch: SwitchCompat
    private lateinit var scheduledTime: TextView
    private lateinit var scheduledTimeDetail: TextView
    private lateinit var keyboardStatusView: TextView
    private lateinit var keyboardActionView: TextView
    private lateinit var keyboardStatusProvider: AndroidVoiceKeyboardStatusProvider
    private lateinit var keyboardSystemGateway: AndroidVoiceKeyboardSystemGateway
    private val selectionExecutor = Executors.newSingleThreadExecutor()
    private var settings = ScheduledTranscriptionSettings()
    private var activityDestroyed = false

    private val outputPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) acceptOutput(uri) else renderStorage()
    }

    private val outputCreator = registerForActivityResult(
        ActivityResultContracts.CreateDocument(FileSelectionRules.CREATED_OUTPUT_MIME_TYPE),
    ) { uri ->
        if (uri != null) acceptOutput(uri) else renderStorage()
    }

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) acceptFolder(uri) else renderStorage()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)
        setSupportActionBar(findViewById(R.id.settingsToolbar))
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle(R.string.settings_title)
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settingsRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        settingsStore = ScheduledTranscriptionSettingsStore(
            getSharedPreferences(ScheduledTranscriptionSettingsStore.PREFERENCES_NAME, MODE_PRIVATE),
        )
        startupPolicyStore = StartupProcessingPolicyStore(
            getSharedPreferences(StartupProcessingPolicyStore.PREFERENCES_NAME, MODE_PRIVATE),
        )
        selectionStore = DocumentSelectionStore(
            getSharedPreferences(DocumentSelectionStore.PREFERENCES_NAME, MODE_PRIVATE),
        )
        documentAccess = DocumentAccess(contentResolver)
        folderScanner = AudioFolderScanner(contentResolver)
        keyboardStatusProvider = AndroidVoiceKeyboardStatusProvider(this)
        keyboardSystemGateway = AndroidVoiceKeyboardSystemGateway(this)
        settings = settingsStore.load()
        folderDetail = findViewById(R.id.settingsFolderDetail)
        outputDetail = findViewById(R.id.settingsOutputDetail)
        modelDetail = findViewById(R.id.settingsModelDetail)
        scheduledTime = findViewById(R.id.scheduledTime)
        scheduledTimeDetail = findViewById(R.id.scheduledTimeDetail)
        scheduledSwitch = findViewById(R.id.scheduledSwitch)
        keyboardStatusView = findViewById(R.id.settingsVoiceKeyboardStatus)
        keyboardActionView = findViewById(R.id.settingsVoiceKeyboardAction)
        findViewById<TextView>(R.id.settingsAboutVersion).text = getString(
            R.string.settings_about_version,
            appVersionName(),
        )
        findViewById<View>(R.id.settingsFolderRow).setOnClickListener {
            folderPicker.launch(selectionStore.loadFolderUri()?.let(Uri::parse))
        }
        findViewById<View>(R.id.settingsOutputRow).setOnClickListener {
            showOutputDocumentOptions()
        }
        findViewById<View>(R.id.settingsModelRow).setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra("open-model-folder-picker", true),
            )
        }
        keyboardActionView.setOnClickListener {
            val action = AndroidVoiceKeyboardActionPresenter.systemAction(keyboardStatusProvider.current())
            if (!keyboardSystemGateway.perform(action)) {
                Toast.makeText(this, R.string.voice_keyboard_system_action_error, Toast.LENGTH_LONG).show()
            }
        }
        findViewById<View>(R.id.settingsVoiceKeyboardDocumentation).setOnClickListener {
            openExternalUrl(VoiceInboxPublicLinks.VOICE_KEYBOARD_DOCUMENTATION)
        }
        findViewById<View>(R.id.settingsWebsiteRow).setOnClickListener {
            openExternalUrl(VoiceInboxPublicLinks.WEBSITE)
        }
        findViewById<View>(R.id.settingsLegalRow).setOnClickListener {
            openExternalUrl(VoiceInboxPublicLinks.LEGAL)
        }
        val startupPolicyGroup = findViewById<RadioGroup>(R.id.startupProcessingPolicy)
        startupPolicyGroup.check(
            when (startupPolicyStore.load()) {
                StartupProcessingPolicy.ASK -> R.id.startupProcessingAsk
                StartupProcessingPolicy.AUTOMATIC -> R.id.startupProcessingAutomatic
                StartupProcessingPolicy.LEAVE_QUEUED -> R.id.startupProcessingLeaveQueued
            },
        )
        startupPolicyGroup.setOnCheckedChangeListener { _, checkedId ->
            val policy = when (checkedId) {
                R.id.startupProcessingAutomatic -> StartupProcessingPolicy.AUTOMATIC
                R.id.startupProcessingLeaveQueued -> StartupProcessingPolicy.LEAVE_QUEUED
                else -> StartupProcessingPolicy.ASK
            }
            startupPolicyStore.save(policy)
        }
        scheduledSwitch.isChecked = settings.enabled
        scheduledSwitch.setOnCheckedChangeListener { _, enabled ->
            settings = settingsStore.setEnabled(enabled)
            ScheduledTranscriptionScheduler.sync(this, settings)
            renderSchedule()
        }
        findViewById<View>(R.id.scheduledTimeRow).setOnClickListener {
            showScheduledTimePicker()
        }
        renderStorage()
        renderSchedule()
        renderVoiceKeyboard()
    }

    override fun onResume() {
        super.onResume()
        renderStorage()
        renderVoiceKeyboard()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    override fun onDestroy() {
        activityDestroyed = true
        selectionExecutor.shutdown()
        super.onDestroy()
    }

    private fun acceptOutput(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        selectionExecutor.execute {
            runCatching {
                documentAccess.requireAppendable(uri)
                documentAccess.metadata(uri)
            }.onSuccess {
                selectionStore.saveOutputUri(uri.toString())
                runOnUiThread {
                    if (activityDestroyed) return@runOnUiThread
                    renderStorage()
                }
            }.onFailure { error ->
                runOnUiThread {
                    if (activityDestroyed) return@runOnUiThread
                    Toast.makeText(
                        this,
                        error.message ?: getString(R.string.error_output_not_writable),
                        Toast.LENGTH_LONG,
                    ).show()
                    renderStorage()
                }
            }
        }
    }

    private fun showOutputDocumentOptions() {
        val actions = listOf(
            R.string.settings_output_create_new,
            R.string.settings_output_choose_existing,
            R.string.settings_output_do_not_export,
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_output_action_title)
            .setItems(actions.map(::getString).toTypedArray()) { _, index ->
                when (actions[index]) {
                    R.string.settings_output_create_new ->
                        outputCreator.launch(FileSelectionRules.DEFAULT_OUTPUT_FILE_NAME)
                    R.string.settings_output_choose_existing ->
                        outputPicker.launch(FileSelectionRules.outputMimeTypes)
                    R.string.settings_output_do_not_export -> {
                        selectionStore.clearOutputUri()
                        selectionStore.hideOutputTask()
                        renderStorage()
                    }
                }
            }
            .show()
    }

    private fun acceptFolder(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        selectionExecutor.execute {
            runCatching {
                folderScanner.requireReadable(uri)
                folderScanner.folderName(uri)
            }.onSuccess {
                selectionStore.saveFolderUri(uri.toString())
                runOnUiThread {
                    if (activityDestroyed) return@runOnUiThread
                    renderStorage()
                }
            }.onFailure { error ->
                runOnUiThread {
                    if (activityDestroyed) return@runOnUiThread
                    Toast.makeText(
                        this,
                        error.message ?: getString(R.string.error_audio_folder_not_readable),
                        Toast.LENGTH_LONG,
                    ).show()
                    renderStorage()
                }
            }
        }
    }

    private fun renderStorage() {
        renderOutput()
        renderFolder()
        renderModel()
    }

    private fun renderVoiceKeyboard() {
        val status = keyboardStatusProvider.current()
        keyboardStatusView.setText(
            when (status) {
                AndroidVoiceKeyboardStatus.DISABLED -> R.string.settings_voice_keyboard_disabled
                AndroidVoiceKeyboardStatus.ENABLED -> R.string.settings_voice_keyboard_enabled
                AndroidVoiceKeyboardStatus.SELECTED -> R.string.settings_voice_keyboard_selected
            },
        )
        keyboardActionView.setText(
            when (status) {
                AndroidVoiceKeyboardStatus.DISABLED -> R.string.settings_voice_keyboard_enable
                AndroidVoiceKeyboardStatus.ENABLED -> R.string.settings_voice_keyboard_choose
                AndroidVoiceKeyboardStatus.SELECTED -> R.string.settings_voice_keyboard_change
            },
        )
    }

    private fun renderModel() {
        modelDetail.text = when (val state = SpeechModelRepository.forActive(
            noBackupFilesDir.resolve("models"),
        ).inspectLightweight()) {
            is InstalledSpeechModelState.Ready -> getString(R.string.settings_model_active, state.descriptor.displayName)
            else -> getString(R.string.settings_model_select)
        }
    }

    private fun renderOutput() {
        val stored = selectionStore.loadOutputUri()?.let(Uri::parse)
        if (stored == null) {
            outputDetail.text = getString(R.string.output_not_selected)
            return
        }
        outputDetail.text = runCatching {
            getString(R.string.output_selected, documentAccess.metadata(stored).displayName)
        }.getOrElse {
            getString(R.string.output_selected, stored.lastPathSegment ?: getString(R.string.output_default_name))
        }
    }

    private fun renderFolder() {
        val stored = selectionStore.loadFolderUri()?.let(Uri::parse)
        if (stored == null) {
            folderDetail.text = getString(R.string.folder_not_selected)
            return
        }
        folderDetail.text = runCatching {
            getString(R.string.folder_selected, folderScanner.folderName(stored))
        }.getOrElse {
            getString(R.string.folder_selected, stored.lastPathSegment ?: getString(R.string.audio_folder_default_name))
        }
    }

    private fun showScheduledTimePicker() {
        TimePickerDialog(
            this,
            { _, hour, minute ->
                settings = settingsStore.setTime(hour, minute)
                ScheduledTranscriptionScheduler.sync(this, settings)
                renderSchedule()
            },
            settings.hour,
            settings.minute,
            true,
        ).show()
    }

    private fun renderSchedule() {
        val text = getString(
            R.string.settings_scheduled_transcription_time,
            settings.hour,
            settings.minute,
        )
        scheduledTime.text = text
        scheduledTimeDetail.text = text
    }

    private fun openExternalUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure { error ->
            if (error is ActivityNotFoundException) {
                Toast.makeText(this, R.string.settings_about_link_error, Toast.LENGTH_LONG).show()
            } else {
                throw error
            }
        }
    }

    private fun appVersionName(): String =
        runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: getString(R.string.unknown_value)
        }.getOrDefault(getString(R.string.unknown_value))

}
