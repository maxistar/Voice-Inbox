package me.maxistar.voiceinbox.core

enum class TranscriptAppendOutcome {
    SUCCEEDED,
    NOT_WRITTEN,
    UNKNOWN,
}

enum class TranscriptPublicationResult {
    APPENDED,
    ALREADY_PUBLISHED,
}

class TranscriptPublicationException(
    message: String,
    val outcome: TranscriptAppendOutcome,
) : IllegalStateException(message)

class TranscriptPublicationCoordinator(
    private val output: PlatformTranscriptOutput,
    private val store: TranscriptPublicationStore,
) {
    fun publish(attempt: TranscriptPublicationAttempt): TranscriptPublicationResult {
        require(attempt.formattedEntry.isNotBlank()) { "Prepared publication entry is empty" }

        val tailBytes = AppendPublication.requiredTailBytes(attempt.formattedEntry)
        val existingTail = output.readTail(attempt.outputDocumentId, tailBytes)
        if (AppendPublication.containsCompleteEntry(existingTail, attempt.formattedEntry)) {
            store.markPublicationConfirmed(attempt.attemptKey)
            return TranscriptPublicationResult.ALREADY_PUBLISHED
        }

        if (attempt.state == TranscriptPublicationState.UNKNOWN) {
            throw TranscriptPublicationException(
                message = ERROR_UNKNOWN_PUBLICATION,
                outcome = TranscriptAppendOutcome.UNKNOWN,
            )
        }

        val appendOutcome = output.append(
            attempt.outputDocumentId,
            AppendPublication.appendText(existingTail, attempt.formattedEntry),
        )
        when (appendOutcome) {
            TranscriptAppendOutcome.SUCCEEDED -> {
                store.markPublicationConfirmed(attempt.attemptKey)
                return TranscriptPublicationResult.APPENDED
            }

            TranscriptAppendOutcome.NOT_WRITTEN -> {
                store.markPublicationRetryableFailure(
                    attempt.attemptKey,
                    ERROR_APPEND_NOT_WRITTEN,
                )
                throw TranscriptPublicationException(
                    message = ERROR_APPEND_NOT_WRITTEN,
                    outcome = appendOutcome,
                )
            }

            TranscriptAppendOutcome.UNKNOWN -> {
                val readBack = output.readTail(attempt.outputDocumentId, tailBytes)
                if (AppendPublication.containsCompleteEntry(readBack, attempt.formattedEntry)) {
                    store.markPublicationConfirmed(attempt.attemptKey)
                    return TranscriptPublicationResult.ALREADY_PUBLISHED
                }
                store.markPublicationUnknown(attempt.attemptKey, ERROR_UNKNOWN_PUBLICATION)
                throw TranscriptPublicationException(
                    message = ERROR_UNKNOWN_PUBLICATION,
                    outcome = appendOutcome,
                )
            }
        }
    }

    companion object {
        const val ERROR_APPEND_NOT_WRITTEN = "Transcript append did not write to the output document"
        const val ERROR_UNKNOWN_PUBLICATION = "Transcript publication outcome is unknown; inspect the output document"
    }
}
