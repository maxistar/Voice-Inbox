package me.maxistar.voiceinbox

import me.maxistar.voiceinbox.core.SpeechModelFile
import me.maxistar.voiceinbox.core.SpeechModelManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class SpeechModelPreparationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun preparationVerifiesAndInitializesOnceUntilInvalidated() {
        val repository = readyRepository()
        var initializations = 0
        SpeechModelPreparation.invalidate()

        repeat(2) {
            assertTrue(
                SpeechModelPreparation.prepare(repository) {
                    initializations += 1
                    true
                }.isSuccess,
            )
        }
        assertEquals(1, initializations)

        SpeechModelPreparation.invalidate()
        assertTrue(
            SpeechModelPreparation.prepare(repository) {
                initializations += 1
                true
            }.isSuccess,
        )
        assertEquals(2, initializations)
    }

    @Test
    fun invalidModelFailsBeforeNativeInitialization() {
        val repository = readyRepository()
        repository.installedDirectory.resolve("model.bin").writeText("other")
        var initialized = false
        SpeechModelPreparation.invalidate()

        val result = SpeechModelPreparation.prepare(repository) {
            initialized = true
            true
        }

        assertTrue(result.isFailure)
        assertTrue(!initialized)
    }

    private fun readyRepository(): SpeechModelRepository {
        val repository = SpeechModelRepository(
            File(temporaryFolder.root, "models"),
            testManifest,
            usableSpace = { Long.MAX_VALUE },
        )
        repository.prepareForInstall().getOrThrow()
        testFiles.forEach { (name, contents) ->
            repository.stagingDirectory.resolve(name).apply {
                parentFile?.mkdirs()
                writeBytes(contents)
            }
        }
        repository.activate().getOrThrow()
        return repository
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
                    name,
                    contents.size.toLong(),
                    MessageDigest.getInstance("SHA-256")
                        .digest(contents)
                        .joinToString("") { "%02x".format(it) },
                )
            },
            safetyMarginBytes = 8,
        )
    }
}
