package me.maxistar.voiceinbox.core

sealed interface AudioCatalogReconciliationOperation {
    data class InsertScannedFile(
        val scannedFile: ScannedAudioFile,
    ) : AudioCatalogReconciliationOperation

    data class UpdateScannedFile(
        val entryId: Long,
        val scannedFile: ScannedAudioFile,
        val state: AudioFileState,
        val stateBeforeMissing: AudioFileState?,
        val clearOutcome: Boolean,
    ) : AudioCatalogReconciliationOperation

    data class MarkMissing(
        val entryId: Long,
        val stateBeforeMissing: AudioFileState,
    ) : AudioCatalogReconciliationOperation
}

interface AudioCatalogReconciliationPort {
    fun existingEntriesForReconciliation(folderUri: String): List<AudioCatalogEntry>

    fun applyReconciliationOperations(
        operations: List<AudioCatalogReconciliationOperation>,
    )
}

class CatalogReconciliationUseCase(
    private val catalog: AudioCatalogReconciliationPort,
) {
    fun reconcile(folderUri: String, scannedFiles: List<ScannedAudioFile>) {
        val operations = planOperations(
            folderUri = folderUri,
            existingEntries = catalog.existingEntriesForReconciliation(folderUri),
            scannedFiles = scannedFiles,
        )
        if (operations.isNotEmpty()) {
            catalog.applyReconciliationOperations(operations)
        }
    }

    fun planOperations(
        folderUri: String,
        existingEntries: List<AudioCatalogEntry>,
        scannedFiles: List<ScannedAudioFile>,
    ): List<AudioCatalogReconciliationOperation> {
        val existingByUri = existingEntries.associateBy(AudioCatalogEntry::documentUri)
        val seenUris = HashSet<String>()

        return buildList {
            scannedFiles.forEach { scanned ->
                require(scanned.folderUri == folderUri)
                seenUris += scanned.documentUri
                val current = existingByUri[scanned.documentUri]
                if (current == null) {
                    add(AudioCatalogReconciliationOperation.InsertScannedFile(scanned))
                } else {
                    val nextState = AudioCatalogRules.rediscoveredState(current, scanned)
                    add(
                        AudioCatalogReconciliationOperation.UpdateScannedFile(
                            entryId = current.id,
                            scannedFile = scanned,
                            state = nextState,
                            stateBeforeMissing = null,
                            clearOutcome = nextState == AudioFileState.PENDING,
                        ),
                    )
                }
            }

            existingEntries
                .filter { it.documentUri !in seenUris && it.state != AudioFileState.MISSING }
                .forEach { entry ->
                    add(
                        AudioCatalogReconciliationOperation.MarkMissing(
                            entryId = entry.id,
                            stateBeforeMissing = entry.state,
                        ),
                    )
                }
        }
    }
}
