package me.maxistar.voiceinbox.core

data class CatalogControlState(
    val outputEnabled: Boolean,
    val folderEnabled: Boolean,
    val outputSetupVisible: Boolean,
    val folderSetupVisible: Boolean,
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
    val folderChecking: Boolean,
    val scanning: Boolean,
    val scanMessage: String?,
    val transcriptionState: TranscriptionObservationState,
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

enum class PreviewPlaybackState {
    IDLE,
    LOADING,
    PLAYING,
}

enum class TranscriptionObservationState {
    UNKNOWN,
    IDLE,
    ACTIVE,
    FINISHED,
}

data class TranscriptionObservationInput(
    val hasActiveWork: Boolean,
    val hasCurrentSessionFinishedWork: Boolean,
)

data class PreviewControlState(
    val label: String,
    val enabled: Boolean,
)

object TranscriptionUiRules {
    fun transcriptionObservation(input: TranscriptionObservationInput): TranscriptionObservationState =
        when {
            input.hasActiveWork -> TranscriptionObservationState.ACTIVE
            input.hasCurrentSessionFinishedWork -> TranscriptionObservationState.FINISHED
            else -> TranscriptionObservationState.IDLE
        }

    fun catalogControls(
        modelReady: Boolean,
        outputSelected: Boolean,
        folderSelected: Boolean,
        pendingCount: Int,
        transcriptionState: TranscriptionObservationState,
        scanning: Boolean,
    ): CatalogControlState {
        val workReady = transcriptionState == TranscriptionObservationState.IDLE ||
            transcriptionState == TranscriptionObservationState.FINISHED
        val idle = workReady && !scanning
        val prerequisites = modelReady && outputSelected && folderSelected && idle
        return CatalogControlState(
            outputEnabled = modelReady && idle,
            folderEnabled = modelReady && idle,
            outputSetupVisible = !outputSelected,
            folderSetupVisible = !folderSelected,
            refreshEnabled = modelReady && folderSelected && idle,
            transcribeAllEnabled = prerequisites && pendingCount > 0,
            retryEnabled = prerequisites,
        )
    }

    fun percent(processedUs: Long, durationUs: Long?): Int? =
        durationUs
            ?.takeIf { it > 0 }
            ?.let { ((processedUs.coerceIn(0, it) * 100) / it).toInt() }

    fun previewControl(
        entryId: Long,
        activeEntryId: Long?,
        playbackState: PreviewPlaybackState,
        transcriptionState: TranscriptionObservationState,
        scanning: Boolean,
    ): PreviewControlState {
        val activeForEntry = entryId == activeEntryId
        val blocked = transcriptionState != TranscriptionObservationState.IDLE &&
            transcriptionState != TranscriptionObservationState.FINISHED || scanning
        return when {
            activeForEntry && playbackState == PreviewPlaybackState.LOADING ->
                PreviewControlState("Loading...", enabled = true)
            activeForEntry && playbackState == PreviewPlaybackState.PLAYING ->
                PreviewControlState("Stop", enabled = true)
            else -> PreviewControlState("Play", enabled = !blocked)
        }
    }

    fun statusProgressBlock(input: StatusProgressInput): StatusProgressBlockState =
        when {
            input.transcriptionState == TranscriptionObservationState.ACTIVE -> activeTranscription(input)
            input.modelLoading -> StatusProgressBlockState(
                title = input.modelMessage,
                progressVisible = true,
                progressIndeterminate = input.modelDownloadProgress == null,
                progress = input.modelDownloadProgress ?: 0,
            )
            input.folderChecking -> StatusProgressBlockState(
                title = "Checking audio folder",
                progressVisible = true,
                progressIndeterminate = true,
            )
            input.scanning -> StatusProgressBlockState(
                title = input.scanMessage ?: "Scanning folder",
                progressVisible = true,
                progressIndeterminate = true,
            )
            !input.modelReady -> StatusProgressBlockState(
                title = input.modelMessage,
                downloadVisible = input.modelDownloadAvailable,
            )
            input.errorMessage != null -> StatusProgressBlockState(
                title = input.errorMessage,
            )
            !input.outputSelected || !input.folderSelected -> setupRequired(input)
            input.transcriptionState == TranscriptionObservationState.UNKNOWN -> StatusProgressBlockState(
                title = "Checking transcription status",
                progressVisible = true,
                progressIndeterminate = true,
            )
            input.transcriptionState == TranscriptionObservationState.FINISHED -> completedTranscription(input)
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
        val minutes = totalSeconds / 60
        val seconds = (totalSeconds % 60).toString().padStart(2, '0')
        return "$minutes:$seconds"
    }
}
