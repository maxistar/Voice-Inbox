package me.maxistar.watchface.notesrecognition

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptionUiRulesTest {
    @Test
    fun controlsRequireModelSelectionsPendingWorkAndIdleState() {
        assertEquals(
            CatalogControlState(false, false, false, false, false),
            controls(modelReady = false),
        )
        assertEquals(
            CatalogControlState(true, true, false, false, false),
            controls(modelReady = true),
        )
        assertEquals(
            CatalogControlState(true, true, true, false, true),
            controls(modelReady = true, output = true, folder = true),
        )
        assertEquals(
            CatalogControlState(true, true, true, true, true),
            controls(modelReady = true, output = true, folder = true, pending = 2),
        )
        assertEquals(
            CatalogControlState(false, false, false, false, false),
            controls(
                modelReady = true,
                output = true,
                folder = true,
                pending = 2,
                active = true,
            ),
        )
    }

    @Test
    fun selectionControlsAreDisabledWhileScanningOrTranscribing() {
        assertEquals(
            CatalogControlState(false, false, false, false, false),
            TranscriptionUiRules.catalogControls(
                modelReady = true,
                outputSelected = true,
                folderSelected = true,
                pendingCount = 2,
                transcriptionActive = true,
                scanning = false,
            ),
        )
        assertEquals(
            CatalogControlState(false, false, false, false, false),
            TranscriptionUiRules.catalogControls(
                modelReady = true,
                outputSelected = true,
                folderSelected = true,
                pendingCount = 2,
                transcriptionActive = false,
                scanning = true,
            ),
        )
    }

    @Test
    fun progressUsesProcessedDuration() {
        assertEquals(50, TranscriptionUiRules.percent(5_000, 10_000))
        assertEquals(100, TranscriptionUiRules.percent(20_000, 10_000))
        assertEquals(null, TranscriptionUiRules.percent(1, null))
    }

    private fun controls(
        modelReady: Boolean,
        output: Boolean = false,
        folder: Boolean = false,
        pending: Int = 0,
        active: Boolean = false,
    ) = TranscriptionUiRules.catalogControls(
        modelReady,
        output,
        folder,
        pending,
        active,
        scanning = false,
    )
}
