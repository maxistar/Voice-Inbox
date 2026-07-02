package me.maxistar.voiceinbox

object AppendPublication {
    fun publish(
        existingTail: String,
        entry: String,
        append: (String) -> Unit,
    ) {
        append(TranscriptOutput.separatorFor(existingTail) + entry)
    }
}
