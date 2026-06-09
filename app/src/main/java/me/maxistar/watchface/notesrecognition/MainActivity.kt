package me.maxistar.watchface.notesrecognition

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
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
    private lateinit var modelStatus: TextView
    private lateinit var modelProgress: ProgressBar
    private lateinit var downloadModel: Button
    private lateinit var selectOutput: Button
    private lateinit var selectAudio: Button
    private lateinit var outputName: TextView
    private lateinit var audioName: TextView
    private lateinit var transcriptionStatus: TextView
    private lateinit var transcriptionProgress: ProgressBar
    private lateinit var progressDetails: TextView

    private lateinit var modelRepository: SpeechModelRepository
    private lateinit var selectionStore: DocumentSelectionStore
    private lateinit var documentAccess: DocumentAccess
    private val executor = Executors.newSingleThreadExecutor()

    private var modelReady = false
    private var outputUri: Uri? = null
    private var transcriptionActive = false

    private val outputPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) acceptOutput(uri)
    }

    private val audioPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) acceptAudio(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        bindViews()

        modelRepository = SpeechModelRepository(noBackupFilesDir.resolve("models"))
        selectionStore = DocumentSelectionStore(
            getSharedPreferences(DocumentSelectionStore.PREFERENCES_NAME, MODE_PRIVATE),
        )
        documentAccess = DocumentAccess(contentResolver)

        downloadModel.setOnClickListener { SpeechModelDownloadWorker.enqueue(this) }
        selectOutput.setOnClickListener { outputPicker.launch(FileSelectionRules.outputMimeTypes) }
        selectAudio.setOnClickListener { audioPicker.launch(SUPPORTED_AUDIO_MIME_TYPES) }

        observeModelInstallation()
        observeTranscription()
        restoreOutputSelection()
        refreshModel()
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    private fun bindViews() {
        modelStatus = findViewById(R.id.modelStatus)
        modelProgress = findViewById(R.id.modelProgress)
        downloadModel = findViewById(R.id.downloadModel)
        selectOutput = findViewById(R.id.selectOutput)
        selectAudio = findViewById(R.id.selectAudio)
        outputName = findViewById(R.id.outputName)
        audioName = findViewById(R.id.audioName)
        transcriptionStatus = findViewById(R.id.transcriptionStatus)
        transcriptionProgress = findViewById(R.id.transcriptionProgress)
        progressDetails = findViewById(R.id.progressDetails)
    }

    private fun refreshModel() {
        setModelUi("Checking speech model", loading = true, canDownload = false)
        executor.execute {
            modelRepository.cleanupStaleState()
            when (val state = modelRepository.inspect()) {
                is InstalledSpeechModelState.Ready -> {
                    val loaded = runCatching {
                        NativeTranscriptionBridge.initialize(state.directory.absolutePath)
                    }.getOrDefault(false)
                    runOnUiThread {
                        modelReady = loaded
                        setModelUi(
                            if (loaded) "Speech model ready" else "Speech model failed to load",
                            loading = false,
                            canDownload = !loaded,
                        )
                        updateFileControls()
                    }
                }
                InstalledSpeechModelState.Missing -> runOnUiThread {
                    modelReady = false
                    setModelUi("Speech model is not installed", false, true)
                    updateFileControls()
                }
                is InstalledSpeechModelState.Invalid -> runOnUiThread {
                    modelReady = false
                    setModelUi("Invalid speech model: ${state.reason}", false, true)
                    updateFileControls()
                }
            }
        }
    }

    private fun setModelUi(message: String, loading: Boolean, canDownload: Boolean) {
        modelStatus.text = message
        modelProgress.visibility = if (loading) View.VISIBLE else View.GONE
        modelProgress.isIndeterminate = loading
        downloadModel.visibility = if (canDownload) View.VISIBLE else View.GONE
    }

    private fun restoreOutputSelection() {
        val stored = selectionStore.loadOutputUri()?.let(Uri::parse) ?: return
        executor.execute {
            runCatching {
                documentAccess.requireAppendable(stored)
                documentAccess.metadata(stored)
            }.onSuccess { metadata ->
                runOnUiThread {
                    outputUri = stored
                    outputName.text = metadata.displayName
                    updateFileControls()
                }
            }.onFailure {
                selectionStore.clearOutputUri()
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
        executor.execute {
            runCatching {
                documentAccess.requireAppendable(uri)
                documentAccess.metadata(uri)
            }.onSuccess { metadata ->
                selectionStore.saveOutputUri(uri.toString())
                runOnUiThread {
                    outputUri = uri
                    outputName.text = metadata.displayName
                    transcriptionStatus.text = "Ready"
                    updateFileControls()
                }
            }.onFailure { showError(it.message ?: "Output file is not writable") }
        }
    }

    private fun acceptAudio(uri: Uri) {
        val destination = outputUri ?: return
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        executor.execute {
            runCatching {
                documentAccess.requireReadable(uri)
                documentAccess.metadata(uri)
            }.onSuccess { metadata ->
                runOnUiThread {
                    audioName.text = metadata.displayName
                    transcriptionStatus.text = "Queued"
                    TranscriptionWorker.enqueue(this, uri, destination, metadata)
                }
            }.onFailure { showError(it.message ?: "Audio file is not readable") }
        }
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
                        modelStatus.text =
                            info.progress.getString(SpeechModelDownloadWorker.KEY_MESSAGE)
                                ?: "Downloading speech model"
                        modelProgress.visibility = View.VISIBLE
                        modelProgress.isIndeterminate = false
                        modelProgress.progress = ((bytes * 100) / total.coerceAtLeast(1)).toInt()
                        downloadModel.visibility = View.GONE
                        updateFileControls()
                    }
                    WorkInfo.State.SUCCEEDED -> refreshModel()
                    WorkInfo.State.FAILED -> {
                        modelReady = false
                        setModelUi(
                            info.outputData.getString(SpeechModelDownloadWorker.KEY_ERROR)
                                ?: "Speech model download failed",
                            false,
                            true,
                        )
                        updateFileControls()
                    }
                    else -> Unit
                }
            }
    }

    private fun observeTranscription() {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(TranscriptionWorker.UNIQUE_WORK_NAME)
            .observe(this) { infos ->
                val info = infos.firstOrNull() ?: return@observe
                transcriptionActive = info.state in setOf(
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.BLOCKED,
                    WorkInfo.State.RUNNING,
                )
                val data = if (info.state.isFinished) info.outputData else info.progress
                transcriptionStatus.text = data.getString(TranscriptionWorker.KEY_PHASE)
                    ?: when (info.state) {
                        WorkInfo.State.ENQUEUED -> "Queued"
                        WorkInfo.State.RUNNING -> "Transcribing"
                        WorkInfo.State.SUCCEEDED -> "Completed"
                        WorkInfo.State.FAILED -> data.getString(TranscriptionWorker.KEY_ERROR) ?: "Failed"
                        else -> info.state.name
                    }
                val indeterminate = data.getBoolean(TranscriptionWorker.KEY_INDETERMINATE, true)
                transcriptionProgress.visibility =
                    if (transcriptionActive || info.state.isFinished) View.VISIBLE else View.GONE
                transcriptionProgress.isIndeterminate = transcriptionActive && indeterminate
                transcriptionProgress.progress = data.getInt(TranscriptionWorker.KEY_PROGRESS, 0)
                val processed = data.getLong(TranscriptionWorker.KEY_PROCESSED_US, -1L)
                val duration = data.getLong(TranscriptionWorker.KEY_DURATION_US, -1L)
                progressDetails.text = if (processed >= 0 && duration > 0) {
                    "${formatDuration(processed)} / ${formatDuration(duration)}"
                } else {
                    ""
                }
                updateFileControls()
            }
    }

    private fun updateFileControls() {
        val controls = TranscriptionUiRules.fileControls(
            modelReady = modelReady,
            outputSelected = outputUri != null,
            transcriptionActive = transcriptionActive,
        )
        selectOutput.isEnabled = controls.outputEnabled
        selectAudio.isEnabled = controls.audioEnabled
    }

    private fun showError(message: String) {
        runOnUiThread {
            transcriptionStatus.text = message
            updateFileControls()
        }
    }

    private fun formatDuration(microseconds: Long): String {
        val totalSeconds = microseconds / 1_000_000
        return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    companion object {
        private val SUPPORTED_AUDIO_MIME_TYPES = arrayOf(
            "audio/*",
            "video/mp4",
        )
    }
}
