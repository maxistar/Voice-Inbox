package me.maxistar.voiceinbox.core

import kotlin.test.Test
import kotlin.test.assertEquals

class StartupProcessingRulesTest {
    @Test
    fun askPromptsWhenStartupStateIsReady() {
        assertEquals(
            StartupProcessingDecision.Prompt(3),
            StartupProcessingRules.decide(readyInput(policy = StartupProcessingPolicy.ASK, pendingCount = 3)),
        )
    }

    @Test
    fun automaticStartsWhenStartupStateIsReady() {
        assertEquals(
            StartupProcessingDecision.Start(2),
            StartupProcessingRules.decide(readyInput(policy = StartupProcessingPolicy.AUTOMATIC, pendingCount = 2)),
        )
    }

    @Test
    fun leaveQueuedFinishesWithoutProcessing() {
        assertEquals(
            StartupProcessingDecision.Finish(StartupProcessingFinishReason.LEAVE_QUEUED),
            StartupProcessingRules.decide(readyInput(policy = StartupProcessingPolicy.LEAVE_QUEUED)),
        )
    }

    @Test
    fun incompleteReadinessWaits() {
        val ready = readyInput()
        val incomplete = listOf(
            ready.copy(startupScanComplete = false),
            ready.copy(catalogReady = false),
            ready.copy(folderReady = false),
            ready.copy(outputReady = false),
            ready.copy(modelReady = false),
            ready.copy(transcriptionStateKnown = false),
        )

        incomplete.forEach { input ->
            assertEquals(StartupProcessingDecision.Wait, StartupProcessingRules.decide(input))
        }
    }

    @Test
    fun unavailablePrerequisiteCanBecomeReadyLater() {
        val missingOutput = readyInput().copy(outputReady = false)
        assertEquals(StartupProcessingDecision.Wait, StartupProcessingRules.decide(missingOutput))
        assertEquals(
            StartupProcessingDecision.Prompt(1),
            StartupProcessingRules.decide(missingOutput.copy(outputReady = true)),
        )
    }

    @Test
    fun emptyAndFailedOnlyCatalogsFinishAsNoPending() {
        val finish = StartupProcessingDecision.Finish(StartupProcessingFinishReason.NO_PENDING)
        assertEquals(finish, StartupProcessingRules.decide(readyInput(pendingCount = 0)))
        assertEquals(finish, StartupProcessingRules.decide(readyInput(pendingCount = 0, failedCount = 4)))
    }

    @Test
    fun activeTranscriptionWaits() {
        assertEquals(
            StartupProcessingDecision.Wait,
            StartupProcessingRules.decide(readyInput(transcriptionActive = true)),
        )
    }

    @Test
    fun scanFailureIsTerminal() {
        assertEquals(
            StartupProcessingDecision.Finish(StartupProcessingFinishReason.SCAN_FAILED),
            StartupProcessingRules.decide(StartupProcessingInput(startupScanFailed = true)),
        )
    }

    @Test
    fun completedEvaluationIsIdempotent() {
        assertEquals(
            StartupProcessingDecision.Finish(StartupProcessingFinishReason.ALREADY_EVALUATED),
            StartupProcessingRules.decide(readyInput(evaluationComplete = true)),
        )
    }

    private fun readyInput(
        policy: StartupProcessingPolicy = StartupProcessingPolicy.ASK,
        pendingCount: Int = 1,
        failedCount: Int = 0,
        transcriptionActive: Boolean = false,
        evaluationComplete: Boolean = false,
    ): StartupProcessingInput =
        StartupProcessingInput(
            policy = policy,
            evaluationComplete = evaluationComplete,
            startupScanComplete = true,
            catalogReady = true,
            pendingCount = pendingCount,
            failedCount = failedCount,
            folderReady = true,
            outputReady = true,
            modelReady = true,
            transcriptionStateKnown = true,
            transcriptionActive = transcriptionActive,
        )
}
