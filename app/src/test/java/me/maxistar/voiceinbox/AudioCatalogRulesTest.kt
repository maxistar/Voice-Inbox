package me.maxistar.voiceinbox

import me.maxistar.voiceinbox.core.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCatalogRulesTest {
    @Test
    fun knownSizeOrModificationDifferenceMarksFileChanged() {
        val entry = entry(size = 100, modified = 200)

        assertTrue(
            AudioCatalogRules.metadataChanged(
                entry,
                scanned(size = 101, modified = 200),
            ),
        )
        assertTrue(
            AudioCatalogRules.metadataChanged(
                entry,
                scanned(size = 100, modified = 201),
            ),
        )
        assertFalse(
            AudioCatalogRules.metadataChanged(
                entry,
                scanned(size = 100, modified = 200),
            ),
        )
    }

    @Test
    fun missingMetadataDoesNotInventAChange() {
        assertFalse(
            AudioCatalogRules.metadataChanged(
                entry(size = null, modified = 200),
                scanned(size = 100, modified = null),
            ),
        )
    }

    @Test
    fun changedAndInterruptedFilesReturnToPending() {
        assertEquals(
            AudioFileState.PENDING,
            AudioCatalogRules.rediscoveredState(
                entry(state = AudioFileState.PROCESSED, size = 100),
                scanned(size = 101),
            ),
        )
        assertEquals(
            AudioFileState.PENDING,
            AudioCatalogRules.rediscoveredState(
                entry(state = AudioFileState.PROCESSING),
                scanned(),
            ),
        )
    }

    @Test
    fun missingFileRestoresPreviousTerminalState() {
        assertEquals(
            AudioFileState.PROCESSED,
            AudioCatalogRules.rediscoveredState(
                entry(
                    state = AudioFileState.MISSING,
                    stateBeforeMissing = AudioFileState.PROCESSED,
                ),
                scanned(),
            ),
        )
        assertEquals(
            AudioFileState.FAILED,
            AudioCatalogRules.rediscoveredState(
                entry(
                    state = AudioFileState.MISSING,
                    stateBeforeMissing = AudioFileState.FAILED,
                ),
                scanned(),
            ),
        )
    }

    @Test
    fun processingOrderUsesModificationThenName() {
        val entries = listOf(
            entry(id = 1, name = "z.wav", modified = 10),
            entry(id = 2, name = "b.wav", modified = 5),
            entry(id = 3, name = "A.wav", modified = 5),
            entry(id = 4, name = "unknown.wav", modified = null),
        ).sortedWith(AudioCatalogRules.processingOrder)

        assertEquals(listOf(3L, 2L, 1L, 4L), entries.map(AudioCatalogEntry::id))
    }

    private fun entry(
        id: Long = 1,
        name: String = "recording.wav",
        state: AudioFileState = AudioFileState.PROCESSED,
        stateBeforeMissing: AudioFileState? = null,
        size: Long? = 100,
        modified: Long? = 200,
    ) = AudioCatalogEntry(
        id = id,
        folderUri = "content://folder",
        documentUri = "content://audio/$id",
        displayName = name,
        mimeType = "audio/wav",
        fingerprint = AudioFileFingerprint(size, modified),
        state = state,
        stateBeforeMissing = stateBeforeMissing,
        lastError = null,
        processedAtMillis = null,
        transcriptText = null,
    )

    private fun scanned(
        size: Long? = 100,
        modified: Long? = 200,
    ) = ScannedAudioFile(
        folderUri = "content://folder",
        documentUri = "content://audio/1",
        displayName = "recording.wav",
        mimeType = "audio/wav",
        fingerprint = AudioFileFingerprint(size, modified),
    )
}
