package me.maxistar.voiceinbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceKeyboardBackspaceRepeaterTest {
    @Test
    fun startsWithAnImmediateDeletionAndRepeatsAtTheConfiguredCadence() {
        val scheduler = FakeScheduler()
        var deletions = 0
        val repeater = VoiceKeyboardBackspaceRepeater(
            scheduler = scheduler,
            deleteOne = { deletions += 1; true },
            repeatIntervalMillis = 55L,
        )

        repeater.start()
        scheduler.runNext()
        scheduler.runNext()

        assertEquals(3, deletions)
        assertEquals(listOf(55L, 55L, 55L), scheduler.scheduledDelays)
        assertTrue(repeater.isRepeating())
    }

    @Test
    fun cancellationIsIdempotentAndPreventsFurtherDeletion() {
        val scheduler = FakeScheduler()
        var deletions = 0
        val repeater = VoiceKeyboardBackspaceRepeater(scheduler, { deletions += 1; true })

        repeater.start()
        repeater.cancel()
        repeater.cancel()
        scheduler.runNext()

        assertEquals(1, deletions)
        assertFalse(repeater.isRepeating())
    }

    @Test
    fun failedDeletionStopsRepeating() {
        val scheduler = FakeScheduler()
        var attempts = 0
        val repeater = VoiceKeyboardBackspaceRepeater(
            scheduler = scheduler,
            deleteOne = { attempts += 1; attempts == 1 },
        )

        repeater.start()
        scheduler.runNext()

        assertEquals(2, attempts)
        assertFalse(repeater.isRepeating())
        assertFalse(scheduler.hasScheduledWork())
    }

    private class FakeScheduler : VoiceKeyboardRepeatScheduler {
        private val pending = mutableListOf<Runnable>()
        val scheduledDelays = mutableListOf<Long>()

        override fun postDelayed(runnable: Runnable, delayMillis: Long) {
            pending += runnable
            scheduledDelays += delayMillis
        }

        override fun removeCallbacks(runnable: Runnable) {
            pending.removeAll { it === runnable }
        }

        fun runNext() {
            pending.removeFirstOrNull()?.run()
        }

        fun hasScheduledWork(): Boolean = pending.isNotEmpty()
    }
}
