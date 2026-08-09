import Foundation
import Shared
import UniformTypeIdentifiers
import UIKit
import XCTest
@testable import VoiceInbox

final class DeferredSpeechModelLoadingTests: XCTestCase {
    func testIosCatalogResolvesStrictParakeetAndWhisperPackageIdentities() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)

        func write(_ json: String) throws {
            try Data(json.utf8).write(to: root.appendingPathComponent("voice-inbox-model.json"))
        }

        try write(#"{"schemaVersion":1,"catalogId":"whisper-tiny-multilingual","modelVersion":"whisper-tiny-ggml-f16-r1"}"#)
        let whisper = try IosSpeechModelStore.resolvePackage(in: root)
        XCTAssertEqual(whisper.backend, "WHISPER_CPP")
        XCTAssertTrue(whisper.networkDownloadAvailable)
        XCTAssertTrue(whisper.localImportAvailable)

        try write(#"{"schemaVersion":1,"catalogId":"parakeet-tdt-0.6b-v3-int8","modelVersion":"parakeet-tdt-0.6b-v3-int8-r1"}"#)
        let parakeet = try IosSpeechModelStore.resolvePackage(in: root)
        XCTAssertEqual(parakeet.backend, "PARAKEET_TDT_ONNX")
        XCTAssertTrue(parakeet.networkDownloadAvailable)
        XCTAssertEqual(
            Set(IosSpeechModelDescriptor.supported.filter(\.networkDownloadAvailable).map(\.catalogId)),
            Set(["parakeet-tdt-0.6b-v3-int8", "whisper-tiny-multilingual"])
        )

        for invalid in [
            #"{"schemaVersion":2,"catalogId":"whisper-tiny-multilingual","modelVersion":"whisper-tiny-ggml-f16-r1"}"#,
            #"{"schemaVersion":1,"catalogId":"unknown","modelVersion":"unknown"}"#,
            #"{"schemaVersion":1,"catalogId":"whisper-tiny-multilingual","modelVersion":"whisper-tiny-ggml-f16-r1","backend":"PARAKEET_TDT_ONNX"}"#,
        ] {
            try write(invalid)
            XCTAssertThrowsError(try IosSpeechModelStore.resolvePackage(in: root))
        }
    }

    func testIosPackageResolutionAcceptsAndroidCompatibleLegacyParakeetFolder() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)

        for file in IosSpeechModelDescriptor.defaultModel.files {
            FileManager.default.createFile(
                atPath: root.appendingPathComponent(file.name).path,
                contents: Data()
            )
        }

        let descriptor = try IosSpeechModelStore.resolvePackage(in: root)
        XCTAssertEqual(descriptor.catalogId, IosSpeechModelDescriptor.defaultModel.catalogId)

        FileManager.default.createFile(
            atPath: root.appendingPathComponent("unexpected.txt").path,
            contents: Data()
        )
        XCTAssertThrowsError(try IosSpeechModelStore.resolvePackage(in: root))
    }

    @MainActor
    func testConfirmedCandidateSurvivesAlertBindingDismissal() async {
        let root = FileManager.default.temporaryDirectory
        let store = IosSpeechModelStore(
            directory: root,
            inspectInstallation: { directory in
                IosSpeechModelStatus(directory: directory, installationState: .missing, missingFiles: [])
            },
            validateInstallation: { _ in [] },
            prepareNative: { _ in true },
            nativeError: { nil },
            recordVerified: {},
            recordInvalid: { _ in },
            resetNative: {}
        )
        let candidate = IosSpeechModelCandidate(
            descriptor: .defaultModel,
            sourceURL: root
        )

        // SwiftUI clears an alert(item:) binding as it dismisses the alert.
        store.pendingCandidate = nil
        store.confirmPendingInstallation(candidate: candidate)

        XCTAssertTrue(store.isInstalling)
        XCTAssertEqual(store.message, "Installing \(candidate.descriptor.displayName)...")

        while store.isInstalling {
            await Task.yield()
        }
    }

    func testLightweightRestoreUsesVersionedWhisperReceiptWithoutHashingPayload() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        let model = root.appendingPathComponent("SpeechModel", isDirectory: true)
        let receipt = root.appendingPathComponent("SpeechModel.receipt")
        let invalid = root.appendingPathComponent("SpeechModel.invalid")
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: model, withIntermediateDirectories: true)
        try Data("not model weights".utf8).write(to: model.appendingPathComponent("ggml-tiny.bin"))
        let active = IosSpeechModelInstallation(
            receiptSchemaVersion: 2,
            packageSchemaVersion: 1,
            catalogId: "whisper-tiny-multilingual",
            modelVersion: "whisper-tiny-ggml-f16-r1",
            backend: "WHISPER_CPP",
            installationGeneration: "test-generation"
        )
        try JSONEncoder().encode(active).write(to: receipt)

        let status = IosSpeechModelStore.inspectLightweight(
            directory: model,
            receiptFile: receipt,
            invalidFile: invalid,
            requiredFileNames: ["ggml-tiny.bin"]
        )
        XCTAssertEqual(status.installationState, .installedVerified)
        XCTAssertEqual(status.activeInstallation, active)
        XCTAssertTrue(status.isReady)
    }

    func testCatalogPreservesProductionParakeetManifest() {
        let descriptor = SpeechModelCatalog.shared.defaultModel
        let manifest = descriptor.manifest

        XCTAssertEqual(SpeechModelCatalog.shared.models.count, 2)
        XCTAssertEqual(SpeechModelCatalog.shared.modelsFor(platform: .ios).count, 2)
        XCTAssertEqual(descriptor.catalogId, "parakeet-tdt-0.6b-v3-int8")
        XCTAssertEqual(manifest.modelId, "istupakov/parakeet-tdt-0.6b-v3-onnx")
        XCTAssertEqual(manifest.version, "parakeet-tdt-0.6b-v3-int8-r1")
        XCTAssertEqual(manifest.repositoryRevision, "8f23f0c03c8761650bdb5b40aaf3e40d2c15f1ce")
        XCTAssertEqual(manifest.totalSizeBytes, 670_619_803)
        XCTAssertEqual(Set(manifest.files.map(\.name)), Set([
            "encoder-model.int8.onnx",
            "decoder_joint-model.int8.onnx",
            "nemo128.onnx",
            "vocab.txt",
            "config.json",
        ]))
    }

    func testLightweightInspectionDistinguishesMissingLegacyVerifiedAndKnownInvalidWithoutReadingPayloads() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        let model = root.appendingPathComponent("SpeechModel", isDirectory: true)
        let receipt = root.appendingPathComponent("SpeechModel.receipt")
        let invalid = root.appendingPathComponent("SpeechModel.invalid")
        defer { try? FileManager.default.removeItem(at: root) }

        let missing = IosSpeechModelStore.inspectLightweight(
            directory: model,
            receiptFile: receipt,
            invalidFile: invalid,
            requiredFileNames: ["large-model.onnx"]
        )
        XCTAssertEqual(missing.installationState, .missing)

        try FileManager.default.createDirectory(at: model, withIntermediateDirectories: true)
        // Deliberately invalid payload contents prove the startup probe only checks presence/readability.
        try Data("not an ONNX model".utf8).write(to: model.appendingPathComponent("large-model.onnx"))
        let legacy = IosSpeechModelStore.inspectLightweight(
            directory: model,
            receiptFile: receipt,
            invalidFile: invalid,
            requiredFileNames: ["large-model.onnx"]
        )
        XCTAssertEqual(legacy.installationState, .installedLegacy)
        XCTAssertTrue(legacy.isReady)

        try SpeechModelCatalog.shared.defaultModel.manifest.version.write(
            to: receipt,
            atomically: true,
            encoding: .utf8
        )
        let verified = IosSpeechModelStore.inspectLightweight(
            directory: model,
            receiptFile: receipt,
            invalidFile: invalid,
            requiredFileNames: ["large-model.onnx"]
        )
        XCTAssertEqual(verified.installationState, .installedVerified)

        try "checksum mismatch".write(to: invalid, atomically: true, encoding: .utf8)
        let knownInvalid = IosSpeechModelStore.inspectLightweight(
            directory: model,
            receiptFile: receipt,
            invalidFile: invalid,
            requiredFileNames: ["large-model.onnx"]
        )
        XCTAssertEqual(knownInvalid.installationState, .invalid)
        XCTAssertFalse(knownInvalid.isReady)
    }

    @MainActor
    func testLegacyPreparationWritesReceiptAndConcurrentAndLaterCallsReuseSingleLoad() async throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        let receipt = root.appendingPathComponent("SpeechModel.receipt")
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let loads = LockedCounter()

        let store = IosSpeechModelStore(
            directory: root,
            inspectInstallation: { directory in
                IosSpeechModelStatus(
                    directory: directory,
                    installationState: FileManager.default.fileExists(atPath: receipt.path)
                        ? .installedVerified
                        : .installedLegacy,
                    missingFiles: []
                )
            },
            validateInstallation: { _ in [] },
            prepareNative: { _ in
                loads.increment()
                Thread.sleep(forTimeInterval: 0.05)
                return true
            },
            nativeError: { nil },
            recordVerified: {
                try? SpeechModelCatalog.shared.defaultModel.manifest.version.write(
                    to: receipt,
                    atomically: true,
                    encoding: .utf8
                )
            },
            recordInvalid: { _ in },
            resetNative: {}
        )

        async let first = store.prepareForTranscription()
        async let second = store.prepareForTranscription()
        let results = await [first, second]

        XCTAssertEqual(results.compactMap { $0 }.count, 2)
        XCTAssertEqual(loads.value, 1)
        XCTAssertEqual(store.status.installationState, .installedVerified)
        XCTAssertTrue(FileManager.default.fileExists(atPath: receipt.path))

        _ = await store.prepareForTranscription()
        XCTAssertEqual(loads.value, 1)

        store.invalidateRuntimeAfterReplacement()
        XCTAssertEqual(store.runtimeState, .unloaded)
        _ = await store.prepareForTranscription()
        XCTAssertEqual(loads.value, 2)
    }

    @MainActor
    func testPreparationFailureDoesNotClaimAudioAndDeferredHandoffDoesNotPrepareEarly() async {
        let prepares = LockedCounter()
        let claims = LockedCounter()

        XCTAssertEqual(prepares.value, 0)
        XCTAssertEqual(claims.value, 0)

        let handedOff = await IosTranscriptionPreparationGate.prepareAndClaim(
            prepare: {
                prepares.increment()
                return false
            },
            claim: { claims.increment() }
        )

        XCTAssertFalse(handedOff)
        XCTAssertEqual(prepares.value, 1)
        XCTAssertEqual(claims.value, 0)
    }

    @MainActor
    func testTaskListAdapterSupportsAllAndKeepsOptionalUnselectedFolderOutOfTasks() {
        let modelStatus = IosSpeechModelStatus(
            directory: FileManager.default.temporaryDirectory,
            installationState: .installedVerified,
            missingFiles: []
        )
        let files = [
            IosImportedAudioFile(
                id: 1,
                displayName: "new.m4a",
                localFileName: "new.m4a",
                sizeBytes: 10,
                importedAt: Date(timeIntervalSince1970: 100),
                status: .pending
            ),
            IosImportedAudioFile(
                id: 2,
                displayName: "failed.m4a",
                localFileName: "failed.m4a",
                sizeBytes: 20,
                importedAt: Date(timeIntervalSince1970: 200),
                status: .failed,
                lastError: "decode failed",
                processedAt: Date(timeIntervalSince1970: 300)
            ),
        ]

        let screen = IosMainScreenShellState().screen(
            selection: .all,
            importedFiles: files,
            modelStatus: modelStatus,
            modelMessage: nil,
            modelInstalling: false,
            modelInstallationPhase: nil,
            modelDownloadAvailable: false,
            modelDownloadProgress: nil,
            modelCanCancel: false,
            outputStatus: IosOutputDocumentStatus(displayName: "notes.md", message: "Ready", ready: true),
            folderStatus: IosInboxFolderStatus(displayName: nil, message: nil, needsSelection: true),
            folderScanning: false,
            activePreviewEntryId: nil,
            previewState: .idle,
            transcription: .idle,
            preparationOwnerEntryId: nil,
            prerequisiteError: nil,
            actionsEnabled: true
        )

        XCTAssertEqual(screen.state.tasks.map(\.stableId), ["audio:2", "audio:1"])
        XCTAssertFalse(screen.state.tasks.contains { $0.stableId == "setup:model" })
        XCTAssertFalse(screen.state.tasks.contains { $0.stableId == "setup:folder" })
        XCTAssertEqual(screen.filesById.count, 2)
    }

    @MainActor
    func testTaskListAdapterAttachesPreparationFailureToPendingOwner() {
        let file = IosImportedAudioFile(
            id: 9,
            displayName: "voice.m4a",
            localFileName: "voice.m4a",
            sizeBytes: 10,
            importedAt: Date(),
            status: .pending
        )
        let screen = IosMainScreenShellState().screen(
            selection: .new,
            importedFiles: [file],
            modelStatus: IosSpeechModelStatus(
                directory: FileManager.default.temporaryDirectory,
                installationState: .installedVerified,
                missingFiles: []
            ),
            modelMessage: nil,
            modelInstalling: false,
            modelInstallationPhase: nil,
            modelDownloadAvailable: false,
            modelDownloadProgress: nil,
            modelCanCancel: false,
            outputStatus: IosOutputDocumentStatus(displayName: "notes.md", message: "Ready", ready: true),
            folderStatus: IosInboxFolderStatus(displayName: nil, message: nil, needsSelection: true),
            folderScanning: false,
            activePreviewEntryId: nil,
            previewState: .idle,
            transcription: .idle,
            preparationOwnerEntryId: 9,
            prerequisiteError: "Model could not be loaded",
            actionsEnabled: true
        )

        guard let task = screen.state.tasks.first as? AudioTaskPresentation else {
            return XCTFail("Expected an audio task")
        }
        XCTAssertEqual(task.state, .pending)
        XCTAssertEqual(task.errorMessage, "Model could not be loaded")
    }

    func testTypedActionsHaveExplicitIosRoutes() {
        XCTAssertEqual(IosTaskActionRouter.route(.downloadModel), .modelDownload)
        XCTAssertEqual(IosTaskActionRouter.route(.createOutput), .outputCreation)
        XCTAssertEqual(IosTaskActionRouter.route(.selectOutput), .outputSelection)
        XCTAssertEqual(IosTaskActionRouter.route(.hideOutput), .hideOutput)
        XCTAssertEqual(IosTaskActionRouter.route(.selectFolder), .folderSelection)
        XCTAssertEqual(IosTaskActionRouter.route(.transcribe), .transcribe)
        XCTAssertEqual(IosTaskActionRouter.route(.retryTranscription), .retry)
        XCTAssertEqual(IosTaskActionRouter.route(.showText), .showText)
    }

    @MainActor
    func testReadyIosShellEnablesTranscriptionWithoutOutputBookmark() {
        let file = IosImportedAudioFile(
            id: 10,
            displayName: "voice.m4a",
            localFileName: "voice.m4a",
            sizeBytes: 10,
            importedAt: Date(),
            status: .pending
        )
        let screen = IosMainScreenShellState().screen(
            selection: .new,
            importedFiles: [file],
            modelStatus: IosSpeechModelStatus(
                directory: FileManager.default.temporaryDirectory,
                installationState: .installedVerified,
                missingFiles: []
            ),
            modelMessage: nil,
            modelInstalling: false,
            modelInstallationPhase: nil,
            modelDownloadAvailable: false,
            modelDownloadProgress: nil,
            modelCanCancel: false,
            outputStatus: IosOutputDocumentStatus(displayName: nil, message: "Optional", ready: false),
            folderStatus: IosInboxFolderStatus(displayName: nil, message: nil, needsSelection: true),
            folderScanning: false,
            activePreviewEntryId: nil,
            previewState: .idle,
            transcription: .idle,
            preparationOwnerEntryId: nil,
            prerequisiteError: nil,
            actionsEnabled: true
        )

        guard let task = screen.state.tasks.compactMap({ $0 as? AudioTaskPresentation }).first else {
            return XCTFail("Expected an audio task")
        }
        XCTAssertTrue(task.actions.contains { $0.kind == .transcribe && $0.enabled })
    }

    func testOutputDocumentCreatorCoordinatorPreservesCancellationAndRoutesCreatedDocument() {
        let controller = UIDocumentPickerViewController(forOpeningContentTypes: [.plainText])
        var pickedURL: URL?
        var cancellationCount = 0
        let coordinator = IosOutputDocumentCreator.Coordinator(
            onPick: { pickedURL = $0 },
            onCancel: { cancellationCount += 1 }
        )

        coordinator.documentPicker(controller, didPickDocumentsAt: [])
        XCTAssertNil(pickedURL)
        XCTAssertEqual(cancellationCount, 1)

        let createdURL = URL(fileURLWithPath: "/tmp/Voice Inbox Transcripts.md")
        coordinator.documentPicker(controller, didPickDocumentsAt: [createdURL])
        XCTAssertEqual(pickedURL, createdURL)
        XCTAssertEqual(cancellationCount, 1)

        coordinator.documentPickerWasCancelled(controller)
        XCTAssertEqual(cancellationCount, 2)
    }

    func testRoutineImportAndScanSummariesDoNotRequestAnAlert() {
        XCTAssertNil(IosAudioImportSummary(imported: 2, skipped: 0, failed: 0).alertMessage)
        XCTAssertNil(IosAudioImportSummary(imported: 0, skipped: 4, failed: 0).alertMessage)
        XCTAssertEqual(
            IosAudioImportSummary(imported: 1, skipped: 0, failed: 1).alertMessage,
            "1 imported, 1 failed"
        )
    }
}

private final class LockedCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0

    var value: Int {
        lock.withLock { count }
    }

    func increment() {
        lock.withLock { count += 1 }
    }
}
