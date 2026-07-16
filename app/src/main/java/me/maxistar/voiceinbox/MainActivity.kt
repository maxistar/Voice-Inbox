package me.maxistar.voiceinbox

import me.maxistar.voiceinbox.core.*

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.UUID

class MainActivity : AppCompatActivity(), StartupProcessingDialogFragment.Listener {
    private lateinit var importAudio: FloatingActionButton
    private lateinit var newTab: MaterialButton
    private lateinit var processedTab: MaterialButton
    private lateinit var allTab: MaterialButton
    private lateinit var taskFilters: MaterialButtonToggleGroup
    private lateinit var taskList: RecyclerView
    private lateinit var taskAdapter: TaskListAdapter
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
    private var modelMessage = "Checking speech model"
    private var modelDownloadAvailable = false
    private var modelDownloadProgress: Int? = null
    private var modelInstallCanCancel = false
    private var scanMessage: String? = null
    private var transcriptionFinished = false
    private var transcriptionPhase: String? = null
    private var transcriptionFilename: String? = null
    private var transcriptionEntryId: Long? = null
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
    private var hasStarted = false
    private var ingestionActive = false
    private var currentEntries: List<AudioCatalogEntry> = emptyList()
    private var renderingFilter = false
    private val queuedImportUris = mutableListOf<Uri>()

    private val outputPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
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
        modelReadiness = getSharedModelReadiness(SpeechModelRepository(noBackupFilesDir.resolve("models")))
        startupPolicyStore = StartupProcessingPolicyStore(
            getSharedPreferences(StartupProcessingPolicyStore.PREFERENCES_NAME, MODE_PRIVATE),
        )
        startupCoordinator = StartupProcessingCoordinator.restore(
            savedInstanceState?.getString(STATE_STARTUP_PROCESSING_STAGE),
        )
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_STARTUP_PROCESSING_STAGE, startupCoordinator.savedStage())
        super.onSaveInstanceState(outState)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_options, menu)
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
                if (selectionControls().refreshEnabled) {
                    scanFolder()
                }
                true
            }
            R.id.menuSettings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    override fun onDestroy() {
        activityDestroyed = true
        stopPreviewPlayback(render = false)
        folderExecutor.shutdown()
        importExecutor.shutdown()
        super.onDestroy()
    }

    private fun bindViews() {
        importAudio = findViewById(R.id.importAudio)
        newTab = findViewById(R.id.newTab)
        processedTab = findViewById(R.id.processedTab)
        allTab = findViewById(R.id.allTab)
        taskFilters = findViewById(R.id.taskFilters)
        taskList = findViewById(R.id.taskList)
        taskAdapter = TaskListAdapter(::handleTaskAction)
        taskList.layoutManager = LinearLayoutManager(this)
        taskList.adapter = taskAdapter
    }

    private fun bootstrapSelectionIdentities() {
        setStoredOutputIdentity(selectionStore.loadOutputUri()?.let(Uri::parse))
        setStoredFolderIdentity(selectionStore.loadFolderUri()?.let(Uri::parse))
    }

    private fun setStoredOutputIdentity(stored: Uri?) {
        outputUri = stored
        outputAccessReady = false
        outputDisplayName = null
        startupCoordinator.setOutputReady(false)
        publishTaskState()
    }

    private fun setStoredFolderIdentity(stored: Uri?) {
        folderUri = stored
        folderAccessReady = false
        folderDisplayName = null
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
            startupCoordinator.setOutputReady(false)
        } else {
            outputAccessReady = false
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
                                ?: "Output file is temporarily unavailable"
                            startupCoordinator.setOutputReady(false)
                            outputDisplayName = null
                        }
                        PersistedSelectionAccessState.PERMISSION_REVOKED -> {
                            selectionStore.clearOutputUri()
                            setStoredOutputIdentity(null)
                            outputAccessError = result.error?.message
                                ?: "Output file access was revoked; select it again"
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
            startupCoordinator.setFolderReady(false)
            refreshCatalog()
            return
        }
        folderAccessReady = false
        folderChecking = true
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
                folderChecking = false
                when (result.state) {
                    PersistedSelectionAccessState.VALID -> {
                        folderAccessReady = true
                        folderAccessError = null
                        startupCoordinator.setFolderReady(true)
                        updateFolderSummary(result.value)
                        if (scanFolderOnSuccess) {
                            pendingFolderScanOrigin = FolderScanOrigin.STARTUP
                        }
                    }
                    PersistedSelectionAccessState.TEMPORARILY_UNAVAILABLE -> {
                        folderAccessReady = false
                        folderAccessError = result.error?.message
                            ?: "Audio folder is temporarily unavailable"
                        startupCoordinator.setFolderReady(false)
                        folderDisplayName = null
                    }
                    PersistedSelectionAccessState.PERMISSION_REVOKED -> {
                        selectionStore.clearFolderUri()
                        setStoredFolderIdentity(null)
                        folderAccessError = result.error?.message
                            ?: "Audio folder access was revoked; select it again"
                    }
                }
                publishTaskState()
                updateControls()
                refreshCatalog()
                maybeStartPendingFolderScan()
                evaluateStartupProcessing()
            }
        }
    }

    private fun maybeStartPendingFolderScan() {
        val origin = pendingFolderScanOrigin ?: return
        if (
            !folderAccessReady ||
            transcriptionState == TranscriptionObservationState.UNKNOWN ||
            transcriptionActive()
        ) return
        pendingFolderScanOrigin = null
        scanFolder(origin)
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
                    outputAccessError = it.message ?: "Output file is not writable"
                    publishTaskState()
                    updateControls()
                    evaluateStartupProcessing()
                }
            }
        }
    }

    private fun acceptFolder(uri: Uri) {
        val validationGeneration = ++folderValidationGeneration
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
                    folderAccessError = null
                    startupCoordinator.setFolderReady(true)
                    updateFolderSummary(name)
                    folderAccessError = null
                    publishTaskState()
                    updateControls()
                    refreshCatalog()
                    pendingFolderScanOrigin = FolderScanOrigin.USER
                    maybeStartPendingFolderScan()
                }
            }.onFailure {
                runOnUiThread {
                    if (
                        activityDestroyed ||
                        validationGeneration != folderValidationGeneration
                    ) return@runOnUiThread
                    folderChecking = false
                    folderAccessError = it.message ?: "Audio folder is not readable"
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
            setModelUi(
                "The selected model folder cannot be accessed after the picker closes",
                canDownload = true,
            )
            updateControls()
            return
        }
        if (!alreadyPersisted) SpeechModelImportPermission.recordOwned(this, uri)
        setModelUi("Checking local speech model", canDownload = false)
        updateControls()
        importExecutor.execute {
            runCatching {
                SpeechModelDirectoryReader(contentResolver).requiredDocuments(
                    uri,
                    EmbeddedSpeechModel.manifest,
                )
            }.onSuccess {
                runOnUiThread {
                    if (activityDestroyed) return@runOnUiThread
                    SpeechModelImportWorker.enqueue(this, uri)
                }
            }.onFailure { error ->
                SpeechModelImportPermission.releaseOwnedIfUnused(this)
                runOnUiThread {
                    if (activityDestroyed) return@runOnUiThread
                    setModelUi(
                        error.message ?: "The selected model folder is not readable",
                        canDownload = true,
                    )
                    updateControls()
                }
            }
        }
    }

    private fun scanFolder(origin: FolderScanOrigin = FolderScanOrigin.USER) {
        val folder = folderUri ?: return
        if (
            !folderAccessReady ||
            folderChecking ||
            folderScanQueued ||
            scanning ||
            transcriptionState == TranscriptionObservationState.UNKNOWN ||
            transcriptionActive()
        ) return
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
        publishTaskState()
        updateControls()
        folderExecutor.execute {
            runOnUiThread {
                if (activityDestroyed) return@runOnUiThread
                folderScanQueued = false
                scanning = true
                scanMessage = "Scanning folder"
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
                    scanMessage = "Scan complete: $count audio files"
                    refreshCatalog(startupGeneration)
                }
            }.onFailure { error ->
                runOnUiThread {
                    if (activityDestroyed) return@runOnUiThread
                    folderScanQueued = false
                    scanning = false
                    folderAccessError = error.message ?: "Folder scan failed"
                    startupGeneration?.let(startupCoordinator::onStartupScanFailed)
                    evaluateStartupProcessing()
                    updateControls()
                }
            }
        }
    }

    private fun refreshCatalog(startupGeneration: Long? = null) {
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
            val newEntries = catalog.newEntries(token.sourceScope)
            val processedEntries = catalog.processedEntries(token.sourceScope)
            val entries = (newEntries + processedEntries).distinctBy(AudioCatalogEntry::id)
            runOnUiThread {
                if (activityDestroyed) return@runOnUiThread
                if (
                    !CatalogRefreshPolicy.isCurrent(
                        request = token,
                        currentGeneration = catalogRefreshGeneration,
                        currentSourceScope = activeSourceScope(),
                    )
                ) return@runOnUiThread
                pendingCount = newEntries.count { it.state == AudioFileState.PENDING }
                val catalogFailedCount = processedEntries.count { it.state == AudioFileState.FAILED }
                renderEntries(entries)
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
        }
    }

    private fun renderEntries(entries: List<AudioCatalogEntry>) {
        currentEntries = entries
        publishTaskState()
    }

    private fun retryEntry(entry: AudioCatalogEntry) {
        val output = outputUri ?: return
        stopPreviewPlayback(render = true)
        currentSessionTranscriptionWorkId = TranscriptionWorker.enqueueRetry(
            this,
            folderUri,
            output,
            entry.id,
        )
    }

    private fun showTranscriptText(entry: AudioCatalogEntry) {
        val transcript = entry.transcriptText?.takeIf { it.isNotBlank() } ?: return
        AlertDialog.Builder(this)
            .setTitle(entry.displayName)
            .setMessage(transcript)
            .setPositiveButton(android.R.string.ok, null)
            .show()
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
                modelSetupState = ModelSetupSnapshotState.INSTALLING
                setModelUi("Checking speech model", canDownload = false)
            }
            is SpeechModelReadinessState.Ready -> {
                modelReady = true
                modelSetupState = ModelSetupSnapshotState.READY
                setModelUi("Speech model ready", canDownload = false)
            }
            SpeechModelReadinessState.Missing -> {
                modelReady = false
                modelSetupState = ModelSetupSnapshotState.REQUIRED
                setModelUi("Speech model is not installed", canDownload = true)
            }
            is SpeechModelReadinessState.Invalid -> {
                modelReady = false
                modelSetupState = ModelSetupSnapshotState.INVALID
                setModelUi("Invalid speech model: ${state.reason}", canDownload = true)
            }
            is SpeechModelReadinessState.Failed -> {
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
                        modelReady = false
                        modelSetupState = ModelSetupSnapshotState.INSTALLING
                        startupCoordinator.setModelReady(false)
                        val bytes = info.progress.getLong(SpeechModelInstallationWork.KEY_BYTES_DOWNLOADED, 0)
                        val total = info.progress.getLong(
                            SpeechModelInstallationWork.KEY_TOTAL_BYTES,
                            EmbeddedSpeechModel.manifest.totalSizeBytes,
                        )
                        modelMessage =
                            info.progress.getString(SpeechModelInstallationWork.KEY_MESSAGE)
                                ?: "Installing speech model"
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
                            SpeechModelPreparation.invalidate(NativeTranscriptionBridge::reset)
                        }
                        refreshModel()
                    }
                    WorkInfo.State.FAILED -> {
                        SpeechModelImportPermission.releaseOwnedIfUnused(this)
                        modelReady = false
                        modelSetupState = ModelSetupSnapshotState.INVALID
                        startupCoordinator.setModelReady(false)
                        setModelUi(
                            info.outputData.getString(SpeechModelInstallationWork.KEY_ERROR)
                                ?: "Speech model installation failed",
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
                    clearTranscriptionProgress()
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
                transcriptionPhase = data.getString(TranscriptionWorker.KEY_PHASE)
                    ?: when (info.state) {
                        WorkInfo.State.ENQUEUED -> "Queued"
                        WorkInfo.State.RUNNING -> "Transcribing"
                        WorkInfo.State.SUCCEEDED -> "Completed"
                        WorkInfo.State.FAILED ->
                            data.getString(TranscriptionWorker.KEY_ERROR) ?: "Failed"
                        else -> info.state.name
                    }
                transcriptionFilename = data.getString(TranscriptionWorker.KEY_FILENAME)
                transcriptionEntryId = data
                    .getLong(TranscriptionWorker.KEY_ACTIVE_ENTRY_ID, TranscriptionWorker.NO_ENTRY_ID)
                    .takeIf { it != TranscriptionWorker.NO_ENTRY_ID }
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
            outputSelected = outputAccessReady,
            folderSelected = folderAccessReady,
            pendingCount = pendingCount,
            transcriptionState = transcriptionState,
            scanning = folderBusy(),
            audioInputAvailable = folderUri?.let { folderAccessReady } ?: currentEntries.any {
                it.folderUri == AndroidAudioImportConstants.SOURCE_ID
            },
        )

    private fun folderBusy(): Boolean = folderChecking || folderScanQueued || scanning

    private fun launchOutputPickerIfEnabled() {
        if (selectionControls().outputEnabled) {
            outputPicker.launch(FileSelectionRules.outputMimeTypes)
        }
    }

    private fun launchFolderPickerIfEnabled() {
        if (selectionControls().folderEnabled) {
            folderPicker.launch(folderUri)
        }
    }

    private fun startBatchTranscription() {
        val output = outputUri ?: return
        if (!outputAccessReady || (folderUri != null && !folderAccessReady)) return
        stopPreviewPlayback(render = true)
        currentSessionTranscriptionWorkId = TranscriptionWorker.enqueueAll(this, folderUri, output)
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

    private fun transcriptionActive(): Boolean = transcriptionState == TranscriptionObservationState.ACTIVE

    private fun classifyTranscriptionState(infos: List<WorkInfo>): TranscriptionObservationState {
        val active = infos.any { it.state in ACTIVE_WORK_STATES }
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
                    showError("Cannot play ${entry.displayName}")
                    stopPreviewPlayback(render = true)
                }
                true
            }
            player.prepareAsync()
        }.onFailure {
            showError("Cannot play ${entry.displayName}")
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
            folderChecking || folderScanQueued || scanning -> FolderSetupSnapshotState.SCANNING
            folderAccessReady -> FolderSetupSnapshotState.READY
            else -> FolderSetupSnapshotState.SCANNING
        }
        val outputState = when {
            outputAccessReady -> OutputSetupSnapshotState.READY
            outputAccessError != null -> OutputSetupSnapshotState.INVALID
            outputUri == null -> OutputSetupSnapshotState.REQUIRED
            else -> OutputSetupSnapshotState.REQUIRED
        }
        val audioInputAvailable = folderUri?.let { folderAccessReady } ?: currentEntries.any {
            it.folderUri == AndroidAudioImportConstants.SOURCE_ID
        }
        val eligible = modelReady &&
            outputAccessReady &&
            audioInputAvailable &&
            !folderBusy() &&
            !transcriptionActive()
        val transcription = TranscriptionTaskSnapshot(
            active = transcriptionActive(),
            activeEntryId = transcriptionEntryId,
            preparationOwnerEntryId = null,
            phase = transcriptionPhase,
            percent = transcriptionProgressValue.takeUnless { transcriptionIndeterminate },
            processedUs = processedUs.takeIf { it >= 0 },
            durationUs = durationUs.takeIf { it > 0 },
            completedFiles = completedFiles.takeIf { totalFiles > 0 },
            totalFiles = totalFiles.takeIf { it > 0 },
            failedFiles = failedFiles.takeIf { it > 0 },
            prerequisiteError = null,
        )
        taskStateHost.update { current ->
            current.copy(
                model = ModelSetupSnapshot(
                    state = modelSetupState,
                    detail = modelMessage.takeUnless { modelSetupState == ModelSetupSnapshotState.READY },
                    installationPhase = modelMessage.takeIf {
                        modelSetupState == ModelSetupSnapshotState.INSTALLING
                    },
                    progressPercent = modelDownloadProgress,
                    downloadAvailable = modelDownloadAvailable,
                    canCancel = modelInstallCanCancel,
                ),
                output = OutputSetupSnapshot(
                    state = outputState,
                    detail = outputAccessError ?: outputDisplayName,
                ),
                folder = FolderSetupSnapshot(
                    state = folderState,
                    detail = folderAccessError ?: scanMessage ?: folderDisplayName,
                ),
                entries = currentEntries,
                preview = PreviewTaskSnapshot(
                    activeEntryId = previewEntryId,
                    state = previewState,
                ),
                transcription = transcription,
                transcriptionEligible = eligible,
                previewEligible = !folderBusy() && !transcriptionActive(),
                importEnabled = !ingestionActive,
                refreshFolderVisible = folderUri != null,
                refreshFolderEnabled = selectionControls().refreshEnabled,
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
        taskAdapter.submitList(TaskListDisplayItems.from(state.taskList))
        invalidateOptionsMenu()
    }

    private fun handleTaskAction(request: AndroidTaskActionRequest) {
        taskActionRouter.route(request)
    }

    private fun performTaskAction(kind: TaskActionKind, entry: AudioCatalogEntry?) {
        when (kind) {
            TaskActionKind.DOWNLOAD_MODEL,
            TaskActionKind.RETRY_MODEL_DOWNLOAD,
            -> SpeechModelDownloadWorker.enqueue(this)
            TaskActionKind.IMPORT_MODEL -> modelFolderPicker.launch(null)
            TaskActionKind.CANCEL_MODEL_DOWNLOAD -> SpeechModelDownloadWorker.cancel(this)
            TaskActionKind.SELECT_OUTPUT -> launchOutputPickerIfEnabled()
            TaskActionKind.SELECT_FOLDER -> launchFolderPickerIfEnabled()
            TaskActionKind.REFRESH_FOLDER -> if (selectionControls().refreshEnabled) scanFolder()
            TaskActionKind.IMPORT_AUDIO -> if (!ingestionActive) {
                importPicker.launch(arrayOf("audio/*", "application/ogg"))
            }
            TaskActionKind.TRANSCRIBE_ALL -> startBatchTranscription()
            TaskActionKind.TRANSCRIBE -> entry?.let(::transcribeEntry)
            TaskActionKind.RETRY_TRANSCRIPTION -> entry?.let(::retryEntry)
            TaskActionKind.PLAY -> entry?.let(::startPreviewPlayback)
            TaskActionKind.STOP -> stopPreviewPlayback(render = true)
            TaskActionKind.SHOW_TEXT -> entry?.let(::showTranscriptText)
        }
    }

    private fun transcribeEntry(entry: AudioCatalogEntry) {
        val output = outputUri ?: return
        if (entry.state != AudioFileState.PENDING || !outputAccessReady) return
        stopPreviewPlayback(render = true)
        currentSessionTranscriptionWorkId = TranscriptionWorker.enqueueEntry(
            this,
            folderUri,
            output,
            entry.id,
        )
    }

    private fun updateMenu(menu: Menu) {
        val state = taskStateHost.state.value
        menu.findItem(R.id.menuRefreshFolder)?.apply {
            isVisible = state.refreshFolderVisible
            isEnabled = state.refreshFolderEnabled
        }
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
                                displayName = "shared audio",
                                reason = error.message ?: "Audio import failed",
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
                Toast.makeText(this, summary.message(), Toast.LENGTH_LONG).show()
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
        val ACTIVE_WORK_STATES = setOf(
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.BLOCKED,
            WorkInfo.State.RUNNING,
        )

        @Volatile
        private var sharedModelReadiness: SpeechModelReadinessManager? = null
        private val handledModelInstallSuccessIds = mutableSetOf<String>()
        private val handledTranscriptionFailureIds = mutableSetOf<String>()

        fun getSharedModelReadiness(repository: SpeechModelRepository): SpeechModelReadinessManager =
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
