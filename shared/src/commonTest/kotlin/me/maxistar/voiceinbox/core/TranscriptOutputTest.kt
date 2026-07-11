package me.maxistar.voiceinbox.core


import kotlin.test.assertEquals
import kotlin.test.Test

class TranscriptOutputTest {
    @Test
    fun formatsFilenameTimestampLabelAndTranscript() {
        val output = TranscriptOutput.formatEntry(
            audioName = "meeting.m4a",
            recordingTimeLabel = "Jan 1, 1970, 12:00:00 AM",
            transcript = " recognized text ",
        )

        assertEquals("meeting.m4a\nJan 1, 1970, 12:00:00 AM\nrecognized text", output)
    }

    @Test
    fun missingTimestampIsExplicit() {
        assertEquals(
            "audio.wav\nRecording time unknown\ntext",
            TranscriptOutput.formatEntry("audio.wav", null, "text"),
        )
    }

    @Test
    fun separatorProducesOneBlankLine() {
        assertEquals("", TranscriptOutput.separatorFor(""))
        assertEquals("\n\n", TranscriptOutput.separatorFor("text"))
        assertEquals("\n", TranscriptOutput.separatorFor("text\n"))
        assertEquals("", TranscriptOutput.separatorFor("text\n\n"))
        assertEquals("", TranscriptOutput.separatorFor("text\r\n\r\n"))
    }
}
