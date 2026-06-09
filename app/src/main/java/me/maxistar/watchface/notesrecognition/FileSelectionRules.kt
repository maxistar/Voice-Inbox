package me.maxistar.watchface.notesrecognition

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
}
