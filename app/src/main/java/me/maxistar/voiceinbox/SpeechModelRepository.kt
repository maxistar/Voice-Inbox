package me.maxistar.voiceinbox

import me.maxistar.voiceinbox.core.*

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

data class ActiveSpeechModelIdentity(
    val catalogId: String,
    val modelVersion: String,
    val backend: SpeechModelBackend,
) {
    fun serialize(): String =
        """{"schemaVersion":1,"catalogId":"$catalogId","modelVersion":"$modelVersion","backend":"${backend.name}"}"""

    companion object {
        fun parse(text: String): ActiveSpeechModelIdentity? {
            fun string(name: String): String? =
                Regex("\\\"$name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(text)?.groupValues?.get(1)
            if (Regex("\\\"schemaVersion\\\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1) != "1") return null
            val backend = string("backend")?.let { runCatching { SpeechModelBackend.valueOf(it) }.getOrNull() }
                ?: return null
            return ActiveSpeechModelIdentity(
                catalogId = string("catalogId") ?: return null,
                modelVersion = string("modelVersion") ?: return null,
                backend = backend,
            )
        }
    }
}

private data class ActivationTransaction(
    val previous: ActiveSpeechModelIdentity?,
    val candidateCatalogId: String,
    val candidateVersion: String,
)

sealed interface InstalledSpeechModelState {
    data class Ready(
        val directory: File,
        val descriptor: SpeechModelDescriptor,
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
    val descriptor: SpeechModelDescriptor,
    private val usableSpace: (File) -> Long = { it.usableSpace },
    private val moveDirectory: (File, File) -> Boolean = { source, destination ->
        source.renameTo(destination)
    },
) {
    constructor(
        root: File,
        manifest: SpeechModelManifest,
        usableSpace: (File) -> Long = { it.usableSpace },
        moveDirectory: (File, File) -> Boolean = { source, destination -> source.renameTo(destination) },
    ) : this(root, descriptorForManifest(manifest), usableSpace, moveDirectory)

    val manifest: SpeechModelManifest = descriptor.manifest
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
                descriptor = descriptor,
                verification = if (readActiveIdentity()?.let {
                        it.catalogId == descriptor.catalogId && it.modelVersion == manifest.version
                    } == true) {
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
        val activeIdentity = readActiveIdentity()
        if (activeIdentity?.catalogId == descriptor.catalogId && activeIdentity.modelVersion == manifest.version) {
            return recordValidation(validateDirectory(installedDirectory))
        }

        return when (val installed = validateDirectory(installedDirectory)) {
            is InstalledSpeechModelState.Ready -> {
                writeActiveIdentity(descriptor)
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
        val previousIdentity = readActiveIdentity()
        val previousDescriptor = previousIdentity?.let {
            SpeechModelCatalog.resolveInstallation(it.catalogId, it.modelVersion)
        }
        val previousDirectory = previousDescriptor?.let { File(installedRoot, it.manifest.version) }
        backupDirectory.deleteRecursively()
        val replacing = installedDirectory.exists()
        writeActivationMarker(previousIdentity, descriptor)
        if (replacing) {
            check(moveDirectory(installedDirectory, backupDirectory)) {
                "Failed to back up installed model"
            }
        }
        try {
            check(moveDirectory(stagingDirectory, installedDirectory)) {
                "Failed to activate staged model"
            }
            writeActiveIdentity(descriptor)
            invalidModelFile.delete()
            activationMarker.delete()
            backupDirectory.deleteRecursively()
        } catch (error: Throwable) {
            installedDirectory.deleteRecursively()
            if (replacing && backupDirectory.exists()) {
                check(moveDirectory(backupDirectory, installedDirectory)) {
                    "Failed to restore previous speech model"
                }
                previousIdentity?.let(::writeActiveIdentity)
                invalidModelFile.delete()
            } else {
                if (previousIdentity == null) activeVersionFile.delete()
            }
            activationMarker.delete()
            throw error
        }

        installedRoot.listFiles()
            ?.filter { it != previousDirectory && it.name != manifest.version }
            ?.forEach(File::deleteRecursively)
        previousDirectory?.takeIf { it != installedDirectory }?.deleteRecursively()
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
                    writeActiveIdentity(descriptor)
                }
            }
            return
        }

        if (activationMarker.readText().trim() == "replacement") {
            installedDirectory.deleteRecursively()
            if (backupDirectory.exists()) {
                check(moveDirectory(backupDirectory, installedDirectory)) {
                    "Failed to recover previous speech model"
                }
                writeActiveIdentity(descriptor)
                invalidModelFile.delete()
            }
            activationMarker.delete()
            return
        }

        val marker = readActivationMarker() ?: run {
            activationMarker.delete()
            return
        }
        val candidateDescriptor = SpeechModelCatalog.resolveInstallation(
            marker.candidateCatalogId,
            marker.candidateVersion,
        )
        val candidateDirectory = File(installedRoot, marker.candidateVersion)
        val candidateBackup = File(installedRoot, "${marker.candidateVersion}.backup")
        val active = readActiveIdentity()
        if (active?.catalogId == marker.candidateCatalogId && active.modelVersion == marker.candidateVersion) {
            activationMarker.delete()
            candidateBackup.deleteRecursively()
            installedRoot.listFiles()?.filter { it.name != marker.candidateVersion }?.forEach(File::deleteRecursively)
            return
        }
        candidateDirectory.deleteRecursively()
        if (candidateBackup.exists()) {
            check(moveDirectory(candidateBackup, candidateDirectory)) {
                "Failed to recover previous speech model"
            }
        }
        if (marker.previous == null) activeVersionFile.delete() else writeActiveIdentity(marker.previous)
        if (candidateDescriptor != null) invalidModelFile.delete()
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
            descriptor = descriptor,
            verification = InstalledSpeechModelState.Ready.Verification.VERIFIED,
        )
    }

    private fun isValidFile(file: File, entry: SpeechModelFile): Boolean =
        verifyFile(file, entry).isSuccess

    private fun readActiveIdentity(): ActiveSpeechModelIdentity? = readActiveIdentity(activeVersionFile)

    private fun writeActivationMarker(
        previous: ActiveSpeechModelIdentity?,
        candidate: SpeechModelDescriptor,
    ) {
        val previousText = previous?.serialize()?.replace("\n", "") ?: "null"
        activationMarker.writeText(
            """{"schemaVersion":1,"previous":$previousText,"candidateCatalogId":"${candidate.catalogId}","candidateVersion":"${candidate.manifest.version}"}""",
        )
    }

    private fun readActivationMarker(): ActivationTransaction? {
        val text = activationMarker.takeIf(File::isFile)?.readText().orEmpty()
        fun string(name: String): String? =
            Regex("\\\"$name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(text)?.groupValues?.get(1)
        val previousObject = Regex("\\\"previous\\\"\\s*:\\s*(\\{.*?})\\s*,\\s*\\\"candidateCatalogId", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.groupValues?.get(1)
        return ActivationTransaction(
            previous = previousObject?.let(ActiveSpeechModelIdentity::parse),
            candidateCatalogId = string("candidateCatalogId") ?: return null,
            candidateVersion = string("candidateVersion") ?: return null,
        )
    }

    private fun writeActiveIdentity(descriptor: SpeechModelDescriptor) = writeActiveIdentity(
        ActiveSpeechModelIdentity(descriptor.catalogId, descriptor.manifest.version, descriptor.backend),
    )

    private fun writeActiveIdentity(identity: ActiveSpeechModelIdentity) {
        root.mkdirs()
        val temporary = File(root, "active-model.${UUID.randomUUID()}.tmp")
        temporary.writeText(identity.serialize())
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
        fun forActive(root: File): SpeechModelRepository {
            val descriptor = readActiveIdentity(File(root, "active-model"))?.let { identity ->
                SpeechModelCatalog.resolveInstallation(identity.catalogId, identity.modelVersion)
                    ?.takeIf { it.backend == identity.backend }
            } ?: SpeechModelCatalog.defaultModel
            return SpeechModelRepository(root, descriptor)
        }

        private fun descriptorForManifest(manifest: SpeechModelManifest): SpeechModelDescriptor =
            SpeechModelCatalog.models.firstOrNull { it.manifest == manifest } ?: SpeechModelDescriptor(
                catalogId = manifest.modelId,
                displayName = manifest.modelId,
                backend = SpeechModelBackend.PARAKEET_TDT_ONNX,
                manifest = manifest,
                distribution = SpeechModelDistribution(false, true),
                languages = SpeechModelLanguageCoverage("Test model", emptyList()),
                maturity = SpeechModelMaturity.EXPERIMENTAL,
                attribution = SpeechModelAttribution("", "", "", "", "", ""),
                supportedPlatforms = setOf(SpeechModelPlatform.ANDROID),
            )

        private fun readActiveIdentity(file: File): ActiveSpeechModelIdentity? {
            val text = file.takeIf(File::isFile)?.readText()?.trim().orEmpty()
            if (text.isEmpty()) return null
            if (!text.startsWith("{")) {
                val legacy = SpeechModelCatalog.models.singleOrNull { it.manifest.version == text }
                    ?: return null
                return ActiveSpeechModelIdentity(legacy.catalogId, legacy.manifest.version, legacy.backend)
            }
            return ActiveSpeechModelIdentity.parse(text)
        }

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
