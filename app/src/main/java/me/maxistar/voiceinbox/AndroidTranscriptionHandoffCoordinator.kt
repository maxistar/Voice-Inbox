package me.maxistar.voiceinbox

data class AndroidTranscriptionHandoff(
    val workId: String,
    val requestedEntryId: Long?,
    val phase: String = AndroidTranscriptionHandoffCoordinator.PREPARATION_PHASE,
)

data class AndroidTranscriptionWorkObservation(
    val workId: String,
    val active: Boolean,
)

class AndroidTranscriptionHandoffCoordinator {
    var handoff: AndroidTranscriptionHandoff? = null
        private set

    fun begin(workId: String, requestedEntryId: Long?) {
        handoff = AndroidTranscriptionHandoff(workId, requestedEntryId)
    }

    fun reconcile(observations: List<AndroidTranscriptionWorkObservation>) {
        val pending = handoff ?: return
        if (observations.any { it.workId == pending.workId } || observations.any { it.active }) {
            handoff = null
        }
    }

    fun clear() {
        handoff = null
    }

    fun active(observedActive: Boolean): Boolean = observedActive || handoff != null

    fun requestedEntryId(tags: Set<String>): Long? = tags
        .firstOrNull { it.startsWith(ENTRY_TAG_PREFIX) }
        ?.removePrefix(ENTRY_TAG_PREFIX)
        ?.toLongOrNull()

    fun preparationOwnerEntryId(
        observedActive: Boolean,
        activeEntryId: Long?,
        requestedEntryId: Long?,
    ): Long? {
        if (!active(observedActive) || activeEntryId != null) return null
        return requestedEntryId ?: handoff?.requestedEntryId
    }

    fun phase(observedPhase: String?, observedActive: Boolean, activeEntryId: Long?): String? =
        observedPhase ?: when {
            active(observedActive) && activeEntryId == null -> PREPARATION_PHASE
            observedActive -> "Transcribing"
            else -> null
        }

    companion object {
        const val PREPARATION_PHASE = "Preparing speech model"
        const val ENTRY_TAG_PREFIX = "transcription-entry:"
    }
}
