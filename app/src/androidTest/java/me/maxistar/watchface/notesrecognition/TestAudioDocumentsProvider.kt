package me.maxistar.watchface.notesrecognition

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.FileNotFoundException

class TestAudioDocumentsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: ROOT_COLUMNS).apply {
            newRow()
                .add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
                .add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_ID)
                .add(DocumentsContract.Root.COLUMN_TITLE, "Test audio folder")
                .add(DocumentsContract.Root.COLUMN_FLAGS, 0)
        }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
            when (documentId) {
                ROOT_ID -> addDocument(ROOT_ID, "Test audio folder", DocumentsContract.Document.MIME_TYPE_DIR)
                WAV_ID -> addDocument(WAV_ID, "recording.wav", "audio/wav", 100, 10)
                M4A_ID -> addDocument(M4A_ID, "recording.m4a", "audio/mp4", 200, 20)
                TEXT_ID -> addDocument(TEXT_ID, "notes.txt", "text/plain", 20, 30)
                NESTED_ID -> addDocument(
                    NESTED_ID,
                    "nested",
                    DocumentsContract.Document.MIME_TYPE_DIR,
                )
                NESTED_AUDIO_ID -> addDocument(
                    NESTED_AUDIO_ID,
                    "nested.wav",
                    "audio/wav",
                    300,
                    40,
                )
            }
        }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
        when (parentDocumentId) {
            ROOT_ID -> {
                addDocument(WAV_ID, "recording.wav", "audio/wav", 100, 10)
                addDocument(M4A_ID, "recording.m4a", "audio/mp4", 200, 20)
                addDocument(HIDDEN_AUDIO_ID, ".hidden.wav", "audio/wav", 400, 50)
                addDocument(TEXT_ID, "notes.txt", "text/plain", 20, 30)
                addDocument(NESTED_ID, "nested", DocumentsContract.Document.MIME_TYPE_DIR)
            }
            NESTED_ID -> addDocument(
                NESTED_AUDIO_ID,
                "nested.wav",
                "audio/wav",
                300,
                40,
            )
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        throw FileNotFoundException(documentId)
    }

    private fun MatrixCursor.addDocument(
        id: String,
        name: String,
        mime: String,
        size: Long? = null,
        modified: Long? = null,
    ) {
        newRow()
            .add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id)
            .add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, name)
            .add(DocumentsContract.Document.COLUMN_MIME_TYPE, mime)
            .add(DocumentsContract.Document.COLUMN_SIZE, size)
            .add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, modified)
            .add(DocumentsContract.Document.COLUMN_FLAGS, 0)
    }

    companion object {
        const val AUTHORITY = "me.maxistar.watchface.notesrecognition.test.documents"
        const val ROOT_ID = "root"
        private const val WAV_ID = "wav"
        private const val M4A_ID = "m4a"
        private const val HIDDEN_AUDIO_ID = "hidden"
        private const val TEXT_ID = "text"
        private const val NESTED_ID = "nested"
        private const val NESTED_AUDIO_ID = "nested/audio"

        private val ROOT_COLUMNS = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS,
        )
        private val DOCUMENT_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
    }
}
