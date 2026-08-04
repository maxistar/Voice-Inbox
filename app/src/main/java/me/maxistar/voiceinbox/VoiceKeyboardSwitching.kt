package me.maxistar.voiceinbox

internal enum class VoiceKeyboardReturnAction {
    RESTORED_PREVIOUS,
    SHOW_PICKER,
}

internal object VoiceKeyboardSwitching {
    fun returnAction(previousKeyboardRestored: Boolean): VoiceKeyboardReturnAction =
        if (previousKeyboardRestored) {
            VoiceKeyboardReturnAction.RESTORED_PREVIOUS
        } else {
            VoiceKeyboardReturnAction.SHOW_PICKER
        }
}
