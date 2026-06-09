package me.maxistar.watchface.notesrecognition

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.not
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityInstrumentedTest {
    @Test
    fun fileControlsAreDisabledBeforeModelIsReady() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.modelStatus)).check(matches(isDisplayed()))
            onView(withId(R.id.selectOutput)).check(matches(not(isEnabled())))
            onView(withId(R.id.selectAudio)).check(matches(not(isEnabled())))
        }
    }
}
