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
    SELECT_DOWNLOAD_MODEL,
    IMPORT_MODEL,
    CANCEL_MODEL_DOWNLOAD,
    RETRY_MODEL_DOWNLOAD,
    CREATE_OUTPUT,
    SELECT_OUTPUT,
    HIDE_OUTPUT,
    SELECT_FOLDER,
    REFRESH_FOLDER,
    TRANSCRIBE,
    TRANSCRIBE_ALL,
    RETRY_TRANSCRIPTION,
    PLAY,
    STOP,
    SHOW_TEXT,
    IMPORT_AUDIO,
    ENABLE_VOICE_KEYBOARD,
    CHOOSE_VOICE_KEYBOARD,
    OPEN_VOICE_KEYBOARD_DOCUMENTATION,
}

/**
 * Stable, locale-neutral identifiers for copy owned by the task-list presentation.
 *
 * [fallback] keeps iOS English-only until it adopts a native localization layer;
 * Android resolves [TaskText.key] through its resources instead.
 */
enum class TaskTextKey {
    OPAQUE,
    INSTALL_SPEECH_MODEL,
    AUTOMATIC_TRANSCRIPT_EXPORT,
    OUTPUT_OPTIONAL_DETAIL,
    REFRESH_AUDIO_FOLDER,
    RESTORE_AUDIO_FOLDER_ACCESS,
    REQUIRED,
    OPTIONAL,
    INSTALLING,
    SCANNING,
    NEEDS_ATTENTION,
    NEW,
    PROCESSING,
    PROCESSED,
    FAILED,
    NO_SPEECH,
    CANCEL,
    RETRY_DOWNLOAD,
    DOWNLOAD,
    CREATE_NEW,
    CHOOSE_EXISTING,
    HIDE,
    SELECT_FOLDER,
    RETRY,
    TRANSCRIBE,
    SHOW_TEXT,
    STOP,
    PLAY,
    IMPORT_AUDIO_FILES,
    SELECT_AUDIO_FOLDER,
    NO_NEW_TASKS,
    NO_PROCESSED_AUDIO,
    NO_TASKS,
    INSTALLING_MODEL,
    SCANNING_AUDIO_FOLDER,
    PREPARING_SPEECH_MODEL,
    CHOOSE_MODEL,
    SELECTED_MODEL_DETAIL,
}

data class TaskText(
    val key: TaskTextKey,
    val fallback: String,
    val arguments: List<String> = emptyList(),
)

private fun taskText(key: TaskTextKey, fallback: String, vararg arguments: String) =
    TaskText(key, fallback, arguments.toList())

private fun opaqueTaskText(value: String) = taskText(TaskTextKey.OPAQUE, value, value)

data class TaskActionPresentation(
    val kind: TaskActionKind,
    val text: TaskText,
    val enabled: Boolean = true,
)

data class TaskProgressPresentation(
    val phase: TaskText,
    val percent: Int? = null,
    val processedUs: Long? = null,
    val durationUs: Long? = null,
    val completedFiles: Int? = null,
    val totalFiles: Int? = null,
    val failedFiles: Int? = null,
)

sealed class TaskPresentation {
    abstract val stableId: String
    abstract val title: TaskText
    abstract val detail: TaskText?
    abstract val badge: TaskText
    abstract val progress: TaskProgressPresentation?
    abstract val errorMessage: TaskText?
    abstract val actions: List<TaskActionPresentation>
    abstract val retention: TaskRetention
}

data class SetupTaskPresentation(
    override val stableId: String,
    val kind: SetupTaskKind,
    val state: SetupTaskState,
    override val title: TaskText,
    override val detail: TaskText?,
    override val badge: TaskText,
    override val progress: TaskProgressPresentation?,
    override val errorMessage: TaskText?,
    override val actions: List<TaskActionPresentation>,
    override val retention: TaskRetention = TaskRetention.UNTIL_COMPLETED,
) : TaskPresentation()

data class AudioTaskPresentation(
    override val stableId: String,
    val entryId: Long,
    val state: AudioTaskState,
    override val title: TaskText,
    override val detail: TaskText?,
    override val badge: TaskText,
    override val progress: TaskProgressPresentation?,
    override val errorMessage: TaskText?,
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
    val selectedModel: SpeechModelPackageIdentity? = null,
    val downloadChoices: List<SpeechModelDownloadChoice> = emptyList(),
)

data class SpeechModelDownloadChoice(
    val identity: SpeechModelPackageIdentity,
    val displayName: String,
    val languageSummary: String,
    val maturity: String,
    val downloadBytes: Long,
    val requiredStorageBytes: Long,
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
    val showOutputTask: Boolean = true,
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
    val emptyMessage: TaskText?,
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
                    add(TaskActionPresentation(TaskActionKind.IMPORT_AUDIO, taskText(TaskTextKey.IMPORT_AUDIO_FILES, "Import Audio Files")))
                    if (input.folder.state == FolderSetupSnapshotState.UNSELECTED) {
                        add(TaskActionPresentation(TaskActionKind.SELECT_FOLDER, taskText(TaskTextKey.SELECT_AUDIO_FOLDER, "Select Audio Folder")))
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
        if (input.showOutputTask) {
            outputTask(input.output)?.let(::add)
        }
        folderTask(input.folder)?.let(::add)
    }

    private fun modelTask(snapshot: ModelSetupSnapshot): SetupTaskPresentation? {
        if (snapshot.state == ModelSetupSnapshotState.READY) return null
        val active = snapshot.state == ModelSetupSnapshotState.INSTALLING
        val error = snapshot.state == ModelSetupSnapshotState.INVALID
        val actions = when {
            active && snapshot.canCancel -> listOf(
                TaskActionPresentation(TaskActionKind.CANCEL_MODEL_DOWNLOAD, taskText(TaskTextKey.CANCEL, "Cancel")),
            )
            active -> emptyList()
            error -> listOf(
                TaskActionPresentation(
                    TaskActionKind.SELECT_DOWNLOAD_MODEL,
                    selectedModelLabel(snapshot),
                    snapshot.downloadChoices.isNotEmpty(),
                ),
                TaskActionPresentation(TaskActionKind.RETRY_MODEL_DOWNLOAD, taskText(TaskTextKey.RETRY_DOWNLOAD, "Retry Download"), snapshot.downloadAvailable),
            )
            else -> buildList {
                if (snapshot.downloadChoices.size > 1) {
                    add(TaskActionPresentation(TaskActionKind.SELECT_DOWNLOAD_MODEL, selectedModelLabel(snapshot)))
                }
                if (snapshot.downloadAvailable) {
                    add(TaskActionPresentation(TaskActionKind.DOWNLOAD_MODEL, taskText(TaskTextKey.DOWNLOAD, "Download")))
                }
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
            title = taskText(TaskTextKey.INSTALL_SPEECH_MODEL, "Install Speech Model"),
            detail = selectedModelDetail(snapshot) ?: snapshot.detail.takeUnless { active }?.let(::opaqueTaskText),
            badge = if (active) taskText(TaskTextKey.INSTALLING, "Installing") else if (error) taskText(TaskTextKey.NEEDS_ATTENTION, "Needs attention") else taskText(TaskTextKey.REQUIRED, "Required"),
            progress = if (active) {
                TaskProgressPresentation(
                    snapshot.installationPhase?.takeIf(String::isNotBlank)?.let(::opaqueTaskText)
                        ?: taskText(TaskTextKey.INSTALLING_MODEL, "Installing model"),
                    snapshot.progressPercent,
                )
            } else {
                null
            },
            errorMessage = snapshot.detail.takeIf { error }?.let(::opaqueTaskText),
            actions = actions,
        )
    }

    private fun selectedModelDetail(snapshot: ModelSetupSnapshot): TaskText? {
        val selected = snapshot.selectedModel ?: return null
        val choice = snapshot.downloadChoices.firstOrNull { it.identity == selected } ?: return null
        val download = formatBytes(choice.downloadBytes)
        val storage = formatBytes(choice.requiredStorageBytes)
        return taskText(
            TaskTextKey.SELECTED_MODEL_DETAIL,
            "${choice.displayName} · ${choice.languageSummary} · ${choice.maturity} · $download download · $storage free",
            choice.displayName,
            choice.languageSummary,
            choice.maturity,
            download,
            storage,
        )
    }

    private fun selectedModelLabel(snapshot: ModelSetupSnapshot): TaskText =
        snapshot.selectedModel
            ?.let { selected -> snapshot.downloadChoices.firstOrNull { it.identity == selected } }
            ?.displayName
            ?.let(::opaqueTaskText)
            ?: taskText(TaskTextKey.CHOOSE_MODEL, "Choose Model")

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> "${bytes / (1024L * 1024L * 1024L)} GB"
        else -> "${bytes / (1024L * 1024L)} MB"
    }

    private fun outputTask(snapshot: OutputSetupSnapshot): SetupTaskPresentation? {
        if (snapshot.state == OutputSetupSnapshotState.READY) return null
        val error = snapshot.state == OutputSetupSnapshotState.INVALID
        return SetupTaskPresentation(
            stableId = "setup:output",
            kind = SetupTaskKind.OUTPUT,
            state = if (error) SetupTaskState.ERROR else SetupTaskState.REQUIRED,
            title = taskText(TaskTextKey.AUTOMATIC_TRANSCRIPT_EXPORT, "Automatic Transcript Export"),
            detail = snapshot.detail?.let(::opaqueTaskText)
                ?: taskText(TaskTextKey.OUTPUT_OPTIONAL_DETAIL, "Optional. Transcripts are stored in Voice Inbox."),
            badge = if (error) taskText(TaskTextKey.NEEDS_ATTENTION, "Needs attention") else taskText(TaskTextKey.OPTIONAL, "Optional"),
            progress = null,
            errorMessage = snapshot.detail.takeIf { error }?.let(::opaqueTaskText),
            actions = buildList {
                add(TaskActionPresentation(TaskActionKind.CREATE_OUTPUT, taskText(TaskTextKey.CREATE_NEW, "Create New")))
                add(TaskActionPresentation(TaskActionKind.SELECT_OUTPUT, taskText(TaskTextKey.CHOOSE_EXISTING, "Choose Existing")))
                if (!error) add(TaskActionPresentation(TaskActionKind.HIDE_OUTPUT, taskText(TaskTextKey.HIDE, "Hide")))
            },
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
            title = taskText(TaskTextKey.REFRESH_AUDIO_FOLDER, "Refresh Audio Folder"),
            detail = snapshot.detail?.let(::opaqueTaskText),
            badge = taskText(TaskTextKey.SCANNING, "Scanning"),
            progress = TaskProgressPresentation(taskText(TaskTextKey.SCANNING_AUDIO_FOLDER, "Scanning audio folder")),
            errorMessage = null,
            actions = emptyList(),
        )
        FolderSetupSnapshotState.ERROR -> SetupTaskPresentation(
            stableId = "setup:folder",
            kind = SetupTaskKind.FOLDER,
            state = SetupTaskState.ERROR,
            title = taskText(TaskTextKey.RESTORE_AUDIO_FOLDER_ACCESS, "Restore Audio Folder Access"),
            detail = snapshot.detail?.let(::opaqueTaskText),
            badge = taskText(TaskTextKey.NEEDS_ATTENTION, "Needs attention"),
            progress = null,
            errorMessage = snapshot.detail?.let(::opaqueTaskText),
            actions = listOf(
                TaskActionPresentation(TaskActionKind.SELECT_FOLDER, taskText(TaskTextKey.SELECT_FOLDER, "Select Folder")),
                TaskActionPresentation(TaskActionKind.REFRESH_FOLDER, taskText(TaskTextKey.RETRY, "Retry")),
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
                        taskText(TaskTextKey.TRANSCRIBE, "Transcribe"),
                        snapshot.eligibleForTranscription && !input.transcription.active,
                    ),
                )
                AudioTaskState.FAILED,
                AudioTaskState.NO_SPEECH,
                -> add(
                    TaskActionPresentation(
                        TaskActionKind.RETRY_TRANSCRIPTION,
                        taskText(TaskTextKey.RETRY, "Retry"),
                        snapshot.eligibleForTranscription && !input.transcription.active,
                    ),
                )
                AudioTaskState.SUCCEEDED -> if (snapshot.hasTranscriptText) {
                    add(TaskActionPresentation(TaskActionKind.SHOW_TEXT, taskText(TaskTextKey.SHOW_TEXT, "Show Text")))
                }
                AudioTaskState.PROCESSING -> Unit
            }
            add(
                TaskActionPresentation(
                    if (isPreviewing) TaskActionKind.STOP else TaskActionKind.PLAY,
                    if (isPreviewing) taskText(TaskTextKey.STOP, "Stop") else taskText(TaskTextKey.PLAY, "Play"),
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
            title = opaqueTaskText(snapshot.title),
            detail = snapshot.detail?.let(::opaqueTaskText),
            badge = when (presentationState) {
                AudioTaskState.PENDING -> taskText(TaskTextKey.NEW, "New")
                AudioTaskState.PROCESSING -> taskText(TaskTextKey.PROCESSING, "Processing")
                AudioTaskState.SUCCEEDED -> taskText(TaskTextKey.PROCESSED, "Processed")
                AudioTaskState.FAILED -> taskText(TaskTextKey.FAILED, "Failed")
                AudioTaskState.NO_SPEECH -> taskText(TaskTextKey.NO_SPEECH, "No speech")
            },
            progress = if (ownsProgress) input.transcription.toProgress() else null,
            errorMessage = (prerequisiteError ?: snapshot.lastError)?.let(::opaqueTaskText),
            actions = actions,
        )
    }

    private fun TranscriptionTaskSnapshot.toProgress(): TaskProgressPresentation =
        TaskProgressPresentation(
            phase = phase?.let(::opaqueTaskText) ?: taskText(TaskTextKey.PROCESSING, "Processing"),
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
        TaskListFilter.PROCESSED,
        TaskListFilter.ALL,
        -> compareByDescending<AudioTaskSnapshot> { it.terminalAtMillis ?: it.importedAtMillis }
            .thenByDescending { it.importedAtMillis }
            .thenByDescending { it.entryId }
        TaskListFilter.NEW -> compareByDescending<AudioTaskSnapshot> { it.importedAtMillis }
            .thenByDescending { it.entryId }
    }

    private fun emptyMessage(filter: TaskListFilter): TaskText = when (filter) {
        TaskListFilter.NEW -> taskText(TaskTextKey.NO_NEW_TASKS, "No new tasks")
        TaskListFilter.PROCESSED -> taskText(TaskTextKey.NO_PROCESSED_AUDIO, "No processed audio files")
        TaskListFilter.ALL -> taskText(TaskTextKey.NO_TASKS, "No audio tasks")
    }
}
