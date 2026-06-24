package me.maxistar.watchface.notesrecognition

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
    fun schemaPersistsEntriesAndOrdersPendingRows() {
        repository.reconcile(
            FOLDER,
            listOf(
                scanned("z.wav", 20),
                scanned("b.wav", 10),
                scanned("A.wav", 10),
            ),
        )
        database.close()
        database = AudioCatalogDatabase(context)
        repository = AudioCatalogRepository(database)

        assertEquals(
            listOf("A.wav", "b.wav", "z.wav"),
            repository.newEntries(FOLDER).map(AudioCatalogEntry::displayName),
        )
    }

    @Test
    fun reconcilePreservesTerminalStateAndDetectsChanges() {
        repository.reconcile(FOLDER, listOf(scanned("one.wav", 10, size = 100)))
        val entry = repository.claimPending(FOLDER)
        assertNotNull(entry)
        repository.markProcessed(entry!!.id, 500)

        repository.reconcile(FOLDER, listOf(scanned("one.wav", 10, size = 100)))
        assertEquals(AudioFileState.PROCESSED, repository.processedEntries(FOLDER).single().state)

        repository.reconcile(FOLDER, listOf(scanned("one.wav", 11, size = 100)))
        val changed = repository.newEntries(FOLDER).single()
        assertEquals(AudioFileState.PENDING, changed.state)
        assertNull(changed.processedAtMillis)
    }

    @Test
    fun failureMissingReappearanceAndRetryTransitionsAreDurable() {
        repository.reconcile(FOLDER, listOf(scanned("one.wav", 10)))
        val entry = repository.claimPending(FOLDER)!!
        repository.markFailed(entry.id, "decode failed")

        repository.reconcile(FOLDER, listOf(scanned("one.wav", 10)))
        assertEquals(AudioFileState.FAILED, repository.newEntries(FOLDER).single().state)

        repository.reconcile(FOLDER, emptyList())
        assertTrue(repository.newEntries(FOLDER).isEmpty())
        assertEquals(AudioFileState.MISSING, repository.missingEntries(FOLDER).single().state)

        repository.reconcile(FOLDER, listOf(scanned("one.wav", 10)))
        val restored = repository.newEntries(FOLDER).single()
        assertEquals(AudioFileState.FAILED, restored.state)
        assertEquals("decode failed", restored.lastError)

        assertTrue(repository.resetForRetry(restored.id))
        assertEquals(AudioFileState.PENDING, repository.newEntries(FOLDER).single().state)
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
        modified: Long,
        size: Long = 50,
    ) = ScannedAudioFile(
        folderUri = FOLDER,
        documentUri = "content://audio/$name",
        displayName = name,
        mimeType = "audio/wav",
        fingerprint = AudioFileFingerprint(size, modified),
    )

    companion object {
        private const val FOLDER = "content://folder"
    }
}
