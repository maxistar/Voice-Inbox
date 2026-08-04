package me.maxistar.voiceinbox

enum class VoiceKeyboardPhase {
    IDLE,
    PREPARING,
    RECORDING,
    TRANSCRIBING,
    RESULT_PENDING,
    ERROR,
}

/** Keeps IME request state deterministic and independent from Android view callbacks. */
class VoiceKeyboardController {
    var phase: VoiceKeyboardPhase = VoiceKeyboardPhase.IDLE
        private set

    var pendingText: String? = null
        private set

    fun beginPreparation(): Boolean {
        if (phase !in setOf(VoiceKeyboardPhase.IDLE, VoiceKeyboardPhase.ERROR)) return false
        phase = VoiceKeyboardPhase.PREPARING
        return true
    }

    fun recordingStarted(): Boolean {
        if (phase != VoiceKeyboardPhase.PREPARING) return false
        phase = VoiceKeyboardPhase.RECORDING
        return true
    }

    fun recordingStopped(): Boolean {
        if (phase != VoiceKeyboardPhase.RECORDING) return false
        phase = VoiceKeyboardPhase.TRANSCRIBING
        return true
    }

    fun completeTranscription(text: String?): String? {
        if (phase != VoiceKeyboardPhase.TRANSCRIBING) return null
        phase = VoiceKeyboardPhase.IDLE
        return text?.trim()?.takeIf(String::isNotEmpty)
    }

    fun deferResult(text: String) {
        pendingText = text
        phase = VoiceKeyboardPhase.RESULT_PENDING
    }

    fun pendingResult(): String? = pendingText

    fun markPendingCommitted() {
        pendingText = null
        phase = VoiceKeyboardPhase.IDLE
    }

    fun dismissPendingResult() {
        pendingText = null
        if (phase == VoiceKeyboardPhase.RESULT_PENDING) phase = VoiceKeyboardPhase.IDLE
    }

    fun fail() {
        phase = VoiceKeyboardPhase.ERROR
    }

    fun cancel() {
        pendingText = null
        phase = VoiceKeyboardPhase.IDLE
    }
}
