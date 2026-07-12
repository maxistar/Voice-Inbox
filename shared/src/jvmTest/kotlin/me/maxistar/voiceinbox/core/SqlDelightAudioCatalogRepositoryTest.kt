package me.maxistar.voiceinbox.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import me.maxistar.voiceinbox.db.VoiceInboxDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
    ): SqlDelightAudioCatalogFile =
        repository.upsertImportedFile(
            folderUri = FOLDER,
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
    }
}
