package me.maxistar.watchface.notesrecognition

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException

data class DocumentMetadata(
    val displayName: String,
    val mimeType: String?,
    val lastModifiedMillis: Long?,
)

class DocumentAccess(
    private val resolver: ContentResolver,
) {
    fun metadata(uri: Uri): DocumentMetadata {
        var name: String? = null
        var modified: Long? = null
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, "last_modified"),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    .takeIf { it >= 0 }
                    ?.let { name = cursor.getString(it) }
                cursor.getColumnIndex("last_modified")
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let { modified = cursor.getLong(it) }
            }
        }
        return DocumentMetadata(
            displayName = FileSelectionRules.displayName(name, uri.lastPathSegment),
            mimeType = resolver.getType(uri),
            lastModifiedMillis = modified,
        )
    }

    fun requireReadable(uri: Uri) {
        resolver.openFileDescriptor(uri, "r")?.use { return }
        throw IOException("The selected audio file is not readable")
    }

    fun requireAppendable(uri: Uri) {
        resolver.openFileDescriptor(uri, "wa")?.use { return }
        throw IOException("The selected text file is not writable")
    }

    fun append(uri: Uri, text: String) {
        resolver.openOutputStream(uri, "wa")?.bufferedWriter(Charsets.UTF_8)?.use {
            it.write(text)
        } ?: throw IOException("Unable to append to the selected text file")
    }

    fun readTail(uri: Uri, maxBytes: Int = 4096): String {
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val all = input.readBytes()
            all.copyOfRange((all.size - maxBytes).coerceAtLeast(0), all.size)
        } ?: return ""
        return bytes.toString(Charsets.UTF_8)
    }
}
