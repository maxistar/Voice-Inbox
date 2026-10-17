package me.maxistar.voiceinbox

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceKeyboardTriggerSettingsStoreTest {
    @Test
    fun missingOrInvalidTriggerDefaultsToVolumeDown() {
        val storage = FakeStorage()
        val store = VoiceKeyboardTriggerSettingsStore(storage)

        assertEquals(VoiceKeyboardTriggerSettingsStore.DEFAULT_SETTINGS, store.load())

        storage.trigger = "UNKNOWN"
        assertEquals(VoiceKeyboardTriggerSettingsStore.DEFAULT_SETTINGS, store.load())
    }

    @Test
    fun supportedPhysicalKeyAndTriggerArePersistedTogether() {
        val storage = FakeStorage()
        val store = VoiceKeyboardTriggerSettingsStore(storage)
        val settings = VoiceKeyboardTriggerSettings(
            VoiceKeyboardHardwareTrigger.PHYSICAL_KEY,
            KeyEvent.KEYCODE_F10,
        )

        assertTrue(store.save(settings))
        assertEquals(settings, store.load())
        assertEquals("PHYSICAL_KEY", storage.trigger)
        assertEquals(KeyEvent.KEYCODE_F10, storage.keyCode)
    }

    @Test
    fun invalidPhysicalKeyAssignmentCannotBeSavedAndInvalidStoredPairDefaultsSafely() {
        val storage = FakeStorage()
        val store = VoiceKeyboardTriggerSettingsStore(storage)

        assertFalse(
            store.save(
                VoiceKeyboardTriggerSettings(
                    VoiceKeyboardHardwareTrigger.PHYSICAL_KEY,
                    KeyEvent.KEYCODE_A,
                ),
            ),
        )
        assertNull(storage.trigger)
        assertFalse(
            store.save(
                VoiceKeyboardTriggerSettings(VoiceKeyboardHardwareTrigger.PHYSICAL_KEY),
            ),
        )

        storage.trigger = "PHYSICAL_KEY"
        storage.keyCode = KeyEvent.KEYCODE_A
        assertEquals(VoiceKeyboardTriggerSettingsStore.DEFAULT_SETTINGS, store.load())
    }

    @Test
    fun changingToTouchOnlyRetainsPhysicalKeyAssignmentWithoutActivatingIt() {
        val storage = FakeStorage()
        val store = VoiceKeyboardTriggerSettingsStore(storage)
        assertTrue(
            store.save(
                VoiceKeyboardTriggerSettings(
                    VoiceKeyboardHardwareTrigger.PHYSICAL_KEY,
                    KeyEvent.KEYCODE_F2,
                ),
            ),
        )

        assertTrue(store.save(VoiceKeyboardTriggerSettings(VoiceKeyboardHardwareTrigger.TOUCH_ONLY)))
        assertEquals(
            VoiceKeyboardTriggerSettings(VoiceKeyboardHardwareTrigger.TOUCH_ONLY, KeyEvent.KEYCODE_F2),
            store.load(),
        )
        assertEquals(KeyEvent.KEYCODE_F2, storage.keyCode)
    }

    private class FakeStorage : VoiceKeyboardTriggerStorage {
        var trigger: String? = null
        var keyCode: Int? = null

        override fun loadTrigger(): String? = trigger
        override fun loadPhysicalKeyCode(): Int? = keyCode
        override fun save(trigger: String, physicalKeyCode: Int?) {
            this.trigger = trigger
            keyCode = physicalKeyCode
        }
    }
}
