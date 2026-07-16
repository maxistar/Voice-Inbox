package me.maxistar.voiceinbox

import me.maxistar.voiceinbox.core.AudioCatalogSourceScope
import me.maxistar.voiceinbox.core.MainScreenCatalogTab

data class CatalogRefreshToken(
    val generation: Long,
    val sourceScope: AudioCatalogSourceScope,
    val selectedTab: MainScreenCatalogTab,
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
        currentTab: MainScreenCatalogTab,
    ): Boolean =
        request.generation == currentGeneration &&
            request.sourceScope == currentSourceScope &&
            request.selectedTab == currentTab

    fun shouldRefreshForWork(
        previous: CatalogWorkRefreshKey?,
        current: CatalogWorkRefreshKey,
    ): Boolean = previous != current
}
