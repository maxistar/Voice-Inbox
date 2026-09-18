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
    KEYBOARD,
}

data class AndroidOnboardingChecklistStep(
    val kind: AndroidOnboardingStepKind,
    val labelRes: Int,
    val complete: Boolean,
    val optional: Boolean = false,
)

data class AndroidOnboardingHintAction(
    val labelRes: Int,
    val enabled: Boolean,
    val kind: TaskActionKind,
)

data class AndroidOnboardingHintPresentation(
    val visible: Boolean = false,
    val titleRes: Int = R.string.onboarding_title,
    val explanationRes: Int = R.string.onboarding_explanation,
    val downloadDisclosureRes: Int? = null,
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
        keyboardStatus: AndroidVoiceKeyboardStatus,
        keyboardKnown: Boolean,
    ): AndroidOnboardingHintPresentation {
        if (
            lifecycle != AndroidOnboardingHintLifecycle.ACTIVE ||
            filter != TaskListFilter.NEW ||
            !setupKnown(hydration, keyboardKnown) ||
            allStepsComplete(model, output, folder, keyboardStatus)
        ) {
            return AndroidOnboardingHintPresentation.HIDDEN
        }

        val steps = listOf(
            AndroidOnboardingChecklistStep(
                kind = AndroidOnboardingStepKind.MODEL,
                labelRes = R.string.onboarding_step_model,
                complete = model.state == ModelSetupSnapshotState.READY,
            ),
            AndroidOnboardingChecklistStep(
                kind = AndroidOnboardingStepKind.OUTPUT,
                labelRes = R.string.onboarding_step_output,
                complete = output.state == OutputSetupSnapshotState.READY,
                optional = true,
            ),
            AndroidOnboardingChecklistStep(
                kind = AndroidOnboardingStepKind.FOLDER,
                labelRes = R.string.onboarding_step_folder,
                complete = folder.state == FolderSetupSnapshotState.READY,
                optional = true,
            ),
            AndroidOnboardingChecklistStep(
                kind = AndroidOnboardingStepKind.KEYBOARD,
                labelRes = R.string.onboarding_step_keyboard,
                complete = keyboardStatus != AndroidVoiceKeyboardStatus.DISABLED,
                optional = true,
            ),
        )
        val action = nextAction(model, output, folder, keyboardStatus)
        return AndroidOnboardingHintPresentation(
            visible = true,
            steps = steps,
            downloadDisclosureRes = R.string.onboarding_download_disclosure
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
        keyboardStatus: AndroidVoiceKeyboardStatus,
        keyboardKnown: Boolean,
    ): Boolean = lifecycle == AndroidOnboardingHintLifecycle.ACTIVE &&
        setupKnown(hydration, keyboardKnown) &&
        allStepsComplete(model, output, folder, keyboardStatus)

    private fun setupKnown(
        hydration: AndroidMainScreenHydration,
        keyboardKnown: Boolean,
    ): Boolean = hydration.modelKnown && hydration.outputKnown && hydration.folderKnown && keyboardKnown

    private fun allStepsComplete(
        model: ModelSetupSnapshot,
        output: OutputSetupSnapshot,
        folder: FolderSetupSnapshot,
        keyboardStatus: AndroidVoiceKeyboardStatus,
    ): Boolean =
        model.state == ModelSetupSnapshotState.READY &&
            output.state == OutputSetupSnapshotState.READY &&
            folder.state == FolderSetupSnapshotState.READY &&
            keyboardStatus != AndroidVoiceKeyboardStatus.DISABLED

    private fun nextAction(
        model: ModelSetupSnapshot,
        output: OutputSetupSnapshot,
        folder: FolderSetupSnapshot,
        keyboardStatus: AndroidVoiceKeyboardStatus,
    ): AndroidOnboardingHintAction = when {
        model.state == ModelSetupSnapshotState.INSTALLING -> AndroidOnboardingHintAction(
            labelRes = R.string.onboarding_action_installing,
            enabled = false,
            kind = TaskActionKind.DOWNLOAD_MODEL,
        )
        model.state != ModelSetupSnapshotState.READY && model.downloadAvailable -> AndroidOnboardingHintAction(
            labelRes = if (model.state == ModelSetupSnapshotState.INVALID) R.string.onboarding_action_retry else R.string.onboarding_action_start,
            enabled = true,
            kind = if (model.state == ModelSetupSnapshotState.INVALID) {
                TaskActionKind.RETRY_MODEL_DOWNLOAD
            } else {
                TaskActionKind.DOWNLOAD_MODEL
            },
        )
        model.state != ModelSetupSnapshotState.READY -> AndroidOnboardingHintAction(
            labelRes = R.string.onboarding_action_import_model,
            enabled = true,
            kind = TaskActionKind.IMPORT_MODEL,
        )
        output.state != OutputSetupSnapshotState.READY -> AndroidOnboardingHintAction(
            labelRes = R.string.onboarding_action_select_output,
            enabled = true,
            kind = TaskActionKind.SELECT_OUTPUT,
        )
        folder.state != FolderSetupSnapshotState.READY -> AndroidOnboardingHintAction(
            labelRes = R.string.onboarding_action_select_folder,
            enabled = true,
            kind = TaskActionKind.SELECT_FOLDER,
        )
        keyboardStatus == AndroidVoiceKeyboardStatus.DISABLED -> AndroidOnboardingHintAction(
            labelRes = R.string.onboarding_action_enable_keyboard,
            enabled = true,
            kind = TaskActionKind.ENABLE_VOICE_KEYBOARD,
        )
        else -> AndroidOnboardingHintAction(
            labelRes = R.string.onboarding_action_ready,
            enabled = false,
            kind = TaskActionKind.IMPORT_AUDIO,
        )
    }
}
