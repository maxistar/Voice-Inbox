package me.maxistar.voiceinbox

import android.view.KeyEvent

internal enum class VoiceKeyboardVolumeShortcutAction {
    PASS_THROUGH,
    CONSUME,
    START,
    STOP,
}

internal object VoiceKeyboardHardwareShortcut {
    fun action(
        settings: VoiceKeyboardTriggerSettings,
        inputActive: Boolean,
        inputViewActive: Boolean,
        keyCode: Int,
        keyAction: Int,
        repeatCount: Int,
        phase: VoiceKeyboardPhase,
    ): VoiceKeyboardVolumeShortcutAction {
        val selectedAndActive = when (settings.trigger) {
            VoiceKeyboardHardwareTrigger.VOLUME_DOWN ->
                inputViewActive && keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
            VoiceKeyboardHardwareTrigger.PHYSICAL_KEY ->
                inputActive && keyCode == settings.physicalKeyCode
            VoiceKeyboardHardwareTrigger.TOUCH_ONLY -> false
        }
        if (!selectedAndActive) return VoiceKeyboardVolumeShortcutAction.PASS_THROUGH
        if (keyAction != KeyEvent.ACTION_DOWN || repeatCount > 0) {
            return VoiceKeyboardVolumeShortcutAction.CONSUME
        }
        return when (phase) {
            VoiceKeyboardPhase.IDLE,
            VoiceKeyboardPhase.ERROR,
            -> VoiceKeyboardVolumeShortcutAction.START
            VoiceKeyboardPhase.RECORDING -> VoiceKeyboardVolumeShortcutAction.STOP
            VoiceKeyboardPhase.WAITING_FOR_MODEL,
            VoiceKeyboardPhase.TRANSCRIBING,
            VoiceKeyboardPhase.RESULT_PENDING,
            -> VoiceKeyboardVolumeShortcutAction.CONSUME
        }
    }

    fun shouldConsumeMultiple(
        settings: VoiceKeyboardTriggerSettings,
        inputActive: Boolean,
        inputViewActive: Boolean,
        keyCode: Int,
    ): Boolean = when (settings.trigger) {
        VoiceKeyboardHardwareTrigger.VOLUME_DOWN ->
            inputViewActive && keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        VoiceKeyboardHardwareTrigger.PHYSICAL_KEY ->
            inputActive && keyCode == settings.physicalKeyCode
        VoiceKeyboardHardwareTrigger.TOUCH_ONLY -> false
    }
}
