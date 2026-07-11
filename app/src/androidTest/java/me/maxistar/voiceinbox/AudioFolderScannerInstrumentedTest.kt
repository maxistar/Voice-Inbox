package me.maxistar.voiceinbox

import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioFolderScannerInstrumentedTest {
    @Test
    fun scansOnlySupportedDirectChildren() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val treeUri = DocumentsContract.buildTreeDocumentUri(
            TestAudioDocumentsProvider.AUTHORITY,
            TestAudioDocumentsProvider.ROOT_ID,
        )
        val scanner = AudioFolderScanner(context.contentResolver)

        scanner.requireReadable(treeUri)
        val scanResult = scanner.scan(treeUri) to scanner.folderName(treeUri)
        val files = scanResult.first
        val folderName = scanResult.second

        assertEquals(listOf("recording.wav", "recording.m4a"), files.map { it.displayName })
        assertEquals(listOf(100L, 200L), files.map { it.fingerprint.sizeBytes })
        assertEquals("Test audio folder", folderName)
    }
}
