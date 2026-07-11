package me.maxistar.voiceinbox

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
