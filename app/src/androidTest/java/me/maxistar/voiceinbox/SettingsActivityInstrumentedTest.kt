package me.maxistar.voiceinbox

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.maxistar.voiceinbox.core.StartupProcessingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsActivityInstrumentedTest {
    @Test
    fun startupPolicyIsDisplayedAndPersistsWithoutChangingNightlySettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        clearSettings(context)
        val scheduledStore = ScheduledTranscriptionSettingsStore(
            context.getSharedPreferences(
                ScheduledTranscriptionSettingsStore.PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            ),
        )
        scheduledStore.setEnabled(true)
        scheduledStore.setTime(4, 30)

        ActivityScenario.launch(SettingsActivity::class.java).use {
            onView(withText(R.string.settings_storage_title)).check(matches(isDisplayed()))
            onView(withText(R.string.settings_startup_processing_title))
                .perform(scrollTo())
                .check(matches(isDisplayed()))
            onView(withId(R.id.startupProcessingAsk)).check(matches(isChecked()))
            onView(withId(R.id.startupProcessingAutomatic)).perform(scrollTo(), click())
            onView(withId(R.id.scheduledSwitch)).perform(scrollTo()).check(matches(isChecked()))
            onView(withText(R.string.settings_about_title)).perform(scrollTo()).check(matches(isDisplayed()))
        }

        val startupStore = StartupProcessingPolicyStore(
            context.getSharedPreferences(StartupProcessingPolicyStore.PREFERENCES_NAME, Context.MODE_PRIVATE),
        )
        assertEquals(StartupProcessingPolicy.AUTOMATIC, startupStore.load())
        assertTrue(scheduledStore.load().enabled)
        assertEquals(4, scheduledStore.load().hour)
        assertEquals(30, scheduledStore.load().minute)
    }

    private fun clearSettings(context: Context) {
        context.getSharedPreferences(StartupProcessingPolicyStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.getSharedPreferences(ScheduledTranscriptionSettingsStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }
}
