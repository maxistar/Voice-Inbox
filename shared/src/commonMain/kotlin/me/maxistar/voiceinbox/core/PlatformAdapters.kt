package me.maxistar.voiceinbox.core

/**
 * Platform responsibilities that should be implemented by Android adapters today
 * and by iOS/KMP adapters in future changes. These contracts intentionally avoid
 * Android framework types.
 */
interface PlatformFileAccess {
    fun readTail(documentId: String): String
    fun append(documentId: String, text: String)
}

interface PlatformAudioDecoder {
    fun decode(
        audioId: String,
        onProgress: (processedUs: Long, durationUs: Long?) -> Unit,
        onChunk: (samples: FloatArray) -> Unit,
    ): PlatformAudioInfo
}

data class PlatformAudioInfo(
    val durationUs: Long?,
    val embeddedRecordingTimeMillis: Long?,
)

interface PlatformModelStorage {
    fun modelDirectory(): String
    fun inspectInstalledModel(): Boolean
}

interface PlatformScheduler {
    fun scheduleNext(settings: ScheduledTranscriptionSettings)
    fun cancel()
}

interface PlatformSettingsStore<T> {
    fun load(): T
    fun save(value: T)
}

interface PlatformNativeTranscriber {
    fun initialize(modelDirectory: String): Boolean
    fun transcribeChunk(samples: FloatArray): String?
}
