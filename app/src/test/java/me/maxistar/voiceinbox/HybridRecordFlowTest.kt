package me.maxistar.voiceinbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridRecordFlowTest {
    @Test
    fun shortTapStartsOnceAndSecondActivationStopsOnce() {
        val flow = FakeHybridRecordFlow()

        flow.down(generation = 1, atMillis = 0)
        flow.preparationCompleted(generation = 1)
        flow.up(generation = 1, atMillis = 100)
        flow.activation(generation = 1)
        flow.activation(generation = 1)

        assertEquals(1, flow.startCount)
        assertEquals(1, flow.stopCount)
        assertEquals(1, flow.transcriptionCount)
    }

    @Test
    fun heldReleaseStopsAndTranscribesExactlyOnce() {
        val flow = FakeHybridRecordFlow()

        flow.down(generation = 1, atMillis = 0)
        flow.preparationCompleted(generation = 1)
        flow.up(generation = 1, atMillis = 500)
        flow.up(generation = 1, atMillis = 700)

        assertEquals(1, flow.startCount)
        assertEquals(1, flow.stopCount)
        assertEquals(1, flow.transcriptionCount)
    }

    @Test
    fun cancellationDiscardsWithoutTranscription() {
        val flow = FakeHybridRecordFlow()

        flow.down(generation = 1, atMillis = 0)
        flow.preparationCompleted(generation = 1)
        flow.cancel(generation = 1)

        assertEquals(1, flow.startCount)
        assertEquals(1, flow.cancelCount)
        assertEquals(0, flow.transcriptionCount)
    }

    @Test
    fun durationAndReleaseRaceHasOneTerminalTransition() {
        val flow = FakeHybridRecordFlow()

        flow.down(generation = 1, atMillis = 0)
        flow.preparationCompleted(generation = 1)
        flow.durationLimit(generation = 1)
        flow.up(generation = 1, atMillis = 800)

        assertEquals(1, flow.stopCount)
        assertEquals(1, flow.transcriptionCount)
    }

    @Test
    fun releasedHoldNeverStartsAfterDelayedPreparation() {
        val flow = FakeHybridRecordFlow()

        flow.down(generation = 1, atMillis = 0)
        flow.up(generation = 1, atMillis = 500)
        flow.preparationCompleted(generation = 1)

        assertEquals(0, flow.startCount)
        assertEquals(1, flow.cancelCount)
    }

    private class FakeHybridRecordFlow {
        private val controller = VoiceKeyboardController()
        private val gestures = HybridRecordGestureCoordinator(HybridRecordGesturePolicy(500))
        private var currentGeneration = 0L
        private var terminal = false
        var startCount = 0
        var stopCount = 0
        var cancelCount = 0
        var transcriptionCount = 0

        fun down(generation: Long, atMillis: Long) {
            currentGeneration = generation
            terminal = false
            assertTrue(gestures.begin(0, generation, atMillis))
            assertTrue(controller.beginPreparation())
        }

        fun preparationCompleted(generation: Long) {
            if (generation != currentGeneration || terminal) return
            when (gestures.preparationAction(generation)) {
                HybridRecordPreparationAction.START_HELD,
                HybridRecordPreparationAction.START_LATCHED,
                -> {
                    assertTrue(controller.recordingStarted())
                    startCount += 1
                }
                HybridRecordPreparationAction.CANCEL,
                HybridRecordPreparationAction.IGNORE,
                -> cancel(generation)
            }
        }

        fun up(generation: Long, atMillis: Long) {
            val release = gestures.release(0, generation, atMillis) ?: return
            if (release.release == HybridRecordRelease.STOP_AND_TRANSCRIBE) stop(generation)
        }

        fun activation(generation: Long) {
            stop(generation)
        }

        fun durationLimit(generation: Long) {
            stop(generation)
        }

        fun cancel(generation: Long) {
            if (generation != currentGeneration || terminal) return
            terminal = true
            cancelCount += 1
            controller.cancel()
            gestures.finish(generation)
        }

        private fun stop(generation: Long) {
            if (generation != currentGeneration || terminal) return
            if (!controller.recordingStopped()) return
            terminal = true
            stopCount += 1
            transcriptionCount += 1
            gestures.finish(generation)
        }
    }
}
