package me.maxistar.voiceinbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceKeyboardControllerTest {
    @Test
    fun serializesRecordingAndTranscriptionRequests() {
        val controller = VoiceKeyboardController()

        assertTrue(controller.beginPreparation())
        assertFalse(controller.beginPreparation())
        assertTrue(controller.recordingStarted())
        assertFalse(controller.beginPreparation())
        assertTrue(controller.recordingStopped())
        assertFalse(controller.recordingStopped())
        assertEquals(VoiceKeyboardPhase.TRANSCRIBING, controller.phase)
    }

    @Test
    fun emptyTranscriptionDoesNotCreatePendingText() {
        val controller = transcribingController()

        assertNull(controller.completeTranscription("   "))
        assertEquals(VoiceKeyboardPhase.IDLE, controller.phase)
        assertNull(controller.pendingResult())
    }

    @Test
    fun completedTextCanWaitForAConnectionAndCommitOnce() {
        val controller = transcribingController()
        val text = controller.completeTranscription(" dictated text ")

        controller.deferResult(requireNotNull(text))
        assertEquals("dictated text", controller.pendingResult())
        assertEquals(VoiceKeyboardPhase.RESULT_PENDING, controller.phase)

        controller.markPendingCommitted()
        assertNull(controller.pendingResult())
        assertEquals(VoiceKeyboardPhase.IDLE, controller.phase)
    }

    @Test
    fun cancellationDropsPendingAndActiveWork() {
        val controller = transcribingController()
        controller.deferResult("keep me")

        controller.cancel()

        assertEquals(VoiceKeyboardPhase.IDLE, controller.phase)
        assertNull(controller.pendingResult())
    }

    @Test
    fun recoverableSetupFailureReturnsToRecordableState() {
        val controller = VoiceKeyboardController()

        assertTrue(controller.beginPreparation())
        controller.fail()

        assertEquals(VoiceKeyboardPhase.ERROR, controller.phase)
        assertTrue(controller.beginPreparation())
    }

    @Test
    fun keyboardReturnUsesPickerWhenPreviousInputMethodIsUnavailable() {
        assertEquals(
            VoiceKeyboardReturnAction.RESTORED_PREVIOUS,
            VoiceKeyboardSwitching.returnAction(previousKeyboardRestored = true),
        )
        assertEquals(
            VoiceKeyboardReturnAction.SHOW_PICKER,
            VoiceKeyboardSwitching.returnAction(previousKeyboardRestored = false),
        )
    }

    private fun transcribingController(): VoiceKeyboardController = VoiceKeyboardController().apply {
        beginPreparation()
        recordingStarted()
        recordingStopped()
    }
}
