package me.maxistar.voiceinbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceKeyboardWarmupSchedulerTest {
    @Test
    fun transientViewCancellationPreventsWarmup() {
        val delay = FakeDelayScheduler()
        val scheduler = VoiceKeyboardWarmupScheduler(delay, delayMillis = 400)
        var starts = 0

        scheduler.schedule { starts += 1 }
        assertTrue(scheduler.isScheduled())
        scheduler.cancel()
        delay.runPending()

        assertFalse(scheduler.isScheduled())
        assertEquals(0, starts)
    }

    @Test
    fun reschedulingKeepsOnlyTheNewestViewCallback() {
        val delay = FakeDelayScheduler()
        val scheduler = VoiceKeyboardWarmupScheduler(delay, delayMillis = 400)
        var firstStarts = 0
        var secondStarts = 0

        scheduler.schedule { firstStarts += 1 }
        scheduler.schedule { secondStarts += 1 }
        delay.runPending()

        assertEquals(0, firstStarts)
        assertEquals(1, secondStarts)
        assertFalse(scheduler.isScheduled())
    }

    @Test
    fun dictationCanCancelDelayAndStartPreparationImmediately() {
        val delay = FakeDelayScheduler()
        val scheduler = VoiceKeyboardWarmupScheduler(delay, delayMillis = 400)
        var passiveStarts = 0
        var requestStarts = 0

        scheduler.schedule { passiveStarts += 1 }
        scheduler.cancel()
        requestStarts += 1
        delay.runPending()

        assertEquals(0, passiveStarts)
        assertEquals(1, requestStarts)
    }

    private class FakeDelayScheduler : VoiceKeyboardDelayScheduler {
        private val callbacks = mutableListOf<Runnable>()

        override fun postDelayed(runnable: Runnable, delayMillis: Long) {
            callbacks += runnable
        }

        override fun removeCallbacks(runnable: Runnable) {
            callbacks.remove(runnable)
        }

        fun runPending() {
            callbacks.toList().also { callbacks.clear() }.forEach(Runnable::run)
        }
    }
}
