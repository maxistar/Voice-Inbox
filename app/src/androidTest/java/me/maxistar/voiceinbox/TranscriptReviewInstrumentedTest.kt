package me.maxistar.voiceinbox

import android.content.Intent
import android.view.LayoutInflater
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranscriptReviewInstrumentedTest {
    @Test
    fun shareIntentUsesExactPlainTextAndFilenameSubject() {
        val review = AndroidTranscriptReview(5, "voice.ogg", "  exact transcript\n")
        val before = review.copy()

        val intent = AndroidTranscriptShareIntent.create(review)

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals(review.text, intent.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(review.filename, intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(before, review)
    }

    @Test
    fun transcriptLayoutKeepsLongTextSelectableAndAccessible() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_transcript, null)
        val transcript = view.findViewById<TextView>(R.id.transcriptText)
        val longText = "selectable transcript ".repeat(500)
        transcript.text = longText

        assertTrue(transcript.isTextSelectable)
        assertEquals(longText, transcript.text.toString())
        assertEquals(
            context.getString(R.string.transcript_text_accessibility),
            transcript.contentDescription,
        )
    }
}
