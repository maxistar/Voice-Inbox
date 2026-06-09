package me.maxistar.watchface.notesrecognition

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptMergerTest {
    @Test
    fun removesRepeatedBoundaryWords() {
        assertEquals(
            "one two three four five",
            TranscriptMerger.merge("one two three", "two three four five"),
        )
    }

    @Test
    fun matchingIgnoresCaseAndPunctuation() {
        assertEquals(
            "Hello, world again",
            TranscriptMerger.merge("Hello, world", "WORLD again"),
        )
    }

    @Test
    fun unrelatedTextIsAppended() {
        assertEquals("first phrase second phrase", TranscriptMerger.merge("first phrase", "second phrase"))
    }
}
