package me.maxistar.voiceinbox.core

import kotlin.coroutines.cancellation.CancellationException

data class BatchTranscriptionInput(
    val sourceScope: AudioCatalogSourceScope,
    val outputId: String,
    val runId: String,
    val retryEntryId: Long? = null,
)

data class BatchTranscriptionProgress(
    val phase: String,
    val activeEntryId: Long? = null,
    val filename: String? = null,
    val completed: Int,
    val total: Int,
    val failed: Int,
    val processedUs: Long? = null,
    val durationUs: Long? = null,
    val progress: Int? = null,
)

data class BatchTranscriptionResult(
    val completed: Int,
    val total: Int,
    val failed: Int,
    val summary: String,
    val progress: Int,
    val indeterminate: Boolean,
)

interface BatchEntryTranscriber {
    fun transcribe(
        entry: AudioCatalogEntry,
        outputId: String,
        runId: String,
        onProgress: (SingleFileTranscriptionProgress) -> Unit,
    ): SingleFileTranscriptionResult
}

interface OutcomeBatchEntryTranscriber {
    fun transcribe(
        entry: AudioCatalogEntry,
        outputId: String,
        runId: String,
        onProgress: (SingleFileTranscriptionProgress) -> Unit,
    ): SingleFileTranscriptionOutcome
}

interface BatchClock {
    fun currentTimeMillis(): Long
}

class BatchTranscriptionUseCase(
    private val catalog: AudioCatalogQueuePort,
    private val transcriber: BatchEntryTranscriber,
    private val clock: BatchClock,
) {
    fun transcribe(
        input: BatchTranscriptionInput,
        onProgress: (BatchTranscriptionProgress) -> Unit,
    ): BatchTranscriptionResult {
        catalog.recoverInterrupted()
        val total = if (input.retryEntryId == null) {
            catalog.pendingCount(input.sourceScope)
        } else {
            1
        }
        var completed = 0
        var failed = 0
        var currentEntry: AudioCatalogEntry? = null

        try {
            while (true) {
                currentEntry = nextEntry(input, completed) ?: break
                val entry = currentEntry
                try {
                    val result = transcriber.transcribe(
                        entry = entry,
                        outputId = input.outputId,
                        runId = input.runId,
                    ) { progress ->
                        onProgress(
                            BatchTranscriptionProgress(
                                phase = progress.phase,
                                activeEntryId = entry.id,
                                filename = entry.displayName,
                                completed = completed,
                                total = total,
                                failed = failed,
                                processedUs = progress.processedUs,
                                durationUs = progress.durationUs,
                                progress = BatchTranscriptionRules.percent(
                                    progress.processedUs,
                                    progress.durationUs,
                                ),
                            ),
                        )
                    }
                    catalog.markProcessed(
                        id = entry.id,
                        processedAtMillis = clock.currentTimeMillis(),
                        transcriptText = result.transcriptText,
                    )
                } catch (cancelled: CancellationException) {
                    catalog.markPending(entry.id)
                    currentEntry = null
                    throw cancelled
                } catch (error: Throwable) {
                    catalog.markFailedAt(
                        entry.id,
                        error.message ?: ERROR_TRANSCRIPTION_FAILED,
                        clock.currentTimeMillis(),
                    )
                    failed += 1
                }
                completed += 1
                currentEntry = null
                onProgress(summaryProgress(completed, total, failed))
                if (input.retryEntryId != null) break
            }
        } catch (cancelled: CancellationException) {
            currentEntry?.let { catalog.markPending(it.id) }
            throw cancelled
        } catch (error: Throwable) {
            currentEntry?.let { catalog.markPending(it.id) }
            throw error
        }

        return BatchTranscriptionResult(
            completed = completed,
            total = total,
            failed = failed,
            summary = BatchTranscriptionRules.summary(completed, total, failed),
            progress = FINAL_PROGRESS,
            indeterminate = false,
        )
    }

    private fun nextEntry(
        input: BatchTranscriptionInput,
        completed: Int,
    ): AudioCatalogEntry? =
        if (input.retryEntryId == null) {
            catalog.claimPending(input.sourceScope)
        } else if (completed == 0) {
            catalog.claimFailed(input.sourceScope, input.retryEntryId)
        } else {
            null
        }

    private fun summaryProgress(
        completed: Int,
        total: Int,
        failed: Int,
    ): BatchTranscriptionProgress = BatchTranscriptionProgress(
        phase = BatchTranscriptionRules.summary(completed, total, failed),
        activeEntryId = null,
        filename = null,
        completed = completed,
        total = total,
        failed = failed,
        processedUs = null,
        durationUs = null,
        progress = null,
    )

    companion object {
        const val FINAL_PROGRESS = 100
        const val ERROR_TRANSCRIPTION_FAILED = "Transcription failed"
    }
}

class OutcomeBatchTranscriptionUseCase(
    catalog: AudioCatalogQueuePort,
    transcriber: OutcomeBatchEntryTranscriber,
    clock: BatchClock,
) {
    private val delegate = BatchTranscriptionUseCase(
        catalog = catalog,
        transcriber = object : BatchEntryTranscriber {
            override fun transcribe(
                entry: AudioCatalogEntry,
                outputId: String,
                runId: String,
                onProgress: (SingleFileTranscriptionProgress) -> Unit,
            ): SingleFileTranscriptionResult {
                val outcome = transcriber.transcribe(entry, outputId, runId, onProgress)
                if (outcome.success) {
                    return requireNotNull(outcome.result) {
                        "Successful transcription outcome did not include a result"
                    }
                }
                throw IllegalStateException(
                    outcome.errorMessage ?: BatchTranscriptionUseCase.ERROR_TRANSCRIPTION_FAILED,
                )
            }
        },
        clock = clock,
    )

    fun transcribe(
        input: BatchTranscriptionInput,
        onProgress: (BatchTranscriptionProgress) -> Unit,
    ): BatchTranscriptionResult =
        delegate.transcribe(input, onProgress)
}
