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

struct IosSpeechModelStatus {
    let directory: URL
    let installationState: IosSpeechModelInstallationState
    let missingFiles: [String]

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

    private var downloadTask: Task<Void, Never>?
    private var preparationTask: Task<PreparationResult, Never>?
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
            validateInstallation: { Self.validateModelFiles(in: $0).missingFiles },
            prepareNative: { IosNativeTranscriber.prepare(modelDirectory: $0) },
            nativeError: { IosNativeTranscriber.consumeLastError() },
            recordVerified: { Self.recordVerifiedInstallation() },
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

    func reload() {
        status = inspectInstallation(installationDirectory)
    }

    func installModel(from sourceURL: URL) {
        guard !isBusy else { return }

        isInstalling = true
        downloadProgress = nil
        message = "Installing speech model..."

        Task {
            let result = await Task.detached(priority: .userInitiated) {
                Self.installModelFiles(from: sourceURL)
            }.value

            isInstalling = false
            status = Self.inspectLightweight(directory: IosSpeechModelPaths.modelDirectory)
            runtimeState = .unloaded
            message = result.message
        }
    }

    func downloadModel() {
        guard !isBusy else { return }

        isInstalling = true
        message = "Preparing speech model download..."
        downloadProgress = IosSpeechModelDownloadProgress(
            message: "Preparing speech model download...",
            bytesDownloaded: 0,
            totalBytes: Self.manifestTotalSizeBytes()
        )

        downloadTask = Task {
            let result = await Self.downloadAndInstallModel { [weak self] progress in
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
            runtimeState = .unloaded
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
        let missing = (requiredFileNames ?? manifestFiles().map(\.name)).compactMap { fileName in
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
        let receipt = try? String(contentsOf: receiptFile, encoding: .utf8)
        let state: IosSpeechModelInstallationState = receipt?.trimmingCharacters(in: .whitespacesAndNewlines)
            == EmbeddedSpeechModel.shared.manifest.version
            ? .installedVerified
            : .installedLegacy
        return IosSpeechModelStatus(
            directory: directory,
            installationState: state,
            missingFiles: []
        )
    }

    nonisolated private static func recordVerifiedInstallation() {
        try? FileManager.default.createDirectory(
            at: IosSpeechModelPaths.applicationSupportDirectory,
            withIntermediateDirectories: true
        )
        try? EmbeddedSpeechModel.shared.manifest.version.write(
            to: IosSpeechModelPaths.receiptFile,
            atomically: true,
            encoding: .utf8
        )
        try? removeItemIfExists(IosSpeechModelPaths.invalidFile)
    }

    nonisolated private static func recordInvalidInstallation(_ reason: String) {
        try? reason.write(to: IosSpeechModelPaths.invalidFile, atomically: true, encoding: .utf8)
    }

    nonisolated private static func validateModelFiles(in directory: URL) -> ModelValidationResult {
        let fileManager = FileManager.default
        var isDirectory: ObjCBool = false
        guard fileManager.fileExists(atPath: directory.path, isDirectory: &isDirectory), isDirectory.boolValue else {
            return ModelValidationResult(missingFiles: ["model directory"])
        }

        var validationIssues = [String]()
        for entry in manifestFiles() {
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

    nonisolated private static func installModelFiles(from sourceURL: URL) -> InstallResult {
        let fileManager = FileManager.default
        let accessed = sourceURL.startAccessingSecurityScopedResource()
        defer {
            if accessed {
                sourceURL.stopAccessingSecurityScopedResource()
            }
        }

        let sourceValidation = validateModelFiles(in: sourceURL)
        guard sourceValidation.missingFiles.isEmpty else {
            return InstallResult(message: "Selected folder is not a valid speech model. Missing: \(sourceValidation.missingFiles.joined(separator: ", "))")
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

            try copyRequiredFiles(from: sourceURL, to: IosSpeechModelPaths.installDirectory)

            let installedValidation = validateModelFiles(in: IosSpeechModelPaths.installDirectory)
            guard installedValidation.missingFiles.isEmpty else {
                try removeItemIfExists(IosSpeechModelPaths.installDirectory)
                return InstallResult(message: "Copied model is incomplete. Missing: \(installedValidation.missingFiles.joined(separator: ", "))")
            }

            if fileManager.fileExists(atPath: IosSpeechModelPaths.modelDirectory.path) {
                try fileManager.moveItem(at: IosSpeechModelPaths.modelDirectory, to: IosSpeechModelPaths.backupDirectory)
            }

            do {
                try fileManager.moveItem(at: IosSpeechModelPaths.installDirectory, to: IosSpeechModelPaths.modelDirectory)
                try removeItemIfExists(IosSpeechModelPaths.backupDirectory)
                recordVerifiedInstallation()
                IosNativeTranscriber.resetModel()
                return InstallResult(message: "Speech model installed.")
            } catch {
                if fileManager.fileExists(atPath: IosSpeechModelPaths.backupDirectory.path) {
                    try? fileManager.moveItem(at: IosSpeechModelPaths.backupDirectory, to: IosSpeechModelPaths.modelDirectory)
                }
                throw error
            }
        } catch {
            try? removeItemIfExists(IosSpeechModelPaths.installDirectory)
            return InstallResult(message: "Could not install speech model: \(error.localizedDescription)")
        }
    }

    nonisolated private static func copyRequiredFiles(from sourceURL: URL, to destinationURL: URL) throws {
        for entry in manifestFiles() {
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
        progress: @escaping @Sendable (IosSpeechModelDownloadProgress) -> Void
    ) async -> InstallResult {
        let fileManager = FileManager.default
        let files = manifestFiles()
        let totalBytes = manifestTotalSizeBytes()

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

            let stagedValidation = validateModelFiles(in: IosSpeechModelPaths.stagingDirectory)
            guard stagedValidation.missingFiles.isEmpty else {
                return InstallResult(
                    message: "Downloaded model is incomplete. Missing: \(stagedValidation.missingFiles.joined(separator: ", "))"
                )
            }

            try activateStagedModel()
            recordVerifiedInstallation()
            IosNativeTranscriber.resetModel()
            return InstallResult(message: "Speech model downloaded and installed.")
        } catch is CancellationError {
            try? removeItemIfExists(IosSpeechModelPaths.stagingDirectory)
            return InstallResult(message: "Speech model download cancelled.")
        } catch {
            try? removeItemIfExists(IosSpeechModelPaths.stagingDirectory)
            return InstallResult(message: "Could not download speech model: \(error.localizedDescription)")
        }
    }

    nonisolated private static func removeItemIfExists(_ url: URL) throws {
        if FileManager.default.fileExists(atPath: url.path) {
            try FileManager.default.removeItem(at: url)
        }
    }

    nonisolated private static func downloadFile(
        _ entry: IosSpeechModelManifestFile,
        completedBytes: Int64,
        totalBytes: Int64,
        progress: @escaping @Sendable (IosSpeechModelDownloadProgress) -> Void
    ) async throws {
        guard let url = URL(string: entry.downloadUrl) else {
            throw ModelDownloadError.invalidUrl(entry.downloadUrl)
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

        if fileManager.fileExists(atPath: IosSpeechModelPaths.modelDirectory.path) {
            try fileManager.moveItem(at: IosSpeechModelPaths.modelDirectory, to: IosSpeechModelPaths.backupDirectory)
        }

        do {
            try fileManager.moveItem(at: IosSpeechModelPaths.installDirectory, to: IosSpeechModelPaths.modelDirectory)
            try removeItemIfExists(IosSpeechModelPaths.backupDirectory)
        } catch {
            if fileManager.fileExists(atPath: IosSpeechModelPaths.backupDirectory.path) {
                try? fileManager.moveItem(at: IosSpeechModelPaths.backupDirectory, to: IosSpeechModelPaths.modelDirectory)
            }
            throw error
        }
    }

    nonisolated private static func cleanupPartialFile(for entry: IosSpeechModelManifestFile) throws {
        try removeItemIfExists(temporaryFile(for: entry))
        let destination = IosSpeechModelPaths.stagingDirectory.appendingPathComponent(entry.name)
        if !isValidFile(destination, entry: entry) {
            try removeItemIfExists(destination)
        }
    }

    nonisolated private static func temporaryFile(for entry: IosSpeechModelManifestFile) -> URL {
        IosSpeechModelPaths.stagingDirectory.appendingPathComponent("\(entry.name).part")
    }

    nonisolated private static func isValidFile(_ url: URL, entry: IosSpeechModelManifestFile) -> Bool {
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

    nonisolated private static func manifestTotalSizeBytes() -> Int64 {
        manifestFiles().reduce(0) { $0 + $1.sizeBytes }
    }

    nonisolated private static func manifestFiles() -> [IosSpeechModelManifestFile] {
        let manifest = EmbeddedSpeechModel.shared.manifest
        return manifest.files.map { file in
            return IosSpeechModelManifestFile(
                name: file.name,
                sizeBytes: file.sizeBytes,
                sha256: file.sha256,
                downloadUrl: manifest.downloadUrl(file: file)
            )
        }
    }

    private struct ModelValidationResult {
        let missingFiles: [String]
    }

    private struct InstallResult {
        let message: String

        func cleanup() {
            try? IosSpeechModelStore.removeItemIfExists(IosSpeechModelPaths.stagingDirectory)
        }
    }

    private struct PreparationResult {
        let directory: URL?
        let invalidInstallation: Bool
        let message: String?
    }

    private struct IosSpeechModelManifestFile {
        let name: String
        let sizeBytes: Int64
        let sha256: String
        let downloadUrl: String
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
}

private extension Comparable {
    func clamped(to limits: ClosedRange<Self>) -> Self {
        min(max(self, limits.lowerBound), limits.upperBound)
    }
}
