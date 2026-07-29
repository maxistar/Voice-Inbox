package me.maxistar.voiceinbox

import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object AndroidAudioMetadataFormatter {
    fun format(
        timestampMillis: Long?,
        sizeBytes: Long?,
        durationUs: Long?,
        locale: Locale = Locale.getDefault(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String? {
        val parts = buildList {
            timestampMillis?.takeIf { it > 0 }?.let { timestamp ->
                add(
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
                        .apply { this.timeZone = timeZone }
                        .format(Date(timestamp)),
                )
            }
            sizeBytes?.takeIf { it > 0 }?.let { add(formatSize(it)) }
            durationUs?.takeIf { it > 0 }?.let { add(formatDuration(it)) }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < KIBIBYTE -> "$bytes B"
        bytes < MEBIBYTE -> "${bytes / KIBIBYTE} KiB"
        bytes < GIBIBYTE -> "${bytes / MEBIBYTE} MiB"
        else -> "${bytes / GIBIBYTE} GiB"
    }

    private fun formatDuration(durationUs: Long): String {
        val totalSeconds = durationUs / MICROSECONDS_PER_SECOND
        val hours = totalSeconds / SECONDS_PER_HOUR
        val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        val seconds = totalSeconds % SECONDS_PER_MINUTE
        return if (hours > 0) {
            "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds)
        } else {
            "%d:%02d".format(Locale.ROOT, minutes, seconds)
        }
    }

    private const val SEPARATOR = " • "
    private const val KIBIBYTE = 1024L
    private const val MEBIBYTE = 1024L * KIBIBYTE
    private const val GIBIBYTE = 1024L * MEBIBYTE
    private const val MICROSECONDS_PER_SECOND = 1_000_000L
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 60L * SECONDS_PER_MINUTE
}
