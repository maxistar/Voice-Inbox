package me.maxistar.voiceinbox

import android.view.KeyEvent

internal enum class VoiceKeyboardVolumeShortcutAction {
    PASS_THROUGH,
    CONSUME,
    START,
    STOP,
}

internal object VoiceKeyboardVolumeShortcut {
    fun action(
        inputViewActive: Boolean,
        keyCode: Int,
        keyAction: Int,
        repeatCount: Int,
        phase: VoiceKeyboardPhase,
    ): VoiceKeyboardVolumeShortcutAction {
        if (!inputViewActive || keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
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

    fun shouldConsumeMultiple(inputViewActive: Boolean, keyCode: Int): Boolean =
        inputViewActive && keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
}
