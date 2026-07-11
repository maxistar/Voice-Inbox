package me.maxistar.voiceinbox

import me.maxistar.voiceinbox.core.*

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.IOException

class AudioFolderScanner(
    private val resolver: ContentResolver,
) {
    fun folderName(treeUri: Uri): String {
        val documentUri = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
        }.getOrNull()
        if (documentUri != null) {
            runCatching {
                resolver.query(
                    documentUri,
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        return cursor.getString(0)?.takeIf(String::isNotBlank)
                            ?: treeUri.lastPathSegment
                            ?: "audio folder"
                    }
                }
            }
        }
        return treeUri.lastPathSegment ?: "audio folder"
    }

    fun requireReadable(treeUri: Uri) {
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
            .getOrElse { throw IOException("The selected audio folder is not readable", it) }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        resolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null,
            null,
            null,
        )?.use { return }
        throw IOException("The selected audio folder is not readable")
    }

    fun scan(treeUri: Uri): List<ScannedAudioFile> {
        val folderUri = treeUri.toString()
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            OpenableColumns.SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val results = ArrayList<ScannedAudioFile>()
        val cursor = resolver.query(childrenUri, projection, null, null, null)
            ?: throw IOException("Unable to scan the selected audio folder")
        cursor.use {
            val idIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
            val modifiedIndex =
                it.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (it.moveToNext()) {
                val displayName = it.getString(nameIndex)
                if (displayName?.startsWith(".") == true) continue
                val mime = it.getString(mimeIndex)
                if (!FileSelectionRules.isSupportedAudio(mime, displayName)) continue
                val childUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    it.getString(idIndex),
                )
                results += ScannedAudioFile(
                    folderUri = folderUri,
                    documentUri = childUri.toString(),
                    displayName = displayName ?: "audio",
                    mimeType = mime,
                    fingerprint = AudioFileFingerprint(
                        sizeBytes = it.nullableLong(sizeIndex),
                        modifiedMillis = it.nullableLong(modifiedIndex),
                    ),
                )
            }
        }
        return results
    }

    private fun android.database.Cursor.nullableLong(index: Int): Long? =
        if (index < 0 || isNull(index)) null else getLong(index)
}
