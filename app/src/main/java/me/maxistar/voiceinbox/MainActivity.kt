package me.maxistar.voiceinbox

import me.maxistar.voiceinbox.core.*

import android.content.Intent
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.Executors
import java.util.UUID

class MainActivity : AppCompatActivity(), StartupProcessingDialogFragment.Listener {
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var statusProgress: ProgressBar
    private lateinit var statusMeta: TextView
    private lateinit var downloadModel: Button
    private lateinit var importModel: Button
    private lateinit var transcribeAll: Button
    private lateinit var selectOutput: Button
    private lateinit var selectFolder: Button
    private lateinit var importAudio: Button
    private lateinit var outputName: TextView
    private lateinit var folderName: TextView
    private lateinit var newTab: Button
    private lateinit var processedTab: Button
    private lateinit var fileList: LinearLayout
    private lateinit var emptyList: TextView

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
    private var outputUri: Uri? = null
    private var folderUri: Uri? = null
    private var outputAccessReady = false
    private var folderAccessReady = false
    private var outputAccessError: String? = null
    private var folderAccessError: String? = null
    private var transcriptionState = TranscriptionObservationState.UNKNOWN
    private var folderChecking = false
    private var folderScanQueued = false
    private var scanning = false
    private var pendingCount = 0
    private var selectedTab = MainScreenCatalogTab.NEW
    private var lastCatalogWorkState: CatalogWorkRefreshKey? = null
    private var currentSessionTranscriptionWorkId: UUID? = null
    private var currentSessionObservedActiveTranscription = false
    private var modelMessage = "Checking speech model"
    private var modelLoading = true
    private var modelDownloadAvailable = false
    private var modelDownloadProgress: Int? = null
    private var scanMessage: String? = null
    private var transcriptionFinished = false
    private var transcriptionPhase: String? = null
    private var transcriptionFilename: String? = null
    private var transcriptionIndeterminate = true
    private var transcriptionProgressValue = 0
    private var processedUs = -1L
    private var durationUs = -1L
    private var completedFiles = 0
    private var totalFiles = 0
    private var failedFiles = 0
    private var statusError: String? = null
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
    private var importInboxAvailable = false
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
        bootstrapSelectionIdentities()
        ScheduledTranscriptionScheduler.sync(
            this,
            ScheduledTranscriptionSettingsStore(
                getSharedPreferences(ScheduledTranscriptionSettingsStore.PREFERENCES_NAME, MODE_PRIVATE),
            ).load(),
        )

        downloadModel.setOnClickListener { SpeechModelDownloadWorker.enqueue(this) }
        importModel.setOnClickListener {
            if (modelDownloadAvailable && !modelLoading) modelFolderPicker.launch(null)
        }
        transcribeAll.setOnClickListener { startBatchTranscription() }
        importAudio.setOnClickListener {
            if (!ingestionActive) importPicker.launch(arrayOf("audio/*", "application/ogg"))
        }
        selectOutput.setOnClickListener { launchOutputPickerIfEnabled() }
        selectFolder.setOnClickListener { launchFolderPickerIfEnabled() }
        newTab.setOnClickListener {
            selectedTab = MainScreenCatalogTab.NEW
            refreshCatalog()
        }
        processedTab.setOnClickListener {
            selectedTab = MainScreenCatalogTab.PROCESSED
            refreshCatalog()
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
                if (currentControls().refreshEnabled) {
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
        statusTitle = findViewById(R.id.statusTitle)
        statusDetail = findViewById(R.id.statusDetail)
        statusProgress = findViewById(R.id.statusProgress)
        statusMeta = findViewById(R.id.statusMeta)
        downloadModel = findViewById(R.id.downloadModel)
        importModel = findViewById(R.id.importModel)
        transcribeAll = findViewById(R.id.transcribeAll)
        selectOutput = findViewById(R.id.selectOutput)
        selectFolder = findViewById(R.id.selectFolder)
        importAudio = findViewById(R.id.importAudio)
        outputName = findViewById(R.id.outputName)
        folderName = findViewById(R.id.folderName)
        newTab = findViewById(R.id.newTab)
        processedTab = findViewById(R.id.processedTab)
        fileList = findViewById(R.id.fileList)
        emptyList = findViewById(R.id.emptyList)
    }

    private fun bootstrapSelectionIdentities() {
        setStoredOutputIdentity(selectionStore.loadOutputUri()?.let(Uri::parse))
        setStoredFolderIdentity(selectionStore.loadFolderUri()?.let(Uri::parse))
    }

    private fun setStoredOutputIdentity(stored: Uri?) {
        outputUri = stored
        outputAccessReady = false
        startupCoordinator.setOutputReady(false)
        outputName.text = getString(
            if (stored == null) R.string.output_not_selected else R.string.output_restoring,
        )
    }

    private fun setStoredFolderIdentity(stored: Uri?) {
        folderUri = stored
        folderAccessReady = false
        startupCoordinator.setFolderReady(false)
        folderName.text = getString(
            if (stored == null) R.string.folder_not_selected else R.string.folder_restoring,
        )
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
            outputName.text = getString(R.string.output_restoring)
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
                            outputName.text = getString(R.string.output_temporarily_unavailable)
                        }
                        PersistedSelectionAccessState.PERMISSION_REVOKED -> {
                            selectionStore.clearOutputUri()
                            setStoredOutputIdentity(null)
                            outputAccessError = result.error?.message
                                ?: "Output file access was revoked; select it again"
                        }
                    }
                    renderStatusBlock()
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
        folderName.text = getString(R.string.folder_restoring)
        renderStatusBlock()
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
                        folderName.text = getString(R.string.folder_temporarily_unavailable)
                    }
                    PersistedSelectionAccessState.PERMISSION_REVOKED -> {
                        selectionStore.clearFolderUri()
                        setStoredFolderIdentity(null)
                        folderAccessError = result.error?.message
                            ?: "Audio folder access was revoked; select it again"
                    }
                }
                renderStatusBlock()
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
                    statusError = null
                    renderStatusBlock()
                    updateControls()
                    evaluateStartupProcessing()
                }
            }.onFailure {
                runOnUiThread {
                    if (
                        activityDestroyed ||
                        validationGeneration != outputValidationGeneration
                    ) return@runOnUiThread
                    statusError = it.message ?: "Output file is not writable"
                    renderStatusBlock()
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
                renderStatusBlock()
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
                    statusError = null
                    renderStatusBlock()
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
                    statusError = it.message ?: "Audio folder is not readable"
                    renderStatusBlock()
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
                loading = false,
                canDownload = true,
            )
            updateControls()
            return
        }
        if (!alreadyPersisted) SpeechModelImportPermission.recordOwned(this, uri)
        statusError = null
        setModelUi("Checking local speech model", loading = true, canDownload = false)
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
                        loading = false,
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
        statusError = null
        renderStatusBlock()
        updateControls()
        folderExecutor.execute {
            runOnUiThread {
                if (activityDestroyed) return@runOnUiThread
                folderScanQueued = false
                scanning = true
                scanMessage = "Scanning folder"
                renderStatusBlock()
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
                    scanMessage = "Scan complete: $count audio files"
                    refreshCatalog(startupGeneration)
                }
            }.onFailure { error ->
                runOnUiThread {
                    if (activityDestroyed) return@runOnUiThread
                    folderScanQueued = false
                    scanning = false
                    startupGeneration?.let(startupCoordinator::onStartupScanFailed)
                    evaluateStartupProcessing()
                    showError(error.message ?: "Folder scan failed")
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
            selectedTab = selectedTab,
        )
        val startupGenerationForRequest = pendingStartupCatalogGeneration
        folderExecutor.execute {
            val newEntries = catalog.newEntries(token.sourceScope)
            val processedEntries = catalog.processedEntries(token.sourceScope)
            val entries = if (token.selectedTab == MainScreenCatalogTab.NEW) {
                newEntries
            } else {
                processedEntries
            }
            runOnUiThread {
                if (activityDestroyed) return@runOnUiThread
                if (
                    !CatalogRefreshPolicy.isCurrent(
                        request = token,
                        currentGeneration = catalogRefreshGeneration,
                        currentSourceScope = activeSourceScope(),
                        currentTab = selectedTab,
                    )
                ) return@runOnUiThread
                pendingCount = newEntries.count { it.state == AudioFileState.PENDING }
                importInboxAvailable = (newEntries + processedEntries).any {
                    it.folderUri == AndroidAudioImportConstants.SOURCE_ID
                }
                val catalogFailedCount = processedEntries.count { it.state == AudioFileState.FAILED }
                renderEntries(entries)
                renderStatusBlock()
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
        val state = currentMainScreenState(entries)
        fileList.removeAllViews()
        emptyList.visibility = if (state.list.emptyVisible) View.VISIBLE else View.GONE
        emptyList.text = state.list.emptyMessage
        newTab.isSelected = state.tabs.newSelected
        processedTab.isSelected = state.tabs.processedSelected
        newTab.isEnabled = state.tabs.newEnabled
        processedTab.isEnabled = state.tabs.processedEnabled
        entries.zip(state.rows).forEach { (entry, rowState) ->
            fileList.addView(createEntryView(entry, rowState))
        }
    }

    private fun createEntryView(entry: AudioCatalogEntry, rowState: MainScreenRowState): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            contentDescription = entry.displayName
            isLongClickable = true
            setOnLongClickListener { anchor ->
                showEntryContextMenu(anchor, entry)
            }

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                )
                addView(TextView(context).apply {
                    text = entry.displayName
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(context).apply {
                    text = entryDescription(entry)
                })
            })
            val actions = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginStart = dp(12)
                }
            }
            actions.addView(Button(context).apply {
                tag = PreviewButtonTag(entry.id)
                text = rowState.preview.label
                isEnabled = rowState.preview.enabled
                setOnClickListener {
                    if (entry.id == previewEntryId && previewState != PreviewPlaybackState.IDLE) {
                        stopPreviewPlayback(render = true)
                    } else {
                        startPreviewPlayback(entry)
                    }
                }
            })
            if (entry.state == AudioFileState.FAILED) {
                actions.addView(Button(context).apply {
                    tag = RetryButtonTag(entry.id)
                    text = "Retry"
                    isEnabled = rowState.retryEnabled
                    setOnClickListener {
                        retryEntry(entry)
                    }
                })
            }
            addView(actions)
        }

    private fun showEntryContextMenu(anchor: View, entry: AudioCatalogEntry): Boolean {
        val popup = PopupMenu(this, anchor)
        val row = MainScreenStateController.rowState(
            row = entry.toMainScreenRowInput(),
            activePreviewEntryId = previewEntryId,
            previewState = previewState,
            transcriptionState = transcriptionState,
            busy = folderBusy(),
            retryEnabled = currentControls().retryEnabled,
        )
        if (row.preview.enabled) {
            popup.menu.add(Menu.NONE, MENU_ENTRY_PLAY, Menu.NONE, row.preview.label)
        }
        if (row.retryVisible && row.retryEnabled) {
            popup.menu.add(Menu.NONE, MENU_ENTRY_RETRY, Menu.NONE, "Retry")
        }
        if (row.showTextVisible) {
            popup.menu.add(Menu.NONE, MENU_ENTRY_SHOW_TEXT, Menu.NONE, "Show text")
        }
        if (popup.menu.size() == 0) return false
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_ENTRY_PLAY -> {
                    if (entry.id == previewEntryId && previewState != PreviewPlaybackState.IDLE) {
                        stopPreviewPlayback(render = true)
                    } else {
                        startPreviewPlayback(entry)
                    }
                    true
                }
                MENU_ENTRY_RETRY -> {
                    retryEntry(entry)
                    true
                }
                MENU_ENTRY_SHOW_TEXT -> {
                    showTranscriptText(entry)
                    true
                }
                else -> false
            }
        }
        popup.show()
        return true
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

    private fun entryDescription(entry: AudioCatalogEntry): String = buildString {
        append(
            when (entry.state) {
                AudioFileState.PENDING -> "New"
                AudioFileState.PROCESSING -> "Processing"
                AudioFileState.PROCESSED -> "Processed"
                AudioFileState.FAILED -> "Failed"
                AudioFileState.MISSING -> "Missing"
            },
        )
        entry.fingerprint.sizeBytes?.let { append(" • ${formatSize(it)}") }
        entry.lastError?.let { append("\n$it") }
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
                setModelUi("Checking speech model", loading = true, canDownload = false)
            }
            is SpeechModelReadinessState.Ready -> {
                modelReady = true
                setModelUi("Speech model ready", loading = false, canDownload = false)
            }
            SpeechModelReadinessState.Missing -> {
                modelReady = false
                setModelUi("Speech model is not installed", loading = false, canDownload = true)
            }
            is SpeechModelReadinessState.Invalid -> {
                modelReady = false
                setModelUi("Invalid speech model: ${state.reason}", loading = false, canDownload = true)
            }
            is SpeechModelReadinessState.Failed -> {
                modelReady = false
                setModelUi(state.message, loading = false, canDownload = true)
            }
        }
        startupCoordinator.setModelReady(modelReady)
        evaluateStartupProcessing()
    }

    private fun setModelUi(message: String, loading: Boolean, canDownload: Boolean) {
        modelMessage = message
        modelLoading = loading
        modelDownloadAvailable = canDownload
        modelDownloadProgress = null
        renderStatusBlock()
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
                        startupCoordinator.setModelReady(false)
                        val bytes = info.progress.getLong(SpeechModelInstallationWork.KEY_BYTES_DOWNLOADED, 0)
                        val total = info.progress.getLong(
                            SpeechModelInstallationWork.KEY_TOTAL_BYTES,
                            EmbeddedSpeechModel.manifest.totalSizeBytes,
                        )
                        modelMessage =
                            info.progress.getString(SpeechModelInstallationWork.KEY_MESSAGE)
                                ?: "Installing speech model"
                        modelLoading = true
                        modelDownloadAvailable = false
                        modelDownloadProgress = ((bytes * 100) / total.coerceAtLeast(1)).toInt()
                        renderStatusBlock()
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
                        startupCoordinator.setModelReady(false)
                        setModelUi(
                            info.outputData.getString(SpeechModelInstallationWork.KEY_ERROR)
                                ?: "Speech model installation failed",
                            false,
                            true,
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
                    renderStatusBlock()
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
                    renderStatusBlock()
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
                    statusError = null
                }
                renderStatusBlock()
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

    private fun currentControls(): CatalogControlState =
        currentMainScreenState().controls

    private fun folderBusy(): Boolean = folderChecking || folderScanQueued || scanning

    private fun launchOutputPickerIfEnabled() {
        if (currentControls().outputEnabled) {
            outputPicker.launch(FileSelectionRules.outputMimeTypes)
        }
    }

    private fun launchFolderPickerIfEnabled() {
        if (currentControls().folderEnabled) {
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
        transcriptionIndeterminate = true
        transcriptionProgressValue = 0
        processedUs = -1L
        durationUs = -1L
        completedFiles = 0
        totalFiles = 0
        failedFiles = 0
    }

    private fun updateControls() {
        val state = currentMainScreenState()
        val controls = state.controls
        selectOutput.visibility = if (controls.outputSetupVisible) View.VISIBLE else View.GONE
        selectOutput.isEnabled = controls.outputEnabled
        selectFolder.visibility = if (controls.folderSetupVisible) View.VISIBLE else View.GONE
        selectFolder.isEnabled = controls.folderEnabled
        importAudio.isEnabled = !ingestionActive
        importAudio.text = getString(if (ingestionActive) R.string.importing_audio else R.string.import_audio)
        transcribeAll.visibility = if (state.list.transcribeAllVisible) View.VISIBLE else View.GONE
        transcribeAll.isEnabled = state.list.transcribeAllEnabled
        for (index in 0 until fileList.childCount) {
            val row = fileList.getChildAt(index) as? LinearLayout ?: continue
            for (childIndex in 0 until row.childCount) {
                val actions = row.getChildAt(childIndex) as? LinearLayout ?: continue
                for (buttonIndex in 0 until actions.childCount) {
                    val button = actions.getChildAt(buttonIndex) as? Button ?: continue
                    when (val tag = button.tag) {
                        is RetryButtonTag -> {
                            val retry = MainScreenStateController.rowState(
                                row = MainScreenRowInput(
                                    entryId = tag.entryId,
                                    state = AudioFileState.FAILED,
                                    hasTranscriptText = false,
                                ),
                                activePreviewEntryId = previewEntryId,
                                previewState = previewState,
                                transcriptionState = transcriptionState,
                                busy = folderBusy(),
                                retryEnabled = controls.retryEnabled,
                            )
                            button.isEnabled = retry.retryEnabled
                        }
                        is PreviewButtonTag -> {
                            val preview = MainScreenStateController.rowState(
                                row = MainScreenRowInput(
                                    entryId = tag.entryId,
                                    state = AudioFileState.PENDING,
                                    hasTranscriptText = false,
                                ),
                                activePreviewEntryId = previewEntryId,
                                previewState = previewState,
                                transcriptionState = transcriptionState,
                                busy = folderBusy(),
                                retryEnabled = controls.retryEnabled,
                            ).preview
                            button.text = preview.label
                            button.isEnabled = preview.enabled
                        }
                    }
                }
            }
        }
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

    private fun renderStatusBlock() {
        val state = currentMainScreenState().status
        statusTitle.text = state.title
        setOptionalText(statusDetail, state.detail)
        setOptionalText(statusMeta, state.meta)
        statusProgress.visibility = if (state.progressVisible) View.VISIBLE else View.GONE
        statusProgress.isIndeterminate = state.progressIndeterminate
        statusProgress.progress = state.progress
        downloadModel.visibility = if (state.downloadVisible) View.VISIBLE else View.GONE
        importModel.visibility = if (state.downloadVisible) View.VISIBLE else View.GONE
        importModel.isEnabled = modelDownloadAvailable && !modelLoading
    }

    private fun setOptionalText(view: TextView, value: String?) {
        view.text = value.orEmpty()
        view.visibility = if (value.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun updateMenu(menu: Menu) {
        val controls = currentMainScreenState().controls
        menu.findItem(R.id.menuRefreshFolder)?.isEnabled = controls.refreshEnabled
    }

    private fun updateOutputSummary(displayName: String?) {
        outputName.text = if (displayName.isNullOrBlank()) {
            getString(R.string.output_not_selected)
        } else {
            getString(R.string.output_selected, displayName)
        }
    }

    private fun updateFolderSummary(displayName: String?) {
        folderName.text = if (displayName.isNullOrBlank()) {
            getString(R.string.folder_not_selected)
        } else {
            getString(R.string.folder_selected, displayName)
        }
    }

    private fun showError(message: String) {
        runOnUiThread {
            if (activityDestroyed) return@runOnUiThread
            statusError = message
            renderStatusBlock()
            updateControls()
        }
    }

    private fun formatSize(bytes: Long): String =
        if (bytes < 1024 * 1024) "${bytes / 1024} KiB" else "${bytes / (1024 * 1024)} MiB"

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun currentMainScreenState(
        entries: List<AudioCatalogEntry> = emptyList(),
    ): MainScreenState =
        MainScreenStateController.state(
            MainScreenInput(
                modelMessage = modelMessage,
                modelInstallationState = when {
                    modelLoading -> SpeechModelInstallationState.INSTALLING
                    modelReady -> SpeechModelInstallationState.INSTALLED
                    modelDownloadAvailable -> SpeechModelInstallationState.NOT_INSTALLED
                    else -> SpeechModelInstallationState.INVALID
                },
                modelRuntimeState = if (
                    transcriptionState == TranscriptionObservationState.ACTIVE &&
                    transcriptionPhase?.contains("model", ignoreCase = true) == true
                ) SpeechModelRuntimeState.LOADING else SpeechModelRuntimeState.UNLOADED,
                modelDownloadAvailable = modelDownloadAvailable,
                modelDownloadProgress = modelDownloadProgress,
                outputSelected = outputUri != null,
                folderSelected = folderUri != null,
                outputReady = outputAccessReady,
                folderReady = folderAccessReady,
                pendingCount = pendingCount,
                folderChecking = folderChecking,
                folderScanQueued = folderScanQueued,
                scanning = scanning,
                scanMessage = scanMessage,
                transcriptionState = transcriptionState,
                transcriptionPhase = transcriptionPhase,
                transcriptionFilename = transcriptionFilename,
                transcriptionIndeterminate = transcriptionIndeterminate,
                transcriptionProgress = transcriptionProgressValue,
                processedUs = processedUs,
                durationUs = durationUs,
                completedFiles = completedFiles,
                totalFiles = totalFiles,
                failedFiles = failedFiles,
                errorMessage = statusError ?: outputAccessError ?: folderAccessError,
                selectedTab = selectedTab,
                displayedRowCount = entries.size,
                activePreviewEntryId = previewEntryId,
                previewState = previewState,
                rows = entries.map { it.toMainScreenRowInput() },
                importInboxAvailable = importInboxAvailable,
            ),
        )

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

    private fun AudioCatalogEntry.toMainScreenRowInput(): MainScreenRowInput =
        MainScreenRowInput(
            entryId = id,
            state = state,
            hasTranscriptText = !transcriptText.isNullOrBlank(),
        )

    private data class PreviewButtonTag(val entryId: Long)
    private data class RetryButtonTag(val entryId: Long)

    private enum class FolderScanOrigin {
        STARTUP,
        USER,
    }

    private companion object {
        const val MENU_ENTRY_PLAY = 1
        const val MENU_ENTRY_RETRY = 2
        const val MENU_ENTRY_SHOW_TEXT = 3
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
