package me.maxistar.voiceinbox

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import me.maxistar.voiceinbox.test.R as TestR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class EndToEndTranscriptionInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val testContext = instrumentation.context
    private val targetContext = instrumentation.targetContext

    @Test
    fun wavAndM4aBatchAppendSeparateTranscriptEntries() {
        val model = SpeechModelRepository(targetContext.noBackupFilesDir.resolve("models")).inspect()
        assumeTrue(model is InstalledSpeechModelState.Ready)
        targetContext.deleteDatabase(AudioCatalogDatabase.DATABASE_NAME)

        val output = File(targetContext.cacheDir, "end-to-end-output.txt").apply {
            writeText("Existing notes")
        }
        val wav = copyFixture(TestR.raw.test_audio_wav, "sample.wav")
        val m4a = copyFixture(TestR.raw.test_audio_m4a, "sample.m4a")
        val folder = "content://test/end-to-end"
        AudioCatalogDatabase(targetContext).use { database ->
            AudioCatalogRepository(database).reconcile(
                folder,
                listOf(scanned(folder, wav), scanned(folder, m4a)),
            )
        }

        val workManager = WorkManager.getInstance(targetContext)
        workManager.cancelUniqueWork(TranscriptionWorker.UNIQUE_WORK_NAME)
            .result.get(30, TimeUnit.SECONDS)
        val firstRun = TranscriptionWorker.enqueueAll(
            targetContext,
            Uri.parse(folder),
            Uri.fromFile(output),
        )
        val info = waitForFinished(workManager, firstRun)

        assertEquals(
            info.outputData.getString(TranscriptionWorker.KEY_ERROR),
            WorkInfo.State.SUCCEEDED,
            info.state,
        )
        val result = output.readText()
        assertTrue(result.startsWith("Existing notes\n\n"))
        assertTrue(result.contains("sample.wav\n"))
        assertTrue(result.contains("\n\nsample.m4a\n"))
        assertEquals(1, Regex("sample\\.wav").findAll(result).count())
        assertEquals(1, Regex("sample\\.m4a").findAll(result).count())
        AudioCatalogDatabase(targetContext).use { database ->
            val repository = AudioCatalogRepository(database)
            assertEquals(2, repository.processedEntries(folder).size)

            output.appendText("\n\nUser edited this Markdown.")
            repository.reconcile(
                folder,
                listOf(
                    scanned(folder, wav).copy(
                        fingerprint = AudioFileFingerprint(
                            wav.length(),
                            wav.lastModified() + 1,
                        ),
                    ),
                    scanned(folder, m4a),
                ),
            )
        }

        val changedRun = TranscriptionWorker.enqueueAll(
            targetContext,
            Uri.parse(folder),
            Uri.fromFile(output),
        )
        assertEquals(WorkInfo.State.SUCCEEDED, waitForFinished(workManager, changedRun).state)

        val retryId = AudioCatalogDatabase(targetContext).use { database ->
            val repository = AudioCatalogRepository(database)
            val m4aEntry = repository.processedEntries(folder)
                .single { it.displayName == "sample.m4a" }
            repository.markFailed(m4aEntry.id, "Simulated publication failure")
            m4aEntry.id
        }
        val retryRun = TranscriptionWorker.enqueueRetry(
            targetContext,
            Uri.parse(folder),
            Uri.fromFile(output),
            retryId,
        )
        assertEquals(WorkInfo.State.SUCCEEDED, waitForFinished(workManager, retryRun).state)

        val reprocessed = output.readText()
        assertTrue(reprocessed.contains("User edited this Markdown."))
        assertEquals(2, Regex("sample\\.wav").findAll(reprocessed).count())
        assertEquals(2, Regex("sample\\.m4a").findAll(reprocessed).count())
        AudioCatalogDatabase(targetContext).use { database ->
            assertEquals(2, AudioCatalogRepository(database).processedEntries(folder).size)
        }
    }

    private fun scanned(folder: String, file: File) = ScannedAudioFile(
        folderUri = folder,
        documentUri = Uri.fromFile(file).toString(),
        displayName = file.name,
        mimeType = if (file.extension == "wav") "audio/wav" else "audio/mp4",
        fingerprint = AudioFileFingerprint(file.length(), file.lastModified()),
    )

    private fun copyFixture(resource: Int, name: String): File =
        File(targetContext.cacheDir, name).apply {
            testContext.resources.openRawResource(resource).use { input ->
                outputStream().use(input::copyTo)
            }
        }

    private fun waitForFinished(manager: WorkManager, id: UUID): WorkInfo {
        val deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5)
        var info: WorkInfo?
        do {
            Thread.sleep(500)
            info = manager.getWorkInfoById(id).get(30, TimeUnit.SECONDS)
        } while (info != null && !info.state.isFinished && System.currentTimeMillis() < deadline)
        return requireNotNull(info)
    }
}
