package me.maxistar.voiceinbox

import me.maxistar.voiceinbox.core.AudioCatalogSourceScope

data class CatalogRefreshToken(
    val generation: Long,
    val sourceScope: AudioCatalogSourceScope,
)

data class CatalogWorkRefreshKey(
    val workId: String,
    val workState: String,
    val filename: String?,
    val completedFiles: Int,
    val failedFiles: Int,
)

object CatalogRefreshPolicy {
    fun isCurrent(
        request: CatalogRefreshToken,
        currentGeneration: Long,
        currentSourceScope: AudioCatalogSourceScope,
    ): Boolean =
        request.generation == currentGeneration &&
            request.sourceScope == currentSourceScope

    fun shouldRefreshForWork(
        previous: CatalogWorkRefreshKey?,
        current: CatalogWorkRefreshKey,
    ): Boolean = previous != current
}
