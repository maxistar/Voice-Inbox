package me.maxistar.voiceinbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class SpeechModelRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun missingModelIsReported() {
        val repository = repository()

        assertEquals(InstalledSpeechModelState.Missing, repository.inspect())
    }

    @Test
    fun validStagedModelActivatesAndSurvivesRepositoryRecreation() {
        val repository = repository()
        repository.prepareForInstall().getOrThrow()
        writeValidStaging(repository)

        val installed = repository.activate().getOrThrow()
        val recreated = repository()

        assertTrue(installed.isDirectory)
        assertEquals(
            installed.canonicalFile,
            (recreated.inspect() as InstalledSpeechModelState.Ready).directory.canonicalFile,
        )
    }

    @Test
    fun corruptAndIncompleteModelsAreRejected() {
        val repository = repository()
        repository.prepareForInstall().getOrThrow()
        repository.stagingFile(testManifest.files.first()).writeText("wrong")

        assertTrue(repository.activate().isFailure)

        writeValidStaging(repository)
        repository.stagingFile(testManifest.files.last()).delete()

        assertTrue(repository.activate().isFailure)
    }

    @Test
    fun temporaryFilesAndStaleStagingVersionsAreCleaned() {
        val repository = repository()
        val staleDirectory = File(temporaryFolder.root, "models/staging/old-version").apply {
            mkdirs()
        }
        File(staleDirectory, "old.part").writeText("partial")
        val currentPart = repository.temporaryFile(testManifest.files.first()).apply {
            parentFile?.mkdirs()
            writeText("partial")
        }

        repository.cleanupStaleState()

        assertTrue(!staleDirectory.exists())
        assertTrue(!currentPart.exists())
    }

    @Test
    fun versionMismatchDoesNotActivateOldModel() {
        val root = File(temporaryFolder.root, "models")
        val oldDirectory = File(root, "installed/old-version").apply { mkdirs() }
        File(root, "active-model").apply {
            parentFile?.mkdirs()
            writeText("old-version")
        }
        File(oldDirectory, "model.bin").writeText("old")

        assertEquals(InstalledSpeechModelState.Missing, repository().inspect())
        assertTrue(oldDirectory.exists())
    }

    @Test
    fun insufficientStorageFailsPreflight() {
        val repository = SpeechModelRepository(
            root = File(temporaryFolder.root, "models"),
            manifest = testManifest,
            usableSpace = { 0L },
        )

        assertTrue(repository.prepareForInstall().isFailure)
    }

    private fun repository() = SpeechModelRepository(
        root = File(temporaryFolder.root, "models"),
        manifest = testManifest,
        usableSpace = { Long.MAX_VALUE },
    )

    private fun writeValidStaging(repository: SpeechModelRepository) {
        testFiles.forEach { (name, contents) ->
            repository.stagingDirectory.mkdirs()
            repository.stagingDirectory.resolve(name).writeBytes(contents)
        }
    }

    companion object {
        private val testFiles = linkedMapOf(
            "model.bin" to "model".toByteArray(),
            "config.json" to "{}".toByteArray(),
        )
        private val testManifest = SpeechModelManifest(
            modelId = "example/model",
            version = "test-version",
            repositoryRevision = "0123456789abcdef",
            files = testFiles.map { (name, contents) ->
                SpeechModelFile(
                    name = name,
                    sizeBytes = contents.size.toLong(),
                    sha256 = MessageDigest.getInstance("SHA-256")
                        .digest(contents)
                        .joinToString("") { "%02x".format(it) },
                )
            },
            safetyMarginBytes = 8,
        )
    }
}
