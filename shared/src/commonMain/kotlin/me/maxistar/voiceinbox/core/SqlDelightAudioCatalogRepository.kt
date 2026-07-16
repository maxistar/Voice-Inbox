package me.maxistar.voiceinbox.core

import app.cash.sqldelight.db.SqlDriver
import me.maxistar.voiceinbox.db.AudioFilesQueries
import me.maxistar.voiceinbox.db.VoiceInboxDatabase

data class SqlDelightAudioCatalogFile(
    val id: Long,
    val folderUri: String,
    val documentUri: String,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val modifiedMillis: Long?,
    val state: AudioFileState,
    val stateBeforeMissing: AudioFileState?,
    val lastError: String?,
    val processedAtMillis: Long?,
    val transcriptText: String?,
    val durationUs: Long?,
) {
    val entry: AudioCatalogEntry
        get() = AudioCatalogEntry(
            id = id,
            folderUri = folderUri,
            documentUri = documentUri,
            displayName = displayName,
            mimeType = mimeType,
            fingerprint = AudioFileFingerprint(
                sizeBytes = sizeBytes,
                modifiedMillis = modifiedMillis,
            ),
            state = state,
            stateBeforeMissing = stateBeforeMissing,
            lastError = lastError,
            processedAtMillis = processedAtMillis,
            transcriptText = transcriptText,
        )
}

class SqlDelightAudioCatalogRepository(
    private val driver: SqlDriver,
) : AudioCatalogQueuePort, AudioCatalogReconciliationPort {
    private val queries = VoiceInboxDatabase(driver).audioFilesQueries

    fun close() {
        driver.close()
    }

    fun importedFiles(folderUri: String): List<SqlDelightAudioCatalogFile> =
        queries.selectDisplayRows(folderUri, mapper = ::toCatalogFile).executeAsList()

    fun newEntries(folderUri: String): List<AudioCatalogEntry> =
        newEntries(AudioCatalogSourceScope.single(folderUri))

    fun newEntries(scope: AudioCatalogSourceScope): List<AudioCatalogEntry> =
        queries.selectNewDisplayRowsForSources(scope.sourceIds, mapper = ::toCatalogEntry)
            .executeAsList()

    fun processedEntries(folderUri: String): List<AudioCatalogEntry> =
        processedEntries(AudioCatalogSourceScope.single(folderUri))

    fun processedEntries(scope: AudioCatalogSourceScope): List<AudioCatalogEntry> =
        queries.selectProcessedDisplayRowsForSources(scope.sourceIds, mapper = ::toCatalogEntry)
            .executeAsList()

    fun missingEntries(folderUri: String): List<AudioCatalogEntry> =
        queries.selectMissingDisplayRows(folderUri, mapper = ::toCatalogEntry).executeAsList()

    fun entry(id: Long): AudioCatalogEntry? =
        queries.selectById(id, mapper = ::toCatalogEntry).executeAsOneOrNull()

    fun catalogFile(id: Long): SqlDelightAudioCatalogFile? =
        queries.selectById(id, mapper = ::toCatalogFile).executeAsOneOrNull()

    fun catalogFile(folderUri: String, documentUri: String): SqlDelightAudioCatalogFile? =
        queries.selectByFolderDocument(folderUri, documentUri, mapper = ::toCatalogFile)
            .executeAsOneOrNull()

    fun upsertImportedFile(
        folderUri: String,
        documentUri: String,
        displayName: String,
        mimeType: String?,
        sizeBytes: Long?,
        importedAtMillis: Long?,
        state: AudioFileState,
        lastError: String?,
        processedAtMillis: Long?,
        transcriptText: String?,
        durationUs: Long?,
    ): SqlDelightAudioCatalogFile {
        queries.transaction {
            queries.insertImportedFile(
                folder_uri = folderUri,
                document_uri = documentUri,
                display_name = displayName,
                mime_type = mimeType,
                size_bytes = sizeBytes,
                modified_millis = importedAtMillis,
                state = state.name,
                last_error = lastError,
                processed_at = processedAtMillis,
                transcript_text = transcriptText,
                duration_us = durationUs,
            )
            queries.updateImportedFile(
                display_name = displayName,
                mime_type = mimeType,
                size_bytes = sizeBytes,
                modified_millis = importedAtMillis,
                state = state.name,
                last_error = lastError,
                processed_at = processedAtMillis,
                transcript_text = transcriptText,
                duration_us = durationUs,
                folder_uri = folderUri,
                document_uri = documentUri,
            )
        }
        return requireNotNull(
            queries.selectByFolderDocument(folderUri, documentUri, mapper = ::toCatalogFile)
                .executeAsOneOrNull(),
        )
    }

    fun markProcessing(id: Long): Boolean =
        markClaimed(id = id, requiredState = AudioFileState.PENDING) != null

    fun markProcessedFile(
        id: Long,
        processedAtMillis: Long,
        transcriptText: String,
        durationUs: Long?,
    ) {
        queries.markProcessed(
            processed_at = processedAtMillis,
            transcript_text = transcriptText,
            duration_us = durationUs,
            id = id,
        )
    }

    fun resetForRetry(id: Long): Boolean {
        queries.resetFailedForRetry(id)
        return entry(id)?.state == AudioFileState.PENDING
    }

    fun reconcile(folderUri: String, scannedFiles: List<ScannedAudioFile>) {
        queries.transaction {
            CatalogReconciliationUseCase(this@SqlDelightAudioCatalogRepository)
                .reconcile(folderUri, scannedFiles)
        }
    }

    override fun pendingCount(scope: AudioCatalogSourceScope): Int =
        queries.pendingCountForSources(scope.sourceIds).executeAsOne().toInt()

    override fun recoverInterrupted() {
        queries.recoverInterrupted()
    }

    override fun claimPending(
        scope: AudioCatalogSourceScope,
        specificId: Long?,
    ): AudioCatalogEntry? =
        queries.transactionWithResult {
            val candidate = if (specificId == null) {
                queries.selectNextPendingForClaimInSources(
                    scope.sourceIds,
                    mapper = ::toCatalogEntry,
                )
                    .executeAsOneOrNull()
            } else {
                queries.selectSpecificPendingForClaimInSources(
                    folder_uri = scope.sourceIds,
                    id = specificId,
                    mapper = ::toCatalogEntry,
                ).executeAsOneOrNull()
            } ?: return@transactionWithResult null
            markClaimed(candidate.id, AudioFileState.PENDING)
        }

    override fun claimFailed(scope: AudioCatalogSourceScope, id: Long): AudioCatalogEntry? =
        queries.transactionWithResult {
            queries.selectSpecificFailedForClaimInSources(
                folder_uri = scope.sourceIds,
                id = id,
                mapper = ::toCatalogEntry,
            ).executeAsOneOrNull() ?: return@transactionWithResult null
            markClaimed(id, AudioFileState.FAILED)
        }

    override fun markProcessed(id: Long, processedAtMillis: Long, transcriptText: String) {
        markProcessedFile(
            id = id,
            processedAtMillis = processedAtMillis,
            transcriptText = transcriptText,
            durationUs = null,
        )
    }

    override fun markFailed(id: Long, message: String) {
        queries.markFailed(last_error = message, processed_at = null, id = id)
    }

    override fun markFailedAt(id: Long, message: String, processedAtMillis: Long) {
        queries.markFailed(last_error = message, processed_at = processedAtMillis, id = id)
    }

    override fun markPending(id: Long) {
        queries.markPendingFromProcessing(id)
    }

    override fun existingEntriesForReconciliation(folderUri: String): List<AudioCatalogEntry> =
        queries.selectExistingForReconciliation(folderUri, mapper = ::toCatalogEntry)
            .executeAsList()

    override fun applyReconciliationOperations(
        operations: List<AudioCatalogReconciliationOperation>,
    ) {
        operations.forEach { operation ->
            when (operation) {
                is AudioCatalogReconciliationOperation.InsertScannedFile ->
                    queries.insertScannedFile(operation.scannedFile)

                is AudioCatalogReconciliationOperation.UpdateScannedFile ->
                    queries.updateScannedFile(
                        folder_uri = operation.scannedFile.folderUri,
                        document_uri = operation.scannedFile.documentUri,
                        display_name = operation.scannedFile.displayName,
                        mime_type = operation.scannedFile.mimeType,
                        size_bytes = operation.scannedFile.fingerprint.sizeBytes,
                        modified_millis = operation.scannedFile.fingerprint.modifiedMillis,
                        state = operation.state.name,
                        state_before_missing = operation.stateBeforeMissing?.name,
                        value = operation.clearOutcome,
                        value_ = operation.clearOutcome,
                        value__ = operation.clearOutcome,
                        value___ = operation.clearOutcome,
                        id = operation.entryId,
                    )

                is AudioCatalogReconciliationOperation.MarkMissing ->
                    queries.markMissing(
                        state_before_missing = operation.stateBeforeMissing.name,
                        id = operation.entryId,
                    )
            }
        }
    }

    private fun markClaimed(
        id: Long,
        requiredState: AudioFileState,
    ): AudioCatalogEntry? {
        queries.markClaimed(id = id, state = requiredState.name)
        return entry(id)
            ?.takeIf { it.state == AudioFileState.PROCESSING }
            ?.copy(lastError = null, processedAtMillis = null, transcriptText = null)
    }

    private fun AudioFilesQueries.insertScannedFile(scannedFile: ScannedAudioFile) {
        insertScannedFile(
            folder_uri = scannedFile.folderUri,
            document_uri = scannedFile.documentUri,
            display_name = scannedFile.displayName,
            mime_type = scannedFile.mimeType,
            size_bytes = scannedFile.fingerprint.sizeBytes,
            modified_millis = scannedFile.fingerprint.modifiedMillis,
        )
    }
}

private fun toCatalogEntry(
    id: Long,
    folder_uri: String,
    document_uri: String,
    display_name: String,
    mime_type: String?,
    size_bytes: Long?,
    modified_millis: Long?,
    state: String,
    state_before_missing: String?,
    last_error: String?,
    processed_at: Long?,
    transcript_text: String?,
    duration_us: Long?,
): AudioCatalogEntry = toCatalogFile(
    id = id,
    folder_uri = folder_uri,
    document_uri = document_uri,
    display_name = display_name,
    mime_type = mime_type,
    size_bytes = size_bytes,
    modified_millis = modified_millis,
    state = state,
    state_before_missing = state_before_missing,
    last_error = last_error,
    processed_at = processed_at,
    transcript_text = transcript_text,
    duration_us = duration_us,
).entry

private fun toCatalogFile(
    id: Long,
    folder_uri: String,
    document_uri: String,
    display_name: String,
    mime_type: String?,
    size_bytes: Long?,
    modified_millis: Long?,
    state: String,
    state_before_missing: String?,
    last_error: String?,
    processed_at: Long?,
    transcript_text: String?,
    duration_us: Long?,
): SqlDelightAudioCatalogFile = SqlDelightAudioCatalogFile(
    id = id,
    folderUri = folder_uri,
    documentUri = document_uri,
    displayName = display_name,
    mimeType = mime_type,
    sizeBytes = size_bytes,
    modifiedMillis = modified_millis,
    state = AudioFileState.valueOf(state),
    stateBeforeMissing = state_before_missing?.let(AudioFileState::valueOf),
    lastError = last_error,
    processedAtMillis = processed_at,
    transcriptText = transcript_text,
    durationUs = duration_us,
)
