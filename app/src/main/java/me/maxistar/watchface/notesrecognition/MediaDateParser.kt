package me.maxistar.watchface.notesrecognition

import java.text.SimpleDateFormat
import java.util.Locale

object MediaDateParser {
    private val formats = listOf(
        "yyyyMMdd'T'HHmmss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd HH:mm:ss",
    )

    fun parse(value: String): Long? {
        value.toLongOrNull()?.let { numeric ->
            return if (numeric < 10_000_000_000L) numeric * 1000 else numeric
        }
        return formats.firstNotNullOfOrNull { pattern ->
            runCatching {
                SimpleDateFormat(pattern, Locale.US).parse(value)?.time
            }.getOrNull()
        }
    }
}
