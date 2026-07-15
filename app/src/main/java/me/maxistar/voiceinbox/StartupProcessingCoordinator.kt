package me.maxistar.voiceinbox

import me.maxistar.voiceinbox.core.StartupProcessingDecision
import me.maxistar.voiceinbox.core.StartupProcessingInput
import me.maxistar.voiceinbox.core.StartupProcessingPolicy
import me.maxistar.voiceinbox.core.StartupProcessingRules

class StartupProcessingCoordinator private constructor(
    private var stage: Stage,
) {
    private var startupScanGeneration: Long? = null
    private var startupScanComplete = false
    private var startupScanFailed = false
    private var catalogReady = false
    private var pendingCount = 0
    private var failedCount = 0
    private var folderReady = false
    private var outputReady = false
    private var modelReady = false
    private var transcriptionStateKnown = false
    private var transcriptionActive = false

    constructor() : this(Stage.ACTIVE)

    fun beginStartupScan(generation: Long): Boolean {
        if (stage == Stage.COMPLETE) return false
        startupScanGeneration = generation
        startupScanComplete = false
        startupScanFailed = false
        catalogReady = false
        pendingCount = 0
        failedCount = 0
        return true
    }

    fun onStartupCatalogReady(
        generation: Long,
        pendingCount: Int,
        failedCount: Int,
    ) {
        if (generation != startupScanGeneration || stage == Stage.COMPLETE) return
        startupScanComplete = true
        startupScanFailed = false
        catalogReady = true
        this.pendingCount = pendingCount.coerceAtLeast(0)
        this.failedCount = failedCount.coerceAtLeast(0)
    }

    fun onCatalogRefreshed(pendingCount: Int, failedCount: Int) {
        if (!startupScanComplete || stage == Stage.COMPLETE) return
        catalogReady = true
        this.pendingCount = pendingCount.coerceAtLeast(0)
        this.failedCount = failedCount.coerceAtLeast(0)
    }

    fun onStartupScanFailed(generation: Long) {
        if (generation != startupScanGeneration || stage == Stage.COMPLETE) return
        startupScanComplete = false
        startupScanFailed = true
        catalogReady = false
    }

    fun setFolderReady(ready: Boolean) {
        folderReady = ready
    }

    fun setOutputReady(ready: Boolean) {
        outputReady = ready
    }

    fun setModelReady(ready: Boolean) {
        modelReady = ready
    }

    fun setTranscriptionState(known: Boolean, active: Boolean) {
        if (transcriptionStateKnown && transcriptionActive && known && !active) {
            catalogReady = false
        }
        transcriptionStateKnown = known
        transcriptionActive = active
    }

    fun evaluate(policy: StartupProcessingPolicy): StartupProcessingDecision {
        if (stage == Stage.COMPLETE || stage == Stage.PROMPTING) {
            return StartupProcessingDecision.Wait
        }
        val decision = StartupProcessingRules.decide(
            StartupProcessingInput(
                policy = if (stage == Stage.CONFIRMED) StartupProcessingPolicy.AUTOMATIC else policy,
                startupScanComplete = startupScanComplete,
                startupScanFailed = startupScanFailed,
                catalogReady = catalogReady,
                pendingCount = pendingCount,
                failedCount = failedCount,
                folderReady = folderReady,
                outputReady = outputReady,
                modelReady = modelReady,
                transcriptionStateKnown = transcriptionStateKnown,
                transcriptionActive = transcriptionActive,
            ),
        )
        when (decision) {
            is StartupProcessingDecision.Prompt -> stage = Stage.PROMPTING
            is StartupProcessingDecision.Start,
            is StartupProcessingDecision.Finish,
            -> stage = Stage.COMPLETE
            StartupProcessingDecision.Wait -> Unit
        }
        return decision
    }

    fun confirmPrompt(): Boolean {
        if (stage != Stage.PROMPTING) return false
        stage = Stage.CONFIRMED
        return true
    }

    fun declinePrompt(): Boolean {
        if (stage != Stage.PROMPTING) return false
        stage = Stage.COMPLETE
        return true
    }

    fun savedStage(): String = stage.name

    fun isComplete(): Boolean = stage == Stage.COMPLETE

    companion object {
        fun restore(savedStage: String?): StartupProcessingCoordinator =
            StartupProcessingCoordinator(
                savedStage
                    ?.let { stored -> Stage.entries.firstOrNull { it.name == stored } }
                    ?: Stage.ACTIVE,
            )
    }

    private enum class Stage {
        ACTIVE,
        PROMPTING,
        CONFIRMED,
        COMPLETE,
    }
}
