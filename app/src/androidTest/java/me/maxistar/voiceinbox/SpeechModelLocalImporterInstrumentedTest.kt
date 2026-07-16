package me.maxistar.voiceinbox

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.maxistar.voiceinbox.core.SpeechModelFile
import me.maxistar.voiceinbox.core.SpeechModelManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class SpeechModelLocalImporterInstrumentedTest {
    @Test
    fun providerDirectoryIsCopiedIntoIndependentAppOwnedInstallation() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val root = File(context.cacheDir, "model-import-test").apply { deleteRecursively() }
            val repository = SpeechModelRepository(root, manifest, usableSpace = { Long.MAX_VALUE })
            val tree = DocumentsContract.buildTreeDocumentUri(
                TestAudioDocumentsProvider.AUTHORITY,
                TestAudioDocumentsProvider.ROOT_ID,
            )
            val progress = mutableListOf<SpeechModelImportProgress>()

            val installed = SpeechModelLocalImporter(context.contentResolver, repository)
                .import(tree.toString()) { progress += it }
                .getOrThrow()

            assertTrue(installed.canonicalPath.startsWith(root.canonicalPath))
            assertEquals("test-audio-wav", installed.resolve("recording.wav").readText())
            assertEquals("test-audio-m4a", installed.resolve("recording.m4a").readText())
            assertEquals(manifest.totalSizeBytes, progress.last().bytesCopied)
            assertTrue(repository.inspect() is InstalledSpeechModelState.Ready)

            File(context.cacheDir, "shared-wav.bin").delete()
            File(context.cacheDir, "shared-m4a.bin").delete()
            assertTrue(repository.inspect() is InstalledSpeechModelState.Ready)
            root.deleteRecursively()
        }
    }

    @Test
    fun importPermissionIsRetainedForConfiguredAudioFolder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tree = Uri.parse("content://${TestAudioDocumentsProvider.AUTHORITY}/tree/root")
        SpeechModelImportPermission.recordOwned(context, tree)
        DocumentSelectionStore(
            context.getSharedPreferences(DocumentSelectionStore.PREFERENCES_NAME, 0),
        ).saveFolderUri(tree.toString())

        SpeechModelImportPermission.releaseOwnedIfUnused(context)

        assertFalse(
            context.getSharedPreferences("speech_model_import", 0)
                .getString("owned_uri", null).isNullOrBlank(),
        )
        DocumentSelectionStore(
            context.getSharedPreferences(DocumentSelectionStore.PREFERENCES_NAME, 0),
        ).clearFolderUri()
        SpeechModelImportPermission.releaseOwnedIfUnused(context)
    }

    companion object {
        private val files = linkedMapOf(
            "recording.wav" to "test-audio-wav".toByteArray(),
            "recording.m4a" to "test-audio-m4a".toByteArray(),
        )
        private val manifest = SpeechModelManifest(
            modelId = "test/model",
            version = "instrumented",
            repositoryRevision = "revision",
            files = files.map { (name, contents) ->
                SpeechModelFile(
                    name,
                    contents.size.toLong(),
                    MessageDigest.getInstance("SHA-256").digest(contents)
                        .joinToString("") { "%02x".format(it) },
                )
            },
            safetyMarginBytes = 0,
        )
    }
}
