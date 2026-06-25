package me.maxistar.watchface.notesrecognition

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.containsString
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityInstrumentedTest {
    @Test
    fun selectionSummariesAndMenuActionsAreVisible() {
        clearActivityState()
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.statusTitle)).check(matches(isDisplayed()))
            onView(withText(R.string.output_not_selected)).check(matches(isDisplayed()))
            onView(withText(R.string.folder_not_selected)).check(matches(isDisplayed()))
            onView(withContentDescription(R.string.menu_refresh_folder)).check(matches(isDisplayed()))
            onView(withId(R.id.transcribeAll)).check(matches(isDisplayed()))

            openActionBarOverflowOrOptionsMenu(
                InstrumentationRegistry.getInstrumentation().targetContext,
            )
            onView(withText(R.string.menu_select_output)).check(matches(isDisplayed()))
            onView(withText(R.string.menu_select_folder)).check(matches(isDisplayed()))
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
                )
                val row = MainActivity::class.java
                    .getDeclaredMethod("createEntryView", AudioCatalogEntry::class.java)
                    .apply { isAccessible = true }
                    .invoke(activity, entry) as android.view.View
                activity.findViewById<android.widget.LinearLayout>(R.id.fileList).addView(row)
            }

            onView(withText("broken.wav")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText(containsString("decode failed"))).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Play")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Retry")).perform(scrollTo()).check(matches(isDisplayed()))
        }
    }

    private fun clearActivityState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences(DocumentSelectionStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.deleteDatabase(AudioCatalogDatabase.DATABASE_NAME)
    }
}
