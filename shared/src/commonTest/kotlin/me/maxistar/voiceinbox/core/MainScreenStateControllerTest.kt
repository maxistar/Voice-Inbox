package me.maxistar.voiceinbox.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainScreenStateControllerTest {
    @Test
    fun setupControlsReflectMissingModelOutputAndFolder() {
        assertEquals(
            CatalogControlState(
                outputEnabled = false,
                folderEnabled = false,
                outputSetupVisible = true,
                folderSetupVisible = true,
                refreshEnabled = false,
                transcribeAllEnabled = false,
                retryEnabled = false,
            ),
            state(input(modelReady = false)).controls,
        )

        assertEquals(
            CatalogControlState(
                outputEnabled = true,
                folderEnabled = true,
                outputSetupVisible = false,
                folderSetupVisible = true,
                refreshEnabled = false,
                transcribeAllEnabled = false,
                retryEnabled = false,
            ),
            state(input(outputSelected = true)).controls,
        )

        assertEquals(
            CatalogControlState(
                outputEnabled = true,
                folderEnabled = true,
                outputSetupVisible = false,
                folderSetupVisible = false,
                refreshEnabled = true,
                transcribeAllEnabled = true,
                retryEnabled = true,
            ),
            state(input(outputSelected = true, folderSelected = true, pendingCount = 2)).controls,
        )
    }

    @Test
    fun controlsAreDisabledDuringActiveWorkOrQueuedScan() {
        assertFalse(
            state(
                input(
                    outputSelected = true,
                    folderSelected = true,
                    pendingCount = 2,
                    transcriptionState = TranscriptionObservationState.ACTIVE,
                ),
            ).controls.transcribeAllEnabled,
        )

        val queued = state(
            input(
                outputSelected = true,
                folderSelected = true,
                pendingCount = 2,
                folderScanQueued = true,
            ),
        )
        assertFalse(queued.controls.refreshEnabled)
        assertFalse(queued.controls.transcribeAllEnabled)
        assertEquals("Ready", queued.status.title)
    }

    @Test
    fun statusPriorityUsesExistingSharedRules() {
        assertEquals(
            "Checking speech model",
            state(
                input(
                    modelMessage = "Checking speech model",
                    modelLoading = true,
                    folderChecking = true,
                    scanning = true,
                ),
            ).status.title,
        )
        assertEquals(
            "Checking audio folder",
            state(input(outputSelected = true, folderSelected = true, folderChecking = true)).status.title,
        )
        assertEquals(
            "Scanning folder",
            state(
                input(
                    outputSelected = true,
                    folderSelected = true,
                    scanning = true,
                    scanMessage = "Scanning folder",
                    transcriptionState = TranscriptionObservationState.FINISHED,
                    transcriptionPhase = "Completed 2 of 2",
                    transcriptionProgress = 100,
                ),
            ).status.title,
        )
        assertEquals(
            "lesson.wav",
            state(
                input(
                    outputSelected = true,
                    folderSelected = true,
                    transcriptionState = TranscriptionObservationState.ACTIVE,
                    transcriptionPhase = "Transcribing",
                    transcriptionFilename = "lesson.wav",
                    transcriptionIndeterminate = false,
                    transcriptionProgress = 50,
                    processedUs = 5_000_000,
                    durationUs = 10_000_000,
                ),
            ).status.title,
        )
        assertEquals(
            "Completed 2 of 2",
            state(
                input(
                    outputSelected = true,
                    folderSelected = true,
                    transcriptionState = TranscriptionObservationState.FINISHED,
                    transcriptionPhase = "Completed 2 of 2",
                    transcriptionProgress = 100,
                    completedFiles = 2,
                    totalFiles = 2,
                ),
            ).status.title,
        )
        assertEquals(
            "Cannot read folder",
            state(input(errorMessage = "Cannot read folder")).status.title,
        )
    }

    @Test
    fun transcribeAllIsVisibleOnlyForNewTabWithPendingRows() {
        assertTrue(
            state(
                input(
                    selectedTab = MainScreenCatalogTab.NEW,
                    outputSelected = true,
                    folderSelected = true,
                    pendingCount = 1,
                ),
            ).list.transcribeAllVisible,
        )
        assertFalse(
            state(
                input(
                    selectedTab = MainScreenCatalogTab.PROCESSED,
                    outputSelected = true,
                    folderSelected = true,
                    pendingCount = 1,
                ),
            ).list.transcribeAllVisible,
        )
        assertFalse(
            state(
                input(
                    selectedTab = MainScreenCatalogTab.NEW,
                    outputSelected = true,
                    folderSelected = true,
                    pendingCount = 0,
                ),
            ).list.transcribeAllVisible,
        )
    }

    @Test
    fun listStateIncludesTabSelectionAndEmptyMessages() {
        val newTab = state(input(selectedTab = MainScreenCatalogTab.NEW, displayedRowCount = 0))
        assertTrue(newTab.tabs.newSelected)
        assertFalse(newTab.tabs.processedSelected)
        assertEquals("No new audio files", newTab.list.emptyMessage)
        assertTrue(newTab.list.emptyVisible)

        val processedTab = state(
            input(
                selectedTab = MainScreenCatalogTab.PROCESSED,
                displayedRowCount = 3,
            ),
        )
        assertFalse(processedTab.tabs.newSelected)
        assertTrue(processedTab.tabs.processedSelected)
        assertEquals("No processed audio files", processedTab.list.emptyMessage)
        assertFalse(processedTab.list.emptyVisible)
    }

    @Test
    fun rowActionsReflectPreviewRetryAndTranscriptState() {
        val result = state(
            input(
                outputSelected = true,
                folderSelected = true,
                rows = listOf(
                    row(1, AudioFileState.PENDING),
                    row(2, AudioFileState.FAILED),
                    row(3, AudioFileState.PROCESSED, hasTranscriptText = true),
                ),
                activePreviewEntryId = 1,
                previewState = PreviewPlaybackState.PLAYING,
            ),
        )

        assertEquals("Stop", result.rows[0].preview.label)
        assertTrue(result.rows[0].preview.enabled)
        assertFalse(result.rows[0].retryVisible)

        assertEquals("Play", result.rows[1].preview.label)
        assertTrue(result.rows[1].retryVisible)
        assertTrue(result.rows[1].retryEnabled)

        assertTrue(result.rows[2].showTextVisible)
    }

    @Test
    fun rowActionsAreBlockedDuringScanningOrTranscription() {
        val scanning = state(
            input(
                outputSelected = true,
                folderSelected = true,
                scanning = true,
                rows = listOf(row(1, AudioFileState.FAILED)),
            ),
        ).rows.single()
        assertFalse(scanning.preview.enabled)
        assertFalse(scanning.retryEnabled)

        val active = state(
            input(
                outputSelected = true,
                folderSelected = true,
                transcriptionState = TranscriptionObservationState.ACTIVE,
                rows = listOf(row(1, AudioFileState.FAILED)),
            ),
        ).rows.single()
        assertFalse(active.preview.enabled)
        assertFalse(active.retryEnabled)
    }

    private fun state(input: MainScreenInput): MainScreenState =
        MainScreenStateController.state(input)

    private fun input(
        modelMessage: String = "Ready",
        modelLoading: Boolean = false,
        modelDownloadAvailable: Boolean = false,
        modelDownloadProgress: Int? = null,
        modelReady: Boolean = true,
        outputSelected: Boolean = false,
        folderSelected: Boolean = false,
        pendingCount: Int = 0,
        folderChecking: Boolean = false,
        folderScanQueued: Boolean = false,
        scanning: Boolean = false,
        scanMessage: String? = null,
        transcriptionState: TranscriptionObservationState = TranscriptionObservationState.IDLE,
        transcriptionPhase: String? = null,
        transcriptionFilename: String? = null,
        transcriptionIndeterminate: Boolean = true,
        transcriptionProgress: Int = 0,
        processedUs: Long = -1,
        durationUs: Long = -1,
        completedFiles: Int = 0,
        totalFiles: Int = 0,
        failedFiles: Int = 0,
        errorMessage: String? = null,
        selectedTab: MainScreenCatalogTab = MainScreenCatalogTab.NEW,
        displayedRowCount: Int = 0,
        activePreviewEntryId: Long? = null,
        previewState: PreviewPlaybackState = PreviewPlaybackState.IDLE,
        rows: List<MainScreenRowInput> = emptyList(),
    ) = MainScreenInput(
        modelMessage = modelMessage,
        modelLoading = modelLoading,
        modelDownloadAvailable = modelDownloadAvailable,
        modelDownloadProgress = modelDownloadProgress,
        modelReady = modelReady,
        outputSelected = outputSelected,
        folderSelected = folderSelected,
        pendingCount = pendingCount,
        folderChecking = folderChecking,
        folderScanQueued = folderScanQueued,
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
        selectedTab = selectedTab,
        displayedRowCount = displayedRowCount,
        activePreviewEntryId = activePreviewEntryId,
        previewState = previewState,
        rows = rows,
    )

    private fun row(
        id: Long,
        state: AudioFileState,
        hasTranscriptText: Boolean = false,
    ) = MainScreenRowInput(
        entryId = id,
        state = state,
        hasTranscriptText = hasTranscriptText,
    )
}
