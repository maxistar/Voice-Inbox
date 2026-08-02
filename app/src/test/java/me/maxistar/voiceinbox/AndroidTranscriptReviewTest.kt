package me.maxistar.voiceinbox

import me.maxistar.voiceinbox.core.AudioCatalogEntry
import me.maxistar.voiceinbox.core.AudioFileFingerprint
import me.maxistar.voiceinbox.core.AudioFileState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidTranscriptReviewTest {
    @Test
    fun reviewPreservesExactTextAndFilenameContext() {
        val text = "  recognized text\n"
        val review = requireNotNull(AndroidTranscriptReview.from(entry(7, text)))

        assertEquals(7, review.entryId)
        assertEquals("7.ogg", review.filename)
        assertEquals(text, review.text)
        assertFalse(review.text.contains(review.filename))
    }

    @Test
    fun reviewRejectsMissingAndBlankStoredTranscript() {
        assertNull(AndroidTranscriptReview.from(entry(1, null)))
        assertNull(AndroidTranscriptReview.from(entry(2, " \n ")))
    }

    private fun entry(id: Long, transcript: String?) = AudioCatalogEntry(
        id = id,
        folderUri = AndroidAudioImportConstants.SOURCE_ID,
        documentUri = "content://audio/$id",
        displayName = "$id.ogg",
        mimeType = "audio/ogg",
        fingerprint = AudioFileFingerprint(sizeBytes = 1024, modifiedMillis = 1000),
        state = AudioFileState.PROCESSED,
        stateBeforeMissing = null,
        lastError = null,
        processedAtMillis = 2000,
        transcriptText = transcript,
    )
}
