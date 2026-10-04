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
import me.maxistar.voiceinbox.core.TaskProgressPresentation
import me.maxistar.voiceinbox.core.TaskText
import me.maxistar.voiceinbox.core.TaskTextKey
import me.maxistar.voiceinbox.core.TranscriptionTaskSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskListDisplayItemsTest {
    @Test
    fun activeBatchSuppressesStructuralRecyclerViewAnimationsOnlyWhileActive() {
        assertTrue(AndroidTaskListAnimationPolicy.suppressStructuralAnimations(true))
        assertFalse(AndroidTaskListAnimationPolicy.suppressStructuralAnimations(false))
    }

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
        assertEquals("Transcribing", (active as TaskListDisplayItem.Audio).task.progress?.phase?.fallback)
        assertEquals(20, active.task.progress?.percent)

        val processed = items(filter = TaskListFilter.PROCESSED, audio = listOf(pending(7)))
        assertTrue(processed.single() is TaskListDisplayItem.Empty)
        assertNotEquals(idle.stableKey, processed.single().stableKey)
    }

    @Test
    fun diffUsesTypedPayloadOnlyForProgressOnlyChanges() {
        val original = items(audio = listOf(pending(7)))
            .filterIsInstance<TaskListDisplayItem.Audio>()
            .single()
        val progress = TaskProgressPresentation(
            phase = TaskText(TaskTextKey.OPAQUE, "Transcribing", listOf("Transcribing")),
            percent = 35,
            processedUs = 3_000_000,
            durationUs = 10_000_000,
            completedFiles = 1,
            totalFiles = 3,
        )
        val progressUpdate = original.copy(task = original.task.copy(progress = progress))
        val badgeUpdate = progressUpdate.copy(
            task = progressUpdate.task.copy(badge = TaskText(TaskTextKey.OPAQUE, "Changed", listOf("Changed"))),
        )
        val actionsUpdate = progressUpdate.copy(task = progressUpdate.task.copy(actions = emptyList()))

        assertEquals(
            TaskListChangePayload.Progress(progress),
            TaskListDisplayItemDiff.getChangePayload(original, progressUpdate),
        )
        assertEquals(null, TaskListDisplayItemDiff.getChangePayload(progressUpdate, badgeUpdate))
        assertEquals(null, TaskListDisplayItemDiff.getChangePayload(progressUpdate, actionsUpdate))
        assertEquals(
            TaskListAdapter.stableLongId(original.stableKey),
            TaskListAdapter.stableLongId(progressUpdate.stableKey),
        )
    }

    @Test
    fun emptyStateCarriesOnlyPresentedTypedActions() {
        val item = items().single() as TaskListDisplayItem.Empty

        assertEquals(TaskTextKey.NO_NEW_TASKS, item.message.key)
        assertEquals(
            listOf(TaskActionKind.IMPORT_AUDIO),
            item.actions.map { it.kind },
        )
    }

    @Test
    fun onboardingHintIsStableAndOrderedAfterSetupBeforeBatchAudioAndEmpty() {
        val hint = onboardingHint()
        val withWork = items(
            model = ModelSetupSnapshot(ModelSetupSnapshotState.REQUIRED, downloadAvailable = true),
            output = OutputSetupSnapshot(OutputSetupSnapshotState.REQUIRED),
            audio = listOf(pending(1), pending(2), pending(3)),
            onboardingHint = hint,
        )
        assertEquals(
            listOf(
                "setup:model",
                "setup:output",
                TaskListDisplayItem.OnboardingHint.STABLE_KEY,
                TaskListDisplayItem.BatchAction.STABLE_KEY,
                "audio:3",
                "audio:2",
                "audio:1",
            ),
            withWork.map { it.stableKey },
        )

        val empty = items(onboardingHint = hint)
        assertTrue(empty[0] is TaskListDisplayItem.OnboardingHint)
        assertTrue(empty[1] is TaskListDisplayItem.Empty)

        val changedHint = hint.copy(
            steps = hint.steps.mapIndexed { index, step -> step.copy(complete = index == 0) },
            action = AndroidOnboardingHintAction(R.string.onboarding_action_select_output, true, TaskActionKind.SELECT_OUTPUT),
        )
        val oldItem = empty.first()
        val newItem = items(onboardingHint = changedHint).first()
        assertTrue(TaskListDisplayItemDiff.areItemsTheSame(oldItem, newItem))
        assertFalse(TaskListDisplayItemDiff.areContentsTheSame(oldItem, newItem))
    }

    @Test
    fun absentOrIneligibleHintLeavesProcessedAndAllListsUnchanged() {
        val hidden = items(filter = TaskListFilter.PROCESSED, onboardingHint = AndroidOnboardingHintPresentation.HIDDEN)
        assertTrue(hidden.single() is TaskListDisplayItem.Empty)
        assertTrue(items(filter = TaskListFilter.ALL).none { it is TaskListDisplayItem.OnboardingHint })
    }

    @Test
    fun keyboardDiscoveryIsStableDismissibleAndOrderedAfterOnboarding() {
        val onboarding = onboardingHint()
        val discovery = AndroidVoiceKeyboardDiscoveryPresentation(visible = true)
        val withBoth = items(onboardingHint = onboarding, keyboardDiscovery = discovery)

        assertEquals(
            listOf(
                TaskListDisplayItem.OnboardingHint.STABLE_KEY,
                TaskListDisplayItem.KeyboardDiscovery.STABLE_KEY,
                TaskListDisplayItem.Empty.STABLE_KEY,
            ),
            withBoth.map { it.stableKey },
        )
        val changed = discovery.copy(setupLabelRes = R.string.settings_voice_keyboard_choose)
        assertTrue(
            TaskListDisplayItemDiff.areItemsTheSame(
                withBoth[1],
                items(keyboardDiscovery = changed).first(),
            ),
        )
        assertFalse(
            TaskListDisplayItemDiff.areContentsTheSame(
                withBoth[1],
                items(keyboardDiscovery = changed).first(),
            ),
        )
    }

    private fun items(
        filter: TaskListFilter = TaskListFilter.NEW,
        model: ModelSetupSnapshot = ModelSetupSnapshot(ModelSetupSnapshotState.READY),
        output: OutputSetupSnapshot = OutputSetupSnapshot(OutputSetupSnapshotState.READY),
        audio: List<AudioTaskSnapshot> = emptyList(),
        transcription: TranscriptionTaskSnapshot = TranscriptionTaskSnapshot(),
        onboardingHint: AndroidOnboardingHintPresentation = AndroidOnboardingHintPresentation.HIDDEN,
        keyboardDiscovery: AndroidVoiceKeyboardDiscoveryPresentation = AndroidVoiceKeyboardDiscoveryPresentation.HIDDEN,
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
        onboardingHint,
        keyboardDiscovery,
    )

    private fun onboardingHint() = AndroidOnboardingHintPresentation(
        visible = true,
        steps = listOf(
            AndroidOnboardingChecklistStep(AndroidOnboardingStepKind.MODEL, R.string.onboarding_step_model, false),
            AndroidOnboardingChecklistStep(AndroidOnboardingStepKind.OUTPUT, R.string.onboarding_step_output, false),
            AndroidOnboardingChecklistStep(AndroidOnboardingStepKind.FOLDER, R.string.onboarding_step_folder, false, true),
            AndroidOnboardingChecklistStep(AndroidOnboardingStepKind.KEYBOARD, R.string.onboarding_step_keyboard, false, true),
        ),
        action = AndroidOnboardingHintAction(R.string.onboarding_action_start, true, TaskActionKind.DOWNLOAD_MODEL),
    )

    private fun pending(id: Long) = AudioTaskSnapshot(
        entryId = id,
        title = "$id.ogg",
        state = AudioFileState.PENDING,
        importedAtMillis = id,
    )
}
