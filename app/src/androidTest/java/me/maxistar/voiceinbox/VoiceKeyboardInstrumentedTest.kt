package me.maxistar.voiceinbox

import android.content.ComponentName
import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.ViewConfiguration
import android.widget.EditText
import android.widget.ImageButton
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
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        assertTrue(packageInfo.requestedPermissions?.contains(android.Manifest.permission.RECORD_AUDIO) == true)
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
            connection.deleteSurroundingTextInCodePoints(1, 0)

            assertEquals("start tex", editor.text.toString())
        }
    }

    @Test
    fun inputConnectionDeletesEmojiAsOneCodePoint() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val editor = EditText(context)
            editor.setText("start \uD83D\uDE00")
            editor.setSelection(editor.length())
            val connection = editor.onCreateInputConnection(android.view.inputmethod.EditorInfo())

            assertTrue(connection.deleteSurroundingTextInCodePoints(1, 0))

            assertEquals("start ", editor.text.toString())
        }
    }

    @Test
    fun keyboardLayoutInflatesWithAccessibleIconControls() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = LayoutInflater.from(context).inflate(R.layout.input_view_voice_keyboard, null)

            assertEquals(
                context.getString(R.string.voice_keyboard_record),
                view.findViewById<ImageButton>(R.id.voiceKeyboardRecord).contentDescription,
            )
            val recordButton = view.findViewById<ImageButton>(R.id.voiceKeyboardRecord)
            val minimumTouchTarget = (48 * context.resources.displayMetrics.density).toInt()
            assertTrue(recordButton.layoutParams.width >= minimumTouchTarget)
            assertTrue(recordButton.layoutParams.height >= minimumTouchTarget)
            assertEquals(
                context.getString(R.string.voice_keyboard_return_to_previous),
                view.findViewById<ImageButton>(R.id.voiceKeyboardNextKeyboard).contentDescription,
            )
            assertEquals(
                context.getString(R.string.voice_keyboard_space),
                view.findViewById<ImageButton>(R.id.voiceKeyboardSpace).contentDescription,
            )
            assertEquals(
                context.getString(R.string.voice_keyboard_enter),
                view.findViewById<ImageButton>(R.id.voiceKeyboardEnter).contentDescription,
            )
            assertEquals(
                context.getString(R.string.voice_keyboard_backspace),
                view.findViewById<ImageButton>(R.id.voiceKeyboardBackspace).contentDescription,
            )
        }
    }

    @Test
    fun androidLongPressThresholdClassifiesLatchedAndHeldRecording() {
        val threshold = ViewConfiguration.getLongPressTimeout().toLong()
        val coordinator = HybridRecordGestureCoordinator(HybridRecordGesturePolicy(threshold))

        assertTrue(coordinator.begin(pointerId = 0, generation = 1, eventTimeMillis = 1_000))
        assertEquals(
            HybridRecordRelease.LATCH,
            coordinator.release(0, 1, 1_000 + threshold - 1)?.release,
        )
        coordinator.finish(1)

        assertTrue(coordinator.begin(pointerId = 0, generation = 2, eventTimeMillis = 2_000))
        assertEquals(
            HybridRecordRelease.STOP_AND_TRANSCRIBE,
            coordinator.release(0, 2, 2_000 + threshold)?.release,
        )
    }

    @Test
    fun cancelledHeldGestureCannotStartAfterModelPreparation() {
        val coordinator = HybridRecordGestureCoordinator(
            HybridRecordGesturePolicy(ViewConfiguration.getLongPressTimeout().toLong()),
        )
        assertTrue(coordinator.begin(pointerId = 4, generation = 9, eventTimeMillis = 1_000))

        assertTrue(coordinator.cancel(pointerId = 4, generation = 9))

        assertEquals(HybridRecordPreparationAction.CANCEL, coordinator.preparationAction(9))
    }
}
