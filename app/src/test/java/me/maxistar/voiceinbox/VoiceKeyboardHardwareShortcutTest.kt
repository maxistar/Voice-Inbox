package me.maxistar.voiceinbox

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceKeyboardHardwareShortcutTest {
    @Test
    fun volumeDownStartsAndStopsOnlyWhenSelectedAndInputViewIsActive() {
        val selected = VoiceKeyboardTriggerSettings(VoiceKeyboardHardwareTrigger.VOLUME_DOWN)
        assertEquals(
            VoiceKeyboardVolumeShortcutAction.START,
            action(selected, inputViewActive = true, keyCode = KeyEvent.KEYCODE_VOLUME_DOWN),
        )
        assertEquals(
            VoiceKeyboardVolumeShortcutAction.STOP,
            action(
                selected,
                inputViewActive = true,
                keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
                phase = VoiceKeyboardPhase.RECORDING,
            ),
        )
        assertEquals(
            VoiceKeyboardVolumeShortcutAction.PASS_THROUGH,
            action(selected, inputViewActive = false, keyCode = KeyEvent.KEYCODE_VOLUME_DOWN),
        )
        assertEquals(
            VoiceKeyboardVolumeShortcutAction.PASS_THROUGH,
            action(selected, inputViewActive = true, keyCode = KeyEvent.KEYCODE_F10),
        )
    }

    @Test
    fun assignedFunctionKeyWorksWithImeSessionEvenWhenInputViewIsHidden() {
        val selected = VoiceKeyboardTriggerSettings(
            VoiceKeyboardHardwareTrigger.PHYSICAL_KEY,
            KeyEvent.KEYCODE_F10,
        )
        assertEquals(
            VoiceKeyboardVolumeShortcutAction.START,
            action(selected, inputActive = true, inputViewActive = false, keyCode = KeyEvent.KEYCODE_F10),
        )
        assertEquals(
            VoiceKeyboardVolumeShortcutAction.PASS_THROUGH,
            action(selected, inputActive = false, inputViewActive = true, keyCode = KeyEvent.KEYCODE_F10),
        )
        assertEquals(
            VoiceKeyboardVolumeShortcutAction.PASS_THROUGH,
            action(selected, inputActive = true, keyCode = KeyEvent.KEYCODE_F9),
        )
        assertEquals(
            VoiceKeyboardVolumeShortcutAction.PASS_THROUGH,
            action(selected, inputActive = true, keyCode = KeyEvent.KEYCODE_VOLUME_DOWN),
        )
    }

    @Test
    fun touchOnlyPassesAllHardwareEventsThrough() {
        val selected = VoiceKeyboardTriggerSettings(VoiceKeyboardHardwareTrigger.TOUCH_ONLY)
        listOf(KeyEvent.KEYCODE_F10, KeyEvent.KEYCODE_VOLUME_DOWN).forEach { keyCode ->
            assertEquals(
                VoiceKeyboardVolumeShortcutAction.PASS_THROUGH,
                action(
                    selected,
                    inputActive = true,
                    inputViewActive = true,
                    keyCode = keyCode,
                ),
            )
        }
        assertFalse(
            VoiceKeyboardHardwareShortcut.shouldConsumeMultiple(
                selected,
                inputActive = true,
                inputViewActive = true,
                keyCode = KeyEvent.KEYCODE_F10,
            ),
        )
    }

    @Test
    fun selectedTriggerConsumesRepeatKeyUpAndBusyEventsWithoutToggling() {
        val selected = VoiceKeyboardTriggerSettings(
            VoiceKeyboardHardwareTrigger.PHYSICAL_KEY,
            KeyEvent.KEYCODE_F2,
        )
        listOf(
            VoiceKeyboardPhase.WAITING_FOR_MODEL,
            VoiceKeyboardPhase.TRANSCRIBING,
            VoiceKeyboardPhase.RESULT_PENDING,
        ).forEach { phase ->
            assertEquals(
                VoiceKeyboardVolumeShortcutAction.CONSUME,
                action(selected, keyCode = KeyEvent.KEYCODE_F2, phase = phase),
            )
        }
        assertEquals(
            VoiceKeyboardVolumeShortcutAction.CONSUME,
            action(selected, keyCode = KeyEvent.KEYCODE_F2, repeatCount = 1),
        )
        assertEquals(
            VoiceKeyboardVolumeShortcutAction.CONSUME,
            action(selected, keyCode = KeyEvent.KEYCODE_F2, keyAction = KeyEvent.ACTION_UP),
        )
        assertTrue(
            VoiceKeyboardHardwareShortcut.shouldConsumeMultiple(
                selected,
                inputActive = true,
                inputViewActive = false,
                keyCode = KeyEvent.KEYCODE_F2,
            ),
        )
    }

    private fun action(
        settings: VoiceKeyboardTriggerSettings,
        inputActive: Boolean = true,
        inputViewActive: Boolean = true,
        keyCode: Int,
        keyAction: Int = KeyEvent.ACTION_DOWN,
        repeatCount: Int = 0,
        phase: VoiceKeyboardPhase = VoiceKeyboardPhase.IDLE,
    ): VoiceKeyboardVolumeShortcutAction = VoiceKeyboardHardwareShortcut.action(
        settings = settings,
        inputActive = inputActive,
        inputViewActive = inputViewActive,
        keyCode = keyCode,
        keyAction = keyAction,
        repeatCount = repeatCount,
        phase = phase,
    )
}
