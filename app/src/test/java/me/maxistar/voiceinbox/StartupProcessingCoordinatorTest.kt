package me.maxistar.voiceinbox

import me.maxistar.voiceinbox.core.StartupProcessingDecision
import me.maxistar.voiceinbox.core.StartupProcessingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupProcessingCoordinatorTest {
    @Test
    fun callbackOrderingWaitsUntilEveryInputIsReady() {
        val coordinator = StartupProcessingCoordinator()
        coordinator.setFolderReady(true)
        coordinator.setOutputReady(true)
        coordinator.setModelReady(true)
        coordinator.setTranscriptionState(known = true, active = false)

        assertEquals(StartupProcessingDecision.Wait, coordinator.evaluate(StartupProcessingPolicy.ASK))
        assertTrue(coordinator.beginStartupScan(1))
        assertEquals(StartupProcessingDecision.Wait, coordinator.evaluate(StartupProcessingPolicy.ASK))
        coordinator.onStartupCatalogReady(1, pendingCount = 2, failedCount = 0)

        assertEquals(
            StartupProcessingDecision.Prompt(2),
            coordinator.evaluate(StartupProcessingPolicy.ASK),
        )
    }

    @Test
    fun staleStartupGenerationIsIgnored() {
        val coordinator = readyCoordinator(generation = 2, completeCatalog = false)
        coordinator.onStartupCatalogReady(1, pendingCount = 3, failedCount = 0)

        assertEquals(
            StartupProcessingDecision.Wait,
            coordinator.evaluate(StartupProcessingPolicy.AUTOMATIC),
        )
    }

    @Test
    fun delayedPrerequisiteReevaluatesToAutomaticStart() {
        val coordinator = readyCoordinator()
        coordinator.setOutputReady(false)
        assertEquals(
            StartupProcessingDecision.Wait,
            coordinator.evaluate(StartupProcessingPolicy.AUTOMATIC),
        )

        coordinator.setOutputReady(true)
        assertEquals(
            StartupProcessingDecision.Start(1),
            coordinator.evaluate(StartupProcessingPolicy.AUTOMATIC),
        )
        assertTrue(coordinator.isComplete())
    }

    @Test
    fun failedStartupScanCompletesEvaluation() {
        val coordinator = StartupProcessingCoordinator()
        assertTrue(coordinator.beginStartupScan(4))
        coordinator.onStartupScanFailed(4)

        coordinator.evaluate(StartupProcessingPolicy.ASK)

        assertTrue(coordinator.isComplete())
    }

    @Test
    fun restoredPromptDoesNotEmitAnotherPrompt() {
        val original = readyCoordinator()
        assertEquals(
            StartupProcessingDecision.Prompt(1),
            original.evaluate(StartupProcessingPolicy.ASK),
        )

        val restored = StartupProcessingCoordinator.restore(original.savedStage())

        assertEquals(StartupProcessingDecision.Wait, restored.evaluate(StartupProcessingPolicy.ASK))
    }

    @Test
    fun confirmedPromptWaitsForRecreatedReadinessThenStarts() {
        val original = readyCoordinator()
        original.evaluate(StartupProcessingPolicy.ASK)
        val restored = StartupProcessingCoordinator.restore(original.savedStage())
        assertTrue(restored.beginStartupScan(7))
        assertTrue(restored.confirmPrompt())
        assertEquals(StartupProcessingDecision.Wait, restored.evaluate(StartupProcessingPolicy.ASK))

        prepareReady(restored, generation = 7)

        assertEquals(
            StartupProcessingDecision.Start(1),
            restored.evaluate(StartupProcessingPolicy.ASK),
        )
    }

    @Test
    fun declinedPromptIsTerminalAndManualRefreshCannotReopenIt() {
        val coordinator = readyCoordinator()
        coordinator.evaluate(StartupProcessingPolicy.ASK)
        assertTrue(coordinator.declinePrompt())

        coordinator.onCatalogRefreshed(pendingCount = 5, failedCount = 0)

        assertEquals(StartupProcessingDecision.Wait, coordinator.evaluate(StartupProcessingPolicy.ASK))
        assertFalse(coordinator.beginStartupScan(8))
    }

    @Test
    fun aFreshCoordinatorEvaluatesANewLaunch() {
        val previous = readyCoordinator()
        previous.evaluate(StartupProcessingPolicy.LEAVE_QUEUED)
        assertTrue(previous.isComplete())

        val nextLaunch = readyCoordinator()

        assertEquals(
            StartupProcessingDecision.Start(1),
            nextLaunch.evaluate(StartupProcessingPolicy.AUTOMATIC),
        )
    }

    @Test
    fun activeWorkSuppressesHandoffUntilCatalogRefreshes() {
        val coordinator = readyCoordinator()
        coordinator.setTranscriptionState(known = true, active = true)
        assertEquals(
            StartupProcessingDecision.Wait,
            coordinator.evaluate(StartupProcessingPolicy.AUTOMATIC),
        )

        coordinator.setTranscriptionState(known = true, active = false)
        assertEquals(
            StartupProcessingDecision.Wait,
            coordinator.evaluate(StartupProcessingPolicy.AUTOMATIC),
        )
        coordinator.onCatalogRefreshed(pendingCount = 0, failedCount = 0)

        coordinator.evaluate(StartupProcessingPolicy.AUTOMATIC)
        assertTrue(coordinator.isComplete())
    }

    @Test
    fun failedOnlyCatalogDoesNotStart() {
        val coordinator = readyCoordinator(pendingCount = 0, failedCount = 3)

        coordinator.evaluate(StartupProcessingPolicy.AUTOMATIC)

        assertTrue(coordinator.isComplete())
    }

    private fun readyCoordinator(
        generation: Long = 1,
        pendingCount: Int = 1,
        failedCount: Int = 0,
        completeCatalog: Boolean = true,
    ): StartupProcessingCoordinator =
        StartupProcessingCoordinator().also { coordinator ->
            coordinator.beginStartupScan(generation)
            coordinator.setFolderReady(true)
            coordinator.setOutputReady(true)
            coordinator.setModelReady(true)
            coordinator.setTranscriptionState(known = true, active = false)
            if (completeCatalog) {
                coordinator.onStartupCatalogReady(generation, pendingCount, failedCount)
            }
        }

    private fun prepareReady(coordinator: StartupProcessingCoordinator, generation: Long) {
        coordinator.setFolderReady(true)
        coordinator.setOutputReady(true)
        coordinator.setModelReady(true)
        coordinator.setTranscriptionState(known = true, active = false)
        coordinator.onStartupCatalogReady(generation, pendingCount = 1, failedCount = 0)
    }
}
