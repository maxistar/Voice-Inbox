package me.maxistar.voiceinbox

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceKeyboardVolumeShortcutTest {
    @Test
    fun inactiveInputViewPassesVolumeDownThrough() {
        assertEquals(
            VoiceKeyboardVolumeShortcutAction.PASS_THROUGH,
            action(inputViewActive = false, phase = VoiceKeyboardPhase.IDLE),
        )
    }

    @Test
    fun volumeUpPassesThroughWhenInputViewIsActive() {
        assertEquals(
            VoiceKeyboardVolumeShortcutAction.PASS_THROUGH,
            VoiceKeyboardVolumeShortcut.action(
                inputViewActive = true,
                keyCode = KeyEvent.KEYCODE_VOLUME_UP,
                keyAction = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                phase = VoiceKeyboardPhase.IDLE,
            ),
        )
    }

    @Test
    fun firstVolumeDownStartsFromIdleOrError() {
        assertEquals(
            VoiceKeyboardVolumeShortcutAction.START,
            action(inputViewActive = true, phase = VoiceKeyboardPhase.IDLE),
        )
        assertEquals(
            VoiceKeyboardVolumeShortcutAction.START,
            action(inputViewActive = true, phase = VoiceKeyboardPhase.ERROR),
        )
    }

    @Test
    fun firstVolumeDownStopsRecording() {
        assertEquals(
            VoiceKeyboardVolumeShortcutAction.STOP,
            action(inputViewActive = true, phase = VoiceKeyboardPhase.RECORDING),
        )
    }

    @Test
    fun volumeDownDoesNotChangeBusyOrPendingPhases() {
        val phases = listOf(
            VoiceKeyboardPhase.WAITING_FOR_MODEL,
            VoiceKeyboardPhase.TRANSCRIBING,
            VoiceKeyboardPhase.RESULT_PENDING,
        )

        phases.forEach { phase ->
            assertEquals(
                VoiceKeyboardVolumeShortcutAction.CONSUME,
                action(inputViewActive = true, phase = phase),
            )
        }
    }

    @Test
    fun repeatAndKeyUpEventsAreConsumedWithoutAnotherToggle() {
        assertEquals(
            VoiceKeyboardVolumeShortcutAction.CONSUME,
            action(inputViewActive = true, phase = VoiceKeyboardPhase.IDLE, repeatCount = 1),
        )
        assertEquals(
            VoiceKeyboardVolumeShortcutAction.CONSUME,
            VoiceKeyboardVolumeShortcut.action(
                inputViewActive = true,
                keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
                keyAction = KeyEvent.ACTION_UP,
                repeatCount = 0,
                phase = VoiceKeyboardPhase.RECORDING,
            ),
        )
        assertTrue(
            VoiceKeyboardVolumeShortcut.shouldConsumeMultiple(
                inputViewActive = true,
                keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
            ),
        )
    }

    private fun action(
        inputViewActive: Boolean,
        phase: VoiceKeyboardPhase,
        repeatCount: Int = 0,
    ): VoiceKeyboardVolumeShortcutAction = VoiceKeyboardVolumeShortcut.action(
        inputViewActive = inputViewActive,
        keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
        keyAction = KeyEvent.ACTION_DOWN,
        repeatCount = repeatCount,
        phase = phase,
    )
}
