package me.maxistar.voiceinbox

import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import me.maxistar.voiceinbox.test.R as TestR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TranscriptionWorkerInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val testContext = instrumentation.context
    private val targetContext = instrumentation.targetContext

    @Before
    fun cleanCatalog() {
        targetContext.deleteDatabase(AudioCatalogDatabase.DATABASE_NAME)
    }

    @Test
    fun invalidAudioFailsFileWithoutChangingOutputOrLeavingStaging() {
        requireModel()
        val audio = File(targetContext.cacheDir, "invalid-audio.bin").apply {
            writeText("not audio")
        }
        assertFileFailedWithoutMutation(audio, "invalid.bin")
    }

    @Test
    fun emptyRecognitionFailsFileWithoutChangingOutputOrLeavingStaging() {
        requireModel()
        val audio = copyFixture(TestR.raw.test_audio_silence, "silence.wav")
        assertFileFailedWithoutMutation(audio, "silence.wav")
    }

    @Test
    fun uniqueWorkKeepsOneActiveChain() {
        requireModel()
        val audio = File(targetContext.cacheDir, "invalid-unique.bin").apply {
            writeText("not audio")
        }
        val output = File(targetContext.cacheDir, "unique-output.txt").apply {
            writeText("unchanged")
        }
        seed(audio, "invalid.bin")
        val manager = WorkManager.getInstance(targetContext)
        manager.cancelUniqueWork(TranscriptionWorker.UNIQUE_WORK_NAME)
            .result.get(30, TimeUnit.SECONDS)
        manager.pruneWork().result.get(30, TimeUnit.SECONDS)

        repeat(2) {
            TranscriptionWorker.enqueueAll(
                targetContext,
                Uri.parse(FOLDER),
                Uri.fromFile(output),
            )
        }
        waitForFinished(manager)

        assertEquals(
            1,
            manager.getWorkInfosForUniqueWork(TranscriptionWorker.UNIQUE_WORK_NAME)
                .get(30, TimeUnit.SECONDS)
                .size,
        )
    }

    @Test
    fun failedFileDoesNotBlockLaterFileAcrossActivityRecreationAndBackgrounding() {
        requireModel()
        val invalid = File(targetContext.cacheDir, "first-invalid.bin").apply {
            writeText("not audio")
        }
        val valid = copyFixture(TestR.raw.test_audio_wav, "second-valid.wav")
        val output = File(targetContext.cacheDir, "failure-isolation-output.txt").apply {
            writeText("Existing")
        }
        AudioCatalogDatabase(targetContext).use { database ->
            AudioCatalogRepository(database).reconcile(
                FOLDER,
                listOf(
                    scanned(invalid, "first-invalid.bin", modified = 1),
                    scanned(valid, "second-valid.wav", modified = 2),
                ),
            )
        }
        val manager = WorkManager.getInstance(targetContext)
        manager.cancelUniqueWork(TranscriptionWorker.UNIQUE_WORK_NAME)
            .result.get(30, TimeUnit.SECONDS)
        val workId = TranscriptionWorker.enqueueAll(
            targetContext,
            Uri.parse(FOLDER),
            Uri.fromFile(output),
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.recreate()
            scenario.moveToState(Lifecycle.State.CREATED)
            val info = waitForFinished(manager, workId)
            assertEquals(WorkInfo.State.SUCCEEDED, info.state)
        }

        AudioCatalogDatabase(targetContext).use { database ->
            val repository = AudioCatalogRepository(database)
            val newEntries = repository.newEntries(FOLDER)
            assertEquals(AudioFileState.FAILED, newEntries.single().state)
            assertEquals("first-invalid.bin", newEntries.single().displayName)
            assertEquals("second-valid.wav", repository.processedEntries(FOLDER).single().displayName)
        }
        val result = output.readText()
        assertTrue(result.startsWith("Existing\n\nsecond-valid.wav\n"))
        assertTrue(!result.contains("first-invalid.bin\n"))
    }

    private fun assertFileFailedWithoutMutation(audio: File, audioName: String) {
        val output = File(targetContext.cacheDir, "failure-output.txt").apply {
            writeText("unchanged")
        }
        seed(audio, audioName)
        val manager = WorkManager.getInstance(targetContext)
        manager.cancelUniqueWork(TranscriptionWorker.UNIQUE_WORK_NAME)
            .result.get(30, TimeUnit.SECONDS)
        TranscriptionWorker.enqueueAll(targetContext, Uri.parse(FOLDER), Uri.fromFile(output))

        val info = waitForFinished(manager)

        assertEquals(WorkInfo.State.SUCCEEDED, info.state)
        assertEquals("unchanged", output.readText())
        AudioCatalogDatabase(targetContext).use { database ->
            val failed = AudioCatalogRepository(database).newEntries(FOLDER).single()
            assertEquals(AudioFileState.FAILED, failed.state)
            assertTrue(failed.lastError?.isNotBlank() == true)
        }
        assertTrue(
            targetContext.cacheDir.listFiles().orEmpty()
                .none { it.name.startsWith("transcript-") },
        )
    }

    private fun seed(audio: File, audioName: String) {
        AudioCatalogDatabase(targetContext).use { database ->
            AudioCatalogRepository(database).reconcile(
                FOLDER,
                listOf(
                    scanned(audio, audioName, audio.lastModified()),
                ),
            )
        }
    }

    private fun scanned(file: File, name: String, modified: Long) = ScannedAudioFile(
        folderUri = FOLDER,
        documentUri = Uri.fromFile(file).toString(),
        displayName = name,
        mimeType = "audio/*",
        fingerprint = AudioFileFingerprint(file.length(), modified),
    )

    private fun waitForFinished(
        manager: WorkManager,
        id: java.util.UUID? = null,
    ): WorkInfo {
        val deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(3)
        var info: WorkInfo?
        do {
            Thread.sleep(250)
            info = if (id == null) {
                manager.getWorkInfosForUniqueWork(TranscriptionWorker.UNIQUE_WORK_NAME)
                    .get(30, TimeUnit.SECONDS)
                    .firstOrNull()
            } else {
                manager.getWorkInfoById(id).get(30, TimeUnit.SECONDS)
            }
        } while (info != null && !info.state.isFinished && System.currentTimeMillis() < deadline)
        return requireNotNull(info)
    }

    private fun requireModel() {
        assumeTrue(
            SpeechModelRepository(targetContext.noBackupFilesDir.resolve("models")).inspect()
                is InstalledSpeechModelState.Ready,
        )
    }

    private fun copyFixture(resource: Int, name: String): File =
        File(targetContext.cacheDir, name).apply {
            testContext.resources.openRawResource(resource).use { input ->
                outputStream().use(input::copyTo)
            }
        }

    companion object {
        private const val FOLDER = "content://test/worker"
    }
}
