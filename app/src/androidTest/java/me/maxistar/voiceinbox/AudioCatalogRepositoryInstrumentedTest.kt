package me.maxistar.voiceinbox

import me.maxistar.voiceinbox.core.*

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
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var database: AudioCatalogDatabase
    private lateinit var repository: AudioCatalogRepository

    @Before
    fun setUp() {
        context.deleteDatabase(AudioCatalogDatabase.DATABASE_NAME)
        database = AudioCatalogDatabase(context)
        repository = AudioCatalogRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(AudioCatalogDatabase.DATABASE_NAME)
    }

    @Test
    fun migrationFromVersionOneAddsNullableTranscriptText() {
        database.close()
        context.deleteDatabase(AudioCatalogDatabase.DATABASE_NAME)
        createVersionOneCatalog()

        database = AudioCatalogDatabase(context)
        database.writableDatabase.rawQuery(
            "PRAGMA table_info(${AudioCatalogDatabase.TABLE_FILES})",
            null,
        ).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val columns = buildList {
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
            assertTrue(columns.contains(AudioCatalogDatabase.COLUMN_TRANSCRIPT_TEXT))
        }
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
        database.close()
        database = AudioCatalogDatabase(context)
        repository = AudioCatalogRepository(database)

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

        database.close()
        database = AudioCatalogDatabase(context)
        repository = AudioCatalogRepository(database)

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
        val path = context.getDatabasePath(AudioCatalogDatabase.DATABASE_NAME)
        path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { database ->
            database.execSQL(
                """
                CREATE TABLE ${AudioCatalogDatabase.TABLE_FILES} (
                    ${AudioCatalogDatabase.COLUMN_ID} INTEGER PRIMARY KEY AUTOINCREMENT,
                    ${AudioCatalogDatabase.COLUMN_FOLDER_URI} TEXT NOT NULL,
                    ${AudioCatalogDatabase.COLUMN_DOCUMENT_URI} TEXT NOT NULL,
                    ${AudioCatalogDatabase.COLUMN_DISPLAY_NAME} TEXT NOT NULL,
                    ${AudioCatalogDatabase.COLUMN_MIME_TYPE} TEXT,
                    ${AudioCatalogDatabase.COLUMN_SIZE_BYTES} INTEGER,
                    ${AudioCatalogDatabase.COLUMN_MODIFIED_MILLIS} INTEGER,
                    ${AudioCatalogDatabase.COLUMN_STATE} TEXT NOT NULL,
                    ${AudioCatalogDatabase.COLUMN_STATE_BEFORE_MISSING} TEXT,
                    ${AudioCatalogDatabase.COLUMN_LAST_ERROR} TEXT,
                    ${AudioCatalogDatabase.COLUMN_PROCESSED_AT} INTEGER,
                    UNIQUE(${AudioCatalogDatabase.COLUMN_FOLDER_URI}, ${AudioCatalogDatabase.COLUMN_DOCUMENT_URI})
                )
                """.trimIndent(),
            )
            database.version = 1
        }
    }

    companion object {
        private const val FOLDER = "content://folder"
    }
}
