package me.maxistar.voiceinbox

import android.provider.DocumentsContract
import me.maxistar.voiceinbox.core.SpeechModelFile
import me.maxistar.voiceinbox.core.SpeechModelManifest
import me.maxistar.voiceinbox.core.SpeechModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

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

    @Test
    fun packageIdentitySelectsExactCatalogModel() {
        val descriptor = SpeechModelDirectoryReader.resolvePackageIdentity(
            """{"schemaVersion":1,"catalogId":"whisper-tiny-multilingual","modelVersion":"whisper-tiny-ggml-f16-r1"}""",
        )

        assertEquals(SpeechModelCatalog.whisperTinyMultilingual, descriptor)
    }

    @Test
    fun malformedUnknownAndUntrustedPackageMetadataAreRejected() {
        listOf(
            "not-json",
            """{"schemaVersion":2,"catalogId":"whisper-tiny-multilingual","modelVersion":"whisper-tiny-ggml-f16-r1"}""",
            """{"schemaVersion":1,"catalogId":"unknown","modelVersion":"unknown"}""",
            """{"schemaVersion":1,"catalogId":"whisper-tiny-multilingual","modelVersion":"whisper-tiny-ggml-f16-r1","files":[]}""",
        ).forEach { json ->
            assertTrue(runCatching { SpeechModelDirectoryReader.resolvePackageIdentity(json) }.isFailure)
        }
    }

    @Test
    fun legacyLayoutIsRestrictedToExactParakeetFiles() {
        val files = SpeechModelCatalog.defaultModel.manifest.files.map { document(it.name) }
        assertEquals(
            SpeechModelCatalog.defaultModel,
            SpeechModelDirectoryReader.resolveLegacyParakeet(files),
        )
        assertTrue(
            runCatching {
                SpeechModelDirectoryReader.resolveLegacyParakeet(files + document("extra.txt"))
            }.isFailure,
        )
        assertTrue(
            runCatching {
                SpeechModelDirectoryReader.resolveLegacyParakeet(
                    SpeechModelCatalog.whisperTinyMultilingual.manifest.files.map { document(it.name) },
                )
            }.isFailure,
        )
    }

    @Test
    fun boundedManifestReadingWorksWithoutApi33InputStreamMethods() {
        val payload = "model package".toByteArray()

        assertTrue(
            payload.contentEquals(
                SpeechModelDirectoryReader.readBounded(ByteArrayInputStream(payload), payload.size),
            ),
        )
        assertTrue(
            runCatching {
                SpeechModelDirectoryReader.readBounded(ByteArrayInputStream(payload), payload.size - 1)
            }.exceptionOrNull()?.message?.contains("too large") == true,
        )
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
