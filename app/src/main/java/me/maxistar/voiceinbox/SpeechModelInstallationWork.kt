package me.maxistar.voiceinbox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException

object SpeechModelInstallationWork {
    const val UNIQUE_WORK_NAME = "speech-model-installation"
    const val KEY_BYTES_DOWNLOADED = "bytes-downloaded"
    const val KEY_TOTAL_BYTES = "total-bytes"
    const val KEY_MESSAGE = "message"
    const val KEY_ERROR = "error"
    const val KEY_MODEL_PATH = "model-path"

    private const val NOTIFICATION_CHANNEL = "speech-model-download"
    private const val NOTIFICATION_ID = 1907
    private const val LOG_TAG = "ModelInstallation"

    enum class Source(
        val diagnosticName: String,
        val userFacingName: String,
    ) {
        NETWORK_DOWNLOAD("network-download", "model download"),
        LOCAL_IMPORT("local-import", "local model import"),
        TRANSCRIPTION("transcription", "transcription"),
    }

    fun foregroundInfo(context: Context, progress: Int, message: String): ForegroundInfo {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL,
                    "Speech model installation",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Voice Inbox")
            .setContentText(message)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress, false)
            .build()
        return foregroundInfo(NOTIFICATION_ID, notification)
    }

    fun foregroundInfo(notificationId: Int, notification: Notification): ForegroundInfo {
        val serviceType = foregroundServiceTypeForSdk(Build.VERSION.SDK_INT)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, serviceType)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    suspend fun promote(
        worker: CoroutineWorker,
        context: Context,
        progress: Int,
        message: String,
        source: Source,
    ) {
        promote(
            worker = worker,
            foregroundInfo = foregroundInfo(context, progress, message),
            source = source,
        )
    }

    suspend fun promote(
        worker: CoroutineWorker,
        foregroundInfo: ForegroundInfo,
        source: Source,
    ) {
        try {
            worker.setForeground(foregroundInfo)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw promotionFailure(source, error)
        }
    }

    fun promoteAsync(
        worker: CoroutineWorker,
        foregroundInfo: ForegroundInfo,
        source: Source,
    ) {
        try {
            worker.setForegroundAsync(foregroundInfo).get()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("Foreground promotion was interrupted").apply {
                initCause(interrupted)
            }
        } catch (error: Exception) {
            throw promotionFailure(source, error.cause ?: error)
        }
    }

    fun failureData(message: String): Data = workDataOf(KEY_ERROR to message)

    internal fun foregroundServiceTypeForSdk(sdkInt: Int): Int =
        if (sdkInt >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }

    internal fun foregroundFailureDiagnostic(
        source: Source,
        sdkInt: Int,
        manufacturer: String,
        model: String,
        error: Throwable,
    ): String =
        "Foreground promotion failed: source=${source.diagnosticName}, " +
            "sdk=$sdkInt, device=$manufacturer $model, " +
            "exception=${error.javaClass.name}: ${error.message ?: "no message"}"

    private fun promotionFailure(source: Source, error: Throwable): ForegroundPromotionException {
        Log.e(
            LOG_TAG,
            foregroundFailureDiagnostic(
                source = source,
                sdkInt = Build.VERSION.SDK_INT,
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                error = error,
            ),
            error,
        )
        return ForegroundPromotionException(
            userMessage = "Could not keep ${source.userFacingName} running in the foreground. Please try again.",
            cause = error,
        )
    }
}

class ForegroundPromotionException(
    val userMessage: String,
    cause: Throwable,
) : RuntimeException(userMessage, cause)
