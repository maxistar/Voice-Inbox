package me.maxistar.voiceinbox

import me.maxistar.voiceinbox.core.AudioCatalogEntry
import me.maxistar.voiceinbox.core.AudioTaskPresentation
import me.maxistar.voiceinbox.core.TaskActionKind

data class AndroidTaskActionRequest(
    val stableId: String,
    val entryId: Long?,
    val kind: TaskActionKind,
)

fun interface AndroidTaskActionGateway {
    fun perform(kind: TaskActionKind, entry: AudioCatalogEntry?)
}

class AndroidTaskActionRouter(
    private val currentState: () -> AndroidMainScreenState,
    private val gateway: AndroidTaskActionGateway,
) {
    fun route(request: AndroidTaskActionRequest): Boolean {
        val state = currentState()
        val authorized = when {
            request.kind == TaskActionKind.TRANSCRIBE_ALL ->
                request.stableId == TaskListDisplayItem.BatchAction.STABLE_KEY &&
                    state.taskList.batchAction.visible &&
                    state.taskList.batchAction.enabled
            request.stableId == TaskListDisplayItem.Empty.STABLE_KEY ->
                state.taskList.emptyActions.any { it.kind == request.kind && it.enabled }
            else -> state.taskList.tasks
                .firstOrNull { it.stableId == request.stableId }
                ?.actions
                ?.any { it.kind == request.kind && it.enabled } == true
        }
        if (!authorized) return false

        val entry = request.entryId?.let(state.entriesById::get)
        val taskRequiresEntry = request.kind in ENTRY_ACTIONS
        if (taskRequiresEntry && entry == null) return false
        val presentedEntryId = state.taskList.tasks
            .filterIsInstance<AudioTaskPresentation>()
            .firstOrNull { it.stableId == request.stableId }
            ?.entryId
        if (taskRequiresEntry && presentedEntryId != entry?.id) return false

        gateway.perform(request.kind, entry)
        return true
    }

    companion object {
        private val ENTRY_ACTIONS = setOf(
            TaskActionKind.TRANSCRIBE,
            TaskActionKind.RETRY_TRANSCRIPTION,
            TaskActionKind.PLAY,
            TaskActionKind.STOP,
            TaskActionKind.SHOW_TEXT,
        )
    }
}
