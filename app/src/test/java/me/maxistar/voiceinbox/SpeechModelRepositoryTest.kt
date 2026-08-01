package me.maxistar.voiceinbox

import me.maxistar.voiceinbox.core.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class SpeechModelRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun missingModelIsReported() {
        val repository = repository()

        assertEquals(InstalledSpeechModelState.Missing, repository.inspect())
    }

    @Test
    fun validStagedModelActivatesAndSurvivesRepositoryRecreation() {
        val repository = repository()
        repository.prepareForInstall().getOrThrow()
        writeValidStaging(repository)

        val installed = repository.activate().getOrThrow()
        val recreated = repository()

        assertTrue(installed.isDirectory)
        assertEquals(
            installed.canonicalFile,
            (recreated.inspect() as InstalledSpeechModelState.Ready).directory.canonicalFile,
        )
    }

    @Test
    fun lightweightInspectionDoesNotHashInstalledPayloads() {
        val repository = repository()
        repository.prepareForInstall().getOrThrow()
        writeValidStaging(repository)
        val installed = repository.activate().getOrThrow()
        installed.resolve("model.bin").writeText("other")

        val lightweight = repository.inspectLightweight() as InstalledSpeechModelState.Ready
        assertEquals(InstalledSpeechModelState.Ready.Verification.VERIFIED, lightweight.verification)
        assertTrue(repository.inspect() is InstalledSpeechModelState.Invalid)
        assertTrue(repository.inspectLightweight() is InstalledSpeechModelState.Invalid)
    }

    @Test
    fun legacyInstallationWithoutReceiptIsAvailableLightweight() {
        val repository = repository()
        repository.prepareForInstall().getOrThrow()
        writeValidStaging(repository)
        repository.activate().getOrThrow()
        File(temporaryFolder.root, "models/active-model").delete()

        val legacy = repository.inspectLightweight() as InstalledSpeechModelState.Ready
        assertEquals(InstalledSpeechModelState.Ready.Verification.LEGACY_UNVERIFIED, legacy.verification)
        assertTrue(repository.inspect() is InstalledSpeechModelState.Ready)
        assertEquals(
            InstalledSpeechModelState.Ready.Verification.VERIFIED,
            (repository.inspectLightweight() as InstalledSpeechModelState.Ready).verification,
        )
        assertTrue(File(temporaryFolder.root, "models/active-model").isFile)
    }

    @Test
    fun catalogDescriptorRecognizesExistingProductionLayoutWithoutMigration() {
        val descriptor = SpeechModelCatalog.defaultModel
        val root = File(temporaryFolder.root, "models")
        val installed = File(root, "installed/${descriptor.manifest.version}").apply { mkdirs() }
        descriptor.manifest.files.forEach { installed.resolve(it.name).createNewFile() }
        File(root, "active-model").apply {
            parentFile?.mkdirs()
            writeText(descriptor.manifest.version)
        }

        val state = SpeechModelRepository(root, descriptor.manifest).inspectLightweight()

        assertTrue(state is InstalledSpeechModelState.Ready)
        assertEquals(
            InstalledSpeechModelState.Ready.Verification.VERIFIED,
            (state as InstalledSpeechModelState.Ready).verification,
        )
        assertEquals(installed.canonicalFile, state.directory.canonicalFile)
    }

    @Test
    fun corruptAndIncompleteModelsAreRejected() {
        val repository = repository()
        repository.prepareForInstall().getOrThrow()
        repository.stagingFile(testManifest.files.first()).writeText("wrong")

        assertTrue(repository.activate().isFailure)

        writeValidStaging(repository)
        repository.stagingFile(testManifest.files.last()).delete()

        assertTrue(repository.activate().isFailure)
    }

    @Test
    fun temporaryFilesAndStaleStagingVersionsAreCleaned() {
        val repository = repository()
        val staleDirectory = File(temporaryFolder.root, "models/staging/old-version").apply {
            mkdirs()
        }
        File(staleDirectory, "old.part").writeText("partial")
        val currentPart = repository.temporaryFile(testManifest.files.first()).apply {
            parentFile?.mkdirs()
            writeText("partial")
        }

        repository.cleanupStaleState()

        assertTrue(!staleDirectory.exists())
        assertTrue(!currentPart.exists())
    }

    @Test
    fun versionMismatchDoesNotActivateOldModel() {
        val root = File(temporaryFolder.root, "models")
        val oldDirectory = File(root, "installed/old-version").apply { mkdirs() }
        File(root, "active-model").apply {
            parentFile?.mkdirs()
            writeText("old-version")
        }
        File(oldDirectory, "model.bin").writeText("old")

        assertEquals(InstalledSpeechModelState.Missing, repository().inspect())
        assertTrue(oldDirectory.exists())
    }

    @Test
    fun insufficientStorageFailsPreflight() {
        val repository = SpeechModelRepository(
            root = File(temporaryFolder.root, "models"),
            manifest = testManifest,
            usableSpace = { 0L },
        )

        assertTrue(repository.prepareForInstall().isFailure)
    }

    @Test
    fun freshImportClearsCurrentStagingAndChecksFullModelSpace() {
        val repository = repository()
        repository.prepareForInstall().getOrThrow()
        writeValidStaging(repository)

        repository.prepareFreshImport().getOrThrow()

        assertTrue(repository.stagingDirectory.isDirectory)
        assertTrue(repository.stagingDirectory.listFiles().orEmpty().isEmpty())

        val insufficient = SpeechModelRepository(
            root = File(temporaryFolder.root, "small-models"),
            manifest = testManifest,
            usableSpace = { testManifest.requiredFreeBytes - 1 },
        )
        assertTrue(insufficient.prepareFreshImport().isFailure)
    }

    @Test
    fun verifiedTemporaryFileIsAcceptedIntoStaging() {
        val repository = repository()
        repository.prepareFreshImport().getOrThrow()
        val entry = testManifest.files.first()
        repository.temporaryFile(entry).writeBytes(testFiles.getValue(entry.name))

        val accepted = repository.acceptTemporaryFile(entry).getOrThrow()

        assertEquals(repository.stagingFile(entry), accepted)
        assertTrue(accepted.isFile)
        assertFalse(repository.temporaryFile(entry).exists())
    }

    @Test
    fun activationFailureRestoresPreviousValidModel() {
        val initial = repository()
        initial.prepareForInstall().getOrThrow()
        writeValidStaging(initial)
        initial.activate().getOrThrow()

        val failing = SpeechModelRepository(
            root = File(temporaryFolder.root, "models"),
            manifest = testManifest,
            usableSpace = { Long.MAX_VALUE },
            moveDirectory = { source, destination ->
                if (source.path.contains("${File.separator}staging${File.separator}")) false
                else source.renameTo(destination)
            },
        )
        failing.prepareFreshImport().getOrThrow()
        writeValidStaging(failing)

        assertTrue(failing.activate().isFailure)
        assertTrue(repository().inspect() is InstalledSpeechModelState.Ready)
    }

    @Test
    fun interruptedReplacementRestoresBackupDuringInspection() {
        val repository = repository()
        repository.prepareForInstall().getOrThrow()
        writeValidStaging(repository)
        val installed = repository.activate().getOrThrow()
        val root = File(temporaryFolder.root, "models")
        val backup = File(root, "installed/${testManifest.version}.backup")
        assertTrue(installed.renameTo(backup))
        installed.mkdirs()
        installed.resolve("model.bin").writeText("partial")
        File(root, "activation-model").writeText("replacement")

        val recovered = repository().inspectLightweight()

        assertTrue(recovered is InstalledSpeechModelState.Ready)
        assertFalse(backup.exists())
        assertFalse(File(root, "activation-model").exists())
        assertEquals("model", installed.resolve("model.bin").readText())
    }

    @Test
    fun staleBackupIsRemovedAfterCompletedActivation() {
        val repository = repository()
        repository.prepareForInstall().getOrThrow()
        writeValidStaging(repository)
        val installed = repository.activate().getOrThrow()
        val backup = File(temporaryFolder.root, "models/installed/${testManifest.version}.backup")
        installed.copyRecursively(backup)

        repository.cleanupStaleState()

        assertFalse(backup.exists())
        assertTrue(repository.inspectLightweight() is InstalledSpeechModelState.Ready)
    }

    @Test
    fun activeReceiptRoundTripsBackendAndIdentity() {
        val identity = ActiveSpeechModelIdentity(
            "whisper-tiny-multilingual",
            "whisper-tiny-ggml-f16-r1",
            SpeechModelBackend.WHISPER_CPP,
        )

        assertEquals(identity, ActiveSpeechModelIdentity.parse(identity.serialize()))
        assertEquals(null, ActiveSpeechModelIdentity.parse("not-json"))
    }

    @Test
    fun crossModelActivationCommitsTypedReceiptAndRemovesPreviousPayload() {
        val root = File(temporaryFolder.root, "switch-models")
        val first = SpeechModelRepository(root, descriptor("first", "first-version"), { Long.MAX_VALUE })
        first.prepareFreshImport().getOrThrow()
        writeValidStaging(first)
        val oldDirectory = first.activate().getOrThrow()

        val second = SpeechModelRepository(root, descriptor("second", "second-version"), { Long.MAX_VALUE })
        second.prepareFreshImport().getOrThrow()
        writeValidStaging(second)
        second.activate().getOrThrow()

        assertFalse(oldDirectory.exists())
        assertTrue(second.inspectLightweight() is InstalledSpeechModelState.Ready)
        val receipt = root.resolve("active-model").readText()
        assertTrue(receipt.contains("\"catalogId\":\"second\""))
        assertTrue(receipt.contains("\"backend\":\"PARAKEET_TDT_ONNX\""))
    }

    @Test
    fun failedCrossModelActivationPreservesPreviousPayloadAndReceipt() {
        val root = File(temporaryFolder.root, "failed-switch-models")
        val first = SpeechModelRepository(root, descriptor("first", "first-version"), { Long.MAX_VALUE })
        first.prepareFreshImport().getOrThrow()
        writeValidStaging(first)
        val oldDirectory = first.activate().getOrThrow()
        val oldReceipt = root.resolve("active-model").readText()
        val second = SpeechModelRepository(
            root,
            descriptor("second", "second-version"),
            { Long.MAX_VALUE },
            { source, destination ->
                if (source.path.contains("${File.separator}staging${File.separator}")) false
                else source.renameTo(destination)
            },
        )
        second.prepareFreshImport().getOrThrow()
        writeValidStaging(second)

        assertTrue(second.activate().isFailure)
        assertTrue(oldDirectory.isDirectory)
        assertEquals(oldReceipt, root.resolve("active-model").readText())
    }

    private fun repository() = SpeechModelRepository(
        root = File(temporaryFolder.root, "models"),
        manifest = testManifest,
        usableSpace = { Long.MAX_VALUE },
    )

    private fun writeValidStaging(repository: SpeechModelRepository) {
        testFiles.forEach { (name, contents) ->
            repository.stagingDirectory.mkdirs()
            repository.stagingDirectory.resolve(name).writeBytes(contents)
        }
    }

    private fun descriptor(catalogId: String, version: String) = SpeechModelDescriptor(
        catalogId = catalogId,
        displayName = catalogId,
        backend = SpeechModelBackend.PARAKEET_TDT_ONNX,
        manifest = testManifest.copy(version = version),
        distribution = SpeechModelDistribution(false, true),
        languages = SpeechModelLanguageCoverage("Test", emptyList()),
        maturity = SpeechModelMaturity.EXPERIMENTAL,
        attribution = SpeechModelAttribution("", "", "", "", "", ""),
        supportedPlatforms = setOf(SpeechModelPlatform.ANDROID),
    )

    companion object {
        private val testFiles = linkedMapOf(
            "model.bin" to "model".toByteArray(),
            "config.json" to "{}".toByteArray(),
        )
        private val testManifest = SpeechModelManifest(
            modelId = "example/model",
            version = "test-version",
            repositoryRevision = "0123456789abcdef",
            files = testFiles.map { (name, contents) ->
                SpeechModelFile(
                    name = name,
                    sizeBytes = contents.size.toLong(),
                    sha256 = MessageDigest.getInstance("SHA-256")
                        .digest(contents)
                        .joinToString("") { "%02x".format(it) },
                )
            },
            safetyMarginBytes = 8,
        )
    }
}
