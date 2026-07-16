package me.maxistar.voiceinbox

import me.maxistar.voiceinbox.core.*

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

sealed interface InstalledSpeechModelState {
    data class Ready(
        val directory: File,
        val verification: Verification = Verification.VERIFIED,
    ) : InstalledSpeechModelState {
        enum class Verification {
            VERIFIED,
            LEGACY_UNVERIFIED,
        }
    }
    data object Missing : InstalledSpeechModelState
    data class Invalid(val reason: String) : InstalledSpeechModelState
}

class SpeechModelRepository(
    private val root: File,
    val manifest: SpeechModelManifest = EmbeddedSpeechModel.manifest,
    private val usableSpace: (File) -> Long = { it.usableSpace },
    private val moveDirectory: (File, File) -> Boolean = { source, destination ->
        source.renameTo(destination)
    },
) {
    private val stagingRoot = File(root, "staging")
    private val installedRoot = File(root, "installed")
    private val activeVersionFile = File(root, "active-model")
    private val invalidModelFile = File(root, "invalid-model")
    private val backupDirectory = File(installedRoot, "${manifest.version}.backup")
    private val activationMarker = File(root, "activation-model")

    val stagingDirectory: File
        get() = File(stagingRoot, manifest.version)

    val installedDirectory: File
        get() = File(installedRoot, manifest.version)

    fun inspectLightweight(): InstalledSpeechModelState {
        recoverInterruptedActivation()
        invalidModelFile.takeIf(File::isFile)?.readText()?.trim()?.takeIf(String::isNotEmpty)?.let {
            return InstalledSpeechModelState.Invalid(it)
        }
        if (!installedDirectory.isDirectory) return InstalledSpeechModelState.Missing
        val missing = manifest.files.firstOrNull { !File(installedDirectory, it.name).isFile }
        return if (missing == null) {
            InstalledSpeechModelState.Ready(
                directory = installedDirectory,
                verification = if (activeVersionFile.takeIf(File::isFile)?.readText()?.trim() == manifest.version) {
                    InstalledSpeechModelState.Ready.Verification.VERIFIED
                } else {
                    InstalledSpeechModelState.Ready.Verification.LEGACY_UNVERIFIED
                },
            )
        } else {
            InstalledSpeechModelState.Invalid("${missing.name} is missing")
        }
    }

    fun inspect(): InstalledSpeechModelState {
        recoverInterruptedActivation()
        val activeVersion = activeVersionFile.takeIf(File::isFile)?.readText()?.trim()
        if (activeVersion == manifest.version) {
            return recordValidation(validateDirectory(installedDirectory))
        }

        return when (val installed = validateDirectory(installedDirectory)) {
            is InstalledSpeechModelState.Ready -> {
                writeActiveVersion(manifest.version)
                invalidModelFile.delete()
                installed
            }
            is InstalledSpeechModelState.Invalid -> installed.also { writeInvalidReason(it.reason) }
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

    fun prepareFreshImport(): Result<Unit> = runCatching {
        root.mkdirs()
        stagingRoot.mkdirs()
        installedRoot.mkdirs()
        recoverInterruptedActivation()
        cleanupTemporaryFiles(root)
        stagingRoot.listFiles()?.forEach(File::deleteRecursively)
        check(stagingDirectory.mkdirs() || stagingDirectory.isDirectory) {
            "Failed to create model import staging directory"
        }
        val required = manifest.totalSizeBytes + manifest.safetyMarginBytes
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

    fun acceptTemporaryFile(entry: SpeechModelFile): Result<File> = runCatching {
        val temporary = temporaryFile(entry)
        verifyFile(temporary, entry).getOrThrow()
        val destination = stagingFile(entry)
        destination.delete()
        check(temporary.renameTo(destination)) { "Failed to accept ${entry.name}" }
        destination
    }

    fun activate(): Result<File> = runCatching {
        recoverInterruptedActivation()
        val validation = validateDirectory(stagingDirectory)
        check(validation is InstalledSpeechModelState.Ready) {
            (validation as? InstalledSpeechModelState.Invalid)?.reason ?: "Staged model is incomplete"
        }

        installedRoot.mkdirs()
        backupDirectory.deleteRecursively()
        val replacing = installedDirectory.exists()
        activationMarker.writeText(if (replacing) MARKER_REPLACEMENT else MARKER_FRESH)
        if (replacing) {
            check(moveDirectory(installedDirectory, backupDirectory)) {
                "Failed to back up installed model"
            }
        }
        try {
            check(moveDirectory(stagingDirectory, installedDirectory)) {
                "Failed to activate staged model"
            }
            writeActiveVersion(manifest.version)
            invalidModelFile.delete()
            activationMarker.delete()
            backupDirectory.deleteRecursively()
        } catch (error: Throwable) {
            installedDirectory.deleteRecursively()
            if (replacing && backupDirectory.exists()) {
                check(moveDirectory(backupDirectory, installedDirectory)) {
                    "Failed to restore previous speech model"
                }
                writeActiveVersion(manifest.version)
                invalidModelFile.delete()
            } else {
                activeVersionFile.delete()
            }
            activationMarker.delete()
            throw error
        }

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
        recoverInterruptedActivation()
        cleanupTemporaryFiles(root)
        stagingRoot.listFiles()
            ?.filter { it.name != manifest.version }
            ?.forEach(File::deleteRecursively)
    }

    internal fun recoverInterruptedActivation() {
        if (!activationMarker.isFile) {
            if (backupDirectory.exists()) {
                if (installedDirectory.exists()) {
                    backupDirectory.deleteRecursively()
                } else if (moveDirectory(backupDirectory, installedDirectory)) {
                    writeActiveVersion(manifest.version)
                }
            }
            return
        }

        val replacement = activationMarker.readText().trim() == MARKER_REPLACEMENT
        if (replacement && backupDirectory.exists()) {
            installedDirectory.deleteRecursively()
            check(moveDirectory(backupDirectory, installedDirectory)) {
                "Failed to recover previous speech model"
            }
            writeActiveVersion(manifest.version)
            invalidModelFile.delete()
        } else if (!replacement) {
            installedDirectory.deleteRecursively()
            activeVersionFile.delete()
        }
        activationMarker.delete()
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
        return InstalledSpeechModelState.Ready(
            directory = directory,
            verification = InstalledSpeechModelState.Ready.Verification.VERIFIED,
        )
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

    private fun recordValidation(state: InstalledSpeechModelState): InstalledSpeechModelState {
        when (state) {
            is InstalledSpeechModelState.Ready -> invalidModelFile.delete()
            is InstalledSpeechModelState.Invalid -> writeInvalidReason(state.reason)
            InstalledSpeechModelState.Missing -> Unit
        }
        return state
    }

    private fun writeInvalidReason(reason: String) {
        root.mkdirs()
        invalidModelFile.writeText(reason)
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
        private const val MARKER_REPLACEMENT = "replacement"
        private const val MARKER_FRESH = "fresh"
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
