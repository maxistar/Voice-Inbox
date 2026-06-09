package me.maxistar.watchface.notesrecognition

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptionUiRulesTest {
    @Test
    fun controlsRequireModelAndOutputInOrder() {
        assertEquals(FileControlState(false, false), TranscriptionUiRules.fileControls(false, false, false))
        assertEquals(FileControlState(true, false), TranscriptionUiRules.fileControls(true, false, false))
        assertEquals(FileControlState(true, true), TranscriptionUiRules.fileControls(true, true, false))
        assertEquals(FileControlState(false, false), TranscriptionUiRules.fileControls(true, true, true))
    }

    @Test
    fun progressUsesProcessedDuration() {
        assertEquals(50, TranscriptionUiRules.percent(5_000, 10_000))
        assertEquals(100, TranscriptionUiRules.percent(20_000, 10_000))
        assertEquals(null, TranscriptionUiRules.percent(1, null))
    }
}
