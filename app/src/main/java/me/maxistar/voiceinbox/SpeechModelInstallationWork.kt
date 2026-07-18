package me.maxistar.voiceinbox

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo

object SpeechModelInstallationWork {
    const val UNIQUE_WORK_NAME = "speech-model-installation"
    const val KEY_BYTES_DOWNLOADED = "bytes-downloaded"
    const val KEY_TOTAL_BYTES = "total-bytes"
    const val KEY_MESSAGE = "message"
    const val KEY_ERROR = "error"
    const val KEY_MODEL_PATH = "model-path"

    private const val NOTIFICATION_CHANNEL = "speech-model-download"
    private const val NOTIFICATION_ID = 1907

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
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }
}
