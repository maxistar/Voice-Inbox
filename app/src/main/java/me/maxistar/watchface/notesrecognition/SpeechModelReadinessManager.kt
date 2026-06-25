package me.maxistar.watchface.notesrecognition

import java.io.File
import java.util.concurrent.Executor

sealed interface SpeechModelReadinessState {
    data object Checking : SpeechModelReadinessState
    data class Ready(val directory: File) : SpeechModelReadinessState
    data object Missing : SpeechModelReadinessState
    data class Invalid(val reason: String) : SpeechModelReadinessState
    data class Failed(val message: String) : SpeechModelReadinessState
}

class SpeechModelReadinessManager(
    private val repository: SpeechModelRepository,
    private val initializeModel: (File) -> Boolean,
    private val executor: Executor,
) {
    private val lock = Any()
    private var cachedState: SpeechModelReadinessState? = null
    private var checking = false
    private val callbacks = mutableListOf<(SpeechModelReadinessState) -> Unit>()

    fun refresh(callback: (SpeechModelReadinessState) -> Unit) {
        var shouldStart = false
        val immediate = synchronized(lock) {
            when {
                cachedState != null -> cachedState
                else -> {
                    callbacks += callback
                    shouldStart = !checking
                    checking = true
                    null
                }
            }
        }
        if (immediate != null) {
            callback(immediate)
        } else {
            callback(SpeechModelReadinessState.Checking)
            if (shouldStart) {
                executor.execute { checkModel() }
            }
        }
    }

    fun invalidate() {
        synchronized(lock) {
            cachedState = null
        }
    }

    private fun checkModel() {
        val state = runCatching {
            repository.cleanupStaleState()
            when (val installed = repository.inspect()) {
                is InstalledSpeechModelState.Ready -> {
                    if (initializeModel(installed.directory)) {
                        SpeechModelReadinessState.Ready(installed.directory)
                    } else {
                        SpeechModelReadinessState.Failed("Speech model failed to load")
                    }
                }
                InstalledSpeechModelState.Missing -> SpeechModelReadinessState.Missing
                is InstalledSpeechModelState.Invalid -> SpeechModelReadinessState.Invalid(installed.reason)
            }
        }.getOrElse { error ->
            SpeechModelReadinessState.Failed(error.message ?: "Speech model failed to load")
        }

        val pending = synchronized(lock) {
            cachedState = state
            checking = false
            callbacks.toList().also { callbacks.clear() }
        }
        pending.forEach { it(state) }
    }
}
