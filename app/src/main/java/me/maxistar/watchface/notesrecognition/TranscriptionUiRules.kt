package me.maxistar.watchface.notesrecognition

data class FileControlState(
    val outputEnabled: Boolean,
    val audioEnabled: Boolean,
)

object TranscriptionUiRules {
    fun fileControls(
        modelReady: Boolean,
        outputSelected: Boolean,
        transcriptionActive: Boolean,
    ): FileControlState = FileControlState(
        outputEnabled = modelReady && !transcriptionActive,
        audioEnabled = modelReady && outputSelected && !transcriptionActive,
    )

    fun percent(processedUs: Long, durationUs: Long?): Int? =
        durationUs
            ?.takeIf { it > 0 }
            ?.let { ((processedUs.coerceIn(0, it) * 100) / it).toInt() }
}
