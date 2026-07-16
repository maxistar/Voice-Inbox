package me.maxistar.voiceinbox

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import me.maxistar.voiceinbox.core.SpeechModelFile
import me.maxistar.voiceinbox.core.SpeechModelManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class SpeechModelLocalImporterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun validSourceIsCopiedVerifiedAndActivatedWithProgress() = runBlocking {
        val sources = sourceUris()
        val progress = mutableListOf<SpeechModelImportProgress>()
        val repository = repository()
        val importer = importer(repository, sources) { uri ->
            ByteArrayInputStream(testFiles.getValue(uri.substringAfterLast('/')))
        }

        val installed = importer.import(treeUri) { progress += it }.getOrThrow()

        assertTrue(installed.isDirectory)
        testFiles.forEach { (name, contents) ->
            assertTrue(installed.resolve(name).readBytes().contentEquals(contents))
        }
        assertEquals(testManifest.totalSizeBytes, progress.last().bytesCopied)
        assertTrue(repository.inspect() is InstalledSpeechModelState.Ready)
    }

    @Test
    fun invalidPayloadIsRejectedAndTemporaryFileIsRemoved() = runBlocking {
        val sources = sourceUris()
        val repository = repository()
        val importer = importer(repository, sources) { uri ->
            val name = uri.substringAfterLast('/')
            val bytes = if (name == "model.bin") "wrong".toByteArray()
            else testFiles.getValue(name)
            ByteArrayInputStream(bytes)
        }

        val result = importer.import(treeUri) {}

        assertTrue(result.isFailure)
        assertFalse(repository.temporaryFile(testManifest.files.first()).exists())
        assertFalse(repository.installedDirectory.exists())
    }

    @Test
    fun providerFailureIsRecoverableAndExistingModelSurvives() = runBlocking {
        val repository = repository()
        repository.prepareForInstall().getOrThrow()
        testFiles.forEach { (name, bytes) -> repository.stagingDirectory.resolve(name).writeBytes(bytes) }
        repository.activate().getOrThrow()
        val importer = importer(repository, sourceUris()) { throw IOException("provider disconnected") }

        val result = importer.import(treeUri) {}

        assertTrue(result.exceptionOrNull()?.message?.contains("provider disconnected") == true)
        assertTrue(repository.inspect() is InstalledSpeechModelState.Ready)
    }

    @Test
    fun missingSourceMapCannotBeCompletedByOldStaging() = runBlocking {
        val repository = repository()
        repository.prepareForInstall().getOrThrow()
        testFiles.forEach { (name, bytes) -> repository.stagingDirectory.resolve(name).writeBytes(bytes) }
        val incomplete = sourceUris().filterKeys { it != "config.json" }
        val importer = importer(repository, incomplete) { ByteArrayInputStream(byteArrayOf()) }

        val result = importer.import(treeUri) {}

        assertTrue(result.isFailure)
        assertFalse(repository.installedDirectory.exists())
        assertTrue(repository.stagingDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun cancellationIsPropagatedAndPartialFileIsRemoved() = runBlocking {
        val repository = repository()
        val importer = importer(repository, sourceUris()) { uri ->
            ByteArrayInputStream(testFiles.getValue(uri.substringAfterLast('/')))
        }

        var cancelled = false
        try {
            importer.import(treeUri) { progress ->
                if (progress.bytesCopied > 0) throw CancellationException("test cancellation")
            }
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertFalse(repository.temporaryFile(testManifest.files.first()).exists())
        assertFalse(repository.installedDirectory.exists())
    }

    private fun importer(
        repository: SpeechModelRepository,
        sources: Map<String, String>,
        open: (String) -> ByteArrayInputStream,
    ) = SpeechModelLocalImporter(
        repository = repository,
        requiredDocuments = { _, manifest ->
            manifest.files.associate { entry ->
                entry.name to (sources[entry.name]
                    ?: throw IOException("${entry.name} is missing from the selected model folder"))
            }
        },
        openInputStream = open,
    )

    private fun repository() = SpeechModelRepository(
        root = File(temporaryFolder.root, "models"),
        manifest = testManifest,
        usableSpace = { Long.MAX_VALUE },
    )

    private fun sourceUris() = testFiles.keys.associateWith { "content://test/$it" }

    companion object {
        private const val treeUri = "content://test/tree/model"
        private val testFiles = linkedMapOf(
            "model.bin" to "model".toByteArray(),
            "config.json" to "{}".toByteArray(),
        )
        private val testManifest = SpeechModelManifest(
            modelId = "example/model",
            version = "test-version",
            repositoryRevision = "revision",
            files = testFiles.map { (name, contents) ->
                SpeechModelFile(
                    name,
                    contents.size.toLong(),
                    MessageDigest.getInstance("SHA-256").digest(contents)
                        .joinToString("") { "%02x".format(it) },
                )
            },
            safetyMarginBytes = 8,
        )
    }
}
