package me.maxistar.voiceinbox

import me.maxistar.voiceinbox.core.AudioCatalogSourceScope
import me.maxistar.voiceinbox.core.MainScreenCatalogTab
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogRefreshPolicyTest {
    @Test
    fun firstObservationFilenameAndTerminalStateTriggerRefresh() {
        val first = workKey(filename = "one.wav")
        assertTrue(CatalogRefreshPolicy.shouldRefreshForWork(null, first))
        assertFalse(CatalogRefreshPolicy.shouldRefreshForWork(first, first))
        assertTrue(
            CatalogRefreshPolicy.shouldRefreshForWork(
                first,
                workKey(filename = "two.wav"),
            ),
        )
        assertTrue(
            CatalogRefreshPolicy.shouldRefreshForWork(
                first,
                workKey(filename = "one.wav", state = "SUCCEEDED", completed = 1),
            ),
        )
    }

    @Test
    fun fineGrainedProgressDoesNotParticipateInRefreshKey() {
        val before = workKey(filename = "one.wav")
        val after = workKey(filename = "one.wav")

        assertFalse(CatalogRefreshPolicy.shouldRefreshForWork(before, after))
    }

    @Test
    fun refreshTokenRejectsOldGenerationScopeAndTab() {
        val scope = AudioCatalogSourceScope.of(listOf("imports", "folder"))
        val token = CatalogRefreshToken(3, scope, MainScreenCatalogTab.NEW)

        assertTrue(
            CatalogRefreshPolicy.isCurrent(token, 3, scope, MainScreenCatalogTab.NEW),
        )
        assertFalse(
            CatalogRefreshPolicy.isCurrent(token, 4, scope, MainScreenCatalogTab.NEW),
        )
        assertFalse(
            CatalogRefreshPolicy.isCurrent(
                token,
                3,
                AudioCatalogSourceScope.single("imports"),
                MainScreenCatalogTab.NEW,
            ),
        )
        assertFalse(
            CatalogRefreshPolicy.isCurrent(token, 3, scope, MainScreenCatalogTab.PROCESSED),
        )
    }

    private fun workKey(
        filename: String?,
        state: String = "RUNNING",
        completed: Int = 0,
    ) = CatalogWorkRefreshKey(
        workId = "work",
        workState = state,
        filename = filename,
        completedFiles = completed,
        failedFiles = 0,
    )
}
