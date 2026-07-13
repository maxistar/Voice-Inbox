package me.maxistar.voiceinbox

import me.maxistar.voiceinbox.core.*

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class TranscriptionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val folderUri = inputData.getString(KEY_FOLDER_URI)
            ?: return@withContext failure("Audio folder is missing")
        val outputUri = inputData.getString(KEY_OUTPUT_URI)?.let(Uri::parse)
            ?: return@withContext failure("Output file is missing")
        val retryId = inputData.getLong(KEY_RETRY_ID, NO_RETRY_ID).takeIf { it != NO_RETRY_ID }
        val catalog: AudioCatalogQueuePort =
            AndroidSqlDelightAudioCatalogFactory(applicationContext).create()

        try {
            setForeground(foreground("Preparing transcription", 0, true))
            val model = SpeechModelRepository(
                applicationContext.noBackupFilesDir.resolve("models"),
            ).inspect() as? InstalledSpeechModelState.Ready
                ?: return@withContext failure("Speech model is not installed")
            publish("Loading model", null, 0, 0, null, null)
            if (!NativeTranscriptionBridge.initialize(model.directory.absolutePath)) {
                return@withContext failure("Speech model failed to load")
            }

            val batch = BatchTranscriptionUseCase(
                catalog = catalog,
                transcriber = AndroidBatchEntryTranscriber(
                    SingleFileTranscriber(applicationContext),
                    outputUri,
                ),
                clock = SystemBatchClock,
            )
            val result = batch.transcribe(
                BatchTranscriptionInput(
                    folderId = folderUri,
                    outputId = outputUri.toString(),
                    runId = id.toString(),
                    retryEntryId = retryId,
                ),
            ) { progress ->
                publishAsync(progress)
            }

            Result.success(
                workDataOf(
                    KEY_PHASE to result.summary,
                    KEY_COMPLETED_FILES to result.completed,
                    KEY_TOTAL_FILES to result.total,
                    KEY_FAILED_FILES to result.failed,
                    KEY_PROGRESS to result.progress,
                    KEY_INDETERMINATE to result.indeterminate,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            failure(error.message ?: "Transcription failed")
        }
    }

    private suspend fun publish(
        phase: String,
        filename: String?,
        completed: Int,
        total: Int,
        processedUs: Long?,
        durationUs: Long?,
    ) {
        val percent = BatchTranscriptionRules.percent(processedUs, durationUs)
        val data = progressData(
            phase,
            filename,
            completed,
            total,
            failed = 0,
            processedUs,
            durationUs,
            percent,
        )
        setProgress(data)
        setForeground(foreground(notificationText(phase, filename, completed, total), percent ?: 0, percent == null))
    }

    private fun publishAsync(progress: BatchTranscriptionProgress) {
        publishAsync(
            phase = progress.phase,
            filename = progress.filename,
            completed = progress.completed,
            total = progress.total,
            failed = progress.failed,
            processedUs = progress.processedUs,
            durationUs = progress.durationUs,
            progress = progress.progress,
        )
    }

    private fun publishAsync(
        phase: String,
        filename: String?,
        completed: Int,
        total: Int,
        failed: Int,
        processedUs: Long?,
        durationUs: Long?,
        progress: Int?,
    ) {
        val data = progressData(
            phase,
            filename,
            completed,
            total,
            failed,
            processedUs,
            durationUs,
            progress,
        )
        setProgressAsync(data)
        setForegroundAsync(
            foreground(
                notificationText(phase, filename, completed, total),
                progress ?: 0,
                progress == null,
            ),
        )
    }

    private fun progressData(
        phase: String,
        filename: String?,
        completed: Int,
        total: Int,
        failed: Int,
        processedUs: Long?,
        durationUs: Long?,
        progress: Int?,
    ) = workDataOf(
        KEY_PHASE to phase,
        KEY_FILENAME to filename,
        KEY_COMPLETED_FILES to completed,
        KEY_TOTAL_FILES to total,
        KEY_FAILED_FILES to failed,
        KEY_PROGRESS to (progress ?: 0),
        KEY_INDETERMINATE to (progress == null),
        KEY_PROCESSED_US to (processedUs ?: -1L),
        KEY_DURATION_US to (durationUs ?: -1L),
    )

    private fun foreground(message: String, progress: Int, indeterminate: Boolean): ForegroundInfo {
        val manager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                "Audio transcription",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Voice Inbox")
            .setContentText(message)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress, indeterminate)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private fun notificationText(
        phase: String,
        filename: String?,
        completed: Int,
        total: Int,
    ): String = buildString {
        if (filename != null) append("$filename: ")
        append(phase)
        if (total > 0) append(" ($completed/$total)")
    }

    private fun failure(message: String): Result =
        Result.failure(workDataOf(KEY_ERROR to message, KEY_PHASE to "Failed"))

    private class AndroidBatchEntryTranscriber(
        private val transcriber: SingleFileTranscriber,
        private val outputUri: Uri,
    ) : BatchEntryTranscriber {
        override fun transcribe(
            entry: AudioCatalogEntry,
            outputId: String,
            runId: String,
            onProgress: (SingleFileTranscriptionProgress) -> Unit,
        ): SingleFileTranscriptionResult {
            val result = transcriber.transcribe(entry, outputUri, runId) { progress ->
                onProgress(
                    SingleFileTranscriptionProgress(
                        phase = progress.phase,
                        processedUs = progress.processedUs,
                        durationUs = progress.durationUs,
                    ),
                )
            }
            return SingleFileTranscriptionResult(
                durationUs = result.durationUs,
                transcriptLength = result.transcriptLength,
                transcriptText = result.transcriptText,
            )
        }
    }

    private object SystemBatchClock : BatchClock {
        override fun currentTimeMillis(): Long = System.currentTimeMillis()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "audio-folder-transcription"
        const val KEY_FOLDER_URI = "folder-uri"
        const val KEY_OUTPUT_URI = "output-uri"
        const val KEY_RETRY_ID = "retry-id"
        const val KEY_PHASE = "phase"
        const val KEY_FILENAME = "filename"
        const val KEY_COMPLETED_FILES = "completed-files"
        const val KEY_TOTAL_FILES = "total-files"
        const val KEY_FAILED_FILES = "failed-files"
        const val KEY_PROGRESS = "progress"
        const val KEY_INDETERMINATE = "indeterminate"
        const val KEY_PROCESSED_US = "processed-us"
        const val KEY_DURATION_US = "duration-us"
        const val KEY_ERROR = "error"

        private const val NO_RETRY_ID = -1L
        private const val NOTIFICATION_CHANNEL = "audio-transcription"
        private const val NOTIFICATION_ID = 2109

        fun enqueueAll(context: Context, folderUri: Uri, outputUri: Uri): UUID =
            enqueue(context, folderUri, outputUri, null)

        fun enqueueRetry(
            context: Context,
            folderUri: Uri,
            outputUri: Uri,
            entryId: Long,
        ): UUID = enqueue(context, folderUri, outputUri, entryId)

        private fun enqueue(
            context: Context,
            folderUri: Uri,
            outputUri: Uri,
            retryId: Long?,
        ): UUID {
            val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
                .setInputData(
                    workDataOf(
                        KEY_FOLDER_URI to folderUri.toString(),
                        KEY_OUTPUT_URI to outputUri.toString(),
                        KEY_RETRY_ID to (retryId ?: NO_RETRY_ID),
                    ),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
            return request.id
        }
    }
}
