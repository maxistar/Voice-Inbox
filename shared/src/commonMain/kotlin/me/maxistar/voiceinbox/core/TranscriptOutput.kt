package me.maxistar.voiceinbox.core

object TranscriptOutput {
    const val UNKNOWN_RECORDING_TIME_LABEL = "Recording time unknown"

    fun formatEntry(
        audioName: String,
        recordingTimeLabel: String?,
        transcript: String,
    ): String {
        val label = recordingTimeLabel ?: UNKNOWN_RECORDING_TIME_LABEL
        return "$audioName\n$label\n${transcript.trim()}"
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
