package me.maxistar.voiceinbox.core

object AppendPublication {
    const val DEFAULT_TAIL_BYTES = 4096

    fun publish(
        existingTail: String,
        entry: String,
        append: (String) -> Unit,
    ) {
        append(TranscriptOutput.separatorFor(existingTail) + entry)
    }

    fun requiredTailBytes(entry: String): Int =
        maxOf(DEFAULT_TAIL_BYTES, entry.encodeToByteArray().size + 4)

    fun containsCompleteEntry(existingTail: String, entry: String): Boolean {
        val normalizedTail = normalize(existingTail)
        val normalizedEntry = normalize(entry)
        if (normalizedTail.isEmpty() || normalizedEntry.isEmpty()) return false

        var start = normalizedTail.indexOf(normalizedEntry)
        while (start >= 0) {
            val beforeIsBoundary = start == 0 || normalizedTail[start - 1] == '\n'
            val end = start + normalizedEntry.length
            val afterIsBoundary = end == normalizedTail.length ||
                normalizedTail.substring(end).all { it == '\n' }
            if (beforeIsBoundary && afterIsBoundary) return true
            start = normalizedTail.indexOf(normalizedEntry, start + 1)
        }
        return false
    }

    fun appendText(existingTail: String, entry: String): String =
        TranscriptOutput.separatorFor(existingTail) + entry

    private fun normalize(value: String): String =
        value.replace("\r\n", "\n").replace('\r', '\n')
}
