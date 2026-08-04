package me.maxistar.voiceinbox

import android.content.ComponentName
import android.content.pm.PackageManager
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceKeyboardInstrumentedTest {
    @Test
    fun manifestDeclaresBoundInputMethodService() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val service = context.packageManager.getServiceInfo(
            ComponentName(context, VoiceKeyboardInputMethodService::class.java),
            PackageManager.GET_META_DATA,
        )

        assertEquals("android.permission.BIND_INPUT_METHOD", service.permission)
        assertTrue(service.metaData?.containsKey("android.view.im") == true)
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            context.packageManager.checkPermission(android.Manifest.permission.RECORD_AUDIO, context.packageName),
        )
    }

    @Test
    fun inputConnectionCommitsAndDeletesText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val editor = EditText(context)
            editor.setText("start")
            editor.setSelection(editor.length())
            val connection = editor.onCreateInputConnection(android.view.inputmethod.EditorInfo())

            connection.commitText(" text", 1)
            connection.deleteSurroundingText(1, 0)

            assertEquals("start tex", editor.text.toString())
        }
    }
}
