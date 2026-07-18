package me.maxistar.voiceinbox

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import me.maxistar.voiceinbox.core.AudioFileState
import me.maxistar.voiceinbox.core.AudioTaskPresentation
import me.maxistar.voiceinbox.core.AndroidSqlDelightAudioCatalogFactory
import me.maxistar.voiceinbox.core.ModelSetupSnapshotState
import me.maxistar.voiceinbox.core.TaskActionKind
import me.maxistar.voiceinbox.core.TaskListFilter
import me.maxistar.voiceinbox.core.TranscriptionObservationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MainActivityInstrumentedTest {
    @Test
    fun taskListShellAndCurrentMenuActionsAreVisible() {
        clearActivityState()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.importAudio)).check(matches(isDisplayed()))
            onView(withId(R.id.newTab)).check(matches(isDisplayed()))
            onView(withId(R.id.processedTab)).check(matches(isDisplayed()))
            onView(withId(R.id.allTab)).check(matches(isDisplayed()))
            onView(withId(R.id.taskList)).check(matches(isDisplayed()))

            awaitActivity(scenario) { activity ->
                descendants(activity.findViewById<RecyclerView>(R.id.taskList))
                    .filterIsInstance<android.widget.Button>()
                    .any()
            }
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<android.view.View>(R.id.importAudio) is FloatingActionButton)
                val rowButtons = descendants(activity.findViewById<RecyclerView>(R.id.taskList))
                    .filterIsInstance<android.widget.Button>()
                    .toList()
                assertTrue(rowButtons.isNotEmpty())
                assertTrue(rowButtons.all { it is MaterialButton })
            }

            openActionBarOverflowOrOptionsMenu(InstrumentationRegistry.getInstrumentation().targetContext)
            onView(withText(R.string.menu_settings)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun setupSelectionActionsRemainAvailableBeforeModelReadiness() {
        clearActivityState()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                setField(activity, "modelReady", false)
                setField(activity, "modelSetupState", ModelSetupSnapshotState.REQUIRED)
                setField(activity, "modelDownloadAvailable", true)
                setField(activity, "transcriptionState", TranscriptionObservationState.IDLE)
                invoke(activity, "updateControls")
            }

            awaitActivity(scenario) { activity ->
                val setup = displayItems(activity).filterIsInstance<TaskListDisplayItem.Setup>()
                setup.any { row -> row.task.actions.any { it.kind == TaskActionKind.DOWNLOAD_MODEL && it.enabled } } &&
                    setup.any { row -> row.task.actions.any { it.kind == TaskActionKind.IMPORT_MODEL && it.enabled } } &&
                    setup.any { row -> row.task.actions.any { it.kind == TaskActionKind.SELECT_OUTPUT && it.enabled } }
            }
        }
    }

    @Test
    fun filtersUseOneStableDisplayListAndRestoreAcrossRecreation() {
        clearActivityState()
        val imported = Uri.parse(AndroidAudioImportConstants.SOURCE_ID)
        seedCatalogEntry(imported, "pending.ogg", AudioFileState.PENDING, 10)
        seedCatalogEntry(imported, "done.ogg", AudioFileState.PROCESSED, 20)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitActivity(scenario) { activity -> audioTitles(activity) == listOf("pending.ogg") }
            scenario.onActivity { it.findViewById<android.widget.Button>(R.id.allTab).performClick() }
            awaitActivity(scenario) { activity -> audioTitles(activity).toSet() == setOf("pending.ogg", "done.ogg") }

            val before = mutableMapOf<String, Long>()
            scenario.onActivity { activity ->
                displayItems(activity).forEach { before[it.stableKey] = TaskListAdapter.stableLongId(it.stableKey) }
            }
            scenario.recreate()

            awaitActivity(scenario) { activity ->
                selectedFilter(activity) == TaskListFilter.ALL &&
                    audioTitles(activity).toSet() == setOf("pending.ogg", "done.ogg") &&
                    displayItems(activity).all { TaskListAdapter.stableLongId(it.stableKey) == before[it.stableKey] }
            }
        }
    }

    @Test
    fun activeModelInstallationReattachesAcrossRecreation() {
        clearActivityState()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val request = OneTimeWorkRequestBuilder<SpeechModelImportWorker>()
            .setInitialDelay(1, TimeUnit.DAYS)
            .setInputData(
                androidx.work.workDataOf(
                    SpeechModelImportWorker.KEY_TREE_URI to "content://model/tree/root",
                ),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            SpeechModelInstallationWork.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        ).result.get(30, TimeUnit.SECONDS)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitActivity(scenario) { activity -> activeModelRow(activity) }
            scenario.recreate()
            awaitActivity(scenario) { activity -> activeModelRow(activity) }
        }
    }

    @Test
    fun importOnlyProcessingRowsSurviveForegroundAndRecreationWithoutFolder() {
        clearActivityState()
        val imported = Uri.parse(AndroidAudioImportConstants.SOURCE_ID)
        seedCatalogEntry(imported, "processing.ogg", AudioFileState.PROCESSING, 20)
        seedCatalogEntry(imported, "pending.ogg", AudioFileState.PENDING, 10)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitActivity(scenario) { activity ->
                audioTitles(activity).toSet() == setOf("processing.ogg", "pending.ogg")
            }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            awaitActivity(scenario) { activity ->
                audioTitles(activity).toSet() == setOf("processing.ogg", "pending.ogg")
            }
            scenario.recreate()
            awaitActivity(scenario) { activity ->
                audioTitles(activity).toSet() == setOf("processing.ogg", "pending.ogg")
            }
        }
    }

    @Test
    fun failedNoSpeechAndTranscriptActionsAppearInProcessedAndAll() {
        clearActivityState()
        val imported = Uri.parse(AndroidAudioImportConstants.SOURCE_ID)
        seedCatalogEntry(imported, "failed.ogg", AudioFileState.FAILED, 10, "decode failed")
        seedCatalogEntry(imported, "silent.ogg", AudioFileState.FAILED, 20, "No speech detected")
        seedCatalogEntry(imported, "done.ogg", AudioFileState.PROCESSED, 30, transcript = "recognized words")

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.findViewById<android.widget.Button>(R.id.processedTab).performClick() }
            awaitActivity(scenario) { activity ->
                val rows = audioRows(activity).associateBy { it.task.title }
                rows.keys == setOf("failed.ogg", "silent.ogg", "done.ogg") &&
                    rows.getValue("failed.ogg").task.actions.any { it.kind == TaskActionKind.RETRY_TRANSCRIPTION } &&
                    rows.getValue("silent.ogg").task.badge == "No speech" &&
                    rows.getValue("done.ogg").task.actions.any { it.kind == TaskActionKind.SHOW_TEXT }
            }
        }
    }

    @Test
    fun downloadAndImportUseOneInstallationWorkSlot() {
        clearActivityState()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val importRequest = OneTimeWorkRequestBuilder<SpeechModelImportWorker>()
            .setInitialDelay(1, TimeUnit.DAYS)
            .setInputData(
                androidx.work.workDataOf(
                    SpeechModelImportWorker.KEY_TREE_URI to "content://model/tree/root",
                ),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            SpeechModelInstallationWork.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            importRequest,
        ).result.get(30, TimeUnit.SECONDS)

        SpeechModelDownloadWorker.enqueue(context)

        val active = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(SpeechModelInstallationWork.UNIQUE_WORK_NAME)
            .get(30, TimeUnit.SECONDS)
            .filter { !it.state.isFinished }
        assertEquals(1, active.size)
        assertEquals(importRequest.id, active.single().id)
    }

    @Test
    fun refreshActionHasStableIdleBusyAndCompletedPresentation() {
        clearActivityState()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitActivity(scenario) { activity ->
                val hydration = stateHost(activity).currentInput.hydration
                hydration.modelKnown && hydration.outputKnown && hydration.folderKnown && hydration.catalogKnown
            }
            scenario.onActivity { activity ->
                val action = refreshAction(activity)
                assertEquals(android.view.View.GONE, action.visibility)

                stateHost(activity).update { input ->
                    input.copy(
                        folderSync = AndroidFolderSyncPresentation(
                            visible = true,
                            enabled = true,
                        ),
                    )
                }
            }
            awaitActivity(scenario) { activity ->
                val action = refreshAction(activity)
                action.visibility == android.view.View.VISIBLE && action.isEnabled
            }
            scenario.onActivity { activity ->
                val action = refreshAction(activity)
                assertEquals(android.view.View.VISIBLE, action.visibility)
                assertTrue(action.isEnabled)
                assertEquals(
                    AndroidFolderSyncPresentation.ACCESSIBILITY_REFRESH,
                    action.contentDescription,
                )
            }

            scenario.onActivity { activity ->
                stateHost(activity).update { input ->
                    input.copy(folderSync = AndroidFolderSyncPresentation(visible = true))
                }
            }
            awaitActivity(scenario) { activity -> !refreshAction(activity).isEnabled }
            scenario.onActivity { activity ->
                assertEquals(
                    AndroidFolderSyncPresentation.ACCESSIBILITY_REFRESH,
                    refreshAction(activity).contentDescription,
                )
            }

            scenario.onActivity { activity ->
                stateHost(activity).update { input ->
                    input.copy(
                        folderSync = AndroidFolderSyncPresentation(
                            visible = true,
                            active = true,
                            accessibilityLabel = AndroidFolderSyncPresentation.ACCESSIBILITY_REFRESHING,
                        ),
                    )
                }
                stateHost(activity).update { input ->
                    input.copy(folderSync = AndroidFolderSyncPresentation(visible = true, enabled = true))
                }
            }
            Thread.sleep(220)
            scenario.onActivity { activity -> assertEquals(0f, refreshAction(activity).rotation) }

            scenario.onActivity { activity ->
                stateHost(activity).update { input ->
                    input.copy(
                        folderSync = AndroidFolderSyncPresentation(
                            visible = true,
                            active = true,
                            accessibilityLabel = AndroidFolderSyncPresentation.ACCESSIBILITY_REFRESHING,
                        ),
                    )
                }
            }
            awaitActivity(scenario) { activity -> !refreshAction(activity).isEnabled }
            scenario.onActivity { activity ->
                val action = refreshAction(activity)
                assertFalse(action.isEnabled)
                assertEquals(
                    AndroidFolderSyncPresentation.ACCESSIBILITY_REFRESHING,
                    action.contentDescription,
                )
                val before = stateHost(activity).currentInput.folderSync
                action.performClick()
                assertEquals(before, stateHost(activity).currentInput.folderSync)
                assertFalse(stateHost(activity).folderSyncCoordinator.state.active)
            }
            Thread.sleep(250)
            scenario.onActivity { activity -> assertTrue(refreshAction(activity).rotation != 0f) }
            scenario.onActivity { activity ->
                stateHost(activity).update { input ->
                    input.copy(
                        folderSync = AndroidFolderSyncPresentation(
                            visible = true,
                            enabled = true,
                        ),
                    )
                }
            }
            Thread.sleep(500)
            awaitActivity(scenario) { activity ->
                val action = refreshAction(activity)
                action.isEnabled && action.rotation == 0f
            }
            scenario.onActivity { activity ->
                val action = refreshAction(activity)
                assertEquals(0f, action.rotation)
                assertTrue(action.isEnabled)
                stateHost(activity).update { input ->
                    input.copy(folderSync = AndroidFolderSyncPresentation())
                }
            }
            awaitActivity(scenario) { activity ->
                refreshAction(activity).visibility == android.view.View.GONE
            }
        }
    }

    @Test
    fun progressHeavyUpdatesKeepTheSameRowAndActionView() {
        clearActivityState()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitActivity(scenario) { activity -> stateHost(activity).currentInput.hydration.modelKnown }
            scenario.onActivity { activity ->
                stateHost(activity).replace(
                    AndroidMainScreenInput(
                        model = me.maxistar.voiceinbox.core.ModelSetupSnapshot(
                            state = ModelSetupSnapshotState.INSTALLING,
                            installationPhase = "Downloading",
                            progressPercent = 10,
                            canCancel = true,
                        ),
                        output = me.maxistar.voiceinbox.core.OutputSetupSnapshot(
                            me.maxistar.voiceinbox.core.OutputSetupSnapshotState.READY,
                        ),
                        folder = me.maxistar.voiceinbox.core.FolderSetupSnapshot(
                            me.maxistar.voiceinbox.core.FolderSetupSnapshotState.READY,
                        ),
                        hydration = AndroidMainScreenHydration(true, true, true, true),
                    ),
                )
            }
            awaitActivity(scenario) { activity -> displayItems(activity).singleOrNull()?.stableKey == "setup:model" }

            lateinit var rowBefore: android.view.View
            lateinit var actionBefore: android.view.View
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.taskList)
                rowBefore = list.layoutManager!!.findViewByPosition(0)!!
                actionBefore = rowBefore.findViewById<android.view.ViewGroup>(R.id.taskActions).getChildAt(0)
            }
            (20..100 step 10).forEach { percent ->
                scenario.onActivity { activity ->
                    stateHost(activity).update { input ->
                        input.copy(model = input.model.copy(progressPercent = percent))
                    }
                }
                awaitActivity(scenario) { activity ->
                    val model = displayItems(activity).singleOrNull() as? TaskListDisplayItem.Setup
                    model?.task?.progress?.percent == percent
                }
            }
            scenario.onActivity { activity ->
                val rowAfter = activity.findViewById<RecyclerView>(R.id.taskList)
                    .layoutManager!!.findViewByPosition(0)!!
                val actionAfter = rowAfter.findViewById<android.view.ViewGroup>(R.id.taskActions).getChildAt(0)
                assertSame(rowBefore, rowAfter)
                assertSame(actionBefore, actionAfter)
            }
        }
    }

    private fun clearActivityState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        WorkManager.getInstance(context).cancelUniqueWork(TranscriptionWorker.UNIQUE_WORK_NAME).result.get(30, TimeUnit.SECONDS)
        WorkManager.getInstance(context).cancelUniqueWork(SpeechModelInstallationWork.UNIQUE_WORK_NAME).result.get(30, TimeUnit.SECONDS)
        WorkManager.getInstance(context).pruneWork().result.get(30, TimeUnit.SECONDS)
        context.getSharedPreferences("speech_model_import", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(DocumentSelectionStore.PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(StartupProcessingPolicyStore.PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase(AndroidSqlDelightAudioCatalogFactory.DATABASE_NAME)
        java.io.File(context.filesDir, AndroidAudioImportConstants.DIRECTORY_NAME).deleteRecursively()
    }

    private fun seedCatalogEntry(
        source: Uri,
        displayName: String,
        state: AudioFileState,
        modifiedMillis: Long,
        error: String? = null,
        transcript: String? = null,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = AndroidSqlDelightAudioCatalogFactory(context).create()
        try {
            repository.upsertImportedFile(
                folderUri = source.toString(),
                documentUri = DocumentsContract.buildDocumentUri(TestAudioDocumentsProvider.AUTHORITY, displayName).toString(),
                displayName = displayName,
                mimeType = "audio/ogg",
                sizeBytes = 100,
                importedAtMillis = modifiedMillis,
                state = state,
                lastError = error,
                processedAtMillis = modifiedMillis.takeIf { state == AudioFileState.PROCESSED || state == AudioFileState.FAILED },
                transcriptText = transcript,
                durationUs = null,
            )
        } finally {
            repository.close()
        }
    }

    private fun activeModelRow(activity: MainActivity): Boolean =
        displayItems(activity)
            .filterIsInstance<TaskListDisplayItem.Setup>()
            .any { it.task.stableId == "setup:model" && it.task.progress != null }

    private fun selectedFilter(activity: MainActivity): TaskListFilter =
        stateHost(activity).state.value.taskList.filter

    private fun refreshAction(activity: MainActivity): android.view.View =
        activity.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
            .menu.findItem(R.id.menuRefreshFolder).actionView!!

    private fun stateHost(activity: MainActivity): AndroidMainScreenStateHost =
        MainActivity::class.java.getDeclaredMethod("getTaskStateHost")
            .apply { isAccessible = true }
            .invoke(activity) as AndroidMainScreenStateHost

    private fun displayItems(activity: MainActivity): List<TaskListDisplayItem> =
        (activity.findViewById<RecyclerView>(R.id.taskList).adapter as TaskListAdapter).currentList

    private fun audioRows(activity: MainActivity): List<TaskListDisplayItem.Audio> =
        displayItems(activity).filterIsInstance<TaskListDisplayItem.Audio>()

    private fun audioTitles(activity: MainActivity): List<String> = audioRows(activity).map { it.task.title }

    private fun descendants(root: android.view.ViewGroup): Sequence<android.view.View> = sequence {
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            yield(child)
            if (child is android.view.ViewGroup) yieldAll(descendants(child))
        }
    }

    private fun awaitActivity(
        scenario: ActivityScenario<MainActivity>,
        condition: (MainActivity) -> Boolean,
    ) {
        repeat(200) {
            var matches = false
            scenario.onActivity { activity -> matches = condition(activity) }
            if (matches) return
            Thread.sleep(25)
        }
        throw AssertionError("Activity did not reach expected state")
    }

    private fun setField(activity: MainActivity, name: String, value: Any) {
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }

    private fun invoke(activity: MainActivity, name: String) {
        MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    }
}
