package me.maxistar.voiceinbox

fun interface VoiceKeyboardMonotonicClock {
    fun nowMillis(): Long
}

interface VoiceKeyboardCountdownScheduler {
    fun postDelayed(runnable: Runnable, delayMillis: Long)
    fun removeCallbacks(runnable: Runnable)
}

class VoiceKeyboardRecordingCountdown(
    private val durationMillis: Long,
    private val clock: VoiceKeyboardMonotonicClock,
    private val scheduler: VoiceKeyboardCountdownScheduler,
    private val onTick: (generation: Long, remainingSeconds: Long) -> Unit,
    private val onExpired: (generation: Long) -> Unit,
) {
    private var activeCallback: Runnable? = null
    private var activeGeneration: Long? = null
    private var deadlineMillis: Long? = null

    fun start(generation: Long) {
        cancel()
        activeGeneration = generation
        deadlineMillis = clock.nowMillis() + durationMillis
        update(generation)
    }

    fun cancel() {
        activeCallback?.let(scheduler::removeCallbacks)
        activeCallback = null
        activeGeneration = null
        deadlineMillis = null
    }

    fun remainingSeconds(): Long? {
        val deadline = deadlineMillis ?: return null
        return snapshot(deadline, clock.nowMillis()).remainingSeconds
    }

    private fun update(generation: Long) {
        if (activeGeneration != generation) return
        val deadline = deadlineMillis ?: return
        val snapshot = snapshot(deadline, clock.nowMillis())
        if (snapshot.expired) {
            activeCallback = null
            activeGeneration = null
            deadlineMillis = null
            onExpired(generation)
            return
        }

        onTick(generation, snapshot.remainingSeconds)
        lateinit var callback: Runnable
        callback = Runnable {
            if (activeCallback !== callback || activeGeneration != generation) return@Runnable
            activeCallback = null
            update(generation)
        }
        activeCallback = callback
        scheduler.postDelayed(callback, snapshot.delayUntilNextUpdateMillis)
    }

    companion object {
        internal data class Snapshot(
            val remainingSeconds: Long,
            val delayUntilNextUpdateMillis: Long,
            val expired: Boolean,
        )

        internal fun snapshot(deadlineMillis: Long, nowMillis: Long): Snapshot {
            val remainingMillis = deadlineMillis - nowMillis
            if (remainingMillis <= 0) return Snapshot(0, 0, expired = true)
            val remainingSeconds = (remainingMillis + 999) / 1_000
            val delayUntilNextUpdate = remainingMillis - ((remainingSeconds - 1) * 1_000)
            return Snapshot(
                remainingSeconds = remainingSeconds,
                delayUntilNextUpdateMillis = delayUntilNextUpdate.coerceAtLeast(1),
                expired = false,
            )
        }

        fun formatRemaining(remainingSeconds: Long): String {
            val minutes = remainingSeconds / 60
            val seconds = (remainingSeconds % 60).toString().padStart(2, '0')
            return "$minutes:$seconds"
        }
    }
}
