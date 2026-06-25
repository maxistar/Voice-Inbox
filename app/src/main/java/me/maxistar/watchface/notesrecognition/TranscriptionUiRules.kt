package me.maxistar.watchface.notesrecognition

data class CatalogControlState(
    val outputEnabled: Boolean,
    val folderEnabled: Boolean,
    val refreshEnabled: Boolean,
    val transcribeAllEnabled: Boolean,
    val retryEnabled: Boolean,
)

data class StatusProgressInput(
    val modelMessage: String,
    val modelLoading: Boolean,
    val modelDownloadAvailable: Boolean,
    val modelDownloadProgress: Int?,
    val modelReady: Boolean,
    val outputSelected: Boolean,
    val folderSelected: Boolean,
    val pendingCount: Int,
    val scanning: Boolean,
    val scanMessage: String?,
    val transcriptionActive: Boolean,
    val transcriptionFinished: Boolean,
    val transcriptionPhase: String?,
    val transcriptionFilename: String?,
    val transcriptionIndeterminate: Boolean,
    val transcriptionProgress: Int,
    val processedUs: Long,
    val durationUs: Long,
    val completedFiles: Int,
    val totalFiles: Int,
    val failedFiles: Int,
    val errorMessage: String?,
)

data class StatusProgressBlockState(
    val title: String,
    val detail: String? = null,
    val meta: String? = null,
    val progressVisible: Boolean = false,
    val progressIndeterminate: Boolean = false,
    val progress: Int = 0,
    val downloadVisible: Boolean = false,
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

    fun statusProgressBlock(input: StatusProgressInput): StatusProgressBlockState =
        when {
            input.transcriptionActive -> activeTranscription(input)
            input.scanning -> StatusProgressBlockState(
                title = input.scanMessage ?: "Scanning folder",
                progressVisible = true,
                progressIndeterminate = true,
            )
            input.modelLoading -> StatusProgressBlockState(
                title = input.modelMessage,
                progressVisible = true,
                progressIndeterminate = input.modelDownloadProgress == null,
                progress = input.modelDownloadProgress ?: 0,
            )
            !input.modelReady -> StatusProgressBlockState(
                title = input.modelMessage,
                downloadVisible = input.modelDownloadAvailable,
            )
            input.errorMessage != null -> StatusProgressBlockState(
                title = input.errorMessage,
            )
            !input.outputSelected || !input.folderSelected -> setupRequired(input)
            input.transcriptionFinished -> completedTranscription(input)
            input.scanMessage != null -> StatusProgressBlockState(
                title = input.scanMessage,
                detail = idleDetail(input.pendingCount),
            )
            else -> StatusProgressBlockState(
                title = "Ready",
                detail = idleDetail(input.pendingCount),
            )
        }

    private fun activeTranscription(input: StatusProgressInput): StatusProgressBlockState =
        StatusProgressBlockState(
            title = input.transcriptionFilename ?: input.transcriptionPhase ?: "Transcribing",
            detail = if (input.transcriptionFilename == null) {
                null
            } else {
                input.transcriptionPhase ?: "Transcribing"
            },
            meta = progressMeta(input),
            progressVisible = true,
            progressIndeterminate = input.transcriptionIndeterminate,
            progress = input.transcriptionProgress,
        )

    private fun completedTranscription(input: StatusProgressInput): StatusProgressBlockState =
        StatusProgressBlockState(
            title = input.transcriptionPhase ?: "Completed",
            meta = progressMeta(input),
            progressVisible = true,
            progressIndeterminate = false,
            progress = input.transcriptionProgress.coerceAtLeast(100),
        )

    private fun setupRequired(input: StatusProgressInput): StatusProgressBlockState {
        val missing = listOfNotNull(
            if (!input.outputSelected) "output file" else null,
            if (!input.folderSelected) "audio folder" else null,
        ).joinToString(" and ")
        return StatusProgressBlockState(
            title = "Setup required",
            detail = "Select $missing to start",
        )
    }

    private fun idleDetail(pendingCount: Int): String =
        if (pendingCount > 0) {
            "$pendingCount files ready to transcribe"
        } else {
            "No new files to transcribe"
        }

    private fun progressMeta(input: StatusProgressInput): String? = buildString {
        if (input.processedUs >= 0 && input.durationUs > 0) {
            append("${formatDuration(input.processedUs)} / ${formatDuration(input.durationUs)}")
        }
        if (input.totalFiles > 0) {
            if (isNotEmpty()) append(" • ")
            append("${input.completedFiles} / ${input.totalFiles} files")
        }
        if (input.failedFiles > 0) {
            if (isNotEmpty()) append(" • ")
            append("${input.failedFiles} failed")
        }
    }.takeIf { it.isNotEmpty() }

    private fun formatDuration(microseconds: Long): String {
        val totalSeconds = microseconds / 1_000_000
        return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }
}
