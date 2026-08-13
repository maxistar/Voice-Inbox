package me.maxistar.voiceinbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridRecordGestureCoordinatorTest {
    private val policy = HybridRecordGesturePolicy(holdThresholdMillis = 500)

    @Test
    fun shortReleaseLatchesRecording() {
        val coordinator = HybridRecordGestureCoordinator(policy)
        assertTrue(coordinator.begin(pointerId = 3, generation = 7, eventTimeMillis = 1_000))

        assertEquals(
            HybridRecordReleaseEvent(7, HybridRecordRelease.LATCH),
            coordinator.release(pointerId = 3, generation = 7, eventTimeMillis = 1_499),
        )
        assertEquals(HybridRecordPreparationAction.START_LATCHED, coordinator.preparationAction(7))
    }

    @Test
    fun releaseAtThresholdStopsAndTranscribes() {
        val coordinator = HybridRecordGestureCoordinator(policy)
        coordinator.begin(pointerId = 1, generation = 2, eventTimeMillis = 100)

        assertEquals(
            HybridRecordRelease.STOP_AND_TRANSCRIBE,
            coordinator.release(pointerId = 1, generation = 2, eventTimeMillis = 600)?.release,
        )
        assertEquals(HybridRecordPreparationAction.CANCEL, coordinator.preparationAction(2))
    }

    @Test
    fun preparationWhilePointerIsDownStartsHeldRecording() {
        val coordinator = HybridRecordGestureCoordinator(policy)
        coordinator.begin(pointerId = 1, generation = 4, eventTimeMillis = 100)

        assertEquals(HybridRecordPreparationAction.START_HELD, coordinator.preparationAction(4))
        assertTrue(coordinator.isPointerDown(4))
    }

    @Test
    fun cancellationBeforePreparationPreventsDelayedStart() {
        val coordinator = HybridRecordGestureCoordinator(policy)
        coordinator.begin(pointerId = 1, generation = 4, eventTimeMillis = 100)

        assertTrue(coordinator.cancel(pointerId = 1, generation = 4))
        assertEquals(HybridRecordPreparationAction.CANCEL, coordinator.preparationAction(4))
    }

    @Test
    fun anotherPointerAndStaleGenerationCannotAlterSession() {
        val coordinator = HybridRecordGestureCoordinator(policy)
        coordinator.begin(pointerId = 1, generation = 4, eventTimeMillis = 100)

        assertFalse(coordinator.begin(pointerId = 2, generation = 5, eventTimeMillis = 101))
        assertNull(coordinator.release(pointerId = 2, generation = 4, eventTimeMillis = 700))
        assertNull(coordinator.release(pointerId = 1, generation = 3, eventTimeMillis = 700))
        assertFalse(coordinator.cancel(pointerId = 2, generation = 4))
        assertEquals(HybridRecordPreparationAction.IGNORE, coordinator.preparationAction(3))
        assertTrue(coordinator.isPointerDown(4))
    }

    @Test
    fun finishedGenerationDoesNotAffectNextRequest() {
        val coordinator = HybridRecordGestureCoordinator(policy)
        coordinator.begin(pointerId = 1, generation = 4, eventTimeMillis = 100)
        coordinator.finish(4)

        assertTrue(coordinator.begin(pointerId = 2, generation = 5, eventTimeMillis = 200))
        assertNull(coordinator.release(pointerId = 1, generation = 4, eventTimeMillis = 800))
        assertTrue(coordinator.isPointerDown(5))
    }
}
