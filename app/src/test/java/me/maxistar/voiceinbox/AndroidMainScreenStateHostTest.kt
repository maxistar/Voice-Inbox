package me.maxistar.voiceinbox

import androidx.lifecycle.SavedStateHandle
import me.maxistar.voiceinbox.core.AudioCatalogEntry
import me.maxistar.voiceinbox.core.AudioFileFingerprint
import me.maxistar.voiceinbox.core.AudioFileState
import me.maxistar.voiceinbox.core.AudioTaskPresentation
import me.maxistar.voiceinbox.core.AudioTaskState
import me.maxistar.voiceinbox.core.FolderSetupSnapshot
import me.maxistar.voiceinbox.core.FolderSetupSnapshotState
import me.maxistar.voiceinbox.core.ModelSetupSnapshot
import me.maxistar.voiceinbox.core.ModelSetupSnapshotState
import me.maxistar.voiceinbox.core.OutputSetupSnapshot
import me.maxistar.voiceinbox.core.OutputSetupSnapshotState
import me.maxistar.voiceinbox.core.PreviewPlaybackState
import me.maxistar.voiceinbox.core.PreviewTaskSnapshot
import me.maxistar.voiceinbox.core.SetupTaskPresentation
import me.maxistar.voiceinbox.core.TaskActionKind
import me.maxistar.voiceinbox.core.TaskListFilter
import me.maxistar.voiceinbox.core.TaskTextKey
import me.maxistar.voiceinbox.core.TranscriptionTaskSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidMainScreenStateHostTest {
    @Test
    fun mapperPreservesSetupAndSourceAwareModelProgress() {
        val state = AndroidTaskListSnapshotMapper.state(
            AndroidMainScreenInput(
                model = ModelSetupSnapshot(
                    state = ModelSetupSnapshotState.INSTALLING,
                    detail = "Installing from selected folder",
                    installationPhase = "Verifying local model",
                    progressPercent = 63,
                ),
                output = OutputSetupSnapshot(OutputSetupSnapshotState.INVALID, "Output unavailable"),
                folder = FolderSetupSnapshot(FolderSetupSnapshotState.SCANNING, "Checking recordings"),
                hydration = hydrated(),
            ),
        )

        val tasks = state.taskList.tasks.filterIsInstance<SetupTaskPresentation>()
        assertEquals(listOf("setup:model", "setup:output", "setup:folder"), tasks.map { it.stableId })
        assertEquals("Verifying local model", tasks.first().progress?.phase?.fallback)
        assertEquals(63, tasks.first().progress?.percent)
        assertEquals("Output unavailable", tasks[1].errorMessage?.fallback)
        assertEquals(TaskTextKey.SCANNING_AUDIO_FOLDER, tasks[2].progress?.phase?.key)
    }

    @Test
    fun activeModelPhaseIsNotRepeatedAsTaskDetail() {
        val message = "Downloading encoder-model.int8.onnx"
        assertNull(androidModelTaskDetail(ModelSetupSnapshotState.INSTALLING, message))
        assertEquals(message, androidModelTaskDetail(ModelSetupSnapshotState.REQUIRED, message))
        assertEquals(message, androidModelTaskDetail(ModelSetupSnapshotState.INVALID, message))
        val input = readyInput().copy(
            model = ModelSetupSnapshot(
                state = ModelSetupSnapshotState.INSTALLING,
                detail = null,
                installationPhase = message,
                progressPercent = 42,
            ),
        )

        val task = AndroidTaskListSnapshotMapper.state(input)
            .taskList.tasks
            .filterIsInstance<SetupTaskPresentation>()
            .single { it.stableId == "setup:model" }

        assertNull(task.detail)
        assertEquals(message, task.progress?.phase?.fallback)
    }

    @Test
    fun mapperPreservesCatalogIdentitySourcesOrderingAndOutcomes() {
        val entries = listOf(
            entry(1, AudioFileState.PENDING, AndroidAudioImportConstants.SOURCE_ID, modified = 10),
            entry(2, AudioFileState.PROCESSING, "content://folder", modified = 20),
            entry(3, AudioFileState.PROCESSED, AndroidAudioImportConstants.SOURCE_ID, modified = 30, processed = 50, transcript = "hello"),
            entry(4, AudioFileState.FAILED, "content://folder", modified = 40, processed = 60, error = "No speech detected"),
            entry(5, AudioFileState.MISSING, "content://folder", modified = 70, error = "Missing"),
        )
        val state = AndroidTaskListSnapshotMapper.state(
            readyInput(filter = TaskListFilter.ALL, entries = entries),
        )

        assertEquals(entries.associateBy { it.id }, state.entriesById)
        val tasks = state.taskList.tasks.filterIsInstance<AudioTaskPresentation>()
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), tasks.map { it.entryId })
        assertEquals(AudioTaskState.NO_SPEECH, tasks.single { it.entryId == 4L }.state)
        assertEquals(TaskActionKind.SHOW_TEXT, tasks.single { it.entryId == 3L }.actions.first().kind)
        assertTrue(requireNotNull(tasks.single { it.entryId == 1L }.detail).fallback.endsWith(" • 1 KiB"))
    }

    @Test
    fun mapperAssignsPreviewAndActiveTranscriptionToStableEntry() {
        val state = AndroidTaskListSnapshotMapper.state(
            readyInput(
                entries = listOf(
                    entry(1, AudioFileState.PENDING, AndroidAudioImportConstants.SOURCE_ID, modified = 10),
                    entry(2, AudioFileState.PENDING, "content://folder", modified = 20),
                ),
                preview = PreviewTaskSnapshot(2, PreviewPlaybackState.PLAYING),
                transcription = TranscriptionTaskSnapshot(
                    active = true,
                    activeEntryId = 1,
                    phase = "Transcribing",
                    percent = 25,
                    completedFiles = 0,
                    totalFiles = 2,
                ),
            ),
        )

        val tasks = state.taskList.tasks.filterIsInstance<AudioTaskPresentation>()
        val active = tasks.single { it.entryId == 1L }
        assertEquals(AudioTaskState.PROCESSING, active.state)
        assertEquals("Transcribing", active.progress?.phase?.fallback)
        assertEquals(25, active.progress?.percent)
        val preview = tasks.single { it.entryId == 2L }
        assertEquals(TaskActionKind.STOP, preview.actions.last().kind)
        assertTrue(preview.actions.last().enabled)
    }

    @Test
    fun preClaimPreparationUsesDeterministicOldestEligibleEntry() {
        val state = AndroidTaskListSnapshotMapper.state(
            readyInput(
                entries = listOf(
                    entry(1, AudioFileState.PENDING, AndroidAudioImportConstants.SOURCE_ID, modified = 100),
                    entry(2, AudioFileState.PENDING, "content://folder", modified = 50),
                ),
                transcription = TranscriptionTaskSnapshot(active = true, phase = "Preparing speech model"),
            ),
        )

        val owner = state.taskList.tasks.filterIsInstance<AudioTaskPresentation>().single { it.progress != null }
        assertEquals(2L, owner.entryId)
        assertEquals(AudioTaskState.PROCESSING, owner.state)
        assertTrue(owner.actions.none { it.enabled })
        assertFalse(state.taskList.batchAction.enabled)
    }

    @Test
    fun explicitPreparationOwnerOverridesOldestEligibleEntry() {
        val state = AndroidTaskListSnapshotMapper.state(
            readyInput(
                entries = listOf(
                    entry(1, AudioFileState.PENDING, AndroidAudioImportConstants.SOURCE_ID, modified = 50),
                    entry(2, AudioFileState.PENDING, "content://folder", modified = 100),
                ),
                transcription = TranscriptionTaskSnapshot(
                    active = true,
                    preparationOwnerEntryId = 2,
                    phase = "Preparing speech model",
                ),
            ),
        )

        val tasks = state.taskList.tasks.filterIsInstance<AudioTaskPresentation>()
        assertNull(tasks.single { it.entryId == 1L }.progress)
        val owner = tasks.single { it.entryId == 2L }
        assertEquals(AudioTaskState.PROCESSING, owner.state)
        assertEquals("Preparing speech model", owner.progress?.phase?.fallback)
    }

    @Test
    fun stateHostPublishesImmutableUpdatesAndRestoresFilter() {
        val savedState = SavedStateHandle()
        val host = AndroidMainScreenStateHost(savedState)
        assertEquals(TaskListFilter.NEW, host.state.value.taskList.filter)

        host.replace(readyInput(filter = TaskListFilter.PROCESSED, entries = listOf(entry(1, AudioFileState.FAILED))))
        val first = host.state.value
        assertEquals(TaskListFilter.PROCESSED, first.taskList.filter)
        assertEquals(listOf("audio:1"), first.taskList.tasks.map { it.stableId })

        host.update { it.copy(entries = emptyList()) }
        assertTrue(host.state.value.taskList.tasks.isEmpty())
        assertEquals(listOf("audio:1"), first.taskList.tasks.map { it.stableId })

        val recreated = AndroidMainScreenStateHost(savedState)
        assertEquals(TaskListFilter.PROCESSED, recreated.state.value.taskList.filter)
    }

    @Test
    fun completedSetupAndOptionalFolderAreHidden() {
        val state = AndroidTaskListSnapshotMapper.state(readyInput())

        assertTrue(state.taskList.tasks.isEmpty())
        assertNull(state.taskList.tasks.filterIsInstance<SetupTaskPresentation>().firstOrNull())
        assertTrue(state.taskList.emptyActions.any { it.kind == TaskActionKind.IMPORT_AUDIO })
    }

    @Test
    fun pendingHydrationSuppressesFalseSetupAndFinalEmptyState() {
        val state = AndroidTaskListSnapshotMapper.state(
            AndroidMainScreenInput(
                model = ModelSetupSnapshot(ModelSetupSnapshotState.REQUIRED),
                output = OutputSetupSnapshot(OutputSetupSnapshotState.REQUIRED),
                folder = FolderSetupSnapshot(FolderSetupSnapshotState.SCANNING),
                hydration = AndroidMainScreenHydration(),
            ),
        )

        assertTrue(state.taskList.tasks.isEmpty())
        assertNull(state.taskList.emptyMessage)
        assertTrue(state.taskList.emptyActions.isEmpty())
        assertFalse(state.onboardingHint.visible)
    }

    @Test
    fun onboardingPresentationUsesCurrentSnapshotsAndFilterWithoutMutatingSetupTasks() {
        val initial = AndroidTaskListSnapshotMapper.state(
            AndroidMainScreenInput(
                model = ModelSetupSnapshot(ModelSetupSnapshotState.REQUIRED, downloadAvailable = true),
                output = OutputSetupSnapshot(OutputSetupSnapshotState.REQUIRED),
                folder = FolderSetupSnapshot(FolderSetupSnapshotState.UNSELECTED),
                hydration = hydrated(),
                onboardingLifecycle = AndroidOnboardingHintLifecycle.ACTIVE,
                keyboardKnown = true,
            ),
        )
        assertTrue(initial.onboardingHint.visible)
        assertEquals(TaskActionKind.DOWNLOAD_MODEL, initial.onboardingHint.action?.kind)
        assertEquals(
            listOf("setup:model", "setup:output"),
            initial.taskList.tasks.map { it.stableId },
        )

        val directModelCompletion = AndroidTaskListSnapshotMapper.state(
            AndroidMainScreenInput(
                model = ModelSetupSnapshot(ModelSetupSnapshotState.READY),
                output = OutputSetupSnapshot(OutputSetupSnapshotState.REQUIRED),
                folder = FolderSetupSnapshot(FolderSetupSnapshotState.UNSELECTED),
                hydration = hydrated(),
                onboardingLifecycle = AndroidOnboardingHintLifecycle.ACTIVE,
                keyboardKnown = true,
            ),
        )
        assertTrue(directModelCompletion.onboardingHint.visible)
        assertEquals(TaskActionKind.SELECT_OUTPUT, directModelCompletion.onboardingHint.action?.kind)
        assertEquals(
            listOf(
                TaskActionKind.CREATE_OUTPUT,
                TaskActionKind.SELECT_OUTPUT,
                TaskActionKind.HIDE_OUTPUT,
            ),
            directModelCompletion.taskList.tasks.single().actions.map { it.kind },
        )
        assertEquals(listOf("setup:output"), directModelCompletion.taskList.tasks.map { it.stableId })

        val hiddenOutput = AndroidTaskListSnapshotMapper.state(
            AndroidMainScreenInput(
                model = ModelSetupSnapshot(ModelSetupSnapshotState.READY),
                output = OutputSetupSnapshot(OutputSetupSnapshotState.REQUIRED),
                folder = FolderSetupSnapshot(FolderSetupSnapshotState.UNSELECTED),
                outputTaskHidden = true,
                hydration = hydrated(),
                onboardingLifecycle = AndroidOnboardingHintLifecycle.DISMISSED,
            ),
        )
        assertTrue(hiddenOutput.taskList.tasks.none { it.stableId == "setup:output" })

        val allFilter = AndroidTaskListSnapshotMapper.state(
            AndroidMainScreenInput(
                filter = TaskListFilter.ALL,
                model = ModelSetupSnapshot(ModelSetupSnapshotState.REQUIRED),
                output = OutputSetupSnapshot(OutputSetupSnapshotState.REQUIRED),
                folder = FolderSetupSnapshot(FolderSetupSnapshotState.UNSELECTED),
                hydration = hydrated(),
                onboardingLifecycle = AndroidOnboardingHintLifecycle.ACTIVE,
                keyboardKnown = true,
            ),
        )
        assertFalse(allFilter.onboardingHint.visible)
    }

    @Test
    fun knownMissingAndInvalidSetupAppearBeforeCatalogHydration() {
        val state = AndroidTaskListSnapshotMapper.state(
            AndroidMainScreenInput(
                model = ModelSetupSnapshot(ModelSetupSnapshotState.REQUIRED),
                output = OutputSetupSnapshot(OutputSetupSnapshotState.INVALID, "Permission revoked"),
                folder = FolderSetupSnapshot(FolderSetupSnapshotState.ERROR, "Folder unavailable"),
                hydration = AndroidMainScreenHydration(
                    modelKnown = true,
                    outputKnown = true,
                    folderKnown = true,
                ),
            ),
        )

        assertEquals(
            listOf("setup:model", "setup:output", "setup:folder"),
            state.taskList.tasks.map { it.stableId },
        )
        assertNull(state.taskList.emptyMessage)
    }

    @Test
    fun folderSyncPresentationIsRendererIndependentAndCopiedToState() {
        val sync = AndroidFolderSyncPresentation(
            visible = true,
            active = true,
            enabled = false,
            accessibilityLabel = AndroidFolderSyncPresentation.ACCESSIBILITY_REFRESHING,
        )
        val state = AndroidTaskListSnapshotMapper.state(readyInput().copy(folderSync = sync))

        assertEquals(sync, state.folderSync)
        assertTrue(state.refreshFolderVisible)
        assertFalse(state.refreshFolderEnabled)
    }

    @Test
    fun hydratedCatalogPublishesEmptyStateAndRetainedEntriesRemainVisibleWhileRevalidating() {
        val empty = AndroidTaskListSnapshotMapper.state(readyInput())
        assertEquals(TaskTextKey.NO_NEW_TASKS, empty.taskList.emptyMessage?.key)

        val retained = AndroidTaskListSnapshotMapper.state(
            readyInput(entries = listOf(entry(1, AudioFileState.PENDING))).copy(
                hydration = AndroidMainScreenHydration(catalogKnown = true),
            ),
        )
        assertEquals(listOf("audio:1"), retained.taskList.tasks.map { it.stableId })
        assertNull(retained.taskList.emptyMessage)
    }

    private fun readyInput(
        filter: TaskListFilter = TaskListFilter.NEW,
        entries: List<AudioCatalogEntry> = emptyList(),
        preview: PreviewTaskSnapshot = PreviewTaskSnapshot(),
        transcription: TranscriptionTaskSnapshot = TranscriptionTaskSnapshot(),
    ) = AndroidMainScreenInput(
        filter = filter,
        model = ModelSetupSnapshot(ModelSetupSnapshotState.READY),
        output = OutputSetupSnapshot(OutputSetupSnapshotState.READY),
        folder = FolderSetupSnapshot(FolderSetupSnapshotState.READY),
        entries = entries,
        preview = preview,
        transcription = transcription,
        transcriptionEligible = true,
        hydration = hydrated(),
    )

    private fun hydrated() = AndroidMainScreenHydration(
        modelKnown = true,
        outputKnown = true,
        folderKnown = true,
        catalogKnown = true,
    )

    private fun entry(
        id: Long,
        state: AudioFileState,
        source: String = AndroidAudioImportConstants.SOURCE_ID,
        modified: Long = id,
        processed: Long? = null,
        error: String? = null,
        transcript: String? = null,
        durationUs: Long? = null,
    ) = AudioCatalogEntry(
        id = id,
        folderUri = source,
        documentUri = "content://audio/$id",
        displayName = "$id.ogg",
        mimeType = "audio/ogg",
        fingerprint = AudioFileFingerprint(sizeBytes = 1024, modifiedMillis = modified),
        state = state,
        stateBeforeMissing = null,
        lastError = error,
        processedAtMillis = processed,
        transcriptText = transcript,
        durationUs = durationUs,
    )

    companion object {
        internal fun readyInputForMetadata(
            filter: TaskListFilter,
            entries: List<AudioCatalogEntry>,
        ) = AndroidMainScreenInput(
            filter = filter,
            model = ModelSetupSnapshot(ModelSetupSnapshotState.READY),
            output = OutputSetupSnapshot(OutputSetupSnapshotState.READY),
            folder = FolderSetupSnapshot(FolderSetupSnapshotState.READY),
            entries = entries,
            transcriptionEligible = true,
            hydration = AndroidMainScreenHydration(
                modelKnown = true,
                outputKnown = true,
                folderKnown = true,
                catalogKnown = true,
            ),
        )

        internal fun entryForMetadata(
            id: Long,
            state: AudioFileState,
            modified: Long,
            durationUs: Long?,
        ) = AudioCatalogEntry(
            id = id,
            folderUri = AndroidAudioImportConstants.SOURCE_ID,
            documentUri = "content://audio/$id",
            displayName = "$id.ogg",
            mimeType = "audio/ogg",
            fingerprint = AudioFileFingerprint(sizeBytes = 1024, modifiedMillis = modified),
            state = state,
            stateBeforeMissing = null,
            lastError = null,
            processedAtMillis = modified + 1,
            transcriptText = "text",
            durationUs = durationUs,
        )
    }
}
