package me.maxistar.voiceinbox.core

import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object TranscriptOutput {
    fun formatEntry(
        audioName: String,
        recordingTimeMillis: Long?,
        transcript: String,
        locale: Locale = Locale.getDefault(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        val date = if (recordingTimeMillis == null) {
            "Recording time unknown"
        } else {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, locale).apply {
                this.timeZone = timeZone
            }.format(Date(recordingTimeMillis))
        }
        return "$audioName\n$date\n${transcript.trim()}"
    }

    fun separatorFor(existingTail: String): String {
        if (existingTail.isEmpty()) return ""
        val normalized = existingTail.replace("\r\n", "\n")
        val trailingNewlines = normalized.takeLastWhile { it == '\n' }.length
        return when {
            trailingNewlines >= 2 -> ""
            trailingNewlines == 1 -> "\n"
            else -> "\n\n"
        }
    }
}
