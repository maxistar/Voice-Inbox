package me.maxistar.voiceinbox.core

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

    fun shouldStartTranscription(pendingCount: Int, transcriptionActive: Boolean): Boolean =
        pendingCount > 0 && !transcriptionActive
}
