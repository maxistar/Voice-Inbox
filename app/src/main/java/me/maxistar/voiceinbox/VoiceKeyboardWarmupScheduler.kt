package me.maxistar.voiceinbox

internal interface VoiceKeyboardDelayScheduler {
    fun postDelayed(runnable: Runnable, delayMillis: Long)
    fun removeCallbacks(runnable: Runnable)
}

internal class VoiceKeyboardWarmupScheduler(
    private val scheduler: VoiceKeyboardDelayScheduler,
    private val delayMillis: Long,
) {
    private var pending: Runnable? = null

    fun schedule(action: () -> Unit) {
        cancel()
        lateinit var callback: Runnable
        callback = Runnable {
            if (pending !== callback) return@Runnable
            pending = null
            action()
        }
        pending = callback
        scheduler.postDelayed(callback, delayMillis)
    }

    fun cancel() {
        pending?.let(scheduler::removeCallbacks)
        pending = null
    }

    fun isScheduled(): Boolean = pending != null
}
