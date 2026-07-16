package me.maxistar.voiceinbox.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AudioCatalogQueuePortTest {
    @Test
    fun queuePortSupportsTranscriptionLifecycleFromCommonCode() {
        val catalog: AudioCatalogQueuePort = FakeAudioCatalogQueuePort(
            listOf(
                entry(id = 1, name = "one.wav", state = AudioFileState.PENDING),
                entry(id = 2, name = "two.wav", state = AudioFileState.FAILED),
                entry(id = 3, name = "three.wav", state = AudioFileState.PROCESSING),
            ),
        )

        assertEquals(1, catalog.pendingCount(FOLDER))

        catalog.recoverInterrupted()
        assertEquals(2, catalog.pendingCount(FOLDER))

        val pending = assertNotNull(catalog.claimPending(FOLDER))
        assertEquals(AudioFileState.PROCESSING, pending.state)
        catalog.markProcessed(pending.id, processedAtMillis = 500, transcriptText = "hello")

        val failed = assertNotNull(catalog.claimFailed(FOLDER, 2))
        assertEquals(AudioFileState.PROCESSING, failed.state)
        catalog.markPending(failed.id)

        assertEquals(2, catalog.pendingCount(FOLDER))
        assertNull(catalog.claimFailed(FOLDER, 2))
    }

    private class FakeAudioCatalogQueuePort(
        entries: List<AudioCatalogEntry>,
    ) : AudioCatalogQueuePort {
        private val entries = entries.associateBy(AudioCatalogEntry::id).toMutableMap()

        override fun pendingCount(scope: AudioCatalogSourceScope): Int =
            entries.values.count {
                it.folderUri in scope.sourceIds && it.state == AudioFileState.PENDING
            }

        override fun recoverInterrupted() {
            entries.keys.toList().forEach { id ->
                val entry = entries.getValue(id)
                if (entry.state == AudioFileState.PROCESSING) {
                    entries[id] = entry.copy(
                        state = AudioFileState.PENDING,
                        lastError = null,
                        processedAtMillis = null,
                        transcriptText = null,
                    )
                }
            }
        }

        override fun claimPending(
            scope: AudioCatalogSourceScope,
            specificId: Long?,
        ): AudioCatalogEntry? = claim(scope, AudioFileState.PENDING, specificId)

        override fun claimFailed(scope: AudioCatalogSourceScope, id: Long): AudioCatalogEntry? =
            claim(scope, AudioFileState.FAILED, id)

        override fun markProcessed(
            id: Long,
            processedAtMillis: Long,
            transcriptText: String,
        ) {
            update(id) {
                it.copy(
                    state = AudioFileState.PROCESSED,
                    lastError = null,
                    processedAtMillis = processedAtMillis,
                    transcriptText = transcriptText,
                )
            }
        }

        override fun markFailed(id: Long, message: String) {
            update(id) {
                it.copy(
                    state = AudioFileState.FAILED,
                    lastError = message,
                    processedAtMillis = null,
                    transcriptText = null,
                )
            }
        }

        override fun markPending(id: Long) {
            update(id) {
                if (it.state == AudioFileState.PROCESSING) {
                    it.copy(
                        state = AudioFileState.PENDING,
                        lastError = null,
                        processedAtMillis = null,
                        transcriptText = null,
                    )
                } else {
                    it
                }
            }
        }

        private fun claim(
            scope: AudioCatalogSourceScope,
            state: AudioFileState,
            specificId: Long?,
        ): AudioCatalogEntry? {
            val entry = entries.values
                .filter { it.folderUri in scope.sourceIds && it.state == state }
                .filter { specificId == null || it.id == specificId }
                .sortedWith(AudioCatalogRules.processingOrder)
                .firstOrNull()
                ?: return null
            val claimed = entry.copy(state = AudioFileState.PROCESSING, lastError = null)
            entries[entry.id] = claimed
            return claimed
        }

        private fun update(id: Long, transform: (AudioCatalogEntry) -> AudioCatalogEntry) {
            entries[id] = transform(entries.getValue(id))
        }
    }

    private fun entry(
        id: Long,
        name: String,
        state: AudioFileState,
    ) = AudioCatalogEntry(
        id = id,
        folderUri = FOLDER,
        documentUri = "content://audio/$id",
        displayName = name,
        mimeType = "audio/wav",
        fingerprint = AudioFileFingerprint(sizeBytes = 50, modifiedMillis = id),
        state = state,
        stateBeforeMissing = null,
        lastError = if (state == AudioFileState.FAILED) "failed" else null,
        processedAtMillis = null,
        transcriptText = null,
    )

    private companion object {
        const val FOLDER = "content://folder"
    }
}
