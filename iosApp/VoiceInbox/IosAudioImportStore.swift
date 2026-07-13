import Foundation
import Shared

enum IosAudioCatalogConstants {
    static let databaseName = "voice-inbox-catalog.db"
    static let importedFolderUri = "ios-imported-audio"
}

enum IosImportedAudioStatus: String, Codable {
    case pending
    case processing
    case processed
    case failed

    init(sharedState: AudioFileState) {
        switch sharedState {
        case .pending:
            self = .pending
        case .processing:
            self = .processing
        case .processed:
            self = .processed
        case .failed:
            self = .failed
        default:
            self = .pending
        }
    }

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
    let importedFileIds: [Int64]

    init(imported: Int, skipped: Int, failed: Int, importedFileIds: [Int64] = []) {
        self.imported = imported
        self.skipped = skipped
        self.failed = failed
        self.importedFileIds = importedFileIds
    }

    var message: String {
        var parts: [String] = []
        if imported > 0 { parts.append("\(imported) imported") }
        if skipped > 0 { parts.append("\(skipped) skipped") }
        if failed > 0 { parts.append("\(failed) failed") }
        return parts.isEmpty ? "No files imported" : parts.joined(separator: ", ")
    }
}

struct IosInboxFolderStatus {
    let displayName: String?
    let message: String?
    let needsSelection: Bool

    var title: String {
        displayName ?? "No audio folder selected"
    }
}

@MainActor
final class IosAudioImportStore: ObservableObject {
    @Published private(set) var files: [IosImportedAudioFile] = []
    @Published private(set) var inboxFolderStatus = IosInboxFolderStatus(
        displayName: nil,
        message: "Choose a folder to use it as your audio inbox.",
        needsSelection: true
    )
    @Published private(set) var isScanningFolder = false
    @Published var importMessage: String?

    private let fileManager: FileManager
    private let catalog: SqlDelightAudioCatalogRepository
    private let userDefaults: UserDefaults
    private let supportedExtensions: Set<String> = ["aac", "aiff", "aif", "caf", "flac", "m4a", "mp3", "mp4", "wav"]

    init(fileManager: FileManager = .default, userDefaults: UserDefaults = .standard) {
        self.fileManager = fileManager
        self.userDefaults = userDefaults
        self.catalog = IosSqlDelightAudioCatalogFactory().create(
            databaseName: IosAudioCatalogConstants.databaseName
        )
        ensureImportDirectoryExists()
        migrateLegacyCatalogIfNeeded()
        restoreInboxFolderStatus()
        load()
    }

    func importFiles(from urls: [URL]) {
        let summary = copySupportedFiles(from: urls)
        load()
        importMessage = summary.message
    }

    @discardableResult
    func ingestSharedImports() -> IosAudioImportSummary? {
        guard let directory = try? IosSharedImportStaging.stagingDirectory(fileManager: fileManager) else { return nil }
        guard let urls = try? fileManager.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey],
            options: [.skipsHiddenFiles]
        ) else {
            return nil
        }

        let stagedFiles = urls.filter { $0.pathExtension.lowercased() != IosSharedImportStaging.manifestExtension }
        guard !stagedFiles.isEmpty else { return nil }

        let summary = copySupportedSharedFiles(from: stagedFiles)
        load()
        importMessage = summary.message
        return summary
    }

    func selectInboxFolder(_ url: URL) {
        let didStartAccessing = url.startAccessingSecurityScopedResource()
        defer {
            if didStartAccessing {
                url.stopAccessingSecurityScopedResource()
            }
        }

        do {
            let bookmark = try url.bookmarkData(
                options: [],
                includingResourceValuesForKeys: nil,
                relativeTo: nil
            )
            userDefaults.set(bookmark, forKey: Self.inboxFolderBookmarkKey)
            userDefaults.set(url.lastPathComponent, forKey: Self.inboxFolderDisplayNameKey)
            inboxFolderStatus = IosInboxFolderStatus(
                displayName: url.lastPathComponent,
                message: "Audio folder selected. Refresh to scan for notes.",
                needsSelection: false
            )
            refreshInboxFolder()
        } catch {
            inboxFolderStatus = IosInboxFolderStatus(
                displayName: url.lastPathComponent,
                message: "Could not save access to this folder. Choose it again.",
                needsSelection: true
            )
        }
    }

    func refreshInboxFolder() {
        guard !isScanningFolder else { return }
        guard let folder = resolveInboxFolder() else { return }

        isScanningFolder = true
        let summary = scanInboxFolder(folder.url, folderIdentity: stableFolderIdentity(for: folder.url))
        load()
        isScanningFolder = false
        inboxFolderStatus = IosInboxFolderStatus(
            displayName: folder.url.lastPathComponent,
            message: summary.message,
            needsSelection: false
        )
        importMessage = summary.message
    }

    func localURL(for file: IosImportedAudioFile) -> URL {
        importDirectory.appendingPathComponent(file.localFileName)
    }

    func localURL(documentUri: String) -> URL {
        importDirectory.appendingPathComponent(documentUri)
    }

    var pendingCount: Int {
        files.count { $0.status == .pending }
    }

    func refresh() {
        load()
    }

    func markProcessing(fileId: Int64) {
        _ = catalog.markProcessing(id: fileId)
        load()
    }

    func markProcessed(fileId: Int64, transcriptText: String, durationUs: Int64?) {
        catalog.markProcessedFile(
            id: fileId,
            processedAtMillis: currentTimeMillis(),
            transcriptText: transcriptText,
            durationUs: durationUs.map { KotlinLong(longLong: $0) }
        )
        load()
    }

    func markFailed(fileId: Int64, error: String) {
        catalog.markFailed(id: fileId, message: error)
        load()
    }

    func resetToPending(fileId: Int64) {
        _ = catalog.resetForRetry(id: fileId)
        load()
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

    private func restoreInboxFolderStatus() {
        guard userDefaults.data(forKey: Self.inboxFolderBookmarkKey) != nil else { return }
        let displayName = userDefaults.string(forKey: Self.inboxFolderDisplayNameKey)
        if resolveInboxFolder(updateStatusOnFailure: false) != nil {
            inboxFolderStatus = IosInboxFolderStatus(
                displayName: displayName,
                message: "Audio folder is ready to refresh.",
                needsSelection: false
            )
        } else {
            inboxFolderStatus = IosInboxFolderStatus(
                displayName: displayName,
                message: "Folder access expired. Choose the folder again.",
                needsSelection: true
            )
        }
    }

    private func resolveInboxFolder(updateStatusOnFailure: Bool = true) -> (url: URL, stale: Bool)? {
        guard let bookmark = userDefaults.data(forKey: Self.inboxFolderBookmarkKey) else {
            if updateStatusOnFailure {
                inboxFolderStatus = IosInboxFolderStatus(
                    displayName: nil,
                    message: "Choose a folder to use it as your audio inbox.",
                    needsSelection: true
                )
            }
            return nil
        }

        do {
            var stale = false
            let url = try URL(
                resolvingBookmarkData: bookmark,
                options: [],
                relativeTo: nil,
                bookmarkDataIsStale: &stale
            )
            if stale, updateStatusOnFailure {
                inboxFolderStatus = IosInboxFolderStatus(
                    displayName: userDefaults.string(forKey: Self.inboxFolderDisplayNameKey),
                    message: "Folder access needs to be refreshed. Choose the folder again.",
                    needsSelection: true
                )
            }
            return (url, stale)
        } catch {
            if updateStatusOnFailure {
                inboxFolderStatus = IosInboxFolderStatus(
                    displayName: userDefaults.string(forKey: Self.inboxFolderDisplayNameKey),
                    message: "Could not access the selected folder. Choose it again.",
                    needsSelection: true
                )
            }
            return nil
        }
    }

    private func scanInboxFolder(_ folderURL: URL, folderIdentity: String) -> IosAudioImportSummary {
        let didStartAccessing = folderURL.startAccessingSecurityScopedResource()
        defer {
            if didStartAccessing {
                folderURL.stopAccessingSecurityScopedResource()
            }
        }

        do {
            let urls = try fileManager.contentsOfDirectory(
                at: folderURL,
                includingPropertiesForKeys: [.isDirectoryKey, .fileSizeKey, .contentModificationDateKey],
                options: [.skipsHiddenFiles]
            )
            return copySupportedFolderFiles(from: urls, folderIdentity: folderIdentity)
        } catch {
            return IosAudioImportSummary(imported: 0, skipped: 0, failed: 1)
        }
    }

    private func copySupportedFolderFiles(from urls: [URL], folderIdentity: String) -> IosAudioImportSummary {
        ensureImportDirectoryExists()
        var imported = 0
        var skipped = 0
        var failed = 0
        var importedFileIds: [Int64] = []
        let existingByLocalName = Dictionary(uniqueKeysWithValues: files.map { ($0.localFileName, $0) })

        for sourceURL in urls {
            guard isSupportedAudio(sourceURL) else {
                skipped += 1
                continue
            }
            guard isDirectFile(sourceURL) else {
                skipped += 1
                continue
            }

            let targetFileName = folderLocalFileName(for: sourceURL, folderIdentity: folderIdentity)
            let targetURL = importDirectory.appendingPathComponent(targetFileName)
            let sizeBytes = fileSize(at: sourceURL)
            let modifiedMillis = fileModifiedMillis(at: sourceURL) ?? currentTimeMillis()

            if let existing = existingByLocalName[targetFileName],
               existing.sizeBytes == sizeBytes,
               isSameMillis(Int64(existing.importedAt.timeIntervalSince1970 * 1000), modifiedMillis) {
                skipped += 1
                continue
            }

            do {
                if fileManager.fileExists(atPath: targetURL.path) {
                    try fileManager.removeItem(at: targetURL)
                }
                try fileManager.copyItem(at: sourceURL, to: targetURL)
                let importedFile = catalog.upsertImportedFile(
                    folderUri: IosAudioCatalogConstants.importedFolderUri,
                    documentUri: targetFileName,
                    displayName: sourceURL.lastPathComponent,
                    mimeType: nil,
                    sizeBytes: KotlinLong(longLong: fileSize(at: targetURL)),
                    importedAtMillis: KotlinLong(longLong: modifiedMillis),
                    state: AudioFileState.pending,
                    lastError: nil,
                    processedAtMillis: nil,
                    transcriptText: nil,
                    durationUs: nil
                )
                importedFileIds.append(importedFile.id)
                imported += 1
            } catch {
                failed += 1
            }
        }

        return IosAudioImportSummary(
            imported: imported,
            skipped: skipped,
            failed: failed,
            importedFileIds: importedFileIds
        )
    }

    private func copySupportedFiles(from urls: [URL]) -> IosAudioImportSummary {
        ensureImportDirectoryExists()
        var imported = 0
        var skipped = 0
        var failed = 0
        var importedFileIds: [Int64] = []

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
                let importedFile = catalog.upsertImportedFile(
                    folderUri: IosAudioCatalogConstants.importedFolderUri,
                    documentUri: targetFileName,
                    displayName: sourceURL.lastPathComponent,
                    mimeType: nil,
                    sizeBytes: KotlinLong(longLong: fileSize(at: targetURL)),
                    importedAtMillis: KotlinLong(longLong: currentTimeMillis()),
                    state: AudioFileState.pending,
                    lastError: nil,
                    processedAtMillis: nil,
                    transcriptText: nil,
                    durationUs: nil
                )
                importedFileIds.append(importedFile.id)
                imported += 1
            } catch {
                failed += 1
            }
        }

        return IosAudioImportSummary(
            imported: imported,
            skipped: skipped,
            failed: failed,
            importedFileIds: importedFileIds
        )
    }

    private func copySupportedSharedFiles(from urls: [URL]) -> IosAudioImportSummary {
        ensureImportDirectoryExists()
        var imported = 0
        var skipped = 0
        var failed = 0
        var importedFileIds: [Int64] = []

        for stagedURL in urls {
            guard isSupportedAudio(stagedURL) else {
                IosSharedImportStaging.removeStagedFileAndMetadata(stagedURL, fileManager: fileManager)
                skipped += 1
                continue
            }

            let metadata = IosSharedImportStaging.readMetadata(for: stagedURL)
            let displayName = metadata?.displayName ?? stagedURL.lastPathComponent
            let importedAtMillis = metadata?.modifiedMillis ?? metadata?.stagedAtMillis ?? currentTimeMillis()

            do {
                let targetFileName = nextAvailableFileName(for: displayName)
                let targetURL = importDirectory.appendingPathComponent(targetFileName)
                try fileManager.copyItem(at: stagedURL, to: targetURL)
                let importedFile = catalog.upsertImportedFile(
                    folderUri: IosAudioCatalogConstants.importedFolderUri,
                    documentUri: targetFileName,
                    displayName: displayName,
                    mimeType: nil,
                    sizeBytes: KotlinLong(longLong: fileSize(at: targetURL)),
                    importedAtMillis: KotlinLong(longLong: importedAtMillis),
                    state: AudioFileState.pending,
                    lastError: nil,
                    processedAtMillis: nil,
                    transcriptText: nil,
                    durationUs: nil
                )
                IosSharedImportStaging.removeStagedFileAndMetadata(stagedURL, fileManager: fileManager)
                importedFileIds.append(importedFile.id)
                imported += 1
            } catch {
                failed += 1
            }
        }

        return IosAudioImportSummary(
            imported: imported,
            skipped: skipped,
            failed: failed,
            importedFileIds: importedFileIds
        )
    }

    private func load() {
        files = catalog.importedFiles(folderUri: IosAudioCatalogConstants.importedFolderUri).map(Self.importedFile)
        sortFiles()
    }

    private func migrateLegacyCatalogIfNeeded() {
        guard catalog.importedFiles(folderUri: IosAudioCatalogConstants.importedFolderUri).isEmpty else { return }
        guard let data = try? Data(contentsOf: metadataURL) else { return }
        guard let legacyFiles = try? JSONDecoder().decode([IosImportedAudioFile].self, from: data) else { return }

        for file in legacyFiles {
            _ = catalog.upsertImportedFile(
                folderUri: IosAudioCatalogConstants.importedFolderUri,
                documentUri: file.localFileName,
                displayName: file.displayName,
                mimeType: nil,
                sizeBytes: KotlinLong(longLong: file.sizeBytes),
                importedAtMillis: KotlinLong(longLong: Int64(file.importedAt.timeIntervalSince1970 * 1000)),
                state: file.status.sharedState,
                lastError: file.lastError,
                processedAtMillis: file.status == .processed
                    ? KotlinLong(longLong: Int64(file.importedAt.timeIntervalSince1970 * 1000))
                    : nil,
                transcriptText: file.transcriptText,
                durationUs: file.durationUs.map { KotlinLong(longLong: $0) }
            )
        }
    }

    private static func importedFile(_ file: SqlDelightAudioCatalogFile) -> IosImportedAudioFile {
        IosImportedAudioFile(
            id: file.id,
            displayName: file.displayName,
            localFileName: file.documentUri,
            sizeBytes: file.sizeBytes?.int64Value ?? 0,
            importedAt: Date(timeIntervalSince1970: Double(file.modifiedMillis?.int64Value ?? 0) / 1000),
            status: IosImportedAudioStatus(sharedState: file.state),
            transcriptText: file.transcriptText,
            durationUs: file.durationUs?.int64Value,
            lastError: file.lastError
        )
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

    private func isDirectFile(_ url: URL) -> Bool {
        let values = try? url.resourceValues(forKeys: [.isDirectoryKey])
        return values?.isDirectory != true
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

    private func folderLocalFileName(for url: URL, folderIdentity: String) -> String {
        let sanitized = sanitizedFileName(url.lastPathComponent)
        return "\(Self.folderImportPrefix)\(folderIdentity)-\(sanitized)"
    }

    private func stableFolderIdentity(for url: URL) -> String {
        stableHash(url.standardizedFileURL.path)
    }

    private func stableHash(_ value: String) -> String {
        var hash: UInt64 = 5381
        for byte in value.utf8 {
            hash = ((hash << 5) &+ hash) &+ UInt64(byte)
        }
        return String(hash, radix: 16)
    }

    private func isSameMillis(_ lhs: Int64, _ rhs: Int64) -> Bool {
        abs(lhs - rhs) <= 1
    }

    private func fileSize(at url: URL) -> Int64 {
        let values = try? url.resourceValues(forKeys: [.fileSizeKey])
        return Int64(values?.fileSize ?? 0)
    }

    private func fileModifiedMillis(at url: URL) -> Int64? {
        let values = try? url.resourceValues(forKeys: [.contentModificationDateKey])
        return values?.contentModificationDate.map { Int64($0.timeIntervalSince1970 * 1000) }
    }

    private func currentTimeMillis() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }

    private static let inboxFolderBookmarkKey = "iosInboxFolderBookmark"
    private static let inboxFolderDisplayNameKey = "iosInboxFolderDisplayName"
    private static let folderImportPrefix = "folder-"
}
