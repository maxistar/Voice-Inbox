package me.maxistar.voiceinbox

import java.io.File

object SpeechModelPreparation {
    private val lock = Any()
    private var preparedDirectory: String? = null

    fun prepare(
        repository: SpeechModelRepository,
        initializeModel: (File) -> Boolean,
    ): Result<File> = synchronized(lock) {
        val expected = repository.installedDirectory.canonicalPath
        if (preparedDirectory == expected) {
            return@synchronized Result.success(repository.installedDirectory)
        }
        runCatching {
            val installed = repository.inspect()
            check(installed is InstalledSpeechModelState.Ready) {
                when (installed) {
                    InstalledSpeechModelState.Missing -> "Speech model is not installed"
                    is InstalledSpeechModelState.Invalid -> installed.reason
                    is InstalledSpeechModelState.Ready -> error("unreachable")
                }
            }
            check(initializeModel(installed.directory)) { "Speech model failed to load" }
            preparedDirectory = installed.directory.canonicalPath
            installed.directory
        }
    }

    fun invalidate(resetNative: () -> Unit = {}) {
        synchronized(lock) {
            preparedDirectory = null
            resetNative()
        }
    }
}
