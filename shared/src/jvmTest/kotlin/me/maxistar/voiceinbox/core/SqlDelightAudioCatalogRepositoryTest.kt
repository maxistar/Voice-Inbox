package me.maxistar.voiceinbox.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import me.maxistar.voiceinbox.db.VoiceInboxDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlDelightAudioCatalogRepositoryTest {
    @Test
    fun importedFilesAreDisplayedNewestFirstAndPersistTranscript() {
        val repository = repository()

        val older = repository.upsertImportedFile(
            folderUri = FOLDER,
            documentUri = "one.wav",
            displayName = "one.wav",
            mimeType = "audio/wav",
            sizeBytes = 100,
            importedAtMillis = 10,
            state = AudioFileState.PENDING,
            lastError = null,
            processedAtMillis = null,
            transcriptText = null,
            durationUs = null,
        )
        val newer = repository.upsertImportedFile(
            folderUri = FOLDER,
            documentUri = "two.wav",
            displayName = "two.wav",
            mimeType = "audio/wav",
            sizeBytes = 200,
            importedAtMillis = 20,
            state = AudioFileState.PENDING,
            lastError = null,
            processedAtMillis = null,
            transcriptText = null,
            durationUs = null,
        )

        assertEquals(listOf(newer.id, older.id), repository.importedFiles(FOLDER).map { it.id })

        repository.markProcessing(newer.id)
        repository.markProcessedFile(
            id = newer.id,
            processedAtMillis = 50,
            transcriptText = "hello from sqlite",
            durationUs = 1_500_000,
        )

        val processed = assertNotNull(repository.catalogFile(newer.id))
        assertEquals(AudioFileState.PROCESSED, processed.state)
        assertEquals("hello from sqlite", processed.transcriptText)
        assertEquals(1_500_000, processed.durationUs)
    }

    @Test
    fun claimRetryAndFailureOperationsFollowQueueContract() {
        val repository = repository()
        val pending = imported(repository, "pending.wav", modified = 10)
        val failed = imported(
            repository = repository,
            name = "failed.wav",
            modified = 20,
            state = AudioFileState.FAILED,
            lastError = "decode failed",
        )

        assertEquals(1, repository.pendingCount(FOLDER))

        val claimed = assertNotNull(repository.claimPending(FOLDER))
        assertEquals(pending.id, claimed.id)
        assertEquals(AudioFileState.PROCESSING, repository.entry(pending.id)?.state)

        repository.markFailed(claimed.id, "no text")
        assertEquals(AudioFileState.FAILED, repository.entry(claimed.id)?.state)
        assertEquals("no text", repository.entry(claimed.id)?.lastError)

        val claimedFailed = assertNotNull(repository.claimFailed(FOLDER, failed.id))
        assertEquals(AudioFileState.PROCESSING, claimedFailed.state)
        repository.markPending(failed.id)
        assertEquals(AudioFileState.PENDING, repository.entry(failed.id)?.state)

        assertEquals(true, repository.resetForRetry(claimed.id))
        assertEquals(AudioFileState.PENDING, repository.entry(claimed.id)?.state)
        assertNull(repository.entry(claimed.id)?.lastError)
    }

    @Test
    fun reconciliationUpdatesChangedFilesAndMarksMissingEntries() {
        val repository = repository()
        val processed = imported(
            repository = repository,
            name = "processed.wav",
            modified = 10,
            state = AudioFileState.PROCESSED,
            processedAtMillis = 100,
            transcriptText = "old text",
        )
        val missing = imported(repository, "missing.wav", modified = 11)

        repository.reconcile(
            FOLDER,
            listOf(
                scanned("processed.wav", modified = 12),
                scanned("new.wav", modified = 13),
            ),
        )

        val changed = assertNotNull(repository.entry(processed.id))
        assertEquals(AudioFileState.PENDING, changed.state)
        assertNull(changed.processedAtMillis)
        assertNull(changed.transcriptText)

        val disappeared = assertNotNull(repository.entry(missing.id))
        assertEquals(AudioFileState.MISSING, disappeared.state)
        assertEquals(AudioFileState.PENDING, disappeared.stateBeforeMissing)

        val rows = repository.importedFiles(FOLDER)
        assertEquals(listOf("new.wav", "processed.wav"), rows.map { it.displayName })
    }

    @Test
    fun activeSourceScopeMergesListsCountsAndClaimsWithoutTouchingOtherSources() {
        val repository = repository()
        val folder = imported(repository, "folder.wav", modified = 10, folderUri = FOLDER)
        val imported = imported(repository, "voice.ogg", modified = 20, folderUri = IMPORTS)
        imported(repository, "inactive.wav", modified = 30, folderUri = OTHER_FOLDER)
        val failed = imported(
            repository,
            "failed.opus",
            modified = 40,
            folderUri = IMPORTS,
            state = AudioFileState.FAILED,
            lastError = "decode failed",
        )
        val scope = AudioCatalogSourceScope.of(listOf(FOLDER, IMPORTS))

        assertEquals(listOf(imported.id, folder.id), repository.newEntries(scope).map { it.id })
        assertEquals(listOf(failed.id), repository.processedEntries(scope).map { it.id })
        assertEquals(2, repository.pendingCount(scope))
        assertEquals(folder.id, repository.claimPending(scope)?.id)
        assertEquals(imported.id, repository.claimPending(scope)?.id)
        assertNull(repository.claimPending(scope))
        assertEquals(failed.id, repository.claimFailed(scope, failed.id)?.id)
    }

    @Test
    fun publicationAttemptSurvivesInterruptedRecoveryAndCanBeConfirmed() {
        val repository = repository()
        val file = imported(repository, "voice.wav", modified = 10)
        val fingerprint = file.entry.fingerprint
        val key = TranscriptPublicationKeys.forEntry(file.id, fingerprint, "output.md")

        repository.ensurePublication(key, file.id, fingerprint, "output.md")
        repository.savePreparedPublication(
            attemptKey = key,
            transcriptText = "hello",
            formattedEntry = "voice.wav\n- recorded: unknown\nhello",
            durationUs = 123,
        )
        repository.markPublicationUnknown(key, "provider result unknown")
        repository.recoverInterrupted()

        val recovered = assertNotNull(repository.findPublication(key))
        assertEquals(TranscriptPublicationState.UNKNOWN, recovered.state)
        assertEquals("hello", recovered.transcriptText)
        assertEquals("provider result unknown", recovered.lastError)

        repository.markPublicationConfirmed(key)
        assertEquals(
            TranscriptPublicationState.CONFIRMED,
            repository.findPublication(key)?.state,
        )
    }

    @Test
    fun publicationKeysDoNotReuseChangedFingerprintOrOutput() {
        val repository = repository()
        val file = imported(repository, "voice.wav", modified = 10)
        val firstFingerprint = file.entry.fingerprint
        val changedFingerprint = firstFingerprint.copy(modifiedMillis = 11)
        val firstKey = TranscriptPublicationKeys.forEntry(file.id, firstFingerprint, "output.md")
        val changedKey = TranscriptPublicationKeys.forEntry(file.id, changedFingerprint, "output.md")
        val otherOutputKey = TranscriptPublicationKeys.forEntry(file.id, firstFingerprint, "other.md")

        repository.ensurePublication(firstKey, file.id, firstFingerprint, "output.md")
        repository.ensurePublication(changedKey, file.id, changedFingerprint, "output.md")
        repository.ensurePublication(otherOutputKey, file.id, firstFingerprint, "other.md")

        assertNotNull(repository.findPublication(firstKey))
        assertNotNull(repository.findPublication(changedKey))
        assertNotNull(repository.findPublication(otherOutputKey))
        assertEquals(TranscriptPublicationState.READY, repository.findPublication(changedKey)?.state)
    }

    @Test
    fun appOwnedEntriesCanBeRenamedAndDeletedWithPublicationMetadata() {
        val repository = repository()
        val file = imported(
            repository = repository,
            name = "voice-note.wav",
            modified = 10,
            state = AudioFileState.PROCESSED,
            processedAtMillis = 20,
            transcriptText = "hello",
        )
        val key = TranscriptPublicationKeys.forEntry(file.id, file.entry.fingerprint, "output.md")
        repository.ensurePublication(key, file.id, file.entry.fingerprint, "output.md")
        repository.markPublicationConfirmed(key)

        assertEquals(AudioStorageOwnership.APP_OWNED_COPY, file.ownership)
        assertEquals("renamed note", repository.renameEntry(file.id, "  renamed note  ")?.displayName)
        assertTrue(repository.hasConfirmedPublication(file.id))
        assertTrue(repository.deleteAppOwnedEntry(file.id))
        assertNull(repository.entry(file.id))
        assertNull(repository.findPublication(key))
    }

    @Test
    fun externalEntriesRejectDeletionAndInvalidNamesDoNotMutateRows() {
        val repository = repository()
        repository.reconcile(
            OTHER_FOLDER,
            listOf(
                ScannedAudioFile(
                    folderUri = OTHER_FOLDER,
                    documentUri = "external.wav",
                    displayName = "external.wav",
                    mimeType = "audio/wav",
                    fingerprint = AudioFileFingerprint(100, 10),
                ),
            ),
        )
        val external = assertNotNull(repository.existingEntriesForReconciliation(OTHER_FOLDER).singleOrNull())
        assertEquals(AudioStorageOwnership.EXTERNAL_SOURCE, external.ownership)
        assertFalse(repository.deleteAppOwnedEntry(external.id))
        assertEquals("external.wav", repository.entry(external.id)?.displayName)
        assertNull(repository.renameEntry(external.id, "bad/name"))
        assertEquals("external.wav", repository.entry(external.id)?.displayName)
    }

    private fun repository(): SqlDelightAudioCatalogRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VoiceInboxDatabase.Schema.create(driver)
        return SqlDelightAudioCatalogRepository(driver)
    }

    private fun imported(
        repository: SqlDelightAudioCatalogRepository,
        name: String,
        modified: Long,
        state: AudioFileState = AudioFileState.PENDING,
        lastError: String? = null,
        processedAtMillis: Long? = null,
        transcriptText: String? = null,
        folderUri: String = FOLDER,
    ): SqlDelightAudioCatalogFile =
        repository.upsertImportedFile(
            folderUri = folderUri,
            documentUri = name,
            displayName = name,
            mimeType = "audio/wav",
            sizeBytes = 100,
            importedAtMillis = modified,
            state = state,
            lastError = lastError,
            processedAtMillis = processedAtMillis,
            transcriptText = transcriptText,
            durationUs = null,
        )

    private fun scanned(name: String, modified: Long): ScannedAudioFile =
        ScannedAudioFile(
            folderUri = FOLDER,
            documentUri = name,
            displayName = name,
            mimeType = "audio/wav",
            fingerprint = AudioFileFingerprint(sizeBytes = 100, modifiedMillis = modified),
        )

    private companion object {
        const val FOLDER = "ios-imported-audio"
        const val IMPORTS = "android-imported-audio"
        const val OTHER_FOLDER = "content://other-folder"
    }
}
