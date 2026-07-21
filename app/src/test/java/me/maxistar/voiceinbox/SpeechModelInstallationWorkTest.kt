package me.maxistar.voiceinbox

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun typedForegroundInfoUsesDataSyncFromApi29() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            SpeechModelInstallationWork.foregroundServiceTypeForSdk(29),
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            SpeechModelInstallationWork.foregroundServiceTypeForSdk(36),
        )
    }

    @Test
    fun legacyForegroundInfoDoesNotRequestAnUnavailableType() {
        assertEquals(0, SpeechModelInstallationWork.foregroundServiceTypeForSdk(24))
        assertEquals(0, SpeechModelInstallationWork.foregroundServiceTypeForSdk(28))
    }

    @Test
    fun foregroundFailureUsesExistingWorkerErrorContract() {
        val message = "Could not keep model download running in the foreground. Please try again."

        val data = SpeechModelInstallationWork.failureData(message)

        assertEquals(message, data.getString(SpeechModelInstallationWork.KEY_ERROR))
    }

    @Test
    fun foregroundFailureDiagnosticIdentifiesDeviceSourceAndException() {
        val diagnostic = SpeechModelInstallationWork.foregroundFailureDiagnostic(
            source = SpeechModelInstallationWork.Source.LOCAL_IMPORT,
            sdkInt = 34,
            manufacturer = "samsung",
            model = "SM-G990B",
            error = SecurityException("foreground type rejected"),
        )

        assertTrue(diagnostic.contains("source=local-import"))
        assertTrue(diagnostic.contains("sdk=34"))
        assertTrue(diagnostic.contains("device=samsung SM-G990B"))
        assertTrue(diagnostic.contains("SecurityException"))
        assertTrue(diagnostic.contains("foreground type rejected"))
    }
}
