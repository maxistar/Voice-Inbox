package me.maxistar.voiceinbox

object BatchTranscriptionRules {
    fun percent(processedUs: Long?, durationUs: Long?): Int? {
        if (processedUs == null || durationUs == null || durationUs <= 0) return null
        return ((processedUs.coerceIn(0, durationUs) * 100) / durationUs).toInt()
    }

    fun summary(completed: Int, total: Int, failed: Int): String =
        if (failed == 0) {
            "Completed $completed of $total"
        } else {
            "Completed $completed of $total, $failed failed"
        }
}
