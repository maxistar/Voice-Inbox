package me.maxistar.voiceinbox.core

enum class StartupProcessingPolicy {
    ASK,
    AUTOMATIC,
    LEAVE_QUEUED,
}

data class StartupProcessingInput(
    val policy: StartupProcessingPolicy = StartupProcessingPolicy.ASK,
    val evaluationComplete: Boolean = false,
    val startupScanComplete: Boolean = false,
    val startupScanFailed: Boolean = false,
    val catalogReady: Boolean = false,
    val pendingCount: Int = 0,
    val failedCount: Int = 0,
    val folderReady: Boolean = false,
    val outputReady: Boolean = false,
    val modelInstallationState: SpeechModelInstallationState = SpeechModelInstallationState.NOT_INSTALLED,
    val transcriptionStateKnown: Boolean = false,
    val transcriptionActive: Boolean = false,
)

sealed interface StartupProcessingDecision {
    data object Wait : StartupProcessingDecision

    data class Prompt(val pendingCount: Int) : StartupProcessingDecision

    data class Start(val pendingCount: Int) : StartupProcessingDecision

    data class Finish(val reason: StartupProcessingFinishReason) : StartupProcessingDecision
}

enum class StartupProcessingFinishReason {
    ALREADY_EVALUATED,
    SCAN_FAILED,
    NO_PENDING,
    LEAVE_QUEUED,
}

object StartupProcessingRules {
    fun decide(input: StartupProcessingInput): StartupProcessingDecision {
        if (input.evaluationComplete) {
            return StartupProcessingDecision.Finish(StartupProcessingFinishReason.ALREADY_EVALUATED)
        }
        if (input.startupScanFailed) {
            return StartupProcessingDecision.Finish(StartupProcessingFinishReason.SCAN_FAILED)
        }
        if (!input.startupScanComplete || !input.catalogReady || !input.transcriptionStateKnown) {
            return StartupProcessingDecision.Wait
        }
        if (input.pendingCount <= 0) {
            return StartupProcessingDecision.Finish(StartupProcessingFinishReason.NO_PENDING)
        }
        if (input.policy == StartupProcessingPolicy.LEAVE_QUEUED) {
            return StartupProcessingDecision.Finish(StartupProcessingFinishReason.LEAVE_QUEUED)
        }
        if (!input.folderReady || !input.outputReady || !input.modelInstallationState.isAvailable || input.transcriptionActive) {
            return StartupProcessingDecision.Wait
        }
        return when (input.policy) {
            StartupProcessingPolicy.ASK -> StartupProcessingDecision.Prompt(input.pendingCount)
            StartupProcessingPolicy.AUTOMATIC -> StartupProcessingDecision.Start(input.pendingCount)
            StartupProcessingPolicy.LEAVE_QUEUED -> error("Leave-queued policy is handled above")
        }
    }
}
