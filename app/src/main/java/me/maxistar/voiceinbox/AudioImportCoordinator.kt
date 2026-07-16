package me.maxistar.voiceinbox

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import me.maxistar.voiceinbox.core.AudioFileState
import me.maxistar.voiceinbox.core.SqlDelightAudioCatalogRepository
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

object AndroidAudioImportConstants {
    const val SOURCE_ID = "android-imported-audio"
    const val DIRECTORY_NAME = "imported-audio"
}

data class AudioImportMetadata(
    val sourceId: String,
    val displayName: String?,
    val mimeType: String?,
    val sizeBytes: Long?,
    val modifiedMillis: Long?,
)

data class AudioImportDestination(
    val documentId: String,
    val existed: Boolean,
)

sealed interface AudioImportItemOutcome {
    val displayName: String

    data class Imported(override val displayName: String) : AudioImportItemOutcome
    data class Duplicate(override val displayName: String) : AudioImportItemOutcome
    data class Rejected(
        override val displayName: String,
        val reason: String,
    ) : AudioImportItemOutcome
}

data class AudioImportSummary(
    val outcomes: List<AudioImportItemOutcome>,
) {
    val importedCount: Int = outcomes.count { it is AudioImportItemOutcome.Imported }
    val duplicateCount: Int = outcomes.count { it is AudioImportItemOutcome.Duplicate }
    val rejectedCount: Int = outcomes.count { it is AudioImportItemOutcome.Rejected }

    fun message(): String = buildString {
        append("Imported $importedCount")
        if (duplicateCount > 0) append(" • $duplicateCount already added")
        if (rejectedCount == 1) {
            val rejection = outcomes.filterIsInstance<AudioImportItemOutcome.Rejected>().single()
            append(" • rejected: ${rejection.reason}")
        } else if (rejectedCount > 1) {
            append(" • $rejectedCount rejected")
        }
        if (outcomes.isEmpty()) append(" • no audio received")
    }
}

interface AudioImportStorage {
    fun metadata(sourceId: String): AudioImportMetadata

    fun destination(receiptKey: String, displayName: String): AudioImportDestination

    fun copy(sourceId: String, destination: AudioImportDestination): Long

    fun delete(destination: AudioImportDestination)
}

interface AudioImportCatalog {
    fun contains(documentId: String): Boolean

    fun insertPending(
        documentId: String,
        displayName: String,
        mimeType: String?,
        sizeBytes: Long,
        importedAtMillis: Long,
    )
}

object AudioImportRules {
    private val supportedExtensions = setOf(
        "wav",
        "m4a",
        "mp3",
        "aac",
        "flac",
        "ogg",
        "opus",
        "3gp",
    )

    fun rejectionReason(metadata: AudioImportMetadata): String? {
        if (metadata.sizeBytes == 0L) return "Audio file is empty"
        val mime = metadata.mimeType?.lowercase()?.substringBefore(';')?.trim()
        val extension = metadata.displayName
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase()
        val supportedMime = mime?.startsWith("audio/") == true || mime == "application/ogg"
        if (!supportedMime && extension !in supportedExtensions) {
            return "Unsupported audio format"
        }
        return null
    }

    fun displayName(metadata: AudioImportMetadata): String =
        metadata.displayName?.trim()?.takeIf(String::isNotEmpty) ?: "shared-audio"

    fun hasStableReceipt(metadata: AudioImportMetadata): Boolean =
        metadata.sizeBytes != null || metadata.modifiedMillis != null

    fun receiptKey(metadata: AudioImportMetadata, unstableToken: String): String {
        val identity = buildString {
            append(metadata.sourceId)
            append('\u0000')
            append(metadata.displayName.orEmpty())
            append('\u0000')
            append(metadata.mimeType.orEmpty())
            append('\u0000')
            append(metadata.sizeBytes?.toString().orEmpty())
            append('\u0000')
            append(metadata.modifiedMillis?.toString().orEmpty())
            if (!hasStableReceipt(metadata)) {
                append('\u0000')
                append(unstableToken)
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    fun safeFilename(displayName: String): String {
        val cleaned = displayName
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('.', '-', '_')
            .take(96)
        return cleaned.ifBlank { "shared-audio" }
    }
}

class AudioImportUseCase(
    private val storage: AudioImportStorage,
    private val catalog: AudioImportCatalog,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val unstableToken: () -> String = { UUID.randomUUID().toString() },
) {
    fun ingest(sourceIds: List<String>): AudioImportSummary {
        val outcomes = sourceIds.distinct().map { sourceId -> ingestOne(sourceId) }
        return AudioImportSummary(outcomes)
    }

    private fun ingestOne(sourceId: String): AudioImportItemOutcome {
        var destination: AudioImportDestination? = null
        var createdCopy = false
        var displayName = "shared-audio"
        return try {
            val metadata = storage.metadata(sourceId)
            displayName = AudioImportRules.displayName(metadata)
            AudioImportRules.rejectionReason(metadata)?.let { reason ->
                return AudioImportItemOutcome.Rejected(displayName, reason)
            }
            val receiptKey = AudioImportRules.receiptKey(metadata, unstableToken())
            destination = storage.destination(receiptKey, displayName)
            if (destination.existed && catalog.contains(destination.documentId)) {
                return AudioImportItemOutcome.Duplicate(displayName)
            }
            if (destination.existed) storage.delete(destination)
            val copiedBytes = storage.copy(sourceId, destination)
            createdCopy = true
            if (copiedBytes <= 0L) {
                storage.delete(destination)
                createdCopy = false
                return AudioImportItemOutcome.Rejected(displayName, "Audio file is empty")
            }
            catalog.insertPending(
                documentId = destination.documentId,
                displayName = displayName,
                mimeType = metadata.mimeType,
                sizeBytes = copiedBytes,
                importedAtMillis = clockMillis(),
            )
            AudioImportItemOutcome.Imported(displayName)
        } catch (error: Throwable) {
            if (createdCopy) destination?.let(storage::delete)
            AudioImportItemOutcome.Rejected(
                displayName = displayName,
                reason = error.message?.takeIf(String::isNotBlank) ?: "Audio import failed",
            )
        }
    }
}

class AndroidAudioImportStorage(
    private val context: Context,
) : AudioImportStorage {
    private val resolver: ContentResolver = context.contentResolver
    private val directory = File(context.filesDir, AndroidAudioImportConstants.DIRECTORY_NAME)

    override fun metadata(sourceId: String): AudioImportMetadata {
        val uri = Uri.parse(sourceId)
        var displayName: String? = null
        var sizeBytes: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    displayName = cursor.stringOrNull(OpenableColumns.DISPLAY_NAME)
                    sizeBytes = cursor.longOrNull(OpenableColumns.SIZE)
                }
            }
        val modifiedMillis = runCatching {
            resolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.longOrNull(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                } else {
                    null
                }
            }
        }.getOrNull()
        return AudioImportMetadata(
            sourceId = sourceId,
            displayName = displayName,
            mimeType = resolver.getType(uri),
            sizeBytes = sizeBytes,
            modifiedMillis = modifiedMillis,
        )
    }

    override fun destination(receiptKey: String, displayName: String): AudioImportDestination {
        check(directory.exists() || directory.mkdirs()) { "Unable to create imported-audio storage" }
        val file = File(directory, "${receiptKey.take(24)}-${AudioImportRules.safeFilename(displayName)}")
        return AudioImportDestination(
            documentId = FileProvider.getUriForFile(
                context,
                "${context.packageName}.files",
                file,
            ).toString(),
            existed = file.exists(),
        )
    }

    override fun copy(sourceId: String, destination: AudioImportDestination): Long {
        check(directory.exists() || directory.mkdirs()) { "Unable to create imported-audio storage" }
        val finalFile = fileFor(destination)
        val temporary = File(directory, "${finalFile.name}.${UUID.randomUUID()}.part")
        try {
            val bytes = resolver.openInputStream(Uri.parse(sourceId))?.use { input ->
                FileOutputStream(temporary).use { output ->
                    val copied = input.copyTo(output)
                    output.fd.sync()
                    copied
                }
            } ?: error("Shared audio is unreadable")
            if (bytes <= 0L) {
                temporary.delete()
                return bytes
            }
            check(temporary.renameTo(finalFile)) { "Unable to finalize imported audio" }
            return bytes
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    override fun delete(destination: AudioImportDestination) {
        fileFor(destination).delete()
    }

    fun cleanupOrphans(catalog: SqlDelightAudioCatalogRepository) {
        if (!directory.exists()) return
        directory.listFiles()?.filter { it.name.endsWith(".part") }?.forEach(File::delete)
        val catalogUris = catalog.importedFiles(AndroidAudioImportConstants.SOURCE_ID)
            .mapTo(mutableSetOf()) { it.documentUri }
        directory.listFiles()
            ?.filter(File::isFile)
            ?.forEach { file ->
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.files",
                    file,
                ).toString()
                if (uri !in catalogUris) file.delete()
            }
    }

    private fun fileFor(destination: AudioImportDestination): File {
        val uri = Uri.parse(destination.documentId)
        val filename = uri.lastPathSegment?.substringAfterLast('/')
            ?: error("Invalid imported-audio destination")
        return File(directory, filename)
    }
}

class SqlDelightAudioImportCatalog(
    private val catalog: SqlDelightAudioCatalogRepository,
) : AudioImportCatalog {
    override fun contains(documentId: String): Boolean =
        catalog.catalogFile(AndroidAudioImportConstants.SOURCE_ID, documentId) != null

    override fun insertPending(
        documentId: String,
        displayName: String,
        mimeType: String?,
        sizeBytes: Long,
        importedAtMillis: Long,
    ) {
        catalog.upsertImportedFile(
            folderUri = AndroidAudioImportConstants.SOURCE_ID,
            documentUri = documentId,
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            importedAtMillis = importedAtMillis,
            state = AudioFileState.PENDING,
            lastError = null,
            processedAtMillis = null,
            transcriptText = null,
            durationUs = null,
        )
    }
}

private fun android.database.Cursor.stringOrNull(columnName: String): String? {
    val index = getColumnIndex(columnName)
    return if (index >= 0 && !isNull(index)) getString(index) else null
}

private fun android.database.Cursor.longOrNull(columnName: String): Long? {
    val index = getColumnIndex(columnName)
    return if (index >= 0 && !isNull(index)) getLong(index) else null
}

object AudioShareIntentParser {
    fun isShareIntent(intent: Intent): Boolean =
        intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE

    @Suppress("DEPRECATION")
    fun streamUris(intent: Intent): List<Uri> {
        if (!isShareIntent(intent)) return emptyList()
        val uris = mutableListOf<Uri>()
        intent.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index).uri?.let(uris::add)
            }
        }
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let(uris::addAll)
        } else {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(uris::add)
        }
        return uris.distinctBy(Uri::toString)
    }
}
