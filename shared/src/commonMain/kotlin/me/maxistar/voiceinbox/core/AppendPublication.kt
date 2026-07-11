package me.maxistar.voiceinbox.core

object AppendPublication {
    fun publish(
        existingTail: String,
        entry: String,
        append: (String) -> Unit,
    ) {
        append(TranscriptOutput.separatorFor(existingTail) + entry)
    }
}
