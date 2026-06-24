package me.maxistar.watchface.notesrecognition

data class CatalogControlState(
    val outputEnabled: Boolean,
    val folderEnabled: Boolean,
    val refreshEnabled: Boolean,
    val transcribeAllEnabled: Boolean,
    val retryEnabled: Boolean,
)

object TranscriptionUiRules {
    fun catalogControls(
        modelReady: Boolean,
        outputSelected: Boolean,
        folderSelected: Boolean,
        pendingCount: Int,
        transcriptionActive: Boolean,
        scanning: Boolean,
    ): CatalogControlState {
        val idle = !transcriptionActive && !scanning
        val prerequisites = modelReady && outputSelected && folderSelected && idle
        return CatalogControlState(
            outputEnabled = modelReady && idle,
            folderEnabled = modelReady && idle,
            refreshEnabled = modelReady && folderSelected && idle,
            transcribeAllEnabled = prerequisites && pendingCount > 0,
            retryEnabled = prerequisites,
        )
    }

    fun percent(processedUs: Long, durationUs: Long?): Int? =
        durationUs
            ?.takeIf { it > 0 }
            ?.let { ((processedUs.coerceIn(0, it) * 100) / it).toInt() }
}
