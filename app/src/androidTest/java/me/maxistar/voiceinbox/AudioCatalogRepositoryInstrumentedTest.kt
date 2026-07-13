package me.maxistar.voiceinbox

import me.maxistar.voiceinbox.core.*

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioCatalogRepositoryInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var repository: SqlDelightAudioCatalogRepository

    @Before
    fun setUp() {
        context.deleteDatabase(TEST_DATABASE_NAME)
        repository = AndroidSqlDelightAudioCatalogFactory(context).create(TEST_DATABASE_NAME)
    }

    @After
    fun tearDown() {
        repository.close()
        context.deleteDatabase(TEST_DATABASE_NAME)
    }

    @Test
    fun migrationFromVersionOneAddsTranscriptAndDurationColumns() {
        repository.close()
        context.deleteDatabase(TEST_DATABASE_NAME)
        createVersionOneCatalog()

        repository = AndroidSqlDelightAudioCatalogFactory(context).create(TEST_DATABASE_NAME)

        val migrated = repository.claimPending(FOLDER)!!
        repository.markProcessedFile(
            id = migrated.id,
            processedAtMillis = 500,
            transcriptText = "migrated text",
            durationUs = 750,
        )

        val processed = repository.processedEntries(FOLDER).single()
        assertEquals("migrated text", processed.transcriptText)
    }

    @Test
    fun migrationFromVersionTwoPreservesTranscriptAndAddsDurationColumn() {
        repository.close()
        context.deleteDatabase(TEST_DATABASE_NAME)
        createVersionTwoCatalog()

        repository = AndroidSqlDelightAudioCatalogFactory(context).create(TEST_DATABASE_NAME)

        val processedRows = repository.processedEntries(FOLDER)
        val restored = processedRows.single { it.state == AudioFileState.PROCESSED }
        val failed = processedRows.single { it.state == AudioFileState.FAILED }
        val pending = repository.newEntries(FOLDER).single()
        val missing = repository.missingEntries(FOLDER).single()

        assertEquals("already recognized", restored.transcriptText)
        assertEquals("decode failed", failed.lastError)
        assertEquals("legacy-pending.wav", pending.displayName)
        assertEquals(AudioFileState.MISSING, missing.state)
        assertEquals(AudioFileState.PROCESSED, missing.stateBeforeMissing)
        repository.markProcessedFile(
            id = restored.id,
            processedAtMillis = 600,
            transcriptText = "updated text",
            durationUs = 900,
        )

        assertEquals(
            "updated text",
            repository.processedEntries(FOLDER)
                .single { it.state == AudioFileState.PROCESSED }
                .transcriptText,
        )
    }

    @Test
    fun schemaPersistsEntriesAndOrdersPendingRowsNewestFirstForDisplay() {
        repository.reconcile(
            FOLDER,
            listOf(
                scanned("z.wav", 20),
                scanned("b.wav", 10),
                scanned("A.wav", 10),
                scanned("unknown.wav", null),
            ),
        )
        repository.close()
        repository = AndroidSqlDelightAudioCatalogFactory(context).create(TEST_DATABASE_NAME)

        assertEquals(
            listOf("z.wav", "A.wav", "b.wav", "unknown.wav"),
            repository.newEntries(FOLDER).map(AudioCatalogEntry::displayName),
        )
    }

    @Test
    fun displayOrderDoesNotChangeBatchClaimOrder() {
        repository.reconcile(
            FOLDER,
            listOf(
                scanned("z.wav", 20),
                scanned("b.wav", 10),
                scanned("A.wav", 10),
                scanned("unknown.wav", null),
            ),
        )

        assertEquals(
            listOf("z.wav", "A.wav", "b.wav", "unknown.wav"),
            repository.newEntries(FOLDER).map(AudioCatalogEntry::displayName),
        )
        assertEquals("A.wav", repository.claimPending(FOLDER)!!.displayName)
        assertEquals("b.wav", repository.claimPending(FOLDER)!!.displayName)
        assertEquals("z.wav", repository.claimPending(FOLDER)!!.displayName)
        assertEquals("unknown.wav", repository.claimPending(FOLDER)!!.displayName)
    }

    @Test
    fun processedEntriesUseNewestFirstDisplayOrder() {
        repository.reconcile(
            FOLDER,
            listOf(scanned("old.wav", 10), scanned("new.wav", 20)),
        )
        repository.claimPending(FOLDER)!!
            .also { repository.markProcessed(it.id, 500, "old text") }
        repository.claimPending(FOLDER)!!
            .also { repository.markProcessed(it.id, 600, "new text") }

        assertEquals(
            listOf("new.wav", "old.wav"),
            repository.processedEntries(FOLDER).map(AudioCatalogEntry::displayName),
        )
    }

    @Test
    fun reconcilePreservesTerminalStateAndDetectsChanges() {
        repository.reconcile(FOLDER, listOf(scanned("one.wav", 10, size = 100)))
        val entry = repository.claimPending(FOLDER)
        assertNotNull(entry)
        repository.markProcessed(entry!!.id, 500, "recognized text")

        repository.reconcile(FOLDER, listOf(scanned("one.wav", 10, size = 100)))
        val unchanged = repository.processedEntries(FOLDER).single()
        assertEquals(AudioFileState.PROCESSED, unchanged.state)
        assertEquals("recognized text", unchanged.transcriptText)

        repository.reconcile(FOLDER, listOf(scanned("one.wav", 11, size = 100)))
        val changed = repository.newEntries(FOLDER).single()
        assertEquals(AudioFileState.PENDING, changed.state)
        assertNull(changed.processedAtMillis)
        assertNull(changed.transcriptText)
    }

    @Test
    fun processedTranscriptTextPersistsAcrossDatabaseReopen() {
        repository.reconcile(FOLDER, listOf(scanned("one.wav", 10)))
        val entry = repository.claimPending(FOLDER)!!
        repository.markProcessed(entry.id, 500, "hello from audio")

        repository.close()
        repository = AndroidSqlDelightAudioCatalogFactory(context).create(TEST_DATABASE_NAME)

        val processed = repository.processedEntries(FOLDER).single()
        assertEquals(AudioFileState.PROCESSED, processed.state)
        assertEquals("hello from audio", processed.transcriptText)
    }

    @Test
    fun failureMissingReappearanceAndRetryTransitionsAreDurable() {
        repository.reconcile(FOLDER, listOf(scanned("one.wav", 10)))
        val entry = repository.claimPending(FOLDER)!!
        repository.markFailed(entry.id, "decode failed")

        repository.reconcile(FOLDER, listOf(scanned("one.wav", 10)))
        assertTrue(repository.newEntries(FOLDER).isEmpty())
        val failed = repository.processedEntries(FOLDER).single()
        assertEquals(AudioFileState.FAILED, failed.state)
        assertEquals("decode failed", failed.lastError)

        repository.reconcile(FOLDER, emptyList())
        assertTrue(repository.newEntries(FOLDER).isEmpty())
        assertTrue(repository.processedEntries(FOLDER).isEmpty())
        assertEquals(AudioFileState.MISSING, repository.missingEntries(FOLDER).single().state)

        repository.reconcile(FOLDER, listOf(scanned("one.wav", 10)))
        assertTrue(repository.newEntries(FOLDER).isEmpty())
        val restored = repository.processedEntries(FOLDER).single()
        assertEquals(AudioFileState.FAILED, restored.state)
        assertEquals("decode failed", restored.lastError)

        assertTrue(repository.resetForRetry(restored.id))
        assertEquals(AudioFileState.PENDING, repository.newEntries(FOLDER).single().state)
        assertTrue(repository.processedEntries(FOLDER).isEmpty())
    }

    @Test
    fun interruptedRowsRecoverAndClaimsExcludeOtherStates() {
        repository.reconcile(
            FOLDER,
            listOf(scanned("one.wav", 10), scanned("two.wav", 20)),
        )
        val first = repository.claimPending(FOLDER)!!
        repository.markFailed(first.id, "failed")
        val second = repository.claimPending(FOLDER)!!
        assertNull(repository.claimPending(FOLDER))

        repository.recoverInterrupted()
        assertEquals(second.id, repository.claimPending(FOLDER)!!.id)
        assertNull(repository.claimFailed(FOLDER, second.id))
        assertNotNull(repository.claimFailed(FOLDER, first.id))
    }

    private fun scanned(
        name: String,
        modified: Long?,
        size: Long = 50,
    ) = ScannedAudioFile(
        folderUri = FOLDER,
        documentUri = "content://audio/$name",
        displayName = name,
        mimeType = "audio/wav",
        fingerprint = AudioFileFingerprint(size, modified),
    )

    private fun createVersionOneCatalog() {
        val path = context.getDatabasePath(TEST_DATABASE_NAME)
        path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { database ->
            database.execSQL(
                """
                CREATE TABLE audio_files (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    folder_uri TEXT NOT NULL,
                    document_uri TEXT NOT NULL,
                    display_name TEXT NOT NULL,
                    mime_type TEXT,
                    size_bytes INTEGER,
                    modified_millis INTEGER,
                    state TEXT NOT NULL,
                    state_before_missing TEXT,
                    last_error TEXT,
                    processed_at INTEGER,
                    UNIQUE(folder_uri, document_uri)
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO audio_files(
                    folder_uri,
                    document_uri,
                    display_name,
                    mime_type,
                    size_bytes,
                    modified_millis,
                    state,
                    state_before_missing,
                    last_error,
                    processed_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL)
                """.trimIndent(),
                arrayOf(FOLDER, "content://audio/legacy-v1.wav", "legacy-v1.wav", "audio/wav", 50, 10, "PENDING"),
            )
            database.version = 1
        }
    }

    private fun createVersionTwoCatalog() {
        val path = context.getDatabasePath(TEST_DATABASE_NAME)
        path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { database ->
            database.execSQL(
                """
                CREATE TABLE audio_files (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    folder_uri TEXT NOT NULL,
                    document_uri TEXT NOT NULL,
                    display_name TEXT NOT NULL,
                    mime_type TEXT,
                    size_bytes INTEGER,
                    modified_millis INTEGER,
                    state TEXT NOT NULL,
                    state_before_missing TEXT,
                    last_error TEXT,
                    processed_at INTEGER,
                    transcript_text TEXT,
                    UNIQUE(folder_uri, document_uri)
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO audio_files(
                    folder_uri,
                    document_uri,
                    display_name,
                    mime_type,
                    size_bytes,
                    modified_millis,
                    state,
                    state_before_missing,
                    last_error,
                    processed_at,
                    transcript_text
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?)
                """.trimIndent(),
                arrayOf(
                    FOLDER,
                    "content://audio/legacy-processed.wav",
                    "legacy-processed.wav",
                    "audio/wav",
                    50,
                    40,
                    "PROCESSED",
                    500,
                    "already recognized",
                ),
            )
            database.execSQL(
                """
                INSERT INTO audio_files(
                    folder_uri,
                    document_uri,
                    display_name,
                    mime_type,
                    size_bytes,
                    modified_millis,
                    state,
                    state_before_missing,
                    last_error,
                    processed_at,
                    transcript_text
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, NULL, NULL)
                """.trimIndent(),
                arrayOf(
                    FOLDER,
                    "content://audio/legacy-failed.wav",
                    "legacy-failed.wav",
                    "audio/wav",
                    50,
                    30,
                    "FAILED",
                    "decode failed",
                ),
            )
            database.execSQL(
                """
                INSERT INTO audio_files(
                    folder_uri,
                    document_uri,
                    display_name,
                    mime_type,
                    size_bytes,
                    modified_millis,
                    state,
                    state_before_missing,
                    last_error,
                    processed_at,
                    transcript_text
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)
                """.trimIndent(),
                arrayOf(
                    FOLDER,
                    "content://audio/legacy-missing.wav",
                    "legacy-missing.wav",
                    "audio/wav",
                    50,
                    20,
                    "MISSING",
                    "PROCESSED",
                    400,
                    "missing transcript",
                ),
            )
            database.execSQL(
                """
                INSERT INTO audio_files(
                    folder_uri,
                    document_uri,
                    display_name,
                    mime_type,
                    size_bytes,
                    modified_millis,
                    state,
                    state_before_missing,
                    last_error,
                    processed_at,
                    transcript_text
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL)
                """.trimIndent(),
                arrayOf(
                    FOLDER,
                    "content://audio/legacy-pending.wav",
                    "legacy-pending.wav",
                    "audio/wav",
                    50,
                    10,
                    "PENDING",
                ),
            )
            database.version = 2
        }
    }

    companion object {
        private const val TEST_DATABASE_NAME = "audio-catalog-test.db"
        private const val FOLDER = "content://folder"
    }
}
