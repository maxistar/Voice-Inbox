package me.maxistar.voiceinbox

import me.maxistar.voiceinbox.core.SpeechModelManifest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.io.IOException

data class SpeechModelImportProgress(
    val bytesCopied: Long,
    val message: String,
)

class SpeechModelLocalImporter(
    private val repository: SpeechModelRepository,
    private val requiredDocuments: (String, SpeechModelManifest) -> Map<String, String>,
    private val openInputStream: (String) -> InputStream?,
) {
    constructor(
        resolver: android.content.ContentResolver,
        repository: SpeechModelRepository,
    ) : this(
        repository = repository,
        requiredDocuments = { treeUri, manifest ->
            SpeechModelDirectoryReader(resolver).requiredDocuments(android.net.Uri.parse(treeUri), manifest)
        },
        openInputStream = { resolver.openInputStream(android.net.Uri.parse(it)) },
    )

    suspend fun import(
        treeUri: String,
        progress: suspend (SpeechModelImportProgress) -> Unit,
    ): Result<java.io.File> {
        return try {
            repository.prepareFreshImport().getOrThrow()
            val sources = requiredDocuments(treeUri, repository.manifest)
            var completedBytes = 0L
            progress(SpeechModelImportProgress(0, "Preparing local speech model"))

            repository.manifest.files.forEach { entry ->
                currentCoroutineContext().ensureActive()
                repository.cleanupFailedCurrentFile(entry)
                val source = sources.getValue(entry.name)
                val temporary = repository.temporaryFile(entry)
                try {
                    val input = openInputStream(source)
                        ?: throw IOException("Cannot read ${entry.name}")
                    input.buffered().use { stream ->
                        temporary.outputStream().buffered().use { output ->
                            val buffer = ByteArray(128 * 1024)
                            var fileBytes = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = stream.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                fileBytes += read
                                check(fileBytes <= entry.sizeBytes) {
                                    "${entry.name} is larger than expected"
                                }
                                progress(
                                    SpeechModelImportProgress(
                                        completedBytes + fileBytes,
                                        "Copying ${entry.name}",
                                    ),
                                )
                            }
                        }
                    }
                    repository.acceptTemporaryFile(entry).getOrThrow()
                    completedBytes += entry.sizeBytes
                    progress(SpeechModelImportProgress(completedBytes, "Verified ${entry.name}"))
                } catch (error: Throwable) {
                    repository.cleanupFailedCurrentFile(entry)
                    throw error
                }
            }

            progress(
                SpeechModelImportProgress(
                    repository.manifest.totalSizeBytes,
                    "Activating speech model",
                ),
            )
            Result.success(repository.activate().getOrThrow())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }
}
