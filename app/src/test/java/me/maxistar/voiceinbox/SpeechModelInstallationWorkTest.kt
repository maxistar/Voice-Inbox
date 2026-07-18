package me.maxistar.voiceinbox

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechModelInstallationWorkTest {
    @Test
    fun downloadCompatibilityAliasesKeepTheirHistoricalValues() {
        assertEquals("speech-model-installation", SpeechModelInstallationWork.UNIQUE_WORK_NAME)
        assertEquals(SpeechModelInstallationWork.UNIQUE_WORK_NAME, SpeechModelDownloadWorker.UNIQUE_WORK_NAME)
        assertEquals(SpeechModelInstallationWork.KEY_BYTES_DOWNLOADED, SpeechModelDownloadWorker.KEY_BYTES_DOWNLOADED)
        assertEquals(SpeechModelInstallationWork.KEY_TOTAL_BYTES, SpeechModelDownloadWorker.KEY_TOTAL_BYTES)
        assertEquals(SpeechModelInstallationWork.KEY_MESSAGE, SpeechModelDownloadWorker.KEY_MESSAGE)
        assertEquals(SpeechModelInstallationWork.KEY_ERROR, SpeechModelDownloadWorker.KEY_ERROR)
        assertEquals(SpeechModelInstallationWork.KEY_MODEL_PATH, SpeechModelDownloadWorker.KEY_MODEL_PATH)
    }
}
