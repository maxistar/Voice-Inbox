package me.maxistar.voiceinbox

import android.content.Context
import android.content.Intent
import android.net.Uri

object SpeechModelImportPermission {
    private const val PREFERENCES_NAME = "speech_model_import"
    private const val KEY_OWNED_URI = "owned_uri"

    fun recordOwned(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_OWNED_URI, uri.toString()).apply()
    }

    fun releaseOwnedIfUnused(context: Context) {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val value = preferences.getString(KEY_OWNED_URI, null) ?: return
        val selectedFolder = DocumentSelectionStore(
            context.getSharedPreferences(DocumentSelectionStore.PREFERENCES_NAME, Context.MODE_PRIVATE),
        ).loadFolderUri()
        if (selectedFolder == value) return
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(value),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        preferences.edit().remove(KEY_OWNED_URI).apply()
    }
}
