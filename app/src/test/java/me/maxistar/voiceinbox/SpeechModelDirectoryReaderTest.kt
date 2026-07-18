package me.maxistar.voiceinbox

import android.provider.DocumentsContract
import me.maxistar.voiceinbox.core.SpeechModelFile
import me.maxistar.voiceinbox.core.SpeechModelManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechModelDirectoryReaderTest {
    @Test
    fun exactDirectFilesAreMatchedByManifestName() {
        val documents = listOf(
            document("model.bin"),
            document("config.json"),
            document("nested", DocumentsContract.Document.MIME_TYPE_DIR),
            document("unrelated.txt"),
        )

        val matched = SpeechModelDirectoryReader.matchRequiredDocuments(documents, manifest)

        assertEquals(setOf("model.bin", "config.json"), matched.keys)
    }

    @Test
    fun missingAndDuplicateRequiredFilesAreRejected() {
        assertTrue(
            runCatching {
                SpeechModelDirectoryReader.matchRequiredDocuments(
                    listOf(document("model.bin")),
                    manifest,
                )
            }.exceptionOrNull()?.message?.contains("config.json") == true,
        )
        assertTrue(
            runCatching {
                SpeechModelDirectoryReader.matchRequiredDocuments(
                    listOf(document("model.bin"), document("model.bin"), document("config.json")),
                    manifest,
                )
            }.exceptionOrNull()?.message?.contains("Multiple") == true,
        )
    }

    @Test
    fun directoryWithRequiredNameDoesNotCountAsAFile() {
        val error = runCatching {
            SpeechModelDirectoryReader.matchRequiredDocuments(
                listOf(
                    document("model.bin", DocumentsContract.Document.MIME_TYPE_DIR),
                    document("config.json"),
                ),
                manifest,
            )
        }.exceptionOrNull()

        assertTrue(error?.message?.contains("model.bin") == true)
    }

    private fun document(name: String, mime: String = "application/octet-stream") =
        SpeechModelSourceDocument(name, "content://test/$name/${System.nanoTime()}", mime)

    companion object {
        private val manifest = SpeechModelManifest(
            modelId = "test/model",
            version = "test",
            repositoryRevision = "revision",
            files = listOf(
                SpeechModelFile("model.bin", 1, "hash"),
                SpeechModelFile("config.json", 1, "hash"),
            ),
            safetyMarginBytes = 0,
        )
    }
}
