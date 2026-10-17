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
    val ownership: AudioStorageOwnership,
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
            durationUs = durationUs,
            ownership = ownership,
        )
}

class SqlDelightAudioCatalogRepository(
    private val driver: SqlDriver,
) : AudioCatalogQueuePort, AudioCatalogReconciliationPort, TranscriptPublicationStore {
    private val queries = VoiceInboxDatabase(driver).audioFilesQueries
    private val publicationQueries = VoiceInboxDatabase(driver).publicationAttemptsQueries

    fun close() {
        driver.close()
    }

    override fun findPublication(attemptKey: String): TranscriptPublicationAttempt? =
        publicationQueries.selectByKey(
            attempt_key = attemptKey,
            mapper = ::toPublicationAttempt,
        ).executeAsOneOrNull()

    override fun ensurePublication(
        attemptKey: String,
        audioFileId: Long,
        sourceFingerprint: AudioFileFingerprint,
        outputDocumentId: String,
    ): TranscriptPublicationAttempt {
        publicationQueries.insertAttempt(
            attempt_key = attemptKey,
            audio_file_id = audioFileId,
            source_size_bytes = sourceFingerprint.sizeBytes,
            source_modified_millis = sourceFingerprint.modifiedMillis,
            output_document_id = outputDocumentId,
            transcript_text = "",
            formatted_entry = "",
            duration_us = null,
            state = TranscriptPublicationState.READY.name,
            last_error = null,
            created_at = 0L,
            updated_at = 0L,
        )
        return requireNotNull(findPublication(attemptKey))
    }

    override fun savePreparedPublication(
        attemptKey: String,
        transcriptText: String,
        formattedEntry: String,
        durationUs: Long?,
    ): TranscriptPublicationAttempt {
        publicationQueries.updatePrepared(
            transcript_text = transcriptText,
            formatted_entry = formattedEntry,
            duration_us = durationUs,
            updated_at = 0L,
            attempt_key = attemptKey,
        )
        return requireNotNull(findPublication(attemptKey))
    }

    override fun markPublicationConfirmed(attemptKey: String) {
        publicationQueries.markConfirmed(updated_at = 0L, attempt_key = attemptKey)
    }

    override fun markPublicationRetryableFailure(attemptKey: String, message: String) {
        publicationQueries.markRetryableFailure(
            last_error = message,
            updated_at = 0L,
            attempt_key = attemptKey,
        )
    }

    override fun markPublicationUnknown(attemptKey: String, message: String) {
        publicationQueries.markUnknown(
            last_error = message,
            updated_at = 0L,
            attempt_key = attemptKey,
        )
    }

    fun hasConfirmedPublication(audioFileId: Long): Boolean =
        publicationQueries.hasConfirmedForAudioFile(audio_file_id = audioFileId).executeAsOne() > 0

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

    fun renameEntry(id: Long, displayName: String): AudioCatalogEntry? {
        val validName = AudioDisplayNameRules.validate(displayName) ?: return null
        queries.updateDisplayName(display_name = validName, id = id)
        return entry(id)
    }

    fun deleteAppOwnedEntry(id: Long): Boolean = queries.transactionWithResult {
        val candidate = queries.selectById(id, mapper = ::toCatalogEntry).executeAsOneOrNull()
            ?: return@transactionWithResult false
        if (candidate.ownership != AudioStorageOwnership.APP_OWNED_COPY ||
            candidate.state == AudioFileState.PROCESSING
        ) return@transactionWithResult false
        publicationQueries.deleteByAudioFileId(audio_file_id = id)
        queries.deleteById(id)
        queries.selectById(id, mapper = ::toCatalogEntry).executeAsOneOrNull() == null
    }

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
    ownership: String,
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
    ownership = ownership,
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
    ownership: String,
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
    ownership = AudioStorageOwnership.valueOf(ownership),
)

private fun toPublicationAttempt(
    attempt_key: String,
    audio_file_id: Long,
    source_size_bytes: Long?,
    source_modified_millis: Long?,
    output_document_id: String,
    transcript_text: String,
    formatted_entry: String,
    duration_us: Long?,
    state: String,
    last_error: String?,
    created_at: Long,
    updated_at: Long,
): TranscriptPublicationAttempt = TranscriptPublicationAttempt(
    attemptKey = attempt_key,
    audioFileId = audio_file_id,
    sourceFingerprint = AudioFileFingerprint(
        sizeBytes = source_size_bytes,
        modifiedMillis = source_modified_millis,
    ),
    outputDocumentId = output_document_id,
    transcriptText = transcript_text,
    formattedEntry = formatted_entry,
    durationUs = duration_us,
    state = TranscriptPublicationState.valueOf(state),
    lastError = last_error,
)
