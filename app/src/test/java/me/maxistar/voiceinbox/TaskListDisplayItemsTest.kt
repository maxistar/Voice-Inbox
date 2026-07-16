package me.maxistar.voiceinbox

import me.maxistar.voiceinbox.core.AudioFileState
import me.maxistar.voiceinbox.core.AudioTaskSnapshot
import me.maxistar.voiceinbox.core.FolderSetupSnapshot
import me.maxistar.voiceinbox.core.FolderSetupSnapshotState
import me.maxistar.voiceinbox.core.ModelSetupSnapshot
import me.maxistar.voiceinbox.core.ModelSetupSnapshotState
import me.maxistar.voiceinbox.core.OutputSetupSnapshot
import me.maxistar.voiceinbox.core.OutputSetupSnapshotState
import me.maxistar.voiceinbox.core.TaskActionKind
import me.maxistar.voiceinbox.core.TaskListFilter
import me.maxistar.voiceinbox.core.TaskListInput
import me.maxistar.voiceinbox.core.TaskListPresentationController
import me.maxistar.voiceinbox.core.TranscriptionTaskSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskListDisplayItemsTest {
    @Test
    fun setupBatchAndAudioItemsHaveStableKindsAndPlacement() {
        val items = items(
            filter = TaskListFilter.NEW,
            model = ModelSetupSnapshot(ModelSetupSnapshotState.REQUIRED, downloadAvailable = true),
            output = OutputSetupSnapshot(OutputSetupSnapshotState.REQUIRED),
            audio = listOf(pending(7)),
        )

        assertEquals(
            listOf(
                TaskListDisplayItem.Setup::class,
                TaskListDisplayItem.Setup::class,
                TaskListDisplayItem.BatchAction::class,
                TaskListDisplayItem.Audio::class,
            ),
            items.map { it::class },
        )
        assertEquals(listOf("setup:model", "setup:output", "action:transcribe-all", "audio:7"), items.map { it.stableKey })
        assertEquals(1, (items[2] as TaskListDisplayItem.BatchAction).eligibleCount)
    }

    @Test
    fun filterAndContentChangesPreserveIdentityButUpdateContents() {
        val idle = items(audio = listOf(pending(7))).single { it is TaskListDisplayItem.Audio }
        val active = items(
            audio = listOf(pending(7)),
            transcription = TranscriptionTaskSnapshot(active = true, activeEntryId = 7, phase = "Transcribing", percent = 20),
        ).single { it is TaskListDisplayItem.Audio }

        assertTrue(TaskListDisplayItemDiff.areItemsTheSame(idle, active))
        assertFalse(TaskListDisplayItemDiff.areContentsTheSame(idle, active))
        assertEquals("Transcribing", (active as TaskListDisplayItem.Audio).task.progress?.phase)
        assertEquals(20, active.task.progress?.percent)

        val processed = items(filter = TaskListFilter.PROCESSED, audio = listOf(pending(7)))
        assertTrue(processed.single() is TaskListDisplayItem.Empty)
        assertNotEquals(idle.stableKey, processed.single().stableKey)
    }

    @Test
    fun emptyStateCarriesOnlyPresentedTypedActions() {
        val item = items().single() as TaskListDisplayItem.Empty

        assertEquals("No new tasks", item.message)
        assertEquals(
            listOf(TaskActionKind.IMPORT_AUDIO, TaskActionKind.SELECT_FOLDER),
            item.actions.map { it.kind },
        )
    }

    private fun items(
        filter: TaskListFilter = TaskListFilter.NEW,
        model: ModelSetupSnapshot = ModelSetupSnapshot(ModelSetupSnapshotState.READY),
        output: OutputSetupSnapshot = OutputSetupSnapshot(OutputSetupSnapshotState.READY),
        audio: List<AudioTaskSnapshot> = emptyList(),
        transcription: TranscriptionTaskSnapshot = TranscriptionTaskSnapshot(),
    ): List<TaskListDisplayItem> = TaskListDisplayItems.from(
        TaskListPresentationController.state(
            TaskListInput(
                filter = filter,
                model = model,
                output = output,
                folder = FolderSetupSnapshot(FolderSetupSnapshotState.READY),
                audio = audio,
                transcription = transcription,
            ),
        ),
    )

    private fun pending(id: Long) = AudioTaskSnapshot(
        entryId = id,
        title = "$id.ogg",
        state = AudioFileState.PENDING,
        importedAtMillis = id,
    )
}
