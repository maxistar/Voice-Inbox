package me.maxistar.voiceinbox

internal interface VoiceKeyboardRepeatScheduler {
    fun postDelayed(runnable: Runnable, delayMillis: Long)
    fun removeCallbacks(runnable: Runnable)
}

internal class VoiceKeyboardBackspaceRepeater(
    private val scheduler: VoiceKeyboardRepeatScheduler,
    private val deleteOne: () -> Boolean,
    private val repeatIntervalMillis: Long = DEFAULT_REPEAT_INTERVAL_MILLIS,
) {
    private var repeating = false

    private val repeatRunnable = object : Runnable {
        override fun run() {
            if (!repeating) return
            if (!deleteOne()) {
                cancel()
                return
            }
            scheduler.postDelayed(this, repeatIntervalMillis)
        }
    }

    fun start() {
        if (repeating) return
        repeating = true
        if (!deleteOne()) {
            cancel()
            return
        }
        scheduler.postDelayed(repeatRunnable, repeatIntervalMillis)
    }

    fun cancel() {
        if (!repeating) return
        repeating = false
        scheduler.removeCallbacks(repeatRunnable)
    }

    fun isRepeating(): Boolean = repeating

    private companion object {
        const val DEFAULT_REPEAT_INTERVAL_MILLIS = 55L
    }
}
