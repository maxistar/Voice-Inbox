package me.maxistar.voiceinbox

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import me.maxistar.voiceinbox.core.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

data class SpeechModelSourceDocument(
    val name: String,
    val uri: String,
    val mimeType: String?,
)

data class SpeechModelFolderPackage(
    val descriptor: SpeechModelDescriptor,
    val requiredDocuments: Map<String, String>,
)

class SpeechModelDirectoryReader(
    private val resolver: ContentResolver,
) {
    fun inspectPackage(treeUri: Uri): SpeechModelFolderPackage {
        val documents = listDocuments(treeUri)
        val manifests = documents.filter {
            it.mimeType != DocumentsContract.Document.MIME_TYPE_DIR &&
                it.name == SpeechModelCatalog.PACKAGE_MANIFEST_FILENAME
        }
        if (manifests.size > 1) throw IOException("Multiple model package manifests were found")
        val descriptor = if (manifests.isEmpty()) {
            resolveLegacyParakeet(documents)
        } else {
            val manifestText = resolver.openInputStream(Uri.parse(manifests.single().uri))?.use { input ->
                readBounded(input, MAX_PACKAGE_MANIFEST_BYTES).toString(Charsets.UTF_8)
            } ?: throw IOException("The model package manifest cannot be read")
            resolvePackageIdentity(manifestText)
        }
        return SpeechModelFolderPackage(descriptor, matchRequiredDocuments(documents, descriptor.manifest))
    }

    fun requiredDocuments(treeUri: Uri, manifest: SpeechModelManifest): Map<String, String> =
        matchRequiredDocuments(listDocuments(treeUri), manifest)

    private fun listDocuments(treeUri: Uri): List<SpeechModelSourceDocument> {
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
            null, null, null,
        ) ?: throw IOException("The selected model folder cannot be enumerated")
        return cursor.use {
            val id = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val name = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mime = it.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            buildList {
                while (it.moveToNext()) {
                    val displayName = it.getString(name) ?: continue
                    add(
                        SpeechModelSourceDocument(
                            displayName,
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, it.getString(id)).toString(),
                            if (mime >= 0 && !it.isNull(mime)) it.getString(mime) else null,
                        ),
                    )
                }
            }
        }
    }

    companion object {
        const val MAX_PACKAGE_MANIFEST_BYTES = 16 * 1024

        internal fun readBounded(input: InputStream, maximumBytes: Int): ByteArray {
            require(maximumBytes >= 0) { "maximumBytes must not be negative" }
            val output = ByteArrayOutputStream(minOf(maximumBytes, 4 * 1024))
            val buffer = ByteArray(minOf(4 * 1024, maximumBytes + 1).coerceAtLeast(1))
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total += read
                if (total > maximumBytes) {
                    throw IOException("The model package manifest is too large")
                }
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }

        fun resolvePackageIdentity(json: String): SpeechModelDescriptor {
            val allowed = setOf("schemaVersion", "catalogId", "modelVersion")
            val trimmed = json.trim()
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
                throw IOException("The model package manifest is malformed")
            }
            val keys = Regex("\"([^\"]+)\"\\s*:").findAll(trimmed).map { it.groupValues[1] }.toList()
            if (keys.size != allowed.size || keys.toSet() != allowed) {
                throw IOException("The model package manifest has an unsupported structure")
            }
            fun string(name: String): String? =
                Regex("\"$name\"\\s*:\\s*\"([^\"]+)\"").find(trimmed)?.groupValues?.get(1)
            val schemaVersion = Regex("\"schemaVersion\"\\s*:\\s*(\\d+)")
                .find(trimmed)?.groupValues?.get(1)?.toIntOrNull()
            val identity = SpeechModelPackageIdentity(
                schemaVersion = schemaVersion ?: throw IOException("The model package manifest is malformed"),
                catalogId = string("catalogId") ?: throw IOException("The model package manifest is malformed"),
                modelVersion = string("modelVersion") ?: throw IOException("The model package manifest is malformed"),
            )
            return SpeechModelCatalog.resolvePackage(identity, SpeechModelPlatform.ANDROID)
                ?: throw IOException("This model package is unknown or unsupported on Android")
        }

        fun resolveLegacyParakeet(documents: List<SpeechModelSourceDocument>): SpeechModelDescriptor {
            val regularNames = documents
                .filter { it.mimeType != DocumentsContract.Document.MIME_TYPE_DIR }
                .map { it.name }
            val expected = SpeechModelCatalog.defaultModel.manifest.files.map { it.name }
            if (regularNames.size != expected.size || regularNames.toSet() != expected.toSet()) {
                throw IOException("voice-inbox-model.json is missing from the selected folder")
            }
            return SpeechModelCatalog.defaultModel
        }

        fun matchRequiredDocuments(
            documents: List<SpeechModelSourceDocument>,
            manifest: SpeechModelManifest,
        ): Map<String, String> {
            val regularFiles = documents.filter { it.mimeType != DocumentsContract.Document.MIME_TYPE_DIR }
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
