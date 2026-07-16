package me.maxistar.voiceinbox.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskListPresentationControllerTest {
    @Test
    fun setupTasksAreSynthesizedInKindOrderAndCompletedTasksDisappear() {
        val state = state(
            filter = TaskListFilter.NEW,
            model = ModelSetupSnapshot(ModelSetupSnapshotState.REQUIRED, downloadAvailable = true),
            output = OutputSetupSnapshot(OutputSetupSnapshotState.INVALID, "Access expired"),
            folder = FolderSetupSnapshot(FolderSetupSnapshotState.SCANNING),
        )

        assertEquals(listOf("setup:model", "setup:output", "setup:folder"), state.tasks.map { it.stableId })
        assertEquals(listOf(SetupTaskKind.MODEL, SetupTaskKind.OUTPUT, SetupTaskKind.FOLDER), state.tasks.map {
            assertIs<SetupTaskPresentation>(it).kind
        })

        val completed = state(
            filter = TaskListFilter.ALL,
            model = ModelSetupSnapshot(ModelSetupSnapshotState.READY),
            output = OutputSetupSnapshot(OutputSetupSnapshotState.READY),
            folder = FolderSetupSnapshot(FolderSetupSnapshotState.READY),
        )
        assertTrue(completed.tasks.isEmpty())
    }

    @Test
    fun unselectedOptionalFolderDoesNotCreateBlockingTask() {
        val state = state(folder = FolderSetupSnapshot(FolderSetupSnapshotState.UNSELECTED))

        assertTrue(state.tasks.none { it.stableId == "setup:folder" })
    }

    @Test
    fun activeModelInstallationUsesSuppliedPhaseAndNeutralFallback() {
        val download = assertIs<SetupTaskPresentation>(
            state(
                model = ModelSetupSnapshot(
                    state = ModelSetupSnapshotState.INSTALLING,
                    installationPhase = "Downloading encoder.onnx",
                    progressPercent = 42,
                    canCancel = true,
                ),
            ).tasks.single(),
        )

        assertEquals("Installing", download.badge)
        assertEquals("Downloading encoder.onnx", download.progress?.phase)
        assertEquals(42, download.progress?.percent)
        assertEquals(listOf(TaskActionKind.CANCEL_MODEL_DOWNLOAD), download.actions.map { it.kind })

        val localImport = assertIs<SetupTaskPresentation>(
            state(
                model = ModelSetupSnapshot(
                    state = ModelSetupSnapshotState.INSTALLING,
                    installationPhase = "Verifying local model",
                    progressPercent = 75,
                ),
            ).tasks.single(),
        )
        assertEquals("Verifying local model", localImport.progress?.phase)
        assertTrue(localImport.actions.isEmpty())

        val fallback = assertIs<SetupTaskPresentation>(
            state(model = ModelSetupSnapshot(ModelSetupSnapshotState.INSTALLING)).tasks.single(),
        )
        assertEquals("Installing model", fallback.progress?.phase)
        assertNull(fallback.progress?.percent)
    }

    @Test
    fun invalidModelOffersOnlyCurrentlySupportedRecoveryActions() {
        val task = assertIs<SetupTaskPresentation>(
            state(
                model = ModelSetupSnapshot(
                    state = ModelSetupSnapshotState.INVALID,
                    detail = "Verification failed",
                    downloadAvailable = false,
                ),
            ).tasks.single(),
        )

        assertEquals("Verification failed", task.errorMessage)
        assertFalse(task.actions.single { it.kind == TaskActionKind.RETRY_MODEL_DOWNLOAD }.enabled)
        assertTrue(task.actions.single { it.kind == TaskActionKind.IMPORT_MODEL }.enabled)
    }

    @Test
    fun filtersApplyOpenTerminalAndAllRetentionRules() {
        val audio = listOf(
            audio(1, AudioFileState.PENDING),
            audio(2, AudioFileState.PROCESSING),
            audio(3, AudioFileState.PROCESSED),
            audio(4, AudioFileState.FAILED),
            audio(5, AudioFileState.MISSING),
        )

        assertEquals(listOf("audio:2", "audio:1"), state(TaskListFilter.NEW, audio = audio).tasks.map { it.stableId })
        assertEquals(listOf("audio:4", "audio:3"), state(TaskListFilter.PROCESSED, audio = audio).tasks.map { it.stableId })
        assertEquals(listOf("audio:5", "audio:4", "audio:3", "audio:2", "audio:1"), state(TaskListFilter.ALL, audio = audio).tasks.map { it.stableId })
    }

    @Test
    fun noSpeechIsTerminalAndRetainsRetry() {
        val task = state(
            TaskListFilter.PROCESSED,
            audio = listOf(audio(1, AudioFileState.FAILED, noSpeech = true, error = "No text was recognized")),
        ).tasks.single()

        val audioTask = assertIs<AudioTaskPresentation>(task)
        assertEquals(AudioTaskState.NO_SPEECH, audioTask.state)
        assertEquals(TaskActionKind.RETRY_TRANSCRIPTION, audioTask.actions.first().kind)
    }

    @Test
    fun prerequisiteFailureLeavesPendingTaskInNew() {
        val state = state(
            filter = TaskListFilter.NEW,
            audio = listOf(audio(7, AudioFileState.PENDING)),
            transcription = TranscriptionTaskSnapshot(
                active = false,
                preparationOwnerEntryId = 7,
                prerequisiteError = "Model could not be loaded",
            ),
        )

        val task = assertIs<AudioTaskPresentation>(state.tasks.single())
        assertEquals(AudioTaskState.PENDING, task.state)
        assertEquals("Model could not be loaded", task.errorMessage)
        assertTrue(state(TaskListFilter.PROCESSED, audio = listOf(audio(7, AudioFileState.PENDING))).tasks.isEmpty())
    }

    @Test
    fun progressBelongsOnlyToStableActiveAudioTask() {
        val state = state(
            audio = listOf(audio(1, AudioFileState.PENDING), audio(2, AudioFileState.PENDING)),
            transcription = TranscriptionTaskSnapshot(
                active = true,
                preparationOwnerEntryId = 1,
                phase = "Preparing speech model",
            ),
        )

        val byId = state.tasks.associateBy(TaskPresentation::stableId)
        val active = assertIs<AudioTaskPresentation>(byId.getValue("audio:1"))
        assertEquals(AudioTaskState.PROCESSING, active.state)
        assertEquals("Preparing speech model", active.progress?.phase)
        assertNull(assertIs<AudioTaskPresentation>(byId.getValue("audio:2")).progress)
        assertEquals("audio:1", active.stableId)
    }

    @Test
    fun preClaimPreparationUsesDeterministicNextEligiblePendingTask() {
        val audio = listOf(
            audio(3, AudioFileState.PENDING, importedAt = 300),
            audio(1, AudioFileState.PENDING, importedAt = 100),
            audio(2, AudioFileState.PENDING, importedAt = 50, eligible = false),
        )

        assertEquals(1L, TaskListPresentationController.preparationOwnerEntryId(audio))
        val state = state(
            audio = audio,
            transcription = TranscriptionTaskSnapshot(active = true, phase = "Preparing speech model"),
        )
        assertEquals(
            "audio:1",
            state.tasks.single { it.progress != null }.stableId,
        )
        assertTrue(audio.all { it.state == AudioFileState.PENDING })
    }

    @Test
    fun batchActionCountsOnlyEligiblePendingAudioAndOnlyAppearsInNew() {
        val audio = listOf(
            audio(1, AudioFileState.PENDING, eligible = true),
            audio(2, AudioFileState.PENDING, eligible = false),
            audio(3, AudioFileState.FAILED, eligible = true),
        )

        val newState = state(TaskListFilter.NEW, audio = audio)
        assertTrue(newState.batchAction.visible)
        assertTrue(newState.batchAction.enabled)
        assertEquals(1, newState.batchAction.eligibleCount)
        assertFalse(state(TaskListFilter.ALL, audio = audio).batchAction.visible)
        assertFalse(state(TaskListFilter.PROCESSED, audio = audio).batchAction.visible)
    }

    @Test
    fun orderingUsesImportTimeExceptProcessedUsesTerminalTime() {
        val audio = listOf(
            audio(1, AudioFileState.PROCESSED, importedAt = 300, terminalAt = 100),
            audio(2, AudioFileState.FAILED, importedAt = 100, terminalAt = 400),
        )

        assertEquals(listOf("audio:2", "audio:1"), state(TaskListFilter.PROCESSED, audio = audio).tasks.map { it.stableId })
        assertEquals(listOf("audio:1", "audio:2"), state(TaskListFilter.ALL, audio = audio).tasks.map { it.stableId })
    }

    @Test
    fun emptyStateIsSpecificToFilterAndOffersImportOnlyForOpenLists() {
        val newState = state(TaskListFilter.NEW)
        val processed = state(TaskListFilter.PROCESSED)

        assertEquals("No new tasks", newState.emptyMessage)
        assertEquals(listOf(TaskActionKind.IMPORT_AUDIO, TaskActionKind.SELECT_FOLDER), newState.emptyActions.map { it.kind })
        assertEquals("No processed audio files", processed.emptyMessage)
        assertTrue(processed.emptyActions.isEmpty())
    }

    private fun state(
        filter: TaskListFilter = TaskListFilter.NEW,
        model: ModelSetupSnapshot = ModelSetupSnapshot(ModelSetupSnapshotState.READY),
        output: OutputSetupSnapshot = OutputSetupSnapshot(OutputSetupSnapshotState.READY),
        folder: FolderSetupSnapshot = FolderSetupSnapshot(FolderSetupSnapshotState.READY),
        audio: List<AudioTaskSnapshot> = emptyList(),
        transcription: TranscriptionTaskSnapshot = TranscriptionTaskSnapshot(),
    ): TaskListState = TaskListPresentationController.state(
        TaskListInput(
            filter = filter,
            model = model,
            output = output,
            folder = folder,
            audio = audio,
            transcription = transcription,
        ),
    )

    private fun audio(
        id: Long,
        state: AudioFileState,
        importedAt: Long = id,
        terminalAt: Long? = if (state == AudioFileState.PROCESSED || state == AudioFileState.FAILED) id else null,
        noSpeech: Boolean = false,
        error: String? = null,
        eligible: Boolean = true,
    ) = AudioTaskSnapshot(
        entryId = id,
        title = "$id.wav",
        state = state,
        importedAtMillis = importedAt,
        terminalAtMillis = terminalAt,
        lastError = error,
        noSpeech = noSpeech,
        eligibleForTranscription = eligible,
    )
}
