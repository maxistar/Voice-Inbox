package me.maxistar.voiceinbox.core

enum class AudioFileState {
    PENDING,
    PROCESSING,
    PROCESSED,
    FAILED,
    MISSING,
}

data class AudioFileFingerprint(
    val sizeBytes: Long?,
    val modifiedMillis: Long?,
)

data class ScannedAudioFile(
    val folderUri: String,
    val documentUri: String,
    val displayName: String,
    val mimeType: String?,
    val fingerprint: AudioFileFingerprint,
)

data class AudioCatalogEntry(
    val id: Long,
    val folderUri: String,
    val documentUri: String,
    val displayName: String,
    val mimeType: String?,
    val fingerprint: AudioFileFingerprint,
    val state: AudioFileState,
    val stateBeforeMissing: AudioFileState?,
    val lastError: String?,
    val processedAtMillis: Long?,
    val transcriptText: String?,
)

data class AudioCatalogSourceScope(
    val sourceIds: List<String>,
) {
    init {
        require(sourceIds.isNotEmpty()) { "At least one audio catalog source is required" }
        require(sourceIds.none(String::isBlank)) { "Audio catalog source ids must not be blank" }
        require(sourceIds.distinct().size == sourceIds.size) {
            "Audio catalog source ids must be unique"
        }
    }

    companion object {
        fun single(sourceId: String): AudioCatalogSourceScope =
            AudioCatalogSourceScope(listOf(sourceId))

        fun of(sourceIds: Iterable<String>): AudioCatalogSourceScope =
            AudioCatalogSourceScope(sourceIds.distinct().toList())
    }
}

interface AudioCatalogQueuePort {
    fun pendingCount(scope: AudioCatalogSourceScope): Int

    fun pendingCount(sourceId: String): Int =
        pendingCount(AudioCatalogSourceScope.single(sourceId))

    fun recoverInterrupted()

    fun claimPending(
        scope: AudioCatalogSourceScope,
        specificId: Long? = null,
    ): AudioCatalogEntry?

    fun claimPending(sourceId: String, specificId: Long? = null): AudioCatalogEntry? =
        claimPending(AudioCatalogSourceScope.single(sourceId), specificId)

    fun claimFailed(scope: AudioCatalogSourceScope, id: Long): AudioCatalogEntry?

    fun claimFailed(sourceId: String, id: Long): AudioCatalogEntry? =
        claimFailed(AudioCatalogSourceScope.single(sourceId), id)

    fun markProcessed(id: Long, processedAtMillis: Long, transcriptText: String)

    fun markFailed(id: Long, message: String)

    fun markFailedAt(id: Long, message: String, processedAtMillis: Long) {
        markFailed(id, message)
    }

    fun markPending(id: Long)
}

object AudioCatalogRules {
    val processingOrder = compareBy<AudioCatalogEntry>(
        { it.fingerprint.modifiedMillis ?: Long.MAX_VALUE },
        { it.displayName.lowercase() },
        { it.documentUri },
    )

    fun metadataChanged(entry: AudioCatalogEntry, scanned: ScannedAudioFile): Boolean =
        knownDifference(entry.fingerprint.sizeBytes, scanned.fingerprint.sizeBytes) ||
            knownDifference(entry.fingerprint.modifiedMillis, scanned.fingerprint.modifiedMillis)

    fun rediscoveredState(entry: AudioCatalogEntry, scanned: ScannedAudioFile): AudioFileState {
        if (metadataChanged(entry, scanned)) return AudioFileState.PENDING
        return when (entry.state) {
            AudioFileState.MISSING -> entry.stateBeforeMissing
                ?.takeUnless { it == AudioFileState.PROCESSING || it == AudioFileState.MISSING }
                ?: AudioFileState.PENDING
            AudioFileState.PROCESSING -> AudioFileState.PENDING
            else -> entry.state
        }
    }

    private fun knownDifference(previous: Long?, current: Long?): Boolean =
        previous != null && current != null && previous != current
}
