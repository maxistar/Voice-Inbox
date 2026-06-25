package me.maxistar.watchface.notesrecognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executor

class SpeechModelReadinessManagerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun duplicateRefreshesAreCoalescedWhileCheckIsRunning() {
        val executor = QueueingExecutor()
        val repository = readyRepository()
        var initializations = 0
        val manager = SpeechModelReadinessManager(
            repository = repository,
            initializeModel = {
                initializations += 1
                true
            },
            executor = executor,
        )
        val firstStates = mutableListOf<SpeechModelReadinessState>()
        val secondStates = mutableListOf<SpeechModelReadinessState>()

        manager.refresh { firstStates += it }
        manager.refresh { secondStates += it }

        assertEquals(1, executor.pendingCount)
        assertEquals(listOf(SpeechModelReadinessState.Checking), firstStates)
        assertEquals(listOf(SpeechModelReadinessState.Checking), secondStates)

        executor.runNext()

        assertEquals(1, initializations)
        assertTrue(firstStates.last() is SpeechModelReadinessState.Ready)
        assertTrue(secondStates.last() is SpeechModelReadinessState.Ready)
    }

    @Test
    fun readyStateIsCachedForLaterRefreshes() {
        val executor = QueueingExecutor()
        val repository = readyRepository()
        var initializations = 0
        val manager = SpeechModelReadinessManager(
            repository = repository,
            initializeModel = {
                initializations += 1
                true
            },
            executor = executor,
        )
        val firstStates = mutableListOf<SpeechModelReadinessState>()
        val cachedStates = mutableListOf<SpeechModelReadinessState>()

        manager.refresh { firstStates += it }
        executor.runNext()
        manager.refresh { cachedStates += it }

        assertEquals(1, initializations)
        assertEquals(0, executor.pendingCount)
        assertTrue(firstStates.last() is SpeechModelReadinessState.Ready)
        assertEquals(1, cachedStates.size)
        assertTrue(cachedStates.single() is SpeechModelReadinessState.Ready)
    }

    private fun readyRepository(): SpeechModelRepository {
        val repository = SpeechModelRepository(
            root = File(temporaryFolder.root, "models"),
            manifest = testManifest,
            usableSpace = { Long.MAX_VALUE },
        )
        repository.prepareForInstall().getOrThrow()
        testFiles.forEach { (name, contents) ->
            repository.stagingDirectory.mkdirs()
            repository.stagingDirectory.resolve(name).writeBytes(contents)
        }
        repository.activate().getOrThrow()
        return repository
    }

    private class QueueingExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        val pendingCount: Int
            get() = tasks.size

        override fun execute(command: Runnable) {
            tasks += command
        }

        fun runNext() {
            tasks.removeFirst().run()
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
