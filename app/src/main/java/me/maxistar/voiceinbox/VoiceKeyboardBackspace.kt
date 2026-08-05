package me.maxistar.voiceinbox

import android.view.inputmethod.InputConnection

internal object VoiceKeyboardBackspace {
    fun deleteOne(connection: InputConnection?): Boolean {
        connection ?: return false
        if (!shouldAttemptDeletion(connection.getTextBeforeCursor(1, 0))) return false
        return deleteOne(
            deleteCodePoint = { connection.deleteSurroundingTextInCodePoints(1, 0) },
            deleteCodeUnit = { connection.deleteSurroundingText(1, 0) },
        )
    }

    internal fun shouldAttemptDeletion(textBeforeCursor: CharSequence?): Boolean =
        textBeforeCursor?.isNotEmpty() ?: true

    internal fun deleteOne(
        deleteCodePoint: () -> Boolean,
        deleteCodeUnit: () -> Boolean,
    ): Boolean = deleteCodePoint() || deleteCodeUnit()
}
