package me.maxistar.voiceinbox

import me.maxistar.voiceinbox.core.*

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.UUID

class MainActivity : AppCompatActivity(), StartupProcessingDialogFragment.Listener {
    private val speechModel = SpeechModelCatalog.defaultModel
    private lateinit var importAudio: FloatingActionButton
    private lateinit var newTab: MaterialButton
    private lateinit var processedTab: MaterialButton
    private lateinit var allTab: MaterialButton
    private lateinit var taskFilters: MaterialButtonToggleGroup
    private lateinit var taskList: RecyclerView
    private lateinit var taskAdapter: TaskListAdapter
    private var taskListItemAnimator: RecyclerView.ItemAnimator? = null
    private var taskListAnimatorSuppressed = false
    private lateinit var taskActionRouter: AndroidTaskActionRouter
    private val taskStateHost: AndroidMainScreenStateHost by viewModels()

    private lateinit var selectionStore: DocumentSelectionStore
    private lateinit var selectionAccess: PersistedSelectionAccess
    private lateinit var documentAccess: DocumentAccess
    private lateinit var folderScanner: AudioFolderScanner
    private lateinit var catalog: SqlDelightAudioCatalogRepository
    private lateinit var modelReadiness: SpeechModelReadinessManager
    private lateinit var startupPolicyStore: StartupProcessingPolicyStore
    private lateinit var startupCoordinator: StartupProcessingCoordinator
    private lateinit var onboardingHintStore: AndroidOnboardingHintStore
    private lateinit var keyboardDiscoveryStore: AndroidVoiceKeyboardDiscoveryStore
    private lateinit var keyboardStatusProvider: AndroidVoiceKeyboardStatusProvider
    private lateinit var keyboardSystemGateway: AndroidVoiceKeyboardSystemGateway
    private val folderExecutor = Executors.newSingleThreadExecutor()
    private val importExecutor = Executors.newSingleThreadExecutor()

    private var modelReady = false
    private var modelSetupState = ModelSetupSnapshotState.REQUIRED
    private var outputUri: Uri? = null
    private var folderUri: Uri? = null
    private var outputAccessReady = false
    private var folderAccessReady = false
    private var outputDisplayName: String? = null
    private var folderDisplayName: String? = null
    private var outputAccessError: String? = null
    private var folderAccessError: String? = null
    private var transcriptionState = TranscriptionObservationState.UNKNOWN
    private var folderChecking = false
    private var folderScanQueued = false
    private var scanning = false
    private var pendingCount = 0
    private var lastCatalogWorkState: CatalogWorkRefreshKey? = null
    private var currentSessionTranscriptionWorkId: UUID? = null
    private var currentSessionObservedActiveTranscription = false
    private val transcriptionHandoff = AndroidTranscriptionHandoffCoordinator()
    private var modelMessage = ""
    private var modelDownloadAvailable = false
    private var modelDownloadProgress: Int? = null
    private var modelInstallCanCancel = false
    private var selectedDownloadModel = SpeechModelCatalog.defaultModel
    private var scanMessage: String? = null
    private var transcriptionFinished = false
    private var transcriptionPhase: String? = null
    private var transcriptionFilename: String? = null
    private var transcriptionEntryId: Long? = null
    private var transcriptionPreparationOwnerEntryId: Long? = null
    private var transcriptionIndeterminate = true
    private var transcriptionProgressValue = 0
    private var processedUs = -1L
    private var durationUs = -1L
    private var completedFiles = 0
    private var totalFiles = 0
    private var failedFiles = 0
    private var previewPlayer: MediaPlayer? = null
    private var previewEntryId: Long? = null
    private var previewState = PreviewPlaybackState.IDLE
    private var activityDestroyed = false
    private var scanGeneration = 0L
    private var outputValidationGeneration = 0L
    private var folderValidationGeneration = 0L
    private var catalogRefreshGeneration = 0L
    private var pendingStartupCatalogGeneration: Long? = null
    private var pendingFolderScanOrigin: FolderScanOrigin? = null
    private var pendingFolderSyncGeneration: Long? = null
    private var hasStarted = false
    private var ingestionActive = false
    private var currentEntries: List<AudioCatalogEntry> = emptyList()
    private var renderingFilter = false
    private var modelPresentationKnown = false
    private var outputPresentationKnown = false
    private var folderPresentationKnown = false
    private var catalogPresentationKnown = false
    private var refreshActionView: View? = null
    private val refreshIndicatorHandler = Handler(Looper.getMainLooper())
    private var refreshIndicatorActive = false
    private var refreshIndicatorStartedAt = 0L
    private var refreshIndicatorAnimator: android.animation.ObjectAnimator? = null
    private val startRefreshIndicator = Runnable(::startRefreshIndicatorAnimation)
    private val stopRefreshIndicator = Runnable(::stopRefreshIndicatorAnimation)
    private val queuedImportUris = mutableListOf<Uri>()
    private var onboardingHintLifecycle = AndroidOnboardingHintLifecycle.DISMISSED
    private var keyboardDiscoveryLifecycle = AndroidVoiceKeyboardDiscoveryLifecycle.SUPPRESSED
    private var keyboardStatus = AndroidVoiceKeyboardStatus.DISABLED
    private var keyboardStatusKnown = false
    private var pendingModelPackageUri: Uri? = null
    private var pendingModelPackageCatalogId: String? = null

    private val outputPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) acceptOutput(uri)
    }

    private val outputCreator = registerForActivityResult(
        ActivityResultContracts.CreateDocument(FileSelectionRules.CREATED_OUTPUT_MIME_TYPE),
    ) { uri ->
        if (uri != null) acceptOutput(uri)
    }

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) acceptFolder(uri)
    }

    private val importPicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) ingestAudioUris(uris)
    }

    private val modelFolderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) acceptModelFolder(uri)
    }

    private val microphonePermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        Toast.makeText(
            this,
            if (granted) R.string.voice_keyboard_permission_granted else R.string.voice_keyboard_permission_denied,
            Toast.LENGTH_LONG,
        ).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        bindViews()

        selectionStore = DocumentSelectionStore(
            getSharedPreferences(DocumentSelectionStore.PREFERENCES_NAME, MODE_PRIVATE),
        )
        selectionAccess = PersistedSelectionAccess(contentResolver)
        documentAccess = DocumentAccess(contentResolver)
        folderScanner = AudioFolderScanner(contentResolver)
        catalog = AndroidSqlDelightAudioCatalogFactory(this).create()
        modelReadiness = getSharedModelReadiness {
            SpeechModelRepository.forActive(noBackupFilesDir.resolve("models"))
        }
        startupPolicyStore = StartupProcessingPolicyStore(
            getSharedPreferences(StartupProcessingPolicyStore.PREFERENCES_NAME, MODE_PRIVATE),
        )
        onboardingHintStore = AndroidOnboardingHintStore(
            getSharedPreferences(AndroidOnboardingHintStore.PREFERENCES_NAME, MODE_PRIVATE),
        )
        onboardingHintLifecycle = onboardingHintStore.load()
        keyboardDiscoveryStore = AndroidVoiceKeyboardDiscoveryStore(
            getSharedPreferences(AndroidVoiceKeyboardDiscoveryStore.PREFERENCES_NAME, MODE_PRIVATE),
        )
        keyboardDiscoveryLifecycle = keyboardDiscoveryStore.loadOrInitialize(onboardingHintLifecycle)
        keyboardStatusProvider = AndroidVoiceKeyboardStatusProvider(this)
        keyboardSystemGateway = AndroidVoiceKeyboardSystemGateway(this)
        refreshKeyboardStatus(publish = false)
        startupCoordinator = StartupProcessingCoordinator.restore(
            savedInstanceState?.getString(STATE_STARTUP_PROCESSING_STAGE),
        )
        pendingModelPackageUri = savedInstanceState?.getString(STATE_PENDING_MODEL_URI)?.let(Uri::parse)
        pendingModelPackageCatalogId = savedInstanceState?.getString(STATE_PENDING_MODEL_CATALOG_ID)
        restoreRetainedPresentation()
        taskActionRouter = AndroidTaskActionRouter(
            currentState = { taskStateHost.state.value },
            gateway = AndroidTaskActionGateway(::performTaskAction),
        )
        bootstrapSelectionIdentities()
        ScheduledTranscriptionScheduler.sync(
            this,
            ScheduledTranscriptionSettingsStore(
                getSharedPreferences(ScheduledTranscriptionSettingsStore.PREFERENCES_NAME, MODE_PRIVATE),
            ).load(),
        )

        importAudio.setOnClickListener {
            if (!ingestionActive) importPicker.launch(arrayOf("audio/*", "application/ogg"))
        }
        taskFilters.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || renderingFilter) return@addOnButtonCheckedListener
            val filter = when (checkedId) {
                R.id.processedTab -> TaskListFilter.PROCESSED
                R.id.allTab -> TaskListFilter.ALL
                else -> TaskListFilter.NEW
            }
            taskStateHost.selectFilter(filter)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                taskStateHost.state.collect(::renderTaskState)
            }
        }

        observeModelInstallation()
        observeTranscription()
        restoreSelections(scanFolderOnSuccess = true)
        refreshCatalog()
        cleanupImportedAudio()
        handleShareIntent(intent)
        refreshModel()
        restorePendingModelConfirmation()
        if (intent.getBooleanExtra(EXTRA_OPEN_MODEL_FOLDER_PICKER, false)) {
            intent.removeExtra(EXTRA_OPEN_MODEL_FOLDER_PICKER)
            modelFolderPicker.launch(null)
        }
        requestMicrophonePermissionIfNeeded(intent)
    }

    override fun onStart() {
        super.onStart()
        if (hasStarted) {
            restoreSelections(scanFolderOnSuccess = false)
            refreshCatalog()
        } else {
            hasStarted = true
        }
    }

    override fun onResume() {
        super.onResume()
        if (::keyboardStatusProvider.isInitialized) refreshKeyboardStatus(publish = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
        requestMicrophonePermissionIfNeeded(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_STARTUP_PROCESSING_STAGE, startupCoordinator.savedStage())
        outState.putString(STATE_PENDING_MODEL_URI, pendingModelPackageUri?.toString())
        outState.putString(STATE_PENDING_MODEL_CATALOG_ID, pendingModelPackageCatalogId)
        super.onSaveInstanceState(outState)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_options, menu)
        refreshIndicatorHandler.removeCallbacks(startRefreshIndicator)
        refreshIndicatorHandler.removeCallbacks(stopRefreshIndicator)
        refreshIndicatorAnimator?.cancel()
        refreshIndicatorAnimator = null
        refreshIndicatorActive = false
        refreshActionView = menu.findItem(R.id.menuRefreshFolder)?.actionView
        refreshActionView?.setOnClickListener { dispatchManualFolderRefresh() }
        updateMenu(menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        updateMenu(menu)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.menuRefreshFolder -> {
                dispatchManualFolderRefresh()
                true
            }
            R.id.menuSettings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.menuDocumentation -> {
                openDocumentation()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    override fun onDestroy() {
        activityDestroyed = true
        refreshIndicatorHandler.removeCallbacksAndMessages(null)
        refreshIndicatorAnimator?.cancel()
        refreshIndicatorAnimator = null
        stopPreviewPlayback(render = false)
        folderExecutor.shutdown()
        importExecutor.shutdown()
        super.onDestroy()
    }

    private fun requestMicrophonePermissionIfNeeded(intent: Intent) {
        if (!intent.getBooleanExtra(VoiceKeyboardSetup.EXTRA_REQUEST_MICROPHONE_PERMISSION, false)) return
        intent.removeExtra(VoiceKeyboardSetup.EXTRA_REQUEST_MICROPHONE_PERMISSION)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            microphonePermissionRequest.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun bindViews() {
        importAudio = findViewById(R.id.importAudio)
        newTab = findViewById(R.id.newTab)
        processedTab = findViewById(R.id.processedTab)
        allTab = findViewById(R.id.allTab)
        taskFilters = findViewById(R.id.taskFilters)
        taskList = findViewById(R.id.taskList)
        taskAdapter = TaskListAdapter(
            ::handleTaskAction,
            ::dismissOnboardingHint,
            ::dismissKeyboardDiscovery,
        )
        taskList.layoutManager = LinearLayoutManager(this)
        taskList.adapter = taskAdapter
        (taskList.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        taskListItemAnimator = taskList.itemAnimator
    }

    private fun restoreRetainedPresentation() {
        val retained = taskStateHost.currentInput
        modelSetupState = retained.model.state
        modelReady = retained.model.state == ModelSetupSnapshotState.READY
        modelMessage = retained.model.detail ?: modelMessage
        modelDownloadAvailable = retained.model.downloadAvailable
        modelDownloadProgress = retained.model.progressPercent
        modelInstallCanCancel = retained.model.canCancel
        currentEntries = retained.entries
        modelPresentationKnown = retained.hydration.modelKnown
        outputPresentationKnown = retained.hydration.outputKnown
        folderPresentationKnown = retained.hydration.folderKnown
        catalogPresentationKnown = retained.hydration.catalogKnown
    }

    private fun bootstrapSelectionIdentities() {
        setStoredOutputIdentity(selectionStore.loadOutputUri()?.let(Uri::parse))
        setStoredFolderIdentity(selectionStore.loadFolderUri()?.let(Uri::parse))
    }

    private fun setStoredOutputIdentity(stored: Uri?) {
        outputUri = stored
        outputAccessReady = false
        outputDisplayName = null
        outputPresentationKnown = stored == null
        startupCoordinator.setOutputReady(false)
        publishTaskState()
    }

    private fun setStoredFolderIdentity(stored: Uri?) {
        folderUri = stored
        folderAccessReady = false
        folderDisplayName = null
        folderPresentationKnown = stored == null
        if (stored == null) {
            pendingFolderSyncGeneration = null
            taskStateHost.folderSyncCoordinator.reset()
        }
        startupCoordinator.setFolderReady(false)
        publishTaskState()
    }

    private fun restoreSelections(scanFolderOnSuccess: Boolean) {
        val storedOutput = selectionStore.loadOutputUri()?.let(Uri::parse)
        if (storedOutput != outputUri) setStoredOutputIdentity(storedOutput)
        val outputGeneration = ++outputValidationGeneration
        if (storedOutput == null) {
            outputAccessError = null
            outputAccessReady = false
            outputPresentationKnown = true
            startupCoordinator.setOutputReady(false)
            publishTaskState()
        } else {
            outputAccessReady = false
            outputPresentationKnown = false
            startupCoordinator.setOutputReady(false)
            publishTaskState()
            folderExecutor.execute {
                val result = selectionAccess.validate(
                    uri = storedOutput,
                    requiredAccess = RequiredDocumentAccess.WRITE,
                ) {
                    documentAccess.requireAppendable(storedOutput)
                    documentAccess.metadata(storedOutput)
                }
                runOnUiThread {
                    if (
                        activityDestroyed ||
                        outputGeneration != outputValidationGeneration ||
                        outputUri != storedOutput
                    ) return@runOnUiThread
                    outputPresentationKnown = true
                    when (result.state) {
                        PersistedSelectionAccessState.VALID -> {
                            outputAccessReady = true
                            outputAccessError = null
                            startupCoordinator.setOutputReady(true)
                            updateOutputSummary(result.value?.displayName)
                        }
                        PersistedSelectionAccessState.TEMPORARILY_UNAVAILABLE -> {
                            outputAccessReady = false
                            outputAccessError = result.error?.message
                                ?: getString(R.string.error_output_temporarily_unavailable)
                            startupCoordinator.setOutputReady(false)
                            outputDisplayName = null
                        }
                        PersistedSelectionAccessState.PERMISSION_REVOKED -> {
                            selectionStore.clearOutputUri()
                            outputUri = null
                            outputAccessReady = false
                            outputDisplayName = null
                            outputAccessError = result.error?.message
                                ?: getString(R.string.error_output_access_revoked)
                            startupCoordinator.setOutputReady(false)
                        }
                    }
                    publishTaskState()
                    updateControls()
                    evaluateStartupProcessing()
                }
            }
        }

        val storedFolder = selectionStore.loadFolderUri()?.let(Uri::parse)
        if (storedFolder != folderUri) setStoredFolderIdentity(storedFolder)
        val folderGeneration = ++folderValidationGeneration
        if (storedFolder == null) {
            folderAccessError = null
            folderAccessReady = false
            folderChecking = false
            folderPresentationKnown = true
            pendingFolderSyncGeneration = null
            taskStateHost.folderSyncCoordinator.reset()
            startupCoordinator.setFolderReady(false)
            publishTaskState()
            refreshCatalog()
            return
        }
        val folderSyncGeneration = taskStateHost.folderSyncCoordinator.begin()
        pendingFolderSyncGeneration = null
        folderAccessReady = false
        folderChecking = true
        folderPresentationKnown = false
        startupCoordinator.setFolderReady(false)
        folderDisplayName = null
        publishTaskState()
        updateControls()
        folderExecutor.execute {
            val result = selectionAccess.validate(
                uri = storedFolder,
                requiredAccess = RequiredDocumentAccess.READ,
            ) {
                folderScanner.requireReadable(storedFolder)
                folderScanner.folderName(storedFolder)
            }
            runOnUiThread {
                if (
                    activityDestroyed ||
                    folderGeneration != folderValidationGeneration ||
                    folderUri != storedFolder
                ) return@runOnUiThread
                if (!taskStateHost.folderSyncCoordinator.isCurrent(folderSyncGeneration)) {
                    return@runOnUiThread
                }
                folderChecking = false
                folderPresentationKnown = true
                when (result.state) {
                    PersistedSelectionAccessState.VALID -> {
                        folderAccessReady = true
                        folderAccessError = null
                        startupCoordinator.setFolderReady(true)
                        updateFolderSummary(result.value)
                        if (scanFolderOnSuccess) {
                            pendingFolderScanOrigin = FolderScanOrigin.STARTUP
                            pendingFolderSyncGeneration = folderSyncGeneration
                            taskStateHost.folderSyncCoordinator.transition(
                                folderSyncGeneration,
                                AndroidFolderSyncPhase.QUEUED,
                            )
                        } else {
                            taskStateHost.folderSyncCoordinator.complete(folderSyncGeneration)
                        }
                    }
                    PersistedSelectionAccessState.TEMPORARILY_UNAVAILABLE -> {
                            folderAccessReady = false
                            folderAccessError = result.error?.message
                                ?: getString(R.string.error_folder_temporarily_unavailable)
                        startupCoordinator.setFolderReady(false)
                        folderDisplayName = null
                        taskStateHost.folderSyncCoordinator.fail(folderSyncGeneration)
                    }
                    PersistedSelectionAccessState.PERMISSION_REVOKED -> {
                        selectionStore.clearFolderUri()
                        folderUri = null
                        folderAccessReady = false
                        folderDisplayName = null
                            folderAccessError = result.error?.message
                                ?: getString(R.string.error_folder_access_revoked)
                        pendingFolderSyncGeneration = null
                        startupCoordinator.setFolderReady(false)
                        taskStateHost.folderSyncCoordinator.fail(folderSyncGeneration)
                    }
                }
                publishTaskState()
                updateControls()
                if (result.state != PersistedSelectionAccessState.VALID || !scanFolderOnSuccess) {
                    refreshCatalog()
                }
                maybeStartPendingFolderScan()
                evaluateStartupProcessing()
            }
        }
    }

    private fun maybeStartPendingFolderScan() {
        val origin = pendingFolderScanOrigin ?: return
        if (
            !folderAccessReady ||
            transcriptionState == TranscriptionObservationState.UNKNOWN
        ) return
        if (transcriptionActive()) {
            pendingFolderSyncGeneration?.let(taskStateHost.folderSyncCoordinator::complete)
            pendingFolderSyncGeneration = null
            publishTaskState()
            return
        }
        val folderSyncGeneration = pendingFolderSyncGeneration
        pendingFolderScanOrigin = null
        pendingFolderSyncGeneration = null
        scanFolder(origin, folderSyncGeneration)
    }

    private fun acceptOutput(uri: Uri) {
        val validationGeneration = ++outputValidationGeneration
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        folderExecutor.execute {
            runCatching {
                documentAccess.requireAppendable(uri)
                documentAccess.metadata(uri)
            }.onSuccess { metadata ->
                runOnUiThread {
                    if (
                        activityDestroyed ||
                        validationGeneration != outputValidationGeneration
                    ) return@runOnUiThread
                    selectionStore.saveOutputUri(uri.toString())
                    outputUri = uri
                    outputAccessReady = true
                    outputAccessError = null
                    outputPresentationKnown = true
                    startupCoordinator.setOutputReady(true)
                    updateOutputSummary(metadata.displayName)
                    outputAccessError = null
                    publishTaskState()
                    updateControls()
                    evaluateStartupProcessing()
                }
            }.onFailure {
                runOnUiThread {
                    if (
                        activityDestroyed ||
                        validationGeneration != outputValidationGeneration
                    ) return@runOnUiThread
                    outputAccessError = it.message ?: getString(R.string.error_output_not_writable)
                    outputPresentationKnown = true
                    publishTaskState()
                    updateControls()
                    evaluateStartupProcessing()
                }
            }
        }
    }

    private fun acceptFolder(uri: Uri) {
        val validationGeneration = ++folderValidationGeneration
        val folderSyncGeneration = taskStateHost.folderSyncCoordinator.begin()
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        folderExecutor.execute {
            runOnUiThread {
                if (
                    activityDestroyed ||
                    validationGeneration != folderValidationGeneration
                ) return@runOnUiThread
                folderChecking = true
                folderPresentationKnown = false
                publishTaskState()
                updateControls()
            }
            runCatching {
                folderScanner.requireReadable(uri)
                folderScanner.folderName(uri)
            }.onSuccess { name ->
                runOnUiThread {
                    if (
                        activityDestroyed ||
                        validationGeneration != folderValidationGeneration
                    ) return@runOnUiThread
                    selectionStore.saveFolderUri(uri.toString())
                    folderChecking = false
                    folderUri = uri
                    folderAccessReady = true
                    folderPresentationKnown = true
                    folderAccessError = null
                    startupCoordinator.setFolderReady(true)
                    updateFolderSummary(name)
                    folderAccessError = null
                    publishTaskState()
                    updateControls()
                    pendingFolderScanOrigin = FolderScanOrigin.USER
                    pendingFolderSyncGeneration = folderSyncGeneration
                    taskStateHost.folderSyncCoordinator.transition(
                        folderSyncGeneration,
                        AndroidFolderSyncPhase.QUEUED,
                    )
                    maybeStartPendingFolderScan()
                }
            }.onFailure {
                runOnUiThread {
                    if (
                        activityDestroyed ||
                        validationGeneration != folderValidationGeneration
                    ) return@runOnUiThread
                    folderChecking = false
                    folderPresentationKnown = true
                    folderAccessError = it.message ?: getString(R.string.error_audio_folder_not_readable)
                    taskStateHost.folderSyncCoordinator.fail(folderSyncGeneration)
                    publishTaskState()
                    updateControls()
                    evaluateStartupProcessing()
                }
            }
        }
    }

    private fun acceptModelFolder(uri: Uri) {
        val alreadyPersisted = contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        val permission = runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        if (permission.isFailure) {
            modelPresentationKnown = true
            modelReady = false
            modelSetupState = ModelSetupSnapshotState.INVALID
            setModelUi(
                getString(R.string.error_model_folder_access),
                canDownload = true,
            )
            updateControls()
            return
        }
        if (!alreadyPersisted) SpeechModelImportPermission.recordOwned(this, uri)
        importExecutor.execute {
            runCatching {
                SpeechModelDirectoryReader(contentResolver).inspectPackage(uri)
            }.onSuccess { source ->
                runOnUiThread {
                    if (activityDestroyed) return@runOnUiThread
                    pendingModelPackageUri = uri
                    pendingModelPackageCatalogId = source.descriptor.catalogId
                    showModelPackageConfirmation(source.descriptor)
                }
            }.onFailure { error ->
                SpeechModelImportPermission.releaseOwnedIfUnused(this)
                runOnUiThread {
                    if (activityDestroyed) return@runOnUiThread
                    modelPresentationKnown = true
                    modelReady = false
                    modelSetupState = ModelSetupSnapshotState.INVALID
                    setModelUi(
                        error.message ?: getString(R.string.error_model_folder_not_readable),
                        canDownload = true,
                    )
                    updateControls()
                }
            }
        }
    }

    private fun restorePendingModelConfirmation() {
        val descriptor = pendingModelPackageCatalogId?.let { catalogId ->
            SpeechModelCatalog.models.singleOrNull { it.catalogId == catalogId }
        } ?: return
        showModelPackageConfirmation(descriptor)
    }

    private fun showModelPackageConfirmation(descriptor: SpeechModelDescriptor) {
        val uri = pendingModelPackageUri ?: return
        val size = SpeechModelRepository.formatBytes(descriptor.approximateDownloadBytes)
        val maturity = descriptor.maturity.name.lowercase().replaceFirstChar(Char::uppercase)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.confirm_install_model, descriptor.displayName))
            .setMessage(getString(R.string.confirm_install_model_detail, maturity, descriptor.languages.summary, size))
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                pendingModelPackageUri = null
                pendingModelPackageCatalogId = null
                SpeechModelImportPermission.releaseOwnedIfUnused(this)
            }
            .setPositiveButton(R.string.model_install, null)
            .create()
        dialog.setOnShowListener {
            val confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            confirm.isEnabled = !transcriptionActive()
            confirm.setOnClickListener {
                if (transcriptionActive()) return@setOnClickListener
                pendingModelPackageUri = null
                pendingModelPackageCatalogId = null
                modelPresentationKnown = true
                modelReady = false
                modelSetupState = ModelSetupSnapshotState.INSTALLING
                setModelUi(getString(R.string.installing_model_named, descriptor.displayName), canDownload = false)
                updateControls()
                SpeechModelImportWorker.enqueue(this, uri, descriptor)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun scanFolder(
        origin: FolderScanOrigin = FolderScanOrigin.USER,
        existingSyncGeneration: Long? = null,
    ) {
        val folder = folderUri ?: return
        if (
            !folderAccessReady ||
            folderChecking ||
            folderScanQueued ||
            scanning ||
            (existingSyncGeneration == null && taskStateHost.folderSyncCoordinator.state.active) ||
            transcriptionState == TranscriptionObservationState.UNKNOWN ||
            transcriptionActive()
        ) return
        val folderSyncGeneration = existingSyncGeneration
            ?.takeIf(taskStateHost.folderSyncCoordinator::isCurrent)
            ?: taskStateHost.folderSyncCoordinator.begin(AndroidFolderSyncPhase.QUEUED)
        val generation = ++scanGeneration
        val startupGeneration = generation.takeIf {
            origin == FolderScanOrigin.STARTUP && startupCoordinator.beginStartupScan(generation)
        }
        folderScanQueued = true
        transcriptionFinished = false
        transcriptionPhase = null
        transcriptionFilename = null
        transcriptionProgressValue = 0
        processedUs = -1L
        durationUs = -1L
        completedFiles = 0
        totalFiles = 0
        failedFiles = 0
        folderAccessError = null
        taskStateHost.folderSyncCoordinator.transition(
            folderSyncGeneration,
            AndroidFolderSyncPhase.QUEUED,
        )
        publishTaskState()
        updateControls()
        folderExecutor.execute {
            runOnUiThread {
                if (activityDestroyed) return@runOnUiThread
                folderScanQueued = false
                scanning = true
                scanMessage = getString(R.string.folder_scanning)
                taskStateHost.folderSyncCoordinator.transition(
                    folderSyncGeneration,
                    AndroidFolderSyncPhase.SCANNING,
                )
                publishTaskState()
                updateControls()
            }
            runCatching {
                val files = folderScanner.scan(folder)
                catalog.reconcile(folder.toString(), files)
                files.size
            }.onSuccess { count ->
                runOnUiThread {
                    if (activityDestroyed) return@runOnUiThread
                    folderScanQueued = false
                    scanning = false
                    folderAccessError = null
                    scanMessage = getString(R.string.folder_scan_complete, count)
                    taskStateHost.folderSyncCoordinator.transition(
                        folderSyncGeneration,
                        AndroidFolderSyncPhase.REFRESHING_CATALOG,
                    )
                    refreshCatalog(startupGeneration, folderSyncGeneration)
                }
            }.onFailure { error ->
                runOnUiThread {
                    if (activityDestroyed) return@runOnUiThread
                    folderScanQueued = false
                    scanning = false
                    folderAccessError = error.message ?: getString(R.string.error_folder_scan_failed)
                    folderPresentationKnown = true
                    taskStateHost.folderSyncCoordinator.fail(folderSyncGeneration)
                    startupGeneration?.let(startupCoordinator::onStartupScanFailed)
                    evaluateStartupProcessing()
                    updateControls()
                    publishTaskState()
                }
            }
        }
    }

    private fun refreshCatalog(
        startupGeneration: Long? = null,
        folderSyncGeneration: Long? = null,
    ) {
        if (activityDestroyed) return
        if (startupGeneration != null) {
            pendingStartupCatalogGeneration = startupGeneration
        }
        val token = CatalogRefreshToken(
            generation = ++catalogRefreshGeneration,
            sourceScope = activeSourceScope(),
        )
        val startupGenerationForRequest = pendingStartupCatalogGeneration
        folderExecutor.execute {
            runCatching {
                val newEntries = catalog.newEntries(token.sourceScope)
                val processedEntries = catalog.processedEntries(token.sourceScope)
                Triple(
                    newEntries,
                    processedEntries,
                    (newEntries + processedEntries).distinctBy(AudioCatalogEntry::id),
                )
            }.onSuccess { (newEntries, processedEntries, entries) ->
                runOnUiThread {
                    if (activityDestroyed) return@runOnUiThread
                    if (
                        !CatalogRefreshPolicy.isCurrent(
                            request = token,
                            currentGeneration = catalogRefreshGeneration,
                            currentSourceScope = activeSourceScope(),
                        )
                    ) {
                        folderSyncGeneration?.let(taskStateHost.folderSyncCoordinator::complete)
                        publishTaskState()
                        return@runOnUiThread
                    }
                    pendingCount = newEntries.count { it.state == AudioFileState.PENDING }
                    val catalogFailedCount = processedEntries.count { it.state == AudioFileState.FAILED }
                    currentEntries = entries
                    catalogPresentationKnown = true
                    folderSyncGeneration?.let(taskStateHost.folderSyncCoordinator::complete)
                    publishTaskState()
                    updateControls()
                    if (startupGenerationForRequest != null) {
                        if (pendingStartupCatalogGeneration == startupGenerationForRequest) {
                            pendingStartupCatalogGeneration = null
                        }
                        startupCoordinator.onStartupCatalogReady(
                            startupGenerationForRequest,
                            pendingCount,
                            catalogFailedCount,
                        )
                    } else {
                        startupCoordinator.onCatalogRefreshed(pendingCount, catalogFailedCount)
                    }
                    evaluateStartupProcessing()
                }
            }.onFailure { error ->
                runOnUiThread {
                    if (activityDestroyed) return@runOnUiThread
                    if (folderSyncGeneration != null &&
                        taskStateHost.folderSyncCoordinator.fail(folderSyncGeneration)
                    ) {
                        folderAccessError = error.message ?: getString(R.string.error_audio_catalog_refresh_failed)
                        folderPresentationKnown = true
                        startupGeneration?.let(startupCoordinator::onStartupScanFailed)
                        publishTaskState()
                        updateControls()
                    }
                }
            }
        }
    }

    private fun retryEntry(entry: AudioCatalogEntry) {
        stopPreviewPlayback(render = true)
        val request = TranscriptionWorker.requestRetry(folderUri, exportOutputUri(), entry.id)
        beginTranscriptionHandoff(request.id, entry.id)
        TranscriptionWorker.enqueue(this, request)
    }

    private fun showTranscriptText(entry: AudioCatalogEntry) {
        val review = AndroidTranscriptReview.from(entry) ?: return
        val content = layoutInflater.inflate(R.layout.dialog_transcript, null)
        content.findViewById<TextView>(R.id.transcriptText).text = review.text
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(review.filename)
            .setView(content)
            .setNegativeButton(R.string.transcript_close, null)
            .setPositiveButton(R.string.transcript_share, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                startActivity(AndroidTranscriptShareIntent.chooser(review))
            }
        }
        dialog.show()
    }

    private fun refreshModel() {
        modelReadiness.refresh { state ->
            runOnUiThread {
                if (activityDestroyed) return@runOnUiThread
                applyModelState(state)
                updateControls()
            }
        }
    }

    private fun applyModelState(state: SpeechModelReadinessState) {
        when (state) {
            SpeechModelReadinessState.Checking -> {
                modelReady = false
                modelPresentationKnown = false
                setModelUi(getString(R.string.model_checking), canDownload = false)
            }
            is SpeechModelReadinessState.Ready -> {
                modelPresentationKnown = true
                modelReady = true
                modelSetupState = ModelSetupSnapshotState.READY
                setModelUi(getString(R.string.model_ready), canDownload = false)
            }
            SpeechModelReadinessState.Missing -> {
                modelPresentationKnown = true
                modelReady = false
                modelSetupState = ModelSetupSnapshotState.REQUIRED
                setModelUi(getString(R.string.model_missing), canDownload = true)
            }
            is SpeechModelReadinessState.Invalid -> {
                modelPresentationKnown = true
                modelReady = false
                modelSetupState = ModelSetupSnapshotState.INVALID
                setModelUi(getString(R.string.model_invalid, state.reason), canDownload = true)
            }
            is SpeechModelReadinessState.Failed -> {
                modelPresentationKnown = true
                modelReady = false
                modelSetupState = ModelSetupSnapshotState.INVALID
                setModelUi(state.message, canDownload = true)
            }
        }
        startupCoordinator.setModelReady(modelReady)
        evaluateStartupProcessing()
    }

    private fun setModelUi(message: String, canDownload: Boolean) {
        modelMessage = message
        modelDownloadAvailable = canDownload
        modelDownloadProgress = null
        modelInstallCanCancel = false
        publishTaskState()
    }

    private fun observeModelInstallation() {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(SpeechModelInstallationWork.UNIQUE_WORK_NAME)
            .observe(this) { infos ->
                val info = infos.firstOrNull { it.state in ACTIVE_WORK_STATES } ?: infos.lastOrNull()
                if (info == null) {
                    SpeechModelImportPermission.releaseOwnedIfUnused(this)
                    return@observe
                }
                when (info.state) {
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.BLOCKED,
                    WorkInfo.State.RUNNING,
                    -> {
                        modelPresentationKnown = true
                        modelReady = false
                        modelSetupState = ModelSetupSnapshotState.INSTALLING
                        startupCoordinator.setModelReady(false)
                        val bytes = info.progress.getLong(SpeechModelInstallationWork.KEY_BYTES_DOWNLOADED, 0)
                        val total = info.progress.getLong(
                            SpeechModelInstallationWork.KEY_TOTAL_BYTES,
                            speechModel.approximateDownloadBytes,
                        )
                        modelMessage =
                            info.progress.getString(SpeechModelInstallationWork.KEY_MESSAGE)
                                ?: getString(R.string.model_installing)
                        modelDownloadAvailable = false
                        modelInstallCanCancel = info.tags.any { tag ->
                            tag.endsWith(SpeechModelDownloadWorker::class.java.simpleName)
                        }
                        modelDownloadProgress = ((bytes * 100) / total.coerceAtLeast(1)).toInt()
                        publishTaskState()
                        updateControls()
                        evaluateStartupProcessing()
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        SpeechModelImportPermission.releaseOwnedIfUnused(this)
                        if (shouldHandleModelInstallSuccess(info.id.toString())) {
                            modelReadiness.invalidate()
                            SpeechModelWarmup.invalidate()
                        }
                        refreshModel()
                    }
                    WorkInfo.State.FAILED -> {
                        SpeechModelImportPermission.releaseOwnedIfUnused(this)
                        modelPresentationKnown = true
                        modelReady = false
                        modelSetupState = ModelSetupSnapshotState.INVALID
                        startupCoordinator.setModelReady(false)
                        setModelUi(
                            info.outputData.getString(SpeechModelInstallationWork.KEY_ERROR)
                                ?: getString(R.string.model_installation_failed),
                            canDownload = true,
                        )
                        updateControls()
                        evaluateStartupProcessing()
                    }
                    WorkInfo.State.CANCELLED -> {
                        SpeechModelImportPermission.releaseOwnedIfUnused(this)
                        modelReadiness.invalidate()
                        refreshModel()
                    }
                }
            }
    }

    private fun observeTranscription() {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(TranscriptionWorker.UNIQUE_WORK_NAME)
            .observe(this) { infos ->
                transcriptionHandoff.reconcile(infos.map { info ->
                    AndroidTranscriptionWorkObservation(
                        workId = info.id.toString(),
                        active = info.state in ACTIVE_WORK_STATES,
                    )
                })
                transcriptionState = classifyTranscriptionState(infos)
                startupCoordinator.setTranscriptionState(
                    known = true,
                    active = transcriptionActive(),
                )
                maybeStartPendingFolderScan()
                evaluateStartupProcessing()
                if (infos.isEmpty()) {
                    transcriptionFinished = false
                    publishTaskState()
                    updateControls()
                    return@observe
                }
                val info = infos.firstOrNull {
                    it.state in ACTIVE_WORK_STATES
                } ?: if (transcriptionState == TranscriptionObservationState.FINISHED) {
                    infos.firstOrNull { it.id == currentSessionTranscriptionWorkId && it.state.isFinished }
                } else {
                    null
                } ?: run {
                    if (transcriptionHandoff.handoff == null) {
                        clearTranscriptionProgress()
                    }
                    publishTaskState()
                    updateControls()
                    return@observe
                }
                if (info.state in ACTIVE_WORK_STATES) {
                    currentSessionTranscriptionWorkId = info.id
                    currentSessionObservedActiveTranscription = true
                }
                if (transcriptionActive() && previewState != PreviewPlaybackState.IDLE) {
                    stopPreviewPlayback(render = true)
                }
                transcriptionFinished = transcriptionState == TranscriptionObservationState.FINISHED
                val data = if (info.state.isFinished) info.outputData else info.progress
                transcriptionFilename = data.getString(TranscriptionWorker.KEY_FILENAME)
                transcriptionEntryId = data
                    .getLong(TranscriptionWorker.KEY_ACTIVE_ENTRY_ID, TranscriptionWorker.NO_ENTRY_ID)
                    .takeIf { it != TranscriptionWorker.NO_ENTRY_ID }
                val workActive = info.state in ACTIVE_WORK_STATES
                val requestedEntryId = transcriptionHandoff.requestedEntryId(info.tags)
                transcriptionPreparationOwnerEntryId = transcriptionHandoff.preparationOwnerEntryId(
                    observedActive = workActive,
                    activeEntryId = transcriptionEntryId,
                    requestedEntryId = requestedEntryId,
                )
                transcriptionPhase = transcriptionHandoff.phase(
                    observedPhase = data.getString(TranscriptionWorker.KEY_PHASE),
                    observedActive = workActive,
                    activeEntryId = transcriptionEntryId,
                ) ?: when (info.state) {
                    WorkInfo.State.SUCCEEDED -> getString(R.string.transcription_completed)
                    WorkInfo.State.FAILED ->
                        data.getString(TranscriptionWorker.KEY_ERROR) ?: getString(R.string.task_failed)
                    else -> info.state.name
                }
                transcriptionIndeterminate =
                    transcriptionActive() && data.getBoolean(TranscriptionWorker.KEY_INDETERMINATE, true)
                transcriptionProgressValue = data.getInt(TranscriptionWorker.KEY_PROGRESS, 0)
                processedUs = data.getLong(TranscriptionWorker.KEY_PROCESSED_US, -1L)
                durationUs = data.getLong(TranscriptionWorker.KEY_DURATION_US, -1L)
                completedFiles = data.getInt(TranscriptionWorker.KEY_COMPLETED_FILES, 0)
                totalFiles = data.getInt(TranscriptionWorker.KEY_TOTAL_FILES, 0)
                failedFiles = data.getInt(TranscriptionWorker.KEY_FAILED_FILES, 0)
                if (info.state == WorkInfo.State.FAILED && shouldRefreshModelAfterFailure(info.id.toString())) {
                    modelReadiness.invalidate()
                    refreshModel()
                }
                if (transcriptionActive() || transcriptionFinished) {
                    scanMessage = null
                }
                publishTaskState()
                val catalogState = CatalogWorkRefreshKey(
                    workId = info.id.toString(),
                    workState = info.state.name,
                    filename = transcriptionFilename,
                    completedFiles = completedFiles,
                    failedFiles = failedFiles,
                )
                if (CatalogRefreshPolicy.shouldRefreshForWork(lastCatalogWorkState, catalogState)) {
                    lastCatalogWorkState = catalogState
                    refreshCatalog()
                } else {
                    updateControls()
                }
            }
    }

    private fun selectionControls(): CatalogControlState =
        TranscriptionUiRules.catalogControls(
            modelInstallationState = if (modelSetupState == ModelSetupSnapshotState.READY) {
                SpeechModelInstallationState.INSTALLED
            } else {
                SpeechModelInstallationState.NOT_INSTALLED
            },
            outputSelected = true,
            folderSelected = folderAccessReady,
            pendingCount = pendingCount,
            transcriptionState = transcriptionState,
            scanning = folderBusy(),
            audioInputAvailable = folderUri?.let { folderAccessReady } ?: currentEntries.any {
                it.folderUri == AndroidAudioImportConstants.SOURCE_ID
            },
        )

    private fun folderBusy(): Boolean =
        folderChecking || folderScanQueued || scanning || taskStateHost.folderSyncCoordinator.state.active

    private fun launchOutputPickerIfEnabled() {
        if (selectionControls().outputEnabled) {
            outputPicker.launch(FileSelectionRules.outputMimeTypes)
        }
    }

    private fun launchOutputCreatorIfEnabled() {
        if (selectionControls().outputEnabled) {
            outputCreator.launch(FileSelectionRules.DEFAULT_OUTPUT_FILE_NAME)
        }
    }

    private fun launchFolderPickerIfEnabled() {
        if (selectionControls().folderEnabled) {
            folderPicker.launch(folderUri)
        }
    }

    private fun startBatchTranscription() {
        if (folderUri != null && !folderAccessReady) return
        stopPreviewPlayback(render = true)
        val request = TranscriptionWorker.requestAll(folderUri, exportOutputUri())
        beginTranscriptionHandoff(request.id, requestedEntryId = null)
        TranscriptionWorker.enqueue(this, request)
    }

    private fun evaluateStartupProcessing() {
        when (val decision = startupCoordinator.evaluate(startupPolicyStore.load())) {
            is StartupProcessingDecision.Prompt -> {
                if (
                    !supportFragmentManager.isStateSaved &&
                    supportFragmentManager.findFragmentByTag(StartupProcessingDialogFragment.TAG) == null
                ) {
                    StartupProcessingDialogFragment.newInstance(decision.pendingCount)
                        .show(supportFragmentManager, StartupProcessingDialogFragment.TAG)
                }
            }
            is StartupProcessingDecision.Start -> startBatchTranscription()
            is StartupProcessingDecision.Finish,
            StartupProcessingDecision.Wait,
            -> Unit
        }
    }

    override fun onStartupProcessingConfirmed(remember: Boolean) {
        if (!startupCoordinator.confirmPrompt()) return
        if (remember) {
            startupPolicyStore.save(StartupProcessingPolicy.AUTOMATIC)
        }
        evaluateStartupProcessing()
    }

    override fun onStartupProcessingDeclined(remember: Boolean) {
        if (!startupCoordinator.declinePrompt()) return
        if (remember) {
            startupPolicyStore.save(StartupProcessingPolicy.LEAVE_QUEUED)
        }
        updateControls()
    }

    private fun beginTranscriptionHandoff(workId: UUID, requestedEntryId: Long?) {
        currentSessionTranscriptionWorkId = workId
        currentSessionObservedActiveTranscription = false
        transcriptionHandoff.begin(workId.toString(), requestedEntryId)
        clearTranscriptionProgress()
        transcriptionState = TranscriptionObservationState.ACTIVE
        transcriptionPhase = AndroidTranscriptionHandoffCoordinator.PREPARATION_PHASE
        transcriptionPreparationOwnerEntryId = requestedEntryId
        startupCoordinator.setTranscriptionState(known = true, active = true)
        updateControls()
    }

    private fun transcriptionActive(): Boolean = transcriptionHandoff.active(
        transcriptionState == TranscriptionObservationState.ACTIVE,
    )

    private fun classifyTranscriptionState(infos: List<WorkInfo>): TranscriptionObservationState {
        val active = transcriptionHandoff.active(infos.any { it.state in ACTIVE_WORK_STATES })
        val currentSessionFinished = infos.any { info ->
            info.state.isFinished &&
                (info.id == currentSessionTranscriptionWorkId || currentSessionObservedActiveTranscription)
        }
        return TranscriptionUiRules.transcriptionObservation(
            TranscriptionObservationInput(
                hasActiveWork = active,
                hasCurrentSessionFinishedWork = currentSessionFinished,
            ),
        )
    }

    private fun clearTranscriptionProgress() {
        transcriptionFinished = false
        transcriptionPhase = null
        transcriptionFilename = null
        transcriptionEntryId = null
        transcriptionPreparationOwnerEntryId = null
        transcriptionIndeterminate = true
        transcriptionProgressValue = 0
        processedUs = -1L
        durationUs = -1L
        completedFiles = 0
        totalFiles = 0
        failedFiles = 0
    }

    private fun updateControls() {
        importAudio.isEnabled = !ingestionActive
        publishTaskState()
        invalidateOptionsMenu()
    }

    private fun startPreviewPlayback(entry: AudioCatalogEntry) {
        if (folderBusy() || transcriptionActive()) return
        stopPreviewPlayback(render = false)
        previewEntryId = entry.id
        previewState = PreviewPlaybackState.LOADING
        refreshCatalog()
        val player = MediaPlayer()
        previewPlayer = player
        runCatching {
            player.setDataSource(this, Uri.parse(entry.documentUri))
            player.setOnPreparedListener { prepared ->
                if (previewPlayer != prepared || previewEntryId != entry.id) return@setOnPreparedListener
                previewState = PreviewPlaybackState.PLAYING
                prepared.start()
                refreshCatalog()
            }
            player.setOnCompletionListener { completed ->
                if (previewPlayer == completed) {
                    stopPreviewPlayback(render = true)
                }
            }
            player.setOnErrorListener { failed, _, _ ->
                if (previewPlayer == failed) {
                    showError(getString(R.string.error_cannot_play, entry.displayName))
                    stopPreviewPlayback(render = true)
                }
                true
            }
            player.prepareAsync()
        }.onFailure {
            showError(getString(R.string.error_cannot_play, entry.displayName))
            stopPreviewPlayback(render = true)
        }
    }

    private fun stopPreviewPlayback(render: Boolean) {
        previewPlayer?.let { player ->
            runCatching {
                player.setOnPreparedListener(null)
                player.setOnCompletionListener(null)
                player.setOnErrorListener(null)
                if (player.isPlaying) player.stop()
                player.release()
            }
        }
        previewPlayer = null
        previewEntryId = null
        previewState = PreviewPlaybackState.IDLE
        if (render) refreshCatalog()
    }

    private fun publishTaskState() {
        if (!::importAudio.isInitialized) return
        val folderState = when {
            folderAccessError != null -> FolderSetupSnapshotState.ERROR
            folderUri == null -> FolderSetupSnapshotState.UNSELECTED
            folderAccessReady -> FolderSetupSnapshotState.READY
            else -> FolderSetupSnapshotState.READY
        }
        val outputState = when {
            outputAccessReady -> OutputSetupSnapshotState.READY
            outputAccessError != null -> OutputSetupSnapshotState.INVALID
            outputUri == null -> OutputSetupSnapshotState.REQUIRED
            else -> OutputSetupSnapshotState.READY
        }
        val audioInputAvailable = folderUri?.let { folderAccessReady } ?: currentEntries.any {
            it.folderUri == AndroidAudioImportConstants.SOURCE_ID
        }
        val eligible = modelReady &&
            audioInputAvailable &&
            !folderBusy()
        val transcription = TranscriptionTaskSnapshot(
            active = transcriptionActive(),
            activeEntryId = transcriptionEntryId,
            preparationOwnerEntryId = transcriptionPreparationOwnerEntryId,
            phase = transcriptionPhase,
            percent = transcriptionProgressValue.takeUnless { transcriptionIndeterminate },
            processedUs = processedUs.takeIf { it >= 0 },
            durationUs = durationUs.takeIf { it > 0 },
            completedFiles = completedFiles.takeIf { totalFiles > 0 },
            totalFiles = totalFiles.takeIf { it > 0 },
            failedFiles = failedFiles.takeIf { it > 0 },
            prerequisiteError = null,
        )
        val controls = selectionControls()
        val folderSyncState = taskStateHost.folderSyncCoordinator.state
        val modelSnapshot = ModelSetupSnapshot(
            state = modelSetupState,
            detail = androidModelTaskDetail(modelSetupState, modelMessage),
            installationPhase = modelMessage.takeIf {
                modelSetupState == ModelSetupSnapshotState.INSTALLING
            },
            progressPercent = modelDownloadProgress,
            downloadAvailable = modelDownloadAvailable,
            canCancel = modelInstallCanCancel,
            selectedModel = SpeechModelPackageIdentity(
                schemaVersion = SpeechModelCatalog.PACKAGE_SCHEMA_VERSION,
                catalogId = selectedDownloadModel.catalogId,
                modelVersion = selectedDownloadModel.manifest.version,
            ),
            downloadChoices = SpeechModelCatalog.networkDownloadChoices(SpeechModelPlatform.ANDROID),
        )
        val outputSnapshot = OutputSetupSnapshot(
            state = outputState,
            detail = outputAccessError ?: outputDisplayName,
        )
        val folderSnapshot = FolderSetupSnapshot(
            state = folderState,
            detail = folderAccessError ?: scanMessage ?: folderDisplayName,
        )
        val hydration = AndroidMainScreenHydration(
            modelKnown = modelPresentationKnown,
            outputKnown = outputPresentationKnown,
            folderKnown = folderPresentationKnown,
            catalogKnown = catalogPresentationKnown,
        )
        if (
            AndroidOnboardingHintPresenter.shouldComplete(
                onboardingHintLifecycle,
                hydration,
                modelSnapshot,
                outputSnapshot,
                folderSnapshot,
                keyboardStatus,
                keyboardStatusKnown,
            )
        ) {
            onboardingHintLifecycle = AndroidOnboardingHintLifecycle.COMPLETED
            onboardingHintStore.save(onboardingHintLifecycle)
        }
        if (
            AndroidVoiceKeyboardDiscoveryPresenter.shouldComplete(
                keyboardDiscoveryLifecycle,
                keyboardStatus,
                keyboardStatusKnown,
            )
        ) {
            keyboardDiscoveryLifecycle = AndroidVoiceKeyboardDiscoveryLifecycle.COMPLETED
            keyboardDiscoveryStore.save(keyboardDiscoveryLifecycle)
        }
        taskStateHost.update { current ->
            current.copy(
                model = modelSnapshot,
                output = outputSnapshot,
                outputTaskHidden = selectionStore.isOutputTaskHidden(),
                folder = folderSnapshot,
                entries = currentEntries,
                preview = PreviewTaskSnapshot(
                    activeEntryId = previewEntryId,
                    state = previewState,
                ),
                transcription = transcription,
                transcriptionEligible = eligible,
                previewEligible = !folderBusy() && !transcriptionActive(),
                importEnabled = !ingestionActive,
                hydration = hydration,
                folderSync = AndroidFolderSyncPresentation(
                    visible = folderUri != null || folderSyncState.active,
                    active = folderSyncState.active,
                    enabled = folderUri != null && !folderSyncState.active && controls.refreshEnabled,
                    accessibilityLabel = if (folderSyncState.active) {
                        AndroidFolderSyncPresentation.ACCESSIBILITY_REFRESHING
                    } else {
                        AndroidFolderSyncPresentation.ACCESSIBILITY_REFRESH
                    },
                ),
                onboardingLifecycle = onboardingHintLifecycle,
                keyboardStatus = keyboardStatus,
                keyboardKnown = keyboardStatusKnown,
                keyboardDiscoveryLifecycle = keyboardDiscoveryLifecycle,
            )
        }
    }

    private fun renderTaskState(state: AndroidMainScreenState) {
        renderingFilter = true
        taskFilters.check(
            when (state.taskList.filter) {
                TaskListFilter.NEW -> R.id.newTab
                TaskListFilter.PROCESSED -> R.id.processedTab
                TaskListFilter.ALL -> R.id.allTab
            },
        )
        renderingFilter = false
        importAudio.isEnabled = state.importEnabled
        updateTaskListAnimation(state.transcriptionActive)
        taskAdapter.submitList(
            TaskListDisplayItems.from(
                state.taskList,
                state.onboardingHint,
                state.keyboardDiscovery,
            ),
        )
        invalidateOptionsMenu()
    }

    private fun updateTaskListAnimation(transcriptionActive: Boolean) {
        val suppress = AndroidTaskListAnimationPolicy.suppressStructuralAnimations(transcriptionActive)
        if (suppress == taskListAnimatorSuppressed) return
        taskListAnimatorSuppressed = suppress
        if (suppress) {
            taskList.itemAnimator?.endAnimations()
            taskList.itemAnimator = null
        } else {
            taskList.itemAnimator = taskListItemAnimator
        }
    }

    private fun handleTaskAction(request: AndroidTaskActionRequest) {
        taskActionRouter.route(request)
    }

    private fun openDocumentation() {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(VoiceInboxPublicLinks.DOCUMENTATION)))
        }.onFailure { error ->
            if (error is android.content.ActivityNotFoundException) {
                Toast.makeText(this, R.string.settings_about_link_error, Toast.LENGTH_LONG).show()
            } else {
                throw error
            }
        }
    }

    private fun dismissOnboardingHint() {
        if (onboardingHintLifecycle != AndroidOnboardingHintLifecycle.ACTIVE) return
        onboardingHintLifecycle = AndroidOnboardingHintLifecycle.DISMISSED
        onboardingHintStore.save(onboardingHintLifecycle)
        publishTaskState()
    }

    private fun dismissKeyboardDiscovery() {
        if (keyboardDiscoveryLifecycle != AndroidVoiceKeyboardDiscoveryLifecycle.ELIGIBLE) return
        keyboardDiscoveryLifecycle = AndroidVoiceKeyboardDiscoveryLifecycle.DISMISSED
        keyboardDiscoveryStore.save(keyboardDiscoveryLifecycle)
        publishTaskState()
    }

    private fun refreshKeyboardStatus(publish: Boolean) {
        keyboardStatus = keyboardStatusProvider.current()
        keyboardStatusKnown = true
        if (publish) publishTaskState()
    }

    private fun performKeyboardSystemAction() {
        val action = AndroidVoiceKeyboardActionPresenter.systemAction(keyboardStatusProvider.current())
        if (!keyboardSystemGateway.perform(action)) {
            Toast.makeText(this, R.string.voice_keyboard_system_action_error, Toast.LENGTH_LONG).show()
        }
    }

    private fun openVoiceKeyboardDocumentation() {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(VoiceInboxPublicLinks.VOICE_KEYBOARD_DOCUMENTATION)))
        }.onFailure { error ->
            if (error is android.content.ActivityNotFoundException) {
                Toast.makeText(this, R.string.settings_about_link_error, Toast.LENGTH_LONG).show()
            } else {
                throw error
            }
        }
    }

    private fun performTaskAction(kind: TaskActionKind, entry: AudioCatalogEntry?) {
        when (kind) {
            TaskActionKind.DOWNLOAD_MODEL,
            TaskActionKind.RETRY_MODEL_DOWNLOAD,
            -> SpeechModelDownloadWorker.enqueue(this, selectedDownloadModel)
            TaskActionKind.SELECT_DOWNLOAD_MODEL -> chooseDownloadModel()
            TaskActionKind.IMPORT_MODEL -> modelFolderPicker.launch(null)
            TaskActionKind.CANCEL_MODEL_DOWNLOAD -> SpeechModelDownloadWorker.cancel(this)
            TaskActionKind.CREATE_OUTPUT -> launchOutputCreatorIfEnabled()
            TaskActionKind.SELECT_OUTPUT -> launchOutputPickerIfEnabled()
            TaskActionKind.HIDE_OUTPUT -> {
                selectionStore.hideOutputTask()
                publishTaskState()
            }
            TaskActionKind.SELECT_FOLDER -> launchFolderPickerIfEnabled()
            TaskActionKind.REFRESH_FOLDER -> dispatchManualFolderRefresh()
            TaskActionKind.IMPORT_AUDIO -> if (!ingestionActive) {
                importPicker.launch(arrayOf("audio/*", "application/ogg"))
            }
            TaskActionKind.TRANSCRIBE_ALL -> startBatchTranscription()
            TaskActionKind.TRANSCRIBE -> entry?.let(::transcribeEntry)
            TaskActionKind.RETRY_TRANSCRIPTION -> entry?.let(::retryEntry)
            TaskActionKind.PLAY -> entry?.let(::startPreviewPlayback)
            TaskActionKind.STOP -> stopPreviewPlayback(render = true)
            TaskActionKind.SHOW_TEXT -> entry?.let(::showTranscriptText)
            TaskActionKind.ENABLE_VOICE_KEYBOARD,
            TaskActionKind.CHOOSE_VOICE_KEYBOARD,
            -> performKeyboardSystemAction()
            TaskActionKind.OPEN_VOICE_KEYBOARD_DOCUMENTATION -> openVoiceKeyboardDocumentation()
        }
    }

    private fun chooseDownloadModel() {
        if (modelSetupState == ModelSetupSnapshotState.INSTALLING) return
        val choices = SpeechModelCatalog.modelsFor(SpeechModelPlatform.ANDROID)
            .filter { it.distribution.networkDownloadAvailable }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.download_speech_model)
            .setSingleChoiceItems(
                choices.map { it.displayName }.toTypedArray(),
                choices.indexOfFirst { it.catalogId == selectedDownloadModel.catalogId },
            ) { dialog, which ->
                selectedDownloadModel = choices[which]
                publishTaskState()
                dialog.dismiss()
            }
            .show()
    }

    private fun transcribeEntry(entry: AudioCatalogEntry) {
        if (entry.state != AudioFileState.PENDING) return
        stopPreviewPlayback(render = true)
        val request = TranscriptionWorker.requestEntry(folderUri, exportOutputUri(), entry.id)
        beginTranscriptionHandoff(request.id, entry.id)
        TranscriptionWorker.enqueue(this, request)
    }

    private fun exportOutputUri(): Uri? = outputUri?.takeIf { outputAccessReady }

    private fun updateMenu(menu: Menu) {
        val state = taskStateHost.state.value
        menu.findItem(R.id.menuRefreshFolder)?.apply {
            isVisible = state.folderSync.visible
            isEnabled = state.folderSync.enabled
        }
        renderRefreshIndicator(state.folderSync)
    }

    private fun dispatchManualFolderRefresh() {
        val sync = taskStateHost.state.value.folderSync
        if (!sync.enabled || sync.active || !selectionControls().refreshEnabled) return
        scanFolder()
    }

    private fun renderRefreshIndicator(sync: AndroidFolderSyncPresentation) {
        val view = refreshActionView ?: return
        view.visibility = if (sync.visible) View.VISIBLE else View.GONE
        view.isEnabled = sync.enabled
        view.contentDescription = getString(
            if (sync.active) R.string.refresh_state_refreshing else R.string.refresh_state_idle,
        )
        ViewCompat.setStateDescription(
            view,
            if (sync.active) getString(R.string.refresh_state_refreshing) else getString(R.string.refresh_state_idle),
        )
        if (sync.active == refreshIndicatorActive) return
        refreshIndicatorActive = sync.active
        refreshIndicatorHandler.removeCallbacks(startRefreshIndicator)
        refreshIndicatorHandler.removeCallbacks(stopRefreshIndicator)
        if (sync.active) {
            refreshIndicatorHandler.postDelayed(startRefreshIndicator, REFRESH_SHOW_DELAY_MS)
        } else if (refreshIndicatorAnimator != null) {
            val elapsed = android.os.SystemClock.uptimeMillis() - refreshIndicatorStartedAt
            refreshIndicatorHandler.postDelayed(
                stopRefreshIndicator,
                (REFRESH_MIN_VISIBLE_MS - elapsed).coerceAtLeast(0L),
            )
        }
    }

    private fun startRefreshIndicatorAnimation() {
        if (!refreshIndicatorActive || activityDestroyed) return
        val view = refreshActionView ?: return
        refreshIndicatorStartedAt = android.os.SystemClock.uptimeMillis()
        refreshIndicatorAnimator?.cancel()
        refreshIndicatorAnimator = android.animation.ObjectAnimator.ofFloat(view, View.ROTATION, 0f, 360f).apply {
            duration = REFRESH_ROTATION_MS
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            start()
        }
    }

    private fun stopRefreshIndicatorAnimation() {
        if (refreshIndicatorActive) return
        refreshIndicatorAnimator?.cancel()
        refreshIndicatorAnimator = null
        refreshActionView?.rotation = 0f
    }

    private fun updateOutputSummary(displayName: String?) {
        outputDisplayName = displayName
        publishTaskState()
    }

    private fun updateFolderSummary(displayName: String?) {
        folderDisplayName = displayName
        publishTaskState()
    }

    private fun showError(message: String) {
        runOnUiThread {
            if (activityDestroyed) return@runOnUiThread
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun activeSourceScope(): AudioCatalogSourceScope =
        AudioCatalogSourceScope.of(
            listOfNotNull(
                AndroidAudioImportConstants.SOURCE_ID,
                folderUri?.toString(),
            ),
        )

    private fun cleanupImportedAudio() {
        importExecutor.execute {
            val cleanupCatalog = AndroidSqlDelightAudioCatalogFactory(this).create()
            try {
                AndroidAudioImportStorage(this).cleanupOrphans(cleanupCatalog)
            } finally {
                cleanupCatalog.close()
            }
        }
    }

    private fun handleShareIntent(sharedIntent: Intent) {
        if (!AudioShareIntentParser.isShareIntent(sharedIntent)) return
        val uris = AudioShareIntentParser.streamUris(sharedIntent)
        sharedIntent.action = Intent.ACTION_MAIN
        sharedIntent.clipData = null
        sharedIntent.replaceExtras(Bundle())
        if (uris.isEmpty()) {
            Toast.makeText(this, R.string.no_shared_audio, Toast.LENGTH_LONG).show()
            return
        }
        ingestAudioUris(uris)
    }

    private fun ingestAudioUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (ingestionActive) {
            queuedImportUris += uris.filter { candidate ->
                queuedImportUris.none { it.toString() == candidate.toString() }
            }
            return
        }
        ingestionActive = true
        updateControls()
        val sourceIds = uris.map(Uri::toString)
        importExecutor.execute {
            val importCatalog = AndroidSqlDelightAudioCatalogFactory(this).create()
            val summary = try {
                runCatching {
                    val storage = AndroidAudioImportStorage(this)
                    storage.cleanupOrphans(importCatalog)
                    AudioImportUseCase(
                        storage = storage,
                        catalog = SqlDelightAudioImportCatalog(importCatalog),
                    ).ingest(sourceIds)
                }.getOrElse { error ->
                    AudioImportSummary(
                        listOf(
                            AudioImportItemOutcome.Rejected(
                                displayName = getString(R.string.audio_import_default_name),
                                reason = error.message ?: getString(R.string.audio_import_failed),
                            ),
                        ),
                    )
                }
            } finally {
                importCatalog.close()
            }
            runOnUiThread {
                if (activityDestroyed) return@runOnUiThread
                ingestionActive = false
                Toast.makeText(this, summary.localizedMessage(resources), Toast.LENGTH_LONG).show()
                refreshCatalog()
                if (queuedImportUris.isNotEmpty()) {
                    val queued = queuedImportUris.toList()
                    queuedImportUris.clear()
                    ingestAudioUris(queued)
                }
            }
        }
    }


    private enum class FolderScanOrigin {
        STARTUP,
        USER,
    }

    private companion object {
        const val STATE_STARTUP_PROCESSING_STAGE = "startup-processing-stage"
        const val STATE_PENDING_MODEL_URI = "pending-model-uri"
        const val STATE_PENDING_MODEL_CATALOG_ID = "pending-model-catalog-id"
        const val EXTRA_OPEN_MODEL_FOLDER_PICKER = "open-model-folder-picker"
        const val REFRESH_SHOW_DELAY_MS = 180L
        const val REFRESH_MIN_VISIBLE_MS = 450L
        const val REFRESH_ROTATION_MS = 800L
        val ACTIVE_WORK_STATES = setOf(
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.BLOCKED,
            WorkInfo.State.RUNNING,
        )

        @Volatile
        private var sharedModelReadiness: SpeechModelReadinessManager? = null
        private val handledModelInstallSuccessIds = mutableSetOf<String>()
        private val handledTranscriptionFailureIds = mutableSetOf<String>()

        fun getSharedModelReadiness(repository: () -> SpeechModelRepository): SpeechModelReadinessManager =
            sharedModelReadiness ?: synchronized(this) {
                sharedModelReadiness ?: SpeechModelReadinessManager(
                    repository = repository,
                    executor = Executors.newSingleThreadExecutor(),
                ).also { sharedModelReadiness = it }
            }

        fun shouldHandleModelInstallSuccess(id: String): Boolean =
            synchronized(this) { handledModelInstallSuccessIds.add(id) }

        fun shouldRefreshModelAfterFailure(id: String): Boolean =
            synchronized(this) { handledTranscriptionFailureIds.add(id) }
    }
}
