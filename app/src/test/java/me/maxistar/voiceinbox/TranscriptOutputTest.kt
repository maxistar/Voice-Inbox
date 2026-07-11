package me.maxistar.voiceinbox

import me.maxistar.voiceinbox.core.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class TranscriptOutputTest {
    @Test
    fun formatsFilenameTimestampAndTranscript() {
        val output = TranscriptOutput.formatEntry(
            audioName = "meeting.m4a",
            recordingTimeMillis = 0,
            transcript = " recognized text ",
            locale = Locale.US,
            timeZone = TimeZone.getTimeZone("UTC"),
        )

        assertTrue(output.startsWith("meeting.m4a\n"))
        assertTrue(output.endsWith("\nrecognized text"))
    }

    @Test
    fun missingTimestampIsExplicit() {
        assertEquals(
            "audio.wav\nRecording time unknown\ntext",
            TranscriptOutput.formatEntry("audio.wav", null, "text", Locale.US),
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
