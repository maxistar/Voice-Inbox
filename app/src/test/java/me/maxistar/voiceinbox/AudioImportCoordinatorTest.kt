package me.maxistar.voiceinbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioImportCoordinatorTest {
    @Test
    fun validationAcceptsAudioMimeAndVoiceNoteExtensions() {
        assertEquals(
            null,
            AudioImportRules.rejectionReason(metadata("voice", "audio/ogg", 10)),
        )
        assertEquals(
            null,
            AudioImportRules.rejectionReason(metadata("voice.OPUS", "application/octet-stream", 10)),
        )
        assertEquals(
            "Unsupported audio format",
            AudioImportRules.rejectionReason(metadata("notes.txt", "text/plain", 10)),
        )
        assertEquals(
            "Audio file is empty",
            AudioImportRules.rejectionReason(metadata("empty.wav", "audio/wav", 0)),
        )
    }

    @Test
    fun sameNameWithDistinctIdentityKeepsBothImports() {
        val storage = FakeStorage(
            metadata("voice.ogg", "audio/ogg", 10, source = "content://one"),
            metadata("voice.ogg", "audio/ogg", 20, source = "content://two"),
        )
        val catalog = FakeCatalog()

        val result = useCase(storage, catalog).ingest(listOf("content://one", "content://two"))

        assertEquals(2, result.importedCount)
        assertEquals(2, catalog.rows.size)
        assertEquals(setOf("voice.ogg"), catalog.rows.values.map { it.displayName }.toSet())
        assertEquals(2, storage.stored.size)
    }

    @Test
    fun stableRedeliveryReusesCopyAndCatalogRow() {
        val storage = FakeStorage(metadata("voice.m4a", "audio/mp4", 100))
        val catalog = FakeCatalog()
        assertEquals(1, useCase(storage, catalog).ingest(listOf(SOURCE)).importedCount)
        assertEquals(1, useCase(storage, catalog).ingest(listOf(SOURCE)).duplicateCount)
        assertEquals(1, storage.copyCount)
        assertEquals(1, catalog.rows.size)
    }

    @Test
    fun insufficientMetadataPreservesRepeatedDeliveries() {
        val storage = FakeStorage(metadata("voice", "audio/ogg", null))
        val catalog = FakeCatalog()
        var token = 0
        val useCase = AudioImportUseCase(
            storage = storage,
            catalog = catalog,
            clockMillis = { 1234 },
            unstableToken = { "token-${token++}" },
        )

        assertEquals(1, useCase.ingest(listOf(SOURCE)).importedCount)
        assertEquals(1, useCase.ingest(listOf(SOURCE)).importedCount)
        assertEquals(2, catalog.rows.size)
    }

    @Test
    fun catalogFailureRemovesNewCopy() {
        val storage = FakeStorage(metadata("voice.wav", "audio/wav", 10))
        val catalog = FakeCatalog(failInsert = true)

        val result = useCase(storage, catalog).ingest(listOf(SOURCE))

        assertEquals(1, result.rejectedCount)
        assertTrue(result.message().contains("catalog unavailable"))
        assertTrue(storage.stored.isEmpty())
        assertEquals(1, storage.deleteCount)
    }

    @Test
    fun partialInputKeepsSuccessAndReportsRejectedItems() {
        val storage = FakeStorage(
            metadata("good.flac", "audio/flac", 30, source = "content://good"),
            metadata("bad.txt", "text/plain", 5, source = "content://bad"),
            metadata("broken.mp3", "audio/mpeg", 10, source = "content://broken"),
        ).apply {
            copyFailures += "content://broken"
        }
        val catalog = FakeCatalog()

        val result = useCase(storage, catalog).ingest(
            listOf("content://good", "content://bad", "content://broken", "content://good"),
        )

        assertEquals(1, result.importedCount)
        assertEquals(0, result.duplicateCount)
        assertEquals(2, result.rejectedCount)
        assertEquals(1, catalog.rows.size)
        assertTrue(result.message().contains("Imported 1"))
        assertTrue(result.message().contains("2 rejected"))
        assertFalse(storage.stored.any { it.contains("broken") })
    }

    private fun useCase(storage: FakeStorage, catalog: FakeCatalog) =
        AudioImportUseCase(
            storage = storage,
            catalog = catalog,
            clockMillis = { 1234 },
            unstableToken = { "unstable" },
        )

    private fun metadata(
        name: String,
        mime: String?,
        size: Long?,
        source: String = SOURCE,
    ) = AudioImportMetadata(
        sourceId = source,
        displayName = name,
        mimeType = mime,
        sizeBytes = size,
        modifiedMillis = null,
    )

    private data class CatalogRow(
        val displayName: String,
        val sizeBytes: Long,
    )

    private class FakeCatalog(
        private val failInsert: Boolean = false,
    ) : AudioImportCatalog {
        val rows = mutableMapOf<String, CatalogRow>()

        override fun contains(documentId: String): Boolean = documentId in rows

        override fun insertPending(
            documentId: String,
            displayName: String,
            mimeType: String?,
            sizeBytes: Long,
            importedAtMillis: Long,
        ) {
            if (failInsert) error("catalog unavailable")
            rows[documentId] = CatalogRow(displayName, sizeBytes)
        }
    }

    private class FakeStorage(
        vararg metadata: AudioImportMetadata,
    ) : AudioImportStorage {
        private val metadata = metadata.associateBy(AudioImportMetadata::sourceId)
        val stored = mutableSetOf<String>()
        val copyFailures = mutableSetOf<String>()
        var copyCount = 0
        var deleteCount = 0

        override fun metadata(sourceId: String): AudioImportMetadata =
            metadata[sourceId] ?: error("unreadable")

        override fun destination(
            receiptKey: String,
            displayName: String,
        ): AudioImportDestination {
            val documentId = "content://imports/$receiptKey-${AudioImportRules.safeFilename(displayName)}"
            return AudioImportDestination(documentId, documentId in stored)
        }

        override fun copy(sourceId: String, destination: AudioImportDestination): Long {
            if (sourceId in copyFailures) error("copy failed")
            copyCount += 1
            stored += destination.documentId
            return metadata(sourceId).sizeBytes ?: 12
        }

        override fun delete(destination: AudioImportDestination) {
            deleteCount += 1
            stored -= destination.documentId
        }
    }

    private companion object {
        const val SOURCE = "content://voice"
    }
}
