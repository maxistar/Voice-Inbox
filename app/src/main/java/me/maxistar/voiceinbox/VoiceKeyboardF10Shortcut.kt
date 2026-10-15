package me.maxistar.voiceinbox

import android.view.KeyEvent

internal object VoiceKeyboardF10Shortcut {
    fun action(
        inputActive: Boolean,
        keyCode: Int,
        keyAction: Int,
        repeatCount: Int,
        phase: VoiceKeyboardPhase,
    ): VoiceKeyboardVolumeShortcutAction {
        if (!inputActive || keyCode != KeyEvent.KEYCODE_F10) {
            return VoiceKeyboardVolumeShortcutAction.PASS_THROUGH
        }
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

    fun shouldConsumeMultiple(inputActive: Boolean, keyCode: Int): Boolean =
        inputActive && keyCode == KeyEvent.KEYCODE_F10
}
