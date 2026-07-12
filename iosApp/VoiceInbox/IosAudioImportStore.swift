import Foundation
import Shared

enum IosImportedAudioStatus: String, Codable {
    case pending
    case processing
    case processed
    case failed

    var sharedState: AudioFileState {
        switch self {
        case .pending:
            AudioFileState.pending
        case .processing:
            AudioFileState.processing
        case .processed:
            AudioFileState.processed
        case .failed:
            AudioFileState.failed
        }
    }

    var badge: String {
        switch self {
        case .pending:
            "New"
        case .processing:
            "Processing"
        case .processed:
            "Processed"
        case .failed:
            "Failed"
        }
    }
}

struct IosImportedAudioFile: Codable, Identifiable, Equatable {
    let id: Int64
    let displayName: String
    let localFileName: String
    let sizeBytes: Int64
    let importedAt: Date
    var status: IosImportedAudioStatus
    var transcriptText: String?
    var durationUs: Int64?
    var lastError: String?

    init(
        id: Int64,
        displayName: String,
        localFileName: String,
        sizeBytes: Int64,
        importedAt: Date,
        status: IosImportedAudioStatus = .pending,
        transcriptText: String? = nil,
        durationUs: Int64? = nil,
        lastError: String? = nil
    ) {
        self.id = id
        self.displayName = displayName
        self.localFileName = localFileName
        self.sizeBytes = sizeBytes
        self.importedAt = importedAt
        self.status = status
        self.transcriptText = transcriptText
        self.durationUs = durationUs
        self.lastError = lastError
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(Int64.self, forKey: .id)
        displayName = try container.decode(String.self, forKey: .displayName)
        localFileName = try container.decode(String.self, forKey: .localFileName)
        sizeBytes = try container.decode(Int64.self, forKey: .sizeBytes)
        importedAt = try container.decode(Date.self, forKey: .importedAt)
        status = try container.decodeIfPresent(IosImportedAudioStatus.self, forKey: .status) ?? .pending
        transcriptText = try container.decodeIfPresent(String.self, forKey: .transcriptText)
        durationUs = try container.decodeIfPresent(Int64.self, forKey: .durationUs)
        lastError = try container.decodeIfPresent(String.self, forKey: .lastError)
    }

    var formattedSize: String {
        let formatter = ByteCountFormatter()
        formatter.allowedUnits = [.useKB, .useMB, .useGB]
        formatter.countStyle = .file
        return formatter.string(fromByteCount: sizeBytes)
    }
}

struct IosAudioImportSummary {
    let imported: Int
    let skipped: Int
    let failed: Int

    var message: String {
        var parts: [String] = []
        if imported > 0 { parts.append("\(imported) imported") }
        if skipped > 0 { parts.append("\(skipped) skipped") }
        if failed > 0 { parts.append("\(failed) failed") }
        return parts.isEmpty ? "No files imported" : parts.joined(separator: ", ")
    }
}

@MainActor
final class IosAudioImportStore: ObservableObject {
    @Published private(set) var files: [IosImportedAudioFile] = []
    @Published var importMessage: String?

    private let fileManager: FileManager
    private let supportedExtensions: Set<String> = ["aac", "aiff", "aif", "caf", "flac", "m4a", "mp3", "mp4", "wav"]

    init(fileManager: FileManager = .default) {
        self.fileManager = fileManager
        load()
    }

    func importFiles(from urls: [URL]) {
        let summary = copySupportedFiles(from: urls)
        save()
        importMessage = summary.message
    }

    func localURL(for file: IosImportedAudioFile) -> URL {
        importDirectory.appendingPathComponent(file.localFileName)
    }

    func markProcessing(fileId: Int64) {
        update(fileId: fileId) { file in
            file.status = .processing
            file.lastError = nil
        }
    }

    func markProcessed(fileId: Int64, transcriptText: String, durationUs: Int64?) {
        update(fileId: fileId) { file in
            file.status = .processed
            file.transcriptText = transcriptText
            file.durationUs = durationUs
            file.lastError = nil
        }
    }

    func markFailed(fileId: Int64, error: String) {
        update(fileId: fileId) { file in
            file.status = .failed
            file.lastError = error
        }
    }

    func resetToPending(fileId: Int64) {
        update(fileId: fileId) { file in
            file.status = .pending
            file.lastError = nil
        }
    }

    private var importDirectory: URL {
        documentsDirectory.appendingPathComponent("ImportedAudio", isDirectory: true)
    }

    private var metadataURL: URL {
        importDirectory.appendingPathComponent("catalog.json")
    }

    private var documentsDirectory: URL {
        fileManager.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    private func copySupportedFiles(from urls: [URL]) -> IosAudioImportSummary {
        ensureImportDirectoryExists()
        var imported = 0
        var skipped = 0
        var failed = 0

        for sourceURL in urls {
            guard isSupportedAudio(sourceURL) else {
                skipped += 1
                continue
            }

            let didStartAccessing = sourceURL.startAccessingSecurityScopedResource()
            defer {
                if didStartAccessing {
                    sourceURL.stopAccessingSecurityScopedResource()
                }
            }

            do {
                let targetFileName = nextAvailableFileName(for: sourceURL.lastPathComponent)
                let targetURL = importDirectory.appendingPathComponent(targetFileName)
                try fileManager.copyItem(at: sourceURL, to: targetURL)
                files.append(
                    IosImportedAudioFile(
                        id: stableId(for: targetFileName),
                        displayName: sourceURL.lastPathComponent,
                        localFileName: targetFileName,
                        sizeBytes: fileSize(at: targetURL),
                        importedAt: Date()
                    )
                )
                imported += 1
            } catch {
                failed += 1
            }
        }

        sortFiles()

        return IosAudioImportSummary(imported: imported, skipped: skipped, failed: failed)
    }

    private func load() {
        ensureImportDirectoryExists()
        guard let data = try? Data(contentsOf: metadataURL) else {
            files = []
            return
        }
        files = (try? JSONDecoder().decode([IosImportedAudioFile].self, from: data)) ?? []
        sortFiles()
    }

    private func save() {
        ensureImportDirectoryExists()
        guard let data = try? JSONEncoder().encode(files) else { return }
        try? data.write(to: metadataURL, options: [.atomic])
    }

    private func update(fileId: Int64, mutate: (inout IosImportedAudioFile) -> Void) {
        guard let index = files.firstIndex(where: { $0.id == fileId }) else { return }
        mutate(&files[index])
        sortFiles()
        save()
    }

    private func sortFiles() {
        files.sort { lhs, rhs in
            if lhs.importedAt != rhs.importedAt {
                return lhs.importedAt > rhs.importedAt
            }
            return lhs.displayName.localizedCaseInsensitiveCompare(rhs.displayName) == .orderedAscending
        }
    }

    private func ensureImportDirectoryExists() {
        try? fileManager.createDirectory(at: importDirectory, withIntermediateDirectories: true)
    }

    private func isSupportedAudio(_ url: URL) -> Bool {
        supportedExtensions.contains(url.pathExtension.lowercased())
    }

    private func nextAvailableFileName(for originalName: String) -> String {
        let sanitized = sanitizedFileName(originalName)
        let base = (sanitized as NSString).deletingPathExtension
        let ext = (sanitized as NSString).pathExtension
        var candidate = sanitized
        var suffix = 2

        while fileManager.fileExists(atPath: importDirectory.appendingPathComponent(candidate).path) {
            candidate = ext.isEmpty ? "\(base)-\(suffix)" : "\(base)-\(suffix).\(ext)"
            suffix += 1
        }

        return candidate
    }

    private func sanitizedFileName(_ name: String) -> String {
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "._- "))
        let scalars = name.unicodeScalars.map { scalar in
            allowed.contains(scalar) ? Character(scalar) : "-"
        }
        let sanitized = String(scalars).trimmingCharacters(in: .whitespacesAndNewlines)
        return sanitized.isEmpty ? "audio-file" : sanitized
    }

    private func fileSize(at url: URL) -> Int64 {
        let values = try? url.resourceValues(forKeys: [.fileSizeKey])
        return Int64(values?.fileSize ?? 0)
    }

    private func stableId(for value: String) -> Int64 {
        var hash: UInt64 = 14_695_981_039_346_656_037
        for byte in value.utf8 {
            hash ^= UInt64(byte)
            hash &*= 1_099_511_628_211
        }
        return Int64(bitPattern: hash)
    }
}
