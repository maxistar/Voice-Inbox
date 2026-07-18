package me.maxistar.voiceinbox.core

enum class TaskListFilter {
    NEW,
    PROCESSED,
    ALL,
}

enum class SetupTaskKind {
    MODEL,
    OUTPUT,
    FOLDER,
}

enum class SetupTaskState {
    REQUIRED,
    ACTIVE,
    ERROR,
}

enum class AudioTaskState {
    PENDING,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    NO_SPEECH,
}

enum class TaskRetention {
    UNTIL_COMPLETED,
    RETAINED,
}

enum class TaskActionKind {
    DOWNLOAD_MODEL,
    IMPORT_MODEL,
    CANCEL_MODEL_DOWNLOAD,
    RETRY_MODEL_DOWNLOAD,
    SELECT_OUTPUT,
    SELECT_FOLDER,
    REFRESH_FOLDER,
    TRANSCRIBE,
    TRANSCRIBE_ALL,
    RETRY_TRANSCRIPTION,
    PLAY,
    STOP,
    SHOW_TEXT,
    IMPORT_AUDIO,
}

data class TaskActionPresentation(
    val kind: TaskActionKind,
    val label: String,
    val enabled: Boolean = true,
)

data class TaskProgressPresentation(
    val phase: String,
    val percent: Int? = null,
    val processedUs: Long? = null,
    val durationUs: Long? = null,
    val completedFiles: Int? = null,
    val totalFiles: Int? = null,
    val failedFiles: Int? = null,
)

sealed class TaskPresentation {
    abstract val stableId: String
    abstract val title: String
    abstract val detail: String?
    abstract val badge: String
    abstract val progress: TaskProgressPresentation?
    abstract val errorMessage: String?
    abstract val actions: List<TaskActionPresentation>
    abstract val retention: TaskRetention
}

data class SetupTaskPresentation(
    override val stableId: String,
    val kind: SetupTaskKind,
    val state: SetupTaskState,
    override val title: String,
    override val detail: String?,
    override val badge: String,
    override val progress: TaskProgressPresentation?,
    override val errorMessage: String?,
    override val actions: List<TaskActionPresentation>,
    override val retention: TaskRetention = TaskRetention.UNTIL_COMPLETED,
) : TaskPresentation()

data class AudioTaskPresentation(
    override val stableId: String,
    val entryId: Long,
    val state: AudioTaskState,
    override val title: String,
    override val detail: String?,
    override val badge: String,
    override val progress: TaskProgressPresentation?,
    override val errorMessage: String?,
    override val actions: List<TaskActionPresentation>,
    override val retention: TaskRetention = TaskRetention.RETAINED,
) : TaskPresentation()

enum class ModelSetupSnapshotState {
    REQUIRED,
    INSTALLING,
    INVALID,
    READY,
}

data class ModelSetupSnapshot(
    val state: ModelSetupSnapshotState,
    val detail: String? = null,
    val installationPhase: String? = null,
    val progressPercent: Int? = null,
    val downloadAvailable: Boolean = false,
    val canCancel: Boolean = false,
)

enum class OutputSetupSnapshotState {
    REQUIRED,
    INVALID,
    READY,
}

data class OutputSetupSnapshot(
    val state: OutputSetupSnapshotState,
    val detail: String? = null,
)

enum class FolderSetupSnapshotState {
    UNSELECTED,
    READY,
    SCANNING,
    ERROR,
}

data class FolderSetupSnapshot(
    val state: FolderSetupSnapshotState,
    val detail: String? = null,
)

data class AudioTaskSnapshot(
    val entryId: Long,
    val title: String,
    val detail: String? = null,
    val state: AudioFileState,
    val importedAtMillis: Long,
    val terminalAtMillis: Long? = null,
    val lastError: String? = null,
    val hasTranscriptText: Boolean = false,
    val noSpeech: Boolean = false,
    val eligibleForTranscription: Boolean = true,
    val eligibleForPreview: Boolean = true,
)

data class PreviewTaskSnapshot(
    val activeEntryId: Long? = null,
    val state: PreviewPlaybackState = PreviewPlaybackState.IDLE,
)

data class TranscriptionTaskSnapshot(
    val active: Boolean = false,
    val activeEntryId: Long? = null,
    val preparationOwnerEntryId: Long? = null,
    val phase: String? = null,
    val percent: Int? = null,
    val processedUs: Long? = null,
    val durationUs: Long? = null,
    val completedFiles: Int? = null,
    val totalFiles: Int? = null,
    val failedFiles: Int? = null,
    val prerequisiteError: String? = null,
)

data class TaskListInput(
    val filter: TaskListFilter,
    val model: ModelSetupSnapshot,
    val output: OutputSetupSnapshot,
    val folder: FolderSetupSnapshot,
    val audio: List<AudioTaskSnapshot>,
    val preview: PreviewTaskSnapshot = PreviewTaskSnapshot(),
    val transcription: TranscriptionTaskSnapshot = TranscriptionTaskSnapshot(),
)

data class TaskListBatchActionState(
    val visible: Boolean,
    val enabled: Boolean,
    val eligibleCount: Int,
)

data class TaskListState(
    val filter: TaskListFilter,
    val tasks: List<TaskPresentation>,
    val emptyMessage: String?,
    val emptyActions: List<TaskActionPresentation>,
    val batchAction: TaskListBatchActionState,
)

object TaskListPresentationController {
    fun state(input: TaskListInput): TaskListState {
        val setupTasks = setupTasks(input)
        val audioTasks = input.audio
            .filter { visibleInFilter(it.state, input.filter) }
            .sortedWith(audioComparator(input.filter))
            .map { audioTask(it, input) }
        val visibleSetup = if (input.filter == TaskListFilter.PROCESSED) emptyList() else setupTasks
        val tasks = visibleSetup + audioTasks
        val eligibleCount = input.audio.count {
            it.state == AudioFileState.PENDING && it.eligibleForTranscription
        }
        val batchVisible = input.filter == TaskListFilter.NEW && eligibleCount > 0
        return TaskListState(
            filter = input.filter,
            tasks = tasks,
            emptyMessage = if (tasks.isEmpty()) emptyMessage(input.filter) else null,
            emptyActions = if (tasks.isEmpty() && input.filter != TaskListFilter.PROCESSED) {
                buildList {
                    add(TaskActionPresentation(TaskActionKind.IMPORT_AUDIO, "Import Audio Files"))
                    if (input.folder.state == FolderSetupSnapshotState.UNSELECTED) {
                        add(TaskActionPresentation(TaskActionKind.SELECT_FOLDER, "Select Audio Folder"))
                    }
                }
            } else {
                emptyList()
            },
            batchAction = TaskListBatchActionState(
                visible = batchVisible,
                enabled = batchVisible && !input.transcription.active,
                eligibleCount = eligibleCount,
            ),
        )
    }

    fun preparationOwnerEntryId(audio: List<AudioTaskSnapshot>): Long? = audio
        .asSequence()
        .filter { it.state == AudioFileState.PENDING && it.eligibleForTranscription }
        .sortedWith(compareBy<AudioTaskSnapshot>({ it.importedAtMillis }, { it.title.lowercase() }, { it.entryId }))
        .firstOrNull()
        ?.entryId

    private fun setupTasks(input: TaskListInput): List<SetupTaskPresentation> = buildList {
        modelTask(input.model)?.let(::add)
        outputTask(input.output)?.let(::add)
        folderTask(input.folder)?.let(::add)
    }

    private fun modelTask(snapshot: ModelSetupSnapshot): SetupTaskPresentation? {
        if (snapshot.state == ModelSetupSnapshotState.READY) return null
        val active = snapshot.state == ModelSetupSnapshotState.INSTALLING
        val error = snapshot.state == ModelSetupSnapshotState.INVALID
        val actions = when {
            active && snapshot.canCancel -> listOf(
                TaskActionPresentation(TaskActionKind.CANCEL_MODEL_DOWNLOAD, "Cancel"),
            )
            active -> emptyList()
            error -> listOf(
                TaskActionPresentation(TaskActionKind.RETRY_MODEL_DOWNLOAD, "Retry Download", snapshot.downloadAvailable),
                TaskActionPresentation(TaskActionKind.IMPORT_MODEL, "Install Manually"),
            )
            else -> buildList {
                if (snapshot.downloadAvailable) {
                    add(TaskActionPresentation(TaskActionKind.DOWNLOAD_MODEL, "Download Model"))
                }
                add(TaskActionPresentation(TaskActionKind.IMPORT_MODEL, "Install Manually"))
            }
        }
        return SetupTaskPresentation(
            stableId = "setup:model",
            kind = SetupTaskKind.MODEL,
            state = when {
                active -> SetupTaskState.ACTIVE
                error -> SetupTaskState.ERROR
                else -> SetupTaskState.REQUIRED
            },
            title = "Install Speech Model",
            detail = snapshot.detail.takeUnless { active },
            badge = if (active) "Installing" else if (error) "Needs attention" else "Required",
            progress = if (active) {
                TaskProgressPresentation(
                    snapshot.installationPhase?.takeIf(String::isNotBlank) ?: "Installing model",
                    snapshot.progressPercent,
                )
            } else {
                null
            },
            errorMessage = snapshot.detail.takeIf { error },
            actions = actions,
        )
    }

    private fun outputTask(snapshot: OutputSetupSnapshot): SetupTaskPresentation? {
        if (snapshot.state == OutputSetupSnapshotState.READY) return null
        val error = snapshot.state == OutputSetupSnapshotState.INVALID
        return SetupTaskPresentation(
            stableId = "setup:output",
            kind = SetupTaskKind.OUTPUT,
            state = if (error) SetupTaskState.ERROR else SetupTaskState.REQUIRED,
            title = "Select Output File",
            detail = snapshot.detail,
            badge = if (error) "Needs attention" else "Required",
            progress = null,
            errorMessage = snapshot.detail.takeIf { error },
            actions = listOf(TaskActionPresentation(TaskActionKind.SELECT_OUTPUT, "Select Output File")),
        )
    }

    private fun folderTask(snapshot: FolderSetupSnapshot): SetupTaskPresentation? = when (snapshot.state) {
        FolderSetupSnapshotState.UNSELECTED,
        FolderSetupSnapshotState.READY,
        -> null
        FolderSetupSnapshotState.SCANNING -> SetupTaskPresentation(
            stableId = "setup:folder",
            kind = SetupTaskKind.FOLDER,
            state = SetupTaskState.ACTIVE,
            title = "Refresh Audio Folder",
            detail = snapshot.detail,
            badge = "Scanning",
            progress = TaskProgressPresentation("Scanning audio folder"),
            errorMessage = null,
            actions = emptyList(),
        )
        FolderSetupSnapshotState.ERROR -> SetupTaskPresentation(
            stableId = "setup:folder",
            kind = SetupTaskKind.FOLDER,
            state = SetupTaskState.ERROR,
            title = "Restore Audio Folder Access",
            detail = snapshot.detail,
            badge = "Needs attention",
            progress = null,
            errorMessage = snapshot.detail,
            actions = listOf(
                TaskActionPresentation(TaskActionKind.SELECT_FOLDER, "Select Folder"),
                TaskActionPresentation(TaskActionKind.REFRESH_FOLDER, "Retry"),
            ),
        )
    }

    private fun audioTask(snapshot: AudioTaskSnapshot, input: TaskListInput): AudioTaskPresentation {
        val progressOwnerEntryId = input.transcription.activeEntryId
            ?: input.transcription.preparationOwnerEntryId
            ?: preparationOwnerEntryId(input.audio).takeIf {
                input.transcription.active && input.transcription.phase?.contains("model", ignoreCase = true) == true
            }
        val ownsProgress = input.transcription.active && (
            progressOwnerEntryId == snapshot.entryId
            )
        val presentationState = when (snapshot.state) {
            AudioFileState.PENDING -> if (ownsProgress) AudioTaskState.PROCESSING else AudioTaskState.PENDING
            AudioFileState.PROCESSING -> AudioTaskState.PROCESSING
            AudioFileState.PROCESSED -> AudioTaskState.SUCCEEDED
            AudioFileState.FAILED -> if (snapshot.noSpeech) AudioTaskState.NO_SPEECH else AudioTaskState.FAILED
            AudioFileState.MISSING -> AudioTaskState.FAILED
        }
        val isPreviewing = input.preview.activeEntryId == snapshot.entryId &&
            input.preview.state != PreviewPlaybackState.IDLE
        val actions = buildList {
            when (presentationState) {
                AudioTaskState.PENDING -> add(
                    TaskActionPresentation(
                        TaskActionKind.TRANSCRIBE,
                        "Transcribe",
                        snapshot.eligibleForTranscription && !input.transcription.active,
                    ),
                )
                AudioTaskState.FAILED,
                AudioTaskState.NO_SPEECH,
                -> add(
                    TaskActionPresentation(
                        TaskActionKind.RETRY_TRANSCRIPTION,
                        "Retry",
                        snapshot.eligibleForTranscription && !input.transcription.active,
                    ),
                )
                AudioTaskState.SUCCEEDED -> if (snapshot.hasTranscriptText) {
                    add(TaskActionPresentation(TaskActionKind.SHOW_TEXT, "Show Text"))
                }
                AudioTaskState.PROCESSING -> Unit
            }
            add(
                TaskActionPresentation(
                    if (isPreviewing) TaskActionKind.STOP else TaskActionKind.PLAY,
                    if (isPreviewing) "Stop" else "Play",
                    isPreviewing || (snapshot.eligibleForPreview && !input.transcription.active),
                ),
            )
        }
        val prerequisiteError = input.transcription.prerequisiteError
            .takeIf {
                snapshot.state == AudioFileState.PENDING &&
                    (input.transcription.preparationOwnerEntryId ?: preparationOwnerEntryId(input.audio)) == snapshot.entryId
            }
        return AudioTaskPresentation(
            stableId = "audio:${snapshot.entryId}",
            entryId = snapshot.entryId,
            state = presentationState,
            title = snapshot.title,
            detail = snapshot.detail,
            badge = when (presentationState) {
                AudioTaskState.PENDING -> "New"
                AudioTaskState.PROCESSING -> "Processing"
                AudioTaskState.SUCCEEDED -> "Processed"
                AudioTaskState.FAILED -> "Failed"
                AudioTaskState.NO_SPEECH -> "No speech"
            },
            progress = if (ownsProgress) input.transcription.toProgress() else null,
            errorMessage = prerequisiteError ?: snapshot.lastError,
            actions = actions,
        )
    }

    private fun TranscriptionTaskSnapshot.toProgress(): TaskProgressPresentation =
        TaskProgressPresentation(
            phase = phase ?: "Processing",
            percent = percent,
            processedUs = processedUs,
            durationUs = durationUs,
            completedFiles = completedFiles,
            totalFiles = totalFiles,
            failedFiles = failedFiles,
        )

    private fun visibleInFilter(state: AudioFileState, filter: TaskListFilter): Boolean = when (filter) {
        TaskListFilter.NEW -> state == AudioFileState.PENDING || state == AudioFileState.PROCESSING
        TaskListFilter.PROCESSED -> state == AudioFileState.PROCESSED || state == AudioFileState.FAILED
        TaskListFilter.ALL -> true
    }

    private fun audioComparator(filter: TaskListFilter): Comparator<AudioTaskSnapshot> = when (filter) {
        TaskListFilter.PROCESSED -> compareByDescending<AudioTaskSnapshot> {
            it.terminalAtMillis ?: it.importedAtMillis
        }.thenByDescending { it.importedAtMillis }.thenByDescending { it.entryId }
        TaskListFilter.NEW,
        TaskListFilter.ALL,
        -> compareByDescending<AudioTaskSnapshot> { it.importedAtMillis }.thenByDescending { it.entryId }
    }

    private fun emptyMessage(filter: TaskListFilter): String = when (filter) {
        TaskListFilter.NEW -> "No new tasks"
        TaskListFilter.PROCESSED -> "No processed audio files"
        TaskListFilter.ALL -> "No audio tasks"
    }
}
