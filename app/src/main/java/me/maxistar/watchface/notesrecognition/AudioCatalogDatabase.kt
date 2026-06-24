package me.maxistar.watchface.notesrecognition

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class AudioCatalogDatabase(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE $TABLE_FILES (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_FOLDER_URI TEXT NOT NULL,
                $COLUMN_DOCUMENT_URI TEXT NOT NULL,
                $COLUMN_DISPLAY_NAME TEXT NOT NULL,
                $COLUMN_MIME_TYPE TEXT,
                $COLUMN_SIZE_BYTES INTEGER,
                $COLUMN_MODIFIED_MILLIS INTEGER,
                $COLUMN_STATE TEXT NOT NULL,
                $COLUMN_STATE_BEFORE_MISSING TEXT,
                $COLUMN_LAST_ERROR TEXT,
                $COLUMN_PROCESSED_AT INTEGER,
                UNIQUE($COLUMN_FOLDER_URI, $COLUMN_DOCUMENT_URI)
            )
            """.trimIndent(),
        )
        database.execSQL(
            "CREATE INDEX index_audio_files_folder_state ON $TABLE_FILES " +
                "($COLUMN_FOLDER_URI, $COLUMN_STATE)",
        )
        database.execSQL(
            "CREATE INDEX index_audio_files_order ON $TABLE_FILES " +
                "($COLUMN_FOLDER_URI, $COLUMN_MODIFIED_MILLIS, $COLUMN_DISPLAY_NAME)",
        )
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == newVersion) {
            "No migration is defined from catalog version $oldVersion to $newVersion"
        }
    }

    companion object {
        const val DATABASE_NAME = "audio-catalog.db"
        const val DATABASE_VERSION = 1
        const val TABLE_FILES = "audio_files"
        const val COLUMN_ID = "id"
        const val COLUMN_FOLDER_URI = "folder_uri"
        const val COLUMN_DOCUMENT_URI = "document_uri"
        const val COLUMN_DISPLAY_NAME = "display_name"
        const val COLUMN_MIME_TYPE = "mime_type"
        const val COLUMN_SIZE_BYTES = "size_bytes"
        const val COLUMN_MODIFIED_MILLIS = "modified_millis"
        const val COLUMN_STATE = "state"
        const val COLUMN_STATE_BEFORE_MISSING = "state_before_missing"
        const val COLUMN_LAST_ERROR = "last_error"
        const val COLUMN_PROCESSED_AT = "processed_at"
    }
}
