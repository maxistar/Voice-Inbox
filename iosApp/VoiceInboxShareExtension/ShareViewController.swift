import UIKit
import UniformTypeIdentifiers

final class ShareViewController: UIViewController {
    private let statusLabel = UILabel()
    private let doneButton = UIButton(type: .system)

    override func viewDidLoad() {
        super.viewDidLoad()
        configureView()
        importSharedItems()
    }

    private func configureView() {
        view.backgroundColor = .systemBackground

        statusLabel.translatesAutoresizingMaskIntoConstraints = false
        statusLabel.numberOfLines = 0
        statusLabel.textAlignment = .center
        statusLabel.text = "Importing audio..."

        doneButton.translatesAutoresizingMaskIntoConstraints = false
        doneButton.setTitle("Done", for: .normal)
        doneButton.addTarget(self, action: #selector(closeExtension), for: .touchUpInside)
        doneButton.isHidden = true

        view.addSubview(statusLabel)
        view.addSubview(doneButton)

        NSLayoutConstraint.activate([
            statusLabel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            statusLabel.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
            statusLabel.centerYAnchor.constraint(equalTo: view.centerYAnchor, constant: -24),

            doneButton.topAnchor.constraint(equalTo: statusLabel.bottomAnchor, constant: 20),
            doneButton.centerXAnchor.constraint(equalTo: view.centerXAnchor),
        ])
    }

    private func importSharedItems() {
        Task {
            let result = await stageSharedItems()
            await MainActor.run {
                statusLabel.text = result.message
                doneButton.isHidden = false
            }
        }
    }

    private func stageSharedItems() async -> ShareImportResult {
        guard let extensionItems = extensionContext?.inputItems as? [NSExtensionItem] else {
            return ShareImportResult(imported: 0, skipped: 0, failed: 1)
        }

        var imported = 0
        var skipped = 0
        var failed = 0

        for item in extensionItems {
            for provider in item.attachments ?? [] {
                do {
                    if try await stageItemProvider(provider) {
                        imported += 1
                    } else {
                        skipped += 1
                    }
                } catch {
                    failed += 1
                }
            }
        }

        return ShareImportResult(imported: imported, skipped: skipped, failed: failed)
    }

    private func stageItemProvider(_ provider: NSItemProvider) async throws -> Bool {
        if provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier),
           let url = try await loadURL(from: provider, typeIdentifier: UTType.fileURL.identifier) {
            return try stageFile(at: url)
        }

        for typeIdentifier in IosSharedImportStaging.supportedTypeIdentifiers {
            if provider.hasItemConformingToTypeIdentifier(typeIdentifier),
               let url = try await loadFileRepresentation(from: provider, typeIdentifier: typeIdentifier) {
                return try stageFile(at: url)
            }
        }

        return false
    }

    private func loadURL(from provider: NSItemProvider, typeIdentifier: String) async throws -> URL? {
        try await withCheckedThrowingContinuation { continuation in
            provider.loadItem(forTypeIdentifier: typeIdentifier, options: nil) { item, error in
                if let error {
                    continuation.resume(throwing: error)
                    return
                }
                if let url = item as? URL {
                    continuation.resume(returning: url)
                    return
                }
                if let data = item as? Data,
                   let url = URL(dataRepresentation: data, relativeTo: nil) {
                    continuation.resume(returning: url)
                    return
                }
                continuation.resume(returning: nil)
            }
        }
    }

    private func loadFileRepresentation(from provider: NSItemProvider, typeIdentifier: String) async throws -> URL? {
        try await withCheckedThrowingContinuation { continuation in
            provider.loadFileRepresentation(forTypeIdentifier: typeIdentifier) { url, error in
                if let error {
                    continuation.resume(throwing: error)
                    return
                }
                continuation.resume(returning: url)
            }
        }
    }

    private func stageFile(at sourceURL: URL) throws -> Bool {
        guard IosSharedImportStaging.isSupportedAudio(sourceURL) else { return false }

        let fileManager = FileManager.default
        let directory = try IosSharedImportStaging.stagingDirectory(fileManager: fileManager)
        let displayName = sourceURL.lastPathComponent
        let stagedFileName = IosSharedImportStaging.uniqueStagedFileName(
            for: displayName,
            in: directory,
            fileManager: fileManager
        )
        let stagedURL = directory.appendingPathComponent(stagedFileName)

        if fileManager.fileExists(atPath: stagedURL.path) {
            try fileManager.removeItem(at: stagedURL)
        }
        try fileManager.copyItem(at: sourceURL, to: stagedURL)

        let metadata = IosSharedImportStaging.Metadata(
            stagedFileName: stagedFileName,
            displayName: displayName,
            originalPathHint: sourceURL.lastPathComponent,
            sizeBytes: IosSharedImportStaging.fileSize(at: stagedURL),
            modifiedMillis: IosSharedImportStaging.fileModifiedMillis(at: sourceURL),
            stagedAtMillis: IosSharedImportStaging.currentTimeMillis()
        )
        try IosSharedImportStaging.writeMetadata(metadata, in: directory, fileManager: fileManager)
        return true
    }

    @objc private func closeExtension() {
        extensionContext?.completeRequest(returningItems: nil)
    }
}

private struct ShareImportResult {
    let imported: Int
    let skipped: Int
    let failed: Int

    var message: String {
        var parts: [String] = []
        if imported > 0 { parts.append("\(imported) staged for Voice Inbox") }
        if skipped > 0 { parts.append("\(skipped) skipped") }
        if failed > 0 { parts.append("\(failed) failed") }
        return parts.isEmpty ? "No supported audio files found." : parts.joined(separator: ", ")
    }
}
