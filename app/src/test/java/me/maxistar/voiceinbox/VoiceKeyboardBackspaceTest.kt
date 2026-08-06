package me.maxistar.voiceinbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceKeyboardBackspaceTest {
    @Test
    fun doesNotDeleteWhenTheCursorIsKnownToBeAtTheStart() {
        assertFalse(VoiceKeyboardBackspace.shouldAttemptDeletion(""))
        assertTrue(VoiceKeyboardBackspace.shouldAttemptDeletion(null))
    }

    @Test
    fun prefersCodePointDeletion() {
        var fallbackCalled = false

        val deleted = VoiceKeyboardBackspace.deleteOne(
            deleteCodePoint = { true },
            deleteCodeUnit = {
                fallbackCalled = true
                true
            },
        )

        assertTrue(deleted)
        assertFalse(fallbackCalled)
    }

    @Test
    fun fallsBackWhenCodePointDeletionIsUnsupported() {
        var fallbackCalled = false

        val deleted = VoiceKeyboardBackspace.deleteOne(
            deleteCodePoint = { false },
            deleteCodeUnit = {
                fallbackCalled = true
                true
            },
        )

        assertTrue(deleted)
        assertTrue(fallbackCalled)
    }
}
