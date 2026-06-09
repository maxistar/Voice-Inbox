package me.maxistar.watchface.notesrecognition

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import me.maxistar.watchface.notesrecognition.test.R as TestR

@RunWith(AndroidJUnit4::class)
class TranscriptionWorkerInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val testContext = instrumentation.context
    private val targetContext = instrumentation.targetContext

    @Test
    fun invalidAudioFailsWithoutChangingOutputOrLeavingStaging() {
        requireModel()
        val audio = File(targetContext.cacheDir, "invalid-audio.bin").apply {
            writeText("not audio")
        }
        assertFailedWithoutMutation(audio, "invalid.bin")
    }

    @Test
    fun emptyRecognitionFailsWithoutChangingOutputOrLeavingStaging() {
        requireModel()
        val audio = copyFixture(TestR.raw.test_audio_silence, "silence.wav")
        assertFailedWithoutMutation(audio, "silence.wav")
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
        val manager = WorkManager.getInstance(targetContext)
        manager.cancelUniqueWork(TranscriptionWorker.UNIQUE_WORK_NAME).result.get(30, TimeUnit.SECONDS)

        repeat(2) {
            TranscriptionWorker.enqueue(
                targetContext,
                Uri.fromFile(audio),
                Uri.fromFile(output),
                DocumentMetadata("invalid.bin", "application/octet-stream", null),
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

    private fun assertFailedWithoutMutation(audio: File, audioName: String) {
        val output = File(targetContext.cacheDir, "failure-output.txt").apply {
            writeText("unchanged")
        }
        val manager = WorkManager.getInstance(targetContext)
        manager.cancelUniqueWork(TranscriptionWorker.UNIQUE_WORK_NAME).result.get(30, TimeUnit.SECONDS)
        TranscriptionWorker.enqueue(
            targetContext,
            Uri.fromFile(audio),
            Uri.fromFile(output),
            DocumentMetadata(audioName, "audio/*", null),
        )

        val info = waitForFinished(manager)

        assertEquals(WorkInfo.State.FAILED, info.state)
        assertEquals("unchanged", output.readText())
        assertTrue(targetContext.cacheDir.listFiles().orEmpty().none { it.name.startsWith("transcript-") })
    }

    private fun waitForFinished(manager: WorkManager): WorkInfo {
        val deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(3)
        var info: WorkInfo?
        do {
            Thread.sleep(250)
            info = manager.getWorkInfosForUniqueWork(TranscriptionWorker.UNIQUE_WORK_NAME)
                .get(30, TimeUnit.SECONDS)
                .firstOrNull()
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
}
