package me.maxistar.voiceinbox

import me.maxistar.voiceinbox.core.FolderSetupSnapshot
import me.maxistar.voiceinbox.core.FolderSetupSnapshotState
import me.maxistar.voiceinbox.core.ModelSetupSnapshot
import me.maxistar.voiceinbox.core.ModelSetupSnapshotState
import me.maxistar.voiceinbox.core.OutputSetupSnapshot
import me.maxistar.voiceinbox.core.OutputSetupSnapshotState
import me.maxistar.voiceinbox.core.TaskActionKind
import me.maxistar.voiceinbox.core.TaskListFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidInlineOnboardingTest {
    @Test
    fun lifecycleDefaultsActiveAndPersistsTerminalValues() {
        val storage = FakeStorage()
        val store = AndroidOnboardingHintStore(storage)

        assertEquals(AndroidOnboardingHintLifecycle.ACTIVE, store.load())
        store.save(AndroidOnboardingHintLifecycle.DISMISSED)
        assertEquals(AndroidOnboardingHintLifecycle.DISMISSED, store.load())
        store.save(AndroidOnboardingHintLifecycle.COMPLETED)
        assertEquals(AndroidOnboardingHintLifecycle.COMPLETED, store.load())
        storage.raw = "future-value"
        assertEquals(AndroidOnboardingHintLifecycle.ACTIVE, store.load())
    }

    @Test
    fun pendingHydrationTerminalLifecycleAndOtherFiltersHideHint() {
        assertFalse(present(hydration = AndroidMainScreenHydration()).visible)
        assertFalse(present(lifecycle = AndroidOnboardingHintLifecycle.DISMISSED).visible)
        assertFalse(present(lifecycle = AndroidOnboardingHintLifecycle.COMPLETED).visible)
        assertFalse(present(filter = TaskListFilter.PROCESSED).visible)
        assertFalse(present(filter = TaskListFilter.ALL).visible)
    }

    @Test
    fun modelActionUsesDownloadRetryManualImportAndNeutralInstallingState() {
        assertEquals(TaskActionKind.DOWNLOAD_MODEL, present().action?.kind)
        assertEquals("Start setup", present().action?.label)

        val invalid = present(
            model = ModelSetupSnapshot(ModelSetupSnapshotState.INVALID, downloadAvailable = true),
        )
        assertEquals(TaskActionKind.RETRY_MODEL_DOWNLOAD, invalid.action?.kind)

        val manual = present(
            model = ModelSetupSnapshot(ModelSetupSnapshotState.REQUIRED, downloadAvailable = false),
        )
        assertEquals(TaskActionKind.IMPORT_MODEL, manual.action?.kind)

        val installing = present(
            model = ModelSetupSnapshot(ModelSetupSnapshotState.INSTALLING, downloadAvailable = true),
        )
        assertFalse(installing.action!!.enabled)
        assertEquals("Installing speech model…", installing.action?.label)
    }

    @Test
    fun directSetupCompletionAdvancesThroughOutputAndOptionalFolder() {
        val output = present(model = readyModel())
        assertEquals(TaskActionKind.CREATE_OUTPUT, output.action?.kind)
        assertEquals("Create Output File", output.action?.label)
        assertTrue(output.steps.single { it.kind == AndroidOnboardingStepKind.OUTPUT }.label.contains("Create or choose"))
        assertTrue(output.steps.first().complete)

        val folder = present(model = readyModel(), output = readyOutput())
        assertEquals(TaskActionKind.SELECT_FOLDER, folder.action?.kind)
        assertTrue(folder.steps.last().optional)
        assertTrue(folder.steps.last().label.contains("Optional"))
    }

    @Test
    fun fullyConfiguredSetupRetiresAndCompletesOnlyAfterHydration() {
        val readyFolder = FolderSetupSnapshot(FolderSetupSnapshotState.READY)
        assertFalse(present(model = readyModel(), output = readyOutput(), folder = readyFolder).visible)
        assertTrue(
            AndroidOnboardingHintPresenter.shouldComplete(
                AndroidOnboardingHintLifecycle.ACTIVE,
                hydrated(),
                readyModel(),
                readyOutput(),
                readyFolder,
            ),
        )
        assertFalse(
            AndroidOnboardingHintPresenter.shouldComplete(
                AndroidOnboardingHintLifecycle.ACTIVE,
                AndroidMainScreenHydration(),
                readyModel(),
                readyOutput(),
                readyFolder,
            ),
        )
    }

    private fun present(
        lifecycle: AndroidOnboardingHintLifecycle = AndroidOnboardingHintLifecycle.ACTIVE,
        filter: TaskListFilter = TaskListFilter.NEW,
        hydration: AndroidMainScreenHydration = hydrated(),
        model: ModelSetupSnapshot = ModelSetupSnapshot(ModelSetupSnapshotState.REQUIRED, downloadAvailable = true),
        output: OutputSetupSnapshot = OutputSetupSnapshot(OutputSetupSnapshotState.REQUIRED),
        folder: FolderSetupSnapshot = FolderSetupSnapshot(FolderSetupSnapshotState.UNSELECTED),
    ) = AndroidOnboardingHintPresenter.present(lifecycle, filter, hydration, model, output, folder)

    private fun hydrated() = AndroidMainScreenHydration(true, true, true, true)
    private fun readyModel() = ModelSetupSnapshot(ModelSetupSnapshotState.READY)
    private fun readyOutput() = OutputSetupSnapshot(OutputSetupSnapshotState.READY)

    private class FakeStorage(var raw: String? = null) : AndroidOnboardingHintStorage {
        override fun loadRaw(): String? = raw
        override fun saveRaw(value: String) {
            raw = value
        }
    }
}
