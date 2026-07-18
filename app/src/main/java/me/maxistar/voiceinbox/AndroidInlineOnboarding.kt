package me.maxistar.voiceinbox

import android.content.SharedPreferences
import me.maxistar.voiceinbox.core.FolderSetupSnapshot
import me.maxistar.voiceinbox.core.FolderSetupSnapshotState
import me.maxistar.voiceinbox.core.ModelSetupSnapshot
import me.maxistar.voiceinbox.core.ModelSetupSnapshotState
import me.maxistar.voiceinbox.core.OutputSetupSnapshot
import me.maxistar.voiceinbox.core.OutputSetupSnapshotState
import me.maxistar.voiceinbox.core.TaskActionKind
import me.maxistar.voiceinbox.core.TaskListFilter

enum class AndroidOnboardingHintLifecycle {
    ACTIVE,
    DISMISSED,
    COMPLETED,
}

interface AndroidOnboardingHintStorage {
    fun loadRaw(): String?
    fun saveRaw(value: String)
}

class AndroidOnboardingHintStore(
    private val storage: AndroidOnboardingHintStorage,
) {
    constructor(preferences: SharedPreferences) : this(
        SharedPreferencesAndroidOnboardingHintStorage(preferences),
    )

    fun load(): AndroidOnboardingHintLifecycle = when (storage.loadRaw()) {
        VALUE_DISMISSED -> AndroidOnboardingHintLifecycle.DISMISSED
        VALUE_COMPLETED -> AndroidOnboardingHintLifecycle.COMPLETED
        else -> AndroidOnboardingHintLifecycle.ACTIVE
    }

    fun save(lifecycle: AndroidOnboardingHintLifecycle) {
        storage.saveRaw(
            when (lifecycle) {
                AndroidOnboardingHintLifecycle.ACTIVE -> VALUE_ACTIVE
                AndroidOnboardingHintLifecycle.DISMISSED -> VALUE_DISMISSED
                AndroidOnboardingHintLifecycle.COMPLETED -> VALUE_COMPLETED
            },
        )
    }

    companion object {
        const val PREFERENCES_NAME = "android_inline_onboarding"
        private const val VALUE_ACTIVE = "active"
        private const val VALUE_DISMISSED = "dismissed"
        private const val VALUE_COMPLETED = "completed"
    }
}

private class SharedPreferencesAndroidOnboardingHintStorage(
    private val preferences: SharedPreferences,
) : AndroidOnboardingHintStorage {
    override fun loadRaw(): String? = preferences.getString(KEY_LIFECYCLE, null)

    override fun saveRaw(value: String) {
        preferences.edit().putString(KEY_LIFECYCLE, value).apply()
    }

    companion object {
        private const val KEY_LIFECYCLE = "hint_lifecycle"
    }
}

enum class AndroidOnboardingStepKind {
    MODEL,
    OUTPUT,
    FOLDER,
}

data class AndroidOnboardingChecklistStep(
    val kind: AndroidOnboardingStepKind,
    val label: String,
    val complete: Boolean,
    val optional: Boolean = false,
)

data class AndroidOnboardingHintAction(
    val label: String,
    val enabled: Boolean,
    val kind: TaskActionKind,
)

data class AndroidOnboardingHintPresentation(
    val visible: Boolean = false,
    val title: String = "Set up Voice Inbox",
    val explanation: String = "Follow these steps, or use the setup tasks above in any order.",
    val downloadDisclosure: String? = null,
    val steps: List<AndroidOnboardingChecklistStep> = emptyList(),
    val action: AndroidOnboardingHintAction? = null,
) {
    companion object {
        val HIDDEN = AndroidOnboardingHintPresentation()
    }
}

object AndroidOnboardingHintPresenter {
    fun present(
        lifecycle: AndroidOnboardingHintLifecycle,
        filter: TaskListFilter,
        hydration: AndroidMainScreenHydration,
        model: ModelSetupSnapshot,
        output: OutputSetupSnapshot,
        folder: FolderSetupSnapshot,
    ): AndroidOnboardingHintPresentation {
        if (
            lifecycle != AndroidOnboardingHintLifecycle.ACTIVE ||
            filter != TaskListFilter.NEW ||
            !setupKnown(hydration) ||
            allStepsComplete(model, output, folder)
        ) {
            return AndroidOnboardingHintPresentation.HIDDEN
        }

        val steps = listOf(
            AndroidOnboardingChecklistStep(
                kind = AndroidOnboardingStepKind.MODEL,
                label = "Install speech model",
                complete = model.state == ModelSetupSnapshotState.READY,
            ),
            AndroidOnboardingChecklistStep(
                kind = AndroidOnboardingStepKind.OUTPUT,
                label = "Select transcript output",
                complete = output.state == OutputSetupSnapshotState.READY,
            ),
            AndroidOnboardingChecklistStep(
                kind = AndroidOnboardingStepKind.FOLDER,
                label = "Select audio folder · Optional",
                complete = folder.state == FolderSetupSnapshotState.READY,
                optional = true,
            ),
        )
        val action = nextAction(model, output, folder)
        return AndroidOnboardingHintPresentation(
            visible = true,
            steps = steps,
            downloadDisclosure = "Start setup downloads the speech model to this device."
                .takeIf {
                    action.enabled && action.kind in setOf(
                        TaskActionKind.DOWNLOAD_MODEL,
                        TaskActionKind.RETRY_MODEL_DOWNLOAD,
                    )
                },
            action = action,
        )
    }

    fun shouldComplete(
        lifecycle: AndroidOnboardingHintLifecycle,
        hydration: AndroidMainScreenHydration,
        model: ModelSetupSnapshot,
        output: OutputSetupSnapshot,
        folder: FolderSetupSnapshot,
    ): Boolean = lifecycle == AndroidOnboardingHintLifecycle.ACTIVE &&
        setupKnown(hydration) &&
        allStepsComplete(model, output, folder)

    private fun setupKnown(hydration: AndroidMainScreenHydration): Boolean =
        hydration.modelKnown && hydration.outputKnown && hydration.folderKnown

    private fun allStepsComplete(
        model: ModelSetupSnapshot,
        output: OutputSetupSnapshot,
        folder: FolderSetupSnapshot,
    ): Boolean = model.state == ModelSetupSnapshotState.READY &&
        output.state == OutputSetupSnapshotState.READY &&
        folder.state == FolderSetupSnapshotState.READY

    private fun nextAction(
        model: ModelSetupSnapshot,
        output: OutputSetupSnapshot,
        folder: FolderSetupSnapshot,
    ): AndroidOnboardingHintAction = when {
        model.state == ModelSetupSnapshotState.INSTALLING -> AndroidOnboardingHintAction(
            label = "Installing speech model…",
            enabled = false,
            kind = TaskActionKind.DOWNLOAD_MODEL,
        )
        model.state != ModelSetupSnapshotState.READY && model.downloadAvailable -> AndroidOnboardingHintAction(
            label = if (model.state == ModelSetupSnapshotState.INVALID) "Retry setup" else "Start setup",
            enabled = true,
            kind = if (model.state == ModelSetupSnapshotState.INVALID) {
                TaskActionKind.RETRY_MODEL_DOWNLOAD
            } else {
                TaskActionKind.DOWNLOAD_MODEL
            },
        )
        model.state != ModelSetupSnapshotState.READY -> AndroidOnboardingHintAction(
            label = "Install model from folder",
            enabled = true,
            kind = TaskActionKind.IMPORT_MODEL,
        )
        output.state != OutputSetupSnapshotState.READY -> AndroidOnboardingHintAction(
            label = "Select output file",
            enabled = true,
            kind = TaskActionKind.SELECT_OUTPUT,
        )
        else -> AndroidOnboardingHintAction(
            label = "Select audio folder (optional)",
            enabled = folder.state != FolderSetupSnapshotState.SCANNING,
            kind = TaskActionKind.SELECT_FOLDER,
        )
    }
}
