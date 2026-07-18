package me.maxistar.voiceinbox.core

enum class SpeechModelInstallationState {
    NOT_INSTALLED,
    INSTALLING,
    INSTALLED,
    INVALID,
}

enum class SpeechModelRuntimeState {
    UNLOADED,
    LOADING,
    LOADED,
    FAILED,
}

val SpeechModelInstallationState.isAvailable: Boolean
    get() = this == SpeechModelInstallationState.INSTALLED

val SpeechModelRuntimeState.isPreparing: Boolean
    get() = this == SpeechModelRuntimeState.LOADING
