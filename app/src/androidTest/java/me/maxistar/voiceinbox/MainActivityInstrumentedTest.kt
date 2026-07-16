package me.maxistar.voiceinbox

import me.maxistar.voiceinbox.core.*

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Button
import android.widget.LinearLayout
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MainActivityInstrumentedTest {
    @Test
    fun selectionSummariesAndCurrentMenuActionsAreVisible() {
        clearActivityState()
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.statusTitle)).check(matches(isDisplayed()))
            onView(withText(R.string.output_not_selected)).check(matches(isDisplayed()))
            onView(withText(R.string.folder_not_selected)).check(matches(isDisplayed()))
            onView(withId(R.id.selectOutput)).check(matches(isDisplayed()))
            onView(withId(R.id.selectFolder)).check(matches(isDisplayed()))
            onView(withId(R.id.importAudio)).check(matches(isDisplayed()))
            onView(withContentDescription(R.string.menu_refresh_folder)).check(matches(isDisplayed()))
            onView(withId(R.id.transcribeAll)).check(matches(not(isDisplayed())))

            openActionBarOverflowOrOptionsMenu(
                InstrumentationRegistry.getInstrumentation().targetContext,
            )
            onView(withText(R.string.menu_settings)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun storageSetupIgnoresModelReadinessButRespectsActiveWork() {
        clearActivityState()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                MainActivity::class.java.getDeclaredField("modelReady")
                    .apply { isAccessible = true }
                    .setBoolean(activity, false)
                MainActivity::class.java.getDeclaredField("modelLoading")
                    .apply { isAccessible = true }
                    .setBoolean(activity, true)
                MainActivity::class.java.getDeclaredField("transcriptionState")
                    .apply { isAccessible = true }
                    .set(activity, TranscriptionObservationState.IDLE)
                MainActivity::class.java.getDeclaredMethod("updateControls")
                    .apply { isAccessible = true }
                    .invoke(activity)
            }

            onView(withId(R.id.selectOutput)).check(matches(isEnabled()))
            onView(withId(R.id.selectFolder)).check(matches(isEnabled()))

            enqueueDelayedTranscription()
            awaitActivity(scenario) { activity ->
                transcriptionState(activity) == TranscriptionObservationState.ACTIVE &&
                    !activity.findViewById<Button>(R.id.selectOutput).isEnabled &&
                    !activity.findViewById<Button>(R.id.selectFolder).isEnabled
            }
        }
    }

    @Test
    fun transcribeAllIsVisibleOnlyOnNewTabWhenPendingWorkExists() {
        clearActivityState()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                MainActivity::class.java.getDeclaredField("modelReady")
                    .apply { isAccessible = true }
                    .setBoolean(activity, true)
                MainActivity::class.java.getDeclaredField("outputUri")
                    .apply { isAccessible = true }
                    .set(activity, Uri.parse("content://output"))
                MainActivity::class.java.getDeclaredField("folderUri")
                    .apply { isAccessible = true }
                    .set(activity, Uri.parse("content://folder"))
                MainActivity::class.java.getDeclaredField("pendingCount")
                    .apply { isAccessible = true }
                    .setInt(activity, 2)
                MainActivity::class.java.getDeclaredField("transcriptionState")
                    .apply { isAccessible = true }
                    .set(activity, TranscriptionObservationState.IDLE)
                MainActivity::class.java.getDeclaredMethod("updateControls")
                    .apply { isAccessible = true }
                    .invoke(activity)
            }

            onView(withId(R.id.transcribeAll)).perform(scrollTo()).check(matches(isDisplayed()))

            scenario.onActivity { activity ->
                activity.findViewById<android.widget.Button>(R.id.processedTab).performClick()
                MainActivity::class.java.getDeclaredMethod("updateControls")
                    .apply { isAccessible = true }
                    .invoke(activity)
            }

            onView(withId(R.id.transcribeAll)).check(matches(not(isDisplayed())))
        }
    }

    @Test
    fun activityRecreationKeepsStatusBlockVisible() {
        clearActivityState()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.recreate()

            onView(withId(R.id.statusTitle)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun recreationRestoresFolderOutputAndProcessingRows() {
        clearActivityState()
        val folder = DocumentsContract.buildTreeDocumentUri(
            TestAudioDocumentsProvider.AUTHORITY,
            TestAudioDocumentsProvider.ROOT_ID,
        )
        val output = DocumentsContract.buildDocumentUri(
            TestAudioDocumentsProvider.AUTHORITY,
            "output",
        )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val store = DocumentSelectionStore(
                    activity.getSharedPreferences(
                        DocumentSelectionStore.PREFERENCES_NAME,
                        Context.MODE_PRIVATE,
                    ),
                )
                store.saveFolderUri(folder.toString())
                store.saveOutputUri(output.toString())
                MainActivity::class.java.getDeclaredMethod(
                    "restoreSelections",
                    Boolean::class.javaPrimitiveType,
                ).apply { isAccessible = true }.invoke(activity, false)
            }
            awaitActivity(scenario) { activity ->
                booleanField(activity, "folderAccessReady") &&
                    booleanField(activity, "outputAccessReady")
            }

            seedCatalogEntry(folder, "processing.wav", AudioFileState.PROCESSING, 20)
            seedCatalogEntry(folder, "pending.wav", AudioFileState.PENDING, 10)
            enqueueDelayedTranscription()
            scenario.onActivity { activity -> invokeRefreshCatalog(activity) }
            awaitActivity(scenario) { activity ->
                transcriptionState(activity) == TranscriptionObservationState.ACTIVE &&
                    rowNames(activity).containsAll(listOf("processing.wav", "pending.wav"))
            }

            scenario.recreate()

            awaitActivity(scenario) { activity ->
                booleanField(activity, "folderAccessReady") &&
                    booleanField(activity, "outputAccessReady") &&
                    transcriptionState(activity) == TranscriptionObservationState.ACTIVE &&
                    rowNames(activity).containsAll(listOf("processing.wav", "pending.wav"))
            }
            onView(withText(containsString("Test audio folder"))).check(matches(isDisplayed()))
            onView(withText(containsString("transcripts.txt"))).check(matches(isDisplayed()))
            onView(withContentDescription("processing.wav")).check(matches(isDisplayed()))
            awaitActivity(scenario) { activity ->
                rowButton(activity, "processing.wav", "Play")?.isEnabled == false
            }
        }
    }

    @Test
    fun importOnlyProcessingRowsSurviveRecreationWithoutFolder() {
        clearActivityState()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = DocumentsContract.buildDocumentUri(
            TestAudioDocumentsProvider.AUTHORITY,
            "output",
        )
        DocumentSelectionStore(
            context.getSharedPreferences(
                DocumentSelectionStore.PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            ),
        ).saveOutputUri(output.toString())
        seedCatalogEntry(
            Uri.parse(AndroidAudioImportConstants.SOURCE_ID),
            "import-processing.ogg",
            AudioFileState.PROCESSING,
            20,
        )
        seedCatalogEntry(
            Uri.parse(AndroidAudioImportConstants.SOURCE_ID),
            "import-pending.ogg",
            AudioFileState.PENDING,
            10,
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitRows(scenario, setOf("import-processing.ogg", "import-pending.ogg"))
            scenario.recreate()
            awaitRows(scenario, setOf("import-processing.ogg", "import-pending.ogg"))

            onView(withText(R.string.folder_not_selected)).check(matches(isDisplayed()))
            onView(withText(containsString("transcripts.txt"))).check(matches(isDisplayed()))
            onView(withContentDescription("import-processing.ogg")).check(matches(isDisplayed()))
        }
    }

    @Test
    fun transientFolderValidationFailureKeepsDurableSelection() {
        clearActivityState()
        val folder = DocumentsContract.buildTreeDocumentUri(
            TestAudioDocumentsProvider.AUTHORITY,
            TestAudioDocumentsProvider.ROOT_ID,
        )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                DocumentSelectionStore(
                    activity.getSharedPreferences(
                        DocumentSelectionStore.PREFERENCES_NAME,
                        Context.MODE_PRIVATE,
                    ),
                ).saveFolderUri(folder.toString())
                MainActivity::class.java.getDeclaredField("selectionAccess")
                    .apply { isAccessible = true }
                    .set(
                        activity,
                        object : PersistedSelectionAccess(activity.contentResolver) {
                            override fun <T> validate(
                                uri: Uri,
                                requiredAccess: RequiredDocumentAccess,
                                validation: () -> T,
                            ): PersistedSelectionValidation<T> = PersistedSelectionValidation(
                                state = PersistedSelectionAccessState.TEMPORARILY_UNAVAILABLE,
                                error = SecurityException("Temporary provider failure"),
                            )
                        },
                    )
                MainActivity::class.java.getDeclaredMethod(
                    "restoreSelections",
                    Boolean::class.javaPrimitiveType,
                ).apply { isAccessible = true }.invoke(activity, false)
            }
            awaitActivity(scenario) { activity ->
                !booleanField(activity, "folderChecking") &&
                    !booleanField(activity, "folderAccessReady")
            }

            scenario.onActivity { activity ->
                val stored = DocumentSelectionStore(
                    activity.getSharedPreferences(
                        DocumentSelectionStore.PREFERENCES_NAME,
                        Context.MODE_PRIVATE,
                    ),
                ).loadFolderUri()
                assertEquals(folder.toString(), stored)
                assertEquals(
                    folder,
                    MainActivity::class.java.getDeclaredField("folderUri")
                        .apply { isAccessible = true }
                        .get(activity),
                )
            }
            onView(withText(R.string.folder_temporarily_unavailable))
                .check(matches(isDisplayed()))
        }
    }

    @Test
    fun rememberedStartupPromptActionsPersistTheirPolicies() {
        clearActivityState()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            showStartupPrompt(scenario, pendingCount = 2)
            onView(withText(R.string.startup_processing_prompt_title)).check(matches(isDisplayed()))
            onView(withText(R.string.startup_processing_remember_choice)).perform(click())
            onView(withText(R.string.startup_processing_process_now)).perform(click())
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = StartupProcessingPolicyStore(
            context.getSharedPreferences(StartupProcessingPolicyStore.PREFERENCES_NAME, Context.MODE_PRIVATE),
        )
        assertEquals(StartupProcessingPolicy.AUTOMATIC, store.load())

        clearActivityState()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            showStartupPrompt(scenario, pendingCount = 1)
            onView(withText(R.string.startup_processing_remember_choice)).perform(click())
            onView(withText(R.string.startup_processing_leave_queued)).perform(click())
        }

        assertEquals(StartupProcessingPolicy.LEAVE_QUEUED, store.load())
    }

    @Test
    fun startupPromptSurvivesActivityRecreationWithoutASecondDecision() {
        clearActivityState()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            showStartupPrompt(scenario, pendingCount = 2)
            scenario.recreate()

            onView(withText(R.string.startup_processing_prompt_title)).check(matches(isDisplayed()))
            onView(withText(R.string.startup_processing_leave_queued)).perform(click())
        }
    }

    @Test
    fun failedRowsShowErrorAndRetryAction() {
        clearActivityState()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val entry = AudioCatalogEntry(
                    id = 42,
                    folderUri = "content://folder",
                    documentUri = "content://audio/broken.wav",
                    displayName = "broken.wav",
                    mimeType = "audio/wav",
                    fingerprint = AudioFileFingerprint(sizeBytes = 1024, modifiedMillis = 10),
                    state = AudioFileState.FAILED,
                    stateBeforeMissing = null,
                    lastError = "decode failed",
                    processedAtMillis = null,
                    transcriptText = null,
                )
                val row = MainActivity::class.java
                    .getDeclaredMethod(
                        "createEntryView",
                        AudioCatalogEntry::class.java,
                        MainScreenRowState::class.java,
                    )
                    .apply { isAccessible = true }
                    .invoke(activity, entry, rowState(entry)) as android.view.View
                activity.findViewById<android.widget.LinearLayout>(R.id.fileList).addView(row)
            }

            onView(withText("broken.wav")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText(containsString("decode failed"))).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Play")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Retry")).perform(scrollTo()).check(matches(isDisplayed()))
        }
    }

    @Test
    fun processedRowContextMenuShowsStoredTranscriptText() {
        clearActivityState()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val entry = AudioCatalogEntry(
                    id = 43,
                    folderUri = "content://folder",
                    documentUri = "content://audio/done.wav",
                    displayName = "done.wav",
                    mimeType = "audio/wav",
                    fingerprint = AudioFileFingerprint(sizeBytes = 2048, modifiedMillis = 20),
                    state = AudioFileState.PROCESSED,
                    stateBeforeMissing = null,
                    lastError = null,
                    processedAtMillis = 500,
                    transcriptText = "recognized words from the note",
                )
                val row = MainActivity::class.java
                    .getDeclaredMethod(
                        "createEntryView",
                        AudioCatalogEntry::class.java,
                        MainScreenRowState::class.java,
                    )
                    .apply { isAccessible = true }
                    .invoke(activity, entry, rowState(entry)) as android.view.View
                activity.findViewById<android.widget.LinearLayout>(R.id.fileList).addView(row)
            }

            onView(withContentDescription("done.wav")).perform(longClick())
            onView(withText("Show text")).perform(click())
            onView(withText("recognized words from the note")).check(matches(isDisplayed()))
        }
    }

    private fun clearActivityState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        WorkManager.getInstance(context)
            .cancelUniqueWork(TranscriptionWorker.UNIQUE_WORK_NAME)
            .result.get(30, TimeUnit.SECONDS)
        WorkManager.getInstance(context).pruneWork().result.get(30, TimeUnit.SECONDS)
        context.getSharedPreferences(DocumentSelectionStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.getSharedPreferences(StartupProcessingPolicyStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.deleteDatabase(AndroidSqlDelightAudioCatalogFactory.DATABASE_NAME)
        java.io.File(context.filesDir, AndroidAudioImportConstants.DIRECTORY_NAME).deleteRecursively()
    }

    private fun seedCatalogEntry(
        source: Uri,
        displayName: String,
        state: AudioFileState,
        modifiedMillis: Long,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = AndroidSqlDelightAudioCatalogFactory(context).create()
        try {
            repository.upsertImportedFile(
                folderUri = source.toString(),
                documentUri = DocumentsContract.buildDocumentUri(
                    TestAudioDocumentsProvider.AUTHORITY,
                    displayName,
                ).toString(),
                displayName = displayName,
                mimeType = "audio/wav",
                sizeBytes = 100,
                importedAtMillis = modifiedMillis,
                state = state,
                lastError = null,
                processedAtMillis = null,
                transcriptText = null,
                durationUs = null,
            )
        } finally {
            repository.close()
        }
    }

    private fun enqueueDelayedTranscription() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setInitialDelay(1, TimeUnit.DAYS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            TranscriptionWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        ).result.get(30, TimeUnit.SECONDS)
    }

    private fun awaitRows(
        scenario: ActivityScenario<MainActivity>,
        expected: Set<String>,
    ) {
        awaitActivity(scenario) { activity -> rowNames(activity).containsAll(expected) }
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

    private fun booleanField(activity: MainActivity, name: String): Boolean =
        MainActivity::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
            .getBoolean(activity)

    private fun transcriptionState(activity: MainActivity): TranscriptionObservationState =
        MainActivity::class.java.getDeclaredField("transcriptionState")
            .apply { isAccessible = true }
            .get(activity) as TranscriptionObservationState

    private fun invokeRefreshCatalog(activity: MainActivity) {
        MainActivity::class.java.getDeclaredMethod(
            "refreshCatalog",
            java.lang.Long::class.java,
        ).apply { isAccessible = true }.invoke(activity, null)
    }

    private fun rowNames(activity: MainActivity): List<String> {
        val list = activity.findViewById<LinearLayout>(R.id.fileList)
        return (0 until list.childCount).mapNotNull { index ->
            list.getChildAt(index).contentDescription?.toString()
        }
    }

    private fun rowButton(activity: MainActivity, rowName: String, label: String): Button? {
        val list = activity.findViewById<LinearLayout>(R.id.fileList)
        val row = (0 until list.childCount)
            .map { index -> list.getChildAt(index) }
            .firstOrNull { it.contentDescription?.toString() == rowName }
            ?: return null
        return descendants(row as ViewGroup)
            .filterIsInstance<Button>()
            .firstOrNull { it.text.toString() == label }
    }

    private fun descendants(root: ViewGroup): Sequence<android.view.View> = sequence {
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            yield(child)
            if (child is ViewGroup) yieldAll(descendants(child))
        }
    }

    private fun showStartupPrompt(
        scenario: ActivityScenario<MainActivity>,
        pendingCount: Int,
    ) {
        scenario.onActivity { activity ->
            val coordinator = MainActivity::class.java.getDeclaredField("startupCoordinator")
                .apply { isAccessible = true }
                .get(activity) as StartupProcessingCoordinator
            coordinator.beginStartupScan(99)
            coordinator.setFolderReady(true)
            coordinator.setOutputReady(true)
            coordinator.setModelReady(true)
            coordinator.setTranscriptionState(known = true, active = false)
            coordinator.onStartupCatalogReady(99, pendingCount, failedCount = 0)
            MainActivity::class.java.getDeclaredMethod("evaluateStartupProcessing")
                .apply { isAccessible = true }
                .invoke(activity)
        }
    }

    private fun rowState(entry: AudioCatalogEntry): MainScreenRowState =
        MainScreenStateController.rowState(
            row = MainScreenRowInput(
                entryId = entry.id,
                state = entry.state,
                hasTranscriptText = !entry.transcriptText.isNullOrBlank(),
            ),
            activePreviewEntryId = null,
            previewState = PreviewPlaybackState.IDLE,
            transcriptionState = TranscriptionObservationState.IDLE,
            busy = false,
            retryEnabled = true,
        )
}
