package me.maxistar.voiceinbox

import me.maxistar.voiceinbox.core.*

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

sealed interface InstalledSpeechModelState {
    data class Ready(val directory: File) : InstalledSpeechModelState
    data object Missing : InstalledSpeechModelState
    data class Invalid(val reason: String) : InstalledSpeechModelState
}

class SpeechModelRepository(
    private val root: File,
    val manifest: SpeechModelManifest = EmbeddedSpeechModel.manifest,
    private val usableSpace: (File) -> Long = { it.usableSpace },
) {
    private val stagingRoot = File(root, "staging")
    private val installedRoot = File(root, "installed")
    private val activeVersionFile = File(root, "active-model")

    val stagingDirectory: File
        get() = File(stagingRoot, manifest.version)

    val installedDirectory: File
        get() = File(installedRoot, manifest.version)

    fun inspect(): InstalledSpeechModelState {
        val activeVersion = activeVersionFile.takeIf(File::isFile)?.readText()?.trim()
        if (activeVersion == manifest.version) {
            return validateDirectory(installedDirectory)
        }

        return when (val installed = validateDirectory(installedDirectory)) {
            is InstalledSpeechModelState.Ready -> {
                writeActiveVersion(manifest.version)
                installed
            }
            is InstalledSpeechModelState.Invalid -> installed
            InstalledSpeechModelState.Missing -> InstalledSpeechModelState.Missing
        }
    }

    fun prepareForInstall(): Result<Unit> = runCatching {
        root.mkdirs()
        stagingRoot.mkdirs()
        installedRoot.mkdirs()
        cleanupTemporaryFiles(root)
        stagingRoot.listFiles()
            ?.filter { it.name != manifest.version }
            ?.forEach(File::deleteRecursively)
        stagingDirectory.mkdirs()

        val remainingBytes = manifest.files
            .filterNot { isValidFile(File(stagingDirectory, it.name), it) }
            .sumOf { it.sizeBytes }
        val required = remainingBytes + manifest.safetyMarginBytes
        val available = usableSpace(root)
        check(available >= required) {
            "Not enough storage: ${formatBytes(required)} required, ${formatBytes(available)} available"
        }
    }

    fun stagingFile(entry: SpeechModelFile): File = File(stagingDirectory, entry.name)

    fun temporaryFile(entry: SpeechModelFile): File =
        File(stagingDirectory, "${entry.name}.part")

    fun isValidStagingFile(entry: SpeechModelFile): Boolean =
        isValidFile(stagingFile(entry), entry)

    fun verifyFile(file: File, entry: SpeechModelFile): Result<Unit> = runCatching {
        check(file.isFile) { "${entry.name} is missing" }
        check(file.length() == entry.sizeBytes) {
            "${entry.name} has size ${file.length()}, expected ${entry.sizeBytes}"
        }
        val actualHash = sha256(file)
        check(actualHash == entry.sha256) {
            "${entry.name} checksum mismatch"
        }
    }

    fun activate(): Result<File> = runCatching {
        val validation = validateDirectory(stagingDirectory)
        check(validation is InstalledSpeechModelState.Ready) {
            (validation as? InstalledSpeechModelState.Invalid)?.reason ?: "Staged model is incomplete"
        }

        if (installedDirectory.exists()) {
            installedDirectory.deleteRecursively()
        }
        installedRoot.mkdirs()
        check(stagingDirectory.renameTo(installedDirectory)) {
            "Failed to activate downloaded model"
        }
        writeActiveVersion(manifest.version)

        installedRoot.listFiles()
            ?.filter { it.name != manifest.version }
            ?.forEach(File::deleteRecursively)
        installedDirectory
    }

    fun cleanupFailedCurrentFile(entry: SpeechModelFile) {
        temporaryFile(entry).delete()
        val finalFile = stagingFile(entry)
        if (!isValidFile(finalFile, entry)) {
            finalFile.delete()
        }
    }

    fun cleanupStaleState() {
        root.mkdirs()
        cleanupTemporaryFiles(root)
        stagingRoot.listFiles()
            ?.filter { it.name != manifest.version }
            ?.forEach(File::deleteRecursively)
    }

    private fun validateDirectory(directory: File): InstalledSpeechModelState {
        if (!directory.isDirectory) {
            return InstalledSpeechModelState.Missing
        }
        for (entry in manifest.files) {
            val result = verifyFile(File(directory, entry.name), entry)
            if (result.isFailure) {
                return InstalledSpeechModelState.Invalid(
                    result.exceptionOrNull()?.message ?: "${entry.name} is invalid",
                )
            }
        }
        return InstalledSpeechModelState.Ready(directory)
    }

    private fun isValidFile(file: File, entry: SpeechModelFile): Boolean =
        verifyFile(file, entry).isSuccess

    private fun writeActiveVersion(version: String) {
        root.mkdirs()
        val temporary = File(root, "active-model.${UUID.randomUUID()}.tmp")
        temporary.writeText(version)
        runCatching {
            Files.move(
                temporary.toPath(),
                activeVersionFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            temporary.delete()
            throw IllegalStateException("Failed to update active model metadata", it)
        }
    }

    private fun cleanupTemporaryFiles(directory: File) {
        directory.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                cleanupTemporaryFiles(file)
            } else if (file.name.endsWith(".part") || file.name.endsWith(".tmp")) {
                file.delete()
            }
        }
    }

    companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun formatBytes(bytes: Long): String {
            val mib = bytes.toDouble() / (1024.0 * 1024.0)
            return "%.0f MiB".format(mib)
        }
    }
}
