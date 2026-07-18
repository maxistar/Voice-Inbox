package me.maxistar.voiceinbox

import me.maxistar.voiceinbox.core.AudioCatalogEntry
import me.maxistar.voiceinbox.core.AudioFileFingerprint
import me.maxistar.voiceinbox.core.AudioFileState
import me.maxistar.voiceinbox.core.FolderSetupSnapshot
import me.maxistar.voiceinbox.core.FolderSetupSnapshotState
import me.maxistar.voiceinbox.core.ModelSetupSnapshot
import me.maxistar.voiceinbox.core.ModelSetupSnapshotState
import me.maxistar.voiceinbox.core.OutputSetupSnapshot
import me.maxistar.voiceinbox.core.OutputSetupSnapshotState
import me.maxistar.voiceinbox.core.TaskActionKind
import me.maxistar.voiceinbox.core.TaskListFilter
import me.maxistar.voiceinbox.core.TranscriptionTaskSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidTaskActionRouterTest {
    @Test
    fun routesEnabledSetupEmptyBatchAndResolvedEntryActions() {
        var state = state(modelReady = false)
        val calls = mutableListOf<Pair<TaskActionKind, AudioCatalogEntry?>>()
        val router = AndroidTaskActionRouter({ state }) { kind, entry -> calls += kind to entry }

        assertTrue(router.route(request("setup:model", null, TaskActionKind.IMPORT_MODEL)))
        assertEquals(TaskActionKind.IMPORT_MODEL, calls.last().first)
        assertNull(calls.last().second)

        state = state(entries = listOf(entry(9)))
        assertTrue(router.route(request("audio:9", 9, TaskActionKind.TRANSCRIBE)))
        assertEquals(9L, calls.last().second?.id)
        assertTrue(router.route(request(TaskListDisplayItem.BatchAction.STABLE_KEY, null, TaskActionKind.TRANSCRIBE_ALL)))

        state = state()
        assertTrue(router.route(request(TaskListDisplayItem.Empty.STABLE_KEY, null, TaskActionKind.IMPORT_AUDIO)))
    }

    @Test
    fun routesBothCurrentOutputConfigurationActionsAndRejectsAnotherKind() {
        val state = AndroidTaskListSnapshotMapper.state(
            AndroidMainScreenInput(
                model = ModelSetupSnapshot(ModelSetupSnapshotState.READY),
                output = OutputSetupSnapshot(OutputSetupSnapshotState.REQUIRED),
                folder = FolderSetupSnapshot(FolderSetupSnapshotState.UNSELECTED),
                hydration = AndroidMainScreenHydration(true, true, true, true),
            ),
        )
        val calls = mutableListOf<TaskActionKind>()
        val router = AndroidTaskActionRouter({ state }) { kind, _ -> calls += kind }

        assertTrue(router.route(request("setup:output", null, TaskActionKind.CREATE_OUTPUT)))
        assertTrue(router.route(request("setup:output", null, TaskActionKind.SELECT_OUTPUT)))
        assertFalse(router.route(request("setup:output", null, TaskActionKind.SELECT_FOLDER)))
        assertEquals(listOf(TaskActionKind.CREATE_OUTPUT, TaskActionKind.SELECT_OUTPUT), calls)
    }

    @Test
    fun rejectsStaleDisabledConflictingAndMismatchedClicks() {
        var state = state(entries = listOf(entry(9)))
        var callCount = 0
        val router = AndroidTaskActionRouter({ state }) { _, _ -> callCount++ }
        val transcribe = request("audio:9", 9, TaskActionKind.TRANSCRIBE)

        state = state(
            entries = listOf(entry(9)),
            transcription = TranscriptionTaskSnapshot(active = true, activeEntryId = 9),
        )
        assertFalse(router.route(transcribe))
        assertFalse(router.route(request("audio:9", 10, TaskActionKind.PLAY)))
        assertFalse(router.route(request("audio:missing", 9, TaskActionKind.PLAY)))
        assertEquals(0, callCount)

        state = state(entries = listOf(entry(9)), eligible = false)
        assertFalse(router.route(transcribe))
        assertFalse(router.route(request(TaskListDisplayItem.BatchAction.STABLE_KEY, null, TaskActionKind.TRANSCRIBE_ALL)))
    }

    @Test
    fun resolvesRetryAndShowTextFromCurrentCatalogState() {
        val failed = entry(4, AudioFileState.FAILED)
        var routed: Pair<TaskActionKind, AudioCatalogEntry?>? = null
        var state = state(filter = TaskListFilter.PROCESSED, entries = listOf(failed))
        val router = AndroidTaskActionRouter({ state }) { kind, entry -> routed = kind to entry }

        assertTrue(router.route(request("audio:4", 4, TaskActionKind.RETRY_TRANSCRIPTION)))
        assertEquals(failed, routed?.second)

        val processed = entry(4, AudioFileState.PROCESSED, transcript = "hello")
        state = state(filter = TaskListFilter.PROCESSED, entries = listOf(processed))
        assertTrue(router.route(request("audio:4", 4, TaskActionKind.SHOW_TEXT)))
        assertEquals(processed, routed?.second)
    }

    @Test
    fun routesOnlyCurrentEnabledOnboardingActionIncludingOptionalFolder() {
        var state = onboardingState(
            model = ModelSetupSnapshot(ModelSetupSnapshotState.REQUIRED, downloadAvailable = true),
        )
        val calls = mutableListOf<TaskActionKind>()
        val router = AndroidTaskActionRouter({ state }) { kind, _ -> calls += kind }

        assertTrue(
            router.route(
                request(TaskListDisplayItem.OnboardingHint.STABLE_KEY, null, TaskActionKind.DOWNLOAD_MODEL),
            ),
        )
        assertFalse(
            router.route(
                request(TaskListDisplayItem.OnboardingHint.STABLE_KEY, null, TaskActionKind.SELECT_OUTPUT),
            ),
        )

        state = onboardingState(
            model = ModelSetupSnapshot(ModelSetupSnapshotState.READY),
        )
        assertTrue(
            router.route(
                request(TaskListDisplayItem.OnboardingHint.STABLE_KEY, null, TaskActionKind.CREATE_OUTPUT),
            ),
        )
        assertFalse(
            router.route(
                request(TaskListDisplayItem.OnboardingHint.STABLE_KEY, null, TaskActionKind.SELECT_OUTPUT),
            ),
        )

        state = onboardingState(
            model = ModelSetupSnapshot(ModelSetupSnapshotState.READY),
            output = OutputSetupSnapshot(OutputSetupSnapshotState.READY),
            folder = FolderSetupSnapshot(FolderSetupSnapshotState.UNSELECTED),
        )
        assertTrue(state.taskList.tasks.none { it.stableId == "setup:folder" })
        assertTrue(
            router.route(
                request(TaskListDisplayItem.OnboardingHint.STABLE_KEY, null, TaskActionKind.SELECT_FOLDER),
            ),
        )

        state = onboardingState(
            model = ModelSetupSnapshot(ModelSetupSnapshotState.INSTALLING),
        )
        assertFalse(
            router.route(
                request(TaskListDisplayItem.OnboardingHint.STABLE_KEY, null, TaskActionKind.DOWNLOAD_MODEL),
            ),
        )
        assertEquals(
            listOf(TaskActionKind.DOWNLOAD_MODEL, TaskActionKind.CREATE_OUTPUT, TaskActionKind.SELECT_FOLDER),
            calls,
        )
    }

    @Test
    fun terminalOrCompletedOnboardingRejectsStaleClick() {
        var state = onboardingState(
            model = ModelSetupSnapshot(ModelSetupSnapshotState.REQUIRED, downloadAvailable = true),
        )
        var callCount = 0
        val router = AndroidTaskActionRouter({ state }) { _, _ -> callCount++ }
        val stale = request(TaskListDisplayItem.OnboardingHint.STABLE_KEY, null, TaskActionKind.DOWNLOAD_MODEL)

        state = AndroidTaskListSnapshotMapper.state(
            stateInput(
                model = ModelSetupSnapshot(ModelSetupSnapshotState.REQUIRED, downloadAvailable = true),
                lifecycle = AndroidOnboardingHintLifecycle.DISMISSED,
            ),
        )
        assertFalse(router.route(stale))

        state = onboardingState(
            model = ModelSetupSnapshot(ModelSetupSnapshotState.READY),
            output = OutputSetupSnapshot(OutputSetupSnapshotState.READY),
            folder = FolderSetupSnapshot(FolderSetupSnapshotState.READY),
        )
        assertFalse(router.route(stale))
        assertEquals(0, callCount)
    }

    private fun state(
        modelReady: Boolean = true,
        filter: TaskListFilter = TaskListFilter.NEW,
        entries: List<AudioCatalogEntry> = emptyList(),
        transcription: TranscriptionTaskSnapshot = TranscriptionTaskSnapshot(),
        eligible: Boolean = true,
    ) = AndroidTaskListSnapshotMapper.state(
        AndroidMainScreenInput(
            filter = filter,
            model = ModelSetupSnapshot(if (modelReady) ModelSetupSnapshotState.READY else ModelSetupSnapshotState.REQUIRED),
            output = OutputSetupSnapshot(OutputSetupSnapshotState.READY),
            folder = FolderSetupSnapshot(FolderSetupSnapshotState.READY),
            entries = entries,
            transcription = transcription,
            transcriptionEligible = eligible,
            hydration = AndroidMainScreenHydration(true, true, true, true),
        ),
    )

    private fun onboardingState(
        model: ModelSetupSnapshot,
        output: OutputSetupSnapshot = OutputSetupSnapshot(OutputSetupSnapshotState.REQUIRED),
        folder: FolderSetupSnapshot = FolderSetupSnapshot(FolderSetupSnapshotState.UNSELECTED),
    ) = AndroidTaskListSnapshotMapper.state(stateInput(model, output, folder))

    private fun stateInput(
        model: ModelSetupSnapshot,
        output: OutputSetupSnapshot = OutputSetupSnapshot(OutputSetupSnapshotState.REQUIRED),
        folder: FolderSetupSnapshot = FolderSetupSnapshot(FolderSetupSnapshotState.UNSELECTED),
        lifecycle: AndroidOnboardingHintLifecycle = AndroidOnboardingHintLifecycle.ACTIVE,
    ) = AndroidMainScreenInput(
        model = model,
        output = output,
        folder = folder,
        hydration = AndroidMainScreenHydration(true, true, true, true),
        onboardingLifecycle = lifecycle,
    )

    private fun request(stableId: String, entryId: Long?, kind: TaskActionKind) =
        AndroidTaskActionRequest(stableId, entryId, kind)

    private fun entry(
        id: Long,
        state: AudioFileState = AudioFileState.PENDING,
        transcript: String? = null,
    ) = AudioCatalogEntry(
        id = id,
        folderUri = AndroidAudioImportConstants.SOURCE_ID,
        documentUri = "content://audio/$id",
        displayName = "$id.ogg",
        mimeType = "audio/ogg",
        fingerprint = AudioFileFingerprint(1024, id),
        state = state,
        stateBeforeMissing = null,
        lastError = null,
        processedAtMillis = id.takeIf { state == AudioFileState.PROCESSED || state == AudioFileState.FAILED },
        transcriptText = transcript,
    )
}
