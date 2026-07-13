package me.maxistar.voiceinbox

import me.maxistar.voiceinbox.core.*

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ScheduledTranscriptionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val result = try {
            runScheduledTrigger()
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            Result.success()
        }
        rescheduleIfStillEnabled()
        result
    }

    private fun runScheduledTrigger() {
        val preferences = applicationContext.getSharedPreferences(
            ScheduledTranscriptionSettingsStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
        val settingsStore = ScheduledTranscriptionSettingsStore(preferences)
        if (!settingsStore.load().enabled) return

        val selectionStore = DocumentSelectionStore(
            applicationContext.getSharedPreferences(
                DocumentSelectionStore.PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            ),
        )
        val folder = selectionStore.loadFolderUri()?.let(Uri::parse) ?: return
        val output = selectionStore.loadOutputUri()?.let(Uri::parse) ?: return

        val documentAccess = DocumentAccess(applicationContext.contentResolver)
        val folderScanner = AudioFolderScanner(applicationContext.contentResolver)
        documentAccess.requireAppendable(output)
        folderScanner.requireReadable(folder)

        val model = SpeechModelRepository(
            applicationContext.noBackupFilesDir.resolve("models"),
        ).inspect() as? InstalledSpeechModelState.Ready ?: return
        if (!NativeTranscriptionBridge.initialize(model.directory.absolutePath)) return

        val catalog = AndroidSqlDelightAudioCatalogFactory(applicationContext).create()
        val files = folderScanner.scan(folder)
        catalog.reconcile(folder.toString(), files)
        val pending = catalog.pendingCount(folder.toString())
        if (ScheduledTranscriptionRules.shouldStartTranscription(pending, transcriptionActive())) {
            TranscriptionWorker.enqueueAll(applicationContext, folder, output)
        }
    }

    private fun transcriptionActive(): Boolean =
        WorkManager.getInstance(applicationContext)
            .getWorkInfosForUniqueWork(TranscriptionWorker.UNIQUE_WORK_NAME)
            .get()
            .any { it.state in ACTIVE_TRANSCRIPTION_STATES }

    private fun rescheduleIfStillEnabled() {
        val preferences = applicationContext.getSharedPreferences(
            ScheduledTranscriptionSettingsStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
        val settings = ScheduledTranscriptionSettingsStore(preferences).load()
        if (settings.enabled) {
            ScheduledTranscriptionScheduler.scheduleNext(applicationContext, settings)
        }
    }

    private companion object {
        val ACTIVE_TRANSCRIPTION_STATES = setOf(
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.BLOCKED,
            WorkInfo.State.RUNNING,
        )
    }
}
