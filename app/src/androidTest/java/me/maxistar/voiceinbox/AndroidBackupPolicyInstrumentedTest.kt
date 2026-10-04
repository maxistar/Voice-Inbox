package me.maxistar.voiceinbox

import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidBackupPolicyInstrumentedTest {
    @Test
    fun installedApplicationDisablesAndroidSystemBackup() {
        val appInfo = InstrumentationRegistry.getInstrumentation().targetContext.applicationInfo

        assertEquals(0, appInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }
}
