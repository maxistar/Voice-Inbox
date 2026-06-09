package me.maxistar.watchface.notesrecognition

object FileSelectionRules {
    fun displayName(providerName: String?, fallbackPathSegment: String?): String =
        providerName?.takeIf(String::isNotBlank)
            ?: fallbackPathSegment?.takeIf(String::isNotBlank)
            ?: "document"

    fun restoredOutputUri(storedUri: String?, appendAccessValid: Boolean): String? =
        storedUri?.takeIf { appendAccessValid }
}
