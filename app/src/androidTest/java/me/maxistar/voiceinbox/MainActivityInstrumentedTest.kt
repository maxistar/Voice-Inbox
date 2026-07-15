package me.maxistar.voiceinbox

import me.maxistar.voiceinbox.core.*

import android.content.Context
import android.net.Uri
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
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

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
            onView(withContentDescription(R.string.menu_refresh_folder)).check(matches(isDisplayed()))
            onView(withId(R.id.transcribeAll)).check(matches(not(isDisplayed())))

            openActionBarOverflowOrOptionsMenu(
                InstrumentationRegistry.getInstrumentation().targetContext,
            )
            onView(withText(R.string.menu_settings)).check(matches(isDisplayed()))
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
        context.getSharedPreferences(DocumentSelectionStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.getSharedPreferences(StartupProcessingPolicyStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.deleteDatabase(AndroidSqlDelightAudioCatalogFactory.DATABASE_NAME)
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
