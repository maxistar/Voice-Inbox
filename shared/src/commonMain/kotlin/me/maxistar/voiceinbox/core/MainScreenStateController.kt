package me.maxistar.voiceinbox.core

enum class MainScreenCatalogTab {
    NEW,
    PROCESSED,
}

data class MainScreenRowInput(
    val entryId: Long,
    val state: AudioFileState,
    val hasTranscriptText: Boolean,
)

data class MainScreenInput(
    val modelMessage: String,
    val modelInstallationState: SpeechModelInstallationState,
    val modelRuntimeState: SpeechModelRuntimeState,
    val modelDownloadAvailable: Boolean,
    val modelDownloadProgress: Int?,
    val outputSelected: Boolean,
    val folderSelected: Boolean,
    val pendingCount: Int,
    val folderChecking: Boolean,
    val folderScanQueued: Boolean,
    val scanning: Boolean,
    val scanMessage: String?,
    val transcriptionState: TranscriptionObservationState,
    val transcriptionPhase: String?,
    val transcriptionFilename: String?,
    val transcriptionIndeterminate: Boolean,
    val transcriptionProgress: Int,
    val processedUs: Long,
    val durationUs: Long,
    val completedFiles: Int,
    val totalFiles: Int,
    val failedFiles: Int,
    val errorMessage: String?,
    val selectedTab: MainScreenCatalogTab,
    val displayedRowCount: Int,
    val activePreviewEntryId: Long?,
    val previewState: PreviewPlaybackState,
    val rows: List<MainScreenRowInput> = emptyList(),
)

data class MainScreenState(
    val status: StatusProgressBlockState,
    val controls: CatalogControlState,
    val tabs: MainScreenTabsState,
    val list: MainScreenListState,
    val rows: List<MainScreenRowState>,
)

data class MainScreenTabsState(
    val selectedTab: MainScreenCatalogTab,
    val newSelected: Boolean,
    val processedSelected: Boolean,
    val newEnabled: Boolean,
    val processedEnabled: Boolean,
)

data class MainScreenListState(
    val emptyVisible: Boolean,
    val emptyMessage: String,
    val transcribeAllVisible: Boolean,
    val transcribeAllEnabled: Boolean,
)

data class MainScreenRowState(
    val entryId: Long,
    val preview: PreviewControlState,
    val retryVisible: Boolean,
    val retryEnabled: Boolean,
    val showTextVisible: Boolean,
)

object MainScreenStateController {
    fun state(input: MainScreenInput): MainScreenState {
        val busy = input.folderChecking || input.folderScanQueued || input.scanning
        val controls = TranscriptionUiRules.catalogControls(
            modelInstallationState = input.modelInstallationState,
            outputSelected = input.outputSelected,
            folderSelected = input.folderSelected,
            pendingCount = input.pendingCount,
            transcriptionState = input.transcriptionState,
            scanning = busy,
        )
        return MainScreenState(
            status = TranscriptionUiRules.statusProgressBlock(input.toStatusInput()),
            controls = controls,
            tabs = tabs(input.selectedTab),
            list = listState(
                selectedTab = input.selectedTab,
                displayedRowCount = input.displayedRowCount,
                pendingCount = input.pendingCount,
                transcribeAllEnabled = controls.transcribeAllEnabled,
            ),
            rows = input.rows.map { row ->
                rowState(
                    row = row,
                    activePreviewEntryId = input.activePreviewEntryId,
                    previewState = input.previewState,
                    transcriptionState = input.transcriptionState,
                    busy = busy,
                    retryEnabled = controls.retryEnabled,
                )
            },
        )
    }

    fun rowState(
        row: MainScreenRowInput,
        activePreviewEntryId: Long?,
        previewState: PreviewPlaybackState,
        transcriptionState: TranscriptionObservationState,
        busy: Boolean,
        retryEnabled: Boolean,
    ): MainScreenRowState =
        MainScreenRowState(
            entryId = row.entryId,
            preview = TranscriptionUiRules.previewControl(
                entryId = row.entryId,
                activeEntryId = activePreviewEntryId,
                playbackState = previewState,
                transcriptionState = transcriptionState,
                scanning = busy,
            ),
            retryVisible = row.state == AudioFileState.FAILED,
            retryEnabled = row.state == AudioFileState.FAILED && retryEnabled,
            showTextVisible = row.state == AudioFileState.PROCESSED && row.hasTranscriptText,
        )

    private fun MainScreenInput.toStatusInput(): StatusProgressInput =
        StatusProgressInput(
            modelMessage = modelMessage,
            modelInstallationState = modelInstallationState,
            modelRuntimeState = modelRuntimeState,
            modelDownloadAvailable = modelDownloadAvailable,
            modelDownloadProgress = modelDownloadProgress,
            outputSelected = outputSelected,
            folderSelected = folderSelected,
            pendingCount = pendingCount,
            folderChecking = folderChecking,
            scanning = scanning,
            scanMessage = scanMessage,
            transcriptionState = transcriptionState,
            transcriptionPhase = transcriptionPhase,
            transcriptionFilename = transcriptionFilename,
            transcriptionIndeterminate = transcriptionIndeterminate,
            transcriptionProgress = transcriptionProgress,
            processedUs = processedUs,
            durationUs = durationUs,
            completedFiles = completedFiles,
            totalFiles = totalFiles,
            failedFiles = failedFiles,
            errorMessage = errorMessage,
        )

    private fun tabs(selectedTab: MainScreenCatalogTab): MainScreenTabsState =
        MainScreenTabsState(
            selectedTab = selectedTab,
            newSelected = selectedTab == MainScreenCatalogTab.NEW,
            processedSelected = selectedTab == MainScreenCatalogTab.PROCESSED,
            newEnabled = selectedTab != MainScreenCatalogTab.NEW,
            processedEnabled = selectedTab != MainScreenCatalogTab.PROCESSED,
        )

    private fun listState(
        selectedTab: MainScreenCatalogTab,
        displayedRowCount: Int,
        pendingCount: Int,
        transcribeAllEnabled: Boolean,
    ): MainScreenListState =
        MainScreenListState(
            emptyVisible = displayedRowCount == 0,
            emptyMessage = if (selectedTab == MainScreenCatalogTab.NEW) {
                "No new audio files"
            } else {
                "No processed audio files"
            },
            transcribeAllVisible = selectedTab == MainScreenCatalogTab.NEW && pendingCount > 0,
            transcribeAllEnabled = transcribeAllEnabled,
        )
}
