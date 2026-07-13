package me.maxistar.voiceinbox.core

data class SingleFileTranscriptionInput(
    val audioId: String,
    val audioName: String,
    val outputId: String,
    val fallbackRecordingTimeMillis: Long?,
)

data class SingleFileTranscriptionProgress(
    val phase: String,
    val processedUs: Long? = null,
    val durationUs: Long? = null,
)

data class SingleFileTranscriptionResult(
    val durationUs: Long?,
    val transcriptLength: Int,
    val transcriptText: String,
)

data class SingleFileTranscriptionOutcome(
    val success: Boolean,
    val result: SingleFileTranscriptionResult?,
    val errorMessage: String?,
)

interface PlatformTranscriptStaging {
    fun write(transcript: String)
    fun cleanup()
}

interface PlatformTimestampLabelFormatter {
    fun formatRecordingTime(recordingTimeMillis: Long?): String?
}

interface PlatformTranscriptOutput {
    fun readTail(outputId: String): String
    fun append(outputId: String, text: String)
}

class SingleFileTranscriptionUseCase(
    private val audioDecoder: PlatformAudioDecoder,
    private val nativeTranscriber: PlatformNativeTranscriber,
    private val staging: PlatformTranscriptStaging,
    private val timestampLabelFormatter: PlatformTimestampLabelFormatter,
    private val transcriptOutput: PlatformTranscriptOutput,
) {
    fun transcribe(
        input: SingleFileTranscriptionInput,
        onProgress: (SingleFileTranscriptionProgress) -> Unit,
    ): SingleFileTranscriptionResult {
        try {
            onProgress(SingleFileTranscriptionProgress(PHASE_DECODING_AUDIO))
            var transcript = ""
            var durationUs: Long? = null
            var transcriptionError: String? = null
            val info = audioDecoder.decode(
                audioId = input.audioId,
                onProgress = { processed, total ->
                    durationUs = total
                    onProgress(
                        SingleFileTranscriptionProgress(
                            phase = PHASE_TRANSCRIBING,
                            processedUs = processed,
                            durationUs = total,
                        ),
                    )
                },
                onChunk = onChunk@ { samples ->
                    val chunkText = nativeTranscriber.transcribeChunk(samples)
                    if (chunkText == null) {
                        transcriptionError = ERROR_SPEECH_RECOGNITION_FAILED
                        return@onChunk
                    }
                    transcript = TranscriptMerger.merge(transcript, chunkText)
                    staging.write(transcript)
                },
            )
            durationUs = info.durationUs ?: durationUs
            transcriptionError?.let { throw IllegalStateException(it) }
            if (transcript.isBlank()) throw IllegalStateException(ERROR_NO_TEXT_RECOGNIZED)
            val finalTranscript = transcript.trim()

            onProgress(SingleFileTranscriptionProgress(PHASE_APPENDING_TRANSCRIPT, durationUs, durationUs))
            val formatted = TranscriptOutput.formatEntry(
                audioName = input.audioName,
                recordingTimeLabel = timestampLabelFormatter.formatRecordingTime(
                    info.embeddedRecordingTimeMillis ?: input.fallbackRecordingTimeMillis,
                ),
                transcript = finalTranscript,
            )
            AppendPublication.publish(transcriptOutput.readTail(input.outputId), formatted) { text ->
                transcriptOutput.append(input.outputId, text)
            }
            return SingleFileTranscriptionResult(
                durationUs = durationUs,
                transcriptLength = finalTranscript.length,
                transcriptText = finalTranscript,
            )
        } finally {
            staging.cleanup()
        }
    }

    fun tryTranscribe(
        input: SingleFileTranscriptionInput,
        onProgress: (SingleFileTranscriptionProgress) -> Unit,
    ): SingleFileTranscriptionOutcome =
        try {
            SingleFileTranscriptionOutcome(
                success = true,
                result = transcribe(input, onProgress),
                errorMessage = null,
            )
        } catch (error: Throwable) {
            SingleFileTranscriptionOutcome(
                success = false,
                result = null,
                errorMessage = error.message ?: ERROR_SPEECH_RECOGNITION_FAILED,
            )
        }

    companion object {
        const val PHASE_DECODING_AUDIO = "Decoding audio"
        const val PHASE_TRANSCRIBING = "Transcribing"
        const val PHASE_APPENDING_TRANSCRIPT = "Appending transcript"
        const val ERROR_SPEECH_RECOGNITION_FAILED = "Speech recognition failed"
        const val ERROR_NO_TEXT_RECOGNIZED = "No text was recognized"
    }
}
