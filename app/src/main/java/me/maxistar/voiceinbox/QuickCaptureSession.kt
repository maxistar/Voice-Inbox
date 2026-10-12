package me.maxistar.voiceinbox

enum class QuickCapturePhase {
    IDLE,
    RECORDING,
    FINALIZING,
    SUCCESS,
    CANCELED,
    ERROR,
}

enum class QuickCaptureStopReason {
    EXPLICIT,
    DEADLINE,
}

data class QuickCaptureSessionState(
    val phase: QuickCapturePhase = QuickCapturePhase.IDLE,
    val generation: Long = 0L,
    val startedAtElapsedMillis: Long? = null,
    val deadlineElapsedMillis: Long? = null,
    val stopReason: QuickCaptureStopReason? = null,
    val errorMessage: String? = null,
) {
    val isActive: Boolean
        get() = phase == QuickCapturePhase.RECORDING || phase == QuickCapturePhase.FINALIZING
}

class QuickCaptureSessionController(
    private val nowElapsedMillis: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val maxDurationMillis: Long = DEFAULT_MAX_DURATION_MILLIS,
) {
    private var current = QuickCaptureSessionState()

    @Synchronized
    fun state(): QuickCaptureSessionState = current

    @Synchronized
    fun start(): QuickCaptureSessionState? {
        if (current.isActive) return null
        val now = nowElapsedMillis()
        val nextGeneration = current.generation + 1
        current = QuickCaptureSessionState(
            phase = QuickCapturePhase.RECORDING,
            generation = nextGeneration,
            startedAtElapsedMillis = now,
            deadlineElapsedMillis = now + maxDurationMillis,
        )
        return current
    }

    @Synchronized
    fun stop(
        generation: Long = current.generation,
        reason: QuickCaptureStopReason = QuickCaptureStopReason.EXPLICIT,
    ): QuickCaptureSessionState? {
        if (current.phase != QuickCapturePhase.RECORDING || current.generation != generation) return null
        current = current.copy(phase = QuickCapturePhase.FINALIZING, stopReason = reason)
        return current
    }

    @Synchronized
    fun deadlineReached(now: Long = nowElapsedMillis()): Boolean =
        current.phase == QuickCapturePhase.RECORDING &&
            current.deadlineElapsedMillis?.let { now >= it } == true

    @Synchronized
    fun complete(generation: Long = current.generation): QuickCaptureSessionState? {
        if (current.phase != QuickCapturePhase.FINALIZING || current.generation != generation) return null
        current = current.copy(
            phase = QuickCapturePhase.SUCCESS,
            errorMessage = null,
        )
        return current
    }

    @Synchronized
    fun cancel(generation: Long = current.generation): QuickCaptureSessionState? {
        if (!current.isActive || current.generation != generation) return null
        current = current.copy(
            phase = QuickCapturePhase.CANCELED,
            stopReason = null,
            errorMessage = null,
        )
        return current
    }

    @Synchronized
    fun fail(message: String, generation: Long = current.generation): QuickCaptureSessionState? {
        if (!current.isActive || current.generation != generation) return null
        current = current.copy(
            phase = QuickCapturePhase.ERROR,
            stopReason = null,
            errorMessage = message,
        )
        return current
    }

    @Synchronized
    fun reset(): QuickCaptureSessionState {
        current = QuickCaptureSessionState(generation = current.generation)
        return current
    }

    companion object {
        const val DEFAULT_MAX_DURATION_MILLIS = 60_000L
    }
}
