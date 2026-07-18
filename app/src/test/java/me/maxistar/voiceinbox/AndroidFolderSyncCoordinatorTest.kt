package me.maxistar.voiceinbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidFolderSyncCoordinatorTest {
    @Test
    fun oneGenerationTraversesTheCompletePipeline() {
        val coordinator = AndroidFolderSyncCoordinator()

        val generation = coordinator.begin()
        assertEquals(AndroidFolderSyncPhase.VALIDATING, coordinator.state.phase)
        assertTrue(coordinator.transition(generation, AndroidFolderSyncPhase.QUEUED))
        assertTrue(coordinator.transition(generation, AndroidFolderSyncPhase.SCANNING))
        assertTrue(coordinator.transition(generation, AndroidFolderSyncPhase.REFRESHING_CATALOG))
        assertTrue(coordinator.complete(generation))
        assertEquals(AndroidFolderSyncPhase.IDLE, coordinator.state.phase)
    }

    @Test
    fun newerGenerationRejectsStaleCallbacks() {
        val coordinator = AndroidFolderSyncCoordinator()
        val stale = coordinator.begin()
        val current = coordinator.begin(AndroidFolderSyncPhase.SCANNING)

        assertFalse(coordinator.transition(stale, AndroidFolderSyncPhase.REFRESHING_CATALOG))
        assertFalse(coordinator.complete(stale))
        assertTrue(coordinator.isCurrent(current))
        assertEquals(AndroidFolderSyncPhase.SCANNING, coordinator.state.phase)
    }

    @Test
    fun failureAndResetEndActivityAndInvalidateCallbacks() {
        val coordinator = AndroidFolderSyncCoordinator()
        val failed = coordinator.begin(AndroidFolderSyncPhase.SCANNING)
        assertTrue(coordinator.fail(failed))
        assertFalse(coordinator.state.active)

        val reset = coordinator.begin()
        coordinator.reset()
        assertFalse(coordinator.isCurrent(reset))
        assertFalse(coordinator.state.active)
    }

    @Test
    fun everyActiveStageCanFailAndFastSyncCanFinishBeforeRendering() {
        AndroidFolderSyncPhase.entries
            .filterNot { it == AndroidFolderSyncPhase.IDLE }
            .forEach { phase ->
                val coordinator = AndroidFolderSyncCoordinator()
                val generation = coordinator.begin(phase)

                assertTrue(coordinator.fail(generation))
                assertEquals(AndroidFolderSyncPhase.IDLE, coordinator.state.phase)
            }

        val coordinator = AndroidFolderSyncCoordinator()
        val fast = coordinator.begin()
        assertTrue(coordinator.complete(fast))
        assertFalse(coordinator.state.active)
    }

    @Test
    fun duplicateRequestSupersedesPriorGenerationWithoutCompletingTheNewOne() {
        val coordinator = AndroidFolderSyncCoordinator()
        val first = coordinator.begin(AndroidFolderSyncPhase.QUEUED)
        val duplicate = coordinator.begin(AndroidFolderSyncPhase.QUEUED)

        assertFalse(coordinator.complete(first))
        assertTrue(coordinator.isCurrent(duplicate))
        assertEquals(AndroidFolderSyncPhase.QUEUED, coordinator.state.phase)
    }
}
