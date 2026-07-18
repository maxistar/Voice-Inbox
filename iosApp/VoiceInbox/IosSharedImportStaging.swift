import Foundation
import UniformTypeIdentifiers

enum IosSharedImportStaging {
    static let appGroupIdentifier = "group.me.maxistar.voiceinbox.ios"
    static let directoryName = "SharedImports"
    static let manifestExtension = "json"

    static let supportedExtensions: Set<String> = [
        "aac", "aif", "aiff", "caf", "flac", "m4a", "mp3", "mp4", "wav"
    ]

    static let supportedTypeIdentifiers: [String] = [
        UTType.audio.identifier,
        UTType.movie.identifier,
        UTType.mpeg4Audio.identifier,
        "com.microsoft.waveform-audio",
        "public.mp3",
        "public.aiff-audio",
        "org.xiph.flac",
    ]

    struct Metadata: Codable {
        let stagedFileName: String
        let displayName: String
        let originalPathHint: String?
        let sizeBytes: Int64
        let modifiedMillis: Int64?
        let stagedAtMillis: Int64
    }

    static func stagingDirectory(fileManager: FileManager = .default) throws -> URL {
        guard let container = fileManager.containerURL(forSecurityApplicationGroupIdentifier: appGroupIdentifier) else {
            throw StagingError.appGroupUnavailable
        }
        let directory = container.appendingPathComponent(directoryName, isDirectory: true)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    static func isSupportedAudio(_ url: URL) -> Bool {
        supportedExtensions.contains(url.pathExtension.lowercased())
    }

    static func uniqueStagedFileName(for displayName: String, in directory: URL, fileManager: FileManager = .default) -> String {
        let sanitized = sanitizedFileName(displayName)
        let base = (sanitized as NSString).deletingPathExtension
        let ext = (sanitized as NSString).pathExtension
        var candidate = sanitized
        var suffix = 2

        while fileManager.fileExists(atPath: directory.appendingPathComponent(candidate).path) ||
            fileManager.fileExists(atPath: directory.appendingPathComponent(manifestFileName(for: candidate)).path) {
            candidate = ext.isEmpty ? "\(base)-\(suffix)" : "\(base)-\(suffix).\(ext)"
            suffix += 1
        }

        return candidate
    }

    static func manifestFileName(for stagedFileName: String) -> String {
        "\(stagedFileName).\(manifestExtension)"
    }

    static func writeMetadata(_ metadata: Metadata, in directory: URL, fileManager: FileManager = .default) throws {
        let data = try JSONEncoder().encode(metadata)
        let metadataURL = directory.appendingPathComponent(manifestFileName(for: metadata.stagedFileName))
        try data.write(to: metadataURL, options: [.atomic])
    }

    static func readMetadata(for stagedFileURL: URL) -> Metadata? {
        let metadataURL = stagedFileURL
            .deletingLastPathComponent()
            .appendingPathComponent(manifestFileName(for: stagedFileURL.lastPathComponent))
        guard let data = try? Data(contentsOf: metadataURL) else { return nil }
        return try? JSONDecoder().decode(Metadata.self, from: data)
    }

    static func removeStagedFileAndMetadata(_ stagedFileURL: URL, fileManager: FileManager = .default) {
        try? fileManager.removeItem(at: stagedFileURL)
        let metadataURL = stagedFileURL
            .deletingLastPathComponent()
            .appendingPathComponent(manifestFileName(for: stagedFileURL.lastPathComponent))
        try? fileManager.removeItem(at: metadataURL)
    }

    static func fileSize(at url: URL) -> Int64 {
        let values = try? url.resourceValues(forKeys: [.fileSizeKey])
        return Int64(values?.fileSize ?? 0)
    }

    static func fileModifiedMillis(at url: URL) -> Int64? {
        let values = try? url.resourceValues(forKeys: [.contentModificationDateKey])
        return values?.contentModificationDate.map { Int64($0.timeIntervalSince1970 * 1000) }
    }

    static func currentTimeMillis() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }

    static func sanitizedFileName(_ name: String) -> String {
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "._- "))
        let scalars = name.unicodeScalars.map { scalar in
            allowed.contains(scalar) ? Character(scalar) : "-"
        }
        let sanitized = String(scalars).trimmingCharacters(in: .whitespacesAndNewlines)
        return sanitized.isEmpty ? "audio-file" : sanitized
    }

    enum StagingError: LocalizedError {
        case appGroupUnavailable

        var errorDescription: String? {
            switch self {
            case .appGroupUnavailable:
                "Shared import storage is not available. Check the App Group entitlement."
            }
        }
    }
}
