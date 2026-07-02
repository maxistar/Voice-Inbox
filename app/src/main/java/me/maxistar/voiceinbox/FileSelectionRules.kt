package me.maxistar.voiceinbox

object FileSelectionRules {
    val outputMimeTypes = arrayOf(
        "text/plain",
        "text/markdown",
        "text/x-markdown",
    )

    fun displayName(providerName: String?, fallbackPathSegment: String?): String =
        providerName?.takeIf(String::isNotBlank)
            ?: fallbackPathSegment?.takeIf(String::isNotBlank)
            ?: "document"

    fun restoredOutputUri(storedUri: String?, appendAccessValid: Boolean): String? =
        storedUri?.takeIf { appendAccessValid }

    fun isSupportedAudio(mimeType: String?, displayName: String): Boolean {
        if (mimeType == "vnd.android.document/directory") return false
        if (mimeType?.startsWith("audio/") == true || mimeType == "video/mp4") return true
        return displayName.substringAfterLast('.', "").lowercase() in SUPPORTED_AUDIO_EXTENSIONS
    }

    private val SUPPORTED_AUDIO_EXTENSIONS = setOf(
        "wav",
        "m4a",
        "mp4",
        "mp3",
        "aac",
        "flac",
        "ogg",
        "opus",
        "amr",
    )
}
