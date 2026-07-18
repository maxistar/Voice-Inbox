package me.maxistar.voiceinbox

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.maxistar.voiceinbox.core.AudioCatalogEntry
import me.maxistar.voiceinbox.core.AudioFileState
import me.maxistar.voiceinbox.core.AudioTaskSnapshot
import me.maxistar.voiceinbox.core.FolderSetupSnapshot
import me.maxistar.voiceinbox.core.FolderSetupSnapshotState
import me.maxistar.voiceinbox.core.ModelSetupSnapshot
import me.maxistar.voiceinbox.core.ModelSetupSnapshotState
import me.maxistar.voiceinbox.core.OutputSetupSnapshot
import me.maxistar.voiceinbox.core.OutputSetupSnapshotState
import me.maxistar.voiceinbox.core.PreviewTaskSnapshot
import me.maxistar.voiceinbox.core.TaskListFilter
import me.maxistar.voiceinbox.core.TaskListInput
import me.maxistar.voiceinbox.core.TaskListPresentationController
import me.maxistar.voiceinbox.core.TaskListState
import me.maxistar.voiceinbox.core.TranscriptionTaskSnapshot

data class AndroidMainScreenInput(
    val filter: TaskListFilter = TaskListFilter.NEW,
    val model: ModelSetupSnapshot = ModelSetupSnapshot(ModelSetupSnapshotState.REQUIRED),
    val output: OutputSetupSnapshot = OutputSetupSnapshot(OutputSetupSnapshotState.REQUIRED),
    val folder: FolderSetupSnapshot = FolderSetupSnapshot(FolderSetupSnapshotState.UNSELECTED),
    val entries: List<AudioCatalogEntry> = emptyList(),
    val preview: PreviewTaskSnapshot = PreviewTaskSnapshot(),
    val transcription: TranscriptionTaskSnapshot = TranscriptionTaskSnapshot(),
    val transcriptionEligible: Boolean = false,
    val previewEligible: Boolean = true,
    val importEnabled: Boolean = true,
    val hydration: AndroidMainScreenHydration = AndroidMainScreenHydration(),
    val folderSync: AndroidFolderSyncPresentation = AndroidFolderSyncPresentation(),
    val onboardingLifecycle: AndroidOnboardingHintLifecycle = AndroidOnboardingHintLifecycle.DISMISSED,
)

data class AndroidMainScreenHydration(
    val modelKnown: Boolean = false,
    val outputKnown: Boolean = false,
    val folderKnown: Boolean = false,
    val catalogKnown: Boolean = false,
)

data class AndroidFolderSyncPresentation(
    val visible: Boolean = false,
    val active: Boolean = false,
    val enabled: Boolean = false,
    val accessibilityLabel: String = ACCESSIBILITY_REFRESH,
) {
    companion object {
        const val ACCESSIBILITY_REFRESH = "Refresh audio folder"
        const val ACCESSIBILITY_REFRESHING = "Refreshing audio folder"
    }
}

internal fun androidModelTaskDetail(
    state: ModelSetupSnapshotState,
    message: String,
): String? = message.takeUnless {
    state == ModelSetupSnapshotState.READY || state == ModelSetupSnapshotState.INSTALLING
}

data class AndroidMainScreenState(
    val taskList: TaskListState,
    val entriesById: Map<Long, AudioCatalogEntry>,
    val importEnabled: Boolean,
    val folderSync: AndroidFolderSyncPresentation,
    val onboardingHint: AndroidOnboardingHintPresentation,
) {
    val refreshFolderVisible: Boolean get() = folderSync.visible
    val refreshFolderEnabled: Boolean get() = folderSync.enabled
}

object AndroidTaskListSnapshotMapper {
    fun state(input: AndroidMainScreenInput): AndroidMainScreenState {
        val audio = input.entries.map { entry ->
            AudioTaskSnapshot(
                entryId = entry.id,
                title = entry.displayName,
                detail = entry.fingerprint.sizeBytes?.let(::formatSize),
                state = entry.state,
                importedAtMillis = entry.fingerprint.modifiedMillis ?: entry.id,
                terminalAtMillis = entry.processedAtMillis,
                lastError = entry.lastError,
                hasTranscriptText = !entry.transcriptText.isNullOrBlank(),
                noSpeech = isNoSpeech(entry.lastError),
                eligibleForTranscription = input.transcriptionEligible,
                eligibleForPreview = input.previewEligible,
            )
        }
        val taskList = TaskListPresentationController.state(
                TaskListInput(
                    filter = input.filter,
                    model = input.model.takeIf { input.hydration.modelKnown }
                        ?: ModelSetupSnapshot(ModelSetupSnapshotState.READY),
                    output = input.output.takeIf { input.hydration.outputKnown }
                        ?: OutputSetupSnapshot(OutputSetupSnapshotState.READY),
                    folder = input.folder.takeIf { input.hydration.folderKnown }
                        ?: FolderSetupSnapshot(FolderSetupSnapshotState.READY),
                    audio = audio,
                    preview = input.preview,
                    transcription = input.transcription,
                ),
            ).let { composed ->
                if (input.hydration.catalogKnown) {
                    composed
                } else {
                    composed.copy(emptyMessage = null, emptyActions = emptyList())
                }
            }
        return AndroidMainScreenState(
            taskList = taskList,
            entriesById = input.entries.associateBy(AudioCatalogEntry::id),
            importEnabled = input.importEnabled,
            folderSync = input.folderSync,
            onboardingHint = AndroidOnboardingHintPresenter.present(
                lifecycle = input.onboardingLifecycle,
                filter = input.filter,
                hydration = input.hydration,
                model = input.model,
                output = input.output,
                folder = input.folder,
            ),
        )
    }

    private fun isNoSpeech(message: String?): Boolean =
        message?.contains("no text", ignoreCase = true) == true ||
            message?.contains("no speech", ignoreCase = true) == true

    private fun formatSize(bytes: Long): String =
        if (bytes < 1024 * 1024) {
            "${bytes / 1024} KiB"
        } else {
            "${bytes / (1024 * 1024)} MiB"
        }
}

class AndroidMainScreenStateHost(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private var input = AndroidMainScreenInput(filter = restoredFilter())
    private val mutableState = MutableStateFlow(AndroidTaskListSnapshotMapper.state(input))
    val folderSyncCoordinator = AndroidFolderSyncCoordinator()

    val state: StateFlow<AndroidMainScreenState> = mutableState.asStateFlow()
    val currentInput: AndroidMainScreenInput
        get() = input

    fun update(transform: (AndroidMainScreenInput) -> AndroidMainScreenInput) {
        input = transform(input)
        savedStateHandle[KEY_FILTER] = input.filter.name
        mutableState.value = AndroidTaskListSnapshotMapper.state(input)
    }

    fun replace(input: AndroidMainScreenInput) {
        update { input }
    }

    fun selectFilter(filter: TaskListFilter) {
        update { current -> current.copy(filter = filter) }
    }

    private fun restoredFilter(): TaskListFilter =
        savedStateHandle.get<String>(KEY_FILTER)
            ?.let { stored -> TaskListFilter.entries.firstOrNull { it.name == stored } }
            ?: TaskListFilter.NEW

    companion object {
        private const val KEY_FILTER = "android-task-list-filter"
    }
}
