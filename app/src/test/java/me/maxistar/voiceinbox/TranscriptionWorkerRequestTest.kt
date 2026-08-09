package me.maxistar.voiceinbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptionWorkerRequestTest {
    @Test
    fun batchRequestCanOmitOutputDocument() {
        val request = TranscriptionWorker.requestAll(folderUri = null, outputUri = null)

        assertNull(request.workSpec.input.getString(TranscriptionWorker.KEY_OUTPUT_URI))
        assertEquals(
            TranscriptionWorker.NO_ENTRY_ID,
            request.workSpec.input.getLong(TranscriptionWorker.KEY_RETRY_ID, TranscriptionWorker.NO_ENTRY_ID),
        )
    }

    @Test
    fun entryRequestRetainsOptionalOutputWhenProvided() {
        val request = TranscriptionWorker.requestEntry(
            folderUri = null,
            outputUri = null,
            entryId = 7L,
        )

        assertNull(request.workSpec.input.getString(TranscriptionWorker.KEY_OUTPUT_URI))
        assertEquals(7L, request.workSpec.input.getLong(TranscriptionWorker.KEY_RETRY_ID, -1L))
    }
}
