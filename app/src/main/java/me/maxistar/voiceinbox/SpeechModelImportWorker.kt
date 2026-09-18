package me.maxistar.voiceinbox

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import me.maxistar.voiceinbox.core.SpeechModelCatalog

class SpeechModelImportWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val treeUri = inputData.getString(KEY_TREE_URI)?.let(Uri::parse)
            ?: return failure(applicationContext.getString(R.string.error_model_folder_missing))
        val catalogId = inputData.getString(KEY_CATALOG_ID)
            ?: return failure(applicationContext.getString(R.string.error_model_missing))
        val modelVersion = inputData.getString(KEY_MODEL_VERSION)
            ?: return failure(applicationContext.getString(R.string.error_model_version_missing))
        val descriptor = SpeechModelCatalog.resolveInstallation(catalogId, modelVersion)
            ?.takeIf { it.distribution.localImportAvailable }
            ?: return failure(applicationContext.getString(R.string.error_model_unsupported))
        val repository = SpeechModelRepository(
            root = applicationContext.noBackupFilesDir.resolve("models"),
            descriptor = descriptor,
        )
        return try {
            SpeechModelInstallationWork.promote(
                worker = this,
                context = applicationContext,
                progress = 0,
                message = applicationContext.getString(R.string.model_import_preparing),
                source = SpeechModelInstallationWork.Source.LOCAL_IMPORT,
            )
            val installed = SpeechModelLocalImporter(
                resolver = applicationContext.contentResolver,
                repository = repository,
            ).import(treeUri.toString()) { progress -> publishProgress(progress, repository) }.getOrElse {
                return failure(it.message ?: applicationContext.getString(R.string.error_model_import))
            }
            SpeechModelWarmup.invalidate()
            Result.success(
                workDataOf(SpeechModelInstallationWork.KEY_MODEL_PATH to installed.absolutePath),
            )
        } catch (error: ForegroundPromotionException) {
            failure(error.userMessage)
        } finally {
            SpeechModelImportPermission.releaseOwnedIfUnused(applicationContext)
        }
    }

    private suspend fun publishProgress(
        progress: SpeechModelImportProgress,
        repository: SpeechModelRepository,
    ) {
        val total = repository.manifest.totalSizeBytes
        val percent = ((progress.bytesCopied.coerceIn(0, total) * 100) / total).toInt()
        setProgress(
            workDataOf(
                SpeechModelInstallationWork.KEY_BYTES_DOWNLOADED to progress.bytesCopied,
                SpeechModelInstallationWork.KEY_TOTAL_BYTES to total,
                SpeechModelInstallationWork.KEY_MESSAGE to localizedProgressMessage(progress.message),
            ),
        )
        SpeechModelInstallationWork.promote(
            worker = this,
            context = applicationContext,
            progress = percent,
            message = localizedProgressMessage(progress.message),
            source = SpeechModelInstallationWork.Source.LOCAL_IMPORT,
        )
    }

    private fun localizedProgressMessage(message: String): String = when {
        message == "Preparing local speech model" -> applicationContext.getString(R.string.model_import_preparing)
        message == "Activating speech model" -> applicationContext.getString(R.string.model_activating)
        message.startsWith("Copying ") -> applicationContext.getString(
            R.string.model_file_downloading,
            message.removePrefix("Copying "),
        )
        message.startsWith("Verified ") -> applicationContext.getString(
            R.string.model_file_verified,
            message.removePrefix("Verified "),
        )
        else -> message
    }

    private fun failure(message: String): Result = Result.failure(
        SpeechModelInstallationWork.failureData(message),
    )

    companion object {
        const val KEY_TREE_URI = "tree-uri"
        const val KEY_CATALOG_ID = "catalog-id"
        const val KEY_MODEL_VERSION = "model-version"

        fun enqueue(
            context: Context,
            treeUri: Uri,
            descriptor: me.maxistar.voiceinbox.core.SpeechModelDescriptor,
        ) {
            val request = OneTimeWorkRequestBuilder<SpeechModelImportWorker>()
                .setInputData(
                    workDataOf(
                        KEY_TREE_URI to treeUri.toString(),
                        KEY_CATALOG_ID to descriptor.catalogId,
                        KEY_MODEL_VERSION to descriptor.manifest.version,
                    ),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                SpeechModelInstallationWork.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
