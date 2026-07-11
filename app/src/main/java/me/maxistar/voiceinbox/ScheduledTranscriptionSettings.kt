package me.maxistar.voiceinbox

import android.content.Context
import android.content.SharedPreferences
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import me.maxistar.voiceinbox.core.ScheduledTranscriptionRules
import me.maxistar.voiceinbox.core.ScheduledTranscriptionSettings
import me.maxistar.voiceinbox.core.ScheduledTranscriptionSettingsStorage

class ScheduledTranscriptionSettingsStore(
    private val storage: ScheduledTranscriptionSettingsStorage,
) {
    constructor(preferences: SharedPreferences) : this(
        SharedPreferencesScheduledTranscriptionSettingsStorage(preferences),
    )

    fun load(): ScheduledTranscriptionSettings = storage.load()

    fun save(settings: ScheduledTranscriptionSettings) {
        storage.save(ScheduledTranscriptionRules.normalize(settings))
    }

    fun setEnabled(enabled: Boolean): ScheduledTranscriptionSettings =
        load().copy(enabled = enabled).also(::save)

    fun setTime(hour: Int, minute: Int): ScheduledTranscriptionSettings =
        load().copy(hour = hour, minute = minute).also(::save)

    companion object {
        const val PREFERENCES_NAME = "scheduled_transcription"
    }
}

private class SharedPreferencesScheduledTranscriptionSettingsStorage(
    private val preferences: SharedPreferences,
) : ScheduledTranscriptionSettingsStorage {
    override fun load(): ScheduledTranscriptionSettings =
        ScheduledTranscriptionSettings(
            enabled = preferences.getBoolean(KEY_ENABLED, false),
            hour = preferences.getInt(KEY_HOUR, ScheduledTranscriptionRules.DEFAULT_HOUR),
            minute = preferences.getInt(KEY_MINUTE, ScheduledTranscriptionRules.DEFAULT_MINUTE),
        ).let(ScheduledTranscriptionRules::normalize)

    override fun save(settings: ScheduledTranscriptionSettings) {
        preferences.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putInt(KEY_HOUR, settings.hour)
            .putInt(KEY_MINUTE, settings.minute)
            .apply()
    }

    companion object {
        private const val KEY_ENABLED = "scheduled_transcription_enabled"
        private const val KEY_HOUR = "scheduled_transcription_hour"
        private const val KEY_MINUTE = "scheduled_transcription_minute"
    }
}

object ScheduledTranscriptionScheduler {
    const val UNIQUE_WORK_NAME = "scheduled-note-transcription"

    fun sync(context: Context, settings: ScheduledTranscriptionSettings) {
        if (settings.enabled) {
            scheduleNext(context, settings)
        } else {
            cancel(context)
        }
    }

    fun scheduleNext(
        context: Context,
        settings: ScheduledTranscriptionSettings,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val normalized = ScheduledTranscriptionRules.normalize(settings)
        if (!normalized.enabled) {
            cancel(context)
            return
        }
        val request = OneTimeWorkRequestBuilder<ScheduledTranscriptionWorker>()
            .setInitialDelay(
                delayUntilNextRunMillis(nowMillis, normalized),
                TimeUnit.MILLISECONDS,
            )
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    fun nextRunAtMillis(
        nowMillis: Long,
        hour: Int,
        minute: Int,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Long {
        val normalized = ScheduledTranscriptionRules.normalize(
            ScheduledTranscriptionSettings(hour = hour, minute = minute),
        )
        val calendar = Calendar.getInstance(timeZone).apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, normalized.hour)
            set(Calendar.MINUTE, normalized.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= nowMillis) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return calendar.timeInMillis
    }

    fun delayUntilNextRunMillis(
        nowMillis: Long,
        settings: ScheduledTranscriptionSettings,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Long =
        (nextRunAtMillis(nowMillis, settings.hour, settings.minute, timeZone) - nowMillis)
            .coerceAtLeast(0L)
}
