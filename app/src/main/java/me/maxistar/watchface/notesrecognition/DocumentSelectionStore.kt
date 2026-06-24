package me.maxistar.watchface.notesrecognition

import android.content.SharedPreferences

class DocumentSelectionStore(
    private val preferences: SharedPreferences,
) {
    fun loadOutputUri(): String? = preferences.getString(KEY_OUTPUT_URI, null)

    fun saveOutputUri(uri: String) {
        preferences.edit().putString(KEY_OUTPUT_URI, uri).apply()
    }

    fun clearOutputUri() {
        preferences.edit().remove(KEY_OUTPUT_URI).apply()
    }

    fun loadFolderUri(): String? = preferences.getString(KEY_FOLDER_URI, null)

    fun saveFolderUri(uri: String) {
        preferences.edit().putString(KEY_FOLDER_URI, uri).apply()
    }

    fun clearFolderUri() {
        preferences.edit().remove(KEY_FOLDER_URI).apply()
    }

    companion object {
        const val PREFERENCES_NAME = "notes_recognition"
        private const val KEY_OUTPUT_URI = "output_uri"
        private const val KEY_FOLDER_URI = "folder_uri"
    }
}
