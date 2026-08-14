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
        flow.up(generation = 1, atMillis = 100)
        flow.activation(generation = 1)
        flow.modelReady(generation = 1)

        assertEquals(1, flow.startCount)
        assertEquals(1, flow.stopCount)
        assertEquals(1, flow.transcriptionCount)
    }

    @Test
    fun heldReleaseStopsAndTranscribesExactlyOnce() {
        val flow = FakeHybridRecordFlow()

        flow.down(generation = 1, atMillis = 0)
        flow.up(generation = 1, atMillis = 500)
        flow.modelReady(generation = 1)
        flow.up(generation = 1, atMillis = 700)

        assertEquals(1, flow.startCount)
        assertEquals(1, flow.stopCount)
        assertEquals(1, flow.transcriptionCount)
    }

    @Test
    fun cancellationDiscardsWithoutTranscription() {
        val flow = FakeHybridRecordFlow()

        flow.down(generation = 1, atMillis = 0)
        flow.cancel(generation = 1)
        flow.modelReady(generation = 1)

        assertEquals(1, flow.startCount)
        assertEquals(1, flow.cancelCount)
        assertEquals(0, flow.transcriptionCount)
    }

    @Test
    fun durationAndReleaseRaceHasOneTerminalTransition() {
        val flow = FakeHybridRecordFlow()

        flow.down(generation = 1, atMillis = 0)
        flow.durationLimit(generation = 1)
        flow.modelReady(generation = 1)
        flow.up(generation = 1, atMillis = 800)

        assertEquals(1, flow.stopCount)
        assertEquals(1, flow.transcriptionCount)
    }

    @Test
    fun recordingStopsBeforeModelAndTranscribesOnlyAfterPreparation() {
        val flow = FakeHybridRecordFlow()

        flow.down(generation = 1, atMillis = 0)
        flow.up(generation = 1, atMillis = 500)

        assertEquals(1, flow.startCount)
        assertEquals(1, flow.stopCount)
        assertEquals(0, flow.transcriptionCount)

        flow.modelReady(generation = 1)

        assertEquals(1, flow.transcriptionCount)
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
            assertTrue(controller.beginRecording())
            startCount += 1
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

        fun modelReady(generation: Long) {
            if (generation != currentGeneration || terminal) return
            if (!controller.modelReady()) return
            terminal = true
            transcriptionCount += 1
            gestures.finish(generation)
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
            stopCount += 1
        }
    }
}
