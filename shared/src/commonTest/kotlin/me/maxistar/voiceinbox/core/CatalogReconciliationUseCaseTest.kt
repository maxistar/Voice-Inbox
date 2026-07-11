package me.maxistar.voiceinbox.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogReconciliationUseCaseTest {
    private val useCase = CatalogReconciliationUseCase(
        object : AudioCatalogReconciliationPort {
            override fun existingEntriesForReconciliation(
                folderUri: String,
            ): List<AudioCatalogEntry> = emptyList()

            override fun applyReconciliationOperations(
                operations: List<AudioCatalogReconciliationOperation>,
            ) = Unit
        },
    )

    @Test
    fun newScannedFilesBecomePendingInsertOperations() {
        val operations = useCase.planOperations(
            folderUri = FOLDER,
            existingEntries = emptyList(),
            scannedFiles = listOf(scanned("one.wav")),
        )

        assertEquals(
            listOf(AudioCatalogReconciliationOperation.InsertScannedFile(scanned("one.wav"))),
            operations,
        )
    }

    @Test
    fun unchangedTerminalEntriesRemainTerminalAndPreserveOutcomes() {
        val processed = entry(
            id = 7,
            name = "one.wav",
            state = AudioFileState.PROCESSED,
            processedAtMillis = 123,
            transcriptText = "hello",
        )
        val failed = entry(
            id = 8,
            name = "two.wav",
            state = AudioFileState.FAILED,
            lastError = "decode failed",
        )

        val operations = useCase.planOperations(
            folderUri = FOLDER,
            existingEntries = listOf(processed, failed),
            scannedFiles = listOf(scanned("one.wav"), scanned("two.wav")),
        ).filterIsInstance<AudioCatalogReconciliationOperation.UpdateScannedFile>()

        assertEquals(listOf(AudioFileState.PROCESSED, AudioFileState.FAILED), operations.map { it.state })
        assertTrue(operations.all { it.stateBeforeMissing == null })
        assertFalse(operations.any { it.clearOutcome })
    }

    @Test
    fun changedFilesResetToPendingAndClearOutcomeData() {
        val operations = useCase.planOperations(
            folderUri = FOLDER,
            existingEntries = listOf(
                entry(
                    id = 3,
                    name = "one.wav",
                    state = AudioFileState.PROCESSED,
                    size = 100,
                    modified = 10,
                    processedAtMillis = 123,
                    transcriptText = "old text",
                ),
            ),
            scannedFiles = listOf(scanned("one.wav", size = 101, modified = 10)),
        )

        val update = operations.single() as AudioCatalogReconciliationOperation.UpdateScannedFile
        assertEquals(3, update.entryId)
        assertEquals(AudioFileState.PENDING, update.state)
        assertTrue(update.clearOutcome)
    }

    @Test
    fun interruptedProcessingResetsToPendingAndClearsOutcomeData() {
        val operations = useCase.planOperations(
            folderUri = FOLDER,
            existingEntries = listOf(entry(id = 4, name = "one.wav", state = AudioFileState.PROCESSING)),
            scannedFiles = listOf(scanned("one.wav")),
        )

        val update = operations.single() as AudioCatalogReconciliationOperation.UpdateScannedFile
        assertEquals(AudioFileState.PENDING, update.state)
        assertTrue(update.clearOutcome)
    }

    @Test
    fun missingEntriesAreRestoredUsingRememberedState() {
        val operations = useCase.planOperations(
            folderUri = FOLDER,
            existingEntries = listOf(
                entry(
                    id = 5,
                    name = "one.wav",
                    state = AudioFileState.MISSING,
                    stateBeforeMissing = AudioFileState.FAILED,
                    lastError = "decode failed",
                ),
            ),
            scannedFiles = listOf(scanned("one.wav")),
        )

        val update = operations.single() as AudioCatalogReconciliationOperation.UpdateScannedFile
        assertEquals(AudioFileState.FAILED, update.state)
        assertEquals(null, update.stateBeforeMissing)
        assertFalse(update.clearOutcome)
    }

    @Test
    fun disappearedNonMissingEntriesBecomeMissingWithPreviousStateRemembered() {
        val operations = useCase.planOperations(
            folderUri = FOLDER,
            existingEntries = listOf(
                entry(id = 6, name = "one.wav", state = AudioFileState.PROCESSED),
                entry(id = 7, name = "already-missing.wav", state = AudioFileState.MISSING),
            ),
            scannedFiles = emptyList(),
        )

        assertEquals(
            listOf(
                AudioCatalogReconciliationOperation.MarkMissing(
                    entryId = 6,
                    stateBeforeMissing = AudioFileState.PROCESSED,
                ),
            ),
            operations,
        )
    }

    @Test
    fun useCaseLoadsAndAppliesOperationsThroughPort() {
        val catalog = RecordingReconciliationPort(
            existingEntries = listOf(entry(id = 9, name = "old.wav")),
        )
        CatalogReconciliationUseCase(catalog).reconcile(FOLDER, listOf(scanned("new.wav")))

        assertEquals(listOf(FOLDER), catalog.loadedFolders)
        assertEquals(
            listOf(
                AudioCatalogReconciliationOperation.InsertScannedFile(scanned("new.wav")),
                AudioCatalogReconciliationOperation.MarkMissing(
                    entryId = 9,
                    stateBeforeMissing = AudioFileState.PROCESSED,
                ),
            ),
            catalog.appliedOperations,
        )
    }

    @Test
    fun scannedFilesMustBelongToRequestedFolder() {
        assertFailsWith<IllegalArgumentException> {
            useCase.planOperations(
                folderUri = FOLDER,
                existingEntries = emptyList(),
                scannedFiles = listOf(scanned("one.wav", folderUri = "content://other-folder")),
            )
        }
    }

    private class RecordingReconciliationPort(
        private val existingEntries: List<AudioCatalogEntry>,
    ) : AudioCatalogReconciliationPort {
        val loadedFolders = mutableListOf<String>()
        var appliedOperations: List<AudioCatalogReconciliationOperation> = emptyList()

        override fun existingEntriesForReconciliation(folderUri: String): List<AudioCatalogEntry> {
            loadedFolders += folderUri
            return existingEntries
        }

        override fun applyReconciliationOperations(
            operations: List<AudioCatalogReconciliationOperation>,
        ) {
            appliedOperations = operations
        }
    }

    private fun entry(
        id: Long = 1,
        name: String = "recording.wav",
        state: AudioFileState = AudioFileState.PROCESSED,
        stateBeforeMissing: AudioFileState? = null,
        size: Long? = 100,
        modified: Long? = 10,
        lastError: String? = null,
        processedAtMillis: Long? = null,
        transcriptText: String? = null,
    ) = AudioCatalogEntry(
        id = id,
        folderUri = FOLDER,
        documentUri = documentUri(name),
        displayName = name,
        mimeType = "audio/wav",
        fingerprint = AudioFileFingerprint(size, modified),
        state = state,
        stateBeforeMissing = stateBeforeMissing,
        lastError = lastError,
        processedAtMillis = processedAtMillis,
        transcriptText = transcriptText,
    )

    private fun scanned(
        name: String,
        folderUri: String = FOLDER,
        size: Long? = 100,
        modified: Long? = 10,
    ) = ScannedAudioFile(
        folderUri = folderUri,
        documentUri = documentUri(name),
        displayName = name,
        mimeType = "audio/wav",
        fingerprint = AudioFileFingerprint(size, modified),
    )

    private companion object {
        const val FOLDER = "content://folder"

        fun documentUri(name: String): String = "content://audio/$name"
    }
}
