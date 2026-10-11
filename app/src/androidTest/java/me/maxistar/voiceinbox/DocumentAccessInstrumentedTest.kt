package me.maxistar.voiceinbox

import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.maxistar.voiceinbox.core.TranscriptAppendOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DocumentAccessInstrumentedTest {
    private val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    private val access = DocumentAccess(resolver)

    @Test
    fun appendAndBoundedTailReadUseUtf8DocumentAccess() {
        File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "shared-output.bin",
        ).delete()
        val output = DocumentsContract.buildDocumentUri(
            TestAudioDocumentsProvider.AUTHORITY,
            "output",
        )

        assertEquals(TranscriptAppendOutcome.SUCCEEDED, access.append(output, "Existing\n"))
        assertEquals(TranscriptAppendOutcome.SUCCEEDED, access.append(output, "Entry\n"))
        assertEquals("Existing\nEntry\n", access.readTail(output, 4096))
        assertTrue(access.readTail(output, 5).endsWith("try\n"))
    }

    @Test
    fun inaccessibleDocumentIsClassifiedAsNotWritten() {
        val root = DocumentsContract.buildDocumentUri(
            TestAudioDocumentsProvider.AUTHORITY,
            TestAudioDocumentsProvider.ROOT_ID,
        )

        assertEquals(TranscriptAppendOutcome.NOT_WRITTEN, access.append(root, "Entry\n"))
    }
}
