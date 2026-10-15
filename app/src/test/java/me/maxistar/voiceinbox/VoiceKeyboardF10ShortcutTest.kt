package me.maxistar.voiceinbox

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceKeyboardF10ShortcutTest {
    @Test
    fun f10StartsFromIdleOrErrorWhenImeSessionIsActive() {
        assertEquals(
            VoiceKeyboardVolumeShortcutAction.START,
            action(inputActive = true, phase = VoiceKeyboardPhase.IDLE),
        )
        assertEquals(
            VoiceKeyboardVolumeShortcutAction.START,
            action(inputActive = true, phase = VoiceKeyboardPhase.ERROR),
        )
    }

    @Test
    fun f10StopsRecording() {
        assertEquals(
            VoiceKeyboardVolumeShortcutAction.STOP,
            action(inputActive = true, phase = VoiceKeyboardPhase.RECORDING),
        )
    }

    @Test
    fun f10IsConsumedDuringBusyPhases() {
        listOf(
            VoiceKeyboardPhase.WAITING_FOR_MODEL,
            VoiceKeyboardPhase.TRANSCRIBING,
            VoiceKeyboardPhase.RESULT_PENDING,
        ).forEach { phase ->
            assertEquals(
                VoiceKeyboardVolumeShortcutAction.CONSUME,
                action(inputActive = true, phase = phase),
            )
        }
    }

    @Test
    fun f10PassesThroughWhenImeSessionIsInactiveOrAnotherKeyIsPressed() {
        assertEquals(
            VoiceKeyboardVolumeShortcutAction.PASS_THROUGH,
            action(inputActive = false, phase = VoiceKeyboardPhase.IDLE),
        )
        assertEquals(
            VoiceKeyboardVolumeShortcutAction.PASS_THROUGH,
            VoiceKeyboardF10Shortcut.action(
                inputActive = true,
                keyCode = KeyEvent.KEYCODE_F9,
                keyAction = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                phase = VoiceKeyboardPhase.IDLE,
            ),
        )
    }

    @Test
    fun f10HoldAndReleaseAreConsumedWithoutAnotherToggle() {
        assertEquals(
            VoiceKeyboardVolumeShortcutAction.CONSUME,
            action(inputActive = true, phase = VoiceKeyboardPhase.RECORDING, repeatCount = 1),
        )
        assertEquals(
            VoiceKeyboardVolumeShortcutAction.CONSUME,
            VoiceKeyboardF10Shortcut.action(
                inputActive = true,
                keyCode = KeyEvent.KEYCODE_F10,
                keyAction = KeyEvent.ACTION_UP,
                repeatCount = 0,
                phase = VoiceKeyboardPhase.RECORDING,
            ),
        )
        assertTrue(VoiceKeyboardF10Shortcut.shouldConsumeMultiple(true, KeyEvent.KEYCODE_F10))
        assertEquals(
            VoiceKeyboardVolumeShortcutAction.PASS_THROUGH,
            VoiceKeyboardF10Shortcut.action(
                inputActive = false,
                keyCode = KeyEvent.KEYCODE_F10,
                keyAction = KeyEvent.ACTION_UP,
                repeatCount = 0,
                phase = VoiceKeyboardPhase.RECORDING,
            ),
        )
    }

    private fun action(
        inputActive: Boolean,
        phase: VoiceKeyboardPhase,
        repeatCount: Int = 0,
    ): VoiceKeyboardVolumeShortcutAction = VoiceKeyboardF10Shortcut.action(
        inputActive = inputActive,
        keyCode = KeyEvent.KEYCODE_F10,
        keyAction = KeyEvent.ACTION_DOWN,
        repeatCount = repeatCount,
        phase = phase,
    )
}
