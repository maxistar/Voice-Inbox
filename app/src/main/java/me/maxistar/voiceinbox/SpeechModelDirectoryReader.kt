package me.maxistar.voiceinbox

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import me.maxistar.voiceinbox.core.SpeechModelManifest
import java.io.IOException

data class SpeechModelSourceDocument(
    val name: String,
    val uri: String,
    val mimeType: String?,
)

class SpeechModelDirectoryReader(
    private val resolver: ContentResolver,
) {
    fun requiredDocuments(
        treeUri: Uri,
        manifest: SpeechModelManifest,
    ): Map<String, String> {
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
            .getOrElse { throw IOException("The selected model folder is not readable", it) }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeId)
        val cursor = resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null,
            null,
            null,
        ) ?: throw IOException("The selected model folder cannot be enumerated")
        val documents = cursor.use {
            val id = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val name = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mime = it.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            buildList {
                while (it.moveToNext()) {
                    val displayName = it.getString(name) ?: continue
                    add(
                        SpeechModelSourceDocument(
                            name = displayName,
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, it.getString(id)).toString(),
                            mimeType = if (mime >= 0 && !it.isNull(mime)) it.getString(mime) else null,
                        ),
                    )
                }
            }
        }
        return matchRequiredDocuments(documents, manifest)
    }

    companion object {
        fun matchRequiredDocuments(
            documents: List<SpeechModelSourceDocument>,
            manifest: SpeechModelManifest,
        ): Map<String, String> {
            val regularFiles = documents.filter {
                it.mimeType != DocumentsContract.Document.MIME_TYPE_DIR
            }
            return manifest.files.associate { entry ->
                val matches = regularFiles.filter { it.name == entry.name }
                when (matches.size) {
                    0 -> throw IOException("${entry.name} is missing from the selected model folder")
                    1 -> entry.name to matches.single().uri
                    else -> throw IOException("Multiple files named ${entry.name} were found")
                }
            }
        }
    }
}
