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
        return try {
            SpeechModelInstallationWork.promote(
                worker = this,
                context = applicationContext,
                progress = 0,
                message = "Preparing local speech model",
                source = SpeechModelInstallationWork.Source.LOCAL_IMPORT,
            )
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
        } catch (error: ForegroundPromotionException) {
            failure(error.userMessage)
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
        SpeechModelInstallationWork.promote(
            worker = this,
            context = applicationContext,
            progress = percent,
            message = progress.message,
            source = SpeechModelInstallationWork.Source.LOCAL_IMPORT,
        )
    }

    private fun failure(message: String): Result = Result.failure(
        SpeechModelInstallationWork.failureData(message),
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
