package me.maxistar.voiceinbox.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TranscriptPublicationCoordinatorTest {
    @Test
    fun detectsCompleteEntryWithoutAppendingAgain() {
        val entry = "voice.wav\n- recorded: unknown\nhello"
        val store = FakeStore(attempt(entry))
        val output = FakeOutput(
            tails = ArrayDeque(listOf("older\r\n\r\n$entry\r\n")),
        )

        val result = TranscriptPublicationCoordinator(output, store).publish(store.attempt)

        assertEquals(TranscriptPublicationResult.ALREADY_PUBLISHED, result)
        assertEquals(0, output.appended.size)
        assertEquals(TranscriptPublicationState.CONFIRMED, store.attempt.state)
    }

    @Test
    fun appendsWhenCompleteEntryIsAbsent() {
        val entry = "voice.wav\n- recorded: unknown\nhello"
        val store = FakeStore(attempt(entry))
        val output = FakeOutput(tails = ArrayDeque(listOf("older")))

        val result = TranscriptPublicationCoordinator(output, store).publish(store.attempt)

        assertEquals(TranscriptPublicationResult.APPENDED, result)
        assertEquals("\n\n$entry", output.appended.single())
        assertEquals(TranscriptPublicationState.CONFIRMED, store.attempt.state)
    }

    @Test
    fun noWriteFailureLeavesPreparedAttemptRetryable() {
        val entry = "voice.wav\n- recorded: unknown\nhello"
        val store = FakeStore(attempt(entry))
        val output = FakeOutput(
            tails = ArrayDeque(listOf("older", "older")),
            appendOutcome = TranscriptAppendOutcome.NOT_WRITTEN,
        )

        assertFailsWith<TranscriptPublicationException> {
            TranscriptPublicationCoordinator(output, store).publish(store.attempt)
        }

        assertEquals(TranscriptPublicationState.READY, store.attempt.state)
        assertEquals(TranscriptAppendOutcome.NOT_WRITTEN, assertFailsWith<TranscriptPublicationException> {
            TranscriptPublicationCoordinator(output, store).publish(store.attempt)
        }.outcome)
        assertEquals(2, output.appended.size)
    }

    @Test
    fun unknownAppendReadsBackAndDoesNotBlindlyRetry() {
        val entry = "voice.wav\n- recorded: unknown\nhello"
        val store = FakeStore(attempt(entry))
        val output = FakeOutput(
            tails = ArrayDeque(listOf("older", "older\npartial")),
            appendOutcome = TranscriptAppendOutcome.UNKNOWN,
        )

        assertFailsWith<TranscriptPublicationException> {
            TranscriptPublicationCoordinator(output, store).publish(store.attempt)
        }

        assertEquals(1, output.appended.size)
        assertEquals(TranscriptPublicationState.UNKNOWN, store.attempt.state)
    }

    @Test
    fun unknownAppendBecomesConfirmedWhenReadBackFindsEntry() {
        val entry = "voice.wav\n- recorded: unknown\nhello"
        val store = FakeStore(attempt(entry))
        val output = FakeOutput(
            tails = ArrayDeque(listOf("older", "older\n\n$entry")),
            appendOutcome = TranscriptAppendOutcome.UNKNOWN,
        )

        val result = TranscriptPublicationCoordinator(output, store).publish(store.attempt)

        assertEquals(TranscriptPublicationResult.ALREADY_PUBLISHED, result)
        assertEquals(TranscriptPublicationState.CONFIRMED, store.attempt.state)
    }

    @Test
    fun completeEntryMatcherRejectsSubstringInsidePreviousEntry() {
        val entry = "voice.wav\n- recorded: unknown\nhello"

        assertEquals(
            false,
            AppendPublication.containsCompleteEntry(
                "older voice.wav\n- recorded: unknown\nhello suffix",
                entry,
            ),
        )
        assertEquals(
            true,
            AppendPublication.containsCompleteEntry("prefix\n\n$entry\n", entry),
        )
    }

    private fun attempt(entry: String) = TranscriptPublicationAttempt(
        attemptKey = "attempt-1",
        audioFileId = 1,
        sourceFingerprint = AudioFileFingerprint(10, 20),
        outputDocumentId = "output.md",
        transcriptText = "hello",
        formattedEntry = entry,
        durationUs = 100,
        state = TranscriptPublicationState.READY,
        lastError = null,
    )

    private class FakeOutput(
        private val tails: ArrayDeque<String>,
        private val appendOutcome: TranscriptAppendOutcome = TranscriptAppendOutcome.SUCCEEDED,
    ) : PlatformTranscriptOutput {
        val appended = mutableListOf<String>()

        override fun readTail(outputId: String, maxBytes: Int): String = tails.removeFirst()

        override fun append(outputId: String, text: String): TranscriptAppendOutcome {
            appended += text
            return appendOutcome
        }
    }

    private class FakeStore(
        var attempt: TranscriptPublicationAttempt,
    ) : TranscriptPublicationStore {
        override fun findPublication(attemptKey: String): TranscriptPublicationAttempt? =
            attempt.takeIf { it.attemptKey == attemptKey }

        override fun ensurePublication(
            attemptKey: String,
            audioFileId: Long,
            sourceFingerprint: AudioFileFingerprint,
            outputDocumentId: String,
        ): TranscriptPublicationAttempt = attempt

        override fun savePreparedPublication(
            attemptKey: String,
            transcriptText: String,
            formattedEntry: String,
            durationUs: Long?,
        ): TranscriptPublicationAttempt {
            attempt = attempt.copy(
                transcriptText = transcriptText,
                formattedEntry = formattedEntry,
                durationUs = durationUs,
                state = TranscriptPublicationState.READY,
            )
            return attempt
        }

        override fun markPublicationConfirmed(attemptKey: String) {
            attempt = attempt.copy(state = TranscriptPublicationState.CONFIRMED, lastError = null)
        }

        override fun markPublicationRetryableFailure(attemptKey: String, message: String) {
            attempt = attempt.copy(state = TranscriptPublicationState.READY, lastError = message)
        }

        override fun markPublicationUnknown(attemptKey: String, message: String) {
            attempt = attempt.copy(state = TranscriptPublicationState.UNKNOWN, lastError = message)
        }
    }
}
