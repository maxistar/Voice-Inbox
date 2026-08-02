package me.maxistar.voiceinbox

import java.io.File

object SpeechModelPreparation {
    private val lock = Any()
    private var preparedInstallation: String? = null

    fun prepare(
        repository: SpeechModelRepository,
        initializeModel: (InstalledSpeechModelState.Ready) -> Boolean,
    ): Result<File> = synchronized(lock) {
        val expected = "${repository.descriptor.backend}:${repository.descriptor.catalogId}:${repository.manifest.version}:${repository.installedDirectory.canonicalPath}"
        if (preparedInstallation == expected) {
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
            check(initializeModel(installed)) { "Speech model failed to load" }
            preparedInstallation = expected
            installed.directory
        }
    }

    fun invalidate(resetNative: () -> Unit = {}) {
        synchronized(lock) {
            preparedInstallation = null
            resetNative()
        }
    }
}
