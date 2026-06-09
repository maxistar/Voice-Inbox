package me.maxistar.watchface.notesrecognition

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
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
class EndToEndTranscriptionInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val testContext = instrumentation.context
    private val targetContext = instrumentation.targetContext

    @Test
    fun wavAndM4aAppendSeparateTranscriptEntries() {
        val model = SpeechModelRepository(targetContext.noBackupFilesDir.resolve("models")).inspect()
        assumeTrue(model is InstalledSpeechModelState.Ready)

        val output = File(targetContext.cacheDir, "end-to-end-output.txt").apply {
            writeText("Existing notes")
        }
        runTranscription(TestR.raw.test_audio_wav, "sample.wav", output)
        runTranscription(TestR.raw.test_audio_m4a, "sample.m4a", output)

        val result = output.readText()
        assertTrue(result.startsWith("Existing notes\n\nsample.wav\n"))
        assertTrue(result.contains("\n\nsample.m4a\n"))
        assertTrue(result.substringAfter("sample.wav").isNotBlank())
        assertEquals(1, Regex("sample\\.wav").findAll(result).count())
        assertEquals(1, Regex("sample\\.m4a").findAll(result).count())
    }

    private fun runTranscription(resource: Int, name: String, output: File) {
        val audio = File(targetContext.cacheDir, name).apply {
            testContext.resources.openRawResource(resource).use { input ->
                outputStream().use(input::copyTo)
            }
        }
        val workManager = WorkManager.getInstance(targetContext)
        workManager.cancelUniqueWork(TranscriptionWorker.UNIQUE_WORK_NAME).result.get(30, TimeUnit.SECONDS)
        TranscriptionWorker.enqueue(
            context = targetContext,
            audioUri = Uri.fromFile(audio),
            outputUri = Uri.fromFile(output),
            audioMetadata = DocumentMetadata(name, "audio/*", null),
        )

        val deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5)
        var info: WorkInfo?
        do {
            Thread.sleep(500)
            info = workManager
                .getWorkInfosForUniqueWork(TranscriptionWorker.UNIQUE_WORK_NAME)
                .get(30, TimeUnit.SECONDS)
                .firstOrNull()
        } while (info != null && !info.state.isFinished && System.currentTimeMillis() < deadline)

        assertEquals(
            info?.outputData?.getString(TranscriptionWorker.KEY_ERROR),
            WorkInfo.State.SUCCEEDED,
            info?.state,
        )
        audio.delete()
    }
}
