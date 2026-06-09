package me.maxistar.watchface.notesrecognition

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class TranscriptionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val audioUri = inputData.getString(KEY_AUDIO_URI)?.let(Uri::parse)
            ?: return@withContext failure("Audio file is missing")
        val outputUri = inputData.getString(KEY_OUTPUT_URI)?.let(Uri::parse)
            ?: return@withContext failure("Output file is missing")
        val audioName = inputData.getString(KEY_AUDIO_NAME) ?: "audio"
        val providerModified = inputData.getLong(KEY_AUDIO_MODIFIED, -1L).takeIf { it >= 0 }
        val staging = File(applicationContext.cacheDir, "transcript-$id.txt")
        val access = DocumentAccess(applicationContext.contentResolver)

        try {
            setForeground(foreground("Preparing transcription", 0, true))
            val repository = SpeechModelRepository(applicationContext.noBackupFilesDir.resolve("models"))
            val model = repository.inspect() as? InstalledSpeechModelState.Ready
                ?: return@withContext failure("Speech model is not installed")
            publish("Loading model", 0, true)
            if (!NativeTranscriptionBridge.initialize(model.directory.absolutePath)) {
                return@withContext failure("Speech model failed to load")
            }

            var transcript = ""
            var durationUs: Long? = null
            publish("Decoding audio", 0, true)
            val info = AndroidAudioDecoder(applicationContext.contentResolver).decode(
                uri = audioUri,
                onProgress = { processed, total ->
                    durationUs = total
                    val percent = TranscriptionUiRules.percent(processed, total) ?: 0
                    setProgressAsync(
                        workDataOf(
                            KEY_PHASE to "Transcribing",
                            KEY_PROGRESS to percent,
                            KEY_INDETERMINATE to (total == null),
                            KEY_PROCESSED_US to processed,
                            KEY_DURATION_US to (total ?: -1L),
                        ),
                    )
                },
                onChunk = { samples ->
                    val result = NativeTranscriptionBridge.transcribeChunk(samples)
                        ?: throw IOException("Speech recognition failed")
                    transcript = TranscriptMerger.merge(transcript, result.text)
                    staging.writeText(transcript)
                },
            )
            durationUs = info.durationUs ?: durationUs

            if (transcript.isBlank()) {
                staging.delete()
                return@withContext failure("No text was recognized")
            }

            publish("Appending transcript", 100, false)
            val entry = TranscriptOutput.formatEntry(
                audioName = audioName,
                recordingTimeMillis = info.embeddedRecordingTimeMillis ?: providerModified,
                transcript = transcript,
            )
            AppendPublication.publish(access.readTail(outputUri), entry) { text ->
                access.append(outputUri, text)
            }
            staging.delete()
            Result.success(
                workDataOf(
                    KEY_PHASE to "Completed",
                    KEY_PROGRESS to 100,
                    KEY_DURATION_US to (durationUs ?: -1L),
                ),
            )
        } catch (error: Throwable) {
            staging.delete()
            failure(error.message ?: "Transcription failed")
        }
    }

    private suspend fun publish(phase: String, progress: Int, indeterminate: Boolean) {
        setProgress(
            workDataOf(
                KEY_PHASE to phase,
                KEY_PROGRESS to progress,
                KEY_INDETERMINATE to indeterminate,
            ),
        )
        setForeground(foreground(phase, progress, indeterminate))
    }

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
            .setContentTitle("Notes Recognition")
            .setContentText(message)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress, indeterminate)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private fun failure(message: String): Result =
        Result.failure(workDataOf(KEY_ERROR to message, KEY_PHASE to "Failed"))

    companion object {
        const val UNIQUE_WORK_NAME = "audio-file-transcription"
        const val KEY_AUDIO_URI = "audio-uri"
        const val KEY_OUTPUT_URI = "output-uri"
        const val KEY_AUDIO_NAME = "audio-name"
        const val KEY_AUDIO_MODIFIED = "audio-modified"
        const val KEY_PHASE = "phase"
        const val KEY_PROGRESS = "progress"
        const val KEY_INDETERMINATE = "indeterminate"
        const val KEY_PROCESSED_US = "processed-us"
        const val KEY_DURATION_US = "duration-us"
        const val KEY_ERROR = "error"

        private const val NOTIFICATION_CHANNEL = "audio-transcription"
        private const val NOTIFICATION_ID = 2109

        fun enqueue(
            context: Context,
            audioUri: Uri,
            outputUri: Uri,
            audioMetadata: DocumentMetadata,
        ) {
            val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
                .setInputData(
                    workDataOf(
                        KEY_AUDIO_URI to audioUri.toString(),
                        KEY_OUTPUT_URI to outputUri.toString(),
                        KEY_AUDIO_NAME to audioMetadata.displayName,
                        KEY_AUDIO_MODIFIED to (audioMetadata.lastModifiedMillis ?: -1L),
                    ),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
