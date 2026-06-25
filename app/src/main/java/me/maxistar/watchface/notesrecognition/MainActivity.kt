package me.maxistar.watchface.notesrecognition

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
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var statusProgress: ProgressBar
    private lateinit var statusMeta: TextView
    private lateinit var downloadModel: Button
    private lateinit var transcribeAll: Button
    private lateinit var outputName: TextView
    private lateinit var folderName: TextView
    private lateinit var newTab: Button
    private lateinit var processedTab: Button
    private lateinit var fileList: LinearLayout
    private lateinit var emptyList: TextView

    private lateinit var selectionStore: DocumentSelectionStore
    private lateinit var documentAccess: DocumentAccess
    private lateinit var folderScanner: AudioFolderScanner
    private lateinit var catalogDatabase: AudioCatalogDatabase
    private lateinit var catalog: AudioCatalogRepository
    private lateinit var modelReadiness: SpeechModelReadinessManager
    private val folderExecutor = Executors.newSingleThreadExecutor()

    private var modelReady = false
    private var outputUri: Uri? = null
    private var folderUri: Uri? = null
    private var transcriptionActive = false
    private var folderChecking = false
    private var folderScanQueued = false
    private var scanning = false
    private var pendingCount = 0
    private var selectedTab = CatalogTab.NEW
    private var lastCatalogWorkState: String? = null
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
        documentAccess = DocumentAccess(contentResolver)
        folderScanner = AudioFolderScanner(contentResolver)
        catalogDatabase = AudioCatalogDatabase(this)
        catalog = AudioCatalogRepository(catalogDatabase)
        modelReadiness = getSharedModelReadiness(SpeechModelRepository(noBackupFilesDir.resolve("models")))

        downloadModel.setOnClickListener { SpeechModelDownloadWorker.enqueue(this) }
        transcribeAll.setOnClickListener {
            val folder = folderUri ?: return@setOnClickListener
            val output = outputUri ?: return@setOnClickListener
            stopPreviewPlayback(render = true)
            TranscriptionWorker.enqueueAll(this, folder, output)
        }
        newTab.setOnClickListener {
            selectedTab = CatalogTab.NEW
            refreshCatalog()
        }
        processedTab.setOnClickListener {
            selectedTab = CatalogTab.PROCESSED
            refreshCatalog()
        }

        observeModelInstallation()
        observeTranscription()
        restoreSelections()
        refreshModel()
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
            R.id.menuSelectOutput -> {
                if (currentControls().outputEnabled) {
                    outputPicker.launch(FileSelectionRules.outputMimeTypes)
                }
                true
            }
            R.id.menuSelectFolder -> {
                if (currentControls().folderEnabled) {
                    folderPicker.launch(folderUri)
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    override fun onDestroy() {
        activityDestroyed = true
        stopPreviewPlayback(render = false)
        folderExecutor.shutdown()
        super.onDestroy()
    }

    private fun bindViews() {
        statusTitle = findViewById(R.id.statusTitle)
        statusDetail = findViewById(R.id.statusDetail)
        statusProgress = findViewById(R.id.statusProgress)
        statusMeta = findViewById(R.id.statusMeta)
        downloadModel = findViewById(R.id.downloadModel)
        transcribeAll = findViewById(R.id.transcribeAll)
        outputName = findViewById(R.id.outputName)
        folderName = findViewById(R.id.folderName)
        newTab = findViewById(R.id.newTab)
        processedTab = findViewById(R.id.processedTab)
        fileList = findViewById(R.id.fileList)
        emptyList = findViewById(R.id.emptyList)
    }

    private fun restoreSelections() {
        selectionStore.loadOutputUri()?.let(Uri::parse)?.let { stored ->
            folderExecutor.execute {
                runCatching {
                    documentAccess.requireAppendable(stored)
                    documentAccess.metadata(stored)
                }.onSuccess { metadata ->
                    runOnUiThread {
                        if (activityDestroyed) return@runOnUiThread
                        outputUri = stored
                        updateOutputSummary(metadata.displayName)
                        updateControls()
                    }
                }.onFailure {
                    selectionStore.clearOutputUri()
                }
            }
        }
        selectionStore.loadFolderUri()?.let(Uri::parse)?.let { stored ->
            folderExecutor.execute {
                runOnUiThread {
                    if (activityDestroyed) return@runOnUiThread
                    folderChecking = true
                    renderStatusBlock()
                    updateControls()
                }
                runCatching {
                    folderScanner.requireReadable(stored)
                    folderScanner.folderName(stored)
                }.onSuccess { name ->
                    runOnUiThread {
                        if (activityDestroyed) return@runOnUiThread
                        folderChecking = false
                        folderUri = stored
                        updateFolderSummary(name)
                        updateControls()
                        scanFolder()
                    }
                }.onFailure {
                    selectionStore.clearFolderUri()
                    runOnUiThread {
                        if (activityDestroyed) return@runOnUiThread
                        folderChecking = false
                    }
                    showError(it.message ?: "Audio folder is not readable")
                }
            }
        }
    }

    private fun acceptOutput(uri: Uri) {
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
                selectionStore.saveOutputUri(uri.toString())
                runOnUiThread {
                    if (activityDestroyed) return@runOnUiThread
                    outputUri = uri
                    updateOutputSummary(metadata.displayName)
                    statusError = null
                    renderStatusBlock()
                    updateControls()
                }
            }.onFailure { showError(it.message ?: "Output file is not writable") }
        }
    }

    private fun acceptFolder(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        folderExecutor.execute {
            runOnUiThread {
                if (activityDestroyed) return@runOnUiThread
                folderChecking = true
                renderStatusBlock()
                updateControls()
            }
            runCatching {
                folderScanner.requireReadable(uri)
                folderScanner.folderName(uri)
            }.onSuccess { name ->
                selectionStore.saveFolderUri(uri.toString())
                runOnUiThread {
                    if (activityDestroyed) return@runOnUiThread
                    folderChecking = false
                    folderUri = uri
                    updateFolderSummary(name)
                    statusError = null
                    renderStatusBlock()
                    updateControls()
                    scanFolder()
                }
            }.onFailure {
                runOnUiThread {
                    if (activityDestroyed) return@runOnUiThread
                    folderChecking = false
                }
                showError(it.message ?: "Audio folder is not readable")
            }
        }
    }

    private fun scanFolder() {
        val folder = folderUri ?: return
        if (folderChecking || folderScanQueued || scanning || transcriptionActive) return
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
                    refreshCatalog()
                }
            }.onFailure { error ->
                runOnUiThread {
                    if (activityDestroyed) return@runOnUiThread
                    folderScanQueued = false
                    scanning = false
                    showError(error.message ?: "Folder scan failed")
                }
            }
        }
    }

    private fun refreshCatalog() {
        if (activityDestroyed) return
        val folder = folderUri?.toString()
        if (folder == null) {
            pendingCount = 0
            renderEntries(emptyList())
            renderStatusBlock()
            updateControls()
            return
        }
        folderExecutor.execute {
            val newEntries = catalog.newEntries(folder)
            val entries = if (selectedTab == CatalogTab.NEW) {
                newEntries
            } else {
                catalog.processedEntries(folder)
            }
            runOnUiThread {
                if (activityDestroyed) return@runOnUiThread
                pendingCount = newEntries.count { it.state == AudioFileState.PENDING }
                renderEntries(entries)
                renderStatusBlock()
                updateControls()
            }
        }
    }

    private fun renderEntries(entries: List<AudioCatalogEntry>) {
        fileList.removeAllViews()
        emptyList.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        emptyList.text = if (selectedTab == CatalogTab.NEW) {
            "No new audio files"
        } else {
            "No processed audio files"
        }
        newTab.isSelected = selectedTab == CatalogTab.NEW
        processedTab.isSelected = selectedTab == CatalogTab.PROCESSED
        newTab.isEnabled = selectedTab != CatalogTab.NEW
        processedTab.isEnabled = selectedTab != CatalogTab.PROCESSED
        entries.forEach { entry -> fileList.addView(createEntryView(entry)) }
    }

    private fun createEntryView(entry: AudioCatalogEntry): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))

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
                val control = TranscriptionUiRules.previewControl(
                    entryId = entry.id,
                    activeEntryId = previewEntryId,
                    playbackState = previewState,
                    transcriptionActive = transcriptionActive,
                    scanning = folderBusy(),
                )
                text = control.label
                isEnabled = control.enabled
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
                    tag = RETRY_BUTTON_TAG
                    text = "Retry"
                    isEnabled = currentControls().retryEnabled
                    setOnClickListener {
                        val folder = folderUri ?: return@setOnClickListener
                        val output = outputUri ?: return@setOnClickListener
                        stopPreviewPlayback(render = true)
                        TranscriptionWorker.enqueueRetry(
                            this@MainActivity,
                            folder,
                            output,
                            entry.id,
                        )
                    }
                })
            }
            addView(actions)
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
            .getWorkInfosForUniqueWorkLiveData(SpeechModelDownloadWorker.UNIQUE_WORK_NAME)
            .observe(this) { infos ->
                val info = infos.firstOrNull() ?: return@observe
                when (info.state) {
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.BLOCKED,
                    WorkInfo.State.RUNNING,
                    -> {
                        modelReady = false
                        val bytes = info.progress.getLong(SpeechModelDownloadWorker.KEY_BYTES_DOWNLOADED, 0)
                        val total = info.progress.getLong(
                            SpeechModelDownloadWorker.KEY_TOTAL_BYTES,
                            EmbeddedSpeechModel.manifest.totalSizeBytes,
                        )
                        modelMessage =
                            info.progress.getString(SpeechModelDownloadWorker.KEY_MESSAGE)
                                ?: "Downloading speech model"
                        modelLoading = true
                        modelDownloadAvailable = false
                        modelDownloadProgress = ((bytes * 100) / total.coerceAtLeast(1)).toInt()
                        renderStatusBlock()
                        updateControls()
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        if (shouldHandleModelInstallSuccess(info.id.toString())) {
                            modelReadiness.invalidate()
                        }
                        refreshModel()
                    }
                    WorkInfo.State.FAILED -> {
                        modelReady = false
                        setModelUi(
                            info.outputData.getString(SpeechModelDownloadWorker.KEY_ERROR)
                                ?: "Speech model download failed",
                            false,
                            true,
                        )
                        updateControls()
                    }
                    else -> Unit
                }
            }
    }

    private fun observeTranscription() {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(TranscriptionWorker.UNIQUE_WORK_NAME)
            .observe(this) { infos ->
                val info = infos.firstOrNull {
                    it.state in setOf(
                        WorkInfo.State.ENQUEUED,
                        WorkInfo.State.BLOCKED,
                        WorkInfo.State.RUNNING,
                    )
                } ?: infos.firstOrNull() ?: return@observe
                transcriptionActive = info.state in setOf(
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.BLOCKED,
                    WorkInfo.State.RUNNING,
                )
                if (transcriptionActive && previewState != PreviewPlaybackState.IDLE) {
                    stopPreviewPlayback(render = true)
                }
                transcriptionFinished = info.state.isFinished
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
                    transcriptionActive && data.getBoolean(TranscriptionWorker.KEY_INDETERMINATE, true)
                transcriptionProgressValue = data.getInt(TranscriptionWorker.KEY_PROGRESS, 0)
                processedUs = data.getLong(TranscriptionWorker.KEY_PROCESSED_US, -1L)
                durationUs = data.getLong(TranscriptionWorker.KEY_DURATION_US, -1L)
                completedFiles = data.getInt(TranscriptionWorker.KEY_COMPLETED_FILES, 0)
                totalFiles = data.getInt(TranscriptionWorker.KEY_TOTAL_FILES, 0)
                failedFiles = data.getInt(TranscriptionWorker.KEY_FAILED_FILES, 0)
                if (transcriptionActive || transcriptionFinished) {
                    scanMessage = null
                    statusError = null
                }
                renderStatusBlock()
                val catalogState = "${info.id}:${info.state}:$completedFiles:$failedFiles"
                if (catalogState != lastCatalogWorkState) {
                    lastCatalogWorkState = catalogState
                    refreshCatalog()
                } else {
                    updateControls()
                }
            }
    }

    private fun currentControls(): CatalogControlState =
        TranscriptionUiRules.catalogControls(
            modelReady = modelReady,
            outputSelected = outputUri != null,
            folderSelected = folderUri != null,
            pendingCount = pendingCount,
            transcriptionActive = transcriptionActive,
            scanning = folderBusy(),
        )

    private fun folderBusy(): Boolean = folderChecking || folderScanQueued || scanning

    private fun updateControls() {
        val controls = currentControls()
        transcribeAll.visibility = View.VISIBLE
        transcribeAll.isEnabled = controls.transcribeAllEnabled
        for (index in 0 until fileList.childCount) {
            val row = fileList.getChildAt(index) as? LinearLayout ?: continue
            for (childIndex in 0 until row.childCount) {
                val actions = row.getChildAt(childIndex) as? LinearLayout ?: continue
                for (buttonIndex in 0 until actions.childCount) {
                    val button = actions.getChildAt(buttonIndex) as? Button ?: continue
                    when (val tag = button.tag) {
                        RETRY_BUTTON_TAG -> button.isEnabled = controls.retryEnabled
                        is PreviewButtonTag -> {
                            val preview = TranscriptionUiRules.previewControl(
                                entryId = tag.entryId,
                                activeEntryId = previewEntryId,
                                playbackState = previewState,
                                transcriptionActive = transcriptionActive,
                                scanning = folderBusy(),
                            )
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
        if (folderBusy() || transcriptionActive) return
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
        val state = TranscriptionUiRules.statusProgressBlock(
            StatusProgressInput(
                modelMessage = modelMessage,
                modelLoading = modelLoading,
                modelDownloadAvailable = modelDownloadAvailable,
                modelDownloadProgress = modelDownloadProgress,
                modelReady = modelReady,
                outputSelected = outputUri != null,
                folderSelected = folderUri != null,
                pendingCount = pendingCount,
                folderChecking = folderChecking,
                scanning = scanning,
                scanMessage = scanMessage,
                transcriptionActive = transcriptionActive,
                transcriptionFinished = transcriptionFinished,
                transcriptionPhase = transcriptionPhase,
                transcriptionFilename = transcriptionFilename,
                transcriptionIndeterminate = transcriptionIndeterminate,
                transcriptionProgress = transcriptionProgressValue,
                processedUs = processedUs,
                durationUs = durationUs,
                completedFiles = completedFiles,
                totalFiles = totalFiles,
                failedFiles = failedFiles,
                errorMessage = statusError,
            ),
        )
        statusTitle.text = state.title
        setOptionalText(statusDetail, state.detail)
        setOptionalText(statusMeta, state.meta)
        statusProgress.visibility = if (state.progressVisible) View.VISIBLE else View.GONE
        statusProgress.isIndeterminate = state.progressIndeterminate
        statusProgress.progress = state.progress
        downloadModel.visibility = if (state.downloadVisible) View.VISIBLE else View.GONE
    }

    private fun setOptionalText(view: TextView, value: String?) {
        view.text = value.orEmpty()
        view.visibility = if (value.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun updateMenu(menu: Menu) {
        val controls = currentControls()
        menu.findItem(R.id.menuRefreshFolder)?.isEnabled = controls.refreshEnabled
        menu.findItem(R.id.menuSelectOutput)?.apply {
            isEnabled = controls.outputEnabled
            setTitle(if (outputUri == null) R.string.menu_select_output else R.string.menu_change_output)
        }
        menu.findItem(R.id.menuSelectFolder)?.apply {
            isEnabled = controls.folderEnabled
            setTitle(if (folderUri == null) R.string.menu_select_folder else R.string.menu_change_folder)
        }
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

    private enum class CatalogTab {
        NEW,
        PROCESSED,
    }

    private data class PreviewButtonTag(val entryId: Long)

    private companion object {
        const val RETRY_BUTTON_TAG = "retry"

        @Volatile
        private var sharedModelReadiness: SpeechModelReadinessManager? = null
        private val handledModelInstallSuccessIds = mutableSetOf<String>()

        fun getSharedModelReadiness(repository: SpeechModelRepository): SpeechModelReadinessManager =
            sharedModelReadiness ?: synchronized(this) {
                sharedModelReadiness ?: SpeechModelReadinessManager(
                    repository = repository,
                    initializeModel = { directory ->
                        NativeTranscriptionBridge.initialize(directory.absolutePath)
                    },
                    executor = Executors.newSingleThreadExecutor(),
                ).also { sharedModelReadiness = it }
            }

        fun shouldHandleModelInstallSuccess(id: String): Boolean =
            synchronized(this) { handledModelInstallSuccessIds.add(id) }
    }
}
