import Combine
import Foundation

struct IosSpeechModelStatus {
    let directory: URL
    let isReady: Bool
    let missingFiles: [String]

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

    static var backupDirectory: URL {
        applicationSupportDirectory.appendingPathComponent("SpeechModel.previous", isDirectory: true)
    }
}

@MainActor
final class IosSpeechModelStore: ObservableObject {
    @Published private(set) var status: IosSpeechModelStatus
    @Published private(set) var isInstalling = false
    @Published var message: String?

    init() {
        let directory = IosSpeechModelPaths.modelDirectory
        status = Self.validate(directory: directory)
    }

    var isReady: Bool {
        status.isReady
    }

    var modelDirectory: URL {
        status.directory
    }

    func reload() {
        status = Self.validate(directory: IosSpeechModelPaths.modelDirectory)
    }

    func installModel(from sourceURL: URL) {
        guard !isInstalling else { return }

        isInstalling = true
        message = "Installing speech model..."

        Task {
            let result = await Task.detached(priority: .userInitiated) {
                Self.installModelFiles(from: sourceURL)
            }.value

            isInstalling = false
            status = Self.validate(directory: IosSpeechModelPaths.modelDirectory)
            message = result.message
        }
    }

    nonisolated private static func validate(directory: URL) -> IosSpeechModelStatus {
        let result = validateModelFiles(in: directory)
        return IosSpeechModelStatus(
            directory: directory,
            isReady: result.missingFiles.isEmpty,
            missingFiles: result.missingFiles
        )
    }

    nonisolated private static func validateModelFiles(in directory: URL) -> ModelValidationResult {
        let fileManager = FileManager.default
        var isDirectory: ObjCBool = false
        guard fileManager.fileExists(atPath: directory.path, isDirectory: &isDirectory), isDirectory.boolValue else {
            return ModelValidationResult(missingFiles: ["model directory"])
        }

        var missingFiles = [String]()
        for fileName in ["nemo128.onnx", "vocab.txt"] where !fileManager.isReadableFile(atPath: directory.appendingPathComponent(fileName).path) {
            missingFiles.append(fileName)
        }

        let encoderNames = ["encoder-model.int8.onnx", "encoder-model.onnx"]
        if firstReadableFile(named: encoderNames, in: directory) == nil {
            missingFiles.append("encoder-model.int8.onnx or encoder-model.onnx")
        }

        let decoderNames = ["decoder_joint-model.int8.onnx", "decoder_joint-model.onnx"]
        if firstReadableFile(named: decoderNames, in: directory) == nil {
            missingFiles.append("decoder_joint-model.int8.onnx or decoder_joint-model.onnx")
        }

        return ModelValidationResult(missingFiles: missingFiles)
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
        for fileName in ["nemo128.onnx", "vocab.txt"] {
            try copyFile(named: fileName, from: sourceURL, to: destinationURL)
        }

        if let encoderName = firstReadableFile(named: ["encoder-model.int8.onnx", "encoder-model.onnx"], in: sourceURL) {
            try copyFile(named: encoderName, from: sourceURL, to: destinationURL)
        }

        if let decoderName = firstReadableFile(named: ["decoder_joint-model.int8.onnx", "decoder_joint-model.onnx"], in: sourceURL) {
            try copyFile(named: decoderName, from: sourceURL, to: destinationURL)
        }
    }

    nonisolated private static func copyFile(named fileName: String, from sourceURL: URL, to destinationURL: URL) throws {
        try FileManager.default.copyItem(
            at: sourceURL.appendingPathComponent(fileName),
            to: destinationURL.appendingPathComponent(fileName)
        )
    }

    nonisolated private static func firstReadableFile(named fileNames: [String], in directory: URL) -> String? {
        fileNames.first { fileName in
            FileManager.default.isReadableFile(atPath: directory.appendingPathComponent(fileName).path)
        }
    }

    nonisolated private static func removeItemIfExists(_ url: URL) throws {
        if FileManager.default.fileExists(atPath: url.path) {
            try FileManager.default.removeItem(at: url)
        }
    }

    private struct ModelValidationResult {
        let missingFiles: [String]
    }

    private struct InstallResult {
        let message: String
    }
}
