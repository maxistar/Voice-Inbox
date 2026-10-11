package me.maxistar.voiceinbox

import me.maxistar.voiceinbox.core.TranscriptAppendOutcome
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

    fun append(uri: Uri, text: String): TranscriptAppendOutcome {
        val stream = try {
            resolver.openOutputStream(uri, "wa")
        } catch (_: Throwable) {
            return TranscriptAppendOutcome.NOT_WRITTEN
        } ?: return TranscriptAppendOutcome.NOT_WRITTEN

        return try {
            stream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(text)
                writer.flush()
            }
            TranscriptAppendOutcome.SUCCEEDED
        } catch (_: Throwable) {
            TranscriptAppendOutcome.UNKNOWN
        }
    }

    fun readTail(uri: Uri, maxBytes: Int = 4096): String {
        val all = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return ""
        val start = (all.size - maxBytes).coerceAtLeast(0)
        for (candidate in start..all.size) {
            val text = all.copyOfRange(candidate, all.size).toString(Charsets.UTF_8)
            if (!text.contains('\uFFFD')) return text
        }
        return all.copyOfRange(start, all.size).toString(Charsets.UTF_8)
    }
}
