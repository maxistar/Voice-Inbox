package me.maxistar.voiceinbox

import me.maxistar.voiceinbox.core.*

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class SpeechModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val model: SpeechModelDescriptor by lazy {
        SpeechModelCatalog.resolveNetworkDownload(
            SpeechModelPackageIdentity(
                schemaVersion = inputData.getInt(KEY_SCHEMA_VERSION, -1),
                catalogId = inputData.getString(KEY_CATALOG_ID).orEmpty(),
                modelVersion = inputData.getString(KEY_MODEL_VERSION).orEmpty(),
            ),
            SpeechModelPlatform.ANDROID,
        ) ?: throw IllegalArgumentException(applicationContext.getString(R.string.error_model_unavailable_download))
    }
    private val repository = SpeechModelRepository(
        root = applicationContext.noBackupFilesDir.resolve("models"),
        descriptor = model,
    )
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override suspend fun doWork(): Result {
        return try {
            installModel()
        } catch (error: IllegalArgumentException) {
            failure(error.message ?: applicationContext.getString(R.string.error_model_unavailable_download))
        } catch (error: ForegroundPromotionException) {
            failure(applicationContext.getString(R.string.error_foreground_work))
        }
    }

    private suspend fun installModel(): Result {
        SpeechModelInstallationWork.promote(
            worker = this,
            context = applicationContext,
            progress = 0,
            message = applicationContext.getString(R.string.model_download_preparing),
            source = SpeechModelInstallationWork.Source.NETWORK_DOWNLOAD,
        )
        repository.prepareForInstall().getOrElse {
            return failure(it.message ?: applicationContext.getString(R.string.error_model_preflight))
        }

        var completedBytes = repository.manifest.files
            .filter(repository::isValidStagingFile)
            .sumOf { it.sizeBytes }
        publishProgress(completedBytes, applicationContext.getString(R.string.model_downloading))

        for (entry in repository.manifest.files) {
            currentCoroutineContext().ensureActive()
            if (repository.isValidStagingFile(entry)) {
                continue
            }

            var lastFailure: Throwable? = null
            for (attempt in 1..MAX_ATTEMPTS) {
                repository.cleanupFailedCurrentFile(entry)
                try {
                    downloadFile(entry, completedBytes)
                    repository.verifyFile(repository.temporaryFile(entry), entry).getOrThrow()
                    check(repository.temporaryFile(entry).renameTo(repository.stagingFile(entry))) {
                        "Failed to accept ${entry.name}"
                    }
                    completedBytes += entry.sizeBytes
                    publishProgress(completedBytes, applicationContext.getString(R.string.model_file_verified, entry.name))
                    lastFailure = null
                    break
                } catch (error: ForegroundPromotionException) {
                    repository.cleanupFailedCurrentFile(entry)
                    throw error
                } catch (error: Throwable) {
                    repository.cleanupFailedCurrentFile(entry)
                    currentCoroutineContext().ensureActive()
                    lastFailure = error
                    if (attempt < MAX_ATTEMPTS) {
                        delay(RETRY_DELAY_MS * attempt)
                    }
                }
            }
            if (lastFailure != null) {
                return failure(
                    applicationContext.getString(R.string.error_model_download, entry.name, lastFailure.message ?: applicationContext.getString(R.string.unknown_error)),
                )
            }
        }

        publishProgress(repository.manifest.totalSizeBytes, applicationContext.getString(R.string.model_activating))
        val installedDirectory = repository.activate().getOrElse {
            return failure(it.message ?: applicationContext.getString(R.string.error_model_activate))
        }
        SpeechModelWarmup.invalidate()
        return Result.success(workDataOf(KEY_MODEL_PATH to installedDirectory.absolutePath))
    }

    private suspend fun downloadFile(entry: SpeechModelFile, completedBytes: Long) {
        val request = Request.Builder()
            .url(repository.manifest.downloadUrl(entry))
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("Empty HTTP response")
            val temporary = repository.temporaryFile(entry)
            body.byteStream().use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var fileBytes = 0L
                    var lastReported = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        fileBytes += read
                        if (fileBytes - lastReported >= PROGRESS_STEP_BYTES) {
                            publishProgress(
                                completedBytes + fileBytes,
                                applicationContext.getString(R.string.model_file_downloading, entry.name),
                            )
                            lastReported = fileBytes
                        }
                    }
                }
            }
        }
    }

    private suspend fun publishProgress(bytes: Long, message: String) {
        val total = repository.manifest.totalSizeBytes
        val percent = ((bytes.coerceIn(0, total) * 100) / total).toInt()
        setProgress(
            workDataOf(
                KEY_BYTES_DOWNLOADED to bytes,
                KEY_TOTAL_BYTES to total,
                KEY_MESSAGE to message,
            ),
        )
        SpeechModelInstallationWork.promote(
            worker = this,
            context = applicationContext,
            progress = percent,
            message = message,
            source = SpeechModelInstallationWork.Source.NETWORK_DOWNLOAD,
        )
    }

    private fun failure(message: String): Result =
        Result.failure(SpeechModelInstallationWork.failureData(message))

    companion object {
        const val UNIQUE_WORK_NAME = SpeechModelInstallationWork.UNIQUE_WORK_NAME
        const val KEY_BYTES_DOWNLOADED = SpeechModelInstallationWork.KEY_BYTES_DOWNLOADED
        const val KEY_TOTAL_BYTES = SpeechModelInstallationWork.KEY_TOTAL_BYTES
        const val KEY_MESSAGE = SpeechModelInstallationWork.KEY_MESSAGE
        const val KEY_ERROR = SpeechModelInstallationWork.KEY_ERROR
        const val KEY_MODEL_PATH = SpeechModelInstallationWork.KEY_MODEL_PATH
        const val KEY_SCHEMA_VERSION = "speech-model-schema-version"
        const val KEY_CATALOG_ID = "speech-model-catalog-id"
        const val KEY_MODEL_VERSION = "speech-model-version"

        private const val MAX_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 2_000L
        private const val PROGRESS_STEP_BYTES = 2L * 1024L * 1024L
        fun enqueue(context: Context, descriptor: SpeechModelDescriptor = SpeechModelCatalog.defaultModel) {
            require(descriptor.distribution.networkDownloadAvailable) {
                context.getString(R.string.error_model_unavailable_download)
            }
            val request = OneTimeWorkRequestBuilder<SpeechModelDownloadWorker>()
                .setInputData(
                    workDataOf(
                        KEY_SCHEMA_VERSION to SpeechModelCatalog.PACKAGE_SCHEMA_VERSION,
                        KEY_CATALOG_ID to descriptor.catalogId,
                        KEY_MODEL_VERSION to descriptor.manifest.version,
                    ),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}
