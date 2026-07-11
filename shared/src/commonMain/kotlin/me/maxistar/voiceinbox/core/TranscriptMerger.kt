package me.maxistar.voiceinbox.core

object TranscriptMerger {
    fun merge(accumulated: String, next: String, maxOverlapWords: Int = 20): String {
        val left = accumulated.trim()
        val right = next.trim()
        if (left.isEmpty()) return right
        if (right.isEmpty()) return left

        val leftWords = left.split(Regex("\\s+"))
        val rightWords = right.split(Regex("\\s+"))
        val maximum = minOf(maxOverlapWords, leftWords.size, rightWords.size)
        val overlap = (maximum downTo 1).firstOrNull { count ->
            leftWords.takeLast(count).map(::normalize) == rightWords.take(count).map(::normalize)
        } ?: 0
        return (leftWords + rightWords.drop(overlap)).joinToString(" ")
    }

    private fun normalize(word: String): String =
        word.lowercase().trim { !it.isLetterOrDigit() }
}
