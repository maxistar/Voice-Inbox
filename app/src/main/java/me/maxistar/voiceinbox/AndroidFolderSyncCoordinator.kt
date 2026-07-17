package me.maxistar.voiceinbox

enum class AndroidFolderSyncPhase {
    IDLE,
    VALIDATING,
    QUEUED,
    SCANNING,
    REFRESHING_CATALOG,
}

data class AndroidFolderSyncState(
    val generation: Long = 0,
    val phase: AndroidFolderSyncPhase = AndroidFolderSyncPhase.IDLE,
) {
    val active: Boolean get() = phase != AndroidFolderSyncPhase.IDLE
}

class AndroidFolderSyncCoordinator {
    var state: AndroidFolderSyncState = AndroidFolderSyncState()
        private set

    fun begin(phase: AndroidFolderSyncPhase = AndroidFolderSyncPhase.VALIDATING): Long {
        val generation = state.generation + 1
        state = AndroidFolderSyncState(generation, phase)
        return generation
    }

    fun transition(generation: Long, phase: AndroidFolderSyncPhase): Boolean {
        if (!isCurrent(generation) || phase == AndroidFolderSyncPhase.IDLE) return false
        state = state.copy(phase = phase)
        return true
    }

    fun complete(generation: Long): Boolean {
        if (!isCurrent(generation)) return false
        state = state.copy(phase = AndroidFolderSyncPhase.IDLE)
        return true
    }

    fun fail(generation: Long): Boolean = complete(generation)

    fun reset() {
        state = AndroidFolderSyncState(state.generation + 1, AndroidFolderSyncPhase.IDLE)
    }

    fun isCurrent(generation: Long): Boolean =
        state.active && state.generation == generation
}
