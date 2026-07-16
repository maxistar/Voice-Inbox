package me.maxistar.voiceinbox

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.maxistar.voiceinbox.core.AndroidSqlDelightAudioCatalogFactory
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AudioShareIntentInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun clearState() {
        context.deleteDatabase(AndroidSqlDelightAudioCatalogFactory.DATABASE_NAME)
        File(context.filesDir, AndroidAudioImportConstants.DIRECTORY_NAME).deleteRecursively()
        WorkManager.getInstance(context)
            .cancelUniqueWork(TranscriptionWorker.UNIQUE_WORK_NAME)
            .result.get(30, TimeUnit.SECONDS)
    }

    @Test
    fun parserNormalizesSingleMultipleClipAndMalformedIntents() {
        val wav = documentUri("wav")
        val m4a = documentUri("m4a")
        val single = Intent(Intent.ACTION_SEND)
            .putExtra(Intent.EXTRA_STREAM, wav)
        assertEquals(listOf(wav), AudioShareIntentParser.streamUris(single))

        val multiple = Intent(Intent.ACTION_SEND_MULTIPLE)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(wav, m4a, wav))
            .apply { clipData = ClipData.newRawUri("audio", m4a) }
        assertEquals(listOf(m4a, wav), AudioShareIntentParser.streamUris(multiple))
        assertTrue(AudioShareIntentParser.streamUris(Intent(Intent.ACTION_SEND)).isEmpty())
        assertTrue(AudioShareIntentParser.streamUris(Intent(Intent.ACTION_VIEW)).isEmpty())
    }

    @Test
    fun coldWarmAndRecreatedDeliveryCreatesOneDurableImport() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                MainActivity::class.java.getDeclaredMethod("handleShareIntent", Intent::class.java)
                    .apply { isAccessible = true }
                    .invoke(activity, shareIntent(documentUri("wav")))
            }
            awaitImportFinished(scenario)
            assertEquals(1, importedRows())
            assertTrue(importedDocumentIds().single().startsWith("content://${context.packageName}.files/"))
            assertTrue(
                WorkManager.getInstance(context)
                    .getWorkInfosForUniqueWork(TranscriptionWorker.UNIQUE_WORK_NAME)
                    .get(30, TimeUnit.SECONDS)
                    .none { it.state in ACTIVE_WORK_STATES },
            )

            scenario.onActivity { activity ->
                MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
                    .apply { isAccessible = true }
                    .invoke(activity, shareIntent(documentUri("wav")))
            }
            awaitImportFinished(scenario)
            assertEquals(1, importedRows())
        }
    }

    @Test
    fun pickerStyleMultipleInputKeepsAcceptedItemWhenAnotherIsRejected() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                MainActivity::class.java.getDeclaredMethod("ingestAudioUris", List::class.java)
                    .apply { isAccessible = true }
                    .invoke(activity, listOf(documentUri("m4a"), documentUri("text")))
            }
            awaitImportFinished(scenario)
            assertEquals(1, importedRows())
        }
    }

    @Test
    fun telegramStyleOggAndOpusMultipleShareImportsBothItems() {
        val multiple = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("audio/*")
            .putParcelableArrayListExtra(
                Intent.EXTRA_STREAM,
                arrayListOf(documentUri("ogg"), documentUri("opus")),
            )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                MainActivity::class.java.getDeclaredMethod("handleShareIntent", Intent::class.java)
                    .apply { isAccessible = true }
                    .invoke(activity, multiple)
            }
            awaitImportFinished(scenario)
            assertEquals(2, importedRows())
        }
    }

    private fun awaitImportFinished(scenario: ActivityScenario<MainActivity>) {
        repeat(100) {
            var active = true
            scenario.onActivity { activity ->
                active = MainActivity::class.java.getDeclaredField("ingestionActive")
                    .apply { isAccessible = true }
                    .getBoolean(activity)
            }
            if (!active) return
            Thread.sleep(25)
        }
        error("Timed out waiting for audio ingestion")
    }

    private fun importedRows(): Int {
        val repository = AndroidSqlDelightAudioCatalogFactory(context).create()
        return try {
            repository.importedFiles(AndroidAudioImportConstants.SOURCE_ID).size
        } finally {
            repository.close()
        }
    }

    private fun importedDocumentIds(): List<String> {
        val repository = AndroidSqlDelightAudioCatalogFactory(context).create()
        return try {
            repository.importedFiles(AndroidAudioImportConstants.SOURCE_ID).map { it.documentUri }
        } finally {
            repository.close()
        }
    }

    private fun shareIntent(uri: Uri): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("audio/wav")
            .putExtra(Intent.EXTRA_STREAM, uri)

    private fun documentUri(id: String): Uri =
        DocumentsContract.buildDocumentUri(TestAudioDocumentsProvider.AUTHORITY, id)

    private companion object {
        val ACTIVE_WORK_STATES = setOf(
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.BLOCKED,
            WorkInfo.State.RUNNING,
        )
    }
}
