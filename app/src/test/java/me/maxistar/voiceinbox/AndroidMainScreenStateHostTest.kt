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
            ),
        )

        val tasks = state.taskList.tasks.filterIsInstance<SetupTaskPresentation>()
        assertEquals(listOf("setup:model", "setup:output", "setup:folder"), tasks.map { it.stableId })
        assertEquals("Verifying local model", tasks.first().progress?.phase)
        assertEquals(63, tasks.first().progress?.percent)
        assertEquals("Output unavailable", tasks[1].errorMessage)
        assertEquals("Scanning audio folder", tasks[2].progress?.phase)
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
        assertEquals("1 KiB", tasks.single { it.entryId == 1L }.detail)
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
        assertEquals("Transcribing", active.progress?.phase)
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
    )

    private fun entry(
        id: Long,
        state: AudioFileState,
        source: String = AndroidAudioImportConstants.SOURCE_ID,
        modified: Long = id,
        processed: Long? = null,
        error: String? = null,
        transcript: String? = null,
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
    )
}
