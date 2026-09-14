package me.maxistar.voiceinbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceKeyboardRecordingCountdownTest {
    @Test
    fun startsAtOneMinuteAndFormatsWholeSeconds() {
        val clock = FakeClock()
        val scheduler = FakeScheduler()
        val ticks = mutableListOf<Long>()
        val countdown = countdown(clock, scheduler, onTick = { _, seconds -> ticks += seconds })

        countdown.start(generation = 1)

        assertEquals(listOf(60L), ticks)
        assertEquals(1_000L, scheduler.latest.delayMillis)
        assertEquals("1:00", VoiceKeyboardRecordingCountdown.formatRemaining(60))
        assertEquals("0:59", VoiceKeyboardRecordingCountdown.formatRemaining(59))
    }

    @Test
    fun delayedCallbackDerivesTimeFromDeadlineWithoutDrift() {
        val clock = FakeClock()
        val scheduler = FakeScheduler()
        val ticks = mutableListOf<Long>()
        val countdown = countdown(clock, scheduler, onTick = { _, seconds -> ticks += seconds })

        countdown.start(generation = 1)
        val firstCallback = scheduler.latest.runnable
        clock.nowMillis = 2_501
        firstCallback.run()

        assertEquals(listOf(60L, 58L), ticks)
        assertEquals(499L, scheduler.latest.delayMillis)
        assertEquals(58L, countdown.remainingSeconds())
    }

    @Test
    fun deadlineExpiresExactlyOnce() {
        val clock = FakeClock()
        val scheduler = FakeScheduler()
        val expired = mutableListOf<Long>()
        val countdown = countdown(clock, scheduler, onExpired = { expired += it })

        countdown.start(generation = 7)
        val deadlineCallback = scheduler.latest.runnable
        clock.nowMillis = 60_000
        deadlineCallback.run()
        deadlineCallback.run()

        assertEquals(listOf(7L), expired)
        assertEquals(null, countdown.remainingSeconds())
    }

    @Test
    fun manualStopCancelsExpirationAndStaleCallback() {
        val clock = FakeClock()
        val scheduler = FakeScheduler()
        val expired = mutableListOf<Long>()
        val countdown = countdown(clock, scheduler, onExpired = { expired += it })

        countdown.start(generation = 1)
        val staleCallback = scheduler.latest.runnable
        countdown.cancel()
        clock.nowMillis = 60_000
        staleCallback.run()

        assertTrue(scheduler.wasRemoved(staleCallback))
        assertTrue(expired.isEmpty())
    }

    @Test
    fun staleCallbackCannotUpdateOrExpireLaterRequest() {
        val clock = FakeClock()
        val scheduler = FakeScheduler()
        val ticks = mutableListOf<Pair<Long, Long>>()
        val expired = mutableListOf<Long>()
        val countdown = countdown(
            clock,
            scheduler,
            onTick = { generation, seconds -> ticks += generation to seconds },
            onExpired = { expired += it },
        )

        countdown.start(generation = 1)
        val staleCallback = scheduler.latest.runnable
        countdown.start(generation = 2)
        val currentCallback = scheduler.latest.runnable

        staleCallback.run()
        assertEquals(listOf(1L to 60L, 2L to 60L), ticks)
        assertTrue(expired.isEmpty())

        clock.nowMillis = 60_000
        currentCallback.run()
        assertEquals(listOf(2L), expired)
        assertFalse(scheduler.wasRemoved(currentCallback))
    }

    private fun countdown(
        clock: FakeClock,
        scheduler: FakeScheduler,
        onTick: (Long, Long) -> Unit = { _, _ -> },
        onExpired: (Long) -> Unit = {},
    ) = VoiceKeyboardRecordingCountdown(
        durationMillis = 60_000,
        clock = clock,
        scheduler = scheduler,
        onTick = onTick,
        onExpired = onExpired,
    )

    private class FakeClock(var nowMillis: Long = 0) : VoiceKeyboardMonotonicClock {
        override fun nowMillis(): Long = nowMillis
    }

    private class FakeScheduler : VoiceKeyboardCountdownScheduler {
        data class Scheduled(val runnable: Runnable, val delayMillis: Long)

        private val scheduled = mutableListOf<Scheduled>()
        private val removed = mutableSetOf<Runnable>()
        val latest: Scheduled get() = scheduled.last()

        override fun postDelayed(runnable: Runnable, delayMillis: Long) {
            scheduled += Scheduled(runnable, delayMillis)
        }

        override fun removeCallbacks(runnable: Runnable) {
            removed += runnable
        }

        fun wasRemoved(runnable: Runnable): Boolean = runnable in removed
    }
}
