package me.maxistar.voiceinbox.core


import kotlin.test.assertEquals
import kotlin.test.Test

class TranscriptionUiRulesTest {
    @Test
    fun importedAudioAllowsTranscriptionWithoutSelectedFolder() {
        val controls = TranscriptionUiRules.catalogControls(
            modelInstallationState = SpeechModelInstallationState.INSTALLED,
            outputSelected = true,
            folderSelected = false,
            pendingCount = 1,
            transcriptionState = TranscriptionObservationState.IDLE,
            scanning = false,
            audioInputAvailable = true,
        )

        assertEquals(true, controls.transcribeAllEnabled)
        assertEquals(true, controls.retryEnabled)
        assertEquals(false, controls.refreshEnabled)
    }

    @Test
    fun controlsRequireModelSelectionsPendingWorkAndIdleState() {
        assertEquals(
            CatalogControlState(true, true, true, true, false, false, false),
            controls(modelReady = false),
        )
        assertEquals(
            CatalogControlState(true, true, true, true, false, false, false),
            controls(modelReady = true),
        )
        assertEquals(
            CatalogControlState(true, true, false, false, true, false, true),
            controls(modelReady = true, output = true, folder = true),
        )
        assertEquals(
            CatalogControlState(true, true, true, false, true, false, false),
            controls(modelReady = true, folder = true),
        )
        assertEquals(
            CatalogControlState(true, true, false, false, true, true, true),
            controls(modelReady = true, output = true, folder = true, pending = 2),
        )
        assertEquals(
            CatalogControlState(false, false, false, false, false, false, false),
            controls(
                modelReady = true,
                output = true,
                folder = true,
                pending = 2,
                active = true,
            ),
        )
    }

    @Test
    fun storageSelectionDoesNotRequireAnInstalledModel() {
        listOf(
            SpeechModelInstallationState.NOT_INSTALLED,
            SpeechModelInstallationState.INSTALLING,
            SpeechModelInstallationState.INVALID,
            SpeechModelInstallationState.INSTALLED,
        ).forEach { modelState ->
            val controls = TranscriptionUiRules.catalogControls(
                modelInstallationState = modelState,
                outputSelected = false,
                folderSelected = false,
                pendingCount = 1,
                transcriptionState = TranscriptionObservationState.IDLE,
                scanning = false,
            )

            assertEquals(true, controls.outputEnabled, modelState.name)
            assertEquals(true, controls.folderEnabled, modelState.name)
            assertEquals(false, controls.refreshEnabled, modelState.name)
            assertEquals(false, controls.transcribeAllEnabled, modelState.name)
            assertEquals(false, controls.retryEnabled, modelState.name)
        }
    }

    @Test
    fun activeWorkBlocksStorageSelectionWithoutAnInstalledModel() {
        val controls = TranscriptionUiRules.catalogControls(
            modelInstallationState = SpeechModelInstallationState.INSTALLING,
            outputSelected = false,
            folderSelected = false,
            pendingCount = 0,
            transcriptionState = TranscriptionObservationState.ACTIVE,
            scanning = false,
        )

        assertEquals(false, controls.outputEnabled)
        assertEquals(false, controls.folderEnabled)
    }

    @Test
    fun selectionControlsAreDisabledWhileScanningOrTranscribing() {
        assertEquals(
            CatalogControlState(false, false, false, false, false, false, false),
            TranscriptionUiRules.catalogControls(
                modelInstallationState = SpeechModelInstallationState.INSTALLED,
                outputSelected = true,
                folderSelected = true,
                pendingCount = 2,
                transcriptionState = TranscriptionObservationState.ACTIVE,
                scanning = false,
            ),
        )
        assertEquals(
            CatalogControlState(false, false, false, false, false, false, false),
            TranscriptionUiRules.catalogControls(
                modelInstallationState = SpeechModelInstallationState.INSTALLED,
                outputSelected = true,
                folderSelected = true,
                pendingCount = 2,
                transcriptionState = TranscriptionObservationState.IDLE,
                scanning = true,
            ),
        )
    }

    @Test
    fun controlsAreDisabledWhileTranscriptionObservationIsUnknown() {
        assertEquals(
            CatalogControlState(false, false, false, false, false, false, false),
            TranscriptionUiRules.catalogControls(
                modelInstallationState = SpeechModelInstallationState.INSTALLED,
                outputSelected = true,
                folderSelected = true,
                pendingCount = 2,
                transcriptionState = TranscriptionObservationState.UNKNOWN,
                scanning = false,
            ),
        )
    }

    @Test
    fun transcriptionObservationIgnoresHistoricalFinishedWork() {
        assertEquals(
            TranscriptionObservationState.IDLE,
            TranscriptionUiRules.transcriptionObservation(
                TranscriptionObservationInput(
                    hasActiveWork = false,
                    hasCurrentSessionFinishedWork = false,
                ),
            ),
        )
    }

    @Test
    fun transcriptionObservationPreservesActiveAndCurrentCompletion() {
        assertEquals(
            TranscriptionObservationState.ACTIVE,
            TranscriptionUiRules.transcriptionObservation(
                TranscriptionObservationInput(
                    hasActiveWork = true,
                    hasCurrentSessionFinishedWork = true,
                ),
            ),
        )
        assertEquals(
            TranscriptionObservationState.FINISHED,
            TranscriptionUiRules.transcriptionObservation(
                TranscriptionObservationInput(
                    hasActiveWork = false,
                    hasCurrentSessionFinishedWork = true,
                ),
            ),
        )
    }

    @Test
    fun setupActionVisibilityReflectsMissingSelections() {
        assertEquals(
            CatalogControlState(
                outputEnabled = true,
                folderEnabled = true,
                outputSetupVisible = true,
                folderSetupVisible = true,
                refreshEnabled = false,
                transcribeAllEnabled = false,
                retryEnabled = false,
            ),
            controls(modelReady = true),
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
            controls(modelReady = true, output = true),
        )
        assertEquals(
            CatalogControlState(
                outputEnabled = true,
                folderEnabled = true,
                outputSetupVisible = false,
                folderSetupVisible = false,
                refreshEnabled = true,
                transcribeAllEnabled = false,
                retryEnabled = true,
            ),
            controls(modelReady = true, output = true, folder = true),
        )
    }

    @Test
    fun progressUsesProcessedDuration() {
        assertEquals(50, TranscriptionUiRules.percent(5_000, 10_000))
        assertEquals(100, TranscriptionUiRules.percent(20_000, 10_000))
        assertEquals(null, TranscriptionUiRules.percent(1, null))
    }

    @Test
    fun previewControlsReflectPlaybackAndWorkState() {
        assertEquals(
            PreviewControlState("Play", enabled = true),
            TranscriptionUiRules.previewControl(
                entryId = 1,
                activeEntryId = null,
                playbackState = PreviewPlaybackState.IDLE,
                transcriptionState = TranscriptionObservationState.IDLE,
                scanning = false,
            ),
        )
        assertEquals(
            PreviewControlState("Play", enabled = false),
            TranscriptionUiRules.previewControl(
                entryId = 1,
                activeEntryId = null,
                playbackState = PreviewPlaybackState.IDLE,
                transcriptionState = TranscriptionObservationState.ACTIVE,
                scanning = false,
            ),
        )
        assertEquals(
            PreviewControlState("Play", enabled = false),
            TranscriptionUiRules.previewControl(
                entryId = 1,
                activeEntryId = null,
                playbackState = PreviewPlaybackState.IDLE,
                transcriptionState = TranscriptionObservationState.UNKNOWN,
                scanning = false,
            ),
        )
        assertEquals(
            PreviewControlState("Loading...", enabled = true),
            TranscriptionUiRules.previewControl(
                entryId = 1,
                activeEntryId = 1,
                playbackState = PreviewPlaybackState.LOADING,
                transcriptionState = TranscriptionObservationState.IDLE,
                scanning = false,
            ),
        )
        assertEquals(
            PreviewControlState("Stop", enabled = true),
            TranscriptionUiRules.previewControl(
                entryId = 1,
                activeEntryId = 1,
                playbackState = PreviewPlaybackState.PLAYING,
                transcriptionState = TranscriptionObservationState.IDLE,
                scanning = false,
            ),
        )
    }

    @Test
    fun statusBlockShowsModelDownloadProgress() {
        assertEquals(
            StatusProgressBlockState(
                title = "Downloading speech model",
                progressVisible = true,
                progressIndeterminate = false,
                progress = 42,
            ),
            status(modelLoading = true, modelDownloadProgress = 42),
        )
    }

    @Test
    fun statusBlockShowsDownloadActionWhenModelIsMissing() {
        assertEquals(
            StatusProgressBlockState(
                title = "Speech model is not installed",
                downloadVisible = true,
            ),
            status(
                modelMessage = "Speech model is not installed",
                modelReady = false,
                modelDownloadAvailable = true,
            ),
        )
    }

    @Test
    fun statusBlockShowsSetupRequirementsAfterModelReady() {
        assertEquals(
            StatusProgressBlockState(
                title = "Setup required",
                detail = "Select output file and audio folder to start",
            ),
            status(modelReady = true),
        )
    }

    @Test
    fun activeTranscriptionHasPriorityAndPreservesProgressDetails() {
        assertEquals(
            StatusProgressBlockState(
                title = "lesson.wav",
                detail = "Transcribing",
                meta = "0:05 / 0:10 • 1 / 3 files",
                progressVisible = true,
                progressIndeterminate = false,
                progress = 50,
            ),
            status(
                modelReady = true,
                output = true,
                folder = true,
                pending = 2,
                scanMessage = "Scan complete: 2 audio files",
                transcriptionState = TranscriptionObservationState.ACTIVE,
                transcriptionPhase = "Transcribing",
                transcriptionFilename = "lesson.wav",
                transcriptionIndeterminate = false,
                transcriptionProgress = 50,
                processedUs = 5_000_000,
                durationUs = 10_000_000,
                completedFiles = 1,
                totalFiles = 3,
            ),
        )
    }

    @Test
    fun scanningHasPriorityOverStaleCompletion() {
        assertEquals(
            StatusProgressBlockState(
                title = "Scanning folder",
                progressVisible = true,
                progressIndeterminate = true,
            ),
            status(
                modelReady = true,
                output = true,
                folder = true,
                scanning = true,
                scanMessage = "Scanning folder",
                transcriptionState = TranscriptionObservationState.FINISHED,
                transcriptionPhase = "Completed",
                transcriptionProgress = 100,
            ),
        )
    }

    @Test
    fun unknownTranscriptionObservationDoesNotRenderReadyState() {
        assertEquals(
            StatusProgressBlockState(
                title = "Checking transcription status",
                progressVisible = true,
                progressIndeterminate = true,
            ),
            status(
                modelReady = true,
                output = true,
                folder = true,
                pending = 2,
                transcriptionState = TranscriptionObservationState.UNKNOWN,
            ),
        )
    }

    @Test
    fun modelLoadingHasPriorityOverFolderWork() {
        assertEquals(
            StatusProgressBlockState(
                title = "Checking speech model",
                progressVisible = true,
                progressIndeterminate = true,
            ),
            status(
                modelMessage = "Checking speech model",
                modelLoading = true,
                modelReady = false,
                output = true,
                folder = true,
                folderChecking = true,
                scanning = true,
                scanMessage = "Scanning folder",
            ),
        )
    }

    @Test
    fun folderCheckingIsDistinctFromFolderScanning() {
        assertEquals(
            StatusProgressBlockState(
                title = "Checking audio folder",
                progressVisible = true,
                progressIndeterminate = true,
            ),
            status(
                modelReady = true,
                output = true,
                folder = true,
                folderChecking = true,
                scanning = false,
            ),
        )
    }

    @Test
    fun queuedFolderScanDoesNotRenderScanningUntilActive() {
        assertEquals(
            StatusProgressBlockState(
                title = "Ready",
                detail = "2 files ready to transcribe",
            ),
            status(
                modelReady = true,
                output = true,
                folder = true,
                pending = 2,
                folderChecking = false,
                scanning = false,
                scanMessage = null,
            ),
        )
    }

    @Test
    fun staleFinishedObservationRendersIdleStatusAfterStartup() {
        assertEquals(
            StatusProgressBlockState(
                title = "Ready",
                detail = "No new files to transcribe",
            ),
            status(
                modelReady = true,
                output = true,
                folder = true,
                transcriptionState = TranscriptionObservationState.IDLE,
                transcriptionPhase = "Completed 2 of 2",
                transcriptionProgress = 100,
                completedFiles = 2,
                totalFiles = 2,
            ),
        )
    }

    @Test
    fun currentSessionCompletionRendersCompletionStatus() {
        assertEquals(
            StatusProgressBlockState(
                title = "Completed 2 of 2",
                meta = "2 / 2 files",
                progressVisible = true,
                progressIndeterminate = false,
                progress = 100,
            ),
            status(
                modelReady = true,
                output = true,
                folder = true,
                transcriptionState = TranscriptionObservationState.FINISHED,
                transcriptionPhase = "Completed 2 of 2",
                transcriptionProgress = 100,
                completedFiles = 2,
                totalFiles = 2,
            ),
        )
    }

    private fun controls(
        modelReady: Boolean,
        output: Boolean = false,
        folder: Boolean = false,
        pending: Int = 0,
        active: Boolean = false,
    ) = TranscriptionUiRules.catalogControls(
        if (modelReady) SpeechModelInstallationState.INSTALLED else SpeechModelInstallationState.NOT_INSTALLED,
        output,
        folder,
        pending,
        if (active) TranscriptionObservationState.ACTIVE else TranscriptionObservationState.IDLE,
        scanning = false,
    )

    private fun status(
        modelMessage: String = "Downloading speech model",
        modelLoading: Boolean = false,
        modelDownloadAvailable: Boolean = false,
        modelDownloadProgress: Int? = null,
        modelReady: Boolean = false,
        output: Boolean = false,
        folder: Boolean = false,
        pending: Int = 0,
        folderChecking: Boolean = false,
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
    ) = TranscriptionUiRules.statusProgressBlock(
        StatusProgressInput(
            modelMessage = modelMessage,
            modelInstallationState = when {
                modelLoading -> SpeechModelInstallationState.INSTALLING
                modelReady -> SpeechModelInstallationState.INSTALLED
                else -> SpeechModelInstallationState.NOT_INSTALLED
            },
            modelRuntimeState = SpeechModelRuntimeState.UNLOADED,
            modelDownloadAvailable = modelDownloadAvailable,
            modelDownloadProgress = modelDownloadProgress,
            outputSelected = output,
            folderSelected = folder,
            pendingCount = pending,
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
        ),
    )
}
