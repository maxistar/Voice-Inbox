package me.maxistar.voiceinbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatchTranscriptionRulesTest {
    @Test
    fun progressIsBoundedAndRequiresKnownDuration() {
        assertEquals(50, BatchTranscriptionRules.percent(5, 10))
        assertEquals(100, BatchTranscriptionRules.percent(20, 10))
        assertEquals(0, BatchTranscriptionRules.percent(-5, 10))
        assertNull(BatchTranscriptionRules.percent(5, null))
        assertNull(BatchTranscriptionRules.percent(5, 0))
    }

    @Test
    fun summaryIncludesFailuresWhenPresent() {
        assertEquals("Completed 2 of 2", BatchTranscriptionRules.summary(2, 2, 0))
        assertEquals(
            "Completed 2 of 3, 1 failed",
            BatchTranscriptionRules.summary(2, 3, 1),
        )
    }
}
