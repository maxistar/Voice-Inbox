package me.maxistar.voiceinbox

enum class HybridRecordRelease {
    LATCH,
    STOP_AND_TRANSCRIBE,
}

class HybridRecordGesturePolicy(
    private val holdThresholdMillis: Long,
) {
    init {
        require(holdThresholdMillis >= 0) { "Hold threshold must not be negative" }
    }

    fun classify(pressStartedAtMillis: Long, releasedAtMillis: Long): HybridRecordRelease =
        if (releasedAtMillis - pressStartedAtMillis >= holdThresholdMillis) {
            HybridRecordRelease.STOP_AND_TRANSCRIBE
        } else {
            HybridRecordRelease.LATCH
        }
}

enum class HybridRecordPreparationAction {
    START_HELD,
    START_LATCHED,
    CANCEL,
    IGNORE,
}

data class HybridRecordReleaseEvent(
    val generation: Long,
    val release: HybridRecordRelease,
)

/** Owns one touch pointer and keeps gesture decisions independent from Android callbacks. */
class HybridRecordGestureCoordinator(
    private val policy: HybridRecordGesturePolicy,
) {
    private data class Session(
        val pointerId: Int,
        val generation: Long,
        val pressedAtMillis: Long,
        var pointerDown: Boolean = true,
        var release: HybridRecordRelease? = null,
        var cancelled: Boolean = false,
    )

    private var session: Session? = null

    fun begin(pointerId: Int, generation: Long, eventTimeMillis: Long): Boolean {
        if (session != null) return false
        session = Session(pointerId, generation, eventTimeMillis)
        return true
    }

    fun release(pointerId: Int, generation: Long, eventTimeMillis: Long): HybridRecordReleaseEvent? {
        val active = session ?: return null
        if (active.pointerId != pointerId || active.generation != generation || !active.pointerDown) return null
        val release = policy.classify(active.pressedAtMillis, eventTimeMillis)
        active.pointerDown = false
        active.release = release
        return HybridRecordReleaseEvent(generation, release)
    }

    fun cancel(pointerId: Int, generation: Long): Boolean {
        val active = session ?: return false
        if (active.pointerId != pointerId || active.generation != generation) return false
        active.pointerDown = false
        active.cancelled = true
        return true
    }

    fun preparationAction(generation: Long): HybridRecordPreparationAction {
        val active = session ?: return HybridRecordPreparationAction.IGNORE
        if (active.generation != generation) return HybridRecordPreparationAction.IGNORE
        return when {
            active.cancelled -> HybridRecordPreparationAction.CANCEL
            active.pointerDown -> HybridRecordPreparationAction.START_HELD
            active.release == HybridRecordRelease.LATCH -> HybridRecordPreparationAction.START_LATCHED
            else -> HybridRecordPreparationAction.CANCEL
        }
    }

    fun isPointerDown(generation: Long): Boolean = session?.let {
        it.generation == generation && it.pointerDown && !it.cancelled
    } == true

    fun isCurrent(generation: Long): Boolean = session?.generation == generation

    fun finish(generation: Long) {
        if (session?.generation == generation) session = null
    }

    fun clear() {
        session = null
    }
}
