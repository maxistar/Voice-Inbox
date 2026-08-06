package me.maxistar.voiceinbox

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask

internal sealed interface SpeechModelWarmupState {
    data object Idle : SpeechModelWarmupState
    data class Preparing(val installation: String) : SpeechModelWarmupState
    data class Ready(val installation: String) : SpeechModelWarmupState
    data class Failed(val installation: String, val message: String) : SpeechModelWarmupState
}

internal class SpeechModelWarmupCoordinator(
    private val executor: Executor = Executors.newSingleThreadExecutor(),
    private val prepareModel: (SpeechModelRepository) -> Result<File>,
) {
    private data class Attempt(
        val installation: String,
        val future: Future<Result<File>>,
    )

    private val lock = Any()
    private var attempt: Attempt? = null
    private var currentState: SpeechModelWarmupState = SpeechModelWarmupState.Idle

    fun state(): SpeechModelWarmupState = synchronized(lock) { currentState }

    fun warmUp(repository: SpeechModelRepository) {
        prepare(repository, retryFailed = false)
    }

    fun prepare(
        repository: SpeechModelRepository,
        retryFailed: Boolean,
    ): Future<Result<File>> {
        val installation = installationIdentity(repository)
        synchronized(lock) {
            attempt?.takeIf { it.installation == installation }?.let { active ->
                if (currentState !is SpeechModelWarmupState.Failed || !retryFailed) {
                    return active.future
                }
            }

            lateinit var task: FutureTask<Result<File>>
            task = FutureTask(Callable {
                val result = prepareModel(repository)
                synchronized(lock) {
                    if (attempt?.future === task) {
                        currentState = result.fold(
                            onSuccess = { SpeechModelWarmupState.Ready(installation) },
                            onFailure = { error ->
                                SpeechModelWarmupState.Failed(
                                    installation,
                                    error.message ?: "Speech model preparation failed",
                                )
                            },
                        )
                    }
                }
                result
            })
            attempt = Attempt(installation, task)
            currentState = SpeechModelWarmupState.Preparing(installation)
            executor.execute(task)
            return task
        }
    }

    fun invalidate() {
        synchronized(lock) {
            attempt = null
            currentState = SpeechModelWarmupState.Idle
        }
    }

    private fun installationIdentity(repository: SpeechModelRepository): String =
        "${repository.descriptor.backend}:${repository.descriptor.catalogId}:" +
            "${repository.manifest.version}:${repository.installedDirectory.canonicalPath}"
}

internal object SpeechModelWarmup {
    private val coordinator = SpeechModelWarmupCoordinator { repository ->
        SpeechModelPreparation.prepare(repository, NativeTranscriptionBridge::initialize)
    }

    fun warmUp(repository: SpeechModelRepository) {
        coordinator.warmUp(repository)
    }

    fun prepare(
        repository: SpeechModelRepository,
        retryFailed: Boolean,
    ): Future<Result<File>> = coordinator.prepare(repository, retryFailed)

    fun invalidate() {
        SpeechModelPreparation.invalidate(NativeTranscriptionBridge::reset)
        coordinator.invalidate()
    }
}
