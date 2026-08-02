import Combine
import CryptoKit
import Foundation
import Shared

enum IosSpeechModelInstallationState: Equatable {
    case missing
    case installedVerified
    case installedLegacy
    case invalid
}

struct IosSpeechModelFileDescriptor: Equatable {
    let name: String
    let sizeBytes: Int64
    let sha256: String
    let downloadURL: String
}

struct IosSpeechModelDescriptor: Equatable, Identifiable {
    let catalogId: String
    let displayName: String
    let modelVersion: String
    let backend: String
    let maturity: String
    let languageSummary: String
    let networkDownloadAvailable: Bool
    let localImportAvailable: Bool
    let safetyMarginBytes: Int64
    let files: [IosSpeechModelFileDescriptor]

    var id: String { "\(catalogId):\(modelVersion)" }
    var primaryFile: String { files.first?.name ?? "" }
    var totalSizeBytes: Int64 { files.reduce(0) { $0 + $1.sizeBytes } }

    init(_ descriptor: SpeechModelDescriptor) {
        catalogId = descriptor.catalogId
        displayName = descriptor.displayName
        modelVersion = descriptor.manifest.version
        backend = descriptor.backend.name
        maturity = descriptor.maturity.name.capitalized
        languageSummary = descriptor.languages.summary
        networkDownloadAvailable = descriptor.distribution.networkDownloadAvailable
        localImportAvailable = descriptor.distribution.localImportAvailable
        safetyMarginBytes = descriptor.manifest.safetyMarginBytes
        files = descriptor.manifest.files.map {
            IosSpeechModelFileDescriptor(
                name: $0.name,
                sizeBytes: $0.sizeBytes,
                sha256: $0.sha256,
                downloadURL: descriptor.manifest.downloadUrl(file: $0)
            )
        }
    }

    static var supported: [IosSpeechModelDescriptor] {
        SpeechModelCatalog.shared.modelsFor(platform: .ios).map(IosSpeechModelDescriptor.init)
    }

    static var defaultModel: IosSpeechModelDescriptor {
        IosSpeechModelDescriptor(SpeechModelCatalog.shared.defaultModel)
    }

    static func resolve(catalogId: String, modelVersion: String) -> IosSpeechModelDescriptor? {
        supported.first { $0.catalogId == catalogId && $0.modelVersion == modelVersion }
    }
}

struct IosSpeechModelInstallation: Codable, Equatable {
    static let receiptSchemaVersion = 2

    let receiptSchemaVersion: Int
    let packageSchemaVersion: Int
    let catalogId: String
    let modelVersion: String
    let backend: String
    let installationGeneration: String

    var identity: String {
        "\(catalogId):\(modelVersion):\(backend):\(installationGeneration)"
    }
}

struct IosSpeechModelCandidate: Identifiable, Equatable {
    let descriptor: IosSpeechModelDescriptor
    let sourceURL: URL
    var id: String { descriptor.id }
}

struct IosSpeechModelStatus {
    let directory: URL
    let installationState: IosSpeechModelInstallationState
    let missingFiles: [String]
    let activeInstallation: IosSpeechModelInstallation?

    init(
        directory: URL,
        installationState: IosSpeechModelInstallationState,
        missingFiles: [String],
        activeInstallation: IosSpeechModelInstallation? = nil
    ) {
        self.directory = directory
        self.installationState = installationState
        self.missingFiles = missingFiles
        self.activeInstallation = activeInstallation
    }

    var isReady: Bool {
        installationState == .installedVerified || installationState == .installedLegacy
    }

    var summary: String {
        if isReady {
            return "Speech model installed"
        }
        return "Speech model is not installed"
    }

    var detail: String? {
        guard !isReady else {
            return directory.path
        }
        guard !missingFiles.isEmpty else {
            return nil
        }
        return "Missing: \(missingFiles.joined(separator: ", "))"
    }
}

enum IosSpeechModelPaths {
    static var applicationSupportDirectory: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)
            .first ?? FileManager.default.temporaryDirectory
        return base
    }

    static var modelDirectory: URL {
        applicationSupportDirectory.appendingPathComponent("SpeechModel", isDirectory: true)
    }

    static var installDirectory: URL {
        applicationSupportDirectory.appendingPathComponent("SpeechModel.installing", isDirectory: true)
    }

    static var stagingDirectory: URL {
        applicationSupportDirectory.appendingPathComponent("SpeechModel.staging", isDirectory: true)
    }

    static var backupDirectory: URL {
        applicationSupportDirectory.appendingPathComponent("SpeechModel.previous", isDirectory: true)
    }

    static var receiptFile: URL {
        applicationSupportDirectory.appendingPathComponent("SpeechModel.receipt")
    }

    static var invalidFile: URL {
        applicationSupportDirectory.appendingPathComponent("SpeechModel.invalid")
    }

    static var backupReceiptFile: URL {
        applicationSupportDirectory.appendingPathComponent("SpeechModel.receipt.previous")
    }
}

struct IosSpeechModelDownloadProgress {
    let message: String
    let bytesDownloaded: Int64
    let totalBytes: Int64

    var percent: Int {
        guard totalBytes > 0 else { return 0 }
        return Int((bytesDownloaded.clamped(to: 0...totalBytes) * 100) / totalBytes)
    }
}

@MainActor
final class IosSpeechModelStore: ObservableObject {
    @Published private(set) var status: IosSpeechModelStatus
    @Published private(set) var isInstalling = false
    @Published private(set) var downloadProgress: IosSpeechModelDownloadProgress?
    @Published private(set) var runtimeState = SpeechModelRuntimeState.unloaded
    @Published var message: String?
    @Published var pendingCandidate: IosSpeechModelCandidate?
    @Published private(set) var installationError: String?

    var availableModels: [IosSpeechModelDescriptor] { IosSpeechModelDescriptor.supported }
    var activeDescriptor: IosSpeechModelDescriptor? {
        guard let active = status.activeInstallation else {
            return status.isReady ? .defaultModel : nil
        }
        return .resolve(catalogId: active.catalogId, modelVersion: active.modelVersion)
    }
    var actionableErrorMessage: String? {
        if let installationError { return installationError }
        guard let message else { return nil }
        let lower = message.lowercased()
        return ["could not", "not enough", "not supported", "malformed", "missing", "invalid", "wait for"]
            .contains(where: lower.contains) ? message : nil
    }

    func clearActionableError() {
        installationError = nil
        if actionableErrorMessage != nil { message = nil }
    }

    private var downloadTask: Task<Void, Never>?
    private var preparationTask: Task<PreparationResult, Never>?
    private var pendingSecurityScopeActive = false
    private var pendingSecurityScopeURL: URL?
    private let installationDirectory: URL
    private let inspectInstallation: @Sendable (URL) -> IosSpeechModelStatus
    private let validateInstallation: @Sendable (URL) -> [String]
    private let prepareNative: @Sendable (String) -> Bool
    private let nativeError: @Sendable () -> String?
    private let recordVerified: @Sendable () -> Void
    private let recordInvalid: @Sendable (String) -> Void
    private let resetNative: @Sendable () -> Void

    convenience init() {
        self.init(
            directory: IosSpeechModelPaths.modelDirectory,
            inspectInstallation: { Self.inspectLightweight(directory: $0) },
            validateInstallation: { directory in
                guard let descriptor = Self.inspectLightweight(directory: directory).activeInstallation.flatMap({
                    IosSpeechModelDescriptor.resolve(catalogId: $0.catalogId, modelVersion: $0.modelVersion)
                }) ?? (Self.inspectLightweight(directory: directory).isReady ? .defaultModel : nil) else {
                    return ["active model identity"]
                }
                return Self.validateModelFiles(in: directory, descriptor: descriptor).missingFiles
            },
            prepareNative: { directory in
                let status = Self.inspectLightweight(directory: URL(fileURLWithPath: directory))
                guard let descriptor = status.activeInstallation.flatMap({
                    IosSpeechModelDescriptor.resolve(catalogId: $0.catalogId, modelVersion: $0.modelVersion)
                }) ?? (status.isReady ? .defaultModel : nil) else { return false }
                let identity = status.activeInstallation?.identity ?? "legacy:\(descriptor.id)"
                return IosNativeTranscriber.prepare(
                    backend: descriptor.backend,
                    installationIdentity: identity,
                    modelDirectory: directory,
                    primaryFile: descriptor.primaryFile
                )
            },
            nativeError: { IosNativeTranscriber.consumeLastError() },
            recordVerified: { Self.recordVerifiedInstallationIfNeeded() },
            recordInvalid: { Self.recordInvalidInstallation($0) },
            resetNative: { IosNativeTranscriber.resetModel() }
        )
    }

    init(
        directory: URL,
        inspectInstallation: @escaping @Sendable (URL) -> IosSpeechModelStatus,
        validateInstallation: @escaping @Sendable (URL) -> [String],
        prepareNative: @escaping @Sendable (String) -> Bool,
        nativeError: @escaping @Sendable () -> String?,
        recordVerified: @escaping @Sendable () -> Void,
        recordInvalid: @escaping @Sendable (String) -> Void,
        resetNative: @escaping @Sendable () -> Void
    ) {
        installationDirectory = directory
        self.inspectInstallation = inspectInstallation
        self.validateInstallation = validateInstallation
        self.prepareNative = prepareNative
        self.nativeError = nativeError
        self.recordVerified = recordVerified
        self.recordInvalid = recordInvalid
        self.resetNative = resetNative
        status = inspectInstallation(directory)
    }

    var isReady: Bool {
        status.isReady
    }

    var modelDirectory: URL {
        status.directory
    }

    var isBusy: Bool {
        isInstalling || downloadTask != nil
    }

    var canCancelDownload: Bool {
        downloadTask != nil
    }

    func reload() {
        status = inspectInstallation(installationDirectory)
    }

    func inspectModelPackage(from sourceURL: URL) {
        guard !isBusy else { return }

        releasePendingSecurityScope()
        let accessed = sourceURL.startAccessingSecurityScopedResource()
        do {
            let descriptor = try Self.resolvePackage(in: sourceURL)
            pendingCandidate = IosSpeechModelCandidate(descriptor: descriptor, sourceURL: sourceURL)
            pendingSecurityScopeActive = accessed
            pendingSecurityScopeURL = accessed ? sourceURL : nil
            installationError = nil
            message = nil
        } catch {
            if accessed { sourceURL.stopAccessingSecurityScopedResource() }
            pendingCandidate = nil
            installationError = error.localizedDescription
            message = nil
        }
    }

    func cancelPendingInstallation() {
        releasePendingSecurityScope()
        pendingCandidate = nil
    }

    func confirmPendingInstallation(
        candidate confirmedCandidate: IosSpeechModelCandidate? = nil,
        replacementAllowed: Bool = true
    ) {
        guard replacementAllowed else {
            message = "Wait for the current transcription to finish before replacing the speech model."
            return
        }
        guard let candidate = confirmedCandidate ?? pendingCandidate, !isBusy else { return }
        let sourceAccessAlreadyActive = pendingSecurityScopeActive
        pendingSecurityScopeActive = false
        pendingSecurityScopeURL = nil
        pendingCandidate = nil

        isInstalling = true
        installationError = nil
        downloadProgress = nil
        message = "Installing \(candidate.descriptor.displayName)..."

        Task {
            let result = await Task.detached(priority: .userInitiated) {
                Self.installModelFiles(
                    from: candidate.sourceURL,
                    descriptor: candidate.descriptor,
                    sourceAccessAlreadyActive: sourceAccessAlreadyActive
                )
            }.value

            isInstalling = false
            status = Self.inspectLightweight(directory: IosSpeechModelPaths.modelDirectory)
            if result.committed {
                invalidateRuntimeAfterReplacement()
                installationError = nil
            } else {
                installationError = result.message
            }
            message = result.message
        }
    }

    private func releasePendingSecurityScope() {
        if pendingSecurityScopeActive, let url = pendingSecurityScopeURL {
            url.stopAccessingSecurityScopedResource()
        }
        pendingSecurityScopeActive = false
        pendingSecurityScopeURL = nil
    }

    // Compatibility adapter used by existing tests and callers. Production UI
    // uses inspect + explicit confirmation.
    func installModel(from sourceURL: URL) {
        inspectModelPackage(from: sourceURL)
    }

    func downloadModel() {
        guard !isBusy else { return }

        let descriptor = IosSpeechModelDescriptor.defaultModel
        guard descriptor.networkDownloadAvailable else {
            message = "Network download is not available for this model."
            return
        }

        isInstalling = true
        message = "Preparing speech model download..."
        downloadProgress = IosSpeechModelDownloadProgress(
            message: "Preparing speech model download...",
            bytesDownloaded: 0,
            totalBytes: descriptor.totalSizeBytes
        )

        downloadTask = Task {
            let result = await Self.downloadAndInstallModel(descriptor: descriptor) { [weak self] progress in
                Task { @MainActor in
                    self?.downloadProgress = progress
                    self?.message = progress.message
                }
            }

            if Task.isCancelled {
                result.cleanup()
            }

            isInstalling = false
            downloadTask = nil
            status = Self.inspectLightweight(directory: IosSpeechModelPaths.modelDirectory)
            if result.committed {
                invalidateRuntimeAfterReplacement()
            }
            downloadProgress = nil
            message = result.message
        }
    }

    func cancelDownload() {
        downloadTask?.cancel()
        downloadTask = nil
        isInstalling = false
        downloadProgress = nil
        message = "Speech model download cancelled."
    }

    func prepareForTranscription() async -> URL? {
        if runtimeState == .loaded { return modelDirectory }
        let task: Task<PreparationResult, Never>
        if let existing = preparationTask {
            task = existing
        } else {
            runtimeState = .loading
            message = "Preparing speech model..."
            let directory = modelDirectory
            let validateInstallation = validateInstallation
            let prepareNative = prepareNative
            let nativeError = nativeError
            let recordVerified = recordVerified
            let recordInvalid = recordInvalid
            task = Task.detached(priority: .userInitiated) {
                let validationIssues = validateInstallation(directory)
                guard validationIssues.isEmpty else {
                    let reason = validationIssues.joined(separator: ", ")
                    recordInvalid(reason)
                    return PreparationResult(
                        directory: nil,
                        invalidInstallation: true,
                        message: "Speech model is invalid: \(reason)"
                    )
                }
                guard prepareNative(directory.path) else {
                    return PreparationResult(
                        directory: nil,
                        invalidInstallation: false,
                        message: nativeError()
                            ?? "Speech model failed to load."
                    )
                }
                recordVerified()
                return PreparationResult(directory: directory, invalidInstallation: false, message: nil)
            }
            preparationTask = task
        }

        let result = await task.value
        preparationTask = nil
        if let directory = result.directory {
            status = inspectInstallation(directory)
            runtimeState = .loaded
            message = nil
            return directory
        }
        runtimeState = .failed
        if result.invalidInstallation {
            status = inspectInstallation(modelDirectory)
        }
        message = result.message
        return nil
    }

    func invalidateRuntimeAfterReplacement() {
        preparationTask?.cancel()
        preparationTask = nil
        runtimeState = .unloaded
        resetNative()
    }

    nonisolated static func inspectLightweight(
        directory: URL,
        receiptFile: URL = IosSpeechModelPaths.receiptFile,
        invalidFile: URL = IosSpeechModelPaths.invalidFile,
        requiredFileNames: [String]? = nil
    ) -> IosSpeechModelStatus {
        recoverInterruptedActivationIfNeeded()
        if let reason = try? String(contentsOf: invalidFile, encoding: .utf8),
           !reason.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return IosSpeechModelStatus(
                directory: directory,
                installationState: .invalid,
                missingFiles: [reason]
            )
        }
        let fileManager = FileManager.default
        var isDirectory: ObjCBool = false
        guard fileManager.fileExists(atPath: directory.path, isDirectory: &isDirectory), isDirectory.boolValue else {
            return IosSpeechModelStatus(
                directory: directory,
                installationState: .missing,
                missingFiles: ["model directory"]
            )
        }
        let receiptText = try? String(contentsOf: receiptFile, encoding: .utf8)
        let receipt = receiptText.flatMap(decodeReceipt)
        let descriptor: IosSpeechModelDescriptor? = receipt.flatMap {
            guard $0.receiptSchemaVersion == IosSpeechModelInstallation.receiptSchemaVersion,
                  $0.packageSchemaVersion == 1,
                  let resolved = IosSpeechModelDescriptor.resolve(
                    catalogId: $0.catalogId,
                    modelVersion: $0.modelVersion
                  ), resolved.backend == $0.backend else { return nil }
            return resolved
        }
        if receipt != nil && descriptor == nil {
            return IosSpeechModelStatus(
                directory: directory,
                installationState: .invalid,
                missingFiles: ["unsupported active model receipt"]
            )
        }
        let legacyDescriptor = IosSpeechModelDescriptor.defaultModel
        let expectedNames = requiredFileNames ?? (descriptor ?? legacyDescriptor).files.map(\.name)
        let missing = expectedNames.compactMap { fileName in
            fileManager.isReadableFile(atPath: directory.appendingPathComponent(fileName).path)
                ? nil
                : fileName
        }
        guard missing.isEmpty else {
            return IosSpeechModelStatus(
                directory: directory,
                installationState: .invalid,
                missingFiles: missing
            )
        }
        let legacyReceiptMatches = receiptText?.trimmingCharacters(in: .whitespacesAndNewlines)
            == SpeechModelCatalog.shared.defaultModel.manifest.version
        let legacyInstallation = IosSpeechModelInstallation(
            receiptSchemaVersion: IosSpeechModelInstallation.receiptSchemaVersion,
            packageSchemaVersion: 1,
            catalogId: legacyDescriptor.catalogId,
            modelVersion: legacyDescriptor.modelVersion,
            backend: legacyDescriptor.backend,
            installationGeneration: "legacy"
        )
        let state: IosSpeechModelInstallationState = (receipt != nil || legacyReceiptMatches)
            ? .installedVerified : .installedLegacy
        return IosSpeechModelStatus(
            directory: directory,
            installationState: state,
            missingFiles: [],
            activeInstallation: receipt ?? legacyInstallation
        )
    }

    nonisolated private static func recordVerifiedInstallation() {
        let descriptor = IosSpeechModelDescriptor.defaultModel
        recordVerifiedInstallation(descriptor: descriptor, generation: "legacy-migrated-\(UUID().uuidString)")
    }

    nonisolated private static func recordVerifiedInstallationIfNeeded() {
        if let text = try? String(contentsOf: IosSpeechModelPaths.receiptFile, encoding: .utf8),
           decodeReceipt(text) != nil {
            try? removeItemIfExists(IosSpeechModelPaths.invalidFile)
            return
        }
        recordVerifiedInstallation()
    }

    nonisolated private static func recordVerifiedInstallation(
        descriptor: IosSpeechModelDescriptor,
        generation: String = UUID().uuidString
    ) {
        try? writeVerifiedInstallation(descriptor: descriptor, generation: generation)
    }

    nonisolated private static func writeVerifiedInstallation(
        descriptor: IosSpeechModelDescriptor,
        generation: String = UUID().uuidString
    ) throws {
        try FileManager.default.createDirectory(
            at: IosSpeechModelPaths.applicationSupportDirectory,
            withIntermediateDirectories: true
        )
        let receipt = IosSpeechModelInstallation(
            receiptSchemaVersion: IosSpeechModelInstallation.receiptSchemaVersion,
            packageSchemaVersion: 1,
            catalogId: descriptor.catalogId,
            modelVersion: descriptor.modelVersion,
            backend: descriptor.backend,
            installationGeneration: generation
        )
        let data = try JSONEncoder().encode(receipt)
        try data.write(to: IosSpeechModelPaths.receiptFile, options: Data.WritingOptions.atomic)
        try removeItemIfExists(IosSpeechModelPaths.invalidFile)
    }

    nonisolated private static func decodeReceipt(_ text: String) -> IosSpeechModelInstallation? {
        guard let data = text.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(IosSpeechModelInstallation.self, from: data)
    }

    nonisolated static func resolvePackage(in directory: URL) throws -> IosSpeechModelDescriptor {
        let manifestURL = directory.appendingPathComponent("voice-inbox-model.json")
        guard let data = try? Data(contentsOf: manifestURL) else {
            let expected = Set(IosSpeechModelDescriptor.defaultModel.files.map(\.name))
            let contents = try? FileManager.default.contentsOfDirectory(
                at: directory,
                includingPropertiesForKeys: [.isRegularFileKey],
                options: [.skipsHiddenFiles]
            )
            let regularNames = Set((contents ?? []).compactMap { url -> String? in
                guard (try? url.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true else {
                    return nil
                }
                return url.lastPathComponent
            })
            guard regularNames == expected else {
                throw ModelPackageError.missingManifest
            }
            return .defaultModel
        }
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw ModelPackageError.malformedManifest
        }
        let allowed = Set(["schemaVersion", "catalogId", "modelVersion"])
        guard Set(object.keys).isSubset(of: allowed),
              object.keys.count == allowed.count,
              let schema = object["schemaVersion"] as? Int,
              let catalogId = object["catalogId"] as? String,
              let modelVersion = object["modelVersion"] as? String else {
            throw ModelPackageError.malformedManifest
        }
        guard schema == 1,
              let descriptor = IosSpeechModelDescriptor.resolve(
                catalogId: catalogId,
                modelVersion: modelVersion
              ), descriptor.localImportAvailable else {
            throw ModelPackageError.unsupportedIdentity
        }
        return descriptor
    }

    nonisolated private static func recoverInterruptedActivationIfNeeded() {
        let fileManager = FileManager.default
        guard !fileManager.fileExists(atPath: IosSpeechModelPaths.modelDirectory.path),
              fileManager.fileExists(atPath: IosSpeechModelPaths.backupDirectory.path) else { return }
        try? fileManager.moveItem(
            at: IosSpeechModelPaths.backupDirectory,
            to: IosSpeechModelPaths.modelDirectory
        )
        if fileManager.fileExists(atPath: IosSpeechModelPaths.backupReceiptFile.path) {
            try? removeItemIfExists(IosSpeechModelPaths.receiptFile)
            try? fileManager.moveItem(
                at: IosSpeechModelPaths.backupReceiptFile,
                to: IosSpeechModelPaths.receiptFile
            )
        }
    }

    nonisolated private static func recordInvalidInstallation(_ reason: String) {
        try? reason.write(to: IosSpeechModelPaths.invalidFile, atomically: true, encoding: .utf8)
    }

    nonisolated private static func validateModelFiles(
        in directory: URL,
        descriptor: IosSpeechModelDescriptor
    ) -> ModelValidationResult {
        let fileManager = FileManager.default
        var isDirectory: ObjCBool = false
        guard fileManager.fileExists(atPath: directory.path, isDirectory: &isDirectory), isDirectory.boolValue else {
            return ModelValidationResult(missingFiles: ["model directory"])
        }

        var validationIssues = [String]()
        for entry in descriptor.files {
            let fileURL = directory.appendingPathComponent(entry.name)
            if !fileManager.isReadableFile(atPath: fileURL.path) {
                validationIssues.append(entry.name)
                continue
            }
            if !isValidFile(fileURL, entry: entry) {
                validationIssues.append("\(entry.name) checksum or size mismatch")
            }
        }

        return ModelValidationResult(missingFiles: validationIssues)
    }

    nonisolated private static func installModelFiles(
        from sourceURL: URL,
        descriptor: IosSpeechModelDescriptor,
        sourceAccessAlreadyActive: Bool = false
    ) -> InstallResult {
        let fileManager = FileManager.default
        let accessed = sourceAccessAlreadyActive || sourceURL.startAccessingSecurityScopedResource()
        defer {
            if accessed {
                sourceURL.stopAccessingSecurityScopedResource()
            }
        }

        let sourceValidation = validateModelFiles(in: sourceURL, descriptor: descriptor)
        guard sourceValidation.missingFiles.isEmpty else {
            return InstallResult(message: "Selected folder is not a valid \(descriptor.displayName) package: \(sourceValidation.missingFiles.joined(separator: ", "))")
        }

        let requiredBytes = descriptor.totalSizeBytes + descriptor.safetyMarginBytes
        if let values = try? IosSpeechModelPaths.applicationSupportDirectory.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]),
           let available = values.volumeAvailableCapacityForImportantUsage,
           available < requiredBytes {
            return InstallResult(message: "Not enough free storage to install \(descriptor.displayName).")
        }

        do {
            try fileManager.createDirectory(
                at: IosSpeechModelPaths.applicationSupportDirectory,
                withIntermediateDirectories: true
            )
            try removeItemIfExists(IosSpeechModelPaths.installDirectory)
            try removeItemIfExists(IosSpeechModelPaths.stagingDirectory)
            try removeItemIfExists(IosSpeechModelPaths.backupDirectory)
            try fileManager.createDirectory(
                at: IosSpeechModelPaths.installDirectory,
                withIntermediateDirectories: true
            )

            try copyRequiredFiles(from: sourceURL, to: IosSpeechModelPaths.installDirectory, descriptor: descriptor)

            let installedValidation = validateModelFiles(in: IosSpeechModelPaths.installDirectory, descriptor: descriptor)
            guard installedValidation.missingFiles.isEmpty else {
                try removeItemIfExists(IosSpeechModelPaths.installDirectory)
                return InstallResult(message: "Copied model is incomplete. Missing: \(installedValidation.missingFiles.joined(separator: ", "))")
            }

            if fileManager.fileExists(atPath: IosSpeechModelPaths.modelDirectory.path) {
                try fileManager.moveItem(at: IosSpeechModelPaths.modelDirectory, to: IosSpeechModelPaths.backupDirectory)
            }
            if fileManager.fileExists(atPath: IosSpeechModelPaths.receiptFile.path) {
                try removeItemIfExists(IosSpeechModelPaths.backupReceiptFile)
                try fileManager.copyItem(
                    at: IosSpeechModelPaths.receiptFile,
                    to: IosSpeechModelPaths.backupReceiptFile
                )
            }

            do {
                try fileManager.moveItem(at: IosSpeechModelPaths.installDirectory, to: IosSpeechModelPaths.modelDirectory)
                try writeVerifiedInstallation(descriptor: descriptor)
                try removeItemIfExists(IosSpeechModelPaths.backupDirectory)
                try removeItemIfExists(IosSpeechModelPaths.backupReceiptFile)
                return InstallResult(message: "\(descriptor.displayName) installed.", committed: true)
            } catch {
                try? removeItemIfExists(IosSpeechModelPaths.modelDirectory)
                if fileManager.fileExists(atPath: IosSpeechModelPaths.backupDirectory.path) {
                    try? fileManager.moveItem(at: IosSpeechModelPaths.backupDirectory, to: IosSpeechModelPaths.modelDirectory)
                }
                if fileManager.fileExists(atPath: IosSpeechModelPaths.backupReceiptFile.path) {
                    try? removeItemIfExists(IosSpeechModelPaths.receiptFile)
                    try? fileManager.moveItem(at: IosSpeechModelPaths.backupReceiptFile, to: IosSpeechModelPaths.receiptFile)
                }
                throw error
            }
        } catch {
            try? removeItemIfExists(IosSpeechModelPaths.installDirectory)
            return InstallResult(message: "Could not install speech model: \(error.localizedDescription)")
        }
    }

    nonisolated private static func copyRequiredFiles(
        from sourceURL: URL,
        to destinationURL: URL,
        descriptor: IosSpeechModelDescriptor
    ) throws {
        for entry in descriptor.files {
            try copyFile(named: entry.name, from: sourceURL, to: destinationURL)
        }
    }

    nonisolated private static func copyFile(named fileName: String, from sourceURL: URL, to destinationURL: URL) throws {
        try FileManager.default.copyItem(
            at: sourceURL.appendingPathComponent(fileName),
            to: destinationURL.appendingPathComponent(fileName)
        )
    }

    nonisolated private static func downloadAndInstallModel(
        descriptor: IosSpeechModelDescriptor,
        progress: @escaping @Sendable (IosSpeechModelDownloadProgress) -> Void
    ) async -> InstallResult {
        let fileManager = FileManager.default
        let files = descriptor.files
        let totalBytes = descriptor.totalSizeBytes

        do {
            try fileManager.createDirectory(
                at: IosSpeechModelPaths.applicationSupportDirectory,
                withIntermediateDirectories: true
            )
            try removeItemIfExists(IosSpeechModelPaths.stagingDirectory)
            try removeItemIfExists(IosSpeechModelPaths.installDirectory)
            try fileManager.createDirectory(
                at: IosSpeechModelPaths.stagingDirectory,
                withIntermediateDirectories: true
            )

            var completedBytes: Int64 = 0
            progress(IosSpeechModelDownloadProgress(
                message: "Downloading speech model",
                bytesDownloaded: completedBytes,
                totalBytes: totalBytes
            ))

            for entry in files {
                try Task.checkCancellation()
                let destination = IosSpeechModelPaths.stagingDirectory.appendingPathComponent(entry.name)
                if isValidFile(destination, entry: entry) {
                    completedBytes += entry.sizeBytes
                    continue
                }

                try cleanupPartialFile(for: entry)
                try await downloadFile(entry, completedBytes: completedBytes, totalBytes: totalBytes, progress: progress)
                let temporary = temporaryFile(for: entry)
                guard isValidFile(temporary, entry: entry) else {
                    try? removeItemIfExists(temporary)
                    return InstallResult(message: "Downloaded \(entry.name) failed verification.")
                }
                try fileManager.moveItem(at: temporary, to: destination)
                completedBytes += entry.sizeBytes
                progress(IosSpeechModelDownloadProgress(
                    message: "Verified \(entry.name)",
                    bytesDownloaded: completedBytes,
                    totalBytes: totalBytes
                ))
            }

            progress(IosSpeechModelDownloadProgress(
                message: "Activating speech model...",
                bytesDownloaded: totalBytes,
                totalBytes: totalBytes
            ))

            let stagedValidation = validateModelFiles(in: IosSpeechModelPaths.stagingDirectory, descriptor: descriptor)
            guard stagedValidation.missingFiles.isEmpty else {
                return InstallResult(
                    message: "Downloaded model is incomplete. Missing: \(stagedValidation.missingFiles.joined(separator: ", "))"
                )
            }

            try activateStagedModel()
            try writeVerifiedInstallation(descriptor: descriptor)
            try removeItemIfExists(IosSpeechModelPaths.backupDirectory)
            try removeItemIfExists(IosSpeechModelPaths.backupReceiptFile)
            return InstallResult(message: "\(descriptor.displayName) downloaded and installed.", committed: true)
        } catch is CancellationError {
            try? removeItemIfExists(IosSpeechModelPaths.stagingDirectory)
            return InstallResult(message: "Speech model download cancelled.")
        } catch {
            try? removeItemIfExists(IosSpeechModelPaths.stagingDirectory)
            restorePreviousInstallationIfNeeded()
            return InstallResult(message: "Could not download speech model: \(error.localizedDescription)")
        }
    }

    nonisolated private static func removeItemIfExists(_ url: URL) throws {
        if FileManager.default.fileExists(atPath: url.path) {
            try FileManager.default.removeItem(at: url)
        }
    }

    nonisolated private static func downloadFile(
        _ entry: IosSpeechModelFileDescriptor,
        completedBytes: Int64,
        totalBytes: Int64,
        progress: @escaping @Sendable (IosSpeechModelDownloadProgress) -> Void
    ) async throws {
        guard let url = URL(string: entry.downloadURL) else {
            throw ModelDownloadError.invalidUrl(entry.downloadURL)
        }

        let (bytes, response) = try await URLSession.shared.bytes(from: url)
        if let httpResponse = response as? HTTPURLResponse, !(200...299).contains(httpResponse.statusCode) {
            throw ModelDownloadError.httpStatus(httpResponse.statusCode)
        }

        let temporary = temporaryFile(for: entry)
        try removeItemIfExists(temporary)
        FileManager.default.createFile(atPath: temporary.path, contents: nil)
        let handle = try FileHandle(forWritingTo: temporary)
        defer {
            try? handle.close()
        }

        var fileBytes: Int64 = 0
        var lastReported: Int64 = 0
        var buffer = [UInt8]()
        buffer.reserveCapacity(128 * 1024)

        for try await byte in bytes {
            try Task.checkCancellation()
            buffer.append(byte)
            fileBytes += 1

            if buffer.count >= 128 * 1024 {
                try handle.write(contentsOf: Data(buffer))
                buffer.removeAll(keepingCapacity: true)
            }

            if fileBytes - lastReported >= 2 * 1024 * 1024 {
                progress(IosSpeechModelDownloadProgress(
                    message: "Downloading \(entry.name)",
                    bytesDownloaded: completedBytes + fileBytes,
                    totalBytes: totalBytes
                ))
                lastReported = fileBytes
            }
        }

        if !buffer.isEmpty {
            try handle.write(contentsOf: Data(buffer))
        }
    }

    nonisolated private static func activateStagedModel() throws {
        let fileManager = FileManager.default
        try removeItemIfExists(IosSpeechModelPaths.installDirectory)
        try fileManager.moveItem(
            at: IosSpeechModelPaths.stagingDirectory,
            to: IosSpeechModelPaths.installDirectory
        )
        try removeItemIfExists(IosSpeechModelPaths.backupDirectory)
        try removeItemIfExists(IosSpeechModelPaths.backupReceiptFile)

        if fileManager.fileExists(atPath: IosSpeechModelPaths.modelDirectory.path) {
            try fileManager.moveItem(at: IosSpeechModelPaths.modelDirectory, to: IosSpeechModelPaths.backupDirectory)
        }
        if fileManager.fileExists(atPath: IosSpeechModelPaths.receiptFile.path) {
            try fileManager.copyItem(at: IosSpeechModelPaths.receiptFile, to: IosSpeechModelPaths.backupReceiptFile)
        }

        do {
            try fileManager.moveItem(at: IosSpeechModelPaths.installDirectory, to: IosSpeechModelPaths.modelDirectory)
        } catch {
            try? removeItemIfExists(IosSpeechModelPaths.modelDirectory)
            if fileManager.fileExists(atPath: IosSpeechModelPaths.backupDirectory.path) {
                try? fileManager.moveItem(at: IosSpeechModelPaths.backupDirectory, to: IosSpeechModelPaths.modelDirectory)
            }
            if fileManager.fileExists(atPath: IosSpeechModelPaths.backupReceiptFile.path) {
                try? removeItemIfExists(IosSpeechModelPaths.receiptFile)
                try? fileManager.moveItem(at: IosSpeechModelPaths.backupReceiptFile, to: IosSpeechModelPaths.receiptFile)
            }
            throw error
        }
    }

    nonisolated private static func restorePreviousInstallationIfNeeded() {
        let fileManager = FileManager.default
        if fileManager.fileExists(atPath: IosSpeechModelPaths.backupDirectory.path) {
            try? removeItemIfExists(IosSpeechModelPaths.modelDirectory)
            try? fileManager.moveItem(
                at: IosSpeechModelPaths.backupDirectory,
                to: IosSpeechModelPaths.modelDirectory
            )
        }
        if fileManager.fileExists(atPath: IosSpeechModelPaths.backupReceiptFile.path) {
            try? removeItemIfExists(IosSpeechModelPaths.receiptFile)
            try? fileManager.moveItem(
                at: IosSpeechModelPaths.backupReceiptFile,
                to: IosSpeechModelPaths.receiptFile
            )
        }
    }

    nonisolated private static func cleanupPartialFile(for entry: IosSpeechModelFileDescriptor) throws {
        try removeItemIfExists(temporaryFile(for: entry))
        let destination = IosSpeechModelPaths.stagingDirectory.appendingPathComponent(entry.name)
        if !isValidFile(destination, entry: entry) {
            try removeItemIfExists(destination)
        }
    }

    nonisolated private static func temporaryFile(for entry: IosSpeechModelFileDescriptor) -> URL {
        IosSpeechModelPaths.stagingDirectory.appendingPathComponent("\(entry.name).part")
    }

    nonisolated private static func isValidFile(_ url: URL, entry: IosSpeechModelFileDescriptor) -> Bool {
        guard FileManager.default.isReadableFile(atPath: url.path) else {
            return false
        }
        guard fileSize(url) == entry.sizeBytes else {
            return false
        }
        return sha256(url) == entry.sha256
    }

    nonisolated private static func fileSize(_ url: URL) -> Int64? {
        guard let attributes = try? FileManager.default.attributesOfItem(atPath: url.path),
              let size = attributes[.size] as? NSNumber else {
            return nil
        }
        return size.int64Value
    }

    nonisolated private static func sha256(_ url: URL) -> String? {
        guard let handle = try? FileHandle(forReadingFrom: url) else {
            return nil
        }
        defer {
            try? handle.close()
        }

        var hasher = SHA256()
        while true {
            let data = handle.readData(ofLength: 1024 * 1024)
            if data.isEmpty {
                break
            }
            hasher.update(data: data)
        }

        let digest = hasher.finalize()
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    private struct ModelValidationResult {
        let missingFiles: [String]
    }

    private struct InstallResult {
        let message: String
        let committed: Bool

        init(message: String, committed: Bool = false) {
            self.message = message
            self.committed = committed
        }

        func cleanup() {
            try? IosSpeechModelStore.removeItemIfExists(IosSpeechModelPaths.stagingDirectory)
        }
    }

    private struct PreparationResult {
        let directory: URL?
        let invalidInstallation: Bool
        let message: String?
    }

    private enum ModelDownloadError: LocalizedError {
        case invalidUrl(String)
        case httpStatus(Int)

        var errorDescription: String? {
            switch self {
            case let .invalidUrl(url):
                return "Invalid model URL: \(url)"
            case let .httpStatus(status):
                return "HTTP \(status)"
            }
        }
    }

    private enum ModelPackageError: LocalizedError {
        case missingManifest
        case malformedManifest
        case unsupportedIdentity

        var errorDescription: String? {
            switch self {
            case .missingManifest:
                return "voice-inbox-model.json is missing from the selected folder."
            case .malformedManifest:
                return "voice-inbox-model.json is malformed or contains unsupported fields."
            case .unsupportedIdentity:
                return "This speech model package is not supported on iOS."
            }
        }
    }
}

private extension Comparable {
    func clamped(to limits: ClosedRange<Self>) -> Self {
        min(max(self, limits.lowerBound), limits.upperBound)
    }
}
