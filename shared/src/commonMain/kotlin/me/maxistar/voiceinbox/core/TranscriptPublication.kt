package me.maxistar.voiceinbox.core

enum class TranscriptPublicationState {
    READY,
    CONFIRMED,
    UNKNOWN,
}

data class TranscriptPublicationAttempt(
    val attemptKey: String,
    val audioFileId: Long,
    val sourceFingerprint: AudioFileFingerprint,
    val outputDocumentId: String,
    val transcriptText: String,
    val formattedEntry: String,
    val durationUs: Long?,
    val state: TranscriptPublicationState,
    val lastError: String?,
)

object TranscriptPublicationKeys {
    fun forEntry(
        audioFileId: Long,
        fingerprint: AudioFileFingerprint,
        outputDocumentId: String,
    ): String = buildString {
        append(audioFileId)
        append('|')
        append(fingerprint.sizeBytes?.toString() ?: "?")
        append('|')
        append(fingerprint.modifiedMillis?.toString() ?: "?")
        append('|')
        append(outputDocumentId.length)
        append(':')
        append(outputDocumentId)
    }
}

interface TranscriptPublicationStore {
    fun findPublication(attemptKey: String): TranscriptPublicationAttempt?

    fun ensurePublication(
        attemptKey: String,
        audioFileId: Long,
        sourceFingerprint: AudioFileFingerprint,
        outputDocumentId: String,
    ): TranscriptPublicationAttempt

    fun savePreparedPublication(
        attemptKey: String,
        transcriptText: String,
        formattedEntry: String,
        durationUs: Long?,
    ): TranscriptPublicationAttempt

    fun markPublicationConfirmed(attemptKey: String)

    fun markPublicationRetryableFailure(attemptKey: String, message: String)

    fun markPublicationUnknown(attemptKey: String, message: String)
}
