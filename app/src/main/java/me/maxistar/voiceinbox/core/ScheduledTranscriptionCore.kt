package me.maxistar.voiceinbox.core

import java.util.Calendar
import java.util.TimeZone

data class ScheduledTranscriptionSettings(
    val enabled: Boolean = false,
    val hour: Int = ScheduledTranscriptionRules.DEFAULT_HOUR,
    val minute: Int = ScheduledTranscriptionRules.DEFAULT_MINUTE,
)

interface ScheduledTranscriptionSettingsStorage {
    fun load(): ScheduledTranscriptionSettings
    fun save(settings: ScheduledTranscriptionSettings)
}

object ScheduledTranscriptionRules {
    const val DEFAULT_HOUR = 1
    const val DEFAULT_MINUTE = 0

    fun normalize(settings: ScheduledTranscriptionSettings): ScheduledTranscriptionSettings =
        settings.copy(
            hour = settings.hour.coerceIn(0, 23),
            minute = settings.minute.coerceIn(0, 59),
        )

    fun nextRunAtMillis(
        nowMillis: Long,
        hour: Int,
        minute: Int,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Long {
        val normalized = normalize(ScheduledTranscriptionSettings(hour = hour, minute = minute))
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

    fun shouldStartTranscription(pendingCount: Int, transcriptionActive: Boolean): Boolean =
        pendingCount > 0 && !transcriptionActive
}
