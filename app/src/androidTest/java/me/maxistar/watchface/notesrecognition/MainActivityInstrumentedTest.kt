package me.maxistar.watchface.notesrecognition

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityInstrumentedTest {
    @Test
    fun selectionSummariesAndMenuActionsAreVisible() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.modelStatus)).check(matches(isDisplayed()))
            onView(withText(R.string.output_not_selected)).check(matches(isDisplayed()))
            onView(withText(R.string.folder_not_selected)).check(matches(isDisplayed()))
            onView(withId(R.id.refreshFolder)).check(matches(isDisplayed()))
            onView(withId(R.id.transcribeAll)).check(matches(isDisplayed()))

            openActionBarOverflowOrOptionsMenu(
                InstrumentationRegistry.getInstrumentation().targetContext,
            )
            onView(withText(R.string.menu_select_output)).check(matches(isDisplayed()))
            onView(withText(R.string.menu_select_folder)).check(matches(isDisplayed()))
        }
    }
}
