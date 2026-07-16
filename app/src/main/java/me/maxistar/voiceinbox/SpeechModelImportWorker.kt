package me.maxistar.voiceinbox

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf

class SpeechModelImportWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val repository = SpeechModelRepository(
        root = applicationContext.noBackupFilesDir.resolve("models"),
    )

    override suspend fun doWork(): Result {
        val treeUri = inputData.getString(KEY_TREE_URI)?.let(Uri::parse)
            ?: return failure("No model folder was selected")
        setForeground(
            SpeechModelInstallationWork.foregroundInfo(
                applicationContext,
                0,
                "Preparing local speech model",
            ),
        )
        return try {
            val installed = SpeechModelLocalImporter(
                resolver = applicationContext.contentResolver,
                repository = repository,
            ).import(treeUri.toString()) { progress -> publishProgress(progress) }.getOrElse {
                return failure(it.message ?: "Could not import speech model")
            }
            SpeechModelPreparation.invalidate(NativeTranscriptionBridge::reset)
            Result.success(
                workDataOf(SpeechModelInstallationWork.KEY_MODEL_PATH to installed.absolutePath),
            )
        } finally {
            SpeechModelImportPermission.releaseOwnedIfUnused(applicationContext)
        }
    }

    private suspend fun publishProgress(progress: SpeechModelImportProgress) {
        val total = repository.manifest.totalSizeBytes
        val percent = ((progress.bytesCopied.coerceIn(0, total) * 100) / total).toInt()
        setProgress(
            workDataOf(
                SpeechModelInstallationWork.KEY_BYTES_DOWNLOADED to progress.bytesCopied,
                SpeechModelInstallationWork.KEY_TOTAL_BYTES to total,
                SpeechModelInstallationWork.KEY_MESSAGE to progress.message,
            ),
        )
        setForeground(
            SpeechModelInstallationWork.foregroundInfo(
                applicationContext,
                percent,
                progress.message,
            ),
        )
    }

    private fun failure(message: String): Result = Result.failure(
        workDataOf(SpeechModelInstallationWork.KEY_ERROR to message),
    )

    companion object {
        const val KEY_TREE_URI = "tree-uri"

        fun enqueue(context: Context, treeUri: Uri) {
            val request = OneTimeWorkRequestBuilder<SpeechModelImportWorker>()
                .setInputData(workDataOf(KEY_TREE_URI to treeUri.toString()))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                SpeechModelInstallationWork.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
