import Foundation
import Shared
import XCTest
@testable import VoiceInbox

final class DeferredSpeechModelLoadingTests: XCTestCase {
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

        try EmbeddedSpeechModel.shared.manifest.version.write(to: receipt, atomically: true, encoding: .utf8)
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
                try? EmbeddedSpeechModel.shared.manifest.version.write(
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
