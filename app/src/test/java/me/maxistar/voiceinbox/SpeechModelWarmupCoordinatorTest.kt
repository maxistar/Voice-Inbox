package me.maxistar.voiceinbox

import me.maxistar.voiceinbox.core.SpeechModelFile
import me.maxistar.voiceinbox.core.SpeechModelManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executor

class SpeechModelWarmupCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun reusesOnePreparationForTheSameReadyInstallation() {
        val repository = readyRepository()
        var preparations = 0
        val coordinator = coordinator { model ->
            preparations += 1
            Result.success(model.installedDirectory)
        }

        val first = coordinator.prepare(repository, retryFailed = false)
        val second = coordinator.prepare(repository, retryFailed = false)

        assertSame(first, second)
        assertTrue(first.get().isSuccess)
        assertEquals(1, preparations)
        assertTrue(coordinator.state() is SpeechModelWarmupState.Ready)
    }

    @Test
    fun warmUpKeepsMissingModelFailureForTheNextRecordRequest() {
        var preparations = 0
        val coordinator = coordinator {
            preparations += 1
            Result.failure(IllegalStateException("Speech model is not installed"))
        }

        coordinator.warmUp(emptyRepository())

        assertEquals(1, preparations)
        assertTrue(coordinator.state() is SpeechModelWarmupState.Failed)
    }

    @Test
    fun userInitiatedRetryReplacesAFailedWarmUp() {
        val repository = readyRepository()
        var preparations = 0
        val coordinator = coordinator { model ->
            preparations += 1
            if (preparations == 1) {
                Result.failure(IllegalStateException("model load failed"))
            } else {
                Result.success(model.installedDirectory)
            }
        }

        coordinator.warmUp(repository)
        assertTrue(coordinator.state() is SpeechModelWarmupState.Failed)

        assertTrue(coordinator.prepare(repository, retryFailed = true).get().isSuccess)
        assertEquals(2, preparations)
        assertTrue(coordinator.state() is SpeechModelWarmupState.Ready)
    }

    @Test
    fun invalidationClearsCompletedPreparationForTheNextInstallation() {
        val repository = readyRepository()
        var preparations = 0
        val coordinator = coordinator { model ->
            preparations += 1
            Result.success(model.installedDirectory)
        }

        coordinator.prepare(repository, retryFailed = false).get()
        coordinator.invalidate()
        coordinator.prepare(repository, retryFailed = false).get()

        assertEquals(2, preparations)
        assertTrue(coordinator.state() is SpeechModelWarmupState.Ready)
    }

    @Test
    fun aDifferentInstallationStartsItsOwnPreparation() {
        val firstRepository = readyRepository()
        val secondRepository = readyRepository()
        var preparations = 0
        val coordinator = coordinator { model ->
            preparations += 1
            Result.success(model.installedDirectory)
        }

        coordinator.prepare(firstRepository, retryFailed = false).get()
        coordinator.prepare(secondRepository, retryFailed = false).get()

        assertEquals(2, preparations)
        val state = coordinator.state() as SpeechModelWarmupState.Ready
        assertTrue(
            state.installation.endsWith(":${secondRepository.installedDirectory.canonicalPath}"),
        )
    }

    private fun coordinator(
        prepare: (SpeechModelRepository) -> Result<File>,
    ): SpeechModelWarmupCoordinator = SpeechModelWarmupCoordinator(
        executor = Executor { runnable -> runnable.run() },
        prepareModel = prepare,
    )

    private fun readyRepository(): SpeechModelRepository = emptyRepository().apply {
        prepareForInstall().getOrThrow()
        testFiles.forEach { (name, contents) ->
            stagingDirectory.resolve(name).apply {
                parentFile?.mkdirs()
                writeBytes(contents)
            }
        }
        activate().getOrThrow()
    }

    private fun emptyRepository(): SpeechModelRepository = SpeechModelRepository(
        root = File(temporaryFolder.root, "models-${System.nanoTime()}"),
        manifest = testManifest,
        usableSpace = { Long.MAX_VALUE },
    )

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
