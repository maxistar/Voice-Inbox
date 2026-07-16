package me.maxistar.voiceinbox.core

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BatchTranscriptionUseCaseTest {
    @Test
    fun transcribeAllProcessesPendingEntriesAndReportsResult() {
        val catalog = FakeCatalog(
            entry(1, "one.wav", AudioFileState.PENDING),
            entry(2, "two.wav", AudioFileState.PROCESSING),
        )
        val transcriber = FakeTranscriber(
            1L to SingleFileTranscriptionResult(10, 5, "first"),
            2L to SingleFileTranscriptionResult(20, 6, "second"),
        )
        val progress = mutableListOf<BatchTranscriptionProgress>()

        val result = useCase(catalog, transcriber).transcribe(input()) {
            progress += it
        }

        assertEquals(1, catalog.recoveries)
        assertEquals(AudioFileState.PROCESSED, catalog.entry(1).state)
        assertEquals(AudioFileState.PROCESSED, catalog.entry(2).state)
        assertEquals("first", catalog.entry(1).transcriptText)
        assertEquals("second", catalog.entry(2).transcriptText)
        assertEquals(listOf(1L, 2L), transcriber.transcribedIds)
        assertEquals(BatchTranscriptionResult(2, 2, 0, "Completed 2 of 2", 100, false), result)
        assertEquals("Completed 2 of 2", progress.last().phase)
    }

    @Test
    fun retryOneClaimsOnlyTheRequestedFailedEntry() {
        val catalog = FakeCatalog(
            entry(1, "pending.wav", AudioFileState.PENDING),
            entry(2, "failed.wav", AudioFileState.FAILED),
        )
        val transcriber = FakeTranscriber(
            2L to SingleFileTranscriptionResult(10, 5, "fixed"),
        )

        val result = useCase(catalog, transcriber).transcribe(input(retryEntryId = 2)) {}

        assertEquals(listOf(2L), transcriber.transcribedIds)
        assertEquals(AudioFileState.PENDING, catalog.entry(1).state)
        assertEquals(AudioFileState.PROCESSED, catalog.entry(2).state)
        assertEquals(BatchTranscriptionResult(1, 1, 0, "Completed 1 of 1", 100, false), result)
    }

    @Test
    fun failedFileIsMarkedAndTranscribeAllContinues() {
        val catalog = FakeCatalog(
            entry(1, "bad.wav", AudioFileState.PENDING),
            entry(2, "good.wav", AudioFileState.PENDING),
        )
        val transcriber = FakeTranscriber(
            1L to IllegalStateException("decode failed"),
            2L to SingleFileTranscriptionResult(20, 4, "good"),
        )
        val progress = mutableListOf<BatchTranscriptionProgress>()

        val result = useCase(catalog, transcriber).transcribe(input()) {
            progress += it
        }

        assertEquals(AudioFileState.FAILED, catalog.entry(1).state)
        assertEquals("decode failed", catalog.entry(1).lastError)
        assertEquals(1234, catalog.entry(1).processedAtMillis)
        assertEquals(AudioFileState.PROCESSED, catalog.entry(2).state)
        assertEquals(BatchTranscriptionResult(2, 2, 1, "Completed 2 of 2, 1 failed", 100, false), result)
        assertEquals("Completed 1 of 2, 1 failed", progress.first { it.filename == null }.phase)
    }

    @Test
    fun cancellationReturnsInFlightEntryToPendingAndRethrows() {
        val catalog = FakeCatalog(entry(1, "one.wav", AudioFileState.PENDING))
        val transcriber = FakeTranscriber(1L to CancellationException("stopped"))

        assertFailsWith<CancellationException> {
            useCase(catalog, transcriber).transcribe(input()) {}
        }

        assertEquals(AudioFileState.PENDING, catalog.entry(1).state)
        assertNull(catalog.entry(1).lastError)
    }

    @Test
    fun fileProgressIsMappedToBatchProgress() {
        val catalog = FakeCatalog(entry(1, "one.wav", AudioFileState.PENDING))
        val transcriber = FakeTranscriber(
            1L to SingleFileTranscriptionResult(10, 5, "first"),
            progress = listOf(SingleFileTranscriptionProgress("Transcribing", 5, 10)),
        )
        val progress = mutableListOf<BatchTranscriptionProgress>()

        useCase(catalog, transcriber).transcribe(input()) {
            progress += it
        }

        assertEquals(
            BatchTranscriptionProgress(
                phase = "Transcribing",
                activeEntryId = 1,
                filename = "one.wav",
                completed = 0,
                total = 1,
                failed = 0,
                processedUs = 5,
                durationUs = 10,
                progress = 50,
            ),
            progress.first(),
        )
        assertEquals(1L, progress.first().activeEntryId)
        assertNull(progress.last().activeEntryId)
    }

    @Test
    fun transcribeAllProcessesPendingEntriesAcrossActiveSources() {
        val catalog = FakeCatalog(
            entry(1, "folder.wav", AudioFileState.PENDING, sourceId = FOLDER),
            entry(2, "imported.ogg", AudioFileState.PENDING, sourceId = IMPORTS),
            entry(3, "inactive.wav", AudioFileState.PENDING, sourceId = OTHER_FOLDER),
        )
        val transcriber = FakeTranscriber(
            1L to SingleFileTranscriptionResult(10, 5, "folder"),
            2L to SingleFileTranscriptionResult(20, 6, "import"),
        )

        val result = useCase(catalog, transcriber).transcribe(
            input().copy(sourceScope = AudioCatalogSourceScope.of(listOf(FOLDER, IMPORTS))),
        ) {}

        assertEquals(listOf(1L, 2L), transcriber.transcribedIds)
        assertEquals(AudioFileState.PROCESSED, catalog.entry(1).state)
        assertEquals(AudioFileState.PROCESSED, catalog.entry(2).state)
        assertEquals(AudioFileState.PENDING, catalog.entry(3).state)
        assertEquals(2, result.total)
    }

    private fun useCase(
        catalog: FakeCatalog,
        transcriber: FakeTranscriber,
    ) = BatchTranscriptionUseCase(catalog, transcriber, FakeClock)

    private fun input(retryEntryId: Long? = null) = BatchTranscriptionInput(
        sourceScope = AudioCatalogSourceScope.single(FOLDER),
        outputId = "content://output",
        runId = "run-1",
        retryEntryId = retryEntryId,
    )

    private class FakeCatalog(
        vararg entries: AudioCatalogEntry,
    ) : AudioCatalogQueuePort {
        private val entries = entries.associateBy(AudioCatalogEntry::id).toMutableMap()
        var recoveries = 0

        fun entry(id: Long): AudioCatalogEntry = entries.getValue(id)

        override fun pendingCount(scope: AudioCatalogSourceScope): Int =
            entries.values.count {
                it.folderUri in scope.sourceIds && it.state == AudioFileState.PENDING
            }

        override fun recoverInterrupted() {
            recoveries += 1
            entries.keys.toList().forEach { id ->
                val entry = entries.getValue(id)
                if (entry.state == AudioFileState.PROCESSING) {
                    entries[id] = entry.copy(state = AudioFileState.PENDING)
                }
            }
        }

        override fun claimPending(
            scope: AudioCatalogSourceScope,
            specificId: Long?,
        ): AudioCatalogEntry? = claim(scope, AudioFileState.PENDING, specificId)

        override fun claimFailed(scope: AudioCatalogSourceScope, id: Long): AudioCatalogEntry? =
            claim(scope, AudioFileState.FAILED, id)

        override fun markProcessed(
            id: Long,
            processedAtMillis: Long,
            transcriptText: String,
        ) {
            update(id) {
                it.copy(
                    state = AudioFileState.PROCESSED,
                    processedAtMillis = processedAtMillis,
                    transcriptText = transcriptText,
                    lastError = null,
                )
            }
        }

        override fun markFailed(id: Long, message: String) {
            update(id) {
                it.copy(
                    state = AudioFileState.FAILED,
                    lastError = message,
                    processedAtMillis = null,
                    transcriptText = null,
                )
            }
        }

        override fun markFailedAt(id: Long, message: String, processedAtMillis: Long) {
            markFailed(id, message)
            update(id) { it.copy(processedAtMillis = processedAtMillis) }
        }

        override fun markPending(id: Long) {
            update(id) {
                if (it.state == AudioFileState.PROCESSING) {
                    it.copy(
                        state = AudioFileState.PENDING,
                        lastError = null,
                        processedAtMillis = null,
                        transcriptText = null,
                    )
                } else {
                    it
                }
            }
        }

        private fun claim(
            scope: AudioCatalogSourceScope,
            state: AudioFileState,
            specificId: Long?,
        ): AudioCatalogEntry? {
            val entry = entries.values
                .filter { it.folderUri in scope.sourceIds && it.state == state }
                .filter { specificId == null || it.id == specificId }
                .sortedWith(AudioCatalogRules.processingOrder)
                .firstOrNull()
                ?: return null
            val claimed = entry.copy(state = AudioFileState.PROCESSING, lastError = null)
            entries[entry.id] = claimed
            return claimed
        }

        private fun update(id: Long, transform: (AudioCatalogEntry) -> AudioCatalogEntry) {
            entries[id] = transform(entries.getValue(id))
        }
    }

    private class FakeTranscriber(
        vararg outcomes: Pair<Long, Any>,
        private val progress: List<SingleFileTranscriptionProgress> = emptyList(),
    ) : BatchEntryTranscriber {
        private val outcomes = outcomes.toMap()
        val transcribedIds = mutableListOf<Long>()

        override fun transcribe(
            entry: AudioCatalogEntry,
            outputId: String,
            runId: String,
            onProgress: (SingleFileTranscriptionProgress) -> Unit,
        ): SingleFileTranscriptionResult {
            transcribedIds += entry.id
            progress.forEach(onProgress)
            return when (val outcome = outcomes.getValue(entry.id)) {
                is SingleFileTranscriptionResult -> outcome
                is Throwable -> throw outcome
                else -> error("Unsupported outcome $outcome")
            }
        }
    }

    private object FakeClock : BatchClock {
        override fun currentTimeMillis(): Long = 1234
    }

    private fun entry(
        id: Long,
        name: String,
        state: AudioFileState,
        sourceId: String = FOLDER,
    ) = AudioCatalogEntry(
        id = id,
        folderUri = sourceId,
        documentUri = "content://audio/$id",
        displayName = name,
        mimeType = "audio/wav",
        fingerprint = AudioFileFingerprint(sizeBytes = 50, modifiedMillis = id),
        state = state,
        stateBeforeMissing = null,
        lastError = if (state == AudioFileState.FAILED) "failed" else null,
        processedAtMillis = null,
        transcriptText = null,
    )

    private companion object {
        const val FOLDER = "content://folder"
        const val IMPORTS = "android-imported-audio"
        const val OTHER_FOLDER = "content://other-folder"
    }
}
